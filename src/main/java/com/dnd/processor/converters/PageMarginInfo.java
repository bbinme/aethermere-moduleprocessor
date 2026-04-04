package com.dnd.processor.converters;

/**
 * Per-page margin analysis result, used by the margin-detection phase.
 *
 * @param page      1-based page number
 * @param type      auto-detected layout type
 * @param fullBleed true if the content area covers ≥ 98% of the page (full-bleed image/border)
 * @param topIn     top margin width in inches
 * @param bottomIn  bottom margin width in inches
 * @param leftIn    left margin width in inches
 * @param rightIn   right margin width in inches
 */
public record PageMarginInfo(
        int page,
        ProjectionAnalyzer.LayoutType type,
        boolean fullBleed,
        float topIn,
        float bottomIn,
        float leftIn,
        float rightIn) {

    /** True if this page should be excluded from margin summary calculations. */
    public boolean excluded() {
        return type == ProjectionAnalyzer.LayoutType.MAP
            || type == ProjectionAnalyzer.LayoutType.FRONT_COVER
            || type == ProjectionAnalyzer.LayoutType.BACK_COVER
            || fullBleed;
    }
}
