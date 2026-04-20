package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

// Reads EXIF orientation from raw JPEG bytes and rotates the bitmap accordingly.
// BitmapFactory.decodeByteArray() does NOT auto-apply EXIF rotation.
public final class BitmapUtils
{
	// Rotation values with magnitude below this threshold (degrees) are treated as
	// 0 for rendering purposes. The rotation ruler resolves at 0.1°, so any
	// sub-0.05° residue is below user control — honoring it forces an unnecessary
	// bilinear pass over the entire image for what the user sees as "0°".
	public static final float ROTATION_EPSILON = 0.05f;

	private BitmapUtils() {}

	// Apply EXIF orientation to a bitmap, returning a correctly rotated bitmap.
	// The input bitmap may be recycled if rotation was needed. EXIF orientations
	// are pure mirror / 90° / 180° transforms — lossless integer-pixel remaps —
	// so createBitmap uses filter=false to guarantee no bilinear softening.
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
	 * @param canvas   output canvas (cropW x cropH)
	 * @param src      source bitmap in display orientation
	 * @param srcX     crop origin X = centerX - cropW/2
	 * @param srcY     crop origin Y = centerY - cropH/2
	 * @param rotation rotation in degrees (0 = no rotation)
	 * @param paint    paint for bitmap drawing
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

			// Cardinal rotations (±90°, 180°, ±270°) are pure integer-pixel remaps
			// at the integer / half-integer pivots the parity invariant produces.
			// Disable bilinear filtering so nearest-neighbor sampling inherits
			// source pixels verbatim rather than cross-blending adjacent ones.
			// Non-cardinal rotations require bilinear — interpolation is inherent
			// to the geometry.
			if (isCardinalRotation(rotation))
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
		else
		{
			int cropW = canvas.getWidth();
			int cropH = canvas.getHeight();
			int intSrcX = (int) Math.floor(srcX);
			int intSrcY = (int) Math.floor(srcY);
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
	}

	// True when `rotation` is within ROTATION_EPSILON of an exact multiple of 90°
	// (±90°, 180°, ±270°, …). Cardinal rotations map integer source pixels to
	// integer destination pixels and are therefore losslessly expressible with
	// nearest-neighbor sampling. Non-cardinal rotations require bilinear filtering.
	public static boolean isCardinalRotation(float rotation)
	{
		float normalized = ((rotation % 360f) + 360f) % 360f;
		return Math.abs(normalized - 90f) < ROTATION_EPSILON
			|| Math.abs(normalized - 180f) < ROTATION_EPSILON
			|| Math.abs(normalized - 270f) < ROTATION_EPSILON;
	}

	// Build a Matrix for the given EXIF orientation value (1-8).
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

	// Read EXIF orientation tag from raw JPEG bytes. Returns orientation value (1-8), or 1 if
	// not found.
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
