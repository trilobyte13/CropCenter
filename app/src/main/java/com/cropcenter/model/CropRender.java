package com.cropcenter.model;

/**
 * Bundle of crop-geometry parameters that travel together to the export pipeline (replaces a 12-param method
 * clump in UltraHdrCompat.compressWithGainmap and the threading through CropExporter.export →
 * buildCroppedGainMap). Lives in model/ (not crop/) as a pure value object both crop/ and util/ consume —
 * keeping it in crop/ would invert the util/ → crop/ layering.
 *
 * A final class with a private constructor + public static of(...) factory, NOT a record: a record's
 * canonical constructor must be as accessible as the record, exposing the all-positional raw ctor. The
 * footgun — a maintainer using the codebase's (W, H) convention everywhere else would write
 * `new CropRender(cx, cy, cropW, cropH, imgW, imgH, rot)` and silently transpose each int pair (no compile
 * error, but visible HDR halos around every cropped export). The of(...) factory takes canonical (W, H) order
 * and threads the swap internally; fields are stored alphabetically (cropH before cropW, etc.).
 */
public final class CropRender
{
	private final float centerX;
	private final float centerY;
	private final float rotation;
	private final int cropH;
	private final int cropW;
	private final int imgH;
	private final int imgW;

	private CropRender(float centerX, float centerY, int cropW, int cropH, int imgW, int imgH, float rotation)
	{
		this.centerX = centerX;
		this.centerY = centerY;
		this.cropH = cropH;
		this.cropW = cropW;
		this.imgH = imgH;
		this.imgW = imgW;
		this.rotation = rotation;
	}

	/**
	 * Construct a CropRender using the codebase's canonical (W, H) parameter convention. The only way to
	 * construct a CropRender — the all-int positional constructor is private so a maintainer can't accidentally
	 * call `new CropRender(...)` with arguments transposed against the storage's (H, W) alphabetical order.
	 *
	 * @param centerX  crop center X in un-rotated image coords
	 * @param centerY  crop center Y in un-rotated image coords
	 * @param cropW    crop width in image pixels
	 * @param cropH    crop height in image pixels
	 * @param imgW     source image width in pixels
	 * @param imgH     source image height in pixels
	 * @param rotation user-applied rotation in degrees
	 * @return CropRender carrying the supplied geometry
	 */
	public static CropRender of(float centerX, float centerY, int cropW, int cropH,
		int imgW, int imgH, float rotation)
	{
		return new CropRender(centerX, centerY, cropW, cropH, imgW, imgH, rotation);
	}

	public float centerX()
	{
		return centerX;
	}

	public float centerY()
	{
		return centerY;
	}

	public int cropH()
	{
		return cropH;
	}

	public int cropW()
	{
		return cropW;
	}

	public int imgH()
	{
		return imgH;
	}

	public int imgW()
	{
		return imgW;
	}

	public float rotation()
	{
		return rotation;
	}

	/**
	 * Crop-origin X (top-left of the crop rect in source image coords) derived from the stored center and width.
	 *
	 * @return centerX − cropW / 2f
	 */
	public float srcX()
	{
		return centerX - cropW / 2f;
	}

	/**
	 * Crop-origin Y derived from the stored center and height.
	 *
	 * @return centerY − cropH / 2f
	 */
	public float srcY()
	{
		return centerY - cropH / 2f;
	}
}
