package com.cropcenter.util;

/**
 * Shared grid-line position chokepoint. Mirror-symmetric integer position for inner line `i` in a
 * grid of `count` intervals spanning `dim` pixels. Used by both the on-screen renderer
 * (`view/GridRenderer.linePos`) and the export pipeline (`crop/CropExporter.gridLinePixel`) so a
 * grid drawn at preview time aligns byte-for-byte with the same grid baked into the saved image at
 * export.
 *
 * Independent rounding in two places previously sat in two different files; per CLAUDE.md
 * §Canonical helpers, a recurring pattern should have one chokepoint — a regression in either
 * file's rounding (e.g., switching from Math.round to truncating int cast) would have produced
 * half-pixel drift on odd dims, visible as a "ghost grid" mismatch when zooming a fresh save
 * compared to the in-editor preview.
 */
public final class GridGeometry
{
	private GridGeometry() {}

	/**
	 * Compute the mirror-symmetric pixel position for the `i`-th inner grid line in a `count`-interval
	 * grid spanning `dim` pixels. The mirroring guarantees `mirroredLinePos(i, count, dim) +
	 * mirroredLinePos(count - i, count, dim) == dim` up to integer rounding — equivalently, lines
	 * are equidistant from the midline. Returns a 0-based offset within the crop, so a caller
	 * drawing onto the screen adds the crop origin and the export caller adds nothing.
	 *
	 * @param i     line index, expected in [1, count - 1] (inner lines only; edges are the crop
	 *              boundary itself). Outside that range the math still produces a defined value but
	 *              callers shouldn't rely on it.
	 * @param count total grid intervals (the grid draws count - 1 inner lines)
	 * @param dim   crop dimension along this axis in pixels
	 * @return integer pixel offset from the crop's start along this axis; mirror-symmetric across
	 *         the midline so any (i, count - i) pair lands at equidistant offsets from the centre
	 */
	public static int mirroredLinePos(int i, int count, int dim)
	{
		// For lines past the midpoint, mirror by computing the position from the other end —
		// this is what keeps an off-centre count-2 grid (single middle line) at dim/2 ± 0
		// instead of dim/2 ± 1 due to round-half-to-even bias.
		if (i * 2 > count)
		{
			int mirror = (int) Math.round((double) dim * (count - i) / count);
			return dim - mirror;
		}
		return (int) Math.round((double) dim * i / count);
	}
}
