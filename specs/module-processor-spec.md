# Module Processor — Spec

## Purpose

A Java pipeline that:
1. Converts D&D module files (PDF, DOCX, TXT) into clean Markdown
2. Parses that Markdown into structured, labeled module components
3. Outputs JSONL training data in the format expected by the fine-tuning pipeline

---

## Phase 1: File → Markdown

### Supported Input Formats

| Format | Library         |
|--------|-----------------|
| PDF    | Apache PDFBox   |
| DOCX   | Apache POI      |
| TXT    | Plain reader    |
| MD     | Pass-through    |

### PDF Column Layout Strategy

Many published modules use mixed layouts within a single page — a full-width title at the top, then 2-column body text below, sometimes with a narrow sidebar floating alongside one column. PDFBox's default extractor reads left-to-right across the full page width, scrambling text across columns. We use a **custom column-aware TextStripper** that detects layout per horizontal band and extracts each zone independently.

#### How it works

1. **Pass 1 — Capture all characters** with x/y coordinates via an inner `PDFTextStripper` subclass. Every individual `TextPosition` character is recorded (not just the first character per text run) to produce an accurate density map.

2. **Detect dominant line height** from the captured characters (median of `heightDir` values across all text runs on the page).

3. **Build per-band x histograms** — bucket characters into horizontal bands of ~1× line height. For each band, build an x-density histogram and scan for gaps.

4. **Classify the page** using `findAllGaps` on the full-page x histogram:
   - No gaps → `SINGLE`
   - Two or more qualifying gaps where all three resulting columns are ≥ 20% of page width,
     widest-to-narrowest ratio ≤ 2.5, and col3 text reaches ≥ 78% of page width → `THREE_COL`
     (gap1 = x of col1/col2 gutter, gap2 = x of col2/col3 gutter)
   - Otherwise one main gap → `TWO_COL`; sidebar detection (Stage 2 + 3) clips the right and/or
     left column at a smoothed-histogram valley if a sidebar box is present
   - For `THREE_COL` pages with a full-width header (detected via characters in the gutter regions):
     split into a `SINGLE` zone for the header + `THREE_COL` zone for the body

5. **Merge consecutive bands** with the same layout and gap position(s) into **zones**.

6. **Pass 2 — Extract each zone** via `PDFTextStripperByArea` using a rectangle matching the zone's y-range and each column's x-range. Columns within a zone are extracted left→right and concatenated.

7. For `SIDEBAR_PLUS_COL` zones, sidebar text is appended after the main column text for that zone, wrapped in a blockquote (`> `) so it is visually distinct in the Markdown output.

```
Example: mixed-layout page split into zones

┌─────────────────────────────────┐
│  Zone 1: SINGLE                 │  y: 0–80     full-width title/header
├─────────────────────────────────┤
│  Zone 2: TWO_COL                │  y: 80–600   2-col body text
│  Left col    │ gap │  Right col │
├──────────────┴─────┴────────────┤
│  Zone 3: SIDEBAR_PLUS_COL       │  y: 600–750  sidebar + main col
│  Sidebar  │        Main col     │
└─────────────────────────────────┘
Each zone extracted independently via PDFTextStripperByArea
```

#### Gap detection rules (per band)

- Scan the center 60% of the page width (ignore outer 20% each side)
- A gap requires ≥ 20px of consecutive zero-density bins
- If multiple gaps exist in a band, choose the one(s) closest to dividing the page evenly
- Bands with fewer than 5 characters are skipped (images, decorative elements)

#### Zone merging rules

- Consecutive bands with the same layout type and gap position(s) within ±10px are merged into one zone
- A layout change of any kind (e.g., SINGLE → TWO_COL) starts a new zone

#### Region overlap padding

When defining `PDFTextStripperByArea` rectangles, each column region is padded by ±5px at internal boundaries to prevent characters at the exact gap edge from being dropped.

#### Post-extraction cleanup

- Merge hyphenated line-breaks (e.g., `adven-\nturers` → `adventurers`)
- Collapse 3+ consecutive blank lines to 2
- Strip page numbers (lines matching `^\d+$`)
- Strip running headers/footers: lines that appear identically on 3 or more pages are treated as headers/footers and removed
- Collapse consecutive duplicate lines (e.g., a running header followed immediately by the same section heading)

#### Read-aloud (boxed text) detection

During character capture (`PositionCapturingStripper`), PDF path operators are intercepted to detect drawn box outlines:

- **`re` operator**: appends a rectangle to the pending path.
- **`S`, `B`, `b`, `s`, `f`, `F`, `f*`, `B*` operators**: paint the path. Each pending rectangle is classified:
  - w ≥ 30pt and h ≥ 30pt → **direct box** (filled/stroked rectangle large enough to be a content box)
  - h ≤ 3pt and w ≥ 40pt → **horizontal border piece** (a thin line used as a box edge)
  - w ≤ 3pt and h ≥ 40pt → **vertical border piece**
- **`reconstructBoxesFromBorders()`**: pairs horizontal border pieces (top + bottom) using nearest-match to reconstruct boxes whose outlines were drawn as four separate strokes rather than a single filled rectangle. For each top piece, finds the closest qualifying bottom piece with matching x and width (within ±8pt), box height ≥ 30pt.

Detected boxes are stored in `pageBoxes` and used during column text extraction (`extractColumnText`). When a text segment overlaps a box rectangle, `appendBoxSegment` is called:

- Lines starting with a **lowercase letter** are emitted as plain text (sentence continuations that wrapped from above the box boundary).
- Lines matching `^\d+[A-Za-z]?\.\s+[A-Z].*` (encounter/room headings, e.g. "1. Solace Township") are emitted as plain text (the heading appears visually before the box).
- The **first uppercase non-heading line** and everything after it is emitted with `> ` prefix (blockquote), making it recognizable as `read_aloud` in Phase 2.

**Known limitation**: some modules (e.g. DL1 *Dragons of Despair*) use read-aloud sections with no drawn box graphics in the PDF content stream. These sections are not detectable by the path-operator approach. The PDF annotations layer (`PDPage.getAnnotations()`) is a possible alternative that has not yet been investigated.

### Extraction Goals

- Preserve heading hierarchy (H1 → H3) via font-size detection
- Preserve lists (bulleted and numbered)
- Preserve tables (convert to Markdown table syntax)
- Detect and label **read-aloud (boxed) text** — via PDF path operator interception; boxes drawn as stroked/filled rectangles or as four separate border lines are both detected. See "Read-aloud detection" above.

### Output

A single `.md` file alongside the source file, e.g. `curse-of-strahd.pdf` → `curse-of-strahd.md`

---

## Phase 2: Markdown → Module Components

### Component Types

| Type                    | Key Signals                                                                      |
|-------------------------|----------------------------------------------------------------------------------|
| `overview`              | First section(s), keywords: "background", "synopsis", "overview", "preface"     |
| `hook`                  | Keywords: "adventure hook", "hook", "getting the party involved"                 |
| `location`              | Numbered room pattern: `^\d+\.?\s+[A-Z\s]+` or "Area X", "Room X", place names  |
| `read_aloud`            | Blockquote (`>`) or italicized block inside a location                           |
| `encounter`             | Keywords: "encounter", "combat", "initiative", CR references                     |
| `npc`                   | Keywords: "personality", "motivation", "secret", inline stat block               |
| `stat_block`            | Pattern: `AC \d`, `HD \d`, `#AT \d`, `hp \d` — standalone monster entry         |
| `trap`                  | Keywords: "trap", "trigger", "effect", "disable device", "glyph of warding"     |
| `puzzle`                | Keywords: "puzzle", "riddle", "solution"                                         |
| `treasure`              | Keywords: "treasure", "loot", "reward", "contains", "gp", magic item names      |
| `story_arc`             | Keywords: "act", "chapter", "part", narrative milestone markers                  |
| `rules_variant`         | Keywords: "spell alteration", "magic item alteration", plane/setting rule mods   |
| `wandering_monster_table` | Table structure with die roll column + monster column + number appearing column |
| `alternate_world`       | Keywords: "gate", "doorway", "world", "prime material plane", portal description |

### Parsing Strategy

1. **Section splitter** — split the Markdown into sections by heading level (H1/H2/H3)
2. **Classifier** — assign a component type to each section using keyword + structural pattern matching
3. **Sub-extractor** — within each section, extract sub-fields:
   - Locations: split read-aloud text from DM notes
   - NPCs: extract name, personality, motivation, secrets
   - Encounters: extract enemy list, tactics, terrain notes, difficulty
   - Stat blocks: extract all stat fields into structured form
4. **Formatter** — serialize each component into JSONL training format

### Output Format

One pretty-printed JSON file per module, with lines wrapped at 120 characters for human readability. Each component is an object in a top-level array:

```json
[
  {
    "source": "curse-of-strahd.pdf",
    "component_type": "location",
    "section": "K20. Heart of Sorrow",
    "fields": {
      "read_aloud": "A crystal heart the size of a man floats...",
      "dm_notes": "The heart has AC 10, 120 HP. Each time Strahd takes damage...",
      "encounters": ["4 shadows", "Strahd arrives if heart destroyed"]
    }
  },
  {
    "source": "curse-of-strahd.pdf",
    "component_type": "npc",
    "section": "Strahd von Zarovich",
    "fields": {
      "motivation": "eternal companionship",
      "personality": "cold, imperious, obsessive",
      "secrets": ["controls the mists", "bound to Barovia by the Dark Powers"]
    }
  }
]
```

Converting to JSONL later is trivial — each array element becomes one line.

---

## Java Project Structure

```
ModuleProcessor/
  src/main/java/com/dnd/processor/
    Main.java                        # CLI entry point
    pipeline/
      FileToMarkdownConverter.java   # Phase 1 orchestrator
      MarkdownToComponentParser.java # Phase 2 orchestrator
    converters/
      PdfConverter.java              # PDFBox orchestrator — page loop, post-cleanup
      ColumnAwareTextStripper.java   # Custom PDFBox stripper: page-level gap detection (SINGLE/TWO_COL/THREE_COL), per-character histogram, column-level PDFTextStripperByArea extraction, sidebar Y-bounding
      DocxConverter.java             # POI-based extraction
      TxtConverter.java              # Plain text pass-through
    parsers/
      SectionSplitter.java           # Splits MD by heading
      ComponentClassifier.java       # Assigns component type
      LocationExtractor.java         # Extracts location fields
      NpcExtractor.java              # Extracts NPC fields
      EncounterExtractor.java        # Extracts encounter fields
      StatBlockExtractor.java        # Extracts stat block fields
    model/
      ModuleComponent.java           # Component data class
      ComponentType.java             # Enum of component types
    output/
      JsonlWriter.java               # Writes JSONL output
  src/test/java/...                  # Unit tests per extractor
  specs/
  build.gradle
```

---

## CLI Interface

```bash
# Convert PDF to Markdown only
java -jar module-processor.jar --input curse-of-strahd.pdf --phase convert

# Parse existing Markdown into components
java -jar module-processor.jar --input curse-of-strahd.md --phase parse

# Full pipeline
java -jar module-processor.jar --input curse-of-strahd.pdf --phase all

# Output directory
java -jar module-processor.jar --input curse-of-strahd.pdf --phase all --output ./training-data/

# Extract sidebar text only (for review/verification)
java -jar module-processor.jar --input curse-of-strahd.pdf --phase sidebars
```

---

## Build

- Java 21
- Gradle
- Dependencies: `pdfbox`, `poi-ooxml`, `jackson-databind`, `picocli` (CLI parsing)

---

## Open Questions

1. **Read-aloud detection in PDFs** — Implemented via PDF path operator interception (see "Read-aloud detection" above). Works for modules with drawn box borders (stroked or filled). Known gap: DL1 *Dragons of Despair* encounter read-aloud sections have no drawn graphics; their visual box appearance is unexplained at the content-stream level. The PDF annotations layer (`PDPage.getAnnotations()`) has not been checked. Consider flagging pages with zero detected boxes as `needs_review` if the module is known to have read-aloud text.
2. ~~**Multi-column PDF layouts**~~ — Resolved. Using page-level gap detection with `SINGLE`, `TWO_COL`, and `THREE_COL` layout types. Sidebar detection is a separate `--phase sidebars` pass. See PDF Column Layout Strategy section above.
3. ~~**Message generation**~~ — Out of scope. The processor extracts raw fields only. Training prompt/response pairs are authored manually from the structured output.
4. ~~**Stat block parsing**~~ — The processor will detect and tag `stat_block` components by recognizing common patterns (`AC X`, `HD X`, `hp X`, `#AT X` for AD&D; `STR/DEX/CON/INT/WIS/CHA` blocks for 5e) and preserve the raw text as-is. Normalization and conversion to a standard format (e.g., 5e stat block) is explicitly out of scope and deferred.
5. **Complex decorative page layouts** — Title pages and cover pages often have decorative text at arbitrary positions (e.g., large drop caps, rotated text, overlapping elements). These pages are likely to produce garbled output. Consider detecting pages with very low text density or unusually scattered x/y positions and flagging them as `needs_review` rather than attempting extraction.
