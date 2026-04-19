package com.cropcenter.model;

public class GridConfig
{
	public boolean enabled = true;
	public boolean includeInExport = false;         // bake the grid into saved output
	public boolean showPixelGrid = true;
	public float lineWidth = 1f;
	public int color = 0xFFFFFFFF;                  // white — grid line color
	public int columns = 4;
	public int pixelGridColor = 0xFF000000;         // black
	public int rows = 4;
	// Shared color for selection points, selection polygon, and horizon paint area.
	public int selectionColor = 0x800000FF;         // standard transparent blue (50% alpha)
}
