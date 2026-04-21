package com.cropcenter;

import android.app.AlertDialog;
import android.net.Uri;

import com.cropcenter.model.ExportConfig;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.StoragePermissionHelper;
import com.cropcenter.view.SaveDialog;

import java.util.Locale;

/**
 * Top-level save flow router: permission prompt → SaveDialog (format/grid) → SAF picker →
 * route result to ExportPipeline (plain save) or ReplaceStrategy (collision). Holds the
 * per-save state (pendingSaveName, savePending) and owns the user-facing dialogs that guard
 * the encode pipeline. Encode/write/verify itself lives in ExportPipeline; collision policy
 * in ReplaceStrategy.
 */
final class SaveController
{
	private final ExportPipeline exportPipeline;
	private final SaveHost host;
	private final ReplaceStrategy replaceStrategy;
	private final SafFileHelper safFiles;
	private final StoragePermissionHelper permissions;

	// Filename we asked SAF to create. When SAF silently auto-renames to avoid a collision (e.g.
	// "vacation.jpg" → "vacation (1).jpg"), the returned URI's display name won't match this —
	// that's how we detect the rename.
	private String pendingSaveName;
	// Set when we launch the SAF picker and cleared when its result arrives (URI or cancel) or
	// the Replace confirmation finishes. Gates rapid taps between launch and result, and also
	// between the result and the Replace dialog response — host.getBusy().get() doesn't flip
	// until exportTo actually runs, so it isn't sufficient on its own.
	private boolean savePending;

	SaveController(SaveHost host, SafFileHelper safFiles, StoragePermissionHelper permissions)
	{
		this.host = host;
		this.safFiles = safFiles;
		this.permissions = permissions;
		this.exportPipeline = new ExportPipeline(host, safFiles);
		this.replaceStrategy = new ReplaceStrategy(host, exportPipeline, safFiles, permissions);
	}

	/**
	 * Route the SAF-returned URI to the correct save path.
	 *
	 * SAF's ACTION_CREATE_DOCUMENT behaviour on filename collision is inconsistent across
	 * providers. The returned URI's display name tells us what actually happened.
	 *
	 * Preflight: if the user changed the extension to one that implies a different encoder
	 * (.jpg ↔ .png), the document's MIME — locked before the picker opened — no longer
	 * matches the bytes we would write. Reject with a dialog and delete the placeholder.
	 *
	 * Otherwise:
	 *
	 * (A) chosen == requested — SAF kept the name. Either the file didn't exist (new file) or SAF
	 *     prompted "Replace?" and the user accepted. Either way, write to newUri directly; SAF
	 *     already handled any confirmation, so no extra dialog from us.
	 *
	 * (B) chosen matches the "requested (N).ext" auto-rename pattern — SAF silently renamed to
	 *     dodge a collision. Offer Replace / Keep / Cancel on the chosen location. If the user
	 *     picks Replace we delete the colliding original, rename the new file to the requested
	 *     name, and write there. Cancel cleans up the placeholder.
	 *
	 * (C) chosen differs from requested but NOT in the auto-rename pattern — the user
	 *     deliberately changed the filename (same-format) in the picker. Save as-is.
	 */
	void handleSaveAsResult(Uri newUri)
	{
		String requested = pendingSaveName;
		pendingSaveName = null;

		String chosen = safFiles.getDisplayName(newUri);

		// Extension-change guard: SAF set the document's MIME from `requested` before the picker
		// opened. If the user renamed ".jpg" → ".png" (or vice versa) in the picker, writing the
		// new format's bytes would land them in a document whose MIME still says the old type —
		// Gallery / MediaStore consumers would then misidentify the file. Delete the placeholder
		// and redirect the user to the Save dialog's format picker.
		String requestedFormat = formatFromExtension(requested);
		String chosenFormat = formatFromExtension(chosen);
		if (requestedFormat != null && chosenFormat != null
			&& !requestedFormat.equals(chosenFormat))
		{
			safFiles.tryDeleteSafDocument(newUri);
			savePending = false;
			showExtensionMismatchDialog(requested, chosen);
			return;
		}

		applyFormatFromFilename(chosen);

		// Case (A): SAF accepted the requested name exactly.
		if (requested != null && chosen != null && requested.equalsIgnoreCase(chosen))
		{
			savePending = false;
			exportPipeline.exportTo(newUri);
			return;
		}

		// Case (B): SAF auto-renamed. Offer Replace / Keep / Cancel in the chosen directory.
		if (looksLikeAutoRename(requested, chosen))
		{
			showReplaceDialog(newUri, requested, chosen);
			return;
		}

		// Case (C): user changed the name intentionally.
		savePending = false;
		exportPipeline.exportTo(newUri);
	}

	/**
	 * SAF picker was cancelled — clear pending flags so Save re-enables.
	 */
	void onSaveCancelled()
	{
		savePending = false;
		pendingSaveName = null;
	}

	/**
	 * Save button handler. First re-checks MANAGE_EXTERNAL_STORAGE (it survives app UPDATE but
	 * is typically revoked on uninstall-then-reinstall) and offers to open Settings if missing —
	 * without it, Replace can leave an extra copy when the colliding file was created by a
	 * previous install. Then runs SaveDialog (format + grid-bake options); on its "Continue"
	 * the SAF picker opens with the correct extension pre-filled. Replace/Keep confirmation is
	 * handled downstream in handleSaveAsResult().
	 */
	void showSaveDialog()
	{
		if (host.getState().getSourceImage() == null)
		{
			return;
		}
		if (host.getBusy().get() || savePending)
		{
			host.showBusyToast();
			return;
		}
		if (!permissions.hasStoragePermission())
		{
			String message = "Without it, Replace can leave an extra copy when overwriting files "
				+ "created by a previous install of CropCenter. Grant now, then tap Save "
				+ "again when you return.";
			new AlertDialog.Builder(host.getActivity())
				.setTitle("Grant \u201CAll files access\u201D for reliable overwrite?")
				.setMessage(message)
				.setPositiveButton("Grant",
					(dialog, which) -> permissions.openStoragePermissionSettings())
				.setNegativeButton("Continue without", (dialog, which) -> openSaveOptionsDialog())
				.show();
			return;
		}
		openSaveOptionsDialog();
	}

	/**
	 * Pick encoder based on the extension the user typed in the SAF picker.
	 */
	private void applyFormatFromFilename(String name)
	{
		if (name == null)
		{
			return;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(ExportConfig.PNG_EXT))
		{
			host.getState().updateExportConfig(c -> c.withFormat(ExportConfig.FORMAT_PNG));
		}
		else if (lower.endsWith(ExportConfig.JPEG_EXT) || lower.endsWith(".jpeg"))
		{
			host.getState().updateExportConfig(c -> c.withFormat(ExportConfig.FORMAT_JPEG));
		}
		// Anything else leaves the format unchanged (source default).
	}

	private void openSaveOptionsDialog()
	{
		SaveDialog.show(host.getActivity(), host.getState(), () ->
		{
			if (host.getBusy().get() || savePending)
			{
				host.showBusyToast();
				return;
			}
			// Extension follows the format the user just picked in SaveDialog; if they change the
			// extension in the SAF picker, applyFormatFromFilename updates ExportConfig.format
			// again before encode.
			String stem = host.getState().getOriginalFilename();
			if (stem == null || stem.isEmpty())
			{
				stem = "crop";
			}
			int dot = stem.lastIndexOf('.');
			if (dot > 0)
			{
				stem = stem.substring(0, dot);
			}
			String name = stem
				+ (ExportConfig.FORMAT_PNG.equals(host.getState().getExportConfig().format())
					? ExportConfig.PNG_EXT : ExportConfig.JPEG_EXT);
			pendingSaveName = name;
			savePending = true;
			host.getSaveAsLauncher().launch(name);
		});
	}

	/**
	 * Warn the user that renaming the extension in the SAF picker would produce a
	 * MIME/content mismatch and tell them to change format in the Save dialog instead.
	 * The placeholder document has already been deleted by the caller.
	 */
	private void showExtensionMismatchDialog(String requested, String chosen)
	{
		if (host.isDestroyed())
		{
			return;
		}
		int reqDot = requested == null ? -1 : requested.lastIndexOf('.');
		int choDot = chosen == null ? -1 : chosen.lastIndexOf('.');
		String reqExt = reqDot < 0 ? "?" : requested.substring(reqDot);
		String choExt = choDot < 0 ? "?" : chosen.substring(choDot);
		String message = "The picker-created document was typed as " + reqExt
			+ ", but you renamed it to " + choExt + ". The document's MIME type was "
			+ "locked when the picker opened, so writing the new format's bytes would "
			+ "leave a file whose content and type disagree. Re-open Save and change "
			+ "the format in the format picker instead.";
		new AlertDialog.Builder(host.getActivity())
			.setTitle("Change format in Save, not the picker")
			.setMessage(message)
			.setPositiveButton("OK", null)
			.show();
	}

	/**
	 * Build and show the Replace / Keep / Cancel dialog for case (B) of the save flow.
	 */
	private void showReplaceDialog(Uri newUri, String requested, String safName)
	{
		String message = "A file with this name already exists in the selected location.\n\n"
			+ "Replace \u2014 overwrite it.\n"
			+ "Keep \u2014 save as \"" + safName + "\" instead.\n"
			+ "Cancel \u2014 don't save.";
		Runnable cleanupPlaceholder = () -> safFiles.tryDeleteSafDocument(newUri);
		new AlertDialog.Builder(host.getActivity())
			.setTitle("Replace " + requested + "?")
			.setMessage(message)
			.setPositiveButton("Replace", (dialog, which) ->
			{
				savePending = false;
				replaceStrategy.replaceColliding(newUri, requested);
			})
			.setNeutralButton("Keep", (dialog, which) ->
			{
				savePending = false;
				exportPipeline.exportTo(newUri); // newUri already has the SAF-assigned name
			})
			.setNegativeButton("Cancel", (dialog, which) ->
			{
				cleanupPlaceholder.run();
				savePending = false;
			})
			.setOnCancelListener(dialog -> // BACK or touch-outside behaves like Cancel
			{
				cleanupPlaceholder.run();
				savePending = false;
			})
			.show();
	}

	/**
	 * Return ExportConfig.FORMAT_JPEG or FORMAT_PNG for a filename's extension, or null when
	 * the extension is missing or unrecognised. Used by handleSaveAsResult to detect when a
	 * user's extension change in the SAF picker would produce a MIME/content mismatch.
	 */
	private static String formatFromExtension(String name)
	{
		if (name == null)
		{
			return null;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(ExportConfig.PNG_EXT))
		{
			return ExportConfig.FORMAT_PNG;
		}
		if (lower.endsWith(ExportConfig.JPEG_EXT) || lower.endsWith(".jpeg"))
		{
			return ExportConfig.FORMAT_JPEG;
		}
		return null;
	}

	/**
	 * Detect SAF's auto-rename naming pattern: "stem (N).ext" where stem/ext match the requested
	 * filename. Used to separate an SAF silent rename from the user genuinely typing a different
	 * name in the picker.
	 */
	private static boolean looksLikeAutoRename(String requested, String chosen)
	{
		if (requested == null || chosen == null)
		{
			return false;
		}
		int reqDot = requested.lastIndexOf('.');
		int choDot = chosen.lastIndexOf('.');
		if (reqDot < 0 || choDot < 0)
		{
			return false;
		}
		String reqExt = requested.substring(reqDot);
		String choExt = chosen.substring(choDot);
		if (!reqExt.equalsIgnoreCase(choExt))
		{
			return false;
		}
		String reqStem = requested.substring(0, reqDot);
		String choStem = chosen.substring(0, choDot);
		// "stem (1)", "stem (2)", … — accept 1+ digits, optional whitespace between stem and paren.
		return choStem.matches("\\Q" + reqStem + "\\E\\s*\\(\\d+\\)");
	}
}
