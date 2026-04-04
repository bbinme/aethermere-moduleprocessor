package com.dnd.processor.parsers;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the content of a ```stat fence into a structured field map.
 *
 * Dispatches to the appropriate sub-parser based on format detection.
 * Formats: F1 (inline semicolon), F2 (labeled block), F3 (narrative character),
 * F4 (NPC capsule), F5 (compact card), F6 (army unit).
 *
 * Fields absent from the source text are absent from the returned map.
 */
public class InlineStatBlockParser {

    // ── Format detection ─────────────────────────────────────────────────────

    private static final Pattern F2_FIELD_LINE = Pattern.compile(
            "^(FREQUENCY|#\\s*APPEARING|ARMOR\\s+CLASS|MOVE|HIT\\s+DICE|" +
            "%\\s*IN\\s+LAIR|TREASURE\\s+TYPE|#\\s*ATTACKS?|DAMAGE|" +
            "SPECIAL\\s+ATTACKS?|SPECIAL\\s+DEFENSES?|MAGIC\\s+RESISTANCE|" +
            "INTELLIGENCE|ALIGNMENT|SIZE|PSIONIC\\s+ABILITY|XP\\s+VALUE)" +
            "[:\\s]",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern F3_ABILITY = Pattern.compile(
            "\\b(Strength|Intelligence|Wisdom|Dexterity|Constitution|Charisma)\\s+\\d",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern F3_THAC0_DETECT = Pattern.compile(
            "\\bTHAC0\\s+\\d", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern F5_ABILITY_LINE = Pattern.compile(
            "\\bSTR\\s+\\d+\\b.*\\b(?:WIS|DEX)\\s+\\d+\\b.*\\b(?:CON|CHR|INT)\\s+\\d+\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern F6_UNIT_LINE = Pattern.compile(
            "^\\d+\\s+[A-Z][a-z]+.*\\([A-Za-z/]+\\)\\s+\\d+\\s+hp\\b",
            Pattern.CASE_INSENSITIVE
    );

    // ── F1 patterns ───────────────────────────────────────────────────────────

    private static final Pattern F1_AL   = Pattern.compile("(?:^|[;:\\s])AL\\s+([A-Za-z]+)",       Pattern.CASE_INSENSITIVE);
    private static final Pattern F1_MV   = Pattern.compile("(?:^|[;:\\s])MV\\s+([^;,\\n\\r]+)",   Pattern.CASE_INSENSITIVE);
    private static final Pattern F1_HP   = Pattern.compile("(?:^|[;:\\s])hp\\s+([\\d,x\\s]+)",    Pattern.CASE_INSENSITIVE);
    private static final Pattern F1_AC   = Pattern.compile("(?:^|[;:\\s])AC\\s+(\\d+)",           Pattern.CASE_INSENSITIVE);
    private static final Pattern F1_HD   = Pattern.compile("(?:^|[;:\\s])HD\\s+([\\d+/]+)",       Pattern.CASE_INSENSITIVE);
    private static final Pattern F1_AT   = Pattern.compile("(?:^|[;:\\s])#AT\\s+([^;]+)",         Pattern.CASE_INSENSITIVE);
    private static final Pattern F1_DMG  = Pattern.compile("(?:^|[;:\\s])Dmg\\s+([^;]+)",         Pattern.CASE_INSENSITIVE);
    private static final Pattern F1_THAC = Pattern.compile("(?:^|[;:\\s])THAC0\\s+(\\d+)",        Pattern.CASE_INSENSITIVE);

    // Name header: optional count, name, optional (type) and [descriptor], then period or colon
    private static final Pattern F1_HEADER = Pattern.compile(
            "^(\\d+\\s+)?([A-Z][^\\.(\\[]+?)(?:\\s*\\(([^)]+)\\))?(?:\\s*\\[([^\\]]+)\\])?[.:]"
    );

    // ── F2 patterns ───────────────────────────────────────────────────────────

    private static final Pattern F2_FREQUENCY  = Pattern.compile("FREQUENCY[:\\s]+(.+)",               Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_APPEARING   = Pattern.compile("#\\s*APPEARING[:\\s]+(.+)",          Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_AC          = Pattern.compile("ARMOR\\s+CLASS[:\\s]+(\\S+)",        Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_MOVE        = Pattern.compile("MOVE[:\\s]+(.+)",                    Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_HD          = Pattern.compile("HIT\\s+DICE[:\\s]+(.+)",             Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_IN_LAIR     = Pattern.compile("%\\s*IN\\s+LAIR[:\\s]+(.+)",         Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_TREASURE    = Pattern.compile("TREASURE\\s+TYPE[:\\s]+(.+)",        Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_ATTACKS     = Pattern.compile("#\\s*ATTACKS?[:\\s]+(.+)",           Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_DAMAGE      = Pattern.compile("DAMAGE[:\\s]+(.+)",                  Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_SP_ATK      = Pattern.compile("SPECIAL\\s+ATTACKS?[:\\s]+(.+)",     Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_SP_DEF      = Pattern.compile("SPECIAL\\s+DEFENSES?[:\\s]+(.+)",    Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_MR          = Pattern.compile("MAGIC\\s+RESISTANCE[:\\s]+(.+)",     Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_INTEL       = Pattern.compile("INTELLIGENCE[:\\s]+(.+)",            Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_ALIGN       = Pattern.compile("ALIGNMENT[:\\s]+(.+)",               Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_SIZE        = Pattern.compile("SIZE[:\\s]+(.+)",                    Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_PSIONIC     = Pattern.compile("PSIONIC\\s+ABILITY[:\\s]+(.+)",      Pattern.CASE_INSENSITIVE);
    private static final Pattern F2_XP          = Pattern.compile("XP\\s+VALUE[:\\s]+(.+)",             Pattern.CASE_INSENSITIVE);

    // ── F3 patterns ───────────────────────────────────────────────────────────

    private static final Pattern F3_STR   = Pattern.compile("Strength[:\\s]+(\\S+)",      Pattern.CASE_INSENSITIVE);
    private static final Pattern F3_INT   = Pattern.compile("Intelligence[:\\s]+(\\S+)",  Pattern.CASE_INSENSITIVE);
    private static final Pattern F3_WIS   = Pattern.compile("Wisdom[:\\s]+(\\S+)",        Pattern.CASE_INSENSITIVE);
    private static final Pattern F3_DEX   = Pattern.compile("Dexterity[:\\s]+(\\S+)",     Pattern.CASE_INSENSITIVE);
    private static final Pattern F3_CON   = Pattern.compile("Constitution[:\\s]+(\\S+)",  Pattern.CASE_INSENSITIVE);
    private static final Pattern F3_CHA   = Pattern.compile("Charisma[:\\s]+(\\S+)",      Pattern.CASE_INSENSITIVE);
    private static final Pattern F3_HP    = Pattern.compile("Hit\\s+Points?[:\\s]+(\\d+)",Pattern.CASE_INSENSITIVE);
    private static final Pattern F3_AC    = Pattern.compile("Armor\\s+Class[:\\s]+(\\S+)",Pattern.CASE_INSENSITIVE);
    private static final Pattern F3_MV    = Pattern.compile("Movement[:\\s]+(\\S+)",      Pattern.CASE_INSENSITIVE);
    private static final Pattern F3_THAC0 = Pattern.compile("THAC0[:\\s]+(\\d+)",         Pattern.CASE_INSENSITIVE);
    private static final Pattern F3_CLASS = Pattern.compile("(\\d+(?:st|nd|rd|th)[- ][Ll]evel[^\\n]*)", Pattern.CASE_INSENSITIVE);

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses the content of a single ```stat fence.
     *
     * @param fenceContent the text between the opening ```stat and closing ``` (trimmed)
     * @return structured field map; never null, may be empty
     */
    public Map<String, Object> parse(String fenceContent) {
        if (fenceContent == null || fenceContent.isBlank()) return new LinkedHashMap<>();

        String[] lines = fenceContent.strip().split("\n", -1);

        // Dispatch on format
        long f2FieldLines = Arrays.stream(lines)
                .filter(l -> F2_FIELD_LINE.matcher(l.trim()).find())
                .count();

        if (f2FieldLines >= 3) return parseF2(lines);

        if (F5_ABILITY_LINE.matcher(fenceContent).find()) return parseF5(lines);

        if (F3_THAC0_DETECT.matcher(fenceContent).find() && F3_ABILITY.matcher(fenceContent).find())
            return parseF3(fenceContent, lines);

        // Check F6: all non-blank lines match army unit pattern
        boolean allF6 = Arrays.stream(lines)
                .filter(l -> !l.trim().isEmpty())
                .allMatch(l -> F6_UNIT_LINE.matcher(l.trim()).find());
        if (allF6 && lines.length >= 2) return parseF6(lines);

        // Default: F1 inline semicolon
        return parseF1(fenceContent);
    }

    // ── F1 Parser ─────────────────────────────────────────────────────────────

    private Map<String, Object> parseF1(String text) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("format", "inline_semicolon");

        // Extract name header (before the period/colon)
        Matcher header = F1_HEADER.matcher(text.trim());
        if (header.find()) {
            String countStr = header.group(1);
            if (countStr != null) {
                try { fields.put("count", Integer.parseInt(countStr.trim())); }
                catch (NumberFormatException ignored) {}
            }
            fields.put("name", header.group(2).trim());
            if (header.group(3) != null) fields.put("type", header.group(3).trim());
            if (header.group(4) != null) fields.put("descriptor", header.group(4).trim());
        }

        extractGroup(text, F1_AL,   fields, "al");
        extractGroup(text, F1_MV,   fields, "mv");
        extractGroupTrimmed(text, F1_HP,  fields, "hp");
        extractGroup(text, F1_AC,   fields, "ac");
        extractGroup(text, F1_HD,   fields, "hd");
        extractGroupTrimmed(text, F1_AT,  fields, "at");
        extractGroupTrimmed(text, F1_DMG, fields, "dmg");
        extractGroup(text, F1_THAC, fields, "thac0");

        // Capture trailing notes after last semicolon field
        int lastSemi = text.lastIndexOf(';');
        if (lastSemi >= 0 && lastSemi < text.length() - 2) {
            String notes = text.substring(lastSemi + 1).trim();
            if (!notes.isBlank() && !notes.matches("[A-Z]+\\s+.*")) {
                fields.put("notes", notes);
            }
        }

        return fields;
    }

    // ── F2 Parser ─────────────────────────────────────────────────────────────

    private Map<String, Object> parseF2(String[] lines) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("format", "labeled_block");

        // First non-field line = creature name
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && !F2_FIELD_LINE.matcher(t).find()) {
                fields.put("name", t);
                break;
            }
        }

        String fullText = String.join("\n", lines);
        extractGroupTrimmed(fullText, F2_FREQUENCY, fields, "frequency");
        extractGroupTrimmed(fullText, F2_APPEARING,  fields, "appearing");
        extractGroup(fullText, F2_AC,       fields, "ac");
        extractGroupTrimmed(fullText, F2_MOVE,       fields, "move");
        extractGroupTrimmed(fullText, F2_HD,         fields, "hd");
        extractGroupTrimmed(fullText, F2_IN_LAIR,    fields, "in_lair");
        extractGroupTrimmed(fullText, F2_TREASURE,   fields, "treasure_type");
        extractGroupTrimmed(fullText, F2_ATTACKS,    fields, "attacks");
        extractGroupTrimmed(fullText, F2_DAMAGE,     fields, "damage");
        extractGroupTrimmed(fullText, F2_SP_ATK,     fields, "special_attacks");
        extractGroupTrimmed(fullText, F2_SP_DEF,     fields, "special_defenses");
        extractGroupTrimmed(fullText, F2_MR,         fields, "magic_resistance");
        extractGroupTrimmed(fullText, F2_INTEL,      fields, "intelligence");
        extractGroupTrimmed(fullText, F2_ALIGN,      fields, "alignment");
        extractGroupTrimmed(fullText, F2_SIZE,       fields, "size");
        extractGroupTrimmed(fullText, F2_PSIONIC,    fields, "psionic_ability");
        extractGroupTrimmed(fullText, F2_XP,         fields, "xp");

        return fields;
    }

    // ── F3 Parser ─────────────────────────────────────────────────────────────

    private Map<String, Object> parseF3(String fullText, String[] lines) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("format", "narrative_character");

        // Name: first non-blank line; title: second non-blank line if it doesn't look like class/level
        String name = null;
        String title = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (name == null) { name = t; continue; }
            if (title == null && !F3_CLASS.matcher(t).find()) { title = t; }
            break;
        }
        if (name != null) fields.put("name", name);
        if (title != null) fields.put("title", title);

        // Class/level line
        Matcher classMatch = F3_CLASS.matcher(fullText);
        if (classMatch.find()) fields.put("class_level", classMatch.group(1).trim());

        extractGroup(fullText, F3_STR,   fields, "str");
        extractGroup(fullText, F3_DEX,   fields, "dex");
        extractGroup(fullText, F3_CON,   fields, "con");
        extractGroup(fullText, F3_INT,   fields, "int");
        extractGroup(fullText, F3_WIS,   fields, "wis");
        extractGroup(fullText, F3_CHA,   fields, "cha");
        extractGroup(fullText, F3_THAC0, fields, "thac0");
        extractGroup(fullText, F3_HP,    fields, "hp");
        extractGroup(fullText, F3_AC,    fields, "ac");
        extractGroup(fullText, F3_MV,    fields, "mv");

        // Equipment: last non-empty line that doesn't look like a stat line
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (!t.isEmpty() && t.contains(" ") && !t.matches(".*\\b(STR|DEX|CON|INT|WIS|CHA|THAC0|AC|MV)\\b.*")) {
                fields.put("equipment", t);
                break;
            }
        }

        return fields;
    }

    // ── F5 Parser (compact card — minimal) ───────────────────────────────────

    private Map<String, Object> parseF5(String[] lines) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("format", "compact_card");
        // Name: first line
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()) { fields.put("name", t); break; }
        }
        fields.put("raw", String.join("\n", lines).trim());
        return fields;
    }

    // ── F6 Parser (army unit) ─────────────────────────────────────────────────

    private Map<String, Object> parseF6(String[] lines) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("format", "army_unit");
        List<Map<String, Object>> units = new ArrayList<>();
        Pattern unitPat = Pattern.compile(
                "^(\\d+)\\s+(.+?)\\s+\\(([A-Za-z/]+)\\)\\s+(\\d+)\\s+hp",
                Pattern.CASE_INSENSITIVE
        );
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            Matcher m = unitPat.matcher(t);
            if (m.find()) {
                Map<String, Object> unit = new LinkedHashMap<>();
                unit.put("count", Integer.parseInt(m.group(1)));
                unit.put("name", m.group(2).trim());
                unit.put("category", m.group(3).trim());
                unit.put("hp", Integer.parseInt(m.group(4)));
                units.add(unit);
            }
        }
        if (!units.isEmpty()) fields.put("units", units);
        return fields;
    }

    // ── Extraction Helpers ────────────────────────────────────────────────────

    private void extractGroup(String text, Pattern pattern, Map<String, Object> fields, String key) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            String val = m.group(1).trim();
            if (!val.isBlank()) fields.put(key, val);
        }
    }

    private void extractGroupTrimmed(String text, Pattern pattern, Map<String, Object> fields, String key) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            String val = m.group(1).trim().replaceAll("\\s+", " ");
            if (!val.isBlank() && !val.equalsIgnoreCase("nil")) fields.put(key, val);
        }
    }

    // ── Fence extraction utility ──────────────────────────────────────────────

    private static final Pattern STAT_FENCE = Pattern.compile(
            "```stat\\n(.*?)\\n```",
            Pattern.DOTALL
    );

    /**
     * Extracts and parses all ```stat fences from the given text.
     *
     * @return list of parsed field maps (one per fence)
     */
    public List<Map<String, Object>> extractAll(String text) {
        List<Map<String, Object>> result = new ArrayList<>();
        Matcher m = STAT_FENCE.matcher(text);
        while (m.find()) {
            result.add(parse(m.group(1).trim()));
        }
        return result;
    }

    /**
     * Returns the given text with all ```stat fences removed.
     */
    public String stripFences(String text) {
        return STAT_FENCE.matcher(text).replaceAll("").replaceAll("\n{3,}", "\n\n").trim();
    }
}
