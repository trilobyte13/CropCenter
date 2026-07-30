package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import com.cropcenter.metadata.JpegMarker;
import com.cropcenter.metadata.JpegMarkerWalker;
import com.cropcenter.metadata.TiffIfd0;

import java.util.Optional;

/**
 * Reads EXIF orientation from raw JPEG bytes and rotates the bitmap accordingly. BitmapFactory.decodeByteArray() does
 * NOT auto-apply EXIF rotation.
 */
public final class BitmapUtils
{
	private static final String TAG = "BitmapUtils";
	// Rotation values with magnitude below this threshold (degrees) are treated as 0 for rendering purposes. The
	// ruler exposes 0.01° as its finest tick step (and the horizon detector rounds to 0.01° too), so the threshold
	// sits a half-step below that — anything ≥ 0.005° is honored end-to-end (renderer rotates, readout shows,
	// ExportPipeline can't bypass); anything below is below user control and would just burn a bilinear pass for no
	// visible benefit. On a 4000-px-wide image, 0.01° corresponds to a corner shift of ~0.7 px, which is observable
	// on fine vertical/horizontal lines.
	public static final float ROTATION_EPSILON = 0.005f;

	// Decode-pixel cap for the consistent-subsampling BitmapFactory sites (ImageLoadController.applyBytes at load
	// and UltraHdrCompat.decodeHdrBitmap at HDR-save re-decode). 256 MP (= 1 GB ARGB) handles Samsung's "200 MP"
	// max-resolution mode (real pixel count 16384×12288 = 192 mebipixels = 201 million pixels) at inSampleSize=1
	// with comfortable headroom for future 256 MP sensors. Peak HDR-save working set is ~2.5× the source bitmap
	// (source + UltraHdrCompat.decodeHdrBitmap re-decode + gainmap at 1/4 resolution + Bitmap.compress encoder
	// buffer), so peak ≈ 2.5 GB at this cap — fits in ~31% of the 8 GB minimum-spec device's RAM, ~21% on 12 GB
	// (current S25 Ultra), ~16% on 16 GB (future S26). Min-spec is 8 GB RAM: pre-8 GB devices (the 4 GB tier was
	// common pre-2022) are not supported because the peak working set during HDR save at the lower cap would still
	// crowd the OS into the low-memory killer. minSdk=35 (Android 15) already requires post-2021 hardware in
	// practice, so this cap is codifying what the OS version cutoff implies.
	public static final int MAX_DECODE_PIXELS = 256 * 1024 * 1024;

	// Display-proxy cap. Used by createDisplayProxy to derive a downsampled bitmap from the full source for the
	// editor's render path AND for HorizonDetector.detectFromPaintedRegion's edge-map compute. 16 MP (4096×4096
	// class = ~64 MB ARGB) is sized to roughly match the highest current phone screen resolution (Samsung S25
	// Ultra: 3120×1440 = 4.5 MP; hypothetical 4K phone: 3840×2160 = 8.3 MP) with 2-4× headroom for editor zoom.
	// Larger proxies waste GPU upload bandwidth + texture memory without visible quality benefit at typical zoom
	// levels since the mipmap chain samples a level matched to the on-screen render size anyway. At zoom ≥ 4 the
	// renderer switches to the full source for pixel-grid accuracy. HorizonDetector at 16 MP allocates ~192 MB of
	// float[] working set (vs 2.3 GB at 192 MP source) — ~12× faster Hough vote. Save paths (CropExporter,
	// UltraHdrCompat) bypass the proxy entirely and read state.getSourceImage() directly, so output resolution
	// stays at source res regardless of display subsampling.
	public static final int MAX_DISPLAY_PIXELS = 16 * 1024 * 1024;

	// Per-axis dimension cap on the display proxy. Independent of MAX_DISPLAY_PIXELS — guards against pathological
	// aspect ratios (1×100M attacker input) that would otherwise produce a 1×16M proxy. Bitmap.createBitmap throws
	// IllegalArgumentException at dim ≥ 32768 (Android's internal limit), and even before that, Skia's
	// HARDWARE-config copy rejects dims past the device's GL_MAX_TEXTURE_SIZE (8192-16384 on modern Android). 16384
	// picks the wider end of that range — fits flagship-class GPUs (Adreno 730+, Mali-G77+) and is
	// power-of-2-clean. For real camera aspect ratios (4:3, 16:9, 3:2, 1:1) at MAX_DISPLAY_PIXELS = 16 MP, neither
	// axis comes close to this cap (~4096-5000 px max).
	static final int MAX_PROXY_AXIS = 16384;

	// Companion per-axis cap. A panorama like 32767×1000 sits under MAX_SOURCE_RENDER_PIXELS (32 MP < 64 MP) but
	// its width is past every supported GPU's GL_MAX_TEXTURE_SIZE (typically 8192–16384). Without this guard the
	// source-switch would hand Skia a bitmap whose texture upload silently fails or allocates a fallback path and
	// stalls. Numerically equal to MAX_PROXY_AXIS — the proxy is sized so its dimensions fit in this axis cap by
	// construction, but the SOURCE is not pre-shrunk and must be gated explicitly before the renderer asks the GPU
	// to bind it.
	public static final int MAX_SOURCE_RENDER_AXIS = 16384;

	// Pixel-count ceiling on the zoom-≥-4 whole-source GPU upload. At zoom ≥ 4 EditorRenderer leaves the display
	// proxy for true source pixels: a source within this cap (and MAX_SOURCE_RENDER_AXIS) is uploaded whole — at 64
	// MP (~256 MB ARGB) a one-shot cost every supported device absorbs. Past either cap the renderer instead draws
	// only the visible region as a viewport-bounded 1:1 tile cut from the software source
	// (EditorRenderer.drawSourceTile, reused across frames via tileCovers), so a 200 MP capture (~800 MB ARGB, past
	// most phone GPUs' addressable texture memory) still pixel-peeks crisply without an over-budget texture upload.
	public static final int MAX_SOURCE_RENDER_PIXELS = 64 * 1024 * 1024;

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
	 * Compute the largest power-of-2 inSampleSize that fits (outWidth × outHeight) within `maxPixels` total after
	 * subsampling — i.e., the smallest sample size that ensures `(outWidth / s) × (outHeight / s) <= maxPixels`.
	 * Returns 1 when the un-subsampled image already fits; doubles until the constraint holds.
	 *
	 * Used by ImageLoadController.applyBytes and UltraHdrCompat.decodeHdrBitmap to bound peak decode memory on
	 * very-large sources (Samsung's "200 MP" mode produces 16384×12288 captures ≈ 192 mebipixels = ~800 MB ARGB).
	 * The cap is the `MAX_DECODE_PIXELS` constant (256 MP, ~1 GB ARGB), comfortably above a 200 MP capture so those
	 * sources decode at `inSampleSize=1` (no quality loss); subsampling only kicks in for hypothetical > 256 MP
	 * sources (future sensor generations). The trade-off when sampleSize > 1 is that the saved crop uses the
	 * subsampled bitmap as its source, so output resolution scales down — preferable to instant OOM.
	 *
	 * Android's BitmapFactory.Options.inSampleSize requires a power of 2 (any other value is rounded down to the
	 * nearest power of 2 internally), so this helper produces 1, 2, 4, 8, ... rather than the exact minimum ratio.
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
	 * Compute the proxy's (width, height) for a source of the given dimensions, landing the resulting pixel count
	 * at-or-below MAX_DISPLAY_PIXELS while keeping each axis ≥ 1. Returns empty when the source already fits
	 * (caller should alias the source in that case rather than allocating). Separated from createDisplayProxy so
	 * the pure-math piece is unit-testable without an Android Bitmap (the createBitmap call in createDisplayProxy
	 * is a JNI hop that fails outside the Android runtime).
	 *
	 * Scale factor is `sqrt(MAX_DISPLAY_PIXELS / sourcePixels)` so the cap is treated as a pixel-count budget (not
	 * a per-axis budget). For a 16384×12288 (192 MP) source at MAX_DISPLAY_PIXELS = 16 MP, the per-axis scale is
	 * ~0.2887 giving rounded dims 4730×3547 = 16,777,310 — 94 px OVER the cap — so the product re-check trims the
	 * dominant axis by 1 to 4729×3547 (under the cap). For typical camera aspect ratios (4:3, 16:9, 3:2, square)
	 * the per-axis Math.round biases within ±1 px of the pixel-count-optimal dimensions, preserving aspect ratio to
	 * within ~0.1% — the editor renders via one width-derived proxyToSource scale, so aspect-ratio drift translates
	 * directly to vertical/horizontal pixel stretch.
	 *
	 * The product re-check fires in two shapes. Normal aspect ratios: Math.round can land the product just past the
	 * cap (the 192 MP 4:3 case above), and the trim is bounded to 1 px on one axis — below render-alignment noise.
	 * Pathological aspect ratios: for sources where one axis scales to less than 0.5 pixels (e.g., 1×100M attacker
	 * input), the Math.max(1, ...) floor bumps that axis up from 0 to 1 and leaves the other axis at its scaled
	 * value, pushing the product far past MAX_DISPLAY_PIXELS (a 1×100M source would give 1×40M = 40 MP); the
	 * re-check shrinks the dominant axis until the cap holds, and the resulting aspect divergence from the source
	 * is acceptable — no real camera produces 1×100M images, and the cap is the harder guarantee than aspect
	 * preservation.
	 *
	 * @param srcW source bitmap width in pixels (must be positive)
	 * @param srcH source bitmap height in pixels (must be positive)
	 * @return 2-element int array {dstW, dstH} when downscaling is needed (guaranteed dstW*dstH ≤
	 *         MAX_DISPLAY_PIXELS and dstW ≥ 1 and dstH ≥ 1), OR empty when the source already fits within
	 *         MAX_DISPLAY_PIXELS (caller must alias the source rather than allocate)
	 */
	public static Optional<int[]> computeProxyDims(int srcW, int srcH)
	{
		long srcPixels = (long) srcW * srcH;
		if (srcPixels <= MAX_DISPLAY_PIXELS)
		{
			return Optional.empty();
		}
		double scale = Math.sqrt((double) MAX_DISPLAY_PIXELS / (double) srcPixels);
		int dstW = Math.max(1, (int) Math.round(srcW * scale));
		int dstH = Math.max(1, (int) Math.round(srcH * scale));
		// Re-check the product after the Math.max-with-1 floor: for very high-aspect-ratio sources the floor
		// bumps one axis up from 0 and leaves the other unchanged, pushing the product past the cap. Shrink the
		// dominant axis until the product fits. Long arithmetic — dstW*dstH can be ~40 MP (well within int) but
		// the multiply uses long to mirror the srcPixels computation pattern.
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
		return Optional.of(new int[] { dstW, dstH });
	}

	/**
	 * Derive a display-proxy bitmap downsampled to fit within MAX_DISPLAY_PIXELS, preserving aspect ratio. When the
	 * source already fits, returns the source reference directly (no copy) — caller treats source and proxy as
	 * interchangeable and must NOT recycle the proxy independently in that case. Used by ImageLoadController after
	 * the EXIF-rotation pass; installed alongside the source via CropState.setSourceImage(source, display) so
	 * EditorRenderer renders the smaller buffer per frame while save paths pull the full source.
	 *
	 * When downscaled, the result is Bitmap.Config.HARDWARE: pixels live in GPU memory and per-frame drawBitmap is
	 * a zero-upload texture-bind. Without HARDWARE, Skia re-uploads ARGB from native heap on any texture-cache
	 * eviction, causing per-frame upload spikes that read as gesture lag. Tradeoff: HARDWARE is immutable and
	 * getPixels() returns null, so AutoRotateBinder copies the proxy back to ARGB_8888 before HorizonDetector — a
	 * one-shot GPU→CPU readback that only fires on auto-rotate, not per frame. If the HARDWARE copy fails (GPU
	 * memory exhausted), falls back to an ARGB scaled bitmap with mipmaps.
	 *
	 * setHasMipMap(true) lets the GPU sample an appropriate mip level (trilinear) at fit-to-view zoom instead of
	 * supersampling (+33% memory from the mip chain); prepareToDraw() hints an early off-thread upload so the first
	 * post-load frame doesn't pay upload latency on the UI thread.
	 *
	 * Side effect in the alias case (source already fits): the proxy IS the source, so these two hints mutate the
	 * source bitmap (mipmap flag on, GPU upload pending). Harmless for save-path CPU consumers, but a caller
	 * expecting an unmutated source reference must know it isn't pristine after this call.
	 *
	 * @param source full-resolution bitmap to downsample; never null. Mutated in-place when aliased (see above)
	 * @return display proxy (HARDWARE when downscaled; ARGB_8888 source reference when aliased), with
	 *         setHasMipMap(true) and prepareToDraw() invoked
	 */
	public static Bitmap createDisplayProxy(Bitmap source)
	{
		Optional<int[]> proxyDims = computeProxyDims(source.getWidth(), source.getHeight());
		Bitmap proxy;
		if (proxyDims.isEmpty())
		{
			// Already fits — aliasing source as the proxy avoids a fresh allocation on the common case of a
			// sub-16-MP capture (every phone camera at default resolution).
			proxy = source;
		}
		else
		{
			int[] dims = proxyDims.orElseThrow();
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

			// Cardinal rotations are pure integer-pixel remaps ONLY when srcX / srcY are integer-aligned
			// AND the angle passes isLosslessCardinalRotation's parity gate (±90° / ±270° need srcW + srcH
			// even — see its Javadoc for the half-pixel math) — then nearest-neighbor inherits source
			// pixels verbatim. Fractional srcX / srcY, mixed-parity 90°/270°, and non-cardinal angles all
			// bilinear-sample, matching the preview: soft but correctly positioned beats sharp but
			// half-pixel offset.
			boolean integerAligned = srcX == Math.floor(srcX) && srcY == Math.floor(srcY);
			if (integerAligned && isLosslessCardinalRotation(rotation, src.getWidth(), src.getHeight()))
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
			// Fractional srcX / srcY — draw at the float offset so Android's renderer bilinear-samples at
			// sub-pixel positions.
			canvas.drawBitmap(src, drawX, drawY, paint);
		}
	}

	/**
	 * True when rotating a srcW × srcH bitmap by rotationDegrees around the crop-window center is a lossless
	 * integer-pixel remap — the single chokepoint predicate for every nearest-neighbor cardinal-rotation gate
	 * (drawCropped's rotated branch and UltraHdrCompat's gain-map draw). Angles match within ROTATION_EPSILON
	 * (strict less-than).
	 *
	 * Parity math: with an integer-aligned draw offset, a 0°/180° center rotation maps each source pixel center
	 * (drawX + i + 0.5) to drawX + srcW − i − 0.5 per axis — a pixel center for ANY dims, because only that axis's
	 * own integer dimension enters the mapping. A ±90°/±270° rotation swaps the axes, so a source pixel center (i +
	 * 0.5, j + 0.5) maps to destination X = drawX + (srcW + srcH)/2 − j − 0.5 — a pixel center only when (srcW +
	 * srcH)/2 is an integer. An odd srcW + srcH (mixed parity) puts the whole rotated grid on half-pixel offsets,
	 * so nearest-neighbor would sample exactly on pixel boundaries and ship a half-pixel-shifted copy; those inputs
	 * must fall back to the bilinear path — soft but correctly positioned beats sharp but offset.
	 *
	 * The caller must separately verify the draw offset is integer-aligned; this predicate covers only the angle +
	 * dimension-parity half of the losslessness condition.
	 *
	 * @param rotationDegrees rotation in degrees; sign / magnitude / mod-360 all handled
	 * @param srcW            width in pixels of the bitmap being drawn (the bitmap NN samples from)
	 * @param srcH            height in pixels of the bitmap being drawn
	 * @return true when nearest-neighbor sampling reproduces the source pixels verbatim for this
	 *         rotation + dimension combination (0°/180° at any dims; ±90°/±270° only when srcW + srcH is even)
	 */
	public static boolean isLosslessCardinalRotation(float rotationDegrees, int srcW, int srcH)
	{
		float normalized = ((rotationDegrees % 360f) + 360f) % 360f;
		if (normalized < ROTATION_EPSILON || normalized > 360f - ROTATION_EPSILON
			|| Math.abs(normalized - 180f) < ROTATION_EPSILON)
		{
			return true;
		}
		if (Math.abs(normalized - 90f) < ROTATION_EPSILON || Math.abs(normalized - 270f) < ROTATION_EPSILON)
		{
			return (srcW + srcH) % 2 == 0;
		}
		return false;
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

	/**
	 * Marker-walk the JPEG head (via JpegMarkerWalker.nextHeadSegment) to the Exif APP1 segment, then locate and
	 * read the IFD0 Orientation entry via the shared TiffIfd0 walker. Header / offset / entry-shape validation
	 * lives in TiffIfd0.findOrientationEntry; this reader keeps the JPEG-specific parts — the APP1 "Exif\0\0"
	 * filter and the upright fallback on every structural failure. The walk stops at SOS / EOI (EXIF never
	 * legally follows the scan).
	 *
	 * May throw IndexOutOfBoundsException from ByteBufferUtils' bounds checks on truncated input —
	 * readExifOrientation's catch maps that to upright per the public contract.
	 *
	 * @param jpeg full JPEG file bytes; null tolerated
	 * @return EXIF orientation 1..8, or 1 when the tag is absent or any structural check fails
	 */
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
			JpegMarkerWalker.HeadSegment step = JpegMarkerWalker.nextHeadSegment(jpeg, off, jpeg.length);
			if (step.kind() == JpegMarkerWalker.HeadKind.NO_MARKER)
			{
				return 1;
			}
			int marker = step.marker();
			if (marker == JpegMarker.SOS || marker == JpegMarker.EOI)
			{
				break;
			}
			if (step.kind() == JpegMarkerWalker.HeadKind.STANDALONE)
			{
				off = step.next();
				continue;
			}
			if (step.kind() == JpegMarkerWalker.HeadKind.BAD_LENGTH)
			{
				return 1;
			}
			int afterMarker = step.markerByteOff() + 1;

			// APP1 with Exif header. Deliberately checked before the OVERRUN bail: a truncated file whose
			// APP1 claims a length past EOF still gets its in-bounds TIFF body parsed, matching the
			// pre-cursor guard order.
			if (marker == JpegMarker.APP1 && step.segLen() > 14 && afterMarker + 8 <= jpeg.length
				&& jpeg[afterMarker + 2] == 'E' && jpeg[afterMarker + 3] == 'x'
				&& jpeg[afterMarker + 4] == 'i' && jpeg[afterMarker + 5] == 'f'
				&& jpeg[afterMarker + 6] == 0 && jpeg[afterMarker + 7] == 0)
			{
				// TIFF header sits just past the 6-byte "Exif\0\0" identifier.
				return TiffIfd0.findOrientationEntry(jpeg, afterMarker + 8, jpeg.length, 0)
					.map(entry -> TiffIfd0.readOrientation(jpeg, entry))
					.orElse(1);
			}
			if (step.kind() == JpegMarkerWalker.HeadKind.OVERRUN)
			{
				return 1;
			}
			off = step.next();
		}
		return 1;
	}
}
