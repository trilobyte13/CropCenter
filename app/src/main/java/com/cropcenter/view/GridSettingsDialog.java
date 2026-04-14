package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.cropcenter.model.GridConfig;

public class GridSettingsDialog {

    public interface OnChangedListener { void onGridChanged(); }

    public static void show(Context ctx, GridConfig cfg, OnChangedListener onChange) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int dp8 = (int)(8 * density);
        int dp24 = (int)(24 * density);
        int dp44 = (int)(44 * density);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp24, (int)(12*density), dp24, dp8);

        // ── Cols / Rows ──
        LinearLayout crRow = row(ctx);
        EditText editCols = numInput(ctx, String.valueOf(cfg.columns), dp44);
        EditText editRows = numInput(ctx, String.valueOf(cfg.rows), dp44);
        addLabel(crRow, "Cols "); crRow.addView(editCols, fixed(dp44));
        addLabel(crRow, "   Rows "); crRow.addView(editRows, fixed(dp44));
        root.addView(crRow);

        // ── Presets ──
        LinearLayout pRow = row(ctx);
        String[] pLabels = {"2x2", "3x3", "4x4", "5x5"};
        int[][] pVals = {{2,2},{3,3},{4,4},{5,5}};
        for (int i = 0; i < pLabels.length; i++) {
            final int c = pVals[i][0], r = pVals[i][1];
            TextView btn = new TextView(ctx);
            btn.setText(pLabels[i]); btn.setTextSize(12); btn.setGravity(Gravity.CENTER);
            btn.setBackgroundColor(0xFF313244); btn.setTextColor(0xFFA6ADC8);
            btn.setPadding(dp8, (int)(4*density), dp8, (int)(4*density));
            btn.setOnClickListener(v -> {
                cfg.columns = c; cfg.rows = r;
                editCols.setText(String.valueOf(c)); editRows.setText(String.valueOf(r));
                onChange.onGridChanged();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = (int)(4*density);
            pRow.addView(btn, lp);
        }
        LinearLayout.LayoutParams pLP = matchWrap(); pLP.topMargin = dp8;
        root.addView(pRow, pLP);

        // ── Grid Color ──
        root.addView(colorRow(ctx, "Grid Color", cfg.color, density, color -> {
            cfg.color = color; onChange.onGridChanged();
        }), topMargin(dp8));

        // ── Line Width ──
        LinearLayout wRow = row(ctx);
        addLabel(wRow, "Width ");
        TextView txtW = new TextView(ctx);
        txtW.setText(String.valueOf((int)cfg.lineWidth)); txtW.setTextSize(12);
        txtW.setTextColor(0xFFCBA6F7); txtW.setMinWidth((int)(24*density));
        txtW.setGravity(Gravity.END);
        SeekBar seek = new SeekBar(ctx); seek.setMax(20); seek.setProgress((int)cfg.lineWidth);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fu) {
                cfg.lineWidth = Math.max(1, p); txtW.setText(String.valueOf(Math.max(1, p)));
                onChange.onGridChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        wRow.addView(seek, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        wRow.addView(txtW);
        root.addView(wRow, topMargin((int)(6*density)));

        Runnable apply = () -> {
            try {
                cfg.columns = clamp(Integer.parseInt(editCols.getText().toString().trim()), 1, 50);
                cfg.rows = clamp(Integer.parseInt(editRows.getText().toString().trim()), 1, 50);
                onChange.onGridChanged();
            } catch (NumberFormatException ignored) {}
        };

        new AlertDialog.Builder(ctx)
                .setTitle("Grid Settings")
                .setView(root)
                .setPositiveButton("Done", (d, w) -> apply.run())
                .show();
    }

    /** A color row: label + small swatch + "Change" button that opens color picker. */
    private static LinearLayout colorRow(Context ctx, String label, int currentColor,
                                          float density, ColorPickerDialog.OnColorSelectedListener onPick) {
        int swatchSize = (int)(22 * density);
        final int[] tracked = {currentColor}; // mutable so re-open shows last pick
        LinearLayout row = row(ctx);
        addLabel(row, label + "  ");
        View swatch = new View(ctx);
        swatch.setBackgroundColor(currentColor);
        row.addView(swatch, new LinearLayout.LayoutParams(swatchSize, swatchSize));
        TextView btn = new TextView(ctx);
        btn.setText("  Change"); btn.setTextSize(12); btn.setTextColor(0xFFCBA6F7);
        btn.setOnClickListener(v ->
                ColorPickerDialog.show(ctx, tracked[0], color -> {
                    tracked[0] = color;
                    swatch.setBackgroundColor(color);
                    onPick.onColorSelected(color);
                }));
        row.addView(btn);
        return row;
    }

    private static LinearLayout row(Context ctx) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }
    private static void addLabel(LinearLayout p, String text) {
        TextView tv = new TextView(p.getContext());
        tv.setText(text); tv.setTextSize(13); tv.setTextColor(0xFFA6ADC8); p.addView(tv);
    }
    private static EditText numInput(Context ctx, String val, int widthPx) {
        EditText e = new EditText(ctx);
        e.setText(val); e.setTextSize(13); e.setGravity(Gravity.CENTER); e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setBackgroundColor(0xFF313244); e.setTextColor(0xFFCDD6F4);
        return e;
    }
    private static LinearLayout.LayoutParams fixed(int w) {
        return new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
    private static LinearLayout.LayoutParams topMargin(int m) {
        LinearLayout.LayoutParams lp = matchWrap(); lp.topMargin = m; return lp;
    }
    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
