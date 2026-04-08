package com.dnd.processor.model;

/**
 * A dungeon floor grid extracted from a classic D&D module map.
 *
 * @param width  grid width in squares
 * @param height grid height in squares
 * @param grid   row-major cell array: grid[row * width + col]
 */
public record DungeonMap(int width, int height, int[] grid) {

    public static final int FLOOR           = 0;
    public static final int WALL            = 1;
    public static final int DOOR_NORTH      = 2;
    public static final int DOOR_EAST       = 3;
    public static final int DOOR_SOUTH      = 4;
    public static final int DOOR_WEST       = 5;
    public static final int THIN_WALL_NORTH = 6;
    public static final int THIN_WALL_EAST  = 7;
    public static final int THIN_WALL_SOUTH = 8;
    public static final int THIN_WALL_WEST  = 9;
}
