package com.cropcenter;

import android.graphics.Bitmap;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.cropcenter.util.HorizonDetector;
import com.cropcenter.util.TextFormat;

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
		Toast.makeText(host.getActivity(), toastText, Toast.LENGTH_SHORT).show();
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
		if (host.getEditorView().isHorizonMode())
		{
			host.getEditorView().setHorizonMode(false, null);
			resetAutoRotateButton(btn);
			return;
		}

		float metaAngle = HorizonDetector.detectFromMetadata(host.getState().getJpegMeta());
		if (!Float.isNaN(metaAngle))
		{
			applyDetectedRotation(metaAngle, "From metadata: " + TextFormat.degrees(metaAngle));
			return;
		}

		btn.setText("Cancel");
		btn.setTextColor(host.getActivity().getResources().getColor(R.color.red, null));
		host.getEditorView().setHorizonMode(true, () -> onHorizonPaintComplete(btn));
	}

	/**
	 * UI-thread handler for the detection result. Rounds to 0.01° precision for display smoothness, then hands off
	 * to setRotationDegrees which snaps sub-epsilon magnitudes to exactly 0 (so a horizon detected at e.g. 0.03°
	 * stores as 0 rather than as a value the rest of the pipeline silently treats as zero). Toasts the rounded
	 * result — or a "no line detected" message when the detector returns NaN.
	 *
	 * @param detected the detector's reported angle, or NaN when no line was found
	 */
	private void onHorizonDetectionResult(float detected)
	{
		if (host.isDestroyed())
		{
			return;
		}
		host.hideProgress();
		if (Float.isNaN(detected))
		{
			Toast.makeText(host.getActivity(), "No line detected in painted area",
				Toast.LENGTH_SHORT).show();
			return;
		}
		float newRot = Math.round(detected * 100f) / 100f;
		applyDetectedRotation(newRot, TextFormat.degrees(newRot));
	}

	/**
	 * Horizon-paint callback: reset the button, grab the painted points, and dispatch the detection pipeline on the
	 * background executor. Empty / too-short paints surface a toast and return immediately.
	 *
	 * @param btn the auto-rotate button (reset to its resting state regardless of the
	 *            detection outcome — the paint phase has ended)
	 */
	private void onHorizonPaintComplete(TextView btn)
	{
		resetAutoRotateButton(btn);
		var points = host.getEditorView().getHorizonPoints();
		float brushRadius = host.getEditorView().getHorizonBrushRadius();
		Bitmap src = host.getState().getSourceImage();

		if (points.size() < 2 || src == null)
		{
			Toast.makeText(host.getActivity(), "Paint was too short", Toast.LENGTH_SHORT).show();
			return;
		}

		host.showProgress("Detecting horizon…");
		host.runInBackground(() -> runHorizonDetectionInBackground(src, points, brushRadius));
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
	 * progress overlay stuck.
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
		catch (Exception | StackOverflowError t)
		{
			// Narrow catch: Exception + StackOverflowError specifically. A degenerate Hough search can blow
			// the stack; that's the one Error subclass worth recovering from here. Catching Throwable
			// (OutOfMemoryError, LinkageError, ThreadDeath) would let the recovery handler itself fail or
			// worsen the situation. HorizonDetector already catches OutOfMemoryError internally.
			Log.w(TAG, "horizon detection failed", t);
			host.hideProgress();
			host.runOnUiThread(() -> host.toastIfAlive("Horizon detection failed", Toast.LENGTH_SHORT));
			return;
		}
		final float detected = angle;
		host.runOnUiThread(() -> onHorizonDetectionResult(detected));
	}
}
