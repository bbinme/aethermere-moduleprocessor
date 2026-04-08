package com.dnd.processor.converters;

import com.dnd.processor.config.DnD_BasicSet;
import com.dnd.processor.model.MapClassification;
import com.dnd.processor.model.MapSection;
import com.dnd.processor.model.MapType;

import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Classifies a rasterised MAP page as hex map, grid map, or non-map image.
 *
 * Algorithm:
 *   1. Crop to content margins
 *   2. Canny edge detection (low thresholds for thin grid lines)
 *   3. Hough line transform
 *   4. Angle histogram → peak detection
 *   5. Spacing regularity analysis at dominant angles
 *   6. Classify by peak pattern + spacing
 */
public class MapClassifier {

    /** Minimum grid spacing in pixels at 150 DPI (~0.07 in). Rejects text-line false positives. */
    private static final double MIN_GRID_SPACING_PX = 10.0;

    /** Maximum lines to keep after Hough extraction (prevents histogram saturation). */
    private static final int MAX_LINES = 500;

    /** Non-maximum suppression radius in rho pixels for Hough line extraction. */
    private static final int NMS_RHO_RADIUS = 5;

    /** A peak in the angle histogram. */
    record Peak(int angle, double strength) {}

    /** Spacing analysis result for one angle family. */
    record SpacingResult(int regularLineCount, double candidateSpacing, double cv,
                         double rhoExtent) {}

    private final DnD_BasicSet config;

    public MapClassifier(DnD_BasicSet config) {
        this.config = config;
    }

    /**
     * Classifies a rasterised MAP page.
     *
     * @param pageImage full-page raster (150 DPI)
     * @param margins   content margins from ProjectionAnalyzer
     */
    public MapClassification classify(BufferedImage pageImage,
                                       ProjectionAnalyzer.Margins margins) {
        // Step 1: crop to content area
        int cx = margins.left();
        int cy = margins.top();
        int cw = margins.right() - margins.left();
        int ch = margins.bottom() - margins.top();
        if (cw <= 0 || ch <= 0) {
            return new MapClassification(MapType.NON_MAP, 0, new double[0], 0);
        }
        BufferedImage cropped = pageImage.getSubimage(cx, cy, cw, ch);

        // Step 2: Canny edge detection
        CannyEdgeDetector canny = new CannyEdgeDetector();
        BufferedImage edgeImage = canny.detect(cropped,
                config.mapCannyLowThreshold(), config.mapCannyHighThreshold());
        boolean[] edges = toBooleanEdges(edgeImage, cw, ch);

        // Step 3: Hough transform
        HoughTransform hough = new HoughTransform();
        int[][] acc = hough.accumulate(edges, cw, ch);
        int rhoOffset = HoughTransform.diagonal(cw, ch);

        int minVotes = (int) (Math.min(cw, ch) * config.houghVoteFraction());

        // Step 4: extract lines. Use NMS along the rho axis to suppress broad
        // vote humps in dense images, with a fallback to plain extraction + cap
        // when NMS is too aggressive (sparse grid maps with closely-spaced lines).
        List<HoughTransform.Line> allLines = extractWithNMS(acc, minVotes, rhoOffset);
        MapClassification nmsResult = classifyFromLines(allLines, edges, cw, ch, cropped);
        if (nmsResult.type() != MapType.NON_MAP) return nmsResult;

        // Fallback: plain extraction with vote-count cap
        allLines = hough.extractLines(acc, minVotes, rhoOffset);
        if (allLines.size() > MAX_LINES) {
            allLines.sort(Comparator.comparingInt(HoughTransform.Line::votes).reversed());
            allLines = new ArrayList<>(allLines.subList(0, MAX_LINES));
        }
        return classifyFromLines(allLines, edges, cw, ch, cropped);
    }

    /** Classifies from a list of already-extracted lines. */
    private MapClassification classifyFromLines(List<HoughTransform.Line> allLines,
                                                 boolean[] edges, int edgeW, int edgeH,
                                                 BufferedImage cropped) {

        // Build histogram: count of detected lines per 1° bin, normalised to [0,1]
        double[] histogram = new double[180];
        for (HoughTransform.Line l : allLines) {
            int t = ((int) l.theta()) % 180;
            histogram[t]++;
        }
        double maxBin = 0;
        for (double v : histogram) if (v > maxBin) maxBin = v;
        if (maxBin > 0) for (int i = 0; i < 180; i++) histogram[i] /= maxBin;

        List<Peak> peaks = findPeaks(histogram);

        // Step 6: classify
        MapClassification hexResult = tryHexClassification(peaks, allLines);
        if (hexResult != null) return hexResult;

        MapClassification gridResult = tryGridClassification(peaks, allLines, cropped);
        if (gridResult != null) return gridResult;

        // Fallback: non-map
        double bestPeak = peaks.isEmpty() ? 0 : peaks.get(0).strength();
        return new MapClassification(MapType.NON_MAP, bestPeak * 0.3, new double[0], 0);
    }

    // ── NMS line extraction ────────────────────────────────────────────────────

    /** Extracts lines using non-maximum suppression along the rho axis. */
    private List<HoughTransform.Line> extractWithNMS(int[][] acc, int minVotes, int rhoOffset) {
        List<HoughTransform.Line> lines = new ArrayList<>();
        for (int t = 0; t < 180; t++) {
            int[] row = acc[t];
            for (int ri = 0; ri < row.length; ri++) {
                if (row[ri] < minVotes) continue;
                boolean isMax = true;
                for (int d = 1; d <= NMS_RHO_RADIUS; d++) {
                    if (ri - d >= 0 && row[ri - d] >= row[ri]) { isMax = false; break; }
                    if (ri + d < row.length && row[ri + d] >= row[ri]) { isMax = false; break; }
                }
                if (isMax) {
                    lines.add(new HoughTransform.Line(t, ri - rhoOffset, row[ri]));
                }
            }
        }
        if (lines.size() > MAX_LINES) {
            lines.sort(Comparator.comparingInt(HoughTransform.Line::votes).reversed());
            lines = new ArrayList<>(lines.subList(0, MAX_LINES));
        }
        return lines;
    }

    // ── Peak detection ───────────────────────────────────────────────────────

    private List<Peak> findPeaks(double[] histogram) {
        List<Peak> raw = new ArrayList<>();
        int mergeWindow = config.peakMergeWindow();

        for (int t = 0; t < 180; t++) {
            if (histogram[t] < config.minPeakStrength()) continue;
            // Local maximum check
            boolean isMax = true;
            for (int d = 1; d <= mergeWindow; d++) {
                if (histogram[wrapAngle(t - d)] >= histogram[t] ||
                    histogram[wrapAngle(t + d)] >= histogram[t]) {
                    isMax = false;
                    break;
                }
            }
            if (isMax) raw.add(new Peak(t, histogram[t]));
        }

        // Sort by strength descending
        raw.sort(Comparator.comparingDouble(Peak::strength).reversed());
        return raw;
    }

    // ── Hex classification ───────────────────────────────────────────────────

    private MapClassification tryHexClassification(List<Peak> peaks,
                                                    List<HoughTransform.Line> lines) {
        if (peaks.size() < 3) return null;

        int tolerance = config.hexAngleTolerance();
        float minPeak = config.minHexPeak();

        // Try every rotation of the (0°, 60°, 120°) triplet in 1° steps
        for (int rotation = 0; rotation < 180; rotation++) {
            int a0 = rotation;
            int a1 = (rotation + 60) % 180;
            int a2 = (rotation + 120) % 180;

            Peak p0 = findClosestPeak(peaks, a0, tolerance);
            Peak p1 = findClosestPeak(peaks, a1, tolerance);
            Peak p2 = findClosestPeak(peaks, a2, tolerance);

            if (p0 == null || p1 == null || p2 == null) continue;

            // At least 2 of 3 must meet minimum strength
            int strongCount = 0;
            if (p0.strength() >= minPeak) strongCount++;
            if (p1.strength() >= minPeak) strongCount++;
            if (p2.strength() >= minPeak) strongCount++;
            if (strongCount < 2) continue;

            // Spacing regularity check: at least 2 of 3 angles need regular lines
            SpacingResult s0 = analyseSpacing(lines, p0.angle(), tolerance);
            SpacingResult s1 = analyseSpacing(lines, p1.angle(), tolerance);
            SpacingResult s2 = analyseSpacing(lines, p2.angle(), tolerance);

            int regularCount = 0;
            if (s0.regularLineCount() >= config.minRegularLines()) regularCount++;
            if (s1.regularLineCount() >= config.minRegularLines()) regularCount++;
            if (s2.regularLineCount() >= config.minRegularLines()) regularCount++;
            if (regularCount < 2) continue;

            // Reject if spacing is too small — real hex cells are at least ~10 px at 150 DPI
            double avgSpacing = averageSpacing(s0, s1, s2);
            if (avgSpacing < MIN_GRID_SPACING_PX) continue;

            double peakScore = (p0.strength() + p1.strength() + p2.strength()) / 3.0;
            double spacingScore = averageSpacingScore(s0, s1, s2);
            double confidence = peakScore * 0.4 + spacingScore * 0.4 + 0.2;

            return new MapClassification(MapType.HEX_MAP, Math.min(confidence, 1.0),
                    new double[]{p0.angle(), p1.angle(), p2.angle()}, avgSpacing);
        }
        return null;
    }

    // ── Grid classification ──────────────────────────────────────────────────

    private MapClassification tryGridClassification(List<Peak> peaks,
                                                     List<HoughTransform.Line> lines,
                                                     BufferedImage cropped) {
        if (peaks.size() < 2) return null;

        int tolerance = config.gridAngleTolerance();
        float minPeak = config.minGridPeak();

        // Try every rotation of the (0°, 90°) pair
        for (int rotation = 0; rotation < 180; rotation++) {
            int a0 = rotation;
            int a1 = (rotation + 90) % 180;

            Peak p0 = findClosestPeak(peaks, a0, tolerance);
            Peak p1 = findClosestPeak(peaks, a1, tolerance);

            if (p0 == null || p1 == null) continue;
            if (p0.strength() < minPeak || p1.strength() < minPeak) continue;

            // Spacing regularity: both angles must have regular lines
            SpacingResult s0 = analyseSpacing(lines, p0.angle(), tolerance);
            SpacingResult s1 = analyseSpacing(lines, p1.angle(), tolerance);

            if (s0.regularLineCount() < config.minRegularLines()) continue;
            if (s1.regularLineCount() < config.minRegularLines()) continue;

            // Grid spacing for H and V should be similar (within 30%)
            if (s0.candidateSpacing() > 0 && s1.candidateSpacing() > 0) {
                double ratio = s0.candidateSpacing() / s1.candidateSpacing();
                if (ratio < 0.7 || ratio > 1.43) continue;
            }

            double avgSpacing = (s0.candidateSpacing() + s1.candidateSpacing()) / 2.0;

            // Reject if spacing is too small — real grid cells are at least ~10 px at 150 DPI
            if (avgSpacing < MIN_GRID_SPACING_PX) continue;

            double peakScore = (p0.strength() + p1.strength()) / 2.0;
            double spacingScore = averageSpacingScore(s0, s1);
            double confidence = peakScore * 0.4 + spacingScore * 0.4 + 0.2;

            // Compute grid dimensions from Hough rho extent.
            // Hough convention: rho = x*cos(θ) + y*sin(θ)
            //   a0 lines (e.g. θ=0°): rho ≈ x → vertical lines → rho extent = width
            //   a1 lines (e.g. θ=90°): rho ≈ y → horizontal lines → rho extent = height
            int totalWidth = avgSpacing > 0
                    ? (int) Math.round(s0.rhoExtent() / avgSpacing) : 0;
            int totalHeight = avgSpacing > 0
                    ? (int) Math.round(s1.rhoExtent() / avgSpacing) : 0;

            // Detect sections using content projection profiles on the actual image
            List<MapSection> sections = detectSections(cropped, avgSpacing);

            return new MapClassification(MapType.GRID_MAP, Math.min(confidence, 1.0),
                    new double[]{p0.angle(), p1.angle()}, avgSpacing,
                    totalWidth, totalHeight, sections);
        }
        return null;
    }

    // ── Multi-level section detection (content projection profiles) ──────

    /** Brightness threshold: pixels darker than this are "content". */
    private static final int CONTENT_THRESHOLD = 230;

    /** Max content density in a strip to qualify as a whitespace gap. */
    private static final double GAP_DENSITY_THRESHOLD = 0.02;

    /**
     * Detects distinct map sections (floors/levels) using content projection
     * profiles on the actual image pixels.
     *
     * 1. Build a content mask (brightness < threshold = content).
     * 2. Horizontal projection → find whitespace bands → split into rows.
     * 3. Per-row vertical projection → find whitespace bands → split into columns.
     * 4. Each (row, column) cell = one MapSection.
     *
     * @param cropped     the cropped content-area image
     * @param gridSpacing average grid spacing in pixels (for minimum gap width + dimension calc)
     */
    private List<MapSection> detectSections(BufferedImage cropped, double gridSpacing) {
        int w = cropped.getWidth();
        int h = cropped.getHeight();
        if (gridSpacing <= 0 || w <= 0 || h <= 0) {
            return List.of(new MapSection(0, 0, 0, 0, w, h));
        }

        boolean[] content = buildContentMask(cropped);
        int minGapWidth = (int) (gridSpacing * config.minSectionGapFactor());

        // Step 1: horizontal projection → row splits
        int[] hProj = horizontalProjection(content, w, h, 0, w);
        List<int[]> hGaps = findGaps(hProj, h, w, minGapWidth);
        List<int[]> rowBands = bandsFromGaps(hGaps, h, minGapWidth);

        List<MapSection> sections = new ArrayList<>();

        for (int[] row : rowBands) {
            int ry = row[0];
            int rh = row[1] - row[0];

            // Step 2: per-row vertical projection → column splits
            int[] vProj = verticalProjection(content, w, h, ry, row[1]);
            List<int[]> vGaps = findGaps(vProj, w, rh, minGapWidth);
            List<int[]> colBands = bandsFromGaps(vGaps, w, minGapWidth);

            for (int[] col : colBands) {
                int cx = col[0];
                int cw = col[1] - col[0];
                int wSquares = (int) Math.round(cw / gridSpacing);
                int hSquares = (int) Math.round(rh / gridSpacing);
                sections.add(new MapSection(wSquares, hSquares, cx, ry, cw, rh));
            }
        }

        if (sections.isEmpty()) {
            sections.add(new MapSection(
                    (int) Math.round(w / gridSpacing),
                    (int) Math.round(h / gridSpacing),
                    0, 0, w, h));
        }
        return sections;
    }

    /**
     * Builds a content mask from a colour image. A pixel is "content" if its
     * grayscale brightness is below CONTENT_THRESHOLD.
     */
    private static boolean[] buildContentMask(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        boolean[] mask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int brightness = (r * 299 + g * 587 + b * 114) / 1000;
                mask[y * w + x] = brightness < CONTENT_THRESHOLD;
            }
        }
        return mask;
    }

    /**
     * Horizontal projection: counts content pixels per row.
     * Restricted to columns [x1, x2).
     */
    private static int[] horizontalProjection(boolean[] mask, int w, int h,
                                               int x1, int x2) {
        int[] proj = new int[h];
        for (int y = 0; y < h; y++) {
            int count = 0;
            for (int x = x1; x < x2; x++) {
                if (mask[y * w + x]) count++;
            }
            proj[y] = count;
        }
        return proj;
    }

    /**
     * Vertical projection: counts content pixels per column.
     * Restricted to rows [y1, y2).
     */
    private static int[] verticalProjection(boolean[] mask, int w, int h,
                                             int y1, int y2) {
        int[] proj = new int[w];
        for (int x = 0; x < w; x++) {
            int count = 0;
            for (int y = y1; y < y2; y++) {
                if (mask[y * w + x]) count++;
            }
            proj[x] = count;
        }
        return proj;
    }

    /**
     * Finds whitespace gaps in a projection array. A gap is a consecutive run
     * where (projectionValue / extent) < GAP_DENSITY_THRESHOLD and the run is
     * at least minWidth pixels wide.
     *
     * @param projection  the projection array (one entry per row or column)
     * @param length      the length of the projection array
     * @param extent      the cross-axis extent (width for h-proj, height for v-proj)
     *                    used to compute density
     * @param minWidth    minimum run length to qualify as a gap
     * @return list of [gapStart, gapEnd) pairs
     */
    private static List<int[]> findGaps(int[] projection, int length, int extent,
                                         int minWidth) {
        List<int[]> gaps = new ArrayList<>();
        int gapStart = -1;

        for (int i = 0; i < length; i++) {
            double density = extent > 0 ? (double) projection[i] / extent : 0;
            if (density < GAP_DENSITY_THRESHOLD) {
                if (gapStart < 0) gapStart = i;
            } else {
                if (gapStart >= 0) {
                    if (i - gapStart >= minWidth) {
                        gaps.add(new int[]{gapStart, i});
                    }
                    gapStart = -1;
                }
            }
        }
        // Don't add trailing gaps — those are page margins, not section breaks
        return gaps;
    }

    /**
     * Converts a list of gaps into content bands (the regions between gaps).
     * Gaps at the very start or end of the range are treated as margins, not splits.
     * Adjacent gaps separated by a content band narrower than {@code minGapWidth}
     * are merged — the narrow band is a decoration (compass rose, label), not a
     * real map section.
     *
     * @param gaps        sorted list of [start, end) gap intervals
     * @param total       total extent (image height or width)
     * @param minGapWidth minimum content band width to qualify as a real section
     * @return list of [bandStart, bandEnd) intervals
     */
    private static List<int[]> bandsFromGaps(List<int[]> gaps, int total, int minGapWidth) {
        // First, merge adjacent gaps that are separated by a narrow content band
        List<int[]> merged = new ArrayList<>();
        for (int[] gap : gaps) {
            if (!merged.isEmpty()) {
                int[] prev = merged.get(merged.size() - 1);
                int bandWidth = gap[0] - prev[1];
                if (bandWidth < minGapWidth) {
                    // Narrow band between gaps — merge into one gap
                    prev[1] = gap[1];
                    continue;
                }
            }
            merged.add(new int[]{gap[0], gap[1]});
        }

        // Now build content bands from the merged gaps
        List<int[]> bands = new ArrayList<>();
        int pos = 0;
        for (int[] gap : merged) {
            if (gap[0] > pos) {
                bands.add(new int[]{pos, gap[0]});
            }
            pos = gap[1];
        }
        if (pos < total) {
            bands.add(new int[]{pos, total});
        }
        // If no gaps found, the whole range is one band
        if (bands.isEmpty()) {
            bands.add(new int[]{0, total});
        }
        return bands;
    }

    // ── Spacing regularity ───────────────────────────────────────────────────

    /**
     * Analyses spacing regularity for lines near a given angle.
     * Collects rho values, sorts them, clusters gaps by size, and counts
     * how many line-pairs match the most common gap.
     */
    SpacingResult analyseSpacing(List<HoughTransform.Line> lines,
                                         int targetAngle, int tolerance) {
        // Collect rho values for lines near the target angle
        List<Double> rhos = new ArrayList<>();
        for (HoughTransform.Line l : lines) {
            if (angleDist(l.theta(), targetAngle) <= tolerance) {
                rhos.add(l.rho());
            }
        }

        if (rhos.size() < 2) {
            return new SpacingResult(0, 0, 1.0, 0);
        }

        Collections.sort(rhos);

        // Compute gaps between consecutive lines, skip tiny gaps (near-duplicates
        // and rho quantisation noise) that are below the minimum grid cell size
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < rhos.size(); i++) {
            double gap = rhos.get(i) - rhos.get(i - 1);
            if (gap >= MIN_GRID_SPACING_PX) gaps.add(gap);
        }

        if (gaps.size() < 2) {
            return new SpacingResult(0, 0, 1.0, 0);
        }

        // Find the most common gap size (cluster within ±20%)
        double bestSpacing = 0;
        int bestCount = 0;

        for (int i = 0; i < gaps.size(); i++) {
            double candidate = gaps.get(i);
            int count = 0;
            for (double gap : gaps) {
                double ratio = gap / candidate;
                if (ratio >= 0.8 && ratio <= 1.2) count++;
            }
            if (count > bestCount) {
                bestCount = count;
                bestSpacing = candidate;
            }
        }

        // Compute CV for the matching gaps only, and track rho extent of regular lines
        List<Double> matching = new ArrayList<>();
        double minRegularRho = Double.MAX_VALUE;
        double maxRegularRho = Double.MIN_VALUE;
        for (int i = 1; i < rhos.size(); i++) {
            double gap = rhos.get(i) - rhos.get(i - 1);
            if (gap < MIN_GRID_SPACING_PX) continue;
            double ratio = gap / bestSpacing;
            if (ratio >= 0.8 && ratio <= 1.2) {
                matching.add(gap);
                minRegularRho = Math.min(minRegularRho, rhos.get(i - 1));
                maxRegularRho = Math.max(maxRegularRho, rhos.get(i));
            }
        }

        double rhoExtent = matching.isEmpty() ? 0 : maxRegularRho - minRegularRho;

        double mean = matching.stream().mapToDouble(d -> d).average().orElse(0);
        double variance = matching.stream()
                .mapToDouble(d -> (d - mean) * (d - mean))
                .average().orElse(0);
        double cv = mean > 0 ? Math.sqrt(variance) / mean : 1.0;

        // Regular line count = matching gaps + 1 (each gap is between two lines)
        int regularLineCount = matching.size() + 1;

        return new SpacingResult(regularLineCount, bestSpacing, cv, rhoExtent);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean[] toBooleanEdges(BufferedImage edgeImage, int w, int h) {
        boolean[] edges = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                edges[y * w + x] = (edgeImage.getRGB(x, y) & 0xFFFFFF) != 0;
            }
        }
        return edges;
    }

    private Peak findClosestPeak(List<Peak> peaks, int targetAngle, int tolerance) {
        Peak best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Peak p : peaks) {
            int dist = angleDist(p.angle(), targetAngle);
            if (dist <= tolerance && dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    /** Angular distance accounting for 180° wrap-around. */
    private static int angleDist(double a, double b) {
        int d = (int) Math.abs(a - b) % 180;
        return Math.min(d, 180 - d);
    }

    /** Wraps angle to [0, 179]. */
    private static int wrapAngle(int angle) {
        return ((angle % 180) + 180) % 180;
    }

    private static double averageSpacingScore(SpacingResult... results) {
        double sum = 0;
        for (SpacingResult r : results) {
            sum += Math.max(0, 1.0 - r.cv());
        }
        return sum / results.length;
    }

    private static double averageSpacing(SpacingResult... results) {
        double sum = 0;
        int count = 0;
        for (SpacingResult r : results) {
            if (r.candidateSpacing() > 0) {
                sum += r.candidateSpacing();
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    // ── Section bounds for visual output ────────────────────────────────────

    /**
     * Computes pixel-space bounding rectangles for each detected section.
     * The rectangles are in page-image coordinates (not cropped).
     *
     * @return list of [x, y, width, height] arrays, one per section
     */
    public List<int[]> computeSectionBounds(
            com.dnd.processor.model.MapClassification result,
            ProjectionAnalyzer.Margins margins) {

        if (result.sections().isEmpty() || result.gridSpacing() <= 0
                || result.dominantAngles().length < 2) {
            return List.of();
        }

        // Sections carry pixel bounds relative to the cropped content area.
        // Translate to page-space by adding the content area origin (margins).
        int cx = margins.left();
        int cy = margins.top();
        double halfPad = result.gridSpacing() / 2.0;

        List<int[]> bounds = new ArrayList<>();
        for (var sec : result.sections()) {
            if (sec.pixelW() <= 0 && sec.pixelH() <= 0) continue;
            int bx = (int) Math.max(0, cx + sec.pixelX() - halfPad);
            int by = (int) Math.max(0, cy + sec.pixelY() - halfPad);
            int bw = (int) (sec.pixelW() + halfPad * 2);
            int bh = (int) (sec.pixelH() + halfPad * 2);
            bounds.add(new int[]{bx, by, bw, bh});
        }
        return bounds;
    }
}
