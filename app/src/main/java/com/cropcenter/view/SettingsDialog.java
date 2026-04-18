package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.cropcenter.model.GridConfig;

/**
 * Unified settings panel — grid customization, pixel grid customization,
 * and shared selection/paint color.
 *
 * Uses Catppuccin Mocha theme colors consistently throughout.
 */
public class SettingsDialog {

    public interface OnChangedListener { void onChanged(); }

    // ── Theme colors (Catppuccin Mocha) ──
    private static final int BG_BASE      = 0xFF1E1E2E;
    private static final int BG_SURFACE0  = 0xFF313244;
    private static final int BG_SURFACE1  = 0xFF45475A;
    private static final int COLOR_TEXT   = 0xFFCDD6F4;
    private static final int COLOR_SUB    = 0xFFA6ADC8;
    private static final int COLOR_OVER   = 0xFF6C7086;
    private static final int ACCENT_MAUVE = 0xFFCBA6F7;

    public static void show(Context ctx, GridConfig cfg, OnChangedListener onChange) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int dp2 = (int)(2 * density);
        int dp4 = (int)(4 * density);
        int dp6 = (int)(6 * density);
        int dp8 = (int)(8 * density);
        int dp12 = (int)(12 * density);
        int dp16 = (int)(16 * density);

        // Outer container with scroll for small screens
        ScrollView scroll = new ScrollView(ctx);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp16, dp8, dp16, dp8);
        scroll.addView(root);

        // ─── GRID card ───
        LinearLayout gridCard = newCard(ctx, density);
        addCardTitle(gridCard, "Grid");

        // Cols × Rows
        LinearLayout crRow = row(ctx);
        EditText editCols = numInput(ctx, String.valueOf(cfg.columns), density);
        EditText editRows = numInput(ctx, String.valueOf(cfg.rows), density);
        addLabel(crRow, "Columns");
        LinearLayout.LayoutParams colsLP = new LinearLayout.LayoutParams(
                (int)(48*density), (int)(30*density));
        colsLP.leftMargin = dp8;
        crRow.addView(editCols, colsLP);
        TextView times = new TextView(ctx);
        times.setText("  \u00D7  "); times.setTextColor(COLOR_SUB); times.setTextSize(13);
        crRow.addView(times);
        addLabel(crRow, "Rows");
        LinearLayout.LayoutParams rowsLP = new LinearLayout.LayoutParams(
                (int)(48*density), (int)(30*density));
        rowsLP.leftMargin = dp8;
        crRow.addView(editRows, rowsLP);
        gridCard.addView(crRow, topMargin(dp6));

        // Presets 2×2 through 8×8 — equal-weight chips that divide available width
        int[][] presets = {{2,2},{3,3},{4,4},{5,5},{6,6},{7,7},{8,8}};
        LinearLayout presetRow = new LinearLayout(ctx);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < presets.length; i++) {
            final int c = presets[i][0], r = presets[i][1];
            TextView btn = chipButton(ctx, c + "\u00D7" + r, density);
            btn.setOnClickListener(v -> {
                cfg.columns = c; cfg.rows = r;
                editCols.setText(String.valueOf(c)); editRows.setText(String.valueOf(r));
                onChange.onChanged();
            });
            // layout_weight=1 with width=0 → each chip gets equal share of row width
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp4;
            presetRow.addView(btn, lp);
        }
        gridCard.addView(presetRow, topMargin(dp8));

        gridCard.addView(colorRow(ctx, "Line color", cfg.color, density, color -> {
            cfg.color = color; onChange.onChanged();
        }), topMargin(dp12));

        LinearLayout wRow = row(ctx);
        addLabel(wRow, "Width");
        SeekBar wSeek = new SeekBar(ctx); wSeek.setMax(20); wSeek.setProgress((int)cfg.lineWidth);
        TextView txtW = valueChip(ctx, String.valueOf((int)cfg.lineWidth), density);
        wSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fu) {
                cfg.lineWidth = Math.max(1, p); txtW.setText(String.valueOf(Math.max(1, p)));
                onChange.onChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayout.LayoutParams seekLP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        seekLP.leftMargin = dp8; seekLP.rightMargin = dp8;
        wRow.addView(wSeek, seekLP);
        wRow.addView(txtW);
        gridCard.addView(wRow, topMargin(dp8));

        root.addView(gridCard, topMargin(dp4));

        // ─── PIXEL GRID card ───
        LinearLayout pixelCard = newCard(ctx, density);
        addCardTitle(pixelCard, "Pixel Grid");

        android.widget.CheckBox chkPixel = new android.widget.CheckBox(ctx);
        chkPixel.setText("Show at 6\u00D7 zoom or higher");
        chkPixel.setTextSize(12);
        chkPixel.setTextColor(COLOR_TEXT);
        chkPixel.setChecked(cfg.showPixelGrid);
        chkPixel.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT_MAUVE));
        chkPixel.setOnCheckedChangeListener((b, c) -> {
            cfg.showPixelGrid = c; onChange.onChanged();
        });
        pixelCard.addView(chkPixel, topMargin(dp4));

        pixelCard.addView(colorRow(ctx, "Color", cfg.pixelGridColor, density, color -> {
            cfg.pixelGridColor = color; onChange.onChanged();
        }), topMargin(dp8));

        root.addView(pixelCard, topMargin(dp8));

        // ─── SELECTION / PAINT card ───
        LinearLayout selCard = newCard(ctx, density);
        addCardTitle(selCard, "Selection & Paint");

        TextView selNote = new TextView(ctx);
        selNote.setText("Color for selection points, polygon fill, and horizon paint.");
        selNote.setTextSize(11); selNote.setTextColor(COLOR_OVER);
        selCard.addView(selNote, topMargin(dp4));

        selCard.addView(colorRow(ctx, "Color", cfg.selectionColor, density,
                ColorPickerDialog.PALETTE_TRANSLUCENT, color -> {
            cfg.selectionColor = color; onChange.onChanged();
        }), topMargin(dp8));

        root.addView(selCard, topMargin(dp8));

        Runnable apply = () -> {
            try {
                cfg.columns = Math.clamp(Integer.parseInt(editCols.getText().toString().trim()), 1, 50);
                cfg.rows = Math.clamp(Integer.parseInt(editRows.getText().toString().trim()), 1, 50);
                onChange.onChanged();
            } catch (NumberFormatException ignored) {}
        };

        new AlertDialog.Builder(ctx)
                .setTitle("Settings")
                .setView(scroll)
                .setPositiveButton("Done", (d, w) -> apply.run())
                .show();
    }

    // ── UI building helpers ──

    /** Card container with rounded background. */
    private static LinearLayout newCard(Context ctx, float density) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_SURFACE0);
        bg.setCornerRadius(8 * density);
        card.setBackground(bg);
        int pad = (int)(12 * density);
        card.setPadding(pad, (int)(10*density), pad, (int)(10*density));
        return card;
    }

    private static void addCardTitle(LinearLayout card, String text) {
        TextView tv = new TextView(card.getContext());
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(ACCENT_MAUVE);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(tv);
    }

    private static LinearLayout colorRow(Context ctx, String label, int currentColor,
                                          float density, ColorPickerDialog.OnColorSelectedListener onPick) {
        return colorRow(ctx, label, currentColor, density, ColorPickerDialog.PALETTE_OPAQUE, onPick);
    }

    private static LinearLayout colorRow(Context ctx, String label, int currentColor,
                                          float density, int[] palette,
                                          ColorPickerDialog.OnColorSelectedListener onPick) {
        int swatchSize = (int)(26 * density);
        final int[] tracked = {currentColor};
        LinearLayout row = row(ctx);
        TextView lbl = new TextView(ctx);
        lbl.setText(label); lbl.setTextSize(13); lbl.setTextColor(COLOR_TEXT);
        row.addView(lbl);

        View spacer = new View(ctx);
        LinearLayout.LayoutParams spLP = new LinearLayout.LayoutParams(0, 1, 1f);
        row.addView(spacer, spLP);

        // Swatch with rounded corners + subtle border
        View swatch = new View(ctx);
        GradientDrawable swatchBg = new GradientDrawable();
        swatchBg.setColor(currentColor);
        swatchBg.setCornerRadius(4 * density);
        swatchBg.setStroke(1, BG_SURFACE1);
        swatch.setBackground(swatchBg);
        row.addView(swatch, new LinearLayout.LayoutParams(swatchSize, swatchSize));

        // Edit text-button
        TextView btn = new TextView(ctx);
        btn.setText("Edit"); btn.setTextSize(12); btn.setTextColor(ACCENT_MAUVE);
        btn.setPadding((int)(10*density), (int)(6*density), (int)(10*density), (int)(6*density));
        btn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLP.leftMargin = (int)(8*density);
        row.addView(btn, btnLP);

        View.OnClickListener openPicker = v ->
                ColorPickerDialog.show(ctx, tracked[0], palette, color -> {
                    tracked[0] = color;
                    swatchBg.setColor(color);
                    swatch.invalidate();
                    onPick.onColorSelected(color);
                });
        btn.setOnClickListener(openPicker);
        swatch.setOnClickListener(openPicker);
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
        tv.setText(text); tv.setTextSize(13); tv.setTextColor(COLOR_SUB);
        p.addView(tv);
    }

    /** Compact chip-style preset button (used with layout_weight in rows). */
    private static TextView chipButton(Context ctx, String text, float density) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(11); btn.setTextColor(COLOR_SUB);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_SURFACE1);
        bg.setCornerRadius(4 * density);
        btn.setBackground(bg);
        btn.setPadding(0, (int)(6 * density), 0, (int)(6 * density));
        btn.setSingleLine(true);
        return btn;
    }

    /** Small rounded readout chip for numeric values. */
    private static TextView valueChip(Context ctx, String text, float density) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12); tv.setTextColor(ACCENT_MAUVE);
        tv.setGravity(Gravity.CENTER);
        tv.setMinWidth((int)(32 * density));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_SURFACE1);
        bg.setCornerRadius(4 * density);
        tv.setBackground(bg);
        tv.setPadding((int)(6*density), (int)(3*density), (int)(6*density), (int)(3*density));
        return tv;
    }

    private static EditText numInput(Context ctx, String val, float density) {
        EditText e = new EditText(ctx);
        e.setText(val); e.setTextSize(13); e.setGravity(Gravity.CENTER); e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setTextColor(COLOR_TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_SURFACE1);
        bg.setCornerRadius(4 * density);
        e.setBackground(bg);
        int pad = (int)(4 * density);
        e.setPadding(pad, pad, pad, pad);
        return e;
    }

    private static LinearLayout.LayoutParams topMargin(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = m;
        return lp;
    }
}
