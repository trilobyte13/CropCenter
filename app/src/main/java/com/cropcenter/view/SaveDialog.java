package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cropcenter.model.ExportConfig;
import com.cropcenter.model.GridConfig;

/**
 * Pre-save dialog: output format (JPEG/PNG) and grid-bake toggle. The filename
 * and target directory are chosen by the SAF picker that follows — this dialog
 * is just the format/options step.
 * Matches the Settings dialog visual style (Catppuccin Mocha cards).
 */
public class SaveDialog {

    public interface OnSaveListener { void onSave(); }

    // ── Theme colors ──
    private static final int BG_SURFACE0  = 0xFF313244;
    private static final int BG_SURFACE1  = 0xFF45475A;
    private static final int COLOR_TEXT   = 0xFFCDD6F4;
    private static final int ACCENT_MAUVE = 0xFFCBA6F7;

    public static void show(Context ctx, ExportConfig export, GridConfig grid,
                            OnSaveListener onSave) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int dp4 = (int)(4 * density);
        int dp8 = (int)(8 * density);
        int dp16 = (int)(16 * density);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp16, dp8, dp16, dp8);

        // ─── FORMAT card ───
        LinearLayout fmtCard = newCard(ctx, density);
        addCardTitle(fmtCard, "Format");

        LinearLayout fmtRow = new LinearLayout(ctx);
        fmtRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView jpegBtn = formatChip(ctx, "JPEG", density);
        final TextView pngBtn  = formatChip(ctx, "PNG", density);
        final boolean[] isJpeg = { "jpeg".equals(export.format) };

        Runnable updateFormatHighlight = () -> {
            GradientDrawable jbg = new GradientDrawable();
            jbg.setColor(isJpeg[0] ? ACCENT_MAUVE : BG_SURFACE1);
            jbg.setCornerRadius(4 * density);
            jpegBtn.setBackground(jbg);
            jpegBtn.setTextColor(isJpeg[0] ? 0xFF11111B : COLOR_TEXT);

            GradientDrawable pbg = new GradientDrawable();
            pbg.setColor(!isJpeg[0] ? ACCENT_MAUVE : BG_SURFACE1);
            pbg.setCornerRadius(4 * density);
            pngBtn.setBackground(pbg);
            pngBtn.setTextColor(!isJpeg[0] ? 0xFF11111B : COLOR_TEXT);
        };
        updateFormatHighlight.run();

        jpegBtn.setOnClickListener(v -> { isJpeg[0] = true; updateFormatHighlight.run(); });
        pngBtn.setOnClickListener(v -> { isJpeg[0] = false; updateFormatHighlight.run(); });

        LinearLayout.LayoutParams jLP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams pLP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pLP.leftMargin = dp8;
        fmtRow.addView(jpegBtn, jLP);
        fmtRow.addView(pngBtn, pLP);
        fmtCard.addView(fmtRow, topMargin(dp8));

        root.addView(fmtCard, topMargin(dp4));

        // ─── OPTIONS card ───
        LinearLayout optCard = newCard(ctx, density);
        addCardTitle(optCard, "Options");

        CheckBox chkBake = new CheckBox(ctx);
        chkBake.setText("Export Grid");
        chkBake.setTextSize(12);
        chkBake.setTextColor(COLOR_TEXT);
        chkBake.setChecked(grid.includeInExport);
        chkBake.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT_MAUVE));
        optCard.addView(chkBake, topMargin(dp4));

        root.addView(optCard, topMargin(dp8));

        Runnable applySettings = () -> {
            export.format = isJpeg[0] ? "jpeg" : "png";
            grid.includeInExport = chkBake.isChecked();
        };

        new AlertDialog.Builder(ctx)
                .setTitle("Save Image")
                .setView(root)
                .setPositiveButton("Continue", (d, w) -> {
                    applySettings.run();
                    onSave.onSave();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── UI helpers (shared visual language with SettingsDialog) ──

    private static LinearLayout newCard(Context ctx, float density) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_SURFACE0);
        bg.setCornerRadius(8 * density);
        card.setBackground(bg);
        int pad = (int)(12 * density);
        card.setPadding(pad, (int)(10 * density), pad, (int)(10 * density));
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

    private static TextView formatChip(Context ctx, String text, float density) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(btn.getTypeface(), android.graphics.Typeface.BOLD);
        btn.setPadding(0, (int)(8 * density), 0, (int)(8 * density));
        btn.setSingleLine(true);
        return btn;
    }

    private static LinearLayout.LayoutParams topMargin(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = m;
        return lp;
    }
}
