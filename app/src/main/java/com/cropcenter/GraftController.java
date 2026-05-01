package com.cropcenter;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;

import com.cropcenter.metadata.GraftWriter;
import com.cropcenter.model.ExportConfig;
import com.cropcenter.model.Graft;
import com.cropcenter.util.AiRegionDetector;
import com.cropcenter.util.AiRegionDetector.AiMask;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.SafFileHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
	/**
	 * Callback for delivering a successfully spliced graft to MainActivity. The Graft
	 * record carries the assembled JPEG bytes, suggested display name, and the AI-region
	 * mask (null when no AI fill was detected). MainActivity is expected to drive
	 * applyImageBytes followed by state.installGraft(graft) atomically — installGraft
	 * encapsulates the "must happen after reset, both fields required" invariant so
	 * callers can't accidentally skip one half of the post-apply state setup.
	 */
	@FunctionalInterface
	interface GraftReadyHandler
	{
		void onReady(Graft graft);
	}

	// Mask-fraction threshold above which we ask the user to confirm the apply. The feature
	// is for small Generative Remove / Generative Fill touch-ups — typical real cases land
	// at 0.001%-0.5% of pixels. A wrong-file pick (different photo with matching dimensions)
	// or a wholesale global edit (Lightroom tone curve, Photoshop colour grade) will spike
	// far past 10%. Confirmation lets those cases proceed if the user actually meant it,
	// while turning the silent "graft an unrelated image into my metadata" footgun into a
	// visible decision point.
	private static final String TAG = "GraftController";

	private static final float LARGE_EDIT_FRACTION = 0.10f;

	// Receives the graft result. MainActivity wires this to applyGraftedBytes, which
	// decodes the bytes and replaces the in-memory image. Invoked on the UI thread so
	// the receiver doesn't have to dispatch internally.
	private final GraftReadyHandler onGraftReady;
	private final SafFileHelper safFiles;
	private final SaveHost host;

	// Read on the UI thread (start, onEditPicked entry, onEditPickerCancelled) and written
	// from both the UI thread and the background graft executor (after each terminal step
	// in onEditPicked's bg lambda). Volatile guarantees the UI thread sees the bg-side
	// transition to false promptly, so a fresh long-press isn't spuriously rejected with
	// the busy toast after the bg work has finished.
	private volatile boolean graftPending;

	// Snapshot of the original file's bytes at the moment the user long-pressed Open. Read
	// on the bg graft executor, written on the UI thread (start, onEditPicked completion,
	// onEditPickerCancelled). Captured so onEditPicked grafts onto the image the user
	// actually long-pressed, not whatever happens to be loaded when the picker returns —
	// without this, the user could load image B while the picker for image A is still
	// open, and onEditPicked would graft the picked edit onto B's bytes (with B's
	// metadata pretending to describe A's primary scan). Volatile because of the cross-
	// thread visibility requirement.
	private volatile byte[] pendingOriginalBytes;

	GraftController(SaveHost host, SafFileHelper safFiles, GraftReadyHandler onGraftReady)
	{
		this.host = host;
		this.safFiles = safFiles;
		this.onGraftReady = onGraftReady;
	}

	/**
	 * Edit-picker callback. Claims busy on the UI thread BEFORE dispatching the bg work
	 * so a Save / Open tap during the read/align/detect/graft window can't preempt and
	 * cause applyGraftedBytes to silently drop the prepared graft. On any failure path
	 * busy is released here; on success the held busy is handed off to applyGraftedBytes
	 * which releases it after the apply completes.
	 *
	 * Reads the picked edit on a bg thread, validates dimensions and EXIF orientation
	 * against the loaded original, computes the graft, and dispatches the result to
	 * onGraftReady on the UI thread. All failure paths clear graftPending so a fresh
	 * long-press can start over.
	 */
	void onEditPicked(Uri editUri)
	{
		if (!graftPending)
		{
			// Spurious result (no active graft session) — ignore to avoid double-handling.
			return;
		}
		// Claim busy on the UI thread (this callback runs there) so concurrent Save / Open
		// taps see the busy state before we even start reading the edit. Without this,
		// a save tapped during the bg work below claims busy first, and when our bg work
		// finishes its onGraftReady handoff, applyGraftedBytes finds busy held by the
		// save and drops the graft on the floor with a "Busy — try again" toast.
		if (!host.getBusy().compareAndSet(false, true))
		{
			graftPending = false;
			host.showBusyToast();
			return;
		}
		host.setBusyUi(true);

		host.runInBackground(() ->
		{
			boolean handedOff = false;
			try
			{
				byte[] editBytes = safFiles.readUriBytes(editUri);
				if (editBytes == null || editBytes.length < 4)
				{
					toast("Couldn't read picked edit");
					graftPending = false;
					return;
				}

				// Use the bytes captured at long-press time, not whatever state currently
				// holds — the user might have loaded a new image while the picker was
				// open, and grafting onto the wrong source would silently produce a
				// file with mismatched metadata vs. primary scan.
				byte[] originalBytes = pendingOriginalBytes;
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

				// Detect the AI-modified pixel region now (we have both source and
				// aligned-edit bytes here). The mask travels through state into
				// UltraHdrCompat at HDR re-encode time, where it patches the gain
				// map's boost values inside the AI fill. Doing the inpaint here on
				// the raw gain-map JPEG forces a Bitmap.compress round-trip that
				// converts source's single-channel grayscale gain map into 3-channel
				// YCbCr, which Android's UHDR decoder then doesn't recognise → HDR
				// is silently dropped at save time. UltraHdrCompat operates on the
				// gain-map Bitmap in source's native single-channel format.
				//
				// Skip detection entirely for SDR sources: the only consumer is
				// UltraHdrCompat.compressWithGainmap, which CropExporter only invokes
				// when state.gainMap != null. Running detection on a non-HDR source
				// burns memory + CPU for a mask that never gets applied.
				byte[] sourceGainMap = host.getState().getGainMap();
				AiMask aiMask = (sourceGainMap != null && sourceGainMap.length > 0)
					? AiRegionDetector.detect(originalBytes, alignedEditBytes)
					: null;

				Graft graft = new Graft(
					GraftWriter.graft(originalBytes, alignedEditBytes),
					suggestedFilename(),
					aiMask);
				// Compute the mask fraction here on the bg thread so the UI-thread handoff
				// doesn't have to walk a 750k-bool array twice (once for the sanity check,
				// once again for the dialog message). Negative count means "no mask" — both
				// the sanity gate and the dialog code treat that as "skip the dialog".
				int maskedPixelCount = (aiMask != null) ? aiMask.maskedCount() : -1;
				int maskTotal = (aiMask != null) ? aiMask.mask().length : 0;
				graftPending = false;
				handedOff = true;
				host.runOnUiThread(() ->
				{
					// Hand off the raw splice without injecting a thumbnail. The splice goes
					// into state.originalFileBytes via applyImageBytes and the save pipeline
					// will canvas-encode through CropExporter (forced via state.graftApplied),
					// which generates a fresh thumbnail in the saved output. Pre-injecting one
					// here would just be discarded by the encode pass. The receiver runs
					// applyImageBytes + state.installGraft(graft); installGraft encapsulates
					// the "set graftApplied AND aiMask, both AFTER reset" rule. The receiver
					// inherits the held busy flag and releases it when the apply completes
					// (and fires the user-visible "External edit applied" toast on success
					// — firing it here would lie about state during the brief window
					// between handoff and the bg apply finishing or failing).
					if (isOversizedEdit(maskedPixelCount, maskTotal))
					{
						confirmOversizedThenApply(graft, maskedPixelCount, maskTotal);
					}
					else
					{
						onGraftReady.onReady(graft);
					}
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
			finally
			{
				// Drop the captured-bytes reference on every exit path — it served its
				// purpose (locked in the long-pressed source for this graft) and a
				// long-lived strong reference to a multi-MB byte[] is wasted memory.
				// Clearing here covers every path: success (handoff happened), failure
				// (toast + early return), and unexpected throw.
				pendingOriginalBytes = null;
				// Release busy on every failure path. The success path leaves it held
				// because applyGraftedBytes is about to claim it transitively.
				if (!handedOff)
				{
					host.getBusy().set(false);
					host.runOnUiThread(() -> host.setBusyUi(false));
				}
			}
		});
	}

	/**
	 * Edit-picker cancellation: user backed out before picking an external edit. Clear
	 * graftPending and the captured bytes so a fresh long-press can start over.
	 */
	void onEditPickerCancelled()
	{
		graftPending = false;
		pendingOriginalBytes = null;
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
		// Snapshot the bytes the user actually long-pressed on. onEditPicked uses this
		// reference instead of re-reading state.originalFileBytes — without the snapshot,
		// loading a different image while the picker is open would cause the graft to
		// land on the new image's bytes, silently producing a file with mismatched
		// metadata vs primary scan.
		pendingOriginalBytes = originalBytes;
		graftPending = true;
		try
		{
			graftPickerLauncher.launch(new String[] { ExportConfig.JPEG_MIME });
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "Edit picker launch failed", e);
			graftPending = false;
			pendingOriginalBytes = null;
			throw e;
		}
		return true;
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
	 * UI-thread confirm dialog for an unusually-large AI-edit fraction. Apply proceeds with
	 * the prepared graft; Cancel discards it and releases the busy flag GraftController is
	 * holding (no MainActivity.applyGraftedBytes will run, so its finally won't release
	 * either). Back-button / outside-tap dismissal routes through the cancel listener for
	 * the same reason. If the Activity is destroyed between the bg-thread post and the
	 * dialog show — common when the user backgrounds the app while the picker is still
	 * processing — the destroyed-Activity guard releases busy and bails rather than
	 * throwing WindowManager.BadTokenException.
	 *
	 * Takes the mask fraction (count + total) as parameters rather than re-walking the
	 * mask array on the UI thread — the bg thread already paid the O(N) walk, no point
	 * paying it again here.
	 *
	 * Lives here rather than in a util because the cleanup contract — release the busy flag
	 * we own, clear the UI's busy indicator — is GraftController-specific and would leak
	 * busy ownership if hoisted to a generic helper.
	 */
	private void confirmOversizedThenApply(Graft graft, int maskedPixelCount, int maskTotal)
	{
		Runnable releaseBusy = () ->
		{
			host.getBusy().set(false);
			host.setBusyUi(false);
		};
		if (host.isDestroyed())
		{
			Log.w(TAG, "skipping oversized-edit dialog on destroyed activity");
			releaseBusy.run();
			return;
		}
		int pct = (int) Math.round(100.0 * maskedPixelCount / Math.max(1, maskTotal));
		String message = "This edit changed about " + pct + "% of pixels — much larger than"
			+ " typical for AI spot removal. Apply anyway?";
		try
		{
			new AlertDialog.Builder(host.getActivity())
				.setTitle("Large edit detected")
				.setMessage(message)
				.setPositiveButton("Apply", (dialog, which) -> onGraftReady.onReady(graft))
				.setNegativeButton("Cancel", (dialog, which) -> releaseBusy.run())
				.setOnCancelListener(dialog -> releaseBusy.run())
				.show();
		}
		catch (RuntimeException e)
		{
			// BadTokenException if the activity died between isDestroyed and show, or any
			// other UI-thread throw from the dialog plumbing. Don't strand busy.
			Log.w(TAG, "oversized-edit dialog failed to show", e);
			releaseBusy.run();
		}
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
	 * True when the AI mask covers a fraction of the image larger than the small-touch-up
	 * profile this feature targets. Triggers the user-visible confirm path. SDR sources
	 * (no mask) and empty masks (no detected change) pass through with maskedPixelCount<0
	 * or maskTotal==0 and skip the dialog.
	 *
	 * Pre-counted args (rather than the AiMask record) so callers don't pay the O(N) mask
	 * walk twice — once here, once again to format the dialog message.
	 */
	private static boolean isOversizedEdit(int maskedPixelCount, int maskTotal)
	{
		if (maskedPixelCount < 0 || maskTotal <= 0)
		{
			return false;
		}
		return maskedPixelCount > maskTotal * LARGE_EDIT_FRACTION;
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
			// applyOrientation either recycled raw and returned a new rotated bitmap, OR
			// (when editOrient is 1 / out of range) returned raw unchanged — in which case
			// inDisplay aliases raw. Either way, the only live reference to those pixels is
			// now inDisplay; null out the local so the finally block doesn't try to recycle
			// twice on the alias case.
			raw = null;
			inOrigStored = BitmapUtils.applyOrientation(inDisplay, inverseOrientation(origOrient));
			// Same alias logic for inDisplay → inOrigStored.
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
}
