package com.dnd.processor.converters;

import com.dnd.processor.config.DnD_BasicSet_B1_4;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic test: dumps edge image, Hough histogram, and detected lines
 * for B4 page 31 to help tune MapClassifier parameters.
 */
class MapClassifierDiagTest {

    private static final int DPI = 150;
    private static final Path OUTPUT_DIR = Path.of("test-output");

    @Test
    void diagnoseB1Page02() throws Exception {
        diagnoseFixture("src/test/resources/B1-page02.pdf", "B1-page02");
    }

    @Test
    void diagnoseB1Page03() throws Exception {
        diagnoseFixture("src/test/resources/B1-page03.pdf", "B1-page03");
    }

    @Test
    void diagnoseB2Page02() throws Exception {
        diagnoseFixture("src/test/resources/B2-page02.pdf", "B2-page02");
    }

    @Test
    void diagnoseDL1Page36() throws Exception {
        diagnoseFixture("src/test/resources/DL1-page36.pdf", "DL1-page36");
    }

    @Test
    void diagnoseDL1Page37() throws Exception {
        diagnoseFixture("src/test/resources/DL1-page37.pdf", "DL1-page37");
    }

    @Test
    void diagnoseX2Page17() throws Exception {
        diagnoseFixture("src/test/resources/X2-page17.pdf", "X2-page17");
    }

    @Test
    void diagnosePage31() throws Exception {
        diagnoseFixture("src/test/resources/B4-page31.pdf", "B4-page31");
    }

    @Test
    void diagnosePage33() throws Exception {
        diagnoseFixture("src/test/resources/B4-page33.pdf", "B4-page33");
    }

    void diagnoseFixture(String path, String label) throws Exception {
        File fixture = new File(path);
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture.exists());
        Files.createDirectories(OUTPUT_DIR);

        DnD_BasicSet_B1_4 config = new DnD_BasicSet_B1_4();
        ProjectionAnalyzer analyzer = new ProjectionAnalyzer(config);

        BufferedImage pageImage;
        try (PDDocument doc = Loader.loadPDF(fixture)) {
            pageImage = new PDFRenderer(doc).renderImageWithDPI(0, DPI, ImageType.RGB);
        }

        ProjectionAnalyzer.PageLayout layout = analyzer.analyzeLayout(pageImage);
        ProjectionAnalyzer.Margins m = layout.margins();
        System.out.printf("Layout: %s, margins: L=%d T=%d R=%d B=%d%n",
                layout.type(), m.left(), m.top(), m.right(), m.bottom());

        // Crop to content
        int cx = m.left(), cy = m.top();
        int cw = m.right() - m.left(), ch = m.bottom() - m.top();
        BufferedImage cropped = pageImage.getSubimage(cx, cy, cw, ch);
        System.out.printf("Cropped: %d x %d%n", cw, ch);

        // Edge detection with different thresholds
        CannyEdgeDetector canny = new CannyEdgeDetector();

        for (float[] thresh : new float[][]{{20, 60}, {10, 30}, {30, 90}, {5, 15}}) {
            BufferedImage edges = canny.detect(cropped, thresh[0], thresh[1]);

            // Count edge pixels
            int edgeCount = 0;
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    if ((edges.getRGB(x, y) & 0xFFFFFF) != 0) edgeCount++;
                }
            }
            double edgeFraction = (double) edgeCount / (cw * ch);
            System.out.printf("Canny(%.0f/%.0f): %d edge pixels (%.2f%% of content)%n",
                    thresh[0], thresh[1], edgeCount, edgeFraction * 100);

            // Save edge image
            String name = String.format("%s-edges-%.0f-%.0f.png", label, thresh[0], thresh[1]);
            ImageIO.write(edges, "PNG", OUTPUT_DIR.resolve(name).toFile());
        }

        // Run Hough on the default thresholds
        BufferedImage edges = canny.detect(cropped, 20, 60);
        boolean[] edgeBools = new boolean[cw * ch];
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                edgeBools[y * cw + x] = (edges.getRGB(x, y) & 0xFFFFFF) != 0;
            }
        }

        HoughTransform hough = new HoughTransform();
        int[][] acc = hough.accumulate(edgeBools, cw, ch);
        double[] histogram = hough.angleHistogram(acc);

        // Print top 10 histogram bins
        int[] indices = new int[180];
        for (int i = 0; i < 180; i++) indices[i] = i;
        // Sort by value descending
        for (int i = 0; i < 10; i++) {
            for (int j = i + 1; j < 180; j++) {
                if (histogram[indices[j]] > histogram[indices[i]]) {
                    int tmp = indices[i]; indices[i] = indices[j]; indices[j] = tmp;
                }
            }
        }

        System.out.println("\nTop 10 angle histogram bins:");
        for (int i = 0; i < 10; i++) {
            System.out.printf("  %3d° = %.4f%n", indices[i], histogram[indices[i]]);
        }

        // Extract lines and build line-count histogram
        int rhoOffset = HoughTransform.diagonal(cw, ch);
        int minVotes = (int) (Math.min(cw, ch) * 0.15f);
        var lines = hough.extractLines(acc, minVotes, rhoOffset);
        System.out.printf("Lines (minVotes=%d): %d total%n", minVotes, lines.size());

        // Build line-count histogram
        double[] lineHist = new double[180];
        for (var l : lines) lineHist[((int) l.theta()) % 180]++;
        double maxBin = 0;
        for (double v : lineHist) if (v > maxBin) maxBin = v;
        if (maxBin > 0) for (int i = 0; i < 180; i++) lineHist[i] /= maxBin;

        System.out.println("\nTop 10 line-count histogram bins:");
        int[] idx = new int[180];
        for (int i = 0; i < 180; i++) idx[i] = i;
        for (int i = 0; i < 10; i++) {
            for (int j = i + 1; j < 180; j++) {
                if (lineHist[idx[j]] > lineHist[idx[i]]) {
                    int tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp;
                }
            }
        }
        for (int i = 0; i < 10; i++) {
            System.out.printf("  %3d° = %.4f (%.0f lines)%n",
                    idx[i], lineHist[idx[i]], lineHist[idx[i]] * maxBin);
        }

        // Run actual classifier
        var classifier = new MapClassifier(config);
        var result = classifier.classify(pageImage, layout.margins());
        System.out.printf("\nClassification: %s (confidence=%.2f, spacing=%.1f, grid=%d×%d, sections=%d)%n",
                result.type(), result.confidence(), result.gridSpacing(),
                result.gridWidthSquares(), result.gridHeightSquares(),
                result.sections().size());
        for (int s = 0; s < result.sections().size(); s++) {
            var sec = result.sections().get(s);
            System.out.printf("  Section %d: %d × %d squares%n",
                    s + 1, sec.widthSquares(), sec.heightSquares());
        }

        // Dump rho gaps for the height axis (horizontal lines, θ≈90°, rho≈y)
        if (result.type() == com.dnd.processor.model.MapType.GRID_MAP
                && result.dominantAngles().length >= 2) {
            // a1 = second angle = a0+90 = horizontal lines for unrotated grids
            int heightAngle = (int) result.dominantAngles()[1];
            var spacingResult = classifier.analyseSpacing(
                    hough.extractLines(acc, minVotes, rhoOffset),
                    heightAngle, config.gridAngleTolerance());
            System.out.printf("\nHeight-axis spacing (angle=%d°, regularLines=%d, spacing=%.1f, rhoExtent=%.1f)%n",
                    heightAngle, spacingResult.regularLineCount(),
                    spacingResult.candidateSpacing(), spacingResult.rhoExtent());
        }
    }

    private static int angleDist(double a, double b) {
        int d = (int) Math.abs(a - b) % 180;
        return Math.min(d, 180 - d);
    }
}
