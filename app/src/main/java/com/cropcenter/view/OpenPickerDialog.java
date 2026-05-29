package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;

import java.io.File;
import java.util.function.Consumer;

/**
 * In-app image picker — replaces the system SAF ACTION_OPEN_DOCUMENT picker for the Open flow on
 * devices where Samsung One UI's DocumentsUI doesn't persist user sort preferences across launches.
 * Delegates the actual filesystem-browser UI (title block + breadcrumb + thumbnail grid/list +
 * folder navigation + thumbnail decode) to FolderBrowser; this file owns just the AlertDialog
 * frame, the file-tap → dismiss-and-callback lifecycle, and the OnDismissListener composition.
 *
 * The FolderBrowser is constructed with a non-null onFileTapped callback (this::onFilePicked), so
 * file rows / grid cells are tap-to-select with ripple feedback. The card height is governed by the
 * shared `FolderBrowser.CARD_RESERVED_DP`, so the Open / Graft / Save dialogs all open at the same
 * maximum size. The Open dialog simply has no options / filename row, so its card devotes that space
 * to the file list instead.
 *
 * Transient-dialog tracking uses host.setActiveTransientDialog (not registerTransientDialog) plus
 * a hostDismissCallback because the dialog installs its own composite OnDismissListener for
 * FolderBrowser.shutdown — same shape as FolderPickerDialog.
 */
public final class OpenPickerDialog
{
	private static final String TAG = "OpenPickerDialog";

	private final Consumer<File> onPicked;
	private final Context ctx;
	// Use the Android-native DialogInterface.OnDismissListener (instead of Consumer<DialogInterface>)
	// so the type matches SettingsDialog / FolderPickerDialog's equivalent callback shape.
	private final DialogInterface.OnDismissListener hostDismissCallback;
	private final FolderBrowser browser;
	private AlertDialog dialog;
	private boolean decided;

	/**
	 * Construct the in-app Open picker. Callers invoke show() to actually display it.
	 *
	 * @param ctx                 Context for inflation
	 * @param startDir            initial folder to display; FolderBrowser clamps to its rootDir
	 *                            if outside; null lands at rootDir
	 * @param onPicked            called with the picked File on tap, or null on Cancel /
	 *                            external dismiss
	 * @param hostDismissCallback host's transient-dialog tracking cleanup
	 * @param jpegOnly            when true, only JPEG files are tappable (PNGs render greyed-out);
	 *                            true for the graft picker (Apply External Edit splices JPEG bytes
	 *                            only), false for the general Open flow which accepts both formats
	 */
	public OpenPickerDialog(Context ctx, File startDir, Consumer<File> onPicked,
		DialogInterface.OnDismissListener hostDismissCallback, boolean jpegOnly)
	{
		this.ctx = ctx;
		this.onPicked = onPicked;
		this.hostDismissCallback = hostDismissCallback;
		float density = ctx.getResources().getDisplayMetrics().density;
		this.browser = new FolderBrowser(ctx, density, startDir, this::onFilePicked, jpegOnly);
	}

	/**
	 * Build and show the in-app Open picker. Returns the AlertDialog so the caller can pass it to
	 * the host's setActiveTransientDialog (NOT registerTransientDialog — the latter would
	 * overwrite the composite OnDismissListener installed here).
	 *
	 * @return the dialog after .show() has been called
	 */
	public AlertDialog show()
	{
		// No positive button — selection happens on file tap (forwarded by FolderBrowser into
		// onFilePicked via the constructor's onFileTapped callback). Cancel is the only explicit
		// button; back-press and external dismiss also route through the OnDismissListener that
		// FolderBrowser.attachToDialog installs below.
		dialog = new AlertDialog.Builder(ctx)
			.setView(browser.wrapInDialogMargins(browser.buildPanel()))
			.setNegativeButton(DialogStrings.CANCEL, null)
			.create();
		browser.attachToDialog(dialog, () -> decided, () -> onPicked.accept(null),
			hostDismissCallback);
		return dialog;
	}

	/**
	 * Commit the user's file selection. Sets `decided` BEFORE calling onPicked so the
	 * OnDismissListener doesn't double-fire onPicked(null) on the subsequent dismiss(). Errors
	 * from the callback are caught + logged so a misbehaving consumer can't strand the dialog
	 * half-dismissed.
	 *
	 * @param file file the user tapped — never null (FolderBrowser only forwards non-null files)
	 */
	private void onFilePicked(File file)
	{
		decided = true;
		try
		{
			onPicked.accept(file);
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "onPicked callback threw for " + file, e);
		}
		if (dialog != null && dialog.isShowing())
		{
			dialog.dismiss();
		}
	}
}
