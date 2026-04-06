# Table Row Detection Spec

## Status: Implemented

## Problem
Phase 1 row splitting treats table horizontal rules as row boundaries, fragmenting
tables into many tiny zones. Additionally, tables can be split in the middle if a
Phase 1 gap falls between rows, causing only part of the table to be detected.

## Table Structure
A table zone has:
1. **Title** — centered text (e.g., "Wandering Monster Table: Level 1")
2. **Header row** — column headers (e.g., "Die Roll", "Monster", "No", "AC", etc.)
3. **Optional horizontal rules** — thin full-width lines separating header from body
4. **Data rows** — sparse text entries aligned to column positions
5. Some rows have cells spanning multiple columns or wrapping to two lines

## Detection Approach — Two Phases

### Phase 1: Initial TABLE Detection (in `buildTextZone`)
After Phase 1 row splitting, each zone is analyzed in `buildTextZone()`:
- Find horizontal gaps >= `MIN_HORIZ_GAP_PX` (5px) within the zone
- If 3+ gaps exist AND majority of content runs between gaps are short
  (< `MIN_ROW_SPLIT_PX` = 40px), classify as TABLE zone
- This catches tables that fit entirely within one Phase 1 row

### Phase 2: Table Retry with Bidirectional Scanning (in `buildZones`)
After initial zone building, if any TABLE zones were detected:

1. **Remove adjacent breaks** — breaks inside or near the TABLE zone are removed
2. **Scan downward** from table bottom, absorbing content runs that look like
   additional table rows:
   - Short height (< `MIN_ROW_SPLIT_PX * 2` = 80px)
   - Low ink density (< 12% of content width) — table rows are sparse data
     across the full width; paragraph text is 15-27% dense
   - Very short runs (< `MIN_HORIZ_GAP_PX`) are skipped as noise/HRs
   - Stop when a dense content run is encountered (paragraph text)
3. **Scan upward** from table top, absorbing content runs above:
   - Same height and ink density checks as downward scan
   - Very short runs (noise pixels, horizontal rules) are skipped over
   - **Centered title detection**: if a content run has >10% margin on both
     left and right sides, it's the table title — include it and stop scanning
   - Stop when dense paragraph text or the title is found
4. Place new breaks at the extended table boundaries
5. Rebuild zones from the modified break list

## Key Thresholds
| Threshold | Value | Purpose |
|-----------|-------|---------|
| `MIN_HORIZ_GAP_PX` | 5px | Minimum gap to detect table inter-row spacing |
| `MIN_ROW_SPLIT_PX` | 40px (B1_4) | Phase 1 gap threshold; also max "short" run |
| Max table row height | 80px | Content runs taller than this aren't table rows |
| Max ink fraction | 0.12 | Table rows: 3-10%; paragraph text: 15-27% |
| Min margin for centered | 10% | Both sides must have >10% margin for title detection |

## Test Coverage
- `B4Page05Test` — wandering monster table at bottom of page (Level 1)
- `B4Page09Test` — table in middle with two-column text above and below (Level 2)
- `B4Page17Test` — table split by Phase 1 gap; tests upward scan + title detection (Level 3)
- `B4Page16Test` — no table page (negative test, no false positives)

## Debug Visualization
Test classes generate per-pass band images (`B4-pageNN-bands-P1.png`, `P2.png`):
- **Magenta fill + thick border** — TABLE zone with red line at bottom boundary
- **Blue fill** — TEXT zone with 2+ columns
- **Green fill** — TEXT zone with 1 column
- **Cyan rectangles** — Phase 1 break gaps between zones
