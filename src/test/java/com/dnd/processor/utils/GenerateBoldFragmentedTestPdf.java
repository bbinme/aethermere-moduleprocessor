package com.dnd.processor.utils;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a test PDF with bold-interleaved fragmented text.
 * Each character is a separate text object. Bold sections (marked with
 * ** in the source) use Times-Bold; normal text uses Times-Roman.
 *
 * Run once: {@code gradlew test --tests "*.generateBoldFragmentedTextPdf"}
 */
class GenerateBoldFragmentedTestPdf {

    @Test
    void generateBoldFragmentedTextPdf() throws Exception {
        // Source text with ** marking bold sections. Three sections separated
        // by blank lines to produce three rows in the layout.
        String source =
            "**Goblin.** Goblins are described in both editions of the **D&D Basic**\n" +
            "rules. The goblins live in caves across the underground lake from\n" +
            "the Cynidiceans (the **Underground City** map, **O**). The goblins\n" +
            "serve **Zargon** (Part 5, room **100**). If no Cynidiceans volunteer to\n" +
            "go to Zargon, the goblins kidnap victims to feed the monster.\n" +
            "Otherwise, the goblins do not usually harm Cynidiceans. After all, if\n" +
            "the Cynidiceans died out, Zargon would start eating goblins!\n" +
            "\n\n\n" +
            "Section 2.\n" +
            "\n\n\n" +
            "Section 3.";

        File outFile = new File("src/test/resources/bold-fragmented-text.pdf");

        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
        float fontSize = 10f;
        float leftMargin = 72f;
        float lineSpacing = 14f;

        // Parse the source into spans of (text, isBold)
        List<Span> spans = parseSpans(source);

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            float x = leftMargin;
            float y = page.getMediaBox().getHeight() - 72f;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (Span span : spans) {
                    PDType1Font font = span.bold ? bold : regular;

                    for (int i = 0; i < span.text.length(); i++) {
                        char c = span.text.charAt(i);

                        if (c == '\n') {
                            // Line break — move to next line
                            x = leftMargin;
                            y -= lineSpacing;
                            continue;
                        }

                        String ch = String.valueOf(c);
                        cs.beginText();
                        cs.setFont(font, fontSize);
                        cs.newLineAtOffset(x, y);
                        cs.showText(ch);
                        cs.endText();

                        // Advance by character width + space gap (to create fragmentation)
                        float charWidth = font.getStringWidth(ch) / 1000f * fontSize;
                        float spaceWidth = font.getStringWidth(" ") / 1000f * fontSize;
                        x += charWidth + spaceWidth;
                    }
                }
            }

            doc.save(outFile);
        }

        System.out.println("Generated: " + outFile.getAbsolutePath());
    }

    /** Parses "normal **bold** normal" into spans. */
    private List<Span> parseSpans(String text) {
        List<Span> spans = new ArrayList<>();
        boolean inBold = false;
        int start = 0;
        int i = 0;
        while (i < text.length() - 1) {
            if (text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                if (i > start) {
                    spans.add(new Span(text.substring(start, i), inBold));
                }
                inBold = !inBold;
                i += 2;
                start = i;
            } else {
                i++;
            }
        }
        if (start < text.length()) {
            spans.add(new Span(text.substring(start), inBold));
        }
        return spans;
    }

    private record Span(String text, boolean bold) {}
}
