# Grid Extraction — Spec

## Purpose

After map detection classifies a page as `GRID_MAP` and identifies its sections,
extract a cell-level grid classifying each grid square and its boundaries.
Output a `DungeonMap` JSON file per section.

---

## CLI

```bash
java -jar module-processor.jar --input "B4 - The Lost City.pdf" --phase map-detection
```

Grid extraction runs automatically as part of the map-detection phase.
Output directory: `<baseName>-maps/`, one JSON + diagnostic PNG per section:

```
B4 - The Lost City-maps/
  B4 - The Lost City-page-31-section1.json
  B4 - The Lost City-page-31-section1-grid.png
  ...
```

---

## Cell Encoding

Each cell in the grid is a single integer:

| Value | Meaning                            |
|-------|------------------------------------|
| 0     | FLOOR — open, walkable             |
| 1     | WALL — full cell is solid wall     |
| 2     | FLOOR with door on **north** side  |
| 3     | FLOOR with door on **east** side   |
| 4     | FLOOR with door on **south** side  |
| 5     | FLOOR with door on **west** side   |
| 6     | FLOOR with thin wall on **north**  |
| 7     | FLOOR with thin wall on **east**   |
| 8     | FLOOR with thin wall on **south**  |
| 9     | FLOOR with thin wall on **west**   |

**Doors** and **thin walls** are boundary features stored in **both** adjacent
cells. A horizontal door between cells (5,10) and (5,11):
```
grid[10 * width + 5] = 4   (south door)
grid[11 * width + 5] = 2   (north door)
```

A thin wall (no door) between cells (5,10) and (5,11):
```
grid[10 * width + 5] = 8   (south thin wall)
grid[11 * width + 5] = 6   (north thin wall)
```

Thin walls and doors are mutually exclusive — a boundary is either open,
a thin wall, or a door (a gap in a thin wall).

---

## Background

Classic D&D module maps (TSR B/X/BECMI era) use a consistent visual language:

| Element         | Appearance                                                |
|-----------------|-----------------------------------------------------------|
| **Floor**       | White or light-coloured fill inside rooms, grid lines visible |
| **Wall**        | Solid blue (TSR blue) or black fill                       |
| **Thin wall**   | Dark line on the boundary between two floor cells         |
| **Door**        | Small square crossing the boundary between two cells; has a bright block (the opening) surrounded by dark pixels |
| **Grid lines**  | Thin dark lines within floor areas                        |
| **Labels**      | Dark text/numbers on floor areas (room numbers)           |

---

## Algorithm

### Phase 1: Cell classification (FLOOR / WALL)

For each cell at grid position (col, row):

1. Compute the pixel region: `(col * spacing, row * spacing)` to
   `((col+1) * spacing, (row+1) * spacing)`, clamped to image bounds.

2. Compute brightness statistics:
   - **Median brightness** (50th percentile) — for FLOOR/WALL classification
   - **10th percentile brightness** — for exterior whitespace detection

3. Classify: median >= `FLOOR_THRESHOLD` (180) → FLOOR (0), else WALL (1).

4. Mark as "blank" if 10th percentile >= `BLANK_THRESHOLD` (200) — uniformly
   bright, no grid lines or content. Used for exterior flood-fill.

### Phase 2: Exterior flood-fill

Flood-fill from border cells through "blank" cells only, marking them as WALL.
This removes exterior whitespace without leaking into the dungeon interior —
interior floor cells have grid lines that pull the 10th percentile below the
blank threshold, stopping the fill at room boundaries.

### Phase 3: Boundary analysis (doors and thin walls)

For each boundary between two adjacent cells where **both** cells are FLOOR
(value 0 after phases 1–2):

1. **Sample a boundary strip**: a narrow band of pixels (±15% of grid spacing)
   centred on the cell boundary.

2. **Compute boundary metrics**:
   - `darkFrac`: fraction of pixels in the strip with brightness < 180
   - `brightBlock`: length of the longest contiguous run of bright columns/rows
     (>50% bright pixels per column/row) along the boundary

3. **Classify the boundary**:

   | darkFrac     | brightBlock (as fraction of spacing) | Classification |
   |--------------|--------------------------------------|----------------|
   | < 0.20       | —                                    | OPEN (grid lines only, no feature) |
   | 0.25 – 0.60  | 0.30 – 0.75                          | **DOOR** |
   | 0.60 – 0.90  | < 0.30                               | **THIN WALL** |
   | > 0.90       | —                                    | Treated as WALL (handled by cell classification) |

4. **Write to both cells**: A door on a horizontal boundary between (col, row)
   and (col, row+1) sets `grid[row,col] = 4` (south) and
   `grid[row+1,col] = 2` (north). Vertical boundaries use 3 (east) / 5 (west).

### Boundary direction convention

- **Horizontal boundaries** are between row `r` and row `r+1`:
  - Upper cell gets SOUTH (4 for door, 8 for thin wall)
  - Lower cell gets NORTH (2 for door, 6 for thin wall)

- **Vertical boundaries** are between col `c` and col `c+1`:
  - Left cell gets EAST (3 for door, 7 for thin wall)
  - Right cell gets WEST (5 for door, 9 for thin wall)

### Multiple boundaries per cell

If a cell has features on multiple sides (e.g., door on north AND thin wall
on east), only the first detected feature is stored — the single-int encoding
supports one feature per cell. In practice, this is rare in classic maps where
rooms have few doors relative to their perimeter.

---

## Visual Output

### Grid overlay (diagnostic)

Filename: `<module>-page<NN>-section<S>-grid.png`

1. **Section image** at full resolution
2. **Grid overlay**: semi-transparent coloured cells:
   - FLOOR (0): green at 30% opacity
   - WALL (1): red at 30% opacity
   - Door (2–5): yellow at 40% opacity
   - Thin wall (6–9): orange at 40% opacity
3. **Grid lines**: thin grey lines at the grid spacing

---

## Configuration

| Parameter        | Default | Description                                        |
|------------------|---------|----------------------------------------------------|
| `floorThreshold` | 180     | Brightness threshold for floor/wall classification |
| `blankThreshold` | 200     | 10th-percentile brightness for exterior detection  |
| `doorDarkMin`    | 0.25    | Min darkFrac for door detection                    |
| `doorDarkMax`    | 0.60    | Max darkFrac for door detection                    |
| `doorBlockMin`   | 0.30    | Min brightBlock ratio for door                     |
| `doorBlockMax`   | 0.75    | Max brightBlock ratio for door                     |
| `thinWallDarkMin`| 0.60    | Min darkFrac for thin wall detection               |
| `thinWallDarkMax`| 0.90    | Max darkFrac for thin wall detection               |

---

## Data Model

### DungeonMap

```java
public record DungeonMap(
    int   width,
    int   height,
    int[] grid       // row-major: grid[row * width + col]
) {
    public static final int FLOOR          = 0;
    public static final int WALL           = 1;
    public static final int DOOR_NORTH     = 2;
    public static final int DOOR_EAST      = 3;
    public static final int DOOR_SOUTH     = 4;
    public static final int DOOR_WEST      = 5;
    public static final int THIN_WALL_NORTH = 6;
    public static final int THIN_WALL_EAST  = 7;
    public static final int THIN_WALL_SOUTH = 8;
    public static final int THIN_WALL_WEST  = 9;
}
```

---

## Test Cases

| Module              | Page | Section | Expected                                        |
|---------------------|------|---------|-------------------------------------------------|
| B4 - The Lost City  | 31   | 1       | Tier 1: rooms 1/1a, doors between rooms         |
| B4 - The Lost City  | 31   | 2       | Tier 2: rooms 2-12, thin walls and doors        |
| B4 - The Lost City  | 31   | 3       | Tier 3: rooms 13-24, many doors visible         |
| B4 - The Lost City  | 33   | 1       | Tier 5: single large dungeon                    |

Validation: visual inspection of grid overlay PNG — doors should be yellow,
thin walls orange, at locations matching the original map.
