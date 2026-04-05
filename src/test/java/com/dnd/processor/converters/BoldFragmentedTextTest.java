package com.dnd.processor.converters;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that bold-fragmented PDF text is extracted with correct bold markers.
 * The fixture PDF has every character as a separate text object, with bold
 * sections using Times-Bold font.
 *
 * Expected: bold markers (**) wrap the correct words, fragmented text is
 * reconstructed, and no interleaving of ** with character fragments.
 *
 * Writes markdown to {@code test-output/} for visual verification.
 */
class BoldFragmentedTextTest {

    private static final File FIXTURE = new File("src/test/resources/bold-fragmented-text.pdf");
    private static final Path OUTPUT_DIR = Path.of("test-output");
    private static String markdown;

    @BeforeAll
    static void extract() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(FIXTURE.exists(),
                "Fixture not found — run: gradlew test --tests \"*.generateBoldFragmentedTextPdf\"");

        Files.createDirectories(OUTPUT_DIR);

        PdfConverter converter = new PdfConverter();
        ConversionResult result = converter.convert(FIXTURE.toPath(), "glossaries/B4.txt");
        markdown = result.markdown();

        Files.writeString(OUTPUT_DIR.resolve("bold-fragmented-text.md"),
                markdown, StandardCharsets.UTF_8);

        System.out.println("=== Bold fragmented text output ===");
        System.out.println(markdown);
    }

    // ── Bold marker placement ───────────────────────────────────────────────

    @Test
    void goblinIsBold() {
        assertTrue(markdown.contains("**Goblin.**") || markdown.contains("**Goblin.** "),
                "'Goblin.' should be bold: " + firstLine());
    }

    @Test
    void dndBasicIsBold() {
        // D&D Basic should be inside ** markers (content may be fragmented)
        assertTrue(markdown.matches("(?s).*\\*\\*D.*ic\\*\\*.*"),
                "D&D Basic should be wrapped in bold markers: " + firstLine());
    }

    @Test
    void undergroundCityIsBold() {
        assertTrue(markdown.contains("**Underground City**"),
                "Underground City should be bold");
    }

    @Test
    void zargonIsBold() {
        assertTrue(markdown.contains("**Zargon**"),
                "Zargon should be bold");
    }

    // ── No interleaving ─────────────────────────────────────────────────────

    @Test
    void noBoldInterleaving() {
        assertFalse(markdown.contains("** **"),
                "Should not contain interleaved bold markers '** **'");
        int count = 0;
        int idx = 0;
        while ((idx = markdown.indexOf("* *", idx)) >= 0) {
            count++;
            idx += 3;
        }
        assertTrue(count < 3,
                "Should not contain many '* *' patterns (found " + count + ")");
    }

    // ── Text reconstruction ─────────────────────────────────────────────────

    @Test
    void textIsReconstructed() {
        assertTrue(markdown.contains("Goblins"),
                "Should contain 'Goblins' (not fragmented)");
        assertTrue(markdown.contains("described"),
                "Should contain 'described' (not fragmented)");
        assertTrue(markdown.contains("goblins"),
                "Should contain 'goblins' (not fragmented)");
    }

    @Test
    void containsAllSections() {
        String stripped = markdown.replaceAll("\\s+", " ").toLowerCase();
        assertTrue(stripped.contains("section 2"), "Should contain Section 2");
        assertTrue(stripped.contains("section 3"), "Should contain Section 3");
    }

    // ── Markdown matches reference ──────────────────────────────────────────

    @Test
    void markdownMatchesExpected() throws Exception {
        Path expectedPath = Path.of("src/test/resources/bold-fragmented-text-expected.md");
        org.junit.jupiter.api.Assumptions.assumeTrue(expectedPath.toFile().exists(),
                "Expected markdown not found — copy test-output/bold-fragmented-text.md to src/test/resources/bold-fragmented-text-expected.md");

        String expected = Files.readString(expectedPath, StandardCharsets.UTF_8).strip();
        String actual = markdown.strip();
        assertEquals(expected, actual, "Markdown output differs from expected");
    }

    private String firstLine() {
        int nl = markdown.indexOf('\n');
        return nl > 0 ? markdown.substring(0, nl) : markdown;
    }
}
