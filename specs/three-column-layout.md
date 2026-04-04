# Three-Column Layout Support — Spec (Implemented)

## Problem

Some D&D modules (e.g. DL1 *Dragons of Despair*) use a **three-column body layout** on most pages.
The current `ColumnAwareTextStripper` only handles `SINGLE` and `TWO_COL` layouts (plus the
`SIDEBAR_PLUS_COL` legacy type, which is unused in extraction).

With the current code, `detectZones` finds the gap between column 1 and column 2 (`mainGap`) and
treats everything to the right as a single "right column." PDFBox then reads column 2 and column 3
left-to-right within that merged region, interleaving the two columns line by line:

```
# Current (wrong)
Events If the players are using the characters provided
As opposed to encounters, which take place in read aloud the backgrounds written on the
```

---

## Scope

- Detect three-column body layout at the **page level** (same as current two-column detection).
- Extract columns 1 → 2 → 3 independently and concatenate top-to-bottom.
- Correctly distinguish a **third column** (≈⅓ page wide) from a **right sidebar** (≤ 25% page wide).
- Update the `Zone` record so `gap2` is consistently a **position** (x coordinate) in all layout types,
  removing the current inconsistency where `gap2` means "width" for `TWO_COL` but is used as a
  position in the (legacy) `SIDEBAR_PLUS_COL` switch arm.
- No change to Phase 2 (parsing) or CLI.

### Out of scope

- Per-band detection of SINGLE vs THREE_COL within the same page (the title area of chapter-opener
  pages spans all three columns but the full-width band is a small fraction of the page; the
  three-column body dominates the histogram and drives the zone classification). This is an
  acceptable limitation for now.
- Sidebar detection within three-column pages.

---

## DL1 Page Geometry (reference)

From the screenshot and extracted coordinates, a typical DL1 body page (595 × 842 pt) has:

| Region    | x range     | width  |
|-----------|-------------|--------|
| Column 1  | ~29 .. ~207 | ~178pt |
| Gutter 1  | ~207 .. ~224| ~17pt  |
| Column 2  | ~224 .. ~402| ~178pt |
| Gutter 2  | ~402 .. ~419| ~17pt  |
| Column 3  | ~419 .. ~566| ~147pt |

Three equal(ish) columns; no right sidebar. Chapter-opener pages have a full-width title box
above the three-column body.

---

## Design

### 1. Zone record — `gap2` becomes a position

**Current semantics (inconsistent):**

| Layout           | gap1 meaning     | gap2 meaning           |
|------------------|------------------|------------------------|
| `TWO_COL`        | x position       | right column **width** |
| `SIDEBAR_PLUS_COL` | x position     | used as x position in switch arm (bug/inconsistency) |

**New semantics (consistent):**

| Layout     | gap1 meaning             | gap2 meaning                              |
|------------|--------------------------|-------------------------------------------|
| `SINGLE`   | -1                       | -1                                        |
| `TWO_COL`  | x of main gap            | x of right column end (= valleyX or pageWidth) |
| `THREE_COL`| x of gap between col1/col2 | x of gap between col2/col3             |

**Callers that must be updated:**

- `extractPage` → `TWO_COL` case: `rightW = zone.gap2() - zone.gap1()` (was `zone.gap2()`)
- `extractPageSidebar` → right sidebar x: `rightSidebarX = zone.gap2()` (was `zone.gap1() + zone.gap2()`)

### 2. Add `THREE_COL` to `BandLayout` enum

```java
private enum BandLayout { SINGLE, TWO_COL, THREE_COL }
```

`SIDEBAR_PLUS_COL` is removed — it was never produced by `detectZones` and the extraction switch arm
is dead code. Sidebar extraction is a separate phase (`--phase sidebars`) that does not use this enum.

### 3. `detectZones` — multi-gap detection strategy

**Why not Stage 2b (smoothed-valley approach)?**

The original spec proposed scanning the right portion of the page for a second gap after finding
`mainGap`. This fails for equal-width 3-column layouts because `findBestGap` (which picks the gap
closest to the page center) picks the *second* gutter (col2/col3, at x≈374) rather than the first
(col1/col2, at x≈192) when the second gutter is closer to center. Stage 2b then scans the wrong
region and finds no second gap.

**Actual implementation: Stage 1b using `findAllGaps`**

Stage 1 now calls `findAllGaps` (already existed, used internally by `findBestGap`) to enumerate
all qualifying gaps in the full-page histogram at once.

**Algorithm:**

```
allGaps = findAllGaps(fullHist, pageWidth, scanStart=20%, scanEnd=80%, maxBinCount=2, minWidth=15)

if allGaps.isEmpty():
    return SINGLE

if allGaps.size() >= 2:
    g1 = allGaps[0]   // left-most qualifying gap
    g2 = allGaps[1]   // second qualifying gap
    col1W = g1
    col2W = g2 - g1
    col3W = pageWidth - g2
    maxW = max(col1W, col2W, col3W)
    minW = min(col1W, col2W, col3W)
    col3Reach = max x in runs where x > g2

    if col1W >= pageWidth*0.20 AND col2W >= pageWidth*0.20 AND col3W >= pageWidth*0.20
            AND maxW/minW <= 2.5 AND col3Reach >= pageWidth*0.78:
        return THREE_COL(gap1=g1, gap2=g2)

// Fall through: single gap or THREE_COL conditions failed
mainGap = gap in allGaps closest to page center
// ... existing sidebar logic (Stage 2 + Stage 3) ...
return TWO_COL(gap1=mainGap, gap2=sidebarBoundaryX or pageWidth)
```

**Why this works for DL1:**

DL1 body pages (pageWidth=612) have two equally-qualified gutters at x≈192 and x≈374.
`findAllGaps` returns both. Stage 1b checks THREE_COL: col1W≈192 (31%), col2W≈182 (30%),
col3W≈238 (39%), ratio≈1.3. All conditions pass → THREE_COL detected.

**Why DD35 is unaffected:**

DD35 body pages have one main column gutter (~17pt wide) and one narrower sidebar gutter (~10pt).
The sidebar gutter is ≤ SIDEBAR_GAP_MIN_WIDTH=10pt and is NOT found by `findAllGaps` with
minWidth=15. So `allGaps.size() == 1`, Stage 1b is skipped, and the existing sidebar logic applies.

### 4. `extractPage` — THREE_COL case

```java
case THREE_COL -> {
    float g1 = zone.gap1();   // x position of gap 1
    float g2 = zone.gap2();   // x position of gap 2
    sb.append(extractRegion(page, 0,  zone.yStart(), g1,          zh)); // col 1
    sb.append(extractRegion(page, g1, zone.yStart(), g2 - g1,     zh)); // col 2
    sb.append(extractRegion(page, g2, zone.yStart(), pageWidth-g2, zh)); // col 3
}
```

`leftSidebarW` on THREE_COL zones is always 0 (no sidebar detection for three-column pages).

### 5. `extractPageSidebar` — THREE_COL pages

Skip sidebar extraction entirely for THREE_COL zones (no sidebar support on 3-col pages):

```java
for (Zone zone : zones) {
    if (zone.layout() != BandLayout.TWO_COL) continue;  // unchanged; THREE_COL skipped automatically
    ...
}
```

### 6. `detectDocumentColumnBoundary` — no change

The existing method detects whether the document uses multi-column layout. For DL1, `mainGap` exists
between col1 and col2, so the probe returns > 0 (multi-column detected). This is correct — the
per-page `detectZones` then decides whether it's TWO_COL or THREE_COL.

---

## Updated Extraction Logic (pseudo-code summary)

```
detectZones(runs, pageWidth, pageHeight):
  mainGap = findBestGap(fullHist, ...)
  if mainGap < 0 or rightReach < pageWidth*0.78:
      return [Zone(SINGLE)]

  // Stage 2b: three-column check
  secondGap = findBestGap(rightHist, searchRange=[mainGap+20%..mainGap+80%], ...)
  if secondGap > 0 and threeColConditionsMet(mainGap, secondGap, pageWidth):
      return [Zone(THREE_COL, gap1=mainGap, gap2=secondGap, leftSidebarW=0)]

  // Existing: right sidebar
  rightColWidth = detectRightSidebar(rightHist, mainGap, pageWidth)
  // Existing: left sidebar
  leftSidebarW = detectLeftSidebar(leftHist, mainGap, pageHeight)

  return [Zone(TWO_COL, gap1=mainGap, gap2=mainGap+rightColWidth, leftSidebarW)]
```

---

## Updated `module-processor-spec.md` changes

The spec's "Classify each band" section needs updating:

**Old:** "Two gaps → three regions; if narrowest region is < 35% of page width → `SIDEBAR_PLUS_COL`;
otherwise → `THREE_COL` (rare, treated as single)"

**New:**
- One gap → `TWO_COL`. If the right region has a secondary gap AND both right sub-regions are ≥ 20%
  of page width AND they are within 2.5:1 width ratio → `THREE_COL`.
- `SIDEBAR_PLUS_COL` is removed. Sidebar detection is a separate phase.
- `THREE_COL`: extract three equal(ish) columns left → center → right.

---

## Implementation Notes

The design was implemented as specified with one addition: full-width header detection.

On THREE_COL pages where a heading spans all columns (characters land in the gutter x-ranges), the page is split into a `SINGLE` zone for the header and a `THREE_COL` zone for the body. This is detected by `fullWidthHeaderEnd(runs, g1, g2)`, which scans runs from the top and returns the y-coordinate where all characters stop crossing the gutters. This produces cleaner output than page-level THREE_COL, which would split the heading text at the col1/col2 boundary.

## Files Changed

| File | Change |
|------|--------|
| `ColumnAwareTextStripper.java` | Added `THREE_COL` to `BandLayout` enum; removed `SIDEBAR_PLUS_COL`; updated `Zone.gap2` semantics to be a position in all layouts; added Stage 1b (`findAllGaps`) to `detectZones`; added `THREE_COL` arm to `extractPage`; updated `TWO_COL` arm to use `gap2 - gap1` for right column width; updated `extractPageSidebar` to skip THREE_COL zones; added `fullWidthHeaderEnd` for SINGLE+THREE_COL zone splitting |
| `module-processor-spec.md` | Updated layout classification section |

---

## Test Cases

| Module | Expected result |
|--------|----------------|
| DL1 body pages | THREE_COL detected; columns read top-to-bottom per column |
| DL1 chapter-opener page (full-width title + 3-col body) | Page-level THREE_COL; title text may be partially split at col1/col2 boundary but readable (known limitation of page-level detection) |
| DD35 Forge of Fury (2-col) | TWO_COL unchanged; sidebar extraction unchanged |
| DD35 page with right sidebar | TWO_COL with gap2 = valleyX; sidebar extraction unchanged |
