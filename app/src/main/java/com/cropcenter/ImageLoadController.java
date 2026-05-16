package com.cropcenter;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.cropcenter.metadata.GainMapExtractor;
import com.cropcenter.metadata.HdrSignature;
import com.cropcenter.metadata.JpegMetadataExtractor;
import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.metadata.PngMetadataExtractor;
import com.cropcenter.metadata.SeftExtractor;
import com.cropcenter.model.CropState;
import com.cropcenter.model.Format;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.SafFileHelper;

import java.util.List;

/**
 * Image-load flow extracted from MainActivity. Owns the bg-thread decode + EXIF orientation + metadata-extract pipeline
 * that turns a SAF URI (or in-memory graft bytes) into a fully-populated CropState. The Activity-specific view setup
 * that follows decode is delegated back to the host via installImageOnUi.
 *
 * Extracted so MainActivity stays focused on Activity glue (lifecycle, host-interface implementations, view wiring)
 * and the load pipeline is independently readable.
 *
 * Public entry points:
 *   load(Uri)                 — SAF URI → bytes → bitmap → state
 *   applyBytes(byte[], String) — already-in-memory bytes (used by the graft flow)
 *   handleIncomingIntent(Intent) — Share / View intents
 *   tryTakePersistable(Uri, String, boolean) — persistable URI permission helper
 */
final class ImageLoadController
{

	/**
	 * Snapshot of the metadata extracted from a freshly-loaded image, ready to commit onto CropState as one
	 * atomic block. Read-only-on-construction so a partial extraction failure can be discarded without
	 * tearing the previous state.
	 *
	 * @param sourceFormat   JPEG or PNG (one of the two — caller guards the input)
	 * @param jpegMeta       APP/COM segment list (synthetic APP1 with capped TIFF for PNG sources)
	 * @param pngExifTiff    raw TIFF bytes uncapped, for PNG → PNG round-trip; null for JPEG sources
	 * @param gainMap        Ultra-HDR gain map JPEG bytes; null when the source isn't HDR
	 * @param seftTrailer    Samsung SEFT trailer bytes; null when not a Samsung-edited file
	 * @param displayString  human-readable info-bar string (e.g. "EXIF+ICC+XMP+HDR+Samsung", "PNG")
	 */
	record MetadataExtraction(Format sourceFormat, List<JpegSegment> jpegMeta, byte[] pngExifTiff,
		byte[] gainMap, byte[] seftTrailer, String displayString) {}

	private static final String TAG = "ImageLoadController";

	private final ImageLoadHost host;
	private final SafFileHelper safFiles;

	ImageLoadController(ImageLoadHost host, SafFileHelper safFiles)
	{
		this.host = host;
		this.safFiles = safFiles;
	}

	/**
	 * Scan the loaded image's bytes for EXIF/ICC/XMP/HDR/SEFT markers and build a snapshot of everything that
	 * needs to land on CropState. Caller commits the snapshot atomically AFTER state.reset() so a thrown
	 * extractor (rare but possible on adversarial input) can't half-populate state and destroy the previous
	 * load — the caller's reset only fires on the success path.
	 *
	 * Caller has already verified the bytes match either a JPEG SOI or PNG signature (applyBytes does the gate
	 * up front), so the JPEG-vs-PNG branch here is a straightforward routing — no need to re-validate or fall
	 * through to "PNG" as a default for unrecognised formats.
	 *
	 * @param fileBytes raw image bytes (must start with FF D8 or the PNG 8-byte signature)
	 * @return snapshot of the extraction result; caller commits to CropState
	 */
	static MetadataExtraction extractMetadata(byte[] fileBytes)
	{
		if (!isJpegSignature(fileBytes))
		{
			// PNG eXIf metadata is captured in two parallel forms because the export paths have different
			// size constraints:
			//   - state.jpegMeta carries a synthetic APP1 segment for the JPEG → PNG → JPEG conversion path
			//     and for the per-segment helpers (ExifPatcher.patch, isExif). Capped at the JPEG APP1 u16
			//     limit (~64KB) so JpegMetadataInjector never writes a malformed segment.
			//   - state.pngExifTiff carries the raw TIFF bytes uncapped. PNG → PNG export prefers this so a
			//     PNG with > 64KB EXIF (camera with extensive MakerNote / GPS metadata) round-trips fully —
			//     the PNG eXIf chunk has a u31 length field, so no cap applies on the output side.
			// PngMetadataExtractor returns empty / null when no eXIf chunk is present; typical PNGs without
			// EXIF are unaffected.
			List<JpegSegment> pngMeta = PngMetadataExtractor.extract(fileBytes);
			byte[] pngExifTiff = PngMetadataExtractor.extractRawTiff(fileBytes);
			String display = pngExifTiff == null ? "PNG" : "PNG+EXIF";
			return new MetadataExtraction(Format.PNG, pngMeta, pngExifTiff, null, null, display);
		}

		List<JpegSegment> meta = JpegMetadataExtractor.extract(fileBytes);

		boolean hasExif = false;
		boolean hasIcc = false;
		boolean hasXmp = false;
		boolean hasMpf = false;
		for (JpegSegment seg : meta)
		{
			hasExif |= seg.isExif();
			hasIcc |= seg.isIcc();
			hasXmp |= seg.isXmp();
			hasMpf |= seg.isMpf();
		}
		Log.d(TAG, "Segments: " + meta.size()
			+ " EXIF=" + hasExif + " ICC=" + hasIcc + " XMP=" + hasXmp + " MPF=" + hasMpf);

		// HDR gate: only consider FF D8 after primary EOI as a gain map when the file carries the XMP
		// hdrgm namespace marker (scanned ONLY inside parsed XMP APP1 segments — a stray "hdrgm" 5-byte
		// sequence in MakerNote / COM / vendor blob / SEFT history / entropy doesn't false-positive)
		// AND has MPF segments (which describe Multi-Picture layouts including HDR). hasMpf is the
		// cheap pre-filter — segment iteration above already computed it; the XMP-segment-only hdrgm
		// scan runs only when MPF is present (Samsung non-HDR files typically don't have MPF, so the
		// scan is skipped on the SDR happy path). Without this combined gate, an SDR Samsung file
		// whose SEFT data block starts with an embedded JPEG thumbnail's FF D8 would be mis-extracted
		// as having a gain map AND have its SEFT trailer truncated past the thumbnail. Both extractors
		// use the resulting hint.
		boolean isHdrSource = hasMpf && HdrSignature.hasHdrgmInXmp(meta);
		byte[] gainMap = GainMapExtractor.extract(fileBytes, isHdrSource);
		byte[] seftTrailer = SeftExtractor.extract(fileBytes, gainMap != null);
		// Use the extractor's authoritative answer rather than re-running the magic-byte check. SeftExtractor
		// returns null when the trailer is structurally invalid (no primary EOI, etc.) even if the bytes end
		// with "SEFT" — the info-bar string and the saved-file SEFT-suffix decision must agree.
		boolean hasSeft = seftTrailer != null;
		Log.d(TAG, "HDR=" + (gainMap != null ? gainMap.length + "b" : "none")
			+ " SEFT=" + hasSeft + " MPF=" + hasMpf + " XMP=" + hasXmp);

		StringBuilder sb = new StringBuilder();
		if (hasExif)
		{
			sb.append("EXIF");
		}
		appendIf(sb, hasIcc, "ICC");
		appendIf(sb, hasXmp, "XMP");
		appendIf(sb, gainMap != null, "HDR");
		appendIf(sb, hasSeft, "Samsung");
		return new MetadataExtraction(Format.JPEG, meta, null, gainMap, seftTrailer, sb.toString());
	}

	/**
	 * True when fileBytes starts with the 2-byte JPEG SOI marker (FF D8). Caller has already accepted "anything
	 * else" as failure, so a partial / truncated JPEG with a valid SOI is allowed through here (downstream parsers
	 * handle the truncation defensively).
	 *
	 * @param fileBytes raw bytes
	 * @return true when the first two bytes are FF D8
	 */
	static boolean isJpegSignature(byte[] fileBytes)
	{
		return fileBytes.length >= 2 && (fileBytes[0] & 0xFF) == 0xFF && (fileBytes[1] & 0xFF) == 0xD8;
	}

	/**
	 * True when fileBytes starts with the 8-byte PNG signature (89 50 4E 47 0D 0A 1A 0A). Routes through
	 * PngMetadataExtractor.PNG_SIGNATURE rather than re-declaring the 8 hex/ASCII bytes here so the
	 * canonical constant lives in one place — paired with the chunk walkers in PngMetadataExtractor that
	 * also reference it.
	 *
	 * @param fileBytes raw bytes
	 * @return true when the first eight bytes match the PNG file signature
	 */
	static boolean isPngSignature(byte[] fileBytes)
	{
		byte[] sig = PngMetadataExtractor.PNG_SIGNATURE;
		if (fileBytes.length < sig.length)
		{
			return false;
		}
		for (int i = 0; i < sig.length; i++)
		{
			if (fileBytes[i] != sig[i])
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Shared bg-thread body for installing a fresh image into the editor — used by both the SAF load flow and the
	 * in-memory graft apply flow. Decodes, applies EXIF orientation, resets crop session state, populates
	 * metadata-side state via extractMetadata, posts UI refresh. The caller has already acquired busy and is
	 * responsible for releasing it in a finally block.
	 *
	 * Returns true on a successful apply; false when the bytes failed to decode (the "Failed to decode" toast has
	 * already been posted). The graft flow gates installGraft on this so a failed splice can't leave graftApplied /
	 * aiMask installed onto the previously-loaded image.
	 *
	 * @param fileBytes raw image bytes (JPEG or PNG)
	 * @param origName  display name used as the source filename in CropState
	 * @return true on successful decode + state population, false when BitmapFactory rejected the bytes
	 */
	boolean applyBytes(byte[] fileBytes, String origName)
	{
		// Reject non-JPEG/PNG sources up front. BitmapFactory will happily decode HEIC, WebP, GIF, and other
		// Android-supported formats, but the rest of the pipeline (EXIF patcher, Ultra HDR / SEFT extractors,
		// save-format toggle, eXIf-chunk PNG path) only models JPEG and PNG. Without this gate, a HEIC file
		// shared into the app would decode and load, then save defaults silently lock to "PNG" — which would
		// re-encode HEIC pixels as a PNG file under a misleading toast. Reject here so the user gets a clear
		// "unsupported format" message instead of a save surface that lies about what's loaded.
		if (!isJpegSignature(fileBytes) && !isPngSignature(fileBytes))
		{
			host.runOnUiThread(() -> host.toastIfAlive(
				"Unsupported image format — only JPEG and PNG are supported", Toast.LENGTH_LONG));
			return false;
		}
		// Two-pass decode for memory bounds. Pass 1 reads only the SOF dimensions (no pixel allocation) so
		// we can pick an inSampleSize that fits the decoded bitmap within BitmapUtils.getMaxDecodePixels()
		// — the device-adaptive cap set at MainActivity.onCreate via BitmapUtils.initialize. On a 12 GB-RAM
		// flagship the cap reaches ~187 MP so 200 MP sources decode at inSampleSize=1 (no quality loss).
		// On a 4 GB device the cap floors at 32 MP and subsampling protects against OOM. Pass 2 does the
		// real decode at that subsampling. BitmapFactory's bounds pre-pass is essentially free — header
		// walk only, no entropy decode — so the overhead is negligible for sources that don't need to
		// subsample.
		int maxPixels = BitmapUtils.getMaxDecodePixels();
		BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
		boundsOpts.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.length, boundsOpts);
		BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
		decodeOpts.inSampleSize = BitmapUtils.computeInSampleSize(
			boundsOpts.outWidth, boundsOpts.outHeight, maxPixels);
		if (decodeOpts.inSampleSize > 1)
		{
			Log.d(TAG, "Large source " + boundsOpts.outWidth + "x" + boundsOpts.outHeight
				+ " — subsampling at inSampleSize=" + decodeOpts.inSampleSize
				+ " to fit device-adaptive cap=" + maxPixels);
		}
		Bitmap raw = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.length, decodeOpts);
		if (raw == null || raw.getWidth() <= 0 || raw.getHeight() <= 0)
		{
			// BitmapFactory.decodeByteArray on a corrupt JPEG can return a non-null Bitmap with width=0 or
			// height=0; without this recycle the (rare) zero-area bitmap's native pixel buffer would leak
			// to the GC finalizer instead of being released immediately.
			if (raw != null)
			{
				raw.recycle();
			}
			host.runOnUiThread(() -> host.toastIfAlive("Failed to decode", Toast.LENGTH_SHORT));
			return false;
		}
		// JPEG and PNG carry EXIF orientation differently. JPEG embeds APP1 segments;
		// BitmapUtils.readExifOrientation walks JPEG markers and returns 1 for non-JPEG inputs. PNG carries the
		// same TIFF orientation tag inside an eXIf chunk; PngMetadataExtractor.extractOrientation walks PNG
		// chunks. Without this branch a PNG with eXIf orientation=6 (rotate 90 CW) would be displayed in
		// stored pixel orientation while the export side normalises orientation back to 1, baking a permanent
		// sideways rotation into the saved file. Both helpers return 1 (upright) on absence / malformed input,
		// so the same applyOrientation call follows for both formats.
		int orientation = isJpegSignature(fileBytes)
			? BitmapUtils.readExifOrientation(fileBytes)
			: PngMetadataExtractor.extractOrientation(fileBytes);
		// applyOrientation recycles raw and returns a new bitmap when orientation != 1, or returns raw
		// unchanged when orientation == 1. If it throws partway through (OOM during Bitmap.createBitmap is the
		// realistic case), raw might still own its native pixel buffer — recycle here so the buffer doesn't
		// leak to the GC finalizer. Double-recycle is safe (Bitmap.recycle no-ops on the second call).
		Bitmap bmp;
		try
		{
			bmp = BitmapUtils.applyOrientation(raw, orientation);
		}
		catch (RuntimeException | OutOfMemoryError e)
		{
			// applyOrientation calls Bitmap.createBitmap for orientations 2..8, which can throw
			// OutOfMemoryError (Error, not RuntimeException) on low-memory devices with multi-MP sources.
			// A narrow RuntimeException catch would let OOM propagate with `raw` still owning its native
			// pixel buffer — exactly the moment recycling matters most because the same allocation
			// pressure that triggered the OOM is hammering the heap. Catching both lets us release the
			// buffer immediately rather than waiting on the GC finalizer.
			raw.recycle();
			throw e;
		}

		// `bmp` is allocated above and ownership transfers to the UI runnable below (which posts
		// setSourceImage). If any step between here and the post throws — extractMetadata could raise on a
		// truly adversarial source segment, or width/height reads could trip on a recycled bitmap — bmp
		// would otherwise leak its native pixel buffer to the GC finalizer. The handedOff flag flips the
		// moment the runnable is queued; until then the catch path recycles bmp and rethrows so the caller's
		// catch (in applyGraftedBytes / loadImage) posts a real failure toast.
		//
		// State commit is deferred until AFTER extractMetadata succeeds: we run the extraction against
		// fileBytes only (no state mutation) and stash everything in a MetadataExtraction snapshot. Only
		// after the snapshot is built do we call state.reset() and apply the snapshot. A throw inside any
		// extractor leaves the previous load intact — no half-populated state, no destroyed prior image.
		boolean handedOff = false;
		try
		{
			// extractMetadata is read-only — builds a MetadataExtraction record without touching state.
			MetadataExtraction extracted = extractMetadata(fileBytes);

			int width = bmp.getWidth();
			int height = bmp.getHeight();

			String sizeInfo = width + "×" + height;

			// All extraction succeeded — now commit to state atomically. The bg executor is single-threaded
			// so these writes are serialized; UI-thread reads see them via the Handler.post happens-before
			// edge below. EditorRenderer / tap paths null-check getSourceImage so a null-during-load
			// snapshot is handled as "no image loaded".
			CropState state = host.getState();
			state.reset();
			state.setOriginalFileBytes(fileBytes);
			state.setOriginalFilename(origName);
			state.setSourceFormat(extracted.sourceFormat());
			state.setJpegMeta(extracted.jpegMeta());
			state.setPngExifTiff(extracted.pngExifTiff());
			state.setGainMap(extracted.gainMap());
			state.setSeftTrailer(extracted.seftTrailer());
			String metaInfo = extracted.displayString();

			// `handedOff = true` flips ONLY AFTER runOnUiThread successfully posts the runnable. If
			// the post itself throws (RejectedExecutionException on a handler whose looper is quitting
			// during a config change, or any other UI-post failure), the catch path with handedOff
			// still false triggers the finally's bmp.recycle() so the native pixel buffer is reclaimed
			// promptly rather than orphaning to the GC finalizer. Mirrors the ordering already used by
			// GraftController.assembleGraftOnBg.
			host.runOnUiThread(() -> host.installImageOnUi(bmp, sizeInfo, metaInfo));
			handedOff = true;
			return true;
		}
		finally
		{
			if (!handedOff)
			{
				bmp.recycle();
			}
		}
	}

	/**
	 * Route a Share / View intent into the load flow. No-op for null / unsupported actions / missing URI extras.
	 *
	 * @param intent the incoming Activity intent (may be null)
	 */
	void handleIncomingIntent(Intent intent)
	{
		if (intent == null)
		{
			return;
		}
		String action = intent.getAction();
		if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_VIEW.equals(action))
		{
			return;
		}
		Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
		if (uri == null)
		{
			uri = intent.getData();
		}
		if (uri == null)
		{
			return;
		}
		// Share/View intents routinely don't carry persistable permission — log at debug, not warn, since this
		// is expected for external intents.
		tryTakePersistable(uri, "(share)", false);
		load(uri);
	}

	/**
	 * Copies URI to a cache file first to guarantee raw byte access. Some ContentProviders (Samsung MediaStore)
	 * strip post-EOI data from JPEGs, which would lose the HDR gain map. Copying to local file bypasses this.
	 *
	 * @param uri SAF URI to load
	 */
	void load(Uri uri)
	{
		// Dismiss any open state-mutating dialog (SettingsDialog) BEFORE busy.compareAndSet — once we
		// dispatch to bg, state.reset() runs and would race the dialog's still-active UI commits to
		// state.gridConfig. Done synchronously on the UI thread so by the time runInBackground is called,
		// no widget inside the dialog can fire another updateGridConfig.
		host.dismissTransientDialogs();
		if (!host.getBusy().compareAndSet(false, true))
		{
			host.showBusyToast();
			return;
		}
		// Between the busy acquire and the runInBackground enqueue, any throw from the UI setup
		// (setBusyUi / showProgress can hit findViewById / setText during an unusual view-tree state) or the
		// executor submission (RejectedExecutionException after onDestroy) would otherwise strand busy=true
		// forever — runLoadBg's finally never runs because the Runnable was never accepted. Clear busy + hide
		// UI before propagating so a second Open tap isn't permanently rejected with "Busy — try again".
		// Mirrors ExportPipeline.exportTo's pre-enqueue guard.
		try
		{
			host.setBusyUi(true);
			// Show the touch-blocking overlay during the bg read+decode+metadata-extract pass. The editor
			// view and toolbar widgets above otherwise still accept taps / drags / AR changes / rotation
			// while CropState is being reset and re-populated underneath, which can leak inputs onto an
			// in-flight-replaced state. setBusyUi only disables Save/Open; the overlay is what gates
			// everything else.
			host.showProgress("Loading…");
			host.runInBackground(() -> runLoadBg(uri));
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "pre-enqueue UI/dispatch threw; releasing busy flag", e);
			host.getBusy().set(false);
			host.setBusyUi(false);
			host.hideProgress();
			throw e;
		}
	}

	/**
	 * Take read+write persistable permission on the URI. Logging level follows context:
	 * Open and Save As log at WARN because failure means we'll lose access on next launch
	 * (actionable for the user); Share intents log at DEBUG because external intents
	 * routinely don't grant persistable permission and the failure is expected, not a
	 * regression. The method swallows the exception in all cases — persistable permission
	 * is a nice-to-have, never required for the current session to succeed.
	 *
	 * @param uri            URI to claim
	 * @param contextTag     short tag appended to log lines so failures are traceable
	 *                       to the entry point (Open / Save / Share)
	 * @param warnOnFailure  true to log failures at WARN (Open / Save flows where the
	 *                       user's action implies they care), false for DEBUG (Share
	 *                       intents where the failure is expected and not actionable)
	 */
	void tryTakePersistable(Uri uri, String contextTag, boolean warnOnFailure)
	{
		try
		{
			int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
			host.getActivity().getContentResolver().takePersistableUriPermission(uri, flags);
		}
		catch (Exception e)
		{
			if (warnOnFailure)
			{
				Log.w(TAG, "takePersistableUriPermission " + contextTag + " failed for " + uri, e);
			}
			else
			{
				Log.d(TAG, "takePersistableUriPermission " + contextTag
					+ " declined: " + e.getMessage());
			}
		}
	}

	/**
	 * Append `part` to the format string if `cond` is true, separated from prior parts by '+' (e.g.
	 * "EXIF+ICC+HDR"). No-op when `cond` is false.
	 *
	 * @param sb   accumulator
	 * @param cond append-or-skip flag
	 * @param part bare format-tag string (no separator)
	 */
	private static void appendIf(StringBuilder sb, boolean cond, String part)
	{
		if (!cond)
		{
			return;
		}
		if (sb.length() > 0)
		{
			sb.append('+');
		}
		sb.append(part);
	}

	/**
	 * Background-thread body of load: reads the URI's bytes via the disk-cache copy path (so post-EOI HDR data
	 * survives provider quirks), then hands off to applyBytes for decode + metadata extract. Releases the busy flag
	 * in finally so a thrown read / decode doesn't strand subsequent Open / Save attempts behind a permanent "Busy"
	 * toast. Failure surfaces as a toast on the UI thread.
	 *
	 * @param uri SAF URI to load
	 */
	private void runLoadBg(Uri uri)
	{
		try
		{
			byte[] fileBytes = safFiles.readUriBytes(uri);
			Log.d(TAG, "Loaded " + fileBytes.length + " raw bytes (via cache)");
			applyBytes(fileBytes, safFiles.getDisplayName(uri));
		}
		catch (Exception | OutOfMemoryError e)
		{
			// Widened to include OutOfMemoryError so a multi-MP / HDR source whose primary-bitmap or
			// applyOrientation rotated copy blows the heap budget surfaces a "Load failed: …" toast
			// instead of dying silently — finally still releases busy and hides the overlay, but the
			// catch is the only place the user-facing toast posts. Mirrors ExportPipeline.encodePhase
			// and the graft-side catches in MainActivity.applyGraftedBytesOnBg +
			// GraftController.assembleGraftOnBg.
			Log.e(TAG, "Load failed", e);
			host.runOnUiThread(() -> host.toastIfAlive(
				"Load failed: " + e.getMessage(), Toast.LENGTH_SHORT));
		}
		finally
		{
			host.getBusy().set(false);
			host.runOnUiThread(() ->
			{
				host.setBusyUi(false);
				host.hideProgress();
			});
		}
	}
}
