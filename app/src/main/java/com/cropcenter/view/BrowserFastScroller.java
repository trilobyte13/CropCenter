package com.cropcenter.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cropcenter.util.DpToPx;
import com.cropcenter.util.ThemeColors;

import java.util.Collections;
import java.util.function.Consumer;

/**
 * Always-visible draggable fast scroller for FolderBrowser. Replaces AndroidX's built-in
 * initFastScroller for two reasons:
 *   1. The built-in thumb height is proportional to visibleCount/itemCount with no minimum — on a
 *      50k-item album it collapses to a 1px sliver that can't be grabbed.
 *   2. Its position math (computeVerticalScrollOffset / Range / Extent) hits SpanSizeLookup.getSpanIndex,
 *      O(N) per call on GridLayoutManager — every drag frame janks on huge folders.
 *
 * Both thumb size and position are computed in ROW space, mapping adapter positions through the
 * GridLayoutManager.SpanSizeLookup so mixed-span layouts (folder rows span all columns, file rows span 1)
 * get proportional travel and a stable thumb height. The row helpers (rowIndexFor / maxFirstVisibleRow /
 * positionForRow) are O(1) for BrowserSpanSizeLookup, falling back to an O(log N) binary search for
 * arbitrary lookups. A 32dp minimum height keeps the thumb grabbable. Seek uses scrollToPositionWithOffset
 * (O(1) — sets the anchor, next layout fills from there), instant even at position 25,000 of 50,000.
 *
 * Drag dispatch is throttled to one scrollToPositionWithOffset per frame via postOnAnimation: MOVE events
 * arrive at 120 Hz and each requestLayout, but the coalesced layout commits to the LAST queued target, so
 * without throttling the recycler keeps catching up to stale targets after the finger lifts — looking like
 * fling momentum. On ACTION_UP / CANCEL the pending scroll is cancelled and the recycler is re-anchored at
 * its current first-child via pinToCurrentScrollPosition, OVERWRITING any queued target so it can't "catch
 * up" past the release position (a sync scroll on UP would use the flick-affected thumb coordinate and
 * overshoot — the "auto-scroll on release" symptom). At maxed-out drag (ratio ≥ 1.0) the final-row reach
 * delegates to scrollBy(0, Integer.MAX_VALUE) so the LayoutManager's own clamp handles per-row heights /
 * padding / partial last row, which the position-based math undercounts in mixed-span layouts.
 *
 * During drag the thumb follows the finger directly (decoupled from recycler scroll) for immediate
 * feedback; between drags its position derives from the recycler scroll offset.
 *
 * The view overlays the RecyclerView's right edge (FrameLayout). With no thumb (content fits) ACTION_DOWN
 * returns false so touches reach the cells; with a thumb, every touch in the strip is consumed — within
 * THUMB_TOUCH_HALO_DP of the thumb starts dragging, further away awaits touch-slop then snaps to finger.
 * Consuming ALL in-strip touches (not just the visible thumb rect) stops an off-thumb tap falling through
 * to the RecyclerView and fling-scrolling on release. onSizeChanged registers the strip with
 * setSystemGestureExclusionRects so the edge back-swipe gesture doesn't intercept touches first.
 */
public final class BrowserFastScroller extends View
{
	// 32dp floor — comfortable Material-spec aim target. 24dp (the prior value) was just
	// inside the "easy to miss" zone on tall folders where the thumb floors at the minimum;
	// 32dp keeps it grabbable at a glance without losing the proportional thumb-size feedback
	// on smaller folders. The earlier 48dp floor was over-generous and pegged on anything past
	// ~2 viewports of content, hiding the size cue entirely.
	private static final int MIN_THUMB_HEIGHT_DP = 32;
	private static final int THUMB_RADIUS_DP = 4;
	// Vertical halo around the visible thumb that ALSO grabs immediately. Without it the user has
	// to land their fingerpad bullseye on a 32dp-tall target — a typical fingerpad is ~40-50dp,
	// so the bottom or top of the touch can easily land just outside the visual thumb and trip
	// the awaiting-slop branch (no immediate drag), which reads as "finicky to grab". 24dp of
	// halo above and below means anything within a ~80dp Y-band grabs at the first touch — wide
	// enough that a deliberate aim near the thumb never misses, while still leaving room for the
	// off-thumb-tap-then-drag-to-jump pattern further away on the track.
	private static final int THUMB_TOUCH_HALO_DP = 24;
	// Visible thumb width; the View itself is wider (40dp via layout XML) so the touch target
	// stays Material-spec generous with extra slack for fingerpad-sized aim, but the painted
	// thumb is only this many dp wide on the right edge so it doesn't crowd the cells
	// underneath. Without the wider touch target users miss the narrow thumb and their touch
	// falls through to the RecyclerView, which interprets the missed grab as a swipe and
	// fling-scrolls on release (the "autoscroll after drag" symptom). 8dp is wide enough to be
	// visually findable without crowding the rightmost cell column; the prior 6dp read as a
	// hairline against the sidebar background and was hard to aim at.
	private static final int THUMB_WIDTH_DP = 8;

	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF thumbRect = new RectF();
	private final int thumbMinHeightPx;
	private final int thumbTouchHaloPx;
	private final int thumbWidthPx;
	private final int touchSlopPx;
	private final float thumbRadiusPx;

	private final RecyclerView.AdapterDataObserver dataObserver = new RecyclerView.AdapterDataObserver()
	{
		@Override
		public void onChanged()
		{
			invalidate();
		}

		@Override
		public void onItemRangeChanged(int positionStart, int itemCount)
		{
			invalidate();
		}

		@Override
		public void onItemRangeInserted(int positionStart, int itemCount)
		{
			invalidate();
		}

		@Override
		public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount)
		{
			invalidate();
		}

		@Override
		public void onItemRangeRemoved(int positionStart, int itemCount)
		{
			invalidate();
		}
	};

	private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener()
	{
		@Override
		public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy)
		{
			invalidate();
		}
	};

	private final Runnable scrollRunnable = () ->
	{
		scrollScheduled = false;
		scrollRecyclerToDraggedPosition();
	};

	private Consumer<Boolean> onDragStateChanged;
	private RecyclerView recycler;
	private boolean awaitingDragSlop;
	private boolean dragging;
	private boolean initialTouchOnThumb;
	private boolean scrollScheduled;
	private float dragOffsetWithinThumb;
	private float draggedThumbTop;
	private float initialTouchY;

	public BrowserFastScroller(@NonNull Context context)
	{
		this(context, null);
	}

	public BrowserFastScroller(@NonNull Context context, @Nullable AttributeSet attrs)
	{
		super(context, attrs);
		float density = context.getResources().getDisplayMetrics().density;
		thumbMinHeightPx = DpToPx.toPx(MIN_THUMB_HEIGHT_DP, density);
		thumbTouchHaloPx = DpToPx.toPx(THUMB_TOUCH_HALO_DP, density);
		thumbWidthPx = DpToPx.toPx(THUMB_WIDTH_DP, density);
		thumbRadiusPx = DpToPx.toPx(THUMB_RADIUS_DP, density);
		touchSlopPx = ViewConfiguration.get(context).getScaledTouchSlop();
	}

	/**
	 * Attach the scroller to a RecyclerView. Registers a scroll listener (to invalidate on every
	 * scroll so the thumb follows the viewport) and an adapter data observer (to invalidate when
	 * folder enumeration finishes and the item count jumps from 0 to many). Detaches from any
	 * previously-attached RecyclerView so re-attaching to a different one is safe — though in
	 * practice FolderBrowser only attaches once per panel build.
	 *
	 * @param recyclerView the RecyclerView this scroller should track; null detaches without
	 *                     re-attaching
	 */
	public void attachTo(@Nullable RecyclerView recyclerView)
	{
		if (recycler == recyclerView)
		{
			return;
		}
		detachFromCurrent();
		recycler = recyclerView;
		if (recyclerView != null)
		{
			recyclerView.addOnScrollListener(scrollListener);
			RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
			if (adapter != null)
			{
				adapter.registerAdapterDataObserver(dataObserver);
			}
		}
		invalidate();
	}

	@Override
	public boolean onTouchEvent(MotionEvent event)
	{
		if (recycler == null)
		{
			return false;
		}
		switch (event.getActionMasked())
		{
			case MotionEvent.ACTION_DOWN:
				return onDown(event.getY());
			case MotionEvent.ACTION_MOVE:
				return onMove(event.getY());
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				return onUpOrCancel();
			default:
				return false;
		}
	}

	/**
	 * Install a listener that fires when the user's drag state changes — `accept(true)` when an
	 * active drag begins (immediate on-thumb grab, or off-thumb tap that crossed touch slop),
	 * `accept(false)` when the drag ends (UP or CANCEL after a real drag). FolderBrowser uses
	 * this to defer thumbnail decoding during fast-scroll so the bg threads aren't churning
	 * through stale decodes for cells that recycle past before paint.
	 *
	 * @param listener callback fired on drag begin/end; null clears any previously-set listener
	 */
	public void setOnDragStateChangedListener(@Nullable Consumer<Boolean> listener)
	{
		this.onDragStateChanged = listener;
	}

	@Override
	protected void onDetachedFromWindow()
	{
		super.onDetachedFromWindow();
		detachFromCurrent();
	}

	@Override
	protected void onDraw(Canvas canvas)
	{
		if (!computeThumbBounds())
		{
			return;
		}
		paint.setColor(dragging ? ThemeColors.MAUVE : ThemeColors.SUBTEXT0);
		canvas.drawRoundRect(thumbRect, thumbRadiusPx, thumbRadiusPx, paint);
	}

	@Override
	protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight)
	{
		super.onSizeChanged(width, height, oldWidth, oldHeight);
		// Claim the entire FastScroller strip from system gesture detection. The scroller sits on
		// the screen's right edge, which is where Android dispatches the gesture-nav "back" swipe
		// and the edge-glow accent — without exclusion, a touch on the FastScroller can be
		// intercepted by the system before reaching our onTouchEvent, which reads as the thumb
		// being "finicky to grab" (the touch never registers on the scroller; the gesture-nav
		// triggers a back swipe instead). setSystemGestureExclusionRects tells the OS "this area
		// is mine — don't intercept here." Updated on every size change because the view's bounds
		// can shift across config changes (rotation, multi-window resize).
		setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, width, height)));
	}

	/**
	 * Last position whose item is FULLY visible in the viewport — falls back to the partially-
	 * visible last position when no item fits completely (extreme zoom, viewport-smaller-than-cell
	 * pathological case). Used in place of findLastVisibleItemPosition so the scroll math treats
	 * the partially-clipped bottom row as outside the visible window.
	 *
	 * @param linearLm the LayoutManager whose viewport is being queried; non-null
	 * @return adapter position of the last fully-visible item, or — when no item is fully visible —
	 *         the last partially-visible position; returns RecyclerView.NO_POSITION when neither
	 *         is available (no items laid out yet)
	 */
	private static int findLastFullyVisibleItemPosition(LinearLayoutManager linearLm)
	{
		int last = linearLm.findLastCompletelyVisibleItemPosition();
		if (last != RecyclerView.NO_POSITION)
		{
			return last;
		}
		return linearLm.findLastVisibleItemPosition();
	}

	/**
	 * Binary-search the smallest adapter position whose row index (per the GridLayoutManager's
	 * SpanSizeLookup) is ≥ targetRow. Used by maxFirstVisibleForRowReach to translate a target
	 * row back to the first item that lives in it — necessary in mixed-span layouts where row
	 * R's first item isn't simply R × spanCount (folder rows occupy a full row but advance only
	 * one item position). O(log itemCount) calls to getSpanGroupIndex; for FolderBrowser's
	 * BrowserSpanSizeLookup (which evaluates getSpanGroupIndex in O(1)) the search is itself
	 * O(log N).
	 *
	 * @param spanLookup the layout's SpanSizeLookup; non-null
	 * @param spanCount  the layout's span count
	 * @param targetRow  the row index whose first item is being sought; must be in
	 *                   [0, lastRow(itemCount−1) + 1]
	 * @param itemCount  adapter item count; > 0
	 * @return smallest position p ∈ [0, itemCount) with spanLookup.getSpanGroupIndex(p, spanCount)
	 *         ≥ targetRow; returns itemCount − 1 when no such p exists (targetRow past the last row)
	 */
	private static int firstItemInRow(GridLayoutManager.SpanSizeLookup spanLookup, int spanCount,
		int targetRow, int itemCount)
	{
		int lo = 0;
		int hi = itemCount - 1;
		while (lo < hi)
		{
			int mid = (lo + hi) >>> 1;
			if (spanLookup.getSpanGroupIndex(mid, spanCount) < targetRow)
			{
				lo = mid + 1;
			}
			else
			{
				hi = mid;
			}
		}
		return lo;
	}

	/**
	 * Cap on first-visible ROW INDEX (NOT item position) such that placing that row at the
	 * viewport top fits exactly the visible-rows window with the very last row flush against the
	 * bottom edge. Returning the row index instead of the item position lets callers do all the
	 * intermediate thumb-position / drag-seek math in row space — which is the only space where
	 * thumb travel maps linearly to user-visible scroll progress in mixed-span layouts (folder
	 * rows full-width, file rows 1-span each). For LinearLayoutManager / 1-span grid this is
	 * `itemCount − visibleCount` since rows and positions coincide. For multi-span grids it
	 * walks rows via spanLookup.getSpanGroupIndex.
	 *
	 * @param layoutManager the LayoutManager whose row geometry is being queried; non-null
	 * @param itemCount     adapter item count; > 0
	 * @param firstVisible  first-visible adapter position (partial-aware ok); ≥ 0
	 * @param lastVisible   last-FULLY-visible adapter position (caller passes
	 *                      findLastFullyVisibleItemPosition's result); ≥ firstVisible
	 * @return max first-visible row index such that scrolling there shows the very last row
	 *         flush against the viewport bottom; convert to an adapter position with
	 *         positionForRow
	 */
	private static int maxFirstVisibleRow(RecyclerView.LayoutManager layoutManager,
		int itemCount, int firstVisible, int lastVisible)
	{
		int lastRow = rowIndexFor(layoutManager, itemCount - 1);
		int firstVisibleRow = rowIndexFor(layoutManager, firstVisible);
		int lastVisibleRow = rowIndexFor(layoutManager, lastVisible);
		int visibleRows = lastVisibleRow - firstVisibleRow + 1;
		return Math.max(0, lastRow + 1 - visibleRows);
	}

	/**
	 * Convert a row index back to its first adapter position. For LinearLayoutManager / 1-span
	 * grid this is the identity (row index == position). For multi-span grids it delegates to
	 * firstItemInRow's binary search via the layout's SpanSizeLookup.
	 *
	 * @param layoutManager the LayoutManager whose SpanSizeLookup is used for the conversion
	 * @param row           row index to convert; ≥ 0
	 * @param itemCount     adapter item count; > 0 (used as the binary-search upper bound)
	 * @return smallest adapter position whose row index ≥ row; clamped to [0, itemCount − 1]
	 */
	private static int positionForRow(RecyclerView.LayoutManager layoutManager, int row,
		int itemCount)
	{
		if (row <= 0)
		{
			return 0;
		}
		if (layoutManager instanceof GridLayoutManager glm)
		{
			int spanCount = glm.getSpanCount();
			if (spanCount > 1)
			{
				return firstItemInRow(glm.getSpanSizeLookup(), spanCount, row, itemCount);
			}
		}
		return Math.min(row, itemCount - 1);
	}

	/**
	 * Row index of a given adapter position. For LinearLayoutManager / 1-span grid the row index
	 * IS the position. For multi-span grids it routes through GridLayoutManager's SpanSizeLookup
	 * so mixed-span layouts (folder rows full-width, file rows 1-span) return their actual row
	 * group rather than the misleading position / spanCount approximation.
	 *
	 * @param layoutManager the LayoutManager whose row geometry is being queried
	 * @param position      adapter position whose row is being looked up; ≥ 0
	 * @return row index of position (== position for non-grid / 1-span layouts)
	 */
	private static int rowIndexFor(RecyclerView.LayoutManager layoutManager, int position)
	{
		if (layoutManager instanceof GridLayoutManager glm)
		{
			int spanCount = glm.getSpanCount();
			if (spanCount > 1)
			{
				return glm.getSpanSizeLookup().getSpanGroupIndex(position, spanCount);
			}
		}
		return position;
	}

	/**
	 * Thumb height proportional to the fraction of ROWS currently visible (not the fraction of
	 * ADAPTER ITEMS), with a minThumbHeightPx floor so huge folders don't collapse the thumb to
	 * a 1px sliver. Item-based sizing produced a visibly oscillating thumb in mixed-span layouts
	 * — folder rows hold 1 item each, file rows hold spanCount items each, so scrolling from a
	 * folder section into a file section changed items/row by a factor of spanCount and the
	 * thumb correspondingly shrunk or grew. Row-based sizing keeps the thumb the same height
	 * regardless of which section is on screen, because the visible-rows / total-rows ratio
	 * doesn't depend on per-row item count.
	 *
	 * @param layoutManager    LayoutManager whose row geometry drives the conversion; non-null
	 * @param itemCount        adapter item count; > 0
	 * @param firstVisible     first-visible adapter position (partial-aware ok); ≥ 0
	 * @param lastVisible      last-FULLY-visible adapter position; ≥ firstVisible
	 * @param trackHeight      FastScroller view height in pixels; > 0
	 * @param minThumbHeightPx grabbability floor — even on 50k-item folders the thumb stays this
	 *                         tall at minimum so it can be aimed at
	 * @return thumb height in pixels
	 */
	private static float rowProportionalThumbHeight(RecyclerView.LayoutManager layoutManager,
		int itemCount, int firstVisible, int lastVisible, int trackHeight, int minThumbHeightPx)
	{
		int totalRows = rowIndexFor(layoutManager, itemCount - 1) + 1;
		int visibleRows = rowIndexFor(layoutManager, lastVisible)
			- rowIndexFor(layoutManager, firstVisible) + 1;
		return Math.max(minThumbHeightPx, (float) trackHeight * visibleRows / totalRows);
	}

	/**
	 * Recompute thumbRect from the recycler's current scroll position + adapter item count (or
	 * from draggedThumbTop when a drag is active). Sets thumbRect to the absolute coordinates
	 * the thumb should occupy within this view's bounds.
	 *
	 * @return true when a thumb should be drawn (content overflows the viewport and the recycler
	 *         is laid out enough to query positions); false when the thumb should be suppressed
	 *         (no items, no overflow, or LayoutManager not ready)
	 */
	private boolean computeThumbBounds()
	{
		if (recycler == null)
		{
			return false;
		}
		RecyclerView.Adapter<?> adapter = recycler.getAdapter();
		RecyclerView.LayoutManager layoutManager = recycler.getLayoutManager();
		if (adapter == null || !(layoutManager instanceof LinearLayoutManager linearLm))
		{
			return false;
		}
		int itemCount = adapter.getItemCount();
		if (itemCount == 0)
		{
			return false;
		}
		int firstVisible = linearLm.findFirstVisibleItemPosition();
		int lastVisible = findLastFullyVisibleItemPosition(linearLm);
		if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION)
		{
			return false;
		}
		int visibleCount = lastVisible - firstVisible + 1;
		if (visibleCount >= itemCount)
		{
			return false;
		}
		int trackHeight = getHeight();
		if (trackHeight == 0)
		{
			return false;
		}
		float thumbHeight = rowProportionalThumbHeight(layoutManager, itemCount,
			firstVisible, lastVisible, trackHeight, thumbMinHeightPx);
		float thumbTop;
		if (dragging)
		{
			// During drag, draw the thumb where the finger is — gives the user instant visual
			// feedback even if the recycler is still mid-layout from the previous frame's
			// scrollToPositionWithOffset. Without this the thumb visibly lags behind the finger
			// on huge folders, then jumps to the catch-up position when the user releases.
			thumbTop = Math.max(0f, Math.min(trackHeight - thumbHeight, draggedThumbTop));
		}
		else
		{
			// Row-based thumb placement. Adapter index would map non-linearly to user-perceived
			// scroll progress in mixed-span layouts (FolderBrowser: folder rows full-width, file
			// rows 1-span) — a folder list at the top would compress into the top sliver of the
			// track because it spans many positions per row while the file grid takes far fewer
			// positions per row. Mapping via row index makes thumb travel proportional to actual
			// rows scrolled.
			int maxFirstRow = maxFirstVisibleRow(layoutManager, itemCount, firstVisible, lastVisible);
			int currentRow = rowIndexFor(layoutManager, firstVisible);
			float scrollRatio = maxFirstRow <= 0 ? 0f : (float) currentRow / maxFirstRow;
			thumbTop = Math.clamp(scrollRatio, 0f, 1f) * (trackHeight - thumbHeight);
		}
		// Visible thumb sits on the right edge with a narrow paint width — the View's full width
		// (40dp via layout XML) is the touch target so a finger that misses the visible thumb by
		// a few dp still grabs the FastScroller rather than falling through to the RecyclerView
		// underneath (which would have interpreted the missed grab as a swipe and fling-scrolled
		// on release).
		thumbRect.set(getWidth() - thumbWidthPx, thumbTop, getWidth(), thumbTop + thumbHeight);
		return true;
	}

	private void detachFromCurrent()
	{
		// Cancel any pending throttled scroll runnable so a runnable scheduled by the last drag
		// doesn't fire after the view is detached (would no-op on a null recycler anyway, but
		// removeCallbacks keeps Choreographer's queue clean).
		removeCallbacks(scrollRunnable);
		scrollScheduled = false;
		if (recycler == null)
		{
			return;
		}
		recycler.removeOnScrollListener(scrollListener);
		RecyclerView.Adapter<?> adapter = recycler.getAdapter();
		if (adapter != null)
		{
			try
			{
				adapter.unregisterAdapterDataObserver(dataObserver);
			}
			catch (IllegalStateException ignored)
			{
				// Observer wasn't registered on this adapter (adapter swapped on the recycler
				// between attachTo and detach) — benign because the swap would have already
				// detached the observer from the old adapter.
			}
		}
		recycler = null;
	}

	private void notifyDragState(boolean dragging)
	{
		if (onDragStateChanged != null)
		{
			onDragStateChanged.accept(dragging);
		}
	}

	/**
	 * Touch DOWN handler. Either grabs the visible thumb (immediate drag) or marks the gesture
	 * as awaiting-slop (deferred drag decision) for off-thumb taps in the FastScroller's strip.
	 * Always consumes the event when a thumb is visible — this is what prevents touches falling
	 * through to the RecyclerView underneath, which would interpret the gesture as a swipe and
	 * fling-scroll on release (the auto-scroll symptom).
	 *
	 * @param y touch Y in this view's coordinate space
	 * @return true when the event is consumed (thumb visible); false when no thumb is drawn so
	 *         the tap should fall through to the cells underneath
	 */
	private boolean onDown(float y)
	{
		if (!computeThumbBounds())
		{
			return false;
		}
		initialTouchY = y;
		// Expand the on-thumb detection by thumbTouchHaloPx above and below the visible thumb. The
		// raw 32dp visual thumb is right at the edge of "easy to hit precisely" for a typical
		// fingerpad; halo widens the immediate-grab zone to ~80dp (32dp thumb + 24dp halo above +
		// 24dp halo below) so a touch landing slightly off the visible thumb still counts as
		// on-thumb (direct grab, no jump). Touches outside the halo still go through the
		// awaiting-slop path so a deliberate off-thumb tap intended for the rare track-tap case
		// isn't pre-empted.
		initialTouchOnThumb = y >= thumbRect.top - thumbTouchHaloPx
			&& y <= thumbRect.bottom + thumbTouchHaloPx;
		if (initialTouchOnThumb)
		{
			// Direct grab — start dragging immediately. dragOffsetWithinThumb captures the touch's
			// position relative to the visible thumb top, then gets clamped into the thumb's
			// vertical extent so a touch landing in the halo (above thumb top or below thumb
			// bottom) maps to "grabbed at the closest edge of the thumb" rather than pulling the
			// thumb's edge to the finger (which would make the thumb visually jump).
			dragging = true;
			float thumbHeight = thumbRect.bottom - thumbRect.top;
			dragOffsetWithinThumb = Math.clamp(y - thumbRect.top, 0f, thumbHeight);
			draggedThumbTop = thumbRect.top;
			invalidate();
			notifyDragState(true);
		}
		else
		{
			// Off-thumb tap — defer the drag decision until movement exceeds touch slop. A tap
			// without a drag stays a tap-and-release (no scroll), but a deliberate drag from
			// off-thumb snaps the thumb to the finger on the first past-slop MOVE.
			awaitingDragSlop = true;
		}
		ViewParent parent = getParent();
		if (parent != null)
		{
			parent.requestDisallowInterceptTouchEvent(true);
		}
		return true;
	}

	/**
	 * Touch MOVE handler. Promotes an awaiting-slop gesture to an active drag once movement
	 * exceeds the system touch slop (and snaps the thumb's center to the finger at that
	 * moment); for an already-active drag, updates draggedThumbTop and schedules a throttled
	 * scroll.
	 *
	 * @param y touch Y in this view's coordinate space
	 * @return true when consumed (gesture is active or awaiting); false when there's no active
	 *         gesture at all
	 */
	private boolean onMove(float y)
	{
		if (awaitingDragSlop)
		{
			if (Math.abs(y - initialTouchY) < touchSlopPx)
			{
				// Below slop — keep consuming so the gesture doesn't escape to the RecyclerView,
				// but don't start scrolling yet (might just be a tap with tiny finger jitter).
				return true;
			}
			awaitingDragSlop = false;
			dragging = true;
			float thumbHeight = thumbRect.bottom - thumbRect.top;
			// Snap the thumb's center to the finger on the slop-crossing MOVE so the off-thumb
			// drag feels like "I grabbed the track at this point, now dragging".
			dragOffsetWithinThumb = thumbHeight / 2f;
			notifyDragState(true);
		}
		if (!dragging)
		{
			return false;
		}
		draggedThumbTop = y - dragOffsetWithinThumb;
		invalidate();
		scheduleScroll();
		return true;
	}

	/**
	 * Touch UP / CANCEL handler. Tears down both awaiting-slop and active-drag state, cancels
	 * any pending throttled scroll, HALTS the RecyclerView and OVERRIDES any
	 * pending scroll target with a pin to the current visible position, then lets the parent
	 * re-enable touch interception for the next gesture.
	 *
	 * @return true when the FastScroller had owned the gesture; false otherwise
	 */
	private boolean onUpOrCancel()
	{
		boolean owned = dragging || awaitingDragSlop;
		if (!owned)
		{
			return false;
		}
		boolean wasDragging = dragging;
		awaitingDragSlop = false;
		dragging = false;
		if (scrollScheduled)
		{
			removeCallbacks(scrollRunnable);
			scrollScheduled = false;
			// Deliberately NOT calling scrollRecyclerToDraggedPosition here. A sync scroll on UP
			// would use the latest draggedThumbTop, which captures the user's finger position at
			// the exact moment of release — and that position usually includes an incidental
			// "flick" (finger isn't perfectly stationary as it lifts; even a slight pivot at
			// the fingertip translates to a few px of touch motion). Letting the recycler stay
			// at the last animation frame's scroll target filters out the last 0-16ms of finger
			// motion, which is where the release flick lives.
		}
		if (wasDragging && recycler != null)
		{
			// Hard-stop any in-progress scroll on the recycler. Removing our throttled runnable
			// above prevents OUR future scroll calls, but RecyclerView has its own internal scroll
			// machinery (fling animator, smooth-scroll runner) that can still be mid-animation
			// from earlier scrollToPositionWithOffset calls during the drag. stopScroll() cancels
			// that machinery, ensuring the recycler is fully halted the moment the finger lifts.
			recycler.stopScroll();
			// Override any pending scroll target via scrollToPositionWithOffset(currentFirstVisible,
			// currentOffset). The throttled runnable that fired most recently (Frame N) called
			// scrollToPositionWithOffset(P, 0), which sets LinearLayoutManager's mPendingScrollPosition
			// = P and requests a layout. If UP arrives BEFORE the layout pass runs, the pending
			// position is still queued — stopScroll() cancels scroller/fling animations but does
			// NOT clear mPendingScrollPosition, so the next layout pass commits P after the user has
			// already lifted their finger. From the user's perspective, this is "auto-scroll on
			// release", and it persists no matter how aggressively we throttle MOVE events or filter
			// flick. Re-calling scrollToPositionWithOffset with the CURRENTLY VISIBLE position +
			// offset overwrites mPendingScrollPosition with a pin to where the recycler is RIGHT
			// NOW (which is the last completed layout's position, not the pending flick). The layout
			// pass then commits our pin instead of the pending flick, freezing the recycler at the
			// position the user last saw. Idempotent when no pending is queued (the pin is a no-op
			// re-anchor).
			pinToCurrentScrollPosition();
		}
		ViewParent parent = getParent();
		if (parent != null)
		{
			parent.requestDisallowInterceptTouchEvent(false);
		}
		invalidate();
		if (wasDragging)
		{
			notifyDragState(false);
		}
		return true;
	}

	/**
	 * Re-anchor the RecyclerView at its currently-visible first-child position so any pending
	 * scrollToPositionWithOffset target from the most recent drag-frame runnable is overwritten.
	 * Reads the first attached child via getChildAt(0) (which returns the topmost laid-out view
	 * regardless of whether it's fully visible) and pins to that view's adapter position + top
	 * offset. Called once on touch UP / CANCEL after stopScroll().
	 *
	 * Tolerates a null layout manager, non-LinearLayoutManager, no attached children, and
	 * NO_POSITION children (mid-recycle) — each case is a no-op since there's nothing to pin
	 * against.
	 */
	private void pinToCurrentScrollPosition()
	{
		if (recycler == null)
		{
			return;
		}
		RecyclerView.LayoutManager layoutManager = recycler.getLayoutManager();
		if (!(layoutManager instanceof LinearLayoutManager linearLm))
		{
			return;
		}
		if (recycler.getChildCount() == 0)
		{
			return;
		}
		View firstChild = recycler.getChildAt(0);
		int firstVisible = recycler.getChildAdapterPosition(firstChild);
		if (firstVisible == RecyclerView.NO_POSITION)
		{
			return;
		}
		linearLm.scrollToPositionWithOffset(firstVisible, firstChild.getTop());
	}

	private void scheduleScroll()
	{
		if (!scrollScheduled)
		{
			scrollScheduled = true;
			postOnAnimation(scrollRunnable);
		}
	}

	/**
	 * Translate the current draggedThumbTop into a target item position and call
	 * scrollToPositionWithOffset (or, at ratio ≥ 1.0, recycler.scrollBy(0, Integer.MAX_VALUE)
	 * to delegate the final-row reach to the LayoutManager's own scroll clamp). Posted to the
	 * animation queue from ACTION_MOVE so it runs at most once per frame regardless of MOVE
	 * event rate; without this throttle the layout queue could hold stale anchor targets past
	 * ACTION_UP and commit them as visible "momentum" scrolling after the user lifted their
	 * finger.
	 *
	 * NOT called after dragging ends — onUpOrCancel removes any pending runnable and instead
	 * routes through pinToCurrentScrollPosition, which re-anchors the recycler at its current
	 * visible position. The earlier behaviour of "settle to where the finger left" used the
	 * release-frame draggedThumbTop, which captured the incidental flick motion at finger lift
	 * and overshot the user's intended position; the pin-instead-of-resync replaces that final
	 * sync scroll so the recycler stays at the last animation frame's target (the user's actual
	 * pre-release drag position).
	 */
	private void scrollRecyclerToDraggedPosition()
	{
		if (recycler == null)
		{
			return;
		}
		RecyclerView.Adapter<?> adapter = recycler.getAdapter();
		RecyclerView.LayoutManager layoutManager = recycler.getLayoutManager();
		if (adapter == null || !(layoutManager instanceof LinearLayoutManager linearLm))
		{
			return;
		}
		int itemCount = adapter.getItemCount();
		int firstVisible = linearLm.findFirstVisibleItemPosition();
		int lastVisible = findLastFullyVisibleItemPosition(linearLm);
		if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION)
		{
			return;
		}
		int maxFirstRow = maxFirstVisibleRow(linearLm, itemCount, firstVisible, lastVisible);
		if (maxFirstRow <= 0)
		{
			return;
		}
		int trackHeight = getHeight();
		float thumbHeight = rowProportionalThumbHeight(linearLm, itemCount,
			firstVisible, lastVisible, trackHeight, thumbMinHeightPx);
		float clampedTop = Math.max(0f, Math.min(trackHeight - thumbHeight, draggedThumbTop));
		float scrollableTrack = trackHeight - thumbHeight;
		float ratio = scrollableTrack <= 0 ? 0f : clampedTop / scrollableTrack;
		if (ratio >= 1.0f)
		{
			// Maxed-out drag: delegate the final-row reach to the LayoutManager's own scrollBy,
			// passing Integer.MAX_VALUE so it clamps to its computed max scroll position. Belt
			// and braces over the row-based math below — scrollBy honours the recycler's actual
			// per-row span heights AND padding (clipToPadding=false content extending into the
			// 12dp bottom padding), so the last row reliably lands flush against the content
			// bottom even when our row math is off by a partial-row count.
			recycler.scrollBy(0, Integer.MAX_VALUE);
			return;
		}
		// Map ratio through row index so a folder-list + file-grid mixed layout scrolls
		// proportionally to user-perceived rows instead of compressing folder rows into a tiny
		// fraction of the track travel.
		int targetRow = Math.max(0, Math.min(maxFirstRow, Math.round(ratio * maxFirstRow)));
		int targetPosition = positionForRow(linearLm, targetRow, itemCount);
		// scrollToPositionWithOffset is O(1) on GridLayoutManager — just sets the anchor; the
		// next layout fills the viewport from there without iterating every position in between.
		// Critical for huge folders where scrollBy would force computeVerticalScrollOffset to
		// traverse the SpanSizeLookup chain across thousands of positions per drag frame.
		linearLm.scrollToPositionWithOffset(targetPosition, 0);
	}
}
