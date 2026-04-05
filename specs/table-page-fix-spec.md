# Table Page Handling — Spec

## Status: Proposed

## Problem

Two bugs affect pages dominated by a tabular layout (e.g. B4 wandering monster table):

### Bug 1 — Bottom margin eats table rows

The green content boundary (bottom margin) is ~100 px too high — the last table row
("8 Goblin") is excluded from the content area.

**Root cause:** `detectBottomMargin` iterates upward absorbing "footer" clusters.
Table horizontal rules create a repeating pattern of thin ink clusters separated by
small gaps.  Each table row is ~20-30 px tall with ~5-10 px rule gaps between them.
`FOOTER_EXTRA_CLUSTER_MAX_PX` (35 px) lets the algorithm absorb table rows as
"extra footer elements," and `FOOTER_EXTRA_PASSES` (3) means up to 3 rows can be
consumed, moving the bottom margin ~100 px above the actual last content row.

### Bug 2 — Row splitting fragments the table

Phase 1 row splitting treats table horizontal rules as row boundaries (gaps >= 
`MIN_ROW_SPLIT_PX`).  With `MIN_ROW_SPLIT_PX = 40` (B1-4 config), rules that create
gaps >= 40 px split the table into tiny rows.  Each tiny row is < 80 px
(`MIN_ILLUSTRATION_PX`), so the short-row guard applies and each becomes a separate
single-column zone.  The preprocessor creates separate sub-pages for each tiny row,
but most are too small and get lost — only "7 Gnome..." survives in output.

Even with `MIN_ROW_SPLIT_PX = 20` (base config), table rules with blank rows above/below
them can accumulate to >= 20 px gaps.

---

## Fix

### Fix 1 — Footer detection guard: minimum content threshold

Before treating the bottom cluster as a footer candidate, check that the content area
above the proposed boundary still contains substantial content.  If the proposed bottom
margin would place the content area boundary above the midpoint of the page, do NOT
absorb — the "footer" is actually content.

Alternative approach: if the page has high horizontal gap density (many horizontal gaps
in a grid pattern), skip footer absorption entirely — footer detection was designed for
pages with body text and a page number, not tables.

**Simpler fix:** In `detectBottomMargin`, after the iterative absorption loop, validate
that `boundary` is not more than `FOOTER_CLUSTER_MAX_PX` above the first gap top
(`gapStart`).  If the absorption moved too far up, revert to `gapStart` (the original
single-footer boundary).  This caps the total footer height while still allowing normal
page-number + ornament detection.

### Fix 2 — Table-aware row splitting

Tables should NOT be row-split.  A table is a single logical unit: many horizontal gaps
AND many vertical gaps in a grid pattern.

**Detection heuristic:** Before applying Phase 1 row splits, check if the content area
exhibits a table pattern:
1. Compute horizontal gaps in the full content area (already done for row splitting)
2. Compute vertical gaps in the full content area  
3. If both horizontal gap count >= `MIN_TABLE_HORIZ_GAPS` AND vertical gap count >= 2,
   treat the content as a table — return a single TEXT zone spanning the full content area
   with no row splitting.

This check runs early in `buildZones`, before the breaks are assembled.

**Alternative:** Instead of pre-detecting tables, merge fragmented rows post-hoc: if
row splitting produces many (>= 5?) tiny rows (< `MIN_ILLUSTRATION_PX`) that are all
single-column, merge them back into one zone.

---

## Files to Change

| File | Change |
|------|--------|
| `ProjectionAnalyzer.java` | Footer absorption limit in `detectBottomMargin`; table-aware guard in `buildZones` |

## Test Cases

- B4 wandering monster table → single zone, all rows visible in layout PDF, "8 Goblin" inside green border
- B4 page 2 (title page) → unchanged (no table pattern)
- B4 page 5 (Players' Background + two-col) → unchanged
- Normal two-column pages → unchanged
- Pages with genuine footer (page number + ornament) → footer still absorbed correctly
