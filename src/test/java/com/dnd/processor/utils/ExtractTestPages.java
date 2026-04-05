package com.dnd.processor.utils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot utility to extract individual pages from the B4 PDF into
 * test fixture files under {@code src/test/resources/}.
 *
 * Run once: {@code gradlew test --tests "*.extractB4Pages"}
 */
class ExtractTestPages {

    private static final int DPI = 150;

    @Test
    void extractB4Pages() throws Exception {
        File src = new File("data/B4 - The Lost City.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(src.exists(),
                "Source PDF not found — skipping extraction");

        File outDir = new File("src/test/resources");
        outDir.mkdirs();

        try (PDDocument doc = Loader.loadPDF(src)) {
            // Extract only new pages — existing fixtures should not be overwritten
            extractPage(doc, 8, new File(outDir, "B4-page09.pdf"));
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

    /**
     * Creates a full-page PDF showing only a horizontal section of the source page.
     * Everything above and below the section is whited out, and a page number
     * is drawn in the footer area — so the layout analyzer sees normal page structure.
     *
     * @param imgTop    top of visible section in image-space pixels (150 DPI)
     * @param imgBottom bottom of visible section in image-space pixels (150 DPI)
     * @param pageNum   page number text to draw in footer
     */
    private static void extractPageSection(PDDocument src, int pageIndex, File out,
                                            int imgTop, int imgBottom, String pageNum)
            throws Exception {
        extractPageSection(src, pageIndex, out, imgTop, imgBottom, pageNum, null);
    }

    private static void extractPageSection(PDDocument src, int pageIndex, File out,
                                            int imgTop, int imgBottom, String pageNum,
                                            String bottomLabel)
            throws Exception {
        BufferedImage img = new PDFRenderer(src).renderImageWithDPI(pageIndex, DPI, ImageType.RGB);
        int imgH = img.getHeight();

        try (PDDocument single = new PDDocument()) {
            PDPage p = single.importPage(src.getPage(pageIndex));
            PDRectangle med = p.getMediaBox();
            float pageWidth = med.getWidth();
            float pageHeight = med.getHeight();
            float scaleY = pageHeight / imgH;

            float pdfSectionTop = pageHeight - imgTop * scaleY;
            float pdfSectionBottom = pageHeight - imgBottom * scaleY;

            try (PDPageContentStream cs = new PDPageContentStream(single, p,
                    PDPageContentStream.AppendMode.APPEND, false)) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                PDType1Font boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

                cs.setNonStrokingColor(1f, 1f, 1f);

                // White out above section
                if (pdfSectionTop < pageHeight) {
                    cs.addRect(0, pdfSectionTop, pageWidth, pageHeight - pdfSectionTop);
                    cs.fill();
                }

                // White out below section
                if (pdfSectionBottom > 0) {
                    cs.addRect(0, 0, pageWidth, pdfSectionBottom);
                    cs.fill();
                }

                // Draw bottom label below the visible section
                if (bottomLabel != null) {
                    float labelFontSize = 28;
                    float labelWidth = boldFont.getStringWidth(bottomLabel) / 1000 * labelFontSize;
                    float labelPdfY = pdfSectionBottom - 150 * scaleY;
                    cs.beginText();
                    cs.setFont(boldFont, labelFontSize);
                    cs.setNonStrokingColor(0f, 0f, 0f);
                    cs.newLineAtOffset((pageWidth - labelWidth) / 2, labelPdfY);
                    cs.showText(bottomLabel);
                    cs.endText();
                }

                // Draw page number centered in footer
                float fontSize = 10;
                float textWidth = font.getStringWidth(pageNum) / 1000 * fontSize;
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.newLineAtOffset((pageWidth - textWidth) / 2, 15);
                cs.showText(pageNum);
                cs.endText();
            }

            single.save(out);
            System.out.println("  Page " + (pageIndex + 1)
                    + " section [" + imgTop + "," + imgBottom + ") -> " + out.getName());
        }
    }

    /**
     * Creates a full-page PDF with ONLY the visible section's original content
     * (no text bleeds from under white overlays). Uses a Form XObject with a
     * clip rectangle so the original page content outside the visible section
     * is completely excluded. Adds a header label and a large table heading.
     */
    private static void extractPageSectionWithLabels(PDDocument src, int pageIndex, File out,
                                                      int imgTop, int imgBottom,
                                                      String pageNum, String headerLabel,
                                                      String bottomLabel, int bottomLabelOffsetPx,
                                                      String... extraHeaderLabels)
            throws Exception {
        BufferedImage img = new PDFRenderer(src).renderImageWithDPI(pageIndex, DPI, ImageType.RGB);
        int imgH = img.getHeight();

        try (PDDocument single = new PDDocument()) {
            // Create a fresh blank page with the same dimensions as the original
            PDPage srcPage = src.getPage(pageIndex);
            PDRectangle med = srcPage.getMediaBox();
            float pageWidth = med.getWidth();
            float pageHeight = med.getHeight();
            float scaleY = pageHeight / imgH;

            PDPage newPage = new PDPage(new PDRectangle(pageWidth, pageHeight));
            single.addPage(newPage);

            // Import the original page as a Form XObject
            LayerUtility layerUtil = new LayerUtility(single);
            PDFormXObject form = layerUtil.importPageAsForm(src, pageIndex);

            // PDF coords for the visible section
            float pdfSectionTop = pageHeight - imgTop * scaleY;
            float pdfSectionBottom = pageHeight - imgBottom * scaleY;

            // Strip text operations outside the visible section from the form's
            // content stream. BBox alone doesn't prevent PDFTextStripper from
            // extracting text — we must actually remove the text operators.
            stripTextOutsideYRange(form, pdfSectionBottom, pdfSectionTop);

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            try (PDPageContentStream cs = new PDPageContentStream(single, newPage)) {
                // Draw original page content clipped to visible section only.
                // This ensures no text from outside the section is in the PDF.
                cs.saveGraphicsState();
                cs.addRect(0, pdfSectionBottom, pageWidth, pdfSectionTop - pdfSectionBottom);
                cs.clip();
                cs.drawForm(form);
                cs.restoreGraphicsState();

                // Draw header label near the top of the page (in margin, no original text)
                if (headerLabel != null) {
                    float labelFontSize = 12;
                    float labelWidth = font.getStringWidth(headerLabel) / 1000 * labelFontSize;
                    float labelPdfY = pageHeight - 30 * scaleY;
                    cs.beginText();
                    cs.setFont(font, labelFontSize);
                    cs.setNonStrokingColor(0f, 0f, 0f);
                    cs.newLineAtOffset((pageWidth - labelWidth) / 2, labelPdfY);
                    cs.showText(headerLabel);
                    cs.endText();
                }

                // Draw extra header labels below the first one
                for (int li = 0; li < extraHeaderLabels.length; li++) {
                    String extra = extraHeaderLabels[li];
                    float extraFontSize = 14;
                    float extraWidth = boldFont.getStringWidth(extra) / 1000 * extraFontSize;
                    float extraPdfY = pageHeight - (30 + 25 * (li + 1)) * scaleY;
                    cs.beginText();
                    cs.setFont(boldFont, extraFontSize);
                    cs.setNonStrokingColor(0f, 0f, 0f);
                    cs.newLineAtOffset((pageWidth - extraWidth) / 2, extraPdfY);
                    cs.showText(extra);
                    cs.endText();
                }

                // Draw large bold label below the visible section.
                if (bottomLabel != null) {
                    float headingFontSize = 28;
                    float headingWidth = boldFont.getStringWidth(bottomLabel) / 1000 * headingFontSize;
                    float headingPdfY = Math.max(20,
                            pageHeight - (imgBottom + bottomLabelOffsetPx) * scaleY);
                    cs.beginText();
                    cs.setFont(boldFont, headingFontSize);
                    cs.setNonStrokingColor(0f, 0f, 0f);
                    cs.newLineAtOffset((pageWidth - headingWidth) / 2, headingPdfY);
                    cs.showText(bottomLabel);
                    cs.endText();
                }

                // Draw page number centered in footer
                float fontSize = 10;
                float textWidth = font.getStringWidth(pageNum) / 1000 * fontSize;
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.newLineAtOffset((pageWidth - textWidth) / 2, 15);
                cs.showText(pageNum);
                cs.endText();
            }

            single.save(out);
            System.out.println("  Page " + (pageIndex + 1)
                    + " section [" + imgTop + "," + imgBottom
                    + ") + labels -> " + out.getName());
        }
    }

    /**
     * Removes all text drawing operations (BT...ET blocks) from a Form XObject's
     * content stream where the text y-position falls outside [pdfYBottom, pdfYTop].
     * This prevents PDFTextStripper from extracting text that was visually hidden.
     */
    private static void stripTextOutsideYRange(PDFormXObject form,
                                                float pdfYBottom, float pdfYTop)
            throws Exception {
        byte[] streamBytes = form.getCOSObject().createInputStream().readAllBytes();
        PDFStreamParser parser = new PDFStreamParser(streamBytes);
        List<Object> tokens = parser.parse();

        List<Object> filtered = new ArrayList<>();
        List<Object> textBlock = new ArrayList<>();
        boolean inText = false;
        boolean keepBlock = true;
        float textY = 0;

        for (Object token : tokens) {
            if (token instanceof Operator op) {
                String name = op.getName();

                if ("BT".equals(name)) {
                    inText = true;
                    keepBlock = true;
                    textY = 0;
                    textBlock.clear();
                    textBlock.add(token);
                    continue;
                }
                if ("ET".equals(name)) {
                    textBlock.add(token);
                    if (keepBlock) {
                        filtered.addAll(textBlock);
                    }
                    inText = false;
                    textBlock.clear();
                    continue;
                }
                if (inText) {
                    if ("Tm".equals(name) && textBlock.size() >= 6) {
                        // Tm operands: a b c d e f — f is the y translation
                        Object fObj = textBlock.get(textBlock.size() - 1);
                        if (fObj instanceof COSNumber num) {
                            textY = num.floatValue();
                            if (textY < pdfYBottom || textY > pdfYTop) {
                                keepBlock = false;
                            }
                        }
                    } else if (("Td".equals(name) || "TD".equals(name))
                            && textBlock.size() >= 2) {
                        // Td/TD operands: tx ty — ty is relative y offset
                        Object tyObj = textBlock.get(textBlock.size() - 1);
                        if (tyObj instanceof COSNumber num) {
                            textY += num.floatValue();
                            if (textY < pdfYBottom || textY > pdfYTop) {
                                keepBlock = false;
                            }
                        }
                    }
                    textBlock.add(token);
                    continue;
                }
            }

            if (inText) {
                textBlock.add(token);
            } else {
                filtered.add(token);
            }
        }

        // Write filtered content back to the form's stream
        try (OutputStream os = form.getCOSObject()
                .createOutputStream(COSName.FLATE_DECODE)) {
            ContentStreamWriter writer = new ContentStreamWriter(os);
            writer.writeTokens(filtered);
        }
    }
}
