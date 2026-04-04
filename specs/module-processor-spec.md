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

### PDF Layout Analysis Strategy

Published D&D modules mix full-width titles, two-column body text, half-page illustrations, full-page maps, and sidebars on a single page. PDFBox's default extractor reads left-to-right across the full page width, scrambling multi-column text. The pipeline solves this by **rasterising each PDF page at 150 DPI** and running projection-profile analysis to detect layout structure, then splitting the page into sub-pages before text extraction.

#### Pipeline overview

```
PDF page → rasterise at 150 DPI → ProjectionAnalyzer → sub-pages → PDFBox text extraction → Markdown
```

The layout analysis lives in `ProjectionAnalyzer`. It operates on the grayscale pixel image of a page and returns a `PageLayout` — an ordered list of horizontal **Zones** (each either TEXT or IMAGE), where TEXT zones carry one or more **Columns**, and each Column contains ordered **ColumnZones** (TEXT sub-zones with blank-line gaps, or IMAGE sub-zones for column-scoped illustrations).

`PDFPreprocessor.processLayout` iterates the zone/column/sub-zone tree and calls `addSubPage` once per leaf, producing a new PDF where each sub-page contains exactly one extracted rectangle.

#### Data model

```
PageLayout
  List<Zone>
    Zone(yTop, yBottom, type=IMAGE, columns=[])          // full-width illustration (Pass 0)
    Zone(yTop, yBottom, type=TEXT, columns=[…])          // text zone (Passes 1–2)
      Column(xLeft, xRight, subZones=[…])
        ColumnZone(yTop, yBottom, type=TEXT, horizGaps)  // text run with blank-line gaps (Pass 2)
        ColumnZone(yTop, yBottom, type=IMAGE, [])        // column-scoped illustration (Pass 1.5)
```

`LayoutType` (summary field, used for diagnostics and MAP/TABLE shortcuts):
`SINGLE`, `MAP`, `TABLE`, `TWO_COLUMN`, `THREE_COLUMN`, `TWO_ROW`, `THREE_ROW`, `TWO_BY_TWO`

#### Four-pass analysis

**Pass 0 — Full-width illustration detection (bilateral)**

Horizontal projections are computed separately for the left half and right half of the content area. A row is "full-width dense" only when **both** halves exceed `FULL_PAGE_ILLUSTRATION_FRACTION` (15%) of the half-width. This bilateral check ensures that column-scoped illustrations (ink only in one half) fail Pass 0 and fall through to Pass 1.5.

Contiguous dense rows ≥ `MIN_ILLUSTRATION_PX` (80 px ≈ 0.5 in at 150 DPI) become full-width **IMAGE zones** (drawn orange in debug). The remaining content rows become candidate TEXT zones.

**Phase A (core)**: find contiguous bilateral-dense runs.
**Phase B (fringe extension)**: after finding each bilateral-dense core, extend upward/downward through any row where either half has ink > `MIN_INK_FOR_CONTENT` (3 px). Stop at a fully blank row. This captures the sparse pen-and-ink edges of illustrations that fail the density threshold on their own.

Post-processing in `buildZones`:
- **Trailing TEXT absorption**: if the last zone is a thin TEXT zone (< 80 px) immediately after an IMAGE zone, it is absorbed into the IMAGE zone. This eliminates spurious trailing column zones from sparse bottom-edge rows.
- **IMG-TEXT-IMG merge**: if a thin TEXT zone (< 80 px) is sandwiched between two IMAGE zones, all three are merged into one IMAGE zone. Implemented as a `while` loop (not `for`) to avoid out-of-bounds access after list shrinkage.

**Pass 1 — Column gutter detection (vertical gaps), per TEXT zone**

For each TEXT zone the vertical projection (ink per column) is computed restricted to that zone's y-range. Gaps in this projection identify column gutters. Qualifying gutters must:
- be ≥ `MIN_VERT_GAP_PX` (10 px) wide
- produce sub-columns each ≥ `MIN_COLUMN_FRACTION` (25%) of content width
- not have an ink-density ratio > `MAX_INK_DENSITY_RATIO` (5×) between adjacent sub-columns

Sparse bridge merging: if the strip of text between two adjacent gutters has ink density below `MAX_BRIDGE_DENSITY_FRACTION` (30%) of the sparser flanking column, the two gaps are merged into one wider gutter (handles centred section headings that span a column boundary).

**Pass 1.5 — Column-scoped illustration detection**

For each column in each TEXT zone, the horizontal projection is restricted to that column's x-range. A row is "column-dense" when ink exceeds `ILLUSTRATION_INK_FRACTION` (15%) of the **column** width. Contiguous dense runs ≥ 80 px become column-scoped **IMAGE sub-zones** (drawn yellow in debug). The remaining rows within the column become TEXT sub-zones.

This catches half-page illustrations that fail Pass 0's bilateral check because their ink occupies only one content half.

**Pass 2 — Blank-line gap detection (horizontal gaps), per TEXT sub-zone**

Within each TEXT sub-zone, horizontal gaps (runs of blank rows ≥ `MIN_HORIZ_GAP_PX` = 5 px) are recorded in the `ColumnZone.horizGaps` list. These are used by `PDFPreprocessor` for sub-page splitting decisions and drawn blue in debug.

#### Example: mixed-layout page

```
┌─────────────────────────────────────┐
│  Zone 1: IMAGE (Pass 0 bilateral)   │  full-width illustration (orange)
├─────────────────────────────────────┤
│  Zone 2: TEXT                       │
│  Column A          │  Column B      │  (column gutter from Pass 1)
│  ColumnZone: TEXT  │  ColumnZone: TEXT  ← blank-line gaps from Pass 2
│  ColumnZone: IMAGE │  ColumnZone: TEXT  ← half-page illus. from Pass 1.5 (yellow)
│  ColumnZone: TEXT  │  ColumnZone: TEXT
└─────────────────────────────────────┘
```

#### MAP detection

Two paths lead to `LayoutType.MAP`:

1. **Primary path**: content-area ink density > `MIN_MAP_INK_FRACTION` (20%), total horizontal gaps ≤ `MAX_MAP_HORIZ_GAPS` (3), and vertical gaps ≤ `MAX_MAP_VERT_GAPS` (1).

2. **Alternate path**: applies to dungeon-map pages that have a title heading and a legend column (which would produce vertical gaps failing the primary check). If ink density is high AND full-width IMAGE zones (from Pass 0) cover ≥ `MAP_IMAGE_ZONE_FRACTION` (50%) of the content height, the page is classified MAP regardless of gap counts.

#### TABLE detection

If there are ≥ `MIN_TABLE_HORIZ_GAPS` (10) horizontal gaps across all TEXT sub-zones and no column gutters, the page is classified as TABLE.

#### Margin detection

Four margin edges are detected by scanning inward from each side until significant ink is found (`MIN_INK_FOR_CONTENT` = 3 px). The **bottom margin** uses a footer-isolation step: a cluster at the bottom ≤ `FOOTER_CLUSTER_MAX_PX` (150 px) is absorbed into the margin, along with additional ornamental rules and page numbers above it (`FOOTER_EXTRA_PASSES` = 3), as long as each extra cluster is ≤ `FOOTER_EXTRA_CLUSTER_MAX_PX` (35 px).

#### Debug drawing (--debug flag)

| Colour | Meaning |
|--------|---------|
| Orange | Full-width IMAGE zone (Pass 0 bilateral) |
| Yellow | Column-scoped IMAGE sub-zone (Pass 1.5) |
| Red    | Column gutter / vertical gap (Pass 1) |
| Blue   | Blank-line gap within a TEXT sub-zone (Pass 2) |

#### Post-extraction cleanup (in `PdfConverter`)

After `PDFPreprocessor` produces the split PDF and `PdfConverter` extracts text from each sub-page:

- Merge hyphenated line-breaks (e.g., `adven-\nturers` → `adventurers`)
- Strip page numbers (lines matching `^\d+$`)
- Strip running headers/footers: lines that appear identically on ≥ 3 pages (or ≥ 25% of pages) are removed
- Collapse consecutive duplicate lines

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

- Correct reading order across columns — guaranteed by the sub-page split before text extraction
- Preserve heading hierarchy (H1 → H3) via ALL-CAPS pattern and room-number heuristics
- Preserve lists (bulleted and numbered)
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
    Main.java                        # CLI entry point (phases: layout, classify, bands, edges, render, split, convert, parse, all, sidebars)
    pipeline/
      FileToMarkdownConverter.java   # Phase 1 orchestrator (runs layout preprocessing, then text extraction)
      MarkdownToComponentParser.java # Phase 2 orchestrator
      ParseResult.java               # Record: components + ruleset
    converters/
      PDFPreprocessor.java           # Rasterises PDF pages, runs ProjectionAnalyzer, writes sub-page PDF
      ProjectionAnalyzer.java        # Four-pass projection-profile layout analysis (Pass 0/1/1.5/2)
                                     #   Records: Margins, Zone, Column, ColumnZone, PageLayout
                                     #   Enums: LayoutType, ZoneType
      PdfConverter.java              # PDFBox text extraction from layout-preprocessed PDF; post-cleanup
      ColumnAwareTextStripper.java   # PDFTextStripper subclass: CropBox-restricted extraction, box detection
      CannyEdgeDetector.java         # Canny edge detection (used by --phase edges)
      StatBlockDetector.java         # Detects and fences stat blocks in extracted text
      RulesetDetector.java           # Detects AD&D / 5e / other rulesets from PDF metadata/content
      ConversionResult.java          # Record: markdown + RulesetInfo
      RulesetInfo.java               # Ruleset name + id
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
      JsonWriter.java                # Writes pretty-printed JSON output
  src/test/java/...                  # Unit tests per extractor
  specs/
  build.gradle
```

---

## CLI Interface

```bash
# Full pipeline (layout + convert + parse)
java -jar module-processor.jar --input curse-of-strahd.pdf --phase all

# Layout preprocessing only: produce a split PDF (one sub-page per detected zone/column)
java -jar module-processor.jar --input curse-of-strahd.pdf --phase layout

# Convert to Markdown only (runs layout phase first if given a raw PDF)
java -jar module-processor.jar --input curse-of-strahd.pdf --phase convert

# Parse existing Markdown into components
java -jar module-processor.jar --input curse-of-strahd.md --phase parse

# Output directory
java -jar module-processor.jar --input curse-of-strahd.pdf --phase all --output ./training-data/

# Debug layout analysis (writes annotated band images alongside output)
java -jar module-processor.jar --input curse-of-strahd.pdf --phase layout --debug

# Diagnostic phases (PDF input only)
java -jar module-processor.jar --input curse-of-strahd.pdf --phase classify  # print per-page layout classification
java -jar module-processor.jar --input curse-of-strahd.pdf --phase bands     # write whitespace-band images to <name>-bands/
java -jar module-processor.jar --input curse-of-strahd.pdf --phase edges     # write Canny edge images to <name>-edges/
java -jar module-processor.jar --input curse-of-strahd.pdf --phase render    # rasterise pages to <name>-pages/
java -jar module-processor.jar --input curse-of-strahd.pdf --phase split     # old two-column split (deprecated)
java -jar module-processor.jar --input curse-of-strahd.pdf --phase sidebars  # extract sidebar text for review
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
