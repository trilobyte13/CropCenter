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
 * Drag to scroll, fling for momentum. Pinch to zoom the ruler scale, enabling 0.01° precision at the highest zoom.
 * After drag/fling settles, the value snaps to the nearest tick interval for the current zoom level. The finest snap
 * step (0.01°) sits above BitmapUtils.ROTATION_EPSILON so the renderer, readout, and ExportPipeline.canBypassEncode all
 * honor every tick the ruler can produce.
 */
public final class RotationRulerView extends View
{
	/**
	 * Receives rotation-degree updates as the user drags / flings / pinches the ruler. The view fires this on every
	 * degree change — including momentum frames during a fling — so listeners that mutate state should be cheap.
	 * The degree value is already clamped to [MIN_DEG, MAX_DEG].
	 */
	public interface OnRotationChangedListener
	{
		void onRotationChanged(float degrees);
	}

	/**
	 * Tick spacing for one zoom tier.
	 *
	 * @param minor spacing between minor ticks in degrees — also the release-snap grid
	 * @param major spacing between labelled major ticks in degrees; an exact multiple of minor in every
	 *              config, so majorEvery = Math.round(major / minor) reproduces it without drift
	 */
	private record TickConfig(float minor, float major) {}

	/**
	 * Result of promoting the surviving finger after ACTION_POINTER_UP of the active pointer: the survivor's id
	 * becomes the active pointer and the drag baseline rebases to the survivor's own x, so the first post-promotion
	 * MOVE contributes a zero drag delta. Without the rebase the full inter-finger distance would be applied as an
	 * uncommanded rotation jump (up to ~20° at minimum zoom), committed live and never rolled back. Package-private
	 * so the test can pin the rebase Canvas-free.
	 *
	 * @param pointerId  pointer id of the surviving finger — the new active pointer
	 * @param lastTouchX the survivor's current x — the value the drag baseline must take at promotion
	 */
	record PointerPromotion(int pointerId, float lastTouchX) {}

	// Pre-built tick configurations for each zoom level. Indexed parallel to TICK_THRESHOLDS:
	// the first config whose threshold is strictly below degreesVisible wins. Every major is an exact
	// multiple of its minor (the coarsest tier groups at 50°, not 45° — the ±45° detents there are
	// drawn off-grid by onDraw's detent pass). Cached as static finals because chooseTickConfig is
	// called on every onDraw and every snapToTick during a fling — otherwise we'd allocate 60+
	// TickConfig records per second.
	private static final TickConfig[] TICK_CONFIGS = {
		new TickConfig(10f,   50f), new TickConfig(5f,    45f),
		new TickConfig(1f,    10f), new TickConfig(1f,    5f),
		new TickConfig(0.5f,  1f), new TickConfig(0.1f,  0.5f),
		new TickConfig(0.05f, 0.1f), new TickConfig(0.01f, 0.1f),
	};
	// Hard ceiling on the detent snap window (used at coarse zoom where minor ticks are wide and detent values like
	// ±45° aren't part of the tick grid). At fine zoom the window shrinks proportionally to the minor tick — a
	// fixed 0.8° window at max zoom (0.01° ticks) creates a 1.6° dead zone around every detent and swallows
	// legitimate fine adjustments like 0.01°-0.79°. The cap matters most at minor=10° where 45° isn't a tick and
	// the user needs the detent to land there at all.
	private static final float DETENT_SNAP_MAX_DEGREES = 0.8f;
	// Multiplier applied to the current minor tick to compute the per-snap detent window. 0.5× makes the window
	// exactly half a tick — the detent then acts as a tie-breaker between two adjacent ticks at fine zoom (which
	// plain rounding already does), so the user can hit ANY fine tick. At coarse zoom the cap above kicks in and
	// the detent reverts to its sticky "land on 45° even though only 40°/50° are real ticks" role.
	private static final float DETENT_SNAP_MINOR_FACTOR = 0.5f;
	private static final float FLING_VELOCITY_THRESHOLD = 200f; // px/s — below this, snap instead
	private static final float MAX_DEG = 180f;
	private static final float MAX_PPD_FACTOR = 120f; // enough to show 0.01° ticks
	private static final float MIN_DEG = -180f;
	private static final float MIN_PPD_FACTOR = 1f;
	private static final float OFF_SCREEN_MARGIN = 10f;       // px — skip ticks this far beyond edges
	private static final float SCROLL_SUBPIXEL_SCALE = 1000f; // int scroller → preserve fractional degrees
	private static final float TAP_SLOP = 8f;                 // pixels — tap vs drag threshold
	private static final float ZERO_MARKER_MARGIN = 5f;       // px — tighter cull than ticks for the 0° line
	// Sticky detent values — release-snap pulls a near-detent rotation onto the exact value within the per-zoom
	// detent threshold (see snapToDetentOrTick) so the user can land cleanly on 0°, ±45°, ±90°, ±180° without
	// fighting finer ticks. Listed sorted ascending; the snap walks the list and picks the first within threshold.
	private static final float[] DETENTS = { -180f, -90f, -45f, 0f, 45f, 90f, 180f };
	private static final float[] TICK_THRESHOLDS = {
		270f, 90f, 30f, 10f, 3f, 1f, 0.3f, 0f,
	};

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
	private Runnable onZoomChanged;
	private VelocityTracker velocityTracker;
	private boolean enabled = true;
	private boolean flingActive; // true between scroller.fling start and last frame
	private boolean isScaling;
	private boolean scalingOccurred; // true if any scaling happened during current gesture
	private float basePixelsPerDegree;
	private float currentDegrees = 0f;
	private float downX; // where finger touched down
	private float gestureStartDegrees; // currentDegrees at ACTION_DOWN — used by drag-release to skip the
	                                   // detent the user is dragging AWAY from (so 0° → 0.4° release doesn't
	                                   // re-snap to 0° at coarse zoom where the 0.5° detent window swallows small
	                                   // intentional drags).
	private float lastTouchX;
	private float pixelsPerDegree;
	private float totalDragDx; // cumulative drag distance since touchdown
	private int activePointerId = MotionEvent.INVALID_POINTER_ID; // finger that owns the drag; see handleTouchMove

	/**
	 * Programmatic-construction overload — no XML attributes, no style. Forwards to the 3-arg base.
	 *
	 * @param context Activity context for resources and gesture detectors
	 */
	public RotationRulerView(Context context)
	{
		this(context, null);
	}

	/**
	 * XML-inflation overload (AttributeSet from layout, no explicit defStyle). Forwards to the 3-arg base.
	 *
	 * @param context Activity context for resources and gesture detectors
	 * @param attrs   XML attributes from layout inflation; may be null
	 */
	public RotationRulerView(Context context, AttributeSet attrs)
	{
		this(context, attrs, 0);
	}

	/**
	 * Base constructor — sets up paints, scroller, and the scale detector. Called either directly or via the 1-arg
	 * / 2-arg overloads.
	 *
	 * @param context  Activity context for resources and gesture detectors
	 * @param attrs    XML attributes from layout inflation; may be null
	 * @param defStyle default style attribute, forwarded to View's constructor for theming
	 */
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
				fireZoomChanged();
				invalidate();
				return true;
			}

			@Override
			public void onScaleEnd(ScaleGestureDetector detector)
			{
				isScaling = false;
			}
		});

		// Drive the fling from a Choreographer frame callback rather than View.computeScroll. computeScroll
		// runs inside the view's own draw pass — since the editor view sits above this ruler in the layout, by
		// the time computeScroll fires and invalidates the editor, the editor's draw for this frame is already
		// complete. The editor then catches up one frame later, which at high fling velocity is visible as the
		// crop/grid briefly appearing at the previous rotation's position. A FrameCallback runs in
		// Choreographer's animation phase, BEFORE traversal — so the state update lands in time for both the
		// ruler and the editor to draw it in the same frame. No lag, no flicker.
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
						commitSnappedDegrees(currentDegrees);
					}
					// Re-check flingActive before reposting: notifyChanged above ultimately calls
					// back into setDegrees via the state listener, and a future caller invoking
					// setDegrees outside the isRulerUpdating guard could flip flingActive=false via
					// stopFling. Reposting unconditionally would resurrect the fling. Cheap defence
					// against that class of bug.
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

	/**
	 * True when further zoom-in (finer ticks) is possible — i.e., pixelsPerDegree is below the MAX_PPD_FACTOR cap.
	 * Used by the toolbar's btnRotZoomIn enable-state binding so the button dims out once the ruler is at maximum
	 * zoom.
	 *
	 * @return true if zoomBy(>1) would change the zoom; false at the cap
	 */
	public boolean canZoomIn()
	{
		return pixelsPerDegree < basePixelsPerDegree * MAX_PPD_FACTOR;
	}

	/**
	 * True when further zoom-out (coarser ticks) is possible — i.e., pixelsPerDegree is above the MIN_PPD_FACTOR
	 * floor. Used by the toolbar's btnRotZoomOut enable-state binding so the button dims out once the ruler is at
	 * minimum zoom.
	 *
	 * @return true if zoomBy(<1) would change the zoom; false at the floor
	 */
	public boolean canZoomOut()
	{
		return pixelsPerDegree > basePixelsPerDegree * MIN_PPD_FACTOR;
	}

	/**
	 * Cancel any in-flight fling immediately, freezing the ruler at its current reading. The host calls this when a
	 * background op (save / load / graft / auto-rotate) claims the busy gate: the fling runs on Choreographer frame
	 * callbacks rather than touch, so the touch-blocking progress overlay does not stop it, and momentum frames
	 * would otherwise keep mutating CropState's rotation while the bg thread reads geometry and the HDR gain-map
	 * angle at separate times — encoding the primary and gain map at two different rotations. The last fling frame
	 * already notified the listener, so the frozen reading is the one CropState holds. Idempotent — safe when no
	 * fling is active.
	 */
	public void cancelMomentum()
	{
		stopFling();
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
			case MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event);
			case MotionEvent.ACTION_UP ->
			{
				handleTouchRelease(event);
				// Accessibility hook — a view consuming touch must route a completed release through
				// performClick so a11y services can replicate the gesture and the click sound fires.
				// Only ACTION_UP: a CANCEL is a stolen/aborted gesture that handleTouchRelease discards
				// without snapping, so emitting a click there would announce an action that never
				// committed.
				performClick();
			}
			case MotionEvent.ACTION_CANCEL -> handleTouchRelease(event);
		}
		return true;
	}

	@Override
	public boolean performClick()
	{
		// Required by the View / accessibility contract for any view that overrides onTouchEvent — the rotation
		// drag itself is committed via handleTouchMove + handleTouchRelease above; this hook just delegates to
		// super for the standard click sound + accessibility events.
		return super.performClick();
	}

	/**
	 * Set the ruler reading programmatically. Clamps `deg` into [MIN_DEG, MAX_DEG] and stops any in-flight fling so
	 * the ruler doesn't keep coasting past the new value. Does NOT fire onRotationChanged — callers use this to
	 * sync the ruler to externally-changed state (e.g. auto-rotate result from horizon detection), not to drive the
	 * listener loop.
	 *
	 * @param deg desired rotation reading; values outside [MIN_DEG, MAX_DEG] are clamped
	 */
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

	/**
	 * Register the listener that receives rotation-degree updates as the user drags / flings / pinches the ruler.
	 * Single-listener semantics: setting a new one replaces any prior listener, and passing null clears it. The
	 * listener fires on every degree change including momentum frames during a fling, so its implementation must be
	 * cheap.
	 *
	 * @param listener new listener, or null to clear
	 */
	public void setOnRotationChangedListener(OnRotationChangedListener listener)
	{
		this.listener = listener;
	}

	/**
	 * Install a listener that fires whenever pixelsPerDegree changes (zoomBy, zoomToMax, or pinch-zoom via the
	 * scale gesture detector). Used by the toolbar to refresh the btnRotZoomIn / btnRotZoomOut enable states; only
	 * one listener is supported so this overwrites any previously-set one.
	 *
	 * @param listener fired (on the UI thread) after each zoom mutation; null to clear
	 */
	public void setOnZoomChangedListener(Runnable listener)
	{
		this.onZoomChanged = listener;
	}

	/**
	 * Toggle the ruler's interactive state. Disabled rulers ignore touch events and dim to 30% alpha so the user
	 * can see the value but can't change it (used during long-running operations like image load).
	 *
	 * @param enabled true to accept touch input and render at full opacity
	 */
	public void setRulerEnabled(boolean enabled)
	{
		this.enabled = enabled;
		setAlpha(enabled ? 1f : 0.3f);
	}

	/**
	 * Multiply the current ruler zoom by a factor and clamp into the valid range. Used by the toolbar's − / +
	 * buttons that flank the ruler.
	 *
	 * @param scaleFactor multiplier on pixels-per-degree; > 1 zooms in (finer ticks, smaller visible degree
	 *                    span), < 1 zooms out
	 */
	public void zoomBy(float scaleFactor)
	{
		// Stop any in-flight fling before mutating pixelsPerDegree. flingFrameCallback decodes
		// scroller.getCurrX() back to degrees by dividing by `pixelsPerDegree * SCROLL_SUBPIXEL_SCALE`, where
		// the dividend was scaled by the OLD pixelsPerDegree at fling start; changing the divisor mid-coast
		// makes the same scroller position decode to a different angle and the displayed rotation visibly
		// jumps. setDegrees applies the same guard.
		stopFling();
		pixelsPerDegree = Math.clamp(pixelsPerDegree * scaleFactor,
			basePixelsPerDegree * MIN_PPD_FACTOR, basePixelsPerDegree * MAX_PPD_FACTOR);
		fireZoomChanged();
		invalidate();
	}

	/**
	 * Snap the ruler to its maximum zoom level (finest 0.01° tick precision). Called by the auto-rotate flow after
	 * horizon detection lands a precise angle, so the user can immediately fine-tune around the detected value.
	 */
	public void zoomToMax()
	{
		// Same fling-divisor invariant as zoomBy — see comment there.
		stopFling();
		pixelsPerDegree = basePixelsPerDegree * MAX_PPD_FACTOR;
		fireZoomChanged();
		invalidate();
	}

	@Override
	protected void onDetachedFromWindow()
	{
		// If the view is torn down mid-gesture (config change, parent removal), Android won't dispatch
		// ACTION_UP/CANCEL — the tracker and scroller leak without this cleanup.
		if (velocityTracker != null)
		{
			velocityTracker.recycle();
			velocityTracker = null;
		}
		stopFling();
		// Don't null `listener` here. setOnRotationChangedListener is called once in
		// ToolbarBinder.setupRotation during MainActivity.onCreate; if the view detaches and re-attaches
		// without Activity recreation (fragment swap, removeView/addView, view-tree manipulation), there's no
		// path to re-register and the ruler would silently no-op every gesture. The listener is activity-scoped
		// (ToolbarBinder lives for the activity) so leaving it bound across detach/reattach cycles doesn't
		// leak.
		super.onDetachedFromWindow();
	}

	@Override
	protected void onDraw(Canvas canvas)
	{
		int width = getWidth();
		int height = getHeight();
		float centerX = width / 2f;
		// Breathing room between the bottom of the degree labels and the next row's content. Without it the
		// labels sit flush against the info bar / row divider underneath; the gap reads as a missing baseline.
		// 3dp is enough to look intentional without consuming the tick area.
		float labelBottomPaddingPx = 3 * getResources().getDisplayMetrics().density;
		float labelY = height - 1 - labelBottomPaddingPx;
		float tickTop = 2;
		float tickBot = height - labelPaint.getTextSize() - 3 - labelBottomPaddingPx;

		float degreesVisible = width / pixelsPerDegree;
		TickConfig tickConfig = chooseTickConfig(degreesVisible);

		// Use integer tick indices to avoid float accumulation errors. tickIndex * tickConfig.minor = degree
		// value.
		float halfVisible = degreesVisible / 2f;
		int iStart = (int) Math.floor((currentDegrees - halfVisible - tickConfig.major) / tickConfig.minor);
		int iEnd = (int) Math.ceil((currentDegrees + halfVisible + tickConfig.major) / tickConfig.minor);

		// Multiplier to convert minor intervals to major check (integer comparison)
		int majorEvery = Math.round(tickConfig.major / tickConfig.minor);

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

			// Heavy-mark a tick only when it IS a detent on the current grid — never promote a neighbour.
			// Off-grid detents (only ±45° at minor=10°, which sits between the 40° and 50° ticks) are drawn
			// separately below at their exact position, so the marker always lands where release-snap
			// (snapToDetentOrTick) pulls instead of on the nearest tick.
			boolean isDetent = isDetentTick(deg, tickConfig.minor());
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

		// Off-grid detent markers. At the coarsest zoom (minor=10°) the ±45° detents fall between the 40° and
		// 50° ticks, so the tick loop above (which marks on-grid detents only) skips them. Draw them here at
		// their exact degree position so the heavy marker + label sit on the value release-snap pulls to — the
		// only off-grid detents in the whole zoom range are ±45° at minor=10°.
		for (float detent : DETENTS)
		{
			if (isDetentOnGrid(detent, tickConfig.minor()))
			{
				continue;
			}
			float detentX = centerX + (detent - currentDegrees) * pixelsPerDegree;
			if (detentX < -OFF_SCREEN_MARGIN || detentX > width + OFF_SCREEN_MARGIN)
			{
				continue;
			}
			canvas.drawLine(detentX, tickTop, detentX, tickBot, detentTickPaint);
			canvas.drawText(TextFormat.degrees(detent), detentX, labelY, labelPaint);
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

	/**
	 * True when the tick at `deg` is exactly a detent on the current grid. Off-grid detents (only ±45° at
	 * minor=10°, between the 40°/50° ticks) return false — onDraw renders those at their exact position so the
	 * marker never lands on a promoted neighbour. Compares by signed tick index (tolerates float residue in `deg`);
	 * the isDetentOnGrid guard rejects the off-grid case the index alone would accept (45° and 50° share index 5 at
	 * minor=10°). Package-private so the test can pin placement Canvas-free.
	 *
	 * @param deg   degree value of the tick being drawn (an integer multiple of minor)
	 * @param minor minor-tick spacing in degrees at the current zoom
	 * @return true when this tick is exactly an on-grid detent
	 */
	static boolean isDetentTick(float deg, float minor)
	{
		long tickIndex = signedTickIndex(deg, minor);
		for (float detent : DETENTS)
		{
			if (isDetentOnGrid(detent, minor) && signedTickIndex(detent, minor) == tickIndex)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Pointer-transition math for ACTION_POINTER_UP of the active pointer. Picks the surviving finger — the
	 * standard Android active-pointer idiom: index 1 when the lifted finger sat at index 0, otherwise index 0 (both
	 * indices are guaranteed populated because POINTER_UP only fires with at least two fingers down) — and returns
	 * its id together with its own current x as the new drag baseline. Rebasing to the survivor's x makes the first
	 * post-promotion MOVE delta exactly zero; see PointerPromotion for why that matters.
	 *
	 * @param liftedIndex pointer index of the finger that lifted (the event's action index)
	 * @param id0         pointer id at index 0
	 * @param id1         pointer id at index 1
	 * @param x0          current x of the pointer at index 0
	 * @param x1          current x of the pointer at index 1
	 * @return the surviving pointer's id and x — the new activePointerId / lastTouchX pair
	 */
	static PointerPromotion promoteActivePointer(int liftedIndex, int id0, int id1, float x0, float x1)
	{
		if (liftedIndex == 0)
		{
			return new PointerPromotion(id1, x1);
		}
		return new PointerPromotion(id0, x0);
	}

	/**
	 * Choose tick intervals based on how many degrees are visible on screen. Walks TICK_THRESHOLDS in order; the
	 * first threshold strictly below degreesVisible picks that index's TickConfig. The last threshold is 0 so the
	 * loop always terminates.
	 *
	 * @param degreesVisible degree span currently visible across the ruler's width
	 * @return the matching zoom tier's tick configuration (finest tier when nothing coarser matches)
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

	/**
	 * True when `detent` lands exactly on the tick grid at `minor` spacing — i.e. detent / minor is a whole number.
	 * The only off-grid case in the whole zoom range is ±45° at minor=10° (4.5); every other detent divides every
	 * minor spacing. onDraw uses this to decide whether a detent is already drawn by the tick loop or needs its own
	 * standalone marker.
	 *
	 * @param detent detent value in degrees
	 * @param minor  minor-tick spacing in degrees at the current zoom
	 * @return true when the detent coincides with a grid tick
	 */
	private static boolean isDetentOnGrid(float detent, float minor)
	{
		float index = detent / minor;
		return Math.abs(index - Math.round(index)) < 0.001f;
	}

	/**
	 * Tick index of `deg` at `minor` spacing, rounding the magnitude and reapplying the sign so the grid is
	 * symmetric about zero (round-half-away-from-zero, not Math.round's round-half-up).
	 *
	 * @param deg   degree value
	 * @param minor minor-tick spacing in degrees
	 * @return signed nearest-tick index
	 */
	private static long signedTickIndex(float deg, float minor)
	{
		return (long) Math.signum(deg) * Math.round(Math.abs(deg) / minor);
	}

	private static float snapTo(float val, float step)
	{
		return Math.round(val / step) * step;
	}

	/**
	 * Snap the given target degree value to the nearest detent / minor tick, clamp to the ruler range, and commit
	 * it as the new currentDegrees — notifying the listener and invalidating only when the snapped result differs
	 * from the pre-call currentDegrees. Both the tap and the drag / fling release paths feed through here so the
	 * listener sees a coherent post-gesture value regardless of mid-gesture state.
	 *
	 * Critical contract: the diff is computed against the OLD currentDegrees, then assigned — a write-then-compare
	 * order would silently swallow the notify when the gesture lands exactly on a tick / detent (the snapped value
	 * would match the just-overwritten field).
	 *
	 * @param targetDeg desired new rotation in degrees (gesture-tapped or drag-end value)
	 */
	private void commitSnappedDegrees(float targetDeg)
	{
		float snapped = Math.clamp(snapToDetentOrTick(targetDeg), MIN_DEG, MAX_DEG);
		if (snapped != currentDegrees)
		{
			currentDegrees = snapped;
			notifyChanged();
			invalidate();
		}
	}

	private void fireZoomChanged()
	{
		if (onZoomChanged != null)
		{
			onZoomChanged.run();
		}
	}

	/**
	 * ACTION_POINTER_UP: when the lifted finger is the active pointer, promote the survivor via
	 * promoteActivePointer — its id becomes activePointerId and lastTouchX rebases to its x so the next MOVE
	 * contributes zero delta. A non-active finger lifting changes nothing: the active pointer's identity and
	 * baseline stay valid.
	 *
	 * @param event the ACTION_POINTER_UP event; getActionIndex() names the lifted finger
	 */
	private void handlePointerUp(MotionEvent event)
	{
		int liftedIndex = event.getActionIndex();
		if (event.getPointerId(liftedIndex) != activePointerId)
		{
			return;
		}
		PointerPromotion promotion = promoteActivePointer(liftedIndex,
			event.getPointerId(0), event.getPointerId(1), event.getX(0), event.getX(1));
		activePointerId = promotion.pointerId();
		lastTouchX = promotion.lastTouchX();
	}

	/**
	 * ACTION_DOWN: stop any in-flight fling, reset per-gesture accumulators, and ask the parent not to intercept
	 * subsequent moves (the rotation dial owns horizontal drag inside its bounds). getParent() is null between
	 * detach and re-attach during config changes — skip the request rather than NPE.
	 *
	 * @param event the ACTION_DOWN event; its primary pointer becomes the active pointer and drag baseline
	 */
	private void handleTouchDown(MotionEvent event)
	{
		stopFling();
		activePointerId = event.getPointerId(0);
		downX = lastTouchX = event.getX();
		totalDragDx = 0;
		scalingOccurred = false;
		gestureStartDegrees = currentDegrees;
		ViewParent parent = getParent();
		if (parent != null)
		{
			parent.requestDisallowInterceptTouchEvent(true);
		}
	}

	/**
	 * ACTION_MOVE: single-finger horizontal drag advances currentDegrees; pinch drags are handled by scaleDetector
	 * and suppressed here (isScaling / pointerCount > 1). Reads the ACTIVE pointer's x — never index 0 — so a
	 * multi-touch index shuffle can't feed another finger's position into the drag delta; handlePointerUp keeps
	 * activePointerId / lastTouchX coherent across finger lifts.
	 *
	 * @param event the ACTION_MOVE event; only the active pointer's x is consumed
	 */
	private void handleTouchMove(MotionEvent event)
	{
		int pointerIndex = event.findPointerIndex(activePointerId);
		if (pointerIndex < 0)
		{
			// Active pointer missing from this event (a POINTER_UP was consumed elsewhere or arrived out of
			// order — not expected under normal dispatch). Re-anchor on the primary pointer without
			// emitting a delta rather than rotate from a stale baseline.
			activePointerId = event.getPointerId(0);
			lastTouchX = event.getX(0);
			return;
		}
		float x = event.getX(pointerIndex);
		if (!isScaling && event.getPointerCount() == 1)
		{
			float dx = x - lastTouchX;
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
		lastTouchX = x;
	}

	/**
	 * ACTION_UP / ACTION_CANCEL: classify the gesture as tap / drag-release-slow / drag-release-fast and dispatch
	 * accordingly. Recycles the velocity tracker on every exit so the next gesture starts fresh.
	 *
	 * ACTION_CANCEL is an interrupted gesture, not a user-completed release — Android dispatches it when the OS or
	 * a parent view claims the gesture (system back, multi-touch disambiguation, scroll-container intercept).
	 * handleTouchMove commits each drag delta live, so the cancel path rolls currentDegrees back to the pre-gesture
	 * value (gestureStartDegrees) and fires one corrective notify, leaving no partial rotation. It never flings or
	 * snaps, which would apply a rotation the user never committed.
	 *
	 * @param event the ACTION_UP or ACTION_CANCEL event ending the gesture
	 */
	private void handleTouchRelease(MotionEvent event)
	{
		// Defensive null guard — onDetachedFromWindow recycles + nulls velocityTracker, and onTouchEvent's lazy
		// creation only fires on ACTION_DOWN. Today the lifecycle ordering keeps these in sync (Android
		// serializes touch dispatch and detach on the UI thread), but any future view-tree manipulation that
		// delivers ACTION_UP after a detach (e.g., a fragment library moving the view between containers
		// mid-gesture) would NPE the dereferences below. Cheap to guard; saves a UI-thread crash.
		if (velocityTracker == null)
		{
			return;
		}
		if (event.getActionMasked() == MotionEvent.ACTION_CANCEL)
		{
			// Interrupted gesture: roll the live-committed drag back to the pre-gesture angle so the
			// interrupt leaves no partial rotation (REQUIREMENTS.md interrupted-gesture cleanup).
			// handleTouchMove publishes each delta immediately, so currentDegrees holds the mid-drag value
			// here; restoring gestureStartDegrees and notifying undoes it. No fling, no snap.
			velocityTracker.recycle();
			velocityTracker = null;
			if (currentDegrees != gestureStartDegrees)
			{
				currentDegrees = gestureStartDegrees;
				notifyChanged();
				invalidate();
			}
			return;
		}
		// If a pinch-zoom occurred during this gesture, skip the angle fling / snap entirely. onScaleEnd fires
		// before ACTION_UP so isScaling is already false here — but scalingOccurred stays true for the full
		// gesture lifetime, and without this check the VelocityTracker's x-velocity (populated by the pinch
		// focus-point motion) would trigger a spurious rotation change on release.
		if (!isScaling && !scalingOccurred)
		{
			// Tap: total movement below the slop. Feed the tapped angle through commitSnappedDegrees so the
			// snapped value is compared against the PRE-tap currentDegrees (see that method's
			// write-then-compare contract).
			if (totalDragDx <= TAP_SLOP && event.getActionMasked() == MotionEvent.ACTION_UP)
			{
				float centerX = getWidth() / 2f;
				float tappedDeg = currentDegrees + (downX - centerX) / pixelsPerDegree;
				commitSnappedDegrees(tappedDeg);
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
				// Slow drag-release: skip the detent the gesture started on so a deliberate small drag
				// away from 0° (e.g. setting 0.4° at coarse zoom where the 0.5° detent threshold would
				// otherwise swallow the move) actually lands at the new value rather than re-snapping
				// back. Fast flings land wherever the trajectory dictates and tap snaps normally, so
				// only this slow-drag-release path passes a skipDetent.
				float snapped = Math.clamp(snapToDetentOrTick(currentDegrees, gestureStartDegrees),
					MIN_DEG, MAX_DEG);
				if (snapped != currentDegrees)
				{
					currentDegrees = snapped;
					notifyChanged();
					invalidate();
				}
			}
		}
		velocityTracker.recycle();
		velocityTracker = null;
	}

	private void notifyChanged()
	{
		if (listener != null)
		{
			listener.onRotationChanged(currentDegrees);
		}
	}

	/**
	 * Snap a degree value to the nearest documented detent (0, ±45, ±90, ±180) when within the per-zoom detent
	 * threshold (min(minor * 0.5, 0.8°)), otherwise fall back to the current zoom's minor-tick snap. The threshold
	 * scales with visible minor-tick spacing so the detent stays sticky at coarse zoom (where ±45° isn't on the
	 * tick grid) without swallowing legitimate fine-tunes at max zoom.
	 *
	 * @param deg ruler reading at gesture release
	 * @return detent value when within threshold; nearest minor tick otherwise
	 */
	private float snapToDetentOrTick(float deg)
	{
		return snapToDetentOrTick(deg, Float.NaN);
	}

	/**
	 * Drag-release variant of snapToDetentOrTick that ignores the detent the gesture STARTED at, so a drag from 0°
	 * to 0.4° doesn't re-snap back to 0°. Compared with a tight tolerance because gestureStartDegrees is a
	 * previously-snapped value. NaN for skipDetent falls back to the original snap-to-any-detent behaviour (used by
	 * tap, which has no "previous state" to escape from).
	 *
	 * @param deg        ruler reading at gesture release / tap
	 * @param skipDetent detent value to skip (NaN to skip none)
	 * @return detent value when within threshold (excluding skipDetent); nearest minor tick otherwise
	 */
	private float snapToDetentOrTick(float deg, float skipDetent)
	{
		float degreesVisible = getWidth() > 0 ? getWidth() / pixelsPerDegree : 30f;
		TickConfig tickConfig = chooseTickConfig(degreesVisible);
		float detentThreshold = Math.min(tickConfig.minor() * DETENT_SNAP_MINOR_FACTOR,
			DETENT_SNAP_MAX_DEGREES);
		for (float detent : DETENTS)
		{
			if (!Float.isNaN(skipDetent) && Math.abs(detent - skipDetent) < 0.001f)
			{
				continue;
			}
			if (Math.abs(deg - detent) <= detentThreshold)
			{
				return detent;
			}
		}
		return snapTo(deg, tickConfig.minor());
	}

	/**
	 * Fire the OverScroller + register the Choreographer frame callback for fling. Separated from
	 * handleTouchRelease so the high-velocity branch reads as a single named action rather than eight lines of
	 * scroller-setup arithmetic.
	 *
	 * @param xVelocity release velocity in px/s from the VelocityTracker; negated because dragging right
	 *                  decreases the rotation angle
	 */
	private void startFling(float xVelocity)
	{
		// The int scroller bounds stay well inside Integer.MAX_VALUE: maxX = MAX_DEG(180) * pixelsPerDegree *
		// SCROLL_SUBPIXEL_SCALE(1000), and pixelsPerDegree caps at 12*density*MAX_PPD_FACTOR(120). At density 4
		// (xxxhdpi, the highest real bucket) that's ~1.04e9 < 2.15e9. A future MAX_PPD_FACTOR bump or a >8x
		// density would overflow and corrupt the fling decode — clamp `scaled` if either ever changes.
		float scaled = pixelsPerDegree * SCROLL_SUBPIXEL_SCALE;
		int startX = (int) (currentDegrees * scaled);
		int minX = (int) (MIN_DEG * scaled);
		int maxX = (int) (MAX_DEG * scaled);
		scroller.fling(startX, 0, (int) (-xVelocity * SCROLL_SUBPIXEL_SCALE), 0, minX, maxX, 0, 0);
		flingActive = true;
		Choreographer.getInstance().postFrameCallback(flingFrameCallback);
	}

	/**
	 * Cancel any active fling and unregister the pending frame callback so it doesn't reschedule itself. Safe to
	 * call when no fling is active — idempotent.
	 */
	private void stopFling()
	{
		flingActive = false;
		scroller.forceFinished(true);
		Choreographer.getInstance().removeFrameCallback(flingFrameCallback);
	}
}
