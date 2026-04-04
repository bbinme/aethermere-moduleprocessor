package com.dnd.processor.parsers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured fields from an ENCOUNTER section.
 */
public class EncounterExtractor {

    private final InlineStatBlockParser statBlockParser = new InlineStatBlockParser();

    // Enemy group pattern: number + creature name, optionally followed by parenthetical stats
    private static final Pattern ENEMY_GROUP = Pattern.compile(
            "(?i)\\b(\\d+|[Oo]ne|[Tt]wo|[Tt]hree|[Ff]our|[Ff]ive|[Ss]ix|[Ss]even|[Ee]ight|[Nn]ine|[Tt]en|[Aa]\\s)" +
            "\\s+([A-Za-z][a-z]+(?:\\s+[a-z]+)?)" +
            "(?:\\s*\\([^)]*\\))?"
    );

    // Tactics keywords
    private static final Pattern TACTICS_KW = Pattern.compile(
            "(?i)\\b(attack|flee|charge|retreat|melee)\\b"
    );

    // Terrain keywords
    private static final Pattern TERRAIN_KW = Pattern.compile(
            "(?i)\\b(floor|ceiling|wall|door|room|corridor)\\b"
    );

    // Treasure: sentences with currency or magic items
    private static final Pattern TREASURE_SENTENCE = Pattern.compile(
            "[^.!?]*(?:\\b(?:gp|pp|sp|ep|cp)\\b|magic item|potion|scroll|sword|ring|staff|wand)[^.!?]*[.!?]",
            Pattern.CASE_INSENSITIVE
    );

    // Sentence splitter
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    /**
     * Extracts fields from an ENCOUNTER section's text.
     *
     * @param sectionText the raw body text of the section
     * @return map with keys: enemies, tactics, terrain_notes, treasure, stat_blocks
     */
    public Map<String, Object> extract(String sectionText) {
        Map<String, Object> fields = new LinkedHashMap<>();

        // Extract and remove stat block fences first
        List<Map<String, Object>> statBlocks = statBlockParser.extractAll(sectionText);
        String cleanText = statBlockParser.stripFences(sectionText);

        // Extract enemy groups
        List<String> enemies = new ArrayList<>();
        Matcher enemyMatcher = ENEMY_GROUP.matcher(cleanText);
        while (enemyMatcher.find()) {
            String full = enemyMatcher.group().trim();
            int end = enemyMatcher.end();
            if (end < cleanText.length() && cleanText.charAt(end) == '(') {
                int closeIdx = cleanText.indexOf(')', end);
                if (closeIdx > 0) {
                    full = full + cleanText.substring(end, closeIdx + 1);
                }
            }
            if (!enemies.contains(full)) {
                enemies.add(full);
            }
        }
        if (!enemies.isEmpty()) fields.put("enemies", enemies);

        // Split into sentences for tactics and terrain extraction
        String[] sentences = SENTENCE_END.split(cleanText);

        List<String> tactics = new ArrayList<>();
        List<String> terrainNotes = new ArrayList<>();

        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;

            boolean isTactics = TACTICS_KW.matcher(s).find();
            boolean isTerrain = TERRAIN_KW.matcher(s).find();

            if (isTactics)  tactics.add(s);
            if (isTerrain)  terrainNotes.add(s);
        }

        if (!tactics.isEmpty())      fields.put("tactics", tactics);
        if (!terrainNotes.isEmpty()) fields.put("terrain_notes", terrainNotes);

        // Extract treasure
        List<String> treasureLines = new ArrayList<>();
        Matcher treasureMatcher = TREASURE_SENTENCE.matcher(cleanText);
        while (treasureMatcher.find()) {
            treasureLines.add(treasureMatcher.group().trim());
        }
        if (!treasureLines.isEmpty()) fields.put("treasure", String.join(" ", treasureLines));

        if (!statBlocks.isEmpty()) fields.put("stat_blocks", statBlocks);

        return fields;
    }
}
