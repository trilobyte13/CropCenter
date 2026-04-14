package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Color picker with a tap-to-select grid of colors, alpha slider, and hex input.
 * Designed for quick single-tap selection — one tap picks a color.
 */
public class ColorPickerDialog {

    public interface OnColorSelectedListener {
        void onColorSelected(int color);
    }

    // 8x6 grid: spectrum from red→yellow→green→cyan→blue→magenta, plus grays
    private static final int[] PALETTE = {
        // Row 1: Bright saturated
        0xFFFF0000, 0xFFFF8000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0080FF, 0xFF0000FF, 0xFFFF00FF,
        // Row 2: Medium saturated
        0xFFCC0000, 0xFFCC6600, 0xFFCCCC00, 0xFF00CC00, 0xFF00CCCC, 0xFF0066CC, 0xFF0000CC, 0xFFCC00CC,
        // Row 3: Muted
        0xFF993333, 0xFF996633, 0xFF999933, 0xFF339933, 0xFF339999, 0xFF336699, 0xFF333399, 0xFF993399,
        // Row 4: Light / pastel
        0xFFFF9999, 0xFFFFCC99, 0xFFFFFF99, 0xFF99FF99, 0xFF99FFFF, 0xFF99CCFF, 0xFF9999FF, 0xFFFF99FF,
        // Row 5: Grays + black/white
        0xFFFFFFFF, 0xFFDDDDDD, 0xFFAAAAAA, 0xFF888888, 0xFF555555, 0xFF333333, 0xFF111111, 0xFF000000,
        // Row 6: Semi-transparent versions
        0x80FFFFFF, 0x80FF0000, 0x80FFFF00, 0x8000FF00, 0x8000FFFF, 0x800000FF, 0x80FF00FF, 0x80000000,
    };
    private static final int COLS = 8;
    private static final int ROWS = 6;

    public static void show(Context context, int currentColor, OnColorSelectedListener listener) {
        float density = context.getResources().getDisplayMetrics().density;
        int dp = (int) density;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12 * dp, 8 * dp, 12 * dp, 4 * dp);

        // Preview swatch
        View swatch = new View(context);
        swatch.setBackgroundColor(currentColor);
        root.addView(swatch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 28 * dp));

        // Color grid
        final int[] selected = {currentColor};
        int cellSize = (int)(36 * density);
        ColorGridView grid = new ColorGridView(context, PALETTE, COLS, ROWS, cellSize, currentColor);
        // seekA/txtA declared here so grid lambda can reference them (assigned below)
        final SeekBar[] seekARef = new SeekBar[1];
        final TextView[] txtARef = new TextView[1];
        grid.setOnColorTapListener(color -> {
            int alpha = Color.alpha(color);
            selected[0] = color; // use full color including alpha
            swatch.setBackgroundColor(selected[0]);
            // Sync alpha slider
            if (seekARef[0] != null) seekARef[0].setProgress(alpha);
            if (txtARef[0] != null) txtARef[0].setText(String.valueOf(alpha));
        });
        LinearLayout.LayoutParams gridLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridLP.topMargin = 6 * dp;
        root.addView(grid, gridLP);

        // Alpha slider
        LinearLayout alphaRow = new LinearLayout(context);
        alphaRow.setOrientation(LinearLayout.HORIZONTAL);
        alphaRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblA = new TextView(context);
        lblA.setText("Opacity "); lblA.setTextSize(12); lblA.setTextColor(0xFFA6ADC8);
        alphaRow.addView(lblA);
        SeekBar seekA = new SeekBar(context);
        seekARef[0] = seekA;
        seekA.setMax(255); seekA.setProgress(Color.alpha(currentColor));
        alphaRow.addView(seekA, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView txtA = new TextView(context);
        txtARef[0] = txtA;
        txtA.setText(String.valueOf(Color.alpha(currentColor)));
        txtA.setTextSize(11); txtA.setTextColor(0xFFCBA6F7); txtA.setMinWidth(28 * dp);
        txtA.setGravity(Gravity.END);
        alphaRow.addView(txtA);
        seekA.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fu) {
                selected[0] = (selected[0] & 0x00FFFFFF) | (p << 24);
                txtA.setText(String.valueOf(p));
                swatch.setBackgroundColor(selected[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayout.LayoutParams aLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aLP.topMargin = 6 * dp;
        root.addView(alphaRow, aLP);

        // Hex input
        EditText hexInput = new EditText(context);
        hexInput.setTextSize(12); hexInput.setSingleLine(true);
        hexInput.setText(String.format("#%08X", currentColor));
        hexInput.setGravity(Gravity.CENTER);
        hexInput.setBackgroundColor(0xFF313244); hexInput.setTextColor(0xFFCDD6F4);
        hexInput.setHint("#AARRGGBB");
        LinearLayout.LayoutParams hLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLP.topMargin = 6 * dp;
        root.addView(hexInput, hLP);

        // Hex apply button
        TextView btnApply = new TextView(context);
        btnApply.setText("Apply Hex"); btnApply.setTextSize(12);
        btnApply.setTextColor(0xFFCBA6F7); btnApply.setGravity(Gravity.CENTER);
        btnApply.setPadding(0, 4*dp, 0, 4*dp);
        btnApply.setOnClickListener(v -> {
            try {
                String hex = hexInput.getText().toString().trim();
                if (hex.startsWith("#")) hex = hex.substring(1);
                if (hex.length() == 6) hex = "FF" + hex;
                if (hex.length() == 8) {
                    selected[0] = (int) Long.parseLong(hex, 16);
                    swatch.setBackgroundColor(selected[0]);
                    seekA.setProgress(Color.alpha(selected[0]));
                }
            } catch (NumberFormatException ignored) {}
        });
        root.addView(btnApply);

        new AlertDialog.Builder(context)
            .setTitle("Pick Color")
            .setView(root)
            .setPositiveButton("OK", (d, w) -> listener.onColorSelected(selected[0]))
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Grid view that draws color swatches in a tap-to-select grid. */
    private static class ColorGridView extends View {
        private final int[] colors;
        private final int cols, rows, cellSize;
        private final Paint paint = new Paint();
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int selectedColor;
        private OnColorTapListener listener;

        interface OnColorTapListener { void onTap(int color); }
        void setOnColorTapListener(OnColorTapListener l) { this.listener = l; }

        ColorGridView(Context ctx, int[] colors, int cols, int rows, int cellSize, int selectedColor) {
            super(ctx);
            this.colors = colors; this.cols = cols; this.rows = rows;
            this.cellSize = cellSize; this.selectedColor = selectedColor;
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(3);
            borderPaint.setColor(0xFFCBA6F7);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = cols * cellSize;
            int h = rows * cellSize;
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth() / cols;
            int h = getHeight() / rows;
            for (int i = 0; i < colors.length && i < cols * rows; i++) {
                int col = i % cols, row = i / cols;
                float l = col * w, t = row * h;
                // Checkerboard for transparency
                if (Color.alpha(colors[i]) < 255) {
                    paint.setColor(0xFFCCCCCC);
                    canvas.drawRect(l, t, l + w, t + h, paint);
                    paint.setColor(0xFF999999);
                    canvas.drawRect(l, t, l + w/2f, t + h/2f, paint);
                    canvas.drawRect(l + w/2f, t + h/2f, l + w, t + h, paint);
                }
                paint.setColor(colors[i]);
                canvas.drawRect(l + 1, t + 1, l + w - 1, t + h - 1, paint);
                // Highlight selected
                if (colors[i] == selectedColor) {
                    canvas.drawRect(l + 1, t + 1, l + w - 1, t + h - 1, borderPaint);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                int col = (int)(e.getX() / (getWidth() / (float)cols));
                int row = (int)(e.getY() / (getHeight() / (float)rows));
                int idx = row * cols + col;
                if (idx >= 0 && idx < colors.length) {
                    selectedColor = colors[idx];
                    if (listener != null) listener.onTap(colors[idx]);
                    invalidate();
                }
                return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
