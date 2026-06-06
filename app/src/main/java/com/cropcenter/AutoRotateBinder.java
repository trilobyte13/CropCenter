package com.cropcenter;

import android.graphics.Bitmap;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.cropcenter.util.HorizonDetector;
import com.cropcenter.util.RotationMath;
import com.cropcenter.util.TextFormat;
import com.cropcenter.view.DialogStrings;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the auto-rotate button + horizon-paint-and-detect flow. Three paths from the tap, in order: cancel paint mode
 * if active; apply XMP-embedded horizon angle if present in the source's metadata; else enter paint mode and hand off
 * to HorizonDetector when the user finishes painting.
 *
 * Extracted from ToolbarBinder so the horizon-detection orchestration (which owns its own paint-mode lifecycle, runs
 * the detector on the bg thread, and translates the result into rotation state + ruler zoom) doesn't share a class with
 * the simpler "wire button to setter" bindings. ToolbarBinder.bindAll() instantiates one of these and calls bind().
 */
final class AutoRotateBinder
{
	private static final String TAG = "AutoRotateBinder";

	// User-facing failure toast — fires from two distinct paths (catch + NaN-result branch) inside
	// runHorizonDetectionInBackground. Extracted so a future copy-edit lands once.
	private static final String DETECTION_FAILED_TOAST = "Horizon detection failed";

	private final ToolbarHost host;

	AutoRotateBinder(ToolbarHost host)
	{
		this.host = host;
	}

	/**
	 * Wire the auto-rotate button to handleAutoRotateTap. Called once from ToolbarBinder.bindAll() after view
	 * inflation.
	 */
	void bind()
	{
		TextView btn = host.findViewById(R.id.btnAutoRotate);
		btn.setOnClickListener(view -> handleAutoRotateTap(btn));
	}

	/**
	 * Externally-driven exit from horizon paint mode. Used by MainActivity.installImageOnUi when a new image
	 * loads while paint mode is active — without this hook, the new image would route the user's first touch
	 * to horizon painting on the freshly-loaded source instead of the expected Select / Move behavior, plus
	 * the Auto button would stay stuck on its "Cancel" label / red color from the previous load.
	 *
	 * No-op when paint mode isn't active. Doesn't touch the busy flag — paint mode never holds it
	 * (acquisition happens later in onHorizonPaintComplete after the user commits the stroke), so there's
	 * nothing to release.
	 */
	void cancelHorizonPaintMode()
	{
		if (!host.getEditorView().isHorizonMode())
		{
			return;
		}
		host.getEditorView().setHorizonMode(false, null);
		// Direct deref matches the same R.id.btnAutoRotate lookup in bind() and UiSync — both trust the
		// lookup. The view is statically declared in activity_main.xml and the call
		// is always reached after onCreate's setContentView; a hypothetical null here would be a deeper
		// inflation bug that a defensive guard would only mask.
		resetAutoRotateButton(host.findViewById(R.id.btnAutoRotate));
	}

	/**
	 * Read back a HARDWARE-config bitmap to an ARGB_8888 copy so HorizonDetector.buildEdgeMap (which uses
	 * getPixels(), null on HARDWARE) can walk it. Returns src unchanged when already an in-memory config (the
	 * aliased-source case where createDisplayProxy returned the source itself), so the caller pays the ~64 MB
	 * readback only when the proxy is GPU-resident.
	 *
	 * Ownership by identity: a reference identical to src must NOT be recycled (it's the live display proxy); a
	 * distinct reference is a fresh copy the caller owns and recycles after detection. Null signals the GPU
	 * readback failed — caller surfaces a clean detection-failure toast rather than crashing on getPixels().
	 *
	 * @param src the bitmap to read back from; must be non-null
	 * @return src itself for non-HARDWARE configs; a fresh ARGB_8888 copy for HARDWARE; or null on readback
	 *         failure (logged here). Failure covers Bitmap.copy returning null and the copy throwing — the catch
	 *         takes Exception + OutOfMemoryError since a 16 MP HARDWARE→ARGB readback can exhaust native heap or
	 *         surface a Skia fault as an unchecked exception
	 */
	private static Bitmap tryReadbackArgb(Bitmap src)
	{
		if (src.getConfig() != Bitmap.Config.HARDWARE)
		{
			return src;
		}
		Bitmap argbCopy;
		try
		{
			argbCopy = src.copy(Bitmap.Config.ARGB_8888, false);
		}
		catch (RuntimeException | OutOfMemoryError e)
		{
			// Narrow catch: RuntimeException + OOM specifically. OOM is the realistic failure on a 16 MP
			// readback when system memory is tight; RuntimeException covers any Skia / driver fault that
			// surfaces as a thrown unchecked rather than a null return. NOT catching Throwable so other
			// Error subclasses (LinkageError, ThreadDeath) propagate as the JVM intended.
			Log.w(TAG, "horizon detection: HARDWARE->ARGB readback threw", e);
			return null;
		}
		if (argbCopy == null)
		{
			Log.w(TAG, "horizon detection: HARDWARE->ARGB readback returned null");
			return null;
		}
		return argbCopy;
	}

	/**
	 * Apply a detected rotation and zoom the ruler so the user can fine-tune within ~0.01° immediately. Shared
	 * between the metadata-fast path and the painted-horizon background path.
	 *
	 * @param degrees   rotation angle in degrees
	 * @param toastText text to surface to the user
	 */
	private void applyDetectedRotation(float degrees, String toastText)
	{
		host.getState().setRotationDegrees(degrees);
		host.getRotationRuler().zoomToMax();
		host.toastIfAlive(toastText, Toast.LENGTH_SHORT);
	}

	/**
	 * Auto-rotate click handler. Three paths, in order: cancel paint mode if active; apply XMP-embedded horizon
	 * angle if present; else enter paint mode to let the user outline the horizon for background detection.
	 *
	 * @param btn the auto-rotate button (its label / color toggles between Auto and
	 *            Cancel as the paint mode toggles)
	 */
	private void handleAutoRotateTap(TextView btn)
	{
		if (host.getState().getSourceImage() == null)
		{
			return;
		}
		// Cancel-paint-mode is allowed even when busy is held (the bg horizon detector is what holds it, and
		// the cancel just exits the paint state without touching the bg work). Every other path enters or
		// queries detection state, so reject during busy: a tap that lands while bg detection is in flight
		// would otherwise (a) clear imagePoints via setHorizonMode(true,...) while the bg thread iterates
		// the same list (CME), or (b) overwrite the rotation result the in-flight detector is about to apply.
		if (host.getEditorView().isHorizonMode())
		{
			host.getEditorView().setHorizonMode(false, null);
			resetAutoRotateButton(btn);
			return;
		}
		if (host.getBusy().get())
		{
			host.showBusyToast();
			return;
		}

		float metaAngle = HorizonDetector.detectFromMetadata(host.getState().getJpegMeta());
		if (!Float.isNaN(metaAngle))
		{
			applyDetectedRotation(metaAngle, "From metadata: " + TextFormat.degrees(metaAngle));
			return;
		}

		btn.setText(DialogStrings.CANCEL);
		btn.setTextColor(host.getActivity().getResources().getColor(R.color.red, null));
		// Pass both callbacks: onDrawn fires on ACTION_UP (user committed the stroke); onCancel fires on
		// ACTION_CANCEL (OS / parent view interrupted before commit, OR user never started a stroke and
		// a CANCEL fired pre-DOWN). Without the onCancel, an interrupted paint left the overlay in a
		// stuck-active state that consumed all subsequent touches — observed as a "frozen editor" until
		// the user noticed the still-Cancel-labelled Auto button. resetAutoRotateButton restores the
		// "Auto" label so the user has a clear "you're back in normal mode" cue.
		host.getEditorView().setHorizonMode(true,
			() -> onHorizonPaintComplete(btn),
			() -> resetAutoRotateButton(btn));
	}

	/**
	 * UI-thread handler for the detection result. Releases the busy flag held since onHorizonPaintComplete (so a
	 * load tap that came in during detection can now proceed), hides the progress overlay, then applies the
	 * rotation. Rounds to 0.01° precision for display smoothness; setRotationDegrees only snaps magnitudes below
	 * BitmapUtils.ROTATION_EPSILON (0.005°) to exactly 0, so the post-round 0.01° / 0.02° / 0.03° / 0.04° values
	 * the detector and ruler can produce all survive end-to-end (renderer rotates, readout shows, ExportPipeline
	 * doesn't bypass). Toasts the rounded result — or a "no line detected" message when the detector returns NaN.
	 *
	 * Busy stays held until this point — not released in runHorizonDetectionInBackground's finally — so a load
	 * tap racing the detection result can't apply our rotation to the new image's state. The brief window
	 * between busy release and the next load tap is fine because applyDetectedRotation has already returned by
	 * then.
	 *
	 * @param detected the detector's reported angle, or NaN when no line was found
	 */
	private void onHorizonDetectionResult(float detected)
	{
		try
		{
			if (host.isDestroyed())
			{
				return;
			}
			host.setBusyUi(false);
			host.hideProgress();
			if (Float.isNaN(detected))
			{
				host.toastIfAlive("No line detected in painted area", Toast.LENGTH_SHORT);
				return;
			}
			float newRot = RotationMath.snapToHundredth(detected);
			applyDetectedRotation(newRot, TextFormat.degrees(newRot));
		}
		finally
		{
			host.getBusy().set(false);
		}
	}

	/**
	 * Horizon-paint callback: reset the button, grab the painted points, claim the busy flag, and dispatch the
	 * detection pipeline on the background executor. Empty / too-short paints surface a toast and return
	 * immediately. A busy-rejected tap (another bg op already running) shows the busy toast and bails.
	 *
	 * Busy is acquired here — not just the touch-blocking progress overlay — because a Share/View intent that
	 * arrives mid-detection bypasses UI taps entirely and would race the detection result: the load's "Loading…"
	 * overlay would be dismissed early when onHorizonDetectionResult hides progress, and the detected rotation
	 * could be applied to the newly loading image's state. Holding busy makes the load wait (or get rejected
	 * with the busy toast if the user taps Save/Open during paint+detect). Released in onHorizonDetectionResult
	 * after rotation is applied.
	 *
	 * @param btn the auto-rotate button (reset to its resting state regardless of the
	 *            detection outcome — the paint phase has ended)
	 */
	private void onHorizonPaintComplete(TextView btn)
	{
		resetAutoRotateButton(btn);
		// Early-return gates BEFORE claiming busy so a "paint too short" path doesn't lock anyone
		// else out of the editor. These reads are pure inspection — they don't bind us to the
		// state references below.
		if (host.getEditorView().getHorizonPoints().size() < 2
			|| host.getState().getSourceImage() == null
			|| host.getState().getDisplayImage() == null)
		{
			host.toastIfAlive("Paint was too short", Toast.LENGTH_SHORT);
			return;
		}
		// Acquire busy BEFORE snapshotting + transforming the points / bitmaps. The UI thread is
		// single-threaded so an inbound Share intent can't preempt the snapshot block today, but
		// the discipline "claim the lock before reading lock-protected state" guards against a
		// future refactor that introduces a yielding callback or posts an intermediate UI message
		// between the reads and the dispatch. Without busy held, a state.reset() racing in
		// after our snapshot would leave our bitmaps detached from the in-memory state — the
		// detection would compute against the OLD source while the new source had taken its place.
		if (!host.getBusy().compareAndSet(false, true))
		{
			host.showBusyToast();
			return;
		}
		// Snapshot the live points list before dispatching to the bg detector. HorizonPaintOverlay.getPoints()
		// returns the in-progress imagePoints ArrayList; a subsequent setHorizonMode(true, ...) (entered if a
		// new tap reaches handleAutoRotateTap before the busy gate took effect, e.g. through a future code
		// path that bypasses busy) would clear imagePoints while the bg detector is iterating it — CME on bg.
		// The copy is small (typically a few hundred 2-element float arrays) and is the only correct fix
		// because the bg detector deliberately reads outside the busy-held UI thread.
		List<float[]> rawPoints = new ArrayList<>(host.getEditorView().getHorizonPoints());
		float brushRadius = host.getEditorView().getHorizonBrushRadius();
		Bitmap source = host.getState().getSourceImage();
		Bitmap display = host.getState().getDisplayImage();
		// Re-check source/display under the busy lock — between the early-return gate above and
		// this snapshot, the references must still be non-null. (A concurrent reset can't happen
		// while we hold busy, so this is purely paranoid; we release busy on any failure path.)
		if (source == null || display == null)
		{
			host.getBusy().set(false);
			host.toastIfAlive("Source image unavailable", Toast.LENGTH_SHORT);
			return;
		}
		// Zero-dimension guard. proxyScale = display.getWidth() / source.getWidth() below would divide
		// by zero (Infinity / NaN) on a 0-width source — real Bitmaps from BitmapFactory always have
		// positive dims, but a recycled-mid-snapshot race produces a zero-width bitmap whose NaN
		// proxyScale would propagate into the points passed to HorizonDetector. Mirror
		// EditorRenderer.draw's matching guard so the failure surfaces as a clean toast rather than as
		// a silently NaN-poisoned detect.
		if (source.getWidth() <= 0 || source.getHeight() <= 0
			|| display.getWidth() <= 0 || display.getHeight() <= 0)
		{
			host.getBusy().set(false);
			host.toastIfAlive("Source image unavailable", Toast.LENGTH_SHORT);
			return;
		}
		// Route detection through the display proxy: buildEdgeMap allocates 3 float[] arrays at width × height,
		// which at 192 MP source = 2.3 GB working set (multi-second compute) but at 16 MP proxy = ~192 MB
		// (~12× faster Hough vote). Painted points are stored in source-coordinate space; rescale them to
		// proxy-coordinate space so the bitmap passed to HorizonDetector and the points/brushRadius it
		// consumes share a consistent coordinate frame. proxyScale = 1 in the aliased case (display ==
		// source when the source already fit MAX_DISPLAY_PIXELS), making the rescale a no-op copy.
		// The returned angle is scale-invariant (line slopes don't change under uniform scaling), so no
		// post-detection rescale is needed when applying the result to source-coordinate rotation state.
		float proxyScale = (float) display.getWidth() / source.getWidth();
		List<float[]> points = new ArrayList<>(rawPoints.size());
		for (float[] point : rawPoints)
		{
			points.add(new float[] { point[0] * proxyScale, point[1] * proxyScale });
		}
		float scaledBrushRadius = brushRadius * proxyScale;
		Bitmap src = display;
		// Pre-enqueue cleanup guard — any throw from setBusyUi / showProgress / runInBackground would otherwise
		// strand busy=true (the bg-task failure path only runs if the Runnable was accepted).
		try
		{
			host.setBusyUi(true);
			host.showProgress("Detecting horizon…");
			host.runInBackground(() -> runHorizonDetectionInBackground(src, points, scaledBrushRadius));
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
	 * Restore the Auto-rotate button to its resting "Auto" label + subtext0 color.
	 *
	 * @param btn the auto-rotate button
	 */
	private void resetAutoRotateButton(TextView btn)
	{
		btn.setText("Auto");
		btn.setTextColor(host.getActivity().getResources().getColor(R.color.subtext0, null));
	}

	/**
	 * Background detection job. Runs on the single-thread executor; posts all UI mutation (toast, hideProgress,
	 * rotation update) back through runOnUiThread. Wrapped so a throw inside HorizonDetector can't leave the
	 * progress overlay stuck or the busy flag held.
	 *
	 * Failure path: releases busy, clears UI, surfaces a toast. Success path: leaves busy held — the UI runnable
	 * onHorizonDetectionResult will release it after applying rotation, so a load tap racing the result can't
	 * apply our rotation to the new image's state.
	 *
	 * @param src         source bitmap (read-only here)
	 * @param points      painted polyline in image-coordinate space
	 * @param brushRadius brush radius in image pixels
	 */
	private void runHorizonDetectionInBackground(Bitmap src, List<float[]> points, float brushRadius)
	{
		// HARDWARE-config bitmaps are GPU-resident and return null from getPixels() — the path
		// HorizonDetector.buildEdgeMap relies on. BitmapUtils.createDisplayProxy returns a HARDWARE
		// bitmap whenever the source needed downscaling (the common large-source case) because keeping
		// the proxy on the GPU eliminates per-frame texture re-uploads during pan/zoom gestures. The
		// trade-off lands here: readback the HARDWARE pixels to a fresh ARGB_8888 bitmap once per
		// detection so HorizonDetector can walk them. The readback is ~50-200 ms on a 16 MP bitmap —
		// only fires when the user invokes auto-rotate, not per render frame. When the proxy is the
		// aliased source (sub-cap source), getConfig() is ARGB_8888 and we skip the copy.
		Bitmap detectBitmap = tryReadbackArgb(src);
		if (detectBitmap == null)
		{
			// HARDWARE→ARGB readback failed (GPU memory exhausted or driver bug). Surface a clean
			// detection-failure toast rather than crashing on src.getPixels() inside the detector.
			// Clear busy LAST, on the UI thread, after teardown (see EditorHost.finishBusy). The toast is a
			// separate post — it doesn't gate state, so landing after busy is released is fine.
			host.finishBusy();
			host.runOnUiThread(() -> host.toastIfAlive(DETECTION_FAILED_TOAST, Toast.LENGTH_SHORT));
			return;
		}
		float angle;
		try
		{
			angle = HorizonDetector.detectFromPaintedRegion(detectBitmap, points, brushRadius);
		}
		catch (Exception | StackOverflowError e)
		{
			// Narrow catch: Exception + StackOverflowError specifically. A degenerate Hough search can blow
			// the stack; that's the one Error subclass worth recovering from here. Catching Throwable
			// (OutOfMemoryError, LinkageError, ThreadDeath) would let the recovery handler itself fail or
			// worsen the situation. HorizonDetector already catches OutOfMemoryError internally.
			Log.w(TAG, "horizon detection failed", e);
			// Clear busy LAST, on the UI thread, after teardown (see EditorHost.finishBusy). The toast is a
			// separate post — it doesn't gate state, so landing after busy is released is fine.
			host.finishBusy();
			host.runOnUiThread(() -> host.toastIfAlive(DETECTION_FAILED_TOAST, Toast.LENGTH_SHORT));
			return;
		}
		finally
		{
			if (detectBitmap != src)
			{
				// Identity check separates the readback copy (recycle to release the ~64 MB native
				// buffer promptly) from the aliased-source case (live display proxy in CropState —
				// must not be recycled). tryReadbackArgb returns src itself for non-HARDWARE inputs;
				// only the HARDWARE branch produces a distinct reference.
				detectBitmap.recycle();
			}
		}
		final float detected = angle;
		host.runOnUiThread(() -> onHorizonDetectionResult(detected));
	}
}
