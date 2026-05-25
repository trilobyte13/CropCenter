package com.cropcenter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.text.InputType;
import android.util.Log;
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
	private static final String TAG = "ToolbarBinder";

	// Spinner order: widest-landscape → square → tallest-portrait. The CropState aspect-ratio default
	// (R4_5) still wins on a fresh image — this array only drives the spinner's display order.
	private static final AspectRatio[] AR_VALUES = {
		AspectRatio.FREE, AspectRatio.R16_9, AspectRatio.R3_2, AspectRatio.R4_3,
		AspectRatio.R5_4, AspectRatio.R1_1, AspectRatio.R4_5, AspectRatio.R3_4,
		AspectRatio.R2_3, AspectRatio.R9_16, null
	};

	private static final String KEY_LAST_CUSTOM_AR_H = "last_custom_ar_h";
	private static final String KEY_LAST_CUSTOM_AR_W = "last_custom_ar_w";
	private static final String PREFS_NAME_CUSTOM_AR = "cropcenter_custom_ar";

	private static final String[] AR_LABELS = {
		"Full", "16:9", "3:2", "4:3", "5:4", "1:1", "4:5", "3:4", "2:3", "9:16", "Custom"
	};

	private final AutoRotateBinder autoRotate;
	private final ToolbarHost host;
	private final UiSync ui;
	// AR spinner adapter, cached so applyAndResetSpinner can call notifyDataSetChanged after updating
	// customArLabel — the closed-spinner view re-reads the (overridden) selected-position text from the
	// adapter. The dropdown's Custom row always reads the literal "Custom" string from AR_LABELS (only
	// the head-view's getView override substitutes customArLabel; getDropDownView does not).
	private ArrayAdapter<String> arAdapter;
	// Dynamic Custom row label. Holds the compact "W:H" form (no "Custom " prefix) so the spinner head
	// matches the compact numeric format of the preset rows once a custom AR is applied. The closed
	// spinner shows this string whenever customArActive is true (instead of the AR_LABELS preset text);
	// the dropdown's Custom row always shows AR_LABELS' literal "Custom" string regardless. Initialised
	// to null because the field is never read while customArActive is false — keeping it null surfaces
	// a future read-without-flag-check regression as an NPE rather than silently rendering a stale
	// literal.
	private String customArLabel;
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

	/**
	 * Re-read CropState and write the dependent toolbar UI: AR spinner head (including the custom-AR
	 * label substitution), Pan / Lock-center checkboxes, mode + lock highlights, and the active editor
	 * mode's lock preference. Called by MainActivity.installImageOnUi after
	 * RestoreController.applyIfPending consumes a saved bundle — installImageOnUi's earlier reset
	 * block unconditionally unchecks Pan / Lock and resets the lock prefs to BOTH / VERTICAL, so
	 * without this resync the toolbar would visually show new-load defaults while the model holds
	 * the restored Move+H or Select+V the user had at kill time.
	 *
	 * Listener suppression on chkPan / chkLockCenter (setOnCheckedChangeListener(null) → setChecked →
	 * restore) prevents the synthetic .setChecked from firing onPanCheckedChanged /
	 * onLockCenterCheckedChanged — those handlers would call recomputeForLockChange against the
	 * just-restored geometry and overwrite it. Same rationale as suppressArListener for the AR
	 * spinner.
	 *
	 * Lock-preference reconstruction: selectLockPref / moveLockPref are persisted as separate bundle
	 * keys (STATE_SELECT_LOCK_PREF / STATE_MOVE_LOCK_PREF in RestoreController) and re-seeded into
	 * MainActivity's fields BEFORE this method runs via the Outcome record applyIfPending returns —
	 * so by the time we get here, both prefs already match the user's pre-kill choices. The
	 * setCurrentPref call below is a defensive belt-and-braces write that keeps the active mode's
	 * pref consistent with state.getCenterMode() (which IS the committed centerMode when Pan was
	 * off); it's idempotent when MainActivity already re-seeded the right value. We skip it when
	 * centerMode == LOCKED (Pan was on at kill time) so the underlying axis preference that
	 * MainActivity restored from the bundle stays in place rather than being overwritten with the
	 * meaningless LOCKED sentinel.
	 */
	void syncFromState()
	{
		AspectRatio aspectRatio = host.getState().getAspectRatio();
		Spinner spinner = host.findViewById(R.id.spinnerAr);
		int targetPos = indexOfAspectRatio(aspectRatio);
		int parkPos = (targetPos == AR_VALUES.length - 1) ? 0 : targetPos;
		if (targetPos == AR_VALUES.length - 1)
		{
			// The restored AR matches no preset — it's a Custom W:H. Flip customArActive so the
			// closed spinner head substitutes the numeric "W:H" label via getView; park the spinner
			// at position 0 (FREE / Full) so a future tap on Custom (rightmost row) reopens the
			// dialog. Spinner suppresses no-op selections, so the park position MUST be non-Custom.
			customArLabel = Math.round(aspectRatio.width()) + ":" + Math.round(aspectRatio.height());
			customArActive = true;
			arAdapter.notifyDataSetChanged();
		}
		else if (customArActive)
		{
			customArActive = false;
			arAdapter.notifyDataSetChanged();
		}
		// Only set suppressArListener when the call will actually transition the spinner. Spinner
		// suppresses no-op (same-position) setSelection silently — onItemSelected never fires for
		// those — so a setSelection() to the already-shown position would leave suppressArListener
		// stuck at true, swallowing the user's next genuine AR pick. Repro: kill the process with
		// AR = R4_5 (default position 6), restore, then tap any other AR → model unchanged because
		// the listener early-returns on the suppress flag. Tap same AR again → works (flag cleared
		// by the first onItemSelected). The guard scopes the suppress window to the actual
		// position-changing call.
		if (spinner.getSelectedItemPosition() != parkPos)
		{
			suppressArListener = true;
			spinner.setSelection(parkPos);
		}

		CenterMode centerMode = host.getState().getCenterMode();
		CheckBox chkPan = host.findViewById(R.id.chkPan);
		chkPan.setOnCheckedChangeListener(null);
		chkPan.setChecked(centerMode == CenterMode.LOCKED);
		chkPan.setOnCheckedChangeListener(this::onPanCheckedChanged);

		CheckBox chkLockCenter = host.findViewById(R.id.chkLockCenter);
		chkLockCenter.setOnCheckedChangeListener(null);
		chkLockCenter.setChecked(host.getState().isCenterLocked());
		chkLockCenter.setOnCheckedChangeListener(this::onLockCenterCheckedChanged);

		// Defensive belt-and-braces: re-derive the active-mode pref from the restored centerMode.
		// MainActivity.installImageOnUi has ALREADY re-seeded selectLockPref / moveLockPref from the
		// bundle's STATE_SELECT_LOCK_PREF / STATE_MOVE_LOCK_PREF before calling syncFromState, so
		// the active mode's pref already matches state.getCenterMode() when Pan was off — this write
		// is idempotent in that case. Skip when LOCKED (Pan was on at kill time): centerMode here is
		// the LOCKED sentinel, not a meaningful axis preference, so writing it through
		// setCurrentPref would overwrite the underlying axis pref MainActivity correctly restored
		// from the bundle with the meaningless LOCKED value.
		if (centerMode != CenterMode.LOCKED)
		{
			host.setCurrentPref(centerMode);
		}

		ui.updateModeHighlight();
		ui.updateLockHighlight();
	}

	/**
	 * Index of the given aspect ratio within AR_VALUES, or the Custom-sentinel index when ratio is null
	 * or matches no preset. Used by setupArSpinner to set the initial spinner position to whatever
	 * matches the model's current aspect ratio so the alphabetic-order reorder doesn't silently
	 * overwrite the model's default with position-0.
	 *
	 * @param ratio aspect ratio to look up
	 * @return index in AR_VALUES whose entry equals ratio; AR_VALUES.length - 1 (Custom row) when no
	 *         preset matches
	 */
	private static int indexOfAspectRatio(AspectRatio ratio)
	{
		for (int i = 0; i < AR_VALUES.length; i++)
		{
			if (AR_VALUES[i] != null && AR_VALUES[i].equals(ratio))
			{
				return i;
			}
		}
		return AR_VALUES.length - 1;
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
	 * with the previous preset.
	 *
	 * @param widthInput         custom-W EditText
	 * @param heightInput        custom-H EditText
	 * @param spinner            the AR spinner whose selection is reset
	 * @param previousArPosition non-Custom spinner position to restore after Apply
	 */
	private void applyCustomArAndResetSpinner(EditText widthInput, EditText heightInput,
		Spinner spinner, int previousArPosition)
	{
		// Parse once and cache. The prior implementation called applyCustomAspectRatio (which parses
		// width + height for the model write), then re-parsed both EditTexts here for the label
		// substring — three parseIntOr calls for two values that haven't changed between calls.
		int ratioW = Math.max(1, parseIntOr(widthInput.getText().toString(), 16));
		int ratioH = Math.max(1, parseIntOr(heightInput.getText().toString(), 9));
		applyCustomAspectRatio(ratioW, ratioH);
		// Numeric "W:H" form (no "Custom " prefix) — the spinner head should display the active
		// ratio compactly, matching the preset rows ("16:9", "4:5", ...). Only getView (the head-view
		// override) substitutes customArLabel — getDropDownView keeps the AR_LABELS "Custom" string
		// on the dropdown row regardless of customArActive, so the row stays discoverable as the
		// edit affordance even after a custom AR has been applied.
		customArLabel = ratioW + ":" + ratioH;
		customArActive = true;
		arAdapter.notifyDataSetChanged();
		suppressArListener = true;
		spinner.setSelection(previousArPosition);
	}

	/**
	 * Apply the already-parsed width/height pair as a custom aspect ratio. Callers are expected to
	 * have already floored to ≥ 1 (applyCustomArAndResetSpinner does this once via parseIntOr +
	 * Math.max) so a stray "0" or empty input from the EditText falls back to a sane min before we
	 * reach here. After applying, if the user has selection points the engine recomputes the crop
	 * around them; otherwise the crop is centered on the image.
	 *
	 * @param ratioW width side of the ratio (≥ 1; caller is responsible for the floor)
	 * @param ratioH height side of the ratio (≥ 1; caller is responsible for the floor)
	 */
	private void applyCustomAspectRatio(int ratioW, int ratioH)
	{
		host.getState().setAspectRatio(new AspectRatio(ratioW, ratioH));
		// Persist the typed Custom AR so a later showCustomArDialog can pre-fill with these values
		// rather than the post-switch current AR. Without this, the flow "type Custom 2.39:1 →
		// switch to preset 1:1 → reopen Custom" would land on 1:1 (current AR) instead of 2.39:1
		// (last typed), forcing the user to re-type their custom ratio. Apply-only write (NOT on
		// Cancel) so a previewed-and-cancelled value doesn't poison the next session's default.
		host.getActivity().getSharedPreferences(PREFS_NAME_CUSTOM_AR, Context.MODE_PRIVATE)
			.edit()
			.putInt(KEY_LAST_CUSTOM_AR_W, ratioW)
			.putInt(KEY_LAST_CUSTOM_AR_H, ratioH)
			.apply();
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
	 * Parse the precise-rotation EditText and update CropState's rotation. The parsed float is clamped to
	 * [-180, 180] (so a user typing 200 commits as 180, not as a rejected input) and committed via
	 * setRotationDegrees, which applies the sub-epsilon snap-to-zero. Silently no-op on parse failure (the
	 * user's existing rotation is preserved and the dialog stays open). Invoked from
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
		host.getEditorView().recenterCropOnImageMidpoint();
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
		// if/else-if ladder: R.id.* in an app module is generated as non-`final int` (only library
		// modules generate `static final int`), so case R.id.btnLockBoth fails "constant expression
		// required".
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
		// customArLabel for the preset text whenever a custom AR is active (so the head reads the
		// numeric "5:7" instead of the previousArPosition preset). The DROPDOWN's Custom row always
		// reads the static "Custom" string from AR_LABELS — it's the edit affordance, so the literal
		// label keeps the row identifiable when the user reopens the spinner.
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
				return styleArLabel(tv, 13, padH * 2, padV * 2);
			}
		};
		spinner.setAdapter(arAdapter);
		// Default selection mirrors CropState's aspect-ratio default (R4_5). Position 0 in the
		// spinner is now FREE / Full after the alphabetic reorder, so an unconditional
		// setSelection(0) would silently overwrite the model's R4_5 default with FREE when the
		// initial onItemSelected fires.
		spinner.setSelection(indexOfAspectRatio(host.getState().getAspectRatio()));

		// Size the spinner head to fit a 2-digit:2-digit numeric label ("##:##") plus the dropdown
		// arrow. The closed head only ever displays numeric labels — preset strings like "4:5" /
		// "16:9" / "Full" and the customArLabel which is now also numeric ("W:H", no "Custom "
		// prefix) — so sizing for "99:99" covers the widest plausible custom AR (2-digit sides) and
		// every preset comfortably. The dropdown's "Custom" row (the only place the literal "Custom"
		// string still appears, and only when no custom has been applied yet) can overflow the head
		// width — Android's Spinner dropdown lays out independently and the row's TextView keeps
		// the text readable.
		Paint textPaint = new Paint();
		textPaint.setTextSize(12 * host.getActivity().getResources().getDisplayMetrics().scaledDensity);
		float maxTextPx = textPaint.measureText("99:99");
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
	 * Build and show the "Custom" aspect-ratio dialog with two number inputs (width, height). On Apply, the
	 * `applyCustomArAndResetSpinner` handler floors each parsed value to ≥ 1 via Math.max before calling
	 * applyCustomAspectRatio — applyCustomAspectRatio itself does NOT re-clamp (its Javadoc explicitly
	 * delegates the floor to the caller, and the Apply path is the only caller). The result triggers a
	 * recompute via setAspectRatio. Triggered by selecting the "Custom" sentinel in the AR spinner.
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
		// Pre-fill in this priority order:
		//   1. Last-typed Custom AR persisted to SharedPreferences (across switches to a preset
		//      AND across app restarts) — so the user's typed 2.39:1 survives a brief detour to
		//      a 1:1 preset and back. This is the dominant case once the user has used Custom
		//      at all.
		//   2. Currently-applied AR (preset or in-memory Custom) when no last-stored value
		//      exists — so a fresh-install user opening the Custom dialog for the first time
		//      sees the preset they're already on.
		//   3. 16:9 as the universal fallback when neither source has values.
		String initialW = "16";
		String initialH = "9";
		SharedPreferences customPrefs = host.getActivity()
			.getSharedPreferences(PREFS_NAME_CUSTOM_AR, Context.MODE_PRIVATE);
		int storedW = customPrefs.getInt(KEY_LAST_CUSTOM_AR_W, 0);
		int storedH = customPrefs.getInt(KEY_LAST_CUSTOM_AR_H, 0);
		if (storedW > 0 && storedH > 0)
		{
			initialW = String.valueOf(storedW);
			initialH = String.valueOf(storedH);
		}
		else
		{
			AspectRatio currentAr = host.getState().getAspectRatio();
			if (currentAr != null && !currentAr.isFree())
			{
				initialW = String.valueOf(Math.round(currentAr.width()));
				initialH = String.valueOf(Math.round(currentAr.height()));
			}
		}
		EditText editW = numberInput(initialW);
		layout.addView(editW, new LinearLayout.LayoutParams(editWidth, LinearLayout.LayoutParams.WRAP_CONTENT));

		TextView separator = new TextView(host.getActivity());
		separator.setText("  :  ");
		separator.setTextSize(16);
		layout.addView(separator);

		EditText editH = numberInput(initialH);
		layout.addView(editH, new LinearLayout.LayoutParams(editWidth, LinearLayout.LayoutParams.WRAP_CONTENT));

		// Cancel and dismiss (X / back-press / forced dismissTransientDialogs) all restore the spinner
		// to the prior non-Custom position so the visual selection matches the spinner's affordance
		// (back to a tap-to-select spot). suppressArListener gates the synthetic onItemSelected fire so
		// it does NOT commit AR_VALUES[previousArPosition] over state.aspectRatio. Without the suppress,
		// two leaks happen: (1) re-edit case — user has Custom AR active, opens Custom dialog to edit,
		// cancels — restore would commit a preset over the preserved Custom AR, silently replacing the
		// user's saved Custom W:H. (2) cross-load case — forced cancel from inbound load posts the
		// setSelection async, by which time state.reset has run and image B is loaded; the post-async
		// onItemSelected would commit a stale preset onto image B's preserved AR. Suppress closes both.
		Runnable restore = () ->
		{
			suppressArListener = true;
			spinner.setSelection(previousArPosition);
		};
		// Register the dialog with the host's transient-dialog tracker so a Share/View intent or graft
		// apply that arrives mid-dialog dismisses it before bg state.reset(). Without this, applying the
		// dialog's Custom values after a load would set state.aspectRatio + customArLabel for image A's
		// typed values onto image B's spinner.
		//
		// BadTokenException guard: the spinner's onItemSelected fires from a posted layout pass, so a
		// config-change race between the user tapping "Custom" and the dialog .show() is reachable.
		if (host.isDestroyed())
		{
			restore.run();
			return;
		}
		try
		{
			host.registerTransientDialog(new AlertDialog.Builder(host.getActivity())
				.setTitle("Custom Aspect Ratio")
				.setView(layout)
				.setPositiveButton(DialogStrings.APPLY, (dialog, which) ->
					applyCustomArAndResetSpinner(editW, editH, spinner, previousArPosition))
				.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> restore.run())
				.setOnCancelListener(dialog -> restore.run())
				.show());
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "custom AR dialog failed to show", e);
			restore.run();
		}
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
		// A's typed degrees onto image B's just-reset 0\u00B0.
		//
		// BadTokenException guard: the rot-degrees text click is forwarded through a posted layout, so
		// a config-change race between tap and .show() is reachable.
		if (host.isDestroyed())
		{
			return;
		}
		try
		{
			host.registerTransientDialog(new AlertDialog.Builder(host.getActivity())
				.setTitle("Enter Rotation (\u00B0)")
				.setView(input)
				.setPositiveButton(DialogStrings.APPLY, (dialog, which) -> applyPreciseRotation(input))
				.setNegativeButton(DialogStrings.CANCEL, null)
				.show());
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "precise-rotation dialog failed to show", e);
		}
	}
}
