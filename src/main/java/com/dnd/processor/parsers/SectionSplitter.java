package com.dnd.processor.parsers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a Markdown document into sections based on heading lines.
 */
public class SectionSplitter {

    /**
     * Represents a single section in the document.
     *
     * @param heading the heading text (without # prefixes)
     * @param level   heading level (0 = preamble, 1 = H1, 2 = H2, 3 = H3)
     * @param body    body text following the heading until the next heading of equal or higher level
     */
    public record Section(String heading, int level, String body) {}

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(#{1,3})\\s+(.+)$", Pattern.MULTILINE
    );

    /**
     * Splits the given Markdown string into a list of Sections.
     *
     * Content before the first heading is returned as a Section with heading "Preamble" and level 0.
     *
     * @param markdown the Markdown text to split
     * @return ordered list of sections
     */
    public List<Section> split(String markdown) {
        List<Section> sections = new ArrayList<>();

        if (markdown == null || markdown.isEmpty()) {
            return sections;
        }

        String[] lines = markdown.split("\n", -1);

        // State machine: accumulate lines until we hit a heading
        String currentHeading = "Preamble";
        int currentLevel = 0;
        StringBuilder currentBody = new StringBuilder();
        boolean inPreamble = true;

        for (String line : lines) {
            Matcher m = HEADING_PATTERN.matcher(line);
            if (m.matches()) {
                int level = m.group(1).length();
                String headingText = m.group(2).trim();

                if (inPreamble) {
                    // Save preamble content (may be empty)
                    String preambleBody = currentBody.toString().trim();
                    if (!preambleBody.isEmpty()) {
                        sections.add(new Section("Preamble", 0, preambleBody));
                    }
                    inPreamble = false;
                } else {
                    // Save previous section
                    sections.add(new Section(currentHeading, currentLevel, currentBody.toString().trim()));
                }

                currentHeading = headingText;
                currentLevel = level;
                currentBody = new StringBuilder();
            } else {
                if (!inPreamble || !line.isBlank() || currentBody.length() > 0) {
                    currentBody.append(line).append("\n");
                }
            }
        }

        // Save the last section
        if (!inPreamble) {
            sections.add(new Section(currentHeading, currentLevel, currentBody.toString().trim()));
        } else {
            // Entire document had no headings
            String preambleBody = currentBody.toString().trim();
            if (!preambleBody.isEmpty()) {
                sections.add(new Section("Preamble", 0, preambleBody));
            }
        }

        return sections;
    }
}
