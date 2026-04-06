# Table Page Handling — Spec

## Status: Implemented

## Problem

Two bugs affect pages with tabular layouts (e.g., B4 wandering monster tables):

### Bug 1 — Bottom margin eats table rows (Fixed)

The footer detection algorithm absorbed table rows as footer elements. Fixed by
adding `BOTTOM_MARGIN_PADDING_PX` and capping iterative absorption.

### Bug 2 — Row splitting fragments tables (Fixed)

Phase 1 row splitting uses `MIN_ROW_SPLIT_PX` (40px for B1_4) to find horizontal
gaps. Table inter-row gaps can trigger these splits, fragmenting the table.

Additionally, bridge merging during gap detection can create a merged gap that only
covers PART of the table — the `rescueContentInGaps` function detects content runs
inside the gap but can't see rows beyond the gap boundary.

---

## Fix — Table Retry with Bidirectional Scanning

Implemented in `ProjectionAnalyzer.buildZones()`:

1. **Pass 1**: Normal Phase 1 row splitting + zone building. `buildTextZone()` detects
   TABLE zones using gap count (3+) and short content run ratio.

2. **Pass 2** (if TABLE zones found): For each detected TABLE zone:
   - Remove breaks inside/adjacent to the table
   - **Scan downward**: absorb short, low-density content runs (table rows)
     until hitting dense paragraph text (ink fraction > 12%)
   - **Scan upward**: absorb table rows above, skipping over noise pixels
     and horizontal rules (< 5px tall). Stop at centered title text (>10%
     margin on both sides) or dense paragraph text
   - Place new breaks at the extended boundaries
   - Rebuild zones from modified breaks

### Why Ink Density Works
Table rows have sparse data spread across the full page width: ink fraction 3-10%.
Paragraph text (even single lines) has dense text: ink fraction 15-27%.
The 12% threshold cleanly separates them.

### Centered Title Detection
When scanning upward, short centered text (significant margins on both sides) is
recognized as the table title. The title is included in the TABLE zone, and scanning
stops — preventing absorption of headings/text above the table.

---

## Files Changed

| File | Change |
|------|--------|
| `ProjectionAnalyzer.java` | `buildZones()` — table retry with bidirectional scan; `buildTextZone()` — TABLE zone detection; `renderBands()` — TABLE zone rendering; `findPhase1Breaks()` / `buildZonesFromBreaks()` — extracted from buildZones for multi-pass |

## Test Cases

- B4 page 5 — table at bottom, all rows detected, extends via downward scan
- B4 page 9 — table in middle of page, rows 6-8 beyond original gap boundary absorbed
- B4 page 16 — no table (pure two-column text), no false positive
- B4 page 17 — table split by Phase 1 gap in middle, upward scan recovers top half + title

## Known Limitations

- Table rows with very high ink density (> 12%) may not be absorbed by the scan
  (e.g., rows with long multi-word entries filling most cells). The current threshold
  works for all tested B4 wandering monster tables.
- The centered title detection uses a 10% margin threshold. Very short titles or
  titles near the left/right edge may not be detected as centered.
- The scan does not currently handle multi-page tables.
