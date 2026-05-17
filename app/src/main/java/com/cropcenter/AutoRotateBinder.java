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
		// Direct deref matches bind() at line 42 and UiSync.java:54 — both look up R.id.btnAutoRotate the
		// same way and trust the lookup. The view is statically declared in activity_main.xml and the call
		// is always reached after onCreate's setContentView; a hypothetical null here would be a deeper
		// inflation bug that a defensive guard would only mask.
		resetAutoRotateButton(host.findViewById(R.id.btnAutoRotate));
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
		host.getEditorView().setHorizonMode(true, () -> onHorizonPaintComplete(btn));
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
		// Snapshot the live points list before dispatching to the bg detector. HorizonPaintOverlay.getPoints()
		// returns the in-progress imagePoints ArrayList; a subsequent setHorizonMode(true, ...) (entered if a
		// new tap reaches handleAutoRotateTap before the busy gate took effect, e.g. through a future code
		// path that bypasses busy) would clear imagePoints while the bg detector is iterating it — CME on bg.
		// The copy is small (typically a few hundred 2-element float arrays) and is the only correct fix
		// because the bg detector deliberately reads outside the busy-held UI thread.
		List<float[]> points = new ArrayList<>(host.getEditorView().getHorizonPoints());
		float brushRadius = host.getEditorView().getHorizonBrushRadius();
		Bitmap src = host.getState().getSourceImage();

		if (points.size() < 2 || src == null)
		{
			host.toastIfAlive("Paint was too short", Toast.LENGTH_SHORT);
			return;
		}

		if (!host.getBusy().compareAndSet(false, true))
		{
			host.showBusyToast();
			return;
		}
		// Pre-enqueue cleanup guard — any throw from setBusyUi / showProgress / runInBackground would otherwise
		// strand busy=true (the bg-task failure path only runs if the Runnable was accepted).
		try
		{
			host.setBusyUi(true);
			host.showProgress("Detecting horizon…");
			host.runInBackground(() -> runHorizonDetectionInBackground(src, points, brushRadius));
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
		float angle;
		try
		{
			angle = HorizonDetector.detectFromPaintedRegion(src, points, brushRadius);
		}
		catch (Exception | StackOverflowError e)
		{
			// Narrow catch: Exception + StackOverflowError specifically. A degenerate Hough search can blow
			// the stack; that's the one Error subclass worth recovering from here. Catching Throwable
			// (OutOfMemoryError, LinkageError, ThreadDeath) would let the recovery handler itself fail or
			// worsen the situation. HorizonDetector already catches OutOfMemoryError internally.
			Log.w(TAG, "horizon detection failed", e);
			host.getBusy().set(false);
			host.runOnUiThread(() ->
			{
				host.setBusyUi(false);
				host.hideProgress();
				host.toastIfAlive("Horizon detection failed", Toast.LENGTH_SHORT);
			});
			return;
		}
		final float detected = angle;
		host.runOnUiThread(() -> onHorizonDetectionResult(detected));
	}
}
