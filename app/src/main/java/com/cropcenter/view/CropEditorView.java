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

	public boolean canRedo()
	{
		return history.canRedo();
	}

	public boolean canUndo()
	{
		return history.canUndo();
	}

	public void clearUndoHistory()
	{
		history.clear();
		notifyPointsChanged();
	}

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

	public float getZoom()
	{
		return viewport.getZoom();
	}

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
		float[] ip = viewport.screenToImagePixel(screenX, screenY, state);
		float ix = ip[0];
		float iy = ip[1];
		float threshold = TOUCH_THRESHOLD_PX / (viewport.getBaseScale() * viewport.getZoom());

		SelectionPoint nearest = null;
		float nearestDist = Float.MAX_VALUE;
		for (SelectionPoint point : state.getSelectionPoints())
		{
			float dist = (float) Math.hypot(point.x() - ix, point.y() - iy);
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
		EditorMode mode = state.getEditorMode();

		if (mode == EditorMode.MOVE && state.hasCenter()
			&& state.getCenterMode() != CenterMode.LOCKED)
		{
			// Drag to move center — respect lock direction. The anchor is a fractional
			// drag accumulator that holds the user's "intent" position; centerX is what
			// recomputeCrop parity-snaps to the pixel grid for display. Reading newCx/newCy
			// from the anchor (not centerX) lets sub-pixel motion accumulate across events —
			// otherwise a slow drag at high zoom would make no progress because each event
			// would read back the just-snapped centerX.
			//
			// Resync the anchor to centerX when the gap exceeds 1 pixel. Parity snapping
			// alone moves them apart by at most 0.5 px, so a larger gap means the anchor
			// went stale: rotation or AR change shrunk the crop and setCenter's clamp pulled
			// centerX inward while the anchor stayed put; or the user switched Select→Move
			// without a selection and the anchor reflects an old intent. Without this
			// resync the first drag event absorbs into the clamp and produces no motion.
			float scale = viewport.getBaseScale() * viewport.getZoom();
			CenterMode lock = state.getCenterMode();
			if (Math.abs(state.getAnchorX() - state.getCenterX()) > 1f
				|| Math.abs(state.getAnchorY() - state.getCenterY()) > 1f)
			{
				state.setAnchor(state.getCenterX(), state.getCenterY());
			}

			float newCx = state.getAnchorX();
			float newCy = state.getAnchorY();
			// Cache the pre-drag SNAPPED center for the cross-axis drift test below.
			// Comparing to the anchor would let up to 0.5 px of parity offset leak past
			// the 0.5-px reject threshold, which on a rotated image can accumulate into
			// visible drift along a "locked" axis across many events.
			float preCx = state.getCenterX();
			float preCy = state.getCenterY();

			if (lock == CenterMode.HORIZONTAL)
			{
				newCx += dx / scale;
			}
			else if (lock == CenterMode.VERTICAL)
			{
				newCy += dy / scale;
			}
			else
			{
				newCx += dx / scale;
				newCy += dy / scale;
			}

			// setCenter clamps both axes jointly (binary search under rotation). On a
			// locked axis we only want motion on the unlocked axis — reject moves that
			// would drift the locked axis more than 0.5 px from its pre-drag position.
			state.setCropSizeDirty(false);
			state.setCenter(newCx, newCy);

			boolean reject = false;
			if (lock == CenterMode.HORIZONTAL)
			{
				reject = Math.abs(state.getCenterY() - preCy) > 0.5f;
			}
			else if (lock == CenterMode.VERTICAL)
			{
				reject = Math.abs(state.getCenterX() - preCx) > 0.5f;
			}

			if (!reject)
			{
				// Advance the anchor to the clamped (still fractional) drag position
				// so the next event continues accumulating from here.
				state.setAnchor(state.getCenterX(), state.getCenterY());
			}
			// Snap the display center so crop borders land on whole-pixel boundaries.
			// When rejected, the anchor is unchanged — recomputeCrop re-derives the
			// snapped center from the unchanged anchor, restoring the pre-drag position.
			CropEngine.recomputeCrop(state);
			invalidate();
		}
		else
		{
			// Pan viewport (select mode or no center in move mode)
			viewport.panViewport(dx, dy, state);
			invalidate();
		}
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
		float[] ip = viewport.screenToImagePixel(screenX, screenY, state);
		float ix = ip[0];
		float iy = ip[1];

		EditorMode mode = state.getEditorMode();
		if (mode == EditorMode.MOVE)
		{
			// Tap does nothing in Move mode — use drag to reposition crop
		}
		else if (mode == EditorMode.SELECT_FEATURE)
		{
			// Check if tapping on existing point → remove it
			float threshold = TOUCH_THRESHOLD_PX / (viewport.getBaseScale() * viewport.getZoom());
			for (int i = 0; i < state.getSelectionPoints().size(); i++)
			{
				SelectionPoint point = state.getSelectionPoints().get(i);
				if (Math.hypot(point.x() - ix, point.y() - iy) < threshold)
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
			// Snap to the center of the tapped pixel (image X in [P, P+1) → P + 0.5). This
			// means a single selection has cropCenter = P + 0.5 → odd cropW → the grid's
			// middle line lands on pixel P's center and visibly covers the point's marker.
			float snappedX = (float) Math.floor(ix) + 0.5f;
			float snappedY = (float) Math.floor(iy) + 0.5f;
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
			float sx = event.getX(), sy = event.getY();
			// Use the rotation-aware mapper: painted points are consumed by
			// HorizonDetector.detectFromPaintedRegion, which operates on the UN-rotated source
			// bitmap. Without un-rotation here, any rotation at paint time produces garbage
			// horizon angles.
			switch (event.getActionMasked())
			{
				case MotionEvent.ACTION_DOWN ->
				{
					horizon.begin(sx, sy, viewport.screenToImagePixel(sx, sy, state));
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
					horizon.extend(sx, sy, viewport.screenToImagePixel(sx, sy, state));
					invalidate();
				}
				case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
				{
					horizon.end(viewport.screenToImagePixel(sx, sy, state));
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
		float imgMidX = state.getImageWidth() / 2f;
		float imgMidY = state.getImageHeight() / 2f;
		state.markCropSizeDirty();
		state.setCenterUnclamped(imgMidX, imgMidY);
		// Reset the rotation anchor too — the user's "intent" is now the image center,
		// not whatever point was selected before (which may have been far away).
		state.setAnchor(imgMidX, imgMidY);
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

	public void setOnPointsChangedListener(Runnable r)
	{
		this.onPointsChanged = r;
	}

	public void setOnZoomChangedListener(Runnable r)
	{
		this.onZoomChanged = r;
	}

	public void setState(CropState state)
	{
		this.state = state;
		fitToView();
		invalidate();
	}

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
		int imgW = state.getImageWidth(), imgH = state.getImageHeight();
		float[] ip = viewport.screenToImagePixel(screenX, screenY, state);
		return ip[0] >= 0 && ip[0] <= imgW && ip[1] >= 0 && ip[1] <= imgH;
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
