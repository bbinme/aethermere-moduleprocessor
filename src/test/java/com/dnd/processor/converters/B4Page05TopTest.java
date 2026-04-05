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
 * B4 page 5, top section — single-column narrative text.
 *
 * "Days ago, your group of adventurers..." through the "Players' Background"
 * heading and the introductory paragraphs above the illustration.
 *
 * Uses the cropped fixture B4-page05-top.pdf.
 * Expected: single TEXT zone, 1 column, no illustrations, several horizontal gaps.
 */
class B4Page05TopTest {

    private static final File FIXTURE = new File("src/test/resources/B4-page05-top.pdf");
    private static final File EXPECTED_MD = new File("src/test/resources/B4-page05-top-expected.md");
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
        ImageIO.write(bands, "PNG", OUTPUT_DIR.resolve("B4-page05-top-bands.png").toFile());

        // 3. Build layout PDF from zones
        int imgW = pageImage.getWidth(), imgH = pageImage.getHeight();
        Path layoutPdf = OUTPUT_DIR.resolve("B4-page05-top-layout.pdf");
        try (PDDocument source = Loader.loadPDF(FIXTURE);
             PDDocument output = new PDDocument()) {
            PDRectangle med = source.getPage(0).getMediaBox();
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
        }

        // 4. Extract markdown
        PdfConverter converter = new PdfConverter();
        ConversionResult result = converter.convert(layoutPdf);
        markdown = result.markdown();
        Files.writeString(OUTPUT_DIR.resolve("B4-page05-top.md"), markdown, StandardCharsets.UTF_8);

        // Debug dump
        System.out.println("Page 5 top layout: " + layout.type());
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

    // ── Layout structure ─────────────────────────────────────────────────────

    @Test
    void hasTextZone() {
        long textZones = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT).count();
        assertTrue(textZones >= 1, "Should have at least 1 TEXT zone");
    }

    @Test
    void topZoneIsSingleColumn() {
        Zone firstText = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .findFirst().orElseThrow();
        assertEquals(1, firstText.columns().size(),
                "Top narrative zone should be single-column");
    }

    @Test
    void noIllustrations() {
        boolean hasImage = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .flatMap(z -> z.columns().stream())
                .flatMap(c -> c.subZones().stream())
                .anyMatch(cz -> cz.type() == ZoneType.IMAGE);
        assertFalse(hasImage, "Top section should have no IMAGE sub-zones");
    }

    @Test
    void hasHorizontalGaps() {
        long gaps = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .flatMap(z -> z.columns().stream())
                .flatMap(c -> c.subZones().stream())
                .filter(cz -> cz.type() == ZoneType.TEXT)
                .mapToLong(cz -> cz.horizGaps().size())
                .sum();
        assertTrue(gaps >= 1, "Should have at least 1 horizontal gap");
    }

    // ── Bands image matches reference ────────────────────────────────────────

    @Test
    void bandsImageMatchesReference() throws Exception {
        File refFile = new File("src/test/resources/B4-page05-top-bands-expected.png");
        org.junit.jupiter.api.Assumptions.assumeTrue(refFile.exists(),
                "Reference bands image not found — copy test-output/B4-page05-top-bands.png to src/test/resources/B4-page05-top-bands-expected.png");

        BufferedImage expected = ImageIO.read(refFile);
        BufferedImage actual = ImageIO.read(OUTPUT_DIR.resolve("B4-page05-top-bands.png").toFile());

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

    // ── Markdown content ─────────────────────────────────────────────────────

    @Test
    void containsNarrativeOpening() {
        assertTrue(markdown.contains("adventurers"),
                "Should contain narrative opening text");
    }

    @Test
    void containsPlayersBackground() {
        assertTrue(markdown.contains("Players") || markdown.contains("Background"),
                "Should contain 'Players' Background' heading");
    }

    @Test
    void markdownIsNotEmpty() {
        assertFalse(markdown.isBlank(), "Markdown should not be empty");
        assertTrue(markdown.length() > 100,
                "Should have substantial text (got " + markdown.length() + " chars)");
    }

    @Test
    void markdownMatchesExpected() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(EXPECTED_MD.exists(),
                "Expected markdown file not found");
        String expected = Files.readString(EXPECTED_MD.toPath(), StandardCharsets.UTF_8);
        assertEquals(expected.strip(), markdown.strip(),
                "Markdown should match expected output");
    }
}
