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
	 * Active lock-mode preference for the current editor mode. Select vs Move each track their own preference; this
	 * returns whichever the current editor mode uses.
	 *
	 * @return the active lock-mode preference for whichever editor mode is current
	 */
	CenterMode getCurrentPref();

	CropEditorView getEditorView();

	/**
	 * Move-mode lock preference, exposed separately from getCurrentPref so UiSync's mode-switch can demote Move +
	 * BOTH → Move + VERTICAL when leaving Select mode (BOTH is Select-only).
	 *
	 * @return the user's persisted Move-mode lock-axis preference (BOTH / HORIZONTAL / VERTICAL)
	 */
	CenterMode getMoveLockPref();

	TextView getRotDegreesTextView();

	RotationRulerView getRotationRuler();

	TextView getSidebarCropSizeTextView();

	TextView getTransformArrowTextView();

	TextView getZoomBadgeTextView();

	/**
	 * Re-entrancy guard for syncRotationUi: true while the helper is mid-write of the rotation ruler so the ruler's
	 * own change listener doesn't bounce back into CropState.setRotationDegrees.
	 *
	 * @return true while a programmatic ruler write is in progress
	 */
	boolean isRulerUpdating();

	/**
	 * Persist the user's Move-mode lock-axis preference. Called by UiSync's mode-switch to demote Move + BOTH →
	 * Move + VERTICAL when leaving Select mode.
	 *
	 * @param pref new Move-mode lock preference (BOTH / HORIZONTAL / VERTICAL)
	 */
	void setMoveLockPref(CenterMode pref);

	/**
	 * Flip the re-entrancy guard that gates the ruler's own change listener.
	 *
	 * @param updating true around a programmatic ruler write so the ruler's listener doesn't fire on the
	 *                 round-trip
	 */
	void setRulerUpdating(boolean updating);
}
