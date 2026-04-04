package com.dnd.processor.converters;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects white-space structure in a page image using projection profiles.
 *
 * Pass 1 — Margin detection (green rectangle):
 *   Scan each edge inward until significant ink is found.  The bottom margin
 *   uses an additional "footer isolation" step: if a small isolated ink cluster
 *   (page number) is found near the bottom with a clear gap above it, that
 *   cluster and the gap are absorbed into the bottom margin so they don't
 *   interfere with column gap detection.
 *
 * Pass 2 — Vertical gap detection (red rectangles), inside margins only:
 *   Sum dark pixels per x-column.  Runs of near-empty columns = column gutters.
 *
 * Pass 3 — Horizontal gap detection (blue rectangles), inside margins only:
 *   Sum dark pixels per y-row.  Runs of near-empty rows = blank lines / breaks.
 */
public class ProjectionAnalyzer {

    // ── Tuning constants (all pixel values assume 150 DPI) ────────────────────

    // A pixel is "ink" if its grayscale value is below this
    private static final int INK_THRESHOLD = 200;

    // A row/column qualifies as "empty" if its ink count ≤ this fraction of
    // the perpendicular dimension.  0.5 % tolerates stray pixels / noise.
    private static final float MAX_INK_FRACTION = 0.005f;

    // Minimum gap sizes inside the content area
    private static final int MIN_VERT_GAP_PX  = 10;   // column gutter  (~0.07 in)
    private static final int MIN_HORIZ_GAP_PX =  5;   // blank line gap (~0.03 in)

    // A vertical gap is only a column gutter if both sub-columns on either side
    // are at least this fraction of the total content width.
    private static final float MIN_COLUMN_FRACTION = 0.25f;

    // If one sub-column has more than this many times the ink density of the
    // other, the gap is an indent: the sparse side is just numbering / bullets.
    private static final float MAX_INK_DENSITY_RATIO = 5.0f;

    // Sparse-bridge merge: if the strip of text between two adjacent gaps has
    // ink density below this fraction of the sparser real column on either side,
    // it is treated as a label strip (section headings, bullets) and the two
    // gaps are merged into one wider gutter.
    private static final float MAX_BRIDGE_DENSITY_FRACTION = 0.3f;

    // Margin detection: a row/column must exceed this ink count to be "content"
    private static final int MIN_INK_FOR_CONTENT = 3;

    // Footer isolation: a cluster at the bottom with height ≤ this is treated
    // as a footer element and absorbed into the bottom margin.
    // 150 px (~1 in at 150 DPI) covers thick decorative borders in D&D Basic modules.
    private static final int FOOTER_CLUSTER_MAX_PX = 150;  // ~1.0 in

    // The gap between the bottom cluster and the next element above it must be at
    // least this many rows to confirm there really is a footer zone worth scanning.
    // This prevents treating normal inter-line paragraph spacing as a footer gap.
    private static final int FOOTER_FIRST_GAP_MIN_PX = 15;  // ~0.10 in at 150 DPI

    // When iterating upward through the footer zone, the gap between one footer
    // element (ornament, page number) and the next must be at least this large to
    // be accepted as the final content boundary.  Smaller gaps are treated as
    // inter-element spacing within the footer zone and absorbed.
    private static final int FOOTER_CONTENT_GAP_MIN_PX = 15; // ~0.10 in at 150 DPI

    // Each extra footer element (page number, ornamental rule) absorbed above the
    // initial cluster must be ≤ this height.  Taller clusters are real content.
    private static final int FOOTER_EXTRA_CLUSTER_MAX_PX = 35;  // ~0.23 in at 150 DPI
    private static final int FOOTER_EXTRA_PASSES = 3;

    // Clusters at or below this height are treated as noise/fragment rows (e.g.
    // descender tips, scattered pixels of the page number).  They are absorbed
    // unconditionally as long as any gap exists above them.  Taller clusters (real
    // page-number glyphs, ornamental rules) are only absorbed when a large gap
    // (≥ FOOTER_CONTENT_GAP_MIN_PX) confirms the content boundary lies above them.
    private static final int FOOTER_TINY_CLUSTER_MAX_PX = 5;

    // A horizontal gap must be at least this tall to qualify as a page-level
    // row divider (TWO_ROW / TWO_BY_TWO).  Ordinary section breaks between
    // paragraphs are typically 5–25 px; genuine row dividers are wider.
    private static final int MIN_ROW_SPLIT_PX = 20;         // ~0.13 in

    // TABLE detection: a page with this many horizontal gaps and no column gutters
    // is treated as a table rather than multi-row text.
    private static final int MIN_TABLE_HORIZ_GAPS = 10;

    // MAP detection: a page whose content area has ink density above this fraction
    // and very few horizontal gaps is treated as a map / full-page illustration.
    private static final float MIN_MAP_INK_FRACTION = 0.20f;
    private static final int   MAX_MAP_HORIZ_GAPS   = 3;
    // MAP may have at most this many vertical gaps (e.g. a legend/key column)
    private static final int   MAX_MAP_VERT_GAPS    = 1;

    // ── Public API ────────────────────────────────────────────────────────────

    /** Detected margin boundaries (pixel coordinates). */
    public record Margins(int top, int bottom, int left, int right) {}

    public enum LayoutType { SINGLE, MAP, TABLE, TWO_COLUMN, THREE_COLUMN, TWO_ROW, THREE_ROW, TWO_BY_TWO }

    /**
     * Layout classification result for one page image.
     *
     * @param type    detected layout
     * @param margins content-area boundaries (image pixels)
     * @param splitX  midpoint of 1st column gutter (-1 if unused)
     * @param splitX2 midpoint of 2nd column gutter (-1 if unused; THREE_COLUMN only)
     * @param splitY  midpoint of 1st row divider   (-1 if unused)
     * @param splitY2 midpoint of 2nd row divider   (-1 if unused; THREE_ROW only)
     * @param vertGaps  surviving vertical gaps  (image pixel coords)
     * @param horizGaps all horizontal gaps      (image pixel coords)
     */
    public record PageLayout(
            LayoutType type,
            Margins margins,
            int splitX,
            int splitX2,
            int splitY,
            int splitY2,
            List<int[]> vertGaps,
            List<int[]> horizGaps) {}

    /**
     * Analyses the image and returns a {@link PageLayout} describing its structure.
     * This is the primary API; {@link #analyze} wraps it to produce a debug image.
     */
    public PageLayout analyzeLayout(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();

        boolean[] ink       = buildInkMap(src, w, h);
        int[]     vertProj  = verticalProjection(ink, w, h);
        int[]     horizProj = horizontalProjection(ink, w, h);
        Margins   m         = detectMargins(vertProj, horizProj, w, h);

        // Recompute projections restricted to the content area so that headers,
        // footers, and page numbers don't add ink to the gap regions and skew
        // column-gutter / row-break detection.
        int[] vertProjContent  = verticalProjection(ink, w, h, m.top(),  m.bottom());
        int[] horizProjContent = horizontalProjection(ink, w, h, m.left(), m.right());

        int maxInkPerCol = Math.max(1, (int)((m.bottom() - m.top())  * MAX_INK_FRACTION));
        int maxInkPerRow = Math.max(1, (int)((m.right()  - m.left()) * MAX_INK_FRACTION));

        List<int[]> vertGaps = findGaps(vertProjContent, m.left(), m.right(), maxInkPerCol, MIN_VERT_GAP_PX);
        vertGaps = filterIndentGaps(vertGaps, m.left(), m.right(), vertProjContent);
        vertGaps = mergeSparseBridges(vertGaps, vertProjContent, m.left(), m.right());

        List<int[]> horizGaps = findGaps(horizProjContent, m.top(), m.bottom(), maxInkPerRow, MIN_HORIZ_GAP_PX);

        // ── Classify ──────────────────────────────────────────────────────────

        // ── Vertical split coordinates ────────────────────────────────────────
        int numVert = vertGaps.size();
        int sx1 = numVert >= 1 ? (vertGaps.get(0)[0] + vertGaps.get(0)[1]) / 2 : -1;
        int sx2 = numVert >= 2 ? (vertGaps.get(1)[0] + vertGaps.get(1)[1]) / 2 : -1;

        // ── Horizontal row-split candidates ──────────────────────────────────
        int contentHeight = m.bottom() - m.top();
        int minThird      = (int)(contentHeight * 0.20f);
        int minHalf       = (int)(contentHeight * 0.30f);

        List<int[]> rowSplits = new ArrayList<>();
        for (int[] gap : horizGaps)
            if (gap[1] - gap[0] >= MIN_ROW_SPLIT_PX) rowSplits.add(gap);

        int[] ry1 = null, ry2 = null;
        outer:
        for (int a = 0; a < rowSplits.size(); a++) {
            int[] g1 = rowSplits.get(a);
            if (g1[0] - m.top() < minThird) continue;
            if (m.bottom() - g1[1] >= minHalf) {
                ry1 = g1;
                for (int b = a + 1; b < rowSplits.size(); b++) {
                    int[] g2 = rowSplits.get(b);
                    if (g2[0] - g1[1] >= minThird && m.bottom() - g2[1] >= minThird) {
                        ry2 = g2;
                        break outer;
                    }
                }
            }
        }

        int sy1 = ry1 != null ? (ry1[0] + ry1[1]) / 2 : -1;
        int sy2 = ry2 != null ? (ry2[0] + ry2[1]) / 2 : -1;

        boolean hasCol  = numVert >= 1;
        boolean has2Col = numVert >= 2;
        boolean hasRow  = ry1 != null;
        boolean has2Row = ry2 != null;

        // ── Split-based classification (checked before MAP/TABLE) ─────────────
        if (has2Col && has2Row) return new PageLayout(LayoutType.TWO_BY_TWO,  m, sx1, sx2, sy1, sy2, vertGaps, horizGaps);
        if (has2Col)            return new PageLayout(LayoutType.THREE_COLUMN, m, sx1, sx2,  -1,  -1, vertGaps, horizGaps);
        if (has2Row)            return new PageLayout(LayoutType.THREE_ROW,    m,  -1,  -1, sy1, sy2, vertGaps, horizGaps);
        if (hasCol && hasRow)   return new PageLayout(LayoutType.TWO_BY_TWO,   m, sx1,  -1, sy1,  -1, vertGaps, horizGaps);
        if (hasCol)             return new PageLayout(LayoutType.TWO_COLUMN,   m, sx1,  -1,  -1,  -1, vertGaps, horizGaps);
        if (hasRow)             return new PageLayout(LayoutType.TWO_ROW,      m,  -1,  -1, sy1,  -1, vertGaps, horizGaps);

        // ── No qualifying splits — check for TABLE and MAP ────────────────────
        if (horizGaps.size() >= MIN_TABLE_HORIZ_GAPS)
            return new PageLayout(LayoutType.TABLE, m, -1, -1, -1, -1, vertGaps, horizGaps);

        long inkInContent = 0;
        for (int x = m.left(); x < m.right(); x++) inkInContent += vertProj[x];
        long contentArea = (long)(m.right() - m.left()) * (m.bottom() - m.top());
        if (contentArea > 0
                && horizGaps.size() <= MAX_MAP_HORIZ_GAPS
                && numVert <= MAX_MAP_VERT_GAPS
                && (float) inkInContent / contentArea >= MIN_MAP_INK_FRACTION) {
            return new PageLayout(LayoutType.MAP, m, -1, -1, -1, -1, vertGaps, horizGaps);
        }

        return new PageLayout(LayoutType.SINGLE, m, -1, -1, -1, -1, vertGaps, horizGaps);
    }

    /**
     * Analyses {@code src} and returns an annotated copy with:
     *   green  – content-area boundary (margin outline)
     *   red    – vertical white-space bands (column gutters) inside the margins
     *   blue   – horizontal white-space bands (blank rows) inside the margins
     */
    public BufferedImage analyze(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();

        PageLayout layout   = analyzeLayout(src);
        Margins    m        = layout.margins();

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        g2.drawImage(src, 0, 0, null);
        g2.setStroke(new BasicStroke(1f));

        // Green: content-area boundary
        g2.setColor(Color.GREEN);
        g2.drawRect(m.left(), m.top(), m.right() - m.left(), m.bottom() - m.top());

        // Red: vertical column gutters (height = content area only)
        g2.setColor(Color.RED);
        for (int[] gap : layout.vertGaps()) {
            g2.drawRect(gap[0], m.top(), gap[1] - gap[0], m.bottom() - m.top());
        }

        // Blue: horizontal blank-row bands (width = content area only)
        g2.setColor(Color.BLUE);
        for (int[] gap : layout.horizGaps()) {
            g2.drawRect(m.left(), gap[0], m.right() - m.left(), gap[1] - gap[0]);
        }

        g2.dispose();
        return out;
    }

    // ── Margin detection ──────────────────────────────────────────────────────

    public Margins detectMargins(int[] vertProj, int[] horizProj, int w, int h) {
        int top    = scanForwardMargin(horizProj, 0, h / 3);
        int left   = scanForwardMargin(vertProj,  0, w / 3);
        int right  = scanBackwardMargin(vertProj,  w - 1, w * 2 / 3);
        int bottom = detectBottomMargin(horizProj, h, top);
        return new Margins(top, bottom, left, right);
    }

    /**
     * Scans forward from {@code from} to {@code limit} and returns the first
     * index whose projection value exceeds {@link #MIN_INK_FOR_CONTENT}.
     * Falls back to {@code from} if nothing is found (page is blank on that edge).
     */
    private int scanForwardMargin(int[] proj, int from, int limit) {
        for (int i = from; i < limit; i++)
            if (proj[i] > MIN_INK_FOR_CONTENT) return i;
        return from;
    }

    private int scanBackwardMargin(int[] proj, int from, int limit) {
        for (int i = from; i > limit; i--)
            if (proj[i] > MIN_INK_FOR_CONTENT) return i;
        return from;
    }

    /**
     * Detects the bottom content boundary, absorbing any page-number footer
     * into the margin.
     *
     * Algorithm:
     *   1. Find the last ink row in the page (scan up from bottom).
     *   2. Scan further up to find where the bottom ink cluster starts.
     *   3. If the cluster height ≤ FOOTER_CLUSTER_MAX_PX, look for a gap
     *      above the initial cluster.  If the first gap is ≥ FOOTER_FIRST_GAP_MIN_PX,
     *      the algorithm iterates upward, absorbing additional small footer elements
     *      (page numbers, ornamental rules) until a gap ≥ FOOTER_CONTENT_GAP_MIN_PX
     *      is found — that gap's top is the real content boundary.
     *   4. Otherwise, fall back to the last ink row.
     */
    private int detectBottomMargin(int[] horizProj, int h, int contentTop) {
        // Search only in the bottom third to avoid mis-firing on content pages
        int searchFrom = h * 2 / 3;

        // Step 1: find the last row with meaningful ink (>= MIN_INK_FOR_CONTENT).
        // Using a threshold rather than strict == 0 prevents 1-2 stray/noise pixels
        // near the physical page bottom from being mistaken for a real ink cluster.
        int lastInkRow = h - 1;
        while (lastInkRow > searchFrom && horizProj[lastInkRow] < MIN_INK_FOR_CONTENT) lastInkRow--;

        if (lastInkRow <= searchFrom) {
            // Nothing meaningful in the bottom third — simple last-ink scan
            lastInkRow = h - 1;
            while (lastInkRow > contentTop && horizProj[lastInkRow] < MIN_INK_FOR_CONTENT) lastInkRow--;
            return lastInkRow;
        }

        // Step 2: find the top of the bottom cluster (border, page number, etc.)
        int clusterTop = lastInkRow;
        while (clusterTop > searchFrom && horizProj[clusterTop] >= MIN_INK_FOR_CONTENT) clusterTop--;
        int clusterHeight = lastInkRow - clusterTop;
        System.err.printf("[DBG] h=%d lastInkRow=%d clusterTop=%d clusterHeight=%d%n",
                h, lastInkRow, clusterTop, clusterHeight);

        if (clusterHeight > FOOTER_CLUSTER_MAX_PX) { System.err.println("[DBG] -> cluster too tall, fallback"); return lastInkRow; }

        // Step 3: find the first gap above the bottom cluster.
        int gapEnd   = clusterTop;
        int gapStart = gapEnd;
        while (gapStart > searchFrom && horizProj[gapStart] < MIN_INK_FOR_CONTENT) gapStart--;
        int firstGapHeight = gapEnd - gapStart;
        System.err.printf("[DBG] firstGap: gapEnd=%d gapStart=%d height=%d (need>=%d)%n",
                gapEnd, gapStart, firstGapHeight, FOOTER_FIRST_GAP_MIN_PX);

        if (firstGapHeight < FOOTER_FIRST_GAP_MIN_PX) { System.err.println("[DBG] -> first gap too small, fallback"); return lastInkRow; }

        // Step 4: iteratively absorb small footer elements above the first gap.
        int boundary = gapStart;
        for (int pass = 0; pass < FOOTER_EXTRA_PASSES; pass++) {
            int elemTop = boundary;
            while (elemTop > searchFrom && horizProj[elemTop] >= MIN_INK_FOR_CONTENT) elemTop--;
            int elemHeight = boundary - elemTop;
            System.err.printf("[DBG]   pass %d: boundary=%d elemTop=%d elemHeight=%d%n",
                    pass, boundary, elemTop, elemHeight);

            if (elemHeight > FOOTER_EXTRA_CLUSTER_MAX_PX) { System.err.println("[DBG]   -> elem too tall, stop"); break; }

            int nextGapEnd   = elemTop;
            int nextGapStart = nextGapEnd;
            while (nextGapStart > searchFrom && horizProj[nextGapStart] < MIN_INK_FOR_CONTENT) nextGapStart--;
            int nextGapHeight = nextGapEnd - nextGapStart;
            System.err.printf("[DBG]   nextGap: end=%d start=%d height=%d (content>=%d)%n",
                    nextGapEnd, nextGapStart, nextGapHeight, FOOTER_CONTENT_GAP_MIN_PX);

            if (nextGapHeight >= FOOTER_CONTENT_GAP_MIN_PX) {
                // Large gap → confirmed content boundary above this element
                boundary = nextGapStart;
                System.err.printf("[DBG]   -> content gap found, boundary=%d%n", boundary);
                break;
            } else if (elemHeight <= FOOTER_TINY_CLUSTER_MAX_PX && nextGapHeight >= 1) {
                // Tiny cluster (noise/fragment) with any gap — absorb unconditionally
                boundary = nextGapStart;
                System.err.printf("[DBG]   -> tiny cluster, absorb, boundary=%d%n", boundary);
            } else {
                // Normal-sized cluster with small gap above — it's a content line, stop
                System.err.println("[DBG]   -> normal cluster + small gap = content, stop");
                break;
            }
        }
        System.err.printf("[DBG] -> final boundary=%d%n", boundary);
        return boundary;
    }

    // ── Projection helpers ────────────────────────────────────────────────────

    private boolean[] buildInkMap(BufferedImage src, int w, int h) {
        boolean[] ink = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb  = src.getRGB(x, y);
                int r    = (rgb >> 16) & 0xFF;
                int g    = (rgb >>  8) & 0xFF;
                int b    =  rgb        & 0xFF;
                int gray = (int)(0.299f * r + 0.587f * g + 0.114f * b);
                ink[y * w + x] = (gray < INK_THRESHOLD);
            }
        }
        return ink;
    }

    private int[] verticalProjection(boolean[] ink, int w, int h) {
        return verticalProjection(ink, w, h, 0, h);
    }

    /** Vertical projection restricted to rows [yFrom, yTo). */
    private int[] verticalProjection(boolean[] ink, int w, int h, int yFrom, int yTo) {
        int[] proj = new int[w];
        for (int x = 0; x < w; x++)
            for (int y = yFrom; y < yTo; y++)
                if (ink[y * w + x]) proj[x]++;
        return proj;
    }

    private int[] horizontalProjection(boolean[] ink, int w, int h) {
        return horizontalProjection(ink, w, h, 0, w);
    }

    /** Horizontal projection restricted to columns [xFrom, xTo). */
    private int[] horizontalProjection(boolean[] ink, int w, int h, int xFrom, int xTo) {
        int[] proj = new int[h];
        for (int y = 0; y < h; y++)
            for (int x = xFrom; x < xTo; x++)
                if (ink[y * w + x]) proj[y]++;
        return proj;
    }

    // ── Gap detection ─────────────────────────────────────────────────────────

    /**
     * Returns every contiguous run of "empty" slots in {@code proj[from..to)}
     * where a slot is empty when its value ≤ {@code maxInk} and the run is at
     * least {@code minWidth} wide.
     *
     * After raw detection, adjacent gaps whose separating bridge is narrower than
     * {@code maxBridge} are merged into a single gap.  This handles stray ink
     * pixels (serifs, hyphens, descender tips) that would otherwise split one
     * logical gutter into two touching rectangles.
     *
     * Returns {@code [startInclusive, endExclusive]} pairs.
     */
    private List<int[]> findGaps(int[] proj, int from, int to,
                                  int maxInk, int minWidth) {
        // Pass 1: collect raw fragments with a low size floor so that two small
        // adjacent gaps (each below minWidth) can still merge into a valid gutter.
        int rawMin = Math.max(1, minWidth / 2);
        List<int[]> raw = new ArrayList<>();
        int start = -1;
        for (int i = from; i < to; i++) {
            if (proj[i] <= maxInk) {
                if (start < 0) start = i;
            } else {
                if (start >= 0) {
                    if (i - start >= rawMin) raw.add(new int[]{start, i});
                    start = -1;
                }
            }
        }
        if (start >= 0 && to - start >= rawMin)
            raw.add(new int[]{start, to});

        // Pass 2: merge fragments whose separating bridge is < minWidth — a bridge
        // that narrow is likely a stray character or serif, not a genuine content run.
        List<int[]> merged = mergeGaps(raw, minWidth);

        // Pass 3: discard merged gaps that still don't reach the minimum width.
        merged.removeIf(g -> g[1] - g[0] < minWidth);
        return merged;
    }

    /**
     * Removes vertical gaps that look like text indentation rather than column
     * gutters.  A gap is an indent if either sub-column it creates — the strip
     * to its left or to its right — is narrower than
     * {@link #MIN_COLUMN_FRACTION} of the total content width.
     *
     * Example: a numbered list indents body text ~30 px from the left margin.
     * That 30 px strip (containing just "1.", "2.", …) is far too narrow to be
     * a real column, so the gap is discarded.
     */
    private List<int[]> filterIndentGaps(List<int[]> gaps, int contentLeft, int contentRight,
                                          int[] vertProj) {
        List<int[]> kept = new ArrayList<>();
        int contentWidth = contentRight - contentLeft;
        int minColWidth  = (int)(contentWidth * MIN_COLUMN_FRACTION);

        for (int[] gap : gaps) {
            int leftColWidth  = gap[0] - contentLeft;
            int rightColWidth = contentRight - gap[1];

            // Check 1: both sides wide enough relative to total content width
            if (leftColWidth < minColWidth || rightColWidth < minColWidth) continue;

            // Check 2: ink density ratio — indent strips are much sparser than real columns
            float leftDensity  = avgInkDensity(vertProj, contentLeft, gap[0]);
            float rightDensity = avgInkDensity(vertProj, gap[1],      contentRight);
            float ratio = Math.max(leftDensity, rightDensity)
                        / Math.max(1f, Math.min(leftDensity, rightDensity));
            if (ratio > MAX_INK_DENSITY_RATIO) continue;

            kept.add(gap);
        }
        return kept;
    }

    /**
     * Removes the gap immediately to the right of a sparse-text bridge.
     *
     * Pattern: [real column] [gap1] [heading labels] [gap2] [real column]
     *
     * When the bridge between two adjacent gaps has ink density below
     * {@link #MAX_BRIDGE_DENSITY_FRACTION} of the sparser flanking real column,
     * the bridge is just section labels / bullets — not a real column.  In that
     * case gap2 (the indent to the right of the labels) is dropped and the
     * labels become part of the right column.  gap1 (the true column gutter)
     * is kept.
     */
    private List<int[]> mergeSparseBridges(List<int[]> gaps, int[] vertProj,
                                            int contentLeft, int contentRight) {
        if (gaps.size() < 2) return gaps;
        List<int[]> result = new ArrayList<>();

        int i = 0;
        while (i < gaps.size()) {
            int[] current = gaps.get(i);
            if (i + 1 < gaps.size()) {
                int[] next = gaps.get(i + 1);
                float bridgeDensity = avgInkDensity(vertProj, current[1], next[0]);
                float leftDensity   = avgInkDensity(vertProj, contentLeft, current[0]);
                float rightDensity  = avgInkDensity(vertProj, next[1],     contentRight);
                float threshold     = Math.min(leftDensity, rightDensity) * MAX_BRIDGE_DENSITY_FRACTION;

                if (bridgeDensity <= threshold) {
                    // Bridge is sparse labels: keep gap1, skip gap2
                    result.add(current);
                    i += 2;
                    continue;
                }
            }
            result.add(current);
            i++;
        }
        return result;
    }

    /** Average ink pixels per x-column in the range {@code [from, to)}. */
    private float avgInkDensity(int[] vertProj, int from, int to) {
        if (to <= from) return 0f;
        long sum = 0;
        for (int x = from; x < to; x++) sum += vertProj[x];
        return (float) sum / (to - from);
    }

    /**
     * Merges consecutive gaps whose separating bridge (non-empty run between
     * them) is ≤ {@code maxBridge} slots wide.
     */
    private List<int[]> mergeGaps(List<int[]> gaps, int maxBridge) {
        if (gaps.size() < 2) return gaps;
        List<int[]> merged = new ArrayList<>();
        int[] current = gaps.get(0).clone();
        for (int i = 1; i < gaps.size(); i++) {
            int[] next   = gaps.get(i);
            int   bridge = next[0] - current[1];   // columns between end of current and start of next
            if (bridge <= maxBridge) {
                current[1] = next[1];              // extend current gap to absorb next
            } else {
                merged.add(current);
                current = next.clone();
            }
        }
        merged.add(current);
        return merged;
    }
}
