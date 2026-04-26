package com.cropcenter.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.Choreographer;
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

	private final Choreographer.FrameCallback flingFrameCallback;
	private final OverScroller scroller;
	private final Paint detentTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint majorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint minorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	// Cached indicator-triangle scratch — rewound per draw so onDraw allocates nothing.
	private final Path indicatorTriangle = new Path();
	private final ScaleGestureDetector scaleDetector;

	private OnRotationChangedListener listener;
	private VelocityTracker velocityTracker;
	private boolean enabled = true;
	private boolean flingActive; // true between scroller.fling start and last frame
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
				stopFling();
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

		// Drive the fling from a Choreographer frame callback rather than View.computeScroll.
		// computeScroll runs inside the view's own draw pass — since the editor view sits above
		// this ruler in the layout, by the time computeScroll fires and invalidates the editor,
		// the editor's draw for this frame is already complete. The editor then catches up one
		// frame later, which at high fling velocity is visible as the crop/grid briefly
		// appearing at the previous rotation's position.
		//
		// A FrameCallback runs in Choreographer's animation phase, BEFORE traversal — so the
		// state update lands in time for both the ruler and the editor to draw it in the same
		// frame. No lag, no flicker.
		flingFrameCallback = new Choreographer.FrameCallback()
		{
			@Override
			public void doFrame(long frameTimeNanos)
			{
				if (!flingActive)
				{
					return;
				}
				if (scroller.computeScrollOffset())
				{
					float rawDeg = scroller.getCurrX() / (pixelsPerDegree * SCROLL_SUBPIXEL_SCALE);
					float deg = Math.clamp(rawDeg, MIN_DEG, MAX_DEG);
					if (deg != currentDegrees)
					{
						currentDegrees = deg;
						notifyChanged();
					}
					invalidate();
					if (scroller.isFinished())
					{
						flingActive = false;
						snapAndNotify();
					}
					// Re-check flingActive before reposting: notifyChanged above ultimately
					// calls back into setDegrees via the state listener, and a future caller
					// invoking setDegrees outside the isRulerUpdating guard could flip
					// flingActive=false via stopFling. Reposting unconditionally would
					// resurrect the fling. Cheap defence against that class of bug.
					else if (flingActive)
					{
						Choreographer.getInstance().postFrameCallback(this);
					}
				}
				else
				{
					flingActive = false;
				}
			}
		};
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
		stopFling();
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
			case MotionEvent.ACTION_DOWN -> handleTouchDown(event);
			case MotionEvent.ACTION_MOVE -> handleTouchMove(event);
			case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handleTouchRelease(event);
		}
		return true;
	}

	/**
	 * ACTION_DOWN: stop any in-flight fling, reset per-gesture accumulators, and ask
	 * the parent not to intercept subsequent moves (the rotation dial owns horizontal
	 * drag inside its bounds). getParent() is null between detach and re-attach during
	 * config changes — skip the request rather than NPE.
	 */
	private void handleTouchDown(MotionEvent event)
	{
		stopFling();
		downX = lastTouchX = event.getX();
		totalDragDx = 0;
		scalingOccurred = false;
		ViewParent parent = getParent();
		if (parent != null)
		{
			parent.requestDisallowInterceptTouchEvent(true);
		}
	}

	/**
	 * ACTION_MOVE: single-finger horizontal drag advances currentDegrees; pinch drags
	 * are handled by scaleDetector and suppressed here (isScaling / pointerCount > 1).
	 */
	private void handleTouchMove(MotionEvent event)
	{
		if (!isScaling && event.getPointerCount() == 1)
		{
			float dx = event.getX() - lastTouchX;
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

	/**
	 * ACTION_UP / ACTION_CANCEL: classify the gesture as tap / drag-release-slow /
	 * drag-release-fast and dispatch accordingly. Recycles the velocity tracker on
	 * every exit so the next gesture starts fresh.
	 */
	private void handleTouchRelease(MotionEvent event)
	{
		// If a pinch-zoom occurred during this gesture, skip the angle fling / snap
		// entirely. onScaleEnd fires before ACTION_UP so isScaling is already false
		// here — but scalingOccurred stays true for the full gesture lifetime, and
		// without this check the VelocityTracker's x-velocity (populated by the pinch
		// focus-point motion) would trigger a spurious rotation change on release.
		if (!isScaling && !scalingOccurred)
		{
			// Tap: total movement below the slop.
			if (totalDragDx <= TAP_SLOP
				&& event.getActionMasked() == MotionEvent.ACTION_UP)
			{
				float centerX = getWidth() / 2f;
				float tappedDeg = currentDegrees + (downX - centerX) / pixelsPerDegree;
				currentDegrees = Math.clamp(tappedDeg, MIN_DEG, MAX_DEG);
				snapAndNotify();
				velocityTracker.recycle();
				velocityTracker = null;
				return;
			}

			velocityTracker.computeCurrentVelocity((int) SCROLL_SUBPIXEL_SCALE);
			float xVelocity = velocityTracker.getXVelocity();
			if (Math.abs(xVelocity) > FLING_VELOCITY_THRESHOLD)
			{
				startFling(xVelocity);
			}
			else
			{
				snapAndNotify();
			}
		}
		velocityTracker.recycle();
		velocityTracker = null;
	}

	/**
	 * Fire the OverScroller + register the Choreographer frame callback for fling.
	 * Separated from handleTouchRelease so the high-velocity branch reads as a single
	 * named action rather than eight lines of scroller-setup arithmetic.
	 */
	private void startFling(float xVelocity)
	{
		float scaled = pixelsPerDegree * SCROLL_SUBPIXEL_SCALE;
		int startX = (int) (currentDegrees * scaled);
		int minX = (int) (MIN_DEG * scaled);
		int maxX = (int) (MAX_DEG * scaled);
		scroller.fling(startX, 0, (int) (-xVelocity * SCROLL_SUBPIXEL_SCALE), 0,
			minX, maxX, 0, 0);
		flingActive = true;
		Choreographer.getInstance().postFrameCallback(flingFrameCallback);
	}

	public void setDegrees(float deg)
	{
		deg = Math.clamp(deg, MIN_DEG, MAX_DEG);
		if (deg != currentDegrees)
		{
			currentDegrees = deg;
			stopFling();
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

	/**
	 * Multiply the current ruler zoom by a factor and clamp into the valid range. Use
	 * scaleFactor > 1 to zoom in (finer ticks, smaller visible degree span); < 1 to zoom out.
	 * Used by the toolbar's − / + buttons that flank the ruler.
	 */
	public void zoomBy(float scaleFactor)
	{
		pixelsPerDegree = Math.clamp(pixelsPerDegree * scaleFactor,
			basePixelsPerDegree * MIN_PPD_FACTOR, basePixelsPerDegree * MAX_PPD_FACTOR);
		invalidate();
	}

	/**
	 * Snap the ruler to its maximum zoom level (finest 0.01° tick precision). Called by the
	 * auto-rotate flow after horizon detection lands a precise angle, so the user can
	 * immediately fine-tune around the detected value.
	 */
	public void zoomToMax()
	{
		pixelsPerDegree = basePixelsPerDegree * MAX_PPD_FACTOR;
		invalidate();
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
		TickConfig tickConfig = chooseTickConfig(degreesVisible);

		// Use integer tick indices to avoid float accumulation errors.
		// tickIndex * tickConfig.minor = degree value.
		float halfVisible = degreesVisible / 2f;
		int iStart = (int) Math.floor((currentDegrees - halfVisible - tickConfig.major) / tickConfig.minor);
		int iEnd = (int) Math.ceil((currentDegrees + halfVisible + tickConfig.major) / tickConfig.minor);

		// Multiplier to convert minor intervals to major check (integer comparison)
		int majorEvery = Math.round(tickConfig.major / tickConfig.minor);
		int detentEvery = Math.round(45f / tickConfig.minor);

		for (int i = iStart; i <= iEnd; i++)
		{
			float deg = i * tickConfig.minor;
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

		float triangleHeight = 4 * getResources().getDisplayMetrics().density;
		indicatorPaint.setStyle(Paint.Style.FILL);
		indicatorTriangle.rewind();
		indicatorTriangle.moveTo(centerX, tickTop);
		indicatorTriangle.lineTo(centerX - triangleHeight, tickTop + triangleHeight);
		indicatorTriangle.lineTo(centerX + triangleHeight, tickTop + triangleHeight);
		indicatorTriangle.close();
		canvas.drawPath(indicatorTriangle, indicatorPaint);
		indicatorPaint.setStyle(Paint.Style.STROKE);
		canvas.drawLine(centerX, tickTop + triangleHeight, centerX, tickBot, indicatorPaint);
	}

	private void notifyChanged()
	{
		if (listener != null)
		{
			listener.onRotationChanged(currentDegrees);
		}
	}

	/**
	 * Cancel any active fling and unregister the pending frame callback so it doesn't reschedule
	 * itself. Safe to call when no fling is active — idempotent.
	 */
	private void stopFling()
	{
		flingActive = false;
		scroller.forceFinished(true);
		Choreographer.getInstance().removeFrameCallback(flingFrameCallback);
	}

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
		TickConfig tickConfig = chooseTickConfig(degreesVisible);
		return snapTo(deg, tickConfig.minor);
	}

	// Pre-built tick configurations for each zoom level. Indexed parallel to TICK_THRESHOLDS:
	// the first config whose threshold is strictly below degreesVisible wins. Cached as
	// static finals because chooseTickConfig is called on every onDraw and every snapToTick
	// during a fling — otherwise we'd allocate 60+ TickConfig records per second.
	private static final TickConfig[] TICK_CONFIGS = {
		new TickConfig(10f,   45f),
		new TickConfig(5f,    45f),
		new TickConfig(1f,    10f),
		new TickConfig(1f,    5f),
		new TickConfig(0.5f,  1f),
		new TickConfig(0.1f,  0.5f),
		new TickConfig(0.05f, 0.1f),
		new TickConfig(0.01f, 0.1f),
	};
	private static final float[] TICK_THRESHOLDS = {
		270f, 90f, 30f, 10f, 3f, 1f, 0.3f, 0f,
	};

	/**
	 * Choose tick intervals based on how many degrees are visible on screen. Walks
	 * TICK_THRESHOLDS in order; the first threshold strictly below degreesVisible picks
	 * that index's TickConfig. The last threshold is 0 so the loop always terminates.
	 */
	private static TickConfig chooseTickConfig(float degreesVisible)
	{
		for (int i = 0; i < TICK_THRESHOLDS.length; i++)
		{
			if (degreesVisible > TICK_THRESHOLDS[i])
			{
				return TICK_CONFIGS[i];
			}
		}
		return TICK_CONFIGS[TICK_CONFIGS.length - 1];
	}

	private static float snapTo(float val, float step)
	{
		return Math.round(val / step) * step;
	}

	private record TickConfig(float minor, float major) {}
}
