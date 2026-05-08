package com.cropcenter;

import android.app.AlertDialog;
import android.graphics.Paint;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.cropcenter.crop.CropEngine;
import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.EditorMode;
import com.cropcenter.util.DpToPx;
import com.cropcenter.util.ThemeColors;
import com.cropcenter.view.DialogStrings;

import java.util.Locale;

/**
 * Wires the toolbar controls (mode / lock / AR / undo-redo / clear / rotation / auto-rotate) and two secondary dialogs
 * (custom AR, precise rotation). All onClick handlers route back into the activity for crop-state manipulation and into
 * UiSync for visual updates, keeping the binder free of any direct rendering or state mutation beyond what the
 * corresponding control conceptually owns.
 */
final class ToolbarBinder
{
	private static final AspectRatio[] AR_VALUES = {
		AspectRatio.R4_5, AspectRatio.FREE, AspectRatio.R16_9, AspectRatio.R3_2,
		AspectRatio.R4_3, AspectRatio.R5_4, AspectRatio.R1_1, AspectRatio.R3_4,
		AspectRatio.R2_3, AspectRatio.R9_16, null
	};
	private static final String[] AR_LABELS = {
		"4:5", "Full", "16:9", "3:2", "4:3", "5:4", "1:1", "3:4", "2:3", "9:16", "Custom"
	};

	private final AutoRotateBinder autoRotate;
	private final ToolbarHost host;
	private final UiSync ui;
	// AR spinner adapter, cached so applyAndResetSpinner can call notifyDataSetChanged after updating
	// customArLabel — the closed-spinner view re-reads the (overridden) selected-position text from the
	// adapter, and the dropdown's Custom row picks up the new label on next open.
	private ArrayAdapter<String> arAdapter;
	// Dynamic Custom row label. Defaults to "Custom" until the user applies a custom AR; then reads
	// "Custom W:H" so the spinner head + dropdown reflect the active model state. The closed spinner
	// shows this string whenever customArActive is true (instead of the AR_LABELS preset text); the
	// Custom dropdown row at AR_LABELS.length - 1 always shows it. Codex round-16: without this dynamic
	// reflection, the spinner could show e.g. "16:9" while the crop was actually using a custom 5:7.
	private String customArLabel = "Custom";
	// True when state holds a Custom (non-preset) AR. Set in applyAndResetSpinner; cleared whenever the
	// user picks a preset row from the AR spinner. Drives the getView override that overrides the closed
	// spinner's displayed text with customArLabel so the spinner head reflects the model rather than the
	// previousArPosition reset.
	private boolean customArActive;
	// Suppression flag for the AR spinner's onItemSelected listener. After the Custom dialog applies a
	// non-preset ratio, we reset the spinner to a non-Custom position so a future tap on Custom can reopen
	// the dialog (Spinner suppresses no-op-position selections, so leaving it on Custom blocks reopen). The
	// listener early-returns on this flag so the synthetic setSelection doesn't overwrite the just-applied
	// custom AR with the previous position's preset. UI-thread-only, no synchronization needed.
	private boolean suppressArListener;

	ToolbarBinder(ToolbarHost host, UiSync ui)
	{
		this.host = host;
		this.ui = ui;
		this.autoRotate = new AutoRotateBinder(host);
	}

	/**
	 * Entry point called once from MainActivity.onCreate, after setContentView and view lookups.
	 */
	void bindAll()
	{
		setupArSpinner();
		setupModeButtons();
		setupCenterModeButtons();
		setupUndoRedo();
		setupClearPointsButton();
		setupRotation();
		autoRotate.bind();
	}

	/**
	 * Forward to AutoRotateBinder.cancelHorizonPaintMode — see that method for the full behaviour contract
	 * (touch routing reset, Auto-button label / color reset) and the rationale.
	 */
	void cancelHorizonPaintMode()
	{
		autoRotate.cancelHorizonPaintMode();
	}

	private static int parseIntOr(String text, int def)
	{
		try
		{
			return Integer.parseInt(text.trim());
		}
		catch (NumberFormatException ignored)
		{
			// Blank / non-numeric text is a routine state for the EditText this drives — caller already
			// supplied the fallback to return on parse failure.
			return def;
		}
	}

	private static TextView styleArLabel(TextView tv, int textSize, int padH, int padV)
	{
		tv.setTextSize(textSize);
		tv.setTextColor(ThemeColors.TEXT);
		tv.setPadding(padH, padV, padH, padV);
		return tv;
	}

	/**
	 * Apply the user's typed Custom AR values, update the spinner's dynamic Custom label + customArActive
	 * flag (so the closed spinner head reads "Custom W:H" via the adapter's getView override), then
	 * setSelection back to previousArPosition so a future Custom tap fires onItemSelected (Spinner
	 * suppresses same-position re-selections, so leaving it on Custom blocks reopen). suppressArListener
	 * gates the synthetic setSelection's listener fire so it doesn't overwrite the just-applied custom AR
	 * with the previous preset. Extracted from the showCustomArDialog Apply runnable to keep the
	 * positive-button lambda within CLAUDE.md's 3-line cap (the inline body grew to 6 statements after
	 * round-16 added the customArLabel / customArActive / notifyDataSetChanged reflection).
	 *
	 * @param widthInput        custom-W EditText
	 * @param heightInput       custom-H EditText
	 * @param spinner           the AR spinner whose selection is reset
	 * @param previousArPosition non-Custom spinner position to restore after Apply
	 */
	private void applyCustomArAndResetSpinner(EditText widthInput, EditText heightInput,
		Spinner spinner, int previousArPosition)
	{
		applyCustomAspectRatio(widthInput, heightInput);
		customArLabel = "Custom "
			+ Math.max(1, parseIntOr(widthInput.getText().toString(), 16))
			+ ":"
			+ Math.max(1, parseIntOr(heightInput.getText().toString(), 9));
		customArActive = true;
		arAdapter.notifyDataSetChanged();
		suppressArListener = true;
		spinner.setSelection(previousArPosition);
	}

	/**
	 * Apply the user-typed width/height pair as a custom aspect ratio. Floors both sides at 1 so a stray "0" or
	 * empty input falls back to a sane min instead of crashing the engine on a zero-size ratio. After applying, if
	 * the user has selection points the engine recomputes the crop around them; otherwise the crop is centered on
	 * the image. Invoked from showCustomArDialog's Apply button.
	 *
	 * @param widthInput  EditText holding the typed width side of the ratio
	 * @param heightInput EditText holding the typed height side of the ratio
	 */
	private void applyCustomAspectRatio(EditText widthInput, EditText heightInput)
	{
		int ratioW = Math.max(1, parseIntOr(widthInput.getText().toString(), 16));
		int ratioH = Math.max(1, parseIntOr(heightInput.getText().toString(), 9));
		host.getState().setAspectRatio(new AspectRatio(ratioW, ratioH));
		if (!host.getState().getSelectionPoints().isEmpty())
		{
			CropEngine.autoComputeFromPoints(host.getState());
		}
		else
		{
			host.ensureCropCenter();
		}
	}

	/**
	 * Parse the precise-rotation EditText and update CropState if it contains a valid signed decimal in [-180,
	 * 180]. Silently no-op on a parse failure (the user's existing rotation is preserved). Invoked from
	 * showPreciseRotationDialog's Apply button.
	 *
	 * @param input EditText containing the user-typed degree value
	 */
	private void applyPreciseRotation(EditText input)
	{
		try
		{
			float val = Math.clamp(Float.parseFloat(input.getText().toString().trim()), -180f, 180f);
			host.getState().setRotationDegrees(val);
		}
		catch (NumberFormatException ignored)
		{
			// Per Javadoc: silently no-op so the user's existing rotation is preserved when Apply is tapped
			// on an unparseable field. The dialog stays open, letting the user fix and retry.
		}
	}

	private EditText numberInput(String initial)
	{
		EditText edit = new EditText(host.getActivity());
		edit.setInputType(InputType.TYPE_CLASS_NUMBER);
		edit.setText(initial);
		edit.setGravity(Gravity.CENTER);
		return edit;
	}

	/**
	 * Clear-selection-points click handler. Drops all selection points, clears the editor's undo history, resets
	 * the crop to the full image, and refreshes the toolbar's point-button enabled state.
	 *
	 * @param view the clear-points button (unused — wired only because OnClickListener requires it)
	 */
	private void onClearPointsClick(View view)
	{
		host.getState().clearSelectionPoints();
		host.getEditorView().clearUndoHistory();
		host.getEditorView().resetCropToFullImage();
		host.getEditorView().invalidate();
		ui.updatePointButtonStates();
	}

	/**
	 * Lock-axis button click handler. Maps the tapped button's id to a CenterMode (BOTH / HORIZONTAL / VERTICAL),
	 * stores the choice as the current mode-specific preference, refreshes the lock highlight, and recomputes /
	 * recenters the crop based on whether the editor is in Select or Move mode and whether the center is currently
	 * locked or panning. Lives on the binder rather than each setup site so all three lock buttons share one path.
	 *
	 * @param view the tapped button (one of btnLockBoth / btnLockH / btnLockV)
	 */
	private void onLockButtonClick(View view)
	{
		int id = view.getId();
		CenterMode pref;
		if (id == R.id.btnLockBoth)
		{
			pref = CenterMode.BOTH;
		}
		else if (id == R.id.btnLockH)
		{
			pref = CenterMode.HORIZONTAL;
		}
		else
		{
			pref = CenterMode.VERTICAL;
		}

		host.setCurrentPref(pref);
		host.applyLockMode();
		ui.updateLockHighlight();

		if (host.getState().getEditorMode() == EditorMode.SELECT_FEATURE
			&& !host.getState().isCenterLocked() && !host.isPanning())
		{
			host.recomputeForLockChange();
		}
		else if (host.getState().getEditorMode() == EditorMode.MOVE && host.getState().hasCenter()
			&& !host.getState().getSelectionPoints().isEmpty() && !host.isPanning())
		{
			host.recenterOnSelection();
		}
		host.getEditorView().invalidate();
	}

	/**
	 * Lock-center checkbox change handler. Updates state's centerLocked flag; in Select mode with selection points
	 * present, unlocking re-derives the center from the points. Move mode preserves the user's current position.
	 *
	 * @param button    the lock-center checkbox (unused — wired only because OnCheckedChangeListener requires it)
	 * @param isChecked new checked state
	 */
	private void onLockCenterCheckedChanged(CompoundButton button, boolean isChecked)
	{
		host.getState().setCenterLocked(isChecked);
		if (!isChecked && host.getState().getEditorMode() == EditorMode.SELECT_FEATURE
			&& !host.getState().getSelectionPoints().isEmpty())
		{
			host.recomputeForLockChange();
		}
		host.getEditorView().invalidate();
	}

	/**
	 * Mode-button click handler. Maps the tapped button's id to MOVE / SELECT_FEATURE, applies the resulting lock
	 * mode, refreshes the mode + lock highlight, and recomputes the crop when entering Select with the center
	 * unlocked and not panning. Lives on the binder rather than each setup site so both mode buttons share one
	 * path.
	 *
	 * @param view the tapped button (one of btnModeMove / btnModeSelect)
	 */
	private void onModeButtonClick(View view)
	{
		int id = view.getId();
		if (id == R.id.btnModeMove)
		{
			host.getState().setEditorMode(EditorMode.MOVE);
		}
		else if (id == R.id.btnModeSelect)
		{
			host.getState().setEditorMode(EditorMode.SELECT_FEATURE);
		}
		host.applyLockMode();
		ui.updateModeHighlight();
		ui.updateLockHighlight();
		if (host.getState().getEditorMode() == EditorMode.SELECT_FEATURE
			&& !host.getState().isCenterLocked() && !host.isPanning())
		{
			host.recomputeForLockChange();
		}
		host.getEditorView().invalidate();
	}

	/**
	 * Pan-checkbox change handler. Re-derives the lock mode (panning forces CenterMode.LOCKED), refreshes the lock
	 * highlight, and recomputes the crop only when turning Pan OFF in Select mode while the center is unlocked —
	 * other transitions don't change the spatial framing.
	 *
	 * @param button    the Pan checkbox (unused — wired only because OnCheckedChangeListener requires it)
	 * @param isChecked new checked state
	 */
	private void onPanCheckedChanged(CompoundButton button, boolean isChecked)
	{
		host.applyLockMode();
		ui.updateLockHighlight();
		// Recompute only when turning Pan off in Select mode
		if (!isChecked && host.getState().getEditorMode() == EditorMode.SELECT_FEATURE
			&& !host.getState().isCenterLocked())
		{
			host.recomputeForLockChange();
		}
		host.getEditorView().invalidate();
	}

	/**
	 * Rotation-ruler change handler. Suppresses recursive updates while the ruler itself is reflecting a
	 * programmatic setRotationDegrees from elsewhere (auto-rotate, precise-rotation dialog) — without that guard
	 * the RotationRulerView's onDraw -> setRotationDegrees -> notifyChanged feedback loop would burn CPU.
	 *
	 * @param degrees the ruler's new value (already clamped to its display range)
	 */
	private void onRotationChanged(float degrees)
	{
		if (host.isRulerUpdating())
		{
			return;
		}
		host.getState().setRotationDegrees(degrees);
	}

	/**
	 * Wire the aspect-ratio spinner: build the labelled adapter from AR_VALUES / AR_LABELS, install the
	 * onItemSelected callback that dispatches to setAspectRatio (or the custom-AR dialog for the "Custom"
	 * sentinel), and resize the spinner to fit the longest label. Called once from bindAll on Activity create.
	 */
	private void setupArSpinner()
	{
		Spinner spinner = host.findViewById(R.id.spinnerAr);
		float density = host.getActivity().getResources().getDisplayMetrics().density;
		int padH = DpToPx.toPx(6, density);
		int padV = DpToPx.toPx(4, density);

		// Custom adapter with compact item views (tight padding, 12sp text). Two getView overrides
		// reflect the active model state in the spinner UI: the closed-spinner head substitutes
		// customArLabel for the preset text whenever a custom AR is active (so the head reads
		// "Custom 5:7" instead of the previousArPosition preset), and the Custom dropdown row always
		// reads customArLabel (so the user can see what's currently committed before re-tapping Custom).
		arAdapter = new ArrayAdapter<>(host.getActivity(),
			android.R.layout.simple_spinner_item, AR_LABELS)
		{
			@Override
			public View getView(int position, View convertView, ViewGroup parent)
			{
				TextView tv = (TextView) super.getView(position, convertView, parent);
				if (customArActive)
				{
					tv.setText(customArLabel);
				}
				return styleArLabel(tv, 12, padH, padV);
			}

			@Override
			public View getDropDownView(int position, View convertView, ViewGroup parent)
			{
				TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
				if (position == AR_LABELS.length - 1)
				{
					tv.setText(customArLabel);
				}
				return styleArLabel(tv, 13, padH * 2, padV * 2);
			}
		};
		spinner.setAdapter(arAdapter);
		spinner.setSelection(0);

		// Size the spinner to exactly fit the widest label + arrow.
		Paint textPaint = new Paint();
		textPaint.setTextSize(12 * host.getActivity().getResources().getDisplayMetrics().scaledDensity);
		float maxTextPx = 0;
		for (String label : AR_LABELS)
		{
			maxTextPx = Math.max(maxTextPx, textPaint.measureText(label));
		}
		int totalPx = (int) maxTextPx + padH * 2 + DpToPx.toPx(24, density);
		spinner.setMinimumWidth(totalPx);
		ViewGroup.LayoutParams lp = spinner.getLayoutParams();
		if (lp != null)
		{
			lp.width = totalPx;
			spinner.setLayoutParams(lp); // triggers re-layout
		}
		spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
		{
			// Last non-Custom position the user picked. When they tap Custom and then cancel the dialog,
			// the spinner is already visually on Custom but the model is unchanged — without restoring, a
			// subsequent tap on Custom doesn't fire onItemSelected (Spinner suppresses no-op selections),
			// so the dialog can't reopen. Capturing the prior position lets cancel revert to it.
			private int lastNonCustomPos = 0;

			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int pos, long id)
			{
				if (suppressArListener)
				{
					// Synthetic re-selection from Custom dialog Apply. State already holds the
					// custom AR; setSelection moved spinner off Custom so a future tap on Custom
					// reopens the dialog. Don't overwrite with the synthetic position's preset.
					suppressArListener = false;
					return;
				}
				if (pos < AR_VALUES.length && AR_VALUES[pos] != null)
				{
					lastNonCustomPos = pos;
					// Picking a preset clears customArActive so the closed-spinner head stops
					// substituting customArLabel — the head should now display the preset that
					// was just committed.
					if (customArActive)
					{
						customArActive = false;
						arAdapter.notifyDataSetChanged();
					}
					host.getState().setAspectRatio(AR_VALUES[pos]);
					if (!host.getState().getSelectionPoints().isEmpty())
					{
						CropEngine.autoComputeFromPoints(host.getState());
					}
					else
					{
						host.ensureCropCenter();
					}
				}
				else
				{
					showCustomArDialog(spinner, lastNonCustomPos);
				}
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {}
		});
	}

	private void setupCenterModeButtons()
	{
		host.findViewById(R.id.btnLockBoth).setOnClickListener(this::onLockButtonClick);
		host.findViewById(R.id.btnLockH).setOnClickListener(this::onLockButtonClick);
		host.findViewById(R.id.btnLockV).setOnClickListener(this::onLockButtonClick);

		((CheckBox) host.findViewById(R.id.chkPan))
			.setOnCheckedChangeListener(this::onPanCheckedChanged);

		// Unlocking in Select mode re-derives the center from selection points; Move mode preserves the user's
		// current position.
		((CheckBox) host.findViewById(R.id.chkLockCenter))
			.setOnCheckedChangeListener(this::onLockCenterCheckedChanged);
	}

	private void setupClearPointsButton()
	{
		host.findViewById(R.id.btnClearPoints).setOnClickListener(this::onClearPointsClick);
	}

	private void setupModeButtons()
	{
		host.findViewById(R.id.btnModeMove).setOnClickListener(this::onModeButtonClick);
		host.findViewById(R.id.btnModeSelect).setOnClickListener(this::onModeButtonClick);
	}

	private void setupRotation()
	{
		host.getRotationRuler().setOnRotationChangedListener(this::onRotationChanged);

		host.getRotDegreesTextView().setOnClickListener(view -> showPreciseRotationDialog());
		host.getRotationRuler().setRulerEnabled(false); // disabled until an image loads

		// Ruler-zoom buttons. 2× per tap matches the pinch-zoom progression and lets the user step from coarse
		// (10°/major tick) to finest (0.01°/minor tick) in ~7 taps.
		host.findViewById(R.id.btnRotZoomOut).setOnClickListener(view ->
			host.getRotationRuler().zoomBy(0.5f));
		host.findViewById(R.id.btnRotZoomIn).setOnClickListener(view ->
			host.getRotationRuler().zoomBy(2f));
	}

	private void setupUndoRedo()
	{
		host.findViewById(R.id.btnUndo).setOnClickListener(view -> host.getEditorView().undo());
		host.findViewById(R.id.btnRedo).setOnClickListener(view -> host.getEditorView().redo());
	}

	/**
	 * Build and show the "Custom" aspect-ratio dialog with two number inputs (width, height). On Apply, parse both
	 * and route through applyCustomAspectRatio which clamps each to ≥ 1 and triggers a recompute. Triggered by
	 * selecting the "Custom" sentinel in the AR spinner.
	 */
	private void showCustomArDialog(Spinner spinner, int previousArPosition)
	{
		float density = host.getActivity().getResources().getDisplayMetrics().density;

		LinearLayout layout = new LinearLayout(host.getActivity());
		layout.setOrientation(LinearLayout.HORIZONTAL);
		layout.setGravity(Gravity.CENTER);
		int padH = DpToPx.toPx(20, density);
		layout.setPadding(padH, DpToPx.toPx(16, density), padH, DpToPx.toPx(8, density));

		int editWidth = DpToPx.toPx(60, density);
		EditText editW = numberInput("16");
		layout.addView(editW, new LinearLayout.LayoutParams(editWidth, LinearLayout.LayoutParams.WRAP_CONTENT));

		TextView separator = new TextView(host.getActivity());
		separator.setText("  :  ");
		separator.setTextSize(16);
		layout.addView(separator);

		EditText editH = numberInput("9");
		layout.addView(editH, new LinearLayout.LayoutParams(editWidth, LinearLayout.LayoutParams.WRAP_CONTENT));

		// Cancel and dismiss (X / back-press / forced dismissTransientDialogs) all restore the spinner
		// to the prior non-Custom position so the visual selection matches the spinner's affordance
		// (back to a tap-to-select spot). suppressArListener gates the synthetic onItemSelected fire so
		// it does NOT commit AR_VALUES[previousArPosition] over state.aspectRatio. Without the suppress,
		// two leaks happen: (1) re-edit case — user has Custom AR active, opens Custom dialog to edit,
		// cancels — restore would commit a preset over the preserved Custom AR, silently replacing the
		// user's saved Custom W:H. (2) cross-load case — forced cancel from inbound load posts the
		// setSelection async, by which time state.reset has run and image B is loaded; the post-async
		// onItemSelected would commit a stale preset onto image B's preserved AR. Suppress closes both
		// (Codex round-18 F18-4).
		Runnable restore = () ->
		{
			suppressArListener = true;
			spinner.setSelection(previousArPosition);
		};
		// Register the dialog with the host's transient-dialog tracker so a Share/View intent or graft
		// apply that arrives mid-dialog dismisses it before bg state.reset(). Without this, applying the
		// dialog's Custom values after a load would set state.aspectRatio + customArLabel for image A's
		// typed values onto image B's spinner (R17-4).
		host.registerTransientDialog(new AlertDialog.Builder(host.getActivity())
			.setTitle("Custom Aspect Ratio")
			.setView(layout)
			.setPositiveButton(DialogStrings.APPLY, (dialog, which) ->
				applyCustomArAndResetSpinner(editW, editH, spinner, previousArPosition))
			.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> restore.run())
			.setOnCancelListener(dialog -> restore.run())
			.show());
	}

	/**
	 * Dialog for entering an exact rotation value.
	 */
	private void showPreciseRotationDialog()
	{
		if (host.getState().getSourceImage() == null)
		{
			return;
		}
		float density = host.getActivity().getResources().getDisplayMetrics().density;

		EditText input = new EditText(host.getActivity());
		input.setText(String.format(Locale.ROOT, "%.2f", host.getState().getRotationDegrees()));
		input.setTextSize(18);
		input.setGravity(Gravity.CENTER);
		input.setTextColor(ThemeColors.TEXT);
		input.setBackgroundColor(ThemeColors.SURFACE0);
		input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
			| InputType.TYPE_NUMBER_FLAG_SIGNED);
		input.setSingleLine(true);
		int padHor = DpToPx.toPx(12, density);
		int padVer = DpToPx.toPx(10, density);
		input.setPadding(padHor, padVer, padHor, padVer);

		// Register with the host's transient-dialog tracker so a Share/View intent or graft apply
		// dismisses this dialog before bg state.reset() \u2014 applyPreciseRotation otherwise commits image
		// A's typed degrees onto image B's just-reset 0\u00B0 (R17-2).
		host.registerTransientDialog(new AlertDialog.Builder(host.getActivity())
			.setTitle("Enter Rotation (\u00B0)")
			.setView(input)
			.setPositiveButton(DialogStrings.APPLY, (dialog, which) -> applyPreciseRotation(input))
			.setNegativeButton(DialogStrings.CANCEL, null)
			.show());
	}
}
