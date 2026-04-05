package com.dnd.processor.utils;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.File;

/**
 * Generates a test PDF containing character-fragmented text.
 * Each small token is placed as a separate text object, simulating
 * the way some older PDF producers store glyphs individually.
 *
 * Run once: {@code gradlew test --tests "*.generateFragmentedTextPdf"}
 */
class GenerateFragmentedTestPdf {

    @Test
    void generateFragmentedTextPdf() throws Exception {
        // The fragmented text as it appears after extraction — each space-separated
        // token was a separate text object in the original PDF.
        String[] lines = {
            "T h e th ree fa c tion s d o n o t g et a long  w ell. E a ch  fa ction  is  s u re th at",
            "on ly its  m em b ers  k n ow  th e  p rop er  w a y to  res to re  th e  los t g rea t-",
            "n es s  o f C yn id ic ea . O ften , w h en  m em b e rs  o f d i ffe ren t fa c t io n s",
            "m e et , th e y  a rg u e  or  fig h t. It is  p os s ib le  fo r  th e  th re e  fa c tion s  to",
            "c o op e ra te , b u t s u ch  c o op era tion  is  ra re .",
            "",
            "The bickering between the three factions, and their attempts to",
            "restore sanity to Cynidicean society, give the DM the chance to",
            "add character interaction to the adventure. While the factions",
            "can be played as simple monsters with treasure, the DM and",
            "players can have a lot of fun with the plots and feuding of the",
            "factions. If this is done, the DM should plan in advance what the",
            "faction members may say or do if the party tries to talk, attack, or",
            "wait to see what the NPCs do first. It is important for the DM to",
            "avoid forcing the action to a pre-set conclusion—the actions of the",
            "players must be able to make a difference",
            "",
            "If the player characters join one of the factions, it will be easier for",
            "them to get supplies and rest between adventures. All the factions",
            "may accept player characters as members",
            "",
            "The Brotherhood of Gorm will take male fighters, male dwarves,",
            "male halflings, and male elves as full members.",
            "The Magi of Usamigaras will take any magic-user, elf, cleric, or thief. The Warrior",
            "Maidens will take female fighters, female elves, female dwarves,",
            "and female halflings as full members. Also, any character may",
            "become a lesser member of a faction, if desired. Factions will not",
            "do as much for lesser members, and a lesser member can never",
            "become powerful within a faction. The DM should decide how",
            "much a faction will do for its members.",
            "The Priests of Zargon are a fourth faction. They are found mainly in",
            "areas outside the basic adventure. The Priests of Zargon serve the",
            "evil monster Zargon and control the underground city.",

        };

        File outFile = new File("src/test/resources/fragmented-text.pdf");

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
            float fontSize = 10f;
            float leftMargin = 72f;  // 1 inch
            float topY = page.getMediaBox().getHeight() - 72f;  // 1 inch from top
            float lineSpacing = 14f;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
                    String line = lines[lineIdx];
                    // Skip blank lines (from trailing \n in input)
                    line = line.replace("\n", "").replace("\r", "");
                    if (line.isEmpty()) continue;

                    // Split into the individual fragments (space-separated tokens)
                    String[] fragments = line.split(" ");

                    float x = leftMargin;
                    float y = topY - (lineIdx * lineSpacing);

                    // Write each fragment as a separate text object, with a small
                    // gap between them — this is what causes PDFBox to extract
                    // spaces between every fragment.
                    for (String fragment : fragments) {
                        if (fragment.isEmpty()) {
                            // Double space in input — add extra gap
                            x += font.getStringWidth(" ") / 1000f * fontSize;
                            continue;
                        }
                        cs.beginText();
                        cs.setFont(font, fontSize);
                        cs.newLineAtOffset(x, y);
                        cs.showText(fragment);
                        cs.endText();

                        // Advance x by fragment width + a gap wide enough that PDFBox
                        // inserts a space between each fragment during extraction.
                        // A full space-width gap reliably triggers PDFBox's word separator.
                        float fragmentWidth = font.getStringWidth(fragment) / 1000f * fontSize;
                        float spaceWidth = font.getStringWidth(" ") / 1000f * fontSize;
                        x += fragmentWidth + spaceWidth;
                    }
                }
            }

            doc.save(outFile);
        }

        System.out.println("Generated: " + outFile.getAbsolutePath());
    }
}
