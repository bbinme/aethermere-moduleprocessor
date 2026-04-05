package com.dnd.processor.converters;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects white-space structure in a page image using projection profiles.
 *
 * <h3>Row-first layout analysis</h3>
 *
 * The page is decomposed top-to-bottom in three phases:
 *
 * <b>Phase 1 — Row splitting:</b>
 *   The content area is divided into independent horizontal rows using two
 *   sources of breaks: full-width illustration zones (bilateral density check)
 *   and significant horizontal gaps (≥ MIN_ROW_SPLIT_PX).  This prevents
 *   headings, illustrations, and tables at different column alignments from
 *   contaminating each other's vertical projections.
 *
 * <b>Phase 2 — Per-row column detection:</b>
 *   For each text row, the vertical projection is computed restricted to that
 *   row's y-range only.  Column gutters are detected from this projection.
 *   Because each row contains only one type of horizontal structure, a centred
 *   heading in one row cannot fill the column gutter of the body row below it.
 *
 * <b>Phase 3 — Per-column sub-zone detection:</b>
 *   For each column in each row:
 *   - Pass 1.5: column-scoped illustration detection (yellow in debug)
 *   - Pass 2: horizontal gap detection within TEXT sub-zones (blue in debug)
 *
 * <h3>Debug colours</h3>
 *   green  – content-area boundary (margin outline)<br/>
 *   cyan   – row boundaries (Phase 1 splits)<br/>
 *   orange – IMAGE rows (full-width illustrations / maps)<br/>
 *   red    – column gutters per row (Phase 2)<br/>
 *   yellow – column-scoped IMAGE sub-zones (Phase 3 / Pass 1.5)<br/>
 *   blue   – horizontal row gaps per column (Phase 3 / Pass 2)
 */
public class ProjectionAnalyzer {

    private static final Logger log = LogManager.getLogger(ProjectionAnalyzer.class);

    // ── Config (all pixel values assume 150 DPI) ──────────────────────────────

    private final int   INK_THRESHOLD;
    private final float MAX_INK_FRACTION;
    private final float MAX_COLUMN_GAP_INK_FRACTION;
    private final int   MIN_VERT_GAP_PX;
    private final int   MIN_HORIZ_GAP_PX;
    private final float MIN_COLUMN_FRACTION;
    private final float MAX_INK_DENSITY_RATIO;
    private final float MAX_BRIDGE_DENSITY_FRACTION;
    private final int   MIN_INK_FOR_CONTENT;
    private final int   FOOTER_CLUSTER_MAX_PX;
    private final int   FOOTER_FIRST_GAP_MIN_PX;
    private final int   FOOTER_CONTENT_GAP_MIN_PX;
    private final int   FOOTER_EXTRA_CLUSTER_MAX_PX;
    private final int   FOOTER_EXTRA_PASSES;
    private final int   FOOTER_TINY_CLUSTER_MAX_PX;
    private final int   MIN_ROW_SPLIT_PX;
    private final int   MIN_TABLE_HORIZ_GAPS;
    private final float MIN_MAP_INK_FRACTION;
    private final int   MAX_MAP_HORIZ_GAPS;
    private final int   MAX_MAP_VERT_GAPS;
    private final float MAP_IMAGE_ZONE_FRACTION;
    private final float FULL_PAGE_ILLUSTRATION_FRACTION;
    private final float ILLUSTRATION_INK_FRACTION;
    private final int   MIN_ILLUSTRATION_PX;

    /** Uses default D&D Basic Set tuning. */
    public ProjectionAnalyzer() {
        this(new com.dnd.processor.config.DnD_BasicSet());
    }

    /** Uses the supplied module config for all tuning constants. */
    public ProjectionAnalyzer(com.dnd.processor.config.DnD_BasicSet config) {
        INK_THRESHOLD                  = config.inkThreshold();
        MAX_INK_FRACTION               = config.maxInkFraction();
        MAX_COLUMN_GAP_INK_FRACTION    = config.maxColumnGapInkFraction();
        MIN_VERT_GAP_PX                = config.minVertGapPx();
        MIN_HORIZ_GAP_PX               = config.minHorizGapPx();
        MIN_COLUMN_FRACTION            = config.minColumnFraction();
        MAX_INK_DENSITY_RATIO          = config.maxInkDensityRatio();
        MAX_BRIDGE_DENSITY_FRACTION    = config.maxBridgeDensityFraction();
        MIN_INK_FOR_CONTENT            = config.minInkForContent();
        FOOTER_CLUSTER_MAX_PX          = config.footerClusterMaxPx();
        FOOTER_FIRST_GAP_MIN_PX        = config.footerFirstGapMinPx();
        FOOTER_CONTENT_GAP_MIN_PX      = config.footerContentGapMinPx();
        FOOTER_EXTRA_CLUSTER_MAX_PX    = config.footerExtraClusterMaxPx();
        FOOTER_EXTRA_PASSES            = config.footerExtraPasses();
        FOOTER_TINY_CLUSTER_MAX_PX     = config.footerTinyClusterMaxPx();
        MIN_ROW_SPLIT_PX               = config.minRowSplitPx();
        MIN_TABLE_HORIZ_GAPS           = config.minTableHorizGaps();
        MIN_MAP_INK_FRACTION           = config.minMapInkFraction();
        MAX_MAP_HORIZ_GAPS             = config.maxMapHorizGaps();
        MAX_MAP_VERT_GAPS              = config.maxMapVertGaps();
        MAP_IMAGE_ZONE_FRACTION        = config.mapImageZoneFraction();
        FULL_PAGE_ILLUSTRATION_FRACTION = config.fullPageIllustrationFraction();
        ILLUSTRATION_INK_FRACTION      = config.illustrationInkFraction();
        MIN_ILLUSTRATION_PX            = config.minIllustrationPx();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Detected margin boundaries (pixel coordinates). */
    public record Margins(int top, int bottom, int left, int right) {}

    public enum LayoutType { SINGLE, MAP, TABLE, TWO_COLUMN, THREE_COLUMN, TWO_ROW, THREE_ROW, TWO_BY_TWO, FRONT_COVER, BACK_COVER }

    /** Whether a zone contains typeset text or an illustration/map. */
    public enum ZoneType { TEXT, IMAGE }

    /**
     * A vertical slice within a column: either a text run (with blank-line gaps)
     * or a column-scoped illustration detected in Pass 1.5.
     * IMAGE sub-zones have an empty horizGaps list.
     */
    public record ColumnZone(int yTop, int yBottom, ZoneType type, List<int[]> horizGaps) {}

    /**
     * A single column within a TEXT zone: its x-extent and the ordered list of
     * TEXT/IMAGE sub-zones produced by Pass 1.5 and Pass 2.
     */
    public record Column(int xLeft, int xRight, List<ColumnZone> subZones) {}

    /**
     * A horizontal slice of the content area, either a full-width TEXT zone (with
     * one or more columns produced by Pass 1) or a full-width IMAGE zone
     * (illustration/map detected in Pass 0, no column structure).
     */
    public record Zone(int yTop, int yBottom, ZoneType type, List<Column> columns) {}

    /**
     * Layout classification result for one page image.
     *
     * {@code zones} is the authoritative structure used for splitting; the
     * remaining fields are legacy coordinates derived from it for display.
     *
     * @param type    summary layout type (display / diagnostics)
     * @param margins content-area boundaries (image pixels)
     * @param zones   ordered list of TEXT / IMAGE zones top-to-bottom
     * @param splitX  midpoint of 1st column gutter in the first TEXT zone (-1 if none)
     * @param splitX2 midpoint of 2nd column gutter                        (-1 if none)
     * @param splitY  midpoint of 1st zone boundary                        (-1 if none)
     * @param splitY2 midpoint of 2nd zone boundary                        (-1 if none)
     * @param vertGaps  all column gutters across all TEXT zones (for debug drawing)
     * @param horizGaps all row breaks across all columns          (for debug drawing)
     */
    public record PageLayout(
            LayoutType type,
            Margins margins,
            List<Zone> zones,
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

        // Horizontal projection restricted to content columns — used for
        // illustration-zone detection and global MAP ink-density check.
        int[] horizProjContent = horizontalProjection(ink, w, h, m.left(), m.right());

        // ── Pass 0: identify full-width illustration / map zones ──────────────
        // Bilateral: a row is full-width dense only when BOTH the left half AND
        // right half each exceed the ink threshold.  Half-page illustrations fail
        // the bilateral check and fall through to Pass 1.5.
        int midX = (m.left() + m.right()) / 2;
        int[] horizProjLeft  = horizontalProjection(ink, w, h, m.left(), midX);
        int[] horizProjRight = horizontalProjection(ink, w, h, midX, m.right());
        List<int[]> illZones = findFullWidthIllustrationZones(
                horizProjLeft, horizProjRight, m.top(), m.bottom(), midX - m.left());

        // Phase 1+2: split into horizontal rows, then detect columns per row.
        // Each text row gets independent column detection (Phase 2) and
        // sub-zone detection (Phase 3) via buildTextZone.
        List<Zone> zones = buildZones(ink, w, h, m, illZones);

        // ── Collect legacy gap lists for debug drawing / MAP+TABLE checks ────
        List<int[]> allVertGaps  = new ArrayList<>();
        List<int[]> allHorizGaps = new ArrayList<>();
        for (Zone z : zones) {
            if (z.type() == ZoneType.TEXT) {
                List<Column> cols = z.columns();
                for (int k = 0; k + 1 < cols.size(); k++)
                    allVertGaps.add(new int[]{cols.get(k).xRight(), cols.get(k + 1).xLeft()});
                for (Column col : cols)
                    for (ColumnZone cz : col.subZones())
                        if (cz.type() == ZoneType.TEXT)
                            allHorizGaps.addAll(cz.horizGaps());
            }
        }

        // ── Legacy split coordinates (from first TEXT zone) ───────────────────
        int splitX = -1, splitX2 = -1, splitY = -1, splitY2 = -1;
        List<Zone> textZones  = new ArrayList<>();
        List<Zone> imageZones = new ArrayList<>();
        for (Zone z : zones) (z.type() == ZoneType.TEXT ? textZones : imageZones).add(z);

        if (!textZones.isEmpty()) {
            List<Column> cols = textZones.get(0).columns();
            if (cols.size() >= 2) splitX  = (cols.get(0).xRight() + cols.get(1).xLeft()) / 2;
            if (cols.size() >= 3) splitX2 = (cols.get(1).xRight() + cols.get(2).xLeft()) / 2;
        }
        if (zones.size() >= 2) splitY  = (zones.get(0).yBottom() + zones.get(1).yTop()) / 2;
        if (zones.size() >= 3) splitY2 = (zones.get(1).yBottom() + zones.get(2).yTop()) / 2;

        // ── MAP check: high overall ink density overrides zone classification ──
        // (handles complex maps with text labels that break illustration runs)
        int[] vertProjContent = verticalProjection(ink, w, h, m.top(), m.bottom());
        long inkInContent = 0;
        for (int x = m.left(); x < m.right(); x++) inkInContent += vertProjContent[x];
        long contentArea = (long)(m.right() - m.left()) * (m.bottom() - m.top());
        boolean highInkDensity = contentArea > 0
                && (float) inkInContent / contentArea >= MIN_MAP_INK_FRACTION;

        // Primary MAP path: high density + very few gaps (simple full-page maps)
        if (highInkDensity
                && allHorizGaps.size() <= MAX_MAP_HORIZ_GAPS
                && allVertGaps.size()  <= MAX_MAP_VERT_GAPS) {
            return new PageLayout(LayoutType.MAP, m, zones,
                    splitX, splitX2, splitY, splitY2, allVertGaps, allHorizGaps);
        }

        // Alternate MAP path: large central IMAGE zone (dungeon map + title + legend).
        // The title and legend create text zones with column gaps that break the primary
        // path, but the IMAGE zone still dominates the content area.
        // Guard: skip if text zones collectively occupy ≥ 30% of the content height —
        // that indicates a real layout page (text + illustration) rather than a map
        // with small legend/title labels.
        long totalImageHeight = 0;
        long totalTextHeight  = 0;
        for (Zone z : zones) {
            if (z.type() == ZoneType.IMAGE) totalImageHeight += z.yBottom() - z.yTop();
            else                            totalTextHeight  += z.yBottom() - z.yTop();
        }
        int contentHeight = m.bottom() - m.top();
        if (highInkDensity && contentHeight > 0
                && (float) totalTextHeight  / contentHeight < 0.30f
                && (float) totalImageHeight / contentHeight >= MAP_IMAGE_ZONE_FRACTION) {
            return new PageLayout(LayoutType.MAP, m, zones,
                    splitX, splitX2, splitY, splitY2, allVertGaps, allHorizGaps);
        }

        // ── TABLE check: single column, many horizontal gaps ──────────────────
        if (textZones.size() == 1 && imageZones.isEmpty()
                && textZones.get(0).columns().size() == 1) {
            Column singleCol = textZones.get(0).columns().get(0);
            long totalHorizGaps = singleCol.subZones().stream()
                    .filter(cz -> cz.type() == ZoneType.TEXT)
                    .mapToLong(cz -> cz.horizGaps().size())
                    .sum();
            if (totalHorizGaps >= MIN_TABLE_HORIZ_GAPS) {
                return new PageLayout(LayoutType.TABLE, m, zones,
                        splitX, splitX2, splitY, splitY2, allVertGaps, allHorizGaps);
            }
        }

        // ── Layout type from zone structure ───────────────────────────────────
        LayoutType type = deriveLayoutType(textZones, imageZones, zones);
        return new PageLayout(type, m, zones,
                splitX, splitX2, splitY, splitY2, allVertGaps, allHorizGaps);
    }

    // ── Pass 0: illustration zone detection ───────────────────────────────────

    /**
     * Finds contiguous runs of "dense" rows in the content area that represent
     * illustrations, maps, or other non-text graphics.
     *
     * A row is dense when its ink count (from {@code horizProjContent}, which
     * covers only the content columns) exceeds
     * {@code contentWidth * ILLUSTRATION_INK_FRACTION}.
     *
     * Only runs ≥ {@link #MIN_ILLUSTRATION_PX} tall are returned; shorter runs
     * (table rules, ornamental lines) are ignored.
     */
    /**
     * Pass 0: bilateral full-width illustration detection.
     *
     * Phase A — core detection: a row is "bilaterally dense" when BOTH the left
     * half AND right half each exceed {@link #FULL_PAGE_ILLUSTRATION_FRACTION} of
     * the half-width.  Only runs ≥ {@link #MIN_ILLUSTRATION_PX} tall are kept.
     * Half-page illustrations fail the bilateral check and fall through to Pass 1.5.
     *
     * Phase B — fringe extension: each core zone is extended upward and downward
     * by absorbing rows that have any ink in either half (above
     * {@link #MIN_INK_FOR_CONTENT}).  This captures the sparse top/bottom edges of
     * pen-and-ink illustrations that dip below the bilateral threshold, preventing
     * illustration ink from bleeding into adjacent text zones.  Extension stops at
     * blank rows (natural paragraph/zone boundaries).
     */
    private List<int[]> findFullWidthIllustrationZones(int[] leftProj, int[] rightProj,
                                                        int yFrom, int yTo, int halfWidth) {
        int threshold = Math.max(1, (int)(halfWidth * FULL_PAGE_ILLUSTRATION_FRACTION));

        // Phase A: find bilateral dense core zones
        List<int[]> cores = new ArrayList<>();
        int start = -1;
        for (int y = yFrom; y < yTo; y++) {
            boolean dense = leftProj[y] > threshold && rightProj[y] > threshold;
            if (dense) {
                if (start < 0) start = y;
            } else {
                if (start >= 0) {
                    if (y - start >= MIN_ILLUSTRATION_PX) cores.add(new int[]{start, y});
                    start = -1;
                }
            }
        }
        if (start >= 0 && yTo - start >= MIN_ILLUSTRATION_PX)
            cores.add(new int[]{start, yTo});

        if (cores.isEmpty()) return cores;

        // Phase B: extend each core zone up/down through sparse fringe rows
        List<int[]> extended = new ArrayList<>();
        for (int[] core : cores) {
            int top = core[0];
            int bot = core[1];
            // Extend upward
            while (top > yFrom && (leftProj[top - 1] > MIN_INK_FOR_CONTENT
                                || rightProj[top - 1] > MIN_INK_FOR_CONTENT))
                top--;
            // Extend downward
            while (bot < yTo && (leftProj[bot] > MIN_INK_FOR_CONTENT
                               || rightProj[bot] > MIN_INK_FOR_CONTENT))
                bot++;
            extended.add(new int[]{top, bot});
        }

        // Merge overlapping or adjacent extended zones
        List<int[]> merged = new ArrayList<>();
        int[] cur = extended.get(0).clone();
        for (int i = 1; i < extended.size(); i++) {
            int[] next = extended.get(i);
            if (next[0] <= cur[1]) {
                cur[1] = Math.max(cur[1], next[1]);
            } else {
                merged.add(cur);
                cur = next.clone();
            }
        }
        merged.add(cur);
        return merged;
    }

    /**
     * Pass 1.5: finds illustration zones within a single column using
     * {@link #ILLUSTRATION_INK_FRACTION} (0.15) of the column width.
     */
    private List<int[]> findIllustrationZones(int[] horizProjContent,
                                               int yFrom, int yTo, int contentWidth) {
        return findIllustrationZones(horizProjContent, yFrom, yTo, contentWidth,
                ILLUSTRATION_INK_FRACTION);
    }

    private List<int[]> findIllustrationZones(int[] horizProjContent,
                                               int yFrom, int yTo, int contentWidth,
                                               float fraction) {
        int threshold = Math.max(1, (int)(contentWidth * fraction));

        // Phase A: find dense core zones
        List<int[]> cores = new ArrayList<>();
        int start = -1;
        for (int y = yFrom; y < yTo; y++) {
            if (horizProjContent[y] > threshold) {
                if (start < 0) start = y;
            } else {
                if (start >= 0) {
                    if (y - start >= MIN_ILLUSTRATION_PX) cores.add(new int[]{start, y});
                    start = -1;
                }
            }
        }
        if (start >= 0 && yTo - start >= MIN_ILLUSTRATION_PX)
            cores.add(new int[]{start, yTo});

        if (cores.isEmpty()) return cores;

        // Phase B: extend each core zone up/down through sparse fringe rows.
        // Illustration edges (thin lines, trailing details) dip below the density
        // threshold but are clearly still part of the illustration.  Extension
        // stops at blank rows (natural boundaries to adjacent text).
        List<int[]> extended = new ArrayList<>();
        for (int[] core : cores) {
            int top = core[0];
            int bot = core[1];
            while (top > yFrom && horizProjContent[top - 1] > MIN_INK_FOR_CONTENT)
                top--;
            while (bot < yTo && horizProjContent[bot] > MIN_INK_FOR_CONTENT)
                bot++;
            extended.add(new int[]{top, bot});
        }

        // Merge overlapping or adjacent extended zones
        List<int[]> merged = new ArrayList<>();
        int[] cur = extended.get(0).clone();
        for (int i = 1; i < extended.size(); i++) {
            int[] next = extended.get(i);
            if (next[0] <= cur[1]) {
                cur[1] = Math.max(cur[1], next[1]);
            } else {
                merged.add(cur);
                cur = next.clone();
            }
        }
        merged.add(cur);
        return merged;
    }

    /**
     * Phase 1 + 2: splits the content area into horizontal rows, then applies
     * column detection (Phase 2) and sub-zone detection (Phase 3) to each row.
     *
     * Row boundaries come from two sources:
     * <ol>
     *   <li>Full-width illustration zones (bilateral density check) → IMAGE rows</li>
     *   <li>Significant horizontal gaps (≥ MIN_ROW_SPLIT_PX) → whitespace separators</li>
     * </ol>
     *
     * Each text region between breaks becomes an independent TEXT row with its
     * own column detection, so a centred heading in one row cannot contaminate
     * the column gutter detection of the body row below it.
     */
    private List<Zone> buildZones(boolean[] ink, int w, int h, Margins m,
                                   List<int[]> illZones) {
        // ── Phase 1: find all row-splitting breaks ───────────────────────────
        int[] horizProj = horizontalProjection(ink, w, h, m.left(), m.right());
        int maxInkForRowGap = Math.max(1, (int)((m.right() - m.left()) * MAX_INK_FRACTION));

        // Collect breaks: illustration zones (type=1 → IMAGE rows) and
        // horizontal gaps (type=0 → whitespace separators, no row generated).
        List<int[]> breaks = new ArrayList<>();

        for (int[] ill : illZones) {
            breaks.add(new int[]{ill[0], ill[1], 1});
        }

        // Find row-splitting horizontal gaps in text regions between illustrations
        int current = m.top();
        for (int[] ill : illZones) {
            if (ill[0] > current) {
                List<int[]> hGaps = findGaps(horizProj, current, ill[0],
                        maxInkForRowGap, MIN_ROW_SPLIT_PX);
                for (int[] g : hGaps) breaks.add(new int[]{g[0], g[1], 0});
            }
            current = ill[1];
        }
        if (current < m.bottom()) {
            List<int[]> hGaps = findGaps(horizProj, current, m.bottom(),
                    maxInkForRowGap, MIN_ROW_SPLIT_PX);
            for (int[] g : hGaps) breaks.add(new int[]{g[0], g[1], 0});
        }

        breaks.sort((a, b) -> Integer.compare(a[0], b[0]));

        // ── Phase 2: build rows from breaks ──────────────────────────────────
        List<Zone> rows = new ArrayList<>();
        current = m.top();
        for (int[] brk : breaks) {
            if (brk[0] > current) {
                rows.addAll(buildTextZoneWithHeaderSplit(ink, w, h, m, current, brk[0]));
            }
            if (brk[2] == 1) {
                rows.add(new Zone(brk[0], brk[1], ZoneType.IMAGE, List.of()));
            }
            current = brk[1];
        }
        if (current < m.bottom()) {
            int trailingHeight = m.bottom() - current;
            if (trailingHeight < MIN_ILLUSTRATION_PX && !rows.isEmpty()
                    && rows.get(rows.size() - 1).type() == ZoneType.IMAGE) {
                Zone last = rows.remove(rows.size() - 1);
                rows.add(new Zone(last.yTop(), m.bottom(), ZoneType.IMAGE, List.of()));
            } else {
                rows.addAll(buildTextZoneWithHeaderSplit(ink, w, h, m, current, m.bottom()));
            }
        }

        // Post-process: merge IMAGE + thin_TEXT + IMAGE into a single IMAGE zone.
        int i = rows.size() - 1;
        while (i >= 2) {
            Zone z    = rows.get(i);
            Zone mid  = rows.get(i - 1);
            Zone prev = rows.get(i - 2);
            if (z.type() == ZoneType.IMAGE
                    && mid.type() == ZoneType.TEXT
                    && prev.type() == ZoneType.IMAGE
                    && mid.yBottom() - mid.yTop() < MIN_ILLUSTRATION_PX) {
                rows.set(i - 2, new Zone(prev.yTop(), z.yBottom(), ZoneType.IMAGE, List.of()));
                rows.remove(i);
                rows.remove(i - 1);
                i -= 2;
            } else {
                i--;
            }
        }

        return rows;
    }

    /**
     * Builds one or more TEXT zones for the band {@code [yTop, yBottom)}.
     *
     * After column detection, checks whether the top of the row is a full-width
     * header that spans the column gutter.  If so, splits into a SINGLE header
     * zone + a multi-column body zone.  This handles centred headings like
     * "Players' Background" above a two-column read-aloud box where the gap
     * between heading and box is too small for Phase 1 row splitting.
     */
    private List<Zone> buildTextZoneWithHeaderSplit(boolean[] ink, int w, int h,
                                                     Margins m, int yTop, int yBottom) {
        Zone zone = buildTextZone(ink, w, h, m, yTop, yBottom);

        // Only split if the zone has multiple columns
        if (zone.columns().size() < 2) return List.of(zone);

        // Check if the column gap is blocked (has ink) at the top of the zone,
        // indicating a full-width header above multi-column content.
        int gapLeft  = zone.columns().get(0).xRight();
        int gapRight = zone.columns().get(1).xLeft();

        // Scan from top: find the first y where the gap has a sustained clean run.
        // "Clean" = no ink pixels across the gap width at row y.
        int headerEnd = -1;
        int cleanRun  = 0;
        for (int y = yTop; y < yBottom; y++) {
            boolean rowClean = true;
            for (int x = gapLeft; x < gapRight; x++) {
                if (ink[y * w + x]) { rowClean = false; break; }
            }
            if (rowClean) {
                if (cleanRun == 0) headerEnd = y;
                cleanRun++;
            } else {
                if (cleanRun < MIN_HORIZ_GAP_PX) {
                    // Short clean run interrupted — not a real gap, reset
                    headerEnd = -1;
                }
                cleanRun = 0;
            }
            if (cleanRun >= MIN_HORIZ_GAP_PX) break;
        }

        // If the gap is clean from the very top, there's no header — return as-is.
        // headerEnd == yTop means the gap starts immediately, no header rows above it.
        if (headerEnd <= yTop || headerEnd < 0) return List.of(zone);

        // Split: SINGLE header zone + re-analyzed multi-column body zone.
        int xLeft  = zone.columns().get(0).xLeft();
        int xRight = zone.columns().get(zone.columns().size() - 1).xRight();
        Zone header = new Zone(yTop, headerEnd, ZoneType.TEXT,
                List.of(new Column(xLeft, xRight,
                        List.of(new ColumnZone(yTop, headerEnd, ZoneType.TEXT, List.of())))));
        Zone body = buildTextZone(ink, w, h, m, headerEnd, yBottom);
        return List.of(header, body);
    }

    /**
     * Builds a TEXT row for the horizontal band {@code [yTop, yBottom)}.
     *
     * Phase 2 — vertical projection restricted to this row's y-range → column gutters.
     * Phase 3a (Pass 1.5) — per-column horizontal projection → column-scoped IMAGE sub-zones.
     * Phase 3b (Pass 2)   — horizontal gaps within each TEXT sub-zone of each column.
     */
    private Zone buildTextZone(boolean[] ink, int w, int h, Margins m,
                                int yTop, int yBottom) {
        // Guard: rows shorter than MIN_ILLUSTRATION_PX (~4 text lines at 150 DPI)
        // cannot contain meaningful multi-column content.  Skip column detection
        // to avoid false gutters through character/word gaps in headings.
        int rowHeight = yBottom - yTop;
        if (rowHeight < MIN_ILLUSTRATION_PX) {
            int colWidth = m.right() - m.left();
            int[] horizProj = horizontalProjection(ink, w, h, m.left(), m.right());
            int maxInkPerRow = Math.max(1, (int)(colWidth * MAX_INK_FRACTION));
            List<int[]> hGaps = findGaps(horizProj, yTop, yBottom, maxInkPerRow, MIN_HORIZ_GAP_PX);
            return new Zone(yTop, yBottom, ZoneType.TEXT,
                    List.of(new Column(m.left(), m.right(),
                            List.of(new ColumnZone(yTop, yBottom, ZoneType.TEXT, hGaps)))));
        }

        // Pass 1: find column gutters in this zone only.
        // Uses the strict MAX_INK_FRACTION (0.5%) — row-first splitting ensures
        // each row contains only one type of content, so cross-contamination from
        // headings or illustrations no longer inflates gutter ink counts.
        int[] vertProjZone = verticalProjection(ink, w, h, yTop, yBottom);
        int   maxInkPerCol = Math.max(1, (int)((yBottom - yTop) * MAX_INK_FRACTION));

        List<int[]> vGaps = findGaps(vertProjZone, m.left(), m.right(), maxInkPerCol, MIN_VERT_GAP_PX);
        vGaps = filterIndentGaps(vGaps, m.left(), m.right(), vertProjZone);
        vGaps = mergeSparseBridges(vGaps, vertProjZone, m.left(), m.right());

        // Pass 1b (header fallback): if no gaps were found, a full-width header spanning
        // the column gutter (e.g. a centered box title) may have injected ink into the
        // gutter x-columns, causing them to exceed maxInkPerCol.  Re-try using only the
        // bottom 80% of the zone — the header ink is concentrated at the top, so the
        // body-only projection typically shows a clean gap.


        // Derive column x-ranges from gap boundaries
        List<int[]> colRanges = new ArrayList<>();
        int prev = m.left();
        for (int[] gap : vGaps) {
            colRanges.add(new int[]{prev, gap[0]});
            prev = gap[1];
        }
        colRanges.add(new int[]{prev, m.right()});

        // Pass 1.5 + Pass 2: per-column illustration sub-zones and blank-line gaps
        List<Column> columns = new ArrayList<>();
        for (int[] cr : colRanges) {
            int colLeft  = cr[0], colRight = cr[1];
            int colWidth = colRight - colLeft;

            // Horizontal projection restricted to this column (full height array, y-indexed)
            int[] horizProjCol = horizontalProjection(ink, w, h, colLeft, colRight);

            // Pass 1.5: find illustration sub-zones within this column's width
            List<int[]> colIllZones = findIllustrationZones(horizProjCol, yTop, yBottom, colWidth);

            // Build sub-zones: TEXT regions between/around column illustrations
            List<ColumnZone> subZones = new ArrayList<>();
            int cur = yTop;
            int maxInkPerRow = Math.max(1, (int)(colWidth * MAX_INK_FRACTION));
            for (int[] ill : colIllZones) {
                if (ill[0] > cur) {
                    List<int[]> hGaps = findGaps(horizProjCol, cur, ill[0], maxInkPerRow, MIN_HORIZ_GAP_PX);
                    subZones.add(new ColumnZone(cur, ill[0], ZoneType.TEXT, hGaps));
                }
                subZones.add(new ColumnZone(ill[0], ill[1], ZoneType.IMAGE, List.of()));
                cur = ill[1];
            }
            if (cur < yBottom) {
                List<int[]> hGaps = findGaps(horizProjCol, cur, yBottom, maxInkPerRow, MIN_HORIZ_GAP_PX);
                subZones.add(new ColumnZone(cur, yBottom, ZoneType.TEXT, hGaps));
            }

            // Post-process column sub-zones: merge IMAGE + thin_TEXT + IMAGE into a single
            // IMAGE sub-zone.  A sparse interior band in a pen-and-ink illustration can dip
            // below the density threshold, splitting one illustration into two IMAGE sub-zones
            // with a thin TEXT gap between them.  Each sub-zone becomes its own sub-page in
            // the layout PDF, producing a horizontal cut through the image.
            // Mirror the same merge logic that buildZones applies to page-level IMAGE zones.
            int si = subZones.size() - 1;
            while (si >= 2) {
                ColumnZone sz   = subZones.get(si);
                ColumnZone mid  = subZones.get(si - 1);
                ColumnZone czPrev = subZones.get(si - 2);
                if (sz.type() == ZoneType.IMAGE
                        && mid.type() == ZoneType.TEXT
                        && czPrev.type() == ZoneType.IMAGE
                        && mid.yBottom() - mid.yTop() < MIN_ILLUSTRATION_PX) {
                    subZones.set(si - 2, new ColumnZone(czPrev.yTop(), sz.yBottom(), ZoneType.IMAGE, List.of()));
                    subZones.remove(si);
                    subZones.remove(si - 1);
                    si -= 2;
                } else {
                    si--;
                }
            }

            columns.add(new Column(colLeft, colRight, subZones));
        }

        return new Zone(yTop, yBottom, ZoneType.TEXT, columns);
    }

    /**
     * Derives a summary {@link LayoutType} from the zone list, used for display
     * and diagnostics.  The zones list itself is the authoritative split structure
     * used by {@link PDFPreprocessor}.
     */
    private LayoutType deriveLayoutType(List<Zone> textZones, List<Zone> imageZones,
                                         List<Zone> allZones) {
        if (textZones.isEmpty()) return LayoutType.MAP;

        int numAll  = allZones.size();
        int numText = textZones.size();

        if (numAll == 1) {
            // Single zone, all text
            int numCols = textZones.get(0).columns().size();
            if (numCols >= 3) return LayoutType.THREE_COLUMN;
            if (numCols == 2) return LayoutType.TWO_COLUMN;
            // Single column: IMAGE sub-zones and large blank-line gaps both count as row splits
            if (!textZones.get(0).columns().isEmpty()) {
                Column firstCol = textZones.get(0).columns().get(0);
                long imageSubZones = firstCol.subZones().stream()
                        .filter(cz -> cz.type() == ZoneType.IMAGE).count();
                long largeHorizGaps = firstCol.subZones().stream()
                        .filter(cz -> cz.type() == ZoneType.TEXT)
                        .flatMap(cz -> cz.horizGaps().stream())
                        .filter(g -> g[1] - g[0] >= MIN_ROW_SPLIT_PX).count();
                long rowSplits = imageSubZones + largeHorizGaps;
                if (rowSplits >= 2) return LayoutType.THREE_ROW;
                if (rowSplits == 1) return LayoutType.TWO_ROW;
            }
            return LayoutType.SINGLE;
        }

        // Multiple zones (text + image combinations, or multiple text blocks)
        int maxCols = textZones.stream().mapToInt(z -> z.columns().size()).max().orElse(0);

        if (numText == 2 && imageZones.isEmpty()) {
            boolean bothMultiCol = textZones.stream().allMatch(z -> z.columns().size() >= 2);
            return bothMultiCol ? LayoutType.TWO_BY_TWO : LayoutType.TWO_ROW;
        }
        if (numText >= 3 && imageZones.isEmpty()) return LayoutType.THREE_ROW;

        // Mix of text and image zones — zone count == row count (zones are horizontal bands)
        if (numAll >= 3) return LayoutType.THREE_ROW;
        return LayoutType.TWO_ROW;
    }

    /**
     * Analyses {@code src} and returns an annotated copy with:
     *   green  – content-area boundary (margin outline)
     *   cyan   – row boundaries (Phase 1 splits between rows)
     *   orange – IMAGE rows (full-width illustrations / maps)
     *   red    – column gutters per row (Phase 2, spans row height only)
     *   yellow – column-scoped IMAGE sub-zones (Phase 3 / Pass 1.5)
     *   blue   – horizontal gaps per column (Phase 3 / Pass 2)
     */
    public BufferedImage analyze(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();

        PageLayout layout = analyzeLayout(src);
        Margins    m      = layout.margins();

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        g2.drawImage(src, 0, 0, null);
        g2.setStroke(new BasicStroke(1f));

        // Green: content-area boundary
        g2.setColor(Color.GREEN);
        g2.drawRect(m.left(), m.top(), m.right() - m.left(), m.bottom() - m.top());

        // Cyan: row boundaries (gaps between adjacent rows from Phase 1 splitting)
        g2.setColor(Color.CYAN);
        List<Zone> zoneList = layout.zones();
        for (int ri = 0; ri + 1 < zoneList.size(); ri++) {
            int gapTop = zoneList.get(ri).yBottom();
            int gapBot = zoneList.get(ri + 1).yTop();
            if (gapBot > gapTop) {
                g2.drawRect(m.left(), gapTop, m.right() - m.left(), gapBot - gapTop);
            } else {
                // Adjacent rows with no gap — draw a single-pixel line
                g2.drawLine(m.left(), gapTop, m.right(), gapTop);
            }
        }

        for (Zone zone : zoneList) {
            int zt = zone.yTop(), zb = zone.yBottom(), zh = zb - zt;
            if (zone.type() == ZoneType.IMAGE) {
                // Orange: illustration / map zone
                g2.setColor(Color.ORANGE);
                g2.drawRect(m.left(), zt, m.right() - m.left(), zh);
            } else {
                List<Column> cols = zone.columns();
                // Red: column gutters (between adjacent columns, full zone height)
                g2.setColor(Color.RED);
                for (int k = 0; k + 1 < cols.size(); k++) {
                    int gLeft  = cols.get(k).xRight();
                    int gRight = cols.get(k + 1).xLeft();
                    g2.drawRect(gLeft, zt, gRight - gLeft, zh);
                }
                // Yellow: column-scoped IMAGE sub-zones (Pass 1.5)
                g2.setColor(Color.YELLOW);
                for (Column col : cols) {
                    for (ColumnZone cz : col.subZones()) {
                        if (cz.type() == ZoneType.IMAGE) {
                            g2.drawRect(col.xLeft(), cz.yTop(),
                                    col.xRight() - col.xLeft(), cz.yBottom() - cz.yTop());
                        }
                    }
                }
                // Blue: horizontal row gaps within TEXT sub-zones
                g2.setColor(Color.BLUE);
                for (Column col : cols) {
                    for (ColumnZone cz : col.subZones()) {
                        if (cz.type() == ZoneType.TEXT) {
                            for (int[] gap : cz.horizGaps()) {
                                g2.drawRect(col.xLeft(), gap[0],
                                        col.xRight() - col.xLeft(), gap[1] - gap[0]);
                            }
                        }
                    }
                }
            }
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

        if (clusterHeight > FOOTER_CLUSTER_MAX_PX) return lastInkRow;

        // Step 3: find the first gap above the bottom cluster.
        int gapEnd   = clusterTop;
        int gapStart = gapEnd;
        while (gapStart > searchFrom && horizProj[gapStart] < MIN_INK_FOR_CONTENT) gapStart--;
        int firstGapHeight = gapEnd - gapStart;

        if (firstGapHeight < FOOTER_FIRST_GAP_MIN_PX) return lastInkRow;

        // If the first gap is already large enough to confirm a content/footer
        // boundary, everything above it is content — don't absorb further.
        // The iterative loop below handles cases where the first gap is small
        // (e.g. page number + ornamental rule with a narrow gap between them).
        if (firstGapHeight >= FOOTER_CONTENT_GAP_MIN_PX) {
            log.debug("[footer] first gap {}px confirms boundary at y={}",
                    firstGapHeight, gapStart);
            return gapStart;
        }

        // Step 4: iteratively absorb small footer elements above the first gap.
        int boundary = gapStart;
        for (int pass = 0; pass < FOOTER_EXTRA_PASSES; pass++) {
            int elemTop = boundary;
            while (elemTop > searchFrom && horizProj[elemTop] >= MIN_INK_FOR_CONTENT) elemTop--;
            int elemHeight = boundary - elemTop;

            if (elemHeight > FOOTER_EXTRA_CLUSTER_MAX_PX) break;

            int nextGapEnd   = elemTop;
            int nextGapStart = nextGapEnd;
            while (nextGapStart > searchFrom && horizProj[nextGapStart] < MIN_INK_FOR_CONTENT) nextGapStart--;
            int nextGapHeight = nextGapEnd - nextGapStart;

            if (nextGapHeight >= FOOTER_CONTENT_GAP_MIN_PX) {
                // Large gap → confirmed content boundary above this element
                boundary = nextGapStart;
                break;
            } else if (elemHeight <= FOOTER_TINY_CLUSTER_MAX_PX && nextGapHeight >= 1) {
                // Tiny cluster (noise/fragment) with any gap — absorb unconditionally
                boundary = nextGapStart;
            } else {
                // Normal-sized cluster with small gap above — it's a content line, stop
                break;
            }
        }
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
