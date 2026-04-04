package com.dnd.processor.parsers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured fields from a STAT_BLOCK section.
 */
public class StatBlockExtractor {

    private static final Pattern AC_PATTERN          = Pattern.compile("AC (\\S+)",                   Pattern.CASE_INSENSITIVE);
    private static final Pattern HD_PATTERN          = Pattern.compile("HD ([\\d+]+)",                 Pattern.CASE_INSENSITIVE);
    private static final Pattern HP_PATTERN          = Pattern.compile("hp (\\d+)",                    Pattern.CASE_INSENSITIVE);
    private static final Pattern MV_PATTERN          = Pattern.compile("MV ([\\d\"]+)",                Pattern.CASE_INSENSITIVE);
    private static final Pattern AT_PATTERN          = Pattern.compile("#AT (\\d+)",                   Pattern.CASE_INSENSITIVE);
    private static final Pattern DMG_PATTERN         = Pattern.compile("D ([0-9\\-/]+)",               Pattern.CASE_INSENSITIVE);
    private static final Pattern MR_PATTERN          = Pattern.compile("MR (\\d+%)",                   Pattern.CASE_INSENSITIVE);

    private static final Pattern SPECIAL_ATTACKS     = Pattern.compile(
            "(?i)SPECIAL ATTACKS?:\\s*(.+?)(?=\\n[A-Z ]+:|$)", Pattern.DOTALL
    );
    private static final Pattern SPECIAL_DEFENSES    = Pattern.compile(
            "(?i)SPECIAL DEFENSES?:\\s*(.+?)(?=\\n[A-Z ]+:|$)", Pattern.DOTALL
    );
    private static final Pattern ALIGNMENT_PATTERN   = Pattern.compile(
            "(?i)ALIGNMENT:\\s*(\\S.*?)(?=\\n|$)"
    );

    /**
     * Extracts fields from a STAT_BLOCK section's text.
     *
     * @param sectionText the raw body text of the section
     * @return map with stat block fields
     */
    public Map<String, Object> extract(String sectionText) {
        Map<String, Object> fields = new LinkedHashMap<>();

        // Name: first non-blank line
        String[] lines = sectionText.split("\n", -1);
        String name = null;
        for (String line : lines) {
            String stripped = line.trim();
            if (!stripped.isEmpty()) {
                name = stripped;
                break;
            }
        }
        if (name != null) {
            fields.put("name", name);
        }

        // Raw stat block preserved
        fields.put("raw_stat_block", sectionText);

        // Regex-extracted numeric/coded fields
        extractGroup(sectionText, AC_PATTERN,  fields, "ac");
        extractGroup(sectionText, HD_PATTERN,  fields, "hd");
        extractGroup(sectionText, HP_PATTERN,  fields, "hp");
        extractGroup(sectionText, MV_PATTERN,  fields, "movement");
        extractGroup(sectionText, AT_PATTERN,  fields, "attacks");
        extractGroup(sectionText, DMG_PATTERN, fields, "damage");
        extractGroup(sectionText, MR_PATTERN,  fields, "magic_resistance");

        // Label-based fields
        extractGroupTrimmed(sectionText, SPECIAL_ATTACKS,  fields, "special_attacks");
        extractGroupTrimmed(sectionText, SPECIAL_DEFENSES, fields, "special_defenses");
        extractGroupTrimmed(sectionText, ALIGNMENT_PATTERN, fields, "alignment");

        return fields;
    }

    private void extractGroup(String text, Pattern pattern, Map<String, Object> fields, String key) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            fields.put(key, m.group(1));
        }
    }

    private void extractGroupTrimmed(String text, Pattern pattern, Map<String, Object> fields, String key) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            String val = m.group(1).trim();
            if (!val.isEmpty()) {
                fields.put(key, val);
            }
        }
    }
}
