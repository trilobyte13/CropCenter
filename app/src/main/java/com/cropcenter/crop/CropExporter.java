package com.cropcenter.crop;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.util.Log;

import com.cropcenter.metadata.ExifPatcher;
import com.cropcenter.metadata.GainMapComposer;
import com.cropcenter.metadata.JpegMetadataInjector;
import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.metadata.SeftBuilder;
import com.cropcenter.model.CropState;
import com.cropcenter.model.ExportConfig;
import com.cropcenter.model.GridConfig;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.UltraHdrCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

// Full export pipeline: render → compress → inject original metadata (EXIF patched, ICC/XMP/MPF
// preserved) → append gain map and fix MPF offsets.
public final class CropExporter
{
	// Status of a saveOriginalBackup() attempt.
	public enum BackupStatus
	{
		// Not applicable — source isn't a MediaStore file we can back up.
		NOT_APPLICABLE,
		// A backup for this file was already on disk; left untouched.
		ALREADY_EXISTS,
		// Wrote a fresh backup.
		WRITTEN,
		// Backup was needed but writing failed — Gallery Revert will not work.
		FAILED
	}

	public record ExportResult(byte[] data, String extension) {}

	private static final String TAG = "CropExporter";
	private static final int CANVAS_BG = 0xFF0D0E14; // opaque very-dark-navy — visible at rotation corners
	private static final int MAX_THUMBNAIL_BUDGET = 60_000; // JPEG thumbnail cap (leaves room under APP1 limit)
	private static final int MIN_THUMBNAIL_BUDGET = 2_000;  // floor for the EXIF thumbnail
	private static final int THUMBNAIL_DEFAULT_BUDGET = 60_000; // when no EXIF to measure against
	private static final int THUMBNAIL_MAX_DIM = 1024;
	private static final int THUMBNAIL_MARGIN_BYTES = 200; // margin for IFD changes beyond measured size

	private CropExporter() {}

	public static ExportResult export(CropState state, File cacheDir) throws IOException
	{
		Bitmap src = state.getSourceImage();
		if (src == null)
		{
			throw new IOException("No image loaded");
		}

		int cropW;
		int cropH;
		int srcX;
		int srcY;
		if (state.hasCenter())
		{
			cropW = state.getCropW();
			cropH = state.getCropH();
			float centerX = state.getCenterX();
			float centerY = state.getCenterY();
			srcX = (int) Math.floor(centerX - cropW / 2f);
			srcY = (int) Math.floor(centerY - cropH / 2f);
		}
		else
		{
			cropW = src.getWidth();
			cropH = src.getHeight();
			srcX = 0;
			srcY = 0;
		}

		// Create output bitmap. Use Display P3 ONLY for JPEG when the source carries a gain map
		// (Ultra HDR): the gain map was tuned against a P3-gamut base, so composing it onto an
		// sRGB primary produces a subtly wrong HDR boost. PNG always uses sRGB — color-managed
		// canvases can apply subtle filtering during rasterization, causing grid lines to render
		// at inconsistent widths or drop out.
		boolean isJpeg = ExportConfig.FORMAT_JPEG.equals(state.getExportConfig().format);
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

		Canvas canvas = new Canvas(outBmp);
		Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
		canvas.drawColor(CANVAS_BG);

		BitmapUtils.drawCropped(canvas, src, srcX, srcY, state.getRotationDegrees(), paint);

		// Optional grid overlay bake-in (independent of whether grid is visible on screen)
		GridConfig grid = state.getGridConfig();
		if (grid.includeInExport)
		{
			drawGridPixels(outBmp, cropW, cropH, grid);
		}

		return switch (state.getExportConfig().format)
		{
			case ExportConfig.FORMAT_JPEG -> exportJpeg(state, outBmp, cropW, cropH, cacheDir);
			default -> exportPng(state, outBmp, cropW, cropH);
		};
	}

	/**
	 * Save the original file to shared storage for Samsung Gallery Revert.
	 * Uses /storage/emulated/0/.cropcenter/ which is readable by Gallery.
	 * Must be called before overwriting the original file.
	 *
	 * @return a BackupStatus so the caller can surface FAILED cases (missing storage
	 *         permission, quota, etc.) to the user instead of silently overwriting without a
	 *         revertable backup.
	 */
	public static BackupStatus saveOriginalBackup(CropState state)
	{
		if (state.getOriginalFilePath() == null || state.getMediaStoreId() < 0)
		{
			return BackupStatus.NOT_APPLICABLE;
		}
		byte[] origBytes = state.getOriginalFileBytes();
		if (origBytes == null)
		{
			return BackupStatus.NOT_APPLICABLE;
		}

		String backupPath = SeftBuilder.generateBackupPath(
			state.getOriginalFilePath(), state.getMediaStoreId());
		File backupFile = new File(backupPath);

		// Don't overwrite an existing backup (might be from a previous edit)
		if (backupFile.exists())
		{
			Log.d(TAG, "Backup already exists: " + backupPath);
			return BackupStatus.ALREADY_EXISTS;
		}

		try
		{
			File dir = backupFile.getParentFile();
			if (dir != null && !dir.exists())
			{
				dir.mkdirs();
			}
			try (FileOutputStream fos = new FileOutputStream(backupFile))
			{
				fos.write(origBytes);
			}
			Log.d(TAG, "Original backed up to: " + backupPath + " (" + origBytes.length + " bytes)");
			return BackupStatus.WRITTEN;
		}
		catch (Exception e)
		{
			Log.w(TAG, "Cannot save backup to " + backupPath + ": " + e.getMessage());
			return BackupStatus.FAILED;
		}
	}

	// Append Samsung SEFT trailer.
	// - If existing SEFT: re-append it verbatim (preserves Gallery's Revert data)
	// - If no existing SEFT but have backup info: generate new SEFT for Revert
	// - Otherwise: no SEFT appended
	private static byte[] appendSeft(byte[] jpeg, byte[] existingSeft, String backupPath,
		float normCenterX, float normCenterY, float normCropW, float normCropH,
		boolean isCropped, int exifRotation)
	{
		byte[] seft;
		if (existingSeft != null && existingSeft.length > 0)
		{
			// Re-append existing SEFT verbatim — preserves Gallery's re-edit data and backup path
			seft = existingSeft;
			Log.d(TAG, "Preserving existing SEFT trailer: " + seft.length + " bytes");
		}
		else if (backupPath != null)
		{
			// No existing SEFT — generate new one for Galaxy Revert
			seft = SeftBuilder.build(backupPath, normCenterX, normCenterY, normCropW, normCropH,
				isCropped, exifRotation, System.currentTimeMillis());
			if (seft == null)
			{
				return jpeg;
			}
			Log.d(TAG, "Generated new SEFT trailer: " + seft.length + " bytes");
		}
		else
		{
			return jpeg;
		}

		byte[] result = new byte[jpeg.length + seft.length];
		System.arraycopy(jpeg, 0, result, 0, jpeg.length);
		System.arraycopy(seft, 0, result, jpeg.length, seft.length);
		return result;
	}

	// Compute SEFT params from state and delegate to appendSeft.
	private static byte[] appendSeftForState(byte[] jpeg, CropState state, int cropW, int cropH)
	{
		int imgW = state.getImageWidth();
		int imgH = state.getImageHeight();
		boolean isCropped = state.hasCenter() && (cropW != imgW || cropH != imgH);

		// Normalized crop params (0..1 relative to image dimensions) — Samsung SEFT format expects these.
		float normCenterX;
		float normCenterY;
		float normCropW;
		float normCropH;
		if (state.hasCenter())
		{
			normCenterX = state.getCenterX() / imgW;
			normCenterY = state.getCenterY() / imgH;
			normCropW = (float) cropW / imgW;
			normCropH = (float) cropH / imgH;
		}
		else
		{
			normCenterX = 0.5f;
			normCenterY = 0.5f;
			normCropW = 1f;
			normCropH = 1f;
		}

		// Generate backup path if we have the original file path
		String backupPath = null;
		if (state.getOriginalFilePath() != null && state.getMediaStoreId() >= 0)
		{
			backupPath = SeftBuilder.generateBackupPath(
				state.getOriginalFilePath(), state.getMediaStoreId());
		}

		int exifOrient = 1;
		byte[] origBytes = state.getOriginalFileBytes();
		if (origBytes != null)
		{
			exifOrient = BitmapUtils.readExifOrientation(origBytes);
		}

		return appendSeft(jpeg, state.getSeftTrailer(), backupPath,
			normCenterX, normCenterY, normCropW, normCropH, isCropped, exifOrient);
	}

	// Draw grid lines by directly setting pixels on the bitmap. Bypasses Canvas rasterization
	// entirely — guaranteed to produce exact line widths regardless of bitmap color space or
	// Canvas rendering quirks.
	private static void drawGridPixels(Bitmap bmp, int width, int height, GridConfig grid)
	{
		int lineWidth = Math.max(1, Math.round(grid.lineWidth));
		int halfLineWidth = lineWidth / 2;
		int color = grid.color;

		// Vertical lines: write a (lineWidth × height) column of pixels for each
		int[] vertColumn = new int[lineWidth * height];
		Arrays.fill(vertColumn, color);
		for (int i = 1; i < grid.columns; i++)
		{
			int x = Math.round((float) (width * i) / grid.columns);
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

		// Horizontal lines: write a (width × lineWidth) row band for each
		int[] horizBand = new int[width * lineWidth];
		Arrays.fill(horizBand, color);
		for (int i = 1; i < grid.rows; i++)
		{
			int y = Math.round((float) (height * i) / grid.rows);
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

	private static ExportResult exportJpeg(CropState state, Bitmap bmp, int cropW, int cropH,
		File cacheDir) throws IOException
	{
		int quality = 100;

		// Generate thumbnail sized to fit the available EXIF space. Use the full remaining APP1
		// budget (EXIF segments cap at 65535 bytes, minus IFD overhead) so the thumbnail matches
		// camera-native resolution instead of being artificially shrunk to 20KB. 1024px matches
		// the size Samsung/Galaxy cameras typically embed.
		List<JpegSegment> metaForThumb = state.getJpegMeta();
		int thumbBudget = (metaForThumb != null && !metaForThumb.isEmpty())
			? ExifPatcher.maxThumbnailBytes(metaForThumb) - THUMBNAIL_MARGIN_BYTES
			: THUMBNAIL_DEFAULT_BUDGET;
		thumbBudget = Math.clamp(thumbBudget, MIN_THUMBNAIL_BUDGET, MAX_THUMBNAIL_BUDGET);
		byte[] thumbnail = generateThumbnail(bmp, THUMBNAIL_MAX_DIM, thumbBudget);

		// HDR path: generate a cropped Ultra HDR JPEG to extract the gain map from. The primary
		// image always comes from the canvas rendering above (matches preview exactly, including
		// rotation around image center). The gain map is extracted from the HDR path and
		// composed with the canvas primary.
		byte[] originalBytes = state.getOriginalFileBytes();
		// UltraHdrCompat requires the Android 14+ Gainmap API; minSdk 35 guarantees availability,
		// so no runtime check is needed.
		boolean hasHdr = state.getGainMap() != null && originalBytes != null;

		byte[] croppedGainMap = null;
		if (hasHdr)
		{
			float centerX = state.hasCenter() ? state.getCenterX() : state.getImageWidth() / 2f;
			float centerY = state.hasCenter() ? state.getCenterY() : state.getImageHeight() / 2f;
			int exifOrient = BitmapUtils.readExifOrientation(originalBytes);
			byte[] hdrResult = UltraHdrCompat.compressWithGainmap(
				originalBytes, quality, cacheDir,
				state.getImageWidth(), state.getImageHeight(),
				centerX, centerY, cropW, cropH,
				state.getRotationDegrees(), exifOrient);

			if (hdrResult != null)
			{
				int pe = findPrimaryEnd(hdrResult);
				if (pe > 0 && pe < hdrResult.length)
				{
					croppedGainMap = new byte[hdrResult.length - pe];
					System.arraycopy(hdrResult, pe, croppedGainMap, 0, croppedGainMap.length);
					Log.d(TAG, "Extracted gain map: " + croppedGainMap.length + " bytes");
				}
			}
			else
			{
				Log.d(TAG, "HDR generation failed, falling back to non-HDR");
			}
		}

		// Non-HDR pipeline (also used for grid+HDR with extracted gain map)
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
		byte[] jpegBytes = bos.toByteArray();
		bmp.recycle();

		List<JpegSegment> meta = state.getJpegMeta();
		if (meta != null && !meta.isEmpty())
		{
			List<JpegSegment> patched = ExifPatcher.patch(meta, cropW, cropH, thumbnail);
			jpegBytes = JpegMetadataInjector.inject(jpegBytes, patched);
		}

		// Append gain map ONLY if the HDR pipeline produced a spatially-aligned cropped map.
		// The original state.getGainMap() is aligned to the UNCROPPED, UNROTATED source — pasting
		// it onto a cropped or rotated primary produces visibly wrong HDR (gain blobs appear off
		// features they were meant to highlight). Better to drop HDR than to ship a broken file;
		// doExport's toast already reports "[HDR dropped]" in this case.
		if (croppedGainMap != null && croppedGainMap.length > 0)
		{
			Log.d(TAG, "Appending cropped gain map: " + croppedGainMap.length + " bytes");
			jpegBytes = GainMapComposer.compose(jpegBytes, croppedGainMap);
		}
		else if (state.getGainMap() != null && state.getGainMap().length > 0)
		{
			Log.d(TAG, "compressWithGainmap failed — dropping HDR to avoid misalignment");
		}

		jpegBytes = appendSeftForState(jpegBytes, state, cropW, cropH);

		return new ExportResult(jpegBytes, "jpg");
	}

	private static ExportResult exportPng(CropState state, Bitmap bmp, int cropW, int cropH)
	{
		// bmp is guaranteed sRGB for PNG exports (see export()); grid was rasterized on it with
		// exact pixel-width rectangles. Straight compress → PNG bytes.
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
		bmp.recycle();
		byte[] pngBytes = bos.toByteArray();

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

	// Find the end of the primary JPEG (position after first EOI). Used to determine where the
	// gain map starts.
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

	// Produce an EXIF thumbnail JPEG that fits within maxBytes. Scales bmp down to maxDim on
	// its longest side (never up), then tries decreasing quality levels until the compressed
	// size fits. Falls back to halving the dimensions if even q50 is too large.
	private static byte[] generateThumbnail(Bitmap bmp, int maxDim, int maxBytes)
	{
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
			Bitmap thumb = (thumbWidth == width && thumbHeight == height)
				? bmp
				: Bitmap.createScaledBitmap(bmp, thumbWidth, thumbHeight, true);

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
					if (thumb != bmp)
					{
						thumb.recycle();
					}
					return result;
				}
			}

			// Still too large — halve dimensions and retry at mid quality.
			// IMPORTANT: recompute from (width * scale * 0.5) rather than (thumbWidth / 2). Integer
			// division on the already-rounded thumbWidth truncates: for a 4:5 source like 4000×5000
			// at scale 0.2048 this produced 819/2 = 409 instead of the correct round(409.6) = 410.
			// Going through the original scale preserves full precision end-to-end.
			if (thumb != bmp)
			{
				thumb.recycle();
			}
			int halvedWidth = Math.max(1, (int) Math.round(width * scale * 0.5));
			int halvedHeight = Math.max(1, (int) Math.round(height * scale * 0.5));
			Bitmap smaller = Bitmap.createScaledBitmap(bmp, halvedWidth, halvedHeight, true);
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			smaller.compress(Bitmap.CompressFormat.JPEG, 70, bos);
			smaller.recycle();
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
	}

	// Inject EXIF data into a PNG as an eXIf chunk, inserted after IHDR. The eXIf chunk
	// contains raw TIFF data (from EXIF APP1, minus the FF E1 length "Exif\0\0" wrapper).
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
