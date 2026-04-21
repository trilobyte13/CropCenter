package com.cropcenter.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import com.cropcenter.crop.CropEngine;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.SelectionPoint;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.ThemeColors;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom view that renders the source image with crop overlay, handles touch gestures for
 * pan/zoom/center-set.
 */
public class CropEditorView extends View implements TouchGestureHandler.Callback
{
	private static final float TOUCH_THRESHOLD_PX = 30f;       // screen-pixel radius for tap / long-press / brush
	private static final int DIM_OVERLAY = 0xAA000000;         // 66% black — dims area outside crop
	private static final int POINT_LABEL_COLOR = ThemeColors.CRUST;

	private final GridRenderer gridRenderer = new GridRenderer();
	private final List<float[]> horizonPoints = new ArrayList<>(); // image coords
	private final List<List<SelectionPoint>> redoStack = new ArrayList<>();
	private final List<List<SelectionPoint>> undoStack = new ArrayList<>();
	private final Paint cropBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint dimPaint = new Paint();
	private final Paint horizonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
	private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint pixelGridPaint = new Paint();
	private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint polygonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path horizonScreenPath = new Path();

	private CropState state;
	private Runnable onHorizonDrawn;
	private Runnable onPointsChanged;
	private Runnable onZoomChanged;
	private TouchGestureHandler gestureHandler;
	private boolean horizonDrawing;
	private boolean horizonMode;
	private float baseScale = 1f; // scale to fit image in view
	private float density = 1f;
	private float vpX = 0; // viewport origin in image space (X)
	private float vpY = 0; // viewport origin in image space (Y)
	private float zoom = 1f;

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
		return !redoStack.isEmpty();
	}

	public boolean canUndo()
	{
		return !undoStack.isEmpty();
	}

	public void clearUndoHistory()
	{
		undoStack.clear();
		redoStack.clear();
		notifyPointsChanged();
	}

	public void fitToView()
	{
		if (state == null || state.getSourceImage() == null || getWidth() == 0)
		{
			return;
		}
		int imgW = state.getImageWidth();
		int imgH = state.getImageHeight();
		baseScale = Math.min((float) getWidth() / imgW, (float) getHeight() / imgH);
		zoom = 1f;
		vpX = imgW / 2f;
		vpY = imgH / 2f;
		invalidate();
	}

	/**
	 * Brush radius in image pixels for the painted region.
	 */
	public float getHorizonBrushRadius()
	{
		return TOUCH_THRESHOLD_PX / (baseScale * zoom);
	}

	/**
	 * Get the painted region points in image coordinates.
	 */
	public List<float[]> getHorizonPoints()
	{
		return horizonPoints;
	}

	public float getZoom()
	{
		return zoom;
	}

	public boolean isHorizonMode()
	{
		return horizonMode;
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
		float ix = screenToImageX(screenX);
		float iy = screenToImageY(screenY);
		float threshold = TOUCH_THRESHOLD_PX / (baseScale * zoom);

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
			float scale = baseScale * zoom;
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
			float scale = baseScale * zoom;
			vpX -= dx / scale;
			vpY -= dy / scale;
			clampViewport();
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
		float ix = screenToImageX(screenX);
		float iy = screenToImageY(screenY);

		EditorMode mode = state.getEditorMode();
		if (mode == EditorMode.MOVE)
		{
			// Tap does nothing in Move mode — use drag to reposition crop
		}
		else if (mode == EditorMode.SELECT_FEATURE)
		{
			// Check if tapping on existing point → remove it
			float threshold = TOUCH_THRESHOLD_PX / (baseScale * zoom);
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
		if (horizonMode)
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
					horizonPoints.clear();
					horizonScreenPath.reset();
					horizonScreenPath.moveTo(sx, sy);
					horizonPoints.add(screenToImagePixel(sx, sy));
					horizonDrawing = true;
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
					horizonScreenPath.lineTo(sx, sy);
					horizonPoints.add(screenToImagePixel(sx, sy));
					invalidate();
				}
				case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
				{
					horizonPoints.add(screenToImagePixel(sx, sy));
					horizonDrawing = false;
					horizonMode = false;
					invalidate();
					if (onHorizonDrawn != null)
					{
						onHorizonDrawn.run();
					}
				}
			}
			return true;
		}
		return gestureHandler.onTouchEvent(event);
	}

	@Override
	public void onZoom(float scaleFactor, float focusX, float focusY)
	{
		float imgFocusX = screenToImageX(focusX);
		float imgFocusY = screenToImageY(focusY);

		// Minimum zoom = 1.0 (fit to view), prevent zooming out beyond image
		zoom = Math.clamp(zoom * scaleFactor, 1f, 256f);

		// Adjust viewport so the focus point stays under the finger
		float newImgFocusX = screenToImageX(focusX);
		float newImgFocusY = screenToImageY(focusY);
		vpX += imgFocusX - newImgFocusX;
		vpY += imgFocusY - newImgFocusY;
		clampViewport();

		invalidate();
		if (onZoomChanged != null)
		{
			onZoomChanged.run();
		}
	}

	public void redo()
	{
		if (redoStack.isEmpty())
		{
			return;
		}
		undoStack.add(snapshotPoints());
		restorePoints(redoStack.remove(redoStack.size() - 1));
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
		this.horizonMode = on;
		this.horizonDrawing = false;
		this.onHorizonDrawn = onDrawn;
		horizonPoints.clear();
		horizonScreenPath.reset();
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
		if (undoStack.isEmpty())
		{
			return;
		}
		redoStack.add(snapshotPoints());
		restorePoints(undoStack.remove(undoStack.size() - 1));
		notifyPointsChanged();
	}

	// ── Drawing ──

	@Override
	protected void onDraw(Canvas canvas)
	{
		super.onDraw(canvas);
		if (state == null || state.getSourceImage() == null)
		{
			canvas.drawColor(ThemeColors.APP_BG);
			// Empty state hint
			infoPaint.setTextAlign(Paint.Align.CENTER);
			infoPaint.setTextSize(16f);
			infoPaint.setColor(ThemeColors.SURFACE2);
			canvas.drawText("Tap the gallery icon to open an image",
				getWidth() / 2f, getHeight() / 2f, infoPaint);
			return;
		}

		canvas.drawColor(ThemeColors.APP_BG);

		Bitmap bmp = state.getSourceImage();
		float scale = baseScale * zoom;

		// Crisp pixels when zoomed past 4x
		imagePaint.setFilterBitmap(scale < 4f);

		float left = imageToScreenX(0);
		float top = imageToScreenY(0);
		float rotation = state.getRotationDegrees();
		Matrix matrix = new Matrix();
		matrix.setScale(scale, scale);
		matrix.postTranslate(left, top);
		// Treat sub-UI-resolution residues as exactly zero — skipping postRotate keeps
		// the identity transform path (crisper preview) when the user has returned the
		// ruler to "0" but a tiny float residue remains.
		if (Math.abs(rotation) >= BitmapUtils.ROTATION_EPSILON)
		{
			float imgCx = left + bmp.getWidth() * scale / 2f;
			float imgCy = top + bmp.getHeight() * scale / 2f;
			matrix.postRotate(rotation, imgCx, imgCy);
		}
		canvas.drawBitmap(bmp, matrix, imagePaint);

		// Pixel grid visible at >=6x zoom
		if (state.getGridConfig().showPixelGrid() && scale >= 6f)
		{
			pixelGridPaint.setColor(state.getGridConfig().pixelGridColor());
			pixelGridPaint.setStrokeWidth(1f);

			float visLeft = screenToImageX(0);
			float visTop = screenToImageY(0);
			float visRight = screenToImageX(getWidth());
			float visBottom = screenToImageY(getHeight());

			int startX = Math.max(0, (int) Math.floor(visLeft));
			int startY = Math.max(0, (int) Math.floor(visTop));
			int endX = Math.min(bmp.getWidth(), (int) Math.ceil(visRight));
			int endY = Math.min(bmp.getHeight(), (int) Math.ceil(visBottom));

			// Vertical lines
			for (int x = startX; x <= endX; x++)
			{
				float sx = imageToScreenX(x);
				canvas.drawLine(sx, imageToScreenY(startY), sx, imageToScreenY(endY), pixelGridPaint);
			}
			// Horizontal lines
			for (int y = startY; y <= endY; y++)
			{
				float sy = imageToScreenY(y);
				canvas.drawLine(imageToScreenX(startX), sy, imageToScreenX(endX), sy, pixelGridPaint);
			}
		}

		float gridImgX, gridImgY;
		int gridW, gridH;

		if (state.hasCenter())
		{
			float cx = state.getCenterX();
			float cy = state.getCenterY();
			int cw = state.getCropW();
			int ch = state.getCropH();
			// Integer crop origin by the pixel-alignment invariant (getCropImgX/Y validates it).
			gridImgX = state.getCropImgX();
			gridImgY = state.getCropImgY();
			gridW = cw;
			gridH = ch;

			float cropL = imageToScreenX(gridImgX);
			float cropT = imageToScreenY(gridImgY);
			float cropR = imageToScreenX(gridImgX + cw);
			float cropB = imageToScreenY(gridImgY + ch);

			// Dim outside crop — cover full canvas, not just image bounds
			int vw = getWidth(), vh = getHeight();
			canvas.drawRect(0, 0, vw, cropT, dimPaint);       // top
			canvas.drawRect(0, cropB, vw, vh, dimPaint);       // bottom
			canvas.drawRect(0, cropT, cropL, cropB, dimPaint); // left
			canvas.drawRect(cropR, cropT, vw, cropB, dimPaint); // right

			canvas.drawRect(cropL, cropT, cropR, cropB, cropBorderPaint);

			float scx = imageToScreenX(cx);
			float scy = imageToScreenY(cy);
			float armLen = 15;
			canvas.drawLine(scx - armLen, scy, scx + armLen, scy, crosshairPaint);
			canvas.drawLine(scx, scy - armLen, scx, scy + armLen, crosshairPaint);

			infoPaint.setTextAlign(Paint.Align.LEFT);
			infoPaint.setTextSize(11f * density);
			infoPaint.setColor(withAlpha(ThemeColors.SUBTEXT0, 0xAA));
			canvas.drawText(cw + " x " + ch, cropL + 4, cropT - 6, infoPaint);
		}
		else
		{
			gridImgX = 0;
			gridImgY = 0;
			gridW = bmp.getWidth();
			gridH = bmp.getHeight();
		}

		gridRenderer.draw(canvas, gridImgX, gridImgY, gridW, gridH,
			state.getGridConfig(), baseScale * zoom,
			this::imageToScreenX, this::imageToScreenY);

		if (!state.getSelectionPoints().isEmpty())
		{
			List<SelectionPoint> points = state.getSelectionPoints();

			// Use the shared selection color (with its exact alpha) for points and polygon.
			int selColor = state.getGridConfig().selectionColor();
			pointPaint.setColor(selColor);
			polygonPaint.setColor(selColor);

			if (points.size() >= 3)
			{
				Path path = new Path();
				boolean first = true;
				for (SelectionPoint point : points)
				{
					float sx = imageToScreenX(point.x());
					float sy = imageToScreenY(point.y());
					if (first)
					{
						path.moveTo(sx, sy);
						first = false;
					}
					else
					{
						path.lineTo(sx, sy);
					}
				}
				path.close();
				canvas.drawPath(path, polygonPaint);
			}

			// Draw points — fill pixel square when zoomed in, circle when zoomed out
			int idx = 0;
			float pixelSize = scale; // one image pixel in screen pixels
			for (SelectionPoint point : points)
			{
				idx++;
				if (pixelSize >= 6f)
				{
					int pixelX = (int) Math.floor(point.x());
					int pixelY = (int) Math.floor(point.y());
					float pixelLeft = imageToScreenX(pixelX);
					float pixelTop = imageToScreenY(pixelY);
					float pixelRight = imageToScreenX(pixelX + 1);
					float pixelBottom = imageToScreenY(pixelY + 1);
					canvas.drawRect(pixelLeft, pixelTop, pixelRight, pixelBottom, pointPaint);
					infoPaint.setTextAlign(Paint.Align.CENTER);
					infoPaint.setTextSize(Math.min(pixelSize * 0.6f, 14f * density));
					infoPaint.setColor(POINT_LABEL_COLOR);
					float textX = (pixelLeft + pixelRight) / 2;
					float textY = (pixelTop + pixelBottom) / 2 + infoPaint.getTextSize() * 0.35f;
					canvas.drawText(String.valueOf(idx), textX, textY, infoPaint);
				}
				else
				{
					float screenX = imageToScreenX(point.x());
					float screenY = imageToScreenY(point.y());
					canvas.drawCircle(screenX, screenY, 10, pointPaint);
					infoPaint.setTextAlign(Paint.Align.CENTER);
					infoPaint.setTextSize(9f * density);
					infoPaint.setColor(POINT_LABEL_COLOR);
					canvas.drawText(String.valueOf(idx), screenX, screenY + 4, infoPaint);
				}
			}
		}

		if (horizonMode || horizonDrawing)
		{
			int selColor = state.getGridConfig().selectionColor();
			if (horizonDrawing && !horizonScreenPath.isEmpty())
			{
				// Paint with the user's exact selection color — no extra blending.
				horizonPaint.setStyle(Paint.Style.STROKE);
				horizonPaint.setStrokeWidth(60f);
				horizonPaint.setColor(selColor);
				horizonPaint.setStrokeCap(Paint.Cap.ROUND);
				horizonPaint.setStrokeJoin(Paint.Join.ROUND);
				canvas.drawPath(horizonScreenPath, horizonPaint);
				horizonPaint.setStrokeWidth(3f);
				horizonPaint.setStrokeCap(Paint.Cap.BUTT);
			}
			if (horizonMode && !horizonDrawing)
			{
				// Waiting for paint — show hint in the selection color.
				infoPaint.setTextAlign(Paint.Align.CENTER);
				infoPaint.setTextSize(14f * density);
				infoPaint.setColor(selColor);
				canvas.drawText("Paint over the horizon",
					getWidth() / 2f, getHeight() / 2f, infoPaint);
			}
		}
	}

	@Override
	protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight)
	{
		super.onSizeChanged(width, height, oldWidth, oldHeight);
		fitToView();
	}

	private void clampViewport()
	{
		if (state == null || state.getSourceImage() == null)
		{
			return;
		}
		int imgW = state.getImageWidth();
		int imgH = state.getImageHeight();
		float scale = baseScale * zoom;

		// How much of the image is visible in screen pixels
		float visibleW = getWidth() / scale;
		float visibleH = getHeight() / scale;

		if (visibleW >= imgW)
		{
			// Entire width fits — center it
			vpX = imgW / 2f;
		}
		else
		{
			vpX = Math.clamp(vpX, visibleW / 2f, imgW - visibleW / 2f);
		}

		if (visibleH >= imgH)
		{
			vpY = imgH / 2f;
		}
		else
		{
			vpY = Math.clamp(vpY, visibleH / 2f, imgH - visibleH / 2f);
		}
	}

	// ── Coordinate transforms ──

	private float imageToScreenX(float ix)
	{
		float scale = baseScale * zoom;
		return getWidth() / 2f + (ix - vpX) * scale;
	}

	private float imageToScreenY(float iy)
	{
		float scale = baseScale * zoom;
		return getHeight() / 2f + (iy - vpY) * scale;
	}

	private void init(Context context)
	{
		density = context.getResources().getDisplayMetrics().density;
		gestureHandler = new TouchGestureHandler(context, this);

		dimPaint.setColor(DIM_OVERLAY);
		cropBorderPaint.setColor(ThemeColors.MAUVE);
		cropBorderPaint.setStrokeWidth(2f);
		cropBorderPaint.setStyle(Paint.Style.STROKE);
		crosshairPaint.setColor(withAlpha(ThemeColors.MAUVE, 0xCC));
		crosshairPaint.setStrokeWidth(1f);
		// Point/polygon/horizon colors are set dynamically from GridConfig.selectionColor
		pointPaint.setStyle(Paint.Style.FILL);
		polygonPaint.setStyle(Paint.Style.FILL);
		infoPaint.setColor(ThemeColors.TEXT);
		infoPaint.setTextSize(24f);

		horizonPaint.setStrokeWidth(3f);
		horizonPaint.setStyle(Paint.Style.STROKE);
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
		float[] ip = screenToImagePixel(screenX, screenY);
		return ip[0] >= 0 && ip[0] <= imgW && ip[1] >= 0 && ip[1] <= imgH;
	}

	private void notifyPointsChanged()
	{
		if (onPointsChanged != null)
		{
			onPointsChanged.run();
		}
	}

	// ── Undo/Redo for selection points ──

	private void pushUndo()
	{
		undoStack.add(snapshotPoints());
		redoStack.clear();
		if (undoStack.size() > 50)
		{
			undoStack.remove(0);
		}
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

	/**
	 * Convert a SCREEN point to IMAGE pixel coordinates, accounting for the rotation applied at
	 * draw time. Returns a float[2] of image pixels (possibly outside the image bounds —
	 * caller checks).
	 */
	private float[] screenToImagePixel(float screenX, float screenY)
	{
		float rotation = (state == null) ? 0f : state.getRotationDegrees();
		if (rotation == 0f)
		{
			return new float[] { screenToImageX(screenX), screenToImageY(screenY) };
		}
		// Un-rotate around the image center in screen space.
		int imgW = state.getImageWidth(), imgH = state.getImageHeight();
		float scrCx = imageToScreenX(imgW / 2f);
		float scrCy = imageToScreenY(imgH / 2f);
		double rad = Math.toRadians(-rotation);
		double dx = screenX - scrCx;
		double dy = screenY - scrCy;
		double unRotX = dx * Math.cos(rad) - dy * Math.sin(rad) + scrCx;
		double unRotY = dx * Math.sin(rad) + dy * Math.cos(rad) + scrCy;
		return new float[] {
				screenToImageX((float) unRotX),
				screenToImageY((float) unRotY)
		};
	}

	private float screenToImageX(float sx)
	{
		float scale = baseScale * zoom;
		return vpX + (sx - getWidth() / 2f) / scale;
	}

	private float screenToImageY(float sy)
	{
		float scale = baseScale * zoom;
		return vpY + (sy - getHeight() / 2f) / scale;
	}

	private List<SelectionPoint> snapshotPoints()
	{
		// SelectionPoint is an immutable record — a shallow copy of the list is a true snapshot.
		return new ArrayList<>(state.getSelectionPoints());
	}

	private static int withAlpha(int color, int alpha)
	{
		return (color & 0x00FFFFFF) | (alpha << 24);
	}
}
