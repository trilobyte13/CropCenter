package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

import com.cropcenter.metadata.JpegMarkerWalker;
import com.cropcenter.metadata.TiffTag;

/**
 * Reads EXIF orientation from raw JPEG bytes and rotates the bitmap accordingly. BitmapFactory.decodeByteArray() does
 * NOT auto-apply EXIF rotation.
 */
public final class BitmapUtils
{
	// Rotation values with magnitude below this threshold (degrees) are treated as 0 for rendering purposes. The
	// ruler exposes 0.01° as its finest tick step (and the horizon detector / precise-rotation dialog round to
	// 0.01° too), so the threshold sits a half-step below that — anything ≥ 0.005° is honored end-to-end (renderer
	// rotates, readout shows, ExportPipeline can't bypass); anything below is below user control and would just
	// burn a bilinear pass for no visible benefit. On a 4000-px-wide image, 0.01° corresponds to a corner shift of
	// ~0.7 px, which is observable on fine vertical/horizontal lines.
	public static final float ROTATION_EPSILON = 0.005f;

	private BitmapUtils() {}

	/**
	 * Apply EXIF orientation to a bitmap, returning a correctly rotated bitmap. The input bitmap may be recycled if
	 * rotation was needed. EXIF orientations are pure mirror / 90° / 180° transforms — lossless integer-pixel
	 * remaps — so createBitmap uses filter=false to guarantee no bilinear softening.
	 *
	 * @param bmp         source bitmap; recycled when rotation produces a new instance
	 * @param orientation EXIF orientation tag value (1..8); values outside that range
	 *                    are treated as identity and the bitmap is returned unchanged
	 * @return correctly rotated bitmap (may be the same reference as bmp when
	 *         orientation == 1 or out of range)
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
	 * Draw a source bitmap onto a canvas representing the crop window, with optional rotation around the image
	 * center. Used by both CropExporter and UltraHdrCompat to ensure identical rendering.
	 *
	 * srcX / srcY are continuous-float image coordinates (= centerX − cropW/2f etc.). When they are integer-exact,
	 * the zero-rotation path takes an integer Rect-to-Rect blit that bypasses any filter-bitmap softening. When
	 * they are fractional (rotated selection, fine rotation drag) the zero-rotation path falls back to a
	 * float-offset drawBitmap which bilinear-samples sub-pixel positions — matching what the rotated path does. The
	 * rotated path always uses the float offset, so it handles fractional input natively.
	 *
	 * @param canvas   output canvas (cropW x cropH)
	 * @param src      source bitmap in display orientation
	 * @param srcX     crop origin X = centerX − cropW/2f (may be fractional)
	 * @param srcY     crop origin Y = centerY − cropH/2f (may be fractional)
	 * @param rotation rotation in degrees (0 = no rotation)
	 * @param paint    paint for bitmap drawing (FILTER_BITMAP_FLAG controls sub-pixel sampling)
	 */
	public static void drawCropped(Canvas canvas, Bitmap src, float srcX, float srcY, float rotation, Paint paint)
	{
		float drawX = -srcX;
		float drawY = -srcY;
		if (Math.abs(rotation) >= ROTATION_EPSILON)
		{
			canvas.save();
			canvas.rotate(rotation, drawX + src.getWidth() / 2f, drawY + src.getHeight() / 2f);

			// Cardinal rotations (±90°, 180°, ±270°) are pure integer-pixel remaps ONLY when srcX / srcY
			// are also integer-aligned — in that case, disable bilinear filtering so nearest-neighbor
			// sampling inherits source pixels verbatim. Fractional srcX / srcY mean dst pixels end up at
			// sub-pixel positions relative to the canvas grid, so we need bilinear to match the preview
			// (which draws at the same fractional offset). Non-cardinal rotations always bilinear-sample —
			// interpolation is inherent to the geometry.
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
			// Integer-aligned: Rect-to-Rect blit produces a lossless pixel copy regardless of the paint's
			// filter flag. Used when the crop has snapped to pixel boundaries (fit-to-view, rotation = 0 on
			// a cardinal-placed selection, etc.).
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
			// Fractional srcX / srcY (continuous-float crop origin during rotation or rotated-selection
			// placement). Draw at the float offset so Android's renderer bilinear-samples at sub-pixel
			// positions — matches the rotated path above and matches what the editor preview renders via
			// the same crop origin.
			canvas.drawBitmap(src, drawX, drawY, paint);
		}
	}

	/**
	 * True when `rotation` is within ROTATION_EPSILON of ±90°, 180°, or ±270° (mod 360). Cardinal rotations map
	 * integer source pixels to integer destination pixels and are therefore losslessly expressible with
	 * nearest-neighbor sampling. Non-cardinal rotations require bilinear filtering.
	 *
	 * 0° (and ±360°, ±720°, …) is explicitly NOT cardinal here — drawCropped's cardinal branch is the rotated path,
	 * and 0° already goes through the unrotated fast path higher up that gates on Math.abs(rotation) >=
	 * ROTATION_EPSILON. Including 0 would route an already-handled case through a strictly-worse code path.
	 *
	 * @param rotation rotation in degrees; sign / magnitude / mod-360 all handled
	 * @return true when the angle is within ROTATION_EPSILON of ±90° / 180° / ±270°
	 */
	public static boolean isCardinalRotation(float rotation)
	{
		float normalized = ((rotation % 360f) + 360f) % 360f;
		return Math.abs(normalized - 90f) < ROTATION_EPSILON || Math.abs(normalized - 180f) < ROTATION_EPSILON
			|| Math.abs(normalized - 270f) < ROTATION_EPSILON;
	}

	/**
	 * Build a Matrix for the given EXIF orientation value (1-8).
	 *
	 * @param orientation EXIF orientation tag (1..8); values outside that range
	 *                    return an identity matrix
	 * @return transformation matrix that, when applied, brings stored pixels to
	 *         display orientation
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
	 * Read EXIF orientation tag from raw JPEG bytes.
	 *
	 * @param jpeg full JPEG file bytes
	 * @return EXIF orientation 1..8, or 1 when the tag is absent, the bytes aren't a
	 *         JPEG, or the EXIF byte-order field is malformed
	 */
	public static int readExifOrientation(byte[] jpeg)
	{
		try
		{
			return readExifOrientationInternal(jpeg);
		}
		catch (IndexOutOfBoundsException ignored)
		{
			// Narrow catch — IOOBE is the only expected throw from ByteBufferUtils.checkRead's bounds
			// checks inside readU16/readU32. Any other RuntimeException (NPE, OOM, anything else) is
			// genuinely unexpected and should propagate so the caller's failure is real, not silently
			// masked. Per the documented contract, malformed EXIF returns 1 (upright) — the same fallback
			// as a missing tag. Corrupted EXIF is common in third-party-edited JPEGs; intentional swallow.
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
			// Fill bytes (legal per ITU-T T.81 §B.1.1.2) — skip extra 0xFF bytes before reading the marker.
			int markerByteOff = JpegMarkerWalker.skipFillBytes(jpeg, off, jpeg.length);
			if (markerByteOff < 0)
			{
				return 1;
			}
			int marker = jpeg[markerByteOff] & 0xFF;
			int afterMarker = markerByteOff + 1;
			if (marker == 0xDA || marker == 0xD9)
			{
				break; // SOS or EOI
			}
			if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7))
			{
				off = afterMarker;
				continue;
			}
			if (afterMarker + 2 > jpeg.length)
			{
				return 1;
			}
			int segLen = ByteBufferUtils.readU16BE(jpeg, afterMarker);

			// APP1 with Exif header
			if (marker == 0xE1 && segLen > 14 && afterMarker + 8 <= jpeg.length
				&& jpeg[afterMarker + 2] == 'E' && jpeg[afterMarker + 3] == 'x'
				&& jpeg[afterMarker + 4] == 'i' && jpeg[afterMarker + 5] == 'f'
				&& jpeg[afterMarker + 6] == 0 && jpeg[afterMarker + 7] == 0)
			{
				int tiffStart = afterMarker + 8; // TIFF header
				if (tiffStart + 8 > jpeg.length)
				{
					return 1;
				}
				// TIFF byte-order marker is 2 bytes — "II" (little) or "MM" (big). A malformed
				// mismatched pair would silently be treated as little-endian and produce nonsense u32
				// reads downstream.
				int byteOrderHi = jpeg[tiffStart] & 0xFF;
				int byteOrderLo = jpeg[tiffStart + 1] & 0xFF;
				if (!((byteOrderHi == 0x49 && byteOrderLo == 0x49)
					|| (byteOrderHi == 0x4D && byteOrderLo == 0x4D)))
				{
					return 1;
				}
				boolean isLittleEndian = byteOrderHi == 0x49;

				// TIFF magic = 42 (0x002A). A byte-order match without the magic value means the chunk
				// isn't really TIFF — without this check a malformed payload with plausible offsets and
				// a coincidental TiffTag.ORIENTATION byte sequence would rotate pixels.
				int tiffMagic = ByteBufferUtils.readU16(jpeg, tiffStart + 2, isLittleEndian);
				if (tiffMagic != 42)
				{
					return 1;
				}

				// Validate the long sum BEFORE casting — an adversarial u32 ifdOff like 0xFFFFFFFE plus
				// a small tiffStart wraps to a small positive int that passes both bounds checks on the
				// truncated value, letting the IFD entry walk read garbage as tag/type/value and rotate
				// pixels. Mirrors the long-arithmetic guard in ExifPatcher.scanIfd, MpfPatcher.patch,
				// and PngMetadataExtractor.extractOrientationInternal — was the lone JPEG-side site
				// that relied on signed-int wrap to catch the overflow.
				long ifdOff = ByteBufferUtils.readU32(jpeg, tiffStart + 4, isLittleEndian);
				long absIfd = (long) tiffStart + ifdOff;
				if (absIfd < tiffStart || absIfd + 2 > jpeg.length || absIfd > Integer.MAX_VALUE)
				{
					return 1;
				}
				int ifd = (int) absIfd;

				int count = ByteBufferUtils.readU16(jpeg, ifd, isLittleEndian);
				for (int i = 0; i < count; i++)
				{
					int entry = ifd + 2 + i * 12;
					if (entry + 12 > jpeg.length)
					{
						break;
					}
					int tag = ByteBufferUtils.readU16(jpeg, entry, isLittleEndian);
					if (tag == TiffTag.ORIENTATION)
					{
						return readOrientationFromIfdEntry(jpeg, entry, isLittleEndian);
					}
				}
				return 1; // EXIF found but no orientation tag
			}
			int next = afterMarker + segLen;
			if (next > jpeg.length || next < off)
			{
				return 1;
			}
			off = next;
		}
		return 1;
	}

	/**
	 * Read the orientation value from an IFD entry whose tag has already been confirmed to be TiffTag.ORIENTATION.
	 * Validates that the entry is well-formed (type SHORT, count 1, value 1..8) — any other shape is malformed
	 * and maps to upright (1). A coincidental TiffTag.ORIENTATION entry with the wrong type / count would
	 * otherwise have us reading random bytes as orientation. Real EXIF always emits this entry as SHORT/1.
	 *
	 * @param data           full JPEG / PNG-eXIf byte array
	 * @param entry          offset of the 12-byte IFD entry (tag at entry, type at entry+2, count at entry+4,
	 *                       value at entry+8)
	 * @param isLittleEndian TIFF byte order
	 * @return orientation 1..8, or 1 when the entry is malformed
	 */
	private static int readOrientationFromIfdEntry(byte[] data, int entry, boolean isLittleEndian)
	{
		int entryType = ByteBufferUtils.readU16(data, entry + 2, isLittleEndian);
		long entryCount = ByteBufferUtils.readU32(data, entry + 4, isLittleEndian);
		if (entryType != TiffTag.TYPE_SHORT || entryCount != 1)
		{
			return 1;
		}
		int orientation = ByteBufferUtils.readU16(data, entry + 8, isLittleEndian);
		if (orientation < 1 || orientation > 8)
		{
			return 1;
		}
		return orientation;
	}
}
