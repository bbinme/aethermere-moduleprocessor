package com.dnd.processor.converters;

/** Classification of a detected table based on its title text. */
public enum TableType {
    WANDERING_MONSTER_TABLE,
    ENCOUNTER_TABLE,
    RUMOR_TABLE,
    WEAPON_TABLE,
    ARMOR_TABLE,
    SPELL_TABLE,
    MAGIC_ITEM_TABLE,
    EQUIPMENT_TABLE,
    SAVING_THROW_TABLE,
    TREASURE_TABLE,
    EXPERIENCE_TABLE,
    ABILITY_SCORE_TABLE,
    HENCHMAN_TABLE,
    NPC_TRAIT_TABLE,
    CHARACTER_CLASS_TABLE,
    GENERIC_TABLE,   // title found but no keyword matched
    UNKNOWN_TABLE    // no title found or classification ambiguous
}
