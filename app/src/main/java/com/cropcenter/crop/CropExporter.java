package com.cropcenter.crop;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Process;
import android.util.Log;

import androidx.annotation.WorkerThread;

import com.cropcenter.metadata.ExifPatcher;
import com.cropcenter.metadata.GainMapComposer;
import com.cropcenter.metadata.HdrSignature;
import com.cropcenter.metadata.JpegMarker;
import com.cropcenter.metadata.JpegMarkerWalker;
import com.cropcenter.metadata.JpegMetadataInjector;
import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.metadata.PngMetadataExtractor;
import com.cropcenter.metadata.TiffIfd0;
import com.cropcenter.model.CropRender;
import com.cropcenter.model.CropState;
import com.cropcenter.model.Format;
import com.cropcenter.model.GridConfig;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.ByteBufferUtils;
import com.cropcenter.util.GridGeometry;
import com.cropcenter.util.ThemeColors;
import com.cropcenter.util.UltraHdrCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Full export pipeline: render → compress → inject original metadata (EXIF patched, ICC/XMP/MPF preserved) → append
 * gain map and fix MPF offsets.
 *
 * Cohesion note: this class also hosts package-private byte-arithmetic helpers (forceTiffOrientationToUpright,
 * stripHdrSegments, patchPngExifTiff, injectPngExifFromTiffFileToFile, appendSeftFileToFile). They stay here rather
 * than in metadata/ because each is exclusively called from one branch of exportJpeg / exportPng and the byte logic is
 * tightly coupled to the surrounding pipeline state (cropW/cropH, encoded-file lifecycle, the temp-file chain) —
 * extracting would force every helper to take a dozen pipeline parameters while still being conceptually pipeline-stage
 * logic. Split point: if a caller outside CropExporter ever needs one of these, that's the signal to extract; until
 * then, locality wins.
 */
public final class CropExporter
{
	private static final String TAG = "CropExporter";

	private CropExporter() {}

	/**
	 * Export the cropped + rotated image as JPEG, PNG, or Ultra HDR JPEG bytes per
	 * `state.getExportConfig().format()`. Single entry point for the whole export pipeline: canvas-renders the
	 * primary at exact (cropW, cropH), generates a fresh EXIF thumbnail of the cropped pixels, encodes, splices the
	 * source's EXIF (orientation normalised to 1, dimensions rewritten, IFD1 thumbnail replaced) + ICC + XMP + MPF
	 * + Samsung SEFT back in, and — for HDR sources — re-renders the gain map at the primary's transform and
	 * composes it into the output.
	 *
	 * The returned ExportResult carries a structural hdrAttached flag (true only when the gain map was successfully
	 * re-composed) so ExportPipeline.reportSuccess can say "[HDR OK]" / "[HDR dropped]" without a substring scan
	 * that false-positives on stale metadata.
	 *
	 * @param state    CropState with source image, crop dims, rotation, AR, grid config, jpegMeta, gainMap,
	 *                 seftTrailer. getSourceImage() is read-only — only the internally-rendered cropped
	 *                 Bitmap is recycled here, so the editor's source stays loaded for re-saves.
	 * @param cacheDir Activity cache dir for UltraHdrCompat's intermediate file work; may be null when the
	 *                 platform decode path needs no temp file
	 * @return ExportResult carrying the encoded bytes and the structural hdrAttached flag
	 * @throws IOException when no image is loaded, encoding fails, or metadata splicing rejects the input
	 */
	@WorkerThread
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
			// Use the continuous-float origin so the exported primary samples the source at exactly the
			// position the editor is showing. BitmapUtils.drawCropped handles fractional srcX / srcY (falls
			// back to bilinear when non-integer, integer blit otherwise). UltraHdrCompat uses the same
			// origin for its primary + gain-map render, so the two stay pixel-aligned with each other.
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

		// Create output bitmap. Color-space picking has three branches:
		//   1. JPEG + gain map (Ultra HDR): force Display P3 because Samsung's gain map was calibrated
		//      against a P3-gamut base. Composing it onto an sRGB primary produces a subtly wrong HDR boost,
		//      so this path overrides whatever the source bitmap reports.
		//   2. JPEG without gain map: match the source bitmap's color space so the metadata-inject pass
		//      (which restores the source's APP2 ICC profile verbatim) describes the actual pixel encoding.
		//      Without this, a Display P3 source (modern iPhone JPEGs, Photoshop P3 exports) would render
		//      into the default sRGB canvas while the saved ICC tag still claimed P3 — ICC-aware viewers
		//      would then show wrong colors. Falls back to default (sRGB) when the source's color space
		//      lookup returns null (rare, but defensive).
		//   3. PNG: stays on the default (sRGB) so source alpha round-trips and rotation corners stay
		//      transparent. Color-managed canvases can apply subtle filtering during rasterization,
		//      causing grid lines to render at inconsistent widths or drop out.
		boolean isJpeg = state.getExportConfig().format() == Format.JPEG;
		// Must match the downstream HDR-gate at buildCroppedGainMap (gainMap AND originalBytes both present) —
		// otherwise we'd render into the P3 canvas while the save path silently degraded to SDR, leaving the
		// JFIF without the P3 ICC tag the canvas color space implied. state.getGainMap() set without
		// state.getOriginalFileBytes() is currently unreachable (applyBytes commits both in one bg pass) but a
		// future graft-only path could expose it; pin the gate here so a partial state can't drift the color
		// profile out of step with the encoded pixels.
		boolean hasGainMap = state.getGainMap() != null && state.getGainMap().length > 0
			&& state.getOriginalFileBytes() != null;
		Bitmap outputBitmap;
		if (isJpeg && hasGainMap)
		{
			outputBitmap = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888, true,
				ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
		}
		else if (isJpeg)
		{
			ColorSpace srcCs = src.getColorSpace();
			outputBitmap = (srcCs != null)
				? Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888, true, srcCs)
				: Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888);
		}
		else
		{
			outputBitmap = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888);
		}

		// outputBitmap ownership transfers to exportJpeg / exportPng on the success path — both recycle in
		// their own finally. But if drawCropped or drawGridPixels throws (OOM on huge inputs is the realistic
		// case), or if the switch hits the encode-failure branch before ownership transfers, outputBitmap would
		// leak its native pixel buffer to the GC finalizer. The handedOff flag flips true the moment the switch
		// is about to delegate, so the catch / non-success paths recycle locally.
		boolean handedOff = false;
		try
		{
			Canvas canvas = new Canvas(outputBitmap);
			Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
			// JPEG can't represent alpha — fill with the editor preview background (ThemeColors.BACKGROUND,
			// the same color EditorRenderer draws behind the image) so rotation corners and any transparent
			// source pixels export exactly as previewed. PNG keeps the bitmap's default transparent state
			// so alpha sources round-trip and rotation corners stay see-through.
			// UltraHdrCompat.renderPrimary mirrors this fill + paint setup — the invariant is documented
			// there.
			if (isJpeg)
			{
				canvas.drawColor(ThemeColors.BACKGROUND);
			}

			BitmapUtils.drawCropped(canvas, src, srcX, srcY, state.getRotationDegrees(), paint);

			// Optional grid overlay bake-in (independent of whether grid is visible on screen)
			GridConfig grid = state.getGridConfig();
			if (grid.includeInExport())
			{
				drawGridPixels(outputBitmap, cropW, cropH, grid);
			}

			handedOff = true;
			return switch (state.getExportConfig().format())
			{
				case JPEG -> exportJpeg(state, outputBitmap, cropW, cropH, cacheDir);
				case PNG -> exportPng(state, outputBitmap, cropW, cropH, cacheDir);
			};
		}
		finally
		{
			if (!handedOff)
			{
				outputBitmap.recycle();
			}
		}
	}

	/**
	 * Re-append an existing SEFT trailer verbatim to a JPEG already on disk, or stream-copy the JPEG unchanged when
	 * no trailer was captured at load. CropCenter does not generate fresh SEFTs — Samsung Gallery's Revert
	 * validates a backup path the SEFT claims, and only honors paths under Samsung-blessed locations like
	 * `/data/sec/photoeditor/` that third-party apps cannot write to. A SEFT we generate pointing at our own
	 * `/storage/emulated/0/.cropcenter/` write is silently rejected by Gallery, so fabricating one is a net
	 * negative (disk bloat with no Revert benefit). Files that came in with a SEFT — Gallery-edited originals —
	 * keep their working Revert chain because we re-append exactly the bytes we extracted at load.
	 *
	 * @param inFile        primary JPEG on disk (typically the compose tempfile)
	 * @param existingSeft  Samsung SEFT trailer to append; null / empty falls through to stream-copy
	 * @param outFile       destination tempfile; truncated and overwritten on every call
	 * @throws IOException on read / write failure
	 */
	static void appendSeftFileToFile(File inFile, byte[] existingSeft, File outFile) throws IOException
	{
		long inSize = inFile.length();
		if (existingSeft == null || existingSeft.length == 0)
		{
			Files.copy(inFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return;
		}
		// No Integer.MAX_VALUE cap on this path — output size is bounded by disk space, not by an in-memory
		// byte[] index. A >2 GB combined output (rare but possible on extreme HDR + large SEFT) preserves the
		// SEFT instead of losing it.
		Log.d(TAG, "Streaming SEFT append: " + existingSeft.length + " bytes after " + inSize);
		try (FileInputStream fis = new FileInputStream(inFile);
			FileOutputStream fos = new FileOutputStream(outFile))
		{
			JpegMetadataInjector.copyRemaining(fis, fos);
			fos.write(existingSeft);
			fos.getFD().sync();
		}
	}

	/**
	 * Force the EXIF orientation tag in a raw TIFF's IFD0 to upright (1). Used by the PNG export's > 64 KB
	 * verbatim-preserve fallback so a TIFF that ExifPatcher.patch rejected (shipped as-is to avoid u16-truncation)
	 * doesn't carry the source's pre-load orientation onto the saved PNG — pixels are already upright
	 * (applyOrientation ran at load), so emitting 6/8 would make EXIF-aware viewers double-rotate.
	 *
	 * The IFD0 walk (byte order from "II"/"MM", magic at +2, IFD0 offset at +4, 12-byte entries, SHORT/count-1
	 * shape check) is the shared TiffIfd0.findOrientationEntry — the same walker BitmapUtils and
	 * PngMetadataExtractor read orientation through. This writer passes minIfdRel=8 (an IFD0 overlapping the
	 * 8-byte TIFF header is rejected) and rewrites any non-1 value rather than range-validating it. On any walk
	 * failure returns the input unchanged — safe, because the saved pixels are upright regardless.
	 *
	 * @param tiff raw TIFF bytes (PNG eXIf chunk contents); never null
	 * @return tiff itself when orientation is already 1 or the walk failed; a fresh byte[] with orientation
	 *         overwritten to 1 otherwise
	 */
	static byte[] forceTiffOrientationToUpright(byte[] tiff)
	{
		Optional<TiffIfd0.OrientationEntry> maybeEntry = TiffIfd0.findOrientationEntry(tiff, 0, tiff.length, 8);
		if (maybeEntry.isEmpty())
		{
			return tiff;
		}
		TiffIfd0.OrientationEntry entry = maybeEntry.orElseThrow();
		int valueOff = entry.entryOffset() + 8;
		int currentValue = ByteBufferUtils.readU16(tiff, valueOff, entry.isLittleEndian());
		if (currentValue == 1)
		{
			return tiff;
		}
		byte[] patched = tiff.clone();
		ByteBufferUtils.writeU16(patched, valueOff, 1, entry.isLittleEndian());
		Log.d(TAG, "forceTiffOrientationToUpright: rewrote IFD0 orientation "
			+ currentValue + " → 1 at offset " + valueOff);
		return patched;
	}

	/**
	 * Pixel index for grid line i of a count-N grid along one axis of the exported crop. Matches the
	 * continuous-float positions GridRenderer.linePos emits for the preview, rounded to the nearest output pixel.
	 * Second-half lines mirror the first half around dim / 2 so (i, count − i) pairs stay symmetric — Java's
	 * Math.round rounds half-up, which would break symmetry at half-integer positions (e.g. count=4, dim=10
	 * produces raw values 2.5 and 7.5; rounding both half-up gives 3 and 8 instead of the symmetric 3 and 7).
	 *
	 * Known half-pixel divergence from the preview: for odd `dim` with `i * 2 == count` (the middle line), the
	 * preview draws at the fractional coord `dim / 2f` and anti-aliases across the two adjacent pixels. This
	 * exporter must pick one integer pixel index, so the middle line in the baked export sits on `ceil(dim / 2f)`
	 * while the preview's visual centre of mass is 0.5 px to its left. Acceptable because the preview is
	 * anti-aliased and the eye reads its centre, not its origin.
	 *
	 * Package-private so CropExporterGridLineTest can pin the mirror-symmetry invariant — a regression that
	 * collapses the mirror trick to a plain Math.round((double) dim * i / count) would silently produce asymmetric
	 * exported grids on count=4 / dim=10-class fixtures.
	 *
	 * @param i     grid line index in [1, count − 1] — the integer line count from the left/top edge
	 * @param count total number of grid intervals (so the grid draws count − 1 internal lines)
	 * @param dim   total dimension in output pixels along this axis (cropW or cropH)
	 * @return pixel index where line i sits in the baked export; for i * 2 != count, the (i, count − i)
	 *         pair satisfies result(i) + result(count − i) == dim. The middle line of an even-count grid
	 *         (i * 2 == count) is its own mirror: on odd dim its doubled value is dim + 1 (Math.round
	 *         rounds the half-integer centre up), not dim
	 */
	static int gridLinePixel(int i, int count, int dim)
	{
		// Delegate to the shared util/GridGeometry chokepoint so the export-baked grid and the on-screen
		// GridRenderer.linePos preview can't drift on rounding edge-cases.
		return GridGeometry.mirroredLinePos(i, count, dim);
	}

	/**
	 * Write a PNG eXIf chunk carrying tiffData into the file at `inFile`, producing the eXIf-injected PNG at
	 * `outFile`. Takes raw TIFF bytes directly with no JPEG APP1 u16 cap — used by the PNG → PNG round-trip path so
	 * a PNG with > 64 KB EXIF (camera with extensive MakerNote / GPS metadata) keeps its full metadata when
	 * re-saved. The PNG eXIf length field is u31 so the chunk holds anything up to ~2 GB. Streams the file-to-file
	 * copy via FileOutputStream so the 400-600 MB PNG primary never materialises as a single byte[]. Peak Java heap
	 * during the operation is the chunk buffer (~64 KB) + the tiffData reference.
	 *
	 * On any malformation (PNG too short, IHDR length signals truncation, total size overflows int, or the eXIf
	 * chunk's CRC computation throws), the input file is stream-copied verbatim to outFile.
	 *
	 * @param inFile   source PNG on disk (typically the tempfile from `encodeToTempfile`)
	 * @param tiffData raw TIFF body (no APP1 wrapper, no "Exif\0\0" header); zero-length input
	 *                 stream-copies inFile verbatim to outFile
	 * @param outFile  destination tempfile; truncated and overwritten on every call
	 * @throws IOException on read / write failure
	 */
	static void injectPngExifFromTiffFileToFile(File inFile, byte[] tiffData, File outFile)
		throws IOException
	{
		int tiffLen = tiffData.length;
		long inSize = inFile.length();
		// Bail to verbatim copy when there's nothing to inject OR the input can't hold even a PNG signature +
		// IEND chunk (8 + 12 = 20 bytes). This is a cheap floor, not a full minimal-PNG check — inputs of 20-32
		// bytes fail the 33-byte signature + IHDR peek below and bail to the same verbatim copy.
		if (tiffLen == 0 || inSize < 8L + 12L)
		{
			Files.copy(inFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return;
		}
		// Peek the first 33 bytes: PNG signature(8) + IHDR length(4) + "IHDR"(4) + IHDR data(13) + IHDR CRC(4).
		// That's everything we need before deciding where to splice the eXIf chunk. Anything shorter is a
		// malformed PNG; bail to verbatim copy.
		byte[] header = new byte[33];
		try (FileInputStream fis = new FileInputStream(inFile))
		{
			if (fis.readNBytes(header, 0, header.length) < header.length)
			{
				Files.copy(inFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
				return;
			}
		}
		// Long-arithmetic guard: u32 IHDR length read as long so a high-bit-set value can't sign-flip into a
		// negative int that slips past the past-EOF check.
		long ihdrLen = ((long) (header[8] & 0xFF) << 24) | ((long) (header[9] & 0xFF) << 16)
				| ((long) (header[10] & 0xFF) << 8) | (header[11] & 0xFF);
		long insertPosLong = 8L + 4L + 4L + ihdrLen + 4L;
		// `>=` rejects an IHDR-only PNG (insertPos == inSize means no IEND follows). Negative check guards the
		// long wrap on adversarial ihdrLen near MAX_INT.
		if (insertPosLong >= inSize || insertPosLong < 0 || insertPosLong > header.length)
		{
			// Malformed — IHDR length doesn't fit within our 33-byte peek, OR there's no IEND after IHDR.
			// Bail to verbatim copy.
			Files.copy(inFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return;
		}
		int insertPos = (int) insertPosLong;
		// Long total to catch the u31 overflow case (2 GB-class TIFF + multi-MP PNG).
		long chunkTotalLong = 4L + 4L + (long) tiffLen + 4L;
		if (chunkTotalLong + inSize > Integer.MAX_VALUE)
		{
			Log.w(TAG, "Streaming PNG eXIf chunk total " + chunkTotalLong + " + png " + inSize
				+ " would overflow int; copying input unchanged");
			Files.copy(inFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return;
		}
		byte[] chunkType = PngMetadataExtractor.EXIF_CHUNK_TYPE;
		byte[] chunkLenBytes = {
			(byte) (tiffLen >> 24), (byte) (tiffLen >> 16), (byte) (tiffLen >> 8), (byte) (tiffLen)
		};
		CRC32 crc = new CRC32();
		crc.update(chunkType);
		crc.update(tiffData);
		long crcVal = crc.getValue();
		byte[] crcBytes = {
			(byte) (crcVal >> 24), (byte) (crcVal >> 16), (byte) (crcVal >> 8), (byte) (crcVal)
		};
		try (FileOutputStream fos = new FileOutputStream(outFile))
		{
			// 1. Write the PNG signature + IHDR chunk (first insertPos bytes from the header peek).
			fos.write(header, 0, insertPos);
			// 2. Write the new eXIf chunk: length(4) + "eXIf"(4) + tiffData + CRC(4).
			fos.write(chunkLenBytes);
			fos.write(chunkType);
			fos.write(tiffData);
			fos.write(crcBytes);
			// 3. Stream-copy the rest of inFile (from insertPos onward) — everything past IHDR's CRC,
			// which is every chunk after IHDR including IDAT and IEND. Skip exactly to insertPos so a
			// partial skip can't strand the read mid-file (same hazard the JPEG path's skipExactly
			// helper handles).
			try (FileInputStream tailFis = new FileInputStream(inFile))
			{
				JpegMetadataInjector.skipExactly(tailFis, insertPos);
				JpegMetadataInjector.copyRemaining(tailFis, fos);
			}
			fos.getFD().sync();
		}
		Log.d(TAG, "Streaming PNG inject: eXIf chunk " + tiffLen + " bytes TIFF data");
	}

	/**
	 * Run a raw TIFF through ExifPatcher.patch to normalise orientation (forced to 1 — pixels were rotated at load
	 * time), rewrite cropped dimensions, and replace or strip the embedded IFD1 thumbnail. Wraps the TIFF as a
	 * synthetic APP1 segment for ExifPatcher's segment API; the wrapper's u16 length may truncate for > 64KB TIFFs
	 * but ExifPatcher reads only data().length, so that's harmless. Returns the patched TIFF, or empty when
	 * ExifPatcher rejects the input.
	 *
	 * Stale-thumbnail footgun: spliceExistingThumbnail enforces the APP1 u16 cap on the rebuilt segment, so a
	 * too-large rebuild rejects silently and leaves the source's IFD1 thumbnail in place — leaking pre-edit content
	 * via the preview. Predict that here with ExifPatcher.maxThumbnailBytes (which subtracts the OLD thumbnail's
	 * bytes before measuring remaining room) and force STRIP_IFD1_THUMBNAIL when the new thumbnail won't fit, so
	 * the saved PNG carries no IFD1 rather than a pre-edit preview.
	 *
	 * Package-private so CropExporterPngExifTest can pin the strip-vs-splice decision directly.
	 *
	 * @param tiff      raw TIFF bytes from the source PNG's eXIf chunk
	 * @param newW      cropped image width
	 * @param newH      cropped image height
	 * @param thumbnail fresh JPEG thumbnail of the cropped pixels; MUST be non-null (null would preserve
	 *                  the source's pre-crop thumbnail, leaking pre-edit content). Force-overridden to
	 *                  STRIP_IFD1_THUMBNAIL when the rebuilt segment can't fit under APP1's cap
	 * @return patched TIFF bytes ready for the eXIf chunk, or empty on parse failure / null or
	 *         oversized input
	 */
	static Optional<byte[]> patchPngExifTiff(byte[] tiff, int newW, int newH, byte[] thumbnail)
	{
		// Long-arithmetic overflow guard. PNG eXIf is u31-uncapped so an adversarial 2 GB-class TIFF would make
		// `2 + 6 + tiff.length` overflow int and `new byte[2 + segLen]` throw NegativeArraySizeException out of
		// the bg encode pipeline. Bail empty so the caller routes through the >64 KB verbatim-preserve path at
		// exportPng (which already handles the "too big to patch but must not be dropped" case for u31 TIFFs).
		if (tiff == null || 2L + 6L + tiff.length > Integer.MAX_VALUE)
		{
			return Optional.empty();
		}
		// Build a synthetic APP1 segment: FF E1 LL LL "Exif\0\0" [TIFF...]. Bytes 2..3 (segLen u16) get
		// truncated when 2 + 6 + tiff.length > 65535, but the only consumer here is ExifPatcher.patch /
		// maxThumbnailBytes which both read data().length directly.
		int segLen = 2 + 6 + tiff.length;
		byte[] wrapped = new byte[2 + segLen];
		wrapped[0] = (byte) JpegMarker.PREFIX;
		wrapped[1] = (byte) JpegMarker.APP1;
		wrapped[2] = (byte) ((segLen >> 8) & 0xFF);
		wrapped[3] = (byte) (segLen & 0xFF);
		wrapped[4] = 'E';
		wrapped[5] = 'x';
		wrapped[6] = 'i';
		wrapped[7] = 'f';
		wrapped[8] = 0;
		wrapped[9] = 0;
		System.arraycopy(tiff, 0, wrapped, 10, tiff.length);

		List<JpegSegment> wrappedList = new ArrayList<>(1);
		wrappedList.add(new JpegSegment(JpegMarker.APP1, wrapped));

		// Predict whether spliceExistingThumbnail's APP1-cap check will reject the rebuild. Using
		// ExifPatcher.maxThumbnailBytes (rather than a naive tiff.length + thumbnail.length sum) walks IFD0 →
		// IFD1 → JPEGInterchangeFormatLength to find the OLD thumbnail size and returns
		// `JpegSegment.MAX_SEGMENT_BYTES - (data.length - oldThumbLen)`, the exact post-splice budget. A naive
		// sum would force-strip a 50KB-old + 30KB-fresh case even though the rebuilt segment (~30KB after
		// old-thumb removal) would fit comfortably.
		if (thumbnail.length > 0 && thumbnail.length > ExifPatcher.maxThumbnailBytes(wrappedList))
		{
			Log.d(TAG, "PNG eXIf rebuild after splice would exceed APP1 cap (TIFF " + tiff.length
				+ " B, fresh thumb " + thumbnail.length + " B); forcing STRIP to prevent leak");
			thumbnail = ExifPatcher.STRIP_IFD1_THUMBNAIL;
		}

		for (JpegSegment seg : ExifPatcher.patch(wrappedList, newW, newH, thumbnail))
		{
			if (seg.isExif())
			{
				byte[] patchedData = seg.data();
				if (patchedData.length <= 10)
				{
					return Optional.empty();
				}
				byte[] patchedTiff = new byte[patchedData.length - 10];
				System.arraycopy(patchedData, 10, patchedTiff, 0, patchedTiff.length);
				return Optional.of(patchedTiff);
			}
		}
		return Optional.empty();
	}

	/**
	 * Drop HDR-specific segments — XMP segments containing the `hdrgm` namespace marker (standard OR Extended XMP),
	 * and APP2/MPF segments pointing at the gain map. Used on the HDR-drop path so the saved JPEG's metadata
	 * doesn't claim HDR that the output file doesn't actually carry.
	 *
	 * Drops the WHOLE XMP segment when it contains hdrgm rather than surgically rewriting the XML — most camera
	 * vendors split XMP into multiple APP1 segments anyway, so any non-hdrgm XMP typically lives in a separate
	 * segment. The corner case (a single XMP segment carrying hdrgm + non-hdrgm metadata) loses the non-hdrgm tags
	 * too, but that beats lying to ExportPipeline.reportSuccess about HDR presence.
	 *
	 * Delegates to HdrSignature.isHdrgmXmpSegment so the standard-plus-extended-XMP detection stays in lockstep
	 * with the load-time hasHdrgmInXmp gate — without that, an HDR-drop output of a source whose hdrgm declaration
	 * was in Extended XMP would leak the HDR signature past the gain-map removal.
	 *
	 * Package-private so CropExporterStripHdrTest can pin the strip behavior — particularly the Extended-XMP
	 * `hdrgm` strip, where a regression that drops the Extended-XMP branch of HdrSignature.isHdrgmXmpSegment would
	 * silently let HDR-claiming XMP survive on the HDR-drop output.
	 *
	 * @param meta source JPEG segment list
	 * @return new list with HDR-specific segments removed; non-HDR segments preserved verbatim
	 */
	static List<JpegSegment> stripHdrSegments(List<JpegSegment> meta)
	{
		List<JpegSegment> filtered = new ArrayList<>(meta.size());
		for (JpegSegment seg : meta)
		{
			if (seg.isMpf())
			{
				continue;
			}
			if (HdrSignature.isHdrgmXmpSegment(seg))
			{
				continue;
			}
			filtered.add(seg);
		}
		return filtered;
	}

	/**
	 * For HDR sources, render a cropped Ultra HDR JPEG via UltraHdrCompat and extract the gain-map bytes from its
	 * tail. The primary-image bytes still come from the canvas rendering above; this only harvests the gain map,
	 * which must be spatially aligned to the same crop / rotation as the primary.
	 *
	 * @param state    source of the original bytes, gain map, crop geometry, and AI mask
	 * @param cropW    output crop width in pixels
	 * @param cropH    output crop height in pixels
	 * @param cacheDir scratch directory handed to UltraHdrCompat for its tempfiles
	 * @param quality  JPEG quality (0..100) for the Ultra HDR encode
	 * @return gain-map bytes (everything past the rendered Ultra HDR JPEG's primary EOI); empty when the source
	 *         isn't HDR or UltraHdrCompat couldn't produce a valid output — the caller drops to the SDR path
	 */
	private static Optional<byte[]> buildCroppedGainMap(CropState state, int cropW, int cropH, File cacheDir,
		int quality)
	{
		byte[] originalBytes = state.getOriginalFileBytes();
		// Match the gain-map presence check at the top of export() — a zero-length gain-map array means
		// extraction succeeded structurally but produced no usable bytes (rare but observed after malformed
		// extraction). UltraHdrCompat would silently fall back to SDR; flag here so the caller's presence check
		// drops the HDR path explicitly rather than letting a degraded HDR encode go through.
		boolean hasHdr = state.getGainMap() != null && state.getGainMap().length > 0 && originalBytes != null;
		if (!hasHdr)
		{
			return Optional.empty();
		}

		float centerX = state.hasCenter() ? state.getCenterX() : state.getImageWidth() / 2f;
		float centerY = state.hasCenter() ? state.getCenterY() : state.getImageHeight() / 2f;
		int exifOrient = BitmapUtils.readExifOrientation(originalBytes);
		CropRender render = CropRender.of(centerX, centerY, cropW, cropH,
			state.getImageWidth(), state.getImageHeight(), state.getRotationDegrees());
		byte[] hdrResult = UltraHdrCompat.compressWithGainmap(
			originalBytes, quality, cacheDir, render, exifOrient, state.getAiMask()).orElse(null);
		if (hdrResult == null)
		{
			Log.d(TAG, "HDR generation failed, falling back to non-HDR");
			return Optional.empty();
		}

		int primaryEnd = JpegMarkerWalker.findPrimaryEoi(hdrResult, hdrResult.length);
		if (primaryEnd <= 0 || primaryEnd >= hdrResult.length)
		{
			return Optional.empty();
		}
		byte[] gainMap = new byte[hdrResult.length - primaryEnd];
		System.arraycopy(hdrResult, primaryEnd, gainMap, 0, gainMap.length);
		Log.d(TAG, "Extracted gain map: " + gainMap.length + " bytes");
		return Optional.of(gainMap);
	}

	/**
	 * Generate the embedded EXIF thumbnail sized to fit the available APP1 budget. Using the full remaining APP1
	 * budget (minus IFD overhead) gives a thumbnail that matches camera-native resolution instead of being
	 * artificially shrunk.
	 *
	 * @param meta source JPEG segment list; ExifPatcher.patchedNonThumbBytes predicts the post-patch
	 *             non-thumbnail byte count from it
	 * @param bmp  rendered output bitmap the thumbnail is scaled down from; not recycled here
	 * @return thumbnail JPEG bytes fitting the budget, or ExifPatcher.STRIP_IFD1_THUMBNAIL (a byte[0] sentinel —
	 *         never null) when the budget is too small, the retry-at-half-size still doesn't fit, or generation
	 *         throws OOM. The sentinel routes ExifPatcher through the strip-IFD1 path so the saved file carries
	 *         no embedded preview, rather than preserving the SOURCE's pre-edit thumbnail (null would leave the
	 *         source thumbnail in place, leaking pre-edit content via any EXIF-thumbnail-aware viewer)
	 */
	private static byte[] buildEmbeddedThumbnail(List<JpegSegment> meta, Bitmap bmp)
	{
		// Exact-budget formulation: ask ExifPatcher to predict the post-patch segment's non-thumbnail byte
		// count (mirrors patch's decision tree — splice / append / synthesise), then subtract from the APP1
		// segment cap to know precisely how many bytes the new thumbnail can occupy. No estimation margin, no
		// upper clamp; the prediction is byte-exact for the splice path that camera sources hit and
		// conservative-by-42-bytes for the append fallback. Floor at 0 because `data.length + 42` from the
		// append-path predictor can exceed the cap on pathological already-near-full source EXIF (corrupt or
		// near-MAX_INT lengths) — a negative budget would propagate to generateThumbnail's `maxBytes <= 0`
		// short-circuit and we'd fall through to STRIP_IFD1_THUMBNAIL, same outcome as if every cascade rung
		// failed.
		int outputNonThumb = ExifPatcher.patchedNonThumbBytes(meta);
		// Defensive double-clamp. patchedNonThumbBytes is contracted to return ≥ 0, but no test pins that
		// invariant directly; a future regression returning negative would make `MAX_SEGMENT_BYTES - negative`
		// wrap positive past int range — Math.max(0, hugeWrap) would then admit a thumbnail too big for any
		// segment cap. Pre-clamp outputNonThumb to [0, ∞) so the subtraction stays in honest territory
		// regardless of upstream contract drift.
		outputNonThumb = Math.max(0, outputNonThumb);
		int thumbBudget = Math.max(0, JpegSegment.MAX_SEGMENT_BYTES - outputNonThumb);
		return generateThumbnail(bmp, thumbBudget).orElseGet(() ->
		{
			Log.w(TAG, "Thumbnail generation came back empty at budget " + thumbBudget
				+ "; falling back to STRIP_IFD1_THUMBNAIL — saved file will have no preview");
			return ExifPatcher.STRIP_IFD1_THUMBNAIL;
		});
	}

	/**
	 * Best-effort tempfile delete with a logged warning on failure. Used by the streaming pipeline's finally block
	 * to clean up every intermediate file we created.
	 *
	 * @param file tempfile to delete; null and already-deleted files are silently tolerated so callers can pass
	 *             uninitialised slots without a null-check at every call site
	 */
	private static void deleteIfExists(File file)
	{
		if (file != null && file.exists() && !file.delete())
		{
			Log.w(TAG, "Failed to delete pipeline tempfile " + file);
		}
	}

	/**
	 * Draw grid lines by directly setting pixels on the bitmap. Bypasses Canvas rasterization entirely — guaranteed
	 * to produce exact line widths regardless of bitmap color space or Canvas rendering quirks. Line positions are
	 * computed as continuous float offsets from the crop's top-left and then rounded to the nearest output pixel,
	 * matching what GridRenderer.linePos produces on the preview canvas.
	 *
	 * @param bmp    output bitmap, mutated in place via setPixels
	 * @param width  bitmap width in pixels (crop width)
	 * @param height bitmap height in pixels (crop height)
	 * @param grid   line count, width, and color configuration
	 */
	private static void drawGridPixels(Bitmap bmp, int width, int height, GridConfig grid)
	{
		int lineWidth = Math.max(1, Math.round(grid.lineWidth()));
		int halfLineWidth = lineWidth / 2;
		int color = grid.color();

		// Allocate one full-size band per axis up front (sized for the WIDEST possible line); reuse the same
		// buffer for every line by passing the actual stride to setPixels. Per-line allocation for edge-clipped
		// lines would cost up to 14 fresh int[] totaling ~18 MB of transient allocation per save on a 16K-wide
		// crop with a 7×7 grid and lineWidth=20.
		int[] vertColumn = new int[lineWidth * height];
		Arrays.fill(vertColumn, color);
		for (int i = 1; i < grid.columns(); i++)
		{
			int x = gridLinePixel(i, grid.columns(), width);
			int left = Math.max(0, x - halfLineWidth);
			// Right bound is computed from the un-clipped line center (x + lineWidth - halfLineWidth) so a
			// line whose left edge clipped against image x=0 doesn't extend its width by halfLineWidth-x to
			// the right. A `left + lineWidth` bound would draw the full lineWidth even after the left
			// portion is lost, producing visibly thicker grid lines on the image's left edge at thick
			// widths; this keeps both edges on the same "render only the visible pixels" semantics (the
			// right edge via the Math.min clamp).
			int right = Math.min(width, x + lineWidth - halfLineWidth);
			int actualWidth = right - left;
			if (actualWidth <= 0)
			{
				continue;
			}
			// Buffer is uniformly colored, so reading any (stride × height) subset gives the same result.
			// Required: vertColumn.length >= actualWidth * height — satisfied because actualWidth is always
			// ≤ lineWidth (clipped at the image edge).
			bmp.setPixels(vertColumn, 0, actualWidth, left, 0, actualWidth, height);
		}

		int[] horizBand = new int[width * lineWidth];
		Arrays.fill(horizBand, color);
		for (int i = 1; i < grid.rows(); i++)
		{
			int y = gridLinePixel(i, grid.rows(), height);
			int top = Math.max(0, y - halfLineWidth);
			// Symmetric fix for the horizontal band — bottom is the un-clipped line's bottom edge clamped
			// to image height, not (top + lineWidth) which over-draws when the top clipped against y=0.
			int bottom = Math.min(height, y + lineWidth - halfLineWidth);
			int actualHeight = bottom - top;
			if (actualHeight <= 0)
			{
				continue;
			}
			bmp.setPixels(horizBand, 0, width, 0, top, width, actualHeight);
		}
	}

	/**
	 * Encode a bitmap via `Bitmap.compress` straight to a tempfile in cacheDir. Returns the tempfile path so the
	 * rest of the pipeline can stream from disk rather than holding the encoded byte[] in Java heap — a 200 MP
	 * source encodes to 100+ MB as JPEG and roughly 400-560 MB as lossless PNG, and an in-memory BAOS +
	 * toByteArray encode would peak at up to ~1 GB live (the BAOS internal buffer at the next power of 2 plus the
	 * toByteArray copy), an unrecoverable OOM even with android:largeHeap. A compress false return throws rather
	 * than shipping structurally-truncated bytes downstream. Caller deletes the returned file once downstream
	 * stages have consumed it; `deleteOnExit` is a JVM-shutdown safety net and
	 * `UltraHdrCompat.sweepStaleCacheFiles` (invoked from MainActivity.onCreate) reclaims orphans from hard
	 * process kills.
	 *
	 * @param bmp       bitmap to encode (not recycled here; caller manages)
	 * @param format    compress format; drives the encoder and the failure-message labels
	 * @param quality   quality 1..100 passed to compress; production JPEG saves pass 100, PNG ignores it
	 * @param nameInfix tempfile-name infix after the sweep prefix ("jpeg_encode_", "png_encode_")
	 * @param extension tempfile extension including the dot
	 * @param cacheDir  directory for the tempfile
	 * @return tempfile holding the encoded bytes, fsynced
	 * @throws IOException if compress returns false (Skia rejected the bitmap; output bytes are incomplete),
	 *                     the FileOutputStream write fails, or the encoded size is out of int range
	 */
	private static File encodeToTempfile(Bitmap bmp, Bitmap.CompressFormat format, int quality,
		String nameInfix, String extension, File cacheDir) throws IOException
	{
		File temp = newPipelineTempfile(cacheDir, UltraHdrCompat.TEMPFILE_PREFIX_HDR_SRC + nameInfix,
			extension);
		boolean success = false;
		try
		{
			try (FileOutputStream fos = new FileOutputStream(temp))
			{
				if (!bmp.compress(format, quality, fos))
				{
					throw new IOException("Bitmap.compress(" + format.name() + ", " + quality
						+ ") returned false — Skia encoder rejected the bitmap; output"
						+ " bytes are incomplete");
				}
				fos.getFD().sync();
			}
			long size = temp.length();
			if (size <= 0 || size > Integer.MAX_VALUE)
			{
				throw new IOException("Encoded " + format.name()
					+ " tempfile size out of range: " + size);
			}
			success = true;
			return temp;
		}
		finally
		{
			if (!success && temp.exists() && !temp.delete())
			{
				Log.w(TAG, "Failed to delete failed-encode " + format.name() + " tempfile "
					+ temp);
			}
		}
	}

	/**
	 * JPEG save pipeline: encode → inject patched metadata → compose gain map (HDR sources) → append SEFT trailer,
	 * each step a file-to-file streaming operation over disk-backed intermediates. The only large Java allocation
	 * is the final readback of the finished file. On HDR-compose drop, re-injects from the encoded primary with
	 * hdrgm XMP + MPF stripped so the output's metadata stays honest.
	 *
	 * @param state    source of segment list, original bytes, gain map, SEFT trailer, and crop geometry
	 * @param bmp      rendered output bitmap; ownership transfers here — recycled immediately after the primary
	 *                 encode (frees ~800 MB of native heap before the HDR re-encode), or in the finally block on
	 *                 failure
	 * @param cropW    output crop width in pixels
	 * @param cropH    output crop height in pixels
	 * @param cacheDir directory for the `hdr_src_`-prefixed pipeline tempfiles; all are deleted in the finally
	 *                 block, and the startup sweep reclaims orphans from a mid-save process kill
	 * @return byte[]-backed ExportResult carrying the final file bytes and whether a gain map was attached
	 * @throws IOException when encoding, injection, composing, or the final readback fails, or a tempfile size
	 *                     is out of int range
	 */
	private static ExportResult exportJpeg(CropState state, Bitmap bmp, int cropW, int cropH,
		File cacheDir) throws IOException
	{
		// Fully streaming pipeline: encode → inject metadata → compose gain map → append SEFT, every step a
		// file-to-file operation with disk-backed intermediates. The naive byte[]-pipeline allocates 4
		// successive byte[] arrays of ~108–148 MB on a 200 MP quality-100 save — peak Java heap of
		// ~350-400 MB during the gain-map compose step, which OOMs even with android:largeHeap once the
		// heap is fragmented. The
		// streaming pipeline holds at most one ~118 MB byte[] alive at a time (the final readback at the end of
		// this method), so peak Java heap drops to roughly (final-output-size + state) ≈ ~150 MB for the same
		// 200 MP HDR save. Tempfile lifecycle: every intermediate file is created in cacheDir with a
		// recognisable `hdr_src_` prefix so UltraHdrCompat.sweepStaleCacheFiles picks them up on next launch if
		// a process kill mid-save leaves orphans. The finally block deletes every file we created on success or
		// failure. deleteOnExit is set as a JVM-shutdown safety net.
		int quality = 100;
		byte[] thumbnail = null;
		byte[] croppedGainMap = null;
		File encodedFile = null;
		File injectedFile = null;
		File composedFile = null;
		File reinjectedFile = null;
		File seftFile = null;
		boolean bmpRecycled = false;
		// Snapshot state.getJpegMeta() ONCE at the top so every downstream stage sees the same list.
		// CropState's volatile field could otherwise change mid-save if any concurrent path (graft install,
		// image reload) commits a new list; without the snapshot, buildEmbeddedThumbnail's budget calculation
		// could be based on one list while the actual inject patches a different one, producing a thumbnail
		// sized to the wrong budget. metaWithHdrStripped is derived once too so the HDR-drop re-inject path
		// uses a list consistent with what was originally injected. state.getJpegMeta() returns the
		// field's immutable list — never null per its Javadoc, so neither reference below needs a null
		// guard; a defensive fallback here would only mask that contract from readers.
		List<JpegSegment> meta = state.getJpegMeta();
		List<JpegSegment> metaWithHdrStripped = stripHdrSegments(meta);
		try
		{
			// Stage 0: build thumbnail FROM bmp while it's still alive (small render — 512 px max).
			thumbnail = buildEmbeddedThumbnail(meta, bmp);
			// Stage 1: encode the primary to a tempfile. After this returns, bmp has served its purpose —
			// every downstream stage reads encodedFile instead. Recycling bmp BEFORE buildCroppedGainMap is
			// critical for HDR reliability: bmp on a lightly-cropped 200 MP source occupies ~800 MB of
			// native memory (200 megapixels × 4 bytes ARGB_8888), and UltraHdrCompat.compressWithGainmap
			// allocates its own 200 MP HDR-decoded bitmap plus a cropped primary plus a cropped gain-map
			// surface — up to ~2 GB of native heap. With bmp still alive AND state.getSourceImage()
			// (another ~800 MB) still alive, native heap can't satisfy the HDR re-decode allocations,
			// UltraHdrCompat catches the failure and returns empty, and HDR drops silently from the saved
			// file. Recycling bmp at this point frees ~800 MB before the HDR re-encode runs.
			encodedFile = encodeToTempfile(bmp, Bitmap.CompressFormat.JPEG, quality, "jpeg_encode_",
				".jpg", cacheDir);
			bmp.recycle();
			bmpRecycled = true;
			// Stage 2: build cropped gain map. state.getOriginalFileBytes() and state.getGainMap() must
			// remain non-null AFTER this returns so a subsequent in-session save (user rotates and re-saves
			// without reloading) can re-run buildCroppedGainMap with the same source bytes. The streaming
			// pipeline below uses tempfile I/O for every step, so Java heap stays well under the largeHeap
			// cap even with the ~50-180 MB originalFileBytes + ~30 MB source gainMap resident — no need to
			// free them mid-save. They get reclaimed by state.reset() at the next image load. Nulling these
			// mid-save would degrade every in-session re-save to SDR — ExportPipeline.doExport reads
			// srcHadHdr from state.getGainMap() at save start, so a null after the first save would
			// short-circuit the HDR re-encode and ship a stripped-HDR file even when the user expects HDR.
			croppedGainMap = buildCroppedGainMap(state, cropW, cropH, cacheDir, quality).orElse(null);

			// Stage 3: pick metadata + inject. HDR-attempted sources (croppedGainMap present) ship with the
			// hdrgm XMP + MPF in the initial inject so composeFileToFile's MPF-offset patcher can rewrite
			// the gain-map offset; non-HDR sources strip HDR markers up front so orphan MPF can't survive a
			// non-HDR re-encode (Samsung "Best Photo" burst groups, focus-stacked panoramas). hdrAttempted
			// is driven off the local croppedGainMap reference — buildCroppedGainMap returns empty on the
			// no-HDR / drop paths (unwrapped to null at the local) and length > 0 bytes otherwise, so the
			// != null check alone classifies correctly (a length > 0 check would just duplicate
			// buildCroppedGainMap's own gate).
			boolean hdrAttempted = croppedGainMap != null;
			List<JpegSegment> initialMeta = hdrAttempted ? meta : metaWithHdrStripped;
			List<JpegSegment> initialPatched = ExifPatcher.patch(initialMeta, cropW, cropH, thumbnail);
			injectedFile = newPipelineTempfile(cacheDir,
				UltraHdrCompat.TEMPFILE_PREFIX_HDR_SRC + "inject_", ".jpg");
			JpegMetadataInjector.injectFileToFile(encodedFile, initialPatched, injectedFile);

			// Stage 4: gain-map compose if HDR was attempted. On compose drop (HDR not attachable),
			// re-inject from encodedFile with stripped meta so the output's XMP / MPF stay honest. Use
			// ComposeResult.hdrAttached() as the canonical signal — reference-inequality on byte[] returns
			// was the original detection mechanism but it's not applicable here.
			boolean hdrAttached = false;
			File preSeftFile;
			if (hdrAttempted)
			{
				composedFile = newPipelineTempfile(cacheDir,
					UltraHdrCompat.TEMPFILE_PREFIX_HDR_SRC + "compose_", ".jpg");
				hdrAttached = GainMapComposer.composeFileToFile(
					injectedFile, croppedGainMap, composedFile);
				if (hdrAttached)
				{
					preSeftFile = composedFile;
				}
				else
				{
					Log.d(TAG, "HDR drop detected — re-injecting with hdrgm XMP + MPF stripped");
					// metaWithHdrStripped is stripHdrSegments(meta)'s return — never null, so no
					// defensive null fallback is needed.
					List<JpegSegment> strippedPatched = ExifPatcher.patch(
						metaWithHdrStripped, cropW, cropH, thumbnail);
					reinjectedFile = newPipelineTempfile(cacheDir,
						UltraHdrCompat.TEMPFILE_PREFIX_HDR_SRC + "reinject_", ".jpg");
					JpegMetadataInjector.injectFileToFile(
						encodedFile, strippedPatched, reinjectedFile);
					preSeftFile = reinjectedFile;
				}
			}
			else
			{
				preSeftFile = injectedFile;
			}
			// croppedGainMap consumed; free for GC before the final readback.
			croppedGainMap = null;

			// Stage 5: SEFT trailer append. Skip the file-to-file copy when there's no SEFT trailer to
			// append — preSeftFile already holds the final bytes, so a Files.copy of a 148 MB tempfile
			// would do 148 MB of pointless disk-to-disk I/O just to rename the file. Point finalFile
			// directly at preSeftFile in that case; the finally block already handles deletion of all the
			// pipeline tempfiles via the local refs.
			byte[] seft = state.getSeftTrailer();
			File finalFile;
			if (seft != null && seft.length > 0)
			{
				seftFile = newPipelineTempfile(cacheDir,
					UltraHdrCompat.TEMPFILE_PREFIX_HDR_SRC + "seft_", ".jpg");
				appendSeftFileToFile(preSeftFile, seft, seftFile);
				finalFile = seftFile;
			}
			else
			{
				finalFile = preSeftFile;
			}

			// Stage 6: final byte[] readback. Only ONE big allocation in the whole save: the encode wrote
			// to tempfile, inject/compose/SEFT-append streamed file-to-file with a 64 KB chunk buffer, and
			// croppedGainMap was nulled above. Peak Java heap at this point is the byte[] size (~100-150 MB
			// on a 200 MP HDR save) + state's originalFileBytes (~50-180 MB, kept resident for in-session
			// re-save) + state's source gainMap (~30 MB) + state.jpegMeta (~10-15 MB) + small overhead —
			// well within the largeHeap budget.
			long finalSize = finalFile.length();
			if (finalSize <= 0 || finalSize > Integer.MAX_VALUE)
			{
				throw new IOException("Final tempfile size out of range: " + finalSize);
			}
			// `finalFile` may point at preSeftFile (when SEFT was empty), so don't expect a separate
			// `seftFile` reference at this point — the finally block deletes all tempfile slots (including
			// the one finalFile aliases) via deleteIfExists.
			byte[] finalBytes = Files.readAllBytes(finalFile.toPath());
			return new ExportResult(finalBytes, hdrAttached);
		}
		finally
		{
			if (!bmpRecycled && !bmp.isRecycled())
			{
				bmp.recycle();
			}
			deleteIfExists(encodedFile);
			deleteIfExists(injectedFile);
			deleteIfExists(composedFile);
			deleteIfExists(reinjectedFile);
			deleteIfExists(seftFile);
		}
	}

	/**
	 * PNG save pipeline: encode → eXIf-chunk EXIF inject, both file-to-file streaming operations. Unlike
	 * exportJpeg, the finished file is never read back into a byte[] — a 200 MP PNG is 400-600 MB, so the result
	 * stays tempfile-backed and ExportPipeline.doExport streams it to the output.
	 *
	 * @param state    source of segment list, raw PNG TIFF (pngExifTiff), and crop geometry
	 * @param bmp      rendered sRGB output bitmap; ownership transfers here — recycled after the encode, or in
	 *                 the finally block on failure
	 * @param cropW    output crop width in pixels
	 * @param cropH    output crop height in pixels
	 * @param cacheDir directory for the `hdr_src_`-prefixed pipeline tempfiles; the non-returned intermediate is
	 *                 deleted here, and the startup sweep reclaims orphans from a mid-save process kill
	 * @return tempfile-backed ExportResult (hdrAttached always false — PNG carries no gain map); ownership of
	 *         the tempfile transfers to the caller, which must delete it after the write completes
	 * @throws IOException when encoding or injection fails, or the final tempfile size is out of int range
	 */
	private static ExportResult exportPng(CropState state, Bitmap bmp, int cropW, int cropH,
		File cacheDir) throws IOException
	{
		// Streaming PNG pipeline: encode → eXIf inject, both file-to-file. The naive byte[] path's
		// `bmp.compress(PNG, 100, BAOS)` + `BAOS.toByteArray()` peaks at ~1 GB live on a 200 MP save (BAOS
		// internal buffer at the next power of 2 above the 400-600 MB compressed size plus the toByteArray
		// copy) — an unrecoverable OOM even with android:largeHeap. Streaming straight to disk keeps the whole
		// method at chunk-buffer Java heap: the result stays tempfile-backed with no whole-file readback here;
		// ExportPipeline streams the tempfile to the output and the save verify runs downstream. bmp is
		// guaranteed sRGB for PNG exports (see export()); grid was rasterized on it with exact pixel-width
		// rectangles. Generate the EXIF thumbnail BEFORE recycle so it represents the cropped +
		// rotated pixels — passing null thumbnail to ExifPatcher would preserve the source's pre-crop IFD1
		// thumbnail, which leaks the original (uncropped, un-rotated, pre-edit) image content via any
		// EXIF-thumbnail-aware viewer. Thumbnail format is JPEG per the EXIF spec regardless of the outer
		// container, so buildEmbeddedThumbnail's JPEG compress is correct for PNG export too. Snapshot
		// state.getJpegMeta() once: thumbnail budget and inject must agree if state mutates concurrently.
		List<JpegSegment> meta = state.getJpegMeta();
		File encodedPng = null;
		File injectedPng = null;
		boolean bmpRecycled = false;
		try
		{
			byte[] thumbnail = buildEmbeddedThumbnail(meta, bmp);
			encodedPng = encodeToTempfile(bmp, Bitmap.CompressFormat.PNG, 100, "png_encode_", ".png",
				cacheDir);
			bmp.recycle();
			bmpRecycled = true;

			// Inject EXIF metadata via PNG eXIf chunk (PNG 1.6 spec). Two paths, both producing a TIFF
			// body that gets streamed into outFile via injectPngExifFromTiffFileToFile:
			//   1. PNG sources keep raw TIFF in state.pngExifTiff (uncapped — the JPEG APP1 u16 limit
			//      doesn't apply to PNG output). Wrap as a synthetic APP1 just to run through
			//      ExifPatcher.patch (which normalises orientation to 1 and rewrites the cropped
			//      dimensions); the wrapper's bytes[2..3] length field may be truncated for > 64 KB
			//      TIFFs but that's harmless because injectPngExifFromTiffFileToFile uses
			//      data().length, not the wrapper's claimed segLen.
			//   2. JPEG sources keep their full segment list in state.jpegMeta; PNG export pulls the
			//      EXIF segment from there. JPEG-source EXIF is always under the u16 cap by spec.
			byte[] pngExifTiff = state.getPngExifTiff();
			byte[] tiffToInject = null;
			if (pngExifTiff != null)
			{
				byte[] patchedTiff = patchPngExifTiff(pngExifTiff, cropW, cropH, thumbnail)
					.orElse(null);
				if (patchedTiff != null)
				{
					tiffToInject = patchedTiff;
				}
				else if (pngExifTiff.length > JpegSegment.MAX_SEGMENT_BYTES - 10)
				{
					// >64 KB PNG source TIFF that ExifPatcher rejected as malformed. The fallback
					// path below (synthetic APP1 from jpegMeta) is u16-capped at 65 535 bytes, so
					// going through it would silently drop the TIFF tail. Ship the source TIFF
					// VERBATIM instead but FIRST force IFD0 orientation to upright (= 1), because
					// the SOURCE TIFF's orientation reflects the pre-load stored orientation and
					// our saved pixels are already upright after BitmapUtils.applyOrientation ran
					// at load. Without the orientation rewrite, EXIF-aware PNG viewers would
					// re-rotate the upright pixels, displaying the saved file sideways. Dimensions
					// in IFD0/IFD1 stay stale (the only place full ExifPatcher.patch would
					// normalise them), but orientation is the load-bearing visual-correctness tag.
					Log.w(TAG, "PNG eXIf >64 KB TIFF (" + pngExifTiff.length
						+ " B) parse failed; forcing orient=1, shipping verbatim");
					tiffToInject = forceTiffOrientationToUpright(pngExifTiff);
				}
			}
			// Fall through to the synthetic-APP1 path when pngExifTiff is null (JPEG source) OR the
			// raw-TIFF patch returned null AND the source TIFF was ≤ 64 KB. Calling ExifPatcher.patch with
			// empty meta fires the synthesise-fresh-EXIF path so a PNG source with no EXIF at all (and a
			// fresh thumbnail) still gets IFD1 written into the eXIf chunk. meta is state.getJpegMeta()
			// which is an immutable list and never null, so no null fallback is needed — adding one
			// would mask the contract as dead defensive code.
			if (tiffToInject == null)
			{
				for (JpegSegment seg : ExifPatcher.patch(meta, cropW, cropH, thumbnail))
				{
					if (seg.isExif())
					{
						// Unwrap the JPEG-style APP1 (FF E1 LL LL "Exif\0\0" [TIFF]) → raw TIFF
						// for the eXIf chunk.
						byte[] segData = seg.data();
						if (segData.length > 10)
						{
							int tl = segData.length - 10;
							tiffToInject = new byte[tl];
							System.arraycopy(segData, 10, tiffToInject, 0, tl);
						}
						break; // only one EXIF segment
					}
				}
			}

			// Pick the final tempfile and hand off ownership to the caller (ExportPipeline.doExport). We do
			// NOT call `Files.readAllBytes` here — on a 200 MP PNG the file is 400-600 MB, and the
			// contiguous allocation OOMs the heap (typical Java heap state at this point is ~390 MB used
			// out of 512 MB largeHeap cap, leaving ~120 MB which can't satisfy a 500 MB allocation).
			// doExport streams from this tempfile to the SAF / file output without ever materialising the
			// bytes as a single byte[]. The non-returned intermediate tempfile (either encodedPng or
			// injectedPng, whichever didn't become finalFile) is deleted here; the returned tempfile is
			// deleted by doExport once write/verify/callback completes. IMPORTANT ordering: validate the
			// final file's size BEFORE nulling the ownership slot. If the size check throws AFTER the slot
			// is nulled, the finally block can't see the tempfile (it's referenced only through finalFile,
			// not through any owned slot) and it leaks on disk. Run the size validation first; only null
			// the slot after the IOException throw point has passed.
			File finalFile;
			File toDelete;
			if (tiffToInject != null && tiffToInject.length > 0)
			{
				injectedPng = newPipelineTempfile(cacheDir,
					UltraHdrCompat.TEMPFILE_PREFIX_HDR_SRC + "png_inject_", ".png");
				injectPngExifFromTiffFileToFile(encodedPng, tiffToInject, injectedPng);
				finalFile = injectedPng;
				toDelete = encodedPng;
			}
			else
			{
				// No EXIF to inject (e.g. screenshot source with no metadata and no thumbnail budget
				// → STRIP_IFD1_THUMBNAIL sentinel produces no EXIF segment). Use encodedPng directly
				// as the final file rather than do a verbatim copy through the inject streamer.
				finalFile = encodedPng;
				toDelete = null;
			}
			long finalSize = finalFile.length();
			if (finalSize <= 0 || finalSize > Integer.MAX_VALUE)
			{
				throw new IOException("Final PNG tempfile size out of range: " + finalSize);
			}
			// Size valid — clean up the discarded intermediate and transfer ownership of finalFile. Now
			// safe to null the owned slot; an exception past this point can't fire.
			deleteIfExists(toDelete);
			if (finalFile == injectedPng)
			{
				injectedPng = null;
			}
			else
			{
				encodedPng = null;
			}
			return new ExportResult(null, finalFile, false);
		}
		finally
		{
			if (!bmpRecycled && !bmp.isRecycled())
			{
				bmp.recycle();
			}
			// Only delete tempfiles still owned by this method. The success-path nulls the slot whose file
			// was handed off to the caller, so deleteIfExists is safe to call unconditionally.
			deleteIfExists(encodedPng);
			deleteIfExists(injectedPng);
		}
	}

	/**
	 * Produce an EXIF thumbnail JPEG that fits within maxBytes. Two-rung cascade on the longest side:
	 * 512 maxDim first, then 256 maxDim. Each rung tries qualities 90 → 80 → 75 → 70 → 65 → 60 → 55 →
	 * 50 in order; the first encoding that fits maxBytes wins. Returns empty when 256-maxDim q50 still
	 * doesn't fit — caller routes that through STRIP_IFD1_THUMBNAIL.
	 *
	 * Cascade design: drop quality before dim. Viewers downscale the EXIF preview for grid / hover display (96-256
	 * px), and downscaling masks JPEG artifacts — a heavily-compressed 410×512 viewed at 128×160 looks better than
	 * a small high-quality 205×256 upscaled to 256×320. Matches Samsung's "preserve dim, scale quality" design. No
	 * 1024-maxDim rung because typical 3-4 MP source produces 130-400 KB at any quality — well above the 65
	 * 535-byte APP1 cap, so the rung was unreachable.
	 *
	 * No q95 rung: documented as unreachable on CropCenter's re-encoded source bitmap (the JPEG cascade tax
	 * inflates bytes-per-pixel 2-4× over fresh-sensor input, so q95 never fits the APP1 cap). A cleaner future
	 * input pipeline (raw decode / lossless intermediate) would re-introduce q95 — until then, keeping it would
	 * waste an encode pass that always falls through to q90.
	 *
	 * The thumbnail is rendered into an sRGB bitmap regardless of bmp's color space: a DISPLAY_P3 bmp would embed
	 * an APP2 ICC profile (~500-600 bytes) inside the thumbnail JPEG, which combined with a tight maxBytes budget
	 * can cause ExifPatcher.replaceThumbnail to silently reject for APP1 overflow. sRGB matches camera-native
	 * thumbnails and keeps the byte budget predictable.
	 *
	 * @param bmp      source bitmap to encode; not recycled
	 * @param maxBytes APP1 budget remaining for the thumbnail (incl. EXIF wrapper overhead the caller
	 *                 already deducted)
	 * @return thumbnail JPEG bytes that fit within maxBytes, or empty when the budget is non-positive,
	 *         no cascade rung succeeds, or generation throws (incl. OOM)
	 */
	private static Optional<byte[]> generateThumbnail(Bitmap bmp, int maxBytes)
	{
		if (maxBytes <= 0)
		{
			Log.w(TAG, "Thumbnail budget ≤ 0 — skipping generation");
			return Optional.empty();
		}
		int width = bmp.getWidth();
		int height = bmp.getHeight();
		// Cascade: dimensions × qualities. First combo whose encoded size fits maxBytes wins. The 512 → 256
		// maxDim split aligns with Samsung's native thumbnail (~512×640 portrait); the 8-step quality bracket
		// exhausts dim-preserving fallback (q90..q50) before stepping down to 256 maxDim. q95 omitted because
		// it's unreachable under the APP1 cap on re-encoded sources — see the method Javadoc above.
		int[] maxDims = { 512, 256 };
		int[] qualities = { 90, 80, 75, 70, 65, 60, 55, 50 };
		Bitmap thumb = null;
		try
		{
			for (int maxDim : maxDims)
			{
				// Compute scale in double precision: 512/5000 in float is 0.102399997f (not 0.1024),
				// which can drop 4096*0.1024=409.6 into 409.599988 and — once Math.round(float)
				// delegates to (int)floor(x + 0.5f) — occasionally land on 409 instead of 410. Using
				// double eliminates the drift entirely.
				double scale = Math.min((double) maxDim / width, (double) maxDim / height);
				scale = Math.min(scale, 1.0); // don't upscale
				int rw = Math.max(1, (int) Math.round(width * scale));
				int rh = Math.max(1, (int) Math.round(height * scale));
				if (thumb != null && !thumb.isRecycled())
				{
					thumb.recycle();
				}
				thumb = renderSrgbThumb(bmp, rw, rh);
				for (int quality : qualities)
				{
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					// Skip on Skia rejection — partial bytes would either fit the budget (corrupt
					// thumbnail in IFD1) or fail it (skip anyway). Explicit skip keeps the fallback
					// chain honest.
					if (!thumb.compress(Bitmap.CompressFormat.JPEG, quality, bos))
					{
						Log.w(TAG, "thumb.compress " + rw + "x" + rh + " q" + quality
							+ " returned false; trying next");
						continue;
					}
					byte[] result = bos.toByteArray();
					if (result.length <= maxBytes)
					{
						Log.d(TAG, "Thumbnail: " + rw + "x" + rh + " q" + quality
							+ " = " + result.length + "B");
						return Optional.of(result);
					}
				}
			}
			Log.w(TAG, "Thumbnail too large at every cascade rung; returning empty (maxBytes="
				+ maxBytes + ")");
			return Optional.empty();
		}
		catch (Exception | OutOfMemoryError e)
		{
			// Widened to OOM as well as Exception so a thumbnail-side allocation failure (Bitmap.compress
			// on a multi-MP intermediate, or renderSrgbThumb's Canvas.drawBitmap rasterisation) degrades to
			// "save without embedded thumbnail" rather than aborting the whole save with no toast —
			// encodePhase's catch is also Exception-only and OOM would otherwise propagate silently.
			Log.w(TAG, "Thumbnail generation failed", e);
			return Optional.empty();
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
	 * Allocate a fresh pipeline tempfile named so the startup sweep (UltraHdrCompat.sweepStaleCacheFiles, called
	 * from MainActivity.onCreate) reclaims it if a process kill leaves an orphan.
	 *
	 * @param cacheDir  app cache directory the sweep covers
	 * @param prefix    one of the `hdr_src_` sweep-matched prefixes; PID + nanoTime are appended for uniqueness
	 * @param extension tempfile extension including the dot
	 * @return fresh non-existent tempfile, also registered with deleteOnExit as a second cleanup layer
	 */
	private static File newPipelineTempfile(File cacheDir, String prefix, String extension)
	{
		File temp = new File(cacheDir, prefix + Process.myPid() + "_" + System.nanoTime() + extension);
		temp.deleteOnExit();
		return temp;
	}

	/**
	 * Render `src` into a fresh sRGB ARGB_8888 bitmap at the requested dimensions using a bilinear-filtered Canvas
	 * draw. The output is guaranteed to compress to a plain baseline JPEG with no ICC profile APP2 segment,
	 * regardless of `src`'s color space.
	 *
	 * Recycles `out` and rethrows on any Canvas / Paint / drawBitmap failure (RuntimeException) or
	 * OutOfMemoryError. Without the wrap, `out`'s native pixel buffer would orphan to the GC finalizer under the
	 * same allocation pressure that triggered the OOM.
	 *
	 * @param src    source bitmap; read only, never recycled here
	 * @param width  output width in pixels
	 * @param height output height in pixels
	 * @return freshly allocated sRGB ARGB_8888 bitmap owned by the caller
	 */
	private static Bitmap renderSrgbThumb(Bitmap src, int width, int height)
	{
		Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true,
			ColorSpace.get(ColorSpace.Named.SRGB));
		try
		{
			Canvas canvas = new Canvas(out);
			Rect srcRect = new Rect(0, 0, src.getWidth(), src.getHeight());
			Rect dstRect = new Rect(0, 0, width, height);
			Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
			canvas.drawBitmap(src, srcRect, dstRect, paint);
			return out;
		}
		catch (RuntimeException | OutOfMemoryError e)
		{
			out.recycle();
			throw e;
		}
	}

}
