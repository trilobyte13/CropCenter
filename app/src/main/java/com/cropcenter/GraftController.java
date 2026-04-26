package com.cropcenter;

import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;

import com.cropcenter.metadata.GraftWriter;
import com.cropcenter.model.ExportConfig;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.SafFileHelper;

import java.io.IOException;
import java.util.function.BiConsumer;

/**
 * Orchestrates the "Apply External Edit" feature: long-press Open → user picks an external
 * edit JPEG → CropCenter validates that the edit's stored dimensions and EXIF orientation
 * match the loaded original's, byte-splices the edit's pixel content into the original's
 * metadata container via GraftWriter, and hands the result to MainActivity to replace the
 * in-memory image. The user can then continue editing (crop, rotate) and save normally
 * through the existing Save flow — the canvas re-encode that the save flow uses adds one
 * generation of JPEG quality loss vs. the byte-perfect graft, but at quality 100 the
 * footprint is imperceptible (~50 dB PSNR).
 *
 * Lives alongside SaveController because it owns its own state machine for the picker
 * stage. Once the splice succeeds, control transfers to MainActivity via the onGraftReady
 * listener — GraftController has no save-flow involvement.
 *
 * State machine:
 *   IDLE          → start()                  → AWAITING_EDIT (pickerLauncher launched)
 *   AWAITING_EDIT → onEditPicked() success    → IDLE (image replaced via onGraftReady)
 *   AWAITING_EDIT → onEditPicked() failure    → IDLE (toast surfaced)
 *   AWAITING_EDIT → onEditPickerCancelled()   → IDLE
 */
final class GraftController
{
	private static final String TAG = "GraftController";

	// Receives the (graftedBytes, suggestedDisplayName) pair after a successful splice.
	// MainActivity wires this to applyGraftedBytes, which decodes the bytes and replaces
	// the in-memory image. Listener is invoked on the UI thread so the receiver doesn't
	// have to dispatch internally.
	private final BiConsumer<byte[], String> onGraftReady;
	private final SaveHost host;
	private final SafFileHelper safFiles;

	private boolean graftPending;

	GraftController(SaveHost host, SafFileHelper safFiles,
		BiConsumer<byte[], String> onGraftReady)
	{
		this.host = host;
		this.safFiles = safFiles;
		this.onGraftReady = onGraftReady;
	}

	/**
	 * Edit-picker callback. Reads the picked edit on a bg thread, validates dimensions and
	 * EXIF orientation against the loaded original, computes the graft, and dispatches the
	 * result to onGraftReady on the UI thread. All failure paths clear graftPending so a
	 * fresh long-press can start over.
	 */
	void onEditPicked(Uri editUri)
	{
		if (!graftPending)
		{
			// Spurious result (no active graft session) — ignore to avoid double-handling.
			return;
		}
		host.runInBackground(() ->
		{
			try
			{
				byte[] editBytes = safFiles.readUriBytes(editUri);
				if (editBytes == null || editBytes.length < 4)
				{
					toast("Couldn't read picked edit");
					graftPending = false;
					return;
				}

				byte[] originalBytes = host.getState().getOriginalFileBytes();
				if (originalBytes == null)
				{
					toast("Original bytes unavailable — reload the image and try again");
					graftPending = false;
					return;
				}

				if (!validateMatchingDimsAndOrientation(originalBytes, editBytes))
				{
					graftPending = false;
					return; // toast already fired by validator
				}

				byte[] grafted = GraftWriter.graft(originalBytes, editBytes);
				String suggested = suggestedFilename();
				graftPending = false;
				host.runOnUiThread(() ->
				{
					// Hand off the raw splice without injecting a thumbnail. The splice goes
					// into state.originalFileBytes via applyImageBytes and the save pipeline
					// will canvas-encode through CropExporter (forced via state.graftApplied),
					// which generates a fresh thumbnail in the saved output. Pre-injecting one
					// here would just be discarded by the encode pass.
					onGraftReady.accept(grafted, suggested);
					host.toastIfAlive("External edit applied", Toast.LENGTH_SHORT);
				});
			}
			catch (IOException e)
			{
				Log.w(TAG, "Graft assembly failed", e);
				toast("Graft failed: " + e.getMessage());
				graftPending = false;
			}
			catch (RuntimeException e)
			{
				Log.e(TAG, "Unexpected graft error", e);
				toast("Graft failed: " + e.getMessage());
				graftPending = false;
			}
		});
	}

	/**
	 * Edit-picker cancellation: user backed out before picking an external edit. Clear
	 * graftPending so a fresh long-press can start over.
	 */
	void onEditPickerCancelled()
	{
		graftPending = false;
	}

	/**
	 * Long-press entry point. Called from MainActivity's btnOpen long-click handler. Returns
	 * true when the long-press is consumed (regardless of whether the graft session actually
	 * started — busy-rejected attempts also consume the gesture so the user gets feedback),
	 * false when no image is loaded so the gesture can fall through.
	 */
	boolean start(ActivityResultLauncher<String[]> graftPickerLauncher)
	{
		if (host.getState().getSourceImage() == null)
		{
			return false;
		}
		if (host.getBusy().get() || graftPending)
		{
			host.showBusyToast();
			return true; // consume the gesture even when we can't act on it
		}
		byte[] originalBytes = host.getState().getOriginalFileBytes();
		if (originalBytes == null)
		{
			host.toastIfAlive("Original bytes unavailable — reload the image",
				Toast.LENGTH_SHORT);
			return true;
		}
		if (originalBytes.length < 4
			|| (originalBytes[0] & 0xFF) != 0xFF || (originalBytes[1] & 0xFF) != 0xD8)
		{
			// Loaded image is PNG (or some non-JPEG) — graft path requires JPEG identity
			// metadata. Refuse upfront so the user doesn't navigate the picker for a
			// graft that would fail validation later.
			host.toastIfAlive("Apply External Edit only works on JPEG sources",
				Toast.LENGTH_SHORT);
			return true;
		}
		graftPending = true;
		try
		{
			graftPickerLauncher.launch(new String[] { ExportConfig.JPEG_MIME });
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "Edit picker launch failed", e);
			graftPending = false;
			throw e;
		}
		return true;
	}

	/**
	 * Build the suggested filename used when the user later saves the grafted image. Suffix
	 * the original's stem with "-graft" so the user can tell at a glance that the file is
	 * post-graft. Falls back to "graft.jpg" when the original has no display name (rare —
	 * usually only for content URIs without OpenableColumns).
	 */
	private String suggestedFilename()
	{
		String orig = host.getState().getOriginalFilename();
		if (orig == null || orig.isEmpty())
		{
			return "graft.jpg";
		}
		int dot = orig.lastIndexOf('.');
		String stem = dot > 0 ? orig.substring(0, dot) : orig;
		return stem + "-graft.jpg";
	}

	/**
	 * Post a short toast on the UI thread.
	 */
	private void toast(String msg)
	{
		host.runOnUiThread(() -> host.toastIfAlive(msg, Toast.LENGTH_SHORT));
	}

	/**
	 * Validate that the edit's stored dimensions (SOF0) and EXIF orientation match the
	 * original's. Both checks together guarantee the splice is decoder-coherent: the
	 * output's SOF (from edit) and APP1/EXIF (from original) describe the same pixel grid.
	 *
	 * Returns true on match. On mismatch, fires a descriptive toast and returns false.
	 *
	 * Reads stored dims via BitmapFactory.decodeByteArray with inJustDecodeBounds=true
	 * (cheap — no pixel data allocated). Reads EXIF orientation via BitmapUtils.
	 */
	private boolean validateMatchingDimsAndOrientation(byte[] originalBytes, byte[] editBytes)
	{
		int[] origStored = decodeStoredDims(originalBytes);
		int[] editStored = decodeStoredDims(editBytes);
		if (origStored == null || editStored == null)
		{
			toast("Couldn't read JPEG dimensions");
			return false;
		}
		int origOrient = BitmapUtils.readExifOrientation(originalBytes);
		int editOrient = BitmapUtils.readExifOrientation(editBytes);

		if (origStored[0] != editStored[0] || origStored[1] != editStored[1])
		{
			toast("Edit dimensions don't match: original "
				+ origStored[0] + "x" + origStored[1]
				+ ", edit " + editStored[0] + "x" + editStored[1]);
			return false;
		}
		if (origOrient != editOrient)
		{
			toast("Edit EXIF orientation differs from original ("
				+ origOrient + " vs " + editOrient
				+ "). Re-export with same orientation.");
			return false;
		}
		return true;
	}

	/**
	 * Decode-cheap dimension probe: returns the JPEG's stored width and height without
	 * allocating pixel data. Returns null when BitmapFactory rejects the byte array.
	 */
	private static int[] decodeStoredDims(byte[] bytes)
	{
		BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
		if (opts.outWidth <= 0 || opts.outHeight <= 0)
		{
			return null;
		}
		return new int[] { opts.outWidth, opts.outHeight };
	}
}
