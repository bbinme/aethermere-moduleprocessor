# Inline Stat Block Detection — Spec

## Purpose

Detect AD&D inline stat blocks in Phase 1 extracted text and mark them with fenced code blocks
so they survive as machine-readable data in the `.md` output and are trivially consumed by Phase 2.

---

## What is an Inline Stat Block

A compact, semicolon-separated combat profile embedded directly in location or encounter body text.

```
Fewmaster Toede (Hobgoblin Lord). AL LE; MV 12; hp 22; HD 4; #AT 1; Dmg 1-6; can leap up to 30

10 Hobgoblins [Advanced Troop]. AL LE; MV 9; hp 2, 3x3, 5, 3x6, 7,9; AC 5; HD 1+1; #AT 1; Dmg 1-8
```

Structure:

```
[count] Name [(Type)] [Descriptor]. <field>; <field>; ...
```

- **count** — optional leading integer or word ("10", "A")
- **Name** — creature or NPC name; may include parenthetical type `(Hobgoblin Lord)` and/or bracket
  descriptor `[Advanced Troop]`
- **`.`** — the period after the name terminates the header and begins the field list
- **fields** — semicolon-separated; **all are optional** (only the fields relevant to the encounter
  are present)

### Known Fields

| Field  | Example values                            | Notes                               |
|--------|-------------------------------------------|-------------------------------------|
| `AL`   | `LE`, `LG`, `N`, `CE`, `NG`, `CN`, `TN`  | Alignment                           |
| `MV`   | `12`, `9`, `6"`, `12/6`                   | Movement rate                       |
| `hp`   | `22`, `2, 3x3, 5, 3x6, 7, 9`             | Single value or per-individual list |
| `AC`   | `5`, `2`                                  | Armor class                         |
| `HD`   | `4`, `1+1`, `4+2`                         | Hit dice (AD&D notation)            |
| `#AT`  | `1`, `2`                                  | Attacks per round                   |
| `Dmg`  | `1-6`, `1-8`, `1-4/1-4`                  | Damage per attack                   |
| extra  | `can leap up to 30`                       | Free-text notes after Dmg           |

No field is required. A stat block may appear with only one or two fields if that is all the
module author stated.

---

## Detection Anchor

The most reliable trigger is the period-dot that ends the name header followed by a semicolon-
separated field sequence. In practice, AD&D modules almost always lead the field list with `AL`:

```
. AL (LE|LG|LN|NE|NG|N|CE|CG|CN|TN)\b
```

Secondary trigger (when `AL` is absent): three or more of the field labels `MV`, `hp`, `AC`, `HD`,
`#AT`, `Dmg` appear separated by semicolons within a short span (≤ 200 characters).

---

## Line Joining (Pre-Detection)

PDF extraction breaks stat blocks across lines at arbitrary points. The hyphenation-merging step
in `PdfConverter` already handles mid-word breaks (`Dmg 1-\n6` → `Dmg 1-6`). Stat blocks also
break mid-field at the end of a line:

```
Fewmaster Toede (Hobgoblin Lord). AL
LE; MV 12; hp 22; HD 4; #AT 1; Dmg 1-6; can leap up to 30
```

**Rule:** while scanning for stat block boundaries, treat a line break as a space when the
preceding line ends with a known field label or alignment code fragment (i.e., the line does
not end with `.` or `?` or `!`) and the following line begins with a field value or another
field label.

Practically: after the standard hyphenation-merge pass, run a second join pass targeted at
mid-field line breaks within detected stat block spans.

---

## Where Detection Runs

**Phase 1 post-cleanup**, in `PdfConverter`, after:
1. Hyphenated line-break merging
2. Running header/footer removal

and before blank-line collapsing. This ensures that the `.md` output already contains the
fenced markers and no Phase 2 work is needed for detection.

A new method `markInlineStatBlocks(String text): String` is added to `PdfConverter`. It
operates on the full page-assembled text for a document.

---

## Markdown Output Format

Detected stat blocks are wrapped in fenced code blocks with the `stat` language hint and
**lifted onto their own lines** (separated from surrounding prose by a blank line):

```markdown
The party enters to find the room occupied.

```stat
Fewmaster Toede (Hobgoblin Lord). AL LE; MV 12; hp 22; HD 4; #AT 1; Dmg 1-6; can leap up to 30
```

He will parley if outnumbered.
```

If a stat block already occupies its own line (no preceding or following prose on the same line),
no restructuring is needed. If it is embedded mid-sentence, the surrounding sentence fragments
are preserved on their own lines.

Multi-individual stat blocks are kept on a single line (the `hp` list already encodes the
per-individual values):

```markdown
```stat
10 Hobgoblins [Advanced Troop]. AL LE; MV 9; hp 2, 3x3, 5, 3x6, 7,9; AC 5; HD 1+1; #AT 1; Dmg 1-8
```
```

---

## Phase 2 Consumption

`LocationExtractor` and `EncounterExtractor` scan section body text for ` ```stat\n...\n``` `
fences. Each fenced block is extracted verbatim and passed to a new `InlineStatBlockParser`
(see Phase 2 spec, to be written) that tokenises the semicolon fields and returns a structured
map.

The `stat_blocks` field is added to the location/encounter JSON output:

```json
{
  "component_type": "LOCATION",
  "section": "14. Dryad Forests",
  "fields": {
    "read_aloud": "...",
    "dm_notes": "...",
    "stat_blocks": [
      {
        "count": 1,
        "name": "Fewmaster Toede",
        "type": "Hobgoblin Lord",
        "al": "LE",
        "mv": "12",
        "hp": "22",
        "hd": "4",
        "at": "1",
        "dmg": "1-6",
        "notes": "can leap up to 30"
      }
    ]
  }
}
```

Fields absent from the source text are absent from the map (no nulls, no empty strings).

---

## Implementation Plan

### Phase 1 changes

| File | Change |
|------|--------|
| `PdfConverter.java` | Add `markInlineStatBlocks(String)` called during post-cleanup |

### Phase 2 changes

| File | Change |
|------|--------|
| `InlineStatBlockParser.java` *(new)* | Tokenises one stat block line into a field map |
| `LocationExtractor.java` | Extract `stat_blocks` from ` ```stat ``` ` fences in body |
| `EncounterExtractor.java` | Same |

---

## Test Cases

| Input | Expected |
|-------|----------|
| `Fewmaster Toede (Hobgoblin Lord). AL LE; MV 12; hp 22; HD 4; #AT 1; Dmg 1-6; can leap up to 30` | Wrapped; `name=Fewmaster Toede`, `type=Hobgoblin Lord`, all fields extracted |
| `10 Hobgoblins [Advanced Troop]. AL LE; MV 9; hp 2, 3x3, 5, 3x6, 7,9; AC 5; HD 1+1; #AT 1; Dmg 1-8` | `count=10`, `hp="2, 3x3, 5, 3x6, 7,9"`, `hd="1+1"` |
| Stat block split across lines mid-field | Lines joined before wrapping |
| Stat block with only `hp` and `#AT` (no `AL`) | Secondary trigger fires; wrapped and parsed |
| Normal prose with semicolons (`"First; second; third."`) | Not detected as stat block |
| Blockquote line containing a stat block | Not touched (read-aloud text is not DM stat data) |
