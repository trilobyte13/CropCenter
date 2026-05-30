package com.cropcenter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.cropcenter.crop.CropEngine;
import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.EditorMode;
import com.cropcenter.util.DpToPx;
import com.cropcenter.view.DialogStrings;

/**
 * Wires the toolbar controls (mode / lock / pin / AR / grid / undo-redo / clear / rotation / auto-rotate) and the
 * Custom AR dialog. All onClick handlers route back into the activity for crop-state manipulation and into UiSync
 * for visual updates, keeping the binder free of any direct rendering or state mutation beyond what the
 * corresponding control conceptually owns.
 */
final class ToolbarBinder
{
	private static final String TAG = "ToolbarBinder";

	// Display order for the AR preset popup: widest-landscape → square → tallest-portrait, with Custom as the
	// trailing sentinel. The fresh-image default is orientation-aware (MainActivity.installImageOnUi seeds R5_4
	// for landscape / R4_5 for portrait) — this array only drives the popup's row order.
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

	ToolbarBinder(ToolbarHost host, UiSync ui)
	{
		this.host = host;
		this.ui = ui;
		this.autoRotate = new AutoRotateBinder(host);
	}

	/**
	 * Compact human-readable label for an AspectRatio — matches a preset's name when one matches
	 * ("4:5", "16:9", "Full"), or falls back to the numeric "W:H" form for custom ratios. Package-private
	 * static so UiSync.updateAspectRatioButton (the chokepoint that writes the AR chip's text) can call
	 * it without holding a ToolbarBinder reference.
	 *
	 * @param ar aspect ratio to format; null treated as FREE / "Full"
	 * @return short label suitable for the AR chip's text — preset name when ar matches one of
	 *         AR_VALUES, else numeric "W:H"
	 */
	static String arLabel(AspectRatio ar)
	{
		if (ar == null || ar.isFree())
		{
			return "Full";
		}
		// indexOfAspectRatio returns AR_VALUES.length - 1 (the Custom sentinel) on no match. Any other
		// returned index is guaranteed to hit a non-null preset, so the single bound check below is
		// sufficient — no need to re-check AR_VALUES[matchedIndex] != null.
		int matchedIndex = indexOfAspectRatio(ar);
		if (matchedIndex < AR_VALUES.length - 1)
		{
			return AR_LABELS[matchedIndex];
		}
		return Math.round(ar.width()) + ":" + Math.round(ar.height());
	}

	/**
	 * Entry point called once from MainActivity.onCreate, after setContentView and view lookups.
	 */
	void bindAll()
	{
		setupArButton();
		setupGridToggle();
		setupModeButtons();
		setupCenterModeButtons();
		setupPinToggle();
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
	 * Re-read CropState and write the dependent toolbar UI: AR chip text + visual state, Pin chip selected
	 * state, mode + lock highlights, and the active mode's lock preference. Called by
	 * MainActivity.installImageOnUi after a restore consumes a saved bundle — the earlier reset block unpins
	 * and resets the lock prefs to defaults, so without this resync the toolbar would show new-load defaults
	 * while the model holds the restored Move+H / Select+V.
	 *
	 * No listener-suppression needed: AR is set via popup choice (no synthetic re-fire) and Pin / Grid are
	 * MaterialButton chips driven by setSelected (no CompoundButton listener on programmatic set).
	 *
	 * Lock-preference reconstruction: selectLockPref / moveLockPref are re-seeded into MainActivity's fields
	 * BEFORE this runs (via applyIfPending's Outcome), so both already match the pre-kill choices. The
	 * setCurrentPref below is a defensive write keeping the active mode's pref consistent with
	 * state.getCenterMode() (idempotent when already re-seeded); skipped when centerMode == LOCKED (Pin was
	 * on) so the restored axis pref isn't overwritten with the meaningless LOCKED sentinel.
	 */
	void syncFromState()
	{
		CenterMode centerMode = host.getState().getCenterMode();

		View btnPin = host.findViewById(R.id.btnPin);
		btnPin.setSelected(centerMode == CenterMode.LOCKED);

		// Defensive belt-and-braces: re-derive the active-mode pref from the restored centerMode.
		// MainActivity.installImageOnUi has ALREADY re-seeded selectLockPref / moveLockPref from the
		// bundle's STATE_SELECT_LOCK_PREF / STATE_MOVE_LOCK_PREF before calling syncFromState, so
		// the active mode's pref already matches state.getCenterMode() when Pin was off — this write
		// is idempotent in that case. Skip when LOCKED (Pin was on at kill time): centerMode here is
		// the LOCKED sentinel, not a meaningful axis preference, so writing it through
		// setCurrentPref would overwrite the underlying axis pref MainActivity correctly restored
		// from the bundle with the meaningless LOCKED value.
		if (centerMode != CenterMode.LOCKED)
		{
			host.setCurrentPref(centerMode);
		}

		ui.updateAspectRatioButton();
		ui.updateGridToggle();
		ui.updateModeHighlight();
		ui.updateLockHighlight();
	}

	/**
	 * Index of the given aspect ratio within AR_VALUES, or the Custom-sentinel index when ratio is null
	 * or matches no preset. Used by arLabel to map a model AR to its preset label, and by the AR popup
	 * builder so the user's current preset appears first.
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

	/**
	 * Apply the user's typed Custom AR values, persist them for next-session pre-fill, and update the
	 * AR chip's displayed text via the standard UiSync refresh path (notifyChanged → applyStateToUi →
	 * UiSync.updateAspectRatioButton picks up the new ratio).
	 *
	 * @param widthInput  custom-W EditText
	 * @param heightInput custom-H EditText
	 */
	private void applyCustomAr(EditText widthInput, EditText heightInput)
	{
		int ratioW = Math.max(1, parseIntOr(widthInput.getText().toString(), 16));
		int ratioH = Math.max(1, parseIntOr(heightInput.getText().toString(), 9));
		applyCustomAspectRatio(ratioW, ratioH);
	}

	/**
	 * Apply the already-parsed width/height pair as a custom aspect ratio. Callers are expected to
	 * have already floored to ≥ 1 (applyCustomAr does this once via parseIntOr +
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

	private EditText numberInput(String initial)
	{
		EditText edit = new EditText(host.getActivity());
		edit.setInputType(InputType.TYPE_CLASS_NUMBER);
		edit.setText(initial);
		edit.setGravity(Gravity.CENTER);
		return edit;
	}

	/**
	 * AR-chip click handler. Builds the preset popup anchored at the chip and dispatches the picked
	 * row to the AR model (preset rows commit immediately, the trailing Custom row opens the typed-ratio
	 * dialog). Lives separately from setupArButton so the popup construction stays one job per method.
	 *
	 * @param anchor the AR chip View (PopupMenu uses it as the anchor and the activity context source)
	 */
	private void onAspectRatioClick(View anchor)
	{
		PopupMenu popup = new PopupMenu(host.getActivity(), anchor);
		for (int i = 0; i < AR_LABELS.length; i++)
		{
			popup.getMenu().add(0, i, i, AR_LABELS[i]);
		}
		popup.setOnMenuItemClickListener(item ->
		{
			int pos = item.getItemId();
			if (pos < AR_VALUES.length && AR_VALUES[pos] != null)
			{
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
				showCustomArDialog();
			}
			return true;
		});
		popup.show();
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
	 * Grid-toggle click handler. Flips the chip's selected state and writes the new value through to
	 * the model's GridConfig. UiSync's applyStateToUi path picks up the change and refreshes the chip
	 * color (mauve when on, surface2 when off) via updateGridToggle so the visual state matches the
	 * model after every flip.
	 *
	 * @param view the Grid chip (selection state is read post-toggle to derive the new model value)
	 */
	private void onGridToggleClick(View view)
	{
		boolean newState = !view.isSelected();
		view.setSelected(newState);
		host.getState().updateGridConfig(g -> g.withEnabled(newState));
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

		if (host.getState().getEditorMode() == EditorMode.SELECT_FEATURE && !host.isPanning())
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
	 * Mode-button click handler. Maps the tapped button's id to MOVE / SELECT_FEATURE, applies the resulting lock
	 * mode, refreshes the mode + lock highlight, and recomputes the crop when entering Select while not panning.
	 * Lives on the binder rather than each setup site so both mode buttons share one path.
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
		if (host.getState().getEditorMode() == EditorMode.SELECT_FEATURE && !host.isPanning())
		{
			host.recomputeForLockChange();
		}
		host.getEditorView().invalidate();
	}

	/**
	 * Pin-chip click handler. Toggles the chip's selected state, propagates it through applyLockMode
	 * (which derives centerMode from MainActivity.isPinned — which now reads btnPin.isSelected),
	 * refreshes the lock highlight, and recomputes the crop only when turning Pin OFF in Select mode —
	 * other transitions don't change the spatial framing.
	 *
	 * @param view the Pin chip; selection state is read post-toggle to drive the model
	 */
	private void onPinClick(View view)
	{
		boolean newState = !view.isSelected();
		view.setSelected(newState);
		host.applyLockMode();
		ui.updateLockHighlight();
		if (!newState && host.getState().getEditorMode() == EditorMode.SELECT_FEATURE)
		{
			host.recomputeForLockChange();
		}
		host.getEditorView().invalidate();
	}

	/**
	 * Rotation-ruler change handler. Suppresses recursive updates while the ruler itself is reflecting a
	 * programmatic setRotationDegrees from elsewhere (auto-rotate, Reset chip) — without that guard the
	 * RotationRulerView's onDraw -> setRotationDegrees -> notifyChanged feedback loop would burn CPU.
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
	 * Wire the AR chip: install the click handler that opens the preset popup, and seed the chip's
	 * initial text from the current model AR via UiSync.updateAspectRatioButton (no separate adapter to
	 * configure — text is just whatever arLabel returns for the current state's AspectRatio).
	 */
	private void setupArButton()
	{
		host.findViewById(R.id.btnAspectRatio).setOnClickListener(this::onAspectRatioClick);
		ui.updateAspectRatioButton();
	}

	private void setupCenterModeButtons()
	{
		host.findViewById(R.id.btnLockBoth).setOnClickListener(this::onLockButtonClick);
		host.findViewById(R.id.btnLockH).setOnClickListener(this::onLockButtonClick);
		host.findViewById(R.id.btnLockV).setOnClickListener(this::onLockButtonClick);
	}

	private void setupClearPointsButton()
	{
		host.findViewById(R.id.btnClearPoints).setOnClickListener(this::onClearPointsClick);
	}

	/**
	 * Wire the Grid chip's click handler. Seeds the chip's initial selected state from the current
	 * GridConfig so a freshly-built UI reflects the persisted Grid preference; UiSync.updateGridToggle
	 * takes over after the first applyStateToUi to keep the color in sync with the model on every
	 * subsequent state change.
	 */
	private void setupGridToggle()
	{
		View btn = host.findViewById(R.id.btnGridToggle);
		btn.setSelected(host.getState().getGridConfig().enabled());
		btn.setOnClickListener(this::onGridToggleClick);
	}

	private void setupModeButtons()
	{
		host.findViewById(R.id.btnModeMove).setOnClickListener(this::onModeButtonClick);
		host.findViewById(R.id.btnModeSelect).setOnClickListener(this::onModeButtonClick);
	}

	/**
	 * Wire the Pin chip's click handler. Seeds the chip's initial selected state from the current
	 * CenterMode (LOCKED ↔ pinned). Separate setup method from the Lock cluster because Pin is an
	 * independent toggle, not a member of the mutually-exclusive Both/H/V axis cluster — keeping
	 * the wire-up in its own method makes that semantic distinction visible at the setup site.
	 */
	private void setupPinToggle()
	{
		View btn = host.findViewById(R.id.btnPin);
		btn.setSelected(host.getState().getCenterMode() == CenterMode.LOCKED);
		btn.setOnClickListener(this::onPinClick);
	}

	private void setupRotation()
	{
		host.getRotationRuler().setOnRotationChangedListener(this::onRotationChanged);
		host.getRotationRuler().setOnZoomChangedListener(ui::updateRotationZoomButtons);
		host.getRotationRuler().setRulerEnabled(false); // disabled until an image loads

		// Ruler-zoom buttons. 2× per tap matches the pinch-zoom progression and lets the user step from coarse
		// (10°/major tick) to finest (0.01°/minor tick) in ~7 taps.
		host.findViewById(R.id.btnRotZoomOut).setOnClickListener(view ->
			host.getRotationRuler().zoomBy(0.5f));
		host.findViewById(R.id.btnRotZoomIn).setOnClickListener(view ->
			host.getRotationRuler().zoomBy(2f));
		// Reset-to-0° chip. Sibling of the Auto button on the rotation actions row — Auto computes
		// a rotation from the image edges and applies it; Reset clears any user rotation back to 0
		// without disturbing other crop state. Setting via CropState (rather than the ruler) so
		// the change funnels through the same state-bus path as a ruler drag, keeping the ruler
		// position, undo stack, and any listeners in lockstep.
		host.findViewById(R.id.btnResetRotation).setOnClickListener(view ->
			host.getState().setRotationDegrees(0f));
		ui.updateRotationZoomButtons();
	}

	private void setupUndoRedo()
	{
		host.findViewById(R.id.btnUndo).setOnClickListener(view -> host.getEditorView().undo());
		host.findViewById(R.id.btnRedo).setOnClickListener(view -> host.getEditorView().redo());
	}

	/**
	 * Build and show the "Custom" aspect-ratio dialog with two number inputs (width, height). On Apply,
	 * the applyCustomAr handler floors each parsed value to ≥ 1 via Math.max before calling
	 * applyCustomAspectRatio. The result triggers a recompute via setAspectRatio. Triggered by selecting
	 * the "Custom" row in the AR chip's preset popup.
	 *
	 * Cancel is a pure no-op: with the chip layout there's no spinner position to restore (the popup is
	 * built fresh on each open). The prior implementation had to suppress a synthetic onItemSelected
	 * fire that re-committed a stale preset over an in-memory custom AR; the popup-based pattern doesn't
	 * have that hazard because the popup doesn't carry persistent selection state.
	 */
	private void showCustomArDialog()
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

		// BadTokenException guard: a config-change race between the user tapping Custom in the popup
		// and the dialog .show() is reachable. registerTransientDialog tracks the open dialog so an
		// inbound Share/View intent or graft apply can dismiss it before bg state.reset().
		if (host.isDestroyed())
		{
			return;
		}
		try
		{
			host.registerTransientDialog(new AlertDialog.Builder(host.getActivity())
				.setTitle("Custom Aspect Ratio")
				.setView(layout)
				.setPositiveButton(DialogStrings.APPLY, (dialog, which) ->
					applyCustomAr(editW, editH))
				.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> { })
				.show());
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "custom AR dialog failed to show", e);
		}
	}

}
