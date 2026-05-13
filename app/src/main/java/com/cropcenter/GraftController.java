package com.cropcenter;

import android.app.AlertDialog;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;

import com.cropcenter.graft.EditAligner;
import com.cropcenter.metadata.GraftWriter;
import com.cropcenter.model.Format;
import com.cropcenter.model.Graft;
import com.cropcenter.util.AiRegionDetector;
import com.cropcenter.util.AiRegionDetector.AiMask;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.view.DialogStrings;

import java.io.IOException;

/**
 * Orchestrates the "Apply External Edit" feature: long-press Open → user picks an external edit JPEG → CropCenter
 * validates that the edit's stored dimensions and EXIF orientation match the loaded original's, byte-splices the edit's
 * pixel content into the original's metadata container via GraftWriter, and hands the result to MainActivity to replace
 * the in-memory image. The user can then continue editing (crop, rotate) and save normally through the existing Save
 * flow — the canvas re-encode that the save flow uses adds one generation of JPEG quality loss vs. the byte-perfect
 * graft, but at quality 100 the footprint is imperceptible (~50 dB PSNR).
 *
 * Lives alongside SaveController because it owns its own state machine for the picker stage. Once the splice succeeds,
 * control transfers to MainActivity via the onGraftReady listener — GraftController has no save-flow involvement.
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
	 * Callback for delivering a successfully spliced graft to MainActivity. The Graft record carries the assembled
	 * JPEG bytes, suggested display name, and the AI-region mask (null when no AI fill was detected). MainActivity
	 * is expected to drive applyBytes followed by state.installGraft(graft) atomically — installGraft encapsulates
	 * the "must happen after reset, both fields required" invariant so callers can't accidentally skip one half of
	 * the post-apply state setup.
	 */
	@FunctionalInterface
	interface GraftReadyHandler
	{
		void onReady(Graft graft);
	}

	/**
	 * Captured at long-press time so onEditPicked sees a coherent view of the source even when the user has loaded
	 * a different image while the picker was open.
	 *
	 * @param originalBytes raw bytes of the source the user long-pressed on
	 * @param gainMap       source's gain map at long-press time, or null for SDR sources
	 * @param filename      source's display name at long-press time (used for the
	 *                      "{stem}-graft.jpg" default save name)
	 */
	private record SourceSnapshot(byte[] originalBytes, byte[] gainMap, String filename) {}

	private static final String TAG = "GraftController";

	// Mask-fraction threshold above which we ask the user to confirm the apply. The feature is for small Generative
	// Remove / Generative Fill touch-ups — typical real cases land at 0.001%-0.5% of pixels. A wrong-file pick
	// (different photo with matching dimensions) or a wholesale global edit (Lightroom tone curve, Photoshop colour
	// grade) will spike far past 10%. Confirmation lets those cases proceed if the user actually meant it, while
	// turning the silent "graft an unrelated image into my metadata" footgun into a visible decision point.
	private static final float LARGE_EDIT_FRACTION = 0.10f;

	// Receives the graft result. MainActivity wires this to applyGraftedBytes, which decodes the bytes and replaces
	// the in-memory image. Invoked on the UI thread so the receiver doesn't have to dispatch internally.
	private final GraftReadyHandler onGraftReady;
	private final SafFileHelper safFiles;
	private final SaveHost host;

	// Snapshot of the original source taken at the moment the user long-pressed Open. Read on the bg graft
	// executor, written on the UI thread (start, onEditPicked completion, onEditPickerCancelled). Captured so
	// onEditPicked grafts onto the image the user actually long-pressed, not whatever happens to be loaded when the
	// picker returns — without this, the user could load image B while the picker for image A is still open, and
	// onEditPicked would graft the picked edit onto B's bytes BUT use B's gain-map state (which determines whether
	// AI mask detection runs) and B's filename (which determines the default save name). Volatile because of the
	// cross-thread visibility requirement. All three fields snapshot together: bytes drive the splice, gainMap
	// drives the HDR / SDR detection branch, filename drives suggestedFilename.
	private volatile SourceSnapshot pendingSource;

	// Read on the UI thread (start, onEditPicked entry, onEditPickerCancelled) and written from both the UI thread
	// and the background graft executor (after each terminal step in onEditPicked's bg lambda). Volatile guarantees
	// the UI thread sees the bg-side transition to false promptly, so a fresh long-press isn't spuriously rejected
	// with the busy toast after the bg work has finished.
	private volatile boolean graftPending;

	GraftController(SaveHost host, SafFileHelper safFiles, GraftReadyHandler onGraftReady)
	{
		this.host = host;
		this.safFiles = safFiles;
		this.onGraftReady = onGraftReady;
	}

	/**
	 * Edit-picker callback. Claims busy on the UI thread BEFORE dispatching the bg work so a Save / Open tap during
	 * the read/align/detect/graft window can't preempt and cause applyGraftedBytes to silently drop the prepared
	 * graft. On any failure path busy is released here; on success the held busy is handed off to applyGraftedBytes
	 * which releases it after the apply completes.
	 *
	 * Reads the picked edit on a bg thread, validates dimensions and EXIF orientation against the loaded original,
	 * computes the graft, and dispatches the result to onGraftReady on the UI thread. All failure paths clear
	 * graftPending so a fresh long-press can start over.
	 */
	void onEditPicked(Uri editUri)
	{
		if (!graftPending)
		{
			// Spurious result (no active graft session) — ignore to avoid double-handling.
			return;
		}
		// Claim busy on the UI thread (this callback runs there) so concurrent Save / Open taps see the busy
		// state before we even start reading the edit. Without this, a save tapped during the bg work below
		// claims busy first, and when our bg work finishes its onGraftReady handoff, applyGraftedBytes finds
		// busy held by the save and drops the graft on the floor with a "Busy — try again" toast.
		if (!host.getBusy().compareAndSet(false, true))
		{
			// Drop the captured snapshot here too — the bg path that normally clears it (assembleGraftOnBg
			// finally) is never reached when we bail before claiming busy. Without this, a graft picker
			// result that arrives while another op holds busy would leave the source byte[] + gain map
			// strongly referenced for the rest of the Activity session.
			graftPending = false;
			pendingSource = null;
			host.showBusyToast();
			return;
		}
		// Pre-enqueue cleanup guard — any throw from setBusyUi / showProgress / runInBackground here would
		// strand busy=true (assembleGraftOnBg's finally only runs if the Runnable was accepted). Mirrors
		// ImageLoadController.load and ExportPipeline.exportTo.
		try
		{
			host.setBusyUi(true);
			// Touch-blocking overlay during read+align+detect+splice. Without it the editor + toolbar still
			// accept input while CropState is mid-replacement; setBusyUi only disables Save/Open. The
			// overlay gates everything else.
			host.showProgress("Applying edit…");
			host.runInBackground(() -> assembleGraftOnBg(editUri));
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "pre-enqueue UI/dispatch threw; releasing busy flag", e);
			graftPending = false;
			// Clear pendingSource here too — the bg lambda was never accepted, so assembleGraftOnBg's
			// finally never runs. Without this clear, the snapshot stays referenced until the next start()
			// or onEditPickerCancelled.
			pendingSource = null;
			host.getBusy().set(false);
			host.setBusyUi(false);
			host.hideProgress();
			throw e;
		}
	}

	/**
	 * Edit-picker cancellation: user backed out before picking an external edit. Clear graftPending and the
	 * captured bytes so a fresh long-press can start over.
	 */
	void onEditPickerCancelled()
	{
		graftPending = false;
		pendingSource = null;
	}

	/**
	 * Long-press entry point. Called from MainActivity's btnOpen long-click handler. Returns true when the
	 * long-press is consumed (regardless of whether the graft session actually started — busy-rejected attempts
	 * also consume the gesture so the user gets feedback), false when no image is loaded so the gesture can fall
	 * through.
	 *
	 * The recommended source editor is Photoshop with Camera Raw set to NOT auto-open JPEGs (Edit → Preferences →
	 * Camera Raw → File Handling → JPEG → Disabled). Photoshop in pixel-space mode preserves source pixel values
	 * everywhere except the AI-edited region, leaving only ICC-encoding-level differences after canvas P3
	 * conversion. Lightroom HDR exports apply a global tone curve that produces a visible seam at the fill
	 * boundary; not recommended.
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
		if (originalBytes.length < 4 || (originalBytes[0] & 0xFF) != 0xFF || (originalBytes[1] & 0xFF) != 0xD8)
		{
			// Loaded image is PNG (or some non-JPEG) — graft path requires JPEG identity metadata. Refuse
			// upfront so the user doesn't navigate the picker for a graft that would fail validation later.
			toast("Apply External Edit only works on JPEG sources");
			return true;
		}
		// Snapshot all source-derived state the user actually long-pressed on:
		//  - originalBytes: locks in the bytes that GraftWriter splices against
		//  - gainMap: determines whether onEditPicked runs HDR mask detection
		//  - filename: drives the {stem}-graft.jpg default save name
		// Without the snapshot, loading a different image while the picker is open
		// would cause the graft to land on image A's bytes BUT use image B's gain-map
		// state and B's filename — producing a file that splices A's pixels under B's
		// metadata defaults.
		pendingSource = new SourceSnapshot(
			originalBytes, host.getState().getGainMap(), host.getState().getOriginalFilename());
		graftPending = true;
		try
		{
			graftPickerLauncher.launch(new String[] { Format.JPEG.mimeType() });
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "Edit picker launch failed", e);
			graftPending = false;
			pendingSource = null;
			throw e;
		}
		return true;
	}

	/**
	 * True when the AI mask covers a fraction of the image larger than the small-touch-up profile this feature
	 * targets. Triggers the user-visible confirm path. SDR sources (no mask) and empty masks (no detected change)
	 * pass through with maskedPixelCount<0 or maskTotal==0 and skip the dialog.
	 *
	 * Pre-counted args (rather than the AiMask record) so callers don't pay the O(N) mask walk twice — once here,
	 * once again to format the dialog message.
	 *
	 * @param maskedPixelCount number of pixels flagged in the AI mask, or <0 when no mask
	 * @param maskTotal        total pixel count in the mask (mask.length)
	 * @return true when count / total exceeds LARGE_EDIT_FRACTION
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
	 * Build the suggested filename used when the user later saves the grafted image. Suffix the original's stem
	 * with "-graft" so the user can tell at a glance that the file is post-graft. Falls back to "graft.jpg" when
	 * the original has no display name (rare — usually only for content URIs without OpenableColumns).
	 *
	 * Takes the filename as a parameter rather than reading state.getOriginalFilename directly so this stays
	 * consistent with the rest of onEditPicked's "use the snapshot, not live state" rule. The user might have
	 * loaded a different image while the picker was open; reading state here would produce image B's stem on a
	 * graft assembled from image A's bytes.
	 *
	 * @param originalFilename source's display name at long-press time (may be null)
	 * @return "{stem}-graft.jpg" when originalFilename is non-empty, else "graft.jpg"
	 */
	private static String suggestedFilename(String originalFilename)
	{
		if (originalFilename == null || originalFilename.isEmpty())
		{
			return "graft.jpg";
		}
		int dot = originalFilename.lastIndexOf('.');
		String stem = dot > 0 ? originalFilename.substring(0, dot) : originalFilename;
		return stem + "-graft.jpg";
	}

	/**
	 * Shared apply-pipeline entry for both the normal graft path (dispatchGraftToUi → here) and the
	 * oversized-edit Apply button (confirmOversizedThenApply → here). Guards against the case where the user
	 * taps through after the Activity started finishing — a config change that races the dialog (or the
	 * UI runnable itself, in the normal path) leaves us at this point with the executor already shut down,
	 * so onGraftReady's downstream `runInBackground` would throw RejectedExecutionException on the UI thread
	 * and leak the held busy flag. The isDestroyed guard short-circuits cleanly; the catch covers any other
	 * UI-thread throw from the apply pipeline.
	 *
	 * @param graft       the assembled graft to install
	 * @param releaseBusy the cleanup lambda that drops busy + clears the UI flags + hides progress
	 */
	private void applyConfirmedGraft(Graft graft, Runnable releaseBusy)
	{
		if (host.isDestroyed())
		{
			Log.w(TAG, "skipping oversized-edit Apply on destroyed activity");
			releaseBusy.run();
			return;
		}
		try
		{
			onGraftReady.onReady(graft);
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "oversized-edit Apply propagated an exception; releasing busy", e);
			releaseBusy.run();
			throw e;
		}
	}

	/**
	 * Bg-thread body of onEditPicked, extracted to satisfy CLAUDE.md's 3-line lambda cap. Reads the picked edit,
	 * snapshots the source, aligns + detects + splices, then hands off to the UI thread via dispatchGraftToUi. Owns
	 * the busy-release-on-failure / clear-snapshot finally contract.
	 *
	 * @param editUri SAF URI of the user-picked edit JPEG
	 */
	private void assembleGraftOnBg(Uri editUri)
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

			// Use the snapshot captured at long-press time, not whatever state currently holds — the user
			// might have loaded a new image while the picker was open. Grafting onto the wrong source would
			// silently produce a file with mismatched metadata vs. primary scan; reading the wrong gain map
			// would route an SDR source through the HDR-mask-detection branch (or vice versa); reading the
			// wrong filename would save under image B's stem when the splice is image A's.
			SourceSnapshot snapshot = pendingSource;
			if (snapshot == null || snapshot.originalBytes() == null)
			{
				toast("Original bytes unavailable — reload the image and try again");
				graftPending = false;
				return;
			}
			byte[] originalBytes = snapshot.originalBytes();

			EditAligner.Result alignment = EditAligner.align(originalBytes, editBytes);
			if (alignment.alignedBytes() == null)
			{
				toast(alignment.errorMessage());
				graftPending = false;
				return;
			}
			byte[] alignedEditBytes = alignment.alignedBytes();

			// Detect the AI-modified pixel region now (we have both source and aligned-edit bytes here).
			// The mask travels through state into UltraHdrCompat at HDR re-encode time, where it patches
			// the gain map's boost values inside the AI fill. Doing the inpaint here on the raw gain-map
			// JPEG forces a Bitmap.compress round-trip that converts source's single-channel grayscale gain
			// map into 3-channel YCbCr, which Android's UHDR decoder then doesn't recognise → HDR is
			// silently dropped at save time. UltraHdrCompat operates on the gain-map Bitmap in source's
			// native single-channel format. Skip detection entirely for SDR sources: the only consumer is
			// UltraHdrCompat.compressWithGainmap, which CropExporter only invokes when state.gainMap !=
			// null. Running detection on a non-HDR source burns memory + CPU for a mask that never gets
			// applied. The gain map check uses the SNAPSHOT, not state — see SourceSnapshot for why state
			// can lie at this point.
			byte[] sourceGainMap = snapshot.gainMap();
			AiMask aiMask = (sourceGainMap != null && sourceGainMap.length > 0)
				? AiRegionDetector.detect(originalBytes, alignedEditBytes)
				: null;

			Graft graft = new Graft(GraftWriter.graft(originalBytes, alignedEditBytes),
				suggestedFilename(snapshot.filename()), aiMask);
			// Compute the mask fraction here on the bg thread so the UI-thread handoff doesn't have to walk
			// a 750k-bool array twice (once for the sanity check, once again for the dialog message).
			// Negative count means "no mask" — both the sanity gate and the dialog code treat that as "skip
			// the dialog".
			int maskedPixelCount = (aiMask != null) ? aiMask.maskedCount() : -1;
			int maskTotal = (aiMask != null) ? aiMask.mask().length : 0;
			graftPending = false;
			handedOff = true;
			host.runOnUiThread(() -> dispatchGraftToUi(graft, maskedPixelCount, maskTotal));
		}
		catch (IOException e)
		{
			Log.w(TAG, "Graft assembly failed", e);
			toast("Graft failed: " + e.getMessage());
			graftPending = false;
		}
		catch (RuntimeException | OutOfMemoryError e)
		{
			// OOM merged with RuntimeException because EditAligner.reorientEdit allocates two full
			// bitmaps in sequence (decode + applyOrientation) and AiRegionDetector + GraftWriter add
			// pixel-array / byte-buffer pressure. A multi-MP HDR source's graft assembly is comparable
			// in allocation to the export path — without OOM here, finally would clear pendingSource +
			// release busy + hide progress, but no "Graft failed" toast would post and the user would
			// have no signal. Mirrors round-17 F5 and the round-18 widenings in
			// ImageLoadController.runLoadBg + MainActivity.applyGraftedBytesOnBg.
			Log.e(TAG, "Unexpected graft error", e);
			toast("Graft failed: " + e.getMessage());
			graftPending = false;
		}
		finally
		{
			// Drop the captured-snapshot reference on every exit path — it served its purpose (locked in
			// the long-pressed source for this graft) and a long-lived strong reference to multi-MB byte[]s
			// is wasted memory. Clearing here covers every path: success (handoff happened), failure (toast
			// + early return), and unexpected throw.
			pendingSource = null;
			// Release busy on every failure path. The success path leaves it held because applyGraftedBytes
			// is about to claim it transitively.
			if (!handedOff)
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

	/**
	 * UI-thread confirm dialog for an unusually-large AI-edit fraction. Apply proceeds with the prepared graft;
	 * Cancel discards it and releases the busy flag GraftController is holding (no MainActivity.applyGraftedBytes
	 * will run, so its finally won't release either). Back-button / outside-tap dismissal routes through the cancel
	 * listener for the same reason. If the Activity is destroyed between the bg-thread post and the dialog show —
	 * common when the user backgrounds the app while the picker is still processing — the destroyed-Activity guard
	 * releases busy and bails rather than throwing WindowManager.BadTokenException.
	 *
	 * Takes the mask fraction (count + total) as parameters rather than re-walking the mask array on the UI thread
	 * — the bg thread already paid the O(N) walk, no point paying it again here.
	 *
	 * Lives here rather than in a util because the cleanup contract — release the busy flag we own, clear the UI's
	 * busy indicator — is GraftController-specific and would leak busy ownership if hoisted to a generic helper.
	 */
	private void confirmOversizedThenApply(Graft graft, int maskedPixelCount, int maskTotal)
	{
		Runnable releaseBusy = this::releaseBusyAndHideProgress;
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
				.setPositiveButton(DialogStrings.APPLY,
					(dialog, which) -> applyConfirmedGraft(graft, releaseBusy))
				.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> releaseBusy.run())
				.setOnCancelListener(dialog -> releaseBusy.run())
				.show();
		}
		catch (RuntimeException e)
		{
			// BadTokenException if the activity died between isDestroyed and show, or any other UI-thread
			// throw from the dialog plumbing. Don't strand busy.
			Log.w(TAG, "oversized-edit dialog failed to show", e);
			releaseBusy.run();
		}
	}

	/**
	 * UI-thread handoff for a successfully-assembled graft. Routes to the oversized-edit confirm dialog when the AI
	 * mask covers a suspiciously-large pixel fraction; otherwise dispatches directly to the onGraftReady callback
	 * that runs applyBytes + state.installGraft. Pre-computed mask totals come from assembleGraftOnBg so this
	 * method doesn't re-walk the bool array.
	 *
	 * Hand off the raw splice without injecting a thumbnail. The splice goes into state.originalFileBytes via
	 * applyBytes and the save pipeline will canvas-encode through CropExporter (forced via state.graftApplied),
	 * which generates a fresh thumbnail in the saved output. Pre-injecting one here would just be discarded by the
	 * encode pass. The receiver runs applyBytes + state.installGraft(graft); installGraft encapsulates the "set
	 * graftApplied AND aiMask, both AFTER reset" rule. The receiver inherits the held busy flag and releases it
	 * when the apply completes (and fires the user-visible "External edit applied" toast on success — firing it
	 * here would lie about state during the brief window between handoff and the bg apply finishing or failing).
	 *
	 * @param graft            the assembled graft to install
	 * @param maskedPixelCount AI-mask masked-pixel count, or -1 when no mask was generated
	 * @param maskTotal        AI-mask total-pixel count (mask array length), or 0 when no mask
	 */
	private void dispatchGraftToUi(Graft graft, int maskedPixelCount, int maskTotal)
	{
		// Activity-destroyed guard: assembleGraftOnBg posts this UI runnable, but the Activity could finish
		// (config change, app backgrounded then killed) between the post and this dispatch. Without the guard,
		// onGraftReady → MainActivity.applyGraftedBytes calls runInBackground on the now-shut-down executor,
		// throwing RejectedExecutionException on the UI thread and leaking the held busy flag. Both the
		// oversized-edit and normal paths route through applyConfirmedGraft so the isDestroyed + try/catch
		// cleanup applies uniformly — a separate `onGraftReady.onReady(graft)` direct call would leave the
		// normal path missing the cleanup applyConfirmedGraft provides.
		Runnable releaseBusy = this::releaseBusyAndHideProgress;
		if (host.isDestroyed())
		{
			Log.w(TAG, "skipping graft handoff on destroyed activity");
			releaseBusy.run();
			return;
		}
		if (isOversizedEdit(maskedPixelCount, maskTotal))
		{
			confirmOversizedThenApply(graft, maskedPixelCount, maskTotal);
		}
		else
		{
			applyConfirmedGraft(graft, releaseBusy);
		}
	}

	/**
	 * Release the held busy flag, clear the UI's busy indicator, and hide the progress overlay. Used as the
	 * cleanup tail by the graft pipeline whenever an Activity-destroyed guard fires, the user cancels the
	 * oversized-edit confirm dialog, or the dialog plumbing throws — anywhere the busy ownership the graft
	 * pipeline holds must be relinquished without proceeding to applyBytes. Hoisted out of two parallel
	 * lambda bodies in confirmOversizedThenApply / dispatchGraftToUi (round-34 style audit P2) so the
	 * three-step cleanup contract (busy flag, busy UI, progress overlay) lives at one chokepoint.
	 */
	private void releaseBusyAndHideProgress()
	{
		host.getBusy().set(false);
		host.setBusyUi(false);
		host.hideProgress();
	}

	/**
	 * Post a short toast. Safe to call from any thread — Activity.runOnUiThread runs the runnable inline when
	 * already on the UI thread, so the indirection is a no-op cost and we don't need separate UI-thread / bg-thread
	 * variants.
	 *
	 * @param msg short user-facing message
	 */
	private void toast(String msg)
	{
		host.runOnUiThread(() -> host.toastIfAlive(msg, Toast.LENGTH_SHORT));
	}
}
