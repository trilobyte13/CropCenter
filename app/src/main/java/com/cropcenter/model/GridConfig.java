package com.cropcenter.model;

public class GridConfig {
    public boolean enabled = true;
    public boolean showPixelGrid = true;
    public int columns = 4;
    public int rows = 4;
    public int color = 0xFFFFFFFF; // white — grid line color
    public int pixelGridColor = 0xFF000000; // black
    public float lineWidth = 1f;

    // Shared color for selection points, selection polygon, and horizon paint area
    public int selectionColor = 0x800000FF; // standard transparent blue (50% alpha)
}
