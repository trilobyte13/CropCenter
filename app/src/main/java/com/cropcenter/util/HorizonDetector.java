package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.cropcenter.metadata.ExtendedXmpReassembler;
import com.cropcenter.metadata.JpegSegment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the horizon tilt angle for auto-rotation correction.
 *
 * Strategy (in priority order):
 *   1. XMP metadata: look for device roll angle (most accurate, ~0.01° precision)
 *   2. Computer vision: Canny edges + two-pass Hough transform (coarse 80–100° at 0.1° steps, then fine ±2° around
 *      the coarse peak at 0.01° steps), refined by an inlier-fit pass
 */
public final class HorizonDetector
{
	private static final String TAG = "HorizonDetector";
	// Cached compiled regex per attribute suffix. detectFromMetadata calls findXmpFloat twice per APP1 segment
	// (Roll, Tilt) and a JPEG can have several APP1s — recompiling each time was ~10 Pattern allocations per
	// Auto-rotate tap. ConcurrentHashMap because the auto-rotate path can run on any thread; the compile is
	// idempotent so a benign concurrent put is fine.
	private static final ConcurrentHashMap<String, Pattern> XMP_FLOAT_PATTERNS = new ConcurrentHashMap<>();
	private static final float COARSE_HOUGH_STEP_DEGREES = 0.1f;
	private static final float FINE_HOUGH_STEP_DEGREES = 0.01f;
	private static final float FINE_SEARCH_WINDOW_DEGREES = 2f;
	private static final float LINE_FIT_INLIER_DISTANCE_PX = 2f;
	// Both the metadata path (normalizeMetadataAngle) and the painted-region path
	// (runHoughAndConvertToRotation) reject tilts past this magnitude as "too far for auto-rotate to be a
	// reasonable correction" — large tilts indicate a held-sideways shot or sensor garbage, not a horizon
	// the user wants nudged. Sharing one constant prevents the two paths from disagreeing on the same image
	// (e.g. a 28° tilt accepted via Hough but rejected via XMP, dropping the user into paint mode purely
	// because metadata happened to be present).
	private static final float MAX_HORIZON_TILT_DEGREES = 30f;
	private static final float MAX_LINE_FIT_DELTA_DEGREES = 0.25f;
	// Pre-built 5x5 Gaussian convolution kernel (sigma ≈ 1.0). Hoisted from gaussianBlur5x5's body so we don't
	// allocate 25 floats per call — auto-rotate runs this on a multi-MP source and the per-call allocation
	// was pure waste.
	private static final float[] GAUSSIAN_5X5_KERNEL = {
		1 / 273f, 4 / 273f,  7 / 273f,  4 / 273f, 1 / 273f,
		4 / 273f, 16 / 273f, 26 / 273f, 16 / 273f, 4 / 273f,
		7 / 273f, 26 / 273f, 41 / 273f, 26 / 273f, 7 / 273f,
		4 / 273f, 16 / 273f, 26 / 273f, 16 / 273f, 4 / 273f,
		1 / 273f, 4 / 273f,  7 / 273f,  4 / 273f, 1 / 273f,
	};
	private static final int MIN_LINE_FIT_INLIERS = 30;

	private HorizonDetector() {}

	/**
	 * Detect horizon tilt from XMP metadata if available. EXIF-tag parsing (e.g. EXIF Roll, GPano roll,
	 * MakerNote vendor extensions) is intentionally NOT implemented — see REQUIREMENTS.md §5: every
	 * production capture path we've observed embeds the roll angle inside the XMP packet, so an EXIF
	 * fallback would add metadata-extraction surface for zero observed benefit.
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

			// XMP data starts after JpegSegment.XMP_HEADER (29 bytes) + APP1 header (4 bytes). seg.data() =
			// FF E1 LL LL [XMP identifier] [XML...]
			int xmlStart = 4 + JpegSegment.XMP_HEADER.length();
			byte[] segData = seg.data();
			if (segData.length <= xmlStart)
			{
				continue;
			}

			// XMP is UTF-8 per the XMP spec — explicit charset prevents mis-decoding on non-UTF8 system
			// locales where new String(...) would otherwise apply the platform default charset.
			String xml = new String(segData, xmlStart, segData.length - xmlStart, StandardCharsets.UTF_8);

			// Search for roll angle in common XMP properties. Different cameras use different namespaces:
			// GCamera:Roll, Device:Roll, samsung:LensRoll, exif:Roll, or generic Roll/Tilt attributes.
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

		// Adobe Extended XMP: a > 64 KB XMP packet split across multiple APP1 segments by GUID + offset
		// (Codex round-21 F1). Reassemble per-GUID before scanning so a Roll/Tilt attribute past the first
		// chunk OR straddling a chunk boundary isn't lost. Per-segment scan in the next pass would otherwise
		// miss "Ro|ll" split across chunks. Reassembly logic lives in metadata/ExtendedXmpReassembler so
		// HdrSignature can share it for the symmetric hdrgm-detection case.
		byte[] extendedBytes = ExtendedXmpReassembler.reassemble(meta);
		if (extendedBytes.length > 0)
		{
			String extendedXml = new String(extendedBytes, StandardCharsets.UTF_8);
			float roll = findXmpFloat(extendedXml, "Roll");
			if (!Float.isNaN(roll))
			{
				Log.d(TAG, "Found Roll in Extended XMP: " + roll + "°");
				return normalizeMetadataAngle(roll);
			}
			float tilt = findXmpFloat(extendedXml, "Tilt");
			if (!Float.isNaN(tilt))
			{
				Log.d(TAG, "Found Tilt in Extended XMP: " + tilt + "°");
				return normalizeMetadataAngle(tilt);
			}
		}

		// Final fallback: any APP1 segment that contains XML-like content with Roll / roll / Tilt. Catches
		// vendor shapes that don't use the canonical Adobe XMP namespace prefix (some Samsung / Pixel
		// firmwares ship roll under a vendor-defined APP1 marker structure).
		for (JpegSegment seg : meta)
		{
			byte[] segData = seg.data();
			if (seg.marker() != 0xE1 || segData.length < 50)
			{
				continue;
			}
			// UTF-8 explicit (see note on the primary loop). No length cap — APP1 segments are bounded by
			// the JPEG u16 length field at ~64 KB so a String allocation here is bounded; the previous
			// 65000-byte clamp could miss a Roll attribute landing in the trailing ~535 bytes of a maxed
			// segment (Codex round-21 F1).
			String raw = new String(segData, 4, segData.length - 4, StandardCharsets.UTF_8);
			// Lowercase pre-filter so a vendor segment with only `tilt="..."` (lowercase) isn't skipped —
			// findXmpFloat itself uses Pattern.CASE_INSENSITIVE so catching it here keeps the pre-filter
			// in step (Codex round-22 logic F1). Single toLowerCase pass over the raw body is bounded by
			// the JPEG APP1 ~64 KB cap so the cost is negligible per segment.
			String lower = raw.toLowerCase(Locale.ROOT);
			if (!lower.contains("roll") && !lower.contains("tilt"))
			{
				continue;
			}

			float roll = findXmpFloat(raw, "Roll");
			if (!Float.isNaN(roll))
			{
				Log.d(TAG, "Found Roll in APP1: " + roll + "°");
				return normalizeMetadataAngle(roll);
			}
			// Pre-filter above accepts Roll/roll/Tilt-bearing segments; the previous version only looked up
			// Roll here, so a segment that matched the pre-filter solely because of a Tilt attribute
			// returned NaN. Mirror the primary loop's Roll-then-Tilt fallback.
			float tilt = findXmpFloat(raw, "Tilt");
			if (!Float.isNaN(tilt))
			{
				Log.d(TAG, "Found Tilt in APP1: " + tilt + "°");
				return normalizeMetadataAngle(tilt);
			}
		}

		return Float.NaN;
	}


	/**
	 * Detect horizon angle using only edges within a user-painted region. The painted points define a brush stroke;
	 * only edge pixels near this stroke are used for the Hough line detection.
	 *
	 * @param src         source bitmap
	 * @param paintPoints list of (x,y) image-coordinate points from the paint stroke
	 * @param brushRadius radius in image pixels around each paint point
	 * @return correction angle in degrees, or NaN if not detected
	 */
	public static float detectFromPaintedRegion(Bitmap src, List<float[]> paintPoints, float brushRadius)
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
		for (float edge : edges)
		{
			if (edge > 0)
			{
				nonZero++;
				if (edge > maxVal)
				{
					maxVal = edge;
				}
			}
		}
		if (nonZero == 0 || maxVal == 0)
		{
			return Float.MAX_VALUE;
		}
		int bins = 256;
		int[] hist = new int[bins];
		for (float edge : edges)
		{
			if (edge > 0)
			{
				hist[Math.min(bins - 1, (int) (edge / maxVal * (bins - 1)))]++;
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

	private static float detectPaintedInternal(Bitmap src, List<float[]> paintPoints, float brushRadius)
	{
		int width = src.getWidth();
		int height = src.getHeight();
		int maskWidth = width / 4;
		int maskHeight = height / 4;

		boolean[] mask = rasterizePaintMask(paintPoints, maskWidth, maskHeight, brushRadius);
		float[] edges = buildEdgeMap(src, width, height);

		int[][] edgeCoords = gatherMaskedEdges(edges, mask, width, height, maskWidth, maskHeight);
		// Release the large intermediates before the coarse+fine Hough pass — on a large source `edges` alone
		// can be 100 MB of floats. Holding them alive through the Hough loops was a memory regression
		// introduced when this method was decomposed; keeping the scope tight avoids a mid-detection OOM on
		// mid-range devices that would not have fired before the refactor.
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
	 * Convert a raw roll/tilt reading from metadata into the UI's correction-angle convention:
	 * snap near-zero values to exact zero, reject implausibly-large tilts as NaN (bad sensor data),
	 * and otherwise invert and round to 2 decimal places. Shared across all XMP/APP1 entry points.
	 */
	private static float normalizeMetadataAngle(float deg)
	{
		// NaN bypasses both abs comparisons (NaN < x and NaN > x are always false), so an explicit guard
		// here prevents the round-then-divide path from producing -0.0f and silently announcing a
		// "valid 0° rotation" when XMP carries malformed roll data.
		if (Float.isNaN(deg) || !Float.isFinite(deg))
		{
			return Float.NaN;
		}
		if (Math.abs(deg) < BitmapUtils.ROTATION_EPSILON)
		{
			return 0f;
		}
		if (Math.abs(deg) > MAX_HORIZON_TILT_DEGREES)
		{
			return Float.NaN;
		}
		return RotationMath.snapToHundredth(-deg);
	}

	/**
	 * Stroke-to-mask rasterization. The paint stroke is rasterized at 1/4 source resolution into a boolean grid —
	 * enough precision to localize which source pixels belong to the horizon region, 16× cheaper in memory than a
	 * full-res mask.
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
		// width * height in int arithmetic silently overflows above ~46k px on a side. Use multiplyExact so a
		// pathological input (synthetic / panorama wider than 65k px) throws ArithmeticException up to the
		// outer catch in detectFromPaintedRegion (returns NaN) rather than allocating a negative-size array.
		int pixelCount = Math.multiplyExact(width, height);
		int[] pixels = new int[pixelCount];
		src.getPixels(pixels, 0, width, 0, 0, width, height);
		float[] luminance = new float[pixelCount];
		for (int i = 0; i < pixels.length; i++)
		{
			int pixel = pixels[i];
			luminance[i] = 0.299f * Color.red(pixel) + 0.587f * Color.green(pixel)
				+ 0.114f * Color.blue(pixel);
		}
		pixels = null;

		float[] blurred = gaussianBlur5x5(luminance, width, height);
		luminance = null;

		float[] gradientMag = new float[pixelCount];
		float[] gradientDir = new float[pixelCount];
		sobelGradient(blurred, width, height, gradientMag, gradientDir);
		blurred = null;

		float[] edges = nonMaxSuppression(gradientMag, gradientDir, width, height);

		// Direction filter: keep only near-horizontal edges (±35° from horizontal).
		for (int i = 0; i < pixelCount; i++)
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
	 * Collect the coordinates of edge pixels that survive the strength threshold AND lie within the painted mask.
	 * Returns {edgeX[], edgeY[]} packed as a 2-element array, or null when fewer than 30 pixels qualify (not enough
	 * signal for the Hough pass to produce a trustworthy angle).
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
	 * Two-pass Hough transform on the masked edge pixels (coarse 80–100° at 0.1° steps, then fine ±2° around the
	 * coarse peak at 0.01° steps), converted to a rotation angle the editor can apply directly. Returns NaN when
	 * the tilt is beyond ±30° (the detector is too unreliable at larger angles), 0 when the tilt is effectively
	 * zero, or the rounded-to-0.01° rotation otherwise.
	 */
	private static float runHoughAndConvertToRotation(int[] edgeX, int[] edgeY,
		int edgeCount, int width, int height)
	{
		float coarseAngle = houghPass(edgeX, edgeY, edgeCount, width, height,
			80f, 100f, COARSE_HOUGH_STEP_DEGREES);
		if (Float.isNaN(coarseAngle))
		{
			return Float.NaN;
		}

		float fineAngle = houghPass(edgeX, edgeY, edgeCount, width, height,
			Math.max(80f, coarseAngle - FINE_SEARCH_WINDOW_DEGREES),
			Math.min(100f, coarseAngle + FINE_SEARCH_WINDOW_DEGREES), FINE_HOUGH_STEP_DEGREES);
		if (Float.isNaN(fineAngle))
		{
			fineAngle = coarseAngle;
		}
		else
		{
			float refinedAngle = refineLineFitAngle(edgeX, edgeY, edgeCount, width, height, fineAngle);
			if (!Float.isNaN(refinedAngle))
			{
				fineAngle = refinedAngle;
			}
		}

		float tilt = fineAngle - 90f;
		Log.d(TAG, "Painted region tilt: " + String.format(Locale.ROOT, "%.3f", tilt) + "°");

		if (Float.isNaN(tilt) || !Float.isFinite(tilt))
		{
			return Float.NaN;
		}
		if (Math.abs(tilt) < BitmapUtils.ROTATION_EPSILON)
		{
			return 0f;
		}
		if (Math.abs(tilt) > MAX_HORIZON_TILT_DEGREES)
		{
			return Float.NaN;
		}
		return RotationMath.snapToHundredth(-tilt);
	}

	/**
	 * Search XMP XML for a float attribute whose name is exactly attrSuffix, optionally with a namespace prefix.
	 * Handles patterns like: namespace:Roll="1.23" or Roll="1.23". The earlier version used "\\w*:?Suffix" which
	 * greedy-matched unrelated names like CameraRoll or GyroRoll — any attribute whose name ends in the literal
	 * suffix — and silently returned their value as the horizon angle.
	 */
	private static float findXmpFloat(String xml, String attrSuffix)
	{
		// Require either the start of a token (non-word char) or start of string, then an optional namespace
		// prefix that ends in ':', then the exact suffix followed by whitespace or '='. This rules out AbcRoll,
		// CameraRoll, GyroRoll, etc. Pattern is cached per suffix.
		Pattern pattern = XMP_FLOAT_PATTERNS.computeIfAbsent(attrSuffix, suffix ->
			Pattern.compile(
				"(?:^|[^\\w:])(?:\\w+:)?" + Pattern.quote(suffix) + "\\s*=\\s*\"([^\"]+)\"",
				Pattern.CASE_INSENSITIVE));
		Matcher matcher = pattern.matcher(xml);
		while (matcher.find())
		{
			try
			{
				return Float.parseFloat(matcher.group(1).trim());
			}
			catch (NumberFormatException ignored)
			{
				// Some namespaces store the attribute name as a non-float (CDATA fragment, enum
				// token, IETF locale) — skip and try the next match; a later occurrence may parse.
			}
		}
		return Float.NaN;
	}

	/**
	 * Refine the Hough winner with a least-squares fit over the edge pixels that sit on the winning line.
	 *
	 * The Hough pass votes into integer-distance bins, so a real-world horizon can sit between bins and still land
	 * one or two ruler ticks off. Once Hough has chosen the correct line, fitting the actual inlier coordinates
	 * recovers the sub-bin slope while retaining Hough's outlier rejection. Returns NaN when the fit is too weak or
	 * disagrees too much with the Hough seed, in which case the caller keeps the original Hough angle.
	 *
	 * @param edgeX         edge pixel X coordinates
	 * @param edgeY         edge pixel Y coordinates
	 * @param edgeCount     number of valid coordinates in edgeX / edgeY
	 * @param width         source bitmap width, used for the minimum inlier threshold
	 * @param height        source bitmap height, used to reproduce the Hough distance-bin geometry
	 * @param houghAngleDeg Hough normal angle in degrees
	 * @return refined Hough normal angle in degrees, or NaN when the fit should be ignored
	 */
	static float refineLineFitAngle(int[] edgeX, int[] edgeY, int edgeCount,
		int width, int height, float houghAngleDeg)
	{
		double rad = Math.toRadians(houghAngleDeg);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		float diagonal = (float) Math.hypot(width, height);
		int numBins = (int) (2 * diagonal) + 1;
		int distanceOffset = (int) diagonal;
		int[] histogram = new int[numBins];

		for (int i = 0; i < edgeCount; i++)
		{
			int bin = (int) Math.floor(edgeX[i] * cos + edgeY[i] * sin) + distanceOffset;
			if (bin >= 0 && bin < numBins)
			{
				histogram[bin]++;
			}
		}

		int bestBin = 0;
		int bestCount = 0;
		for (int bin = 0; bin < numBins; bin++)
		{
			if (histogram[bin] > bestCount)
			{
				bestCount = histogram[bin];
				bestBin = bin;
			}
		}

		int minInliers = Math.max(MIN_LINE_FIT_INLIERS, width * 3 / 100);
		if (bestCount < minInliers)
		{
			return Float.NaN;
		}

		double rhoCenter = bestBin - distanceOffset + 0.5;
		double sumX = 0;
		double sumY = 0;
		double sumXX = 0;
		double sumXY = 0;
		int inliers = 0;
		for (int i = 0; i < edgeCount; i++)
		{
			double rho = edgeX[i] * cos + edgeY[i] * sin;
			if (Math.abs(rho - rhoCenter) > LINE_FIT_INLIER_DISTANCE_PX)
			{
				continue;
			}
			double x = edgeX[i];
			double y = edgeY[i];
			sumX += x;
			sumY += y;
			sumXX += x * x;
			sumXY += x * y;
			inliers++;
		}
		if (inliers < minInliers)
		{
			return Float.NaN;
		}

		double denom = sumXX - sumX * sumX / inliers;
		if (denom <= 1e-6)
		{
			return Float.NaN;
		}
		double slope = (sumXY - sumX * sumY / inliers) / denom;
		// NaN slope (sumXX == sumX^2/inliers via floating-point underflow not caught by denom > 1e-6) would
		// flow into atan → NaN → 90+NaN=NaN. The Math.clamp below would return NaN; the caller treats the
		// result as a real rotation. Guard explicitly so a degenerate inlier set returns the spec'd "no
		// reading" sentinel.
		if (Double.isNaN(slope) || Double.isInfinite(slope))
		{
			return Float.NaN;
		}
		float refinedAngle = 90f + (float) Math.toDegrees(Math.atan(slope));
		if (Math.abs(refinedAngle - houghAngleDeg) > MAX_LINE_FIT_DELTA_DEGREES)
		{
			return Float.NaN;
		}
		return Math.clamp(refinedAngle, 80f, 100f);
	}

	private static float[] gaussianBlur5x5(float[] src, int width, int height)
	{
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
							* GAUSSIAN_5X5_KERNEL[(kernelY + 2) * 5 + (kernelX + 2)];
					}
				}
				dst[y * width + x] = sum;
			}
		}
		return dst;
	}

	/**
	 * Hough transform: find the angle of the single strongest near-horizontal line. Uses max-single-bin (longest
	 * line wins) rather than sum-of-squares (all edges).
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
				int bin = (int) Math.floor(edgeX[i] * cos + edgeY[i] * sin) + distanceOffset;
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

	private static void sobelGradient(float[] src, int width, int height, float[] magnitude, float[] direction)
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
