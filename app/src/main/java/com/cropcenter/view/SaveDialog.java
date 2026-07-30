package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cropcenter.model.CropState;
import com.cropcenter.model.Format;
import com.cropcenter.util.DpToPx;

/**
 * Legacy (pre-MES) save options dialog — the primary Save flow uses FolderPickerDialog when MANAGE_EXTERNAL_STORAGE is
 * granted; this dialog is the fallback when it isn't. Output format (JPEG/PNG) and grid-bake toggle live here; the
 * filename and target directory are chosen by the SAF picker that follows. Matches the Settings dialog visual style
 * (Catppuccin Mocha cards).
 */
public final class SaveDialog
{
	/**
	 * Fires when the user confirms a save (taps the dialog's positive button). The dialog has already updated the
	 * supplied CropState's exportConfig / gridConfig with any user-toggled options before invoking this — the
	 * listener just needs to kick off the actual save pipeline.
	 */
	public interface OnSaveListener
	{
		void onSave();
	}

	/**
	 * Build and show the save dialog (format JPEG/PNG, bake-grid toggle, export config); onSave fires on confirm,
	 * onCancel on every cancel path.
	 *
	 * State mutation: applySettings commits the user's choices to CropState BEFORE onSave fires (the following SAF
	 * picker needs the format-aware extension already on state), so a SAF cancel after Continue would bake the
	 * choices into the next save unless rolled back — SaveController owns that via a snapshot taken before this
	 * call and restored on every abort path.
	 *
	 * Returns the AlertDialog so the caller registers it with SaveHost.registerTransientDialog: a Share/View intent
	 * or graft arriving mid-dialog must dismiss it before bg state.reset(), else applySettings's commits for image
	 * A would land on image B.
	 *
	 * onCancel fires on Cancel (routed through dialog.cancel() so OnCancelListener is the single source of truth),
	 * back-press, outside-touch, and forced dismissTransientDialogs — SaveController uses it to clear priorSnapshot
	 * so an abandoned dialog doesn't pin the source bitmap. It does NOT fire on Continue (onSave committed, and the
	 * SAF abort paths own the rollback).
	 *
	 * @param ctx      Activity context for inflation
	 * @param state    CropState mutated with format / grid choices before onSave — caller snapshots + rolls back
	 * @param onSave   invoked on the positive-button click
	 * @param onCancel invoked on every cancel path; SaveController uses it to clear priorSnapshot
	 * @return the shown AlertDialog (caller registers it with the host's transient-dialog tracker)
	 */
	public static AlertDialog show(Context ctx, CropState state, OnSaveListener onSave, Runnable onCancel)
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

		CheckBox chkBake = DialogCards.newMauveCheckBox(ctx, "Export Grid",
			state.getGridConfig().includeInExport());
		root.addView(buildOptionsCard(ctx, density, chkBake), DialogCards.topMargin(dp8));

		return new AlertDialog.Builder(ctx)
			.setTitle("Save Image")
			.setView(root)
			.setPositiveButton("Continue", (dialog, which) ->
			{
				applySettings(state, isJpeg[0], chkBake.isChecked());
				onSave.onSave();
			})
			// Negative button calls dialog.cancel() so the OnCancelListener below is the single source of
			// truth for the abandon path — back-press / outside-touch / forced cancel all funnel through
			// the same listener instead of needing duplicated cleanup.
			.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> dialog.cancel())
			.setOnCancelListener(dialog -> onCancel.run())
			.show();
	}

	/**
	 * Commit the dialog's selections to CropState — one updateExportConfig for format, one updateGridConfig for the
	 * export-grid toggle.
	 *
	 * @param state    CropState to mutate; receives the format + grid-include updates
	 * @param isJpeg   true to commit JPEG, false to commit PNG (the dialog's format chip state)
	 * @param bakeGrid the Export Grid checkbox state to commit
	 */
	private static void applySettings(CropState state, boolean isJpeg, boolean bakeGrid)
	{
		state.updateExportConfig(c ->
			c.withFormat(isJpeg ? Format.JPEG : Format.PNG));
		state.updateGridConfig(g -> g.withIncludeInExport(bakeGrid));
	}

	/**
	 * Build the "Format" card — two equal-weight toggle chips for JPEG / PNG.
	 *
	 * @param ctx     dialog context for view construction
	 * @param density display density for dp→px sizing
	 * @param isJpeg  single-element out-box: chip taps write the current selection to isJpeg[0]; the
	 *                caller reads it on OK
	 * @return the assembled card, ready to add to the dialog root
	 */
	private static LinearLayout buildFormatCard(Context ctx, float density, boolean[] isJpeg)
	{
		int dp8 = DpToPx.toPx(8, density);
		LinearLayout card = DialogCards.newCard(ctx, density);
		DialogCards.addCardTitle(card, "Format");

		LinearLayout formatRow = new LinearLayout(ctx);
		formatRow.setOrientation(LinearLayout.HORIZONTAL);
		final TextView jpegBtn = DialogCards.newToggleChip(ctx, "JPEG", density, 8);
		final TextView pngBtn = DialogCards.newToggleChip(ctx, "PNG", density, 8);

		// 4dp corners match this dialog's tighter card styling (FolderPickerDialog uses 6dp pills).
		Runnable updateFormatHighlight = () ->
			DialogCards.styleChipPair(jpegBtn, pngBtn, isJpeg[0], density, 4);
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
		formatRow.addView(jpegBtn, jpegBtnLayoutParams);
		formatRow.addView(pngBtn, pngBtnLayoutParams);
		card.addView(formatRow, DialogCards.topMargin(dp8));
		return card;
	}

	/**
	 * Build the "Options" card — single checkbox for baking the grid into the export.
	 *
	 * @param ctx     dialog context for view construction
	 * @param density display density for dp→px sizing
	 * @param chkBake caller-owned pre-configured CheckBox added to the card; the caller reads its checked
	 *                state on OK
	 * @return the assembled card, ready to add to the dialog root
	 */
	private static LinearLayout buildOptionsCard(Context ctx, float density, CheckBox chkBake)
	{
		int dp4 = DpToPx.toPx(4, density);
		LinearLayout card = DialogCards.newCard(ctx, density);
		DialogCards.addCardTitle(card, "Options");
		card.addView(chkBake, DialogCards.topMargin(dp4));
		return card;
	}
}
