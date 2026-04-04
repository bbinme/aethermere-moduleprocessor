package com.dnd.processor.converters;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Splits two-column PDF pages into individual single-column pages.
 *
 * For each page in the source PDF:
 *  - If the page has a detectable two-column layout, it is split into two pages:
 *    the left column first, then the right column.
 *  - Single-column (or undetected) pages are copied as-is.
 *
 * Column splitting is done by adjusting the MediaBox and CropBox of copied pages.
 * The left-column page's MediaBox is set to [0, 0, splitX, height] and the
 * right-column page's MediaBox is set to [splitX, 0, width, height].  PDF viewers
 * clip content to the MediaBox, so each output page shows only its column.
 *
 * The split x-coordinate is detected per page using the same histogram-based
 * gap analysis used by the text extraction pipeline.
 */
public class PDFPreprocessor {

    private final ColumnAwareTextStripper columnDetector = new ColumnAwareTextStripper();

    /**
     * Renders every page of the PDF as a PNG image and writes them to {@code outputDir}.
     *
     * Output files are named {@code <baseName>-page-NNN.png} (1-based, zero-padded).
     *
     * @param inputPath source PDF file
     * @param outputDir directory to write PNG files into (created if absent)
     * @param dpi       render resolution (150 is fast/preview, 300 is print-quality)
     * @throws IOException if the PDF cannot be read or images cannot be written
     */
    public void renderPages(Path inputPath, Path outputDir, float dpi) throws IOException {
        Files.createDirectories(outputDir);
        String baseName = stripExtension(inputPath.getFileName().toString());

        try (PDDocument doc = Loader.loadPDF(inputPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();
            int digits = String.valueOf(pageCount).length();

            for (int i = 0; i < pageCount; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                String filename = String.format("%s-page-%0" + digits + "d.png", baseName, i + 1);
                Path outFile = outputDir.resolve(filename);
                ImageIO.write(image, "PNG", outFile.toFile());
                System.out.printf("  rendered page %d/%d → %s%n", i + 1, pageCount, filename);
            }
        }
    }

    /**
     * Analyses the layout of every page and writes a new PDF where each source
     * page has been split into sub-pages with margins removed.
     *
     * <ul>
     *   <li>SINGLE / MAP / TABLE → 1 sub-page (margin-cropped)</li>
     *   <li>TWO_COLUMN → 2 sub-pages (left, right)</li>
     *   <li>TWO_ROW    → 2 sub-pages (top, bottom)</li>
     *   <li>THREE_COLUMN → 3 sub-pages (left, centre, right)</li>
     *   <li>THREE_ROW    → 3 sub-pages (top, middle, bottom)</li>
     *   <li>TWO_BY_TWO   → 4 sub-pages (top-left, top-right, bottom-left, bottom-right)</li>
     * </ul>
     *
     * @param inputPath  source PDF
     * @param outputPath destination PDF (created / overwritten)
     * @param dpi        render resolution for layout analysis (150 is typical)
     * @param debugBands if true, annotated band images are written alongside the output PDF
     */
    public void processLayout(Path inputPath, Path outputPath,
                              float dpi, boolean debugBands) throws IOException {
        Path debugDir = null;
        if (debugBands) {
            debugDir = outputPath.getParent().resolve(
                    stripExtension(outputPath.getFileName().toString()) + "-bands");
            Files.createDirectories(debugDir);
        }

        ProjectionAnalyzer analyzer = new ProjectionAnalyzer();

        try (PDDocument source = Loader.loadPDF(inputPath.toFile());
             PDDocument output = new PDDocument()) {

            PDFRenderer renderer = new PDFRenderer(source);
            int pageCount = source.getNumberOfPages();
            int digits    = String.valueOf(pageCount).length();
            String base   = stripExtension(inputPath.getFileName().toString());

            for (int i = 0; i < pageCount; i++) {
                BufferedImage img    = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ProjectionAnalyzer.PageLayout layout = analyzer.analyzeLayout(img);

                int imgW = img.getWidth();
                int imgH = img.getHeight();

                PDPage   src   = source.getPage(i);
                PDRectangle med = src.getMediaBox();
                float scaleX   = med.getWidth()  / imgW;
                float scaleY   = med.getHeight() / imgH;

                ProjectionAnalyzer.Margins m = layout.margins();

                // Iterate over zones top-to-bottom.
                // Full-width IMAGE zones (Pass 0) become a single full-width sub-page.
                // TEXT zones are split into columns; within each column, column-scoped
                // IMAGE sub-zones (Pass 1.5) and TEXT sub-zones each become one sub-page.
                for (ProjectionAnalyzer.Zone zone : layout.zones()) {
                    int zt = zone.yTop(), zb = zone.yBottom();
                    if (zone.type() == ProjectionAnalyzer.ZoneType.IMAGE
                            || zone.columns().isEmpty()) {
                        // Full-width IMAGE zone or degenerate text zone
                        addSubPage(output, source, i, med, imgW, imgH, scaleX, scaleY,
                                   m.left(), zt, m.right(), zb);
                    } else {
                        // One sub-page per column sub-zone
                        for (ProjectionAnalyzer.Column col : zone.columns()) {
                            for (ProjectionAnalyzer.ColumnZone cz : col.subZones()) {
                                addSubPage(output, source, i, med, imgW, imgH, scaleX, scaleY,
                                           col.xLeft(), cz.yTop(), col.xRight(), cz.yBottom());
                            }
                        }
                    }
                }

                if (debugBands) {
                    BufferedImage annotated = analyzer.analyze(img);
                    String fname = String.format("%s-page-%0" + digits + "d-bands.png", base, i + 1);
                    ImageIO.write(annotated, "PNG", debugDir.resolve(fname).toFile());
                }

                System.out.printf("  page %d/%d  %s%n", i + 1, pageCount, layout.type());
            }

            output.save(outputPath.toFile());
            System.out.printf("Layout complete: %d source pages → %d sub-pages%n",
                    pageCount, output.getNumberOfPages());
        }
    }

    /** Imports a copy of source page {@code pageIdx} and sets its box to the given image-space region. */
    private void addSubPage(PDDocument output, PDDocument source, int pageIdx,
                            PDRectangle med, int imgW, int imgH, float scaleX, float scaleY,
                            int imgLeft, int imgTop, int imgRight, int imgBottom) throws IOException {
        PDPage p = output.importPage(source.getPage(pageIdx));
        PDRectangle box = subBox(med, imgW, imgH, scaleX, scaleY,
                imgLeft, imgTop, imgRight, imgBottom);
        p.setMediaBox(box);
        p.setCropBox(box);
    }

    /**
     * Converts an image-space region to a PDF MediaBox, expanding outward by
     * SUB_PAGE_MARGIN_PX on every side to avoid clipping text near detected margins.
     * Text extraction uses strict CropBox filtering so expansion does not cause
     * bleed between adjacent sub-pages.
     * Image y=0 is page top; PDF y=0 is page bottom — hence the vertical flip.
     */
    // ~2 mm at 150 DPI
    private static final int SUB_PAGE_MARGIN_PX = 12;

    private PDRectangle subBox(PDRectangle med, int imgW, int imgH, float scaleX, float scaleY,
                               int imgLeft, int imgTop, int imgRight, int imgBottom) {
        int l = Math.max(0,    imgLeft   - SUB_PAGE_MARGIN_PX);
        int t = Math.max(0,    imgTop    - SUB_PAGE_MARGIN_PX);
        int r = Math.min(imgW, imgRight  + SUB_PAGE_MARGIN_PX);
        int b = Math.min(imgH, imgBottom + SUB_PAGE_MARGIN_PX);
        return new PDRectangle(
                med.getLowerLeftX() + l * scaleX,
                med.getLowerLeftY() + (imgH - b) * scaleY,
                (r - l) * scaleX,
                (b - t) * scaleY
        );
    }

    /**
     * Renders every page of the PDF and prints the detected layout type for each.
     * No files are written; this is a diagnostic step.
     */
    public void classifyPages(Path inputPath, float dpi) throws IOException {
        ProjectionAnalyzer analyzer = new ProjectionAnalyzer();

        try (PDDocument doc = Loader.loadPDF(inputPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();

            for (int i = 0; i < pageCount; i++) {
                BufferedImage page = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ProjectionAnalyzer.PageLayout layout = analyzer.analyzeLayout(page);
                System.out.printf("  page %2d/%d  %-14s  zones=%-20s  splitX=%-6s  splitY=%-6s%n",
                        i + 1, pageCount,
                        layout.type(),
                        fmtZones(layout.zones()),
                        fmtSplit(layout.splitX(), layout.splitX2()),
                        fmtSplit(layout.splitY(), layout.splitY2()));
            }
        }
    }

    /**
     * Renders every page of the PDF, runs projection-profile band detection on each,
     * and writes the annotated images to {@code outputDir}.
     *
     * Vertical white-space bands (column gutters) are outlined in red.
     * Horizontal white-space bands (blank rows / section breaks) are outlined in blue.
     *
     * @param inputPath source PDF file
     * @param outputDir directory to write annotated images into (created if absent)
     * @param dpi       render resolution (150 is a good starting point)
     */
    public void analyzeBands(Path inputPath, Path outputDir, float dpi) throws IOException {
        Files.createDirectories(outputDir);
        String baseName = stripExtension(inputPath.getFileName().toString());
        ProjectionAnalyzer analyzer = new ProjectionAnalyzer();

        try (PDDocument doc = Loader.loadPDF(inputPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();
            int digits = String.valueOf(pageCount).length();

            for (int i = 0; i < pageCount; i++) {
                BufferedImage page       = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                BufferedImage annotated  = analyzer.analyze(page);

                String filename = String.format("%s-page-%0" + digits + "d-bands.png", baseName, i + 1);
                Path outFile = outputDir.resolve(filename);
                ImageIO.write(annotated, "PNG", outFile.toFile());
                System.out.printf("  page %d/%d → %s%n", i + 1, pageCount, filename);
            }
        }
    }

    /**
     * Renders every page of the PDF at {@code dpi}, runs Canny edge detection on
     * each page image, and writes the edge images to {@code outputDir}.
     *
     * Output files are named {@code <baseName>-page-NNN-edges.png}.
     *
     * @param inputPath    source PDF file
     * @param outputDir    directory to write edge images into (created if absent)
     * @param dpi          render resolution (150 is a good starting point)
     * @param lowThreshold  Canny weak-edge threshold  (e.g. 30)
     * @param highThreshold Canny strong-edge threshold (e.g. 90)
     */
    public void detectEdges(Path inputPath, Path outputDir,
                            float dpi, float lowThreshold, float highThreshold)
            throws IOException {
        Files.createDirectories(outputDir);
        String baseName = stripExtension(inputPath.getFileName().toString());
        CannyEdgeDetector canny = new CannyEdgeDetector();

        try (PDDocument doc = Loader.loadPDF(inputPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();
            int digits = String.valueOf(pageCount).length();

            for (int i = 0; i < pageCount; i++) {
                BufferedImage page  = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                BufferedImage edges = canny.detect(page, lowThreshold, highThreshold);

                // Overlay: copy original image then paint every edge pixel red
                BufferedImage overlay = new BufferedImage(page.getWidth(), page.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                overlay.getGraphics().drawImage(page, 0, 0, null);
                int red = 0xFF0000;
                for (int y = 0; y < edges.getHeight(); y++) {
                    for (int x = 0; x < edges.getWidth(); x++) {
                        // Edge pixels are white (0xFFFFFF) in the Canny output
                        if ((edges.getRGB(x, y) & 0xFFFFFF) != 0) {
                            overlay.setRGB(x, y, red);
                        }
                    }
                }

                String filename = String.format("%s-page-%0" + digits + "d-edges.png", baseName, i + 1);
                Path outFile = outputDir.resolve(filename);
                ImageIO.write(overlay, "PNG", outFile.toFile());
                System.out.printf("  page %d/%d → %s%n", i + 1, pageCount, filename);
            }
        }
    }

    private static String fmtZones(List<ProjectionAnalyzer.Zone> zones) {
        StringBuilder sb = new StringBuilder();
        for (ProjectionAnalyzer.Zone z : zones) {
            if (sb.length() > 0) sb.append('+');
            if (z.type() == ProjectionAnalyzer.ZoneType.IMAGE) {
                sb.append("IMG");
            } else {
                long colImgs = z.columns().stream()
                        .flatMap(c -> c.subZones().stream())
                        .filter(cz -> cz.type() == ProjectionAnalyzer.ZoneType.IMAGE)
                        .count();
                sb.append("T(").append(z.columns().size()).append("col");
                if (colImgs > 0) sb.append("+").append(colImgs).append("img");
                sb.append(")");
            }
        }
        return sb.toString();
    }

    private static String fmtSplit(int a, int b) {
        if (a < 0) return "-";
        return b >= 0 ? a + "," + b : String.valueOf(a);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * Reads {@code inputPath}, splits every two-column page into two pages, and
     * writes the result to {@code outputPath}.
     *
     * @param inputPath  source PDF file
     * @param outputPath destination PDF file (created or overwritten)
     * @throws IOException if the PDF cannot be read or written
     */
    public void split(Path inputPath, Path outputPath) throws IOException {
        try (PDDocument source = Loader.loadPDF(inputPath.toFile())) {
            int pageCount = source.getNumberOfPages();

            try (PDDocument output = new PDDocument()) {
                int splitPages = 0;

                for (int i = 0; i < pageCount; i++) {
                    PDPage sourcePage = source.getPage(i);
                    PDRectangle media  = sourcePage.getMediaBox();
                    float pageWidth    = media.getWidth();
                    float pageHeight   = media.getHeight();

                    float splitX = columnDetector.detectPageColumnGap(source, i);

                    if (splitX > 0) {
                        // ── Left column ───────────────────────────────────────────
                        // Visible region: x in [lowerLeftX, lowerLeftX + splitX]
                        PDPage leftPage = output.importPage(sourcePage);
                        PDRectangle leftBox = new PDRectangle(
                                media.getLowerLeftX(),
                                media.getLowerLeftY(),
                                splitX,
                                pageHeight);
                        leftPage.setMediaBox(leftBox);
                        leftPage.setCropBox(leftBox);

                        // ── Right column ──────────────────────────────────────────
                        // Visible region: x in [lowerLeftX + splitX, lowerLeftX + pageWidth]
                        // Re-fetch the source page so we get a fresh copy for import.
                        PDPage rightPage = output.importPage(source.getPage(i));
                        PDRectangle rightBox = new PDRectangle(
                                media.getLowerLeftX() + splitX,
                                media.getLowerLeftY(),
                                pageWidth - splitX,
                                pageHeight);
                        rightPage.setMediaBox(rightBox);
                        rightPage.setCropBox(rightBox);

                        splitPages++;
                    } else {
                        // Single-column: copy unchanged
                        output.importPage(sourcePage);
                    }
                }

                output.save(outputPath.toFile());
                System.out.printf("PDFPreprocessor: %d source pages → %d output pages (%d pages split)%n",
                        pageCount, output.getNumberOfPages(), splitPages);
            }
        }
    }
}
