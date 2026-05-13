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

import com.cropcenter.crop.CropRender;
import com.cropcenter.metadata.HdrSignature;
import com.cropcenter.util.AiRegionDetector.AiMask;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Ultra HDR support using Android 14+ Gainmap API.
 *
 * Strategy: decode original with gainmap, render onto a cropW×cropH canvas using the exact same rotation/positioning as
 * CropExporter (Canvas.rotate around image center). Apply the analogous transform to the gainmap bitmap — same EXIF
 * rotation matrix, same scaled draw offset — so the gain map and primary are spatially aligned. Compress → Ultra HDR
 * JPEG.
 *
 * Sub-pixel alignment caveat (round-40 follow-up, Codex round-43 logic-agent finding): the unrotated draw snaps the
 * gainmap's fractional draw offset to the nearest integer and switches to nearest-neighbor sampling. This introduces
 * ≤ 0.5 gainmap-pixel (≤ 2 primary-pixel at quarter-res gainmaps) of spatial drift on crops whose origin isn't grid-
 * aligned with the gainmap lattice, in exchange for pixel-exact gainmap reproduction at the JPEG round-trip noise
 * floor. Bilinear sampling at fractional offsets produced 5–30-level per-pixel softening visible as bright-orange
 * bands in the noise-proof heatmaps over high-contrast features; the snap pushes that softening below the noise
 * floor at a sub-perceptible spatial cost. The rotated branch keeps bilinear (rotation already requires
 * resampling), and the gainmap's lower spatial resolution diffuses the snap into a sub-perceptible shift on the
 * primary's display lattice.
 */
public final class UltraHdrCompat
{
	private static final String TAG = "UltraHdrCompat";

	private UltraHdrCompat() {}

	/**
	 * Produce an Ultra HDR JPEG using the same canvas rendering as CropExporter. The gain map undergoes the
	 * analogous spatial transform as the primary (same EXIF rotation, same scaled draw offset). Alignment is
	 * structurally identical for rotated draws; the unrotated branch snaps the gain map's fractional draw offset
	 * to the nearest integer for pixel-exact reproduction, accepting ≤ 0.5 gainmap-pixel drift as the cost of
	 * avoiding bilinear softening — see class-level Javadoc for the trade-off rationale.
	 *
	 * @param originalBytes   source JPEG bytes (must carry an Ultra HDR gain map)
	 * @param quality         JPEG quality for the final compress
	 * @param cacheDir        scratch directory for the decode-from-file step
	 * @param render          crop geometry (image dims, crop center, crop dims, user rotation)
	 * @param exifOrientation EXIF orientation tag (1..8) read from originalBytes
	 * @param aiMask          AI-modified pixel mask for gain-map inpainting, or null when
	 *                        the source isn't a graft / no AI region was detected
	 * @return Ultra HDR JPEG bytes, or null when the source has no gain map or compress failed
	 */
	public static byte[] compressWithGainmap(byte[] originalBytes, int quality, File cacheDir,
		CropRender render, int exifOrientation, AiMask aiMask)
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
				+ " hasGm=" + current.hasGainmap() + " expected=" + render.imgW() + "x" + render.imgH()
				+ " exif=" + exifOrientation);

			// AI-region inpaint runs BEFORE applyExifOrientation: the mask was computed in source's stored
			// orientation (BitmapFactory.decodeByteArray didn't apply EXIF rotation), and the gainmap
			// bitmap here is also in stored orientation. Inpainting after applyExifOrientation would mean
			// the mask coords no longer match the rotated gainmap. Operating on the bitmap in place via the
			// ALPHA_8 / ARGB path preserves source's single-channel format — re-encoding through
			// Bitmap.compress would force YCbCr 4:2:0 3-channel and break the downstream UHDR recognition.
			inpaintGainmapIfMasked(current, aiMask);

			current = applyExifOrientation(current, exifOrientation);

			// Capture gainmap before the rendering step may drop it.
			Gainmap sourceGainmap = current.hasGainmap() ? current.getGainmap() : null;
			Bitmap gainmapBitmap = sourceGainmap != null ? sourceGainmap.getGainmapContents() : null;

			// Crop origin — matches CropExporter.export() exactly via CropRender's derived srcX/srcY.
			float srcX = render.srcX();
			float srcY = render.srcY();

			output = renderPrimary(current, srcX, srcY, render.cropW(), render.cropH(), render.rotation());

			if (sourceGainmap != null && gainmapBitmap != null)
			{
				gainmapOutput = renderGainmap(sourceGainmap, gainmapBitmap,
					current.getWidth(), current.getHeight(),
					srcX, srcY, render.cropW(), render.cropH(), render.rotation());
				Gainmap newGainmap = new Gainmap(gainmapOutput);
				copyGainmapMetadata(sourceGainmap, newGainmap);
				output.setGainmap(newGainmap);
			}

			current.recycle();
			current = null;

			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			// Bitmap.compress can return false on Skia rejection — typically when the bitmap's attached
			// Gainmap state isn't quite what the HDR-aware encoder expects (round-40 follow-up). The
			// downstream containsHdrgm check below would already catch the partial-buffer case (no hdrgm
			// marker in incomplete bytes → null return), but checking compress's return value directly
			// keeps the diagnostic log clean and avoids paying the substring scan on a known-bad result.
			if (!output.compress(Bitmap.CompressFormat.JPEG, quality, bos))
			{
				Log.w(TAG, "output.compress(JPEG, " + quality + ") returned false — "
					+ "Skia rejected the HDR bitmap; falling back to non-HDR encode");
				return null;
			}
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
			// Recycle every intermediate bitmap on every exit — the success path needs output recycled
			// after compress, the exception path needs ALL of them to avoid native leaks. Null checks guard
			// against partial initialization.
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
	 * Scan data for the XMP "hdrgm" namespace marker — the signature of an Ultra HDR gain map. Delegates to
	 * the metadata-package HdrSignature helper so the pure-Java scan can be reused by GainMapExtractor and
	 * SeftExtractor (both in the metadata package) without dragging in this class's Android Bitmap / Gainmap
	 * surface.
	 *
	 * @param data full file bytes to scan
	 * @return true when the XMP "hdrgm" namespace marker is present anywhere in data
	 */
	public static boolean containsHdrgm(byte[] data)
	{
		return HdrSignature.isHdrSource(data);
	}

	/**
	 * Apply EXIF orientation to the decoded bitmap AND to the embedded gainmap. BitmapFactory.decodeFile does NOT
	 * auto-apply EXIF orientation, and the previous heuristic "autoRotated = decoded dimensions match display
	 * dimensions" silently skipped orientations 2/3/4 (mirror, 180°, vertical flip) because those don't swap W/H
	 * even though they still need the matrix. Always apply when orientation > 1. Uses filter=false: EXIF transforms
	 * are pure mirror / 90° / 180° integer-pixel remaps — lossless, and bilinear would only add softening. Returns
	 * the new bitmap (may be the same reference when the matrix is identity); recycles the old one if it differs.
	 *
	 * Bitmap.createBitmap(matrix) propagates the Gainmap reference to the new bitmap but does NOT rotate the
	 * gainmap contents (the Gainmap pixel buffer is opaque to Canvas.drawBitmap's matrix path). Without an explicit
	 * rotation, primary ends up in display orientation while the gainmap stays in stored orientation, so
	 * renderGainmap's scaleX = gmW/primW vs scaleY = gmH/primH diverge — squashing the gainmap and producing the
	 * catastrophic spatial incoherence panel-3 of the graft-analyze heatmaps shows for orient=6 sources. Rotate
	 * the gainmap with the same matrix and re-attach so both stay coherent (round-40 fix).
	 */
	private static Bitmap applyExifOrientation(Bitmap current, int exifOrientation)
	{
		// Mirror BitmapUtils.applyOrientation's guard: orientations outside [2, 8] are treated as
		// identity. Without the upper bound a malformed value (e.g., 99 from a future caller that
		// bypasses readExifOrientation's [1, 8] clamp) builds an identity matrix via
		// orientationMatrix's default branch, then Bitmap.createBitmap allocates a fresh copy and
		// recycles `current` for nothing. Reject early so the matrix path is genuinely needed.
		if (exifOrientation <= 1 || exifOrientation > 8)
		{
			return current;
		}
		// Capture gainmap references BEFORE rotating the primary. createBitmap on `current`
		// propagates the Gainmap reference (rotated.hasGainmap() returns true), but the underlying
		// gainmap bitmap is the original stored-orient one. We must rotate it explicitly and
		// substitute via setGainmap so the new bitmap carries an orientation-matched gainmap.
		Gainmap sourceGainmap = current.hasGainmap() ? current.getGainmap() : null;
		Bitmap sourceGainmapBitmap = sourceGainmap != null ? sourceGainmap.getGainmapContents() : null;
		Matrix matrix = BitmapUtils.orientationMatrix(exifOrientation);
		Bitmap rotated = Bitmap.createBitmap(current, 0, 0,
			current.getWidth(), current.getHeight(), matrix, false);
		if (sourceGainmap != null && sourceGainmapBitmap != null)
		{
			// Apply the SAME matrix to the gainmap bitmap. Cardinal-rotation matrices are lossless
			// integer-pixel remaps for ALPHA_8 / ARGB_8888 gainmap configs, so filter=false matches
			// the primary's lossless rotation. The new Gainmap inherits all tone-mapping metadata
			// from source; only the pixel buffer differs.
			Bitmap rotatedGainmapBitmap = Bitmap.createBitmap(sourceGainmapBitmap, 0, 0,
				sourceGainmapBitmap.getWidth(), sourceGainmapBitmap.getHeight(), matrix, false);
			// Skip the setGainmap call when createBitmap short-circuited (returned the source itself —
			// per Android docs this can happen "if no transformation is required"; in practice limited
			// to degenerate 1×1 inputs since orient 2..8 matrices are never geometrically identity).
			// `rotated`'s propagated gainmap reference already aligns with the rotated primary in that
			// case; setting a new Gainmap wrapping the same bitmap would leave the wrapped Bitmap
			// reachable from two Gainmap objects, which works but creates a confusing ownership graph
			// (Codex round-40 logic-agent finding).
			if (rotatedGainmapBitmap != sourceGainmapBitmap)
			{
				Gainmap rotatedGainmap = new Gainmap(rotatedGainmapBitmap);
				copyGainmapMetadata(sourceGainmap, rotatedGainmap);
				rotated.setGainmap(rotatedGainmap);
			}
		}
		if (rotated != current)
		{
			// Recycling current releases its pixel buffer AND its attached gainmap's pixel buffer
			// (the original stored-orient sourceGainmapBitmap). The freshly-allocated
			// rotatedGainmapBitmap above is owned by rotated's new Gainmap and survives this recycle.
			current.recycle();
		}
		Log.d(TAG, "EXIF applied: " + rotated.getWidth() + "x" + rotated.getHeight()
			+ " hasGm=" + rotated.hasGainmap()
			+ (rotated.hasGainmap()
				? " gmDim=" + rotated.getGainmap().getGainmapContents().getWidth()
					+ "x" + rotated.getGainmap().getGainmapContents().getHeight()
				: ""));
		return rotated;
	}

	/**
	 * Copy the HDR tone-mapping parameters (ratios, gamma, epsilon, display-ratio thresholds) from source to target
	 * Gainmap. Preserving these verbatim is what keeps the cropped HDR looking identical to the source at the kept
	 * pixels.
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
		newGainmap.setMinDisplayRatioForHdrTransition(sourceGainmap.getMinDisplayRatioForHdrTransition());
	}

	/**
	 * Decode the source JPEG into a Bitmap that preserves its gainmap. BitmapFactory reads HDR gainmaps from files
	 * (not ByteArrays), so we write the bytes to a cache file first. The cache file is deleted as soon as
	 * decodeFile returns, whether it produced a bitmap or not — no "leaked cache file on decode failure" path.
	 */
	private static Bitmap decodeHdrBitmap(byte[] originalBytes, File cacheDir) throws IOException
	{
		// Unique filename so concurrent exports never collide on the cache path. Single-threaded today; suffix
		// is cheap insurance against future parallelism.
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
	 * Rotated gain-map draw. Cardinal rotations at integer-aligned draw offsets are lossless integer-pixel remaps —
	 * disable bilinear so nearest-neighbor reads source pixels verbatim. Fractional draw offsets need bilinear to
	 * match the primary path (which bilinear-samples at sub-pixel offsets).
	 */
	private static void drawGainmapRotated(Canvas gainmapCanvas, Bitmap gainmapBitmap,
		float gainmapDrawX, float gainmapDrawY, float userRotation, Paint gainmapPaint)
	{
		gainmapCanvas.save();
		gainmapCanvas.rotate(userRotation, gainmapDrawX + gainmapBitmap.getWidth() / 2f,
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
	 * Inpaint the gainmap attached to `current` at the AI-mask coordinates, in place when the gainmap bitmap is
	 * mutable, otherwise via a mutable copy that gets substituted back on `current`. No-op when aiMask is
	 * null/empty or the bitmap has no gainmap. Must run BEFORE applyExifOrientation so the mask coordinates (in
	 * source's stored orientation, since BitmapFactory doesn't auto-rotate by EXIF) align with the gainmap bitmap's
	 * coordinates.
	 */
	private static void inpaintGainmapIfMasked(Bitmap current, AiMask aiMask)
	{
		if (aiMask == null || !aiMask.hasMaskedPixels() || !current.hasGainmap())
		{
			return;
		}
		Gainmap sourceGainmap = current.getGainmap();
		Bitmap gainmapBitmap = sourceGainmap.getGainmapContents();
		if (gainmapBitmap == null)
		{
			return;
		}
		if (!gainmapBitmap.isMutable())
		{
			// Skia returns immutable bitmaps for some decode paths. Copy preserves the source config
			// (ALPHA_8 for Samsung's 1-channel gain map) so the downstream encode keeps the right
			// structural format. Bitmap.copy returns null when (Config, isMutable=true) is not supported —
			// most commonly for HARDWARE-config sources on API ≥ 31 (HARDWARE bitmaps are GPU-resident and
			// cannot be made mutable; copy returns null rather than silently downgrading the config).
			// Low-memory conditions can also produce null. Fall through silently — the un-inpainted source
			// gain map is the safe fallback; HDR will render with the original boost instead of the
			// AI-region patched version.
			Bitmap mutableCopy = gainmapBitmap.copy(gainmapBitmap.getConfig(), true);
			if (mutableCopy == null)
			{
				Log.w(TAG, "Bitmap.copy returned null (config=" + gainmapBitmap.getConfig()
					+ "); skipping inpaint, source gain map ships unchanged");
				return;
			}
			// Inpaint BEFORE attaching to current — if inpaintBitmap throws (OOM on a pathological
			// gain map, internal exception in the pixel loop), the mutableCopy is still owned only
			// by this local scope and we can recycle its native buffer cleanly. Attaching first
			// would leave mutableCopy held by current.getGainmap(); current.recycle() in the
			// caller's finally doesn't auto-recycle the attached gain-map bitmap, so the native
			// pixel buffer would orphan until the GC bitmap finalizer runs (Codex round-29 logic).
			try
			{
				GainMapInpainter.inpaintBitmap(mutableCopy, aiMask);
				Gainmap newGainmap = new Gainmap(mutableCopy);
				copyGainmapMetadata(sourceGainmap, newGainmap);
				current.setGainmap(newGainmap);
			}
			catch (RuntimeException | OutOfMemoryError e)
			{
				mutableCopy.recycle();
				throw e;
			}
			return;
		}
		GainMapInpainter.inpaintBitmap(gainmapBitmap, aiMask);
	}

	/**
	 * Render the cropped + rotated gain-map bitmap, spatially aligned with the primary render. Scales the draw
	 * offset by gainmap/primary ratio so the gainmap subregion lines up pixel-for-pixel with the primary crop (at
	 * the gainmap's native resolution, which is typically lower than the primary's).
	 */
	private static Bitmap renderGainmap(Gainmap sourceGainmap, Bitmap gainmapBitmap,
		int primaryW, int primaryH, float srcX, float srcY, int cropW, int cropH, float userRotation)
	{
		float gainmapScaleX = (float) gainmapBitmap.getWidth() / primaryW;
		float gainmapScaleY = (float) gainmapBitmap.getHeight() / primaryH;
		int gainmapOutputW = Math.max(1, Math.round(cropW * gainmapScaleX));
		int gainmapOutputH = Math.max(1, Math.round(cropH * gainmapScaleY));
		// Natural rounded gain-map dims — do NOT snap to (Wᵣ·k, Hᵣ·k) here even though the primary did.
		// Snapping shrinks the sampled source region (16:9 quarter-res 1000×563 → 992×558 loses 8 cols
		// + 5 rows of source gain-map data on the right / bottom edges), and Android's HDR decoder
		// scales the gain map over the primary's full extent — so a shrunk gain map effectively
		// "stretches" the remaining source region to cover pixels it was never meant to. The 0.0002
		// AR drift between primary (exact (Wᵣ·k, Hᵣ·k)) and gain map (round-of-quarter) is
		// imperceptible after the decoder's scale-to-fit, and is the lesser evil vs spatial HDR
		// misalignment on high-contrast crop edges (Codex round-27 F1 reverts the round-24 snap).

		Bitmap.Config gainmapConfig = gainmapBitmap.getConfig() != null
			? gainmapBitmap.getConfig()
			: Bitmap.Config.ARGB_8888;
		Bitmap gainmapOutput = Bitmap.createBitmap(gainmapOutputW, gainmapOutputH, gainmapConfig);
		// Mirror renderPrimary's recycle-on-throw guard: a Canvas-init / drawBitmap OOM mid-method would
		// strand `gainmapOutput` and defer native reclaim to the GC bitmap finalizer. Catch and recycle.
		try
		{
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
				// Even integer-valued primary srcX/srcY produce fractional gainmapDrawX/Y once
				// multiplied by the gainmap/primary scale (e.g., srcY=125 with scaleY=0.25 →
				// drawY=-31.25). Drawing at a fractional offset with FILTER_BITMAP_FLAG bilinear-
				// blends adjacent rows/cols of the gainmap, softening it and producing 5-30 levels of
				// per-pixel diff against the source gainmap on high-contrast content (horizon lines,
				// cliff edges, wave foam) — exactly the bright-orange band pattern the noise-proof
				// heatmaps showed (round-40 follow-up). The mirrored issue in drawGainmapRotated
				// already had an integer-alignment check that uses nearest-neighbor when applicable;
				// the unrotated path was missing this gate. Snap the draw offset to the nearest
				// integer and switch to nearest-neighbor: the cost is ≤ 0.5 gainmap pixels of
				// spatial misalignment (≤ 2 primary pixels at quarter-res gainmap — sub-perceptible
				// on a 4000-pixel-tall image), and the benefit is pixel-exact gainmap reproduction
				// at the JPEG round-trip noise floor instead of the bilinear softening above it.
				int snappedDrawX = Math.round(gainmapDrawX);
				int snappedDrawY = Math.round(gainmapDrawY);
				Paint nearestPaint = new Paint(gainmapPaint);
				nearestPaint.setFilterBitmap(false);
				gainmapCanvas.drawBitmap(gainmapBitmap, snappedDrawX, snappedDrawY, nearestPaint);
			}

			Log.d(TAG, "Gainmap rendered: " + gainmapOutputW + "x" + gainmapOutputH
				+ " (scale " + gainmapScaleX + "x" + gainmapScaleY + ")");
			return gainmapOutput;
		}
		catch (RuntimeException | OutOfMemoryError e)
		{
			gainmapOutput.recycle();
			throw e;
		}
	}

	/**
	 * Render the primary output bitmap via BitmapUtils.drawCropped so the result is byte-identical to what
	 * CropExporter produces — crucial because the primary bytes shipped to the user come from CropExporter, while
	 * the gainmap alignment depends on UltraHdrCompat's primary matching.
	 */
	private static Bitmap renderPrimary(Bitmap current, float srcX, float srcY,
		int cropW, int cropH, float userRotation)
	{
		Bitmap output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888, true,
			ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
		// Recycle `output` if any subsequent step throws — without this, an OOM during Canvas init or
		// drawCropped strands the partial allocation, deferring native pixel-buffer reclaim to the GC
		// finalizer at the exact moment we need immediate reclaim. The compressWithGainmap-level finally
		// would have recycled `output` only if the assignment had completed, but a throw before return
		// means the caller's `output` field is still null. Catch Exception | OutOfMemoryError (Error, not
		// Exception, must be caught explicitly) and rethrow after recycling.
		try
		{
			Canvas canvas = new Canvas(output);
			Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
			BitmapUtils.drawCropped(canvas, current, srcX, srcY, userRotation, paint);
			return output;
		}
		catch (RuntimeException | OutOfMemoryError e)
		{
			output.recycle();
			throw e;
		}
	}
}
