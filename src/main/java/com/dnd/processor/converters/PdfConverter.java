package com.dnd.processor.converters;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Converts a PDF file to Markdown text using per-band column-aware extraction.
 *
 * After extracting all pages individually, performs cross-page analysis to strip
 * running headers and footers, detects stat blocks, then assembles the final Markdown.
 */
public class PdfConverter {

    private static final String PAGE_SEPARATOR = "\n\n---\n\n";

    // ALL CAPS line with 3+ words
    private static final Pattern ALL_CAPS_HEADING = Pattern.compile(
            "^[A-Z][A-Z\\s'\",:;!?-]{2,}(?:\\s+[A-Z]+){2,}$"
    );

    // Room heading: number + ALL CAPS name (e.g. "1. STATUE ROOM", "5a. POTTERY JARS")
    private static final Pattern ROOM_NUMBER = Pattern.compile(
            "^\\d+[a-zA-Z]?\\.\\s+[A-Z][A-Z']+(?:\\s+[A-Z][A-Z']+)*$"
    );

    private final ColumnAwareTextStripper columnAwareStripper = new ColumnAwareTextStripper();
    private final RulesetDetector rulesetDetector = new RulesetDetector();
    private final StatBlockDetector statBlockDetector = new StatBlockDetector();

    public ConversionResult convert(Path inputPath) throws IOException {
        return convert(inputPath, null);
    }

    /**
     * Converts a layout-preprocessed PDF to Markdown.
     *
     * @param inputPath path to the PDF file
     * @param glossary  optional glossary resource path (e.g. "glossaries/B4.txt")
     *                  for module-specific terms used during fragment reconstruction
     */
    public ConversionResult convert(Path inputPath, String glossary) throws IOException {
        List<String> pageTexts = new ArrayList<>();
        RulesetInfo ruleset;

        try (PDDocument doc = Loader.loadPDF(inputPath.toFile())) {
            ruleset = rulesetDetector.detect(doc);
            int pageCount = doc.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                pageTexts.add(columnAwareStripper.extractPageByCropBox(doc, i));
            }
        }

        pageTexts = stripRunningHeadersFooters(pageTexts);

        StringBuilder fullText = new StringBuilder();
        for (int i = 0; i < pageTexts.size(); i++) {
            if (i > 0) fullText.append(PAGE_SEPARATOR);
            fullText.append(pageTexts.get(i));
        }

        FragmentCleaner fragmentCleaner = new FragmentCleaner(120, glossary);

        List<String> unknownWords = fragmentCleaner.findUnknownWords(fullText.toString());
        if (!unknownWords.isEmpty()) {
            System.out.println("[glossary] " + unknownWords.size()
                    + " unknown words found: " + unknownWords);
        }

        String cleaned = fragmentCleaner.clean(fullText.toString());
        String withFences = statBlockDetector.markStatBlocks(cleaned);
        String markdown = toMarkdown(withFences);
        return new ConversionResult(markdown, ruleset);
    }

    /**
     * Extracts only sidebar text from a PDF, one section per detected sidebar page.
     * Useful for verifying sidebar detection accuracy before full conversion.
     *
     * @param inputPath path to the PDF file
     * @return formatted string with each page's sidebar text labeled by page number,
     *         or a "no sidebars" message if none were found
     */
    public String convertSidebarsOnly(Path inputPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        int sidebarCount = 0;

        try (PDDocument doc = Loader.loadPDF(inputPath.toFile())) {
            int pageCount = doc.getNumberOfPages();
            float mainBoundary = columnAwareStripper.detectDocumentColumnBoundary(doc, pageCount);

            if (mainBoundary <= 0) {
                return "No two-column layout detected — no sidebars to extract.\n";
            }

            for (int i = 0; i < pageCount; i++) {
                String sidebar = columnAwareStripper.extractPageSidebar(doc, i, mainBoundary);
                if (!sidebar.isEmpty()) {
                    sb.append("--- Page ").append(i + 1).append(" Sidebar ---\n");
                    sb.append(sidebar).append("\n\n");
                    sidebarCount++;
                }
            }
        }

        if (sidebarCount == 0) {
            return "No sidebars detected across all pages.\n";
        }
        return sb.toString();
    }

    /**
     * Identifies lines that appear on 3 or more pages (running headers/footers) and removes them.
     * The threshold is at least 3 pages, or 25% of total pages, whichever is higher.
     */
    private List<String> stripRunningHeadersFooters(List<String> pages) {
        if (pages.size() < 3) return pages;

        // Count how many distinct pages each trimmed non-blank line appears on
        Map<String, Integer> linePageCount = new HashMap<>();
        for (String page : pages) {
            Set<String> seenOnPage = new HashSet<>();
            for (String line : page.split("\n", -1)) {
                String trimmed = line.trim();
                // Skip very short lines and pure punctuation — likely not headers
                if (trimmed.length() <= 3) continue;
                if (seenOnPage.add(trimmed)) {
                    linePageCount.merge(trimmed, 1, Integer::sum);
                }
            }
        }

        int threshold = Math.max(3, pages.size() / 4);
        Set<String> runningLines = new HashSet<>();
        for (Map.Entry<String, Integer> entry : linePageCount.entrySet()) {
            if (entry.getValue() >= threshold) {
                runningLines.add(entry.getKey());
            }
        }

        if (runningLines.isEmpty()) return pages;

        List<String> result = new ArrayList<>(pages.size());
        for (String page : pages) {
            StringBuilder sb = new StringBuilder();
            for (String line : page.split("\n", -1)) {
                if (!runningLines.contains(line.trim())) {
                    sb.append(line).append("\n");
                }
            }
            result.add(sb.toString());
        }
        return result;
    }

    /**
     * Applies basic Markdown formatting heuristics to raw extracted text.
     * Lines inside ```stat fences are passed through unchanged.
     */
    private String toMarkdown(String rawText) {
        // Pre-split lines that contain embedded room headings
        String presplit = splitEmbeddedHeadings(rawText);
        String[] lines = presplit.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inFence = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Track fence boundaries — pass all fence content through unchanged
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                sb.append(line).append("\n");
                continue;
            }

            if (inFence) {
                sb.append(line).append("\n");
                continue;
            }

            if (trimmed.isEmpty()) {
                sb.append(line).append("\n");
                continue;
            }

            if (trimmed.equals("---")) {
                sb.append(line).append("\n");
                continue;
            }

            // Blockquote lines (read-aloud / sidebars) — pass through as-is
            if (trimmed.startsWith(">")) {
                sb.append(line).append("\n");
                continue;
            }

            // Strip bold/italic markers before pattern matching so that lines like
            // "**1. ROOM NAME: ...**" still get promoted to headings.
            String bare = trimmed.replaceAll("\\*+", "").trim();

            // Room number → H3  (use bare text in heading; bold is redundant inside ###)
            if (ROOM_NUMBER.matcher(bare).matches()) {
                sb.append("### ").append(bare).append("\n");
                continue;
            }

            // ALL CAPS with 3+ words → H2
            if (isAllCapsHeading(bare)) {
                sb.append("## ").append(bare).append("\n");
                continue;
            }

            sb.append(line).append("\n");
        }

        return sb.toString();
    }

    private boolean isAllCapsHeading(String line) {
        if (!line.equals(line.toUpperCase())) return false;
        return line.trim().split("\\s+").length >= 3;
    }

    /**
     * Splits lines that contain embedded room headings.
     * E.g. "KEY TO TIER 2 2. STORAGE ROOM This room contains..."
     * becomes three separate lines:
     *   "KEY TO TIER 2"
     *   "2. STORAGE ROOM"
     *   "This room contains..."
     */
    private String splitEmbeddedHeadings(String text) {
        // Pattern: room number heading embedded in a line
        // Matches "N. ROOM NAME" where N is digits + optional letter
        // Match room heading: "N. ROOM NAME" where name is ALL CAPS words (2+ chars each),
        // stopping before any single uppercase letter followed by lowercase.
        Pattern embeddedRoom = Pattern.compile(
                "(\\d+[a-zA-Z]?\\.\\s+(?:[A-Z][A-Z']+(?:\\s+|$))*[A-Z][A-Z']+)(?=\\s|$)");

        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            String bare = line.trim().replaceAll("\\*+", "").trim();
            java.util.regex.Matcher m = embeddedRoom.matcher(bare);

            if (m.find()) {
                String before = bare.substring(0, m.start()).trim();
                String heading = m.group(1).trim();
                String after = bare.substring(m.end()).trim();

                // Only split if there's text before or after the heading
                if (before.isEmpty() && after.isEmpty()) {
                    result.append(line).append("\n");
                } else {
                    if (!before.isEmpty()) {
                        result.append(before).append("\n");
                    }
                    result.append(heading).append("\n");
                    if (!after.isEmpty()) {
                        result.append(after).append("\n");
                    }
                }
            } else {
                result.append(line).append("\n");
            }
        }

        // Remove trailing extra newline added by the loop
        if (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }
}
