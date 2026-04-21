package com.cropcenter;

import android.widget.TextView;

import com.cropcenter.model.CenterMode;
import com.cropcenter.view.CropEditorView;
import com.cropcenter.view.RotationRulerView;

/**
 * Host surface consumed by ToolbarBinder. Combines view access with the crop-state
 * transition methods the toolbar's onClick lambdas call (ensureCropCenter,
 * recomputeForLockChange, recenterOnSelection) and the feedback helpers the
 * auto-rotate / horizon-detect flow uses.
 */
interface ToolbarHost extends EditorHost
{
	/**
	 * Reapply the active lock mode to CropState.
	 */
	void applyLockMode();

	/**
	 * Seed a crop center at the image midpoint when none exists yet. Called from AR
	 * spinner / custom AR dialog flows so an aspect-ratio change takes immediate effect.
	 */
	void ensureCropCenter();

	CropEditorView getEditorView();

	RotationRulerView getRotationRuler();

	TextView getRotDegreesTextView();

	/**
	 * Hide the full-screen progress overlay. Safe to call from any thread.
	 */
	void hideProgress();

	boolean isCenterLocked();

	boolean isPanning();

	boolean isRulerUpdating();

	/**
	 * Move-mode axis switch — re-anchor the crop at the current selection midpoint
	 * without resizing.
	 */
	void recenterOnSelection();

	/**
	 * After a lock mode / editor mode / pan toggle, recompute the crop rectangle so the
	 * new mode's constraints are applied to the existing state.
	 */
	void recomputeForLockChange();

	/**
	 * Update the lock-mode preference for the currently-active editor mode.
	 */
	void setCurrentPref(CenterMode pref);

	/**
	 * Show the full-screen progress overlay with a status message. Safe to call from
	 * any thread.
	 */
	void showProgress(String msg);

	/**
	 * UI-thread-safe toast helper — noop if Activity is destroyed.
	 */
	void toastIfAlive(String msg, int length);
}
