package com.cropcenter;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.cropcenter.metadata.GainMapExtractor;
import com.cropcenter.metadata.JpegMetadataExtractor;
import com.cropcenter.metadata.JpegSegment;
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
 * Extracted so MainActivity stays focused on Activity glue (lifecycle, host- interface implementations, view wiring)
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
	private static final String TAG = "ImageLoadController";

	private final ImageLoadHost host;
	private final SafFileHelper safFiles;

	ImageLoadController(ImageLoadHost host, SafFileHelper safFiles)
	{
		this.host = host;
		this.safFiles = safFiles;
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
	 * @return true on successful decode + state population, false when BitmapFactory
	 *         rejected the bytes
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
		Bitmap raw = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.length);
		if (raw == null || raw.getWidth() <= 0 || raw.getHeight() <= 0)
		{
			host.runOnUiThread(() -> host.toastIfAlive("Failed to decode", Toast.LENGTH_SHORT));
			return false;
		}
		int orientation = BitmapUtils.readExifOrientation(fileBytes);
		// applyOrientation recycles raw and returns a new bitmap when orientation != 1, or returns raw
		// unchanged when orientation == 1. If it throws partway through (OOM during Bitmap.createBitmap is the
		// realistic case), raw might still own its native pixel buffer — recycle here so the buffer doesn't
		// leak to the GC finalizer. Double-recycle is safe (Bitmap.recycle no-ops on the second call).
		Bitmap bmp;
		try
		{
			bmp = BitmapUtils.applyOrientation(raw, orientation);
		}
		catch (RuntimeException e)
		{
			raw.recycle();
			throw e;
		}

		// `bmp` is allocated above and ownership transfers to the UI runnable below (which posts
		// setSourceImage). If extractMetadata or any step between here and the post throws —
		// JpegMetadataExtractor / GainMapExtractor / SeftExtractor could raise on a malformed source segment —
		// bmp would otherwise leak its native pixel buffer to the GC finalizer and CropState would be left
		// half-populated (originalFileBytes set, sourceImage null, jpegMeta cleared by reset but not
		// repopulated). The handedOff flag flips the moment the runnable is queued; until then the catch path
		// recycles bmp and rethrows so the caller's catch (in applyGraftedBytes / loadImage) posts a real
		// failure toast.
		boolean handedOff = false;
		try
		{
			// Mutations below run on the background executor. They become visible to the posted UI-thread
			// runnable via Handler.post's happens-before, so the runnable's body (setSourceImage + setState
			// + setText) sees a fully populated state. Concurrent UI-thread reads that beat the post — an
			// onDraw or gesture fired before setSourceImage is dispatched — see the field they read either
			// unchanged (state.reset clears, no tearing) or not yet written; EditorRenderer / tap paths
			// null-check getSourceImage so a null-during-load snapshot is handled as "no image loaded". No
			// tearing, no volatile needed, no visibility gap.
			CropState state = host.getState();
			state.reset();
			state.setOriginalFileBytes(fileBytes);
			state.setOriginalFilename(origName);

			String metaInfo = extractMetadata(state, fileBytes);

			int width = bmp.getWidth();
			int height = bmp.getHeight();

			String sizeInfo = width + "×" + height;

			// handedOff flips only after width/height are read so a (theoretical) recycle race during those
			// reads still leaves the finally responsible for the cleanup. In practice nothing else holds
			// bmp at this point.
			handedOff = true;
			host.runOnUiThread(() -> host.installImageOnUi(bmp, sizeInfo, metaInfo));
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
		if (!host.getBusy().compareAndSet(false, true))
		{
			host.showBusyToast();
			return;
		}
		host.setBusyUi(true);

		host.runInBackground(() -> runLoadBg(uri));
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
		catch (Exception e)
		{
			Log.e(TAG, "Load failed", e);
			host.runOnUiThread(() -> host.toastIfAlive(
				"Load failed: " + e.getMessage(), Toast.LENGTH_SHORT));
		}
		finally
		{
			host.getBusy().set(false);
			host.runOnUiThread(() -> host.setBusyUi(false));
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
	 * True when fileBytes starts with the 8-byte PNG signature (89 50 4E 47 0D 0A 1A 0A).
	 *
	 * @param fileBytes raw bytes
	 * @return true when the first eight bytes match the PNG file signature
	 */
	static boolean isPngSignature(byte[] fileBytes)
	{
		return fileBytes.length >= 8 && (fileBytes[0] & 0xFF) == 0x89
			&& fileBytes[1] == 'P' && fileBytes[2] == 'N' && fileBytes[3] == 'G'
			&& (fileBytes[4] & 0xFF) == 0x0D && (fileBytes[5] & 0xFF) == 0x0A
			&& (fileBytes[6] & 0xFF) == 0x1A && (fileBytes[7] & 0xFF) == 0x0A;
	}

	/**
	 * Scan the loaded JPEG for EXIF/ICC/XMP/HDR/SEFT markers, populate state, and build the human-readable format
	 * string shown in the info bar.
	 *
	 * Caller has already verified the bytes match either a JPEG SOI or PNG signature (applyBytes does the gate up
	 * front), so the JPEG-vs-PNG branch here is a straightforward routing — no need to re-validate or fall through
	 * to "PNG" as a default for unrecognised formats.
	 *
	 * @param state     CropState to populate (sourceFormat, jpegMeta, gainMap, seftTrailer)
	 * @param fileBytes raw image bytes (must start with FF D8 or the PNG 8-byte signature)
	 * @return human-readable format string ("EXIF+ICC+HDR+Samsung", "PNG", etc.)
	 */
	private static String extractMetadata(CropState state, byte[] fileBytes)
	{
		if (!isJpegSignature(fileBytes))
		{
			state.setSourceFormat(Format.PNG);
			return "PNG";
		}

		state.setSourceFormat(Format.JPEG);
		List<JpegSegment> meta = JpegMetadataExtractor.extract(fileBytes);
		state.setJpegMeta(meta);

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

		byte[] gainMap = GainMapExtractor.extract(fileBytes);
		state.setGainMap(gainMap);
		state.setSeftTrailer(SeftExtractor.extract(fileBytes));

		boolean hasSeft = fileBytes.length >= 12 && fileBytes[fileBytes.length - 4] == 'S'
			&& fileBytes[fileBytes.length - 3] == 'E' && fileBytes[fileBytes.length - 2] == 'F'
			&& fileBytes[fileBytes.length - 1] == 'T';
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
		return sb.toString();
	}
}
