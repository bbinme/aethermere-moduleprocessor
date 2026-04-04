package com.dnd.processor.parsers;

import com.dnd.processor.model.ComponentType;

import java.util.regex.Pattern;

/**
 * Classifies a Section into a ComponentType using keyword matching and structural patterns.
 * All matching is case-insensitive.
 */
public class ComponentClassifier {

    // Priority 1: room number pattern
    private static final Pattern ROOM_NUMBER_PATTERN = Pattern.compile(
            "^\\d+[A-Z]?\\.\\s+", Pattern.CASE_INSENSITIVE
    );

    // Priority 2: stat block signals
    private static final Pattern STAT_BLOCK_AC = Pattern.compile("AC \\d", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAT_BLOCK_HD = Pattern.compile("HD \\d", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAT_BLOCK_HP = Pattern.compile("hp \\d", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAT_BLOCK_AT = Pattern.compile("#AT", Pattern.CASE_INSENSITIVE);

    // Priority 3: wandering monster table
    private static final Pattern WANDERING_MONSTER_KW = Pattern.compile(
            "wandering monster", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DIE_ROLL = Pattern.compile("die roll", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONSTER_COL = Pattern.compile("\\bmonster\\b", Pattern.CASE_INSENSITIVE);

    // Priority 4: rules variant
    private static final Pattern RULES_VARIANT_KW = Pattern.compile(
            "spell alteration|magic item alteration", Pattern.CASE_INSENSITIVE
    );

    // Priority 5: alternate world
    private static final Pattern ALTERNATE_WORLD_KW = Pattern.compile(
            "\\bgate\\b|\\bdoorway\\b|prime material plane|alternate world", Pattern.CASE_INSENSITIVE
    );

    // Priority 6: overview
    private static final Pattern OVERVIEW_KW = Pattern.compile(
            "background|synopsis|overview|preface|introduction", Pattern.CASE_INSENSITIVE
    );

    // Priority 7: hook
    private static final Pattern HOOK_KW = Pattern.compile(
            "adventure hook|\\bhook\\b", Pattern.CASE_INSENSITIVE
    );

    // Priority 8: trap
    private static final Pattern TRAP_KW = Pattern.compile(
            "\\btrap\\b|\\btrigger\\b|glyph of warding|disable device", Pattern.CASE_INSENSITIVE
    );

    // Priority 9: puzzle
    private static final Pattern PUZZLE_KW = Pattern.compile(
            "\\bpuzzle\\b|\\briddle\\b", Pattern.CASE_INSENSITIVE
    );

    // Priority 10: treasure
    private static final Pattern TREASURE_KW = Pattern.compile(
            "\\btreasure\\b|\\bcontains\\b", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TREASURE_CURRENCY = Pattern.compile(
            "\\bgp\\b|\\bpp\\b|\\bsp\\b|\\bep\\b|\\bcp\\b", Pattern.CASE_INSENSITIVE
    );

    // Priority 11: NPC
    private static final Pattern NPC_KW = Pattern.compile(
            "\\bpersonality\\b|\\bmotivation\\b|\\bsecret\\b", Pattern.CASE_INSENSITIVE
    );

    // Priority 12: encounter
    private static final Pattern ENCOUNTER_KW = Pattern.compile(
            "\\bencounter\\b|\\binitiative\\b|\\bsurprise\\b", Pattern.CASE_INSENSITIVE
    );

    // Priority 13: story arc
    private static final Pattern STORY_ARC_KW = Pattern.compile(
            "\\bact\\b|\\bchapter\\b|\\bpart\\b", Pattern.CASE_INSENSITIVE
    );

    /**
     * Classifies the given section into a ComponentType.
     *
     * @param section the section to classify
     * @return the determined ComponentType
     */
    public ComponentType classify(SectionSplitter.Section section) {
        String heading = section.heading() != null ? section.heading() : "";
        String body = section.body() != null ? section.body() : "";
        String headingLower = heading.toLowerCase();
        String bodyLower = body.toLowerCase();

        // 1. Room number pattern → LOCATION
        if (ROOM_NUMBER_PATTERN.matcher(heading).find()) {
            return ComponentType.LOCATION;
        }

        // 2. Stat block signals
        if (headingLower.contains("stat block") || bodyLower.contains("stat block")) {
            return ComponentType.STAT_BLOCK;
        }
        if (STAT_BLOCK_AC.matcher(body).find()
                && STAT_BLOCK_HD.matcher(body).find()
                && STAT_BLOCK_HP.matcher(body).find()
                && STAT_BLOCK_AT.matcher(body).find()) {
            return ComponentType.STAT_BLOCK;
        }

        // 3. Wandering monster table
        if (WANDERING_MONSTER_KW.matcher(heading).find()
                || WANDERING_MONSTER_KW.matcher(body).find()) {
            return ComponentType.WANDERING_MONSTER_TABLE;
        }
        // Table-like structure with "die roll" and "monster"
        if (body.contains("|") && DIE_ROLL.matcher(body).find() && MONSTER_COL.matcher(body).find()) {
            return ComponentType.WANDERING_MONSTER_TABLE;
        }

        // 4. Rules variant
        if (RULES_VARIANT_KW.matcher(heading).find() || RULES_VARIANT_KW.matcher(body).find()) {
            return ComponentType.RULES_VARIANT;
        }

        // 5. Alternate world
        if (ALTERNATE_WORLD_KW.matcher(body).find()) {
            return ComponentType.ALTERNATE_WORLD;
        }

        // 6. Overview
        if (OVERVIEW_KW.matcher(heading).find()) {
            return ComponentType.OVERVIEW;
        }

        // 7. Hook
        if (HOOK_KW.matcher(heading).find() || HOOK_KW.matcher(body).find()) {
            return ComponentType.HOOK;
        }

        // 8. Trap
        if (TRAP_KW.matcher(body).find()) {
            return ComponentType.TRAP;
        }

        // 9. Puzzle
        if (PUZZLE_KW.matcher(body).find()) {
            return ComponentType.PUZZLE;
        }

        // 10. Treasure
        if (TREASURE_KW.matcher(body).find() && TREASURE_CURRENCY.matcher(body).find()) {
            return ComponentType.TREASURE;
        }

        // 11. NPC
        if (NPC_KW.matcher(body).find()) {
            return ComponentType.NPC;
        }

        // 12. Encounter
        if (ENCOUNTER_KW.matcher(body).find()) {
            return ComponentType.ENCOUNTER;
        }

        // 13. Story arc (heading-only match for act/chapter/part as narrative structure)
        if (STORY_ARC_KW.matcher(heading).find()) {
            return ComponentType.STORY_ARC;
        }

        return ComponentType.UNKNOWN;
    }
}
