package com.dnd.processor.converters;

import com.dnd.processor.config.DnD_BasicSet_B1_4;
import com.dnd.processor.converters.ProjectionAnalyzer.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for B4 page 6 — a two-column page with the wandering monster
 * table descriptions on the left and room 1 (Statue Room) key on the right.
 *
 * This page was previously misclassified as FRONT_COVER, causing no column
 * splitting and interleaved text from both columns.
 */
class B4Page06Test {

    private static final File FIXTURE = new File("src/test/resources/B4-page06.pdf");
    private static final Path OUTPUT_DIR = Path.of("test-output");
    private static final int DPI = 150;
    private static final int SUB_PAGE_MARGIN_PX = 12;

    private static PageLayout layout;
    private static String markdown;

    @BeforeAll
    static void analyzeAndExtract() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(FIXTURE.exists(),
                "Fixture not found — run: gradlew test --tests \"*.extractB4Pages\"");

        Files.createDirectories(OUTPUT_DIR);
        DnD_BasicSet_B1_4 config = new DnD_BasicSet_B1_4();
        ProjectionAnalyzer analyzer = new ProjectionAnalyzer(config);

        // 1. Render and analyze layout
        BufferedImage pageImage;
        try (PDDocument doc = Loader.loadPDF(FIXTURE)) {
            pageImage = new PDFRenderer(doc).renderImageWithDPI(0, DPI, ImageType.RGB);
        }
        layout = analyzer.analyzeLayout(pageImage);

        // 2. Write bands image for visual verification
        BufferedImage bands = analyzer.analyze(pageImage);
        ImageIO.write(bands, "PNG", OUTPUT_DIR.resolve("B4-page06-bands.png").toFile());

        // 3. Build layout PDF from zones
        int imgW = pageImage.getWidth(), imgH = pageImage.getHeight();
        Path layoutPdf = OUTPUT_DIR.resolve("B4-page06-layout.pdf");
        try (PDDocument source = Loader.loadPDF(FIXTURE);
             PDDocument output = new PDDocument()) {
            PDPage srcPage = source.getPage(0);
            PDRectangle med = srcPage.getMediaBox();
            float scaleX = med.getWidth() / imgW;
            float scaleY = med.getHeight() / imgH;

            for (Zone zone : layout.zones()) {
                if (zone.type() == ZoneType.IMAGE || zone.columns().isEmpty()) {
                    addSubPage(output, source, med, imgW, imgH, scaleX, scaleY,
                            layout.margins().left(), zone.yTop(),
                            layout.margins().right(), zone.yBottom());
                } else {
                    for (Column col : zone.columns()) {
                        for (ColumnZone cz : col.subZones()) {
                            addSubPage(output, source, med, imgW, imgH, scaleX, scaleY,
                                    col.xLeft(), cz.yTop(), col.xRight(), cz.yBottom());
                        }
                    }
                }
            }
            output.save(layoutPdf.toFile());
            System.out.println("Page 6 layout: " + layout.type()
                    + " -> " + output.getNumberOfPages() + " sub-pages");
        }

        // 4. Extract markdown
        PdfConverter converter = new PdfConverter();
        ConversionResult result = converter.convert(layoutPdf, "glossaries/B4.txt");
        markdown = result.markdown();
        Files.writeString(OUTPUT_DIR.resolve("B4-page06.md"), markdown, StandardCharsets.UTF_8);

        // Debug
        System.out.println("Page 6 layout type: " + layout.type());
        System.out.println("Zones: " + layout.zones().size());
        for (int i = 0; i < layout.zones().size(); i++) {
            Zone z = layout.zones().get(i);
            System.out.println("  Zone " + i + ": " + z.type()
                    + " y=[" + z.yTop() + "," + z.yBottom() + ")"
                    + " cols=" + z.columns().size());
        }
    }

    private static void addSubPage(PDDocument output, PDDocument source,
                                    PDRectangle med, int imgW, int imgH,
                                    float scaleX, float scaleY,
                                    int imgLeft, int imgTop, int imgRight, int imgBottom)
            throws java.io.IOException {
        PDPage p = output.importPage(source.getPage(0));
        int l = Math.max(0, imgLeft - SUB_PAGE_MARGIN_PX);
        int t = Math.max(0, imgTop - SUB_PAGE_MARGIN_PX);
        int r = Math.min(imgW, imgRight + SUB_PAGE_MARGIN_PX);
        int b = Math.min(imgH, imgBottom + SUB_PAGE_MARGIN_PX);
        PDRectangle box = new PDRectangle(
                med.getLowerLeftX() + l * scaleX,
                med.getLowerLeftY() + (imgH - b) * scaleY,
                (r - l) * scaleX, (b - t) * scaleY);
        p.setMediaBox(box);
        p.setCropBox(box);
    }

    // ── Bands image matches reference ────────────────────────────────────────

    @Test
    void bandsImageMatchesReference() throws Exception {
        File refFile = new File("src/test/resources/B4-page06-bands-expected.png");
        org.junit.jupiter.api.Assumptions.assumeTrue(refFile.exists(),
                "Reference bands image not found — copy test-output/B4-page06-bands.png to src/test/resources/B4-page06-bands-expected.png");

        BufferedImage expected = ImageIO.read(refFile);
        BufferedImage actual = ImageIO.read(OUTPUT_DIR.resolve("B4-page06-bands.png").toFile());

        assertEquals(expected.getWidth(), actual.getWidth(), "Bands image width mismatch");
        assertEquals(expected.getHeight(), actual.getHeight(), "Bands image height mismatch");

        int mismatched = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y)) mismatched++;
            }
        }
        assertEquals(0, mismatched,
                "Bands image differs from reference in " + mismatched + " pixels");
    }

    // ── Markdown matches reference ──────────────────────────────────────────

    @Test
    void markdownMatchesExpected() throws Exception {
        Path expectedPath = Path.of("src/test/resources/B4-page06-expected.md");
        org.junit.jupiter.api.Assumptions.assumeTrue(expectedPath.toFile().exists(),
                "Expected markdown not found — copy test-output/B4-page06.md to src/test/resources/B4-page06-expected.md");

        String expected = Files.readString(expectedPath, StandardCharsets.UTF_8).strip();
        String actual = markdown.strip();
        assertEquals(expected, actual, "Markdown output differs from expected");
    }

    // ── Layout structure ─────────────────────────────────────────────────────

    @Test
    void layoutHasTwoColumnZones() {
        long twoColZones = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .filter(z -> z.columns().size() == 2)
                .count();
        assertTrue(twoColZones >= 1,
                "Should have at least 1 two-column TEXT zone, layout type: " + layout.type());
    }

    @Test
    void hasAtLeastOneTextZone() {
        long textZones = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT).count();
        assertTrue(textZones >= 1, "Should have at least 1 TEXT zone");
    }

    @Test
    void producesMultipleSubPages() {
        // With two-column zones, we should get more than 1 sub-page
        long totalSubZones = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .flatMap(z -> z.columns().stream())
                .flatMap(c -> c.subZones().stream())
                .count();
        assertTrue(totalSubZones > 2,
                "Should produce multiple sub-pages from column splitting, got " + totalSubZones);
    }

    // ── Column separation (no interleaving) ─────────────────────────────────

    @Test
    void leftColumnContainsMonsterDescriptions() {
        // The left column has the wandering monster table descriptions
        // (Centipede, Cave Locust, Cynidicean, Ferret, Gnome, Goblin)
        String stripped = markdown.replaceAll("\\s+", "").toLowerCase();
        assertTrue(stripped.contains("cavelocust"),
                "Should contain Cave Locust description");
    }

    @Test
    void rightColumnContainsStatueRoom() {
        // The right column has room 1: Statue Room
        String stripped = markdown.replaceAll("[\\s*]+", "").toUpperCase();
        assertTrue(stripped.contains("STATUEROOM"),
                "Should contain '1. STATUE ROOM' heading");
    }

    @Test
    void columnsAreNotInterleaved() {
        // The key test: if columns are properly separated, "Centipede" text
        // and "STATUE ROOM" text should NOT appear on the same line.
        // When interleaved, monster descriptions and room text get mixed.
        String[] lines = markdown.split("\n");
        for (String line : lines) {
            String lower = line.toLowerCase();
            boolean hasMonster = lower.contains("centipede") || lower.contains("cave locust");
            boolean hasRoom = lower.contains("statue room") || lower.contains("pyramid");
            assertFalse(hasMonster && hasRoom,
                    "Columns are interleaved — monster and room text on same line: " + line);
        }
    }

    @Test
    void markdownHasSubstantialContent() {
        assertFalse(markdown.isBlank(), "Markdown should not be empty");
        assertTrue(markdown.length() > 500,
                "Should have substantial text (got " + markdown.length() + " chars)");
    }
}
