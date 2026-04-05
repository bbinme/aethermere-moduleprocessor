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
 * Tests for B4 page 9 — a page with a wandering monster table at the top
 * and two-column text below.  This exercises TABLE zone detection and
 * verifies that table rows don't bleed into the text zone beneath.
 *
 * Writes bands image, layout PDF, and markdown to {@code test-output/}
 * for visual verification.
 */
class B4Page09Test {

    private static final File FIXTURE = new File("src/test/resources/B4-page09.pdf");
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
        ImageIO.write(bands, "PNG", OUTPUT_DIR.resolve("B4-page09-bands.png").toFile());

        // 3. Build layout PDF from zones
        int imgW = pageImage.getWidth(), imgH = pageImage.getHeight();
        Path layoutPdf = OUTPUT_DIR.resolve("B4-page09-layout.pdf");
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
            System.out.println("Page 9 layout: " + layout.type()
                    + " -> " + output.getNumberOfPages() + " sub-pages");
        }

        // 4. Extract markdown
        PdfConverter converter = new PdfConverter();
        ConversionResult result = converter.convert(layoutPdf, "glossaries/B4.txt");
        markdown = result.markdown();
        Files.writeString(OUTPUT_DIR.resolve("B4-page09.md"), markdown, StandardCharsets.UTF_8);

        // Debug
        System.out.println("Page 9 layout type: " + layout.type());
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
        File refFile = new File("src/test/resources/B4-page09-bands-expected.png");
        org.junit.jupiter.api.Assumptions.assumeTrue(refFile.exists(),
                "Reference bands image not found — copy test-output/B4-page09-bands.png to src/test/resources/B4-page09-bands-expected.png");

        BufferedImage expected = ImageIO.read(refFile);
        BufferedImage actual = ImageIO.read(OUTPUT_DIR.resolve("B4-page09-bands.png").toFile());

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
        Path expectedPath = Path.of("src/test/resources/B4-page09-expected.md");
        org.junit.jupiter.api.Assumptions.assumeTrue(expectedPath.toFile().exists(),
                "Expected markdown not found — copy test-output/B4-page09.md to src/test/resources/B4-page09-expected.md");

        String expected = Files.readString(expectedPath, StandardCharsets.UTF_8).strip();
        String actual = markdown.strip();
        assertEquals(expected, actual, "Markdown output differs from expected");
    }

    // ── Layout structure — TABLE zone ───────────────────────────────────────

    @Test
    void hasTableZone() {
        long tableZones = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TABLE).count();
        assertTrue(tableZones >= 1,
                "Should have at least 1 TABLE zone for the wandering monster table");
    }

    @Test
    void hasTextZoneBelowTable() {
        int tableBottom = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TABLE)
                .mapToInt(Zone::yBottom).max().orElse(-1);
        assertTrue(tableBottom > 0, "Should have a TABLE zone");

        long textZonesBelowTable = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .filter(z -> z.yTop() >= tableBottom)
                .count();
        assertTrue(textZonesBelowTable >= 1,
                "Should have at least 1 TEXT zone below the TABLE zone (table bottom=" + tableBottom + ")");
    }

    // ── Layout structure — TEXT zone ────────────────────────────────────────

    @Test
    void hasTextZoneWithTwoColumns() {
        long twoColZones = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .filter(z -> z.columns().size() == 2)
                .count();
        assertTrue(twoColZones >= 1,
                "Should have at least 1 two-column TEXT zone below the table");
    }

    @Test
    void tableZoneDoesNotOverlapTextZone() {
        for (Zone table : layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TABLE).toList()) {
            for (Zone text : layout.zones().stream()
                    .filter(z -> z.type() == ZoneType.TEXT).toList()) {
                boolean overlaps = table.yTop() < text.yBottom() && table.yBottom() > text.yTop();
                assertFalse(overlaps,
                        "TABLE zone [" + table.yTop() + "," + table.yBottom()
                                + ") overlaps TEXT zone [" + text.yTop() + "," + text.yBottom() + ")");
            }
        }
    }

    // ── Markdown content ────────────────────────────────────────────────────

    @Test
    void markdownHasSubstantialContent() {
        assertFalse(markdown.isBlank(), "Markdown should not be empty");
        assertTrue(markdown.length() > 200,
                "Should have substantial text (got " + markdown.length() + " chars)");
    }
}
