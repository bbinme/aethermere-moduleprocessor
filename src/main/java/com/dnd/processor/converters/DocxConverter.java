package com.dnd.processor.converters;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Converts a DOCX file to Markdown using Apache POI.
 */
public class DocxConverter {

    /**
     * Converts the given DOCX file to a Markdown string.
     *
     * @param inputPath path to the DOCX file
     * @return Markdown text
     */
    public String convert(Path inputPath) throws IOException {
        StringBuilder sb = new StringBuilder();

        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(inputPath))) {
            // Process body elements in document order (paragraphs and tables interleaved)
            for (Object bodyElement : document.getBodyElements()) {
                if (bodyElement instanceof XWPFParagraph para) {
                    String paraText = processParagraph(para);
                    sb.append(paraText).append("\n");
                } else if (bodyElement instanceof XWPFTable table) {
                    String tableText = processTable(table);
                    sb.append(tableText).append("\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * Converts a paragraph to Markdown, applying heading styles.
     */
    private String processParagraph(XWPFParagraph para) {
        String text = para.getText();
        if (text == null || text.isBlank()) {
            return "";
        }

        String styleName = para.getStyle();
        if (styleName == null) {
            // Try the style ID as a fallback
            styleName = "";
        }

        // Normalize style name for comparison
        String styleUpper = styleName.toUpperCase();

        if (styleUpper.contains("HEADING1") || styleUpper.contains("HEADING 1")) {
            return "# " + text;
        } else if (styleUpper.contains("HEADING2") || styleUpper.contains("HEADING 2")) {
            return "## " + text;
        } else if (styleUpper.contains("HEADING3") || styleUpper.contains("HEADING 3")) {
            return "### " + text;
        } else {
            return text;
        }
    }

    /**
     * Converts a table to Markdown table syntax.
     */
    private String processTable(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();

        if (rows.isEmpty()) {
            return "";
        }

        boolean firstRow = true;
        for (XWPFTableRow row : rows) {
            List<XWPFTableCell> cells = row.getTableCells();
            sb.append("|");
            for (XWPFTableCell cell : cells) {
                String cellText = cell.getText().replace("|", "\\|").replace("\n", " ").trim();
                sb.append(" ").append(cellText).append(" |");
            }
            sb.append("\n");

            // After the first row, add a separator row
            if (firstRow) {
                sb.append("|");
                for (int i = 0; i < cells.size(); i++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
                firstRow = false;
            }
        }

        return sb.toString();
    }
}
