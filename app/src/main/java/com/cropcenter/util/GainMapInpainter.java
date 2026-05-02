package com.cropcenter.util;

import android.graphics.Bitmap;
import android.util.Log;

import com.cropcenter.util.AiRegionDetector.AiMask;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Patch a gain-map bitmap at the AI-modified region by replacing each masked pixel's boost value with the average of
 * its surrounding unmasked pixels. The source's gain map was calibrated against the original primary; where Photoshop's
 * Generative Fill / Remove changed pixels, the original boost is mis-targeted (it boosts highlights / shadows that no
 * longer exist). Inpainting from the AI region's boundary inward gives the fill the same HDR boost as its surroundings,
 * which matches what Generative Remove visually intends ("this region should look like its neighbors").
 *
 * Algorithm: frontier-tracked grow-from-boundary. Each pass processes only the "frontier" — masked pixels adjacent to
 * at least one unmasked pixel — instead of scanning the whole gain map. Total work is O(AI region area), not O(W * H *
 * radius).
 *
 * Operates in place on the bitmap so the caller (UltraHdrCompat) can apply the patch after Android's UHDR-aware decode
 * has produced a gain-map Bitmap in source's native format (typically ALPHA_8 single channel for Samsung sources).
 * Re-encoding ourselves via Bitmap.compress would force YCbCr 4:2:0 3-channel output regardless of input config, and
 * the resulting structural mismatch with source's 1-channel gain map causes Android's UHDR decoder downstream to drop
 * HDR entirely (saved file renders as SDR, looking dark).
 */
public final class GainMapInpainter
{
	private static final String TAG = "GainMapInpainter";

	// Dilate the scaled mask by this many pixels in gain-map coords before inpainting. Catches the AI region's
	// boundary where pixel diffs fade just below the detector's threshold but the gain map at those coords is still
	// mis-targeted.
	private static final int DILATE_RADIUS = 2;

	// Hard cap on grow-from-boundary passes. Defensive runaway guard rather than a tuned limit — the largest
	// realistic AI fill (~200 px half-width in gain-map coords) needs a few hundred passes.
	private static final int MAX_PASSES = 10_000;

	private GainMapInpainter() {}

	/**
	 * Inpaint the masked region of a gain-map bitmap in place. Handles both single- channel ALPHA_8 (Samsung's
	 * typical gain-map format) and multi-channel ARGB_8888 (Adobe's variant). For ALPHA_8 the alpha byte holds the
	 * boost value; for ARGB_8888 each RGB channel is inpainted independently. No-op when the bitmap isn't mutable
	 * (defensive — returns silently rather than throwing) or when the mask has no pixels flagged.
	 *
	 * The mask is scaled nearest-neighbor to the bitmap's dimensions and dilated by DILATE_RADIUS pixels before
	 * inpainting begins.
	 *
	 * @param bmp    gain-map bitmap; mutated in place. Must be mutable, ALPHA_8 or
	 *               ARGB_8888 — other configs no-op silently
	 * @param aiMask AI-modified region mask in arbitrary coordinates (scaled to bmp's
	 *               dimensions internally), or null to no-op
	 */
	public static void inpaintBitmap(Bitmap bmp, AiMask aiMask)
	{
		if (bmp == null || aiMask == null || !aiMask.hasMaskedPixels())
		{
			return;
		}
		if (!bmp.isMutable())
		{
			Log.w(TAG, "Gain-map bitmap is immutable; cannot inpaint in place");
			return;
		}
		int width = bmp.getWidth();
		int height = bmp.getHeight();
		boolean[] mask = scaleMask(aiMask, width, height);
		dilateMask(mask, width, height, DILATE_RADIUS);

		Bitmap.Config config = bmp.getConfig();
		if (config == Bitmap.Config.ALPHA_8)
		{
			inpaintAlpha8(bmp, mask, width, height);
		}
		else
		{
			inpaintArgb(bmp, mask, width, height);
		}
	}

	/**
	 * In-place 8-connected mask dilation by `radius` pixels. Each iteration marks any unmasked pixel whose
	 * 8-neighborhood contains a masked pixel. Uses a snapshot per iteration so dilation grows by exactly one ring
	 * per pass (vs reading the same array we're writing, which would race growth across the mask within a pass).
	 */
	private static void dilateMask(boolean[] mask, int width, int height, int radius)
	{
		for (int ring = 0; ring < radius; ring++)
		{
			boolean[] snapshot = mask.clone();
			for (int y = 0; y < height; y++)
			{
				for (int x = 0; x < width; x++)
				{
					int idx = y * width + x;
					if (mask[idx])
					{
						continue;
					}
					boolean grow = false;
					for (int dy = -1; dy <= 1 && !grow; dy++)
					{
						int ny = y + dy;
						if (ny < 0 || ny >= height)
						{
							continue;
						}
						for (int dx = -1; dx <= 1; dx++)
						{
							int nx = x + dx;
							if (nx < 0 || nx >= width)
							{
								continue;
							}
							if (snapshot[ny * width + nx])
							{
								grow = true;
								break;
							}
						}
					}
					if (grow)
					{
						mask[idx] = true;
					}
				}
			}
		}
	}

	private static int[] extractChannel(int[] pixels, int shift)
	{
		int[] channel = new int[pixels.length];
		for (int i = 0; i < pixels.length; i++)
		{
			channel[i] = (pixels[i] >> shift) & 0xFF;
		}
		return channel;
	}

	/**
	 * Quick check: does (x, y) have at least one 8-neighbor whose mask bit is false? Used to seed the initial
	 * frontier for inpaintIterative — a pixel is on the frontier if it's masked AND at least one neighbor is
	 * already unmasked. Short- circuits on the first hit.
	 */
	private static boolean hasUnmaskedNeighbor(boolean[] mask, int x, int y, int width, int height)
	{
		for (int dy = -1; dy <= 1; dy++)
		{
			int ny = y + dy;
			if (ny < 0 || ny >= height)
			{
				continue;
			}
			for (int dx = -1; dx <= 1; dx++)
			{
				if (dx == 0 && dy == 0)
				{
					continue;
				}
				int nx = x + dx;
				if (nx < 0 || nx >= width)
				{
					continue;
				}
				if (!mask[ny * width + nx])
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Single-channel inpaint for ALPHA_8 bitmaps. ALPHA_8 stores one byte per pixel (the alpha value); for
	 * Samsung's grayscale gain map, that byte IS the boost value. copyPixelsToBuffer / copyPixelsFromBuffer are the
	 * only safe accessors — getPixels would return ARGB ints with alpha in the high byte and zeros in RGB.
	 *
	 * Buffer sizing uses bmp.getByteCount() (not width * height) and pixel access uses bmp.getRowBytes() (not
	 * width) so this works correctly for bitmaps where Skia adds row-stride padding for memory alignment —
	 * copyPixelsToBuffer requires the buffer to hold getByteCount() bytes, and indexing as if row stride equals
	 * width would write inpainted values into padding bytes for stridden bitmaps.
	 */
	private static void inpaintAlpha8(Bitmap bmp, boolean[] mask, int width, int height)
	{
		int rowBytes = bmp.getRowBytes();
		ByteBuffer buf = ByteBuffer.allocate(bmp.getByteCount());
		bmp.copyPixelsToBuffer(buf);
		byte[] bytes = buf.array();
		int[] values = new int[width * height];
		for (int y = 0; y < height; y++)
		{
			int rowOffset = y * rowBytes;
			int valueOffset = y * width;
			for (int x = 0; x < width; x++)
			{
				values[valueOffset + x] = bytes[rowOffset + x] & 0xFF;
			}
		}
		int passes = inpaintIterative(values, mask, width, height);
		for (int y = 0; y < height; y++)
		{
			int rowOffset = y * rowBytes;
			int valueOffset = y * width;
			for (int x = 0; x < width; x++)
			{
				bytes[rowOffset + x] = (byte) values[valueOffset + x];
			}
		}
		buf.position(0);
		bmp.copyPixelsFromBuffer(buf);
		Log.d(TAG, "Inpainted ALPHA_8 gain-map " + width + "x" + height
			+ " (rowBytes=" + rowBytes + ") in " + passes + " passes");
	}

	/**
	 * Multi-channel inpaint for ARGB_8888 bitmaps. Each RGB channel runs its own grow-from-boundary pass with a
	 * fresh mask copy (the iteration mutates the mask, so the first two passes clone; the last reuses the original
	 * since it has no successor). Alpha is preserved verbatim. Pass counts are typically within 1 of each other for
	 * grayscale-like (R == G == B) sources but the log reports the per-channel max so non-grayscale (Adobe variant)
	 * sources don't silently hide a channel that took longer to converge.
	 */
	private static void inpaintArgb(Bitmap bmp, boolean[] mask, int width, int height)
	{
		int[] pixels = new int[width * height];
		bmp.getPixels(pixels, 0, width, 0, 0, width, height);
		int[] r = extractChannel(pixels, 16);
		int[] g = extractChannel(pixels, 8);
		int[] b = extractChannel(pixels, 0);
		int passesR = inpaintIterative(r, mask.clone(), width, height);
		int passesG = inpaintIterative(g, mask.clone(), width, height);
		int passesB = inpaintIterative(b, mask, width, height);
		for (int i = 0; i < pixels.length; i++)
		{
			int alpha = pixels[i] & 0xFF000000;
			pixels[i] = alpha | (r[i] << 16) | (g[i] << 8) | b[i];
		}
		bmp.setPixels(pixels, 0, width, 0, 0, width, height);
		Log.d(TAG, "Inpainted ARGB gain-map " + width + "x" + height + " in passes R=" + passesR
			+ " G=" + passesG + " B=" + passesB);
	}

	/**
	 * Frontier-tracked grow-from-boundary inpaint on a single channel of values. Each pass processes only masked
	 * pixels adjacent to at least one unmasked pixel; resolved pixels join the unmasked set for the next pass's
	 * averaging. Initial frontier seed is O(W * H) (single pass over the mask); subsequent passes are O(frontier
	 * size), so total work is O(W * H + AI region area) rather than O(W * H * radius).
	 *
	 * The mask is mutated. Isolated masked pixels (no path to any unmasked pixel) keep their original value when
	 * the loop exits with empty frontier.
	 */
	private static int inpaintIterative(int[] values, boolean[] mask, int width, int height)
	{
		int[] frontier = new int[mask.length];
		int[] nextFrontier = new int[mask.length];
		int[] newValues = new int[mask.length];
		boolean[] inNextFrontier = new boolean[mask.length];
		int frontierSize = 0;

		for (int y = 0; y < height; y++)
		{
			for (int x = 0; x < width; x++)
			{
				int idx = y * width + x;
				if (mask[idx] && hasUnmaskedNeighbor(mask, x, y, width, height))
				{
					frontier[frontierSize++] = idx;
				}
			}
		}

		int passes = 0;
		while (frontierSize > 0)
		{
			passes++;
			for (int i = 0; i < frontierSize; i++)
			{
				int idx = frontier[i];
				int x = idx % width;
				int y = idx / width;
				int sum = 0;
				int count = 0;
				for (int dy = -1; dy <= 1; dy++)
				{
					int ny = y + dy;
					if (ny < 0 || ny >= height)
					{
						continue;
					}
					for (int dx = -1; dx <= 1; dx++)
					{
						if (dx == 0 && dy == 0)
						{
							continue;
						}
						int nx = x + dx;
						if (nx < 0 || nx >= width)
						{
							continue;
						}
						int neighbor = ny * width + nx;
						if (!mask[neighbor])
						{
							sum += values[neighbor];
							count++;
						}
					}
				}
				newValues[idx] = (count > 0) ? sum / count : values[idx];
			}

			for (int i = 0; i < frontierSize; i++)
			{
				int idx = frontier[i];
				values[idx] = newValues[idx];
				mask[idx] = false;
			}

			int nextSize = 0;
			Arrays.fill(inNextFrontier, false);
			for (int i = 0; i < frontierSize; i++)
			{
				int idx = frontier[i];
				int x = idx % width;
				int y = idx / width;
				for (int dy = -1; dy <= 1; dy++)
				{
					int ny = y + dy;
					if (ny < 0 || ny >= height)
					{
						continue;
					}
					for (int dx = -1; dx <= 1; dx++)
					{
						if (dx == 0 && dy == 0)
						{
							continue;
						}
						int nx = x + dx;
						if (nx < 0 || nx >= width)
						{
							continue;
						}
						int neighbor = ny * width + nx;
						if (mask[neighbor] && !inNextFrontier[neighbor])
						{
							inNextFrontier[neighbor] = true;
							nextFrontier[nextSize++] = neighbor;
						}
					}
				}
			}

			int[] swap = frontier;
			frontier = nextFrontier;
			nextFrontier = swap;
			frontierSize = nextSize;

			if (passes >= MAX_PASSES)
			{
				Log.w(TAG, "Inpaint exceeded MAX_PASSES; remaining masked pixels keep original values");
				break;
			}
		}
		return passes;
	}

	/**
	 * Nearest-neighbor mask scale to (targetWidth, targetHeight). Long arithmetic for the index multiply so 8000 x
	 * 6000 sources don't overflow int when (y * srcH) grows past 4.8e7.
	 */
	private static boolean[] scaleMask(AiMask aiMask, int targetWidth, int targetHeight)
	{
		int srcWidth = aiMask.width();
		int srcHeight = aiMask.height();
		boolean[] src = aiMask.mask();
		if (srcWidth == targetWidth && srcHeight == targetHeight)
		{
			return src.clone();
		}
		boolean[] dst = new boolean[targetWidth * targetHeight];
		for (int y = 0; y < targetHeight; y++)
		{
			int sourceY = (int) ((long) y * srcHeight / targetHeight);
			for (int x = 0; x < targetWidth; x++)
			{
				int sourceX = (int) ((long) x * srcWidth / targetWidth);
				dst[y * targetWidth + x] = src[sourceY * srcWidth + sourceX];
			}
		}
		return dst;
	}
}
