package com.cropcenter.crop;

/**
 * Bundle of crop-geometry parameters that travel together to the export pipeline. Replaces the data clump that the
 * audit flagged in UltraHdrCompat.compressWithGainmap (12-param method) and the analogous threading through
 * CropExporter.export → buildCroppedGainMap → compressWithGainmap.
 *
 * Implemented as a final class with a private constructor + public static `of(...)` factory, NOT a record, because a
 * record's canonical constructor must be at least as accessible as the record itself — making the all-positional raw
 * constructor reachable to every caller. The footgun: a maintainer using the codebase's (W, H) convention everywhere
 * else (CropState.getCropW() / .getCropH(), CropFitContext.of(imgW, imgH, ...), RotatedCropClamp, CropEngine,
 * EditorRenderer) would write `new CropRender(cx, cy, cropW, cropH, imgW, imgH, rot)` and silently transpose each pair
 * — all int, no compile error, but the gain-map output would carry visible HDR halos around every cropped export.
 * Converting from `public record` to `public final class` + `private` ctor closes that hole at compile time: the only
 * way to construct a CropRender is through `of(...)`, whose parameter order matches the rest of the codebase. Fields
 * are stored alphabetically (cropH before cropW, imgH before imgW); the factory accepts the canonical (W, H) order
 * and threads the swap internally.
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
