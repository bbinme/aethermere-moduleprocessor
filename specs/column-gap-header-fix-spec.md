# Column Gap Detection Failure Under Full-Width Header — Spec

## Problem

When a page sub-section contains a **centered full-width title** (e.g. "Players' Background")
above two-column body text, `detectZones` incorrectly classifies the whole sub-page as
`SINGLE`, producing garbled interleaved output instead of correct left/right column text.

### Root cause

`detectZones` builds ONE histogram over **all** text on the page (Stage 1).  A centered
title places characters at x-positions that overlap the column gutter.  Those bins exceed
the `maxBinCount=2` threshold and the gutter is no longer detected as a gap.

Additionally, `extractPage` passes a `docBoundaryHint` (the document-level column gap
detected by sampling many pages), but `detectZones` never receives it.  When page-level
detection fails, there is no fallback.

The THREE_COL path already solves the title problem via `fullWidthHeaderEnd()`: it
detects runs whose y-level has characters spanning both the gutter regions, identifies
those as a full-width header, and emits `[SINGLE header zone] + [THREE_COL body zone]`.
The TWO_COL path has no equivalent.

---

## Fix — Two Changes to `ColumnAwareTextStripper`

### Change 1 — Pass `docBoundaryHint` into `detectZones`

**`extractPage`**: change the call from  
```java
List<Zone> zones = detectZones(runs, pageWidth, pageHeight);
```
to  
```java
List<Zone> zones = detectZones(runs, pageWidth, pageHeight, docBoundaryHint);
```

**`detectZones` signature**: add `float docBoundaryHint` parameter.

**In `detectZones`**, after Stage 1 (and the fallback with `maxBinCount=4`) still
finds `allGaps.isEmpty()`, before returning `SINGLE` at line 500:

```java
if (allGaps.isEmpty() && docBoundaryHint > 0) {
    // Verify the hint gap position is actually plausible on this page:
    // right column must reach at least RIGHT_REACH_MIN of page width.
    float rightReach = 0;
    for (TextRun r : runs) if (r.x() > docBoundaryHint) rightReach = Math.max(rightReach, r.x());
    if (rightReach >= pageWidth * RIGHT_REACH_MIN) {
        allGaps = List.of(docBoundaryHint);   // continue into TWO_COL logic below
    }
}
if (allGaps.isEmpty()) {
    return List.of(new Zone(0, pageHeight, BandLayout.SINGLE, -1, -1, 0));
}
```

### Change 2 — Apply `fullWidthHeaderEnd` to TWO_COL path

After `mainGap` is chosen (and `rightReach` check passes), before returning the final
`TWO_COL` zone, check for a full-width header:

```java
// Detect full-width header above two-column body (mirrors the THREE_COL logic).
float headerEndY = fullWidthHeaderEnd(runs, mainGap, pageWidth);
if (headerEndY > 0) {
    // Stage 2 (sidebar detection) runs only for the body zone's x-range;
    // for simplicity, return SINGLE header + TWO_COL body with mainGap only.
    float rightEnd = detectSidebarBoundary(...);  // existing sidebar logic
    return List.of(
        new Zone(0,           headerEndY, BandLayout.SINGLE, -1,      -1,       0),
        new Zone(headerEndY,  pageHeight, BandLayout.TWO_COL, mainGap, rightEnd, 0)
    );
}
```

`fullWidthHeaderEnd(runs, g1, g2)` considers a row "full-width" when it has characters
in the gutter region between `g1` and `g2`.  For TWO_COL we pass `mainGap` as `g1` and
`pageWidth` as `g2` (i.e., any character to the right of `mainGap` that also appears
to be centred qualifies).

**Note:** `fullWidthHeaderEnd` was written for the two-gutter THREE_COL case.  For TWO_COL
we need a slightly different version: a row is a "full-width header row" if it has
characters on BOTH the left side (x < mainGap) AND the right side (x > mainGap) while
centred within the page — essentially the same bitmap the box-drawing detector already
uses.  The simplest implementation: a row at y is in the header if it has at least one
character in `[mainGap - 20, mainGap + 20]` (in the gutter area).

---

## Files Changed

| File | Change |
|------|--------|
| `ColumnAwareTextStripper.java` | `detectZones` + `extractPage` |

## Test cases

- B4 page with "Players' Background" box → output is correctly split as left col then right col
- Normal two-column pages (no full-width header) → unchanged behaviour
- Single-column pages → unchanged (docBoundaryHint plausibility check prevents false positives)
