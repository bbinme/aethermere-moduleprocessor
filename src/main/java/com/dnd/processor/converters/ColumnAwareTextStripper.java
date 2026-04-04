package com.dnd.processor.converters;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Column-aware PDF text extractor using page-level zone detection.
 *
 * Strategy:
 *   1. Capture per-character x/y positions for the page.
 *   2. Build a full-page x-density histogram and find column gaps.
 *   3. Classify the page as SINGLE, TWO_COL, or THREE_COL.
 *   4. Extract each column independently via PDFTextStripperByArea and concatenate.
 *
 * Sidebar extraction (--phase sidebars) is handled separately via
 * extractPageSidebar / findSidebarYBounds / findRightSidebarYBounds and does not
 * affect the main column text output.
 */
public class ColumnAwareTextStripper {

    // Column gap: scan the center 60% of the page (20%–80%)
    private static final float COL_SCAN_START = 0.20f;
    private static final float COL_SCAN_END   = 0.80f;

    // Minimum gap width for the main column gutter (Stage 1).
    // Must exceed typical inter-word spaces (~3-6 pt) while being ≤ the actual
    // column gutter width (~13-50 pt in D&D modules).
    // 12 was chosen to catch DL1 page 12's second gutter (13 px of near-zero density)
    // while remaining safely above inter-word spaces.
    private static final int MAIN_GAP_MIN_WIDTH = 12;

    // Minimum gap width for sidebar sub-gutters (Stage 2).
    // Sidebar gutters are narrower than main column gutters (~10-15 pt), so a
    // smaller threshold is needed here.
    private static final int SIDEBAR_GAP_MIN_WIDTH = 10;

    // A region is considered a sidebar if it is narrower than this fraction of the page
    private static final float SIDEBAR_MAX_WIDTH_RATIO = 0.35f;

    // Right-side content must reach this far for a TWO_COL split to be valid
    private static final float RIGHT_REACH_MIN = 0.78f;

    // For document-level two-column hint probe
    private static final int DOC_HINT_GAP_MIN_WIDTH = 10;

    private static final Pattern HYPHEN_BREAK     = Pattern.compile("(\\w+)-\\n(\\w+)");
    private static final Pattern PAGE_NUMBER_LINE = Pattern.compile("(?m)^\\s*\\d{1,3}\\s*$");
    private static final Pattern TRIPLE_BLANK     = Pattern.compile("(\n\\s*){3,}");

    /** A single captured character with its on-page position. */
    public record TextRun(String text, float x, float y, float height, boolean italic) {}

    private enum BandLayout { SINGLE, TWO_COL, THREE_COL }

    /**
     * A contiguous y-range of the page with a consistent detected column layout.
     *
     * @param yStart       top y-coordinate (PDF user space, 0 = top of page)
     * @param yEnd         bottom y-coordinate
     * @param layout       SINGLE, TWO_COL, or THREE_COL
     * @param gap1         x-position of the first column gap (between col1 and col2).
     *                     -1 for SINGLE.
     * @param gap2         For TWO_COL: x-position of the right column's end
     *                     (= sidebar boundary when sidebar detected, else = pageWidth).
     *                     For THREE_COL: x-position of the second column gap (between col2 and col3).
     *                     -1 for SINGLE.
     * @param leftSidebarW width of a sidebar in the LEFT column (0 = none, TWO_COL only).
     *                     When > 0, the left column text begins at x=leftSidebarW.
     */
    private record Zone(float yStart, float yEnd, BandLayout layout,
                        float gap1, float gap2, float leftSidebarW) {}

    // -------------------------------------------------------------------------
    // Inner stripper: per-character position capture
    // -------------------------------------------------------------------------

    private static class PositionCapturingStripper extends PDFTextStripper {

        private final List<TextRun>      runs         = new ArrayList<>();
        private final List<Rectangle2D>  boxes        = new ArrayList<>();
        private final List<float[]>      pendingRects = new ArrayList<>();
        private final List<Rectangle2D>  borderPieces = new ArrayList<>();

        PositionCapturingStripper() throws IOException {
            super();
            setSortByPosition(true);
        }

        List<TextRun>     getRuns()  { return runs; }
        List<Rectangle2D> getBoxes() { return boxes; }

        @Override
        protected void startPage(PDPage page) throws IOException {
            runs.clear();
            boxes.clear();
            pendingRects.clear();
            borderPieces.clear();
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (textPositions == null || textPositions.isEmpty()) return;
            boolean hasUnicode = textPositions.stream()
                    .anyMatch(tp -> tp.getUnicode() != null && !tp.getUnicode().isBlank());
            if (hasUnicode) {
                for (TextPosition tp : textPositions) {
                    String unicode = tp.getUnicode();
                    if (unicode != null && !unicode.isBlank()) {
                        runs.add(new TextRun(unicode, tp.getXDirAdj(), tp.getYDirAdj(),
                                tp.getHeightDir(), isItalicFont(tp)));
                    }
                }
            } else if (!text.isBlank()) {
                // Fallback for fonts where individual unicode is null (e.g. ligature encodings):
                // attach the run-level text to the first character's position.
                TextPosition first = textPositions.get(0);
                runs.add(new TextRun(text, first.getXDirAdj(), first.getYDirAdj(),
                        first.getHeightDir(), isItalicFont(first)));
            }
        }

        private static boolean isItalicFont(TextPosition tp) {
            try {
                var font = tp.getFont();
                if (font == null) return false;
                String name = font.getName();
                if (name != null) {
                    String n = name.toLowerCase();
                    if (n.contains("italic") || n.contains("oblique")) return true;
                }
                var desc = font.getFontDescriptor();
                if (desc != null) {
                    if (desc.isItalic()) return true;
                    if (desc.getItalicAngle() < -5f) return true;
                }
                // Check text matrix for shear (italic via matrix transformation)
                float shear = tp.getTextMatrix().getValue(0, 1);
                if (Math.abs(shear) > 0.1f) return true;
            } catch (Exception ignored) {}
            return false;
        }

        /**
         * Intercepts PDF path operators to detect stroked rectangles (read-aloud boxes).
         *
         * The `re` operator appends a rectangle to the current path; `S` strokes it.
         * We capture any such rectangle that is large enough to be a content box
         * (width ≥ 80pt, height ≥ 40pt) and convert its coordinates from PDF space
         * (y=0 at bottom) to Java2D space (y=0 at top).
         */
        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            switch (operator.getName()) {
                case "re" -> {
                    if (operands.size() >= 4
                            && operands.get(0) instanceof COSNumber x
                            && operands.get(1) instanceof COSNumber y
                            && operands.get(2) instanceof COSNumber w
                            && operands.get(3) instanceof COSNumber h) {
                        pendingRects.add(new float[]{
                            x.floatValue(), y.floatValue(),
                            w.floatValue(), h.floatValue()
                        });
                    }
                }
                case "S", "B", "b", "s", "f", "F", "f*", "B*" -> {
                    // Path-painting operator: process all pending rectangles.
                    float pageH = getCurrentPage().getBBox().getHeight();
                    for (float[] r : pendingRects) {
                        float rw = Math.abs(r[2]);
                        float rh = Math.abs(r[3]);
                        // Accept both large boxes AND thin border pieces (h≤3 or w≤3)
                        // that have meaningful extent in one dimension.
                        float j2dY = pageH - r[1] - (r[3] < 0 ? 0 : rh);
                        if (rw >= 30 && rh >= 30) {
                            // Large rectangle: record as a direct box
                            boxes.add(new Rectangle2D.Float(r[0], j2dY, rw, rh));
                        } else if (rh <= 3 && rw >= 40) {
                            // Thin horizontal border piece: record as a 1pt-high marker
                            borderPieces.add(new Rectangle2D.Float(r[0], j2dY, rw, Math.max(rh, 1)));
                        } else if (rw <= 3 && rh >= 40) {
                            // Thin vertical border piece: record as a 1pt-wide marker
                            borderPieces.add(new Rectangle2D.Float(r[0], j2dY, Math.max(rw, 1), rh));
                        }
                    }
                    pendingRects.clear();
                }
                case "n" -> pendingRects.clear(); // path discard
            }
            super.processOperator(operator, operands);
        }

        /**
         * After all operators have been processed, reconstruct boxes from border pieces.
         * Border pieces are thin (≤3pt) rectangles used as box outlines.  Pairs of
         * horizontal pieces at similar x with similar widths define the top and bottom
         * of a box; the corresponding vertical pieces give the sides.
         */
        void reconstructBoxesFromBorders() {
            if (borderPieces.isEmpty()) return;

            // Separate horizontal (w > h) pieces only — verticals are not used for pairing
            List<Rectangle2D> horiz = new ArrayList<>();
            for (Rectangle2D bp : borderPieces) {
                if (bp.getWidth() > bp.getHeight()) horiz.add(bp);
            }
            // Sort top-to-bottom so we can use nearest-match efficiently
            horiz.sort(Comparator.comparingDouble(Rectangle2D::getY));

            // For each top piece, find the NEAREST matching bottom piece below it.
            // Using nearest-match prevents spurious boxes from pairing a top piece
            // with a distant bottom piece that belongs to a different box.
            for (int i = 0; i < horiz.size(); i++) {
                Rectangle2D top = horiz.get(i);
                Rectangle2D bestBot = null;
                float bestH = Float.MAX_VALUE;
                for (int j = i + 1; j < horiz.size(); j++) {
                    Rectangle2D bot = horiz.get(j);
                    if (Math.abs(top.getX() - bot.getX()) > 8) continue;
                    if (Math.abs(top.getWidth() - bot.getWidth()) > 8) continue;
                    float boxH = (float)(bot.getY() - top.getY());
                    if (boxH < 30) continue; // too thin to be a content box
                    if (boxH < bestH) { bestH = boxH; bestBot = bot; }
                }
                if (bestBot != null) {
                    boxes.add(new Rectangle2D.Float(
                        (float) top.getX(),
                        (float) top.getY(),
                        (float) top.getWidth(),
                        bestH
                    ));
                }
            }
        }

        @Override protected void writeLineSeparator()      throws IOException {}
        @Override protected void writeWordSeparator()      throws IOException {}
        @Override protected void writeParagraphSeparator() throws IOException {}
    }

    // -------------------------------------------------------------------------
    // Document-level two-column hint
    // -------------------------------------------------------------------------

    /**
     * Probes pages spread across the document.  Returns a positive value if the
     * document appears to use a two-column layout (causing extractPage to attempt
     * per-band zone analysis), or -1 if single-column.
     */
    public float detectDocumentColumnBoundary(PDDocument doc, int pageCount) throws IOException {
        int skip  = Math.max(1, pageCount / 20);
        int first = Math.min(skip, pageCount - 1);
        int last  = pageCount - 1;
        int total = last - first + 1;
        int step  = Math.max(1, total / 20);

        int detected = 0;
        int sampled  = 0;
        for (int i = first; i <= last; i += step) {
            PDPage page   = doc.getPage(i);
            float pgWidth = page.getMediaBox().getWidth();
            int pgHW      = (int) Math.ceil(pgWidth) + 1;
            int[] hist    = new int[pgHW];
            List<TextRun> runs = captureRuns(doc, i);
            for (TextRun r : runs) {
                int xi = Math.max(0, Math.min(pgHW - 1, (int) r.x()));
                hist[xi]++;
            }
            float gap = findBestGap(hist, pgWidth, COL_SCAN_START, COL_SCAN_END, 0, DOC_HINT_GAP_MIN_WIDTH);
            if (gap > 0) {
                float rightReach = 0;
                for (TextRun r : runs) if (r.x() > gap) rightReach = Math.max(rightReach, r.x());
                if (rightReach >= pgWidth * RIGHT_REACH_MIN) detected++;
            }
            sampled++;
        }

        return (detected >= Math.max(2, sampled / 3)) ? 1f : -1f;
    }

    // -------------------------------------------------------------------------
    // Public per-page extraction API
    // -------------------------------------------------------------------------

    /**
     * Returns the x-coordinate of the column gutter for the given page, or -1 if
     * the page appears to be single-column.  Uses the same zone detection logic as
     * the full text extraction path.
     *
     * @param doc       loaded source document
     * @param pageIndex zero-based page index
     * @return column gap x-position in PDF user-space units, or -1
     */
    public float detectPageColumnGap(PDDocument doc, int pageIndex) throws IOException {
        PDPage page      = doc.getPage(pageIndex);
        float pageWidth  = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();
        List<TextRun> runs = captureRuns(doc, pageIndex);
        List<Zone> zones   = detectZones(runs, pageWidth, pageHeight, 0);
        for (Zone z : zones) {
            if (z.layout() == BandLayout.TWO_COL || z.layout() == BandLayout.THREE_COL) {
                return z.gap1();   // first column gutter x
            }
        }
        return -1f;
    }

    /**
     * Extracts text from a single page using per-band zone detection.
     *
     * @param doc             the loaded PDDocument
     * @param zeroBasedPageIndex  page index (0-based)
     * @param docBoundaryHint result of {@link #detectDocumentColumnBoundary};
     *                        positive → attempt per-band analysis, ≤0 → single-column
     */
    public String extractPage(PDDocument doc, int zeroBasedPageIndex, float docBoundaryHint)
            throws IOException {
        PDPage page      = doc.getPage(zeroBasedPageIndex);
        float pageWidth  = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();

        List<TextRun> runs = captureRuns(doc, zeroBasedPageIndex);
        if (runs.isEmpty()) return "";

        String text;

        if (docBoundaryHint <= 0) {
            text = extractSingleColumnPage(doc, zeroBasedPageIndex);
        } else {
            List<Zone> zones = detectZones(runs, pageWidth, pageHeight, docBoundaryHint);
            boolean anyMultiCol = zones.stream().anyMatch(z -> z.layout() != BandLayout.SINGLE);

            if (!anyMultiCol) {
                text = extractSingleColumnPage(doc, zeroBasedPageIndex);
            } else {
                StringBuilder sb = new StringBuilder();
                for (Zone zone : zones) {
                    float zh = zone.yEnd() - zone.yStart();
                    if (zh <= 0) continue;

                    float zEnd = zone.yEnd();
                    switch (zone.layout()) {
                        case SINGLE -> {
                            sb.append(extractColumnText(page, runs, 0, zone.yStart(), pageWidth, zEnd));
                        }
                        case TWO_COL -> {
                            float g1 = zone.gap1();
                            float g2 = zone.gap2();  // right col end x (sidebar boundary or pageWidth)
                            // Left column: always extract full width (x=0..g1).
                            // Left-column sidebars are detected but NOT clipped here —
                            // clipping causes title/header text loss because those chars
                            // share the sidebar's x-range but span a different y-range.
                            // Use --phase sidebars to review and extract left sidebars.
                            sb.append(extractColumnText(page, runs, 0,  zone.yStart(), g1,      zEnd));
                            sb.append(extractColumnText(page, runs, g1, zone.yStart(), g2 - g1, zEnd));
                        }
                        case THREE_COL -> {
                            float g1 = zone.gap1();
                            float g2 = zone.gap2();
                            sb.append(extractColumnText(page, runs, 0,  zone.yStart(), g1,            zEnd));
                            sb.append(extractColumnText(page, runs, g1, zone.yStart(), g2 - g1,       zEnd));
                            sb.append(extractColumnText(page, runs, g2, zone.yStart(), pageWidth - g2, zEnd));
                        }
                    }
                }
                text = sb.toString();
            }
        }

        return postProcess(text);
    }

    /**
     * Extracts only the sidebar text from a single page.
     * Returns an empty string if no sidebar is detected on this page.
     *
     * For TWO_COL zones: when a right sidebar is detected, gap2 is the sidebar boundary
     * (valleyX); the sidebar occupies x=[gap2 .. pageWidth].  When no sidebar, gap2 ==
     * pageWidth, so rightSW == 0 and nothing is extracted.
     * THREE_COL zones are skipped — sidebar extraction is not supported on 3-col pages.
     *
     * @param doc                the loaded PDDocument
     * @param zeroBasedPageIndex page index (0-based)
     * @param docBoundaryHint    result of {@link #detectDocumentColumnBoundary}
     * @return trimmed sidebar text, or "" if no sidebar on this page
     */
    public String extractPageSidebar(PDDocument doc, int zeroBasedPageIndex, float docBoundaryHint)
            throws IOException {
        if (docBoundaryHint <= 0) return "";

        PDPage page      = doc.getPage(zeroBasedPageIndex);
        float pageWidth  = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();

        List<TextRun> runs = captureRuns(doc, zeroBasedPageIndex);
        if (runs.isEmpty()) return "";

        List<Zone> zones = detectZones(runs, pageWidth, pageHeight, 0);

        StringBuilder sb = new StringBuilder();
        for (Zone zone : zones) {
            if (zone.layout() != BandLayout.TWO_COL) continue;
            float zh = zone.yEnd() - zone.yStart();
            if (zh <= 0) continue;

            // Left sidebar (at x=0..leftSidebarW)
            // Clamp to the sidebar's actual Y range: within the sidebar, no text crosses
            // x=leftSW (lines are narrow); outside it, main-column lines extend past leftSW.
            // We scan horizontal bands and keep only bands where the gap at x~leftSW is clear.
            float leftSW = zone.leftSidebarW();
            if (leftSW >= 20) {
                float[] leftYBounds = findSidebarYBounds(runs, zone.yStart(), zone.yEnd(), leftSW, zone.gap1());
                if (leftYBounds != null) {
                    String text = extractRegion(page, 0, leftYBounds[0], leftSW, leftYBounds[1] - leftYBounds[0]);
                    if (!text.isBlank()) sb.append(text);
                }
            }

            // Right sidebar (at x=gap2..pageWidth)
            // When no right sidebar: gap2 = pageWidth, so rightSW = 0.
            float rightSidebarX = zone.gap2();
            float rightSW       = pageWidth - rightSidebarX;
            if (rightSW >= 20) {
                float[] rightYBounds = findRightSidebarYBounds(runs, zone.yStart(), zone.yEnd(),
                        rightSidebarX, zone.gap1(), pageWidth);
                if (rightYBounds != null) {
                    String text = extractRegion(page, rightSidebarX, rightYBounds[0],
                            rightSW, rightYBounds[1] - rightYBounds[0]);
                    if (!text.isBlank()) sb.append(text);
                }
            }
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Per-band zone detection
    // -------------------------------------------------------------------------

    /**
     * Two-stage zone detection:
     *
     * Stage 1 — full-page histogram finds the main column gap reliably.
     *   If none found, the whole page is SINGLE.
     *
     * Stage 2 — full-page right-column histogram looks for a sidebar sub-gap.
     *   Using the whole page (not slabs) gives enough density to reliably
     *   distinguish the sidebar gutter (~10-20 pt empty) from inter-word spaces.
     *   If a sidebar is found, the whole page uses SIDEBAR_PLUS_COL so every
     *   row extracts three regions.  Empty regions produce empty text, which is
     *   harmless when the sidebar only covers part of the page height.
     */
    private List<Rectangle2D> pageBoxes  = Collections.emptyList(); // populated by captureRuns

    private List<Zone> detectZones(List<TextRun> runs, float pageWidth, float pageHeight,
                                   float docBoundaryHint) {
        int hw = (int) Math.ceil(pageWidth) + 1;

        // ------------------------------------------------------------------
        // Stage 1: find ALL qualifying gaps in the full-page histogram.
        //
        // Using findAllGaps (not just findBestGap) lets us detect 3-column layouts
        // where two gutters both qualify.  findBestGap picks the gap nearest the
        // page center — for equal-width 3-column layouts the second gutter can be
        // closer to center, causing findBestGap to return the wrong gap.
        // ------------------------------------------------------------------
        int[] fullHist = new int[hw];
        for (TextRun r : runs) {
            int xi = Math.max(0, Math.min(hw - 1, (int) r.x()));
            fullHist[xi]++;
        }
        // maxBinCount=2: allow up to 2 stray chars per bin in the gutter.
        // Column content bins typically have 20-40+ chars per bin on a full page.
        List<Float> allGaps = findAllGaps(fullHist, pageWidth,
                COL_SCAN_START, COL_SCAN_END, 2, MAIN_GAP_MIN_WIDTH);

        // Fallback: if no gap found with the strict threshold, retry with a looser
        // bin count to catch pages where dotted leaders (TOC, index) partially fill
        // the gutter with sparse characters (3-4 per bin).  Only applies when 0 gaps
        // found — pages that already detect gaps are unchanged.
        if (allGaps.isEmpty()) {
            allGaps = findAllGaps(fullHist, pageWidth,
                    COL_SCAN_START, COL_SCAN_END, 4, MAIN_GAP_MIN_WIDTH);
        }

        // Hint fallback: if Stage 1 found nothing (e.g. a centered full-width title
        // filled the gutter bins), try the document-level column boundary.  Accept it
        // only when the right column has text that reaches across the page — prevents
        // false positives on pages that are genuinely single-column.
        if (allGaps.isEmpty() && docBoundaryHint > 0) {
            float rightReachHint = 0;
            for (TextRun r : runs) if (r.x() > docBoundaryHint) rightReachHint = Math.max(rightReachHint, r.x());
            if (rightReachHint >= pageWidth * RIGHT_REACH_MIN) {
                allGaps = List.of(docBoundaryHint);
            }
        }

        if (allGaps.isEmpty()) {
            return List.of(new Zone(0, pageHeight, BandLayout.SINGLE, -1, -1, 0));
        }

        // ------------------------------------------------------------------
        // Stage 1b: three-column detection.
        //
        // If two qualifying gaps exist, check whether they carve the page into
        // three columns of plausible equal(ish) width.  If yes → THREE_COL.
        // This runs before the single-gap TWO_COL logic below.
        // ------------------------------------------------------------------
        if (allGaps.size() >= 2) {
            float g1 = allGaps.get(0);
            float g2 = allGaps.get(1);
            float col1W = g1;
            float col2W = g2 - g1;
            float col3W = pageWidth - g2;
            float maxW  = Math.max(col1W, Math.max(col2W, col3W));
            float minW  = Math.max(Math.min(col1W, Math.min(col2W, col3W)), 1f);

            float col3Reach = 0;
            for (TextRun r : runs) if (r.x() > g2) col3Reach = Math.max(col3Reach, r.x());

            boolean threeColValid = col1W >= pageWidth * 0.20f
                    && col2W >= pageWidth * 0.20f
                    && col3W >= pageWidth * 0.20f
                    && maxW / minW <= 2.5f
                    && col3Reach >= pageWidth * RIGHT_REACH_MIN;

            if (threeColValid) {
                // Detect a full-width header above the three-column body.
                // A heading that spans all columns will have characters in the
                // gutter regions (x≈g1 and x≈g2).  Normal body text never does.
                // Split into SINGLE + THREE_COL zones so the title is extracted whole.
                float headerEndY = fullWidthHeaderEnd(runs, g1, g2);
                if (headerEndY > 0) {
                    return List.of(
                        new Zone(0,           headerEndY, BandLayout.SINGLE,    -1, -1, 0),
                        new Zone(headerEndY,  pageHeight, BandLayout.THREE_COL, g1, g2, 0)
                    );
                }
                return List.of(new Zone(0, pageHeight, BandLayout.THREE_COL, g1, g2, 0));
            }
        }

        // Single-gap (or failed THREE_COL): proceed with TWO_COL logic.
        // Pick the gap closest to the page center as the main column divider.
        float mainGap;
        {
            float center = pageWidth * (COL_SCAN_START + COL_SCAN_END) / 2f;
            mainGap = allGaps.stream()
                    .min(Comparator.comparingDouble(g -> Math.abs(g - center)))
                    .orElse(-1f);
        }

        // Verify the right column actually reaches far across the page
        float rightReach = 0;
        for (TextRun r : runs) if (r.x() > mainGap) rightReach = Math.max(rightReach, r.x());
        if (rightReach < pageWidth * RIGHT_REACH_MIN) {
            return List.of(new Zone(0, pageHeight, BandLayout.SINGLE, -1, -1, 0));
        }

        // ------------------------------------------------------------------
        // Stage 2: detect sidebar boundary in the right column via smoothed
        // histogram valley.
        //
        // Strategy: sidebar boxes in D&D modules create a bimodal x-density
        // in the right column — the main column text clusters at one range,
        // the sidebar at another, with a low-density valley between them.
        // A 8-pt smoothing window merges the noise from word spaces, so even
        // a narrow gutter (~8-12 pt) produces a clear valley in the smoothed
        // signal.
        //
        // When found, the right column is CLIPPED at the sidebar boundary.
        // The sidebar content is dropped from Phase 1 output (no interleaving)
        // and can be re-extracted separately in a later phase.
        // ------------------------------------------------------------------
        int[] rightHist = new int[hw];
        for (TextRun r : runs) {
            if (r.x() > mainGap) {
                int xi = Math.min(hw - 1, (int) r.x());
                rightHist[xi]++;
            }
        }

        // Smooth the right-column histogram with an 8-pt window.
        // Smaller window preserves the narrow gutter signature (~8-12pt) while still
        // suppressing single-character noise within the gutter.
        int smoothW = 8;
        float[] smoothed = new float[hw];
        for (int x = 0; x < hw; x++) {
            int sum = 0, cnt = 0;
            for (int dx = -smoothW / 2; dx <= smoothW / 2; dx++) {
                int ix = x + dx;
                if (ix >= 0 && ix < hw) { sum += rightHist[ix]; cnt++; }
            }
            smoothed[x] = cnt > 0 ? sum / (float) cnt : 0;
        }

        // Scan for the deepest valley in the right column, between 25% and
        // 85% of page width (avoids the main-column start and right margin).
        int valleyStart = (int) (mainGap + (pageWidth - mainGap) * 0.25f);
        int valleyEnd   = (int) (pageWidth * 0.85f);
        int valleyX = -1;
        float valleyVal = Float.MAX_VALUE;
        for (int x = valleyStart; x <= valleyEnd; x++) {
            if (smoothed[x] < valleyVal) { valleyVal = smoothed[x]; valleyX = x; }
        }

        float rightColEndX = pageWidth;  // default: right col reaches page edge

        if (valleyX > 0) {
            // Measure average density in the 40-pt windows on each side of valley.
            // Left side (main column) is always dense; right side (sidebar) may be sparser.
            float leftDensity  = avgSmoothed(smoothed, valleyX - 40, valleyX - 1);
            float rightDensity = avgSmoothed(smoothed, valleyX + 1,  valleyX + 40);
            float sidebarW     = pageWidth - valleyX;

            // Count total chars actually in the proposed sidebar region to distinguish
            // a real text box (50+ chars) from a sparse right margin (< 30 chars).
            int sidebarCharCount = 0;
            for (int x = valleyX; x < hw; x++) sidebarCharCount += rightHist[x];

            // The valley is real when:
            //   1. valley < 45% of the (always-dense) left side — clear dip into a gutter
            //   2. right side has some density (not just empty page margin)
            //   3. sidebar region has enough chars to be a real text box
            //   4. sidebar width is plausible (60pt..35% of page)
            boolean validValley = valleyVal < leftDensity * 0.45f
                    && leftDensity > 0.5f
                    && rightDensity > 0.5f
                    && sidebarCharCount >= 30
                    && sidebarW >= 60
                    && sidebarW / pageWidth < SIDEBAR_MAX_WIDTH_RATIO;

            if (validValley) {
                // Clip the right column at the sidebar boundary
                rightColEndX = valleyX;
            }
        }

        // ------------------------------------------------------------------
        // Stage 3: detect sidebar boundary in the LEFT column.
        //
        // Same smoothed-valley approach, but scanning the left column.
        // The sidebar sits on the OUTER (x=0) side; the main column text is
        // on the INNER side (x closer to mainGap).  When found, left column
        // extraction starts at leftSidebarW instead of x=0.
        // ------------------------------------------------------------------
        int[] leftHist = new int[hw];
        for (TextRun r : runs) {
            if (r.x() < mainGap) {
                int xi = Math.max(0, Math.min(hw - 1, (int) r.x()));
                leftHist[xi]++;
            }
        }

        // Band-based left-column sidebar detection.
        //
        // A left sidebar that spans only 25-30% of the page height is invisible in
        // a full-page histogram because the upper-portion text dilutes the valley.
        // Dividing into bands ensures that the band(s) containing the sidebar see
        // the valley at full contrast — the best band's ratio drives the decision.
        int    leftValleyEnd = (int) (mainGap * 0.80f);
        int    NUM_BANDS_L   = 6;
        float  bandHL        = pageHeight / NUM_BANDS_L;
        int    bestLValleyX  = -1;
        float  bestLRatio    = 1.0f;

        for (int b = 0; b < NUM_BANDS_L; b++) {
            float yBandStart = b * bandHL;
            float yBandEnd   = (b + 1) * bandHL;

            int[] bandHist = new int[hw];
            for (TextRun r : runs) {
                if (r.x() < mainGap && r.y() >= yBandStart && r.y() < yBandEnd) {
                    int xi = Math.max(0, Math.min(hw - 1, (int) r.x()));
                    bandHist[xi]++;
                }
            }

            float[] bandSm = new float[hw];
            for (int x = 0; x < hw; x++) {
                int sum = 0, cnt = 0;
                for (int dx = -smoothW / 2; dx <= smoothW / 2; dx++) {
                    int ix = x + dx;
                    if (ix >= 0 && ix < hw) { sum += bandHist[ix]; cnt++; }
                }
                bandSm[x] = cnt > 0 ? sum / (float) cnt : 0;
            }

            // Skip leading margin for this band
            int bScanStart = (int) (pageWidth * 0.05f);
            while (bScanStart < leftValleyEnd && bandSm[bScanStart] < 0.3f) bScanStart++;

            for (int x = bScanStart; x <= leftValleyEnd; x++) {
                float leftD  = avgSmoothed(bandSm, x - 30, x - 1);
                float rightD = avgSmoothed(bandSm, x + 1,  x + 30);
                if (leftD < 0.5f || rightD < 0.5f) continue;
                float ratio = bandSm[x] / Math.max(leftD, rightD);
                if (ratio < bestLRatio) { bestLRatio = ratio; bestLValleyX = x; }
            }
        }

        float leftSidebarW = 0;  // default: no left sidebar

        // ratio < 0.40: sidebar boxes create a clear gap in the character histogram.
        // The colCoverage < 0.65 guard (below) handles the high-ratio false positive on
        // page 9 (ratio=0.41, colCov=0.78). Pages 15 and 29 (ratio=0.43) fall outside
        // the threshold. Real sidebars observed at ratio 0.00-0.37; false positives at 0.41+.
        if (bestLValleyX > 0 && bestLRatio < 0.40f) {
            // Confirm with full-page char count so we don't trigger on nearly-empty bands
            int leftSidebarCharCount = 0;
            for (int x = 0; x <= bestLValleyX; x++) leftSidebarCharCount += leftHist[x];

            float leftSidebarWidth = bestLValleyX;
            // colCoverage < 0.65: sidebar must be a narrow sub-region of the left column.
            // Real sidebars sit in ~40-50% of the column; a valley at 78%+ is an artifact.
            float colCoverage = leftSidebarWidth / mainGap;
            if (leftSidebarCharCount >= 30
                    && leftSidebarWidth >= 60
                    && leftSidebarWidth / pageWidth < SIDEBAR_MAX_WIDTH_RATIO
                    && colCoverage < 0.65f) {
                leftSidebarW = bestLValleyX;
            }
        }

        // Detect a full-width header above the two-column body (mirrors THREE_COL logic).
        // Pass mainGap as both g1 and g2: fullWidthHeaderEnd treats any character within
        // ±15 pt of the gutter as a "gutter char".  A centered title (e.g. "Players'
        // Background") has characters that straddle the column gutter; body text in the
        // two columns does not.
        float headerEndY = fullWidthHeaderEnd(runs, mainGap, mainGap);
        if (headerEndY > 0) {
            return List.of(
                new Zone(0,           headerEndY, BandLayout.SINGLE,  -1,      -1,          0),
                new Zone(headerEndY,  pageHeight, BandLayout.TWO_COL, mainGap, rightColEndX, leftSidebarW)
            );
        }

        return List.of(new Zone(0, pageHeight, BandLayout.TWO_COL, mainGap, rightColEndX, leftSidebarW));
    }

    /**
     * Determine the Y range of a left sidebar at x=[0..gapX].
     *
     * Strategy: divide [yStart..yEnd] into fine horizontal bands. A band is "inside
     * the sidebar" when no text character lands in the gap zone x=[gapX-12..gapX+12]
     * (meaning the sidebar's narrow lines don't reach gapX) AND there is text at
     * x < gapX in that band. Main-column lines above/below the sidebar extend past
     * gapX, so those bands are excluded.
     *
     * @return float[]{sidebarYStart, sidebarYEnd} for the largest contiguous block,
     *         or null if no valid block found.
     */
    private float[] findSidebarYBounds(List<TextRun> runs, float yStart, float yEnd,
                                       float gapX, float mainGap) {
        int numBands = 80;
        float zh = yEnd - yStart;
        float bandH = zh / numBands;

        // For each band, count chars in three zones (left-column only):
        //   leftZone:   x = [gapX-80 .. gapX-10]   (sidebar text density)
        //   valleyZone: x = [gapX-10 .. gapX+10]   (the gap itself)
        //   rightZone:  x = [gapX+10 .. mainGap]    (main-column text density)
        //
        // A band is "inside the sidebar" when leftZone and rightZone are both populated
        // but valleyZone is sparse (ratio < threshold). Bands above/below the sidebar
        // have full-width text that crosses gapX, so valleyZone fills up.
        int[] leftCnt   = new int[numBands];
        int[] valCnt    = new int[numBands];
        int[] rightCnt  = new int[numBands];
        float valLo  = gapX - 10;
        float valHi  = gapX + 10;
        float leftLo = 0;  // count all sidebar chars x=0..valLo, not just the 80pt near the gap

        for (TextRun r : runs) {
            if (r.x() >= mainGap) continue;  // right column — irrelevant
            if (r.y() < yStart || r.y() >= yEnd) continue;
            int b = Math.min((int) ((r.y() - yStart) / bandH), numBands - 1);
            float x = r.x();
            if      (x >= valLo && x <= valHi) valCnt[b]++;
            else if (x >= leftLo && x < valLo) leftCnt[b]++;
            else if (x > valHi && x < mainGap) rightCnt[b]++;
        }

        boolean[] isSidebar = new boolean[numBands];
        for (int b = 0; b < numBands; b++) {
            // A band is inside the sidebar when:
            //   - leftCnt is dense (sidebar text fills x=0..gapX fully → ≥8 chars)
            //   - valCnt is tiny relative to leftCnt (gap at gapX is clear; val*5 ≤ lft)
            //   - rightCnt is present (main column text is also active in this y-range)
            // Full-width text above/below the sidebar fails because either leftCnt is low
            // (only the left portion of the left sub-column) or valCnt is proportionally
            // high (text flows continuously through the gap zone).
            if (leftCnt[b] >= 8 && valCnt[b] * 5 <= leftCnt[b] && rightCnt[b] >= 3) {
                isSidebar[b] = true;
            }
        }

        // Find the longest contiguous run of confirmed sidebar bands.
        int bestStart = -1, bestLen = 0;
        int curStart  = -1, curLen  = 0;
        for (int b = 0; b < numBands; b++) {
            if (isSidebar[b]) {
                if (curStart < 0) curStart = b;
                curLen++;
            } else {
                if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
                curStart = -1; curLen = 0;
            }
        }
        if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }

        if (bestStart < 0 || bestLen < 3) return null;

        // Extend the block outward to capture sidebar titles / sparse final lines.
        // The extension accepts two kinds of bands beyond the dense core:
        //   (a) Dense sidebar bands with clear gap: leftCnt >= 8 && val*5 <= lft (same as core)
        //   (b) Sparse sidebar lines: leftCnt >= 3 && val <= 2 (short lines / titles with near-zero val)
        // Bands where val is proportionally high (full-width text crossing the gap) stop extension.
        // No pre-padding: the extension explicitly reaches the title band, so pre-padding only
        // risks capturing adjacent main-column text.
        int extStart = bestStart;
        int extEnd   = bestStart + bestLen - 1;
        for (int b = bestStart - 1; b >= Math.max(0, bestStart - 50); b--) {
            if (leftCnt[b] == 0 && rightCnt[b] == 0) continue;    // completely blank — skip
            if (rightCnt[b] == 0 && leftCnt[b] >= 3) { extStart = b; continue; }  // sidebar-only title band
            if (leftCnt[b] == 0 && valCnt[b] < 3) continue;        // blank sidebar line (main col continues)
            boolean denseClear  = leftCnt[b] >= 8 && valCnt[b] * 5 <= leftCnt[b];
            boolean sparseClear = leftCnt[b] >= 3 && valCnt[b] <= 2;
            if (denseClear || sparseClear) extStart = b;
            else break;
        }
        for (int b = extEnd + 1; b <= Math.min(numBands - 1, extEnd + 15); b++) {
            if (leftCnt[b] == 0 && rightCnt[b] == 0) continue;
            if (rightCnt[b] == 0 && leftCnt[b] >= 3) { extEnd = b; continue; }
            if (leftCnt[b] == 0 && valCnt[b] < 3) continue;
            boolean denseClear  = leftCnt[b] >= 8 && valCnt[b] * 5 <= leftCnt[b] && rightCnt[b] >= 3;
            boolean sparseClear = leftCnt[b] >= 3 && valCnt[b] <= 2 && rightCnt[b] >= 3;
            if (denseClear || sparseClear) extEnd = b;
            else break;
        }

        // No pre-padding: extStart is the first band that contains sidebar content.
        // The extraction region starts exactly at the band boundary.
        return new float[]{ yStart + extStart * bandH,
                            yStart + (extEnd + 1) * bandH };
    }

    /**
     * Mirrors findSidebarYBounds for right-side sidebar boxes.
     *
     * Right sidebar geometry:
     *   sidebarCnt: chars at x = rightSidebarX+10 .. pageWidth   (sidebar box content)
     *   valCnt:     chars at x = rightSidebarX-10 .. rightSidebarX+10 (the gap)
     *   mainCnt:    chars at x = mainGap .. rightSidebarX-10     (right main-column content)
     *
     * A band is "inside the sidebar" when sidebarCnt is dense, the gap is clear,
     * and the right main-column is also active.  Full-width text above/below the
     * sidebar box fails because either sidebarCnt is low (only a fraction of the
     * right column lands in the sidebar zone) or valCnt is proportionally high.
     */
    private float[] findRightSidebarYBounds(List<TextRun> runs, float yStart, float yEnd,
                                             float rightSidebarX, float mainGap, float pageWidth) {
        int numBands = 80;
        float zh = yEnd - yStart;
        float bandH = zh / numBands;

        int[] sidebarCnt = new int[numBands];
        int[] valCnt     = new int[numBands];
        int[] mainCnt    = new int[numBands];
        float valLo = rightSidebarX - 10;
        float valHi = rightSidebarX + 10;

        for (TextRun r : runs) {
            if (r.x() < mainGap) continue;  // left column — irrelevant
            if (r.y() < yStart || r.y() >= yEnd) continue;
            int b = Math.min((int) ((r.y() - yStart) / bandH), numBands - 1);
            float x = r.x();
            if      (x >= valLo && x <= valHi)       valCnt[b]++;
            else if (x > valHi  && x <= pageWidth)   sidebarCnt[b]++;
            else if (x >= mainGap && x < valLo)       mainCnt[b]++;
        }

        boolean[] isSidebar = new boolean[numBands];
        for (int b = 0; b < numBands; b++) {
            if (sidebarCnt[b] >= 8 && valCnt[b] * 5 <= sidebarCnt[b] && mainCnt[b] >= 3) {
                isSidebar[b] = true;
            }
        }

        int bestStart = -1, bestLen = 0;
        int curStart  = -1, curLen  = 0;
        for (int b = 0; b < numBands; b++) {
            if (isSidebar[b]) {
                if (curStart < 0) curStart = b;
                curLen++;
            } else {
                if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
                curStart = -1; curLen = 0;
            }
        }
        if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }

        if (bestStart < 0 || bestLen < 3) return null;

        int extStart = bestStart;
        int extEnd   = bestStart + bestLen - 1;
        // Upward extension: uses a ratio-based fallback so we can bridge through sparse sidebar
        // bands (sb=4-11, val=3-4) that fail the strict val*5<=sb criterion.  The ratio
        // val/max(sb,mc) stays low (≈0.10-0.15) even for these bands because mc is large.
        // The sb>=3 floor prevents extension into near-empty page-header regions (sb=0-2).
        for (int b = bestStart - 1; b >= Math.max(0, bestStart - 50); b--) {
            if (sidebarCnt[b] == 0 && mainCnt[b] == 0) continue;
            if (mainCnt[b] == 0 && sidebarCnt[b] >= 3) { extStart = b; continue; }
            if (sidebarCnt[b] == 0 && valCnt[b] < 3) continue;
            int   denom     = Math.max(sidebarCnt[b], Math.max(mainCnt[b], 1));
            float ratioB    = valCnt[b] / (float) denom;
            boolean denseClear  = sidebarCnt[b] >= 8 && valCnt[b] * 5 <= sidebarCnt[b];
            boolean sparseClear = sidebarCnt[b] >= 3 && valCnt[b] <= 2;
            boolean ratioPass   = sidebarCnt[b] >= 3 && ratioB < 0.40f;
            if (denseClear || sparseClear || ratioPass) extStart = b;
            else break;
        }
        // Downward extension: right sidebar text starts at x=rightSidebarX, so leading chars of
        // each line fall in the val zone — val/sb ratios run slightly higher than the left sidebar.
        // Use val*4<=sb (ratio ≤ 0.25) for dense bands; val<=2 for sparse bands.
        // The sb>=8 floor still blocks the below-sidebar full-width text (sb=5-7).
        for (int b = extEnd + 1; b <= Math.min(numBands - 1, extEnd + 15); b++) {
            if (sidebarCnt[b] == 0 && mainCnt[b] == 0) continue;
            if (mainCnt[b] == 0 && sidebarCnt[b] >= 3) { extEnd = b; continue; }
            if (sidebarCnt[b] == 0 && valCnt[b] < 3) continue;
            boolean denseClear  = sidebarCnt[b] >= 8 && valCnt[b] * 4 <= sidebarCnt[b] && mainCnt[b] >= 3;
            boolean sparseClear = sidebarCnt[b] >= 3 && valCnt[b] <= 2 && mainCnt[b] >= 3;
            if (denseClear || sparseClear) extEnd = b;
            else break;
        }

        return new float[]{ yStart + extStart * bandH,
                            yStart + (extEnd + 1) * bandH };
    }

    /** Average of smoothed[from..to], clamped to array bounds. */
    private float avgSmoothed(float[] smoothed, int from, int to) {
        if (from < 0) from = 0;
        if (to >= smoothed.length) to = smoothed.length - 1;
        if (from > to) return 0;
        float sum = 0;
        for (int x = from; x <= to; x++) sum += smoothed[x];
        return sum / (to - from + 1);
    }

    // -------------------------------------------------------------------------
    // Header detection
    // -------------------------------------------------------------------------

    /**
     * Returns the y-coordinate boundary between a full-width header (title) zone
     * and the three-column body zone below it on a chapter-opener page.
     *
     * Strategy: characters that straddle a column gutter only appear in two cases:
     *   (a) a full-width heading that spans all columns, or
     *   (b) body text words whose glyphs happen to fall at the gutter boundary.
     *
     * Case (a) produces gutter chars in one isolated cluster at the top of the page,
     * followed by a clear vertical gap before the body text gutter chars in (b).
     * We detect this gap: if the distance from the first cluster to the next gutter
     * char exceeds 2× the estimated body line height, the first cluster is a heading.
     *
     * Returns the y below the heading cluster (suitable as yStart for the body zone),
     * or -1 if no isolated header is found (pure THREE_COL body, no split needed).
     */
    private float fullWidthHeaderEnd(List<TextRun> runs, float g1, float g2) {
        float gutterHalfW = 15f;
        List<Float> gutterYs = new ArrayList<>();
        for (TextRun r : runs) {
            boolean inGutter = (r.x() >= g1 - gutterHalfW && r.x() <= g1 + gutterHalfW)
                            || (r.x() >= g2 - gutterHalfW && r.x() <= g2 + gutterHalfW);
            if (inGutter) gutterYs.add(r.y());
        }
        if (gutterYs.size() < 2) return -1;
        Collections.sort(gutterYs);

        // Estimate line spacing from gutter y-gaps.
        // Character heights (x-height) underestimate the actual line rhythm, so we
        // derive it from the minimum non-trivial gap between consecutive distinct
        // gutter y-values instead.  A floor of 8pt guards against pathological cases.
        float lineH;
        {
            List<Float> dedupYs = new ArrayList<>();
            float prevY = Float.MIN_VALUE;
            for (float y : gutterYs) {
                if (y - prevY > 1.5f) { dedupYs.add(y); prevY = y; }
            }
            List<Float> yGaps = new ArrayList<>();
            for (int i = 1; i < dedupYs.size(); i++) {
                float gap = dedupYs.get(i) - dedupYs.get(i - 1);
                if (gap > 3f) yGaps.add(gap);
            }
            if (!yGaps.isEmpty()) Collections.sort(yGaps);
            // Use the median gap, not the minimum: a few spurious small gaps (e.g. from
            // baseline-shifted chars) can drag the minimum down and cause false positives.
            lineH = yGaps.isEmpty() ? 12f : yGaps.get(yGaps.size() / 2);
            lineH = Math.max(lineH, 8f);
        }

        // Find the end of the first (topmost) cluster of gutter chars.
        // Use 1.5× lineH so that paragraph breaks (≈2× lineH) stop the cluster.
        // This gives us just the heading lines, not the whole body.
        float clusterEnd = gutterYs.get(0);
        int nextIdx = 1;
        while (nextIdx < gutterYs.size() && gutterYs.get(nextIdx) <= clusterEnd + lineH * 1.5f) {
            clusterEnd = gutterYs.get(nextIdx++);
        }

        // Check for a significant gap after the first cluster.
        // A true heading-to-body gap is ≥ 3× lineH (much larger than a paragraph break).
        // If all gutter chars belong to one cluster (no gap), this is pure body text.
        if (nextIdx >= gutterYs.size()) return -1;
        float gapToNextCluster = gutterYs.get(nextIdx) - clusterEnd;
        if (gapToNextCluster < lineH * 3) return -1;

        // The first cluster is an isolated heading.  Return its bottom + one line height.
        return clusterEnd + lineH;
    }

    // -------------------------------------------------------------------------
    // Gap detection
    // -------------------------------------------------------------------------

    /**
     * Returns the midpoint of the single best gap (closest to scan-range center)
     * with ≥ minGapWidth consecutive bins ≤ maxBinCount.  Returns -1 if none found.
     */
    private float findBestGap(int[] hist, float pageWidth,
                               float scanStartRatio, float scanEndRatio,
                               int maxBinCount, int minGapWidth) {
        List<Float> all = findAllGaps(hist, pageWidth, scanStartRatio, scanEndRatio, maxBinCount, minGapWidth);
        if (all.isEmpty()) return -1;
        float center = pageWidth * (scanStartRatio + scanEndRatio) / 2f;
        return all.stream().min(Comparator.comparingDouble(g -> Math.abs(g - center))).orElse(-1f);
    }

    /**
     * Returns midpoints of ALL qualifying gaps (left-to-right order) with
     * ≥ minGapWidth consecutive bins ≤ maxBinCount within the scan range.
     */
    private List<Float> findAllGaps(int[] hist, float pageWidth,
                                     float scanStartRatio, float scanEndRatio,
                                     int maxBinCount, int minGapWidth) {
        int scanStart = (int) (pageWidth * scanStartRatio);
        int scanEnd   = Math.min((int) (pageWidth * scanEndRatio), hist.length - 1);

        List<Float> gaps = new ArrayList<>();
        int gapStart = -1;
        for (int x = scanStart; x <= scanEnd; x++) {
            if (hist[x] <= maxBinCount) {
                if (gapStart < 0) gapStart = x;
            } else {
                if (gapStart >= 0) {
                    int gapEnd = x - 1;
                    if (gapEnd - gapStart + 1 >= minGapWidth)
                        gaps.add((gapStart + gapEnd) / 2.0f);
                    gapStart = -1;
                }
            }
        }
        if (gapStart >= 0) {
            int gapEnd = scanEnd;
            if (gapEnd - gapStart + 1 >= minGapWidth)
                gaps.add((gapStart + gapEnd) / 2.0f);
        }
        return gaps;
    }

    // -------------------------------------------------------------------------
    // Extraction helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts text from a page using its CropBox as the extraction region.
     * This bypasses all column-detection logic and is intended for pages that
     * have already been pre-split into single-column sub-pages (e.g. by the
     * layout phase).  If the CropBox equals the MediaBox the extraction falls
     * back to a plain full-page strip.
     */
    /**
     * Extracts text strictly within the page's CropBox bounds.
     *
     * PDFBox transforms text positions into display space relative to the CropBox
     * origin: (0,0) is top-left of the CropBox, x increases right, y increases down.
     * So valid text is in [0, cropBox.getWidth()] × [0, cropBox.getHeight()].
     * Comparing against the absolute PDF user-space coordinates (getLowerLeftX/Y etc.)
     * mixes coordinate systems and allows adjacent-column text to leak through.
     */
    public String extractPageByCropBox(PDDocument doc, int zeroBasedPageIndex) throws IOException {
        PDPage page         = doc.getPage(zeroBasedPageIndex);
        PDRectangle cropBox = page.getCropBox();
        float cropW = cropBox.getWidth();
        float cropH = cropBox.getHeight();

        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition text) {
                float x = text.getX();
                float y = text.getY();
                if (x >= 0 && x <= cropW && y >= 0 && y <= cropH) {
                    super.processTextPosition(text);
                }
            }
            @Override
            protected void writeString(String text, List<TextPosition> textPositions)
                    throws IOException {
                if (textPositions == null || textPositions.isEmpty()) {
                    super.writeString(text, textPositions);
                    return;
                }
                String fontName = textPositions.get(0).getFont().getName();
                boolean bold   = isBoldFont(fontName);
                boolean italic = isItalicFont(fontName);
                if (bold && italic) {
                    writeString("***" + text + "***");
                } else if (bold) {
                    writeString("**" + text + "**");
                } else if (italic) {
                    writeString("*" + text + "*");
                } else {
                    super.writeString(text, textPositions);
                }
            }
            @Override
            protected void writeParagraphSeparator() throws IOException {
                writeString("\n");
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(zeroBasedPageIndex + 1);
        stripper.setEndPage(zeroBasedPageIndex + 1);
        StringWriter sw = new StringWriter();
        stripper.writeText(doc, sw);
        return sw.toString();
    }

    private static boolean isBoldFont(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("bold") || n.contains("black") || n.contains("heavy") || n.contains("demi");
    }

    private static boolean isItalicFont(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("italic") || n.contains("oblique");
    }

    private String extractSingleColumnPage(PDDocument doc, int zeroBasedPageIndex) throws IOException {
        // Use PDFTextStripperByArea so we respect the page's cropBox.
        // A raw PDFTextStripper reads the full content stream — text that has been
        // visually cropped out (e.g. footer page numbers in layout sub-pages) still
        // appears in the output.  Clipping to the cropBox prevents that bleed.
        PDPage page = doc.getPage(zeroBasedPageIndex);
        PDRectangle crop = page.getCropBox();
        return extractRegion(page, 0, 0, crop.getWidth(), crop.getHeight());
    }

    /**
     * Extracts text from a column region with two enhancements over plain extractRegion:
     *
     * 1. Paragraph spacing — detects y-gaps between text lines that exceed 1.5× the
     *    median line spacing and inserts a blank line between those paragraph blocks.
     *
     * 2. Boxed text marking — if a detected rectangle (read-aloud box) overlaps this
     *    column region, the text inside the box is prefixed with "> " on every line
     *    so that Phase 2 can classify it as read_aloud.
     *
     * Falls back to a single extractRegion call when there is no paragraph spacing
     * or box overlap to handle.
     */
    private String extractColumnText(PDPage page, List<TextRun> allRuns,
            float colX, float yStart, float colW, float yEnd) throws IOException {

        float colXEnd = colX + colW;

        // ── Step 1: collect distinct text-line y-positions within this column ──────
        TreeSet<Float> rawYs = new TreeSet<>();
        for (TextRun r : allRuns) {
            if (r.x() >= colX - 5 && r.x() <= colXEnd + 5
                    && r.y() >= yStart - 1 && r.y() <= yEnd + 1) {
                rawYs.add(Math.round(r.y() * 2) / 2.0f); // round to 0.5pt
            }
        }
        List<Float> lineYs = new ArrayList<>();
        Float prev = null;
        for (float y : rawYs) {
            if (prev == null || y - prev > 1f) { lineYs.add(y); prev = y; }
        }

        // ── Step 2: detect paragraph break y-positions ────────────────────────────
        List<Float> breakYs = Collections.emptyList();
        if (lineYs.size() >= 2) {
            List<Float> gaps = new ArrayList<>();
            for (int i = 1; i < lineYs.size(); i++)
                gaps.add(lineYs.get(i) - lineYs.get(i - 1));
            Collections.sort(gaps);
            float lineH = gaps.get(gaps.size() / 2); // median line spacing
            float threshold = lineH * 1.5f;
            breakYs = new ArrayList<>();
            for (int i = 1; i < lineYs.size(); i++) {
                float g = lineYs.get(i) - lineYs.get(i - 1);
                if (g > threshold)
                    breakYs.add((lineYs.get(i - 1) + lineYs.get(i)) / 2f);
            }
        }

        // ── Step 3: collect box regions that overlap this column ──────────────────
        List<Rectangle2D> colBoxes = new ArrayList<>();
        for (Rectangle2D box : pageBoxes) {
            if (box.getX() < colXEnd && box.getMaxX() > colX
                    && box.getY() < yEnd && box.getMaxY() > yStart) {
                colBoxes.add(box);
            }
        }
        colBoxes.sort(Comparator.comparingDouble(Rectangle2D::getY));

        // Fast path: no paragraph breaks AND no box regions
        if (breakYs.isEmpty() && colBoxes.isEmpty())
            return extractRegion(page, colX, yStart, colW, yEnd - yStart);

        // ── Step 4: build a list of segment boundaries (breaks + box edges) ───────
        TreeSet<Float> cuts = new TreeSet<>();
        cuts.add(yStart);
        cuts.add(yEnd);
        for (float by : breakYs) cuts.add(by);
        for (Rectangle2D box : colBoxes) {
            cuts.add((float) Math.max(box.getY(),    yStart));
            cuts.add((float) Math.min(box.getMaxY(), yEnd));
        }

        // ── Step 5: extract each segment, prefixing box segments with "> " ────────
        StringBuilder sb = new StringBuilder();
        Float segStart = null;
        for (float cut : cuts) {
            if (segStart == null) { segStart = cut; continue; }
            float segEnd = cut;
            if (segEnd <= segStart) { segStart = segEnd; continue; }

            final float ss = segStart, se = segEnd;
            boolean isBox = colBoxes.stream().anyMatch(b ->
                    b.getY() <= ss + 0.5 && b.getMaxY() >= se - 0.5);

            String segText = extractRegion(page, colX, segStart, colW, segEnd - segStart);

            if (!segText.isBlank()) {
                if (isBox) {
                    appendBoxSegment(sb, segText);
                } else {
                    sb.append(segText.stripTrailing()).append("\n");
                    // If this segment ends at a paragraph break, add a blank line
                    if (breakYs.contains(segEnd))
                        sb.append("\n");
                }
            }
            segStart = segEnd;
        }
        return sb.toString();
    }

    // Encounter/room heading: "1. Name", "1a. Name", "42B. Name" etc.
    private static final java.util.regex.Pattern ENCOUNTER_HEADING_LINE =
            java.util.regex.Pattern.compile("^\\d+[A-Za-z]?\\.\\s+[A-Z].*");

    /**
     * Appends a box segment to the output StringBuilder.
     *
     * Two classes of leading lines are emitted as plain text rather than blockquote:
     *
     *  1. Lines that begin with a lowercase letter — sentence continuations that spilled
     *     over the box boundary from preceding non-box text (e.g. "further sense of
     *     uncertainty..." wrapping into the box region).
     *
     *  2. Encounter/room headings ("1. Solace Township", "3. Solace East Woods") that sit
     *     at the very top of the detected box but visually appear as a label before the
     *     box.  These match the pattern "N[a]. Name".
     *
     * The first uppercase line that is NOT a heading starts the blockquote region.
     */
    private static void appendBoxSegment(StringBuilder sb, String segText) {
        String[] lines = segText.split("\n", -1);

        // Find the index of the first line that is real box content:
        //   uppercase AND not an encounter heading.
        int boxStart = lines.length; // default: nothing qualifies → all plain
        for (int li = 0; li < lines.length; li++) {
            String t = lines[li].trim();
            if (t.isEmpty()) continue;
            char c = t.charAt(0);
            if (Character.isUpperCase(c) && !ENCOUNTER_HEADING_LINE.matcher(t).matches()) {
                boxStart = li;
                break;
            }
            // Digit-starting lines (encounter headings) and lowercase lines are "before the box"
        }

        // Emit all pre-box lines (continuations + headings) as plain text
        for (int li = 0; li < boxStart; li++) {
            String line = lines[li];
            if (!line.isBlank()) sb.append(line).append("\n");
        }

        // Emit box content with "> " prefix
        boolean hadBox = false;
        for (int li = boxStart; li < lines.length; li++) {
            String line = lines[li];
            if (!line.isBlank()) { sb.append("> ").append(line).append("\n"); hadBox = true; }
        }
        if (hadBox) sb.append("\n");
    }

    private String extractRegion(PDPage page, float x, float y, float w, float h) throws IOException {
        x = Math.max(0, x);
        y = Math.max(0, y);
        w = Math.max(1, w);
        h = Math.max(1, h);

        PDFTextStripperByArea byArea = new PDFTextStripperByArea();
        byArea.setSortByPosition(true);
        byArea.addRegion("r", new Rectangle2D.Float(x, y, w, h));
        byArea.extractRegions(page);
        return byArea.getTextForRegion("r");
    }

    private List<TextRun> captureRuns(PDDocument doc, int zeroBasedPageIndex) throws IOException {
        PositionCapturingStripper stripper = new PositionCapturingStripper();
        stripper.setStartPage(zeroBasedPageIndex + 1);
        stripper.setEndPage(zeroBasedPageIndex + 1);
        stripper.writeText(doc, new StringWriter());
        stripper.reconstructBoxesFromBorders();
        pageBoxes = stripper.getBoxes();
        return stripper.getRuns();
    }

    private float detectLineHeight(List<TextRun> runs) {
        List<Float> heights = new ArrayList<>();
        for (TextRun r : runs) {
            if (r.height() > 2 && r.height() < 30) heights.add(r.height());
        }
        if (heights.isEmpty()) return 12f;
        Collections.sort(heights);
        return heights.get(heights.size() / 2);
    }

    // -------------------------------------------------------------------------
    // Post-processing
    // -------------------------------------------------------------------------

    String postProcess(String text) {
        String result = fixWin1252(text);
        result = HYPHEN_BREAK.matcher(result).replaceAll("$1$2");
        result = PAGE_NUMBER_LINE.matcher(result).replaceAll("");
        result = TRIPLE_BLANK.matcher(result).replaceAll("\n\n");
        result = collapseConsecutiveDuplicates(result);
        return result;
    }

    /**
     * Converts Windows-1252 C1 control characters (U+0080–U+009F) to their
     * proper Unicode equivalents.  PDFBox sometimes returns these raw bytes
     * instead of the correct Unicode code points for curly quotes, dashes, etc.
     */
    private static String fixWin1252(String text) {
        if (text == null) return null;
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char fixed = win1252Char(c);
            if (fixed != c) {
                if (sb == null) sb = new StringBuilder(text.substring(0, i));
                sb.append(fixed);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : text;
    }

    private static char win1252Char(char c) {
        switch (c) {
            case '\u0080': return '\u20AC'; // €
            case '\u0082': return '\u201A'; // ‚
            case '\u0083': return '\u0192'; // ƒ
            case '\u0084': return '\u201E'; // „
            case '\u0085': return '\u2026'; // …
            case '\u0086': return '\u2020'; // †
            case '\u0087': return '\u2021'; // ‡
            case '\u0088': return '\u02C6'; // ˆ
            case '\u0089': return '\u2030'; // ‰
            case '\u008A': return '\u0160'; // Š
            case '\u008B': return '\u2039'; // ‹
            case '\u008C': return '\u0152'; // Œ
            case '\u008E': return '\u017D'; // Ž
            case '\u0091': return '\u2018'; // '
            case '\u0092': return '\u2019'; // '
            case '\u0093': return '\u201C'; // "
            case '\u0094': return '\u201D'; // "
            case '\u0095': return '\u2022'; // •
            case '\u0096': return '\u2013'; // –
            case '\u0097': return '\u2014'; // —
            case '\u0098': return '\u02DC'; // ˜
            case '\u0099': return '\u2122'; // ™
            case '\u009A': return '\u0161'; // š
            case '\u009B': return '\u203A'; // ›
            case '\u009C': return '\u0153'; // œ
            case '\u009E': return '\u017E'; // ž
            case '\u009F': return '\u0178'; // Ÿ
            default:       return c;
        }
    }

    private String collapseConsecutiveDuplicates(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                sb.append(line).append("\n");
                prev = null;
            } else if (!trimmed.equals(prev)) {
                sb.append(line).append("\n");
                prev = trimmed;
            }
        }
        return sb.toString();
    }
}
