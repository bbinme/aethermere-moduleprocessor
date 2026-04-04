package com.dnd.processor.converters;

/**
 * A detected table region within a rasterised page image.
 *
 * All coordinates are in pixels relative to the full page image (150 DPI).
 *
 * @param xLeft        left edge of the table body
 * @param xRight       right edge of the table body
 * @param yTop         top of the table (title top if present, else rule top or body top)
 * @param yBottom      bottom of the table body
 * @param ruleYTop     top pixel row of the detected horizontal rule (-1 if no rule)
 * @param ruleYBottom  bottom pixel row of the detected horizontal rule (-1 if no rule)
 * @param titleYTop    top pixel row of the title cluster (-1 if no title found)
 * @param titleYBottom bottom pixel row of the title cluster (-1 if no title found)
 * @param style        structural style of the detected table
 * @param type         classification based on title text
 * @param title        extracted title text, or empty string if none found
 */
public record TableRegion(
        int xLeft,
        int xRight,
        int yTop,
        int yBottom,
        int ruleYTop,
        int ruleYBottom,
        int titleYTop,
        int titleYBottom,
        TableStyle style,
        TableType type,
        String title) {

    /** Structural style of the detected table. */
    public enum TableStyle {
        /** High confidence — a drawn horizontal rule separates header from data. */
        RULE_BASED,
        /** Lower confidence — no drawn rule; detected via vertical column gutter. */
        COLUMN_IMPLICIT,
        /** Lower confidence — numbered list (1–N entries) with a title above. */
        DIE_ROLL
    }

    public boolean hasRule()     { return ruleYTop  >= 0; }
    public boolean hasTitle()    { return titleYTop >= 0; }
    public boolean implicit()    { return style != TableStyle.RULE_BASED; }
}
