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
- `List<MapSection> detectSections(BufferedImage cropped, double gridSpacing)` — content projection profile section detection
- `boolean[] buildContentMask(BufferedImage image)` — grayscale threshold to content mask
- `int[] horizontalProjection(boolean[] mask, int w, int h, int x1, int x2)` — content density per row
- `int[] verticalProjection(boolean[] mask, int w, int h, int y1, int y2)` — content density per column
- `List<int[]> findGaps(int[] projection, int extent, double densityThreshold, int minWidth)` — whitespace runs in a projection

---

## Visual Output

### Annotated overlay (diagnostic)

One annotated PNG per MAP page for the user to visually verify detection results.
Filename: `<module>-page<NN>-annotated.png`

Contents:
1. **Original page image** (dimmed to 40% opacity)
2. **Detected Hough lines** — colour-coded by angle family:
   - Red: 0° ± tolerance
   - Green: 60° ± tolerance (or 90° for grid)
   - Blue: 120° ± tolerance
3. **Classification label** in top-left corner: `GRID_MAP (0.93)`
4. **Section bounding boxes** — for multi-section pages, draw a distinct coloured
   rectangle around each detected section's grid area. Use a rotating palette:
   - Section 1: red
   - Section 2: green
   - Section 3: blue
   - Section 4+: cycle
   Each box has a label in its corner: `Section 1: 24×10`
5. **Angle histogram** rendered as a small bar chart in the bottom-right corner

### Section crops (for further processing)

For each detected section in a `GRID_MAP`, output a separate cropped image
containing just that section's grid area. These are full-resolution crops from the
original page raster (not the dimmed overlay), suitable for downstream processing
(VTT export, OCR of room labels, etc.).

Filename: `<module>-page<NN>-section<S>.png`

Examples for B4 page 31 (3 tiers):
```
B4-page31-annotated.png     ← full page with coloured bounding boxes
B4-page31-section1.png      ← Tier 1 grid area only
B4-page31-section2.png      ← Tier 2 grid area only
B4-page31-section3.png      ← Tier 3 grid area only
```

For single-section pages (e.g., B4 page 33), only one section crop is produced:
```
B4-page33-annotated.png
B4-page33-section1.png
```

Crop boundaries: Use the rho extent of the regular lines on both axes to define
the bounding rectangle, padded by half a grid spacing on each side to include the
outermost grid cell borders. Clamp to the content margins so the crop doesn't
extend into page borders.

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

## Grid Dimensions (Size in Squares)

For pages classified as `GRID_MAP`, compute the overall map size in grid squares
(width × height). This tells us how large the dungeon map is, which is useful for
downstream processing (e.g., VTT export, scaling calculations).

### Approach

The `analyseSpacing()` method already collects rho values of lines near each dominant
angle and identifies the dominant grid spacing. To compute dimensions:

1. **Track rho extent**: After clustering gaps and identifying the regularly-spaced
   lines, compute the rho extent = `max(rho) - min(rho)` of only the lines whose
   gaps matched the dominant spacing cluster (±20% tolerance). This excludes outlier
   lines from wall edges or page borders that aren't part of the grid.

2. **Compute dimension**: `extent / candidateSpacing` gives the number of grid
   squares along that axis, rounded to the nearest integer.

3. **Map the angles to width/height**:
   - Lines at the **horizontal** angle (θ ≈ 0°) are horizontal lines — their rho
     values measure vertical position → rho extent gives the **height** in squares
   - Lines at the **vertical** angle (θ ≈ 90°) are vertical lines — their rho
     values measure horizontal position → rho extent gives the **width** in squares

### Data model changes

Extend `SpacingResult` with a `rhoExtent` field:

```java
record SpacingResult(int regularLineCount, double candidateSpacing, double cv,
                     double rhoExtent) {}
```

Extend `MapClassification` with grid dimensions:

```java
public record MapClassification(
    MapType type,
    double  confidence,
    double[] dominantAngles,
    double  gridSpacing,
    int     gridWidthSquares,   // 0 if not GRID_MAP
    int     gridHeightSquares   // 0 if not GRID_MAP
) {}
```

### Angle-to-axis mapping

The Hough convention `rho = x·cos(θ) + y·sin(θ)` means:
- θ = 0° → cos=1, sin=0 → rho ≈ x → **vertical lines** → rho extent = **width**
- θ = 90° → cos=0, sin=1 → rho ≈ y → **horizontal lines** → rho extent = **height**

For a grid detected at rotation `r`:
- Angle `r` (first family, e.g. 0°) → rho ≈ x → **width** axis
- Angle `r + 90` (second family, e.g. 90°) → rho ≈ y → **height** axis

### Edge cases

- **Partial-coverage grids**: The rho extent only covers the portion of the page
  where regularly-spaced grid lines were detected. For dungeon maps where the grid
  doesn't span the full page, this gives the bounding box of the actual dungeon
  content, which is the useful measurement.

- **Non-grid maps**: `gridWidthSquares` and `gridHeightSquares` are 0 for
  `HEX_MAP` and `NON_MAP` classifications.

### Console output

```
Page 31: MAP → GRID_MAP  (score: 0.93, angles: 0°/90°, spacing: 37.0 px, grid: 24×32)
```

---

## Multi-Level Detection

Many classic D&D module pages pack multiple dungeon levels onto a single page.
For example, B4 page 31 has Tiers 1 and 2 side-by-side in the upper half, with
Tier 3 spanning the full width below — three separate dungeon maps with no
connecting grid lines or corridors between them.

### Goal

Detect when a `GRID_MAP` page contains multiple distinct map sections (levels or
locations) and report the count, individual dimensions, and pixel bounds of each.

### Approach: Content projection profiles

The previous approach attempted to find section breaks by looking for gaps in
Hough rho values (line positions in parameter space). This is unreliable because:
- Hough lines are **global** — rho is a 1D projection that loses spatial locality
- Partial-coverage grids produce sparse, noisy rho values
- Wall edges at similar rho values fill in gaps between tiers
- Recovering 2D spatial separation from 1D frequency-domain parameters is fragile

The better approach works **directly in image space**: once we know a page is a
grid map, the tiers/levels are separated by **whitespace bands** visible in the
pixel data. This is the same projection profile technique that `ProjectionAnalyzer`
already uses for page margins and zone splitting.

**Algorithm:**

1. **Build content mask** from the cropped page image:
   a. Convert to grayscale.
   b. Threshold: any pixel with brightness < `CONTENT_THRESHOLD` (230) is "content."
   c. This captures all map ink — walls, grid lines, solid fills, labels, legends.

2. **Horizontal projection → row splits:**
   a. For each row y, compute `density[y]` = (content pixels in row) / width.
   b. Find consecutive runs where density < `GAP_DENSITY_THRESHOLD` (2%).
   c. Discard runs narrower than `gridSpacing × MIN_SECTION_GAP_FACTOR` (2.0×).
      This ensures only real tier separators qualify, not thin whitespace between
      adjacent rooms.
   d. The remaining runs are **horizontal section breaks**. They split the page
      into horizontal bands.

3. **Per-band vertical projection → column splits:**
   a. For each horizontal band, compute a vertical projection restricted to that
      band's y-range: for each column x, `density[x]` = (content pixels in column
      within the band) / band_height.
   b. Find consecutive runs where density < `GAP_DENSITY_THRESHOLD` (2%).
   c. Discard runs narrower than `gridSpacing × MIN_SECTION_GAP_FACTOR` (2.0×).
   d. The remaining runs are **vertical section breaks** within this band.
   e. A vertical gap may exist in one band but not another — this is handled
      naturally because each band's projection is computed independently.
      (e.g., B4 p31: vertical gap between Tiers 1+2 in the top band, but no
      gap in the full-width Tier 3 in the bottom band.)

4. **Build sections**: Each (band, column) cell becomes a `MapSection` with:
   - Pixel bounds (x, y, width, height) relative to the cropped content area,
     taken directly from the band/column boundaries
   - Grid dimensions (width/height in squares) computed by dividing the pixel
     extent by gridSpacing from the Hough analysis

### Why projection profiles are robust

| Factor                  | Rho-gap approach (old)               | Projection approach (new)                |
|-------------------------|--------------------------------------|------------------------------------------|
| Works in                | Hough parameter space (indirect)     | Image pixel space (direct)               |
| Partial-coverage grids  | Sparse, noisy rho values             | All ink contributes, not just grid lines |
| Solid fills (blue walls)| Invisible (no grid lines inside fill)| Visible as content pixels                |
| Labels / legends        | Noise in Hough space                 | Naturally included as content            |
| Proven technique        | No                                   | Yes — ProjectionAnalyzer uses it already |

The core advantage is directness: whitespace between tiers appears as a clear
valley in the projection profile regardless of whether grid lines, walls, or
fills are present. The rho-gap approach required grid lines to be detected first,
then tried to infer spatial separation from their parameters — an inherently
lossy transformation.

### Minimum gap width

The `MIN_SECTION_GAP_FACTOR` of 2.0× (= 1 empty grid square of whitespace) is
lower than the previous 5.0× because the projection approach doesn't suffer from
the same false-positive problem. With rho gaps, wall edges produced spurious large
gaps that needed a high threshold to filter. With projection profiles, a whitespace
band is unambiguous — if the density is below 2% across a strip wider than 2 grid
squares, it's a real section break.

Within a single dungeon level, there is no whitespace band this wide — even
corridors and wide open areas contain wall outlines and grid lines that raise
the content density well above 2%.

### Data model

```java
record SpacingResult(
    int regularLineCount,
    double candidateSpacing,
    double cv,
    double rhoExtent
) {}

/**
 * One distinct map section detected on the page.
 * Pixel bounds are relative to the cropped content area.
 */
public record MapSection(
    int widthSquares,
    int heightSquares,
    int pixelX,     // x-offset within cropped content area
    int pixelY,     // y-offset within cropped content area
    int pixelW,     // width in pixels
    int pixelH      // height in pixels
) {}

public record MapClassification(
    MapType type,
    double  confidence,
    double[] dominantAngles,
    double  gridSpacing,
    int     gridWidthSquares,    // total grid width (0 if not GRID_MAP)
    int     gridHeightSquares,   // total grid height (0 if not GRID_MAP)
    List<MapSection> sections    // individual map sections (1 if single map)
) {}
```

### Configuration

| Parameter               | Default | Description                                              |
|-------------------------|---------|----------------------------------------------------------|
| `minSectionGapFactor`   | 2.0     | Minimum whitespace gap width as multiple of grid spacing |
| `contentThreshold`      | 230     | Brightness threshold (0–255) for content mask            |
| `gapDensityThreshold`   | 0.02    | Max content density in a strip to qualify as a gap (2%)  |

The 2.0× gap factor means a whitespace band must be at least 2 grid squares wide
to qualify as a section break. This is sufficient because the projection approach
identifies true whitespace (density < 2%) rather than rho-space gaps that could be
caused by wall edges.

### Console output

Single map:
```
Page 33: MAP → GRID_MAP  (score: 0.95, spacing: 29.5 px, grid: 63×42, 1 section)
```

Multi-level:
```
Page 31: MAP → GRID_MAP  (score: 0.93, spacing: 37.0 px, grid: 44×38, 3 sections: 6×13, 15×13, 44×17)
```

### Test expectations

| Page     | Expected sections | Notes                                       |
|----------|-------------------|---------------------------------------------|
| B4 p31   | 3                 | Tiers 1+2 side-by-side (top row), Tier 3 full-width (bottom row) |
| B4 p32   | 1 or 2            | Tier 4 + legend (legend may not have grid)   |
| B4 p33   | 1                 | Single Tier 5 dungeon                        |
| B1 p02   | 1                 | Single dungeon level                         |
| B1 p03   | 1                 | Single cave level (large internal gaps corroborated as walls) |

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
