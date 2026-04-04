package com.dnd.processor.converters;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects tabular regions in a rasterised page image.
 *
 * Pass 1  — Build H-line and V-line segment lists from the ink map.
 * Pass 2  — For each H-line that has matching V-lines on both sides (a box border):
 *             classify interior as dense prose (skip) or organised table (detect).
 * Pass 3  — For H-lines NOT enclosed by a box: require a title above, then confirm
 *             a regular table body below.
 * Pass 4  — Title-anchored implicit detection: for every isolated title candidate
 *             on the page, examine the block below for sub-column gutters (implicit
 *             table) or a numbered integer sequence (die-roll table).
 *             Sub-column search is restricted to the title's own x-extent and
 *             excludes known page-level column gutters so body text is not mistaken
 *             for a table.
 */
public class TableDetector {

    // ── Tuning constants ──────────────────────────────────────────────────────

    private static final int   INK_THRESHOLD                   = 200;
    private static final int   MIN_INK_FOR_CONTENT             = 3;

    // Pass 1 – H-line detection
    private static final float H_LINE_MIN_SPAN_FRACTION        = 0.50f;
    private static final int   H_LINE_MAX_HEIGHT_PX            = 4;

    // Pass 1 – V-line detection
    private static final int   V_LINE_MIN_LENGTH_PX            = 40;
    private static final int   V_LINE_MAX_WIDTH_PX             = 4;
    private static final int   V_LINE_SEARCH_MARGIN            = 15; // px outside content margins

    // Pass 2 – box classification
    private static final int   BOX_BORDER_SLOP_PX              = 12;
    private static final float PROSE_GAP_RATIO_MAX             = 0.25f;
    private static final int   BOX_MIN_SUB_COLUMNS_FOR_TABLE   = 3;

    // Pass 3 / body analysis
    private static final int   MIN_TABLE_ROWS                  = 3;
    private static final float ROW_REGULARITY_THRESHOLD        = 0.60f;
    private static final float MIN_GAP_TO_ROW_RATIO            = 0.20f;
    private static final int   TABLE_END_GAP_PX                = 20;

    // Title detection
    private static final int   TITLE_MAX_HEIGHT_PX             = 40;
    private static final int   TITLE_LEAD_GAP_PX               = 8;
    private static final int   TITLE_SEARCH_RANGE_PX           = 100;

    // Pass 4 – implicit / die-roll
    private static final int   MIN_GUTTER_WIDTH_PX             = 8;
    private static final float MIN_SUB_COL_FRACTION            = 0.10f;
    private static final int   MIN_DIE_TABLE_ENTRIES           = 6;
    private static final float DIE_TABLE_NUMBER_STRIP_FRACTION = 0.15f;

    // Array-field indices (avoids tiny record classes)
    // HLine: {yTop, yBot, xLeft, xRight}
    private static final int H_YTOP = 0, H_YBOT = 1, H_XLEFT = 2, H_XRIGHT = 3;
    // VLine: {xLeft, xRight, yTop, yBot}
    private static final int V_XLEFT = 0, V_XRIGHT = 1, V_YTOP = 2, V_YBOT = 3;

    // ── Public API ────────────────────────────────────────────────────────────

    public List<TableRegion> detect(BufferedImage src,
                                     ProjectionAnalyzer.PageLayout layout,
                                     PDDocument doc, int pageIndex, float dpi) {
        int w = src.getWidth();
        int h = src.getHeight();
        ProjectionAnalyzer.Margins m = layout.margins();
        int contentWidth = m.right() - m.left();

        boolean[] ink    = buildInkMap(src, w, h);
        int[] horizProj  = horizontalProjection(ink, w, h, m.left(), m.right());

        // Pass 1: line segments
        List<int[]> hLines = findHLines(ink, horizProj, w, m, contentWidth);
        List<int[]> vLines = findVLines(ink, w, h, m);

        // Page column gutter positions (excluded from implicit sub-column checks)
        List<int[]> pageGutters = getPageGutters(layout);

        List<TableRegion> results = new ArrayList<>();

        // Passes 2 & 3: process every H-line
        for (int[] hLine : hLines) {
            if (isBoxBorder(hLine, vLines)) {
                // Pass 2: bordered region
                if (isTableInBox(ink, w, horizProj, hLine, m)) {
                    int[] title = findTitleAbove(horizProj, hLine[H_YTOP], m.top());
                    int[] body  = findTableBody(horizProj, hLine[H_YBOT], m.bottom());
                    if (body != null) {
                        results.add(makeRegion(m, title, hLine, body,
                                TableRegion.TableStyle.RULE_BASED));
                    }
                }
                // else: prose box (read-aloud / sidebar) — skip
            } else {
                // Pass 3: unboxed H-line — must have title above
                int[] title = findTitleAbove(horizProj, hLine[H_YTOP], m.top());
                if (title == null) continue;          // decorative divider — skip
                int[] body = findTableBody(horizProj, hLine[H_YBOT], m.bottom());
                if (body == null) continue;
                results.add(makeRegion(m, title, hLine, body,
                        TableRegion.TableStyle.RULE_BASED));
            }
        }

        // Pass 4: title-anchored implicit detection
        for (int[] title : findTitleCandidates(horizProj, m, results)) {
            if (overlapsAny(title[0], title[1], results)) continue;

            // Content block immediately below this title
            int blockStart = skipGap(horizProj, title[1], m.bottom());
            if (blockStart >= m.bottom()) continue;
            int blockEnd = findRegionEnd(horizProj, blockStart, m.bottom(), TABLE_END_GAP_PX);
            if (overlapsAny(title[0], blockEnd, results)) continue;

            // Restrict gutter search to title's horizontal ink extent
            int[] titleX = getTitleXBounds(ink, w, title[0], title[1], m);

            // Sub-case A: column-aligned implicit table
            if (hasSubColumnGutter(ink, w, titleX[0], titleX[1],
                    blockStart, blockEnd, pageGutters)) {
                List<int[]> clusters = findClusters(horizProj, blockStart, blockEnd, TABLE_END_GAP_PX);
                if (clusters.size() >= MIN_TABLE_ROWS) {
                    List<Integer> gaps = buildGaps(clusters);
                    if (meetsGapToRowRatio(gaps, clusters)
                            && isRegular(gaps, ROW_REGULARITY_THRESHOLD)) {
                        results.add(new TableRegion(
                                m.left(), m.right(), title[0], blockEnd,
                                -1, -1, title[0], title[1],
                                TableRegion.TableStyle.COLUMN_IMPLICIT,
                                TableType.UNKNOWN_TABLE, ""));
                        continue;
                    }
                }
            }

            // Sub-case B: die-roll / numbered list
            if (isDieRollBody(doc, pageIndex, w, h, dpi,
                    m.left(), m.right(), blockStart, blockEnd)) {
                results.add(new TableRegion(
                        m.left(), m.right(), title[0], blockEnd,
                        -1, -1, title[0], title[1],
                        TableRegion.TableStyle.DIE_ROLL,
                        TableType.UNKNOWN_TABLE, ""));
            }
        }

        results.sort((a, b) -> Integer.compare(a.yTop(), b.yTop()));
        return results;
    }

    // ── Pass 1: line segment detection ───────────────────────────────────────

    private List<int[]> findHLines(boolean[] ink, int[] horizProj, int w,
                                    ProjectionAnalyzer.Margins m, int contentWidth) {
        int threshold = (int)(contentWidth * H_LINE_MIN_SPAN_FRACTION);
        List<int[]> lines = new ArrayList<>();
        int start = -1, runXLeft = m.left(), runXRight = m.right();

        for (int y = m.top(); y < m.bottom(); y++) {
            int[] run = longestRunInRow(ink, w, y, m.left(), m.right());
            boolean isHRow = horizProj[y] >= MIN_INK_FOR_CONTENT && run[0] >= threshold;
            if (isHRow) {
                if (start < 0) { start = y; runXLeft = run[1]; runXRight = run[2]; }
                else { runXLeft = Math.min(runXLeft, run[1]); runXRight = Math.max(runXRight, run[2]); }
            } else {
                if (start >= 0) {
                    if (y - start <= H_LINE_MAX_HEIGHT_PX)
                        lines.add(new int[]{start, y, runXLeft, runXRight});
                    start = -1;
                }
            }
        }
        if (start >= 0 && m.bottom() - start <= H_LINE_MAX_HEIGHT_PX)
            lines.add(new int[]{start, m.bottom(), runXLeft, runXRight});
        return lines;
    }

    private List<int[]> findVLines(boolean[] ink, int w, int h,
                                    ProjectionAnalyzer.Margins m) {
        int xFrom = Math.max(0, m.left() - V_LINE_SEARCH_MARGIN);
        int xTo   = Math.min(w, m.right() + V_LINE_SEARCH_MARGIN);
        List<int[]> lines = new ArrayList<>();
        int start = -1, runYTop = m.top(), runYBot = m.bottom();

        for (int x = xFrom; x < xTo; x++) {
            int[] run = longestRunInColumn(ink, w, x, m.top(), m.bottom());
            boolean isVCol = run[0] >= V_LINE_MIN_LENGTH_PX;
            if (isVCol) {
                if (start < 0) { start = x; runYTop = run[1]; runYBot = run[2]; }
                else { runYTop = Math.min(runYTop, run[1]); runYBot = Math.max(runYBot, run[2]); }
            } else {
                if (start >= 0) {
                    if (x - start <= V_LINE_MAX_WIDTH_PX)
                        lines.add(new int[]{start, x, runYTop, runYBot});
                    start = -1;
                }
            }
        }
        if (start >= 0 && (xTo - start) <= V_LINE_MAX_WIDTH_PX)
            lines.add(new int[]{start, xTo, runYTop, runYBot});
        return lines;
    }

    // ── Pass 2: box border check and classification ───────────────────────────

    /** Returns true if hLine has matching V-lines on both its left and right edges. */
    private boolean isBoxBorder(int[] hLine, List<int[]> vLines) {
        int leftEdge  = hLine[H_XLEFT];
        int rightEdge = hLine[H_XRIGHT];
        int yMid      = (hLine[H_YTOP] + hLine[H_YBOT]) / 2;
        boolean hasLeft = false, hasRight = false;
        for (int[] v : vLines) {
            int vXMid = (v[V_XLEFT] + v[V_XRIGHT]) / 2;
            boolean spansY = v[V_YTOP] <= yMid + BOX_BORDER_SLOP_PX
                          && v[V_YBOT]  >= yMid - BOX_BORDER_SLOP_PX;
            if (!spansY) continue;
            if (Math.abs(vXMid - leftEdge)  <= BOX_BORDER_SLOP_PX) hasLeft  = true;
            if (Math.abs(vXMid - rightEdge) <= BOX_BORDER_SLOP_PX) hasRight = true;
        }
        return hasLeft && hasRight;
    }

    /**
     * Returns true if the content inside this box is organised (≥3 sub-columns,
     * or gap/row ratio above the prose threshold) rather than dense flowing prose.
     */
    private boolean isTableInBox(boolean[] ink, int imgW, int[] horizProj, int[] hLine,
                                   ProjectionAnalyzer.Margins m) {
        int xLeft  = hLine[H_XLEFT]  + V_LINE_MAX_WIDTH_PX;
        int xRight = hLine[H_XRIGHT] - V_LINE_MAX_WIDTH_PX;
        int yFrom  = hLine[H_YBOT];
        int yTo    = findRegionEnd(horizProj, yFrom, m.bottom(), TABLE_END_GAP_PX * 3);

        if (countSubColumnsW(ink, imgW, xLeft, xRight, yFrom, yTo)
                >= BOX_MIN_SUB_COLUMNS_FOR_TABLE) {
            return true;
        }
        List<int[]> clusters = findClusters(horizProj, yFrom, yTo, TABLE_END_GAP_PX);
        if (clusters.size() < 2) return false;
        List<Integer> gaps = buildGaps(clusters);
        double meanGap = gaps.stream().mapToInt(i -> i).average().orElse(0);
        double meanRow = clusters.stream().mapToInt(c -> c[1] - c[0]).average().orElse(0);
        return meanRow > 0 && (meanGap / meanRow) > PROSE_GAP_RATIO_MAX;
    }

    // ── Title detection ───────────────────────────────────────────────────────

    /**
     * Scans upward from {@code tableTop} within {@code TITLE_SEARCH_RANGE_PX} and
     * returns the topmost short ink cluster (height ≤ TITLE_MAX_HEIGHT_PX) found.
     * Returns null if none found (likely a decorative divider with no label above).
     */
    private int[] findTitleAbove(int[] horizProj, int tableTop, int contentTop) {
        int limit = Math.max(contentTop, tableTop - TITLE_SEARCH_RANGE_PX);
        int[] topCluster = null;
        int y = tableTop - 1;
        while (y > limit) {
            while (y > limit && horizProj[y] < MIN_INK_FOR_CONTENT) y--;
            if (y <= limit) break;
            int cBot = y;
            int cTop = y;
            while (cTop > limit && horizProj[cTop] >= MIN_INK_FOR_CONTENT) cTop--;
            cTop++;
            int height = cBot - cTop + 1;
            if (height <= TITLE_MAX_HEIGHT_PX) topCluster = new int[]{cTop, cBot + 1};
            y = cTop - 1;
        }
        return topCluster;
    }

    /**
     * Finds isolated short ink clusters that are title candidates for Pass 4.
     * A candidate must have ≥ TITLE_LEAD_GAP_PX of blank space above it.
     */
    private List<int[]> findTitleCandidates(int[] horizProj,
                                              ProjectionAnalyzer.Margins m,
                                              List<TableRegion> already) {
        List<int[]> candidates = new ArrayList<>();
        int y = m.top();
        while (y < m.bottom()) {
            int gapStart = y;
            while (y < m.bottom() && horizProj[y] < MIN_INK_FOR_CONTENT) y++;
            int gap = y - gapStart;
            if (y >= m.bottom()) break;

            int cTop = y;
            while (y < m.bottom() && horizProj[y] >= MIN_INK_FOR_CONTENT) y++;
            int cBot = y;

            if ((cBot - cTop) <= TITLE_MAX_HEIGHT_PX
                    && gap >= TITLE_LEAD_GAP_PX
                    && !overlapsAny(cTop, cBot, already)) {
                candidates.add(new int[]{cTop, cBot});
            }
        }
        return candidates;
    }

    // ── Pass 3: table body analysis ───────────────────────────────────────────

    private int[] findTableBody(int[] horizProj, int from, int contentBottom) {
        List<int[]> clusters = findClusters(horizProj, from, contentBottom, TABLE_END_GAP_PX);
        if (clusters.size() < MIN_TABLE_ROWS) return null;
        List<Integer> gaps = buildGaps(clusters);
        if (!isRegular(gaps, ROW_REGULARITY_THRESHOLD)) return null;
        if (!meetsGapToRowRatio(gaps, clusters)) return null;
        return new int[]{clusters.get(0)[0], clusters.get(clusters.size() - 1)[1]};
    }

    // ── Pass 4: implicit sub-column and die-roll detection ────────────────────

    /**
     * Returns true if the ink band [xFrom,xTo) × [yFrom,yTo) contains a vertical
     * gutter that is not one of the page's own column gutters.
     */
    private boolean hasSubColumnGutter(boolean[] ink, int w,
                                        int xFrom, int xTo,
                                        int yFrom, int yTo,
                                        List<int[]> pageGutters) {
        if (xTo <= xFrom) return false;
        int rangeW = xTo - xFrom;
        int minCol = (int)(rangeW * MIN_SUB_COL_FRACTION);

        int[] colInk = new int[rangeW];
        for (int y = yFrom; y < yTo; y++) {
            int base = y * w;
            for (int x = xFrom; x < xTo; x++)
                if (ink[base + x]) colInk[x - xFrom]++;
        }

        int gutterStart = -1;
        for (int cx = 0; cx < rangeW; cx++) {
            if (colInk[cx] == 0) {
                if (gutterStart < 0) gutterStart = cx;
            } else {
                if (gutterStart >= 0) {
                    int gw = cx - gutterStart;
                    if (gw >= MIN_GUTTER_WIDTH_PX
                            && gutterStart >= minCol
                            && rangeW - cx >= minCol
                            && !matchesPageGutter(xFrom + gutterStart, xFrom + cx, pageGutters)) {
                        return true;
                    }
                    gutterStart = -1;
                }
            }
        }
        return false;
    }

    private boolean matchesPageGutter(int x1, int x2, List<int[]> pageGutters) {
        for (int[] pg : pageGutters) {
            if (x1 < pg[1] && x2 > pg[0]) return true; // any overlap
        }
        return false;
    }

    private int countSubColumnsW(boolean[] ink, int imgW, int xFrom, int xTo,
                                   int yFrom, int yTo) {
        if (xTo <= xFrom) return 1;
        int rangeW = xTo - xFrom;
        int minCol = (int)(rangeW * 0.08f);
        int[] colInk = new int[rangeW];
        for (int y = yFrom; y < yTo; y++) {
            int base = y * imgW;
            for (int x = xFrom; x < xTo; x++)
                if (ink[base + x]) colInk[x - xFrom]++;
        }
        int count = 1, gutterStart = -1;
        for (int cx = 0; cx < rangeW; cx++) {
            if (colInk[cx] == 0) { if (gutterStart < 0) gutterStart = cx; }
            else {
                if (gutterStart >= 0) {
                    if (cx - gutterStart >= MIN_GUTTER_WIDTH_PX
                            && gutterStart >= minCol && rangeW - cx >= minCol) count++;
                    gutterStart = -1;
                }
            }
        }
        return count;
    }

    private boolean isDieRollBody(PDDocument doc, int pageIndex,
                                   int imgW, int imgH, float dpi,
                                   int xLeft, int xRight,
                                   int yTop, int yBottom) {
        try {
            PDPage page = doc.getPage(pageIndex);
            PDRectangle mb = page.getMediaBox();
            float sx = mb.getWidth()  / imgW;
            float sy = mb.getHeight() / imgH;
            int stripW = (int)((xRight - xLeft) * DIE_TABLE_NUMBER_STRIP_FRACTION);
            float pdfL = mb.getLowerLeftX() + xLeft           * sx;
            float pdfR = mb.getLowerLeftX() + (xLeft + stripW) * sx;
            float pdfB = mb.getLowerLeftY() + (imgH - yBottom) * sy;
            float pdfT = mb.getLowerLeftY() + (imgH - yTop)    * sy;
            PDFTextStripperByArea s = new PDFTextStripperByArea();
            s.setSortByPosition(true);
            s.addRegion("n", new Rectangle2D.Float(pdfL, pdfB, pdfR - pdfL, pdfT - pdfB));
            s.extractRegions(page);
            return countConsecutiveIntegers(s.getTextForRegion("n")) >= MIN_DIE_TABLE_ENTRIES;
        } catch (IOException e) {
            return false;
        }
    }

    private static final Pattern DIE_ENTRY = Pattern.compile("^(\\d{1,2})[.)F]?\\s*");

    private int countConsecutiveIntegers(String text) {
        int expected = 1;
        for (String line : text.split("\\R")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            Matcher mat = DIE_ENTRY.matcher(line);
            if (mat.find()) {
                int n = Integer.parseInt(mat.group(1));
                if (n == expected) expected++;
                else if (n > expected) expected = n + 1;
            }
        }
        return expected - 1;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns the x-extent [xLeft, xRight] of ink in the title rows. */
    private int[] getTitleXBounds(boolean[] ink, int w, int yTop, int yBot,
                                   ProjectionAnalyzer.Margins m) {
        int xl = m.right(), xr = m.left();
        for (int y = yTop; y < yBot; y++) {
            int base = y * w;
            for (int x = m.left(); x < m.right(); x++) {
                if (ink[base + x]) { if (x < xl) xl = x; if (x > xr) xr = x; }
            }
        }
        if (xl >= xr) return new int[]{m.left(), m.right()};
        int pad = Math.max(20, (xr - xl) / 4);
        return new int[]{Math.max(m.left(), xl - pad), Math.min(m.right(), xr + pad)};
    }

    /** Page column gutter x-ranges derived from the zone/column structure. */
    private List<int[]> getPageGutters(ProjectionAnalyzer.PageLayout layout) {
        List<int[]> gutters = new ArrayList<>();
        for (ProjectionAnalyzer.Zone z : layout.zones()) {
            if (z.type() == ProjectionAnalyzer.ZoneType.TEXT) {
                List<ProjectionAnalyzer.Column> cols = z.columns();
                for (int i = 0; i < cols.size() - 1; i++)
                    gutters.add(new int[]{cols.get(i).xRight(), cols.get(i + 1).xLeft()});
            }
        }
        return gutters;
    }

    /** Skip blank rows starting at {@code y}, return y of first ink row. */
    private int skipGap(int[] horizProj, int y, int limit) {
        while (y < limit && horizProj[y] < MIN_INK_FOR_CONTENT) y++;
        return y;
    }

    /** Find the end of a contiguous ink region, stopping at a gap ≥ maxGap. */
    private int findRegionEnd(int[] horizProj, int yFrom, int yLimit, int maxGap) {
        int gapStart = -1;
        for (int y = yFrom; y < yLimit; y++) {
            if (horizProj[y] >= MIN_INK_FOR_CONTENT) {
                gapStart = -1;
            } else {
                if (gapStart < 0) gapStart = y;
                else if (y - gapStart >= maxGap) return gapStart;
            }
        }
        return yLimit;
    }

    private List<int[]> findClusters(int[] horizProj, int yFrom, int yTo, int maxGap) {
        List<int[]> clusters = new ArrayList<>();
        int cStart = -1, gapStart = -1;
        for (int y = yFrom; y < yTo; y++) {
            if (horizProj[y] >= MIN_INK_FOR_CONTENT) {
                if (cStart < 0) cStart = y;
                gapStart = -1;
            } else {
                if (cStart >= 0) { clusters.add(new int[]{cStart, y}); cStart = -1; gapStart = y; }
                else if (gapStart >= 0 && y - gapStart >= maxGap) break;
            }
        }
        if (cStart >= 0) clusters.add(new int[]{cStart, yTo});
        return clusters;
    }

    private List<Integer> buildGaps(List<int[]> clusters) {
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < clusters.size(); i++)
            gaps.add(clusters.get(i)[0] - clusters.get(i - 1)[1]);
        return gaps;
    }

    private boolean isRegular(List<Integer> gaps, float threshold) {
        if (gaps.isEmpty()) return false;
        double mean = gaps.stream().mapToInt(i -> i).average().orElse(0);
        if (mean == 0) return false;
        double var = gaps.stream().mapToDouble(g -> (g - mean) * (g - mean)).average().orElse(0);
        return Math.sqrt(var) / mean <= threshold;
    }

    private boolean meetsGapToRowRatio(List<Integer> gaps, List<int[]> clusters) {
        if (gaps.isEmpty() || clusters.isEmpty()) return false;
        double meanGap = gaps.stream().mapToInt(i -> i).average().orElse(0);
        double meanRow = clusters.stream().mapToInt(c -> c[1] - c[0]).average().orElse(0);
        return meanRow > 0 && meanGap / meanRow >= MIN_GAP_TO_ROW_RATIO;
    }

    private boolean overlapsAny(int top, int bottom, List<TableRegion> regions) {
        for (TableRegion r : regions)
            if (top < r.yBottom() && bottom > r.yTop()) return true;
        return false;
    }

    /** Returns {longestRunLength, runXStart, runXEnd} for the given row. */
    private int[] longestRunInRow(boolean[] ink, int w, int y, int xFrom, int xTo) {
        int maxLen = 0, maxStart = xFrom, maxEnd = xFrom;
        int run = 0, rStart = xFrom;
        int base = y * w;
        for (int x = xFrom; x < xTo; x++) {
            if (ink[base + x]) {
                if (run == 0) rStart = x;
                if (++run > maxLen) { maxLen = run; maxStart = rStart; maxEnd = x + 1; }
            } else { run = 0; }
        }
        return new int[]{maxLen, maxStart, maxEnd};
    }

    /** Returns {longestRunLength, runYStart, runYEnd} for the given column. */
    private int[] longestRunInColumn(boolean[] ink, int w, int x, int yFrom, int yTo) {
        int maxLen = 0, maxStart = yFrom, maxEnd = yFrom;
        int run = 0, rStart = yFrom;
        for (int y = yFrom; y < yTo; y++) {
            if (ink[y * w + x]) {
                if (run == 0) rStart = y;
                if (++run > maxLen) { maxLen = run; maxStart = rStart; maxEnd = y + 1; }
            } else { run = 0; }
        }
        return new int[]{maxLen, maxStart, maxEnd};
    }

    private boolean[] buildInkMap(BufferedImage src, int w, int h) {
        boolean[] ink = new boolean[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int rgb = src.getRGB(x, y);
            int gray = (int)(0.299f * ((rgb >> 16) & 0xFF)
                           + 0.587f * ((rgb >>  8) & 0xFF)
                           + 0.114f * ( rgb        & 0xFF));
            ink[y * w + x] = gray < INK_THRESHOLD;
        }
        return ink;
    }

    private int[] horizontalProjection(boolean[] ink, int w, int h, int xFrom, int xTo) {
        int[] proj = new int[h];
        for (int y = 0; y < h; y++)
            for (int x = xFrom; x < xTo; x++)
                if (ink[y * w + x]) proj[y]++;
        return proj;
    }

    /** Convenience builder for a TableRegion from detect-time components. */
    private TableRegion makeRegion(ProjectionAnalyzer.Margins m, int[] title,
                                    int[] hLine, int[] body,
                                    TableRegion.TableStyle style) {
        int tableTop = (title != null) ? title[0] : (hLine != null ? hLine[H_YTOP] : body[0]);
        return new TableRegion(
                m.left(), m.right(), tableTop, body[1],
                hLine != null ? hLine[H_YTOP] : -1,
                hLine != null ? hLine[H_YBOT] : -1,
                title != null ? title[0] : -1,
                title != null ? title[1] : -1,
                style, TableType.UNKNOWN_TABLE, "");
    }
}
