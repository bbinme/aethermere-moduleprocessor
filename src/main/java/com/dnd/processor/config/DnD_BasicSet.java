package com.dnd.processor.config;

/**
 * Base tuning config for all D&D Basic Set (Moldvay 1981) B-series modules.
 * Subclasses override only what differs for their specific module range.
 *
 * All pixel values assume 150 DPI rasterisation.
 */
public class DnD_BasicSet {

    // ── Ink detection ─────────────────────────────────────────────────────────

    /** Grayscale value below which a pixel is considered ink. */
    public int inkThreshold()           { return 200; }

    /** A row/column is "empty" if its ink count ≤ this fraction of its length. */
    public float maxInkFraction()       { return 0.005f; }

    /** Minimum ink pixels in a row/column to count as content. */
    public int minInkForContent()       { return 3; }

    /**
     * Column gutter ink tolerance: a vertical gap column is "empty" if its ink
     * count ≤ this fraction of the zone height.  Higher than {@link #maxInkFraction}
     * to tolerate full-width headings and illustration-edge bleed that inject a
     * few stray ink rows into an otherwise clean gutter.
     */
    public float maxColumnGapInkFraction() { return 0.03f; }

    // ── Column / gutter detection ─────────────────────────────────────────────

    /** Minimum width of a vertical column gutter in pixels (~0.07 in). */
    public int minVertGapPx()           { return 10; }

    /** Minimum height of a horizontal blank-line gap in pixels (~0.03 in). */
    public int minHorizGapPx()          { return 5; }

    /**
     * A vertical gap is only a column gutter if both sub-columns are at least
     * this fraction of the total content width.
     */
    public float minColumnFraction()    { return 0.25f; }

    /**
     * If one sub-column has more than this many times the ink density of the
     * other, the gap is treated as an indent (e.g. bullet numbers), not a gutter.
     * Raised to 10.0 from the original 5.0: pen-and-ink illustrations beside a
     * text column produce a density ratio of 4–8×, which was incorrectly filtered
     * as an indent gap.  Real bullet-number indents ("1." vs paragraph) produce
     * ratios of 15–30×, well above the new threshold.
     */
    public float maxInkDensityRatio()   { return 10.0f; }

    /**
     * If the text strip between two adjacent gaps has ink density below this
     * fraction of the sparser flanking column, the two gaps are merged
     * (handles centred section headings that bridge a column gutter).
     */
    public float maxBridgeDensityFraction() { return 0.3f; }

    // ── Footer isolation ──────────────────────────────────────────────────────

    /** Bottom cluster height ≤ this is absorbed into the bottom margin as footer. */
    public int footerClusterMaxPx()     { return 150; }

    /** Gap above the bottom cluster must be ≥ this to confirm a footer zone. */
    public int footerFirstGapMinPx()    { return 15; }

    /** Gap between footer elements must be ≥ this to confirm the content boundary. */
    public int footerContentGapMinPx()  { return 15; }

    /** Extra footer elements (page number, ornament) must be ≤ this tall. */
    public int footerExtraClusterMaxPx() { return 35; }

    /** Number of extra upward passes when absorbing footer ornaments. */
    public int footerExtraPasses()      { return 3; }

    /** Clusters ≤ this height are treated as noise/fragment rows unconditionally. */
    public int footerTinyClusterMaxPx() { return 5; }

    /** Pixels of padding added below the last detected ink row for bottom margin. */
    public int bottomMarginPaddingPx()  { return 3; }

    // ── Layout classification ─────────────────────────────────────────────────

    /**
     * Minimum height of a horizontal gap to qualify as a page-level row split
     * (TWO_ROW / TWO_BY_TWO). Ordinary section breaks are typically 5–25 px;
     * genuine row dividers are wider (~0.13 in at 150 DPI).
     */
    public int minRowSplitPx()          { return 20; }

    /** A page with ≥ this many horizontal gaps and no column gutters → TABLE. */
    public int minTableHorizGaps()      { return 10; }

    // ── Map detection ─────────────────────────────────────────────────────────

    /** Content ink density must exceed this fraction to classify as MAP. */
    public float minMapInkFraction()    { return 0.20f; }

    /** MAP pages may have at most this many horizontal gaps. */
    public int maxMapHorizGaps()        { return 3; }

    /** MAP pages may have at most this many vertical gaps (e.g. a legend column). */
    public int maxMapVertGaps()         { return 1; }

    /**
     * Alternate MAP path: if full-width IMAGE zones cover ≥ this fraction of the
     * content height AND ink density is high, classify as MAP.
     */
    public float mapImageZoneFraction() { return 0.50f; }

    // ── Map classification (MapClassifier) ─────────────────────────────────────

    /** Canny low threshold for map grid-line detection. */
    public float mapCannyLowThreshold()    { return 20f; }

    /** Canny high threshold for map grid-line detection. */
    public float mapCannyHighThreshold()   { return 60f; }

    /** Min Hough votes as fraction of min(width, height). */
    public float houghVoteFraction()       { return 0.15f; }

    /** Min normalized histogram value for an angle peak. */
    public float minPeakStrength()         { return 0.25f; }

    /** Degrees within which adjacent peaks are merged. */
    public int peakMergeWindow()           { return 3; }

    /** Angle tolerance for hex triplet matching (degrees). */
    public int hexAngleTolerance()         { return 8; }

    /** Angle tolerance for grid pair matching (degrees). */
    public int gridAngleTolerance()        { return 8; }

    /** Min peak strength for hex classification. */
    public float minHexPeak()              { return 0.30f; }

    /** Min peak strength for grid classification. */
    public float minGridPeak()             { return 0.35f; }

    /** Max coefficient of variation for regular grid spacing. */
    public float maxSpacingCV()            { return 0.35f; }

    /** Min parallel lines at a dominant angle to confirm a grid. */
    public int minRegularLines()           { return 4; }

    /** Minimum whitespace gap width as a multiple of grid spacing for section detection. */
    public float minSectionGapFactor()     { return 2.0f; }

    // ── Illustration zone detection ───────────────────────────────────────────

    /**
     * A row qualifies as full-width dense (Pass 0 bilateral check) only when
     * BOTH the left and right halves each exceed this fraction of the half-width.
     */
    public float fullPageIllustrationFraction() { return 0.15f; }

    /**
     * A row within a single column qualifies as column-dense (Pass 1.5) only
     * when its ink exceeds this fraction of the column width.
     */
    public float illustrationInkFraction() { return 0.15f; }

    /**
     * A contiguous run of dense rows must be at least this tall to be treated
     * as an illustration zone (~0.5 in at 150 DPI). Filters rule-lines and
     * table borders.
     */
    public int minIllustrationPx()      { return 80; }
}
