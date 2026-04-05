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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for B4 page 7 — a clean two-column text page with no illustrations
 * or row splits.  This is the "happy path" for the layout analyzer.
 *
 * Verifies: layout structure, sub-page splitting, and markdown output.
 *
 * Writes bands image, layout PDF, and markdown to {@code test-output/}
 * for visual verification.
 */
class B4Page07Test {

    private static final File FIXTURE = new File("src/test/resources/B4-page07.pdf");
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
        ImageIO.write(bands, "PNG", OUTPUT_DIR.resolve("B4-page07-bands.png").toFile());

        // 3. Build layout PDF from zones (bypasses cover override that
        //    treats single-page fixtures as front covers)
        int imgW = pageImage.getWidth(), imgH = pageImage.getHeight();
        Path layoutPdf = OUTPUT_DIR.resolve("B4-page07-layout.pdf");
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
            System.out.println("Layout PDF: " + output.getNumberOfPages() + " sub-pages");
        }

        // 4. Extract markdown from layout PDF
        PdfConverter converter = new PdfConverter();
        ConversionResult result = converter.convert(layoutPdf);
        markdown = result.markdown();

        // 5. Write markdown for visual verification
        Files.writeString(OUTPUT_DIR.resolve("B4-page07.md"), markdown, StandardCharsets.UTF_8);
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
                (r - l) * scaleX,
                (b - t) * scaleY);
        p.setMediaBox(box);
        p.setCropBox(box);
    }

    // ── Layout structure ─────────────────────────────────────────────────────

    @Test
    void singleTextZone() {
        long textZones = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT).count();
        assertEquals(1, textZones, "Should have exactly 1 TEXT zone");
    }

    @Test
    void noImageZones() {
        long imageZones = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.IMAGE).count();
        assertEquals(0, imageZones, "Should have no IMAGE zones");
    }

    @Test
    void twoColumns() {
        Zone textZone = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT).findFirst().orElseThrow();
        assertEquals(2, textZone.columns().size(), "Should have exactly 2 columns");
    }

    @Test
    void noColumnScopedIllustrations() {
        boolean hasColImage = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .flatMap(z -> z.columns().stream())
                .flatMap(c -> c.subZones().stream())
                .anyMatch(cz -> cz.type() == ZoneType.IMAGE);
        assertFalse(hasColImage, "Should have no column-scoped IMAGE sub-zones");
    }

    @Test
    void bothColumnsHaveHorizontalGaps() {
        Zone textZone = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT).findFirst().orElseThrow();
        for (int i = 0; i < textZone.columns().size(); i++) {
            Column col = textZone.columns().get(i);
            long totalGaps = col.subZones().stream()
                    .filter(cz -> cz.type() == ZoneType.TEXT)
                    .mapToLong(cz -> cz.horizGaps().size())
                    .sum();
            assertTrue(totalGaps > 0,
                    "Column " + i + " should have at least one horizontal gap");
        }
    }

    @Test
    void layoutTypeIsTwoColumn() {
        assertEquals(LayoutType.TWO_COLUMN, layout.type());
    }

    // ── Bands image matches reference ────────────────────────────────────────

    @Test
    void bandsImageMatchesReference() throws Exception {
        File refFile = new File("src/test/resources/B4-page07-bands-expected.png");
        org.junit.jupiter.api.Assumptions.assumeTrue(refFile.exists(),
                "Reference bands image not found");

        BufferedImage expected = ImageIO.read(refFile);
        BufferedImage actual = ImageIO.read(OUTPUT_DIR.resolve("B4-page07-bands.png").toFile());

        assertEquals(expected.getWidth(), actual.getWidth(), "Bands image width mismatch");
        assertEquals(expected.getHeight(), actual.getHeight(), "Bands image height mismatch");

        int mismatched = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y)) {
                    mismatched++;
                }
            }
        }
        assertEquals(0, mismatched,
                "Bands image differs from reference in " + mismatched + " pixels");
    }

    // ── Markdown content — left column ───────────────────────────────────────

    // Note: section headings in the PDF use letter-spaced typography,
    // so extracted text has spaces between characters (e.g. "K E Y  T O  T IE R  2").

    @Test
    void leftColumn_containsKeyToTier2() {
        assertTrue(markdown.contains("K E Y  T O  T IE R  2"),
                "Should contain 'KEY TO TIER 2' heading\nActual:\n" + firstLines(5));
    }

    @Test
    void leftColumn_containsStorageRoom() {
        assertTrue(markdown.contains("S T O R A G E  R O O M"),
                "Should contain '2. STORAGE ROOM'");
    }

    @Test
    void leftColumn_containsSecretRoom() {
        assertTrue(markdown.contains("S E C R E T  R O O M"),
                "Should contain '3. SECRET ROOM'");
    }

    @Test
    void leftColumn_containsStirges() {
        assertTrue(markdown.contains("stirge"),
                "Should contain stirges encounter text");
    }

    @Test
    void leftColumn_containsPriestsQuarters() {
        assertTrue(markdown.contains("P R IE S T"),
                "Should contain '4. PRIEST'S QUARTERS'");
    }

    @Test
    void leftColumn_containsFireworksStoreroom() {
        assertTrue(markdown.contains("F IR E W O R K S"),
                "Should contain '5. FIREWORKS STOREROOM'");
    }

    // ── Markdown content — right column ──────────────────────────────────────

    @Test
    void rightColumn_containsSpecialStoreroom() {
        assertTrue(markdown.contains("S P E C IA L  S T O R E R O O M"),
                "Should contain '6. SPECIAL STOREROOM'");
    }

    @Test
    void rightColumn_containsFireBeetles() {
        assertTrue(markdown.contains("fire beetle") || markdown.contains("fire beetles"),
                "Should contain fire beetles encounter");
    }

    @Test
    void rightColumn_containsPotteryJars() {
        assertTrue(markdown.contains("P O T T E R Y") || markdown.contains("pottery jars"),
                "Should contain '5a. POTTERY JARS'");
    }

    @Test
    void rightColumn_containsTreasureRoom() {
        assertTrue(markdown.contains("T R E A S U R E  R O O M"),
                "Should contain '7. TREASURE ROOM'");
    }

    @Test
    void rightColumn_containsAbandonedRoom() {
        assertTrue(markdown.contains("A B A N D O N E D  R O O M"),
                "Should contain '8. ABANDONED ROOM'");
    }

    // ── Markdown ordering ────────────────────────────────────────────────────

    @Test
    void leftColumnComesBeforeRightColumn() {
        int keyTier2 = markdown.indexOf("T IE R  2");
        int special = markdown.indexOf("S P E C IA L");
        assertTrue(keyTier2 >= 0 && special >= 0, "Both sections should be present");
        assertTrue(keyTier2 < special,
                "Left column (KEY TO TIER 2) should come before right column (SPECIAL STOREROOM)");
    }

    @Test
    void markdownHasSubstantialContent() {
        assertFalse(markdown.isBlank(), "Markdown should not be empty");
        assertTrue(markdown.length() > 500,
                "Markdown should have substantial text (got " + markdown.length() + " chars)");
    }

    private String firstLines(int n) {
        return markdown.lines().limit(n).reduce("", (a, b) -> a + "\n" + b);
    }
}
