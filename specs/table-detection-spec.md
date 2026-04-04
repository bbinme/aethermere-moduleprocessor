# Table Detection — Spec

## Purpose

A diagnostic phase (`--phase table-detection`) that scans each PDF page for tabular
regions, highlights them in annotated images, and attempts to classify each table by
its title. Output is tunable before being wired into the main pipeline.

---

## CLI

```bash
java -jar module-processor.jar --input "B1 - In Search of the Unknown.pdf" --phase table-detection
```

Output directory: `<baseName>-tables/`, one PNG per page with at least one detected
table. Pages with no detected tables produce no output file.

---

## Core Principle

**Every detected table must be anchored to a title.**

Normal body text paragraphs do not have table titles above them. Tables in D&D modules
almost always do. Without this constraint, two-column body text will be mistaken for
implicit tables on every page of any multi-column module.

The only exception is tables detected inside a bordered box: if the box content is
organised into 3+ sub-columns, the title check may be relaxed.

---

## Distinguishing Tables from Read-Aloud Boxes / Sidebars

Bordered rectangular regions (enclosed by ink lines on all or most sides) must be
classified before any table analysis is performed.

| Signal | Classification |
|---|---|
| Enclosed by lines on all 4 sides AND interior ink forms dense flowing prose (1–2 columns, gap/row ratio ≤ `PROSE_GAP_RATIO_MAX`) | Read-aloud box or sidebar — **skip** |
| Enclosed by lines on all 4 sides AND interior has 3+ sub-columns OR gap/row ratio > `PROSE_GAP_RATIO_MAX` | Table inside a box — detect as ruled table |
| Has only horizontal rule(s), no vertical borders | Candidate ruled table — proceed to Pass 2 |

A "bordered region" requires at least one horizontal line segment across the top AND
at least one vertical line segment on each side (left and right). Bottom border is
optional — some ruled tables have a top rule but no bottom border.

---

## Detection Algorithm

Detection runs at the image level (rasterised at 150 DPI). PDFBox is used only for
title text extraction and die-roll entry scanning.

### Pass 1 — Line Segment Detection

Build two data structures from the ink map:

**Horizontal line segments**: for each row `y` in the content area, compute the
longest continuous ink run in that row using the `boolean[] ink` array (NOT the
column-aggregate vertical projection). A row is an H-line row if:
- Longest run ≥ `H_LINE_MIN_SPAN_FRACTION` (50%) of content width
- Total ink pixels in row ≥ `MIN_INK_FOR_CONTENT` (3 px)

Merge consecutive H-line rows into a single segment `(yTop, yBottom, xLeft, xRight)`.
Discard segments taller than `H_LINE_MAX_HEIGHT_PX` (4 px).

**Vertical line segments**: for each column `x` in the content area, compute the
longest continuous ink run in that column using the `boolean[] ink` array. A column
is a V-line column if:
- Longest run ≥ `V_LINE_MIN_LENGTH_PX` (40 px)
- Total ink pixels in column ≥ `MIN_INK_FOR_CONTENT` (3 px)

Merge consecutive V-line columns into a single segment `(xLeft, xRight, yTop, yBottom)`.
Discard segments wider than `V_LINE_MAX_WIDTH_PX` (4 px).

### Pass 2 — Bordered Region Classification

For each H-line segment, check whether it is part of an enclosing box:
- Look for V-line segments within `BOX_BORDER_SLOP_PX` (10 px) of the H-line's
  left and right edges, whose y-extent overlaps the H-line's y position.
- If matching V-lines exist on BOTH sides → the H-line is part of a bordered box.

For each bordered box, measure the interior content:
1. Count sub-columns inside the box using a vertical projection restricted to the
   box interior (same gutter criteria as `ProjectionAnalyzer` Pass 1).
2. Compute the gap/row ratio of interior ink clusters.
3. Apply the classification rule from the table above:
   - Sub-columns ≥ 3 OR gap/row ratio > `PROSE_GAP_RATIO_MAX` (0.25) → **table in box**
   - Otherwise → **read-aloud/sidebar, skip**

### Pass 3 — Ruled Table Detection (H-line, no enclosing box)

For each H-line segment that is NOT part of a bordered box:

1. **Title check** (required): look immediately above the H-line for a title cluster.
   - Title is the nearest ink cluster above, separated by ≤ `TITLE_GAP_MAX_PX` (30 px)
   - Title height ≤ `TITLE_MAX_HEIGHT_PX` (40 px)
   - If no title found → skip this H-line (likely a decorative divider)

2. **Table body check**: analyse the horizontal projection below the H-line.
   - Find ink clusters and inter-cluster gaps
   - Gap count ≥ `MIN_TABLE_ROWS` (3)
   - Gap/row ratio ≥ `MIN_GAP_TO_ROW_RATIO` (0.20)
   - Gap stddev/mean ≤ `ROW_REGULARITY_THRESHOLD` (0.60)

3. If both checks pass → **ruled table** (high confidence, solid orange border).

### Pass 4 — Title-Anchored Implicit Table Detection

For each title candidate on the page not already covered by a detected table:

A title candidate is a short isolated ink cluster:
- Height ≤ `TITLE_MAX_HEIGHT_PX` (40 px)
- Preceded by a blank gap ≥ `TITLE_LEAD_GAP_PX` (8 px) above it
- Not inside any already-detected bordered box

Examine the content block immediately below the title candidate (up to the next
blank gap ≥ `TABLE_END_GAP_PX` or the content bottom):

**Sub-case A — Column-aligned implicit table:**
- The block has at least one vertical gutter (column separator) when the vertical
  projection is restricted to the block's y-range and the SINGLE column zone
  containing the title (do NOT look for gutters across the full page width —
  that fires on every two-column page)
- Gap/row ratio ≥ `MIN_GAP_TO_ROW_RATIO` (0.20)
- Gap count ≥ `MIN_TABLE_ROWS` (3)
- → **column-implicit table** (lower confidence, dashed border)

**Sub-case B — Die-roll / numbered list table:**
- Extract text from the left strip (`DIE_TABLE_NUMBER_STRIP_FRACTION` = 15% of
  the block width) using `PDFTextStripperByArea`
- Count consecutive integer tokens starting at 1 (`^\d{1,2}[.)F]?\s`)
- Count ≥ `MIN_DIE_TABLE_ENTRIES` (6) → **die-roll table** (lower confidence, dashed border)

Both sub-cases require the title. No title = no implicit detection for that block.

### Pass 5 — Title Text Extraction

For every detected table region (ruled, column-implicit, or die-roll):
- If title bounds were located in Pass 3 or 4, extract text using
  `PDFTextStripperByArea` with the title pixel bounds converted to PDF points.
- For die-roll tables the title was already located; only text extraction is needed.

### Pass 6 — Classification by Title

Keyword matching (case-insensitive) on the extracted title text:

| Keywords | Classification |
|---|---|
| "wandering", "random encounter" | `WANDERING_MONSTER_TABLE` |
| "encounter" + "table" | `ENCOUNTER_TABLE` |
| "rumor", "rumour", "legend", "lore" | `RUMOR_TABLE` |
| "weapon", "damage" | `WEAPON_TABLE` |
| "armor", "armour" | `ARMOR_TABLE` |
| "spell" | `SPELL_TABLE` |
| "magic item" | `MAGIC_ITEM_TABLE` |
| "equipment", "pack", "gear" | `EQUIPMENT_TABLE` |
| "saving throw", "save" + "table" | `SAVING_THROW_TABLE` |
| "treasure" | `TREASURE_TABLE` |
| "experience", " xp ", starts with "xp " | `EXPERIENCE_TABLE` |
| "ability", "pregen", "pre-gen", "strength", "prerolled" | `ABILITY_SCORE_TABLE` |
| "henchm", "hireling", "retainer" | `HENCHMAN_TABLE` |
| "personality", "morale", "loyalty" | `NPC_TRAIT_TABLE` |
| "character class" | `CHARACTER_CLASS_TABLE` |
| "non-player character", "npc" + "availability" | `NPC_AVAILABILITY_TABLE` |
| (title found, no keyword matched) | `GENERIC_TABLE` |
| (no title) | `UNKNOWN_TABLE` |

---

## Output Images

One PNG per page written to `<baseName>-tables/`. Annotation colours:

| Colour | Meaning |
|---|---|
| Orange solid border | Ruled table (high confidence) |
| Orange dashed border | Column-implicit or die-roll table (lower confidence) |
| Cyan | Detected H-rule line |
| Magenta | Detected V-line segments (for box classification) |
| Blue | Detected title region |

The table classification label is drawn in white text on an orange background bar at
the top of the detected region.

---

## Tuning Constants

| Constant | Default | Meaning |
|---|---|---|
| `H_LINE_MIN_SPAN_FRACTION` | 0.50 | Min fraction of content width for an H-line |
| `H_LINE_MAX_HEIGHT_PX` | 4 | Max thickness of a merged H-line segment |
| `V_LINE_MIN_LENGTH_PX` | 40 | Min height of a V-line segment |
| `V_LINE_MAX_WIDTH_PX` | 4 | Max thickness of a merged V-line segment |
| `BOX_BORDER_SLOP_PX` | 10 | Tolerance when matching V-lines to H-line edges |
| `PROSE_GAP_RATIO_MAX` | 0.25 | Max gap/row ratio to classify box content as prose |
| `MIN_TABLE_ROWS` | 3 | Min inter-cluster gap count to qualify as a table body |
| `ROW_REGULARITY_THRESHOLD` | 0.60 | Max stddev/mean ratio of row gaps |
| `MIN_GAP_TO_ROW_RATIO` | 0.20 | Min mean-gap / mean-row-height (filters body text) |
| `TABLE_END_GAP_PX` | 20 | Gap height that ends a table body scan |
| `TITLE_GAP_MAX_PX` | 30 | Max gap between title cluster and table top |
| `TITLE_LEAD_GAP_PX` | 8 | Min blank gap above a title candidate |
| `TITLE_MAX_HEIGHT_PX` | 40 | Max height of a title cluster |
| `MIN_DIE_TABLE_ENTRIES` | 6 | Min integer entries to qualify as a die-roll table |
| `DIE_TABLE_NUMBER_STRIP_FRACTION` | 0.15 | Left-strip width for die-roll entry scanning |

---

## Java Structure

```
converters/
  TableDetector.java       — Passes 1–4: line detection, box classification,
                             ruled and implicit table detection
                             Returns List<TableRegion>
  TableRegion.java         — record: xLeft, xRight, yTop, yBottom,
                             ruleYTop, ruleYBottom, titleYTop, titleYBottom,
                             TableStyle (RULE_BASED / COLUMN_IMPLICIT / DIE_ROLL),
                             TableType, title String
  TableClassifier.java     — Passes 5–6: title extraction + keyword classification
  TableType.java           — enum of all table types
```

`PDFPreprocessor.detectTables(Path, Path, float)` orchestrates per-page detection,
classification, and annotated image writing.

---

## Known Limitations

- Tables with **no title and no drawn rule** cannot be detected (e.g. an unlabelled
  list of items). These are rare in published D&D modules.
- **Multi-page tables** are not detected as a unit.
- **Die-roll sub-tables** nested within a larger table (e.g. d6 sub-table inside a d20
  table) may produce overlapping detections. Overlap suppression keeps the larger region.
- Title extraction relies on PDFBox coordinate mapping from raster pixel to PDF point
  space; accuracy may degrade on heavily warped scans.
- Tables inside boxes are detected when sub-column count ≥ 3. A two-column table
  inside a box may be missed if its gap/row ratio is below `PROSE_GAP_RATIO_MAX`.
  Adjust the constant if this is observed.
