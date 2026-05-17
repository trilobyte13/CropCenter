package com.cropcenter;

import android.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host surface consumed by SaveController, ExportPipeline, and ReplaceStrategy. Adds the save-flow pieces (busy flag,
 * SAF launcher, progress overlay, feedback toasts) on top of the common EditorHost plumbing.
 */
interface SaveHost extends EditorHost
{
	/**
	 * @return shared busy flag gating Save and Load so rapid taps can't stack two background threads that both
	 *         mutate CropState.
	 */
	AtomicBoolean getBusy();

	/**
	 * @return launcher registered in onCreate for the ACTION_CREATE_DOCUMENT save-as flow. SaveController triggers
	 *         it with a pre-filled filename.
	 */
	ActivityResultLauncher<String> getSaveAsLauncher();

	/**
	 * Hide the full-screen progress overlay. Safe to call from any thread.
	 */
	void hideProgress();

	/**
	 * Track an AlertDialog as the active state-mutating transient dialog and install an OnDismissListener that
	 * clears the tracked reference when the dialog dismisses normally. Called by every dialog producer whose
	 * widgets commit to CropState directly (SaveDialog format/grid choices, custom AR dimensions, precise
	 * rotation value) and the Replace dialog (writes to the SAF target on Replace/Keep) so a Share/View intent
	 * or graft apply that arrives mid-dialog can dismiss it via dismissTransientDialogs() before bg
	 * state.reset() races the dialog's UI commits.
	 *
	 * @param newDialog dialog to track; passed through and returned for fluent chaining
	 * @return same dialog, returned so callers can chain or assign in one line
	 */
	AlertDialog registerTransientDialog(AlertDialog newDialog);

	/**
	 * Toggle Save / Open button enabled state while a background task is running.
	 *
	 * @param busy true while a save / load / detect task is in flight; false when complete
	 */
	void setBusyUi(boolean busy);

	/**
	 * Post the shared "Busy — try again" toast. Used when the user triggers Save/Load while another task is already
	 * running.
	 */
	void showBusyToast();

	/**
	 * Show the full-screen progress overlay with a status message. Safe to call from any thread.
	 *
	 * @param msg status text shown over the overlay
	 */
	void showProgress(String msg);

	/**
	 * UI-thread-safe toast helper — noop if Activity is destroyed. Callers that need a toast from a background
	 * thread route through here rather than Toast.makeText directly.
	 *
	 * @param msg    user-facing toast text
	 * @param length Toast.LENGTH_SHORT or Toast.LENGTH_LONG
	 */
	void toastIfAlive(String msg, int length);
}
