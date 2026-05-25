package com.cropcenter.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import com.cropcenter.crop.CropEngine;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.SelectionPoint;
import com.cropcenter.util.RotatedCropClamp;

import java.util.List;

/**
 * Custom view that renders the source image with crop overlay, handles touch gestures for pan/zoom/center-set.
 */
public final class CropEditorView extends View implements TouchGestureHandler.Callback
{
	private static final float TOUCH_THRESHOLD_PX = 30f;       // screen-pixel radius for tap / long-press / brush

	private final HorizonPaintOverlay horizon = new HorizonPaintOverlay();
	private final SelectionHistory history = new SelectionHistory();
	// `new ViewportMath(this)` here captures `this` before the constructor body runs, which Effective Java
	// item 17 names as a leak hazard. Safe in practice — ViewportMath's View-bound constructor wraps the
	// reference in a ViewSize adapter that only ever calls `getWidth()` / `getHeight()` on it, never
	// registering it with a listener / executor / anything that could call back. None of those reads
	// happen during the rest of the View constructor chain.
	private final ViewportMath viewport = new ViewportMath(this);

	private CropState state;
	private EditorRenderer renderer;
	private Runnable onPointsChanged;
	private Runnable onZoomChanged;
	private TouchGestureHandler gestureHandler;

	public CropEditorView(Context context)
	{
		super(context);
		init(context);
	}

	public CropEditorView(Context context, AttributeSet attrs)
	{
		super(context, attrs);
		init(context);
	}

	public CropEditorView(Context context, AttributeSet attrs, int defStyleAttr)
	{
		super(context, attrs, defStyleAttr);
		init(context);
	}

	/**
	 * Check whether the redo stack has at least one selection-point edit available. The toolbar uses
	 * this to enable / disable the Redo button.
	 *
	 * @return true when redo would pop an edit (the redo stack is non-empty)
	 */
	public boolean canRedo()
	{
		return history.canRedo();
	}

	/**
	 * Check whether the undo stack has at least one selection-point edit available. The toolbar uses
	 * this to enable / disable the Undo button.
	 *
	 * @return true when undo would pop an edit (the undo stack is non-empty)
	 */
	public boolean canUndo()
	{
		return history.canUndo();
	}

	/**
	 * Drop both undo and redo stacks. Called by the image-load flow so edits from the previous image don't leak
	 * into the new session.
	 */
	public void clearUndoHistory()
	{
		history.clear();
		notifyPointsChanged();
	}

	/**
	 * Reset zoom to 1 and center the viewport on the image. Called on image load and on double-tap outside Select
	 * mode.
	 */
	public void fitToView()
	{
		viewport.fitToView(state);
		invalidate();
	}

	/**
	 * Convert the screen-space touch threshold to image-space brush radius for horizon paint mode.
	 * Inverse-scales TOUCH_THRESHOLD_PX by (baseScale * zoom) so the brush feels the same finger-
	 * thickness at any zoom level — at 4× zoom, one source pixel maps to 4 screen pixels, so the
	 * image-space brush radius shrinks to a quarter to keep the visible brush at the same size.
	 * Called by AutoRotateBinder before dispatching to HorizonDetector.
	 *
	 * @return brush radius in image-space pixels at the current zoom level
	 */
	public float getHorizonBrushRadius()
	{
		return TOUCH_THRESHOLD_PX / (viewport.getBaseScale() * viewport.getZoom());
	}

	/**
	 * Expose the painted horizon polyline. Returns the live List held by HorizonPaintOverlay — NOT
	 * a defensive copy — because AutoRotateBinder's bg-thread detector needs to read the points
	 * after dispatch and copying multi-hundred-point lists per dispatch is wasted work. The caller
	 * MUST take its own snapshot before crossing to the bg thread; AutoRotateBinder does this via
	 * `new ArrayList<>(...)` immediately on dispatch.
	 *
	 * @return live List of (x, y) float-pairs in image-space coordinates; empty when paint mode
	 *         is off or no points have been painted yet
	 */
	public List<float[]> getHorizonPoints()
	{
		return horizon.getPoints();
	}

	public float getZoom()
	{
		return viewport.getZoom();
	}

	/**
	 * Whether the editor is currently in horizon-paint mode — the transient state between an AutoRotate
	 * tap and the ACTION_UP commit that hands the painted polyline to HorizonDetector. Touch routing
	 * inside onTouchEvent consults this so paint-mode strokes don't accidentally trigger Select-mode
	 * point taps or Move-mode crop drags; the same flag drives the AutoRotateBinder's "Cancel" label
	 * on the Auto button while the user is mid-stroke.
	 *
	 * @return true when the editor is currently in horizon-paint mode (between AutoRotate-tap and
	 *         ACTION_UP commit)
	 */
	public boolean isHorizonMode()
	{
		return horizon.isActive();
	}

	@Override
	public void onDoubleTap()
	{
		// Disable in select mode to prevent accidental zoom while placing points
		if (state != null && state.getEditorMode() == EditorMode.SELECT_FEATURE)
		{
			return;
		}
		fitToView();
	}

	@Override
	public void onLongPress(float screenX, float screenY)
	{
		if (state == null || state.getEditorMode() != EditorMode.SELECT_FEATURE)
		{
			return;
		}
		// Un-rotate: selection points live in un-rotated image coords but the image is drawn rotated. A tap
		// must map through the inverse rotation so the nearest-point test compares like-for-like with what the
		// user visually tapped.
		float[] imagePoint = viewport.screenToImagePixel(screenX, screenY, state);
		float imageX = imagePoint[0];
		float imageY = imagePoint[1];
		float threshold = TOUCH_THRESHOLD_PX / (viewport.getBaseScale() * viewport.getZoom());

		SelectionPoint nearest = null;
		float nearestDist = Float.MAX_VALUE;
		for (SelectionPoint point : state.getSelectionPoints())
		{
			float dist = (float) Math.hypot(point.x() - imageX, point.y() - imageY);
			if (dist < nearestDist)
			{
				nearestDist = dist;
				nearest = point;
			}
		}
		if (nearest != null && nearestDist < threshold)
		{
			pushUndo();
			state.removeSelectionPoint(nearest);
			if (state.getSelectionPoints().isEmpty())
			{
				recenterCropOnImageMidpoint();
			}
			else
			{
				CropEngine.autoComputeFromPoints(state);
			}
			invalidate();
			notifyPointsChanged();
		}
	}

	@Override
	public void onPan(float dx, float dy)
	{
		if (state == null)
		{
			return;
		}
		// Same race window as onTap — a bg-thread state.reset() (Share/View intent's load mid-drag) can
		// null sourceImage between the state-non-null check and the downstream getImageWidth() reads
		// inside dragCropCenter / viewport.panViewport. Snapshot here and bail so the geometry routines
		// don't receive imgW=0 / imgH=0 and trip a Math.clamp(value, +0.5f, -0.5f) IllegalArgumentException.
		if (state.getSourceImage() == null)
		{
			return;
		}
		if (isMovingCrop())
		{
			dragCropCenter(dx, dy);
		}
		else
		{
			// Select mode, or Move mode without a placed center — pan the viewport instead.
			viewport.panViewport(dx, dy, state);
			invalidate();
		}
	}

	/**
	 * Drag-release: applies parity snap to centerX/centerY via snapAxisPreservingTies. Mid-drag stays
	 * continuous (CropEngine.recomputeCrop does not snap per-frame — a continuous snap would flip
	 * cropW even↔odd during rotation sweeps and flicker the crop). Viewport-pan releases skip the
	 * snap. See snapAxisPreservingTies for the formula and tie-preservation rationale.
	 */
	@Override
	public void onPanRelease()
	{
		if (state == null || !isMovingCrop() || !state.hasCenter())
		{
			return;
		}
		int cropW = state.getCropW();
		int cropH = state.getCropH();
		float centerX = state.getCenterX();
		float centerY = state.getCenterY();
		float snappedX = snapAxisPreservingTies(centerX, cropW);
		float snappedY = snapAxisPreservingTies(centerY, cropH);
		if (snappedX == centerX && snappedY == centerY)
		{
			return;
		}
		state.beginBatch();
		try
		{
			state.setCenter(snappedX, snappedY);
			// Update anchor too so a subsequent rotation / AR change starts from the snapped position
			// rather than the pre-snap float — otherwise recomputeCrop's non-select path would re-derive
			// the un-snapped center from the stale anchor.
			state.setAnchor(state.getCenterX(), state.getCenterY());
			invalidate();
		}
		finally
		{
			state.endBatch();
		}
	}

	@Override
	public void onTap(float screenX, float screenY)
	{
		if (state == null)
		{
			return;
		}
		// Snapshot the source bitmap reference once. The volatile sourceImage field can be swapped to null by
		// a bg-thread state.reset() (Share/View intent's load that arrived mid-tap) between this read and the
		// imgW / imgH reads further down. Without the snapshot, a race between `isInsideRotatedImage`'s
		// non-null check and the later getImageWidth() call yields imgW=0; Math.clamp(value, 0.5f, -0.5f) then
		// throws IllegalArgumentException (Java 21 enforces min ≤ max) and propagates uncaught to the touch
		// dispatcher, crashing the UI thread.
		Bitmap sourceImage = state.getSourceImage();
		if (sourceImage == null)
		{
			return;
		}
		int imgW = sourceImage.getWidth();
		int imgH = sourceImage.getHeight();
		// Defensive degenerate-size check: the snapshot guards the reference from being nulled
		// mid-method, but the bitmap it points to can still be RECYCLED by the bg thread that
		// initiated the reset (recycle() then null-out is the canonical teardown order). A
		// recycled Bitmap's getWidth/getHeight is not guaranteed to keep its pre-recycle value
		// across all Android versions — observed returning 0 on some devices. Bail before the
		// snappedX/snappedY Math.clamp below would receive max < min (0.5f, imgW - 0.5f) and
		// crash the UI thread with IllegalArgumentException.
		if (imgW < 1 || imgH < 1)
		{
			return;
		}

		// Un-rotate: same reasoning as onLongPress — selection points are stored in un-rotated image coords, so
		// the tap location must be converted through the inverse rotation before comparing to existing points
		// or adding a new one.
		float[] imagePoint = viewport.screenToImagePixel(screenX, screenY, state);
		float imageX = imagePoint[0];
		float imageY = imagePoint[1];

		// Tap does nothing in Move mode — use drag to reposition crop.
		if (state.getEditorMode() == EditorMode.SELECT_FEATURE)
		{
			float threshold = TOUCH_THRESHOLD_PX / (viewport.getBaseScale() * viewport.getZoom());
			// Snapshot the volatile-swapped selection list once. A bg-thread reset between the .size()
			// call and a per-iteration .get() returns a different (smaller) list, throwing IOOBE on the
			// get. EditorRenderer.draw uses the same snapshot pattern.
			List<SelectionPoint> selectionSnapshot = state.getSelectionPoints();
			for (int i = 0; i < selectionSnapshot.size(); i++)
			{
				SelectionPoint point = selectionSnapshot.get(i);
				if (Math.hypot(point.x() - imageX, point.y() - imageY) < threshold)
				{
					pushUndo();
					// Remove by EQUALITY against the live list rather than by snapshot-index. The
					// snapshot's iteration only protects the .size()/.get() loop; the live volatile
					// selectionPoints field could have been swapped (bg-thread reset) between the
					// matching iteration and the remove call. removeSelectionPointAt(i) would IOOBE
					// on the fresh empty list; removeSelectionPoint(point) returns false cleanly
					// (no-op) when the live list no longer contains the captured reference.
					state.removeSelectionPoint(point);
					if (state.getSelectionPoints().isEmpty())
					{
						recenterCropOnImageMidpoint();
					}
					else
					{
						CropEngine.autoComputeFromPoints(state);
					}
					invalidate();
					notifyPointsChanged();
					return;
				}
			}
			// Only add point if inside the visible (rotated) image content
			if (!isInsideRotatedImage(screenX, screenY))
			{
				return;
			}
			pushUndo();
			// Snap the stored point to the tapped pixel's centre (image X in [P, P+1) → P + 0.5).
			// GridRenderer.linePos draws the grid's middle line at the crop centre regardless of cropW
			// parity, so placing the point on a half-integer and letting CropEngine carry that through to
			// cropCenter lands the middle grid line over the marker. Clamp to the last valid pixel centre:
			// isInsideRotatedImage accepts coords equal to imgW / imgH (inclusive bounds), but floor(imgW)
			// + 0.5 = imgW + 0.5 which is outside the source. Rotated-tap float precision can also push
			// marginal taps just past the edge; the clamp absorbs both.
			float snappedX = Math.clamp((float) Math.floor(imageX) + 0.5f, 0.5f, imgW - 0.5f);
			float snappedY = Math.clamp((float) Math.floor(imageY) + 0.5f, 0.5f, imgH - 0.5f);
			state.addSelectionPoint(new SelectionPoint(snappedX, snappedY));
			CropEngine.autoComputeFromPoints(state);
			invalidate();
			notifyPointsChanged();
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent event)
	{
		if (horizon.isActive())
		{
			float screenX = event.getX();
			float screenY = event.getY();
			// Use the rotation-aware mapper: painted points are consumed by
			// HorizonDetector.detectFromPaintedRegion, which operates on the UN-rotated source bitmap.
			// Without un-rotation here, any rotation at paint time produces garbage horizon angles.
			switch (event.getActionMasked())
			{
				case MotionEvent.ACTION_DOWN ->
				{
					float[] imagePoint = viewport.screenToImagePixel(screenX, screenY, state);
					horizon.begin(screenX, screenY, imagePoint);
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
					float[] imagePoint = viewport.screenToImagePixel(screenX, screenY, state);
					horizon.extend(screenX, screenY, imagePoint);
					invalidate();
				}
				case MotionEvent.ACTION_UP ->
				{
					// Genuine stroke completion — feed the final point and trigger detection.
					horizon.end(viewport.screenToImagePixel(screenX, screenY, state));
					invalidate();
					performClick(); // a11y hook
				}
				case MotionEvent.ACTION_CANCEL ->
				{
					// OS / parent gesture interruption — see HorizonPaintOverlay.cancelStroke.
					horizon.cancelStroke();
					invalidate();
				}
			}
			return true;
		}
		return gestureHandler.onTouchEvent(event);
	}

	@Override
	public void onZoom(float scaleFactor, float focusX, float focusY)
	{
		viewport.zoomAt(scaleFactor, focusX, focusY, state);
		invalidate();
		if (onZoomChanged != null)
		{
			onZoomChanged.run();
		}
	}

	@Override
	public boolean performClick()
	{
		return super.performClick();
	}

	/**
	 * Re-center crop and rotation anchor on the image midpoint, then recompute the crop dimensions for
	 * the current aspect ratio + lock mode. Used by the four "selection just emptied" call sites
	 * (onTap / onLongPress deletion of last point, undo/redo restoring an empty snapshot, Clear Points
	 * button) — once the user no longer has any selection driving the framing, the natural default is
	 * an image-centered crop at max size for the current AR.
	 *
	 * NOT a generic "reset" affordance. The four callers all run on an already-empty selection list, so
	 * recomputeCrop's findCropCenter falls into the "no selection → use anchor" branch and the anchor
	 * we just set to the image midpoint genuinely takes effect. Calling this with selection points still
	 * present in Select mode would let the points override the anchor (rotatedSelectionMidpoint wins
	 * inside findCropCenter), making the operation a silent no-op with confusing semantics — that's why
	 * the prior long-press AR-spinner "Crop reset" affordance was removed; switching modes already
	 * provides the natural recompute path users want.
	 */
	public void recenterCropOnImageMidpoint()
	{
		// Snapshot the volatile sourceImage reference once. state.getImageWidth() and getImageHeight()
		// each independently re-dereference the volatile sourceImage field (CropState returns 0 when
		// null) — a bg-thread reset() between the two reads would silently produce (imageMidX, 0) or
		// (0, imageMidY) and write it through setCenterUnclamped. ToolbarBinder.onClearPointsClick
		// reaches this method without busy-gate protection, so the race window IS reachable. The tap
		// / pan paths in this same View already use the snapshot-once pattern.
		Bitmap source = state.getSourceImage();
		if (source == null)
		{
			return;
		}
		float imageMidX = source.getWidth() / 2f;
		float imageMidY = source.getHeight() / 2f;
		state.markCropSizeDirty();
		state.setCenterUnclamped(imageMidX, imageMidY);
		// Reset the rotation anchor too — the user's "intent" is now the image center, not whatever point was
		// selected before (which may have been far away).
		state.setAnchor(imageMidX, imageMidY);
		CropEngine.recomputeCrop(state);
	}

	/**
	 * Re-apply the most recently undone selection-point edit. No-op when the redo stack is empty.
	 */
	public void redo()
	{
		List<SelectionPoint> snapshot = history.redo(state.getSelectionPoints());
		if (snapshot == null)
		{
			return;
		}
		restorePoints(snapshot);
		notifyPointsChanged();
	}

	/**
	 * Enter or exit horizon paint mode. Entering installs the detection callback that fires on ACTION_UP
	 * once the user finishes painting the horizon line; exiting (on=false) discards any in-progress stroke
	 * without invoking the callback. Round-15 added two exit-path callers — AutoRotateBinder's re-tap
	 * branch and AutoRotateBinder.cancelHorizonPaintMode (called from MainActivity.installImageOnUi when a
	 * new image loads) — so this method is now bidirectional, not just an entry.
	 *
	 * @param on       true to enter paint mode, false to exit
	 * @param onDrawn  callback invoked on ACTION_UP when on=true; ignored / may be null when on=false
	 */
	public void setHorizonMode(boolean on, Runnable onDrawn)
	{
		horizon.setActive(on, onDrawn);
		invalidate();
	}

	/**
	 * Enter paint mode with both completion AND cancellation callbacks. `onCancel` fires when the OS or a
	 * parent view interrupts the stroke (ACTION_CANCEL) — host uses it to reset external UI (e.g.
	 * AutoRotateBinder's button label) that's only synced via the entry path. Earlier code held paint
	 * mode active across CANCEL "so the user could try again", which stranded touches in the horizon
	 * handler when the cancel happened before any stroke began; the new contract exits paint mode and
	 * lets the host clean up.
	 *
	 * @param on       true to enter paint mode; pass false to use the simpler two-arg setHorizonMode
	 * @param onDrawn  callback invoked on ACTION_UP when on=true
	 * @param onCancel callback invoked on ACTION_CANCEL when on=true; one-shot (cleared after fire)
	 */
	public void setHorizonMode(boolean on, Runnable onDrawn, Runnable onCancel)
	{
		horizon.setActive(on, onDrawn, onCancel);
		invalidate();
	}

	/**
	 * Subscribe to "selection points changed" events — fired whenever the user adds, removes, or restores a
	 * selection point. The host uses this to refresh undo / redo / clear button enablement.
	 */
	public void setOnPointsChangedListener(Runnable listener)
	{
		this.onPointsChanged = listener;
	}

	/**
	 * Subscribe to zoom-changed events — fired on every pinch-zoom gesture step. The host uses this to update the
	 * zoom badge in the info bar.
	 */
	public void setOnZoomChangedListener(Runnable listener)
	{
		this.onZoomChanged = listener;
	}

	/**
	 * Hand the view the CropState to render. Triggers an initial fit-to-view and redraw. Call again after loading a
	 * new image.
	 */
	public void setState(CropState state)
	{
		this.state = state;
		fitToView();
		invalidate();
	}

	/**
	 * Undo the most recent selection-point edit. No-op when the undo stack is empty.
	 */
	public void undo()
	{
		List<SelectionPoint> snapshot = history.undo(state.getSelectionPoints());
		if (snapshot == null)
		{
			return;
		}
		restorePoints(snapshot);
		notifyPointsChanged();
	}

	@Override
	protected void onDraw(Canvas canvas)
	{
		super.onDraw(canvas);
		renderer.draw(canvas, state, horizon);
	}

	@Override
	protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight)
	{
		super.onSizeChanged(width, height, oldWidth, oldHeight);
		fitToView();
	}

	/**
	 * True when the locked axis moved more than 0.5 px between (preDragCenterX, preDragCenterY) and the
	 * post-clamp (postCenterX, postCenterY) — i.e. the joint clamp in setCenter pushed the locked axis.
	 * Used to reject moves on a locked axis and restore via recomputeCrop.
	 *
	 * Package-private static so CropEditorViewCrossAxisDriftedTest can pin the lock-axis selection AND the
	 * 0.5px strict-greater threshold without a Context-bound View. CenterMode.BOTH / LOCKED / null all fall
	 * through to false (only HORIZONTAL and VERTICAL are checked by name).
	 *
	 * @param lock           the current lock mode; only HORIZONTAL and VERTICAL trigger a drift check
	 * @param preDragCenterX X-center captured before the drag began
	 * @param preDragCenterY Y-center captured before the drag began
	 * @param postCenterX    X-center after setCenter applied the joint clamp
	 * @param postCenterY    Y-center after setCenter applied the joint clamp
	 * @return true when the LOCKED axis moved more than 0.5 px from its pre-drag position
	 */
	static boolean crossAxisDrifted(CenterMode lock, float preDragCenterX, float preDragCenterY,
		float postCenterX, float postCenterY)
	{
		if (lock == CenterMode.HORIZONTAL)
		{
			return Math.abs(postCenterY - preDragCenterY) > 0.5f;
		}
		if (lock == CenterMode.VERTICAL)
		{
			return Math.abs(postCenterX - preDragCenterX) > 0.5f;
		}
		return false;
	}

	/**
	 * Snap a centerX/Y to the parity-valid pixel grid: even dim → integer center, odd dim → half-integer.
	 * Returns the input unchanged when both candidate targets are equidistant — a half-up bias would
	 * shift the crop 0.5 px on a no-op MOVE pan after a SELECT-mode tap had placed the selection at the
	 * pixel half-integer where the grid central line passes through the marker square's center.
	 * cropImageX may then be fractional; BitmapUtils.drawCropped bilinear-samples through it (the
	 * integer-cropImageX property is for crispness, not correctness).
	 *
	 * Package-private so snap math can be unit-tested directly.
	 *
	 * @param center current axis-center value (post-drag)
	 * @param dim    crop dim along that axis (cropW for X, cropH for Y); parity drives the target type
	 * @return the nearest parity-valid snap target, OR `center` itself when both candidates are equidistant
	 */
	static float snapAxisPreservingTies(float center, int dim)
	{
		float target = ((dim & 1) == 0)
			? Math.round(center)
			: (float) Math.floor(center) + 0.5f;
		// Equidistant tie-case: the snap shift would be exactly 0.5 px in a fixed direction (half-up bias
		// from Math.round, or floor+0.5 jumping a half-step from an integer input). Use 0.499f as the
		// epsilon-tolerant threshold so float precision noise around 0.5 still resolves to "tied" rather
		// than to a sub-half snap that mimics the half-up bias we're trying to avoid.
		if (Math.abs(center - target) > 0.499f)
		{
			return center;
		}
		return target;
	}

	/**
	 * Move the crop center by the current gesture's delta, respecting the lock mode. The anchor accumulates
	 * unclamped intent across events; state.centerX is what setCenter's rotation clamp produced. Reading newCenter
	 * from the anchor (not centerX) lets sub-pixel motion accumulate across events — a slow drag at high zoom would
	 * otherwise make no progress because each event would read back the just-clamped centerX.
	 *
	 * Wrapped in a beginBatch / endBatch pair so the two setCenter calls (ours below plus the one inside
	 * CropEngine.recomputeCrop) coalesce into a single listener fire — otherwise each pan event triggers two full
	 * applyStateToUi cycles.
	 */
	private void dragCropCenter(float dx, float dy)
	{
		state.beginBatch();
		try
		{
			float scale = viewport.getBaseScale() * viewport.getZoom();
			CenterMode lock = state.getCenterMode();
			resyncAnchorIfStale();

			float newCenterX = state.getAnchorX();
			float newCenterY = state.getAnchorY();
			// Cache the pre-drag CLAMPED center for the cross-axis drift test below. Comparing to the
			// anchor would compare intent (pre-clamp) to intent (pre-clamp), which can't detect the
			// rotation clamp moving the locked axis.
			float preDragCenterX = state.getCenterX();
			float preDragCenterY = state.getCenterY();

			if (lock == CenterMode.HORIZONTAL)
			{
				newCenterX += dx / scale;
			}
			else if (lock == CenterMode.VERTICAL)
			{
				newCenterY += dy / scale;
			}
			else
			{
				newCenterX += dx / scale;
				newCenterY += dy / scale;
			}

			// Lock-aware pre-clamp: under per-axis lock, pre-clamp the FREE axis to a value valid
			// at the LOCKED-axis pin BEFORE setCenter so the joint-clamp inside setCenter doesn't
			// pull the locked axis. Without this pre-clamp, dragging the free axis to the rotated
			// AABB's extreme causes clampRotated's binary search to pull the locked axis toward
			// imageMid (the only position where the rotated corners fit at the extreme free-axis
			// value), visibly breaking the per-axis lock (e.g. VERTICAL lock + drag Y to bottom
			// under rotation caused X to snap to imageMidX). HORIZONTAL → free X (varyX=true);
			// VERTICAL → free Y (varyX=false). This pre-clamp ALSO replaces an earlier
			// selection-midpoint override that snapped the locked axis to selectionMid on every
			// drag frame — that override was added to work around clampRotated's joint pull (the
			// "frozen drag" bug it cited resolved because the override's `overrideActive` flag
			// bypassed the cross-axis-drift rejection); with the pre-clamp here, the joint pull
			// no longer happens and the override's drift-bypass is no longer needed. Dropping the
			// override means dragging the crop in MOVE mode with selection points present no
			// longer auto-recenters X onto the selection midpoint every frame — the user
			// controls the locked axis position via their pre-drag center, and a one-shot
			// `recenterOnSelection` on H/V toggle (ToolbarBinder.onLockButtonClick) still
			// establishes the initial alignment to selection on entry. Bitmap dims read off the
			// snapshot below — same race-avoidance pattern as setCenter.
			Bitmap source = state.getSourceImage();
			if (source != null && state.getCropW() > 0 && state.getCropH() > 0
				&& (lock == CenterMode.HORIZONTAL || lock == CenterMode.VERTICAL))
			{
				boolean varyX = (lock == CenterMode.HORIZONTAL);
				float[] preClamped = RotatedCropClamp.clampSingleAxis(
					newCenterX, newCenterY,
					state.getCropW(), state.getCropH(),
					state.getRotationDegrees(),
					source.getWidth(), source.getHeight(), varyX);
				newCenterX = preClamped[0];
				newCenterY = preClamped[1];
			}

			// setCenter clamps both axes jointly (binary search under rotation). On a locked axis we only
			// want motion on the unlocked axis — reject moves that would drift the locked axis more than
			// 0.5 px from its pre-drag position. With the pre-clamp above, the locked axis is already at
			// its pre-drag value when setCenter is called, so the joint clamp doesn't pull it and the
			// drift check passes — anchor advances normally.
			state.setCropSizeDirty(false);
			state.setCenter(newCenterX, newCenterY);
			boolean shouldAdvanceAnchor = !crossAxisDrifted(lock, preDragCenterX, preDragCenterY,
				state.getCenterX(), state.getCenterY());
			if (shouldAdvanceAnchor)
			{
				// Advance the anchor to the clamped (still fractional) drag position so the next event
				// continues accumulating from here.
				state.setAnchor(state.getCenterX(), state.getCenterY());
			}
			// Recompute crop size + rotation fit. When rejected, the anchor is unchanged — recomputeCrop
			// re-derives centerX / Y from the unchanged anchor, restoring the pre-drag position on the
			// locked axis.
			CropEngine.recomputeCrop(state);
			invalidate();
		}
		finally
		{
			state.endBatch();
		}
	}

	private void init(Context context)
	{
		gestureHandler = new TouchGestureHandler(context, this);
		renderer = new EditorRenderer(context, this, viewport);
	}

	/**
	 * Check if a SCREEN point is inside the visible (rotated) image content. The image is drawn rotated by
	 * state.getRotationDegrees() around its center.
	 */
	private boolean isInsideRotatedImage(float screenX, float screenY)
	{
		if (state == null || state.getSourceImage() == null)
		{
			return false;
		}
		int imgW = state.getImageWidth();
		int imgH = state.getImageHeight();
		float[] imagePoint = viewport.screenToImagePixel(screenX, screenY, state);
		return imagePoint[0] >= 0 && imagePoint[0] <= imgW && imagePoint[1] >= 0 && imagePoint[1] <= imgH;
	}

	/**
	 * True when a pan gesture should move the crop box (Move mode + center placed + not in Pan-the-viewport lock
	 * mode). Otherwise the pan gesture moves the viewport.
	 */
	private boolean isMovingCrop()
	{
		return state.getEditorMode() == EditorMode.MOVE && state.hasCenter()
			&& state.getCenterMode() != CenterMode.LOCKED;
	}

	private void notifyPointsChanged()
	{
		if (onPointsChanged != null)
		{
			onPointsChanged.run();
		}
	}

	private void pushUndo()
	{
		history.push(state.getSelectionPoints());
	}

	private void restorePoints(List<SelectionPoint> snapshot)
	{
		// SelectionPoint is an immutable record, so we can share the instances directly rather than deep-copy
		// them.
		state.replaceSelectionPoints(snapshot);
		if (snapshot.isEmpty())
		{
			// No points left — re-center the crop on the image midpoint at the current AR.
			recenterCropOnImageMidpoint();
		}
		else
		{
			CropEngine.autoComputeFromPoints(state);
		}
		invalidate();
	}

	/**
	 * Resync the drag anchor back to centerX/centerY when they've diverged by more than a pixel. A gap that large
	 * means the anchor went stale: rotation / AR shrunk the crop and setCenter's rotation clamp pulled centerX
	 * inward while the anchor stayed put, or the user switched Select → Move without a selection and the anchor
	 * still reflects an old intent. Without the resync the first drag event would be absorbed by the clamp and
	 * produce no visible motion.
	 */
	private void resyncAnchorIfStale()
	{
		if (Math.abs(state.getAnchorX() - state.getCenterX()) > 1f
			|| Math.abs(state.getAnchorY() - state.getCenterY()) > 1f)
		{
			state.setAnchor(state.getCenterX(), state.getCenterY());
		}
	}
}
