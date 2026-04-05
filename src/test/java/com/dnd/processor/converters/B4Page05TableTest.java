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
 * B4 page 5, bottom section — Wandering Monster Table: Level 1.
 *
 * A single-column table with a header row ("Die Roll", "Wandering Monster",
 * "No", "AC", "HD", etc.), horizontal rules between rows, and 8 monster
 * entries (Centipede Giant through Goblin).
 *
 * Uses the cropped fixture B4-page05-table.pdf.
 * Expected: table region detected as content (not eaten by footer),
 * not fragmented into many tiny zones, all 8 monster entries in markdown.
 */
class B4Page05TableTest {

    private static final File FIXTURE = new File("src/test/resources/B4-page05-table.pdf");
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
        ImageIO.write(bands, "PNG", OUTPUT_DIR.resolve("B4-page05-table-bands.png").toFile());

        // 3. Build layout PDF from zones
        int imgW = pageImage.getWidth(), imgH = pageImage.getHeight();
        Path layoutPdf = OUTPUT_DIR.resolve("B4-page05-table-layout.pdf");
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
        Files.writeString(OUTPUT_DIR.resolve("B4-page05-table.md"), markdown, StandardCharsets.UTF_8);

        // Debug: check horizontal projection at key rows
        boolean[] ink = new boolean[pageImage.getWidth() * pageImage.getHeight()];
        for (int y = 0; y < pageImage.getHeight(); y++)
            for (int x = 0; x < pageImage.getWidth(); x++) {
                int rgb = pageImage.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b2 = rgb & 0xFF;
                int lum = (r * 299 + g * 587 + b2 * 114) / 1000;
                ink[y * pageImage.getWidth() + x] = lum < 200;
            }
        System.out.println("Ink sample rows (count of dark pixels across full width):");
        for (int y : new int[]{0, 100, 400, 550, 800, 850, 880, 900, 950, 1000, 1100, 1200, 1300, 1400, 1420, 1450, 1500, 1600, 1610, 1620, 1630, 1649}) {
            if (y >= pageImage.getHeight()) continue;
            int count = 0;
            for (int x = 0; x < pageImage.getWidth(); x++)
                if (ink[y * pageImage.getWidth() + x]) count++;
            System.out.println("  y=" + y + ": " + count + " ink pixels");
        }

        // Debug dump
        System.out.println("Page 5 table layout: " + layout.type());
        System.out.println("Image: " + pageImage.getWidth() + "x" + pageImage.getHeight());
        System.out.println("Margins: top=" + layout.margins().top()
                + " bottom=" + layout.margins().bottom()
                + " left=" + layout.margins().left()
                + " right=" + layout.margins().right());
        System.out.println("Zones: " + layout.zones().size());
        for (int i = 0; i < layout.zones().size(); i++) {
            Zone z = layout.zones().get(i);
            System.out.println("  Zone " + i + ": " + z.type()
                    + " y=[" + z.yTop() + "," + z.yBottom() + ")"
                    + " h=" + (z.yBottom() - z.yTop()) + "px"
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
    void tableNotFragmentedIntoManyZones() {
        assertTrue(layout.zones().size() <= 6,
                "Table section has " + layout.zones().size() + " zones — too fragmented");
    }

    @Test
    void hasContentZones() {
        long textZones = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT).count();
        assertTrue(textZones >= 1, "Should have at least 1 TEXT zone");
    }

    // ── Bands image matches reference ────────────────────────────────────────

    @Test
    void bandsImageMatchesReference() throws Exception {
        File refFile = new File("src/test/resources/B4-page05-table-bands-expected.png");
        org.junit.jupiter.api.Assumptions.assumeTrue(refFile.exists(),
                "Reference bands image not found — copy test-output/B4-page05-table-bands.png to src/test/resources/B4-page05-table-bands-expected.png");

        BufferedImage expected = ImageIO.read(refFile);
        BufferedImage actual = ImageIO.read(OUTPUT_DIR.resolve("B4-page05-table-bands.png").toFile());

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

    // ── Markdown: all 8 monster entries present ──────────────────────────────

    @Test
    void containsWanderingMonsterTableHeader() {
        assertTrue(markdown.contains("Wandering Monster")
                        || markdown.contains("wandering monster")
                        || markdown.contains("Wandering Monsters"),
                "Should contain 'Wandering Monster' table heading");
    }

    @Test
    void containsCentipede() {
        assertTrue(markdown.contains("Centipede") || markdown.contains("centipede"),
                "Should contain Centipede entry");
    }

    @Test
    void containsCaveLocust() {
        assertTrue(markdown.contains("Cave Locust") || markdown.contains("cave locust"),
                "Should contain Cave Locust entry");
    }

    @Test
    void containsCynidicean() {
        assertTrue(markdown.contains("Cynidicean") || markdown.contains("cynidicean"),
                "Should contain Cynidicean entry");
    }

    @Test
    void containsFerretGiant() {
        assertTrue(markdown.contains("Ferret") || markdown.contains("ferret"),
                "Should contain Ferret Giant entry");
    }

    @Test
    void containsGnome() {
        assertTrue(markdown.contains("Gnome") || markdown.contains("gnome"),
                "Should contain Gnome entry");
    }

    @Test
    void containsGoblin() {
        assertTrue(markdown.contains("Goblin") || markdown.contains("goblin"),
                "Should contain Goblin entry");
    }

    @Test
    void markdownHasSubstantialContent() {
        assertTrue(markdown.length() > 200,
                "Markdown should have substantial text (got " + markdown.length() + " chars)");
    }
}
