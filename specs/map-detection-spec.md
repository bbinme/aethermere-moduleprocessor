# Map Detection & Classification — Spec

## Purpose

Analyze PDF pages that have been identified as full-page images (LayoutType.MAP) and
classify them as: **hex map**, **grid map**, or **non-map image** (illustration, cover
art, handout, etc.). Output is a new diagnostic phase (`--phase map-detection`) for
tuning, with results eventually feeding into the main pipeline's metadata.

---

## CLI

```bash
java -jar module-processor.jar --input "B2 - Keep on the Borderlands.pdf" --phase map-detection
```

Output directory: `<baseName>-maps/`, one annotated PNG per MAP page showing detected
line orientations and classification result. Non-MAP pages produce no output.

Console summary per page:

```
Page  3: MAP → HEX_MAP   (score: 0.82, dominant angles: 0°, 60°, 120°)
Page  7: MAP → GRID_MAP  (score: 0.91, dominant angles: 0°, 90°)
Page 12: MAP → NON_MAP   (score: 0.15)
```

---

## Background

D&D modules contain three kinds of full-page images that the current pipeline already
groups under `LayoutType.MAP`:

| Type          | Visual characteristics                                           |
|---------------|------------------------------------------------------------------|
| **Hex map**   | Tessellated hexagons, typically outdoor/wilderness/overland maps. Lines cluster at 0°, 60°, and 120°. |
| **Grid map**  | Square grid, typically dungeon/building floor plans. Lines cluster at 0° and 90°. Some grids are rotated 45° (isometric). |
| **Non-map**   | Full-page illustration, cover art, player handout, character sheet. No repeating geometric grid pattern. |

The key insight: **maps are defined by repeating geometric line patterns at characteristic
angles, with regular spacing**. Illustrations have irregular edge distributions with no
dominant periodic structure.

### Challenge: partial-coverage grids

Classic D&D dungeon maps (e.g., B4 "The Lost City" pp. 31–33) do **not** have a grid
spanning the entire page. Grid squares appear only inside rooms (the white areas), while
corridors and walls are solid fill (blue/black). This means:

- Grid lines are **short and localised**, not full-page spans
- Solid-fill walls produce strong edges at 0° and 90° that look like grid lines but
  have irregular spacing
- A single page may contain **multiple map sections** (e.g., Tiers 1, 2, 3 on one page)
- Legend boxes, compass roses, and labels add non-grid clutter

The algorithm must handle both full-coverage hex/grid maps **and** these partial-coverage
dungeon maps where the grid signal is weaker and mixed with wall edges.

---

## Algorithm

### Overview

```
MAP page → rasterise (150 DPI)
         → crop to content margins
         → Canny edge detection
         → Hough line transform
         → angle histogram (0°–179°)
         → peak detection + spacing analysis
         → classify by peak pattern + spacing regularity
```

### Step 1: Rasterise & crop

Reuse the existing `PDFRenderer.renderImageWithDPI()` at 150 DPI. Crop to the content
margins returned by `ProjectionAnalyzer.analyzeLayout()` to exclude page borders and
printer marks.

### Step 2: Edge detection

Run the existing `CannyEdgeDetector.detect()` with thresholds tuned for map line work:
- `lowThreshold`: 20
- `highThreshold`: 60

These are lower than the defaults (30/90) because map grid lines are often thin and
lightly printed, especially in older TSR modules.

### Step 3: Hough line transform

Implement a standard Hough transform on the binary edge image. For each edge pixel
(x, y), vote into an accumulator indexed by (theta, rho):

```
rho = x * cos(theta) + y * sin(theta)
```

Parameters:
- **Theta resolution**: 1° (180 bins, 0°–179°)
- **Rho resolution**: 1 pixel
- **Minimum votes**: `MIN_HOUGH_VOTES` — scale with image size:
  `min(width, height) * HOUGH_VOTE_FRACTION` (suggested: 0.15)

Only accumulator cells above the minimum-votes threshold are considered detected lines.

### Step 4: Angle histogram

Collapse the 2D accumulator into a 1D angle histogram by summing votes across all rho
values for each theta bin. Normalize to [0, 1] by dividing by the maximum bin value.

### Step 5: Peak detection

Find peaks in the angle histogram. A peak is a local maximum with normalized value
≥ `MIN_PEAK_STRENGTH` (suggested: 0.25).

Merge peaks within ±`PEAK_MERGE_WINDOW` (suggested: 3°) — the strongest bin absorbs
its neighbours.

### Step 6: Spacing regularity analysis

For each dominant angle, extract the rho values of all detected lines at that angle,
sort them, and compute the gaps between consecutive lines.

**Cluster the gaps by size** (within ±20% tolerance) and find the most common gap size.
This is the candidate grid spacing.

Count how many consecutive line-pairs match the candidate spacing. This is the
**regular-line count** — the core signal for grid detection.

This approach is robust to partial-coverage grids: wall edges at 0°/90° produce
*irregularly* spaced lines, while grid squares inside rooms produce clusters of
*regularly* spaced lines at the grid pitch. Even if wall edges outnumber grid edges,
the regularity signal survives because we count matching-spacing lines rather than
requiring all lines to be regular.

### Step 7: Classify by peak pattern + spacing

**Grid map detection:**

Square grids produce lines at two characteristic angle families separated by ~90°.

Detection rule:
1. The two strongest peaks are within ±`GRID_ANGLE_TOLERANCE` (8°) of any rotation
   of the (0°, 90°) pair
2. **Both** angles have ≥ `MIN_REGULAR_LINES` (4) lines at the same grid spacing
   (gap sizes within ±20% of each other)
3. The grid spacing for horizontal and vertical lines is similar (within ±30%),
   confirming square cells

Also check for 45°-rotated grids (isometric): peaks near (45°, 135°).

**Hex map detection:**

Hex grids produce lines at three characteristic angle families separated by ~60°:
- Family A: ~0° (horizontal)
- Family B: ~60°
- Family C: ~120°

Detection rule:
1. The three strongest peaks are within ±`HEX_ANGLE_TOLERANCE` (8°) of any rotation
   of the (0°, 60°, 120°) triplet
2. At least 2 of the 3 angle families have ≥ `MIN_REGULAR_LINES` (4) regularly
   spaced lines
3. At least 2 of the 3 peaks have normalized strength ≥ `MIN_HEX_PEAK` (0.30)

The "any rotation" check handles hex grids printed at non-standard orientations
(e.g., pointy-top vs flat-top hexes shift the triplet by 30°).

**Non-map fallback:**

If neither hex nor grid pattern is detected, classify as **NON_MAP**.

### Confidence scoring

```
confidence = peak_pattern_score * 0.4
           + spacing_regularity_score * 0.4
           + coverage_score * 0.2
```

Where:
- `peak_pattern_score`: average normalized strength of the matched peaks (0–1)
- `spacing_regularity_score`: fraction of detected lines at dominant angles that
  match the grid spacing (0–1)
- `coverage_score`: fraction of the page area that contains grid lines, estimated
  from the spatial extent of regularly-spaced line segments (0–1). Partial-coverage
  dungeon maps will score lower here but still classify correctly via the other terms.

---

## Data Model

```java
public enum MapType {
    HEX_MAP,
    GRID_MAP,
    NON_MAP
}

public record MapClassification(
    MapType type,
    double  confidence,       // 0.0–1.0
    double[] dominantAngles,  // degrees of detected peaks
    double  gridSpacing       // average grid spacing in pixels (0 if NON_MAP)
) {}
```

---

## New Class: `MapClassifier`

Location: `com.dnd.processor.converters.MapClassifier`

```java
public class MapClassifier {

    /**
     * Classifies a rasterised MAP page as hex, grid, or non-map.
     *
     * @param pageImage  the full-page raster (150 DPI)
     * @param margins    content margins from ProjectionAnalyzer
     * @return classification result with confidence and detected angles
     */
    public MapClassification classify(BufferedImage pageImage, Margins margins) { ... }
}
```

Internal methods:
- `int[][] houghTransform(boolean[] edges, int w, int h)` — accumulator
- `double[] angleHistogram(int[][] accumulator)` — normalized 180-bin histogram
- `List<Peak> findPeaks(double[] histogram)` — peak detection with merging
- `MapType matchPattern(List<Peak> peaks)` — hex/grid/non-map classification
- `double spacingRegularity(int[][] accumulator, double angle, int threshold)` — CV of line gaps

---

## Diagnostic Output

The annotated PNG overlay for each MAP page should include:

1. **Original page image** (dimmed to 40% opacity)
2. **Detected Hough lines** — colour-coded by angle family:
   - Red: 0° ± tolerance
   - Green: 60° ± tolerance (or 90° for grid)
   - Blue: 120° ± tolerance
3. **Classification label** in top-left corner: `HEX_MAP (0.82)` or `GRID_MAP (0.91)`
4. **Angle histogram** rendered as a small bar chart in the bottom-right corner

---

## Configuration

Add to the existing config record pattern:

| Parameter              | Default | Description                                    |
|------------------------|---------|------------------------------------------------|
| `houghVoteFraction`    | 0.15    | Min votes as fraction of min(w,h)              |
| `minPeakStrength`      | 0.25    | Min normalized histogram value for a peak      |
| `peakMergeWindow`      | 3       | Degrees within which peaks are merged          |
| `hexAngleTolerance`    | 8       | Degrees of tolerance for hex triplet matching  |
| `gridAngleTolerance`   | 8       | Degrees of tolerance for grid pair matching    |
| `minHexPeak`           | 0.30    | Min peak strength for hex classification       |
| `minGridPeak`          | 0.35    | Min peak strength for grid classification      |
| `maxSpacingCV`         | 0.35    | Max coefficient of variation for regular grid  |
| `minRegularLines`      | 4       | Min parallel lines needed for spacing check    |
| `cannyLowThreshold`    | 20      | Canny low threshold for map pages              |
| `cannyHighThreshold`   | 60      | Canny high threshold for map pages             |

---

## Integration with Main Pipeline

### Phase 1 (immediate): Diagnostic phase

Wire `--phase map-detection` into `Main.java` alongside the existing diagnostic phases.
For each page:
1. Rasterise at 150 DPI
2. Run `ProjectionAnalyzer.analyzeLayout()` — skip pages that are not `LayoutType.MAP`
3. Run `MapClassifier.classify()` on MAP pages
4. Write annotated overlay PNG
5. Print console summary

### Phase 2 (future): Pipeline integration

Once tuned, the `MapClassification` result can be:
- Stored in the Markdown frontmatter (`map_pages: [{page: 3, type: hex, confidence: 0.82}]`)
- Used to skip text extraction on confirmed map pages (no useful text to extract)
- Passed through to the JSON output as metadata

---

## Test Cases

| Module                                 | Page | Expected     | Notes                              |
|----------------------------------------|------|--------------|------------------------------------|
| B4 - The Lost City                     | 31   | GRID_MAP     | Three dungeon tiers on one page; grid only inside white rooms, blue solid-fill walls. Partial-coverage grid. |
| B4 - The Lost City                     | 32   | GRID_MAP     | Tier 4 dungeon map + legend key below. Grid inside rooms, large non-grid legend area. |
| B4 - The Lost City                     | 33   | GRID_MAP     | Tier 5 dungeon map; large rooms with visible grid, irregular building outline. |
| B2 - Keep on the Borderlands           | 3    | HEX_MAP      | Outdoor wilderness hex map         |
| B1 - In Search of the Unknown          | 7    | GRID_MAP     | Dungeon floor plan, square grid    |
| X1 - Isle of Dread                     | fold | HEX_MAP      | Large hex overland map             |
| Any module with full-page illustration | —    | NON_MAP      | Cover art, player handout          |

---

## Implementation Plan

| # | File                                          | Change                                              |
|---|-----------------------------------------------|-----------------------------------------------------|
| 1 | `converters/MapClassifier.java`               | New class: Hough transform + classification logic   |
| 2 | `converters/HoughTransform.java`              | New class: reusable Hough line detection            |
| 3 | `model/MapType.java`                          | New enum: HEX_MAP, GRID_MAP, NON_MAP               |
| 4 | `model/MapClassification.java`                | New record: classification result                   |
| 5 | `Main.java`                                   | Add `--phase map-detection` case                    |
| 6 | `converters/PDFPreprocessor.java`             | Add `classifyMaps()` method for diagnostic output   |
| 7 | Config record                                 | Add map-detection tuning parameters                 |
