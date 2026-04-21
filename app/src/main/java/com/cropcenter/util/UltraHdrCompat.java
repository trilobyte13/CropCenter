package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

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
		File tmp = null;
		try
		{
			tmp = new File(cacheDir, "hdr_src.jpg");
			try (FileOutputStream fos = new FileOutputStream(tmp))
			{
				fos.write(originalBytes);
			}
			BitmapFactory.Options opts = new BitmapFactory.Options();
			opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
			current = BitmapFactory.decodeFile(tmp.getAbsolutePath(), opts);
			tmp.delete();
			tmp = null;

			if (current == null || !current.hasGainmap())
			{
				Log.d(TAG, "No gainmap in source");
				return null;
			}

			Log.d(TAG, "Decoded: " + current.getWidth() + "x" + current.getHeight()
				+ " hasGm=" + current.hasGainmap()
				+ " expected=" + imgW + "x" + imgH
				+ " exif=" + exifOrientation);

			// BitmapFactory.decodeFile does not auto-apply EXIF orientation. For orientations
			// that swap dimensions (5/6/7/8) the previous heuristic "autoRotated = decoded W/H
			// matches display W/H" worked incidentally. But for orientations 2/3/4 — mirror,
			// 180°, vertical flip — the decoded dimensions equal the display dimensions even
			// though the pixels still need the EXIF matrix, so the heuristic silently
			// skipped the transform and the exported HDR primary was rendered un-mirrored /
			// un-flipped. Always apply the matrix when orientation > 1. Use filter=false:
			// EXIF transforms are pure mirror / 90° / 180° operations — lossless integer
			// pixel remaps — and bilinear would only add softening.
			if (exifOrientation > 1)
			{
				Matrix matrix = BitmapUtils.orientationMatrix(exifOrientation);
				Bitmap rotated = Bitmap.createBitmap(current, 0, 0,
					current.getWidth(), current.getHeight(), matrix, false);
				if (rotated != current)
				{
					current.recycle();
					current = rotated;
				}
				Log.d(TAG, "EXIF applied: " + current.getWidth() + "x" + current.getHeight()
					+ " hasGm=" + current.hasGainmap());
			}

			// Capture gainmap before the rendering step may drop it.
			Gainmap srcGm = current.hasGainmap() ? current.getGainmap() : null;
			Bitmap gmBmp = srcGm != null ? srcGm.getGainmapContents() : null;

			// Crop origin — matches CropExporter.export() exactly.
			float srcX = centerX - cropW / 2f;
			float srcY = centerY - cropH / 2f;

			// Render primary via shared BitmapUtils.drawCropped — identical to CropExporter.
			Bitmap output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888, true,
				ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
			Canvas canvas = new Canvas(output);
			Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
			BitmapUtils.drawCropped(canvas, current, srcX, srcY, userRotation, paint);

			// Apply the same transform to the gain map so primary + gainmap stay aligned.
			if (srcGm != null && gmBmp != null)
			{
				float gmScaleX = (float) gmBmp.getWidth() / current.getWidth();
				float gmScaleY = (float) gmBmp.getHeight() / current.getHeight();
				int gmOutW = Math.max(1, Math.round(cropW * gmScaleX));
				int gmOutH = Math.max(1, Math.round(cropH * gmScaleY));

				Bitmap.Config gmConfig = gmBmp.getConfig() != null
					? gmBmp.getConfig()
						: Bitmap.Config.ARGB_8888;
				Bitmap gmOutput = Bitmap.createBitmap(gmOutW, gmOutH, gmConfig);
				Canvas gmCanvas = new Canvas(gmOutput);
				Paint gmPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

				float gmDrawX = -srcX * gmScaleX;
				float gmDrawY = -srcY * gmScaleY;

				if (Math.abs(userRotation) >= BitmapUtils.ROTATION_EPSILON)
				{
					gmCanvas.save();
					gmCanvas.rotate(userRotation,
						gmDrawX + gmBmp.getWidth() / 2f,
						gmDrawY + gmBmp.getHeight() / 2f);
					// Cardinal rotations: disable bilinear so nearest-neighbor reads
					// pixels verbatim — matches the primary path and keeps the gain
					// map aligned pixel-for-pixel with the primary.
					if (BitmapUtils.isCardinalRotation(userRotation))
					{
						Paint gmNearest = new Paint(gmPaint);
						gmNearest.setFilterBitmap(false);
						gmCanvas.drawBitmap(gmBmp, gmDrawX, gmDrawY, gmNearest);
					}
					else
					{
						gmCanvas.drawBitmap(gmBmp, gmDrawX, gmDrawY, gmPaint);
					}
					gmCanvas.restore();
				}
				else
				{
					gmCanvas.drawBitmap(gmBmp, gmDrawX, gmDrawY, gmPaint);
				}

				Gainmap newGm = new Gainmap(gmOutput);
				float[] ratioMin = srcGm.getRatioMin();
				float[] ratioMax = srcGm.getRatioMax();
				float[] gamma = srcGm.getGamma();
				float[] epsilonSdr = srcGm.getEpsilonSdr();
				float[] epsilonHdr = srcGm.getEpsilonHdr();
				newGm.setRatioMin(ratioMin[0], ratioMin[1], ratioMin[2]);
				newGm.setRatioMax(ratioMax[0], ratioMax[1], ratioMax[2]);
				newGm.setGamma(gamma[0], gamma[1], gamma[2]);
				newGm.setEpsilonSdr(epsilonSdr[0], epsilonSdr[1], epsilonSdr[2]);
				newGm.setEpsilonHdr(epsilonHdr[0], epsilonHdr[1], epsilonHdr[2]);
				newGm.setDisplayRatioForFullHdr(srcGm.getDisplayRatioForFullHdr());
				newGm.setMinDisplayRatioForHdrTransition(srcGm.getMinDisplayRatioForHdrTransition());

				output.setGainmap(newGm);
				Log.d(TAG, "Gainmap rendered: " + gmOutW + "x" + gmOutH
					+ " (scale " + gmScaleX + "x" + gmScaleY + ")");
			}

			current.recycle();
			current = null;

			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			output.compress(Bitmap.CompressFormat.JPEG, quality, bos);
			byte[] result = bos.toByteArray();
			output.recycle();

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
			if (current != null)
			{
				current.recycle();
			}
			if (tmp != null)
			{
				tmp.delete();
			}
		}
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
