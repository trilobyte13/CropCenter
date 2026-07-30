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
		 * Long-press detected. The crop editor uses this to remove a selection point in select mode.
		 *
		 * @param screenX press position, view-local screen px
		 * @param screenY press position, view-local screen px
		 */
		void onLongPress(float screenX, float screenY);

		/**
		 * Single-finger drag.
		 *
		 * @param dx horizontal delta since the last onPan call, already negated from GestureDetector's
		 *           distance convention so positive dx means the finger moved right
		 * @param dy vertical delta since the last onPan call, same convention (positive = moved down)
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
		 *
		 * @param screenX tap position, view-local screen px
		 * @param screenY tap position, view-local screen px
		 */
		void onTap(float screenX, float screenY);

		/**
		 * Pinch zoom.
		 *
		 * @param scaleFactor multiplicative zoom delta since the last onZoom (1.0 = no change)
		 * @param focusX      pinch pivot, view-local screen px
		 * @param focusY      pinch pivot, view-local screen px
		 */
		void onZoom(float scaleFactor, float focusX, float focusY);
	}

	// Per-frame scaleFactor deadzone for onScale → onZoom. Hand tremor + ScaleGestureDetector sensor noise produces
	// scaleFactor jitter typically below 1% (0.99–1.01) when the user has two fingers down but isn't actively
	// pinching; deltas inside this band are suppressed so the viewport doesn't drift-zoom on its own. Intentional
	// pinch produces 2–10% scaleFactor per frame, well above the band.
	private static final float SCALE_DEADZONE = 0.015f;

	private final Callback callback;
	private final GestureDetector gestureDetector;
	private final ScaleGestureDetector scaleDetector;

	private boolean isPanning = false; // onScroll fired at least once this gesture
	private boolean isScaling = false;

	/**
	 * Wire up Android's GestureDetector + ScaleGestureDetector to forward recognized events to the supplied
	 * callback.
	 *
	 * @param context  view context the platform detectors need for touch-slop / timing constants
	 * @param callback receiver of the recognized gestures; held by reference for the lifetime of the
	 *                 handler — typically the owning view itself
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
				float scaleFactor = detector.getScaleFactor();
				if (shouldConsumeScale(scaleFactor))
				{
					callback.onZoom(scaleFactor, detector.getFocusX(), detector.getFocusY());
					return true;
				}
				// Deadzone-suppressed: report the event unconsumed so ScaleGestureDetector keeps its
				// span baseline and sub-threshold motion accumulates across events — a slow steady
				// pinch eventually crosses the gate. Consuming (true) here would reset the baseline
				// every event and discard the motion, so a slow pinch would never zoom at all.
				return false;
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
			public boolean onDoubleTap(MotionEvent event)
			{
				callback.onDoubleTap();
				return true;
			}

			@Override
			public boolean onDown(MotionEvent event)
			{
				return true;
			}

			@Override
			public void onLongPress(MotionEvent event)
			{
				callback.onLongPress(event.getX(), event.getY());
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
		if (action == MotionEvent.ACTION_UP && isPanning)
		{
			// Real release — commit the gesture (parity-snap, anchor update). onPanRelease performs
			// CropEditorView's setCenter/setAnchor + snapAxisPreservingTies, which is the right terminal
			// step ONLY when the user genuinely lifted their finger.
			isPanning = false;
			callback.onPanRelease();
		}
		else if (action == MotionEvent.ACTION_CANCEL && isPanning)
		{
			// Cancelled — parent intercepted the gesture, system back fired, multi-touch disambiguation
			// rejected the pan, etc. Reset the panning flag WITHOUT committing onPanRelease's snap:
			// persisting a parity-snap or anchor update from an interrupted drag leaves the crop at a
			// position the user never released to. Symmetric to RotationRulerView.handleTouchRelease which
			// already gates fling/snap/notify on ACTION_UP only.
			isPanning = false;
		}
		return handled;
	}

	/**
	 * Deadzone gate for onScale: true when scaleFactor sits outside the SCALE_DEADZONE band around 1 and the event
	 * should be consumed (onZoom fired, detector span baseline reset). False means suppress AND report the event
	 * unconsumed, so the detector keeps accumulating span change against the prior baseline — a slow steady pinch
	 * whose per-event delta never reaches 1.5% still zooms once its accumulated change does. Jitter rejection
	 * survives the accumulation: hand tremor is zero-mean, so the accumulated ratio oscillates inside the band
	 * instead of walking across it. Strict comparison — a factor exactly at the band edge is treated as jitter.
	 * Package-private so the test can pin the boundary.
	 *
	 * @param scaleFactor multiplicative span delta reported by ScaleGestureDetector since the last consumed
	 *                    event
	 * @return true to consume the event and forward it as onZoom; false to suppress and keep accumulating
	 */
	static boolean shouldConsumeScale(float scaleFactor)
	{
		return Math.abs(scaleFactor - 1f) > SCALE_DEADZONE;
	}
}
