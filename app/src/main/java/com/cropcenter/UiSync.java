package com.cropcenter;

import android.view.View;

import com.cropcenter.model.CenterMode;
import com.cropcenter.model.EditorMode;
import com.cropcenter.util.TextFormat;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

/**
 * Centralised UI state sync: update* / sync* methods that reflect CropState changes into the
 * toolbar and info bar. All methods run on the UI thread. Every CropState-driven UI refresh in
 * the activity's state listener fans out through here.
 */
final class UiSync
{
	private final UiHost host;

	UiSync(UiHost host)
	{
		this.host = host;
	}

	/**
	 * Sync ruler + readout to current state rotation.
	 */
	void syncRotationUi()
	{
		float deg = host.getState().getRotationDegrees();
		boolean hasImage = host.getState().getSourceImage() != null;

		host.setRulerUpdating(true);
		host.getRotationRuler().setDegrees(deg);
		host.setRulerUpdating(false);
		host.getRotationRuler().setRulerEnabled(hasImage);

		// Clear the readout when there's nothing to rotate so the info bar doesn't display a
		// stale "0°" against no image.
		host.getRotDegreesTextView().setText(hasImage ? TextFormat.degrees(deg) : "");
	}

	/**
	 * Show / hide the (now-hidden) Auto rotate button based on whether an image is
	 * loaded. The button is hidden in production but the visibility plumbing is kept
	 * so it can be re-enabled via XML without touching code.
	 */
	void updateAutoRotateVisibility()
	{
		host.findViewById(R.id.btnAutoRotate).setVisibility(
			host.getState().getSourceImage() != null ? View.VISIBLE : View.GONE);
	}

	/**
	 * Update the info bar's "cropped size" readout (or "Full" when no crop is placed
	 * and an image is loaded, or blank before any image).
	 */
	void updateCropInfo()
	{
		boolean hasImage = host.getState().getSourceImage() != null;
		if (host.getState().hasCenter())
		{
			host.getSidebarCropSizeTextView().setText(
				host.getState().getCropW() + "\u00D7" + host.getState().getCropH());
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
	 * Highlight the active lock-mode button (Both / H / V) in mauve and the others
	 * in surface2. Reflects the current CenterMode preference.
	 */
	void updateLockHighlight()
	{
		CenterMode pref = host.getCurrentPref();
		int active = host.getActivity().getResources().getColor(R.color.mauve, null);
		int inactive = host.getActivity().getResources().getColor(R.color.surface2, null);
		MaterialButton btnLockBoth = host.findViewById(R.id.btnLockBoth);
		MaterialButton btnLockH = host.findViewById(R.id.btnLockH);
		MaterialButton btnLockV = host.findViewById(R.id.btnLockV);
		btnLockBoth.setTextColor(pref == CenterMode.BOTH ? active : inactive);
		btnLockH.setTextColor(pref == CenterMode.HORIZONTAL ? active : inactive);
		btnLockV.setTextColor(pref == CenterMode.VERTICAL ? active : inactive);
	}

	/**
	 * Highlight the active mode button (Move or Select) in mauve. Also toggles the
	 * visibility of the Both / Undo / Redo / Clear buttons, which are Select-only.
	 * Falls the move-lock preference back to Vertical when leaving Select mode if it
	 * was Both (a Select-only option).
	 */
	void updateModeHighlight()
	{
		EditorMode mode = host.getState().getEditorMode();
		int active = host.getActivity().getResources().getColor(R.color.mauve, null);
		int inactive = host.getActivity().getResources().getColor(R.color.surface2, null);
		MaterialButton btnModeMove = host.findViewById(R.id.btnModeMove);
		MaterialButton btnModeSelect = host.findViewById(R.id.btnModeSelect);
		btnModeMove.setTextColor(mode == EditorMode.MOVE ? active : inactive);
		btnModeSelect.setTextColor(mode == EditorMode.SELECT_FEATURE ? active : inactive);

		boolean isSelect = mode == EditorMode.SELECT_FEATURE;

		host.findViewById(R.id.btnLockBoth).setVisibility(isSelect ? View.VISIBLE : View.GONE);
		// BOTH is a Select-only option; fall back to Vertical when leaving Select mode.
		if (!isSelect && host.getMoveLockPref() == CenterMode.BOTH)
		{
			host.setMoveLockPref(CenterMode.VERTICAL);
			host.applyLockMode();
		}

		// Undo/Redo/Clear only visible in Select mode (they act on selection points)
		int pointCtrlVis = isSelect ? View.VISIBLE : View.GONE;
		host.findViewById(R.id.btnUndo).setVisibility(pointCtrlVis);
		host.findViewById(R.id.btnRedo).setVisibility(pointCtrlVis);
		host.findViewById(R.id.btnClearPoints).setVisibility(pointCtrlVis);
	}

	/**
	 * Refresh the enabled / disabled state and tint of the Undo / Redo / Clear buttons
	 * based on what the editor view's selection history can do right now.
	 */
	void updatePointButtonStates()
	{
		boolean canUndo = host.getEditorView().canUndo();
		boolean canRedo = host.getEditorView().canRedo();
		boolean hasPoints = !host.getState().getSelectionPoints().isEmpty();
		int enabledColor = host.getActivity().getResources().getColor(R.color.subtext0, null);
		int disabledColor = host.getActivity().getResources().getColor(R.color.surface1, null);

		MaterialButton btnUndo = host.findViewById(R.id.btnUndo);
		MaterialButton btnRedo = host.findViewById(R.id.btnRedo);
		MaterialButton btnClear = host.findViewById(R.id.btnClearPoints);

		btnUndo.setEnabled(canUndo);
		btnUndo.setTextColor(canUndo ? enabledColor : disabledColor);
		btnRedo.setEnabled(canRedo);
		btnRedo.setTextColor(canRedo ? enabledColor : disabledColor);
		btnClear.setEnabled(hasPoints);
		btnClear.setTextColor(
			hasPoints ? host.getActivity().getResources().getColor(R.color.red, null) : disabledColor);
	}

	/**
	 * Show / hide the zoom-level badge in the info bar. Hidden at zoom ≈ 1 (within
	 * the ≤1.01 dead zone) so the badge doesn't clutter fit-to-view state.
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
		// Compact format: "2.5x", "26x" — avoids huge "25600%"
		host.getZoomBadgeTextView().setText(zoom < 10f
			? String.format(Locale.ROOT, "%.1fx", zoom)
			: Math.round(zoom) + "x");
	}
}
