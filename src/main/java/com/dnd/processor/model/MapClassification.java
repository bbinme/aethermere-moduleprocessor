package com.dnd.processor.model;

import java.util.List;

/**
 * Result of classifying a MAP page as hex, grid, or non-map.
 *
 * @param type              the detected map type
 * @param confidence        0.0–1.0 overall confidence score
 * @param dominantAngles    degrees of detected angle peaks
 * @param gridSpacing       average grid spacing in pixels (0 if NON_MAP)
 * @param gridWidthSquares  total grid width in squares (0 if not GRID_MAP)
 * @param gridHeightSquares total grid height in squares (0 if not GRID_MAP)
 * @param sections          individual map sections (empty if not GRID_MAP)
 */
public record MapClassification(
        MapType  type,
        double   confidence,
        double[] dominantAngles,
        double   gridSpacing,
        int      gridWidthSquares,
        int      gridHeightSquares,
        List<MapSection> sections
) {
    /** Convenience constructor for non-grid results (no dimensions/sections). */
    public MapClassification(MapType type, double confidence, double[] dominantAngles,
                             double gridSpacing) {
        this(type, confidence, dominantAngles, gridSpacing, 0, 0, List.of());
    }
}
