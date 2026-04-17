package com.cropcenter.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.widget.OverScroller;
import android.view.View;

/**
 * Galaxy-style scrollable rotation ruler with pinch-to-zoom.
 *
 * Drag to scroll, fling for momentum. Pinch to zoom the ruler scale,
 * enabling 0.01° precision at the highest zoom. After drag/fling settles,
 * the value snaps to the nearest tick interval for the current zoom level.
 */
public class RotationRulerView extends View {

    public interface OnRotationChangedListener {
        void onRotationChanged(float degrees);
    }

    private static final float MIN_DEG = -180f;
    private static final float MAX_DEG = 180f;

    private float currentDegrees = 0f;
    private boolean enabled = true;

    // Zoom
    private float basePixelsPerDegree;
    private float pixelsPerDegree;
    private static final float MIN_PPD_FACTOR = 1f;
    private static final float MAX_PPD_FACTOR = 120f; // enough to show 0.01° ticks

    private final Paint minorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint majorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detentTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final OverScroller scroller;
    private VelocityTracker velocityTracker;
    private float lastTouchX;
    private float downX; // where finger touched down
    private float totalDragDx; // cumulative drag distance since touchdown
    private boolean isDragging;
    private static final float TAP_SLOP = 8f; // pixels — tap vs drag threshold

    private final ScaleGestureDetector scaleDetector;
    private boolean isScaling;
    private boolean scalingOccurred; // true if any scaling happened during current gesture

    private OnRotationChangedListener listener;

    public RotationRulerView(Context context) { this(context, null); }
    public RotationRulerView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public RotationRulerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        float density = context.getResources().getDisplayMetrics().density;
        basePixelsPerDegree = 12 * density;
        pixelsPerDegree = basePixelsPerDegree;

        scroller = new OverScroller(context);

        minorTickPaint.setColor(0xFF45475A);
        minorTickPaint.setStrokeWidth(density);

        majorTickPaint.setColor(0xFF585B70);
        majorTickPaint.setStrokeWidth(density);

        detentTickPaint.setColor(0xFFA6ADC8);
        detentTickPaint.setStrokeWidth(1.5f * density);

        indicatorPaint.setColor(0xFFCBA6F7);
        indicatorPaint.setStrokeWidth(2f * density);

        zeroPaint.setColor(0xFFF38BA8);
        zeroPaint.setStrokeWidth(1.5f * density);

        labelPaint.setColor(0xFF6C7086);
        labelPaint.setTextSize(8 * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector det) {
                isScaling = true;
                scalingOccurred = true;
                scroller.forceFinished(true);
                return true;
            }
            @Override
            public boolean onScale(ScaleGestureDetector det) {
                pixelsPerDegree = Math.max(basePixelsPerDegree * MIN_PPD_FACTOR,
                        Math.min(basePixelsPerDegree * MAX_PPD_FACTOR, pixelsPerDegree * det.getScaleFactor()));
                invalidate();
                return true;
            }
            @Override
            public void onScaleEnd(ScaleGestureDetector det) {
                isScaling = false;
            }
        });
    }

    public void setOnRotationChangedListener(OnRotationChangedListener l) { this.listener = l; }
    public float getDegrees() { return currentDegrees; }

    public void setDegrees(float deg) {
        deg = Math.max(MIN_DEG, Math.min(MAX_DEG, deg));
        if (deg != currentDegrees) {
            currentDegrees = deg;
            scroller.forceFinished(true);
            invalidate();
        }
    }

    public void setRulerEnabled(boolean e) {
        this.enabled = e;
        setAlpha(e ? 1f : 0.3f);
    }

    // ── Tick configuration ──

    private record TickConfig(float minor, float major) {}

    /** Choose tick intervals based on how many degrees are visible on screen. */
    private static TickConfig chooseTickConfig(float degreesVisible) {
        if (degreesVisible > 270) return new TickConfig(10f, 45f);
        if (degreesVisible > 90)  return new TickConfig(5f, 45f);
        if (degreesVisible > 30)  return new TickConfig(1f, 10f);
        if (degreesVisible > 10)  return new TickConfig(1f, 5f);
        if (degreesVisible > 3)   return new TickConfig(0.5f, 1f);
        if (degreesVisible > 1)   return new TickConfig(0.1f, 0.5f);
        if (degreesVisible > 0.3) return new TickConfig(0.05f, 0.1f);
        return new TickConfig(0.01f, 0.1f);
    }

    /** Snap a degree value to the nearest minor tick for the current zoom. */
    private float snapToTick(float deg) {
        float degreesVisible = getWidth() > 0 ? getWidth() / pixelsPerDegree : 30f;
        TickConfig tc = chooseTickConfig(degreesVisible);
        return snapTo(deg, tc.minor);
    }

    private static float snapTo(float val, float step) {
        return Math.round(val / step) * step;
    }

    // ── Drawing ──

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float centerX = w / 2f;
        float labelY = h - 1;
        float tickTop = 2;
        float tickBot = h - labelPaint.getTextSize() - 3;

        float degreesVisible = w / pixelsPerDegree;
        TickConfig tc = chooseTickConfig(degreesVisible);

        // Use integer tick indices to avoid float accumulation errors.
        // tickIndex * tc.minor = degree value.
        int iStart = (int) Math.floor((currentDegrees - degreesVisible / 2f - tc.major) / tc.minor);
        int iEnd = (int) Math.ceil((currentDegrees + degreesVisible / 2f + tc.major) / tc.minor);

        // Multiplier to convert minor intervals to major check (integer comparison)
        int majorEvery = Math.round(tc.major / tc.minor);
        int detentEvery = Math.round(45f / tc.minor);

        for (int i = iStart; i <= iEnd; i++) {
            float deg = i * tc.minor;
            if (deg < MIN_DEG || deg > MAX_DEG) continue;

            float x = centerX + (deg - currentDegrees) * pixelsPerDegree;
            if (x < -10 || x > w + 10) continue;

            boolean isDetent = detentEvery > 0 && i % detentEvery == 0;
            boolean isMajor = majorEvery > 0 && i % majorEvery == 0;

            if (isDetent) {
                canvas.drawLine(x, tickTop, x, tickBot, detentTickPaint);
                canvas.drawText(formatTickLabel(deg), x, labelY, labelPaint);
            } else if (isMajor) {
                float mid = (tickTop + tickBot) / 2f;
                float halfH = (tickBot - tickTop) * 0.35f;
                canvas.drawLine(x, mid - halfH, x, mid + halfH, majorTickPaint);
                canvas.drawText(formatTickLabel(deg), x, labelY, labelPaint);
            } else {
                float mid = (tickTop + tickBot) / 2f;
                float halfH = (tickBot - tickTop) * 0.18f;
                canvas.drawLine(x, mid - halfH, x, mid + halfH, minorTickPaint);
            }
        }

        // Zero marker
        float zeroX = centerX - currentDegrees * pixelsPerDegree;
        if (zeroX > -5 && zeroX < w + 5 && currentDegrees != 0f) {
            canvas.drawLine(zeroX, tickTop, zeroX, tickBot, zeroPaint);
        }

        // Center indicator triangle
        float triH = 4 * getResources().getDisplayMetrics().density;
        indicatorPaint.setStyle(Paint.Style.FILL);
        android.graphics.Path tri = new android.graphics.Path();
        tri.moveTo(centerX, tickTop);
        tri.lineTo(centerX - triH, tickTop + triH);
        tri.lineTo(centerX + triH, tickTop + triH);
        tri.close();
        canvas.drawPath(tri, indicatorPaint);
        indicatorPaint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(centerX, tickTop + triH, centerX, tickBot, indicatorPaint);
    }

    private static String formatTickLabel(float deg) {
        if (deg == Math.floor(deg)) return (int) deg + "\u00B0";
        if (Math.abs(deg * 10 - Math.round(deg * 10)) < 0.001f)
            return String.format("%.1f\u00B0", deg);
        return String.format("%.2f\u00B0", deg);
    }

    // ── Touch handling ──

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!enabled) return false;

        scaleDetector.onTouchEvent(event);

        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true);
                downX = lastTouchX = event.getX();
                totalDragDx = 0;
                scalingOccurred = false;
                isDragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!isScaling && event.getPointerCount() == 1) {
                    float dx = event.getX() - lastTouchX;
                    lastTouchX = event.getX();
                    totalDragDx += Math.abs(dx);
                    float newDeg = Math.max(MIN_DEG, Math.min(MAX_DEG,
                            currentDegrees - dx / pixelsPerDegree));
                    if (newDeg != currentDegrees) {
                        currentDegrees = newDeg;
                        notifyChanged();
                        invalidate();
                    }
                }
                lastTouchX = event.getX();
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false;
                if (!isScaling) {
                    // Tap vs drag: total movement <= TAP_SLOP AND no scaling happened
                    if (!scalingOccurred && totalDragDx <= TAP_SLOP
                            && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        float centerX = getWidth() / 2f;
                        float tappedDeg = currentDegrees + (downX - centerX) / pixelsPerDegree;
                        tappedDeg = Math.max(MIN_DEG, Math.min(MAX_DEG, tappedDeg));
                        currentDegrees = tappedDeg;
                        snapAndNotify();
                        velocityTracker.recycle();
                        velocityTracker = null;
                        return true;
                    }
                    velocityTracker.computeCurrentVelocity(1000);
                    float velX = velocityTracker.getXVelocity();
                    if (Math.abs(velX) > 200) {
                        int scale = 1000;
                        int startX = (int) (currentDegrees * pixelsPerDegree * scale);
                        int minX = (int) (MIN_DEG * pixelsPerDegree * scale);
                        int maxX = (int) (MAX_DEG * pixelsPerDegree * scale);
                        scroller.fling(startX, 0, (int) (-velX * scale), 0, minX, maxX, 0, 0);
                        postInvalidateOnAnimation();
                    } else {
                        // Snap to nearest tick
                        snapAndNotify();
                    }
                }
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }
        return true;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            int scale = 1000;
            float deg = scroller.getCurrX() / (pixelsPerDegree * scale);
            deg = Math.max(MIN_DEG, Math.min(MAX_DEG, deg));
            if (deg != currentDegrees) {
                currentDegrees = deg;
                notifyChanged();
            }
            invalidate();
            if (scroller.isFinished()) {
                snapAndNotify();
            }
        }
    }

    /** Snap currentDegrees to the nearest minor tick and notify. */
    private void snapAndNotify() {
        float snapped = snapToTick(currentDegrees);
        snapped = Math.max(MIN_DEG, Math.min(MAX_DEG, snapped));
        if (snapped != currentDegrees) {
            currentDegrees = snapped;
            notifyChanged();
            invalidate();
        }
    }

    private void notifyChanged() {
        if (listener != null) listener.onRotationChanged(currentDegrees);
    }
}
