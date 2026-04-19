package com.cropcenter.view;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

// Handles touch gestures for the crop editor:
//  - Single finger drag: set center (click mode) or pan (browse mode)
//  - Two finger pinch: zoom with pivot
//  - Double tap: fit image to view
//  - Long press: remove point in select mode
public class TouchGestureHandler
{
	public interface Callback
	{
		void onDoubleTap();
		void onLongPress(float screenX, float screenY);
		void onPan(float dx, float dy);
		void onTap(float screenX, float screenY);
		void onZoom(float scaleFactor, float focusX, float focusY);
	}

	private final Callback callback;
	private final GestureDetector gestureDetector;
	private boolean isScaling = false;
	private final ScaleGestureDetector scaleDetector;

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
				return true;
			}

			@Override
			public boolean onScale(ScaleGestureDetector detector)
			{
				callback.onZoom(detector.getScaleFactor(),
					detector.getFocusX(), detector.getFocusY());
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

	public boolean onTouchEvent(MotionEvent event)
	{
		boolean handled = scaleDetector.onTouchEvent(event);
		handled |= gestureDetector.onTouchEvent(event);
		return handled;
	}
}
