package com.cropcenter.crop;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import com.cropcenter.metadata.ExifPatcher;
import com.cropcenter.metadata.GainMapComposer;
import com.cropcenter.metadata.JpegMetadataInjector;
import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.model.CropState;
import com.cropcenter.model.ExportConfig;
import com.cropcenter.model.GridConfig;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.UltraHdrCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Full export pipeline: render → compress → inject original metadata (EXIF patched, ICC/XMP/MPF
 * preserved) → append gain map and fix MPF offsets.
 */
public final class CropExporter
{
	public record ExportResult(byte[] data, String extension) {}

	private static final String TAG = "CropExporter";
	private static final int CANVAS_BG = 0xFF0D0E14; // opaque very-dark-navy — visible at rotation corners
	private static final int MAX_THUMBNAIL_BUDGET = 60_000; // JPEG thumbnail cap (leaves room under APP1 limit)
	// Used when no EXIF is present to measure against — defaults to the same cap. Kept as a
	// separate constant so the two can diverge later without a literal hunt.
	private static final int THUMBNAIL_DEFAULT_BUDGET = MAX_THUMBNAIL_BUDGET;
	private static final int THUMBNAIL_MARGIN_BYTES = 200; // margin for IFD changes beyond measured size
	private static final int THUMBNAIL_MAX_DIM = 1024;

	private CropExporter() {}

	public static ExportResult export(CropState state, File cacheDir)
		throws IOException
	{
		Bitmap src = state.getSourceImage();
		if (src == null)
		{
			throw new IOException("No image loaded");
		}

		int cropW;
		int cropH;
		float srcX;
		float srcY;
		if (state.hasCenter())
		{
			cropW = state.getCropW();
			cropH = state.getCropH();
			// Use the continuous-float origin so the exported primary samples the source at
			// exactly the position the editor is showing. BitmapUtils.drawCropped handles
			// fractional srcX / srcY (falls back to bilinear when non-integer, integer blit
			// otherwise). UltraHdrCompat uses the same origin for its primary + gain-map
			// render, so the two stay pixel-aligned with each other.
			srcX = state.getCropImageXFloat();
			srcY = state.getCropImageYFloat();
		}
		else
		{
			cropW = src.getWidth();
			cropH = src.getHeight();
			srcX = 0f;
			srcY = 0f;
		}

		// Create output bitmap. Use Display P3 ONLY for JPEG when the source carries a gain map
		// (Ultra HDR): the gain map was tuned against a P3-gamut base, so composing it onto an
		// sRGB primary produces a subtly wrong HDR boost. PNG always uses sRGB — color-managed
		// canvases can apply subtle filtering during rasterization, causing grid lines to render
		// at inconsistent widths or drop out.
		boolean isJpeg = ExportConfig.FORMAT_JPEG.equals(state.getExportConfig().format());
		boolean hasGainMap = state.getGainMap() != null && state.getGainMap().length > 0;
		Bitmap outBmp;
		if (isJpeg && hasGainMap)
		{
			outBmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888, true,
				ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
		}
		else
		{
			outBmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888);
		}

		// outBmp ownership transfers to exportJpeg / exportPng on the success path — both
		// recycle in their own finally. But if drawCropped or drawGridPixels throws
		// (OOM on huge inputs is the realistic case), or if the switch hits the encode-
		// failure branch before ownership transfers, outBmp would leak its native pixel
		// buffer to the GC finalizer. The handedOff flag flips true the moment the
		// switch is about to delegate, so the catch / non-success paths recycle locally.
		boolean handedOff = false;
		try
		{
			Canvas canvas = new Canvas(outBmp);
			Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
			// JPEG can't represent alpha — fill with the editor's canvas color so rotation
			// corners and any transparent source pixels read as the same dark navy the user
			// saw in the preview. PNG keeps the bitmap's default transparent state so alpha
			// sources round-trip and rotation corners stay see-through.
			if (isJpeg)
			{
				canvas.drawColor(CANVAS_BG);
			}

			BitmapUtils.drawCropped(canvas, src, srcX, srcY, state.getRotationDegrees(), paint);

			// Optional grid overlay bake-in (independent of whether grid is visible on screen)
			GridConfig grid = state.getGridConfig();
			if (grid.includeInExport())
			{
				drawGridPixels(outBmp, cropW, cropH, grid);
			}

			handedOff = true;
			return switch (state.getExportConfig().format())
			{
				case ExportConfig.FORMAT_JPEG -> exportJpeg(state, outBmp, cropW, cropH, cacheDir);
				default -> exportPng(state, outBmp, cropW, cropH);
			};
		}
		finally
		{
			if (!handedOff)
			{
				outBmp.recycle();
			}
		}
	}

	/**
	 * Re-append an existing SEFT trailer verbatim, or return the JPEG unchanged when none was
	 * captured at load. CropCenter does not generate fresh SEFTs — Samsung Gallery's Revert
	 * validates a backup path the SEFT claims, and only honors paths under Samsung-blessed
	 * locations like `/data/sec/photoeditor/` that third-party apps cannot write to. A SEFT
	 * we generate pointing at our own `/storage/emulated/0/.cropcenter/` write is silently
	 * rejected by Gallery, so fabricating one is a net negative (disk bloat with no Revert
	 * benefit). Files that came in with a SEFT — Gallery-edited originals — keep their
	 * working Revert chain because we re-append exactly the bytes we extracted at load.
	 */
	private static byte[] appendSeft(byte[] jpeg, byte[] existingSeft)
	{
		if (existingSeft == null || existingSeft.length == 0)
		{
			return jpeg;
		}
		Log.d(TAG, "Preserving existing SEFT trailer: " + existingSeft.length + " bytes");
		byte[] result = new byte[jpeg.length + existingSeft.length];
		System.arraycopy(jpeg, 0, result, 0, jpeg.length);
		System.arraycopy(existingSeft, 0, result, jpeg.length, existingSeft.length);
		return result;
	}

	/**
	 * Draw grid lines by directly setting pixels on the bitmap. Bypasses Canvas rasterization
	 * entirely — guaranteed to produce exact line widths regardless of bitmap color space or
	 * Canvas rendering quirks. Line positions are computed as continuous float offsets from
	 * the crop's top-left and then rounded to the nearest output pixel, matching what
	 * GridRenderer.linePos produces on the preview canvas.
	 */
	private static void drawGridPixels(Bitmap bmp, int width, int height, GridConfig grid)
	{
		int lineWidth = Math.max(1, Math.round(grid.lineWidth()));
		int halfLineWidth = lineWidth / 2;
		int color = grid.color();

		// Vertical lines
		int[] vertColumn = new int[lineWidth * height];
		Arrays.fill(vertColumn, color);
		for (int i = 1; i < grid.columns(); i++)
		{
			int x = gridLinePixel(i, grid.columns(), width);
			int left = Math.max(0, x - halfLineWidth);
			int right = Math.min(width, left + lineWidth);
			int actualWidth = right - left;
			if (actualWidth <= 0)
			{
				continue;
			}
			int[] band = (actualWidth == lineWidth)
				? vertColumn
				: filledBuffer(actualWidth * height, color);
			bmp.setPixels(band, 0, actualWidth, left, 0, actualWidth, height);
		}

		// Horizontal lines
		int[] horizBand = new int[width * lineWidth];
		Arrays.fill(horizBand, color);
		for (int i = 1; i < grid.rows(); i++)
		{
			int y = gridLinePixel(i, grid.rows(), height);
			int top = Math.max(0, y - halfLineWidth);
			int bottom = Math.min(height, top + lineWidth);
			int actualHeight = bottom - top;
			if (actualHeight <= 0)
			{
				continue;
			}
			int[] band = (actualHeight == lineWidth)
				? horizBand
				: filledBuffer(width * actualHeight, color);
			bmp.setPixels(band, 0, width, 0, top, width, actualHeight);
		}
	}

	private static int[] filledBuffer(int size, int color)
	{
		int[] buf = new int[size];
		Arrays.fill(buf, color);
		return buf;
	}

	/**
	 * Pixel index for grid line i of a count-N grid along one axis of the exported crop.
	 * Matches the continuous-float positions GridRenderer.linePos emits for the preview,
	 * rounded to the nearest output pixel. Second-half lines mirror the first half around
	 * dim / 2 so (i, count − i) pairs stay symmetric — Java's Math.round rounds half-up,
	 * which would break symmetry at half-integer positions (e.g. count=4, dim=10 produces
	 * raw values 2.5 and 7.5; rounding both half-up gives 3 and 8 instead of the
	 * symmetric 3 and 7).
	 *
	 * Known half-pixel divergence from the preview: for odd `dim` with `i * 2 == count`
	 * (the middle line), the preview draws at the fractional coord `dim / 2f` and
	 * anti-aliases across the two adjacent pixels. This exporter must pick one integer
	 * pixel index, so the middle line in the baked export sits on `ceil(dim / 2f)` while
	 * the preview's visual centre of mass is 0.5 px to its left. Acceptable because the
	 * preview is anti-aliased and the eye reads its centre, not its origin.
	 */
	private static int gridLinePixel(int i, int count, int dim)
	{
		if (i * 2 > count)
		{
			int mirror = (int) Math.round((double) dim * (count - i) / count);
			return dim - mirror;
		}
		return (int) Math.round((double) dim * i / count);
	}

	private static ExportResult exportJpeg(CropState state, Bitmap bmp, int cropW, int cropH,
		File cacheDir) throws IOException
	{
		int quality = 100;
		byte[] thumbnail = buildEmbeddedThumbnail(state, bmp);
		byte[] croppedGainMap = buildCroppedGainMap(state, cropW, cropH, cacheDir, quality);

		// Recycle the primary bitmap on every exit, including when bmp.compress throws
		// a native OOM / format error partway through — the non-finally version would
		// orphan the native pixel buffer for the GC finalizer to clean up later.
		byte[] jpegBytes;
		try
		{
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
			jpegBytes = bos.toByteArray();
		}
		finally
		{
			bmp.recycle();
		}

		jpegBytes = injectExifMetadata(jpegBytes, state, cropW, cropH, thumbnail);
		jpegBytes = composeGainMap(jpegBytes, state, croppedGainMap);
		jpegBytes = appendSeft(jpegBytes, state.getSeftTrailer());

		return new ExportResult(jpegBytes, "jpg");
	}

	/**
	 * Generate the embedded EXIF thumbnail sized to fit the available APP1 budget.
	 * Using the full remaining APP1 budget (minus IFD overhead) gives a thumbnail that
	 * matches camera-native resolution instead of being artificially shrunk. Returns
	 * null when the budget is too small for a meaningful thumbnail — replaceThumbnail
	 * preserves the existing one in that case.
	 */
	private static byte[] buildEmbeddedThumbnail(CropState state, Bitmap bmp)
	{
		List<JpegSegment> metaForThumb = state.getJpegMeta();
		int thumbBudget = (metaForThumb != null && !metaForThumb.isEmpty())
			? ExifPatcher.maxThumbnailBytes(metaForThumb) - THUMBNAIL_MARGIN_BYTES
			: THUMBNAIL_DEFAULT_BUDGET;
		thumbBudget = Math.clamp(thumbBudget, 0, MAX_THUMBNAIL_BUDGET);
		return generateThumbnail(bmp, THUMBNAIL_MAX_DIM, thumbBudget);
	}

	/**
	 * For HDR sources, render a cropped Ultra HDR JPEG via UltraHdrCompat and extract
	 * the gain-map bytes from its tail. The primary-image bytes still come from the
	 * canvas rendering above; this only harvests the gain map, which must be spatially
	 * aligned to the same crop / rotation as the primary. Returns null when the source
	 * isn't HDR or when UltraHdrCompat couldn't produce a valid output.
	 */
	private static byte[] buildCroppedGainMap(CropState state, int cropW, int cropH,
		File cacheDir, int quality)
	{
		byte[] originalBytes = state.getOriginalFileBytes();
		boolean hasHdr = state.getGainMap() != null && originalBytes != null;
		if (!hasHdr)
		{
			return null;
		}

		float centerX = state.hasCenter() ? state.getCenterX() : state.getImageWidth() / 2f;
		float centerY = state.hasCenter() ? state.getCenterY() : state.getImageHeight() / 2f;
		int exifOrient = BitmapUtils.readExifOrientation(originalBytes);
		byte[] hdrResult = UltraHdrCompat.compressWithGainmap(
			originalBytes, quality, cacheDir,
			state.getImageWidth(), state.getImageHeight(),
			centerX, centerY, cropW, cropH,
			state.getRotationDegrees(), exifOrient, state.getAiMask());
		if (hdrResult == null)
		{
			Log.d(TAG, "HDR generation failed, falling back to non-HDR");
			return null;
		}

		int pe = findPrimaryEnd(hdrResult);
		if (pe <= 0 || pe >= hdrResult.length)
		{
			return null;
		}
		byte[] gainMap = new byte[hdrResult.length - pe];
		System.arraycopy(hdrResult, pe, gainMap, 0, gainMap.length);
		Log.d(TAG, "Extracted gain map: " + gainMap.length + " bytes");
		return gainMap;
	}

	/**
	 * Patch the JPEG's EXIF metadata with new crop dimensions and the freshly-generated
	 * thumbnail, re-injecting the patched segments into the output bytes. No-op when the
	 * source carried no JPEG segment list.
	 */
	private static byte[] injectExifMetadata(byte[] jpegBytes, CropState state,
		int cropW, int cropH, byte[] thumbnail) throws IOException
	{
		List<JpegSegment> meta = state.getJpegMeta();
		if (meta == null || meta.isEmpty())
		{
			return jpegBytes;
		}
		List<JpegSegment> patched = ExifPatcher.patch(meta, cropW, cropH, thumbnail);
		return JpegMetadataInjector.inject(jpegBytes, patched);
	}

	/**
	 * Append the cropped gain map to the primary JPEG when HDR extraction succeeded.
	 * The original state.getGainMap() is aligned to the UNCROPPED / UNROTATED source,
	 * so we refuse to ship it onto a cropped / rotated primary — that would put
	 * gain-map blobs off the features they were meant to highlight. Better to drop HDR
	 * than ship a broken file; doExport's toast reports "[HDR dropped]" in that case.
	 */
	private static byte[] composeGainMap(byte[] jpegBytes, CropState state, byte[] croppedGainMap)
	{
		if (croppedGainMap != null && croppedGainMap.length > 0)
		{
			Log.d(TAG, "Appending cropped gain map: " + croppedGainMap.length + " bytes");
			return GainMapComposer.compose(jpegBytes, croppedGainMap);
		}
		if (state.getGainMap() != null && state.getGainMap().length > 0)
		{
			Log.d(TAG, "compressWithGainmap failed — dropping HDR to avoid misalignment");
		}
		return jpegBytes;
	}

	private static ExportResult exportPng(CropState state, Bitmap bmp, int cropW, int cropH)
	{
		// bmp is guaranteed sRGB for PNG exports (see export()); grid was rasterized on it with
		// exact pixel-width rectangles. Straight compress → PNG bytes.
		byte[] pngBytes;
		try
		{
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
			pngBytes = bos.toByteArray();
		}
		finally
		{
			bmp.recycle();
		}

		// Inject EXIF metadata via PNG eXIf chunk (PNG 1.6 spec)
		List<JpegSegment> meta = state.getJpegMeta();
		if (meta != null)
		{
			for (JpegSegment seg : ExifPatcher.patch(meta, cropW, cropH, null))
			{
				if (seg.isExif())
				{
					pngBytes = injectPngExif(pngBytes, seg.data());
					break; // only one EXIF segment
				}
			}
		}

		return new ExportResult(pngBytes, "png");
	}

	/**
	 * Find the end of the primary JPEG (position after first EOI). Used to determine where the
	 * gain map starts.
	 */
	private static int findPrimaryEnd(byte[] jpeg)
	{
		// Walk JPEG markers to find the primary's EOI
		int off = 2; // skip SOI
		while (off < jpeg.length - 1)
		{
			if ((jpeg[off] & 0xFF) != 0xFF)
			{
				off++;
				continue;
			}
			int marker = jpeg[off + 1] & 0xFF;
			if (marker == 0xD9)
			{
				return off + 2; // EOI found
			}
			if (marker == 0xDA)
			{
				// SOS — scan entropy data for EOI
				if (off + 3 >= jpeg.length)
				{
					break;
				}
				int sosLen = ((jpeg[off + 2] & 0xFF) << 8) | (jpeg[off + 3] & 0xFF);
				off += 2 + sosLen;
				while (off < jpeg.length - 1)
				{
					if ((jpeg[off] & 0xFF) != 0xFF)
					{
						off++;
						continue;
					}
					int next = jpeg[off + 1] & 0xFF;
					if (next == 0xD9)
					{
						return off + 2;
					}
					if (next == 0x00)
					{
						off += 2;
						continue;
					}
					if (next >= 0xD0 && next <= 0xD7)
					{
						off += 2;
						continue;
					}
					break;
				}
				continue;
			}
			if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7))
			{
				off += 2;
				continue;
			}
			if (off + 3 < jpeg.length)
			{
				int segLen = ((jpeg[off + 2] & 0xFF) << 8) | (jpeg[off + 3] & 0xFF);
				off += 2 + segLen;
			}
			else
			{
				off += 2;
			}
		}
		return -1; // not found
	}

	/**
	 * Produce an EXIF thumbnail JPEG that fits within maxBytes. Scales bmp down to maxDim on
	 * its longest side (never up), then tries decreasing quality levels until the compressed
	 * size fits. Falls back to halving the dimensions if even q50 is too large.
	 *
	 * The thumbnail is rendered into an sRGB bitmap regardless of `bmp`'s color space: when
	 * `bmp` is DISPLAY_P3 (used for HDR JPEG exports), Bitmap.compress would embed an APP2 ICC
	 * profile (~500-600 bytes) inside the thumbnail JPEG, and that overhead combined with a
	 * tight `maxBytes` budget can cause `ExifPatcher.replaceThumbnail` to silently reject the
	 * thumbnail for APP1 overflow. sRGB compression produces a plain baseline JPEG with no ICC
	 * segment, matching camera-native thumbnails and keeping the byte budget predictable.
	 */
	private static byte[] generateThumbnail(Bitmap bmp, int maxDim, int maxBytes)
	{
		if (maxBytes <= 0)
		{
			Log.w(TAG, "Thumbnail budget ≤ 0 — skipping generation");
			return null;
		}
		Bitmap thumb = null;
		try
		{
			int width = bmp.getWidth();
			int height = bmp.getHeight();

			// Compute scale in double precision: 512/5000 in float is 0.102399997f (not 0.1024),
			// which can drop 4096*0.1024=409.6 into 409.599988 and — once Math.round(float)
			// delegates to (int)floor(x + 0.5f) — occasionally land on 409 instead of 410.
			// Using double eliminates the drift entirely.
			double scale = Math.min((double) maxDim / width, (double) maxDim / height);
			scale = Math.min(scale, 1.0); // don't upscale
			int thumbWidth = Math.max(1, (int) Math.round(width * scale));
			int thumbHeight = Math.max(1, (int) Math.round(height * scale));

			thumb = renderSrgbThumb(bmp, thumbWidth, thumbHeight);

			// Match camera fidelity when the EXIF budget allows; fall through to scale-down only at q50.
			int[] qualities = { 90, 85, 80, 75, 70, 60, 50 };
			for (int quality : qualities)
			{
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				thumb.compress(Bitmap.CompressFormat.JPEG, quality, bos);
				byte[] result = bos.toByteArray();
				if (result.length <= maxBytes)
				{
					Log.d(TAG, "Thumbnail: " + thumbWidth + "x" + thumbHeight
						+ " q" + quality + " = " + result.length + "B");
					return result;
				}
			}

			// Still too large — halve dimensions and retry at mid quality.
			// IMPORTANT: recompute from (width * scale * 0.5) rather than (thumbWidth / 2). Integer
			// division on the already-rounded thumbWidth truncates: for a 4:5 source like 4000×5000
			// at scale 0.2048 this produced 819/2 = 409 instead of the correct round(409.6) = 410.
			// Going through the original scale preserves full precision end-to-end.
			thumb.recycle();
			int halvedWidth = Math.max(1, (int) Math.round(width * scale * 0.5));
			int halvedHeight = Math.max(1, (int) Math.round(height * scale * 0.5));
			thumb = renderSrgbThumb(bmp, halvedWidth, halvedHeight);
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			thumb.compress(Bitmap.CompressFormat.JPEG, 70, bos);
			byte[] result = bos.toByteArray();
			if (result.length <= maxBytes)
			{
				Log.d(TAG, "Thumbnail (halved): " + halvedWidth + "x" + halvedHeight
					+ " q70 = " + result.length + "B");
				return result;
			}
			Log.w(TAG, "Thumbnail too large even at halved size: " + result.length + " > " + maxBytes);
			return null;
		}
		catch (Exception e)
		{
			Log.w(TAG, "Thumbnail generation failed", e);
			return null;
		}
		finally
		{
			if (thumb != null && !thumb.isRecycled())
			{
				thumb.recycle();
			}
		}
	}

	/**
	 * Render `src` into a fresh sRGB ARGB_8888 bitmap at the requested dimensions using a
	 * bilinear-filtered Canvas draw. The output is guaranteed to compress to a plain baseline
	 * JPEG with no ICC profile APP2 segment, regardless of `src`'s color space.
	 */
	private static Bitmap renderSrgbThumb(Bitmap src, int width, int height)
	{
		Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true,
			ColorSpace.get(ColorSpace.Named.SRGB));
		Canvas canvas = new Canvas(out);
		Rect srcRect = new Rect(0, 0, src.getWidth(), src.getHeight());
		Rect dstRect = new Rect(0, 0, width, height);
		Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
		canvas.drawBitmap(src, srcRect, dstRect, paint);
		return out;
	}

	/**
	 * Inject EXIF data into a PNG as an eXIf chunk, inserted after IHDR. The eXIf chunk
	 * contains raw TIFF data (from EXIF APP1, minus the FF E1 length "Exif\0\0" wrapper).
	 */
	private static byte[] injectPngExif(byte[] png, byte[] exifApp1)
	{
		// exifApp1 = FF E1 LL LL "Exif\0\0" [TIFF data...]
		// eXIf chunk data = just the TIFF data (starting at byte 10)
		if (exifApp1.length <= 10)
		{
			return png;
		}
		int tiffLen = exifApp1.length - 10;
		byte[] tiffData = new byte[tiffLen];
		System.arraycopy(exifApp1, 10, tiffData, 0, tiffLen);

		// PNG structure: 8-byte signature, then chunks.
		// Insert eXIf after the first chunk (IHDR).
		if (png.length < 8 + 12)
		{
			return png; // too small
		}

		// Find end of IHDR chunk: signature(8) + length(4) + "IHDR"(4) + data(13) + CRC(4) = 33
		int ihdrLen = ((png[8] & 0xFF) << 24) | ((png[9] & 0xFF) << 16)
				| ((png[10] & 0xFF) << 8) | (png[11] & 0xFF);
		int insertPos = 8 + 4 + 4 + ihdrLen + 4; // after IHDR chunk
		if (insertPos > png.length)
		{
			return png;
		}

		// Build eXIf chunk: length(4) + "eXIf"(4) + tiffData + CRC(4)
		byte[] chunkType = { 'e', 'X', 'I', 'f' };
		byte[] chunkLenBytes = {
				(byte) (tiffLen >> 24), (byte) (tiffLen >> 16),
				(byte) (tiffLen >> 8), (byte) (tiffLen)
		};

		// CRC32 covers chunk type + data
		CRC32 crc = new CRC32();
		crc.update(chunkType);
		crc.update(tiffData);
		long crcVal = crc.getValue();
		byte[] crcBytes = {
				(byte) (crcVal >> 24), (byte) (crcVal >> 16),
				(byte) (crcVal >> 8), (byte) (crcVal)
		};

		int chunkTotal = 4 + 4 + tiffLen + 4;
		byte[] result = new byte[png.length + chunkTotal];
		System.arraycopy(png, 0, result, 0, insertPos);
		System.arraycopy(chunkLenBytes, 0, result, insertPos, 4);
		System.arraycopy(chunkType, 0, result, insertPos + 4, 4);
		System.arraycopy(tiffData, 0, result, insertPos + 8, tiffLen);
		System.arraycopy(crcBytes, 0, result, insertPos + 8 + tiffLen, 4);
		System.arraycopy(png, insertPos, result, insertPos + chunkTotal, png.length - insertPos);

		Log.d(TAG, "Injected eXIf chunk: " + tiffLen + " bytes TIFF data");
		return result;
	}
}
