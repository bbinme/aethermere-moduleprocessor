package com.dnd.processor.model;

/**
 * One distinct map section (floor/level/location) detected on a page.
 *
 * @param widthSquares  width of this section in grid squares
 * @param heightSquares height of this section in grid squares
 * @param pixelX        x-offset in pixels within the cropped content area
 * @param pixelY        y-offset in pixels within the cropped content area
 * @param pixelW        width in pixels
 * @param pixelH        height in pixels
 */
public record MapSection(int widthSquares, int heightSquares,
                         int pixelX, int pixelY, int pixelW, int pixelH) {

    /** Convenience constructor for sections without pixel bounds (e.g. single-section pages). */
    public MapSection(int widthSquares, int heightSquares) {
        this(widthSquares, heightSquares, 0, 0, 0, 0);
    }
}
