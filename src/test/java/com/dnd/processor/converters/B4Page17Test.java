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
 * Tests for B4 page 16.
 *
 * Writes bands images, layout PDF, and markdown to {@code test-output/}
 * for visual verification.
 */
class B4Page17Test {

    private static final File FIXTURE = new File("src/test/resources/B4-page17.pdf");
    private static final Path OUTPUT_DIR = Path.of("test-output");
    private static final int DPI = 150;
    private static final int SUB_PAGE_MARGIN_PX = 12;

    private static final int MAX_PHASE = Integer.parseInt(
            System.getProperty("phase", "4"));

    private static PageLayout layout;
    private static String markdown;

    @BeforeAll
    static void analyzeAndExtract() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(FIXTURE.exists(),
                "Fixture not found — run: gradlew test --tests \"*.extractB4Pages\"");

        Files.createDirectories(OUTPUT_DIR);
        DnD_BasicSet_B1_4 config = new DnD_BasicSet_B1_4();
        ProjectionAnalyzer analyzer = new ProjectionAnalyzer(config);

        // Phase 1: Render and analyze layout
        BufferedImage pageImage;
        try (PDDocument doc = Loader.loadPDF(FIXTURE)) {
            pageImage = new PDFRenderer(doc).renderImageWithDPI(0, DPI, ImageType.RGB);
        }
        layout = analyzer.analyzeLayout(pageImage);

        // Write zone-overlay image (always, even phase 1)
        {
            BufferedImage overlay = new BufferedImage(
                    pageImage.getWidth(), pageImage.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = overlay.createGraphics();
            g.drawImage(pageImage, 0, 0, null);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

            java.awt.Color[] zoneColors = {
                    java.awt.Color.RED, java.awt.Color.BLUE,
                    java.awt.Color.GREEN, new java.awt.Color(255, 140, 0),
                    java.awt.Color.MAGENTA, java.awt.Color.CYAN
            };
            int ml = layout.margins().left(), mr = layout.margins().right();

            for (int zi = 0; zi < layout.zones().size(); zi++) {
                Zone z = layout.zones().get(zi);
                java.awt.Color c = zoneColors[zi % zoneColors.length];
                int zt = z.yTop(), zb = z.yBottom();

                // zone fill
                g.setComposite(java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, 0.15f));
                g.setColor(c);
                g.fillRect(ml, zt, mr - ml, zb - zt);

                // zone border
                g.setComposite(java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, 0.9f));
                g.setStroke(new java.awt.BasicStroke(3f));
                g.setColor(c);
                g.drawRect(ml, zt, mr - ml, zb - zt);

                // label
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14));
                String label = "Z" + zi + " " + z.type()
                        + " y=[" + zt + "," + zb + ") cols=" + z.columns().size();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(ml + 2, zt + 2, g.getFontMetrics().stringWidth(label) + 6, 18);
                g.setColor(c);
                g.drawString(label, ml + 5, zt + 16);

                // column dividers
                g.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_BUTT,
                        java.awt.BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f));
                for (Column col : z.columns()) {
                    g.setColor(c.darker());
                    g.drawLine(col.xLeft(), zt, col.xLeft(), zb);
                    g.drawLine(col.xRight(), zt, col.xRight(), zb);
                }
            }

            // margin lines
            g.setComposite(java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.SRC_OVER, 0.5f));
            g.setColor(java.awt.Color.YELLOW);
            g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT,
                    java.awt.BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g.drawLine(ml, 0, ml, overlay.getHeight());
            g.drawLine(mr, 0, mr, overlay.getHeight());
            g.drawLine(0, layout.margins().top(), overlay.getWidth(), layout.margins().top());
            g.drawLine(0, layout.margins().bottom(), overlay.getWidth(), layout.margins().bottom());

            g.dispose();
            ImageIO.write(overlay, "JPEG", OUTPUT_DIR.resolve("B4-page17-zones.jpg").toFile());
            System.out.println("Wrote B4-page17-zones.jpg");
        }

        // Phase 2: Write bands images for each pass
        if (MAX_PHASE >= 2) {
            BufferedImage bands = analyzer.analyze(pageImage);
            ImageIO.write(bands, "PNG", OUTPUT_DIR.resolve("B4-page17-bands.png").toFile());

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

                String name = "B4-page17-bands-P" + (pi + 1) + ".png";
                ImageIO.write(passBands, "PNG", OUTPUT_DIR.resolve(name).toFile());
                System.out.println("Wrote " + name + " (" + passes.get(pi).size() + " zones)");
            }
        }

        // Phase 3: Build layout PDF from zones
        if (MAX_PHASE >= 3) {
            int imgW = pageImage.getWidth(), imgH = pageImage.getHeight();
            Path layoutPdf = OUTPUT_DIR.resolve("B4-page17-layout.pdf");
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
                System.out.println("Page 17 layout: " + layout.type()
                        + " -> " + output.getNumberOfPages() + " sub-pages");
            }

            // Phase 4: Extract markdown
            if (MAX_PHASE >= 4) {
                Path layoutPdf2 = OUTPUT_DIR.resolve("B4-page17-layout.pdf");
                PdfConverter converter = new PdfConverter();
                ConversionResult result = converter.convert(layoutPdf2, "glossaries/B4.txt");
                markdown = result.markdown();
                Files.writeString(OUTPUT_DIR.resolve("B4-page17.md"), markdown, StandardCharsets.UTF_8);
            }
        }

        // Debug
        System.out.println("Page 17 layout type: " + layout.type());
        System.out.println("Zones: " + layout.zones().size());
        for (int i = 0; i < layout.zones().size(); i++) {
            Zone z = layout.zones().get(i);
            System.out.println("  Zone " + i + ": " + z.type()
                    + " y=[" + z.yTop() + "," + z.yBottom() + ")"
                    + " cols=" + z.columns().size());
        }

        // Dump horizontal projection in Z3 region to find gaps
        int ml = layout.margins().left(), mr = layout.margins().right();
        int contentW = mr - ml;
        System.out.println("\n--- Horizontal projection y=[750,1439) ---");
        for (int y = 750; y < Math.min(1439, pageImage.getHeight()); y++) {
            int ink = 0;
            for (int x = ml; x < mr; x++) {
                int rgb = pageImage.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g2 = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < 128 && g2 < 128 && b < 128) ink++;
            }
            if (ink <= (int)(contentW * 0.02)) {
                System.out.printf("  y=%4d ink=%3d (%.1f%%) ← LOW%n", y, ink, 100.0 * ink / contentW);
            }
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
        org.junit.jupiter.api.Assumptions.assumeTrue(MAX_PHASE >= 2,
                "Skipped — phase < 2");
        File refFile = new File("src/test/resources/B4-page17-bands-expected.png");
        org.junit.jupiter.api.Assumptions.assumeTrue(refFile.exists(),
                "Reference bands image not found — copy test-output/B4-page17-bands.png to src/test/resources/B4-page17-bands-expected.png");

        BufferedImage expected = ImageIO.read(refFile);
        BufferedImage actual = ImageIO.read(OUTPUT_DIR.resolve("B4-page17-bands.png").toFile());

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
        org.junit.jupiter.api.Assumptions.assumeTrue(MAX_PHASE >= 4,
                "Skipped — phase < 4");
        Path expectedPath = Path.of("src/test/resources/B4-page17-expected.md");
        org.junit.jupiter.api.Assumptions.assumeTrue(expectedPath.toFile().exists(),
                "Expected markdown not found — copy test-output/B4-page17.md to src/test/resources/B4-page17-expected.md");

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
        org.junit.jupiter.api.Assumptions.assumeTrue(MAX_PHASE >= 4,
                "Skipped — phase < 4");
        assertFalse(markdown.isBlank(), "Markdown should not be empty");
        assertTrue(markdown.length() > 200,
                "Should have substantial text (got " + markdown.length() + " chars)");
    }
}
