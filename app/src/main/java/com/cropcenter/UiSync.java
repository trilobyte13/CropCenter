package com.cropcenter;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.cropcenter.model.CenterMode;
import com.cropcenter.model.EditorMode;
import com.cropcenter.model.Format;
import com.cropcenter.util.TextFormat;

import java.util.Locale;

/**
 * Centralised UI state sync: update* / sync* methods that reflect CropState changes into the toolbar and info bar. All
 * methods run on the UI thread. Every CropState-driven UI refresh in the activity's state listener fans out through
 * here.
 */
final class UiSync
{
	private final UiHost host;

	UiSync(UiHost host)
	{
		this.host = host;
	}

	/**
	 * Sync ruler + readout to current state rotation. The degree readout is always shown — it sits
	 * as the centered anchor in the rotation actions row between the Auto and Reset chips, so an
	 * empty string would leave a visible hole; "0°" against an unrotated image accurately reflects
	 * what the rest of the pipeline applies (sub-epsilon rotations snap to exactly 0).
	 */
	void syncRotationUi()
	{
		float deg = host.getState().getRotationDegrees();
		boolean hasImage = host.getState().getSourceImage() != null;

		host.setRulerUpdating(true);
		host.getRotationRuler().setDegrees(deg);
		host.setRulerUpdating(false);
		host.getRotationRuler().setRulerEnabled(hasImage);

		host.getRotDegreesTextView().setText(TextFormat.degrees(deg));
		updateRotationZoomButtons();
	}

	/**
	 * Refresh the AR chip's displayed text and enabled-state coloring. The chip's text is whatever
	 * compact label fits the current model AspectRatio (preset name when one matches, "W:H" numeric
	 * form for custom ratios) — derived via ToolbarBinder.arLabel so the popup row labels and the
	 * chip-head text use the exact same source. Disabled (surface1) when no image is loaded since
	 * picking an AR with no source bitmap is a no-op; subtext0 when active so the chip reads as
	 * "tap me" rather than "active toggle" — AR isn't a toggle, it's a selector.
	 */
	void updateAspectRatioButton()
	{
		boolean hasImage = host.getState().getSourceImage() != null;
		int enabledColor = host.getActivity().getResources().getColor(R.color.subtext0, null);
		int disabledColor = host.getActivity().getResources().getColor(R.color.surface1, null);

		Button btn = host.findViewById(R.id.btnAspectRatio);
		btn.setText(ToolbarBinder.arLabel(host.getState().getAspectRatio()));
		btn.setEnabled(hasImage);
		btn.setTextColor(hasImage ? enabledColor : disabledColor);
	}

	/**
	 * Refresh the enable + tint of the rotation action row's three controls (Auto chip, Reset chip, centered
	 * degree label) based on whether an image is loaded. The name reads "chips" because all three are visually
	 * chip-tier; the degree label is a TextView but shares the row and the same active / disabled palette.
	 * All three stay visible — the user sees the affordances and that they're currently inactive. Text colors:
	 *  - No image: disabled, color surface1 for Auto / Reset / degree label.
	 *  - Image loaded, idle Auto ("Auto" label) + Reset: enabled, color subtext0; degree label: mauve.
	 *  - Image loaded, paint mode active ("Cancel" label on Auto, color red): managed by AutoRotateBinder.
	 *    This method skips the color write on Auto in that state so we don't stomp on the red Cancel tint;
	 *    Reset and the degree label are unaffected by paint mode and are updated normally.
	 */
	void updateAutoRotateChips()
	{
		boolean hasImage = host.getState().getSourceImage() != null;
		int enabledColor = host.getActivity().getResources().getColor(R.color.subtext0, null);
		int disabledColor = host.getActivity().getResources().getColor(R.color.surface1, null);

		TextView btnAuto = host.findViewById(R.id.btnAutoRotate);
		btnAuto.setEnabled(hasImage);
		// Skip the color write on Auto while the editor is in horizon-paint mode — AutoRotateBinder owns
		// the red "Cancel" tint during that mode, and overwriting it here would visually break the paint
		// affordance.
		if (!host.getEditorView().isHorizonMode())
		{
			btnAuto.setTextColor(hasImage ? enabledColor : disabledColor);
		}

		TextView btnReset = host.findViewById(R.id.btnResetRotation);
		btnReset.setEnabled(hasImage);
		btnReset.setTextColor(hasImage ? enabledColor : disabledColor);

		// Degree label sits at the center of this row (FrameLayout layout_gravity=center). When no
		// image is loaded the row's actions are all disabled, so the label should visually match that
		// disabled state too — leaving it mauve when Auto / Reset are dimmed surface1 reads as "the
		// rotation value is currently meaningful", which it isn't pre-load. surface1 (the same color
		// the disabled chips use) keeps the row's "all dim" visual unity.
		int activeColor = host.getActivity().getResources().getColor(R.color.mauve, null);
		TextView txtRotDegrees = host.getRotDegreesTextView();
		txtRotDegrees.setTextColor(hasImage ? activeColor : disabledColor);
	}

	/**
	 * Update the info bar's "cropped size" readout (or "Full" when no crop is placed and an image is loaded, or
	 * blank before any image).
	 */
	void updateCropInfo()
	{
		boolean hasImage = host.getState().getSourceImage() != null;
		if (host.getState().hasCenter())
		{
			host.getSidebarCropSizeTextView().setText(
				host.getState().getCropW() + "×" + host.getState().getCropH());
		}
		else if (hasImage)
		{
			host.getSidebarCropSizeTextView().setText("Full");
		}
		else
		{
			host.getSidebarCropSizeTextView().setText("");
		}
		if (host.getTransformArrowTextView() != null)
		{
			host.getTransformArrowTextView().setVisibility(hasImage ? View.VISIBLE : View.GONE);
		}
	}

	/**
	 * Refresh the Grid toggle chip's selected state and color from the current GridConfig.enabled() and
	 * hasImage state. The chip uses the standard toggle-chip palette — mauve when on, surface2 when off,
	 * surface1 when disabled. setSelected mirrors the model so a state restore (or any other indirect
	 * change to gridConfig.enabled()) updates the chip without the click handler firing.
	 */
	void updateGridToggle()
	{
		boolean hasImage = host.getState().getSourceImage() != null;
		boolean gridOn = host.getState().getGridConfig().enabled();
		int activeColor = host.getActivity().getResources().getColor(R.color.mauve, null);
		int inactiveColor = host.getActivity().getResources().getColor(R.color.surface2, null);
		int disabledColor = host.getActivity().getResources().getColor(R.color.surface1, null);

		Button btn = host.findViewById(R.id.btnGridToggle);
		btn.setSelected(gridOn);
		btn.setEnabled(hasImage);
		btn.setTextColor(!hasImage ? disabledColor : (gridOn ? activeColor : inactiveColor));
	}

	/**
	 * Enable / disable the top-toolbar Graft icon based on whether a JPEG image is loaded. Graft's
	 * metadata splice can't run against a PNG. Settings is always enabled and Open is image-independent
	 * (busy-gated in MainActivity.setBusyUi); Save lives in setBusyUi too with its own hasImage + !busy
	 * gate. AR / Grid / Pin chips also live in the top toolbar but each has its own dedicated refresh
	 * method (updateAspectRatioButton, updateGridToggle, updateLockHighlight) that handles the
	 * hasImage-driven enable state — they don't need to ride this Graft-specific path.
	 */
	void updateImageDependentToolbar()
	{
		boolean hasImage = host.getState().getSourceImage() != null;
		boolean isJpeg = hasImage && host.getState().getSourceFormat() == Format.JPEG;

		View btnGraft = host.findViewById(R.id.btnGraft);
		btnGraft.setEnabled(isJpeg);
		btnGraft.setAlpha(isJpeg ? 1f : 0.4f);
	}

	/**
	 * Highlight the active lock-mode chips. Both / H / V reflect the underlying axis preference
	 * (getCurrentPref) — mauve when active, surface2 when inactive — so the user's preferred axis
	 * stays visible even while Pin overrides centerMode to LOCKED. Pin is the independent
	 * lock-everything toggle and lights up mauve when state.centerMode == LOCKED. Both is disabled
	 * in Move mode (Move-mode pref ∈ {H, V}) but stays visible so the user sees the affordance and
	 * understands it's not currently actionable.
	 */
	void updateLockHighlight()
	{
		CenterMode pref = host.getCurrentPref();
		boolean hasImage = host.getState().getSourceImage() != null;
		boolean isSelect = host.getState().getEditorMode() == EditorMode.SELECT_FEATURE;
		boolean pinOn = host.getState().getCenterMode() == CenterMode.LOCKED;
		int active = host.getActivity().getResources().getColor(R.color.mauve, null);
		int inactive = host.getActivity().getResources().getColor(R.color.surface2, null);
		int disabled = host.getActivity().getResources().getColor(R.color.surface1, null);
		Button btnLockBoth = host.findViewById(R.id.btnLockBoth);
		Button btnLockH = host.findViewById(R.id.btnLockH);
		Button btnLockV = host.findViewById(R.id.btnLockV);
		boolean bothEnabled = hasImage && isSelect;
		btnLockBoth.setEnabled(bothEnabled);
		btnLockBoth.setTextColor(!bothEnabled ? disabled : (pref == CenterMode.BOTH ? active : inactive));
		btnLockH.setEnabled(hasImage);
		btnLockH.setTextColor(!hasImage ? disabled : (pref == CenterMode.HORIZONTAL ? active : inactive));
		btnLockV.setEnabled(hasImage);
		btnLockV.setTextColor(!hasImage ? disabled : (pref == CenterMode.VERTICAL ? active : inactive));

		Button btnPin = host.findViewById(R.id.btnPin);
		btnPin.setSelected(pinOn);
		btnPin.setEnabled(hasImage);
		btnPin.setTextColor(!hasImage ? disabled : (pinOn ? active : inactive));
	}

	/**
	 * Highlight the active mode button (Move or Select) in mauve. Disables the mode buttons when no image is
	 * loaded (the editor has nothing to operate on). Falls the move-lock preference back to Vertical when
	 * leaving Select mode if it was Both — even though the Both button is now visible+disabled in Move mode,
	 * the underlying model invariant (Move-mode pref ∈ {H, V}) is unchanged.
	 */
	void updateModeHighlight()
	{
		EditorMode mode = host.getState().getEditorMode();
		boolean hasImage = host.getState().getSourceImage() != null;
		int active = host.getActivity().getResources().getColor(R.color.mauve, null);
		int inactive = host.getActivity().getResources().getColor(R.color.surface2, null);
		int disabled = host.getActivity().getResources().getColor(R.color.surface1, null);
		Button btnModeMove = host.findViewById(R.id.btnModeMove);
		Button btnModeSelect = host.findViewById(R.id.btnModeSelect);
		btnModeMove.setEnabled(hasImage);
		btnModeMove.setTextColor(!hasImage ? disabled : (mode == EditorMode.MOVE ? active : inactive));
		btnModeSelect.setEnabled(hasImage);
		btnModeSelect.setTextColor(!hasImage ? disabled
			: (mode == EditorMode.SELECT_FEATURE ? active : inactive));

		// BOTH is a Select-only option; fall back to Vertical when leaving Select mode so Move-mode lock-axis
		// pref stays in {H, V}. The Both button itself remains visible (disabled) in Move mode — the user sees
		// the affordance but can't enter Move+BOTH.
		if (mode != EditorMode.SELECT_FEATURE && host.getMoveLockPref() == CenterMode.BOTH)
		{
			host.setMoveLockPref(CenterMode.VERTICAL);
			host.applyLockMode();
		}
	}

	/**
	 * Refresh the enabled state and tint of the Undo / Redo / Clear buttons. The buttons stay VISIBLE in Move
	 * mode and render disabled / greyed-out instead of GONE — the Move-mode-hides-them approach reflows
	 * pointControlsRow on every mode switch, so the spec ("Disabled-controls-stay-visible principle" in
	 * REQUIREMENTS) keeps the affordance fixed in place and only drives enabled + textColor. Within Select
	 * mode the buttons additionally disable themselves when (a) no image is loaded or (b) the underlying
	 * history doesn't support the action.
	 */
	void updatePointButtonStates()
	{
		boolean hasImage = host.getState().getSourceImage() != null;
		boolean inSelect = host.getState().getEditorMode() == EditorMode.SELECT_FEATURE;
		boolean gate = hasImage && inSelect;
		boolean canUndo = gate && host.getEditorView().canUndo();
		boolean canRedo = gate && host.getEditorView().canRedo();
		boolean hasPoints = gate && !host.getState().getSelectionPoints().isEmpty();
		int enabledColor = host.getActivity().getResources().getColor(R.color.subtext0, null);
		int disabledColor = host.getActivity().getResources().getColor(R.color.surface1, null);

		Button btnUndo = host.findViewById(R.id.btnUndo);
		Button btnRedo = host.findViewById(R.id.btnRedo);
		Button btnClear = host.findViewById(R.id.btnClearPoints);

		// setVisibility is deliberately not touched — the XML declares VISIBLE and the buttons stay there
		// in every mode. setEnabled + setTextColor are the only state knobs the spec exercises.
		btnUndo.setEnabled(canUndo);
		btnUndo.setTextColor(canUndo ? enabledColor : disabledColor);
		btnRedo.setEnabled(canRedo);
		btnRedo.setTextColor(canRedo ? enabledColor : disabledColor);
		btnClear.setEnabled(hasPoints);
		btnClear.setTextColor(
			hasPoints ? host.getActivity().getResources().getColor(R.color.red, null) : disabledColor);
	}

	/**
	 * Refresh the enable / disable state of the ruler zoom-out (−) and zoom-in (+) buttons.
	 * A button is enabled only when (a) an image is loaded — the ruler itself is disabled until
	 * then — AND (b) further zoom in that direction is possible (the ruler isn't already at the
	 * MIN_PPD_FACTOR floor / MAX_PPD_FACTOR ceiling). Called from two sites: syncRotationUi
	 * (image-load state changes) and ToolbarBinder.setupRotation's onZoomChangedListener (pinch
	 * / button-press zoom changes).
	 */
	void updateRotationZoomButtons()
	{
		boolean hasImage = host.getState().getSourceImage() != null;
		int enabledColor = host.getActivity().getResources().getColor(R.color.subtext0, null);
		int disabledColor = host.getActivity().getResources().getColor(R.color.surface1, null);

		boolean zoomOutOk = hasImage && host.getRotationRuler().canZoomOut();
		boolean zoomInOk = hasImage && host.getRotationRuler().canZoomIn();

		TextView btnRotZoomOut = host.findViewById(R.id.btnRotZoomOut);
		TextView btnRotZoomIn = host.findViewById(R.id.btnRotZoomIn);
		btnRotZoomOut.setEnabled(zoomOutOk);
		btnRotZoomOut.setTextColor(zoomOutOk ? enabledColor : disabledColor);
		btnRotZoomIn.setEnabled(zoomInOk);
		btnRotZoomIn.setTextColor(zoomInOk ? enabledColor : disabledColor);
	}

	/**
	 * Show / hide the zoom-level badge in the info bar. Hidden at zoom ≈ 1 (within the ≤1.01 dead zone) so the
	 * badge doesn't clutter fit-to-view state.
	 */
	void updateZoomBadge()
	{
		float zoom = host.getEditorView().getZoom();
		if (zoom <= 1.01f)
		{
			host.getZoomBadgeTextView().setVisibility(View.GONE);
			return;
		}
		host.getZoomBadgeTextView().setVisibility(View.VISIBLE);
		// Compact format: "2.5x", "26x" — avoids huge "25600%". The %.1f format uses the system locale's
		// decimal separator (e.g. "2,5x" on de-DE) per CLAUDE.md's "system locale only for user-facing
		// display" rule — Locale.ROOT here would force a "." even where the user's OS expects ",", which
		// is the exact mismatch the rule exists to prevent for visible UI text. Explicit
		// Locale.getDefault() so the locale choice is auditable rather than implicit.
		host.getZoomBadgeTextView().setText(zoom < 10f
			? String.format(Locale.getDefault(), "%.1fx", zoom)
			: Math.round(zoom) + "x");
	}
}
