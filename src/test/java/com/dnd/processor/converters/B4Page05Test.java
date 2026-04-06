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
 * Tests for B4 page 5.
 *
 * Writes bands images, layout PDF, and markdown to {@code test-output/}
 * for visual verification.
 */
class B4Page05Test {

    private static final File FIXTURE = new File("src/test/resources/B4-page05.pdf");
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

        // 2. Write bands images for each pass
        BufferedImage bands = analyzer.analyze(pageImage);
        ImageIO.write(bands, "PNG", OUTPUT_DIR.resolve("B4-page05-bands.png").toFile());

        var passes = analyzer.getPassZones();
        for (int pi = 0; pi < passes.size(); pi++) {
            BufferedImage passBands = analyzer.renderBands(pageImage, layout.margins(), passes.get(pi));

            java.awt.Graphics2D g2 = passBands.createGraphics();
            int ml = layout.margins().left(), mr = layout.margins().right();
            for (Zone z : passes.get(pi)) {
                int zt = z.yTop(), zh = z.yBottom() - z.yTop();
                if (z.type() == ZoneType.TABLE) {
                    g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.30f));
                    g2.setColor(java.awt.Color.MAGENTA);
                    g2.fillRect(ml, zt, mr - ml, zh);
                    g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f));
                    g2.setStroke(new java.awt.BasicStroke(3f));
                    g2.setColor(java.awt.Color.MAGENTA);
                    g2.drawRect(ml, zt, mr - ml, zh);
                    g2.setColor(java.awt.Color.RED);
                    g2.setStroke(new java.awt.BasicStroke(5f));
                    g2.drawLine(0, z.yBottom(), passBands.getWidth(), z.yBottom());
                    g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14));
                    g2.drawString("TABLE bottom = y=" + z.yBottom(), ml + 10, z.yBottom() - 8);
                    g2.setStroke(new java.awt.BasicStroke(1f));
                } else if (z.type() == ZoneType.TEXT && z.columns().size() >= 2) {
                    g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.15f));
                    g2.setColor(java.awt.Color.BLUE);
                    g2.fillRect(ml, zt, mr - ml, zh);
                } else if (z.type() == ZoneType.TEXT) {
                    g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.15f));
                    g2.setColor(java.awt.Color.GREEN);
                    g2.fillRect(ml, zt, mr - ml, zh);
                }
            }
            g2.dispose();

            String name = "B4-page05-bands-P" + (pi + 1) + ".png";
            ImageIO.write(passBands, "PNG", OUTPUT_DIR.resolve(name).toFile());
            System.out.println("Wrote " + name + " (" + passes.get(pi).size() + " zones)");
        }

        // 3. Build layout PDF from zones
        int imgW = pageImage.getWidth(), imgH = pageImage.getHeight();
        Path layoutPdf = OUTPUT_DIR.resolve("B4-page05-layout.pdf");
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
            System.out.println("Page 5 layout: " + layout.type()
                    + " -> " + output.getNumberOfPages() + " sub-pages");
        }

        // 4. Extract markdown
        PdfConverter converter = new PdfConverter();
        ConversionResult result = converter.convert(layoutPdf, "glossaries/B4.txt");
        markdown = result.markdown();
        Files.writeString(OUTPUT_DIR.resolve("B4-page05.md"), markdown, StandardCharsets.UTF_8);

        // Debug
        System.out.println("Page 5 layout type: " + layout.type());
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
        File refFile = new File("src/test/resources/B4-page05-bands-expected.png");
        org.junit.jupiter.api.Assumptions.assumeTrue(refFile.exists(),
                "Reference bands image not found — copy test-output/B4-page05-bands.png to src/test/resources/B4-page05-bands-expected.png");

        BufferedImage expected = ImageIO.read(refFile);
        BufferedImage actual = ImageIO.read(OUTPUT_DIR.resolve("B4-page05-bands.png").toFile());

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
        Path expectedPath = Path.of("src/test/resources/B4-page05-expected.md");
        org.junit.jupiter.api.Assumptions.assumeTrue(expectedPath.toFile().exists(),
                "Expected markdown not found — copy test-output/B4-page05.md to src/test/resources/B4-page05-expected.md");

        String expected = Files.readString(expectedPath, StandardCharsets.UTF_8).strip();
        String actual = markdown.strip();
        assertEquals(expected, actual, "Markdown output differs from expected");
    }

    // ── Layout structure ───────────────────────────────────────────────────

    @Test
    void hasAtLeastOneZone() {
        assertFalse(layout.zones().isEmpty(), "Should have at least one zone");
    }

    @Test
    void noZonesOverlap() {
        var zones = layout.zones();
        for (int i = 0; i < zones.size(); i++) {
            for (int j = i + 1; j < zones.size(); j++) {
                Zone a = zones.get(i), b = zones.get(j);
                boolean overlaps = a.yTop() < b.yBottom() && a.yBottom() > b.yTop();
                assertFalse(overlaps,
                        "Zone " + i + " [" + a.yTop() + "," + a.yBottom()
                                + ") overlaps Zone " + j + " [" + b.yTop() + "," + b.yBottom() + ")");
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
