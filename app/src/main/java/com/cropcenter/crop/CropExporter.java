package com.cropcenter.crop;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import com.cropcenter.metadata.ExifPatcher;
import com.cropcenter.metadata.GainMapComposer;
import com.cropcenter.metadata.HdrSignature;
import com.cropcenter.metadata.JpegMarker;
import com.cropcenter.metadata.JpegMarkerWalker;
import com.cropcenter.metadata.JpegMetadataInjector;
import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.metadata.PngMetadataExtractor;
import com.cropcenter.model.CropState;
import com.cropcenter.model.Format;
import com.cropcenter.model.GridConfig;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.UltraHdrCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Full export pipeline: render → compress → inject original metadata (EXIF patched, ICC/XMP/MPF preserved) → append
 * gain map and fix MPF offsets.
 */
public final class CropExporter
{
	private static final String TAG = "CropExporter";
	// Referenced by name in REQUIREMENTS.md §9 ("not filled with CANVAS_BG for PNG"). Keeping at class scope
	// so the spec citation remains a real symbol rather than a bare hex literal in prose.
	private static final int CANVAS_BG = 0xFF0D0E14; // opaque very-dark-navy — visible at rotation corners

	private CropExporter() {}

	/**
	 * Export the cropped + rotated image as JPEG, PNG, or Ultra HDR JPEG bytes per
	 * `state.getExportConfig().format()`. Single entry point for the entire export pipeline:
	 * canvas-renders the primary at exact (cropW, cropH) integer pixel dimensions, generates a
	 * fresh EXIF thumbnail of the cropped pixels, encodes to JPEG / PNG, splices the source's EXIF
	 * (orientation normalised to 1, dimensions rewritten, IFD1 thumbnail replaced) + ICC + XMP +
	 * MPF + Samsung SEFT trailer back in, and — for HDR sources — re-renders the gain map at the
	 * primary's transform via UltraHdrCompat and composes it into the output via GainMapComposer.
	 *
	 * Returns an ExportResult carrying the bytes plus a structural hdrAttached flag — true when the
	 * gain map was successfully re-composed, false when HDR was dropped (UltraHdrCompat couldn't
	 * produce a valid output, MPF patching failed, XmpItemLengthPatcher fail-closed, etc.) so
	 * `ExportPipeline.reportSuccess` can report "[HDR OK]" / "[HDR dropped]" without a substring
	 * scan that false-positives on stale metadata.
	 *
	 * @param state    CropState with source image, crop dims, rotation, AR, grid config, jpegMeta,
	 *                 gainMap, seftTrailer. `state.getSourceImage()` is read-only here — only the
	 *                 internally-rendered cropped Bitmap (passed down to exportJpeg / exportPng) gets
	 *                 recycled inside this call. The editor's source bitmap stays loaded so the user
	 *                 can re-save with different settings without a re-decode.
	 * @param cacheDir Activity cache dir for UltraHdrCompat's intermediate file work; may be null
	 *                 when the platform decode path doesn't need a temp file
	 * @return ExportResult carrying the encoded bytes and the structural hdrAttached flag
	 * @throws IOException when no image is loaded, encoding fails, or metadata splicing rejects the input
	 *                     as malformed
	 */
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
		boolean hasGainMap = state.getGainMap() != null && state.getGainMap().length > 0;
		Bitmap outBmp;
		if (isJpeg && hasGainMap)
		{
			outBmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888, true,
				ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
		}
		else if (isJpeg)
		{
			ColorSpace srcCs = src.getColorSpace();
			outBmp = (srcCs != null)
				? Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888, true, srcCs)
				: Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888);
		}
		else
		{
			outBmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888);
		}

		// outBmp ownership transfers to exportJpeg / exportPng on the success path — both recycle in their own
		// finally. But if drawCropped or drawGridPixels throws (OOM on huge inputs is the realistic case), or
		// if the switch hits the encode-failure branch before ownership transfers, outBmp would leak its
		// native pixel buffer to the GC finalizer. The handedOff flag flips true the moment the switch is about
		// to delegate, so the catch / non-success paths recycle locally.
		boolean handedOff = false;
		try
		{
			Canvas canvas = new Canvas(outBmp);
			Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
			// JPEG can't represent alpha — fill with the editor's canvas color so rotation corners and any
			// transparent source pixels read as the same dark navy the user saw in the preview. PNG keeps
			// the bitmap's default transparent state so alpha sources round-trip and rotation corners stay
			// see-through.
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
				case JPEG -> exportJpeg(state, outBmp, cropW, cropH, cacheDir);
				case PNG -> new ExportResult(exportPng(state, outBmp, cropW, cropH), false);
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
	 * collapses the mirror trick to a plain Math.round((double) dim * i / count) would silently produce
	 * asymmetric exported grids on count=4 / dim=10-class fixtures.
	 *
	 * @param i     grid line index in [1, count − 1] — the integer line count from the left/top edge
	 * @param count total number of grid intervals (so the grid draws count − 1 internal lines)
	 * @param dim   total dimension in output pixels along this axis (cropW or cropH)
	 * @return pixel index where line i sits in the baked export; for any i, the (i, count − i) pair
	 *         satisfies result(i) + result(count − i) == dim
	 */
	static int gridLinePixel(int i, int count, int dim)
	{
		if (i * 2 > count)
		{
			int mirror = (int) Math.round((double) dim * (count - i) / count);
			return dim - mirror;
		}
		return (int) Math.round((double) dim * i / count);
	}

	/**
	 * Run a raw TIFF through ExifPatcher.patch to normalise orientation (forced to 1 because pixels
	 * were rotated at load time), rewrite cropped dimensions, and replace OR strip the embedded IFD1
	 * thumbnail. Wraps the TIFF as a synthetic APP1 segment for ExifPatcher's segment-oriented API;
	 * the wrapper's u16 length field (bytes 2..3) may be truncated for > 64KB TIFFs but ExifPatcher
	 * reads only data().length, never the wrapped length, so the truncation is harmless. Returns the
	 * patched TIFF bytes, or null when ExifPatcher rejected the input (malformed byte order, etc.).
	 *
	 * Stale-thumbnail safety on huge PNG eXIf: ExifPatcher's spliceExistingThumbnail still enforces the
	 * JPEG APP1 u16 cap (65535) on the rebuilt synthetic segment, so a too-large rebuild rejects
	 * silently and leaves the source's IFD1 thumbnail in place — leaking pre-edit content via the
	 * embedded preview. Predict the rejection here using `ExifPatcher.maxThumbnailBytes`, which
	 * subtracts the OLD thumbnail's bytes from the segment size before measuring remaining APP1 room
	 * (a naive `tiff.length + thumbnail.length` sum would force-strip many splices that actually
	 * shrink the segment).
	 * When the new thumbnail won't fit even after old-thumbnail removal, force-route to
	 * `ExifPatcher.STRIP_IFD1_THUMBNAIL` so the saved PNG carries no IFD1 rather than the source's
	 * pre-edit preview.
	 *
	 * Package-private (not private) so CropExporterPngExifTest can pin the strip-vs-splice decision
	 * directly without round-tripping through the full export pipeline.
	 *
	 * @param tiff      raw TIFF bytes from the source PNG's eXIf chunk
	 * @param newW      cropped image width
	 * @param newH      cropped image height
	 * @param thumbnail fresh JPEG thumbnail of the cropped pixels — non-null is required so the IFD1
	 *                  thumbnail doesn't carry the source's pre-crop preview; passing null here would
	 *                  PRESERVE the original thumbnail, leaking pre-edit content. Force-overridden to
	 *                  STRIP_IFD1_THUMBNAIL when the rebuilt segment cannot fit under APP1's cap even
	 *                  after old-thumbnail removal
	 * @return patched TIFF bytes ready for the eXIf chunk, or null on parse failure
	 */
	static byte[] patchPngExifTiff(byte[] tiff, int newW, int newH, byte[] thumbnail)
	{
		// Build a synthetic APP1 segment: FF E1 LL LL "Exif\0\0" [TIFF...]. Bytes 2..3 (segLen u16)
		// get truncated when 2 + 6 + tiff.length > 65535, but the only consumer here is
		// ExifPatcher.patch / maxThumbnailBytes which both read data().length directly.
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
		// ExifPatcher.maxThumbnailBytes (rather than a naive tiff.length + thumbnail.length sum) walks
		// IFD0 → IFD1 → JPEGInterchangeFormatLength to find the OLD thumbnail size and returns
		// `JpegSegment.MAX_SEGMENT_BYTES - (data.length - oldThumbLen)`, the exact post-splice budget.
		// A naive sum would force-strip a 50KB-old + 30KB-fresh case even though the rebuilt segment
		// (~30KB after old-thumb removal) would fit comfortably.
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
					return null;
				}
				byte[] patchedTiff = new byte[patchedData.length - 10];
				System.arraycopy(patchedData, 10, patchedTiff, 0, patchedTiff.length);
				return patchedTiff;
			}
		}
		return null;
	}

	/**
	 * Drop HDR-specific segments — XMP segments containing the `hdrgm` namespace marker (standard OR
	 * Extended XMP), and APP2/MPF segments pointing at the gain map. Used on the HDR-drop path so the
	 * saved JPEG's metadata doesn't claim HDR that the output file doesn't actually carry.
	 *
	 * Drops the WHOLE XMP segment when it contains hdrgm rather than surgically rewriting the XML — most camera
	 * vendors split XMP into multiple APP1 segments anyway, so any non-hdrgm XMP typically lives in a separate
	 * segment. The corner case (a single XMP segment carrying hdrgm + non-hdrgm metadata) loses the non-hdrgm
	 * tags too, but that beats lying to ExportPipeline.reportSuccess about HDR presence.
	 *
	 * Delegates to HdrSignature.isHdrgmXmpSegment so the standard-plus-extended-XMP detection stays in
	 * lockstep with the load-time hasHdrgmInXmp gate — without that, an HDR-drop output of a source whose
	 * hdrgm declaration was in Extended XMP would leak the HDR signature past the gain-map removal.
	 *
	 * Package-private so CropExporterStripHdrTest can pin the strip behavior — particularly the
	 * Extended-XMP `hdrgm` strip, where a regression that drops the Extended-XMP branch of
	 * HdrSignature.isHdrgmXmpSegment would silently let HDR-claiming XMP survive on the HDR-drop output.
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
	 * Re-append an existing SEFT trailer verbatim, or return the JPEG unchanged when none was captured at load.
	 * CropCenter does not generate fresh SEFTs — Samsung Gallery's Revert validates a backup path the SEFT claims,
	 * and only honors paths under Samsung-blessed locations like `/data/sec/photoeditor/` that third-party apps
	 * cannot write to. A SEFT we generate pointing at our own `/storage/emulated/0/.cropcenter/` write is silently
	 * rejected by Gallery, so fabricating one is a net negative (disk bloat with no Revert benefit). Files that
	 * came in with a SEFT — Gallery-edited originals — keep their working Revert chain because we re-append exactly
	 * the bytes we extracted at load.
	 */
	private static byte[] appendSeft(byte[] jpeg, byte[] existingSeft)
	{
		if (existingSeft == null || existingSeft.length == 0)
		{
			return jpeg;
		}
		// Long-arithmetic guard against int overflow on jpeg.length + existingSeft.length — wrap
		// produces a negative sum that NegativeArraySizeException's the bg encode worker.
		long combinedLong = (long) jpeg.length + (long) existingSeft.length;
		if (combinedLong > Integer.MAX_VALUE)
		{
			Log.w(TAG, "JPEG " + jpeg.length + " + SEFT " + existingSeft.length
				+ " would overflow int; dropping SEFT trailer to keep the primary intact");
			return jpeg;
		}
		Log.d(TAG, "Preserving existing SEFT trailer: " + existingSeft.length + " bytes");
		byte[] result = new byte[(int) combinedLong];
		System.arraycopy(jpeg, 0, result, 0, jpeg.length);
		System.arraycopy(existingSeft, 0, result, jpeg.length, existingSeft.length);
		return result;
	}

	/**
	 * For HDR sources, render a cropped Ultra HDR JPEG via UltraHdrCompat and extract the gain-map bytes from its
	 * tail. The primary-image bytes still come from the canvas rendering above; this only harvests the gain map,
	 * which must be spatially aligned to the same crop / rotation as the primary. Returns null when the source
	 * isn't HDR or when UltraHdrCompat couldn't produce a valid output.
	 */
	private static byte[] buildCroppedGainMap(CropState state, int cropW, int cropH, File cacheDir, int quality)
	{
		byte[] originalBytes = state.getOriginalFileBytes();
		// Match the gain-map presence check at the top of export() — a zero-length gain-map array means
		// extraction succeeded structurally but produced no usable bytes (rare but observed after malformed
		// extraction). UltraHdrCompat would silently fall back to SDR; flag here so the caller's null check
		// drops the HDR path explicitly rather than letting a degraded HDR encode go through.
		boolean hasHdr = state.getGainMap() != null && state.getGainMap().length > 0 && originalBytes != null;
		if (!hasHdr)
		{
			return null;
		}

		float centerX = state.hasCenter() ? state.getCenterX() : state.getImageWidth() / 2f;
		float centerY = state.hasCenter() ? state.getCenterY() : state.getImageHeight() / 2f;
		int exifOrient = BitmapUtils.readExifOrientation(originalBytes);
		CropRender render = CropRender.of(centerX, centerY, cropW, cropH,
			state.getImageWidth(), state.getImageHeight(), state.getRotationDegrees());
		byte[] hdrResult = UltraHdrCompat.compressWithGainmap(
			originalBytes, quality, cacheDir, render, exifOrient, state.getAiMask());
		if (hdrResult == null)
		{
			Log.d(TAG, "HDR generation failed, falling back to non-HDR");
			return null;
		}

		int primaryEnd = JpegMarkerWalker.findPrimaryEoi(hdrResult, hdrResult.length);
		if (primaryEnd <= 0 || primaryEnd >= hdrResult.length)
		{
			return null;
		}
		byte[] gainMap = new byte[hdrResult.length - primaryEnd];
		System.arraycopy(hdrResult, primaryEnd, gainMap, 0, gainMap.length);
		Log.d(TAG, "Extracted gain map: " + gainMap.length + " bytes");
		return gainMap;
	}

	/**
	 * Generate the embedded EXIF thumbnail sized to fit the available APP1 budget. Using the full remaining APP1
	 * budget (minus IFD overhead) gives a thumbnail that matches camera-native resolution instead of being
	 * artificially shrunk. Returns ExifPatcher.STRIP_IFD1_THUMBNAIL (a byte[0] sentinel) — never null — when the
	 * budget is too small for a meaningful thumbnail, the retry-at-half-size still doesn't fit, or generation
	 * throws OOM. That sentinel routes ExifPatcher through the strip-IFD1 path so the saved file carries no
	 * embedded preview, rather than preserving the SOURCE's pre-edit thumbnail (passing null here would
	 * leave the source thumbnail in place, leaking pre-edit content via any EXIF-thumbnail-aware viewer).
	 */
	private static byte[] buildEmbeddedThumbnail(CropState state, Bitmap bmp)
	{
		// Exact-budget formulation: ask ExifPatcher to predict the post-patch segment's non-thumbnail
		// byte count (mirrors patch's decision tree — splice / append / synthesise), then subtract from
		// the APP1 segment cap to know precisely how many bytes the new thumbnail can occupy. No
		// estimation margin, no upper clamp; the prediction is byte-exact for the splice path that
		// camera sources hit and conservative-by-42-bytes for the append fallback. Floor at 0 because
		// `data.length + 42` from the append-path predictor can exceed the cap on pathological
		// already-near-full source EXIF (corrupt or near-MAX_INT lengths) — a negative budget would
		// propagate to generateThumbnail's `maxBytes <= 0` short-circuit and we'd fall through to
		// STRIP_IFD1_THUMBNAIL, same outcome as if every cascade rung failed.
		List<JpegSegment> metaForThumb = state.getJpegMeta();
		int outputNonThumb = ExifPatcher.patchedNonThumbBytes(metaForThumb);
		int thumbBudget = Math.max(0, JpegSegment.MAX_SEGMENT_BYTES - outputNonThumb);
		byte[] thumb = generateThumbnail(bmp, thumbBudget);
		if (thumb == null)
		{
			Log.w(TAG, "Thumbnail generation returned null at budget " + thumbBudget
				+ "; falling back to STRIP_IFD1_THUMBNAIL — saved file will have no preview");
			return ExifPatcher.STRIP_IFD1_THUMBNAIL;
		}
		return thumb;
	}

	/**
	 * Append the cropped gain map to the primary JPEG when HDR extraction succeeded. The original
	 * state.getGainMap() is aligned to the UNCROPPED / UNROTATED source, so we refuse to ship it onto a cropped /
	 * rotated primary — that would put gain-map blobs off the features they were meant to highlight. Better to drop
	 * HDR than ship a broken file; doExport's toast reports "[HDR dropped]" in that case.
	 *
	 * @param jpegBytes      primary JPEG bytes with metadata already injected
	 * @param state          source state (used for diagnostic logging on the gain-map-extraction-failed path)
	 * @param croppedGainMap cropped gain-map JPEG bytes from UltraHdrCompat, or null/empty when extraction
	 *                       failed
	 * @return tagged result — bytes set to either the input jpegBytes (no gain map to attempt) or
	 *         GainMapComposer.compose's output; hdrAttached true only when the gain map was successfully
	 *         appended AND MPF offsets point at it
	 */
	private static GainMapComposer.ComposeResult composeGainMap(byte[] jpegBytes, CropState state,
		byte[] croppedGainMap)
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
		return new GainMapComposer.ComposeResult(jpegBytes, false);
	}

	/**
	 * Draw grid lines by directly setting pixels on the bitmap. Bypasses Canvas rasterization entirely — guaranteed
	 * to produce exact line widths regardless of bitmap color space or Canvas rendering quirks. Line positions are
	 * computed as continuous float offsets from the crop's top-left and then rounded to the nearest output pixel,
	 * matching what GridRenderer.linePos produces on the preview canvas.
	 */
	private static void drawGridPixels(Bitmap bmp, int width, int height, GridConfig grid)
	{
		int lineWidth = Math.max(1, Math.round(grid.lineWidth()));
		int halfLineWidth = lineWidth / 2;
		int color = grid.color();

		// Allocate one full-size band per axis up front (sized for the WIDEST possible line); reuse the same
		// buffer for every line by passing the actual stride to setPixels. The previous version allocated
		// a fresh int[] for any line clipped at the image edge — on a 16K-wide crop with a 7×7 grid and
		// lineWidth=20, that was up to 14 fresh allocations totaling ~18 MB of transient int[] per save.
		int[] vertColumn = new int[lineWidth * height];
		Arrays.fill(vertColumn, color);
		for (int i = 1; i < grid.columns(); i++)
		{
			int x = gridLinePixel(i, grid.columns(), width);
			int left = Math.max(0, x - halfLineWidth);
			// Right bound is computed from the un-clipped line center (x + lineWidth - halfLineWidth) so a
			// line whose left edge clipped against image x=0 doesn't extend its width by halfLineWidth-x to
			// the right. The previous `left + lineWidth` form drew the full lineWidth even after the left
			// portion was lost, producing visibly thicker grid lines on the image's left edge at thick
			// widths. The right-edge case has always been correct because the clamp catches it; this aligns
			// the left-edge case with the same "render only the visible pixels" semantics.
			int right = Math.min(width, x + lineWidth - halfLineWidth);
			int actualWidth = right - left;
			if (actualWidth <= 0)
			{
				continue;
			}
			// Buffer is uniformly colored, so reading any (stride × height) subset gives the same result.
			// Required: vertColumn.length >= actualWidth * height — satisfied because actualWidth is
			// always ≤ lineWidth (clipped at the image edge).
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

	private static ExportResult exportJpeg(CropState state, Bitmap bmp, int cropW, int cropH,
		File cacheDir) throws IOException
	{
		int quality = 100;
		// Recycle the primary bitmap on every exit. buildEmbeddedThumbnail and buildCroppedGainMap render
		// into intermediate bitmaps that can OOM on multi-MP HDR sources; without the wrap covering them
		// the OOM would orphan bmp's native pixel buffer for the GC finalizer. Post-compress metadata work
		// doesn't touch bmp, so it stays outside.
		byte[] thumbnail;
		byte[] croppedGainMap;
		byte[] jpegBytes;
		try
		{
			thumbnail = buildEmbeddedThumbnail(state, bmp);
			croppedGainMap = buildCroppedGainMap(state, cropW, cropH, cacheDir, quality);
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			// Bitmap.compress returns false on Skia encoder rejection — but the partial output in `bos`
			// (headers, segments, mid-entropy bytes that landed before the failure) looks like a valid
			// JPEG to a casual byte walker. Throw IOException so encodePhase routes it to "Export failed"
			// instead of writing a structurally invalid file. Samsung HDR is the most likely trigger —
			// Skia's Gainmap-aware encoder fails when the attached gainmap state isn't what it expects.
			if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos))
			{
				throw new IOException("Bitmap.compress(JPEG, " + quality + ") returned false — "
					+ "Skia encoder rejected the bitmap; output bytes are incomplete");
			}
			jpegBytes = bos.toByteArray();
		}
		finally
		{
			bmp.recycle();
		}

		// HDR-drop bookkeeping. If the gain-map composition is skipped (croppedGainMap == null) or fails
		// inside GainMapComposer.compose (MPF patch rejects, malformed source MPF), the output can't
		// carry an attached gain map but source's XMP-hdrgm + APP2-MPF metadata would still claim one.
		// Strict decoders reject the orphan; lenient decoders render with the wrong offset. Strip the
		// HDR-specific segments when we know the gain map didn't make it.
		//
		// When the source carries an MPF segment but no Ultra HDR gain map (Samsung "Best Photo" burst
		// groups, focus-stacked panoramas, any multi-picture JPEG without hdrgm), source's MPF is
		// anchored at non-existent secondary-image offsets — strip MPF up-front for the non-HDR inject
		// path so the output never ships orphan MPF metadata.
		boolean hdrAttempted = state.getGainMap() != null && state.getGainMap().length > 0
			&& state.getOriginalFileBytes() != null;
		List<JpegSegment> meta = state.getJpegMeta();
		List<JpegSegment> metaWithHdrStripped = (meta != null) ? stripHdrSegments(meta) : null;
		// HDR path: optimistic inject uses full meta so composeGainMap's MPF-offset patcher has the
		// source's MPF segment to rewrite for the new gain-map slot. Non-HDR path: use the pre-stripped
		// meta directly so orphan MPF can't survive a non-HDR re-encode.
		List<JpegSegment> initialMeta = hdrAttempted ? meta : metaWithHdrStripped;

		// Inject the chosen metadata, attempt the gain-map compose, and on drop (hdrAttached=false)
		// re-inject from scratch with HDR-specific segments stripped so the output's metadata stays
		// honest. Use ComposeResult.hdrAttached() as the canonical signal: reference-inequality
		// (`withGainMap != withFullMeta`) is unreliable because the MPF-fail path returns a
		// freshly-allocated XMP-patched primary distinct from withFullMeta.
		byte[] withFullMeta = injectExifMetadata(jpegBytes, initialMeta, cropW, cropH, thumbnail);
		GainMapComposer.ComposeResult composed = composeGainMap(withFullMeta, state, croppedGainMap);
		byte[] withGainMap = composed.bytes();
		boolean hdrAttached = composed.hdrAttached();
		if (hdrAttempted && !hdrAttached)
		{
			Log.d(TAG, "HDR drop detected — re-injecting metadata with hdrgm XMP + MPF stripped");
			jpegBytes = injectExifMetadata(jpegBytes, metaWithHdrStripped, cropW, cropH, thumbnail);
		}
		else
		{
			jpegBytes = withGainMap;
		}
		jpegBytes = appendSeft(jpegBytes, state.getSeftTrailer());

		// hdrAttached threaded back structurally: true ONLY when the gain map was appended AND MPF
		// offsets were patched to point at it; false on every drop path. ExportPipeline.reportSuccess
		// uses this flag instead of substring-scanning the output for "hdrgm" (false-positives on
		// preserved trailers, stale metadata, and Extended-XMP segments).
		return new ExportResult(jpegBytes, hdrAttached);
	}

	private static byte[] exportPng(CropState state, Bitmap bmp, int cropW, int cropH) throws IOException
	{
		// bmp is guaranteed sRGB for PNG exports (see export()); grid was rasterized on it with exact
		// pixel-width rectangles. Generate the EXIF thumbnail BEFORE recycle so it represents the
		// cropped + rotated pixels — passing null thumbnail to ExifPatcher would preserve the source's
		// pre-crop IFD1 thumbnail, which leaks the original (uncropped, un-rotated, pre-edit) image
		// content via any EXIF-thumbnail-aware viewer. Thumbnail format is JPEG
		// per the EXIF spec regardless of the outer container, so buildEmbeddedThumbnail's JPEG
		// compress is correct for PNG export too.
		byte[] thumbnail;
		byte[] pngBytes;
		try
		{
			thumbnail = buildEmbeddedThumbnail(state, bmp);
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			// Same check as the JPEG path — a false return ships a partial PNG header with no IEND
			// chunk to downstream injection, producing a file viewers reject as truncated.
			if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, bos))
			{
				throw new IOException("Bitmap.compress(PNG, 100) returned false — "
					+ "Skia encoder rejected the bitmap; output bytes are incomplete");
			}
			pngBytes = bos.toByteArray();
		}
		finally
		{
			bmp.recycle();
		}

		// Inject EXIF metadata via PNG eXIf chunk (PNG 1.6 spec). Two paths:
		//   1. PNG sources keep raw TIFF in state.pngExifTiff (uncapped — the JPEG APP1 u16 limit doesn't
		//      apply to PNG output). Wrap as a synthetic APP1 just to run through ExifPatcher.patch (which
		//      normalises orientation to 1 and rewrites the cropped dimensions); the wrapper's bytes[2..3]
		//      length field may be truncated for > 64KB TIFFs but that's harmless because injectPngExif
		//      uses data().length, not the wrapper's claimed segLen.
		//   2. JPEG sources keep their full segment list in state.jpegMeta; PNG export pulls the EXIF
		//      segment from there. JPEG-source EXIF is always under the u16 cap by spec, so no special
		//      handling needed.
		byte[] pngExifTiff = state.getPngExifTiff();
		boolean wrotePngExif = false;
		if (pngExifTiff != null)
		{
			byte[] patchedTiff = patchPngExifTiff(pngExifTiff, cropW, cropH, thumbnail);
			if (patchedTiff != null)
			{
				pngBytes = injectPngExifFromTiff(pngBytes, patchedTiff);
				wrotePngExif = true;
			}
		}
		// Fall through to the synthetic-APP1 path when (a) the source was JPEG (pngExifTiff null) or (b) the
		// raw-TIFF patch returned null because ExifPatcher rejected the input as malformed. Without this
		// fallback, a malformed PNG eXIf chunk would silently drop EXIF on the saved PNG even though
		// state.jpegMeta still has a valid synthetic APP1 (capped, but valid for in-bounds payloads).
		// Calling ExifPatcher.patch with null/empty meta also fires its synthesise-fresh-EXIF path so a
		// PNG source with no EXIF at all (and a fresh thumbnail generated above) still gets its IFD1
		// written into the eXIf chunk.
		if (!wrotePngExif)
		{
			List<JpegSegment> meta = state.getJpegMeta();
			List<JpegSegment> safeMeta = (meta != null) ? meta : List.of();
			for (JpegSegment seg : ExifPatcher.patch(safeMeta, cropW, cropH, thumbnail))
			{
				if (seg.isExif())
				{
					pngBytes = injectPngExif(pngBytes, seg.data());
					break; // only one EXIF segment
				}
			}
		}

		return pngBytes;
	}

	/**
	 * Produce an EXIF thumbnail JPEG that fits within maxBytes. Two-rung cascade on the longest side:
	 * 512 maxDim first, then 256 maxDim. Each rung tries qualities 95 → 90 → 80 → 75 → 70 → 65 → 60 →
	 * 55 → 50 in order; the first encoding that fits maxBytes wins. Returns null when 256-maxDim q50
	 * still doesn't fit — caller routes that through STRIP_IFD1_THUMBNAIL.
	 *
	 * Cascade design: drop quality before dim. Viewers downscale the EXIF preview for grid / hover
	 * display (96-256 px), and downscaling masks JPEG artifacts — a heavily-compressed 410×512 viewed
	 * at 128×160 looks better than a small high-quality 205×256 upscaled to 256×320. Matches Samsung's
	 * "preserve dim, scale quality" design. No 1024-maxDim rung because typical 3-4 MP source produces
	 * 130-400 KB at any quality — well above the 65 535-byte APP1 cap, so the rung was unreachable.
	 *
	 * q95 is currently unreachable on CropCenter's re-encoded source bitmap (JPEG cascade tax inflates
	 * bytes-per-pixel 2-4× over fresh-sensor input), but kept in the cascade as future-proofing for
	 * a cleaner input pipeline (raw decode, lossless intermediate).
	 *
	 * The thumbnail is rendered into an sRGB bitmap regardless of bmp's color space: a DISPLAY_P3 bmp
	 * would embed an APP2 ICC profile (~500-600 bytes) inside the thumbnail JPEG, which combined with
	 * a tight maxBytes budget can cause ExifPatcher.replaceThumbnail to silently reject for APP1
	 * overflow. sRGB matches camera-native thumbnails and keeps the byte budget predictable.
	 *
	 * @param bmp      source bitmap to encode; not recycled
	 * @param maxBytes APP1 budget remaining for the thumbnail (incl. EXIF wrapper overhead the caller
	 *                 already deducted)
	 * @return thumbnail JPEG bytes that fit within maxBytes, or null when no cascade rung succeeds
	 */
	private static byte[] generateThumbnail(Bitmap bmp, int maxBytes)
	{
		if (maxBytes <= 0)
		{
			Log.w(TAG, "Thumbnail budget ≤ 0 — skipping generation");
			return null;
		}
		int width = bmp.getWidth();
		int height = bmp.getHeight();
		// Cascade: dimensions × qualities. First combo whose encoded size fits maxBytes wins. The
		// 512 → 256 maxDim split aligns with Samsung's native thumbnail (~512×640 portrait); the
		// 9-step quality bracket exhausts dim-preserving fallback (q95..q50) before stepping down
		// to 256 maxDim.
		int[] maxDims = { 512, 256 };
		int[] qualities = { 95, 90, 80, 75, 70, 65, 60, 55, 50 };
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
						return result;
					}
				}
			}
			Log.w(TAG, "Thumbnail too large at every cascade rung; returning null (maxBytes="
				+ maxBytes + ")");
			return null;
		}
		catch (Exception | OutOfMemoryError e)
		{
			// Widened to OOM as well as Exception so a thumbnail-side allocation failure (Bitmap.compress
			// on a multi-MP intermediate, or renderSrgbThumb's Canvas.drawBitmap rasterisation) degrades
			// to "save without embedded thumbnail" rather than aborting the whole save with no toast —
			// encodePhase's catch is also Exception-only and OOM would otherwise propagate silently.
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
	 * Patch the JPEG's EXIF metadata with new crop dimensions and the freshly-generated thumbnail, re-injecting
	 * the patched segments into the output bytes. No-op when the source carried no JPEG segment list. Caller
	 * chooses the meta list (full vs HDR-stripped) so the same primary-JPEG bytes can be re-injected on the
	 * rare HDR-drop path without re-compressing the bitmap.
	 *
	 * @param jpegBytes raw JPEG bytes (output of bmp.compress) — must still carry Skia's APP markers
	 * @param meta      original metadata segments; null / empty short-circuits the inject
	 * @param cropW     cropped width to write into IFD0/SubIFD dimension tags
	 * @param cropH     cropped height to write into IFD0/SubIFD dimension tags
	 * @param thumbnail freshly-generated thumbnail JPEG bytes; pass `ExifPatcher.STRIP_IFD1_THUMBNAIL` (a byte[0]
	 *                  sentinel) to strip the source's IFD1 thumbnail when fresh generation failed —
	 *                  null preserves the source's IFD1 thumbnail and is unsafe for cropped / rotated /
	 *                  grafted exports
	 * @return JPEG bytes with metadata replaced
	 */
	private static byte[] injectExifMetadata(byte[] jpegBytes, List<JpegSegment> meta,
		int cropW, int cropH, byte[] thumbnail) throws IOException
	{
		// Skip injection only when there's nothing to inject AND no thumbnail to add. When a fresh
		// thumbnail exists but `meta` carries no EXIF segment (screenshots, generated images, files
		// re-encoded by minimal tools), routing through ExifPatcher.patch lets its synthesise-fresh-EXIF
		// path fire so the saved file gains an IFD1 thumbnail instead of silently dropping the
		// freshly-generated bytes. A collapsing early-return would let screenshots come out without
		// thumbnails (user-reported regression).
		boolean hasRealThumbnail = thumbnail != null && thumbnail.length > 0;
		if ((meta == null || meta.isEmpty()) && !hasRealThumbnail)
		{
			return jpegBytes;
		}
		List<JpegSegment> safeMeta = (meta != null) ? meta : List.of();
		List<JpegSegment> patched = ExifPatcher.patch(safeMeta, cropW, cropH, thumbnail);
		if (patched.isEmpty())
		{
			return jpegBytes;
		}
		return JpegMetadataInjector.inject(jpegBytes, patched);
	}

	/**
	 * Inject EXIF data into a PNG as an eXIf chunk, inserted after IHDR. Takes a JPEG-style APP1 segment (FF
	 * E1 LL LL "Exif\0\0" [TIFF...]) — the form held in state.jpegMeta — unwraps the TIFF body, and delegates
	 * to injectPngExifFromTiff for the actual chunk write. Used by JPEG → PNG export where state.jpegMeta is
	 * the only metadata source.
	 */
	private static byte[] injectPngExif(byte[] png, byte[] exifApp1)
	{
		// exifApp1 = FF E1 LL LL "Exif\0\0" [TIFF data...] eXIf chunk data = just the TIFF data (starting at
		// byte 10)
		if (exifApp1.length <= 10)
		{
			return png;
		}
		int tiffLen = exifApp1.length - 10;
		byte[] tiffData = new byte[tiffLen];
		System.arraycopy(exifApp1, 10, tiffData, 0, tiffLen);
		return injectPngExifFromTiff(png, tiffData);
	}

	/**
	 * Write a PNG eXIf chunk carrying tiffData, inserted after the IHDR chunk. Unlike injectPngExif this takes
	 * raw TIFF bytes directly with no JPEG APP1 u16 cap — used by the PNG → PNG round-trip path so a PNG with
	 * > 64KB EXIF (camera with extensive MakerNote / GPS metadata) keeps its full metadata when re-saved. The
	 * PNG eXIf length field is u31 so the chunk holds anything up to ~2GB.
	 */
	private static byte[] injectPngExifFromTiff(byte[] png, byte[] tiffData)
	{
		int tiffLen = tiffData.length;
		if (tiffLen == 0)
		{
			return png;
		}
		// PNG structure: 8-byte signature, then chunks. Insert eXIf after the first chunk (IHDR).
		if (png.length < 8 + 12)
		{
			return png; // too small
		}

		// Find end of IHDR chunk: signature(8) + length(4) + "IHDR"(4) + data(13) + CRC(4) = 33. Read the
		// length as a long so the high-bit-set u32 case (length ≥ 0x80000000) doesn't sign-flip into a negative
		// int that would slip past the past-EOF guard and trigger an AIOOBE on System.arraycopy below.
		long ihdrLen = ((long) (png[8] & 0xFF) << 24) | ((long) (png[9] & 0xFF) << 16)
				| ((long) (png[10] & 0xFF) << 8) | (png[11] & 0xFF);
		long insertPosLong = 8L + 4L + 4L + ihdrLen + 4L; // after IHDR chunk
		// `>=` rejects an IHDR-only PNG (insertPos == png.length means IHDR's CRC is the last byte and
		// there's no IEND chunk following). Strict PNG decoders reject IEND-less PNGs as truncated;
		// inserting eXIf at the very tail would produce a PNG that ends with eXIf and still no IEND,
		// which is no better than the source. The negative check guards the long-arithmetic wrap on
		// adversarial ihdrLen values near MAX_INT.
		if (insertPosLong >= png.length || insertPosLong < 0)
		{
			return png;
		}
		int insertPos = (int) insertPosLong;

		// Build eXIf chunk: length(4) + "eXIf"(4) + tiffData + CRC(4)
		byte[] chunkType = PngMetadataExtractor.EXIF_CHUNK_TYPE;
		byte[] chunkLenBytes = {
				(byte) (tiffLen >> 24), (byte) (tiffLen >> 16), (byte) (tiffLen >> 8), (byte) (tiffLen)
		};

		// CRC32 covers chunk type + data
		CRC32 crc = new CRC32();
		crc.update(chunkType);
		crc.update(tiffData);
		long crcVal = crc.getValue();
		byte[] crcBytes = {
				(byte) (crcVal >> 24), (byte) (crcVal >> 16), (byte) (crcVal >> 8), (byte) (crcVal)
		};

		// PNG eXIf is u31-uncapped, so on a pathological 2GB-class TIFF the int sum `4+4+tiffLen+4`
		// would overflow negative and NegativeArraySizeException the new byte[] allocation.
		long chunkTotalLong = 4L + 4L + (long) tiffLen + 4L;
		if (chunkTotalLong + png.length > Integer.MAX_VALUE)
		{
			Log.w(TAG, "eXIf chunk total " + chunkTotalLong + " + png " + png.length
				+ " would overflow int; returning png unchanged");
			return png;
		}
		int chunkTotal = (int) chunkTotalLong;
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

	/**
	 * Render `src` into a fresh sRGB ARGB_8888 bitmap at the requested dimensions using a bilinear-filtered Canvas
	 * draw. The output is guaranteed to compress to a plain baseline JPEG with no ICC profile APP2 segment,
	 * regardless of `src`'s color space.
	 *
	 * Recycles `out` and rethrows on any Canvas / Paint / drawBitmap failure (RuntimeException) or
	 * OutOfMemoryError. Without the wrap, `out`'s native pixel buffer would orphan to the GC finalizer
	 * under the same allocation pressure that triggered the OOM.
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
