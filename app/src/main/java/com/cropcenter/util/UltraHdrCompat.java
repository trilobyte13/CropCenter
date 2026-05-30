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

import com.cropcenter.metadata.HdrSignature;
import com.cropcenter.model.CropRender;
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
 * Sub-pixel alignment caveat: the unrotated draw snaps the gainmap's fractional draw offset to the nearest integer
 * and switches to nearest-neighbor sampling. This introduces ≤ 0.5 gainmap-pixel (≤ 2 primary-pixel at quarter-res
 * gainmaps) of spatial drift on crops whose origin isn't grid-aligned with the gainmap lattice, in exchange for
 * pixel-exact gainmap reproduction at the JPEG round-trip noise floor. Bilinear sampling at fractional offsets
 * produced 5–30-level per-pixel softening visible as bright-orange bands in the noise-proof heatmaps over
 * high-contrast features; the snap pushes that softening below the noise floor at a sub-perceptible spatial cost.
 * The rotated branch keeps bilinear (rotation already requires resampling), and the gainmap's lower spatial
 * resolution diffuses the snap into a sub-perceptible shift on the primary's display lattice.
 */
public final class UltraHdrCompat
{
	private static final String TAG = "UltraHdrCompat";

	// Tempfile name prefixes shared by every HDR pipeline stage that writes to cacheDir. The reaper
	// at sweepStaleCacheFiles filters by these prefixes to clean up files left behind by prior runs
	// that crashed before the per-stage cleanup ran (process killed mid-save, OOM, etc.); the
	// writers (here AND CropExporter for its multi-stage encode/inject/compose/reinject/seft pipeline)
	// must use one of these prefixes or the reaper won't catch them. Public so CropExporter can read
	// the same constants instead of duplicating the literal string.
	public static final String TEMPFILE_PREFIX_HDR_SRC = "hdr_src_";
	public static final String TEMPFILE_PREFIX_INPUT_RAW = "input_raw_";

	private UltraHdrCompat() {}

	/**
	 * Produce an Ultra HDR JPEG using the same canvas rendering as CropExporter. See the class Javadoc for the
	 * gain-map sub-pixel alignment trade-off (unrotated branch snaps + nearest-neighbor; rotated keeps bilinear).
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
				gainmapOutput = renderGainmap(gainmapBitmap,
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
			// Gainmap state isn't quite what the HDR-aware encoder expects. The
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
	 * Sweep stale cache files from previous sessions. Two prefixes participate:
	 *   - `hdr_src_<pid>_<nanos>` — written by decodeHdrBitmap / CropExporter for HDR re-decode and
	 *     encode pipeline scratch (5-50 MB on Samsung HDR captures, 200 MB-class on 200 MP HDR).
	 *   - `input_raw_*` — written by SafFileHelper.readUriBytes as the cache-file fallback when a SAF
	 *     URI doesn't resolve to a direct filesystem path (cloud / SAF-only providers). Up to the
	 *     MAX_READ_BYTES cap (128 MB).
	 *
	 * Both prefixes are deleted in `finally` blocks on clean exit; hard process kills (Android OOM
	 * killer, user force-stop, low-memory eviction during the read/decode) bypass both the finally and
	 * the `deleteOnExit()` JVM hook, leaving stale multi-MB blobs that accumulate across sessions —
	 * also a privacy concern since they're full source-image copies.
	 *
	 * Called once at host startup (MainActivity.onCreate) with the cache dir the read/export paths use;
	 * deletes any file matching either prefix regardless of pid (the writers encode pid/nanos to avoid
	 * intra-session collisions, but a different pid at sweep time means "previous app launch — safe to
	 * delete"). Silent on individual delete failure (caller's prior crash may have left a file the
	 * current process can't unlink — better to log + continue than to abort startup).
	 *
	 * @param cacheDir cache directory to sweep; null short-circuits without throwing
	 */
	public static void sweepStaleCacheFiles(File cacheDir)
	{
		if (cacheDir == null || !cacheDir.isDirectory())
		{
			return;
		}
		File[] entries = cacheDir.listFiles(
			(dir, name) -> name.startsWith(TEMPFILE_PREFIX_HDR_SRC)
				|| name.startsWith(TEMPFILE_PREFIX_INPUT_RAW));
		if (entries == null)
		{
			return;
		}
		int deleted = 0;
		for (File entry : entries)
		{
			if (entry.delete())
			{
				deleted++;
			}
			else
			{
				Log.w(TAG, "sweepStaleCacheFiles: failed to delete " + entry.getName());
			}
		}
		if (deleted > 0)
		{
			Log.d(TAG, "sweepStaleCacheFiles: reclaimed " + deleted + " stale cache file(s)");
		}
	}

	/**
	 * Apply EXIF orientation to the decoded bitmap AND to the embedded gainmap. BitmapFactory.decodeFile
	 * does NOT auto-apply EXIF orientation; always apply when orientation > 1 (orientations 2/3/4 don't
	 * swap W/H but still need the matrix). filter=false because EXIF transforms are integer-pixel remaps.
	 *
	 * Bitmap.createBitmap(matrix) propagates the Gainmap reference but does NOT rotate the gainmap
	 * contents (its pixel buffer is opaque to drawBitmap's matrix path). Without an explicit rotation,
	 * primary in display orientation + gainmap in stored orientation makes renderGainmap's scaleX vs
	 * scaleY diverge, squashing the gainmap. Rotate it with the same matrix and re-attach.
	 *
	 * @param current         decoded bitmap; recycled when the rotation produces a new instance
	 * @param exifOrientation EXIF orientation tag (1..8); values outside that range return current
	 *                        unchanged
	 * @return correctly rotated bitmap (may be the same reference as current when orientation ≤ 1 or
	 *         out of range)
	 */
	private static Bitmap applyExifOrientation(Bitmap current, int exifOrientation)
	{
		// Mirror BitmapUtils.applyOrientation's guard: orientations outside [2, 8] are treated as identity.
		// Without the upper bound a malformed value (e.g., 99 from a future caller that bypasses
		// readExifOrientation's [1, 8] clamp) builds an identity matrix via orientationMatrix's default branch,
		// then Bitmap.createBitmap allocates a fresh copy and recycles `current` for nothing. Reject early so
		// the matrix path is genuinely needed.
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
		// Recycle on throw — once rotated is allocated, a subsequent OOM (gainmap createBitmap, new
		// Gainmap, copyGainmapMetadata) would orphan its native pixel buffer to the GC finalizer
		// exactly when the heap is most pressured.
		Bitmap rotatedGainmapBitmap = null;
		try
		{
			if (sourceGainmap != null && sourceGainmapBitmap != null)
			{
				// Apply the SAME matrix to the gainmap bitmap. Cardinal-rotation matrices are lossless
				// integer-pixel remaps for ALPHA_8 / ARGB_8888 gainmap configs, so filter=false matches
				// the primary's lossless rotation. The new Gainmap inherits all tone-mapping metadata
				// from source; only the pixel buffer differs.
				rotatedGainmapBitmap = Bitmap.createBitmap(sourceGainmapBitmap, 0, 0,
					sourceGainmapBitmap.getWidth(), sourceGainmapBitmap.getHeight(), matrix, false);
				// Skip the setGainmap call when createBitmap short-circuited (returned the source
				// itself — per Android docs this can happen "if no transformation is required"; in
				// practice limited to degenerate 1×1 inputs since orient 2..8 matrices are never
				// geometrically identity). `rotated`'s propagated gainmap reference already aligns
				// with the rotated primary in that case; setting a new Gainmap wrapping the same
				// bitmap would leave the wrapped Bitmap reachable from two Gainmap objects, which
				// works but creates a confusing ownership graph for callers inspecting the structure.
				if (rotatedGainmapBitmap != sourceGainmapBitmap)
				{
					Gainmap rotatedGainmap = new Gainmap(rotatedGainmapBitmap);
					copyGainmapMetadata(sourceGainmap, rotatedGainmap);
					rotated.setGainmap(rotatedGainmap);
				}
			}
		}
		catch (RuntimeException | OutOfMemoryError e)
		{
			// Recycle the freshly-allocated rotated bitmap (and the freshly-allocated rotatedGainmapBitmap
			// if it exists and isn't the source itself) before propagating. Caller's `current` reference
			// is still valid — only the rotated allocations we own are reclaimed.
			if (rotated != current)
			{
				rotated.recycle();
			}
			if (rotatedGainmapBitmap != null && rotatedGainmapBitmap != sourceGainmapBitmap)
			{
				rotatedGainmapBitmap.recycle();
			}
			throw e;
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
	 *
	 * @param sourceGainmap source Gainmap providing the tone-mapping parameters (read-only)
	 * @param newGainmap    destination Gainmap; its ratio / gamma / epsilon / display-ratio fields are overwritten
	 *                      from `sourceGainmap`
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
	 * Decode the source JPEG into a Bitmap that preserves its gainmap. BitmapFactory reads HDR gainmaps from
	 * files (not byte arrays), so the bytes are written to a cache file first; the file is deleted as soon as
	 * decodeFile returns (success or not — no leaked-cache-file path).
	 *
	 * Subsampled via a bounds-only pre-pass + BitmapUtils.computeInSampleSize (smallest power-of-2 fitting
	 * MAX_DECODE_PIXELS, 256 MP). This method re-decodes the original from scratch to keep the gainmap, so
	 * without its own cap it would bypass the load-time subsampling and OOM the HDR-save path on a > 256 MP
	 * source. Skia applies inSampleSize to primary AND gainmap consistently, so the gainmap-to-primary ratio
	 * stays correct and downstream renderGainmap math is unaffected.
	 *
	 * @param originalBytes raw source JPEG bytes including the gainmap segments
	 * @param cacheDir      writable scratch dir; a unique cache file is created and immediately deleted inside it
	 * @return decoded Bitmap with gainmap attached (subsampled when over MAX_DECODE_PIXELS); null on decode failure
	 * @throws IOException if writing the source bytes to the cache file fails
	 */
	private static Bitmap decodeHdrBitmap(byte[] originalBytes, File cacheDir) throws IOException
	{
		// Unique filename so concurrent exports never collide on the cache path. Single-threaded today; suffix
		// is cheap insurance against future parallelism.
		File hdrSourceCache = new File(cacheDir,
			TEMPFILE_PREFIX_HDR_SRC + Process.myPid() + "_" + System.nanoTime() + ".jpg");
		// Belt-and-braces against process kill mid-decode: the explicit delete() in the finally below
		// runs on clean exit; deleteOnExit() registers a JVM shutdown hook for graceful Application
		// teardown. Hard kills (OOM killer, force-stop) bypass both — sweepStaleCacheFiles handles those
		// at next launch.
		hdrSourceCache.deleteOnExit();
		try
		{
			try (FileOutputStream fos = new FileOutputStream(hdrSourceCache))
			{
				fos.write(originalBytes);
			}
			BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
			boundsOpts.inJustDecodeBounds = true;
			BitmapFactory.decodeFile(hdrSourceCache.getAbsolutePath(), boundsOpts);
			BitmapFactory.Options opts = new BitmapFactory.Options();
			opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
			opts.inSampleSize = BitmapUtils.computeInSampleSize(
				boundsOpts.outWidth, boundsOpts.outHeight, BitmapUtils.MAX_DECODE_PIXELS);
			if (opts.inSampleSize > 1)
			{
				Log.d(TAG, "HDR re-decode subsampling " + boundsOpts.outWidth + "x"
					+ boundsOpts.outHeight + " at inSampleSize=" + opts.inSampleSize);
			}
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
	 *
	 * @param gainmapCanvas Canvas wrapping the destination gainmap output bitmap; drawn into in place
	 * @param gainmapBitmap source gainmap bitmap to draw
	 * @param gainmapDrawX  draw origin X in destination gainmap pixels (typically negative when cropping into the
	 *                      source)
	 * @param gainmapDrawY  draw origin Y in destination gainmap pixels
	 * @param userRotation  user-applied rotation in degrees
	 * @param gainmapPaint  base Paint; cloned with FILTER_BITMAP disabled for the cardinal-rotation fast path
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
	 *
	 * @param current source Bitmap whose attached Gainmap is inpainted (replaced when the original gainmap bitmap
	 *                is immutable and a mutable copy succeeds)
	 * @param aiMask  AI mask in source stored-orientation coords; null / no-masked-pixels short-circuits to no-op
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
			// pixel buffer would orphan until the GC bitmap finalizer runs.
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
	 * the gainmap's native resolution, typically lower than the primary's).
	 *
	 * @param gainmapBitmap  source gain-map pixel buffer at its native resolution
	 * @param primaryW       primary bitmap width (used to derive gainmap-to-primary scale)
	 * @param primaryH       primary bitmap height
	 * @param srcX           crop origin X on the primary (continuous float — see CropState.getCropImageXFloat)
	 * @param srcY           crop origin Y on the primary
	 * @param cropW          crop width in primary pixels
	 * @param cropH          crop height in primary pixels
	 * @param userRotation   rotation in degrees applied to the primary; mirrored on the gainmap
	 * @return freshly-allocated bitmap at the gain-map's quarter-res cropped output dims
	 */
	private static Bitmap renderGainmap(Bitmap gainmapBitmap,
		int primaryW, int primaryH, float srcX, float srcY, int cropW, int cropH, float userRotation)
	{
		float gainmapScaleX = (float) gainmapBitmap.getWidth() / primaryW;
		float gainmapScaleY = (float) gainmapBitmap.getHeight() / primaryH;
		int gainmapOutputW = Math.max(1, Math.round(cropW * gainmapScaleX));
		int gainmapOutputH = Math.max(1, Math.round(cropH * gainmapScaleY));
		// Natural rounded gain-map dims — do NOT snap to (Wᵣ·k, Hᵣ·k) here even though the primary did.
		// Snapping shrinks the sampled source region (16:9 quarter-res 1000×563 → 992×558 loses 8 cols
		// + 5 rows on the right / bottom), and Android's HDR decoder scales the gain map over the
		// primary's full extent — a shrunk gain map effectively stretches over pixels it wasn't meant
		// for. The AR drift from half-pixel rounding at gainmap-side dims (≤ ~6e-04 worst case at
		// gainmapH ~900-1000; see REQUIREMENTS §4 locked-AR exact-integer realisation) is
		// sub-perceptible after scale-to-fit and is the lesser evil vs spatial HDR misalignment on
		// high-contrast crop edges.

		Bitmap.Config gainmapConfig = gainmapBitmap.getConfig() != null
			? gainmapBitmap.getConfig()
			: Bitmap.Config.ARGB_8888;
		Bitmap gainmapOutput = Bitmap.createBitmap(gainmapOutputW, gainmapOutputH, gainmapConfig);
		// Recycle on throw — a Canvas-init / drawBitmap OOM would strand gainmapOutput and defer native
		// reclaim to the GC bitmap finalizer.
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
				// Snap draw offset + nearest-neighbor: integer primary srcX/srcY produce fractional
				// gainmapDrawX/Y once multiplied by the gainmap/primary scale (srcY=125 × scaleY=0.25
				// → -31.25). Bilinear at a fractional offset softens the gainmap and produces 5-30
				// per-pixel diff levels on high-contrast content (horizon lines, cliff edges, foam).
				// Cost: ≤ 0.5 gainmap pixels of misalignment (sub-perceptible at quarter-res on a
				// 4000-pixel image); benefit: pixel-exact reproduction at the JPEG round-trip noise
				// floor. drawGainmapRotated has the same gate.
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
	 *
	 * @param current      source primary bitmap to crop / rotate; not recycled here
	 * @param srcX         continuous-float crop origin X on `current` (see CropState.getCropImageXFloat)
	 * @param srcY         continuous-float crop origin Y on `current`
	 * @param cropW        crop output width in pixels
	 * @param cropH        crop output height in pixels
	 * @param userRotation user-applied rotation in degrees
	 * @return freshly-allocated DISPLAY_P3 ARGB_8888 bitmap of size (cropW, cropH); ownership transfers to caller
	 */
	private static Bitmap renderPrimary(Bitmap current, float srcX, float srcY,
		int cropW, int cropH, float userRotation)
	{
		Bitmap output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888, true,
			ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
		// Recycle on throw — an OOM during Canvas init or drawCropped strands the partial allocation
		// and defers native pixel-buffer reclaim to the GC finalizer. The compressWithGainmap-level
		// finally only recycles if the assignment completed.
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
