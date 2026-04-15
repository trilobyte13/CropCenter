package com.cropcenter.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.cropcenter.crop.CropEngine;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.SelectionPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom view that renders the source image with crop overlay,
 * handles touch gestures for pan/zoom/center-set.
 */
public class CropEditorView extends View implements TouchGestureHandler.Callback {

    // Viewport state
    private float vpX = 0, vpY = 0; // viewport origin in image space
    private float zoom = 1f;
    private float baseScale = 1f;   // scale to fit image in view

    // Rendering
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint cropBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inactivePointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint polygonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pixelGridPaint = new Paint();
    private final GridRenderer gridRenderer = new GridRenderer();

    // State
    private CropState state;
    private TouchGestureHandler gestureHandler;
    private float density = 1f;
    private Runnable onZoomChanged;
    private Runnable onPointsChanged;

    // Undo/redo stacks for selection points
    private final List<List<SelectionPoint>> undoStack = new ArrayList<>();
    private final List<List<SelectionPoint>> redoStack = new ArrayList<>();

    public CropEditorView(Context context) {
        super(context);
        init(context);
    }

    public CropEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CropEditorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        gestureHandler = new TouchGestureHandler(context, this);

        dimPaint.setColor(0xAA000000);
        cropBorderPaint.setColor(0xFFCBA6F7); // Catppuccin mauve
        cropBorderPaint.setStrokeWidth(2f);
        cropBorderPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setColor(0xCCCBA6F7);
        crosshairPaint.setStrokeWidth(1f);
        pointPaint.setColor(0xFFF9E2AF); // Catppuccin yellow (active)
        pointPaint.setStyle(Paint.Style.FILL);
        inactivePointPaint.setColor(0x66F9E2AF); // transparent yellow (inactive)
        inactivePointPaint.setStyle(Paint.Style.FILL);
        polygonPaint.setColor(0x22F9E2AF); // very transparent yellow fill
        polygonPaint.setStyle(Paint.Style.FILL);
        infoPaint.setColor(0xFFCDD6F4); // text color
        infoPaint.setTextSize(24f);
    }

    public void setState(CropState state) {
        this.state = state;
        fitToView();
        invalidate();
    }

    public float getZoom() { return zoom; }
    public void setOnZoomChangedListener(Runnable r) { this.onZoomChanged = r; }
    public void setOnPointsChangedListener(Runnable r) { this.onPointsChanged = r; }
    private void notifyPointsChanged() { if (onPointsChanged != null) onPointsChanged.run(); }

    /**
     * Check if a SCREEN point is inside the visible (rotated) image content.
     * The image is drawn rotated by state.getRotationDegrees() around its center.
     * We un-rotate the screen point relative to the image center, then check
     * if it maps to valid image coordinates.
     */
    private boolean isInsideRotatedImage(float screenX, float screenY) {
        if (state == null || state.getSourceImage() == null) return false;
        int imgW = state.getImageWidth(), imgH = state.getImageHeight();
        float rotation = state.getRotationDegrees();

        if (rotation == 0f) {
            float ix = screenToImageX(screenX);
            float iy = screenToImageY(screenY);
            return ix >= 0 && ix <= imgW && iy >= 0 && iy <= imgH;
        }

        // Image center in screen coords
        float scrCx = imageToScreenX(imgW / 2f);
        float scrCy = imageToScreenY(imgH / 2f);

        // Un-rotate the screen point around the image center by -rotation
        double rad = Math.toRadians(-rotation);
        double dx = screenX - scrCx;
        double dy = screenY - scrCy;
        double unRotX = dx * Math.cos(rad) - dy * Math.sin(rad) + scrCx;
        double unRotY = dx * Math.sin(rad) + dy * Math.cos(rad) + scrCy;

        // Convert un-rotated screen point to image coords
        float ix = screenToImageX((float) unRotX);
        float iy = screenToImageY((float) unRotY);
        return ix >= 0 && ix <= imgW && iy >= 0 && iy <= imgH;
    }

    private void clampViewport() {
        if (state == null || state.getSourceImage() == null) return;
        int imgW = state.getImageWidth();
        int imgH = state.getImageHeight();
        float scale = baseScale * zoom;

        // How much of the image is visible in screen pixels
        float visibleW = getWidth() / scale;
        float visibleH = getHeight() / scale;

        if (visibleW >= imgW) {
            // Entire width fits — center it
            vpX = imgW / 2f;
        } else {
            vpX = Math.max(visibleW / 2f, Math.min(imgW - visibleW / 2f, vpX));
        }

        if (visibleH >= imgH) {
            vpY = imgH / 2f;
        } else {
            vpY = Math.max(visibleH / 2f, Math.min(imgH - visibleH / 2f, vpY));
        }
    }

    // ── Undo/Redo for selection points ──

    private List<SelectionPoint> snapshotPoints() {
        List<SelectionPoint> copy = new ArrayList<>();
        for (SelectionPoint p : state.getSelectionPoints()) {
            copy.add(new SelectionPoint(p.x, p.y));
            copy.get(copy.size() - 1).active = p.active;
        }
        return copy;
    }

    private void restorePoints(List<SelectionPoint> snapshot) {
        state.getSelectionPoints().clear();
        for (SelectionPoint p : snapshot) {
            SelectionPoint np = new SelectionPoint(p.x, p.y);
            np.active = p.active;
            state.getSelectionPoints().add(np);
        }
        if (snapshot.isEmpty()) {
            // No points left — clear crop center (reverts to full image)
            resetCropToFullImage();
        } else {
            CropEngine.autoComputeFromPoints(state);
        }
        invalidate();
    }

    private void pushUndo() {
        undoStack.add(snapshotPoints());
        redoStack.clear(); // new action invalidates redo
        if (undoStack.size() > 50) undoStack.remove(0); // cap history
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.add(snapshotPoints());
        restorePoints(undoStack.remove(undoStack.size() - 1));
        notifyPointsChanged();
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.add(snapshotPoints());
        restorePoints(redoStack.remove(redoStack.size() - 1));
        notifyPointsChanged();
    }

    public void clearUndoHistory() {
        undoStack.clear();
        redoStack.clear();
        notifyPointsChanged();
    }

    /** Reset crop to full image centered with current AR. */
    public void resetCropToFullImage() {
        state.markCropSizeDirty();
        state.setCenterUnclamped(state.getImageWidth() / 2f, state.getImageHeight() / 2f);
        CropEngine.recomputeCrop(state);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    public void fitToView() {
        if (state == null || state.getSourceImage() == null || getWidth() == 0) return;
        int imgW = state.getImageWidth();
        int imgH = state.getImageHeight();
        baseScale = Math.min((float) getWidth() / imgW, (float) getHeight() / imgH);
        zoom = 1f;
        vpX = imgW / 2f;
        vpY = imgH / 2f;
        invalidate();
    }

    // ── Coordinate transforms ──

    private float imageToScreenX(float ix) {
        float scale = baseScale * zoom;
        return getWidth() / 2f + (ix - vpX) * scale;
    }

    private float imageToScreenY(float iy) {
        float scale = baseScale * zoom;
        return getHeight() / 2f + (iy - vpY) * scale;
    }

    private float screenToImageX(float sx) {
        float scale = baseScale * zoom;
        return vpX + (sx - getWidth() / 2f) / scale;
    }

    private float screenToImageY(float sy) {
        float scale = baseScale * zoom;
        return vpY + (sy - getHeight() / 2f) / scale;
    }

    // ── Drawing ──

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (state == null || state.getSourceImage() == null) {
            canvas.drawColor(0xFF111318);
            // Empty state hint
            infoPaint.setTextAlign(Paint.Align.CENTER);
            infoPaint.setTextSize(16f);
            infoPaint.setColor(0xFF585B70);
            canvas.drawText("Tap the gallery icon to open an image",
                    getWidth() / 2f, getHeight() / 2f, infoPaint);
            return;
        }

        canvas.drawColor(0xFF111318);

        Bitmap bmp = state.getSourceImage();
        float scale = baseScale * zoom;

        // Disable bitmap filtering when zoomed in far for crisp pixels
        imagePaint.setFilterBitmap(scale < 4f);

        // Draw image
        float left = imageToScreenX(0);
        float top = imageToScreenY(0);
        float rotation = state.getRotationDegrees();
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(left, top);
        if (rotation != 0f) {
            float imgCx = left + bmp.getWidth() * scale / 2f;
            float imgCy = top + bmp.getHeight() * scale / 2f;
            m.postRotate(rotation, imgCx, imgCy);
        }
        canvas.drawBitmap(bmp, m, imagePaint);

        // Pixel grid: show individual pixel boundaries when zoomed in >= 6x
        if (state.getGridConfig().showPixelGrid && scale >= 6f) {
            pixelGridPaint.setColor(state.getGridConfig().pixelGridColor);
            pixelGridPaint.setStrokeWidth(1f);

            // Compute visible pixel range in image coordinates
            float visLeft = screenToImageX(0);
            float visTop = screenToImageY(0);
            float visRight = screenToImageX(getWidth());
            float visBottom = screenToImageY(getHeight());

            int startX = Math.max(0, (int) Math.floor(visLeft));
            int startY = Math.max(0, (int) Math.floor(visTop));
            int endX = Math.min(bmp.getWidth(), (int) Math.ceil(visRight));
            int endY = Math.min(bmp.getHeight(), (int) Math.ceil(visBottom));

            // Vertical lines
            for (int x = startX; x <= endX; x++) {
                float sx = imageToScreenX(x);
                canvas.drawLine(sx, imageToScreenY(startY), sx, imageToScreenY(endY), pixelGridPaint);
            }
            // Horizontal lines
            for (int y = startY; y <= endY; y++) {
                float sy = imageToScreenY(y);
                canvas.drawLine(imageToScreenX(startX), sy, imageToScreenX(endX), sy, pixelGridPaint);
            }
        }

        // Determine grid/crop region (full image if no crop center)
        float gridImgX, gridImgY;
        int gridW, gridH;

        if (state.hasCenter()) {
            float cx = state.getCenterX();
            float cy = state.getCenterY();
            int cw = state.getCropW();
            int ch = state.getCropH();
            gridImgX = cx - cw / 2f;
            gridImgY = cy - ch / 2f;
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

            // Crop border
            canvas.drawRect(cropL, cropT, cropR, cropB, cropBorderPaint);

            // Crosshair at center
            float scx = imageToScreenX(cx);
            float scy = imageToScreenY(cy);
            float armLen = 15;
            canvas.drawLine(scx - armLen, scy, scx + armLen, scy, crosshairPaint);
            canvas.drawLine(scx, scy - armLen, scx, scy + armLen, crosshairPaint);

            // Crop size text
            infoPaint.setTextAlign(Paint.Align.LEFT);
            infoPaint.setTextSize(11f * density);
            infoPaint.setColor(0xAAA6ADC8);
            canvas.drawText(cw + " x " + ch, cropL + 4, cropT - 6, infoPaint);
        } else {
            // No crop — grid covers full image
            gridImgX = 0;
            gridImgY = 0;
            gridW = bmp.getWidth();
            gridH = bmp.getHeight();
        }

        // Grid overlay (always drawn, on crop rect or full image)
        gridRenderer.draw(canvas, gridImgX, gridImgY, gridW, gridH,
                state.getGridConfig(), baseScale * zoom,
                this::imageToScreenX, this::imageToScreenY);

        // Draw selection points and polygon (visible in all modes)
        if (!state.getSelectionPoints().isEmpty()) {
            java.util.List<SelectionPoint> points = state.getSelectionPoints();

            // Use grid color for points and polygon
            int gridColor = state.getGridConfig().color;
            pointPaint.setColor(gridColor);
            inactivePointPaint.setColor(withAlpha(gridColor, 0x66));
            polygonPaint.setColor(withAlpha(gridColor, 0x22));

            // Draw polygon fill between active points
            android.graphics.Path path = new android.graphics.Path();
            boolean first = true;
            int activeCount = 0;
            for (SelectionPoint p : points) {
                if (!p.active) continue;
                activeCount++;
                float sx = imageToScreenX(p.x);
                float sy = imageToScreenY(p.y);
                if (first) { path.moveTo(sx, sy); first = false; }
                else path.lineTo(sx, sy);
            }
            if (activeCount >= 3) {
                path.close();
                canvas.drawPath(path, polygonPaint);
            }

            // Draw points — fill pixel square when zoomed in, circle when zoomed out
            int idx = 0;
            float pixelSize = scale; // one image pixel in screen pixels
            for (SelectionPoint p : points) {
                idx++;
                Paint pp = p.active ? pointPaint : inactivePointPaint;

                if (pixelSize >= 6f) {
                    // Zoomed in: fill the pixel square
                    int px = (int) Math.floor(p.x);
                    int py = (int) Math.floor(p.y);
                    float l = imageToScreenX(px);
                    float t = imageToScreenY(py);
                    float r = imageToScreenX(px + 1);
                    float b = imageToScreenY(py + 1);
                    canvas.drawRect(l, t, r, b, pp);
                    // Number
                    infoPaint.setTextAlign(Paint.Align.CENTER);
                    infoPaint.setTextSize(Math.min(pixelSize * 0.6f, 14f * density));
                    infoPaint.setColor(p.active ? 0xFF11111B : 0x88FFFFFF);
                    canvas.drawText(String.valueOf(idx), (l + r) / 2, (t + b) / 2 + infoPaint.getTextSize() * 0.35f, infoPaint);
                } else {
                    // Zoomed out: circle
                    float sx = imageToScreenX(p.x);
                    float sy = imageToScreenY(p.y);
                    canvas.drawCircle(sx, sy, 10, pp);
                    infoPaint.setTextAlign(Paint.Align.CENTER);
                    infoPaint.setTextSize(9f * density);
                    infoPaint.setColor(p.active ? 0xFF11111B : 0x88FFFFFF);
                    canvas.drawText(String.valueOf(idx), sx, sy + 4, infoPaint);
                }
            }
        }
    }

    // ── Touch handling ──

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureHandler.onTouchEvent(event);
    }

    @Override
    public void onTap(float screenX, float screenY) {
        if (state == null) return;
        float ix = screenToImageX(screenX);
        float iy = screenToImageY(screenY);

        EditorMode mode = state.getEditorMode();
        if (mode == EditorMode.MOVE) {
            // Tap does nothing in Move mode — use drag to reposition crop
        } else if (mode == EditorMode.SELECT_FEATURE) {
            // Check if tapping on existing point → remove it
            float threshold = 30 / (baseScale * zoom);
            for (int i = 0; i < state.getSelectionPoints().size(); i++) {
                SelectionPoint p = state.getSelectionPoints().get(i);
                if (Math.hypot(p.x - ix, p.y - iy) < threshold) {
                    pushUndo();
                    state.getSelectionPoints().remove(i);
                    if (state.getSelectionPoints().isEmpty()) {
                        resetCropToFullImage();
                    } else {
                        CropEngine.autoComputeFromPoints(state);
                    }
                    invalidate();
                    notifyPointsChanged();
                    return;
                }
            }
            // Only add point if inside the visible (rotated) image content
            if (!isInsideRotatedImage(screenX, screenY)) return;
            pushUndo();
            state.getSelectionPoints().add(new SelectionPoint(ix, iy));
            CropEngine.autoComputeFromPoints(state);
            invalidate();
            notifyPointsChanged();
        }
    }

    @Override
    public void onPan(float dx, float dy) {
        if (state == null) return;
        EditorMode mode = state.getEditorMode();

        if (mode == EditorMode.MOVE && state.hasCenter()
                && state.getCenterMode() != CenterMode.LOCKED) {
            // Drag to move center — respect lock direction
            float scale = baseScale * zoom;
            CenterMode lock = state.getCenterMode();
            float newCx = state.getCenterX();
            float newCy = state.getCenterY();

            float origCx = newCx, origCy = newCy;
            if (lock == CenterMode.HORIZONTAL) {
                newCx += dx / scale;
            } else if (lock == CenterMode.VERTICAL) {
                newCy += dy / scale;
            } else {
                newCx += dx / scale;
                newCy += dy / scale;
            }

            // Test if the proposed position is valid without cross-axis drift.
            // setCenter clamps both axes, which can cause the non-moving axis to shift.
            // Instead: test the move, and only accept it if the non-moving axis stays put.
            state.setCropSizeDirty(false);
            state.setCenter(newCx, newCy);

            boolean reject = false;
            if (lock == CenterMode.HORIZONTAL) {
                reject = Math.abs(state.getCenterY() - origCy) > 0.5f;
            } else if (lock == CenterMode.VERTICAL) {
                reject = Math.abs(state.getCenterX() - origCx) > 0.5f;
            }

            if (reject) {
                // Move would cause drift on locked axis — revert to previous position
                state.setCenter(origCx, origCy);
            }
            invalidate();
        } else {
            // Pan viewport (select mode or no center in move mode)
            float scale = baseScale * zoom;
            vpX -= dx / scale;
            vpY -= dy / scale;
            clampViewport();
            invalidate();
        }
    }

    @Override
    public void onZoom(float scaleFactor, float focusX, float focusY) {
        float imgFocusX = screenToImageX(focusX);
        float imgFocusY = screenToImageY(focusY);

        // Minimum zoom = 1.0 (fit to view), prevent zooming out beyond image
        zoom = Math.max(1f, Math.min(256f, zoom * scaleFactor));

        // Adjust viewport so the focus point stays under the finger
        float newImgFocusX = screenToImageX(focusX);
        float newImgFocusY = screenToImageY(focusY);
        vpX += imgFocusX - newImgFocusX;
        vpY += imgFocusY - newImgFocusY;
        clampViewport();

        invalidate();
        if (onZoomChanged != null) onZoomChanged.run();
    }

    @Override
    public void onDoubleTap() {
        // Disable in select mode to prevent accidental zoom while placing points
        if (state != null && state.getEditorMode() == EditorMode.SELECT_FEATURE) return;
        fitToView();
    }

    @Override
    public void onLongPress(float screenX, float screenY) {
        if (state == null || state.getEditorMode() != EditorMode.SELECT_FEATURE) return;
        float ix = screenToImageX(screenX);
        float iy = screenToImageY(screenY);
        float threshold = 30 / (baseScale * zoom);

        SelectionPoint nearest = null;
        float nearestDist = Float.MAX_VALUE;
        for (SelectionPoint p : state.getSelectionPoints()) {
            float d = (float) Math.hypot(p.x - ix, p.y - iy);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = p;
            }
        }
        if (nearest != null && nearestDist < threshold) {
            pushUndo();
            state.getSelectionPoints().remove(nearest);
            if (state.getSelectionPoints().isEmpty()) {
                resetCropToFullImage();
            } else {
                CropEngine.autoComputeFromPoints(state);
            }
            invalidate();
            notifyPointsChanged();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitToView();
    }
}
