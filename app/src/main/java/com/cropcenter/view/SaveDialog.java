package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cropcenter.model.CropState;
import com.cropcenter.model.Format;
import com.cropcenter.util.DpToPx;
import com.cropcenter.util.ThemeColors;

/**
 * Pre-save dialog: output format (JPEG/PNG) and grid-bake toggle. The filename and target directory are chosen by the
 * SAF picker that follows — this dialog is just the format/options step. Matches the Settings dialog visual style
 * (Catppuccin Mocha cards).
 */
public class SaveDialog
{
	/**
	 * Fires when the user confirms a save (taps the dialog's positive button). The dialog has already updated the
	 * supplied CropState's exportConfig / gridConfig with any user-toggled options before invoking this — the
	 * listener just needs to kick off the actual save pipeline.
	 */
	public interface OnSaveListener
	{
		/**
		 * Invoked once when the user confirms the save dialog.
		 */
		void onSave();
	}

	/**
	 * Build and show the save dialog. The user can pick the output format (JPEG / PNG), toggle the bake-grid
	 * option, and tweak related export config; on confirmation, onSave is invoked. Cancellation does nothing.
	 *
	 * @param ctx    Activity context for inflation; the dialog uses Android's AlertDialog.Builder
	 * @param state  CropState mutated in-place with the user's format / grid choices before onSave fires
	 * @param onSave invoked on the positive-button click
	 */
	public static void show(Context ctx, CropState state, OnSaveListener onSave)
	{
		float density = ctx.getResources().getDisplayMetrics().density;
		int dp4 = DpToPx.toPx(4, density);
		int dp8 = DpToPx.toPx(8, density);
		int dp16 = DpToPx.toPx(16, density);

		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp16, dp8, dp16, dp8);

		final boolean[] isJpeg = { state.getExportConfig().format() == Format.JPEG };
		root.addView(buildFormatCard(ctx, density, isJpeg), DialogCards.topMargin(dp4));

		CheckBox chkBake = new CheckBox(ctx);
		root.addView(buildOptionsCard(ctx, state, density, chkBake), DialogCards.topMargin(dp8));

		new AlertDialog.Builder(ctx)
			.setTitle("Save Image")
			.setView(root)
			.setPositiveButton("Continue", (dialog, which) ->
			{
				applySettings(state, isJpeg[0], chkBake.isChecked());
				onSave.onSave();
			})
			.setNegativeButton("Cancel", null)
			.show();
	}

	/**
	 * Build the "Format" card — two equal-weight toggle chips for JPEG / PNG. Writes to `isJpeg[0]` as the user
	 * taps; the caller reads that on OK.
	 */
	private static LinearLayout buildFormatCard(Context ctx, float density, boolean[] isJpeg)
	{
		int dp8 = DpToPx.toPx(8, density);
		LinearLayout card = DialogCards.newCard(ctx, density);
		DialogCards.addCardTitle(card, "Format");

		LinearLayout fmtRow = new LinearLayout(ctx);
		fmtRow.setOrientation(LinearLayout.HORIZONTAL);
		final TextView jpegBtn = formatChip(ctx, "JPEG", density);
		final TextView pngBtn = formatChip(ctx, "PNG", density);

		Runnable updateFormatHighlight = () -> applyFormatChipStyle(jpegBtn, pngBtn, isJpeg[0], density);
		updateFormatHighlight.run();

		jpegBtn.setOnClickListener(view ->
		{
			isJpeg[0] = true;
			updateFormatHighlight.run();
		});
		pngBtn.setOnClickListener(view ->
		{
			isJpeg[0] = false;
			updateFormatHighlight.run();
		});

		LinearLayout.LayoutParams jpegBtnLayoutParams = new LinearLayout.LayoutParams(
			0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams pngBtnLayoutParams = new LinearLayout.LayoutParams(
			0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
		pngBtnLayoutParams.leftMargin = dp8;
		fmtRow.addView(jpegBtn, jpegBtnLayoutParams);
		fmtRow.addView(pngBtn, pngBtnLayoutParams);
		card.addView(fmtRow, DialogCards.topMargin(dp8));
		return card;
	}

	/**
	 * Build the "Options" card — single checkbox for baking the grid into the export. The passed-in CheckBox is
	 * configured in place and added to the card; caller reads its checked state on OK.
	 */
	private static LinearLayout buildOptionsCard(Context ctx, CropState state, float density, CheckBox chkBake)
	{
		int dp4 = DpToPx.toPx(4, density);
		LinearLayout card = DialogCards.newCard(ctx, density);
		DialogCards.addCardTitle(card, "Options");

		chkBake.setText("Export Grid");
		chkBake.setTextSize(12);
		chkBake.setTextColor(ThemeColors.TEXT);
		chkBake.setChecked(state.getGridConfig().includeInExport());
		chkBake.setButtonTintList(ColorStateList.valueOf(ThemeColors.MAUVE));
		card.addView(chkBake, DialogCards.topMargin(dp4));
		return card;
	}

	/**
	 * Apply highlight styling to the two format chips based on which is selected. Selected chip gets mauve
	 * background + crust text; unselected gets surface1 bg + default text.
	 */
	private static void applyFormatChipStyle(TextView jpegBtn, TextView pngBtn, boolean jpegSelected, float density)
	{
		GradientDrawable jpegBg = new GradientDrawable();
		jpegBg.setColor(jpegSelected ? ThemeColors.MAUVE : ThemeColors.SURFACE1);
		jpegBg.setCornerRadius(4 * density);
		jpegBtn.setBackground(jpegBg);
		jpegBtn.setTextColor(jpegSelected ? ThemeColors.CRUST : ThemeColors.TEXT);

		GradientDrawable pngBg = new GradientDrawable();
		pngBg.setColor(!jpegSelected ? ThemeColors.MAUVE : ThemeColors.SURFACE1);
		pngBg.setCornerRadius(4 * density);
		pngBtn.setBackground(pngBg);
		pngBtn.setTextColor(!jpegSelected ? ThemeColors.CRUST : ThemeColors.TEXT);
	}

	/**
	 * Commit the dialog's selections to CropState — one updateExportConfig for format, one updateGridConfig for the
	 * export-grid toggle.
	 */
	private static void applySettings(CropState state, boolean isJpeg, boolean bakeGrid)
	{
		state.updateExportConfig(c ->
			c.withFormat(isJpeg ? Format.JPEG : Format.PNG));
		state.updateGridConfig(g -> g.withIncludeInExport(bakeGrid));
	}

	// ── UI helpers (shared visual language with SettingsDialog lives in DialogCards) ──

	private static TextView formatChip(Context ctx, String text, float density)
	{
		TextView btn = new TextView(ctx);
		btn.setText(text);
		btn.setTextSize(13);
		btn.setGravity(Gravity.CENTER);
		btn.setTypeface(btn.getTypeface(), Typeface.BOLD);
		btn.setPadding(0, DpToPx.toPx(8, density), 0, DpToPx.toPx(8, density));
		btn.setSingleLine(true);
		return btn;
	}
}
