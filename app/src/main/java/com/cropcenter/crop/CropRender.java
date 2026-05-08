package com.cropcenter.crop;

/**
 * Bundle of crop-geometry parameters that travel together to the export pipeline. Replaces the data clump that the
 * audit flagged in UltraHdrCompat.compressWithGainmap (12-param method) and the analogous threading through
 * CropExporter.export → buildCroppedGainMap → compressWithGainmap.
 *
 * @param centerX  crop center X in un-rotated image coords
 * @param centerY  crop center Y in un-rotated image coords
 * @param cropH    crop height in image pixels
 * @param cropW    crop width in image pixels
 * @param imgH     source image height in pixels
 * @param imgW     source image width in pixels
 * @param rotation user-applied rotation in degrees (the rotation baked into the export)
 */
public record CropRender(float centerX, float centerY, int cropH, int cropW, int imgH, int imgW, float rotation)
{
	/**
	 * Construct a CropRender using the codebase's canonical (W, H) parameter convention.
	 *
	 * The record's component order is (cropH, cropW, imgH, imgW) because CLAUDE.md sorts record components
	 * alphabetically by name and `cropH` < `cropW` / `imgH` < `imgW`. But the rest of the codebase uses
	 * (W, H) ordering everywhere — `getCropW()` then `getCropH()` on CropState, `(imgW, imgH)` parameters
	 * on CropFitContext.of, RotatedCropClamp, CropEngine, EditorRenderer. Calling the canonical record
	 * constructor positionally is a footgun: a maintainer copying the codebase convention would write
	 * `new CropRender(cx, cy, cropW, cropH, imgW, imgH, rot)` and silently swap each pair (all `int`, no
	 * compile error). This factory takes parameters in (W, H) order and forwards to the canonical
	 * constructor with the swap applied.
	 *
	 * @param centerX  crop center X in un-rotated image coords
	 * @param centerY  crop center Y in un-rotated image coords
	 * @param cropW    crop width in image pixels
	 * @param cropH    crop height in image pixels
	 * @param imgW     source image width in pixels
	 * @param imgH     source image height in pixels
	 * @param rotation user-applied rotation in degrees
	 * @return CropRender with components in their canonical alphabetical order
	 */
	public static CropRender of(float centerX, float centerY, int cropW, int cropH,
		int imgW, int imgH, float rotation)
	{
		return new CropRender(centerX, centerY, cropH, cropW, imgH, imgW, rotation);
	}

	/**
	 * @return crop-origin X (top-left of the crop rect in source image coords) — `centerX − cropW / 2f`
	 */
	public float srcX()
	{
		return centerX - cropW / 2f;
	}

	/**
	 * @return crop-origin Y — `centerY − cropH / 2f`
	 */
	public float srcY()
	{
		return centerY - cropH / 2f;
	}
}
