package com.cropcenter.view;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/**
 * Handles touch gestures for the crop editor:
 *  - Single finger drag: set center (click mode) or pan (browse mode)
 *  - Two finger pinch: zoom with pivot
 *  - Double tap: fit image to view
 *  - Long press: remove point in select mode
 */
public final class TouchGestureHandler
{
	/**
	 * Receives the high-level gesture events recognized by the handler. The view that owns the handler implements
	 * this and translates events into crop-state mutations. Coordinates passed in are in the view's local screen
	 * space (px), not image space — the view is responsible for any conversion.
	 */
	public interface Callback
	{
		/**
		 * Double-tap detected. The crop editor uses this to fit the image to the view.
		 */
		void onDoubleTap();

		/**
		 * Long-press detected at the given screen position. The crop editor uses this to remove a selection
		 * point in select mode.
		 */
		void onLongPress(float screenX, float screenY);

		/**
		 * Single-finger drag — `dx` / `dy` are the deltas since the last onPan call (already negated from
		 * GestureDetector's distance convention so positive dx means finger moved right).
		 */
		void onPan(float dx, float dy);

		/**
		 * Fires once on ACTION_UP / ACTION_CANCEL after at least one onPan call during the same gesture. Lets
		 * the editor apply a one-shot post-drag cleanup (e.g. parity-snap the crop center to pixel alignment)
		 * without the continuous-snap flicker that would happen mid-drag. Does NOT fire for tap-only gestures.
		 */
		void onPanRelease();

		/**
		 * Single-tap confirmed (post-double-tap-window). The crop editor adds a selection point or sets the
		 * crop center, depending on the current mode.
		 */
		void onTap(float screenX, float screenY);

		/**
		 * Pinch zoom — `scaleFactor` is the multiplicative zoom delta since the last onZoom; `focusX` /
		 * `focusY` are the screen-space pivot point of the pinch gesture.
		 */
		void onZoom(float scaleFactor, float focusX, float focusY);
	}

	private final Callback callback;
	private final GestureDetector gestureDetector;
	private final ScaleGestureDetector scaleDetector;

	private boolean isPanning = false; // onScroll fired at least once this gesture
	private boolean isScaling = false;

	/**
	 * Wire up Android's GestureDetector + ScaleGestureDetector to forward recognized events to the supplied
	 * callback. The callback is held by reference for the lifetime of the handler — typically the view itself.
	 */
	public TouchGestureHandler(Context context, Callback callback)
	{
		this.callback = callback;

		scaleDetector = new ScaleGestureDetector(context,
			new ScaleGestureDetector.SimpleOnScaleGestureListener()
		{
			@Override
			public boolean onScaleBegin(ScaleGestureDetector detector)
			{
				isScaling = true;
				// Clear isPanning so ACTION_UP at the end of a pan→pinch transition doesn't fire
				// onPanRelease. Without this, a single-finger drag that transitions into a two-finger
				// pinch would still snap the crop on release — a pinch gesture shouldn't trigger a
				// drag-specific snap.
				isPanning = false;
				return true;
			}

			@Override
			public boolean onScale(ScaleGestureDetector detector)
			{
				callback.onZoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
				return true;
			}

			@Override
			public void onScaleEnd(ScaleGestureDetector detector)
			{
				isScaling = false;
			}
		});

		gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener()
		{
			@Override
			public boolean onDown(MotionEvent event)
			{
				return true;
			}

			@Override
			public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY)
			{
				if (isScaling)
				{
					return false;
				}
				isPanning = true;
				callback.onPan(-distanceX, -distanceY);
				return true;
			}

			@Override
			public boolean onSingleTapConfirmed(MotionEvent event)
			{
				callback.onTap(event.getX(), event.getY());
				return true;
			}

			@Override
			public boolean onDoubleTap(MotionEvent event)
			{
				callback.onDoubleTap();
				return true;
			}

			@Override
			public void onLongPress(MotionEvent event)
			{
				callback.onLongPress(event.getX(), event.getY());
			}
		});
	}

	/**
	 * Hand off a MotionEvent for scale + gesture recognition. The view's onTouchEvent should forward to this
	 * directly. Also synthesizes the onPanRelease callback on ACTION_UP / ACTION_CANCEL when the gesture included
	 * at least one pan event.
	 *
	 * @param event the MotionEvent received by the view
	 * @return true if either detector consumed the event
	 */
	public boolean onTouchEvent(MotionEvent event)
	{
		boolean handled = scaleDetector.onTouchEvent(event);
		handled |= gestureDetector.onTouchEvent(event);
		int action = event.getActionMasked();
		if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && isPanning)
		{
			isPanning = false;
			callback.onPanRelease();
		}
		return handled;
	}
}
