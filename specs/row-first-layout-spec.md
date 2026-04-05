# Row-First Layout Analysis — Spec

## Status: Implemented

## Problem

The original `ProjectionAnalyzer` detected columns across the **full vertical extent** of a
page zone.  When a zone contained content at different horizontal alignments — a centred
heading, a half-page illustration beside text, and a bottom table — the vertical projection
accumulated ink from all of them.  This cross-contamination made column gap detection
impossible: a gap that was clean in the two-column body rows was filled by heading ink or
illustration edge bleed in other rows.

Multiple attempts to patch this (relaxed ink thresholds, body-only fallback, density ratio
tuning) all failed because the fundamental assumption — one vertical projection per zone —
cannot handle heterogeneous horizontal structure.

## Solution

Split each page into **horizontal rows first**, then detect columns independently within
each row.  Each row contains only one type of horizontal structure, so vertical projection
within a single row sees clean gaps.

### Three-Phase Analysis

**Phase 1 — Row Splitting** (`buildZones`)

After margin detection, two sources of breaks divide the content area into rows:

1. Full-width illustration zones (bilateral density check) → become IMAGE rows
2. Significant horizontal gaps (≥ `minRowSplitPx`, blank rows with ink ≤ `maxInkFraction`
   of content width) → whitespace separators between rows (no row generated for the gap)

All breaks are sorted by y-position.  Text regions between breaks become independent rows,
each receiving its own column detection.

**Phase 2 — Per-Row Column Detection** (`buildTextZone`)

For each text row independently:

1. Short-row guard: rows shorter than `minIllustrationPx` (80px, ~4 text lines at 150 DPI)
   skip column detection entirely — they cannot contain meaningful multi-column content.
   This prevents false gutters through character/word gaps in headings.

2. Vertical projection restricted to the row's y-range.  Column gutters found using
   `findGaps` + `filterIndentGaps` + `mergeSparseBridges` with the strict `maxInkFraction`
   (0.5%) threshold.

3. The strict threshold (0.5%) is safe because row splitting ensures each row contains only
   one type of content — no cross-contamination from headings or illustrations.

**Phase 3 — Per-Column Sub-Zone Detection** (unchanged)

For each column in each row:
- Pass 1.5: column-scoped illustration detection with Phase B fringe extension
- Pass 2: horizontal gap detection within TEXT sub-zones

**Header Split** (`buildTextZoneWithHeaderSplit`)

After column detection, if a row has multiple columns, a header check runs: scan from the
top of the row to find where the column gap first becomes clean.  If the top portion has ink
at the gap position (a centred heading spanning the gutter), split into a SINGLE header zone
+ a multi-column body zone.  This handles cases where the gap between a heading and the
content below it is too small for Phase 1 row splitting (< `minRowSplitPx`).

### Removed: Pass 1b (Header Fallback)

The old Pass 1b re-scanned the bottom 80% of a zone when Pass 1 found no column gaps,
attempting to skip header ink that contaminated the gutter.  **This is removed** because:

1. Row-first splitting now handles header isolation — headers get their own rows.
2. Pass 1b was actively harmful: when Pass 1 correctly found no gap in a heading-only row
   (e.g. "Dungeon Module B4" / "The Lost City" on a title page), Pass 1b would re-scan a
   smaller region and find false gaps through character spacing in the remaining text.
3. The 80% body region sometimes contained only one or two lines of text, where inter-word
   gaps of 10px could survive `filterIndentGaps` and produce false two-column splits.

### Column Gap Ink Threshold

Changed from `maxColumnGapInkFraction` (3%) back to `maxInkFraction` (0.5%).

The elevated 3% threshold was a workaround for cross-contamination: heading text and
illustration edge bleed injected a few ink pixels into gutter columns, and the strict 0.5%
threshold rejected them.  With row-first splitting, each row's projection only contains
that row's ink — no cross-contamination.  The strict threshold is restored because:

- At 150 DPI, thin character strokes (connecting arches in "n", "m", serifs) produce only
  1-2 ink pixels in the vertical projection.  The 3% threshold (maxInk=4 for a 150px row)
  treated these as "empty," allowing column gaps to pass through letterforms.
- Real column gutters have zero ink — the strict threshold detects them correctly.
- Bridge merging in `findGaps` already handles stray noise pixels (serifs, hyphens) by
  merging across bridges < `minVertGapPx` wide.

### Cover Page Handling

`PDFPreprocessor.processLayout` now creates a single full-width sub-page for FRONT_COVER,
BACK_COVER, and MAP pages (`isFullPageLayout` types), skipping zone/column iteration
entirely.  Previously, `withCoverOverride` only changed the display label but zones were
still split into sub-pages with false column gutters.

### Illustration Fringe Extension (Pass 1.5)

Column-scoped illustration detection (`findIllustrationZones`) now includes Phase B fringe
extension, mirroring what Pass 0 already does for full-width illustrations.  After finding
the dense core zone, it extends upward/downward through rows with any ink above
`minInkForContent`, stopping at blank rows.  This fixes illustration zone boundaries cutting
off sparse trailing edges (thin lines, details) that fell below the 15% density threshold.

---

## Files Changed

| File | Change |
|------|--------|
| `ProjectionAnalyzer.java` | Row-first `buildZones`; `buildTextZoneWithHeaderSplit`; short-row guard in `buildTextZone`; removed Pass 1b; reverted to strict ink threshold; Pass 1.5 fringe extension; cyan debug drawing for row boundaries |
| `PDFPreprocessor.java` | Single sub-page for cover/MAP pages |

## What This Fixes

- **B4 page 2 (title page)**: No false column gap through "The Lost City" character spacing.
  Title row correctly detected as single-column.
- **B4 page 5 (Players' Background box + two-col + table)**: Heading is split from the
  box content.  Column detection in the body row sees a clean gutter.
- **B4 page 12 (two-col with left-column illustration)**: Body row detects 2 columns,
  illustration stays in left column only (Pass 1.5 with fringe extension).
- **Section headings** ("PART 4: TIER 5..."): Short-row guard prevents false column
  detection through word gaps in headings < 80px tall.
- **Cover pages**: Single sub-page output, no spurious column splits.
- **Any page with a full-width heading above two-column text**: Row splitting isolates the
  heading; `buildTextZoneWithHeaderSplit` handles cases where the gap is too small for
  Phase 1 splitting.

## What Stays the Same

- Margin detection (including footer isolation)
- Bilateral full-width illustration detection (Pass 0 with Phase B fringe extension)
- `findGaps`, `filterIndentGaps`, `mergeSparseBridges` (unchanged algorithms)
- Pass 2 horizontal gap detection per TEXT sub-zone
- Config system (`DnD_BasicSet` hierarchy)
- `ColumnAwareTextStripper` (independent text extraction path)
- `deriveLayoutType` (unchanged, works with more granular zone list)

## Test Cases

- B4 page 2 (title page) → single-column rows, no false gutter
- B4 page 5 (Players' Background + two-col + table) → 3+ rows, correct column detection
- B4 page 12 (two-col with left-column illustration) → 2 columns, illustration bounded
- Normal two-column pages (no heading) → 1 row with 2 columns (unchanged)
- Single-column pages → 1 row, SINGLE (unchanged)
- Full-page maps → 1 IMAGE row (unchanged)
- Section headings (< 80px) → single-column, no false gaps
- Cover pages → single sub-page in layout PDF
