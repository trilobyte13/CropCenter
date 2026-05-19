package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.util.Locale;

/**
 * Detect the AI-modified pixel region by diffing source vs aligned-edit bitmaps. Used by the graft pipeline to locate
 * where Photoshop's Generative Fill / Remove changed pixels, so GainMapInpainter can patch the source's gain map at
 * exactly those coordinates (the gain map's HDR boost was calibrated against the original pixels and produces visible
 * artifacts where the new pixels differ).
 *
 * Decodes both bitmaps at IN_SAMPLE_SIZE downsample to keep peak memory bounded for large Samsung sources (a 4000x3000
 * source decodes to ~1000x750, dropping peak from ~96 MB to ~6 MB for the bitmap pair). The result happens to align
 * with typical Samsung gain-map dimensions (1/4 of primary), so GainMapInpainter usually gets a near-1:1 mask without
 * needing scale-up.
 */
public final class AiRegionDetector
{
	/**
	 * Binary mask of AI-modified pixels in downsampled stored coordinates. The mask is row-major: mask[y * width +
	 * x] = true means the pixel at (x, y) in the downsampled coords differs from source by more than DIFF_THRESHOLD
	 * on at least one channel. sampleSize is the BitmapFactory inSampleSize used during detection, so callers can
	 * map mask coordinates back to full-resolution coords (multiply by sampleSize) when needed. maskedCount caches
	 * the flagged-pixel count from detect()'s diff pass — accessing it is O(1). Before the cache, callers
	 * (GraftController.assembleGraftOnBg, GainMapInpainter.inpaintBitmap via hasMaskedPixels) re-walked the mask
	 * array to re-derive the count, paying O(N) each. Storing the count on the record makes those reads O(1) and
	 * is the reason the canonical constructor takes the count as an explicit parameter (rather than always
	 * re-walking).
	 */
	public record AiMask(boolean[] mask, int width, int height, int sampleSize, int maskedCount)
	{
		/**
		 * Construct an AiMask with maskedCount derived from a single walk of `mask`. Used by tests and
		 * out-of-band callers that have a pre-built mask but no count; AiRegionDetector.detect uses the
		 * canonical constructor directly because it counted during the diff pass anyway.
		 *
		 * @param mask       row-major boolean array of width*height
		 * @param width      mask width in downsampled pixels
		 * @param height     mask height in downsampled pixels
		 * @param sampleSize BitmapFactory inSampleSize used during detection
		 * @return AiMask with maskedCount set to the number of true entries in mask
		 */
		public static AiMask of(boolean[] mask, int width, int height, int sampleSize)
		{
			int count = 0;
			for (boolean flag : mask)
			{
				if (flag)
				{
					count++;
				}
			}
			return new AiMask(mask, width, height, sampleSize, count);
		}

		/**
		 * True when at least one pixel is flagged as AI-modified. Callers use this to skip the inpaint step
		 * entirely when the edit didn't change anything (the inpaint would be a no-op but the JPEG re-encode of
		 * the gain map would still burn cycles + add minor quantization noise to the saved file).
		 */
		public boolean hasMaskedPixels()
		{
			return maskedCount > 0;
		}
	}

	private static final String TAG = "AiRegionDetector";

	// Per-pixel max-channel diff threshold above which a pixel is considered AI-modified. 30 cleanly separates
	// typical Generative Remove fills (peak diff 100-240) from JPEG re-encode noise (which tops out around 36 in
	// our 171720 / 172023 / 093032 test corpus). Below 30 risks tagging encode noise as AI; above ~50 risks missing
	// subtle fills at the boundary.
	private static final int DIFF_THRESHOLD = 30;

	// BitmapFactory inSampleSize for the diff pass. 4 matches the typical Samsung gain map's 1/4-of-primary
	// resolution, so the produced mask can be applied to the gain map directly when dimensions agree
	// (GainMapInpainter scales nearest-neighbor when they don't). 16x memory reduction vs full-res decode.
	private static final int IN_SAMPLE_SIZE = 4;

	private AiRegionDetector() {}

	/**
	 * Decode both inputs at IN_SAMPLE_SIZE, compute per-pixel max-channel diff, threshold, and return the resulting
	 * mask. Returns null when either decode fails or the two decoded bitmaps don't share dimensions (e.g. caller
	 * passed unaligned edit bytes).
	 *
	 * Both bitmaps are recycled before return — the mask is the only output that survives this call. Logs the
	 * masked-pixel count at DEBUG so a missing AI mask after a known-edited file points at the threshold being too
	 * high (or the diff pipeline misidentifying the AI region).
	 *
	 * @param sourceBytes original JPEG bytes (pre-AI-edit)
	 * @param editBytes   alignment-corrected edit JPEG bytes
	 * @return mask in IN_SAMPLE_SIZE-downsampled coordinates, or null on decode failure or shape mismatch
	 */
	public static AiMask detect(byte[] sourceBytes, byte[] editBytes)
	{
		BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inSampleSize = IN_SAMPLE_SIZE;
		Bitmap source = null;
		Bitmap edit = null;
		try
		{
			source = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.length, opts);
			edit = BitmapFactory.decodeByteArray(editBytes, 0, editBytes.length, opts);
			if (source == null || edit == null)
			{
				Log.w(TAG, "Decode failed (source=" + (source != null)
					+ ", edit=" + (edit != null) + ")");
				return null;
			}
			int width = source.getWidth();
			int height = source.getHeight();
			if (edit.getWidth() != width || edit.getHeight() != height)
			{
				Log.w(TAG, "Downsampled shape mismatch: source " + width + "x" + height
					+ ", edit " + edit.getWidth() + "x" + edit.getHeight());
				return null;
			}
			// width * height in int arithmetic silently overflows above ~46k px on a side. Reject before
			// allocating with Math.multiplyExact so a 200MP-mode source (12k×9k = 108M, fine) doesn't trip
			// us, but a synthetic 50k+ px input throws ArithmeticException to the outer catch instead of
			// allocating a negative-size array.
			int pixelCount;
			try
			{
				pixelCount = Math.multiplyExact(width, height);
			}
			catch (ArithmeticException e)
			{
				Log.w(TAG, "AI mask refused: width*height overflows int ("
					+ width + "x" + height + ")");
				return null;
			}
			int[] sourcePixels = new int[pixelCount];
			int[] editPixels = new int[pixelCount];
			source.getPixels(sourcePixels, 0, width, 0, 0, width, height);
			edit.getPixels(editPixels, 0, width, 0, 0, width, height);

			boolean[] mask = new boolean[pixelCount];
			int maskedCount = diffPixels(sourcePixels, editPixels, mask, DIFF_THRESHOLD);
			Log.d(TAG, "AI mask: " + maskedCount + " of " + mask.length
				+ " pixels (" + String.format(Locale.ROOT, "%.3f", 100.0 * maskedCount / mask.length)
				+ "%) at " + width + "x" + height + " (sampleSize=" + IN_SAMPLE_SIZE + ")");
			return new AiMask(mask, width, height, IN_SAMPLE_SIZE, maskedCount);
		}
		finally
		{
			if (source != null)
			{
				source.recycle();
			}
			if (edit != null)
			{
				edit.recycle();
			}
		}
	}

	/**
	 * Per-pixel max-channel-diff pass. Pure int-array math, package-private so AiRegionDetectorTest can pin the
	 * channel-diff and threshold contract without Robolectric — the Bitmap decode side of detect() is what needs
	 * an Android runtime, but the diff math is where the load-bearing correctness lives (a bad threshold or
	 * channel-extraction bug would silently include encode noise OR exclude real AI fills).
	 *
	 * Mask + count contract: mask[i] is SET to true at indices where the max-channel diff exceeds threshold —
	 * pre-existing true entries are not cleared. The returned count is the number of pixels whose diff exceeds
	 * threshold (NOT the number of false → true transitions); when the production caller passes a fresh
	 * boolean[] these two counts coincide, but a pre-populated input would double-count the already-true bits.
	 *
	 * @param sourcePixels ARGB-packed pixel array from the source bitmap
	 * @param editPixels   ARGB-packed pixel array from the alignment-corrected edit bitmap; must be the same
	 *                     length as sourcePixels
	 * @param mask         caller-allocated boolean[sourcePixels.length] — filled with true at indices where the
	 *                     max-channel diff exceeds `threshold`. Pre-existing true entries are NOT cleared (the
	 *                     production caller always passes a fresh new boolean[]).
	 * @param threshold    inclusive upper bound on "encode noise" — a diff equal to threshold is NOT flagged,
	 *                     diff > threshold is. Production uses DIFF_THRESHOLD (30).
	 * @return number of pixels whose diff exceeds threshold (regardless of prior mask state at that index)
	 */
	static int diffPixels(int[] sourcePixels, int[] editPixels, boolean[] mask, int threshold)
	{
		int maskedCount = 0;
		for (int i = 0; i < sourcePixels.length; i++)
		{
			int sourceArgb = sourcePixels[i];
			int editArgb = editPixels[i];
			int dr = Math.abs(((sourceArgb >> 16) & 0xFF) - ((editArgb >> 16) & 0xFF));
			int dg = Math.abs(((sourceArgb >> 8) & 0xFF) - ((editArgb >> 8) & 0xFF));
			int db = Math.abs((sourceArgb & 0xFF) - (editArgb & 0xFF));
			int maxChannelDiff = Math.max(dr, Math.max(dg, db));
			if (maxChannelDiff > threshold)
			{
				mask[i] = true;
				maskedCount++;
			}
		}
		return maskedCount;
	}
}
