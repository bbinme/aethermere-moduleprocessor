# Table Row Detection Spec

## Problem
The bottom row of a page section containing a table (e.g., B4 page 5 wandering monster table) 
gets fragmented into many tiny TEXT zones. Each table data row becomes its own zone, losing 
the table structure. The table should be detected as a single TABLE zone.

## Table Structure
A table zone has:
1. **Title** — bold centered text (e.g., "Wandering Monster Table: Level 1")
2. **Header row** — column headers (e.g., "Die Roll", "Monster", "No", "AC", "HD", etc.)
3. **Optional horizontal rule** — thin full-width line separating header from body
4. **Data rows** — sparse text entries aligned to column positions, separated by horizontal rules
5. Some rows (3rd, 4th, 5th) have cells spanning multiple columns

## Detection Approach
After rescuing content runs from a merged gap, check if the pattern matches a table:

1. **Pattern check** — if a gap contained 3+ rescued content runs with consistent spacing,
   this is likely a table region
2. **Row gap calculation** — for horizontal rules within the table:
   - Calculate gaps ABOVE the rule (between previous data row and the rule)
   - Calculate gaps BELOW the rule (between the rule and next data row)
   - These gaps should be consistent across the table
3. **Table zone creation** — instead of fragmenting into individual row zones, create a 
   single TABLE zone spanning from title through last data row

## Implementation
In `buildZones()` — after `rescueContentInGaps`, detect sequences of closely-spaced content 
runs (separated by gaps < some threshold) and merge them into a single content region. 
This prevents the table from being split into 8+ tiny zones.

The zone type should be TABLE (or TEXT with table metadata) so downstream processing 
can extract the table structure.

## Affected Tests
- `B4Page05TableTest` — all 8 monster entry tests pass, fragmentation test needs table awareness
