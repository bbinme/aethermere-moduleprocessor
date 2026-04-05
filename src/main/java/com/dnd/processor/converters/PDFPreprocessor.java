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
import java.util.ArrayList;
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

    private final com.dnd.processor.config.DnD_BasicSet config;

    /** Uses default D&D Basic Set tuning. */
    public PDFPreprocessor() {
        this(new com.dnd.processor.config.DnD_BasicSet());
    }

    /** Uses the supplied module config for all projection-analysis tuning. */
    public PDFPreprocessor(com.dnd.processor.config.DnD_BasicSet config) {
        this.config = config;
    }

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

        ProjectionAnalyzer analyzer = new ProjectionAnalyzer(config);

        try (PDDocument source = Loader.loadPDF(inputPath.toFile());
             PDDocument output = new PDDocument()) {

            PDFRenderer renderer = new PDFRenderer(source);
            int pageCount = source.getNumberOfPages();
            int digits    = String.valueOf(pageCount).length();
            String base   = stripExtension(inputPath.getFileName().toString());

            float[] minM = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] maxM = {0, 0, 0, 0};
            int excluded = 0;

            for (int i = 0; i < pageCount; i++) {
                BufferedImage img    = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ProjectionAnalyzer.PageLayout layout = withCoverOverride(analyzer.analyzeLayout(img), i, pageCount);

                int imgW = img.getWidth();
                int imgH = img.getHeight();

                PDPage   src   = source.getPage(i);
                PDRectangle med = src.getMediaBox();
                float scaleX   = med.getWidth()  / imgW;
                float scaleY   = med.getHeight() / imgH;

                ProjectionAnalyzer.Margins m = layout.margins();

                // Cover pages → single full-width sub-page (no column/row splitting).
                // All other pages → iterate zones for sub-page creation.
                if (isFullPageLayout(layout.type())) {
                    addSubPage(output, source, i, med, imgW, imgH, scaleX, scaleY,
                               m.left(), m.top(), m.right(), m.bottom());
                } else {
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
                }

                if (debugBands) {
                    BufferedImage annotated = analyzer.analyze(img);
                    String fname = String.format("%s-page-%0" + digits + "d-bands.png", base, i + 1);
                    ImageIO.write(annotated, "PNG", debugDir.resolve(fname).toFile());
                }

                {
                    float tIn = m.top()             / dpi;
                    float bIn = (imgH - m.bottom()) / dpi;
                    float lIn = m.left()            / dpi;
                    float rIn = (imgW - m.right())  / dpi;
                    boolean excludeFromSummary = isFullPageLayout(layout.type());
                    if (excludeFromSummary) {
                        excluded++;
                    } else {
                        minM[0] = Math.min(minM[0], tIn); maxM[0] = Math.max(maxM[0], tIn);
                        minM[1] = Math.min(minM[1], bIn); maxM[1] = Math.max(maxM[1], bIn);
                        minM[2] = Math.min(minM[2], lIn); maxM[2] = Math.max(maxM[2], lIn);
                        minM[3] = Math.min(minM[3], rIn); maxM[3] = Math.max(maxM[3], rIn);
                    }
                    System.out.printf("  page %d/%d  %-14s  margins=[T:%.2f B:%.2f L:%.2f R:%.2f]%s%n",
                            i + 1, pageCount, layout.type(), tIn, bIn, lIn, rIn,
                            excludeFromSummary ? " (excluded)" : "");
                }
            }

            output.save(outputPath.toFile());
            System.out.printf("Layout complete: %d source pages → %d sub-pages%n",
                    pageCount, output.getNumberOfPages());
            int included = pageCount - excluded;
            System.out.printf("Margin summary (%d pages, %d excluded, inches at %.0f DPI):%n", included, excluded, dpi);
            System.out.printf("  top:    min=%.2f  max=%.2f%n", minM[0], maxM[0]);
            System.out.printf("  bottom: min=%.2f  max=%.2f%n", minM[1], maxM[1]);
            System.out.printf("  left:   min=%.2f  max=%.2f%n", minM[2], maxM[2]);
            System.out.printf("  right:  min=%.2f  max=%.2f%n", minM[3], maxM[3]);
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
        ProjectionAnalyzer analyzer = new ProjectionAnalyzer(config);

        try (PDDocument doc = Loader.loadPDF(inputPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();

            float[] minM = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] maxM = {0, 0, 0, 0};
            int excluded = 0;

            for (int i = 0; i < pageCount; i++) {
                BufferedImage page = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ProjectionAnalyzer.PageLayout layout = withCoverOverride(analyzer.analyzeLayout(page), i, pageCount);
                ProjectionAnalyzer.Margins m = layout.margins();
                int imgW = page.getWidth(), imgH = page.getHeight();
                float tIn = m.top()              / dpi;
                float bIn = (imgH - m.bottom())  / dpi;
                float lIn = m.left()             / dpi;
                float rIn = (imgW - m.right())   / dpi;
                boolean excludeFromSummary = isFullPageLayout(layout.type()) || isFullBleed(m, imgW, imgH);
                if (excludeFromSummary) {
                    excluded++;
                } else {
                    minM[0] = Math.min(minM[0], tIn); maxM[0] = Math.max(maxM[0], tIn);
                    minM[1] = Math.min(minM[1], bIn); maxM[1] = Math.max(maxM[1], bIn);
                    minM[2] = Math.min(minM[2], lIn); maxM[2] = Math.max(maxM[2], lIn);
                    minM[3] = Math.min(minM[3], rIn); maxM[3] = Math.max(maxM[3], rIn);
                }
                System.out.printf("  page %2d/%d  %-14s  margins=[T:%.2f B:%.2f L:%.2f R:%.2f]%s  zones=%-20s  splitX=%-6s  splitY=%-6s%n",
                        i + 1, pageCount,
                        layout.type(),
                        tIn, bIn, lIn, rIn,
                        excludeFromSummary ? " (excluded)" : "           ",
                        fmtZones(layout.zones()),
                        fmtSplit(layout.splitX(), layout.splitX2()),
                        fmtSplit(layout.splitY(), layout.splitY2()));
            }
            int included = pageCount - excluded;
            System.out.printf("%nMargin summary (%d pages, %d excluded, inches at %.0f DPI):%n", included, excluded, dpi);
            System.out.printf("  top:    min=%.2f  max=%.2f%n", minM[0], maxM[0]);
            System.out.printf("  bottom: min=%.2f  max=%.2f%n", minM[1], maxM[1]);
            System.out.printf("  left:   min=%.2f  max=%.2f%n", minM[2], maxM[2]);
            System.out.printf("  right:  min=%.2f  max=%.2f%n", minM[3], maxM[3]);
        }
    }

    /**
     * Processes every page and prints only the pages whose layout is not cleanly
     * TWO_COLUMN. Useful for verifying that a two-column module is being read correctly
     * and for spotting pages that need special handling (maps, tables, covers, etc.).
     *
     * @param inputPath source PDF file
     * @param dpi       render resolution
     */
    public void verifyColumns(Path inputPath, float dpi) throws IOException {
        ProjectionAnalyzer analyzer = new ProjectionAnalyzer(config);

        try (PDDocument doc = Loader.loadPDF(inputPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();
            int digits    = String.valueOf(pageCount).length();
            int flagged   = 0;

            System.out.printf("Verifying two-column layout: %s  (%d pages)%n%n",
                    inputPath.getFileName(), pageCount);

            for (int i = 0; i < pageCount; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ProjectionAnalyzer.PageLayout layout = withCoverOverride(analyzer.analyzeLayout(img), i, pageCount);
                ProjectionAnalyzer.LayoutType type   = layout.type();

                if (type == ProjectionAnalyzer.LayoutType.TWO_COLUMN) continue;

                flagged++;
                String zones = fmtZones(layout.zones());
                String splitX = fmtSplit(layout.splitX(), layout.splitX2());
                System.out.printf("  page %0" + digits + "d  %-14s  zones=%-22s  splitX=%s%n",
                        i + 1, type, zones, splitX);
            }

            System.out.printf("%n%d / %d pages are not TWO_COLUMN%n", flagged, pageCount);
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
        ProjectionAnalyzer analyzer = new ProjectionAnalyzer(config);

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
     * Detects tables on every page and writes annotated PNG images to {@code outputDir}.
     * Pages with no detected tables are skipped.
     * Orange solid border = rule-based (high confidence).
     * Orange dashed border = implicit (lower confidence).
     * Cyan = detected rule line. Blue = detected title region.
     */
    public void detectTables(Path inputPath, Path outputDir, float dpi) throws IOException {
        Files.createDirectories(outputDir);
        String baseName = stripExtension(inputPath.getFileName().toString());

        ProjectionAnalyzer analyzer  = new ProjectionAnalyzer(config);
        TableDetector      detector  = new TableDetector();
        TableClassifier    classifier = new TableClassifier();

        try (PDDocument doc = Loader.loadPDF(inputPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();
            int digits    = String.valueOf(pageCount).length();

            int pagesWritten = 0;
            for (int i = 0; i < pageCount; i++) {
                BufferedImage img    = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ProjectionAnalyzer.PageLayout layout = withCoverOverride(analyzer.analyzeLayout(img), i, pageCount);
                ProjectionAnalyzer.Margins margins   = layout.margins();

                List<TableRegion> raw       = detector.detect(img, layout, doc, i, dpi);
                List<TableRegion> classified = new java.util.ArrayList<>();
                for (TableRegion r : raw) {
                    classified.add(classifier.classify(r, doc, i, img.getWidth(), img.getHeight(), dpi));
                }

                if (classified.isEmpty()) continue;

                BufferedImage annotated = annotateTablePage(img, classified);
                String fname = String.format("%s-page-%0" + digits + "d-tables.png", baseName, i + 1);
                javax.imageio.ImageIO.write(annotated, "PNG", outputDir.resolve(fname).toFile());
                pagesWritten++;

                for (TableRegion r : classified) {
                    System.out.printf("  page %d/%d  %-28s  %s%n",
                            i + 1, pageCount,
                            r.type(),
                            r.title().isEmpty() ? "(no title)" : "\"" + r.title() + "\"");
                }
            }
            System.out.printf("Table detection complete: %d pages with tables written to %s%n",
                    pagesWritten, outputDir);
        }
    }

    private BufferedImage annotateTablePage(BufferedImage src, List<TableRegion> regions) {
        java.awt.Color orange = new java.awt.Color(255, 140, 0);
        java.awt.Color cyan   = new java.awt.Color(0, 200, 220);
        java.awt.Color blue   = new java.awt.Color(50, 100, 255);
        java.awt.Color white  = java.awt.Color.WHITE;

        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

        java.awt.Font labelFont = new java.awt.Font("SansSerif", java.awt.Font.BOLD, 11);
        g.setFont(labelFont);

        for (TableRegion r : regions) {
            int x = r.xLeft(), w = r.xRight() - r.xLeft();
            int y = r.yTop(),  h = r.yBottom() - r.yTop();

            // Table region border
            g.setColor(orange);
            if (r.implicit()) {
                float[] dash = {6f, 4f};
                g.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_BUTT,
                        java.awt.BasicStroke.JOIN_MITER, 10f, dash, 0f));
            } else {
                g.setStroke(new java.awt.BasicStroke(2f));
            }
            g.drawRect(x, y, w, h);

            // Rule line (cyan)
            if (r.hasRule()) {
                g.setColor(cyan);
                g.setStroke(new java.awt.BasicStroke(1f));
                g.drawRect(x, r.ruleYTop(), w, r.ruleYBottom() - r.ruleYTop());
            }

            // Title region (blue)
            if (r.hasTitle()) {
                g.setColor(blue);
                g.setStroke(new java.awt.BasicStroke(1f));
                g.drawRect(x, r.titleYTop(), w, r.titleYBottom() - r.titleYTop());
            }

            // Label bar at top of table region
            String label = r.type().name().replace('_', ' ');
            java.awt.FontMetrics fm = g.getFontMetrics();
            int barH  = fm.getHeight() + 4;
            int barY  = Math.max(0, y - barH);
            int textX = x + 4;
            int textY = barY + fm.getAscent() + 2;

            g.setStroke(new java.awt.BasicStroke(1f));
            g.setColor(orange);
            g.fillRect(x, barY, w, barH);
            g.setColor(white);
            g.drawString(label, textX, textY);
        }

        g.dispose();
        return out;
    }

    /** Returns true for layout types that represent full-page images (excluded from margin summary). */
    /**
     * Overrides the layout type for the first and last pages of a document to
     * FRONT_COVER / BACK_COVER regardless of what the analyzer detected.
     */
    private static ProjectionAnalyzer.PageLayout withCoverOverride(
            ProjectionAnalyzer.PageLayout layout, int pageIndex, int pageCount) {
        ProjectionAnalyzer.LayoutType type = layout.type();
        if (pageIndex == 0)             type = ProjectionAnalyzer.LayoutType.FRONT_COVER;
        else if (pageIndex == pageCount - 1) type = ProjectionAnalyzer.LayoutType.BACK_COVER;
        if (type == layout.type()) return layout;
        return new ProjectionAnalyzer.PageLayout(type, layout.margins(), layout.zones(),
                layout.splitX(), layout.splitX2(), layout.splitY(), layout.splitY2(),
                layout.vertGaps(), layout.horizGaps());
    }

    private static boolean isFullPageLayout(ProjectionAnalyzer.LayoutType type) {
        return type == ProjectionAnalyzer.LayoutType.MAP
            || type == ProjectionAnalyzer.LayoutType.FRONT_COVER
            || type == ProjectionAnalyzer.LayoutType.BACK_COVER;
    }

    /** Returns true if the detected content area covers ≥ 98% of the page (full-bleed image or border frame). */
    private static boolean isFullBleed(ProjectionAnalyzer.Margins m, int imgW, int imgH) {
        float contentArea = (float)(m.right() - m.left()) * (m.bottom() - m.top());
        float pageArea    = (float) imgW * imgH;
        return pageArea > 0 && contentArea / pageArea >= 0.98f;
    }

    /**
     * Analyses margins for every page and returns one {@link PageMarginInfo} per page.
     * Prints per-page lines and a per-file summary to stdout.
     * Full-page layout types (MAP, COVER, BACK_COVER) and full-bleed pages are flagged
     * and excluded from the summary statistics.
     */
    public List<PageMarginInfo> analyzeMargins(Path inputPath, float dpi) throws IOException {
        List<PageMarginInfo> results = new ArrayList<>();
        ProjectionAnalyzer analyzer = new ProjectionAnalyzer(config);

        try (PDDocument doc = Loader.loadPDF(inputPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();

            float[] minM = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] maxM = {0, 0, 0, 0};
            float[] sumM = {0, 0, 0, 0};
            int included = 0, excluded = 0;

            System.out.printf("%nFile: %s (%d pages)%n", inputPath.getFileName(), pageCount);

            for (int i = 0; i < pageCount; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ProjectionAnalyzer.PageLayout layout = withCoverOverride(analyzer.analyzeLayout(img), i, pageCount);
                ProjectionAnalyzer.Margins m = layout.margins();
                int imgW = img.getWidth(), imgH = img.getHeight();

                float tIn = m.top()             / dpi;
                float bIn = (imgH - m.bottom()) / dpi;
                float lIn = m.left()            / dpi;
                float rIn = (imgW - m.right())  / dpi;
                boolean fullBleed = isFullBleed(m, imgW, imgH);

                PageMarginInfo info = new PageMarginInfo(i + 1, layout.type(), fullBleed, tIn, bIn, lIn, rIn);
                results.add(info);

                String note;
                if (isFullPageLayout(layout.type())) {
                    note = " (excluded - " + layout.type().name().toLowerCase() + ")";
                    excluded++;
                } else if (fullBleed) {
                    note = " (excluded - full-bleed)";
                    excluded++;
                } else {
                    note = "";
                    included++;
                    minM[0] = Math.min(minM[0], tIn); maxM[0] = Math.max(maxM[0], tIn); sumM[0] += tIn;
                    minM[1] = Math.min(minM[1], bIn); maxM[1] = Math.max(maxM[1], bIn); sumM[1] += bIn;
                    minM[2] = Math.min(minM[2], lIn); maxM[2] = Math.max(maxM[2], lIn); sumM[2] += lIn;
                    minM[3] = Math.min(minM[3], rIn); maxM[3] = Math.max(maxM[3], rIn); sumM[3] += rIn;
                }
                System.out.printf("  page %2d/%d  %-14s  [T:%.2f B:%.2f L:%.2f R:%.2f]%s%n",
                        i + 1, pageCount, layout.type(), tIn, bIn, lIn, rIn, note);
            }

            printMarginSummary(inputPath.getFileName().toString(), included, excluded, dpi, minM, maxM, sumM);
        }
        return results;
    }

    public static void printMarginSummary(String label, int included, int excluded, float dpi,
                                           float[] minM, float[] maxM, float[] sumM) {
        System.out.printf("  Margin summary: %s (%d pages included, %d excluded, inches at %.0f DPI)%n",
                label, included, excluded, dpi);
        if (included == 0) {
            System.out.println("    (no included pages)");
            return;
        }
        System.out.printf("    top:    min=%.2f  avg=%.2f  max=%.2f%n", minM[0], sumM[0] / included, maxM[0]);
        System.out.printf("    bottom: min=%.2f  avg=%.2f  max=%.2f%n", minM[1], sumM[1] / included, maxM[1]);
        System.out.printf("    left:   min=%.2f  avg=%.2f  max=%.2f%n", minM[2], sumM[2] / included, maxM[2]);
        System.out.printf("    right:  min=%.2f  avg=%.2f  max=%.2f%n", minM[3], sumM[3] / included, maxM[3]);
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
