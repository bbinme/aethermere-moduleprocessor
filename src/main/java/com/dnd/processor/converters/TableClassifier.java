package com.dnd.processor.converters;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.Locale;

/**
 * Pass 5–6: extracts the title text for a detected table region and classifies the
 * table type using keyword matching.
 *
 * Title text is extracted by converting the image-space title bounds to PDF point
 * coordinates and using {@link PDFTextStripperByArea}.
 */
public class TableClassifier {

    /**
     * Extracts the title text for the given region and returns a new {@link TableRegion}
     * with {@code title} and {@code type} populated.
     *
     * @param region    detected region (title bounds in image pixels)
     * @param doc       source PDF document
     * @param pageIndex 0-based page index
     * @param imgW      width of the rasterised page image in pixels
     * @param imgH      height of the rasterised page image in pixels
     * @param dpi       rasterisation DPI (used for coordinate scaling)
     */
    public TableRegion classify(TableRegion region, PDDocument doc, int pageIndex,
                                 int imgW, int imgH, float dpi) {
        String title = "";
        if (region.hasTitle()) {
            title = extractTitle(region, doc, pageIndex, imgW, imgH);
        }
        TableType type = classify(title);
        return new TableRegion(
                region.xLeft(), region.xRight(),
                region.yTop(), region.yBottom(),
                region.ruleYTop(), region.ruleYBottom(),
                region.titleYTop(), region.titleYBottom(),
                region.style(),
                type,
                title.strip());
    }

    // ── Title text extraction ─────────────────────────────────────────────────

    private String extractTitle(TableRegion region, PDDocument doc, int pageIndex,
                                 int imgW, int imgH) {
        try {
            PDPage page = doc.getPage(pageIndex);
            PDRectangle mediaBox = page.getMediaBox();

            // Scale: image pixel → PDF points
            // Image y=0 is page top; PDF y=0 is page bottom — flip Y axis
            float scaleX = mediaBox.getWidth()  / imgW;
            float scaleY = mediaBox.getHeight() / imgH;

            float pdfLeft   = mediaBox.getLowerLeftX() + region.xLeft()        * scaleX;
            float pdfRight  = mediaBox.getLowerLeftX() + region.xRight()       * scaleX;
            float pdfBottom = mediaBox.getLowerLeftY() + (imgH - region.titleYBottom()) * scaleY;
            float pdfTop    = mediaBox.getLowerLeftY() + (imgH - region.titleYTop())    * scaleY;

            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);
            stripper.addRegion("title", new Rectangle2D.Float(
                    pdfLeft, pdfBottom, pdfRight - pdfLeft, pdfTop - pdfBottom));
            stripper.extractRegions(page);
            return stripper.getTextForRegion("title").strip();
        } catch (IOException e) {
            return "";
        }
    }

    // ── Keyword classification ────────────────────────────────────────────────

    public TableType classify(String title) {
        if (title == null || title.isBlank()) return TableType.UNKNOWN_TABLE;

        String t = title.toLowerCase(Locale.ROOT);

        if (contains(t, "wandering") || (contains(t, "random") && contains(t, "encounter")))
            return TableType.WANDERING_MONSTER_TABLE;

        if (contains(t, "encounter") && contains(t, "table"))
            return TableType.ENCOUNTER_TABLE;

        if (contains(t, "rumor") || contains(t, "rumour")
                || contains(t, "legend") || contains(t, "lore"))
            return TableType.RUMOR_TABLE;

        if (contains(t, "saving throw") || (contains(t, "save") && contains(t, "table")))
            return TableType.SAVING_THROW_TABLE;

        if (contains(t, "magic item"))
            return TableType.MAGIC_ITEM_TABLE;

        if (contains(t, "spell"))
            return TableType.SPELL_TABLE;

        if (contains(t, "weapon") || contains(t, "damage"))
            return TableType.WEAPON_TABLE;

        if (contains(t, "armor") || contains(t, "armour"))
            return TableType.ARMOR_TABLE;

        if (contains(t, "equipment") || contains(t, "pack") || contains(t, "gear"))
            return TableType.EQUIPMENT_TABLE;

        if (contains(t, "treasure"))
            return TableType.TREASURE_TABLE;

        if (contains(t, "experience") || contains(t, " xp ") || t.startsWith("xp "))
            return TableType.EXPERIENCE_TABLE;

        if (contains(t, "ability") || contains(t, "pregen") || contains(t, "pre-gen")
                || contains(t, "strength") || contains(t, "prerolled"))
            return TableType.ABILITY_SCORE_TABLE;

        if (contains(t, "henchm") || contains(t, "hireling") || contains(t, "retainer"))
            return TableType.HENCHMAN_TABLE;

        if (contains(t, "personality") || contains(t, "morale") || contains(t, "loyalty"))
            return TableType.NPC_TRAIT_TABLE;

        if (contains(t, "character class"))
            return TableType.CHARACTER_CLASS_TABLE;

        return TableType.GENERIC_TABLE;
    }

    private static boolean contains(String text, String keyword) {
        return text.contains(keyword);
    }
}
