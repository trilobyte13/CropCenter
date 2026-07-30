package com.cropcenter.util;

/**
 * Pre-computed values for the rotated-clamp binary search and corner check, bundled so those hot-path helpers take
 * one parameter instead of eleven. Every value is a function of (cropW, cropH, rotationDegrees, imgW, imgH), so the
 * caller pre-computes once. Lives in util/ alongside RotatedCropClamp so
 * model/CropState can consume the geometry helpers without a layering inversion.
 *
 * @param cosR        pre-computed cosine of negated rotation in radians
 * @param halfHeight  half the crop height
 * @param halfWidth   half the crop width
 * @param imageMidX   image center X (rotation pivot)
 * @param imageMidY   image center Y (rotation pivot)
 * @param imgH        source image height
 * @param imgW        source image width
 * @param sinR        pre-computed sine of negated rotation in radians
 */
public record CropFitContext(double cosR, float halfHeight, float halfWidth, float imageMidX, float imageMidY,
	int imgH, int imgW, double sinR)
{
	/**
	 * Build a context for the given crop and rotation. The negation of rotationDegrees matches
	 * RotatedCropClamp.clampRotated's convention — the clamp checks corners by un-rotating them, so the trig values
	 * cache the inverse rotation.
	 *
	 * @param cropW           crop width in image pixels
	 * @param cropH           crop height in image pixels
	 * @param rotationDegrees rotation applied to the image (the context inverts this internally)
	 * @param imgW            source image width
	 * @param imgH            source image height
	 * @return populated context with all derived values cached
	 */
	public static CropFitContext of(int cropW, int cropH, float rotationDegrees, int imgW, int imgH)
	{
		double rad = Math.toRadians(-rotationDegrees);
		return new CropFitContext(Math.cos(rad), cropH / 2f, cropW / 2f,
			imgW / 2f, imgH / 2f, imgH, imgW, Math.sin(rad));
	}
}
