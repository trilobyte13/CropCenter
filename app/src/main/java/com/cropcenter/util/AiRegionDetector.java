package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.util.Locale;

/**
 * Detect the AI-modified pixel region by diffing source vs aligned-edit bitmaps.
 * Used by the graft pipeline to locate where Photoshop's Generative Fill / Remove
 * changed pixels, so GainMapInpainter can patch the source's gain map at exactly
 * those coordinates (the gain map's HDR boost was calibrated against the original
 * pixels and produces visible artifacts where the new pixels differ).
 *
 * Decodes both bitmaps at IN_SAMPLE_SIZE downsample to keep peak memory bounded
 * for large Samsung sources (a 4000x3000 source decodes to ~1000x750, dropping
 * peak from ~96 MB to ~6 MB for the bitmap pair). The result happens to align with
 * typical Samsung gain-map dimensions (1/4 of primary), so GainMapInpainter usually
 * gets a near-1:1 mask without needing scale-up.
 */
public final class AiRegionDetector
{
	/**
	 * Binary mask of AI-modified pixels in downsampled stored coordinates. The mask is
	 * row-major: mask[y * width + x] = true means the pixel at (x, y) in the
	 * downsampled coords differs from source by more than DIFF_THRESHOLD on at least
	 * one channel. sampleSize is the BitmapFactory inSampleSize used during detection,
	 * so callers can map mask coordinates back to full-resolution coords (multiply by
	 * sampleSize) when needed.
	 */
	public record AiMask(boolean[] mask, int width, int height, int sampleSize)
	{
		/**
		 * True when at least one pixel is flagged as AI-modified. Callers use this to
		 * skip the inpaint step entirely when the edit didn't change anything (the
		 * inpaint would be a no-op but the JPEG re-encode of the gain map would still
		 * burn cycles + add minor quantization noise to the saved file). Short-circuits
		 * on the first true pixel — use maskedCount when the actual number is needed.
		 */
		public boolean hasMaskedPixels()
		{
			for (boolean flag : mask)
			{
				if (flag)
				{
					return true;
				}
			}
			return false;
		}

		/**
		 * Total number of AI-modified pixels in the mask. Used by the graft sanity check
		 * — a fraction (count / mask.length) far above what a typical Generative Remove
		 * touch-up produces (~0.001%-0.5% of pixels) signals either a wrong-file pick or
		 * a wholesale global edit, both of which the graft pipeline isn't designed for.
		 */
		public int maskedCount()
		{
			int count = 0;
			for (boolean flag : mask)
			{
				if (flag)
				{
					count++;
				}
			}
			return count;
		}
	}

	private static final String TAG = "AiRegionDetector";

	// Per-pixel max-channel diff threshold above which a pixel is considered AI-modified.
	// 30 cleanly separates typical Generative Remove fills (peak diff 100-240) from JPEG
	// re-encode noise (which tops out around 36 in our 171720 / 172023 / 093032 test
	// corpus). Below 30 risks tagging encode noise as AI; above ~50 risks missing
	// subtle fills at the boundary.
	private static final int DIFF_THRESHOLD = 30;

	// BitmapFactory inSampleSize for the diff pass. 4 matches the typical Samsung gain
	// map's 1/4-of-primary resolution, so the produced mask can be applied to the gain
	// map directly when dimensions agree (GainMapInpainter scales nearest-neighbor when
	// they don't). 16x memory reduction vs full-res decode.
	private static final int IN_SAMPLE_SIZE = 4;

	private AiRegionDetector() {}

	/**
	 * Decode both inputs at IN_SAMPLE_SIZE, compute per-pixel max-channel diff, threshold,
	 * and return the resulting mask. Returns null when either decode fails or the two
	 * decoded bitmaps don't share dimensions (e.g. caller passed unaligned edit bytes).
	 *
	 * Both bitmaps are recycled before return — the mask is the only output that
	 * survives this call. Logs the masked-pixel count at DEBUG so a missing AI mask
	 * after a known-edited file points at the threshold being too high (or the diff
	 * pipeline misidentifying the AI region).
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
			int[] sourcePixels = new int[width * height];
			int[] editPixels = new int[width * height];
			source.getPixels(sourcePixels, 0, width, 0, 0, width, height);
			edit.getPixels(editPixels, 0, width, 0, 0, width, height);

			boolean[] mask = new boolean[width * height];
			int maskedCount = 0;
			for (int i = 0; i < sourcePixels.length; i++)
			{
				int sourceArgb = sourcePixels[i];
				int editArgb = editPixels[i];
				int dr = Math.abs(((sourceArgb >> 16) & 0xFF) - ((editArgb >> 16) & 0xFF));
				int dg = Math.abs(((sourceArgb >> 8) & 0xFF) - ((editArgb >> 8) & 0xFF));
				int db = Math.abs((sourceArgb & 0xFF) - (editArgb & 0xFF));
				int maxChannelDiff = Math.max(dr, Math.max(dg, db));
				if (maxChannelDiff > DIFF_THRESHOLD)
				{
					mask[i] = true;
					maskedCount++;
				}
			}
			Log.d(TAG, "AI mask: " + maskedCount + " of " + mask.length
				+ " pixels (" + String.format(Locale.ROOT, "%.3f", 100.0 * maskedCount / mask.length)
				+ "%) at " + width + "x" + height + " (sampleSize=" + IN_SAMPLE_SIZE + ")");
			return new AiMask(mask, width, height, IN_SAMPLE_SIZE);
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
}
