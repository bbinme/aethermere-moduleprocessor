package com.dnd.processor.converters;

import com.dnd.processor.config.DnD_BasicSet_B1_4;
import com.dnd.processor.converters.ProjectionAnalyzer.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layout analysis regression tests for {@link ProjectionAnalyzer}.
 *
 * Each test renders a single-page PDF fixture at 150 DPI, runs
 * {@link ProjectionAnalyzer#analyzeLayout}, and asserts on the
 * resulting {@link PageLayout}.
 *
 * Fixtures live in {@code src/test/resources/} — extract them with
 * {@code gradlew test --tests "*.extractB4Pages"}.
 */
class ProjectionAnalyzerTest {

    private static final int DPI = 150;
    private static ProjectionAnalyzer analyzer;

    @BeforeAll
    static void init() {
        analyzer = new ProjectionAnalyzer(new DnD_BasicSet_B1_4());
    }

    private static BufferedImage renderFixture(String resourceName) throws Exception {
        File f = new File("src/test/resources/" + resourceName);
        org.junit.jupiter.api.Assumptions.assumeTrue(f.exists(),
                "Test fixture not found: " + resourceName
                        + " — run: gradlew test --tests \"*.extractB4Pages\"");
        try (PDDocument doc = Loader.loadPDF(f)) {
            return new PDFRenderer(doc).renderImageWithDPI(0, DPI, ImageType.RGB);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Page 1 (cover) — no false column splits
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void b4Page1_cover_noFalseColumns() throws Exception {
        PageLayout layout = analyzer.analyzeLayout(renderFixture("B4-page01.pdf"));
        for (Zone z : layout.zones()) {
            if (z.type() == ZoneType.TEXT) {
                assertTrue(z.columns().size() <= 1,
                        "Cover TEXT zone at y=" + z.yTop() + " has "
                                + z.columns().size() + " columns — expected 1");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Page 2 (title page) — single-column, no false gutter
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void b4Page2_titlePage_singleColumn() throws Exception {
        PageLayout layout = analyzer.analyzeLayout(renderFixture("B4-page02.pdf"));
        assertNotEquals(LayoutType.TWO_COLUMN, layout.type());
        for (Zone z : layout.zones()) {
            if (z.type() == ZoneType.TEXT) {
                assertTrue(z.columns().size() <= 1,
                        "Title page TEXT zone at y=" + z.yTop() + " has "
                                + z.columns().size() + " columns — expected 1");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Page 5 — heading + two-col body + illustration + wandering monster table
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void b4Page5_hasMultipleZones() throws Exception {
        PageLayout layout = analyzer.analyzeLayout(renderFixture("B4-page05.pdf"));
        assertTrue(layout.zones().size() >= 2,
                "Page 5 should have >= 2 zones, got " + layout.zones().size());
    }

    @Test
    void b4Page5_hasTwoColumnZone() throws Exception {
        PageLayout layout = analyzer.analyzeLayout(renderFixture("B4-page05.pdf"));
        boolean hasTwoCol = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .anyMatch(z -> z.columns().size() == 2);
        assertTrue(hasTwoCol, "Page 5 should have a two-column TEXT zone");
    }

    @Test
    void b4Page5_bottomMarginIncludesFullTable() throws Exception {
        PageLayout layout = analyzer.analyzeLayout(renderFixture("B4-page05.pdf"));
        // "8 Goblin" row is at roughly y=1383-1399.
        // Bottom margin must be >= 1390 to include it.
        assertTrue(layout.margins().bottom() >= 1390,
                "Bottom margin at y=" + layout.margins().bottom()
                        + " cuts off table content — should be >= 1390");
    }

    @Test
    void b4Page5_tableNotFragmented() throws Exception {
        PageLayout layout = analyzer.analyzeLayout(renderFixture("B4-page05.pdf"));
        // The table region (lower half) should not be fragmented into many zones
        int contentMid = (layout.margins().top() + layout.margins().bottom()) / 2;
        long bottomZones = layout.zones().stream()
                .filter(z -> z.yTop() >= contentMid)
                .count();
        assertTrue(bottomZones <= 14,
                "Bottom half has " + bottomZones + " zones — table fragmented");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Page 6 — section headings, short-row guard
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void b4Page6_noFalseColumnInShortRows() throws Exception {
        PageLayout layout = analyzer.analyzeLayout(renderFixture("B4-page06.pdf"));
        for (Zone z : layout.zones()) {
            if (z.type() == ZoneType.TEXT && z.yBottom() - z.yTop() < 80) {
                assertTrue(z.columns().size() <= 1,
                        "Short TEXT zone (h=" + (z.yBottom() - z.yTop())
                                + "px) at y=" + z.yTop() + " has "
                                + z.columns().size() + " cols");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Page 7 — normal two-column (regression guard)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void b4Page7_twoColumn() throws Exception {
        PageLayout layout = analyzer.analyzeLayout(renderFixture("B4-page07.pdf"));
        boolean hasTwoCol = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .anyMatch(z -> z.columns().size() == 2);
        assertTrue(hasTwoCol, "Page 7 should have a two-column TEXT zone");
    }

    @Test
    void b4Page7_singleTextZone() throws Exception {
        PageLayout layout = analyzer.analyzeLayout(renderFixture("B4-page07.pdf"));
        long textZones = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT).count();
        assertEquals(1, textZones, "Page 7 should have exactly 1 TEXT zone");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Page 12 — two-col with left-column illustration
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void b4Page12_twoColumns() throws Exception {
        PageLayout layout = analyzer.analyzeLayout(renderFixture("B4-page12.pdf"));
        boolean hasTwoCol = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .anyMatch(z -> z.columns().size() == 2);
        assertTrue(hasTwoCol, "Page 12 should have a two-column TEXT zone");
    }

    @Test
    void b4Page12_hasIllustrationSubZone() throws Exception {
        PageLayout layout = analyzer.analyzeLayout(renderFixture("B4-page12.pdf"));
        boolean hasColIll = layout.zones().stream()
                .filter(z -> z.type() == ZoneType.TEXT)
                .flatMap(z -> z.columns().stream())
                .flatMap(c -> c.subZones().stream())
                .anyMatch(cz -> cz.type() == ZoneType.IMAGE);
        assertTrue(hasColIll, "Page 12 should have a column-scoped IMAGE sub-zone");
    }
}
