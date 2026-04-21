package com.cropcenter;

import androidx.activity.result.ActivityResultLauncher;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host surface consumed by SaveController, ExportPipeline, and ReplaceStrategy. Adds the
 * save-flow pieces (busy flag, SAF launcher, progress overlay, feedback toasts) on top of
 * the common EditorHost plumbing.
 */
interface SaveHost extends EditorHost
{
	/**
	 * Shared busy flag gating Save and Load so rapid taps can't stack two background
	 * threads that both mutate CropState.
	 */
	AtomicBoolean getBusy();

	/**
	 * Launcher registered in onCreate for the ACTION_CREATE_DOCUMENT save-as flow.
	 * SaveController triggers it with a pre-filled filename.
	 */
	ActivityResultLauncher<String> getSaveAsLauncher();

	/**
	 * Hide the full-screen progress overlay. Safe to call from any thread.
	 */
	void hideProgress();

	/**
	 * Toggle Save / Open button enabled state while a background task is running.
	 */
	void setBusyUi(boolean busy);

	/**
	 * Post the shared "Busy — try again" toast. Used when a user triggers Save/Load while
	 * another task is already running.
	 */
	void showBusyToast();

	/**
	 * Show the full-screen progress overlay with a status message. Safe to call from
	 * any thread.
	 */
	void showProgress(String msg);

	/**
	 * UI-thread-safe toast helper — noop if Activity is destroyed. Callers that need a
	 * toast from a background thread route through here rather than Toast.makeText
	 * directly.
	 */
	void toastIfAlive(String msg, int length);
}
