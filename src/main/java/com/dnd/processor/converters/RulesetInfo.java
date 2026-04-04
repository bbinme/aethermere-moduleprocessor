package com.dnd.processor.converters;

/**
 * Identifies the game ruleset a module was written for.
 *
 * @param id        machine-readable identifier (e.g. "adnd1e", "dnd5e")
 * @param name      human-readable edition name (e.g. "AD&D 1st Edition")
 * @param publisher publisher name (e.g. "TSR, Inc.")
 */
public record RulesetInfo(String id, String name, String publisher) {

    public static RulesetInfo unknown() {
        return new RulesetInfo("unknown", "Unknown", "Unknown");
    }
}
