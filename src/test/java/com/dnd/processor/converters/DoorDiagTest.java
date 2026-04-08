package com.dnd.processor.converters;

import com.dnd.processor.config.DnD_BasicSet;
import com.dnd.processor.model.DungeonMap;
import com.dnd.processor.model.MapClassification;
import com.dnd.processor.model.MapType;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * Diagnostic: samples pixel data at cell boundaries in a grid map section
 * to characterize door signatures.
 */
class DoorDiagTest {

    @Test
    void analyzeBoundaries_B4_page31_section3() throws Exception {
        DnD_BasicSet config = new DnD_BasicSet();
        Path pdf = Path.of("src/test/resources/B4-page31.pdf");

        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage img = renderer.renderImageWithDPI(0, 150, ImageType.RGB);

            ProjectionAnalyzer analyzer = new ProjectionAnalyzer(config);
            ProjectionAnalyzer.PageLayout layout = analyzer.analyzeLayout(img);
            MapClassifier classifier = new MapClassifier(config);
            MapClassification result = classifier.classify(img, layout.margins());

            // Get section 3 (Tier 3, index 2) crop
            var bounds = classifier.computeSectionBounds(result, layout.margins());
            int sIdx = 2; // Section 3
            int[] b = bounds.get(sIdx);
            int x = Math.max(0, b[0]);
            int y = Math.max(0, b[1]);
            int bw = Math.min(b[2], img.getWidth() - x);
            int bh = Math.min(b[3], img.getHeight() - y);
            BufferedImage section = img.getSubimage(x, y, bw, bh);

            var sec = result.sections().get(sIdx);
            double spacing = result.gridSpacing();
            int gridW = sec.widthSquares();
            int gridH = sec.heightSquares();

            // Extract grid for reference
            GridExtractor extractor = new GridExtractor(config.floorThreshold());
            DungeonMap map = extractor.extract(section, gridW, gridH, spacing);

            System.out.println("=== Tier 3: " + gridW + "x" + gridH + ", spacing=" + spacing + " ===\n");

            // Sample HORIZONTAL boundaries (between row and row+1)
            System.out.println("--- HORIZONTAL BOUNDARIES (row/row+1) ---");
            System.out.println("col,row | above | below | stripW | darkPixels | darkFrac | brightBlock | signature");
            analyzeHorizontalBoundaries(section, map, gridW, gridH, spacing);

            System.out.println("\n--- VERTICAL BOUNDARIES (col/col+1) ---");
            System.out.println("col,row | left  | right | stripH | darkPixels | darkFrac | brightBlock | signature");
            analyzeVerticalBoundaries(section, map, gridW, gridH, spacing);
        }
    }

    private void analyzeHorizontalBoundaries(BufferedImage img, DungeonMap map,
                                              int gridW, int gridH, double spacing) {
        int imgW = img.getWidth();
        int imgH = img.getHeight();
        int stripHalf = (int)(spacing * 0.15); // sample ±15% of spacing around boundary

        for (int row = 0; row < gridH - 1; row++) {
            for (int col = 0; col < gridW; col++) {
                int above = map.grid()[row * gridW + col];
                int below = map.grid()[(row + 1) * gridW + col];

                // Only analyze boundaries where at least one side is floor
                if (above == DungeonMap.WALL && below == DungeonMap.WALL) continue;

                int boundaryY = (int)((row + 1) * spacing);
                int y1 = Math.max(0, boundaryY - stripHalf);
                int y2 = Math.min(imgH, boundaryY + stripHalf);
                int x1 = (int)(col * spacing);
                int x2 = (int)Math.min((col + 1) * spacing, imgW);

                if (y1 >= y2 || x1 >= x2) continue;

                int[] stats = analyzeStrip(img, x1, y1, x2, y2);
                int darkCount = stats[0];
                int totalPixels = stats[1];
                int brightBlockLen = stats[2]; // longest run of bright pixels along the boundary
                double darkFrac = (double) darkCount / totalPixels;

                String cellAbove = above == 0 ? "FLOOR" : "WALL ";
                String cellBelow = below == 0 ? "FLOOR" : "WALL ";
                String sig = classifyBoundary(darkFrac, brightBlockLen, spacing);

                if (darkFrac > 0.1 && darkFrac < 0.9) { // only show interesting boundaries
                    System.out.printf("(%2d,%2d) | %s | %s | %3d    | %4d       | %.3f    | %3d         | %s%n",
                            col, row, cellAbove, cellBelow, x2 - x1, darkCount, darkFrac, brightBlockLen, sig);
                }
            }
        }
    }

    private void analyzeVerticalBoundaries(BufferedImage img, DungeonMap map,
                                            int gridW, int gridH, double spacing) {
        int imgW = img.getWidth();
        int imgH = img.getHeight();
        int stripHalf = (int)(spacing * 0.15);

        for (int row = 0; row < gridH; row++) {
            for (int col = 0; col < gridW - 1; col++) {
                int left  = map.grid()[row * gridW + col];
                int right = map.grid()[row * gridW + col + 1];

                if (left == DungeonMap.WALL && right == DungeonMap.WALL) continue;

                int boundaryX = (int)((col + 1) * spacing);
                int x1 = Math.max(0, boundaryX - stripHalf);
                int x2 = Math.min(imgW, boundaryX + stripHalf);
                int y1 = (int)(row * spacing);
                int y2 = (int)Math.min((row + 1) * spacing, imgH);

                if (y1 >= y2 || x1 >= x2) continue;

                int[] stats = analyzeStrip(img, x1, y1, x2, y2);
                int darkCount = stats[0];
                int totalPixels = stats[1];
                int brightBlockLen = stats[2];
                double darkFrac = (double) darkCount / totalPixels;

                String cellLeft  = left  == 0 ? "FLOOR" : "WALL ";
                String cellRight = right == 0 ? "FLOOR" : "WALL ";
                String sig = classifyBoundary(darkFrac, brightBlockLen, spacing);

                if (darkFrac > 0.1 && darkFrac < 0.9) {
                    System.out.printf("(%2d,%2d) | %s | %s | %3d    | %4d       | %.3f    | %3d         | %s%n",
                            col, row, cellLeft, cellRight, y2 - y1, darkCount, darkFrac, brightBlockLen, sig);
                }
            }
        }
    }

    /**
     * Analyzes a rectangular strip of pixels.
     * Returns: [darkPixelCount, totalPixels, longestBrightRun]
     *
     * "Dark" = brightness < 180, "Bright" = brightness >= 180
     * longestBrightRun is measured along the axis perpendicular to the boundary
     * (columns for horizontal boundaries, rows for vertical boundaries).
     */
    private int[] analyzeStrip(BufferedImage img, int x1, int y1, int x2, int y2) {
        int darkCount = 0;
        int totalPixels = 0;

        // Compute column-wise (or row-wise) brightness profile for bright block detection
        // For simplicity, project onto the longer axis
        int w = x2 - x1;
        int h = y2 - y1;

        // Project along the longer axis to find bright blocks
        boolean horizontal = w >= h;
        int projLen = horizontal ? w : h;
        int[] projBright = new int[projLen];
        int[] projTotal = new int[projLen];

        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                int rgb = img.getRGB(x1 + dx, y1 + dy);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int brightness = (r * 299 + g * 587 + b * 114) / 1000;

                totalPixels++;
                if (brightness < 180) darkCount++;

                int projIdx = horizontal ? dx : dy;
                projTotal[projIdx]++;
                if (brightness >= 180) projBright[projIdx]++;
            }
        }

        // Find longest run where >50% of pixels in that column/row are bright
        int longestBright = 0;
        int currentBright = 0;
        for (int i = 0; i < projLen; i++) {
            if (projTotal[i] > 0 && (double) projBright[i] / projTotal[i] > 0.5) {
                currentBright++;
                longestBright = Math.max(longestBright, currentBright);
            } else {
                currentBright = 0;
            }
        }

        return new int[]{darkCount, totalPixels, longestBright};
    }

    private String classifyBoundary(double darkFrac, int brightBlockLen, double spacing) {
        double blockRatio = brightBlockLen / spacing;
        if (darkFrac < 0.2) return "OPEN";
        if (darkFrac > 0.8) return "WALL";
        if (blockRatio > 0.3 && blockRatio < 0.8) return "*** DOOR? ***";
        return "mixed";
    }
}
