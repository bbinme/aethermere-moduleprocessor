package com.dnd.processor.config;

/**
 * Tuning config for D&D Basic Set modules B1 through B4.
 *
 * These modules use a two-column body layout with occasional full-width section
 * breaks. The default {@link DnD_BasicSet#minRowSplitPx()} of 20 px is too small —
 * a full-width blank gap between two paragraphs or sections can exceed 20 px and
 * incorrectly trigger a TWO_ROW classification. Raising it to 40 px (~0.27 in)
 * ensures only genuine page-level row divisions (e.g. map + legend stacked
 * vertically) are classified as TWO_ROW.
 */
public class DnD_BasicSet_B1_4 extends DnD_BasicSet {

    @Override
    public int minRowSplitPx() { return 40; }
}
