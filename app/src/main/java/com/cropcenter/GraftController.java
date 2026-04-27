package com.cropcenter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;

import com.cropcenter.metadata.GraftWriter;
import com.cropcenter.model.ExportConfig;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.SafFileHelper;

import java.io.ByteArrayOutputStream;
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

	// Read on the UI thread (start, onEditPicked entry, onEditPickerCancelled) and written
	// from both the UI thread and the background graft executor (after each terminal step
	// in onEditPicked's bg lambda). Volatile guarantees the UI thread sees the bg-side
	// transition to false promptly, so a fresh long-press isn't spuriously rejected with
	// the busy toast after the bg work has finished.
	private volatile boolean graftPending;

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

				byte[] alignedEditBytes = alignEditToOriginalLayout(originalBytes, editBytes);
				if (alignedEditBytes == null)
				{
					graftPending = false;
					return; // toast already fired by validator
				}

				byte[] grafted = GraftWriter.graft(originalBytes, alignedEditBytes);
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
	 *
	 * The recommended source editor is Photoshop with Camera Raw set to NOT auto-open JPEGs
	 * (Edit → Preferences → Camera Raw → File Handling → JPEG → Disabled). Photoshop in
	 * pixel-space mode preserves source pixel values everywhere except the AI-edited
	 * region, leaving only ICC-encoding-level differences after canvas P3 conversion.
	 * Lightroom HDR exports apply a global tone curve that produces a visible seam at the
	 * fill boundary; not recommended.
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
			toast("Original bytes unavailable — reload the image");
			return true;
		}
		if (originalBytes.length < 4
			|| (originalBytes[0] & 0xFF) != 0xFF || (originalBytes[1] & 0xFF) != 0xD8)
		{
			// Loaded image is PNG (or some non-JPEG) — graft path requires JPEG identity
			// metadata. Refuse upfront so the user doesn't navigate the picker for a
			// graft that would fail validation later.
			toast("Apply External Edit only works on JPEG sources");
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
	 * Post a short toast. Safe to call from any thread — Activity.runOnUiThread runs the
	 * runnable inline when already on the UI thread, so the indirection is a no-op cost
	 * and we don't need separate UI-thread / bg-thread variants.
	 */
	private void toast(String msg)
	{
		host.runOnUiThread(() -> host.toastIfAlive(msg, Toast.LENGTH_SHORT));
	}

	/**
	 * Validate that the edit and original describe the same DISPLAY image, and produce
	 * an edit byte stream whose stored layout matches the original's so the splice is
	 * decoder-coherent. Returns the (possibly re-encoded) edit bytes on success, null
	 * on irreconcilable mismatch (fires a descriptive toast in that case).
	 *
	 * Why display dims, not stored dims: Photoshop's "Save As JPEG" applies the EXIF
	 * orientation tag to the pixels and writes the result with orientation=1. So a
	 * Samsung-rotated photo (stored 4000×3000, orient=6, displays 3000×4000) round-
	 * tripped through Photoshop becomes (stored 3000×4000, orient=1, displays the same
	 * 3000×4000). The two files describe the same visible image but their stored dims
	 * differ — the user reasonably expects them to match. Comparing display dims gives
	 * the user-visible answer.
	 *
	 * When stored layouts differ but display dims match, decode + re-rotate the edit
	 * back to original's stored layout so GraftWriter's splice (= edit's primary scan +
	 * original's metadata, including the EXIF orientation tag) decodes coherently. The
	 * re-rotation runs Bitmap.compress at quality 100, which adds ~1 level of channel
	 * noise to the edit pixels — same noise floor the save-time canvas pass would add,
	 * so the net cost is bounded.
	 */
	private byte[] alignEditToOriginalLayout(byte[] originalBytes, byte[] editBytes)
	{
		int[] origStored = decodeStoredDims(originalBytes);
		int[] editStored = decodeStoredDims(editBytes);
		if (origStored == null || editStored == null)
		{
			toast("Couldn't read JPEG dimensions");
			return null;
		}
		int origOrient = BitmapUtils.readExifOrientation(originalBytes);
		int editOrient = BitmapUtils.readExifOrientation(editBytes);

		int[] origDisplay = displayDims(origStored, origOrient);
		int[] editDisplay = displayDims(editStored, editOrient);
		if (origDisplay[0] != editDisplay[0] || origDisplay[1] != editDisplay[1])
		{
			toast("Edit dimensions don't match: original "
				+ origDisplay[0] + "x" + origDisplay[1]
				+ ", edit " + editDisplay[0] + "x" + editDisplay[1]);
			return null;
		}

		boolean perfectMatch = origOrient == editOrient
			&& origStored[0] == editStored[0]
			&& origStored[1] == editStored[1];
		if (perfectMatch)
		{
			return editBytes;
		}
		byte[] reoriented = reorientEdit(editBytes, editOrient, origOrient);
		if (reoriented == null)
		{
			toast("Couldn't reorient edit to match original");
			return null;
		}
		Log.d(TAG, "Reoriented edit (origOrient=" + origOrient + " editOrient=" + editOrient
			+ ") from " + editStored[0] + "x" + editStored[1]
			+ " to original's stored layout (" + origStored[0] + "x" + origStored[1] + ")");
		return reoriented;
	}

	/**
	 * Apply EXIF orientation to stored dims to get display dims. EXIF tags 5/6/7/8
	 * swap the axes (90° rotations + transpose / transverse); 1/2/3/4 leave them
	 * alone. Returns a fresh int[2] so callers can mutate without aliasing.
	 */
	private static int[] displayDims(int[] stored, int orient)
	{
		boolean swap = orient == 5 || orient == 6 || orient == 7 || orient == 8;
		return swap ? new int[] { stored[1], stored[0] } : new int[] { stored[0], stored[1] };
	}

	/**
	 * Re-encode the edit so its stored pixel layout matches the original's. Pipeline:
	 * decode raw (BitmapFactory does not apply orientation) → apply edit's orientation
	 * to land in display orientation → apply the inverse of original's orientation to
	 * land in original's stored orientation → JPEG-compress at quality 100. The output
	 * has no EXIF (Bitmap.compress doesn't write APP1/EXIF segments); GraftWriter only
	 * uses the primary scan from this file, so the missing EXIF is fine.
	 *
	 * Returns null when the decode fails (corrupt edit) — caller surfaces a toast.
	 */
	private static byte[] reorientEdit(byte[] editBytes, int editOrient, int origOrient)
	{
		Bitmap raw = BitmapFactory.decodeByteArray(editBytes, 0, editBytes.length);
		if (raw == null)
		{
			return null;
		}
		Bitmap inDisplay = null;
		Bitmap inOrigStored = null;
		try
		{
			inDisplay = BitmapUtils.applyOrientation(raw, editOrient);
			raw = null; // applyOrientation may have recycled raw
			inOrigStored = BitmapUtils.applyOrientation(inDisplay, inverseOrientation(origOrient));
			inDisplay = null;
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			inOrigStored.compress(Bitmap.CompressFormat.JPEG, 100, bos);
			return bos.toByteArray();
		}
		finally
		{
			if (raw != null && !raw.isRecycled())
			{
				raw.recycle();
			}
			if (inDisplay != null && !inDisplay.isRecycled())
			{
				inDisplay.recycle();
			}
			if (inOrigStored != null && !inOrigStored.isRecycled())
			{
				inOrigStored.recycle();
			}
		}
	}

	/**
	 * Inverse of an EXIF orientation transform — applying orient then inverseOrientation
	 * gives the identity. Most orientations (1, 2, 3, 4, 5, 7) are involutions and map
	 * to themselves; only the 90° rotations (6 ↔ 8) form an inverse pair.
	 */
	private static int inverseOrientation(int orient)
	{
		if (orient == 6)
		{
			return 8;
		}
		if (orient == 8)
		{
			return 6;
		}
		return orient;
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
