package com.dnd.processor.converters;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.File;

/**
 * One-shot utility to extract individual pages from the B4 PDF into
 * test fixture files under {@code src/test/resources/}.
 *
 * Run once: {@code gradlew test --tests "*.extractB4Pages"}
 */
class ExtractTestPages {

    @Test
    void extractB4Pages() throws Exception {
        File src = new File("data/B4 - The Lost City.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(src.exists(),
                "Source PDF not found — skipping extraction");

        File outDir = new File("src/test/resources");
        outDir.mkdirs();

        try (PDDocument doc = Loader.loadPDF(src)) {
            // Page 1 (index 0) — front cover
            extractPage(doc, 0, new File(outDir, "B4-page01.pdf"));
            // Page 2 (index 1) — title page
            extractPage(doc, 1, new File(outDir, "B4-page02.pdf"));
            // Page 5 (index 4) — Players' Background + two-col + wandering monster table
            extractPage(doc, 4, new File(outDir, "B4-page05.pdf"));
            // Page 6 (index 5) — section headings + two-col body
            extractPage(doc, 5, new File(outDir, "B4-page06.pdf"));
            // Page 7 (index 6) — normal two-column
            extractPage(doc, 6, new File(outDir, "B4-page07.pdf"));
            // Page 8 (index 7) — normal two-column (second regression guard)
            extractPage(doc, 7, new File(outDir, "B4-page08.pdf"));
            // Page 12 (index 11) — two-col with left-column illustration
            extractPage(doc, 11, new File(outDir, "B4-page12.pdf"));
        }
        System.out.println("Extracted test pages to " + outDir.getAbsolutePath());
    }

    private static void extractPage(PDDocument src, int pageIndex, File out) throws Exception {
        try (PDDocument single = new PDDocument()) {
            single.addPage(src.getPages().get(pageIndex));
            single.save(out);
            System.out.println("  Page " + (pageIndex + 1) + " -> " + out.getName());
        }
    }
}
