package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.cropcenter.metadata.JpegSegment;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the horizon tilt angle for auto-rotation correction.
 *
 * Strategy (in priority order):
 *   1. XMP metadata: look for device roll angle (most accurate, ~0.01° precision)
 *   2. Computer vision: Canny edges + Radon variance maximization (fallback)
 */
public final class HorizonDetector
{
	private static final String TAG = "HorizonDetector";

	private HorizonDetector() {}

	/**
	 * Detect horizon tilt from EXIF/XMP metadata if available.
	 *
	 * @param meta JPEG metadata segments (from JpegMetadataExtractor)
	 * @return correction angle in degrees, or NaN if no roll data found
	 */
	public static float detectFromMetadata(List<JpegSegment> meta)
	{
		if (meta == null)
		{
			return Float.NaN;
		}

		for (JpegSegment seg : meta)
		{
			if (!seg.isXmp())
			{
				continue;
			}

			// XMP data starts after "http://ns.adobe.com/xap/1.0/\0" (29 bytes) + APP1 header (4 bytes)
			// seg.data() = FF E1 LL LL [XMP identifier] [XML...]
			String xmpId = "http://ns.adobe.com/xap/1.0/\0";
			int xmlStart = 4 + xmpId.length();
			byte[] segData = seg.data();
			if (segData.length <= xmlStart)
			{
				continue;
			}

			String xml = new String(segData, xmlStart, segData.length - xmlStart);

			// Search for roll angle in common XMP properties. Different cameras use different
			// namespaces: GCamera:Roll, Device:Roll, samsung:LensRoll, exif:Roll, or generic
			// Roll/Tilt attributes.
			float roll = findXmpFloat(xml, "Roll");
			if (!Float.isNaN(roll))
			{
				Log.d(TAG, "Found XMP Roll: " + roll + "°");
				// Roll > 0 typically means CW tilt → need CCW correction
				return normalizeMetadataAngle(roll);
			}

			// Some cameras store pitch/tilt instead of roll
			float tilt = findXmpFloat(xml, "Tilt");
			if (!Float.isNaN(tilt))
			{
				Log.d(TAG, "Found XMP Tilt: " + tilt + "°");
				return normalizeMetadataAngle(tilt);
			}
		}

		// Also check extended XMP (APP1 segments with different identifier)
		for (JpegSegment seg : meta)
		{
			byte[] segData = seg.data();
			if (seg.marker() != 0xE1 || segData.length < 50)
			{
				continue;
			}
			// Try to find roll in any APP1 segment that contains XML-like content
			String raw = new String(segData, 4, Math.min(segData.length - 4, 65000));
			if (!raw.contains("Roll") && !raw.contains("roll") && !raw.contains("Tilt"))
			{
				continue;
			}

			float roll = findXmpFloat(raw, "Roll");
			if (!Float.isNaN(roll))
			{
				Log.d(TAG, "Found Roll in APP1: " + roll + "°");
				return normalizeMetadataAngle(roll);
			}
		}

		return Float.NaN;
	}

	/**
	 * Convert a raw roll/tilt reading from metadata into the UI's correction-angle convention:
	 * snap near-zero values to exact zero, reject implausibly-large tilts as NaN (bad sensor data),
	 * and otherwise invert and round to 2 decimal places. Shared across all XMP/APP1 entry points.
	 */
	private static float normalizeMetadataAngle(float deg)
	{
		if (Math.abs(deg) < 0.005f)
		{
			return 0f;
		}
		if (Math.abs(deg) > 25f)
		{
			return Float.NaN;
		}
		return -Math.round(deg * 100f) / 100f;
	}

	/**
	 * Detect horizon angle using only edges within a user-painted region.
	 * The painted points define a brush stroke; only edge pixels near this stroke are used for
	 * the Hough line detection.
	 *
	 * @param src         source bitmap
	 * @param paintPoints list of (x,y) image-coordinate points from the paint stroke
	 * @param brushRadius radius in image pixels around each paint point
	 * @return correction angle in degrees, or NaN if not detected
	 */
	public static float detectFromPaintedRegion(Bitmap src, List<float[]> paintPoints,
		float brushRadius)
	{
		if (src == null || src.getWidth() < 10 || src.getHeight() < 10
			|| paintPoints == null || paintPoints.size() < 2)
		{
			return Float.NaN;
		}

		try
		{
			return detectPaintedInternal(src, paintPoints, brushRadius);
		}
		catch (OutOfMemoryError e)
		{
			Log.w(TAG, "OOM in painted detection");
			return Float.NaN;
		}
	}

	// ── Image processing primitives ──

	private static float computeThreshold(float[] edges, float topFraction)
	{
		float maxVal = 0;
		int nonZero = 0;
		for (float v : edges)
		{
			if (v > 0)
			{
				nonZero++;
				if (v > maxVal)
				{
					maxVal = v;
				}
			}
		}
		if (nonZero == 0 || maxVal == 0)
		{
			return Float.MAX_VALUE;
		}
		int bins = 256;
		int[] hist = new int[bins];
		for (float v : edges)
		{
			if (v > 0)
			{
				hist[Math.min(bins - 1, (int) (v / maxVal * (bins - 1)))]++;
			}
		}
		int target = (int) (nonZero * (1f - topFraction));
		int cumulative = 0;
		for (int i = 0; i < bins; i++)
		{
			cumulative += hist[i];
			if (cumulative >= target)
			{
				return (i / (float) (bins - 1)) * maxVal;
			}
		}
		return maxVal * 0.5f;
	}

	private static float detectPaintedInternal(Bitmap src, List<float[]> paintPoints,
		float brushRadius)
	{
		int width = src.getWidth();
		int height = src.getHeight();
		int maskWidth = width / 4;
		int maskHeight = height / 4;

		boolean[] mask = rasterizePaintMask(paintPoints, maskWidth, maskHeight, brushRadius);
		float[] edges = buildEdgeMap(src, width, height);

		int[][] edgeCoords = gatherMaskedEdges(edges, mask, width, height, maskWidth, maskHeight);
		// Release the large intermediates before the coarse+fine Hough pass — on a large
		// source `edges` alone can be 100 MB of floats. Holding them alive through the
		// Hough loops was a memory regression introduced when this method was
		// decomposed; keeping the scope tight avoids a mid-detection OOM on mid-range
		// devices that would not have fired before the refactor.
		edges = null;
		mask = null;
		if (edgeCoords == null)
		{
			return Float.NaN;
		}
		int[] edgeX = edgeCoords[0];
		int[] edgeY = edgeCoords[1];
		int edgeCount = edgeX.length;
		Log.d(TAG, "Masked edge pixels: " + edgeCount);

		return runHoughAndConvertToRotation(edgeX, edgeY, edgeCount, width, height);
	}

	/**
	 * Stroke-to-mask rasterization. The paint stroke is rasterized at 1/4 source
	 * resolution into a boolean grid — enough precision to localize which source
	 * pixels belong to the horizon region, 16× cheaper in memory than a full-res mask.
	 */
	private static boolean[] rasterizePaintMask(List<float[]> paintPoints,
		int maskWidth, int maskHeight, float brushRadius)
	{
		float maskScale = 4f;
		boolean[] mask = new boolean[maskWidth * maskHeight];
		float maskRadius = brushRadius / maskScale;
		float maskRadiusSquared = maskRadius * maskRadius;

		for (float[] paintPoint : paintPoints)
		{
			int centerX = (int) (paintPoint[0] / maskScale);
			int centerY = (int) (paintPoint[1] / maskScale);
			int radius = (int) Math.ceil(maskRadius);
			for (int dy = -radius; dy <= radius; dy++)
			{
				int maskY = centerY + dy;
				if (maskY < 0 || maskY >= maskHeight)
				{
					continue;
				}
				for (int dx = -radius; dx <= radius; dx++)
				{
					int maskX = centerX + dx;
					if (maskX < 0 || maskX >= maskWidth)
					{
						continue;
					}
					if (dx * dx + dy * dy <= maskRadiusSquared)
					{
						mask[maskY * maskWidth + maskX] = true;
					}
				}
			}
		}
		return mask;
	}

	/**
	 * Canny-style edge pipeline: luminance → Gaussian blur → Sobel magnitude + direction
	 * → non-max suppression → direction filter (keep only edges within 35° of
	 * horizontal). Returns the edge strength at each pixel in row-major order.
	 * Intermediate arrays are nulled as soon as they're consumed to let GC reclaim
	 * the ~4 MB working sets early on mid-range devices.
	 */
	private static float[] buildEdgeMap(Bitmap src, int width, int height)
	{
		int[] pixels = new int[width * height];
		src.getPixels(pixels, 0, width, 0, 0, width, height);
		float[] luminance = new float[width * height];
		for (int i = 0; i < pixels.length; i++)
		{
			int pixel = pixels[i];
			luminance[i] = 0.299f * Color.red(pixel)
				+ 0.587f * Color.green(pixel)
				+ 0.114f * Color.blue(pixel);
		}
		pixels = null;

		float[] blurred = gaussianBlur5x5(luminance, width, height);
		luminance = null;

		float[] gradientMag = new float[width * height];
		float[] gradientDir = new float[width * height];
		sobelGradient(blurred, width, height, gradientMag, gradientDir);
		blurred = null;

		float[] edges = nonMaxSuppression(gradientMag, gradientDir, width, height);

		// Direction filter: keep only near-horizontal edges (±35° from horizontal).
		for (int i = 0; i < width * height; i++)
		{
			if (edges[i] > 0)
			{
				float absDirection = Math.abs(gradientDir[i]);
				if (absDirection < (float) (Math.PI / 2 - Math.PI * 35 / 180)
					|| absDirection > (float) (Math.PI / 2 + Math.PI * 35 / 180))
				{
					edges[i] = 0;
				}
			}
		}
		return edges;
	}

	/**
	 * Collect the coordinates of edge pixels that survive the strength threshold AND
	 * lie within the painted mask. Returns {edgeX[], edgeY[]} packed as a 2-element
	 * array, or null when fewer than 30 pixels qualify (not enough signal for the
	 * Hough pass to produce a trustworthy angle).
	 */
	private static int[][] gatherMaskedEdges(float[] edges, boolean[] mask,
		int width, int height, int maskWidth, int maskHeight)
	{
		float threshold = computeThreshold(edges, 0.15f);
		int edgeCount = 0;
		for (int y = 0; y < height; y++)
		{
			int maskY = Math.min(y / 4, maskHeight - 1);
			int rowOffset = y * width;
			for (int x = 0; x < width; x++)
			{
				int maskX = Math.min(x / 4, maskWidth - 1);
				if (edges[rowOffset + x] >= threshold && mask[maskY * maskWidth + maskX])
				{
					edgeCount++;
				}
			}
		}
		if (edgeCount < 30)
		{
			Log.d(TAG, "Too few masked edge pixels: " + edgeCount);
			return null;
		}

		int[] edgeX = new int[edgeCount];
		int[] edgeY = new int[edgeCount];
		int edgeIndex = 0;
		for (int y = 0; y < height; y++)
		{
			int maskY = Math.min(y / 4, maskHeight - 1);
			int rowOffset = y * width;
			for (int x = 0; x < width; x++)
			{
				int maskX = Math.min(x / 4, maskWidth - 1);
				if (edges[rowOffset + x] >= threshold && mask[maskY * maskWidth + maskX])
				{
					edgeX[edgeIndex] = x;
					edgeY[edgeIndex] = y;
					edgeIndex++;
				}
			}
		}
		return new int[][] { edgeX, edgeY };
	}

	/**
	 * Two-pass Hough transform on the masked edge pixels (coarse 80–100° at 0.1°
	 * steps, then fine ±2° around the coarse peak at 0.01° steps), converted to a
	 * rotation angle the editor can apply directly. Returns NaN when the tilt is
	 * beyond ±30° (the detector is too unreliable at larger angles), 0 when the tilt
	 * is effectively zero, or the rounded-to-0.01° rotation otherwise.
	 */
	private static float runHoughAndConvertToRotation(int[] edgeX, int[] edgeY,
		int edgeCount, int width, int height)
	{
		float coarseAngle = houghPass(edgeX, edgeY, edgeCount, width, height, 80f, 100f, 0.1f);
		if (Float.isNaN(coarseAngle))
		{
			return Float.NaN;
		}

		float fineAngle = houghPass(edgeX, edgeY, edgeCount, width, height,
			Math.max(80f, coarseAngle - 2f),
			Math.min(100f, coarseAngle + 2f), 0.01f);
		if (Float.isNaN(fineAngle))
		{
			fineAngle = coarseAngle;
		}

		float tilt = fineAngle - 90f;
		Log.d(TAG, "Painted region tilt: " + String.format(Locale.ROOT, "%.3f", tilt) + "°");

		if (Math.abs(tilt) < 0.005f)
		{
			return 0f;
		}
		if (Math.abs(tilt) > 30f)
		{
			return Float.NaN;
		}
		return -Math.round(tilt * 100f) / 100f;
	}

	/**
	 * Search XMP XML for a float attribute whose name is exactly attrSuffix, optionally with a
	 * namespace prefix. Handles patterns like: namespace:Roll="1.23" or Roll="1.23".
	 * The earlier version used "\\w*:?Suffix" which greedy-matched unrelated names like
	 * CameraRoll or GyroRoll — any attribute whose name ends in the literal suffix — and
	 * silently returned their value as the horizon angle.
	 */
	private static float findXmpFloat(String xml, String attrSuffix)
	{
		// Require either the start of a token (non-word char) or start of string, then an
		// optional namespace prefix that ends in ':', then the exact suffix followed by
		// whitespace or '='. This rules out AbcRoll, CameraRoll, GyroRoll, etc.
		Pattern pattern = Pattern.compile(
			"(?:^|[^\\w:])(?:\\w+:)?" + Pattern.quote(attrSuffix) + "\\s*=\\s*\"([^\"]+)\"",
			Pattern.CASE_INSENSITIVE);
		Matcher matcher = pattern.matcher(xml);
		while (matcher.find())
		{
			try
			{
				return Float.parseFloat(matcher.group(1).trim());
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		return Float.NaN;
	}

	private static float[] gaussianBlur5x5(float[] src, int width, int height)
	{
		float[] kernel = {
				1 / 273f, 4 / 273f,  7 / 273f,  4 / 273f, 1 / 273f,
				4 / 273f, 16 / 273f, 26 / 273f, 16 / 273f, 4 / 273f,
				7 / 273f, 26 / 273f, 41 / 273f, 26 / 273f, 7 / 273f,
				4 / 273f, 16 / 273f, 26 / 273f, 16 / 273f, 4 / 273f,
				1 / 273f, 4 / 273f,  7 / 273f,  4 / 273f, 1 / 273f,
		};
		float[] dst = new float[width * height];
		for (int y = 2; y < height - 2; y++)
		{
			for (int x = 2; x < width - 2; x++)
			{
				float sum = 0;
				for (int kernelY = -2; kernelY <= 2; kernelY++)
				{
					int rowOffset = (y + kernelY) * width;
					for (int kernelX = -2; kernelX <= 2; kernelX++)
					{
						sum += src[rowOffset + (x + kernelX)]
							* kernel[(kernelY + 2) * 5 + (kernelX + 2)];
					}
				}
				dst[y * width + x] = sum;
			}
		}
		return dst;
	}

	/**
	 * Hough transform: find the angle of the single strongest near-horizontal line. Uses
	 * max-single-bin (longest line wins) rather than sum-of-squares (all edges).
	 */
	private static float houghPass(int[] edgeX, int[] edgeY, int edgeCount,
		int width, int height, float minDeg, float maxDeg, float stepDeg)
	{
		int numAngles = (int) ((maxDeg - minDeg) / stepDeg) + 1;
		float diagonal = (float) Math.hypot(width, height);
		int numBins = (int) (2 * diagonal) + 1;
		int distanceOffset = (int) diagonal;

		double[] cosTable = new double[numAngles];
		double[] sinTable = new double[numAngles];
		for (int i = 0; i < numAngles; i++)
		{
			double rad = Math.toRadians(minDeg + i * stepDeg);
			cosTable[i] = Math.cos(rad);
			sinTable[i] = Math.sin(rad);
		}

		int[] histogram = new int[numBins];
		int[] peakPerAngle = new int[numAngles]; // strongest single bin per angle

		for (int angleIdx = 0; angleIdx < numAngles; angleIdx++)
		{
			Arrays.fill(histogram, 0);
			double cos = cosTable[angleIdx];
			double sin = sinTable[angleIdx];
			for (int i = 0; i < edgeCount; i++)
			{
				int bin = (int) (edgeX[i] * cos + edgeY[i] * sin) + distanceOffset;
				if (bin >= 0 && bin < numBins)
				{
					histogram[bin]++;
				}
			}
			int maxBin = 0;
			for (int bin = 0; bin < numBins; bin++)
			{
				if (histogram[bin] > maxBin)
				{
					maxBin = histogram[bin];
				}
			}
			peakPerAngle[angleIdx] = maxBin;
		}

		// Find the angle whose strongest single line has the most votes
		int bestAngleIdx = 0;
		int bestPeak = 0;
		for (int angleIdx = 0; angleIdx < numAngles; angleIdx++)
		{
			if (peakPerAngle[angleIdx] > bestPeak)
			{
				bestPeak = peakPerAngle[angleIdx];
				bestAngleIdx = angleIdx;
			}
		}

		// Line must span at least 3% of image width
		if (bestPeak < Math.max(15, width * 3 / 100))
		{
			return Float.NaN;
		}

		float bestAngle = minDeg + bestAngleIdx * stepDeg;

		// Parabolic interpolation for sub-bin accuracy
		if (bestAngleIdx > 0 && bestAngleIdx < numAngles - 1)
		{
			float scoreLeft = peakPerAngle[bestAngleIdx - 1];
			float scoreCenter = peakPerAngle[bestAngleIdx];
			float scoreRight = peakPerAngle[bestAngleIdx + 1];
			float denom = scoreLeft - 2 * scoreCenter + scoreRight;
			if (denom != 0)
			{
				float delta = Math.clamp((scoreLeft - scoreRight) / (2f * denom), -0.5f, 0.5f);
				bestAngle += delta * stepDeg;
			}
		}

		return bestAngle;
	}

	private static float[] nonMaxSuppression(float[] magnitude, float[] direction, int width, int height)
	{
		float[] out = new float[width * height];
		for (int y = 1; y < height - 1; y++)
		{
			for (int x = 1; x < width - 1; x++)
			{
				int i = y * width + x;
				float center = magnitude[i];
				if (center == 0)
				{
					continue;
				}
				float angle = direction[i];
				if (angle < 0)
				{
					angle += (float) Math.PI;
				}
				// Sample the two neighbours along the gradient direction
				float neighbour1;
				float neighbour2;
				if (angle < Math.PI / 8 || angle >= 7 * Math.PI / 8)
				{
					neighbour1 = magnitude[y * width + x - 1];
					neighbour2 = magnitude[y * width + x + 1];
				}
				else if (angle < 3 * Math.PI / 8)
				{
					neighbour1 = magnitude[(y - 1) * width + x + 1];
					neighbour2 = magnitude[(y + 1) * width + x - 1];
				}
				else if (angle < 5 * Math.PI / 8)
				{
					neighbour1 = magnitude[(y - 1) * width + x];
					neighbour2 = magnitude[(y + 1) * width + x];
				}
				else
				{
					neighbour1 = magnitude[(y - 1) * width + x - 1];
					neighbour2 = magnitude[(y + 1) * width + x + 1];
				}
				out[i] = (center >= neighbour1 && center >= neighbour2) ? center : 0;
			}
		}
		return out;
	}

	private static void sobelGradient(float[] src, int width, int height,
		float[] magnitude, float[] direction)
	{
		for (int y = 1; y < height - 1; y++)
		{
			int prevRow = (y - 1) * width;
			int curRow = y * width;
			int nextRow = (y + 1) * width;
			for (int x = 1; x < width - 1; x++)
			{
				float topLeft  = src[prevRow + x - 1];
				float topCent  = src[prevRow + x];
				float topRight = src[prevRow + x + 1];
				float midLeft  = src[curRow + x - 1];
				float midRight = src[curRow + x + 1];
				float botLeft  = src[nextRow + x - 1];
				float botCent  = src[nextRow + x];
				float botRight = src[nextRow + x + 1];
				float gradX = -topLeft + topRight - 2 * midLeft + 2 * midRight - botLeft + botRight;
				float gradY = -topLeft - 2 * topCent - topRight + botLeft + 2 * botCent + botRight;
				int i = curRow + x;
				magnitude[i] = (float) Math.hypot(gradX, gradY);
				direction[i] = (float) Math.atan2(gradY, gradX);
			}
		}
	}
}
