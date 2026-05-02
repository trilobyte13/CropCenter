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
