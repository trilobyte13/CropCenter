package com.cropcenter.view;

import android.content.Context;
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

import java.util.List;

/**
 * Custom view that renders the source image with crop overlay, handles touch gestures for
 * pan/zoom/center-set.
 */
public class CropEditorView extends View implements TouchGestureHandler.Callback
{
	private static final float TOUCH_THRESHOLD_PX = 30f;       // screen-pixel radius for tap / long-press / brush

	private final HorizonPaintOverlay horizon = new HorizonPaintOverlay();
	private final SelectionHistory history = new SelectionHistory();
	private final ViewportMath viewport = new ViewportMath(this);

	private CropState state;
	private EditorRenderer renderer;
	private Runnable onPointsChanged;
	private Runnable onZoomChanged;
	private TouchGestureHandler gestureHandler;

	/**
	 * Standard code-instantiated constructor.
	 */
	public CropEditorView(Context context)
	{
		super(context);
		init(context);
	}

	/**
	 * Inflation-time constructor — called when the view is instantiated from XML.
	 */
	public CropEditorView(Context context, AttributeSet attrs)
	{
		super(context, attrs);
		init(context);
	}

	/**
	 * Inflation-time constructor with custom default style attribute.
	 */
	public CropEditorView(Context context, AttributeSet attrs, int defStyleAttr)
	{
		super(context, attrs, defStyleAttr);
		init(context);
	}

	/**
	 * True when there is at least one selection-point edit on the redo stack — the
	 * toolbar uses this to enable / disable the Redo button.
	 */
	public boolean canRedo()
	{
		return history.canRedo();
	}

	/**
	 * True when there is at least one selection-point edit on the undo stack.
	 */
	public boolean canUndo()
	{
		return history.canUndo();
	}

	/**
	 * Drop both undo and redo stacks. Called by the image-load flow so edits from the
	 * previous image don't leak into the new session.
	 */
	public void clearUndoHistory()
	{
		history.clear();
		notifyPointsChanged();
	}

	/**
	 * Reset zoom to 1 and center the viewport on the image. Called on image load and
	 * on double-tap outside Select mode.
	 */
	public void fitToView()
	{
		viewport.fitToView(state);
		invalidate();
	}

	/**
	 * Brush radius in image pixels for the painted region.
	 */
	public float getHorizonBrushRadius()
	{
		return TOUCH_THRESHOLD_PX / (viewport.getBaseScale() * viewport.getZoom());
	}

	/**
	 * Get the painted region points in image coordinates.
	 */
	public List<float[]> getHorizonPoints()
	{
		return horizon.getPoints();
	}

	/**
	 * Current zoom factor on top of fit-to-view's baseScale. 1 = fit-to-view, capped at 256.
	 */
	public float getZoom()
	{
		return viewport.getZoom();
	}

	/**
	 * True while the user is painting a horizon region for auto-rotate detection.
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
		// Un-rotate: selection points live in un-rotated image coords but the image is drawn
		// rotated. A tap must map through the inverse rotation so the nearest-point test
		// compares like-for-like with what the user visually tapped.
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
				resetCropToFullImage();
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
	 * Drag-release handler: applies parity snap to centerX/centerY so the crop lands on a
	 * pixel-aligned position once the finger lifts. Mid-drag keeps centerX/Y continuous
	 * (CropEngine.recomputeCrop intentionally does NOT snap per-frame — a continuous snap
	 * makes cropW flip even↔odd during rotation sweeps and the crop flickers). A one-shot
	 * snap on release has no such flicker problem: no sweep is in progress.
	 *
	 * Snap formula matches what getCropImgXFloat / exporter integer floor expect:
	 *   cropW even → centerX must be integer   → round to nearest int
	 *   cropW odd  → centerX must end in .5    → floor + 0.5
	 * Both cases produce an integer cropImgX = centerX − cropW/2, so the grid's outer
	 * borders land exactly on pixel boundaries and the rule-of-thirds lines hit pixel
	 * centers when cropW is divisible by 3. Viewport-pan releases (not moving the crop)
	 * skip the snap entirely.
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
		float snappedX = ((cropW & 1) == 0)
			? Math.round(centerX)
			: (float) Math.floor(centerX) + 0.5f;
		float snappedY = ((cropH & 1) == 0)
			? Math.round(centerY)
			: (float) Math.floor(centerY) + 0.5f;
		if (snappedX == centerX && snappedY == centerY)
		{
			return;
		}
		state.beginBatch();
		try
		{
			state.setCenter(snappedX, snappedY);
			// Update anchor too so a subsequent rotation / AR change starts from the
			// snapped position rather than the pre-snap float — otherwise recomputeCrop's
			// non-select path would re-derive the un-snapped center from the stale anchor.
			state.setAnchor(state.getCenterX(), state.getCenterY());
			invalidate();
		}
		finally
		{
			state.endBatch();
		}
	}

	/**
	 * True when a pan gesture should move the crop box (Move mode + center placed +
	 * not in Pan-the-viewport lock mode). Otherwise the pan gesture moves the viewport.
	 */
	private boolean isMovingCrop()
	{
		return state.getEditorMode() == EditorMode.MOVE
			&& state.hasCenter()
			&& state.getCenterMode() != CenterMode.LOCKED;
	}

	/**
	 * Move the crop center by the current gesture's delta, respecting the lock mode.
	 * The anchor accumulates unclamped intent across events; state.centerX is what
	 * setCenter's rotation clamp produced. Reading newCenter from the anchor (not
	 * centerX) lets sub-pixel motion accumulate across events — a slow drag at high
	 * zoom would otherwise make no progress because each event would read back the
	 * just-clamped centerX.
	 *
	 * Wrapped in a beginBatch / endBatch pair so the two setCenter calls (ours below
	 * plus the one inside CropEngine.recomputeCrop) coalesce into a single listener
	 * fire — otherwise each pan event triggers two full applyStateToUi cycles.
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
			// Cache the pre-drag CLAMPED center for the cross-axis drift test below.
			// Comparing to the anchor would compare intent (pre-clamp) to intent
			// (pre-clamp), which can't detect the rotation clamp moving the locked axis.
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

			// setCenter clamps both axes jointly (binary search under rotation). On a
			// locked axis we only want motion on the unlocked axis — reject moves that
			// would drift the locked axis more than 0.5 px from its pre-drag position.
			state.setCropSizeDirty(false);
			state.setCenter(newCenterX, newCenterY);
			if (!crossAxisDrifted(lock, preDragCenterX, preDragCenterY))
			{
				// Advance the anchor to the clamped (still fractional) drag position so
				// the next event continues accumulating from here.
				state.setAnchor(state.getCenterX(), state.getCenterY());
			}
			// Recompute crop size + rotation fit. When rejected, the anchor is
			// unchanged — recomputeCrop re-derives centerX / Y from the unchanged
			// anchor, restoring the pre-drag position on the locked axis.
			CropEngine.recomputeCrop(state);
			invalidate();
		}
		finally
		{
			state.endBatch();
		}
	}

	/**
	 * Resync the drag anchor back to centerX/centerY when they've diverged by more than
	 * a pixel. A gap that large means the anchor went stale: rotation / AR shrunk the
	 * crop and setCenter's rotation clamp pulled centerX inward while the anchor stayed
	 * put, or the user switched Select → Move without a selection and the anchor still
	 * reflects an old intent. Without the resync the first drag event would be absorbed
	 * by the clamp and produce no visible motion.
	 */
	private void resyncAnchorIfStale()
	{
		if (Math.abs(state.getAnchorX() - state.getCenterX()) > 1f
			|| Math.abs(state.getAnchorY() - state.getCenterY()) > 1f)
		{
			state.setAnchor(state.getCenterX(), state.getCenterY());
		}
	}

	/**
	 * True when the locked axis moved more than 0.5 px between preDrag* and the current
	 * state.centerX/centerY — i.e. the joint clamp in setCenter pushed the locked axis.
	 * Used to reject moves on a locked axis and restore via recomputeCrop.
	 */
	private boolean crossAxisDrifted(CenterMode lock, float preDragCenterX, float preDragCenterY)
	{
		if (lock == CenterMode.HORIZONTAL)
		{
			return Math.abs(state.getCenterY() - preDragCenterY) > 0.5f;
		}
		if (lock == CenterMode.VERTICAL)
		{
			return Math.abs(state.getCenterX() - preDragCenterX) > 0.5f;
		}
		return false;
	}

	@Override
	public void onTap(float screenX, float screenY)
	{
		if (state == null)
		{
			return;
		}
		// Un-rotate: same reasoning as onLongPress — selection points are stored in un-rotated
		// image coords, so the tap location must be converted through the inverse rotation
		// before comparing to existing points or adding a new one.
		float[] imagePoint = viewport.screenToImagePixel(screenX, screenY, state);
		float imageX = imagePoint[0];
		float imageY = imagePoint[1];

		// Tap does nothing in Move mode — use drag to reposition crop.
		if (state.getEditorMode() == EditorMode.SELECT_FEATURE)
		{
			// Check if tapping on existing point → remove it
			float threshold = TOUCH_THRESHOLD_PX / (viewport.getBaseScale() * viewport.getZoom());
			for (int i = 0; i < state.getSelectionPoints().size(); i++)
			{
				SelectionPoint point = state.getSelectionPoints().get(i);
				if (Math.hypot(point.x() - imageX, point.y() - imageY) < threshold)
				{
					pushUndo();
					state.removeSelectionPointAt(i);
					if (state.getSelectionPoints().isEmpty())
					{
						resetCropToFullImage();
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
			// GridRenderer.linePos draws the grid's middle line at the crop centre regardless
			// of cropW parity, so placing the point on a half-integer and letting CropEngine
			// carry that through to cropCenter lands the middle grid line over the marker.
			// Clamp to the last valid pixel centre: isInsideRotatedImage accepts coords equal
			// to imgW / imgH (inclusive bounds), but floor(imgW) + 0.5 = imgW + 0.5 which is
			// outside the source. Rotated-tap float precision can also push marginal taps just
			// past the edge; the clamp absorbs both.
			int imgW = state.getImageWidth();
			int imgH = state.getImageHeight();
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
			// HorizonDetector.detectFromPaintedRegion, which operates on the UN-rotated source
			// bitmap. Without un-rotation here, any rotation at paint time produces garbage
			// horizon angles.
			switch (event.getActionMasked())
			{
				case MotionEvent.ACTION_DOWN ->
				{
					float[] imagePoint = viewport.screenToImagePixel(screenX, screenY, state);
					horizon.begin(screenX, screenY, imagePoint);
					// getParent() is null between detach and re-attach (config change
					// mid-gesture); skip the request rather than NPE.
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
				case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
				{
					horizon.end(viewport.screenToImagePixel(screenX, screenY, state));
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

	/**
	 * Re-apply the most recently undone selection-point edit. No-op when the redo
	 * stack is empty.
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
	 * Reset crop to full image centered with current AR.
	 */
	public void resetCropToFullImage()
	{
		float imageMidX = state.getImageWidth() / 2f;
		float imageMidY = state.getImageHeight() / 2f;
		state.markCropSizeDirty();
		state.setCenterUnclamped(imageMidX, imageMidY);
		// Reset the rotation anchor too — the user's "intent" is now the image center,
		// not whatever point was selected before (which may have been far away).
		state.setAnchor(imageMidX, imageMidY);
		CropEngine.recomputeCrop(state);
	}

	/**
	 * Enter horizon paint mode. User paints over the horizon region.
	 */
	public void setHorizonMode(boolean on, Runnable onDrawn)
	{
		horizon.setActive(on, onDrawn);
		invalidate();
	}

	/**
	 * Subscribe to "selection points changed" events — fired whenever the user adds,
	 * removes, or restores a selection point. The host uses this to refresh
	 * undo / redo / clear button enablement.
	 */
	public void setOnPointsChangedListener(Runnable r)
	{
		this.onPointsChanged = r;
	}

	/**
	 * Subscribe to zoom-changed events — fired on every pinch-zoom gesture step. The
	 * host uses this to update the zoom badge in the info bar.
	 */
	public void setOnZoomChangedListener(Runnable r)
	{
		this.onZoomChanged = r;
	}

	/**
	 * Hand the view the CropState to render. Triggers an initial fit-to-view and
	 * redraw. Call again after loading a new image.
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

	private void init(Context context)
	{
		gestureHandler = new TouchGestureHandler(context, this);
		renderer = new EditorRenderer(context, this, viewport);
	}

	/**
	 * Check if a SCREEN point is inside the visible (rotated) image content.
	 * The image is drawn rotated by state.getRotationDegrees() around its center.
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
		// SelectionPoint is an immutable record, so we can share the instances directly rather
		// than deep-copy them.
		state.replaceSelectionPoints(snapshot);
		if (snapshot.isEmpty())
		{
			// No points left — clear crop center (reverts to full image)
			resetCropToFullImage();
		}
		else
		{
			CropEngine.autoComputeFromPoints(state);
		}
		invalidate();
	}

}
