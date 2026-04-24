package com.cropcenter.model;

/**
 * Immutable grid + selection-paint settings. Callers mutate via CropState.updateGridConfig,
 * which replaces the current instance with one produced by one or more withXxx transformers.
 *
 * Field meanings:
 *   enabled            — master toggle for the preview grid
 *   includeInExport    — bake the grid into saved output
 *   showPixelGrid      — show the pixel grid when zoom ≥ 6x
 *   lineWidth          — grid stroke width in image pixels
 *   color              — grid line color (default white)
 *   columns / rows     — grid cell count
 *   pixelGridColor     — pixel-grid stroke color (default black)
 *   selectionColor     — shared color for selection points, polygon fill, and horizon paint
 */
public record GridConfig(
	boolean enabled,
	boolean includeInExport,
	boolean showPixelGrid,
	float lineWidth,
	int color,
	int columns,
	int pixelGridColor,
	int rows,
	int selectionColor)
{
	/**
	 * Default configuration applied to every freshly-loaded image: grid visible at 4×4,
	 * pixel grid visible when zoomed past 6×, grid NOT baked into exports, 50%-transparent
	 * blue selection overlay.
	 */
	public static GridConfig defaults()
	{
		return new GridConfig(
			true,          // enabled
			false,         // includeInExport
			true,          // showPixelGrid
			1f,            // lineWidth
			0xFFFFFFFF,    // color — white
			4,             // columns
			0xFF000000,    // pixelGridColor — black
			4,             // rows
			0x800000FF);   // selectionColor — 50% transparent blue
	}

	public GridConfig withColor(int color)
	{
		return new GridConfig(enabled, includeInExport, showPixelGrid, lineWidth,
			color, columns, pixelGridColor, rows, selectionColor);
	}

	/**
	 * Replace the column count. Clamped by CropState's update path — this method itself
	 * does not validate.
	 */
	public GridConfig withColumns(int columns)
	{
		return new GridConfig(enabled, includeInExport, showPixelGrid, lineWidth,
			color, columns, pixelGridColor, rows, selectionColor);
	}

	public GridConfig withEnabled(boolean enabled)
	{
		return new GridConfig(enabled, includeInExport, showPixelGrid, lineWidth,
			color, columns, pixelGridColor, rows, selectionColor);
	}

	public GridConfig withIncludeInExport(boolean includeInExport)
	{
		return new GridConfig(enabled, includeInExport, showPixelGrid, lineWidth,
			color, columns, pixelGridColor, rows, selectionColor);
	}

	public GridConfig withLineWidth(float lineWidth)
	{
		return new GridConfig(enabled, includeInExport, showPixelGrid, lineWidth,
			color, columns, pixelGridColor, rows, selectionColor);
	}

	public GridConfig withPixelGridColor(int pixelGridColor)
	{
		return new GridConfig(enabled, includeInExport, showPixelGrid, lineWidth,
			color, columns, pixelGridColor, rows, selectionColor);
	}

	public GridConfig withRows(int rows)
	{
		return new GridConfig(enabled, includeInExport, showPixelGrid, lineWidth,
			color, columns, pixelGridColor, rows, selectionColor);
	}

	/**
	 * Replace the shared selection color (ARGB) — used by selection points, polygon
	 * fill, and horizon paint together for visual consistency.
	 */
	public GridConfig withSelectionColor(int selectionColor)
	{
		return new GridConfig(enabled, includeInExport, showPixelGrid, lineWidth,
			color, columns, pixelGridColor, rows, selectionColor);
	}

	/**
	 * Toggle the per-pixel grid overlay that appears at high zoom levels.
	 */
	public GridConfig withShowPixelGrid(boolean showPixelGrid)
	{
		return new GridConfig(enabled, includeInExport, showPixelGrid, lineWidth,
			color, columns, pixelGridColor, rows, selectionColor);
	}
}
