package com.dnd.processor.converters;

import com.dnd.processor.config.DnD_BasicSet_B1_4;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;

class B4Page09DiagTest {

    @Test
    void dumpInkProjection() throws Exception {
        File f = new File("src/test/resources/B4-page09.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(f.exists());

        BufferedImage img;
        try (PDDocument doc = Loader.loadPDF(f)) {
            img = new PDFRenderer(doc).renderImageWithDPI(0, 150, ImageType.RGB);
        }

        DnD_BasicSet_B1_4 config = new DnD_BasicSet_B1_4();
        ProjectionAnalyzer analyzer = new ProjectionAnalyzer(config);
        var layout = analyzer.analyzeLayout(img);
        var margins = layout.margins();

        // Compute horizontal projection manually
        int w = img.getWidth(), h = img.getHeight();
        int inkThreshold = 200;
        boolean[] ink = new boolean[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                ink[y * w + x] = (r < inkThreshold && g < inkThreshold && b < inkThreshold);
            }

        int[] horizProj = new int[h];
        for (int y = 0; y < h; y++)
            for (int x = margins.left(); x < margins.right(); x++)
                if (ink[y * w + x]) horizProj[y]++;

        int maxInk = (int)((margins.right() - margins.left()) * 0.005);
        System.out.println("maxInkForRowGap = " + maxInk);
        System.out.println("Margins: " + margins);
        System.out.println("\n--- Ink projection y=490 to y=1120 ---");
        for (int y = 490; y <= 1120; y++) {
            String marker = "";
            if (horizProj[y] <= maxInk) marker = " <-- GAP";
            if (y % 5 == 0 || horizProj[y] <= maxInk)
                System.out.printf("y=%4d ink=%4d%s%n", y, horizProj[y], marker);
        }
    }
}
