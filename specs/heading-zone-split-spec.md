# Heading Zone Split — Spec

## Problem

Phase 1 row splitting uses `findGaps` to locate horizontal whitespace gaps ≥
`MIN_ROW_SPLIT_PX` (40 px for B1-4). When a full-width heading sits between two
whitespace gaps, the heading ink interrupts what is effectively a single large gap.

Example from B4 page 17, current Z3 `y=[750,1439)`:

```
y=1095–1108  whitespace gap  (14 px)
y=1109–1126  "K E Y  T O  T I E R  5" heading — bridge (18 px)
y=1127–1146  whitespace gap  (20 px)
```

Total whitespace: 14 + 20 = 34 px. Including the thin heading bridge: 1146 − 1095 =
**51 px**, well above the 40 px threshold. But `findGaps` never merges them because:

1. `rawMin = minWidth / 2 = 20` — the 14 px gap is rejected before bridge merging
2. Even if both fragments survived, the 18 px bridge between them is below `maxBridge`
   (40 px) so `mergeGaps` *would* merge them — if they existed in the raw list.

The root cause is that `rawMin` is too aggressive a floor for the case where two
small gaps flank a short, sparse bridge (a heading line).

## Goal

Allow `findGaps` to collect smaller whitespace fragments as **bridge-merge candidates**
so that adjacent sub-threshold gaps can merge across a thin heading bridge into a
single gap that exceeds `MIN_ROW_SPLIT_PX`. The minimum zone gap size stays at 40 px —
a 13 px fragment on its own is not a valid gap. It only matters when bridge merging
combines it with a neighbour to exceed the threshold.

---

## Fix

### Lower `rawMin` for bridge-merge candidates only

`findGaps` collects raw whitespace fragments ≥ `rawMin` before bridge merging. The
current floor (`minWidth / 2 = 20`) rejects 14 px fragments that could merge into
valid gaps. Lower this floor to 13 px **only in the initial Pass 1 call** (not in
the table retry or other gap-finding contexts) so these fragments enter the raw list
as bridge-merge candidates.

The minimum gap size for an actual zone split remains `MIN_ROW_SPLIT_PX` (40 px) —
enforced by the final `minWidth` filter in `findGaps`. A 13 px or 14 px fragment
that fails to merge is discarded.

Add a `rawMin` parameter to `findGaps` and `findPhase1Breaks`. Pass `rawMin = 13`
from the initial Pass 1 call in `buildZones`. All other callers use the default
`rawMin = minWidth / 2`.

```
raw[0] = [1095, 1108)  (14 px)  — admitted by rawMin = 13
raw[1] = [1127, 1146)  (20 px)
bridge = 1127 − 1108 = 19 px  ≤  maxBridge (40)  →  merge
merged = [1095, 1146)  (51 px) ≥ minWidth (40)   →  kept as zone break
```

The merged gap becomes a normal Phase 1 break, and `buildZonesFromBreaks` splits Z3
at that boundary. Each resulting zone gets independent column detection via
`buildTextZoneWithHeaderSplit`, which already handles centred headings above
multi-column body text.

### Table retry: restore bottom boundary break

When the table scan downward finds NOT-table on the first content run (no table rows
absorbed), `lastGapEnd` stays -1 and no bottom boundary break is placed — even though
the adjacent break was already removed. Fix: when `lastGapEnd == -1`, place a break
at the gap between the table bottom and the first non-table content.

### Guard against noise

The lower `rawMin` admits more tiny fragments into the raw list for bridge merging.
Two existing safeguards prevent false splits:

1. **`maxBridge` check in `mergeGaps`**: bridges wider than `minWidth` (40 px) are
   never merged. A normal text paragraph between two inter-paragraph gaps is much
   taller than 40 px, so it won't be bridged.

2. **Final `minWidth` filter**: after merging, any gap still below `minWidth` is
   discarded. Only merged gaps that actually reach the threshold survive.

The lower `rawMin` is scoped to the initial Pass 1 call only. The table retry pass
uses the default `rawMin = minWidth / 2`, so table region detection is unaffected.

---

## Expected Output — B4 Page 17

The gap at y=[1095,1146) is now detected as a Phase 1 break. Similarly, the gap
cluster at y=[1164,1181) (18 px alone, but potentially mergeable with nearby
fragments) may also surface. The previous 4-zone layout becomes approximately:

```
Zone 0: TEXT  y=[44,68)      cols=1   (page title)
Zone 1: TEXT  y=[130,228)    cols=2   (wandering monster intro)
Zone 2: TABLE y=[271,733)    cols=1   (wandering monster table)
Zone 3: TEXT  y=[750,775)    cols=1   (monster description header — existing header split)
Zone 4: TEXT  y=[775,1095)   cols=2   (monster descriptions)
Zone 5: TEXT  y=[1109,1128)  cols=1   (KEY TO TIER heading)
Zone 6: TEXT  y=[1146,1161)  cols=1   (sub-section content)
Zone 7: TEXT  y=[1182,1439)  cols=2   (room descriptions 41, 42)
```

Exact y-values depend on final gap boundary positions.

---

## Test Plan

- [ ] B4Page17Test `--phase 1`: Z3 splits into multiple zones around "KEY TO TIER"
- [ ] Zone overlay JPG confirms heading is in its own zone
- [ ] No regressions on B4 pages 5, 16, or other existing page tests
- [ ] Full pipeline run: markdown for page 17 has correct section ordering
