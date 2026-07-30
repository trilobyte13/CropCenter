package com.cropcenter.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-contained state + rendering for the auto-rotate "paint over the horizon" overlay. The host view drives touch
 * routing (it owns the MotionEvent stream) and calls begin / extend / end to update the stroke; this class just stores
 * points in image space, builds a screen-space Path for display, and renders the stroke + placeholder hint.
 *
 * No viewport / rotation math lives here — callers pass already-converted image points.
 */
final class HorizonPaintOverlay
{
	// Painted-stroke width = the visual brush DIAMETER: 2 × the screen-pixel brush radius CropEditorView feeds the
	// detector (getHorizonBrushRadius), so the stroke the user sees is exactly the region HorizonDetector samples.
	// Deriving (not hardcoding 60f) keeps the two in lockstep if the shared threshold changes.
	private static final float STROKE_WIDTH_PX = 2f * CropEditorView.TOUCH_THRESHOLD_PX;

	private final List<float[]> imagePoints = new ArrayList<>(); // image-pixel coords
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path screenPath = new Path();

	private Runnable onCancel; // host-side cleanup callback fired when paint mode exits via CANCEL
	private Runnable onDrawn;
	private boolean active;    // listening for paint input
	private boolean drawing;   // actively mid-stroke

	HorizonPaintOverlay()
	{
		paint.setStrokeWidth(3f);
		paint.setStyle(Paint.Style.STROKE);
	}

	/**
	 * Decide whether a completed stroke is too short to carry directional information for the horizon line fit.
	 * Measures the bounding-box diagonal of the painted points — endpoint distance would mis-classify a V-shaped
	 * stroke whose ends coincide. Point count alone can't detect a degenerate stroke: a bare tap still contributes
	 * two coincident begin / end points, so tap-strokes must be rejected by distance.
	 *
	 * @param points  painted polyline as {x, y} pairs in image-pixel coordinates
	 * @param minSpan minimum bounding-box diagonal in image pixels; a stroke spanning exactly minSpan is
	 *                accepted
	 * @return true when the stroke has fewer than 2 points or its bounding-box diagonal is less than minSpan
	 */
	static boolean isStrokeTooShort(List<float[]> points, float minSpan)
	{
		if (points.size() < 2)
		{
			return true;
		}
		float minX = Float.POSITIVE_INFINITY;
		float minY = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float maxY = Float.NEGATIVE_INFINITY;
		for (float[] point : points)
		{
			minX = Math.min(minX, point[0]);
			minY = Math.min(minY, point[1]);
			maxX = Math.max(maxX, point[0]);
			maxY = Math.max(maxY, point[1]);
		}
		return Math.hypot(maxX - minX, maxY - minY) < minSpan;
	}

	/**
	 * Record the first touch of a stroke. screenX/screenY go into the displayed path; imagePoint is the
	 * already-un-rotated image-pixel coordinate for the detector.
	 *
	 * @param screenX    horizontal screen coordinate of the touch (used for path rendering)
	 * @param screenY    vertical screen coordinate of the touch (used for path rendering)
	 * @param imagePoint two-element image-pixel coordinate {x, y} consumed by the detector
	 */
	void begin(float screenX, float screenY, float[] imagePoint)
	{
		imagePoints.clear();
		screenPath.reset();
		screenPath.moveTo(screenX, screenY);
		imagePoints.add(imagePoint);
		drawing = true;
	}

	/**
	 * Discard the in-progress stroke AND exit paint mode. Used for `MotionEvent.ACTION_CANCEL` — Android dispatches
	 * that when the OS or a parent view claims the gesture (e.g. system back, multi-touch disambiguation, scroll
	 * container intercept), which is NOT a user-initiated stroke completion. Treating ACTION_CANCEL as if it were
	 * ACTION_UP would feed a truncated point list into `HorizonDetector.detectFromPaintedRegion` and produce a
	 * wrong-angle auto-rotate the user never asked for, so the detection callback is NOT invoked.
	 *
	 * Paint mode is exited (`active=false`) and the caller's `onCancel` runnable is fired so it can reset external
	 * UI (e.g. AutoRotateBinder's "Cancel" → "Auto" button label). Exiting on CANCEL (rather than staying active)
	 * avoids stranding the user in paint mode — where every subsequent touch is consumed by the overlay — until
	 * they notice the Cancel button; they can re-tap Auto to retry.
	 */
	void cancelStroke()
	{
		drawing = false;
		active = false;
		imagePoints.clear();
		screenPath.reset();
		if (onCancel != null)
		{
			Runnable callback = onCancel;
			onCancel = null;
			callback.run();
		}
	}

	/**
	 * Render the painted-region polygon and a hint label. No-op when neither active (user has finished painting and
	 * the region is being processed) nor drawing (user is mid-stroke). The polygon uses the shared selection color
	 * from GridConfig so markers, polygon, and horizon paint stay visually consistent.
	 *
	 * @param canvas     destination canvas
	 * @param viewWidth  view width in screen px (for centering the hint label)
	 * @param viewHeight view height in screen px
	 * @param selColor   shared selection color (ARGB)
	 * @param infoPaint  pre-configured paint for the hint label text
	 * @param density    display density for stroke width / text size scaling
	 */
	void draw(Canvas canvas, int viewWidth, int viewHeight, int selColor, Paint infoPaint, float density)
	{
		if (!active && !drawing)
		{
			return;
		}
		if (drawing && !screenPath.isEmpty())
		{
			// Paint with the user's exact selection color — no extra blending.
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(STROKE_WIDTH_PX);
			paint.setColor(selColor);
			paint.setStrokeCap(Paint.Cap.ROUND);
			paint.setStrokeJoin(Paint.Join.ROUND);
			canvas.drawPath(screenPath, paint);
			paint.setStrokeWidth(3f);
			paint.setStrokeCap(Paint.Cap.BUTT);
		}
		if (active && !drawing)
		{
			// Waiting for paint — show hint in the selection color.
			infoPaint.setTextAlign(Paint.Align.CENTER);
			infoPaint.setTextSize(14f * density);
			infoPaint.setColor(selColor);
			canvas.drawText("Paint over the horizon", viewWidth / 2f, viewHeight / 2f, infoPaint);
		}
	}

	/**
	 * Record the final touch (image-space only; the screen-space path isn't extended for the ACTION_UP point) and
	 * exit paint mode. The caller's onDrawn callback runs; the overlay stays non-active until the next
	 * setActive(true, ...) call.
	 *
	 * @param imagePoint two-element image-pixel coordinate {x, y} for the ACTION_UP point
	 */
	void end(float[] imagePoint)
	{
		imagePoints.add(imagePoint);
		drawing = false;
		active = false;
		if (onDrawn != null)
		{
			onDrawn.run();
		}
	}

	/**
	 * Append a mid-stroke touch — updates the displayed path and appends another image coordinate for the detector.
	 *
	 * @param screenX    horizontal screen coordinate of the touch
	 * @param screenY    vertical screen coordinate of the touch
	 * @param imagePoint two-element image-pixel coordinate {x, y} consumed by the detector
	 */
	void extend(float screenX, float screenY, float[] imagePoint)
	{
		screenPath.lineTo(screenX, screenY);
		imagePoints.add(imagePoint);
	}

	List<float[]> getPoints()
	{
		return imagePoints;
	}

	boolean isActive()
	{
		return active;
	}

	boolean isDrawing()
	{
		return drawing;
	}

	/**
	 * Enter or exit paint mode without a cancellation callback. Forwards to the three-arg overload with
	 * onCancelCallback=null; see that overload's Javadoc for the lifecycle contract.
	 *
	 * @param on              true to enter paint mode, false to exit
	 * @param onDrawnCallback invoked from `end` when the user completes a stroke
	 */
	void setActive(boolean on, Runnable onDrawnCallback)
	{
		setActive(on, onDrawnCallback, null);
	}

	/**
	 * Enter paint mode with both completion and cancellation callbacks. `onCancelCallback` fires from
	 * `cancelStroke` (ACTION_CANCEL) so the host can reset any UI tied to paint mode (e.g. AutoRotateBinder's
	 * "Cancel" → "Auto" button label). The cancel callback runs ONCE then clears itself, so a subsequent stroke
	 * completion via `end` doesn't double-fire it.
	 *
	 * @param on               true to enter paint mode, false to exit
	 * @param onDrawnCallback  invoked from `end` when the user completes a stroke
	 * @param onCancelCallback invoked from `cancelStroke` when the OS / parent view interrupts the stroke,
	 *                         or when a future caller-side cancel path needs the same cleanup
	 */
	void setActive(boolean on, Runnable onDrawnCallback, Runnable onCancelCallback)
	{
		this.active = on;
		this.drawing = false;
		this.onDrawn = onDrawnCallback;
		this.onCancel = onCancelCallback;
		imagePoints.clear();
		screenPath.reset();
	}
}
