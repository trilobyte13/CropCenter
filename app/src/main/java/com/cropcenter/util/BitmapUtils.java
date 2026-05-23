package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import com.cropcenter.metadata.JpegMarker;
import com.cropcenter.metadata.JpegMarkerWalker;
import com.cropcenter.metadata.TiffTag;

/**
 * Reads EXIF orientation from raw JPEG bytes and rotates the bitmap accordingly. BitmapFactory.decodeByteArray() does
 * NOT auto-apply EXIF rotation.
 */
public final class BitmapUtils
{
	private static final String TAG = "BitmapUtils";
	// Rotation values with magnitude below this threshold (degrees) are treated as 0 for rendering purposes. The
	// ruler exposes 0.01° as its finest tick step (and the horizon detector / precise-rotation dialog round to
	// 0.01° too), so the threshold sits a half-step below that — anything ≥ 0.005° is honored end-to-end (renderer
	// rotates, readout shows, ExportPipeline can't bypass); anything below is below user control and would just
	// burn a bilinear pass for no visible benefit. On a 4000-px-wide image, 0.01° corresponds to a corner shift of
	// ~0.7 px, which is observable on fine vertical/horizontal lines.
	public static final float ROTATION_EPSILON = 0.005f;

	// Decode-pixel cap for the consistent-subsampling BitmapFactory sites (ImageLoadController.applyBytes
	// at load and UltraHdrCompat.decodeHdrBitmap at HDR-save re-decode). 256 MP (= 1 GB ARGB) handles
	// Samsung's "200 MP" max-resolution mode (real pixel count 16384×12288 = 192 mebipixels = 201 million
	// pixels) at inSampleSize=1 with comfortable headroom for future 256 MP sensors. Peak HDR-save working
	// set is ~2.5× the source bitmap (source + UltraHdrCompat.decodeHdrBitmap re-decode + gainmap at 1/4
	// resolution + Bitmap.compress encoder buffer), so peak ≈ 2.5 GB at this cap — fits in ~31% of the
	// 8 GB minimum-spec device's RAM, ~21% on 12 GB (current S25 Ultra), ~16% on 16 GB (future S26).
	// Min-spec is 8 GB RAM: pre-8 GB devices (the 4 GB tier was common pre-2022) are not supported because
	// the peak working set during HDR save at the lower cap would still crowd the OS into the low-memory
	// killer. minSdk=35 (Android 15) already requires post-2021 hardware in practice, so this cap is
	// codifying what the OS version cutoff implies.
	public static final int MAX_DECODE_PIXELS = 256 * 1024 * 1024;

	// Display-proxy cap. Used by createDisplayProxy to derive a downsampled bitmap from the full source for
	// the editor's render path AND for HorizonDetector.detectFromPaintedRegion's edge-map compute. 16 MP
	// (4096×4096 class = ~64 MB ARGB) is sized to roughly match the highest current phone screen
	// resolution (Samsung S25 Ultra: 3120×1440 = 4.5 MP; hypothetical 4K phone: 3840×2160 = 8.3 MP) with
	// 2-4× headroom for editor zoom. Larger proxies waste GPU upload bandwidth + texture memory without
	// visible quality benefit at typical zoom levels since the mipmap chain samples a level matched to
	// the on-screen render size anyway. At zoom ≥ 4 the renderer switches to the full source for
	// pixel-grid accuracy. HorizonDetector at 16 MP allocates ~192 MB of float[] working set (vs 2.3 GB
	// at 192 MP source) — ~12× faster Hough vote. Save paths (CropExporter, UltraHdrCompat) bypass the
	// proxy entirely and read state.getSourceImage() directly, so output resolution stays at source res
	// regardless of display subsampling.
	public static final int MAX_DISPLAY_PIXELS = 16 * 1024 * 1024;

	// Pixel-count ceiling above which the renderer keeps using the display proxy even at zoom ≥ 4.
	// Background: at zoom ≥ 4 EditorRenderer normally switches from the proxy to the full source so the
	// pixel-grid overlay (scale ≥ 6) lands on true source-pixel boundaries. For sub-cap sources this is a
	// one-shot GPU texture upload that the renderer can absorb. For 200 MP class sources the upload would
	// be ~800 MB ARGB — past most phone GPUs' addressable texture memory and certain to thrash the texture
	// cache (visible as a multi-second freeze when crossing zoom = 4) or fail allocation outright. At
	// 64 MP (~256 MB ARGB) the upload still costs but is recoverable on every supported device. Above the
	// cap the user sees the 16 MP proxy interpolated at very high zoom — soft pixels rather than the
	// crisp pixel grid, but a working app rather than a freeze + OOM. The pixel grid path itself reads
	// source.getWidth/getHeight unconditionally so the grid line spacing always matches source coords;
	// only the bitmap-render path is gated by this cap.
	public static final int MAX_SOURCE_RENDER_PIXELS = 64 * 1024 * 1024;

	// Companion per-axis cap. A panorama like 32767×1000 sits under MAX_SOURCE_RENDER_PIXELS (32 MP < 64
	// MP) but its width is past every supported GPU's GL_MAX_TEXTURE_SIZE (typically 8192–16384). Without
	// this guard the source-switch would hand Skia a bitmap whose texture upload silently fails or
	// allocates a fallback path and stalls. Numerically equal to MAX_PROXY_AXIS — the proxy is sized so
	// its dimensions fit in this axis cap by construction, but the SOURCE is not pre-shrunk and must be
	// gated explicitly before the renderer asks the GPU to bind it.
	public static final int MAX_SOURCE_RENDER_AXIS = 16384;

	// Per-axis dimension cap on the display proxy. Independent of MAX_DISPLAY_PIXELS — guards against
	// pathological aspect ratios (1×100M attacker input) that would otherwise produce a 1×16M proxy.
	// Bitmap.createBitmap throws IllegalArgumentException at dim ≥ 32768 (Android's internal limit),
	// and even before that, Skia's HARDWARE-config copy rejects dims past the device's GL_MAX_TEXTURE_SIZE
	// (8192-16384 on modern Android). 16384 picks the wider end of that range — fits flagship-class GPUs
	// (Adreno 730+, Mali-G77+) and is power-of-2-clean. For real camera aspect ratios (4:3, 16:9, 3:2,
	// 1:1) at MAX_DISPLAY_PIXELS = 16 MP, neither axis comes close to this cap (~4096-5000 px max).
	static final int MAX_PROXY_AXIS = 16384;

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
	 * on very-large sources (Samsung's "200 MP" mode produces 16384×12288 captures ≈ 192 mebipixels =
	 * ~800 MB ARGB). The cap is the `MAX_DECODE_PIXELS` constant (256 MP, ~1 GB ARGB), comfortably above
	 * a 200 MP capture so those sources decode at `inSampleSize=1` (no quality loss); subsampling only
	 * kicks in for hypothetical > 256 MP sources (future sensor generations). The trade-off when
	 * sampleSize > 1 is that the saved crop uses the subsampled bitmap as its source, so output
	 * resolution scales down — preferable to instant OOM.
	 *
	 * Android's BitmapFactory.Options.inSampleSize requires a power of 2 (any other value is rounded down
	 * to the nearest power of 2 internally), so this helper produces 1, 2, 4, 8, ... rather than the exact
	 * minimum ratio.
	 *
	 * @param outWidth  decoded image width before subsampling (from BitmapFactory bounds pre-pass)
	 * @param outHeight decoded image height before subsampling
	 * @param maxPixels total subsampled-pixel budget (e.g., MAX_DECODE_PIXELS = 256_000_000)
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
	 * Compute the proxy's (width, height) for a source of the given dimensions, landing the resulting pixel
	 * count at-or-below MAX_DISPLAY_PIXELS while keeping each axis ≥ 1. Returns null when the source already
	 * fits (caller should alias the source in that case rather than allocating). Separated from
	 * createDisplayProxy so the pure-math piece is unit-testable without an Android Bitmap (the createBitmap
	 * call in createDisplayProxy is a JNI hop that fails outside the Android runtime).
	 *
	 * Scale factor is `sqrt(MAX_DISPLAY_PIXELS / sourcePixels)` so the cap is treated as a pixel-count budget
	 * (not a per-axis budget). For a 16384×12288 (192 MP) source at MAX_DISPLAY_PIXELS = 16 MP, the per-axis
	 * scale is ~0.289 giving dims ~4738×3553 (~16 MP, just under the cap). For typical camera aspect ratios
	 * (4:3, 16:9, 3:2, square) the per-axis Math.round biases within ±1 px of the pixel-count-optimal
	 * dimensions, preserving aspect ratio to within ~0.1% — the editor renders via one width-derived
	 * proxyToSource scale, so aspect-ratio drift translates directly to vertical/horizontal pixel stretch.
	 *
	 * Pathological aspect-ratio guard: for sources where one axis scales to less than 0.5 pixels (e.g.,
	 * 1×100M attacker input), the Math.max(1, ...) floor bumps that axis up from 0 to 1 and leaves the
	 * other axis at its scaled value — which would push the product past MAX_DISPLAY_PIXELS (a 1×100M
	 * source would give 1×40M = 40 MP, blowing the 16 MP cap by 2.5×). The product re-check below shrinks
	 * the dominant axis until the cap holds. The resulting proxy's aspect ratio diverges from the source
	 * for such pathological inputs, which is acceptable — no real camera produces 1×100M images, and the
	 * cap is the harder guarantee than aspect preservation.
	 *
	 * @param srcW source bitmap width in pixels (must be positive)
	 * @param srcH source bitmap height in pixels (must be positive)
	 * @return 2-element int array {dstW, dstH} when downscaling is needed (guaranteed dstW*dstH ≤
	 *         MAX_DISPLAY_PIXELS and dstW ≥ 1 and dstH ≥ 1), OR null when the source already fits within
	 *         MAX_DISPLAY_PIXELS (caller must alias the source rather than allocate)
	 */
	public static int[] computeProxyDims(int srcW, int srcH)
	{
		long srcPixels = (long) srcW * srcH;
		if (srcPixels <= MAX_DISPLAY_PIXELS)
		{
			return null;
		}
		double scale = Math.sqrt((double) MAX_DISPLAY_PIXELS / (double) srcPixels);
		int dstW = Math.max(1, (int) Math.round(srcW * scale));
		int dstH = Math.max(1, (int) Math.round(srcH * scale));
		// Re-check the product after the Math.max-with-1 floor: for very high-aspect-ratio sources the
		// floor bumps one axis up from 0 and leaves the other unchanged, pushing the product past the
		// cap. Shrink the dominant axis until the product fits. Long arithmetic — dstW*dstH can be ~40 MP
		// (well within int) but the multiply uses long to mirror the srcPixels computation pattern.
		long proxyPixels = (long) dstW * dstH;
		if (proxyPixels > MAX_DISPLAY_PIXELS)
		{
			if (dstW >= dstH)
			{
				dstW = Math.max(1, (int) (MAX_DISPLAY_PIXELS / dstH));
			}
			else
			{
				dstH = Math.max(1, (int) (MAX_DISPLAY_PIXELS / dstW));
			}
		}
		// Per-axis dimension cap, applied as a SECOND uniform downscale to both axes so the proxy
		// stays geometrically similar to the source. Independent per-axis clamping would distort:
		// a 32767×1000 source (just under Bitmap's 32768 max-dim) computes to dstW = 23428,
		// dstH = 715 after the uniform downscale (aspect 32.77:1 preserved); independent clamp
		// would give 16384×715 (aspect 22.91:1) which the renderer's width-derived proxyToSource
		// (= source.getWidth() / proxy.getWidth()) would then stretch vertically by ~1.43× when
		// drawing the proxy — overlays + crop geometry computed in source coords would misalign
		// with the visible bitmap. EditorRenderer and AutoRotateBinder both rely on this single
		// width-derived ratio being correct for the Y axis too, so the proxy must remain a uniform
		// scale of the source. For pathological aspect ratios where the source's minor axis already
		// rounded to 1 in the pixel-budget step, the resulting proxy aspect inevitably diverges
		// from the source's (the 1×100M case produces a 1×16384 proxy, aspect 1:16384 vs source
		// 1:100M) — but such inputs aren't allocatable as Bitmaps in the first place (the source
		// would exceed Bitmap.createBitmap's 32768 max-dim during decode), so the "wrong" aspect
		// here only matters as a non-crash defense, not for visible rendering.
		int maxAxis = Math.max(dstW, dstH);
		if (maxAxis > MAX_PROXY_AXIS)
		{
			double axisScale = (double) MAX_PROXY_AXIS / maxAxis;
			dstW = Math.max(1, (int) (dstW * axisScale));
			dstH = Math.max(1, (int) (dstH * axisScale));
		}
		return new int[] { dstW, dstH };
	}

	/**
	 * Derive a display-proxy bitmap downsampled to fit within MAX_DISPLAY_PIXELS, preserving aspect ratio. When
	 * the source already fits, returns the source reference directly (no allocation, no copy) — the caller
	 * treats source and proxy as interchangeable and must NOT recycle the proxy independently in that case.
	 * Used by ImageLoadController on the bg thread immediately after the EXIF-rotation pass; the resulting
	 * proxy is installed alongside the source via CropState.setSourceImage(source, display) so
	 * EditorRenderer can render the smaller buffer per frame while save paths still pull the full source.
	 *
	 * For sources requiring downscale, the result is a Bitmap.Config.HARDWARE bitmap: pixel data lives in
	 * GPU memory permanently and per-frame canvas.drawBitmap is a zero-upload texture-bind + draw. Without
	 * HARDWARE the Skia renderer must re-upload the ARGB pixel data from native heap to the GPU on any
	 * texture-cache eviction (driven by other apps' memory pressure or even our own source bitmap's
	 * cache footprint), causing per-frame upload spikes that read as gesture lag. With HARDWARE, the
	 * texture is uploaded once and bound for the lifetime of the bitmap. Tradeoff: HARDWARE bitmaps are
	 * immutable and getPixels() returns null on them — AutoRotateBinder must copy the proxy back to
	 * ARGB_8888 before passing it to HorizonDetector.detectFromPaintedRegion. That one-shot GPU→CPU
	 * readback (a few hundred ms on a 16 MP HARDWARE bitmap) only fires when the user invokes auto-rotate,
	 * not per render frame. If the HARDWARE copy fails (rare — typically GPU memory exhausted), the
	 * fallback is the ARGB scaled bitmap with mipmaps (slower per frame but functional).
	 *
	 * Enables `setHasMipMap(true)` on the result so the Skia renderer maintains a mipmap chain (1, 1/4,
	 * 1/16, ... pixel counts) — at typical fit-to-view editor zoom the GPU samples the appropriate ~2-8 MP
	 * mip level via HW-accelerated trilinear filtering instead of supersampling. Memory overhead: +33%
	 * from the geometric series of mip levels. Then prepareToDraw() to hint the renderer to start the GPU
	 * upload + mipmap generation immediately on a worker thread, so the first frame after load doesn't
	 * pay the upload latency on the UI thread. Both hints are valid on HARDWARE bitmaps (the underlying
	 * GPU-texture renderer honors them).
	 *
	 * Alias case (source already fits MAX_DISPLAY_PIXELS): returns the source unchanged, ARGB_8888 mutable.
	 * Save paths use the source via CPU drawCropped (no GPU sampling), so leaving it ARGB costs nothing on
	 * the save side. HorizonDetector reads pixels from the aliased source directly — no readback needed.
	 *
	 * Side effects on aliased source: `setHasMipMap(true)` and `prepareToDraw()` are invoked on the proxy
	 * unconditionally — in the alias case the proxy IS the source, so the source bitmap's mipmap flag is
	 * flipped to true and its texture upload is hinted. Both are harmless for save-path consumers (which
	 * draw via CPU canvas and ignore the GPU-side flags) but documented because callers passing in a source
	 * expecting an unmutated reference must understand the contract: the source after this call is the same
	 * object with mipmaps enabled and a GPU upload pending, not a pristine pre-call copy.
	 *
	 * @param source full-resolution bitmap to be downsampled; never null. Mutated in-place when aliased (see
	 *               "Side effects on aliased source" above)
	 * @return display proxy bitmap (HARDWARE config when downscaled; ARGB_8888 source reference when
	 *         aliased), with setHasMipMap(true) and prepareToDraw() invoked
	 */
	public static Bitmap createDisplayProxy(Bitmap source)
	{
		int[] dims = computeProxyDims(source.getWidth(), source.getHeight());
		Bitmap proxy;
		if (dims == null)
		{
			// Already fits — aliasing source as the proxy avoids a fresh allocation on the common case of
			// a sub-16-MP capture (every phone camera at default resolution).
			proxy = source;
		}
		else
		{
			// Two-step: first downscale to ARGB via createScaledBitmap (bilinear, smooth), then copy that
			// to HARDWARE config for GPU-resident rendering. The intermediate ARGB is recycled — it served
			// only as the source of pixel data for the HARDWARE copy. If HARDWARE allocation fails (the
			// copy returns null OR throws — Skia / driver faults on GPU memory pressure surface as
			// unchecked RuntimeException, and a 16 MP ARGB allocation can hit native-heap OOM), fall back
			// to keeping the ARGB scaled bitmap: still benefits from mipmaps + prepareToDraw, just pays
			// texture-upload cost on cache misses. Mirrors AutoRotateBinder.tryReadbackArgb's
			// RuntimeException + OutOfMemoryError catch — Throwable would also catch LinkageError /
			// ThreadDeath which we want to propagate.
			Bitmap scaledArgb = Bitmap.createScaledBitmap(source, dims[0], dims[1], true);
			Bitmap proxyHardware;
			try
			{
				proxyHardware = scaledArgb.copy(Bitmap.Config.HARDWARE, false);
			}
			catch (RuntimeException | OutOfMemoryError e)
			{
				Log.w(TAG, "createDisplayProxy: HARDWARE copy threw — using ARGB scaled bitmap", e);
				proxyHardware = null;
			}
			if (proxyHardware != null)
			{
				scaledArgb.recycle();
				proxy = proxyHardware;
			}
			else
			{
				proxy = scaledArgb;
			}
		}
		proxy.setHasMipMap(true);
		proxy.prepareToDraw();
		return proxy;
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
			// Fractional srcX / srcY — draw at the float offset so Android's renderer bilinear-samples
			// at sub-pixel positions.
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
		if ((jpeg[0] & 0xFF) != JpegMarker.PREFIX || (jpeg[1] & 0xFF) != JpegMarker.SOI)
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
				// Segment length must include the 2 length bytes themselves (JPEG spec). Zero or one
				// would advance off by 0 or 1 instead of the real segment size.
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
				// a small tiffStart wraps to a small positive int that passes both bounds checks on
				// the truncated value, letting the IFD entry walk read garbage as tag/type/value and
				// rotate pixels.
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
