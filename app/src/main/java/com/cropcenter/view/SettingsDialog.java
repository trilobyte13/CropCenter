package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.cropcenter.BuildConfig;
import com.cropcenter.model.CropState;
import com.cropcenter.model.GridConfig;
import com.cropcenter.util.DpToPx;
import com.cropcenter.util.StoragePermissionHelper;
import com.cropcenter.util.ThemeColors;

/**
 * Unified settings panel — grid customization, pixel grid customization, and shared selection/paint color. Uses the
 * shared Catppuccin Mocha palette throughout.
 *
 * All mutations flow through CropState.updateGridConfig so each user action fires the state listener exactly once —
 * callers don't need to pass an invalidate callback.
 */
public final class SettingsDialog
{
	// Active color picker tracked across all colorRow swatches so a forced parent cancellation cancels
	// the open picker too, and a user picking a new swatch dismisses the prior picker first. UI-thread
	// only — cleared by the picker's OnDismissListener.
	private static AlertDialog activePicker;

	/**
	 * Dismiss any open ColorPickerDialog and clear the tracker. Called by the parent SettingsDialog's
	 * OnCancelListener so the picker's OK button can't mutate gridConfig after the parent is gone, and
	 * by MainActivity.onDestroy so a config-change destroy that races a still-showing picker doesn't
	 * pin the destroyed Activity's window via the static field. Snapshot the field before clearing so a
	 * re-entrant cancel from picker.cancel()'s own OnCancelListener doesn't see a half-cleared state.
	 */
	public static void cancelActivePicker()
	{
		AlertDialog picker = activePicker;
		activePicker = null;
		if (picker != null && picker.isShowing())
		{
			try
			{
				picker.cancel();
			}
			catch (RuntimeException ignored)
			{
				// Picker's window was already destroyed (Activity-destroyed-mid-cancel race); the field
				// is already cleared above, nothing more to do.
			}
		}
	}

	/**
	 * Build and show the settings dialog. Card-style layout: Grid (cols/rows + line color/width), Pixel Grid
	 * (toggle + color), Selection & Paint (shared color), and a Build info card. Toggles and color-picker
	 * selections commit immediately; Cols / Rows EditTexts are deferred to the "Done" button.
	 *
	 * Returns the AlertDialog so the caller can track it for forced dismissal — a Share/View intent that
	 * arrives mid-dialog runs state.reset() on bg, racing the user's in-dialog commits.
	 *
	 * Both OnCancel AND OnDismiss cancel any open ColorPickerDialog: the picker is a separate AlertDialog
	 * that mutates state.gridConfig through its own OK button, so without forced cancellation a stale
	 * picker outliving SettingsDialog could keep applying colors to the new image's state. Cancel covers
	 * user-initiated cancels; dismiss covers Done, the Activity-destroyed-at-config-change path, and any
	 * future dialog-API dismissal — all of which reach dismiss-but-not-cancel.
	 *
	 * AlertDialog.setOnDismissListener replaces rather than chains, so SettingsDialog installs one
	 * listener that calls cancelActivePicker AND then hostDismissListener — a caller cannot install
	 * its own dismiss listener without clobbering the picker cleanup.
	 *
	 * @param ctx                 Activity context for inflation
	 * @param state               CropState whose gridConfig is mutated as the user interacts
	 * @param permissions         storage-permission helper — used to render the "All files access"
	 *                            row that lets the user grant MANAGE_EXTERNAL_STORAGE without first
	 *                            triggering a save-time collision. Pass null to omit the row (host
	 *                            doesn't expose the permission surface)
	 * @param hostDismissListener additional cleanup composed with cancelActivePicker on dismiss; may
	 *                            be null
	 * @return the shown AlertDialog (caller tracks it for cross-load dismissal)
	 */
	public static AlertDialog show(Context ctx, CropState state, StoragePermissionHelper permissions,
		DialogInterface.OnDismissListener hostDismissListener)
	{
		// Dismiss any prior picker before resetting the tracker. A stale activePicker can outlive its
		// parent Activity when the previous SettingsDialog closed without its dismiss listener firing
		// (config change mid-picker, torn-down path) — the static field would hold the destroyed
		// Activity's window through the AlertDialog → Context chain.
		AlertDialog stalePicker = activePicker;
		if (stalePicker != null && stalePicker.isShowing())
		{
			try
			{
				stalePicker.cancel();
			}
			catch (RuntimeException ignored)
			{
				// Stale dialog whose window was already destroyed throws on cancel; nothing to clean.
			}
		}
		activePicker = null;
		float density = ctx.getResources().getDisplayMetrics().density;
		int dp4 = DpToPx.toPx(4, density);
		int dp8 = DpToPx.toPx(8, density);
		int dp16 = DpToPx.toPx(16, density);

		GridConfig cfg = state.getGridConfig();

		ScrollView scroll = new ScrollView(ctx);
		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp16, dp8, dp16, dp8);
		scroll.addView(root);

		// Cards in alphabetical order by title (Build, Grid, Permissions, Pixel Grid, Selection &
		// Paint). The Permissions card is only added when the host exposes a permission helper —
		// see the `permissions != null` guard at the show() signature. The first card uses dp4 top
		// margin (tighter to the dialog header); subsequent cards use dp8.
		EditText[] dimensionInputs = new EditText[2];
		root.addView(buildInfoCard(ctx, density), DialogCards.topMargin(dp4));
		root.addView(buildGridCard(ctx, state, cfg, density, dimensionInputs), DialogCards.topMargin(dp8));
		if (permissions != null)
		{
			root.addView(buildPermissionsCard(ctx, permissions, density), DialogCards.topMargin(dp8));
		}
		root.addView(buildPixelGridCard(ctx, state, cfg, density), DialogCards.topMargin(dp8));
		root.addView(buildSelectionCard(ctx, state, cfg, density), DialogCards.topMargin(dp8));

		Runnable applyDimensions = () -> applyDimensionInputs(state, dimensionInputs[0], dimensionInputs[1]);

		// Outer dialog reference qualified as `settingsDialog` so the lambda parameters below can use the
		// canonical `dialog` / `(dialog, which)` names without shadowing.
		AlertDialog settingsDialog = new AlertDialog.Builder(ctx)
			.setTitle("Settings")
			.setView(scroll)
			.setPositiveButton("Done", (dialog, which) -> applyDimensions.run())
			.setOnCancelListener(dialog -> cancelActivePicker())
			.create();
		// Dismiss listener covers paths the cancel listener misses: Done, the config-change destroy
		// path, and any future dialog-API dismissal. Compose with the host's optional cleanup so a
		// caller's host-tracking listener doesn't clobber cancelActivePicker.
		settingsDialog.setOnDismissListener(dialog ->
		{
			cancelActivePicker();
			if (hostDismissListener != null)
			{
				hostDismissListener.onDismiss(dialog);
			}
		});
		settingsDialog.show();
		return settingsDialog;
	}

	private static void addLabel(LinearLayout parent, String text)
	{
		TextView tv = new TextView(parent.getContext());
		tv.setText(text);
		tv.setTextSize(13);
		tv.setTextColor(ThemeColors.SUBTEXT0);
		parent.addView(tv);
	}

	/**
	 * Commit the Columns / Rows EditText values to state, clamping each to [1, 50]. A blank / non-numeric
	 * field is silently ignored — preset-chip taps already synced the inputs back in.
	 *
	 * @param state    CropState whose gridConfig is mutated with the parsed values
	 * @param editCols Columns input — text trimmed, parsed as int, clamped to [1, 99]
	 * @param editRows Rows input — same parse / clamp as editCols
	 */
	private static void applyDimensionInputs(CropState state, EditText editCols, EditText editRows)
	{
		try
		{
			int rawCols = Integer.parseInt(editCols.getText().toString().trim());
			int rawRows = Integer.parseInt(editRows.getText().toString().trim());
			int newCols = Math.clamp(rawCols, 1, 99);
			int newRows = Math.clamp(rawRows, 1, 99);
			// Write back the clamped values so the user sees what actually got saved — without this,
			// a "0" silently clamps to 1 with no on-screen acknowledgment, leaving the user thinking
			// they got 0 columns (and confused when the grid renders with 1).
			if (rawCols != newCols)
			{
				editCols.setText(String.valueOf(newCols));
			}
			if (rawRows != newRows)
			{
				editRows.setText(String.valueOf(newRows));
			}
			state.updateGridConfig(g -> g.withColumns(newCols).withRows(newRows));
		}
		catch (NumberFormatException ignored)
		{
			// Per Javadoc: blank / non-numeric input keeps the prior dimensions. Preset chip taps already
			// synced the EditTexts back, so dropping the OK action on bad text is the documented contract.
		}
	}

	private static void applyPickedColor(int color, int[] tracked, GradientDrawable swatchBg,
		View swatch, ColorPickerDialog.OnColorSelectedListener onPick)
	{
		tracked[0] = color;
		swatchBg.setColor(color);
		swatch.invalidate();
		onPick.onColorSelected(color);
	}

	/**
	 * Build the "Grid" card — Cols / Rows EditTexts, presets, line color, line width.
	 *
	 * @param ctx             Activity context for inflation
	 * @param state           CropState whose gridConfig is mutated as the user interacts
	 * @param cfg             snapshot of the initial gridConfig used to seed control values
	 * @param density         display density used by DpToPx for sizing
	 * @param dimensionInputs OUT — Cols EditText written to index [0], Rows EditText written to index [1] so the
	 *                        dialog's "Done" button can commit them via applyDimensionInputs. Caller must pass a
	 *                        2-element array.
	 * @return the assembled card LinearLayout, ready for the dialog root
	 */
	private static LinearLayout buildGridCard(Context ctx, CropState state, GridConfig cfg,
		float density, EditText[] dimensionInputs)
	{
		int dp4 = DpToPx.toPx(4, density);
		int dp6 = DpToPx.toPx(6, density);
		int dp8 = DpToPx.toPx(8, density);
		int dp12 = DpToPx.toPx(12, density);

		LinearLayout card = DialogCards.newCard(ctx, density);
		DialogCards.addCardTitle(card, "Grid");

		// Cols × Rows input row
		LinearLayout dimensionsRow = row(ctx);
		EditText editCols = numInput(ctx, String.valueOf(cfg.columns()), density);
		EditText editRows = numInput(ctx, String.valueOf(cfg.rows()), density);
		dimensionInputs[0] = editCols;
		dimensionInputs[1] = editRows;

		addLabel(dimensionsRow, "Columns");
		LinearLayout.LayoutParams colsLayoutParams = new LinearLayout.LayoutParams(
			DpToPx.toPx(48, density), DpToPx.toPx(30, density));
		colsLayoutParams.leftMargin = dp8;
		dimensionsRow.addView(editCols, colsLayoutParams);

		TextView times = new TextView(ctx);
		times.setText("  \u00D7  ");
		times.setTextColor(ThemeColors.SUBTEXT0);
		times.setTextSize(13);
		dimensionsRow.addView(times);

		addLabel(dimensionsRow, "Rows");
		LinearLayout.LayoutParams rowsLayoutParams = new LinearLayout.LayoutParams(
			DpToPx.toPx(48, density), DpToPx.toPx(30, density));
		rowsLayoutParams.leftMargin = dp8;
		dimensionsRow.addView(editRows, rowsLayoutParams);
		card.addView(dimensionsRow, DialogCards.topMargin(dp6));

		card.addView(buildPresetRow(ctx, state, editCols, editRows, density), DialogCards.topMargin(dp8));

		card.addView(colorRow(ctx, "Line color", cfg.color(), density, color ->
			state.updateGridConfig(g -> g.withColor(color))), DialogCards.topMargin(dp12));

		card.addView(buildWidthRow(ctx, state, cfg, density), DialogCards.topMargin(dp8));
		return card;
	}

	/**
	 * Build the "Build" info card — shows the BUILD_TIME constant from BuildConfig so testers can verify which
	 * build is running without Logcat.
	 *
	 * @param ctx     Activity context for view inflation
	 * @param density display density used by DpToPx for sizing
	 * @return the assembled card LinearLayout
	 */
	private static LinearLayout buildInfoCard(Context ctx, float density)
	{
		int dp4 = DpToPx.toPx(4, density);
		LinearLayout card = DialogCards.newCard(ctx, density);
		DialogCards.addCardTitle(card, "Build");

		TextView buildTime = new TextView(ctx);
		buildTime.setText("Version: " + BuildConfig.BUILD_TIME);
		buildTime.setTextSize(11);
		buildTime.setTextColor(ThemeColors.SUBTEXT0);
		buildTime.setTypeface(Typeface.MONOSPACE);
		card.addView(buildTime, DialogCards.topMargin(dp4));
		return card;
	}

	/**
	 * Build the "Permissions" card — surfaces the MANAGE_EXTERNAL_STORAGE grant state plus a tap target to
	 * open the system Settings page where the user actually grants it. Without this card the only path to
	 * grant the permission was via the Replace-failure dialog, which requires the user to first hit a
	 * collision-overwrite save failure — a path many users never reach. Mirrors the wording in
	 * ReplaceStrategy.showReplaceFailureDialog so the user sees the same "All files access" label across
	 * both surfaces.
	 *
	 * @param ctx         Activity context for view inflation
	 * @param permissions storage-permission helper used to query current grant state + open Settings
	 * @param density     display density used by DpToPx for sizing
	 * @return the assembled card LinearLayout
	 */
	private static LinearLayout buildPermissionsCard(Context ctx, StoragePermissionHelper permissions,
		float density)
	{
		int dp4 = DpToPx.toPx(4, density);
		LinearLayout card = DialogCards.newCard(ctx, density);
		// Whole card is the tap target — clicking anywhere inside the panel (title, status row, hint
		// text, or the surrounding padding) opens the system Settings page. Adding the ripple via the
		// selectableItemBackground theme attribute keeps tap feedback visible without overriding the
		// card's SURFACE0 fill. setClickable + setFocusable are explicit because LinearLayout doesn't
		// default to clickable, and without focusable the TalkBack semantics would skip the row.
		TypedValue rippleAttr = new TypedValue();
		ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, rippleAttr, true);
		card.setForeground(ctx.getResources().getDrawable(rippleAttr.resourceId, ctx.getTheme()));
		card.setClickable(true);
		card.setFocusable(true);
		card.setOnClickListener(view -> permissions.openStoragePermissionSettings());

		DialogCards.addCardTitle(card, "Permissions");

		boolean granted = permissions.hasStoragePermission();
		TextView row = new TextView(ctx);
		row.setText(granted
			? "All files access: granted (tap to manage)"
			: "All files access: not granted (tap to grant)");
		row.setTextSize(13);
		row.setTextColor(granted ? ThemeColors.SUBTEXT0 : ThemeColors.MAUVE);
		card.addView(row, DialogCards.topMargin(dp4));

		TextView hint = new TextView(ctx);
		hint.setText("Required for Replace-mode saves over existing files. Plain Save-As uses SAF and "
			+ "works without this permission.");
		hint.setTextSize(11);
		hint.setTextColor(ThemeColors.SUBTEXT0);
		card.addView(hint, DialogCards.topMargin(dp4));
		return card;
	}

	/**
	 * Build the "Pixel Grid" card — checkbox to toggle the per-pixel overlay + color picker for the pixel-grid
	 * stroke.
	 *
	 * @param ctx     Activity context for view inflation
	 * @param state   CropState whose gridConfig is mutated by the toggle / color picker
	 * @param cfg     snapshot of the initial gridConfig used to seed control values
	 * @param density display density used by DpToPx for sizing
	 * @return the assembled card LinearLayout
	 */
	private static LinearLayout buildPixelGridCard(Context ctx, CropState state, GridConfig cfg, float density)
	{
		int dp4 = DpToPx.toPx(4, density);
		int dp8 = DpToPx.toPx(8, density);

		LinearLayout card = DialogCards.newCard(ctx, density);
		DialogCards.addCardTitle(card, "Pixel Grid");

		CheckBox chkPixel = new CheckBox(ctx);
		chkPixel.setText("Show when zoomed in enough to see individual pixels");
		chkPixel.setTextSize(12);
		chkPixel.setTextColor(ThemeColors.TEXT);
		chkPixel.setChecked(cfg.showPixelGrid());
		chkPixel.setButtonTintList(ColorStateList.valueOf(ThemeColors.MAUVE));
		chkPixel.setOnCheckedChangeListener((button, isChecked) ->
			state.updateGridConfig(g -> g.withShowPixelGrid(isChecked)));
		card.addView(chkPixel, DialogCards.topMargin(dp4));

		card.addView(colorRow(ctx, "Color", cfg.pixelGridColor(), density, color ->
			state.updateGridConfig(g -> g.withPixelGridColor(color))), DialogCards.topMargin(dp8));
		return card;
	}

	/**
	 * 2×2..8×8 equal-weight preset chip row. Tapping a chip syncs both the GridConfig and the Cols / Rows
	 * EditTexts so the user sees the new values.
	 *
	 * @param ctx      Activity context for inflation
	 * @param state    CropState whose gridConfig is mutated on chip tap
	 * @param editCols Columns input — chip taps reflect the new value back so the user sees it
	 * @param editRows Rows input — same as editCols
	 * @param density  display density used by DpToPx for sizing
	 * @return the assembled chip row LinearLayout
	 */
	private static LinearLayout buildPresetRow(Context ctx, CropState state,
		EditText editCols, EditText editRows, float density)
	{
		int dp4 = DpToPx.toPx(4, density);
		int[][] presets = { { 2, 2 }, { 3, 3 }, { 4, 4 }, { 5, 5 }, { 6, 6 }, { 7, 7 }, { 8, 8 } };
		LinearLayout presetRow = new LinearLayout(ctx);
		presetRow.setOrientation(LinearLayout.HORIZONTAL);
		for (int i = 0; i < presets.length; i++)
		{
			final int cols = presets[i][0];
			final int rows = presets[i][1];
			TextView btn = chipButton(ctx, cols + "\u00D7" + rows, density);
			btn.setOnClickListener(view ->
			{
				state.updateGridConfig(g -> g.withColumns(cols).withRows(rows));
				editCols.setText(String.valueOf(cols));
				editRows.setText(String.valueOf(rows));
			});
			// layout_weight=1 with width=0 → each chip gets equal share of row width
			LinearLayout.LayoutParams chipLayoutParams = new LinearLayout.LayoutParams(
				0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
			if (i > 0)
			{
				chipLayoutParams.leftMargin = dp4;
			}
			presetRow.addView(btn, chipLayoutParams);
		}
		return presetRow;
	}

	/**
	 * Build the "Selection & Paint" card — shared color for selection markers, polygon fill, and horizon paint
	 * strokes.
	 *
	 * @param ctx     Activity context for view inflation
	 * @param state   CropState whose gridConfig is mutated by the color picker
	 * @param cfg     snapshot of the initial gridConfig used to seed the swatch
	 * @param density display density used by DpToPx for sizing
	 * @return the assembled card LinearLayout
	 */
	private static LinearLayout buildSelectionCard(Context ctx, CropState state, GridConfig cfg, float density)
	{
		int dp4 = DpToPx.toPx(4, density);
		int dp8 = DpToPx.toPx(8, density);

		LinearLayout card = DialogCards.newCard(ctx, density);
		DialogCards.addCardTitle(card, "Selection & Paint");

		TextView selNote = new TextView(ctx);
		selNote.setText("Color for selection points, polygon fill, and horizon paint.");
		selNote.setTextSize(11);
		selNote.setTextColor(ThemeColors.OVERLAY0);
		card.addView(selNote, DialogCards.topMargin(dp4));

		card.addView(colorRow(ctx, "Color", cfg.selectionColor(), density,
			ColorPickerDialog.PALETTE_TRANSLUCENT, color ->
				state.updateGridConfig(g -> g.withSelectionColor(color))), DialogCards.topMargin(dp8));
		return card;
	}

	/**
	 * Build the "Width" seek-bar row — user drags to pick a grid stroke width (1-20 image pixels).
	 * The seek bar is configured with `setMin(1)` so the thumb cannot land on a 0 position; the
	 * progress-changed handler also clamps defensively to [1, 20] as a belt-and-braces guard
	 * against any future tampering with the bar's range.
	 *
	 * @param ctx     Activity context for inflation
	 * @param state   CropState whose gridConfig.lineWidth is mutated as the seek-bar drags
	 * @param cfg     snapshot of the initial gridConfig used to seed the seek-bar position
	 * @param density display density used by DpToPx for sizing
	 * @return the assembled row LinearLayout
	 */
	private static LinearLayout buildWidthRow(Context ctx, CropState state, GridConfig cfg, float density)
	{
		int dp8 = DpToPx.toPx(8, density);
		LinearLayout widthRow = row(ctx);
		addLabel(widthRow, "Width");

		SeekBar widthSeekBar = new SeekBar(ctx);
		// min=1 so the slider thumb can't show a position that doesn't correspond to the model. The
		// onProgressChanged handler below clamps to [1, 20] before writing GridConfig anyway, but
		// without setMin the thumb at the left edge would render at position 0 while the label and
		// state both read 1 — a UI lie.
		widthSeekBar.setMin(1);
		widthSeekBar.setMax(20);
		widthSeekBar.setProgress((int) cfg.lineWidth());
		TextView widthValueText = valueChip(ctx, String.valueOf((int) cfg.lineWidth()), density);
		widthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
		{
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
			{
				int clamped = Math.clamp(progress, 1, 20);
				state.updateGridConfig(g -> g.withLineWidth(clamped));
				widthValueText.setText(String.valueOf(clamped));
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {}
		});
		LinearLayout.LayoutParams seekBarLayoutParams = new LinearLayout.LayoutParams(
			0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
		seekBarLayoutParams.leftMargin = dp8;
		seekBarLayoutParams.rightMargin = dp8;
		widthRow.addView(widthSeekBar, seekBarLayoutParams);
		widthRow.addView(widthValueText);
		return widthRow;
	}

	/**
	 * Compact chip-style preset button (used with layout_weight in rows).
	 */
	private static TextView chipButton(Context ctx, String text, float density)
	{
		TextView btn = new TextView(ctx);
		btn.setText(text);
		btn.setTextSize(11);
		btn.setTextColor(ThemeColors.SUBTEXT0);
		btn.setGravity(Gravity.CENTER);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(ThemeColors.SURFACE1);
		bg.setCornerRadius(DpToPx.toPx(4, density));
		btn.setBackground(bg);
		btn.setPadding(0, DpToPx.toPx(6, density), 0, DpToPx.toPx(6, density));
		btn.setSingleLine(true);
		return btn;
	}

	private static LinearLayout colorRow(Context ctx, String label, int currentColor,
		float density, ColorPickerDialog.OnColorSelectedListener onPick)
	{
		return colorRow(ctx, label, currentColor, density, ColorPickerDialog.PALETTE_OPAQUE, onPick);
	}

	private static LinearLayout colorRow(Context ctx, String label, int currentColor,
		float density, int[] palette, ColorPickerDialog.OnColorSelectedListener onPick)
	{
		int swatchSize = DpToPx.toPx(26, density);
		final int[] tracked = { currentColor };
		LinearLayout row = row(ctx);
		TextView labelView = new TextView(ctx);
		labelView.setText(label);
		labelView.setTextSize(13);
		labelView.setTextColor(ThemeColors.TEXT);
		row.addView(labelView);

		View spacer = new View(ctx);
		LinearLayout.LayoutParams spacerLayoutParams = new LinearLayout.LayoutParams(0, 1, 1f);
		row.addView(spacer, spacerLayoutParams);

		// Swatch with rounded corners + subtle border
		View swatch = new View(ctx);
		GradientDrawable swatchBg = new GradientDrawable();
		swatchBg.setColor(currentColor);
		swatchBg.setCornerRadius(DpToPx.toPx(4, density));
		swatchBg.setStroke(1, ThemeColors.SURFACE1);
		swatch.setBackground(swatchBg);
		row.addView(swatch, new LinearLayout.LayoutParams(swatchSize, swatchSize));

		TextView btn = new TextView(ctx);
		btn.setText("Edit");
		btn.setTextSize(12);
		btn.setTextColor(ThemeColors.MAUVE);
		int btnPadHor = DpToPx.toPx(10, density);
		int btnPadVer = DpToPx.toPx(6, density);
		btn.setPadding(btnPadHor, btnPadVer, btnPadHor, btnPadVer);
		btn.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams editBtnLayoutParams = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		editBtnLayoutParams.leftMargin = DpToPx.toPx(8, density);
		row.addView(btn, editBtnLayoutParams);

		View.OnClickListener openPicker = view ->
			openColorPicker(ctx, tracked, swatchBg, swatch, palette, onPick);
		btn.setOnClickListener(openPicker);
		swatch.setOnClickListener(openPicker);
		return row;
	}

	private static EditText numInput(Context ctx, String val, float density)
	{
		EditText edit = new EditText(ctx);
		edit.setText(val);
		edit.setTextSize(13);
		edit.setGravity(Gravity.CENTER);
		edit.setSingleLine(true);
		edit.setInputType(InputType.TYPE_CLASS_NUMBER);
		// Hard-cap to 2 digits at the IME layer. Mirrors the [1, 99] applyDimensionInputs clamp so the
		// user can't type a 3-digit value (e.g. 100) that would silently clamp to 99 at Done — the
		// soft keyboard simply refuses the third character. Both current call sites (cols, rows) want
		// the same cap; if a future numeric input wants a different width, parameterise this.
		edit.setFilters(new InputFilter[] { new InputFilter.LengthFilter(2) });
		edit.setTextColor(ThemeColors.TEXT);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(ThemeColors.SURFACE1);
		bg.setCornerRadius(DpToPx.toPx(4, density));
		edit.setBackground(bg);
		int pad = DpToPx.toPx(4, density);
		edit.setPadding(pad, pad, pad, pad);
		return edit;
	}

	/**
	 * Open a ColorPickerDialog for one of the swatch rows. Tracks the fresh picker as the active one so
	 * SettingsDialog's OnCancelListener can cancel it on forced parent dismissal, and dismisses any prior
	 * picker first so rapid swatch taps don't stack two pickers (the deeper one would still fire its OK
	 * listener and mutate gridConfig).
	 *
	 * @param ctx      Activity context for inflation
	 * @param tracked  1-element box holding the row's currently-tracked color (ARGB)
	 * @param swatchBg drawable backing the swatch view; repainted by applyPickedColor
	 * @param swatch   the row's swatch view; invalidated by applyPickedColor
	 * @param palette  PALETTE_OPAQUE or PALETTE_TRANSLUCENT
	 * @param onPick   listener invoked by applyPickedColor after the picker confirms
	 */
	private static void openColorPicker(Context ctx, int[] tracked, GradientDrawable swatchBg,
		View swatch, int[] palette, ColorPickerDialog.OnColorSelectedListener onPick)
	{
		AlertDialog prev = activePicker;
		if (prev != null && prev.isShowing())
		{
			// cancel() fires OnCancelListener AND OnDismissListener; dismiss() only fires the latter.
			// cancelActivePicker (the other teardown site) also uses cancel(), so this matches —
			// without consistency, a future OnCancelListener wired to the picker would silently
			// miss firing on the swatch-tap-while-picker-open path.
			prev.cancel();
		}
		AlertDialog picker = ColorPickerDialog.show(ctx, tracked[0], palette,
			color -> applyPickedColor(color, tracked, swatchBg, swatch, onPick));
		activePicker = picker;
		picker.setOnDismissListener(dialog ->
		{
			if (activePicker == dialog)
			{
				activePicker = null;
			}
		});
	}

	private static LinearLayout row(Context ctx)
	{
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		return row;
	}

	/**
	 * Small rounded readout chip for numeric values.
	 */
	private static TextView valueChip(Context ctx, String text, float density)
	{
		TextView tv = new TextView(ctx);
		tv.setText(text);
		tv.setTextSize(12);
		tv.setTextColor(ThemeColors.MAUVE);
		tv.setGravity(Gravity.CENTER);
		tv.setMinWidth(DpToPx.toPx(32, density));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(ThemeColors.SURFACE1);
		bg.setCornerRadius(DpToPx.toPx(4, density));
		tv.setBackground(bg);
		int chipPadHor = DpToPx.toPx(6, density);
		int chipPadVer = DpToPx.toPx(3, density);
		tv.setPadding(chipPadHor, chipPadVer, chipPadHor, chipPadVer);
		return tv;
	}
}
