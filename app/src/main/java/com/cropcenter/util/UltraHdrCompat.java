package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Process;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Ultra HDR support using Android 14+ Gainmap API.
 *
 * Strategy: decode original with gainmap, render onto a cropW×cropH canvas using the exact same
 * rotation/positioning as CropExporter (Canvas.rotate around image center). Apply the identical
 * transform to the gainmap bitmap so gain map and primary are spatially aligned. Compress →
 * Ultra HDR JPEG.
 */
public final class UltraHdrCompat
{
	private static final String TAG = "UltraHdrCompat";

	private UltraHdrCompat() {}

	/**
	 * Produce an Ultra HDR JPEG using the same canvas rendering as CropExporter.
	 * The gain map undergoes the identical spatial transform as the primary, guaranteeing
	 * alignment regardless of rotation or crop position.
	 *
	 * @return Ultra HDR JPEG bytes, or null if failed
	 */
	public static byte[] compressWithGainmap(byte[] originalBytes, int quality, File cacheDir,
		int imgW, int imgH, float centerX, float centerY, int cropW, int cropH,
		float userRotation, int exifOrientation)
	{
		Bitmap current = null;
		Bitmap output = null;
		Bitmap gainmapOutput = null;
		try
		{
			current = decodeHdrBitmap(originalBytes, cacheDir);
			if (current == null || !current.hasGainmap())
			{
				Log.d(TAG, "No gainmap in source");
				return null;
			}
			Log.d(TAG, "Decoded: " + current.getWidth() + "x" + current.getHeight()
				+ " hasGm=" + current.hasGainmap()
				+ " expected=" + imgW + "x" + imgH
				+ " exif=" + exifOrientation);

			current = applyExifOrientation(current, exifOrientation);

			// Capture gainmap before the rendering step may drop it.
			Gainmap sourceGainmap = current.hasGainmap() ? current.getGainmap() : null;
			Bitmap gainmapBitmap = sourceGainmap != null ? sourceGainmap.getGainmapContents() : null;

			// Crop origin — matches CropExporter.export() exactly.
			float srcX = centerX - cropW / 2f;
			float srcY = centerY - cropH / 2f;

			output = renderPrimary(current, srcX, srcY, cropW, cropH, userRotation);

			if (sourceGainmap != null && gainmapBitmap != null)
			{
				gainmapOutput = renderGainmap(sourceGainmap, gainmapBitmap,
					current.getWidth(), current.getHeight(),
					srcX, srcY, cropW, cropH, userRotation);
				Gainmap newGainmap = new Gainmap(gainmapOutput);
				copyGainmapMetadata(sourceGainmap, newGainmap);
				output.setGainmap(newGainmap);
			}

			current.recycle();
			current = null;

			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			output.compress(Bitmap.CompressFormat.JPEG, quality, bos);
			byte[] result = bos.toByteArray();
			if (containsHdrgm(result))
			{
				Log.d(TAG, "Ultra HDR: " + result.length + " bytes");
				return result;
			}
			Log.w(TAG, "No hdrgm in compress output");
			return null;
		}
		catch (Exception e)
		{
			Log.e(TAG, "compressWithGainmap: " + e.getMessage(), e);
			return null;
		}
		finally
		{
			// Recycle every intermediate bitmap on every exit — the success path needs output
			// recycled after compress, the exception path needs ALL of them to avoid native
			// leaks. Null checks guard against partial initialization.
			if (current != null)
			{
				current.recycle();
			}
			if (gainmapOutput != null)
			{
				gainmapOutput.recycle();
			}
			if (output != null)
			{
				output.recycle();
			}
		}
	}

	/**
	 * Decode the source JPEG into a Bitmap that preserves its gainmap. BitmapFactory
	 * reads HDR gainmaps from files (not ByteArrays), so we write the bytes to a
	 * cache file first. The cache file is deleted as soon as decodeFile returns,
	 * whether it produced a bitmap or not — no "leaked cache file on decode failure"
	 * path.
	 */
	private static Bitmap decodeHdrBitmap(byte[] originalBytes, File cacheDir) throws IOException
	{
		// Unique filename so concurrent exports never collide on the cache path. Single-
		// threaded today; suffix is cheap insurance against future parallelism.
		File hdrSourceCache = new File(cacheDir,
			"hdr_src_" + Process.myPid() + "_" + System.nanoTime() + ".jpg");
		try
		{
			try (FileOutputStream fos = new FileOutputStream(hdrSourceCache))
			{
				fos.write(originalBytes);
			}
			BitmapFactory.Options opts = new BitmapFactory.Options();
			opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
			return BitmapFactory.decodeFile(hdrSourceCache.getAbsolutePath(), opts);
		}
		finally
		{
			hdrSourceCache.delete();
		}
	}

	/**
	 * Apply EXIF orientation to the decoded bitmap. BitmapFactory.decodeFile does NOT
	 * auto-apply EXIF orientation, and the previous heuristic "autoRotated = decoded
	 * dimensions match display dimensions" silently skipped orientations 2/3/4 (mirror,
	 * 180°, vertical flip) because those don't swap W/H even though they still need
	 * the matrix. Always apply when orientation > 1. Uses filter=false: EXIF
	 * transforms are pure mirror / 90° / 180° integer-pixel remaps — lossless, and
	 * bilinear would only add softening. Returns the new bitmap (may be the same
	 * reference when the matrix is identity); recycles the old one if it differs.
	 */
	private static Bitmap applyExifOrientation(Bitmap current, int exifOrientation)
	{
		if (exifOrientation <= 1)
		{
			return current;
		}
		Matrix matrix = BitmapUtils.orientationMatrix(exifOrientation);
		Bitmap rotated = Bitmap.createBitmap(current, 0, 0,
			current.getWidth(), current.getHeight(), matrix, false);
		if (rotated != current)
		{
			current.recycle();
		}
		Log.d(TAG, "EXIF applied: " + rotated.getWidth() + "x" + rotated.getHeight()
			+ " hasGm=" + rotated.hasGainmap());
		return rotated;
	}

	/**
	 * Render the primary output bitmap via BitmapUtils.drawCropped so the result is
	 * byte-identical to what CropExporter produces — crucial because the primary
	 * bytes shipped to the user come from CropExporter, while the gainmap alignment
	 * depends on UltraHdrCompat's primary matching.
	 */
	private static Bitmap renderPrimary(Bitmap current, float srcX, float srcY,
		int cropW, int cropH, float userRotation)
	{
		Bitmap output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888, true,
			ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
		Canvas canvas = new Canvas(output);
		Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
		BitmapUtils.drawCropped(canvas, current, srcX, srcY, userRotation, paint);
		return output;
	}

	/**
	 * Render the cropped + rotated gain-map bitmap, spatially aligned with the
	 * primary render. Scales the draw offset by gainmap/primary ratio so the gainmap
	 * subregion lines up pixel-for-pixel with the primary crop (at the gainmap's
	 * native resolution, which is typically lower than the primary's).
	 */
	private static Bitmap renderGainmap(Gainmap sourceGainmap, Bitmap gainmapBitmap,
		int primaryW, int primaryH, float srcX, float srcY, int cropW, int cropH,
		float userRotation)
	{
		float gainmapScaleX = (float) gainmapBitmap.getWidth() / primaryW;
		float gainmapScaleY = (float) gainmapBitmap.getHeight() / primaryH;
		int gainmapOutputW = Math.max(1, Math.round(cropW * gainmapScaleX));
		int gainmapOutputH = Math.max(1, Math.round(cropH * gainmapScaleY));

		Bitmap.Config gainmapConfig = gainmapBitmap.getConfig() != null
			? gainmapBitmap.getConfig()
			: Bitmap.Config.ARGB_8888;
		Bitmap gainmapOutput = Bitmap.createBitmap(
			gainmapOutputW, gainmapOutputH, gainmapConfig);
		Canvas gainmapCanvas = new Canvas(gainmapOutput);
		Paint gainmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

		float gainmapDrawX = -srcX * gainmapScaleX;
		float gainmapDrawY = -srcY * gainmapScaleY;

		if (Math.abs(userRotation) >= BitmapUtils.ROTATION_EPSILON)
		{
			drawGainmapRotated(gainmapCanvas, gainmapBitmap, gainmapDrawX, gainmapDrawY,
				userRotation, gainmapPaint);
		}
		else
		{
			gainmapCanvas.drawBitmap(gainmapBitmap, gainmapDrawX, gainmapDrawY, gainmapPaint);
		}

		Log.d(TAG, "Gainmap rendered: " + gainmapOutputW + "x" + gainmapOutputH
			+ " (scale " + gainmapScaleX + "x" + gainmapScaleY + ")");
		return gainmapOutput;
	}

	/**
	 * Rotated gain-map draw. Cardinal rotations at integer-aligned draw offsets are
	 * lossless integer-pixel remaps — disable bilinear so nearest-neighbor reads
	 * source pixels verbatim. Fractional draw offsets need bilinear to match the
	 * primary path (which bilinear-samples at sub-pixel offsets).
	 */
	private static void drawGainmapRotated(Canvas gainmapCanvas, Bitmap gainmapBitmap,
		float gainmapDrawX, float gainmapDrawY, float userRotation, Paint gainmapPaint)
	{
		gainmapCanvas.save();
		gainmapCanvas.rotate(userRotation,
			gainmapDrawX + gainmapBitmap.getWidth() / 2f,
			gainmapDrawY + gainmapBitmap.getHeight() / 2f);
		boolean integerAligned = gainmapDrawX == Math.floor(gainmapDrawX)
			&& gainmapDrawY == Math.floor(gainmapDrawY);
		if (BitmapUtils.isCardinalRotation(userRotation) && integerAligned)
		{
			Paint nearestPaint = new Paint(gainmapPaint);
			nearestPaint.setFilterBitmap(false);
			gainmapCanvas.drawBitmap(gainmapBitmap, gainmapDrawX, gainmapDrawY, nearestPaint);
		}
		else
		{
			gainmapCanvas.drawBitmap(gainmapBitmap, gainmapDrawX, gainmapDrawY, gainmapPaint);
		}
		gainmapCanvas.restore();
	}

	/**
	 * Copy the HDR tone-mapping parameters (ratios, gamma, epsilon, display-ratio
	 * thresholds) from source to target Gainmap. Preserving these verbatim is what
	 * keeps the cropped HDR looking identical to the source at the kept pixels.
	 */
	private static void copyGainmapMetadata(Gainmap sourceGainmap, Gainmap newGainmap)
	{
		float[] ratioMin = sourceGainmap.getRatioMin();
		float[] ratioMax = sourceGainmap.getRatioMax();
		float[] gamma = sourceGainmap.getGamma();
		float[] epsilonSdr = sourceGainmap.getEpsilonSdr();
		float[] epsilonHdr = sourceGainmap.getEpsilonHdr();
		newGainmap.setRatioMin(ratioMin[0], ratioMin[1], ratioMin[2]);
		newGainmap.setRatioMax(ratioMax[0], ratioMax[1], ratioMax[2]);
		newGainmap.setGamma(gamma[0], gamma[1], gamma[2]);
		newGainmap.setEpsilonSdr(epsilonSdr[0], epsilonSdr[1], epsilonSdr[2]);
		newGainmap.setEpsilonHdr(epsilonHdr[0], epsilonHdr[1], epsilonHdr[2]);
		newGainmap.setDisplayRatioForFullHdr(sourceGainmap.getDisplayRatioForFullHdr());
		newGainmap.setMinDisplayRatioForHdrTransition(
			sourceGainmap.getMinDisplayRatioForHdrTransition());
	}

	/**
	 * Scan data for the XMP "hdrgm" namespace marker — the signature of an Ultra HDR
	 * gain map. Scans the full byte array (not a prefix window): a maxed-out EXIF thumbnail can
	 * push the XMP segment past any fixed offset. Linear but cheap (~5ms for 20MB on modern
	 * hardware) and runs at most once per export.
	 */
	public static boolean containsHdrgm(byte[] data)
	{
		if (data == null)
		{
			return false;
		}
		// For a 5-byte pattern, last valid start index is (length - 5),
		// so the exclusive loop bound is (length - 4).
		int limit = data.length - 4;
		for (int i = 0; i < limit; i++)
		{
			if (data[i] == 'h' && data[i + 1] == 'd' && data[i + 2] == 'r'
				&& data[i + 3] == 'g' && data[i + 4] == 'm')
			{
				return true;
			}
		}
		return false;
	}
}
