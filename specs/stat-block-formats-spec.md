# Stat Block Formats — Spec

## Purpose

Detect all distinct AD&D stat block formats embedded in module text during Phase 1 (PDF → MD)
and mark them with fenced code blocks so Phase 2 can parse them into structured fields. This
spec covers all formats found across the DL1–DL16 corpus.

The marked `.md` files are human-readable and machine-parseable. Phase 2 extracts
`stat_blocks` from `\`\`\`stat` fences in location/encounter/NPC sections.

---

## Formats Overview

| ID | Name | Primary files | Detection anchor |
|----|------|---------------|-----------------|
| F1 | Inline Semicolon | All modules | `. AL XX;` or ≥3 semicolon fields |
| F2 | Labeled Block | DL10, DL13, DL14, DL15 | `FREQUENCY` label + multi-line field list |
| F3 | Narrative Character | DL10, DL13 | Ability score pairs + `THAC0` |
| F4 | NPC Capsule | DL15, DL16 | `NPC Capsule` section header |
| F5 | Compact Card | DL10 | All-caps compressed ability lines |
| F6 | Army Unit | DL11 | `N Name (Age/Size) hp` — battle system |

Formats F5 and F6 are low-value for training data (character cards and mass-combat units).
They are detected and fenced but not deeply parsed in Phase 2.

---

## Format F1 — Inline Semicolon

### Description

A compact, semicolon-separated profile embedded inline in location or encounter body text.
The creature name (with optional count, type, and descriptor) is followed by a period, then
a sequence of semicolon-delimited field=value pairs. **All fields are optional** — only the
fields the module author chose to state appear.

### Examples

```
Fewmaster Toede (Hobgoblin Lord). AL LE; MV 12"; hp 22; HD 4; #AT 1; Dmg 1-6; can leap up to 30'

10 Hobgoblins [Advanced Troop]. AL LE; MV 9"; hp 2, 3x3, 5, 3x6, 7,9; AC 5; HD 1+1; #AT 1; Dmg 1-8

8 Baaz Draconians. AL LE; MV 6"[15"]18"; hp 3,6,2x9,10,2x12,16; AC 4; HD 2; #AT 1 or 2; Dmg 1-8 or 1-4/1-4; turn to stone and crumble apart on death

Otik Sandath (Innkeeper). AL LN; MV 12"; AC 10; HD 1; hp 6; #AT 1; Dmg 1-4

Bennybeck Cloudberry, 2d-level kender thief: AC 8; MV 9"; hp 10; #AT 1; Dmg 1d6 (pick axe); THAC0 20; S10, I10, W9, D14, Cn14, Ch11; AL N
```

### Structure

```
[count] Name [(Type)] [/[Descriptor]]. <field>; <field>; ...
                                       ^
                                    period ends name; fields begin
```

Variant: name followed by comma + class/level + colon instead of period:
```
Bennybeck Cloudberry, 2d-level kender thief: AC 8; MV 9"; ...
```

### Fields (all optional)

| Label | Example | Notes |
|-------|---------|-------|
| `AL` | `LE`, `N`, `CG` | Alignment |
| `MV` | `12"`, `6"[15"]18"` | Movement; brackets = fly/swim alternate |
| `hp` | `22`, `2, 3x3, 5` | Single value or per-individual list (`NxM` = N creatures with M hp) |
| `AC` | `5`, `10` | Armor class |
| `HD` | `4`, `1+1`, `4+2` | Hit dice; AD&D notation |
| `#AT` | `1`, `1 or 2` | Attacks per round |
| `Dmg` | `1-6`, `1-4/1-4` | Damage; slash = multiple attacks |
| `THAC0` | `20` | To hit AC 0 (appears in later modules) |
| `S/I/W/D/Cn/Ch` | `S10, I10, W9` | Abbreviated ability scores (late modules) |
| extra | `can leap up to 30'` | Free-text notes after last field |

### Detection Anchor

**Primary:** period (or comma+level+colon) followed within 40 chars by `. AL (LE|LG|LN|NE|NG|N|CE|CG|CN|TN)\b`

**Secondary (no AL field):** three or more of the labels `MV`, `hp`, `AC`, `HD`, `#AT`, `Dmg`
appearing semicolon-separated within a 250-character span.

### Line Joining

PDF extraction wraps these across lines at arbitrary points. After the standard
hyphenation-merge pass, apply a **stat-block continuation join**: while scanning and an
in-progress F1 block ends a line without `.` or `?` or `!` (i.e. the line ends with a field
value or label fragment), append the next line with a space before continuing detection.

---

## Format F2 — Labeled Block

### Description

The TSR standard multi-line monster stat block format. Each field is on its own line with an
uppercase label. This is the most common format in appendices and new-monster descriptions.
**This format causes the section-fragmentation bug** because field lines look like Markdown
headings after PDF extraction adds `##` prefixes.

### Examples

```
dreamwraith
FREQUENCY: Very Rare
# APPEARING: 1-400
ARMOR CLASS: 3
MOVE: Variable
HIT DICE: 8
% IN LAIR: 100%
TREASURE TYPE: Nil
# ATTACKS: 1
DAMAGE: 1-10 (Illusionary)
SPECIAL ATTACKS: Illusionary weapons
SPECIAL DEFENSES: Nil
MAGIC RESISTANCE: By dream level: normal/10%/20%
INTELLIGENCE: Of Dreamer
ALIGNMENT: CE
SIZE: Variable
XP VALUE: 1,275 + 10/hp
```

```
AURAK
FREQUENCY: Rare
# APPEARING: 1-2
ARMOR CLASS: 0
MOVE: 15"
HIT DICE: 8
% IN LAIR: 10%
TREASURE TYPE: K, L, N, V
# ATTACKS: 2 or 1
DAMAGE: 1d8+2 (x2) or spell
SPECIAL ATTACKS: Spells & Breath
SPECIAL DEFENSES: Save at +4
MAGIC RESISTANCE: 30%
INTELLIGENCE: Exceptional
ALIGNMENT: Lawful Evil
SIZE: M (7 ft.)
PSIONIC ABILITY: Nil
XP VALUE: 1,800 + 10/hp
```

### Structure

```
<CreatureName>               ← line before first labeled field; may be all-caps
LABEL: value                 ← one or more labeled lines
LABEL: value
...
```

The block ends at the first blank line or at a line that does not match `LABEL: value` and is
not a continuation of the previous field.

### Fields

Standard TSR set (all optional):

`FREQUENCY`, `# APPEARING`, `ARMOR CLASS`, `MOVE`, `HIT DICE`, `% IN LAIR`,
`TREASURE TYPE`, `# ATTACKS`, `DAMAGE`, `SPECIAL ATTACKS`, `SPECIAL DEFENSES`,
`MAGIC RESISTANCE`, `INTELLIGENCE`, `ALIGNMENT`, `SIZE`, `PSIONIC ABILITY`, `XP VALUE`

Extended description blocks (Format F2 variant): the labeled fields are followed by hundreds
of words of narrative. The entire block (labels + narrative) is wrapped in a single fence.

### Detection Anchor

A run of **3 or more consecutive lines** where each line matches `[A-Z# %]+:\s+\S` (uppercase
label with colon and a non-blank value). The creature name is the nearest non-blank line above
the first labeled field that does not itself match the label pattern.

### Notes on Section Fragmentation

Field lines extracted by PDFBox often get `##` Markdown heading prefixes (e.g. `## ARMOR CLASS: 3`)
because font-size detection misclassifies the bold label font. Detection must strip leading `#`
characters from lines before testing the label pattern, and the fenced output should use the
clean label text (no `##` prefix).

---

## Format F3 — Narrative Character Block

### Description

Named character stat blocks with full ability scores listed as word-labeled pairs, followed by
THAC0, hit points, AC, movement, and a prose equipment/notes line. Used for key NPCs and
pre-generated characters in adventure appendices.

### Examples

```
Lorac Caladon, King of Silvanesti
15th-Level Fighter / 3rd Level Magic-User
Strength 13   Dexterity 14
Intelligence 12   Constitution 7
Wisdom 7   Charisma 12
THAC0 6   Hit Points 18
Armor Class 6   Movement 12"
Wears Leather Armor +2, carries Longsword +3.
```

```
Loralon, Conscience of the King
12th Level Cleric
Strength 11   Dexterity 14
Intelligence 12   Constitution 10
Wisdom 16   Charisma 12
THAC0 14   Hit Points 39
Armor Class 4   Movement 12"
Wears Chain mail +3, carries a Mace +2
```

### Structure

```
<Name>[, Title]            ← line 1: character name and optional title
<Level> <Class>            ← line 2: class/level description
Strength N   Dexterity N   ← ability score pairs, 2-3 per line
...
THAC0 N   Hit Points N     ← combat stats
Armor Class N   Movement N
<equipment prose>          ← optional
```

### Detection Anchor

A block where:
1. A non-heading line contains a level + class description (e.g. `12th Level Cleric`,
   `15th-Level Fighter`)
2. Followed within 6 lines by two or more of: `Strength`, `Intelligence`, `Wisdom`,
   `Dexterity`, `Constitution`, `Charisma`
3. Followed within 3 lines by `THAC0` and `Hit Points`

The character name is the nearest non-blank line above the level/class line.

---

## Format F4 — NPC Capsule

### Description

A structured NPC profile introduced by an `NPC Capsule` section label (DL15/DL16 format).
Ability scores are word-labeled with colons, followed by combat stats and equipment in prose.
Sometimes mixed with Format F1 inline semicolons within the same block.

### Examples

```
NPC Capsule

Chot es-Kalin
King of Mithas

Strength: 18/99
Intelligence: 9
Wisdom: 10
Dexterity: 12
Constitution: 16
Charisma: 8

Hit Dice: 10
hit points: 80
Armor Class: 4 (chain mail +1)
THAC0: 8
# of Attacks: 2 or 1
Weapons: Horns (2d4) and Bite (1d4) or Battle Axe 1d10 +4 (x2)
```

```
Lord Myca (minotaur): hp 50; Dmg 2d4 or 1d4/2d6 (extremely huge axe); all other statistics as for minotaur soldier.
```

### Structure

Section header `NPC Capsule` (case-insensitive) followed by name, optional title, ability
score lines (`Label: value`), and combat stat lines. The entire section up to the next blank
line or heading is one block.

### Detection Anchor

The text `NPC Capsule` (case-insensitive) on its own line. Everything until the next `---`
separator or heading is part of the block.

---

## Format F5 — Compact Character Card (low priority)

### Description

All-caps compressed character cards used in DL10. Two lines of ability scores + AL + HP,
followed by AC, WEAPONS, EQUIPMENT, LANGUAGES.

### Example

```
TANIS 8TH LEVEL HALF-ELF FIGHTER
STR 16  WIS 13  CON 12  THAC0 14
INT 12  DEX 16  CHR 15  AL NG  HP 61
AC 4 (LEATHER ARMOR +2, DEX BONUS)
WEAPONS LONGSWORD +2 (1-8/1-12; THAC0 12)
LONGBOW, QUIVER WITH 20 ARROWS (1-6/1-6)
EQUIPMENT AS SELECTED BY PLAYER
LANGUAGES COMMON, QUALINESTI ELF
```

### Detection Anchor

An all-caps line matching `\b(STR|INT)\s+\d+\b.*\b(WIS|DEX)\s+\d+\b.*\b(CON|CHR)\s+\d+\b`
(three abbreviations on a single line). The character name and class is the preceding all-caps
line.

### Priority: Low

Detected and fenced for completeness. Phase 2 parsing is minimal (preserve as raw text with
name extracted). These are player character cards, not encounter-level DM data.

---

## Format F6 — Army Unit (low priority)

### Description

Minimal battle-system format from DL11. Used for mass-combat unit rosters, not individual
encounters.

### Example

```
1 Red Dragon (Ancient/Large) 80 hp
2 Red Dragons (Old/Average) 60 hp each
4 Green Dragons (Very Old/Average) 56 hp
```

### Detection Anchor

A run of 2+ lines each matching `^\d+\s+[A-Z][a-z].*\([A-Za-z/]+\)\s+\d+\s+hp\b`.

### Priority: Low

Fenced as `stat` blocks. Phase 2 extracts count, name, age/size category, hp. No further
conversion planned.

---

## Phase 1 Detection — `markStatBlocks(String text)`

A new method in `PdfConverter` runs all six detectors in one pass over the fully assembled
page text (after hyphenation merging, before blank-line collapsing).

**Detection order (within the same text span):**
1. F4 NPC Capsule (section-header anchor — greedily consumes everything under the header)
2. F2 Labeled Block (3+ consecutive labeled lines)
3. F3 Narrative Character (level+class + ability score block)
4. F5 Compact Card (all-caps STR/INT line)
5. F1 Inline Semicolon (period + AL or ≥3 semicolon fields)
6. F6 Army Unit (dragon age/hp list)

Each detected block is wrapped in a fenced code block and lifted to its own lines:

```markdown
> A party of hobgoblins guards the gate.

```stat
10 Hobgoblins [Advanced Troop]. AL LE; MV 9"; hp 2, 3x3, 5, 3x6, 7,9; AC 5; HD 1+1; #AT 1; Dmg 1-8
```

They will not negotiate.
```

Blockquote lines (read-aloud text, starting with `>`) are never scanned for stat blocks.

---

## Phase 2 Parsing — `InlineStatBlockParser`

New class. Accepts a single `stat` fence's content and returns a `Map<String, Object>`.

**Dispatch on format:**
- If content contains 3+ consecutive `LABEL: value` lines → parse as F2
- If content contains `NPC Capsule` → parse as F4
- If content contains `Strength:` or `THAC0` + `Hit Points` word → parse as F3
- If content matches all-caps STR/INT pattern → parse as F5 (raw text + name)
- If content matches `N Name (Age/Size) hp` → parse as F6 (raw text + count/name/hp)
- Otherwise → parse as F1

### F1 Parsed Output

```json
{
  "format": "inline_semicolon",
  "count": 10,
  "name": "Hobgoblins",
  "descriptor": "Advanced Troop",
  "al": "LE",
  "mv": "9\"",
  "hp": "2, 3x3, 5, 3x6, 7,9",
  "ac": "5",
  "hd": "1+1",
  "at": "1",
  "dmg": "1-8"
}
```

### F2 Parsed Output

```json
{
  "format": "labeled_block",
  "name": "Aurak",
  "frequency": "Rare",
  "appearing": "1-2",
  "ac": "0",
  "move": "15\"",
  "hd": "8",
  "in_lair": "10%",
  "treasure_type": "K, L, N, V",
  "attacks": "2 or 1",
  "damage": "1d8+2 (x2) or spell",
  "special_attacks": "Spells & Breath",
  "special_defenses": "Save at +4",
  "magic_resistance": "30%",
  "intelligence": "Exceptional",
  "alignment": "Lawful Evil",
  "size": "M (7 ft.)",
  "psionic_ability": "Nil",
  "xp": "1,800 + 10/hp"
}
```

### F3 Parsed Output

```json
{
  "format": "narrative_character",
  "name": "Lorac Caladon",
  "title": "King of Silvanesti",
  "class_level": "15th-Level Fighter / 3rd Level Magic-User",
  "str": "13", "dex": "14", "int": "12", "con": "7", "wis": "7", "cha": "12",
  "thac0": "6",
  "hp": "18",
  "ac": "6",
  "mv": "12\"",
  "equipment": "Wears Leather Armor +2, carries Longsword +3."
}
```

All absent fields are omitted from the map.

---

## JSON Output

`stat_blocks` is added to the `fields` map of any component type (LOCATION, ENCOUNTER, NPC,
STAT_BLOCK):

```json
{
  "component_type": "LOCATION",
  "section": "14. Dryad Forests",
  "fields": {
    "read_aloud": "...",
    "dm_notes": "...",
    "stat_blocks": [
      {
        "format": "inline_semicolon",
        "name": "Fewmaster Toede",
        "type": "Hobgoblin Lord",
        "al": "LE",
        "mv": "12\"",
        "hp": "22",
        "hd": "4",
        "at": "1",
        "dmg": "1-6",
        "notes": "can leap up to 30'"
      }
    ]
  }
}
```

---

## Implementation Plan

| File | Change |
|------|--------|
| `PdfConverter.java` | Add `markStatBlocks(String)` — orchestrates all format detectors |
| `StatBlockDetector.java` *(new)* | Per-format detection + fence insertion logic |
| `InlineStatBlockParser.java` *(new)* | Dispatching parser; one method per format |
| `LocationExtractor.java` | Extract `stat_blocks` from `\`\`\`stat` fences |
| `EncounterExtractor.java` | Same |
| `NpcExtractor.java` | Same |

---

## Test Cases

| Input | Expected format | Key parsed fields |
|-------|----------------|-------------------|
| `Fewmaster Toede (Hobgoblin Lord). AL LE; MV 12"; hp 22; HD 4; #AT 1; Dmg 1-6` | F1 | name, type, all fields |
| `10 Hobgoblins [Advanced Troop]. AL LE; MV 9"; hp 2, 3x3, 5, 3x6, 7,9; AC 5; HD 1+1; #AT 1; Dmg 1-8` | F1 | count=10, hp list |
| `dreamwraith\nFREQUENCY: Very Rare\nARMOR CLASS: 3\nMOVE: Variable\n...` | F2 | all labeled fields |
| `Lorac Caladon, King of Silvanesti\n15th-Level Fighter\nStrength 13 Dexterity 14\n...THAC0 6` | F3 | name, title, ability scores, thac0 |
| `NPC Capsule\nChot es-Kalin\nKing of Mithas\nStrength: 18/99\n...` | F4 | name, title, all labeled fields |
| `TANIS 8TH LEVEL HALF-ELF FIGHTER\nSTR 16 WIS 13 CON 12 THAC0 14\n...` | F5 | raw text + name |
| `2 Red Dragons (Old/Average) 60 hp each` | F6 | count=2, name, hp=60 |
| F2 block where field lines have `##` prefix from PDF extraction | F2 (## stripped) | same as clean input |
| Blockquote line containing stat-like text | Not detected | — |
