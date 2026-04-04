# Row-First Layout Analysis — Spec

## Problem

The current `ProjectionAnalyzer` detects columns across the **full vertical extent** of a
page zone.  When a zone contains content at different horizontal alignments — a centred
heading, a half-page illustration beside text, and a bottom table — the vertical projection
accumulates ink from all of them.  This cross-contamination makes column gap detection
impossible: a gap that is clean in the two-column body rows is filled by heading ink or
illustration edge bleed in other rows.

Multiple attempts to patch this (relaxed ink thresholds, body-only fallback, density ratio
tuning) all failed because the fundamental assumption — one vertical projection per zone —
cannot handle heterogeneous horizontal structure.

## Proposed Approach

Split each page into **horizontal rows first**, then detect columns independently within
each row.  Each row contains only one type of horizontal structure, so vertical projection
within a single row sees clean gaps.

### Three-Phase Analysis

**Phase 1 — Row Splitting**

After margin detection, compute the horizontal projection across the full content width
(as today).  Find significant horizontal gaps (blank rows ≥ `minRowSplitPx`) that divide
the content area into horizontal rows.  This is essentially the current Pass 0 + the
horizontal gap detection from Pass 2, but applied at the page level before any column
detection.

Each row is a horizontal band `[yTop, yBottom)` spanning the full content width.

Rows may be:
- Separated by clear blank-line gaps (e.g. gap between a boxed section and body text)
- Separated by illustration boundaries (the current Pass 0 bilateral detection identifies
  full-width illustration zones that naturally break the page into rows)

Phase 1 produces an ordered list of **Row** records: `(yTop, yBottom)`.

**Phase 2 — Per-Row Classification**

For each row independently:

1. **Illustration check**: If the row's ink density (bilateral) is high and contiguous,
   classify as `IMAGE`.  This is the existing Pass 0 bilateral check, now applied per-row
   rather than across the whole content area.

2. **Column detection** (current Pass 1): Compute the vertical projection restricted to
   this row's y-range only.  Find column gutters using `findGaps` + `filterIndentGaps` +
   `mergeSparseBridges`.  Because the projection only covers this row's content, a heading
   row sees no gap (correct — it's single-column), while the two-column body row sees a
   clean gap (correct — no heading ink contaminating it).

3. **Content-type hints**:
   - A row with ≥ `minTableHorizGaps` horizontal gaps and a single column → TABLE
   - A row with high bilateral ink density → IMAGE / MAP
   - A row with a boxed border (ink at left/right edges + top/bottom edges of the row) →
     BOXED_TEXT (future enhancement, not in initial implementation)

Phase 2 produces a **ClassifiedRow**: `(yTop, yBottom, RowType, columns[])`.
- `RowType`: IMAGE, SINGLE, TWO_COLUMN, THREE_COLUMN, TABLE
- `columns[]`: for text rows, the column x-ranges from gap detection

**Phase 3 — Per-Column Sub-Zone Detection**

For each column in each text row:
1. Pass 1.5 (column-scoped illustration detection) — unchanged from current logic
2. Pass 2 (horizontal gap detection within each TEXT sub-zone) — unchanged

This is identical to the current Pass 1.5 and Pass 2, but now each column's vertical
extent is limited to its row, not the full page zone.

### Key Difference from Current Architecture

Current flow:
```
Page → Pass 0 (full-width illustrations) → TEXT/IMAGE zones
     → Pass 1 (columns across full zone height) → columns
     → Pass 1.5 (per-column illustrations) → sub-zones
     → Pass 2 (horizontal gaps per sub-zone)
```

New flow:
```
Page → Margins → Horizontal row split (gaps + bilateral illustration detection)
     → Per-row column detection (vertical projection scoped to row)
     → Per-column sub-zone detection (Pass 1.5 + Pass 2, scoped to row)
```

The critical change: column detection sees only one row's worth of ink, not the full zone.

## Data Model Changes

### New record: `Row`

```java
public enum RowType { IMAGE, SINGLE, TWO_COLUMN, THREE_COLUMN, TABLE }

public record Row(int yTop, int yBottom, RowType type, List<Column> columns) {}
```

### Zone model

The existing `Zone` / `Column` / `ColumnZone` hierarchy remains for the per-column
sub-zone structure (Pass 1.5 + Pass 2).  `Row` wraps the column list and replaces the
current `Zone` as the top-level page decomposition unit.

`PageLayout.zones` field type changes from `List<Zone>` to `List<Row>`.  Downstream
consumers (`PDFPreprocessor`, `deriveLayoutType`, debug drawing) update accordingly.

Alternatively, if the refactor is too large, `Row` could simply be a new `Zone` variant
where `ZoneType` gains `TABLE` etc., but a dedicated `Row` type is cleaner because rows
and zones serve different roles (page decomposition vs. column sub-structure).

## Algorithm Detail — Phase 1 Row Splitting

1. Detect margins (unchanged).
2. Compute horizontal projection `horizProj[y]` for content columns `[m.left, m.right)`.
3. Find full-width illustration zones using bilateral check (current Pass 0).
4. In the non-illustration regions, find horizontal gaps ≥ `minRowSplitPx`.
5. Illustration zones and horizontal gaps together partition the content area into rows.

Example for B4 page 5 ("Players' Background" box + two-column text + encounter table):

```
Row 0:  y=85..240   — boxed title section (Players' Background)
        [gap: y=240..265, ~25px blank]
Row 1:  y=265..780  — two-column body text with left-column illustration  
        [gap: y=780..805, ~25px blank]
Row 2:  y=805..1050 — encounter table
```

## Algorithm Detail — Phase 2 Per-Row Column Detection

For each non-IMAGE row:

```java
int[] vertProj = verticalProjection(ink, w, h, row.yTop, row.yBottom);
int maxInk = (int)((row.yBottom - row.yTop) * MAX_COLUMN_GAP_INK_FRACTION);
List<int[]> vGaps = findGaps(vertProj, m.left, m.right, maxInk, MIN_VERT_GAP_PX);
vGaps = filterIndentGaps(vGaps, m.left, m.right, vertProj);
vGaps = mergeSparseBridges(vGaps, vertProj, m.left, m.right);
```

This is identical to the current Pass 1 code, but `yTop`/`yBottom` now span only the
row, not the full zone.

## Impact on PDFPreprocessor

`processLayout` currently iterates `layout.zones()` and creates sub-pages per zone/column/
sub-zone.  With the new model it iterates rows instead:

```
for each Row:
  if IMAGE → one full-width sub-page
  if SINGLE → one full-width sub-page
  if TWO_COLUMN/THREE_COLUMN → one sub-page per column (per sub-zone within each column)
  if TABLE → one full-width sub-page
```

The per-column sub-page creation (iterating `Column` → `ColumnZone`) is unchanged.

## Impact on deriveLayoutType

`deriveLayoutType` currently infers the page-level LayoutType from zone/column counts.
With rows, the logic becomes:

- All rows SINGLE or IMAGE → use existing heuristics (SINGLE, MAP, TWO_ROW, THREE_ROW)
- Any row is TWO_COLUMN → TWO_COLUMN (or TWO_BY_TWO if multiple two-col rows separated
  by an image)
- Mix of row types → compose from row types (e.g. SINGLE + TWO_COLUMN + TABLE → THREE_ROW)

The exact mapping can remain similar to today; the difference is that the input data is
now more accurate because column detection operates per-row.

## Impact on Debug Drawing

- Green: margins (unchanged)
- Orange: IMAGE rows (was: IMAGE zones)
- Red: column gutters per row (was: per zone — now correctly scoped to row height)
- Yellow: column-scoped IMAGE sub-zones within each column (unchanged)
- Blue: horizontal gaps within column TEXT sub-zones (unchanged)
- New: row boundaries could be drawn in a distinct colour (e.g. cyan) to show the Phase 1
  decomposition

## Files Changed

| File | Change |
|------|--------|
| `ProjectionAnalyzer.java` | New `Row`/`RowType` records; refactor `analyzeLayout` to Phase 1→2→3 flow; `buildTextZone` becomes per-row; `deriveLayoutType` updated |
| `PDFPreprocessor.java` | Iterate rows instead of zones for sub-page creation |
| `DnD_BasicSet.java` | No new constants needed (reuses `minRowSplitPx`, existing thresholds) |

## What This Fixes

- **B4 page 5**: The centred "Players' Background" heading is in its own row. The two-column
  body (with left-column illustration) is in a separate row. Column detection in the body row
  sees a clean gutter because the heading ink is not in its projection. The illustration is
  detected by Pass 1.5 within the left column of the body row only.

- **Any page with a full-width heading above two-column text**: The heading becomes its own
  SINGLE row; the body becomes a TWO_COLUMN row. No special "header detection" heuristic
  needed.

- **Pages with tables below body text**: The table becomes its own row, classified
  independently. No risk of table horizontal gaps confusing column detection in the body.

## What Stays the Same

- Margin detection
- Bilateral full-width illustration detection (Phase B fringe extension)
- `findGaps`, `filterIndentGaps`, `mergeSparseBridges` (unchanged algorithms)
- Pass 1.5 column-scoped illustration detection
- Pass 2 horizontal gap detection per TEXT sub-zone
- Config system (`DnD_BasicSet` hierarchy)
- `ColumnAwareTextStripper` (independent text extraction path)

## Test Cases

- B4 page 5 (Players' Background box + two-col + table) → 3 rows, correct column detection
- B4 page 12 (two-col with left-column illustration) → body row detects 2 columns, illustration stays in left column only
- Normal two-column pages (no heading) → 1 row with 2 columns (unchanged behaviour)
- Single-column pages → 1 row, SINGLE (unchanged)
- Full-page maps → 1 IMAGE row (unchanged)
- B1 page 34 (text + illustration + text) → rows split at illustration boundaries
