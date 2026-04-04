package com.dnd.processor.parsers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured fields from a LOCATION section.
 */
public class LocationExtractor {

    private final InlineStatBlockParser statBlockParser = new InlineStatBlockParser();

    // Blockquote lines: start with "> "
    private static final Pattern BLOCKQUOTE_LINE = Pattern.compile("^>\\s?(.*)$", Pattern.MULTILINE);

    // Italic runs: *text* or _text_
    private static final Pattern ITALIC_PATTERN = Pattern.compile("[*_](.+?)[*_]");

    // "Read aloud:" keyword
    private static final Pattern READ_ALOUD_KEYWORD = Pattern.compile(
            "(?i)read aloud:\\s*(.+?)(?=\\n\\n|$)", Pattern.DOTALL
    );

    // Monster count pattern: "3 trolls", "1 gnoll", etc.
    private static final Pattern MONSTER_COUNT = Pattern.compile(
            "(?i)\\b(\\d+)\\s+(troll|gnoll|demon|devil|orc|goblin|kobold|hobgoblin|bugbear|ogre|giant|dragon|undead|zombie|skeleton|vampire|ghost|wraith|specter|wight|ghoul|lich|mummy|golem|elemental|djinn|efreeti|succubus|incubus|balor|pit fiend|illithid|mind flayer|beholder|aboleth|yuan-ti|drow|duergar|troglodyte|lizardman|sahuagin|merrow|harpy|medusa|basilisk|gorgon|manticore|owlbear|displacer beast|rust monster|gelatinous cube|mimic|minotaur|centaur|griffon|hippogriff|pegasus|unicorn|wyvern|hydra|chimera|sphinx)s?\\b"
    );

    // Treasure summary: sentences with currency
    private static final Pattern TREASURE_SENTENCE = Pattern.compile(
            "[^.!?]*(?:\\b(?:gp|pp|sp|ep|cp)\\b|magic item|potion|scroll|sword|ring|staff|wand)[^.!?]*[.!?]",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Extracts fields from a LOCATION section's text.
     *
     * @param sectionText the raw body text of the section
     * @return map with keys: read_aloud, dm_notes, monsters, treasure_summary, stat_blocks
     */
    public Map<String, Object> extract(String sectionText) {
        Map<String, Object> fields = new LinkedHashMap<>();

        // Extract and remove stat block fences first
        List<Map<String, Object>> statBlocks = statBlockParser.extractAll(sectionText);
        String cleanText = statBlockParser.stripFences(sectionText);

        StringBuilder readAloud = new StringBuilder();
        StringBuilder dmNotes = new StringBuilder();

        // Extract blockquote lines as read-aloud
        Matcher bqMatcher = BLOCKQUOTE_LINE.matcher(cleanText);
        while (bqMatcher.find()) {
            readAloud.append(bqMatcher.group(1)).append("\n");
        }

        // If no blockquotes, check for "Read aloud:" keyword
        if (readAloud.length() == 0) {
            Matcher kaMatcher = READ_ALOUD_KEYWORD.matcher(cleanText);
            if (kaMatcher.find()) {
                readAloud.append(kaMatcher.group(1).trim());
            }
        }

        // DM notes: lines that are not blockquotes, not italic-only, not read aloud keyword
        String[] lines = cleanText.split("\n", -1);
        boolean skipReadAloudKeywordBlock = false;
        for (String line : lines) {
            String stripped = line.trim();

            if (stripped.startsWith(">")) continue;

            if (stripped.toLowerCase().startsWith("read aloud:")) {
                skipReadAloudKeywordBlock = true;
                continue;
            }

            if (skipReadAloudKeywordBlock && stripped.isEmpty()) {
                skipReadAloudKeywordBlock = false;
                continue;
            }

            if (skipReadAloudKeywordBlock) {
                if (readAloud.length() == 0 || !readAloud.toString().contains(stripped)) {
                    readAloud.append(stripped).append("\n");
                }
                continue;
            }

            if (isEntirelyItalic(stripped)) {
                readAloud.append(stripped.replaceAll("[*_]", "")).append("\n");
                continue;
            }

            dmNotes.append(line).append("\n");
        }

        String readAloudStr = readAloud.toString().trim();
        if (!readAloudStr.isEmpty()) fields.put("read_aloud", readAloudStr);

        String dmNotesStr = dmNotes.toString().trim();
        if (!dmNotesStr.isEmpty()) fields.put("dm_notes", dmNotesStr);

        // Monsters from dm_notes (fallback when no stat blocks detected)
        if (statBlocks.isEmpty()) {
            List<String> monsters = new ArrayList<>();
            Matcher monsterMatcher = MONSTER_COUNT.matcher(cleanText);
            while (monsterMatcher.find()) {
                String count = monsterMatcher.group(1);
                String monsterName = monsterMatcher.group(2);
                monsters.add(count + " " + monsterName + (count.equals("1") ? "" : "s"));
            }
            if (!monsters.isEmpty()) fields.put("monsters", monsters);
        }

        // Treasure summary
        List<String> treasureSentences = new ArrayList<>();
        Matcher treasureMatcher = TREASURE_SENTENCE.matcher(cleanText);
        while (treasureMatcher.find()) {
            treasureSentences.add(treasureMatcher.group().trim());
        }
        if (!treasureSentences.isEmpty()) {
            fields.put("treasure_summary", String.join(" ", treasureSentences));
        }

        if (!statBlocks.isEmpty()) fields.put("stat_blocks", statBlocks);

        return fields;
    }

    private boolean isEntirelyItalic(String line) {
        if (line.isEmpty()) return false;
        return (line.startsWith("*") && line.endsWith("*") && line.length() > 2)
                || (line.startsWith("_") && line.endsWith("_") && line.length() > 2);
    }
}
