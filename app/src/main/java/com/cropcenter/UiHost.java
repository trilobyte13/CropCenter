package com.cropcenter;

import android.widget.TextView;

import com.cropcenter.model.CenterMode;
import com.cropcenter.view.CropEditorView;
import com.cropcenter.view.RotationRulerView;

/**
 * Host surface consumed by UiSync. Exposes cached view references plus lock-mode and ruler-flag read/write endpoints
 * used by the update... / sync... methods.
 */
interface UiHost extends EditorHost
{
	/**
	 * Reapply the active lock mode to CropState — forwards isPanning + getCurrentPref into CropState.setCenterMode,
	 * which fires the state listener.
	 */
	void applyLockMode();

	/**
	 * @return active lock-mode preference. Select vs move modes track their own preferences; this returns whichever
	 *         the current editor mode uses.
	 */
	CenterMode getCurrentPref();

	CropEditorView getEditorView();

	/**
	 * @return move-mode lock preference (BOTH / HORIZONTAL / VERTICAL). Exposed separately from getCurrentPref so
	 *         UiSync's mode-switch can demote BOTH → VERTICAL when leaving Select mode (BOTH is Select-only).
	 */
	CenterMode getMoveLockPref();

	/**
	 * Cached reference to the rotation-degrees readout TextView.
	 */
	TextView getRotDegreesTextView();

	/**
	 * Cached reference to the rotation ruler resolved at onCreate.
	 */
	RotationRulerView getRotationRuler();

	/**
	 * Sidebar TextView showing "WIDTH × HEIGHT" of the current crop rectangle.
	 */
	TextView getSidebarCropSizeTextView();

	/**
	 * Arrow between the "image size" and "crop size" info-bar readouts; hidden when no image is loaded.
	 */
	TextView getTransformArrowTextView();

	/**
	 * Zoom-factor badge shown in the editor view when zoom is above 1x.
	 */
	TextView getZoomBadgeTextView();

	/**
	 * @return true while UiSync.syncRotationUi is mid-update of the rotation ruler, gating the ruler's own change
	 *         listener from re-entering CropState.setRotationDegrees.
	 */
	boolean isRulerUpdating();

	/**
	 * @param pref new move-mode lock preference. Used by UiSync to demote Move + BOTH → Move + VERTICAL when
	 *             leaving Select mode.
	 */
	void setMoveLockPref(CenterMode pref);

	/**
	 * @param updating true around a programmatic ruler write so the ruler's own change listener doesn't fire on the
	 *                 round-trip.
	 */
	void setRulerUpdating(boolean updating);
}
