package com.cropcenter;

import android.app.AlertDialog;
import android.widget.TextView;

import com.cropcenter.model.CenterMode;
import com.cropcenter.view.CropEditorView;
import com.cropcenter.view.RotationRulerView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host surface consumed by ToolbarBinder. Combines view access with the crop-state transition methods the toolbar's
 * onClick lambdas call (ensureCropCenter, recomputeForLockChange, recenterOnSelection) and the feedback helpers the
 * auto-rotate / horizon-detect flow uses.
 */
interface ToolbarHost extends EditorHost
{
	/**
	 * Reapply the active lock mode to CropState.
	 */
	void applyLockMode();

	/**
	 * Seed a crop center at the image midpoint when none exists yet. Called from AR spinner / custom AR dialog
	 * flows so an aspect-ratio change takes immediate effect.
	 */
	void ensureCropCenter();

	/**
	 * @return shared busy flag gating Save / Load / horizon-detect so a long-running bg op can't be raced by
	 *         another. Holders compareAndSet(false, true) on entry and set(false) on exit.
	 */
	AtomicBoolean getBusy();

	CropEditorView getEditorView();

	TextView getRotDegreesTextView();

	RotationRulerView getRotationRuler();

	/**
	 * Hide the full-screen progress overlay. Safe to call from any thread.
	 */
	void hideProgress();

	boolean isPanning();

	boolean isRulerUpdating();

	/**
	 * Move-mode axis switch — re-anchor the crop at the current selection midpoint without resizing.
	 */
	void recenterOnSelection();

	/**
	 * After a lock mode / editor mode / pan toggle, recompute the crop rectangle so the new mode's constraints are
	 * applied to the existing state.
	 */
	void recomputeForLockChange();

	/**
	 * Track an AlertDialog as the active state-mutating transient dialog. Called by ToolbarBinder for the custom
	 * AR and precise-rotation dialogs (both commit to CropState directly) so a Share/View intent that arrives
	 * mid-dialog can dismiss it before its UI-thread commits race the bg state.reset(). See SaveHost equivalent
	 * for the full contract.
	 *
	 * @param newDialog dialog to track
	 * @return same dialog (for fluent chaining)
	 */
	AlertDialog registerTransientDialog(AlertDialog newDialog);

	/**
	 * Toggle Save / Open button enabled state while a background task is running.
	 *
	 * @param busy true while a save / load / detect task is in flight; false when complete
	 */
	void setBusyUi(boolean busy);

	/**
	 * Update the lock-mode preference for the currently-active editor mode.
	 *
	 * @param pref new center-lock preference (BOTH / HORIZONTAL / VERTICAL / LOCKED)
	 */
	void setCurrentPref(CenterMode pref);

	/**
	 * Post the shared "Busy — try again" toast. Used when the user triggers a bg-gated action while another task
	 * is already running.
	 */
	void showBusyToast();

	/**
	 * Show the full-screen progress overlay with a status message. Safe to call from any thread.
	 *
	 * @param msg status text shown over the overlay
	 */
	void showProgress(String msg);

	/**
	 * UI-thread-safe toast helper — noop if Activity is destroyed.
	 *
	 * @param msg    user-facing toast text
	 * @param length Toast.LENGTH_SHORT or Toast.LENGTH_LONG
	 */
	void toastIfAlive(String msg, int length);
}
