package com.cropcenter.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.widget.OverScroller;
import android.view.View;

/**
 * Galaxy-style scrollable rotation ruler.
 * A wide ruler with tick marks that scrolls horizontally; the center indicator
 * shows the current rotation angle. Supports drag, fling, and snap-to-detent.
 */
public class RotationRulerView extends View {

    public interface OnRotationChangedListener {
        void onRotationChanged(float degrees);
    }

    private static final float MIN_DEG = -180f;
    private static final float MAX_DEG = 180f;
    private static final float[] DETENTS = {-180f, -90f, -45f, 0f, 45f, 90f, 180f};
    private static final float SNAP_THRESHOLD_DEG = 0.8f;

    private float currentDegrees = 0f;
    private float pixelsPerDegree;
    private boolean enabled = true;

    private final Paint minorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint majorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detentTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final OverScroller scroller;
    private VelocityTracker velocityTracker;
    private float lastTouchX;

    private OnRotationChangedListener listener;

    public RotationRulerView(Context context) { this(context, null); }
    public RotationRulerView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public RotationRulerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        float density = context.getResources().getDisplayMetrics().density;
        pixelsPerDegree = 12 * density;

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

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float centerX = w / 2f;
        float labelY = h - 1; // labels at very bottom
        float tickTop = 2;
        float tickBot = h - labelPaint.getTextSize() - 3; // leave room for labels

        float halfVisibleDeg = (centerX / pixelsPerDegree) + 1;
        int iStart = Math.max((int) MIN_DEG, (int) Math.floor(currentDegrees - halfVisibleDeg));
        int iEnd = Math.min((int) MAX_DEG, (int) Math.ceil(currentDegrees + halfVisibleDeg));

        for (int deg = iStart; deg <= iEnd; deg++) {
            float x = centerX + (deg - currentDegrees) * pixelsPerDegree;

            boolean isDetent = false;
            for (float d : DETENTS) if (deg == (int) d) { isDetent = true; break; }

            if (isDetent) {
                canvas.drawLine(x, tickTop, x, tickBot, detentTickPaint);
                canvas.drawText(deg + "\u00B0", x, labelY, labelPaint);
            } else if (deg % 5 == 0) {
                float mid = (tickTop + tickBot) / 2f;
                float halfH = (tickBot - tickTop) * 0.35f;
                canvas.drawLine(x, mid - halfH, x, mid + halfH, majorTickPaint);
                canvas.drawText(String.valueOf(deg), x, labelY, labelPaint);
            } else {
                float mid = (tickTop + tickBot) / 2f;
                float halfH = (tickBot - tickTop) * 0.18f;
                canvas.drawLine(x, mid - halfH, x, mid + halfH, minorTickPaint);
            }
        }

        // Zero marker
        float zeroX = centerX + (0 - currentDegrees) * pixelsPerDegree;
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!enabled) return false;
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true);
                lastTouchX = event.getX();
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            case MotionEvent.ACTION_MOVE -> {
                float dx = event.getX() - lastTouchX;
                lastTouchX = event.getX();
                float newDeg = Math.max(MIN_DEG, Math.min(MAX_DEG,
                        currentDegrees - dx / pixelsPerDegree));
                if (newDeg != currentDegrees) {
                    currentDegrees = newDeg;
                    notifyChanged();
                    invalidate();
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker.computeCurrentVelocity(1000);
                float velX = velocityTracker.getXVelocity();
                velocityTracker.recycle();
                velocityTracker = null;

                if (Math.abs(velX) > 200) {
                    int startX = (int) (currentDegrees * pixelsPerDegree);
                    int minX = (int) (MIN_DEG * pixelsPerDegree);
                    int maxX = (int) (MAX_DEG * pixelsPerDegree);
                    scroller.fling(startX, 0, (int) -velX, 0, minX, maxX, 0, 0);
                    postInvalidateOnAnimation();
                } else {
                    snapToDetent();
                }
            }
        }
        return true;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            float deg = scroller.getCurrX() / pixelsPerDegree;
            deg = Math.max(MIN_DEG, Math.min(MAX_DEG, deg));
            if (deg != currentDegrees) {
                currentDegrees = deg;
                notifyChanged();
            }
            invalidate();
            if (scroller.isFinished()) snapToDetent();
        }
    }

    private void snapToDetent() {
        for (float d : DETENTS) {
            if (Math.abs(currentDegrees - d) <= SNAP_THRESHOLD_DEG) {
                currentDegrees = d;
                notifyChanged();
                invalidate();
                return;
            }
        }
    }

    private void notifyChanged() {
        if (listener != null) listener.onRotationChanged(currentDegrees);
    }
}
