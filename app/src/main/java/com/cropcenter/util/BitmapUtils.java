package com.cropcenter.util;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

import com.cropcenter.metadata.JpegMarker;
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

	// Safe floor for the decode-pixel cap on devices we haven't queried yet (or on test runs where no
	// Context is available). 32 MP (~33.5M pixels) keeps the decoded bitmap under ~128 MB ARGB which fits
	// comfortably on the ~256-512 MB heap a low-end Android device gives an app. The device-adaptive
	// path raises this for high-RAM devices via initialize(Context) — see computeMaxDecodePixels.
	private static final int DECODE_PIXELS_FLOOR = 32 * 1024 * 1024;

	// Hard ceiling on the device-adaptive cap. 512 MP × 4 bytes ARGB ≈ 2 GB; even on a 16 GB Samsung
	// flagship with native bitmap memory, holding more than this in a single bitmap risks crowding out
	// other apps and pushing CropCenter into the low-memory-killer's kill list. The current Samsung
	// max-resolution mode is 200 MP (16384×12288 ≈ 201M pixels), so 512 MP leaves room for one round of
	// future sensor upgrades.
	private static final int DECODE_PIXELS_CEILING = 512 * 1024 * 1024;

	// Cached cap. Defaults to the floor so callers running before initialize(Context) (e.g., a background-thread
	// bg-init race, a JUnit test) get the safe value. Set once at MainActivity.onCreate time via initialize;
	// reads are unsynchronised (volatile) since the value is set-once per process.
	private static volatile int cachedMaxDecodePixels = DECODE_PIXELS_FLOOR;

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
	 * Compute the largest power-of-2 inSampleSize that fits (outWidth × outHeight) within `maxPixels` total
	 * after subsampling — i.e., the smallest sample size that ensures `(outWidth / s) × (outHeight / s) <=
	 * maxPixels`. Returns 1 when the un-subsampled image already fits; doubles until the constraint holds.
	 *
	 * Used by ImageLoadController.applyBytes and UltraHdrCompat.decodeHdrBitmap to bound peak decode memory
	 * on very-large sources (Samsung's 200 MP mode produces 16384×12288 captures ≈ 200M pixels = ~800 MB
	 * ARGB). On a 12 GB-RAM flagship the device-adaptive cap (`getMaxDecodePixels`) returns ~187 MP, so
	 * those sources decode at `inSampleSize=1` (no quality loss). On a 4 GB-RAM device the cap floors at
	 * 32 MP and subsampling kicks in. The trade-off when sampleSize > 1 is that the saved crop uses the
	 * subsampled bitmap as its source, so output resolution scales down — preferable to instant OOM.
	 *
	 * Android's BitmapFactory.Options.inSampleSize requires a power of 2 (any other value is rounded down
	 * to the nearest power of 2 internally), so this helper produces 1, 2, 4, 8, ... rather than the exact
	 * minimum ratio.
	 *
	 * @param outWidth  decoded image width before subsampling (from BitmapFactory bounds pre-pass)
	 * @param outHeight decoded image height before subsampling
	 * @param maxPixels total subsampled-pixel budget (e.g., 32_000_000 for ~32 MP)
	 * @return power-of-2 sample size such that the subsampled bitmap fits within maxPixels
	 */
	public static int computeInSampleSize(int outWidth, int outHeight, int maxPixels)
	{
		if (outWidth <= 0 || outHeight <= 0 || maxPixels <= 0)
		{
			return 1;
		}
		int sampleSize = 1;
		long subsampledPixels = (long) outWidth * outHeight;
		while (subsampledPixels > maxPixels)
		{
			sampleSize *= 2;
			subsampledPixels /= 4;
		}
		return sampleSize;
	}

	/**
	 * Compute the device-adaptive decode-pixel cap from total device RAM
	 * (`ActivityManager.getMemoryInfo().totalMem`). Budgets 1/16 of total system RAM for the largest single
	 * bitmap, clamped to `[DECODE_PIXELS_FLOOR, DECODE_PIXELS_CEILING]` so a 4 GB-RAM phone gets a sensible cap
	 * (~64 MP) and a 12 GB Samsung flagship gets a much higher cap (~187 MP, enough to handle a 200 MP capture
	 * at `inSampleSize=1`). The 1/16 fraction leaves room for the export pipeline's working bitmaps
	 * (primary canvas + gain-map render + EXIF-rotation temporary + Bitmap.compress encoder buffer)
	 * — peak HDR-save allocation is roughly 4× the source bitmap, so 1/16 keeps all 4 within 1/4 of RAM
	 * even before the kernel's bitmap-native-memory headroom kicks in.
	 *
	 * Separate from `initialize(Context)` so the pure-math piece is testable without an Android Context.
	 *
	 * @param totalMemBytes device's total RAM in bytes (from ActivityManager.MemoryInfo.totalMem)
	 * @return pixel-count cap, clamped to [DECODE_PIXELS_FLOOR, DECODE_PIXELS_CEILING]
	 */
	public static int computeMaxDecodePixels(long totalMemBytes)
	{
		// Long arithmetic: totalMemBytes can be 16 GB+ on flagship devices (16 * 1024^3 = 17.2 billion bytes,
		// far past int range). Divide by 16 to get the bitmap budget in bytes, then by 4 to convert bytes to
		// ARGB pixels. Math.clamp(long, int, int) is a Java 21 overload that returns int directly; the cast
		// on the result is structural (the compiler picks the right overload from the int bounds) and the
		// returned int is guaranteed to fit because the ceiling is < Integer.MAX_VALUE.
		long pixelBudget = totalMemBytes / 16L / 4L;
		return Math.clamp(pixelBudget, DECODE_PIXELS_FLOOR, DECODE_PIXELS_CEILING);
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
	 * Current decoded-bitmap pixel-count cap. Returned value is the device-adaptive cap set by
	 * `initialize(Context)` at app startup, or `DECODE_PIXELS_FLOOR` (32 MP) when initialize hasn't run yet
	 * (test runs, early bg-thread decode before the Activity's onCreate completed).
	 *
	 * Used by `ImageLoadController.applyBytes` and `UltraHdrCompat.decodeHdrBitmap` as the budget threshold for
	 * the inSampleSize pre-pass — anything larger gets subsampled at the smallest power-of-2 that fits the
	 * pixel count under the cap. `EditAligner.reorientEdit` deliberately does NOT route through this cap because
	 * `GraftWriter.graft` splices the edit's primary scan into the original's full-resolution EXIF / MPF / gainmap
	 * / SEFT package — a downsampled edit primary would disagree with the full-resolution metadata describing
	 * dimensions and gainmap offsets, silently corrupting Samsung Revert chains and HDR alignment. For graft
	 * inputs that genuinely don't fit, `reorientEdit` catches `OutOfMemoryError` and returns null, surfacing
	 * a clean "Couldn't decode the edit during reorientation" toast instead of a stale-metadata graft.
	 *
	 * @return current decoded-bitmap pixel-count cap (always in [DECODE_PIXELS_FLOOR, DECODE_PIXELS_CEILING])
	 */
	public static int getMaxDecodePixels()
	{
		return cachedMaxDecodePixels;
	}

	/**
	 * Set the decoded-bitmap pixel-count cap based on the device's total RAM. Idempotent — calling more than once
	 * with the same Context produces the same cap (totalMem is constant per device boot). Designed to be called
	 * once from `MainActivity.onCreate` so every subsequent decode site picks up the device-adaptive value.
	 *
	 * @param ctx any Context; only used to obtain ActivityManager.MemoryInfo (no reference is retained)
	 */
	public static void initialize(Context ctx)
	{
		ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
		if (am == null)
		{
			// Robolectric / unit-test contexts can return null for ACTIVITY_SERVICE on minimal stub
			// configurations. Fall back to the floor — safer than running with no cap, and the same
			// outcome as if initialize was never called.
			return;
		}
		ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
		am.getMemoryInfo(info);
		cachedMaxDecodePixels = computeMaxDecodePixels(info.totalMem);
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
	 * @param orientation EXIF orientation tag (1..8); values outside that range return an identity matrix
	 * @return transformation matrix that, when applied, brings stored pixels to display orientation
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
			if (marker == JpegMarker.SOS || marker == JpegMarker.EOI)
			{
				break;
			}
			if (marker == JpegMarker.STUFFING || marker == JpegMarker.TEM
				|| (marker >= JpegMarker.RST_FIRST && marker <= JpegMarker.RST_LAST))
			{
				off = afterMarker;
				continue;
			}
			if (afterMarker + 2 > jpeg.length)
			{
				return 1;
			}
			int segLen = ByteBufferUtils.readU16BE(jpeg, afterMarker);
			if (segLen < 2)
			{
				// Segment length MUST include the 2 length bytes themselves (JPEG spec). Zero or one
				// would advance off by 0 or 1 instead of the real segment size, getting stuck
				// mid-segment and mis-reading payload as the next marker. Matches the defensive guard
				// in every sister walker (JpegMarkerWalker.findPrimaryEoi,
				// JpegMetadataExtractor.extract, JpegMetadataInjector.inject, MpfPatcher.patch,
				// GraftWriter.findFirstNonAppNonCom, XmpItemLengthPatcher.walkApp1Ranges) — the
				// cross-walker invariant kept consistent.
				return 1;
			}

			// APP1 with Exif header
			if (marker == JpegMarker.APP1 && segLen > 14 && afterMarker + 8 <= jpeg.length
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
				if (tiffMagic != TiffTag.MAGIC)
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
					// Long-arithmetic stride matches sister walkers in ExifPatcher / MpfPatcher /
					// PngMetadataExtractor. JPEG inputs are 128 MB capped
					// so int stride is unreachable in practice; kept for cross-walker symmetry.
					long entryLong = (long) ifd + 2 + (long) i * 12;
					if (entryLong + 12 > jpeg.length)
					{
						break;
					}
					int entry = (int) entryLong;
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
