package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import androidx.annotation.WorkerThread;

import com.cropcenter.metadata.ExtendedXmpReassembler;
import com.cropcenter.metadata.JpegMarker;
import com.cropcenter.metadata.JpegSegment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
	// (Roll, Tilt) and a JPEG can have several APP1s — per-call recompiles would cost ~10 Pattern allocations per
	// Auto-rotate tap. ConcurrentHashMap because the auto-rotate path can run on any thread; the compile is
	// idempotent so a benign concurrent put is fine.
	private static final ConcurrentHashMap<String, Pattern> XMP_FLOAT_PATTERNS = new ConcurrentHashMap<>();
	private static final float COARSE_HOUGH_STEP_DEGREES = 0.1f;
	private static final float FINE_HOUGH_STEP_DEGREES = 0.01f;
	private static final float FINE_SEARCH_WINDOW_DEGREES = 2f;
	// Inlier window for the LSQ-refinement pass. 3 px so soft horizons (ocean-sky, sky-haze) whose Canny-suppressed
	// edge pixels scatter ±2–3 px from the true line still contribute to the fit. A tighter window leaves most of
	// the gradient outside it, biasing the slope toward the strongest sub-pixel-aligned ridge instead of the
	// overall trend.
	private static final float LINE_FIT_INLIER_DISTANCE_PX = 3f;
	// The metadata path (normalizeMetadataAngle) rejects roll values past this magnitude as "too far for
	// auto-rotate to be a reasonable correction" — large tilts indicate a held-sideways shot or sensor garbage, not
	// a horizon the user wants nudged. The painted-region path needs no such gate: its Hough sweep is bounded to
	// [80°, 100°] and refineLineFitAngle clamps to the same range, so painted tilt is structurally capped at ±10°.
	private static final float MAX_HORIZON_TILT_DEGREES = 30f;
	// Maximum LSQ-vs-Hough disagreement we accept as a refinement. 0.5° gives the LSQ pass room to correct a noisy
	// Hough peak (common on soft horizons where the gradient spreads across several distance bins). The LSQ inlier
	// set is centered on the Hough peak's rho bin so a 0.5° disagreement still corresponds to a coherent line —
	// wider than that and the LSQ is fitting a different feature than the Hough seed found.
	private static final float MAX_LINE_FIT_DELTA_DEGREES = 0.5f;
	// Pre-built 5x5 Gaussian convolution kernel (sigma ≈ 1.0). Class-level so gaussianBlur5x5 doesn't allocate 25
	// floats per call — auto-rotate runs it on a multi-MP source.
	private static final float[] GAUSSIAN_5X5_KERNEL = {
		1 / 273f, 4 / 273f,  7 / 273f,  4 / 273f, 1 / 273f, 4 / 273f, 16 / 273f, 26 / 273f, 16 / 273f, 4 / 273f,
		7 / 273f, 26 / 273f, 41 / 273f, 26 / 273f, 7 / 273f,
		4 / 273f, 16 / 273f, 26 / 273f, 16 / 273f, 4 / 273f, 1 / 273f, 4 / 273f,  7 / 273f,  4 / 273f, 1 / 273f,
	};
	// Downscale factor between the source-resolution edge map and the painted-region mask grid (16× cheaper in
	// memory than a full-res mask). Single chokepoint for rasterizePaintMask (stroke rasterization),
	// gatherMaskedEdges (per-pixel mask lookup), and detectPaintedInternal (grid sizing) — the three must agree or
	// mask indexing silently corrupts with no error.
	private static final int MASK_DOWNSCALE = 4;
	// Absolute floor on the winning Hough peak's vote count (paired with the VOTE_FLOOR_WIDTH_PERCENT term).
	// Deliberately lower than MIN_LINE_FIT_INLIERS: 15 votes in one (angle, rho) bin is enough evidence to SEED
	// refinement — the seed is only trusted after refineLineFitAngle's stricter re-vote gate, which needs the
	// larger sample for a stable least-squares slope.
	private static final int MIN_HOUGH_PEAK_VOTES = 15;
	private static final int MIN_LINE_FIT_INLIERS = 30;
	// Minimum edge-pixel count surviving the mask intersection before the Hough vote can run. Below this, the vote
	// produces noise-driven peaks with no statistical signal — bail to a NaN "no line detected" rather than ship a
	// random angle. Numerically equal to MIN_LINE_FIT_INLIERS by coincidence, not design (both pinned at 30); kept
	// as a separate constant because the rationales diverge — this gate is about Hough-vote sample-size sanity,
	// MIN_LINE_FIT_INLIERS is about RANSAC inlier-count sanity. If a future tuning pass adjusts one, the other
	// shouldn't drift in lockstep.
	private static final int MIN_MASKED_EDGE_PIXELS = 30;
	// Percent of image width a credible horizon line's vote count must span. Shared proportional term of the two
	// vote-floor gates: the Hough peak gate (absolute floor MIN_HOUGH_PEAK_VOTES) and refineLineFitAngle's re-vote
	// gate (absolute floor MIN_LINE_FIT_INLIERS) — the fraction is common, the absolute minimums deliberately
	// differ (see MIN_HOUGH_PEAK_VOTES).
	private static final int VOTE_FLOOR_WIDTH_PERCENT = 3;

	private HorizonDetector() {}

	/**
	 * Detect horizon tilt from XMP metadata if available. EXIF-tag parsing (e.g. EXIF Roll, GPano roll, MakerNote
	 * vendor extensions) is intentionally NOT implemented — see REQUIREMENTS.md §5: every production capture path
	 * we've observed embeds the roll angle inside the XMP packet, so an EXIF fallback would add metadata-extraction
	 * surface for zero observed benefit.
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
			Optional<Float> angle = findRollOrTilt(xml, "XMP");
			if (angle.isPresent())
			{
				return angle.orElseThrow();
			}
		}

		// Adobe Extended XMP: a > 64 KB XMP packet split across multiple APP1 segments by GUID + offset.
		// Reassemble per-GUID before scanning so a Roll/Tilt attribute past the first chunk OR straddling a
		// chunk boundary isn't lost. Per-segment scan in the next pass would otherwise miss "Ro|ll" split
		// across chunks. Each GUID group is one XMP document and scans independently — an attribute must
		// never synthesize across the boundary between two groups. Reassembly logic lives in
		// metadata/ExtendedXmpReassembler so HdrSignature can share it for the symmetric hdrgm-detection case.
		for (byte[] groupBytes : ExtendedXmpReassembler.reassemble(meta))
		{
			String extendedXml = new String(groupBytes, StandardCharsets.UTF_8);
			Optional<Float> extendedAngle = findRollOrTilt(extendedXml, "Extended XMP");
			if (extendedAngle.isPresent())
			{
				return extendedAngle.orElseThrow();
			}
		}

		// Final fallback: any APP1 segment that contains XML-like content with Roll / roll / Tilt. Catches
		// vendor shapes that don't use the canonical Adobe XMP namespace prefix (some Samsung / Pixel firmwares
		// ship roll under a vendor-defined APP1 marker structure).
		for (JpegSegment seg : meta)
		{
			byte[] segData = seg.data();
			if (seg.marker() != JpegMarker.APP1 || segData.length < 50)
			{
				continue;
			}
			// UTF-8 explicit (see note on the primary loop). No length cap — APP1 segments are bounded by
			// the JPEG u16 length field at ~64 KB so a String allocation here is bounded; a shorter clamp
			// could miss a Roll attribute landing in the trailing bytes of a maxed segment.
			String raw = new String(segData, 4, segData.length - 4, StandardCharsets.UTF_8);
			// Case-insensitive contains-check directly against `raw` — no toLowerCase allocation. A
			// lowercase pass would copy up to ~64 KB per APP1 segment just to gate on contains(), and
			// findXmpFloat already runs with Pattern.CASE_INSENSITIVE, so the lowercasing would be
			// duplicated work on the AutoRotate hot path (each segment can hit this loop dozens of times in
			// a multi-APP1 file). containsIgnoreCase below walks the raw bytes once with a per-character
			// case-folded comparison — no allocation, no GC pressure.
			if (!containsIgnoreCase(raw, "roll") && !containsIgnoreCase(raw, "tilt"))
			{
				continue;
			}

			Optional<Float> fallbackAngle = findRollOrTilt(raw, "APP1");
			if (fallbackAngle.isPresent())
			{
				return fallbackAngle.orElseThrow();
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
	@WorkerThread
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
			// Pass the exception so the stack trace surfaces in logcat; without it we lose the allocation
			// site that triggered the OOM (typically buildEdgeMap's float[] trio).
			Log.w(TAG, "OOM in painted detection", e);
			return Float.NaN;
		}
	}

	/**
	 * Refine the Hough winner with two iterative least-squares passes over the inlier edge pixels. Hough votes into
	 * integer-distance bins, so a real horizon can sit between bins and land one or two ticks off; fitting the
	 * actual inlier coordinates recovers the sub-bin slope while keeping Hough's outlier rejection.
	 *
	 * Pass 1 uses houghAngleDeg's (cos, sin) to find the densest rho bin + inliers within
	 * ±LINE_FIT_INLIER_DISTANCE_PX and LSQ-fits their slope; pass 2 re-projects with that refined angle, re-votes,
	 * re-selects, and re-fits — converging off the Hough seed's bin-quantization. The result is checked against
	 * houghAngleDeg with MAX_LINE_FIT_DELTA_DEGREES; large disagreement means the LSQ locked onto a different
	 * feature, and the caller falls back to the Hough seed.
	 *
	 * @param edgeX         edge pixel X coordinates
	 * @param edgeY         edge pixel Y coordinates
	 * @param edgeCount     number of valid coordinates in edgeX / edgeY
	 * @param width         source bitmap width, for the minimum inlier threshold
	 * @param height        source bitmap height, to reproduce the Hough distance-bin geometry
	 * @param houghAngleDeg Hough normal angle in degrees
	 * @return refined Hough normal angle in degrees, or NaN when the fit should be ignored
	 */
	static float refineLineFitAngle(int[] edgeX, int[] edgeY, int edgeCount,
		int width, int height, float houghAngleDeg)
	{
		float pass1 = lsqPassAtSeed(edgeX, edgeY, edgeCount, width, height, houghAngleDeg);
		if (Float.isNaN(pass1))
		{
			return Float.NaN;
		}
		float pass2 = lsqPassAtSeed(edgeX, edgeY, edgeCount, width, height, pass1);
		if (Float.isNaN(pass2))
		{
			// Pass 2 went degenerate (the refined angle's basis collapsed denom under 1e-6 or threw out the
			// inlier count). The pass-1 result is still a sub-bin improvement over Hough, so fall back to
			// it rather than discarding the refinement entirely.
			pass2 = pass1;
		}
		if (Math.abs(pass2 - houghAngleDeg) > MAX_LINE_FIT_DELTA_DEGREES)
		{
			return Float.NaN;
		}
		return Math.clamp(pass2, 80f, 100f);
	}

	// ── Image processing primitives ── Package-private rather than private so HorizonDetectorTest can pin the
	// pure-array math without an Android Bitmap fixture — same pattern refineLineFitAngle uses above.

	/**
	 * Otsu-style top-percentile threshold over a non-negative edge magnitude array. Builds a 256-bin histogram
	 * normalised to the maximum non-zero value, then returns the magnitude at which the cumulative count from the
	 * bottom reaches `(1 - topFraction)` of the non-zero population — i.e. the cutoff that isolates the top
	 * `topFraction` strongest edges. Float.MAX_VALUE on a no-edge input (all zeros or empty) so callers' "above
	 * threshold" tests cleanly reject; `maxVal * 0.5f` if the histogram walk runs off the end (numerical edge case
	 * from rounding into the top bin).
	 *
	 * @param edges       non-negative edge magnitudes, one per source pixel; zero entries are treated as no
	 *                    edge and skipped
	 * @param topFraction fraction of the non-zero edge population to keep ABOVE the returned threshold,
	 *                    in [0, 1] (e.g. 0.1 = top 10% of edges)
	 * @return magnitude threshold; edges strictly greater than this are the top-topFraction population;
	 *         Float.MAX_VALUE when no non-zero edges exist
	 */
	static float computeThreshold(float[] edges, float topFraction)
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

	/**
	 * Stroke-to-mask rasterization. The paint stroke is rasterized at 1/MASK_DOWNSCALE source resolution into a
	 * boolean grid — enough precision to localize which source pixels belong to the horizon region, far cheaper in
	 * memory than a full-res mask.
	 *
	 * @param paintPoints stroke points in source-pixel coordinates
	 * @param maskWidth   mask grid width (source width / MASK_DOWNSCALE)
	 * @param maskHeight  mask grid height (source height / MASK_DOWNSCALE)
	 * @param brushRadius brush radius in source pixels (scaled to mask resolution internally)
	 * @return boolean mask of length maskWidth * maskHeight, true where a stroke point falls within brushRadius
	 */
	static boolean[] rasterizePaintMask(List<float[]> paintPoints, int maskWidth, int maskHeight, float brushRadius)
	{
		boolean[] mask = new boolean[maskWidth * maskHeight];
		float maskRadius = brushRadius / MASK_DOWNSCALE;
		float maskRadiusSquared = maskRadius * maskRadius;

		for (float[] paintPoint : paintPoints)
		{
			int centerX = (int) (paintPoint[0] / MASK_DOWNSCALE);
			int centerY = (int) (paintPoint[1] / MASK_DOWNSCALE);
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
	 * Collect the coordinates of edge pixels that survive the strength threshold AND lie within the painted mask.
	 * The strength threshold is computed over edges INSIDE the mask only — so a soft ocean-sky horizon stays
	 * detectable even when the rest of the image has strong edges (foreground rocks, foliage, foam) that would
	 * otherwise pull a global threshold above the soft gradient and silently filter every horizon edge out.
	 *
	 * @param edges      per-pixel edge-strength map (length width * height)
	 * @param mask       painted-region mask at 1/MASK_DOWNSCALE resolution (length maskWidth * maskHeight)
	 * @param width      source-resolution edge-map width
	 * @param height     source-resolution edge-map height
	 * @param maskWidth  mask grid width (width / MASK_DOWNSCALE)
	 * @param maskHeight mask grid height (height / MASK_DOWNSCALE)
	 * @return {edgeX[], edgeY[]} packed as a 2-element array, or empty when fewer than
	 *         MIN_MASKED_EDGE_PIXELS qualify (not enough signal for the Hough pass to produce a trustworthy angle)
	 */
	static Optional<int[][]> gatherMaskedEdges(float[] edges, boolean[] mask,
		int width, int height, int maskWidth, int maskHeight)
	{
		// Precompute the mask-coordinate lookup so the inner loop avoids repeated Math.min calls.
		// maskRowOffset[y] = maskY * maskWidth (the absolute index of row y's first mask sample).
		// maskColIndex[x] = the mask sample's column index for image column x. Together they let the inner loop
		// compute the mask bit's flat index as a single int add: maskRowOffset[y] + maskColIndex[x]. For a 16
		// MP proxy this eliminates ~32 M Math.min calls.
		int[] maskRowOffset = new int[height];
		for (int y = 0; y < height; y++)
		{
			maskRowOffset[y] = Math.min(y / MASK_DOWNSCALE, maskHeight - 1) * maskWidth;
		}
		int[] maskColIndex = new int[width];
		for (int x = 0; x < width; x++)
		{
			maskColIndex[x] = Math.min(x / MASK_DOWNSCALE, maskWidth - 1);
		}

		// Walk 1: collect every non-zero edge magnitude inside the painted mask into a growable primitive
		// buffer (initial 1024, double on overflow — amortised O(n) growth, no per-element boxing). Without the
		// masked threshold, a shoreline composition where the painted region's strongest edges are 5-10× weaker
		// than the off-region foreground rocks / foam would lose the entire horizon below the global top-15%
		// cutoff.
		float[] maskedEdges = new float[1024];
		int maskedCount = 0;
		for (int y = 0; y < height; y++)
		{
			int rowOffset = y * width;
			int maskRow = maskRowOffset[y];
			for (int x = 0; x < width; x++)
			{
				float edgeMagnitude = edges[rowOffset + x];
				if (edgeMagnitude > 0 && mask[maskRow + maskColIndex[x]])
				{
					if (maskedCount == maskedEdges.length)
					{
						maskedEdges = Arrays.copyOf(maskedEdges, maskedEdges.length * 2);
					}
					maskedEdges[maskedCount++] = edgeMagnitude;
				}
			}
		}
		// Trim to actual length before percentile computation — computeThreshold reads array.length.
		if (maskedCount < maskedEdges.length)
		{
			maskedEdges = Arrays.copyOf(maskedEdges, maskedCount);
		}
		float threshold = computeThreshold(maskedEdges, 0.15f);

		// Walk 2: collect (x, y) pairs whose edge magnitude clears the threshold. Same growable pattern — a
		// single fused count-and-fill walk instead of a count walk plus a fill walk.
		int[] edgeX = new int[1024];
		int[] edgeY = new int[1024];
		int edgeCount = 0;
		for (int y = 0; y < height; y++)
		{
			int rowOffset = y * width;
			int maskRow = maskRowOffset[y];
			for (int x = 0; x < width; x++)
			{
				if (edges[rowOffset + x] >= threshold && mask[maskRow + maskColIndex[x]])
				{
					if (edgeCount == edgeX.length)
					{
						int newCap = edgeX.length * 2;
						edgeX = Arrays.copyOf(edgeX, newCap);
						edgeY = Arrays.copyOf(edgeY, newCap);
					}
					edgeX[edgeCount] = x;
					edgeY[edgeCount] = y;
					edgeCount++;
				}
			}
		}
		if (edgeCount < MIN_MASKED_EDGE_PIXELS)
		{
			Log.d(TAG, "Too few masked edge pixels: " + edgeCount);
			return Optional.empty();
		}
		// Trim if we over-allocated. The downstream LSQ + Hough consumers honor array.length.
		if (edgeCount < edgeX.length)
		{
			edgeX = Arrays.copyOf(edgeX, edgeCount);
			edgeY = Arrays.copyOf(edgeY, edgeCount);
		}
		return Optional.of(new int[][] { edgeX, edgeY });
	}

	/**
	 * Two-pass Hough transform on the masked edge pixels (coarse 80–100° at 0.1° steps, then fine ±2° around the
	 * coarse peak at 0.01° steps), converted to a rotation angle the editor can apply directly. The sweep bounds
	 * (and refineLineFitAngle's matching clamp) structurally cap the result at ±10° tilt. Returns NaN when the
	 * Hough vote produces no peak, 0 when the tilt is effectively zero, or the rounded-to-0.01° rotation otherwise.
	 *
	 * @param edgeX     x-coordinates of masked edge pixels
	 * @param edgeY     y-coordinates of masked edge pixels (parallel to edgeX)
	 * @param edgeCount number of valid entries in edgeX / edgeY (arrays may be over-allocated)
	 * @param width     source-image width (for the Hough distance range)
	 * @param height    source-image height
	 * @return rotation in degrees rounded to 0.01°, 0 when effectively level, or NaN when the Hough vote
	 *         produced no peak
	 */
	static float runHoughAndConvertToRotation(int[] edgeX, int[] edgeY, int edgeCount, int width, int height)
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

		if (!Float.isFinite(tilt))
		{
			return Float.NaN;
		}
		if (Math.abs(tilt) < BitmapUtils.ROTATION_EPSILON)
		{
			return 0f;
		}
		return RotationMath.snapToHundredth(-tilt);
	}

	/**
	 * 5x5 Gaussian convolution using the class-level GAUSSIAN_5X5_KERNEL (sigma ≈ 1.0). The 2-pixel border is left
	 * at zero rather than edge-extended — the downstream Sobel / suppression passes skip borders anyway.
	 * Package-private so the unit tests can pin the kernel weights without a Bitmap fixture.
	 *
	 * @param src    row-major luminance values; read only
	 * @param width  image width in pixels
	 * @param height image height in pixels
	 * @return freshly allocated row-major blurred array of the same dimensions
	 */
	static float[] gaussianBlur5x5(float[] src, int width, int height)
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
	 *
	 * @param edgeX     x-coordinates of masked edge pixels
	 * @param edgeY     y-coordinates of masked edge pixels (parallel to edgeX)
	 * @param edgeCount number of valid entries in edgeX / edgeY
	 * @param width     source-image width (for the Hough distance range)
	 * @param height    source-image height
	 * @param minDeg    inclusive lower bound of the angle sweep, in degrees
	 * @param maxDeg    inclusive upper bound of the angle sweep, in degrees
	 * @param stepDeg   angular resolution of the sweep, in degrees
	 * @return the peak-bin angle in degrees, or NaN when no bin accumulates enough votes
	 */
	static float houghPass(int[] edgeX, int[] edgeY, int edgeCount,
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

		// Line must span at least VOTE_FLOOR_WIDTH_PERCENT of image width
		if (bestPeak < Math.max(MIN_HOUGH_PEAK_VOTES, width * VOTE_FLOOR_WIDTH_PERCENT / 100))
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

	/**
	 * Canny-style edge thinning: quantize each pixel's gradient direction into one of four sectors and keep its
	 * magnitude only when it is ≥ both neighbors along that direction, so ridges collapse to one-pixel-wide lines
	 * the Hough vote can bin cleanly. The 1-pixel border stays zero. Package-private for unit tests.
	 *
	 * @param magnitude row-major Sobel gradient magnitudes; read only
	 * @param direction row-major Sobel gradient directions in radians (atan2 range); read only
	 * @param width     image width in pixels
	 * @param height    image height in pixels
	 * @return freshly allocated row-major array with non-maximal magnitudes zeroed
	 */
	static float[] nonMaxSuppression(float[] magnitude, float[] direction, int width, int height)
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

	/**
	 * 3x3 Sobel gradient over a luminance array, filling the caller-supplied magnitude and direction arrays in
	 * place (out-parameters — the caller allocates both once and this method avoids two more full-size allocations
	 * on multi-MB working sets). The 1-pixel border is left untouched. Package-private for unit tests.
	 *
	 * @param src       row-major luminance values (typically the Gaussian-blurred map); read only
	 * @param width     image width in pixels
	 * @param height    image height in pixels
	 * @param magnitude out: gradient magnitude per pixel (hypot of the two Sobel responses)
	 * @param direction out: gradient direction per pixel in radians (atan2(gradY, gradX))
	 */
	static void sobelGradient(float[] src, int width, int height, float[] magnitude, float[] direction)
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

	// ── Private helpers ── Internal dispatchers and small private utilities. These call into the package-private
	// primitives above but stay private themselves — either because they need a Bitmap fixture (untestable on the
	// host JVM) or because their only meaningful behavior is exercised through the public entry points.

	/**
	 * Canny-style edge pipeline: luminance → Gaussian blur → Sobel magnitude + direction
	 * → non-max suppression → direction filter (keep only edges within 35° of
	 * horizontal). Intermediate arrays are nulled as soon as they're consumed to let
	 * GC reclaim the ~4 MB working sets early on mid-range devices.
	 *
	 * @param src    source bitmap; read via getPixels, never recycled here
	 * @param width  bitmap width in pixels
	 * @param height bitmap height in pixels
	 * @return row-major edge-strength map; zero everywhere except thinned near-horizontal edges
	 */
	private static float[] buildEdgeMap(Bitmap src, int width, int height)
	{
		// width * height in int arithmetic silently overflows above ~46k px on a side. Use multiplyExact so a
		// pathological input (synthetic / panorama wider than 65k px) throws ArithmeticException — absorbed by
		// AutoRotateBinder's catch (Exception | StackOverflowError), surfacing the detection-failed toast —
		// rather than allocating a negative-size array. (Unreachable in practice: Android caps Bitmap dims at
		// 32768.)
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
	 * Case-insensitive substring search without allocating a lowercased copy of the haystack. Used by
	 * detectFromMetadata's final APP1 fallback prefilter — toLowerCase on the full ~64 KB segment body would be
	 * bounded but allocation-heavy, and it runs per segment per autoRotate tap.
	 *
	 * @param haystack string to search inside (typically the raw APP1 XML body)
	 * @param needle   substring to match (all callers pass lowercase ASCII literals like "roll" or "tilt")
	 * @return true when haystack contains needle ignoring case
	 */
	private static boolean containsIgnoreCase(String haystack, String needle)
	{
		int needleLen = needle.length();
		int limit = haystack.length() - needleLen;
		for (int i = 0; i <= limit; i++)
		{
			// regionMatches(true, ...) is allocation-free — no lowercased haystack copy per call.
			if (haystack.regionMatches(true, i, needle, 0, needleLen))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Painted-region detection pipeline: rasterize the stroke into a 1/MASK_DOWNSCALE-resolution mask, build the
	 * full-resolution edge map, gather the edge pixels under the mask, then run the coarse + refined Hough pass.
	 * The large intermediates (edge map can be ~100 MB of floats on a big source) are nulled before the Hough loops
	 * to keep peak memory down.
	 *
	 * @param src         bitmap to detect against; read only
	 * @param paintPoints stroke points as {x, y} pairs in bitmap coordinates
	 * @param brushRadius brush radius in bitmap pixels (rasterizePaintMask scales it to the downscaled mask)
	 * @return correction angle in degrees, or NaN when too few masked edges exist or no line wins the Hough vote
	 */
	private static float detectPaintedInternal(Bitmap src, List<float[]> paintPoints, float brushRadius)
	{
		int width = src.getWidth();
		int height = src.getHeight();
		int maskWidth = width / MASK_DOWNSCALE;
		int maskHeight = height / MASK_DOWNSCALE;

		boolean[] mask = rasterizePaintMask(paintPoints, maskWidth, maskHeight, brushRadius);
		float[] edges = buildEdgeMap(src, width, height);

		Optional<int[][]> maskedEdgeCoords = gatherMaskedEdges(edges, mask, width, height, maskWidth,
			maskHeight);
		// Release the large intermediates before the coarse+fine Hough pass — on a large source `edges` alone
		// can be 100 MB of floats. Holding them alive through the Hough loops risks a mid-detection OOM on
		// mid-range devices; the explicit nulls keep the effective scope tight.
		edges = null;
		mask = null;
		if (maskedEdgeCoords.isEmpty())
		{
			return Float.NaN;
		}
		int[][] edgeCoords = maskedEdgeCoords.orElseThrow();
		int[] edgeX = edgeCoords[0];
		int[] edgeY = edgeCoords[1];
		int edgeCount = edgeX.length;
		Log.d(TAG, "Masked edge pixels: " + edgeCount);

		return runHoughAndConvertToRotation(edgeX, edgeY, edgeCount, width, height);
	}

	/**
	 * Look up a Roll attribute, then a Tilt fallback, in one decoded XMP/XML text and normalize the first hit.
	 * Roll > 0 typically means CW tilt needing CCW correction; some cameras store pitch/tilt instead of roll,
	 * so a Roll-only lookup would miss Tilt-bearing segments.
	 *
	 * @param xml         decoded XMP/XML text to search
	 * @param sourceLabel log label naming which metadata pass supplied the text
	 * @return normalized correction angle when a Roll or Tilt attribute parses (NaN when normalizeMetadataAngle
	 *         rejects the parsed value — the search stops at the first parsed attribute either way), or empty
	 *         when neither attribute exists
	 */
	private static Optional<Float> findRollOrTilt(String xml, String sourceLabel)
	{
		float roll = findXmpFloat(xml, "Roll");
		if (!Float.isNaN(roll))
		{
			Log.d(TAG, "Found Roll in " + sourceLabel + ": " + roll + "°");
			return Optional.of(normalizeMetadataAngle(roll));
		}
		float tilt = findXmpFloat(xml, "Tilt");
		if (!Float.isNaN(tilt))
		{
			Log.d(TAG, "Found Tilt in " + sourceLabel + ": " + tilt + "°");
			return Optional.of(normalizeMetadataAngle(tilt));
		}
		return Optional.empty();
	}

	/**
	 * Search XMP XML for a float attribute whose name is exactly attrSuffix, optionally with a namespace prefix.
	 * Handles patterns like: namespace:Roll="1.23" or Roll="1.23". The exact-suffix anchoring is load-bearing:
	 * a looser pattern ("\\w*:?Suffix") matches any attribute whose name merely ends in the suffix (CameraRoll,
	 * GyroRoll) and silently returns its value as the horizon angle.
	 *
	 * @param xml        XMP packet text to search
	 * @param attrSuffix exact attribute name to match (case-insensitive), with or without a namespace prefix
	 * @return the first occurrence that parses as a float (later occurrences are tried when an earlier one holds
	 *         a non-float token); NaN when no parsable occurrence exists
	 */
	private static float findXmpFloat(String xml, String attrSuffix)
	{
		// Require either the start of a token (non-word char) or start of string, then an optional namespace
		// prefix that ends in ':', then the exact suffix followed by whitespace or '='. This rules out AbcRoll,
		// CameraRoll, GyroRoll, etc. Pattern is cached per suffix.
		Pattern pattern = XMP_FLOAT_PATTERNS.computeIfAbsent(attrSuffix, suffix ->
			Pattern.compile("(?:^|[^\\w:])(?:\\w+:)?" + Pattern.quote(suffix) + "\\s*=\\s*\"([^\"]+)\"",
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
				// Some namespaces store the attribute name as a non-float (CDATA fragment, enum token,
				// IETF locale) — skip and try the next match; a later occurrence may parse.
			}
		}
		return Float.NaN;
	}

	/**
	 * One LSQ-refinement pass: re-vote a rho histogram at seedAngleDeg's basis, pick the densest bin as the inlier
	 * center, LSQ-fit the points within ±LINE_FIT_INLIER_DISTANCE_PX of that bin. Returns the fitted angle in
	 * degrees (un-clamped), or NaN when the fit can't be trusted (too few peak votes, too few inliers, or
	 * degenerate slope-from-X denominator).
	 *
	 * Caller refineLineFitAngle chains two of these — first at the Hough seed, then at the first pass's output — to
	 * converge through the Hough peak's rho-bin quantization noise.
	 *
	 * @param edgeX        edge pixel X coordinates
	 * @param edgeY        edge pixel Y coordinates
	 * @param edgeCount    number of valid coordinates in edgeX / edgeY
	 * @param width        source bitmap width, used for the minimum inlier threshold
	 * @param height       source bitmap height, used to size the rho histogram
	 * @param seedAngleDeg basis angle for rho voting (degrees)
	 * @return fitted angle in degrees (no clamp), or NaN when the fit failed
	 */
	private static float lsqPassAtSeed(int[] edgeX, int[] edgeY, int edgeCount,
		int width, int height, float seedAngleDeg)
	{
		double rad = Math.toRadians(seedAngleDeg);
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

		int minInliers = Math.max(MIN_LINE_FIT_INLIERS, width * VOTE_FLOOR_WIDTH_PERCENT / 100);
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
		// flow into atan → NaN → 90+NaN=NaN. Guard explicitly so a degenerate inlier set returns the spec'd "no
		// reading" sentinel.
		if (Double.isNaN(slope) || Double.isInfinite(slope))
		{
			return Float.NaN;
		}
		return 90f + (float) Math.toDegrees(Math.atan(slope));
	}

	/**
	 * Convert a raw roll/tilt reading from metadata into the UI's correction-angle convention:
	 * snap near-zero values to exact zero, reject implausibly-large tilts as NaN (bad sensor data),
	 * and otherwise invert and round to 2 decimal places. Shared across all XMP/APP1 entry points.
	 *
	 * @param deg raw roll / tilt reading in degrees as stored by the camera
	 * @return correction angle in degrees (negated, snapped to hundredths); 0 within ROTATION_EPSILON of level;
	 *         NaN for NaN / infinite input or magnitudes above MAX_HORIZON_TILT_DEGREES
	 */
	private static float normalizeMetadataAngle(float deg)
	{
		// NaN bypasses both abs comparisons (NaN < x and NaN > x are always false), so an explicit guard here
		// prevents the round-then-divide path from producing -0.0f and silently announcing a "valid 0°
		// rotation" when XMP carries malformed roll data.
		if (!Float.isFinite(deg))
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
}
