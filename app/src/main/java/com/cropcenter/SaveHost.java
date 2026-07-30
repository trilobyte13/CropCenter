package com.cropcenter;

import android.app.AlertDialog;
import android.content.DialogInterface;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.UiThread;

/**
 * Host surface consumed by SaveController, ExportPipeline, and ReplaceStrategy. Adds the save-flow pieces
 * (clearTransientDialog, SaveAs launcher, setActiveTransientDialog) on top of the common EditorHost plumbing (busy +
 * progress + toast + registerTransientDialog moved there to remove duplication with ToolbarHost).
 */
interface SaveHost extends EditorHost
{
	/**
	 * Clear the active-transient-dialog tracking when the supplied dialog matches the currently tracked reference.
	 * Used by dialogs that install their own composite OnDismissListener (because registerTransientDialog would
	 * overwrite the listener and break the dialog's own cleanup) — the dialog includes this callback in its
	 * composite listener so the host's tracking still releases when the dialog dismisses.
	 *
	 * @param dialog dialog that just dismissed; no-op when it doesn't match the tracked reference
	 */
	@UiThread
	void clearTransientDialog(DialogInterface dialog);

	/**
	 * The Activity-registered SAF launcher for the ACTION_CREATE_DOCUMENT save-as flow. Must be registered in
	 * onCreate (Activity contract — registerForActivityResult after START throws) and surfaced here so
	 * SaveController can trigger it with a pre-filled filename without depending on the Activity directly.
	 *
	 * @return launcher registered in onCreate; SaveController.launch(filename) → SAF picker →
	 *         onSaveAsLauncherResult
	 */
	ActivityResultLauncher<String> getSaveAsLauncher();

	/**
	 * Track an AlertDialog as the active transient dialog WITHOUT wrapping its OnDismissListener. Companion to
	 * registerTransientDialog for dialogs that install their own composite OnDismissListener which already includes
	 * clearTransientDialog as one of its callbacks. Calling registerTransientDialog instead would overwrite the
	 * composite listener and break the dialog's own cleanup paths.
	 *
	 * @param dialog dialog to track; must already have an OnDismissListener that invokes clearTransientDialog
	 */
	@UiThread
	void setActiveTransientDialog(AlertDialog dialog);
}
