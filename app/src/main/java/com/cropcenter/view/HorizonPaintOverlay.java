package com.cropcenter.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-contained state + rendering for the auto-rotate "paint over the horizon" overlay.
 * The host view drives touch routing (it owns the MotionEvent stream) and calls begin /
 * extend / end to update the stroke; this class just stores points in image space, builds
 * a screen-space Path for display, and renders the stroke + placeholder hint.
 *
 * No viewport / rotation math lives here — callers pass already-converted image points.
 */
final class HorizonPaintOverlay
{
	private final List<float[]> imagePoints = new ArrayList<>(); // image-pixel coords
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path screenPath = new Path();

	private Runnable onDrawn;
	private boolean active;    // listening for paint input
	private boolean drawing;   // actively mid-stroke

	HorizonPaintOverlay()
	{
		paint.setStrokeWidth(3f);
		paint.setStyle(Paint.Style.STROKE);
	}

	/**
	 * Record the first touch of a stroke. screenX/screenY go into the displayed path;
	 * imagePoint is the already-un-rotated image-pixel coordinate for the detector.
	 */
	void begin(float screenX, float screenY, float[] imagePoint)
	{
		imagePoints.clear();
		screenPath.reset();
		screenPath.moveTo(screenX, screenY);
		imagePoints.add(imagePoint);
		drawing = true;
	}

	void draw(Canvas canvas, int viewWidth, int viewHeight, int selColor, Paint infoPaint,
		float density)
	{
		if (!active && !drawing)
		{
			return;
		}
		if (drawing && !screenPath.isEmpty())
		{
			// Paint with the user's exact selection color — no extra blending.
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(60f);
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
			canvas.drawText("Paint over the horizon",
				viewWidth / 2f, viewHeight / 2f, infoPaint);
		}
	}

	/**
	 * Record the final touch (image-space only; the screen-space path isn't extended for the
	 * ACTION_UP point) and exit paint mode. The caller's onDrawn callback runs; the overlay
	 * stays non-active until the next setActive(true, ...) call.
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
	 * Append a mid-stroke touch — updates the displayed path and appends another image
	 * coordinate for the detector.
	 */
	void extend(float screenX, float screenY, float[] imagePoint)
	{
		screenPath.lineTo(screenX, screenY);
		imagePoints.add(imagePoint);
	}

	/**
	 * Image-space points collected during the stroke, consumed by the horizon detector.
	 */
	List<float[]> getPoints()
	{
		return imagePoints;
	}

	/**
	 * True while the overlay is listening for paint input (set by setActive(true, ...)).
	 */
	boolean isActive()
	{
		return active;
	}

	/**
	 * True during an in-progress stroke (between ACTION_DOWN and ACTION_UP).
	 */
	boolean isDrawing()
	{
		return drawing;
	}

	/**
	 * Enter or exit paint mode. Entering clears any previous stroke. Exiting (on=false) also
	 * clears; the caller's previous onDrawn is replaced.
	 */
	void setActive(boolean on, Runnable onDrawnCallback)
	{
		this.active = on;
		this.drawing = false;
		this.onDrawn = onDrawnCallback;
		imagePoints.clear();
		screenPath.reset();
	}
}
