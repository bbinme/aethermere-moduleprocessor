package com.dnd.processor.converters;

import com.dnd.processor.model.DungeonMap;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

/**
 * Extracts a floor/wall grid from a rasterised dungeon map section.
 *
 * For each grid cell, samples pixel brightnesses and classifies the cell
 * as FLOOR (bright, median >= threshold) or WALL (dark, median < threshold).
 * Median is robust against grid lines and room labels — sparse dark pixels
 * on a bright floor don't shift the median below the threshold.
 */
public class GridExtractor {

    /**
     * 10th-percentile brightness threshold for "blank" (exterior whitespace) cells.
     * Interior floor cells have grid lines at ~80-120 brightness, pulling the
     * 10th percentile well below this. Exterior whitespace is uniformly bright.
     */
    private static final int BLANK_THRESHOLD = 200;

    private final int floorThreshold;

    public GridExtractor(int floorThreshold) {
        this.floorThreshold = floorThreshold;
    }

    /**
     * Extracts a floor/wall grid from a map section image.
     *
     * @param sectionImage  the cropped section raster
     * @param widthSquares  grid width in squares
     * @param heightSquares grid height in squares
     * @param gridSpacing   grid spacing in pixels
     * @return a DungeonMap with classified cells
     */
    public DungeonMap extract(BufferedImage sectionImage,
                              int widthSquares, int heightSquares,
                              double gridSpacing) {
        int imgW = sectionImage.getWidth();
        int imgH = sectionImage.getHeight();
        int totalCells = widthSquares * heightSquares;
        int[] grid = new int[totalCells];
        boolean[] blank = new boolean[totalCells];

        for (int row = 0; row < heightSquares; row++) {
            for (int col = 0; col < widthSquares; col++) {
                int px = (int) (col * gridSpacing);
                int py = (int) (row * gridSpacing);
                int pw = (int) Math.min(gridSpacing, imgW - px);
                int ph = (int) Math.min(gridSpacing, imgH - py);
                if (pw <= 0 || ph <= 0) {
                    grid[row * widthSquares + col] = DungeonMap.WALL;
                    continue;
                }

                int[] stats = brightnessStats(sectionImage, px, py, pw, ph);
                int median = stats[0];
                int pct10  = stats[1];

                grid[row * widthSquares + col] =
                        median >= floorThreshold ? DungeonMap.FLOOR : DungeonMap.WALL;

                // A cell is "blank" (exterior whitespace candidate) if even its
                // darkest pixels are bright — no grid lines, labels, or wall
                // fragments. Interior floor has grid lines (~80-120 brightness)
                // that pull the 10th percentile well below BLANK_THRESHOLD.
                blank[row * widthSquares + col] = pct10 >= BLANK_THRESHOLD;
            }
        }

        // Flood-fill from edges through blank cells only — exterior whitespace
        // that doesn't leak through grid-lined interior floor.
        fillExterior(grid, blank, widthSquares, heightSquares);

        // Boundary analysis: detect doors and thin walls between adjacent floor cells
        analyzeBoundaries(sectionImage, grid, widthSquares, heightSquares, gridSpacing);

        return new DungeonMap(widthSquares, heightSquares, grid);
    }

    /**
     * Renders a diagnostic overlay showing the extracted grid on top of the
     * section image. Floor cells are tinted green, wall cells are tinted red.
     */
    public BufferedImage renderOverlay(BufferedImage sectionImage, DungeonMap map,
                                       double gridSpacing) {
        int w = sectionImage.getWidth();
        int h = sectionImage.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();

        // Draw original image
        g.drawImage(sectionImage, 0, 0, null);

        // Overlay cells
        Color floorColor    = new Color(0, 200, 0, 75);    // green 30%
        Color wallColor     = new Color(200, 0, 0, 75);     // red 30%
        Color doorColor     = new Color(255, 220, 0, 100);   // yellow 40%
        Color thinWallColor = new Color(255, 140, 0, 100);   // orange 40%

        for (int row = 0; row < map.height(); row++) {
            for (int col = 0; col < map.width(); col++) {
                int px = (int) (col * gridSpacing);
                int py = (int) (row * gridSpacing);
                int pw = (int) Math.min(gridSpacing, w - px);
                int ph = (int) Math.min(gridSpacing, h - py);
                if (pw <= 0 || ph <= 0) continue;

                int cell = map.grid()[row * map.width() + col];
                Color c;
                if (cell == DungeonMap.WALL) c = wallColor;
                else if (cell >= DungeonMap.DOOR_NORTH && cell <= DungeonMap.DOOR_WEST) c = doorColor;
                else if (cell >= DungeonMap.THIN_WALL_NORTH && cell <= DungeonMap.THIN_WALL_WEST) c = thinWallColor;
                else c = floorColor;
                g.setColor(c);
                g.fillRect(px, py, pw, ph);
            }
        }

        // Draw grid lines
        g.setColor(new Color(128, 128, 128, 100));
        g.setStroke(new BasicStroke(1f));
        for (int col = 0; col <= map.width(); col++) {
            int x = (int) (col * gridSpacing);
            if (x <= w) g.drawLine(x, 0, x, h);
        }
        for (int row = 0; row <= map.height(); row++) {
            int y = (int) (row * gridSpacing);
            if (y <= h) g.drawLine(0, y, w, y);
        }

        g.dispose();
        return out;
    }

    // ── Boundary analysis ─────────────────────────────────────────────────

    /** Boundary strip extends ±15% of grid spacing around the cell edge. */
    private static final double STRIP_FRACTION = 0.15;

    // Door detection thresholds (from empirical analysis of B4 maps)
    private static final double DOOR_DARK_MIN  = 0.25;
    private static final double DOOR_DARK_MAX  = 0.60;
    private static final double DOOR_BLOCK_MIN = 0.30; // bright block as fraction of spacing
    private static final double DOOR_BLOCK_MAX = 0.75;

    // Thin wall detection thresholds
    private static final double THIN_WALL_DARK_MIN = 0.60;
    private static final double THIN_WALL_DARK_MAX = 0.90;

    /**
     * Scans all boundaries between adjacent FLOOR cells and classifies them
     * as doors or thin walls based on pixel content in the boundary strip.
     */
    private void analyzeBoundaries(BufferedImage img, int[] grid,
                                    int gridW, int gridH, double spacing) {
        int imgW = img.getWidth();
        int imgH = img.getHeight();
        int stripHalf = Math.max(1, (int) (spacing * STRIP_FRACTION));

        // Horizontal boundaries (between row and row+1)
        for (int row = 0; row < gridH - 1; row++) {
            for (int col = 0; col < gridW; col++) {
                int idxAbove = row * gridW + col;
                int idxBelow = (row + 1) * gridW + col;
                if (grid[idxAbove] != DungeonMap.FLOOR || grid[idxBelow] != DungeonMap.FLOOR)
                    continue;

                int boundaryY = (int) ((row + 1) * spacing);
                int y1 = Math.max(0, boundaryY - stripHalf);
                int y2 = Math.min(imgH, boundaryY + stripHalf);
                int x1 = (int) (col * spacing);
                int x2 = (int) Math.min((col + 1) * spacing, imgW);
                if (y1 >= y2 || x1 >= x2) continue;

                int boundaryType = classifyBoundary(img, x1, y1, x2, y2, true, spacing);
                if (boundaryType == 1) { // door
                    grid[idxAbove] = DungeonMap.DOOR_SOUTH;
                    grid[idxBelow] = DungeonMap.DOOR_NORTH;
                } else if (boundaryType == 2) { // thin wall
                    grid[idxAbove] = DungeonMap.THIN_WALL_SOUTH;
                    grid[idxBelow] = DungeonMap.THIN_WALL_NORTH;
                }
            }
        }

        // Vertical boundaries (between col and col+1)
        for (int row = 0; row < gridH; row++) {
            for (int col = 0; col < gridW - 1; col++) {
                int idxLeft  = row * gridW + col;
                int idxRight = row * gridW + col + 1;
                if (grid[idxLeft] != DungeonMap.FLOOR || grid[idxRight] != DungeonMap.FLOOR)
                    continue;

                int boundaryX = (int) ((col + 1) * spacing);
                int x1 = Math.max(0, boundaryX - stripHalf);
                int x2 = Math.min(imgW, boundaryX + stripHalf);
                int y1 = (int) (row * spacing);
                int y2 = (int) Math.min((row + 1) * spacing, imgH);
                if (y1 >= y2 || x1 >= x2) continue;

                int boundaryType = classifyBoundary(img, x1, y1, x2, y2, false, spacing);
                if (boundaryType == 1) { // door
                    grid[idxLeft]  = DungeonMap.DOOR_EAST;
                    grid[idxRight] = DungeonMap.DOOR_WEST;
                } else if (boundaryType == 2) { // thin wall
                    grid[idxLeft]  = DungeonMap.THIN_WALL_EAST;
                    grid[idxRight] = DungeonMap.THIN_WALL_WEST;
                }
            }
        }
    }

    /**
     * Classifies a boundary strip as open (0), door (1), or thin wall (2).
     *
     * @param horizontal true for horizontal boundary (project along x), false for vertical
     */
    private int classifyBoundary(BufferedImage img, int x1, int y1, int x2, int y2,
                                  boolean horizontal, double spacing) {
        int w = x2 - x1;
        int h = y2 - y1;
        int darkCount = 0;
        int totalPixels = 0;

        // Project along the boundary axis to find bright blocks
        int projLen = horizontal ? w : h;
        int[] projBright = new int[projLen];
        int[] projTotal = new int[projLen];

        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                int rgb = img.getRGB(x1 + dx, y1 + dy);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int brightness = (r * 299 + g * 587 + b * 114) / 1000;

                totalPixels++;
                if (brightness < floorThreshold) darkCount++;

                int projIdx = horizontal ? dx : dy;
                projTotal[projIdx]++;
                if (brightness >= floorThreshold) projBright[projIdx]++;
            }
        }

        if (totalPixels == 0) return 0;
        double darkFrac = (double) darkCount / totalPixels;

        // Find longest contiguous bright run
        int longestBright = 0;
        int currentBright = 0;
        for (int i = 0; i < projLen; i++) {
            if (projTotal[i] > 0 && (double) projBright[i] / projTotal[i] > 0.5) {
                currentBright++;
                longestBright = Math.max(longestBright, currentBright);
            } else {
                currentBright = 0;
            }
        }
        double blockRatio = longestBright / spacing;

        // Classify
        if (darkFrac < 0.20) return 0; // OPEN — just grid lines
        if (darkFrac >= DOOR_DARK_MIN && darkFrac <= DOOR_DARK_MAX
                && blockRatio >= DOOR_BLOCK_MIN && blockRatio <= DOOR_BLOCK_MAX) {
            return 1; // DOOR
        }
        if (darkFrac >= THIN_WALL_DARK_MIN && darkFrac <= THIN_WALL_DARK_MAX
                && blockRatio < DOOR_BLOCK_MIN) {
            return 2; // THIN WALL
        }
        return 0; // default: open
    }

    /**
     * Flood-fills from border cells through "blank" FLOOR cells and marks them
     * as WALL. Only propagates through cells that are both FLOOR and blank
     * (uniformly bright), so the fill stops at interior floor cells that have
     * grid lines or other content.
     */
    private static void fillExterior(int[] grid, boolean[] blank, int w, int h) {
        boolean[] visited = new boolean[w * h];
        Queue<Integer> queue = new ArrayDeque<>();

        // Seed with all blank FLOOR cells on the four borders
        for (int col = 0; col < w; col++) {
            seedIfBlank(grid, blank, visited, queue, w, col, 0);
            seedIfBlank(grid, blank, visited, queue, w, col, h - 1);
        }
        for (int row = 1; row < h - 1; row++) {
            seedIfBlank(grid, blank, visited, queue, w, 0, row);
            seedIfBlank(grid, blank, visited, queue, w, w - 1, row);
        }

        // BFS — 4-connected neighbours, only through blank FLOOR cells
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int col = idx % w;
            int row = idx / w;
            grid[idx] = DungeonMap.WALL;

            if (col > 0)     visitBlank(grid, blank, visited, queue, w, col - 1, row);
            if (col < w - 1) visitBlank(grid, blank, visited, queue, w, col + 1, row);
            if (row > 0)     visitBlank(grid, blank, visited, queue, w, col, row - 1);
            if (row < h - 1) visitBlank(grid, blank, visited, queue, w, col, row + 1);
        }
    }

    private static void seedIfBlank(int[] grid, boolean[] blank, boolean[] visited,
                                     Queue<Integer> queue, int w, int col, int row) {
        int idx = row * w + col;
        if (grid[idx] == DungeonMap.FLOOR && blank[idx] && !visited[idx]) {
            visited[idx] = true;
            queue.add(idx);
        }
    }

    private static void visitBlank(int[] grid, boolean[] blank, boolean[] visited,
                                    Queue<Integer> queue, int w, int col, int row) {
        int idx = row * w + col;
        if (!visited[idx] && grid[idx] == DungeonMap.FLOOR && blank[idx]) {
            visited[idx] = true;
            queue.add(idx);
        }
    }

    /**
     * Computes brightness statistics for a rectangular pixel region.
     *
     * @return int[2]: [0] = median brightness, [1] = 10th percentile brightness
     */
    private static int[] brightnessStats(BufferedImage img, int x, int y, int w, int h) {
        int[] values = new int[w * h];
        int idx = 0;
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                int rgb = img.getRGB(x + dx, y + dy);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                values[idx++] = (r * 299 + g * 587 + b * 114) / 1000;
            }
        }
        Arrays.sort(values, 0, idx);
        int median = values[idx / 2];
        int pct10  = values[idx / 10]; // 10th percentile
        return new int[]{median, pct10};
    }
}
