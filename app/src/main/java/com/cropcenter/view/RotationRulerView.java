package com.cropcenter.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewParent;
import android.widget.OverScroller;

import com.cropcenter.util.TextFormat;
import com.cropcenter.util.ThemeColors;

/**
 * Galaxy-style scrollable rotation ruler with pinch-to-zoom.
 *
 * Drag to scroll, fling for momentum. Pinch to zoom the ruler scale, enabling 0.01° precision at
 * the highest zoom. After drag/fling settles, the value snaps to the nearest tick interval for
 * the current zoom level.
 */
public class RotationRulerView extends View
{
	public interface OnRotationChangedListener
	{
		void onRotationChanged(float degrees);
	}

	private static final float FLING_VELOCITY_THRESHOLD = 200f; // px/s — below this, snap instead
	private static final float MAX_DEG = 180f;
	private static final float MAX_PPD_FACTOR = 120f; // enough to show 0.01° ticks
	private static final float MIN_DEG = -180f;
	private static final float MIN_PPD_FACTOR = 1f;
	private static final float OFF_SCREEN_MARGIN = 10f;       // px — skip ticks this far beyond edges
	private static final float SCROLL_SUBPIXEL_SCALE = 1000f; // int scroller → preserve fractional degrees
	private static final float TAP_SLOP = 8f;                 // pixels — tap vs drag threshold
	private static final float ZERO_MARKER_MARGIN = 5f;       // px — tighter cull than ticks for the 0° line

	private final OverScroller scroller;
	private final Paint detentTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint majorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint minorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final ScaleGestureDetector scaleDetector;

	private OnRotationChangedListener listener;
	private VelocityTracker velocityTracker;
	private boolean enabled = true;
	private boolean isScaling;
	private boolean scalingOccurred; // true if any scaling happened during current gesture
	private float basePixelsPerDegree;
	private float currentDegrees = 0f;
	private float downX; // where finger touched down
	private float lastTouchX;
	private float pixelsPerDegree;
	private float totalDragDx; // cumulative drag distance since touchdown

	public RotationRulerView(Context context)
	{
		this(context, null);
	}

	public RotationRulerView(Context context, AttributeSet attrs)
	{
		this(context, attrs, 0);
	}

	public RotationRulerView(Context context, AttributeSet attrs, int defStyle)
	{
		super(context, attrs, defStyle);
		float density = context.getResources().getDisplayMetrics().density;
		basePixelsPerDegree = 12 * density;
		pixelsPerDegree = basePixelsPerDegree;

		scroller = new OverScroller(context);

		minorTickPaint.setColor(ThemeColors.SURFACE1);
		minorTickPaint.setStrokeWidth(density);

		majorTickPaint.setColor(ThemeColors.SURFACE2);
		majorTickPaint.setStrokeWidth(density);

		detentTickPaint.setColor(ThemeColors.SUBTEXT0);
		detentTickPaint.setStrokeWidth(1.5f * density);

		indicatorPaint.setColor(ThemeColors.MAUVE);
		indicatorPaint.setStrokeWidth(2f * density);

		zeroPaint.setColor(ThemeColors.RED);
		zeroPaint.setStrokeWidth(1.5f * density);

		labelPaint.setColor(ThemeColors.OVERLAY0);
		labelPaint.setTextSize(8 * density);
		labelPaint.setTextAlign(Paint.Align.CENTER);

		scaleDetector = new ScaleGestureDetector(context,
			new ScaleGestureDetector.SimpleOnScaleGestureListener()
		{
			@Override
			public boolean onScaleBegin(ScaleGestureDetector detector)
			{
				isScaling = true;
				scalingOccurred = true;
				scroller.forceFinished(true);
				return true;
			}

			@Override
			public boolean onScale(ScaleGestureDetector detector)
			{
				pixelsPerDegree = Math.clamp(pixelsPerDegree * detector.getScaleFactor(),
					basePixelsPerDegree * MIN_PPD_FACTOR, basePixelsPerDegree * MAX_PPD_FACTOR);
				invalidate();
				return true;
			}

			@Override
			public void onScaleEnd(ScaleGestureDetector detector)
			{
				isScaling = false;
			}
		});
	}

	@Override
	public void computeScroll()
	{
		if (scroller.computeScrollOffset())
		{
			float deg = Math.clamp(scroller.getCurrX() / (pixelsPerDegree * SCROLL_SUBPIXEL_SCALE),
				MIN_DEG, MAX_DEG);
			if (deg != currentDegrees)
			{
				currentDegrees = deg;
				notifyChanged();
			}
			invalidate();
			if (scroller.isFinished())
			{
				snapAndNotify();
			}
		}
	}

	public float getDegrees()
	{
		return currentDegrees;
	}

	@Override
	protected void onDetachedFromWindow()
	{
		// If the view is torn down mid-gesture (config change, parent removal), Android won't
		// dispatch ACTION_UP/CANCEL — the tracker and scroller leak without this cleanup.
		if (velocityTracker != null)
		{
			velocityTracker.recycle();
			velocityTracker = null;
		}
		scroller.forceFinished(true);
		listener = null;
		super.onDetachedFromWindow();
	}

	@Override
	public boolean onTouchEvent(MotionEvent event)
	{
		if (!enabled)
		{
			return false;
		}

		scaleDetector.onTouchEvent(event);

		if (velocityTracker == null)
		{
			velocityTracker = VelocityTracker.obtain();
		}
		velocityTracker.addMovement(event);

		switch (event.getActionMasked())
		{
			case MotionEvent.ACTION_DOWN ->
			{
				scroller.forceFinished(true);
				downX = lastTouchX = event.getX();
				totalDragDx = 0;
				scalingOccurred = false;
				// getParent() is null between detach and re-attach (config change mid-gesture);
				// skip the request rather than NPE.
				ViewParent parent = getParent();
				if (parent != null)
				{
					parent.requestDisallowInterceptTouchEvent(true);
				}
			}
			case MotionEvent.ACTION_MOVE ->
			{
				if (!isScaling && event.getPointerCount() == 1)
				{
					float dx = event.getX() - lastTouchX;
					lastTouchX = event.getX();
					totalDragDx += Math.abs(dx);
					float rawDeg = currentDegrees - dx / pixelsPerDegree;
					float newDeg = Math.clamp(rawDeg, MIN_DEG, MAX_DEG);
					if (newDeg != currentDegrees)
					{
						currentDegrees = newDeg;
						notifyChanged();
						invalidate();
					}
				}
				lastTouchX = event.getX();
			}
			case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
			{
				if (!isScaling)
				{
					// Tap vs drag: total movement <= TAP_SLOP AND no scaling happened
					if (!scalingOccurred && totalDragDx <= TAP_SLOP
						&& event.getActionMasked() == MotionEvent.ACTION_UP)
					{
						float centerX = getWidth() / 2f;
						float tappedDeg = currentDegrees + (downX - centerX) / pixelsPerDegree;
						currentDegrees = Math.clamp(tappedDeg, MIN_DEG, MAX_DEG);
						snapAndNotify();
						velocityTracker.recycle();
						velocityTracker = null;
						return true;
					}
					velocityTracker.computeCurrentVelocity((int) SCROLL_SUBPIXEL_SCALE);
					float velX = velocityTracker.getXVelocity();
					if (Math.abs(velX) > FLING_VELOCITY_THRESHOLD)
					{
						float scaled = pixelsPerDegree * SCROLL_SUBPIXEL_SCALE;
						int startX = (int) (currentDegrees * scaled);
						int minX = (int) (MIN_DEG * scaled);
						int maxX = (int) (MAX_DEG * scaled);
						scroller.fling(startX, 0, (int) (-velX * SCROLL_SUBPIXEL_SCALE), 0,
							minX, maxX, 0, 0);
						postInvalidateOnAnimation();
					}
					else
					{
						snapAndNotify();
					}
				}
				velocityTracker.recycle();
				velocityTracker = null;
			}
		}
		return true;
	}

	public void setDegrees(float deg)
	{
		deg = Math.clamp(deg, MIN_DEG, MAX_DEG);
		if (deg != currentDegrees)
		{
			currentDegrees = deg;
			scroller.forceFinished(true);
			invalidate();
		}
	}

	public void setOnRotationChangedListener(OnRotationChangedListener listener)
	{
		this.listener = listener;
	}

	public void setRulerEnabled(boolean enabled)
	{
		this.enabled = enabled;
		setAlpha(enabled ? 1f : 0.3f);
	}

	@Override
	protected void onDraw(Canvas canvas)
	{
		int width = getWidth();
		int height = getHeight();
		float centerX = width / 2f;
		float labelY = height - 1;
		float tickTop = 2;
		float tickBot = height - labelPaint.getTextSize() - 3;

		float degreesVisible = width / pixelsPerDegree;
		TickConfig tc = chooseTickConfig(degreesVisible);

		// Use integer tick indices to avoid float accumulation errors.
		// tickIndex * tc.minor = degree value.
		int iStart = (int) Math.floor((currentDegrees - degreesVisible / 2f - tc.major) / tc.minor);
		int iEnd = (int) Math.ceil((currentDegrees + degreesVisible / 2f + tc.major) / tc.minor);

		// Multiplier to convert minor intervals to major check (integer comparison)
		int majorEvery = Math.round(tc.major / tc.minor);
		int detentEvery = Math.round(45f / tc.minor);

		for (int i = iStart; i <= iEnd; i++)
		{
			float deg = i * tc.minor;
			if (deg < MIN_DEG || deg > MAX_DEG)
			{
				continue;
			}

			float x = centerX + (deg - currentDegrees) * pixelsPerDegree;
			if (x < -OFF_SCREEN_MARGIN || x > width + OFF_SCREEN_MARGIN)
			{
				continue;
			}

			boolean isDetent = detentEvery > 0 && i % detentEvery == 0;
			boolean isMajor = majorEvery > 0 && i % majorEvery == 0;

			if (isDetent)
			{
				canvas.drawLine(x, tickTop, x, tickBot, detentTickPaint);
				canvas.drawText(TextFormat.degrees(deg), x, labelY, labelPaint);
			}
			else if (isMajor)
			{
				float mid = (tickTop + tickBot) / 2f;
				float halfH = (tickBot - tickTop) * 0.35f;
				canvas.drawLine(x, mid - halfH, x, mid + halfH, majorTickPaint);
				canvas.drawText(TextFormat.degrees(deg), x, labelY, labelPaint);
			}
			else
			{
				float mid = (tickTop + tickBot) / 2f;
				float halfH = (tickBot - tickTop) * 0.18f;
				canvas.drawLine(x, mid - halfH, x, mid + halfH, minorTickPaint);
			}
		}

		// Zero marker
		float zeroX = centerX - currentDegrees * pixelsPerDegree;
		if (zeroX > -ZERO_MARKER_MARGIN && zeroX < width + ZERO_MARKER_MARGIN && currentDegrees != 0f)
		{
			canvas.drawLine(zeroX, tickTop, zeroX, tickBot, zeroPaint);
		}

		float triH = 4 * getResources().getDisplayMetrics().density;
		indicatorPaint.setStyle(Paint.Style.FILL);
		Path tri = new Path();
		tri.moveTo(centerX, tickTop);
		tri.lineTo(centerX - triH, tickTop + triH);
		tri.lineTo(centerX + triH, tickTop + triH);
		tri.close();
		canvas.drawPath(tri, indicatorPaint);
		indicatorPaint.setStyle(Paint.Style.STROKE);
		canvas.drawLine(centerX, tickTop + triH, centerX, tickBot, indicatorPaint);
	}

	private void notifyChanged()
	{
		if (listener != null)
		{
			listener.onRotationChanged(currentDegrees);
		}
	}

	/**
	 * Snap currentDegrees to the nearest minor tick and notify.
	 */
	private void snapAndNotify()
	{
		float snapped = Math.clamp(snapToTick(currentDegrees), MIN_DEG, MAX_DEG);
		if (snapped != currentDegrees)
		{
			currentDegrees = snapped;
			notifyChanged();
			invalidate();
		}
	}

	/**
	 * Snap a degree value to the nearest minor tick for the current zoom.
	 */
	private float snapToTick(float deg)
	{
		float degreesVisible = getWidth() > 0 ? getWidth() / pixelsPerDegree : 30f;
		TickConfig tc = chooseTickConfig(degreesVisible);
		return snapTo(deg, tc.minor);
	}

	/**
	 * Choose tick intervals based on how many degrees are visible on screen.
	 */
	private static TickConfig chooseTickConfig(float degreesVisible)
	{
		if (degreesVisible > 270)
		{
			return new TickConfig(10f, 45f);
		}
		if (degreesVisible > 90)
		{
			return new TickConfig(5f, 45f);
		}
		if (degreesVisible > 30)
		{
			return new TickConfig(1f, 10f);
		}
		if (degreesVisible > 10)
		{
			return new TickConfig(1f, 5f);
		}
		if (degreesVisible > 3)
		{
			return new TickConfig(0.5f, 1f);
		}
		if (degreesVisible > 1)
		{
			return new TickConfig(0.1f, 0.5f);
		}
		if (degreesVisible > 0.3)
		{
			return new TickConfig(0.05f, 0.1f);
		}
		return new TickConfig(0.01f, 0.1f);
	}

	private static float snapTo(float val, float step)
	{
		return Math.round(val / step) * step;
	}

	private record TickConfig(float minor, float major) {}
}
