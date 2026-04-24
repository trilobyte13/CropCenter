package com.cropcenter;

import android.widget.TextView;

import com.cropcenter.model.CenterMode;
import com.cropcenter.view.CropEditorView;
import com.cropcenter.view.RotationRulerView;

/**
 * Host surface consumed by UiSync. Exposes cached view references plus lock-mode and
 * ruler-flag read/write endpoints used by the update... / sync... methods.
 */
interface UiHost extends EditorHost
{
	/**
	 * Reapply the active lock mode to CropState — forwards isPanning + getCurrentPref into
	 * CropState.setCenterMode, which fires the state listener.
	 */
	void applyLockMode();

	/**
	 * Currently-active lock-mode preference — select vs move modes track their own
	 * preferences, and this returns whichever one the current editor mode uses.
	 */
	CenterMode getCurrentPref();

	/**
	 * Cached reference to the main editor view resolved at onCreate.
	 */
	CropEditorView getEditorView();

	/**
	 * Move-mode lock preference (BOTH / HORIZONTAL / VERTICAL). Exposed separately from
	 * getCurrentPref so UiSync's mode-switch can demote BOTH → VERTICAL when leaving
	 * Select mode (BOTH is Select-only).
	 */
	CenterMode getMoveLockPref();

	/**
	 * Cached reference to the rotation ruler resolved at onCreate.
	 */
	RotationRulerView getRotationRuler();

	/**
	 * Cached reference to the rotation-degrees readout TextView.
	 */
	TextView getRotDegreesTextView();

	/**
	 * Sidebar TextView showing "WIDTH × HEIGHT" of the current crop rectangle.
	 */
	TextView getSidebarCropSizeTextView();

	/**
	 * Arrow between the "image size" and "crop size" info-bar readouts; hidden when no
	 * image is loaded.
	 */
	TextView getTransformArrowTextView();

	/**
	 * Zoom-factor badge shown in the editor view when zoom is above 1x.
	 */
	TextView getZoomBadgeTextView();

	/**
	 * True while UiSync.syncRotationUi is mid-update of the rotation ruler, gating the
	 * ruler's own change listener from re-entering CropState.setRotationDegrees.
	 */
	boolean isRulerUpdating();

	/**
	 * Directly set the move-mode lock preference, used by UiSync to demote
	 * Move + BOTH → Move + VERTICAL when leaving Select mode.
	 */
	void setMoveLockPref(CenterMode pref);

	/**
	 * Raise/clear the ruler-updating flag around a programmatic ruler write so the
	 * ruler's own change listener doesn't fire on the round-trip.
	 */
	void setRulerUpdating(boolean updating);
}
