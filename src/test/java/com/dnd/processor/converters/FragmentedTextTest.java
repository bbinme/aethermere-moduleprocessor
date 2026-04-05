package com.dnd.processor.converters;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that character-fragmented PDF text is cleaned up into readable prose.
 *
 * The fixture fragmented-text.pdf contains text where every word is broken
 * into 1-3 character chunks (simulating old PDF glyph-per-text-object encoding).
 * The first paragraph is fragmented; the rest is clean.
 *
 * Expected: after conversion, the fragment spacing is removed and the text
 * reads as normal English prose.
 */
class FragmentedTextTest {

    private static final File FIXTURE = new File("src/test/resources/fragmented-text.pdf");
    private static final File EXPECTED_MD = new File("src/test/resources/fragmented-text-expected.md");
    private static final Path OUTPUT_DIR = Path.of("test-output");

    private static String markdown;

    @BeforeAll
    static void convertPdf() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(FIXTURE.exists(),
                "Fixture not found — run: gradlew test --tests \"*.generateFragmentedTextPdf\"");

        Files.createDirectories(OUTPUT_DIR);

        // Convert the PDF to markdown via PdfConverter
        PdfConverter converter = new PdfConverter();
        ConversionResult result = converter.convert(FIXTURE.toPath());
        markdown = result.markdown();

        Files.writeString(OUTPUT_DIR.resolve("fragmented-text.md"), markdown, StandardCharsets.UTF_8);
        System.out.println("--- Extracted markdown ---");
        System.out.println(markdown);
    }

    /**
     * Strip the YAML front-matter block (if any) so we compare only the body text.
     */
    private static String stripFrontMatter(String md) {
        if (md.startsWith("---")) {
            int end = md.indexOf("---", 3);
            if (end != -1) {
                return md.substring(end + 3).strip();
            }
        }
        return md.strip();
    }

    // ── Content checks ──────────────────────────────────────────────────────

    @Test
    void markdownIsNotEmpty() {
        assertFalse(markdown.isBlank(), "Markdown should not be empty");
    }

    @Test
    void fragmentedTextIsReconstructed() {
        String body = stripFrontMatter(markdown);
        // The fragmented first line should be reconstructed into whole words
        assertTrue(body.contains("The three factions do not get along well"),
                "Fragmented text should be reconstructed.\nActual start: "
                        + body.substring(0, Math.min(120, body.length())));
    }

    @Test
    void cleanTextIsPreserved() {
        String body = stripFrontMatter(markdown);
        // Non-fragmented text should pass through unchanged
        assertTrue(body.contains("The bickering between the three factions"),
                "Clean text should be preserved unchanged");
    }

    @Test
    void hyphenatedWordIsRejoined() {
        String body = stripFrontMatter(markdown);
        // "grea t-\nn es s" should become "greatness" (hyphen rejoined)
        assertTrue(body.contains("greatness"),
                "Hyphenated line-break 'great-ness' should be rejoined");
    }

    @Test
    void properNounsPreserved() {
        String body = stripFrontMatter(markdown);
        assertTrue(body.contains("Cynidicea"),
                "Proper noun 'Cynidicea' should be preserved");
    }

    @Test
    void noSpuriousBlankLines() {
        String body = stripFrontMatter(markdown);
        // There should not be blank lines between every line of text.
        // Consecutive non-blank lines should exist in paragraphs.
        String[] lines = body.split("\n");
        int consecutiveNonBlank = 0;
        int maxConsecutive = 0;
        for (String line : lines) {
            if (!line.isBlank()) {
                consecutiveNonBlank++;
                maxConsecutive = Math.max(maxConsecutive, consecutiveNonBlank);
            } else {
                consecutiveNonBlank = 0;
            }
        }
        assertTrue(maxConsecutive >= 3,
                "Should have runs of consecutive non-blank lines (paragraphs), "
                        + "but max consecutive was " + maxConsecutive);
    }

    // ── Full comparison ─────────────────────────────────────────────────────

    @Test
    void markdownMatchesExpected() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(EXPECTED_MD.exists(),
                "Expected markdown not found at " + EXPECTED_MD);
        String expected = Files.readString(EXPECTED_MD.toPath(), StandardCharsets.UTF_8);
        assertEquals(expected.strip(), stripFrontMatter(markdown),
                "Markdown body should match expected output");
    }
}
