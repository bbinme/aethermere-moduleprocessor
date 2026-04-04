package com.dnd.processor.parsers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured fields from an NPC section.
 */
public class NpcExtractor {

    private final InlineStatBlockParser statBlockParser = new InlineStatBlockParser();

    // Stat line: contains "AC \d"
    private static final Pattern STAT_LINE_PATTERN = Pattern.compile(
            "(?i).*AC \\d.*"
    );

    // Spells label
    private static final Pattern SPELLS_LABEL = Pattern.compile(
            "(?i)spells:\\s*(.+?)(?=\\n|$)"
    );

    // Proper noun (capitalized word, not at start of sentence after period)
    private static final Pattern PROPER_NOUN = Pattern.compile(
            "(?<![.!?]\\s)\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b"
    );

    // Spell name separator
    private static final Pattern SPELL_SEPARATOR = Pattern.compile("[,;/]\\s*");

    /**
     * Extracts fields from an NPC section's text.
     *
     * @param sectionText the raw body text of the section
     * @return map with keys: name, stat_line, spells, description, stat_blocks
     */
    public Map<String, Object> extract(String sectionText) {
        Map<String, Object> fields = new LinkedHashMap<>();

        // Extract and remove stat block fences first
        List<Map<String, Object>> statBlocks = statBlockParser.extractAll(sectionText);
        String cleanText = statBlockParser.stripFences(sectionText);

        String[] lines = cleanText.split("\n", -1);

        String name = null;
        String statLine = null;
        StringBuilder descriptionBuilder = new StringBuilder();

        for (String line : lines) {
            String stripped = line.trim();
            if (stripped.isEmpty()) {
                descriptionBuilder.append("\n");
                continue;
            }

            if (statLine == null && STAT_LINE_PATTERN.matcher(stripped).matches()) {
                statLine = stripped;
                continue;
            }

            if (name == null) {
                Matcher pn = PROPER_NOUN.matcher(stripped);
                if (pn.find()) {
                    name = pn.group(1);
                } else {
                    name = stripped;
                }
            }

            descriptionBuilder.append(line).append("\n");
        }

        if (name != null)    fields.put("name", name);
        if (statLine != null) fields.put("stat_line", statLine);

        // Extract spells
        List<String> spells = new ArrayList<>();
        Matcher spellsMatcher = SPELLS_LABEL.matcher(cleanText);
        if (spellsMatcher.find()) {
            String spellList = spellsMatcher.group(1).trim();
            spellList = spellList.replaceAll("\\(\\d+\\w*\\)", "").trim();
            String[] spellNames = SPELL_SEPARATOR.split(spellList);
            for (String spell : spellNames) {
                String s = spell.trim();
                if (!s.isEmpty()) spells.add(s);
            }
        }
        if (!spells.isEmpty()) fields.put("spells", spells);

        String description = descriptionBuilder.toString().trim();
        if (!description.isEmpty()) fields.put("description", description);

        if (!statBlocks.isEmpty()) fields.put("stat_blocks", statBlocks);

        return fields;
    }
}
