package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Reads EXIF orientation from raw JPEG bytes and rotates the bitmap accordingly.
 * BitmapFactory.decodeByteArray() does NOT auto-apply EXIF rotation.
 */
public final class BitmapUtils
{
	// Rotation values with magnitude below this threshold (degrees) are treated as
	// 0 for rendering purposes. The rotation ruler resolves at 0.1°, so any
	// sub-0.05° residue is below user control — honoring it forces an unnecessary
	// bilinear pass over the entire image for what the user sees as "0°".
	public static final float ROTATION_EPSILON = 0.05f;

	private BitmapUtils() {}

	/**
	 * Apply EXIF orientation to a bitmap, returning a correctly rotated bitmap.
	 * The input bitmap may be recycled if rotation was needed. EXIF orientations
	 * are pure mirror / 90° / 180° transforms — lossless integer-pixel remaps —
	 * so createBitmap uses filter=false to guarantee no bilinear softening.
	 */
	public static Bitmap applyOrientation(Bitmap bmp, int orientation)
	{
		if (orientation <= 1 || orientation > 8)
		{
			return bmp;
		}
		Matrix matrix = orientationMatrix(orientation);
		Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, false);
		if (rotated != bmp)
		{
			bmp.recycle();
		}
		return rotated;
	}

	/**
	 * Draw a source bitmap onto a canvas representing the crop window, with optional rotation
	 * around the image center. Used by both CropExporter and UltraHdrCompat to ensure identical
	 * rendering.
	 *
	 * srcX / srcY are continuous-float image coordinates (= centerX − cropW/2f etc.). When
	 * they are integer-exact, the zero-rotation path takes an integer Rect-to-Rect blit that
	 * bypasses any filter-bitmap softening. When they are fractional (rotated selection, fine
	 * rotation drag) the zero-rotation path falls back to a float-offset drawBitmap which
	 * bilinear-samples sub-pixel positions — matching what the rotated path does. The rotated
	 * path always uses the float offset, so it handles fractional input natively.
	 *
	 * @param canvas   output canvas (cropW x cropH)
	 * @param src      source bitmap in display orientation
	 * @param srcX     crop origin X = centerX − cropW/2f (may be fractional)
	 * @param srcY     crop origin Y = centerY − cropH/2f (may be fractional)
	 * @param rotation rotation in degrees (0 = no rotation)
	 * @param paint    paint for bitmap drawing (FILTER_BITMAP_FLAG controls sub-pixel sampling)
	 */
	public static void drawCropped(Canvas canvas, Bitmap src, float srcX, float srcY,
		float rotation, Paint paint)
	{
		float drawX = -srcX;
		float drawY = -srcY;
		if (Math.abs(rotation) >= ROTATION_EPSILON)
		{
			canvas.save();
			canvas.rotate(rotation,
				drawX + src.getWidth() / 2f,
				drawY + src.getHeight() / 2f);

			// Cardinal rotations (±90°, 180°, ±270°) are pure integer-pixel remaps ONLY when
			// srcX / srcY are also integer-aligned — in that case, disable bilinear filtering
			// so nearest-neighbor sampling inherits source pixels verbatim. Fractional srcX /
			// srcY mean dst pixels end up at sub-pixel positions relative to the canvas grid,
			// so we need bilinear to match the preview (which draws at the same fractional
			// offset). Non-cardinal rotations always bilinear-sample — interpolation is
			// inherent to the geometry.
			boolean integerAligned = srcX == Math.floor(srcX) && srcY == Math.floor(srcY);
			if (isCardinalRotation(rotation) && integerAligned)
			{
				Paint nearestPaint = new Paint(paint);
				nearestPaint.setFilterBitmap(false);
				canvas.drawBitmap(src, drawX, drawY, nearestPaint);
			}
			else
			{
				canvas.drawBitmap(src, drawX, drawY, paint);
			}
			canvas.restore();
		}
		else if (srcX == Math.floor(srcX) && srcY == Math.floor(srcY))
		{
			// Integer-aligned: Rect-to-Rect blit produces a lossless pixel copy regardless of
			// the paint's filter flag. Used when the crop has snapped to pixel boundaries
			// (fit-to-view, rotation = 0 on a cardinal-placed selection, etc.).
			int cropW = canvas.getWidth();
			int cropH = canvas.getHeight();
			int intSrcX = (int) srcX;
			int intSrcY = (int) srcY;
			int visibleLeft   = Math.max(0, intSrcX);
			int visibleTop    = Math.max(0, intSrcY);
			int visibleRight  = Math.min(src.getWidth(), intSrcX + cropW);
			int visibleBottom = Math.min(src.getHeight(), intSrcY + cropH);
			if (visibleRight > visibleLeft && visibleBottom > visibleTop)
			{
				Rect srcRect = new Rect(visibleLeft, visibleTop, visibleRight, visibleBottom);
				Rect dstRect = new Rect(visibleLeft - intSrcX, visibleTop - intSrcY,
					visibleRight - intSrcX, visibleBottom - intSrcY);
				canvas.drawBitmap(src, srcRect, dstRect, paint);
			}
		}
		else
		{
			// Fractional srcX / srcY (continuous-float crop origin during rotation or
			// rotated-selection placement). Draw at the float offset so Android's renderer
			// bilinear-samples at sub-pixel positions — matches the rotated path above and
			// matches what the editor preview renders via the same crop origin.
			canvas.drawBitmap(src, drawX, drawY, paint);
		}
	}

	/**
	 * True when `rotation` is within ROTATION_EPSILON of an exact multiple of 90°
	 * (±90°, 180°, ±270°, …). Cardinal rotations map integer source pixels to
	 * integer destination pixels and are therefore losslessly expressible with
	 * nearest-neighbor sampling. Non-cardinal rotations require bilinear filtering.
	 */
	public static boolean isCardinalRotation(float rotation)
	{
		float normalized = ((rotation % 360f) + 360f) % 360f;
		return Math.abs(normalized - 90f) < ROTATION_EPSILON
			|| Math.abs(normalized - 180f) < ROTATION_EPSILON
			|| Math.abs(normalized - 270f) < ROTATION_EPSILON;
	}

	/**
	 * Build a Matrix for the given EXIF orientation value (1-8).
	 */
	public static Matrix orientationMatrix(int orientation)
	{
		Matrix matrix = new Matrix();
		switch (orientation)
		{
			case 2 -> matrix.setScale(-1, 1);
			case 3 -> matrix.setRotate(180);
			case 4 -> matrix.setScale(1, -1);
			case 5 ->
			{
				matrix.setRotate(90);
				matrix.postScale(-1, 1);
			}
			case 6 -> matrix.setRotate(90);
			case 7 ->
			{
				matrix.setRotate(-90);
				matrix.postScale(-1, 1);
			}
			case 8 -> matrix.setRotate(-90);
		}
		return matrix;
	}

	/**
	 * Read EXIF orientation tag from raw JPEG bytes. Returns orientation value (1-8), or 1 if
	 * not found.
	 */
	public static int readExifOrientation(byte[] jpeg)
	{
		try
		{
			return readExifOrientationInternal(jpeg);
		}
		catch (Exception e)
		{
			return 1;
		}
	}

	private static int readExifOrientationInternal(byte[] jpeg)
	{
		if (jpeg == null || jpeg.length < 14)
		{
			return 1;
		}
		if ((jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8)
		{
			return 1;
		}

		int off = 2;
		while (off < jpeg.length - 4)
		{
			if ((jpeg[off] & 0xFF) != 0xFF)
			{
				return 1;
			}
			int marker = jpeg[off + 1] & 0xFF;
			if (marker == 0xDA || marker == 0xD9)
			{
				break; // SOS or EOI
			}
			if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7))
			{
				off += 2;
				continue;
			}
			int segLen = ByteBufferUtils.readU16BE(jpeg, off + 2);

			// APP1 with Exif header
			if (marker == 0xE1 && segLen > 14
				&& jpeg[off + 4] == 'E' && jpeg[off + 5] == 'x'
				&& jpeg[off + 6] == 'i' && jpeg[off + 7] == 'f'
				&& jpeg[off + 8] == 0 && jpeg[off + 9] == 0)
			{
				int tiffStart = off + 10; // TIFF header
				if (tiffStart + 8 > jpeg.length)
				{
					return 1;
				}
				boolean isLittleEndian = jpeg[tiffStart] == 0x49; // 'I' = little-endian

				long ifdOff = ByteBufferUtils.readU32(jpeg, tiffStart + 4, isLittleEndian);
				int ifd = (int) (tiffStart + ifdOff);
				if (ifd < tiffStart || ifd + 2 > jpeg.length)
				{
					return 1;
				}

				int count = ByteBufferUtils.readU16(jpeg, ifd, isLittleEndian);
				for (int i = 0; i < count; i++)
				{
					int entry = ifd + 2 + i * 12;
					if (entry + 12 > jpeg.length)
					{
						break;
					}
					int tag = ByteBufferUtils.readU16(jpeg, entry, isLittleEndian);
					if (tag == 0x0112) // Orientation
					{
						return ByteBufferUtils.readU16(jpeg, entry + 8, isLittleEndian);
					}
				}
				return 1; // EXIF found but no orientation tag
			}
			off += 2 + segLen;
		}
		return 1;
	}
}
