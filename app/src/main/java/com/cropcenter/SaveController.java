package com.cropcenter;

import android.app.AlertDialog;
import android.net.Uri;

import com.cropcenter.model.ExportConfig;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.StoragePermissionHelper;
import com.cropcenter.view.SaveDialog;

import java.io.File;
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
	private final ReplaceStrategy replaceStrategy;
	private final SafFileHelper safFiles;
	private final SaveHost host;

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
		this.exportPipeline = new ExportPipeline(host, safFiles);
		// permissions is forwarded to ReplaceStrategy and not retained on this — SaveController
		// itself never prompts for permissions, so storing it would be dead state.
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
	 * matches the bytes we would write. Reject with a dialog and leave newUri on disk.
	 * We do NOT delete the placeholder here: ACTION_CREATE_DOCUMENT may have returned an
	 * existing zero-byte file after the provider's own Replace prompt, and that case is
	 * indistinguishable from a fresh SAF-created empty doc. Deleting on rejection would
	 * risk destroying a real user file; a leftover fresh placeholder is acceptable fallout.
	 *
	 * Otherwise:
	 *
	 * (A) chosen == requested — SAF kept the name. Either the file didn't exist (new file) or
	 *     SAF prompted "Replace?" and the user accepted. Since we can't distinguish the two
	 *     from the URI alone, always try to create a sibling placeholder and route through
	 *     ReplaceStrategy: this gives provider-confirmed overwrites the crash-safe write-
	 *     first-then-swap pattern. When createDocument isn't supported (opaque-ID
	 *     providers), fall back to exportToPreserving (writes to newUri directly but
	 *     doesn't delete on verification failure — minimises data loss on the narrow
	 *     fallback path) for ambiguous cases, or exportToOverwrite for confirmed
	 *     overwrites where the success toast should announce as "Replaced <name>".
	 *
	 * (B) chosen ends in an "(N)" auto-rename suffix AND the inferred base name still exists
	 *     in the picked directory — SAF silently renamed to dodge a collision. The detection
	 *     is derived from `chosen` alone, not from the original pendingSaveName, so a user
	 *     who edited the filename in the picker and still collided (typed "foo.jpg", SAF
	 *     returned "foo (1).jpg" because foo.jpg existed) also lands here. Offer Replace /
	 *     Keep / Cancel on the collided name. Replace overwrites the colliding original;
	 *     Keep saves at the auto-renamed location; Cancel cleans up the placeholder.
	 *
	 * (C) chosen differs from requested but NOT an auto-rename pattern (or "(N)" stripped
	 *     doesn't actually collide in that directory) — the user deliberately typed a
	 *     different name in the picker. Save as-is.
	 */
	void handleSaveAsResult(Uri newUri)
	{
		String requested = pendingSaveName;
		pendingSaveName = null;

		String chosen = safFiles.getDisplayName(newUri);

		// Extension-change guard: SAF set the document's MIME from `requested` before the picker
		// opened. If the user renamed ".jpg" → ".png" (or vice versa) in the picker, writing the
		// new format's bytes would land them in a document whose MIME still says the old type —
		// Gallery / MediaStore consumers would then misidentify the file. Reject the save and
		// redirect the user to the Save dialog's format picker.
		String requestedFormat = formatFromExtension(requested);
		String chosenFormat = formatFromExtension(chosen);
		if (requestedFormat != null && chosenFormat != null
			&& !requestedFormat.equals(chosenFormat))
		{
			savePending = false;
			// Do NOT delete newUri on this rejection path. The same-name save logic below
			// correctly treats priorSize <= 0 as ambiguous — a zero-byte URI can be either
			// a fresh SAF placeholder OR an already-existing empty file the provider
			// returned after its own Replace prompt. We can't tell from SAF alone, and
			// losing a real zero-byte file (unusual but valid) to a rejection cleanup is
			// strictly worse than leaving a disposable fresh placeholder behind. The
			// dialog tells the user to fix the format in the Save dialog; a leftover
			// placeholder is a minor file-manager annoyance, not data loss.
			showExtensionMismatchDialog(requested, chosen);
			return;
		}

		applyFormatFromFilename(chosen);

		// Case (A): SAF accepted the requested name exactly. Always route through the
		// crash-safe Replace flow — we can't rule out provider-confirmed overwrite from
		// the URI alone (SIZE == 0 may be an empty existing file, SIZE == -1 is unknown).
		// The priorSize query discriminates messaging:
		//   priorSize  > 0  → confirmed overwrite → wasOverwrite=true: toast "Replaced"
		//   priorSize  ≤ 0  → ambiguous (fresh doc OR zero-byte existing OR no-SIZE
		//                     provider) → wasOverwrite=false: toast "Saved"
		// Sibling placeholder creation is required for the full write-then-swap safety;
		// when that's unavailable (opaque-ID providers), fall back to a direct write with
		// preserveOnFailure.
		if (requested != null && chosen != null && requested.equalsIgnoreCase(chosen))
		{
			savePending = false;
			// wasOverwrite classification:
			//   priorSize >  0                → confirmed overwrite
			//   priorSize == 0                → ambiguous (treat as not-overwrite; empty
			//                                   placeholder nearly always, no meaningful
			//                                   content there either way)
			//   priorSize == -1 (no-SIZE)     → fall back to a content-stream probe; if the
			//                                   URI serves at least one byte it's a real
			//                                   existing file regardless of missing SIZE
			//                                   metadata. Probe returns false on empty /
			//                                   provider-refused / security-exception, which
			//                                   all coincide with "don't claim overwrite".
			long priorSize = safFiles.querySafFileSize(newUri);
			boolean wasOverwrite = priorSize > 0
				|| (priorSize < 0 && safFiles.hasExistingContent(newUri));
			String mime = ExportConfig.FORMAT_PNG.equals(host.getState().getExportConfig().format())
				? ExportConfig.PNG_MIME : ExportConfig.JPEG_MIME;
			String placeholderName = ".cropcenter-tmp-" + System.currentTimeMillis() + "-" + requested;
			Uri placeholder = safFiles.createSiblingPlaceholder(newUri, mime, placeholderName);
			if (placeholder != null)
			{
				replaceStrategy.replaceColliding(placeholder, requested, wasOverwrite);
			}
			else if (wasOverwrite)
			{
				// Opaque-ID + confirmed overwrite: can't placeholder. Direct overwrite
				// with preserve-on-failure. Pass `requested` so the success toast says
				// "Replaced <name>" — a generic "Saved N KB" would misrepresent a
				// confirmed overwrite.
				exportPipeline.exportToOverwrite(newUri, requested);
			}
			else
			{
				// Opaque-ID + ambiguous: can't confirm existing content, can't placeholder.
				// Preserve on failure so we don't destroy a file the user might own.
				exportPipeline.exportToPreserving(newUri);
			}
			return;
		}

		// Case (B): chosen has a "X (N).ext" auto-rename pattern. Detection works on `chosen`
		// alone so it catches the case where the user edited the filename in the picker and
		// THAT name collided — the "X" in "X (N)" doesn't have to match the original
		// pendingSaveName. Verify the inferred base still lives in the same directory before
		// showing the Replace dialog so a user who intentionally typed "foo (1).jpg" without
		// a real collision doesn't get offered Replace on a phantom. getDisplayName is a
		// more robust "does this document exist" probe than querySafFileSize > 0 — it catches
		// zero-byte files AND providers that don't expose OpenableColumns.SIZE.
		String autoRenameBase = autoRenameBaseName(chosen);
		if (autoRenameBase != null)
		{
			Uri baseUri = safFiles.deriveSiblingUri(newUri, autoRenameBase);
			if (siblingLooksLikeCollision(baseUri))
			{
				showReplaceDialog(newUri, autoRenameBase, chosen);
				return;
			}
		}

		// Case (C): user changed the name intentionally (no "(N)" suffix, or "(N)" stripped
		// doesn't collide with anything in the picked directory).
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
	 * Save button handler. Runs SaveDialog (format + grid-bake options); on its "Continue"
	 * the SAF picker opens with the correct extension pre-filled. Replace/Keep confirmation is
	 * handled downstream in handleSaveAsResult(); that's also where MANAGE_EXTERNAL_STORAGE
	 * is prompted if the user actually hits a collision — ordinary Save As flows no longer
	 * carry the overwrite-oriented permission UX.
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
			try
			{
				host.getSaveAsLauncher().launch(name);
			}
			catch (RuntimeException e)
			{
				// ActivityNotFoundException (no SAF picker installed) or similar provider
				// failure — without this clear, savePending stays true forever and every
				// subsequent Save tap hits the "Busy — try again" toast. Mirror ExportPipeline's
				// pre-enqueue guard: release the pending flag and rethrow so the caller sees
				// the real error.
				savePending = false;
				pendingSaveName = null;
				throw e;
			}
		});
	}

	/**
	 * Warn the user that renaming the extension in the SAF picker would produce a
	 * MIME/content mismatch and tell them to change format in the Save dialog instead.
	 * The caller does NOT delete the placeholder — ACTION_CREATE_DOCUMENT can return an
	 * existing zero-byte file after the provider's own Replace prompt, and that case is
	 * indistinguishable from a fresh empty doc; deleting on rejection would risk data
	 * loss. A disposable fresh placeholder left on disk is acceptable fallout.
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
				// Case B: user explicitly confirmed Replace on a detected collision — always
				// a real overwrite, so full Replace semantics (write-then-swap + "Replaced" toast).
				replaceStrategy.replaceColliding(newUri, requested, true);
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
	 * Decide whether a sibling URI (the inferred pre-auto-rename target) actually has a
	 * colliding document behind it. The "(N)" suffix on `chosen` could be either SAF
	 * auto-renaming around a real collision OR the user typing "(N)" intentionally in the
	 * picker — we can't tell from the suffix alone. Probe order:
	 *   1. getDisplayName non-null → document accessible and named → collision confirmed.
	 *   2. fileFromSafUri returns a File: File.exists() is the authoritative answer.
	 *   3. baseUri == null (opaque-ID provider can't derive the sibling) OR both probes
	 *      inconclusive: return false. We have no proof of collision and asserting one
	 *      surfaces a Replace dialog about a phantom file when the user just typed
	 *      "(N)" themselves. The fall-through path saves the user's "(N)" name as-is;
	 *      a real collision in this rare opaque-provider case becomes a duplicate save
	 *      under a different name (no data loss), not silent overwrite.
	 */
	private boolean siblingLooksLikeCollision(Uri baseUri)
	{
		if (baseUri == null)
		{
			return false;
		}
		if (safFiles.getDisplayName(baseUri) != null)
		{
			return true;
		}
		File baseFile = safFiles.fileFromSafUri(baseUri);
		if (baseFile != null)
		{
			// Filesystem accessible — authoritative answer regardless of SAF query result.
			return baseFile.exists();
		}
		// Both probes inconclusive. Without proof, prefer the false-negative outcome
		// (save under the SAF-assigned "(N)" name) over the false-positive Replace dialog.
		return false;
	}

	/**
	 * Detect SAF's auto-rename pattern on `chosen` alone and return the inferred base name
	 * (what the user was actually trying to save). Returns null when chosen doesn't look
	 * like an auto-rename.
	 *
	 * Pattern: "X (N).ext" where X is any non-empty stem and N is 1+ digits, optionally
	 * preceded by whitespace. The caller uses the returned base as the `requested` name
	 * for the Replace dialog and for ReplaceStrategy, so this correctly handles both:
	 * (1) classical auto-rename (pendingSaveName = "crop.jpg", chosen = "crop (1).jpg" → base
	 *     = "crop.jpg", matches the original suggestion)
	 * (2) user-edited-then-collided (pendingSaveName = "crop.jpg", user typed "foo.jpg",
	 *     chosen = "foo (1).jpg" → base = "foo.jpg", the ACTUAL name that collided).
	 *
	 * A false positive from a user typing "(N)" intentionally without a real collision is
	 * filtered at the call site by querying whether the base name actually exists.
	 */
	private static String autoRenameBaseName(String chosen)
	{
		if (chosen == null)
		{
			return null;
		}
		int choDot = chosen.lastIndexOf('.');
		if (choDot <= 0)
		{
			return null;
		}
		String choStem = chosen.substring(0, choDot);
		String choExt = chosen.substring(choDot);
		// Must end with "(digits)"; allow optional whitespace between the stem and the
		// open paren to match SAF variants that use "stem (1)" vs "stem(1)".
		if (!choStem.endsWith(")"))
		{
			return null;
		}
		int openParen = choStem.lastIndexOf('(');
		if (openParen <= 0)
		{
			return null;
		}
		String between = choStem.substring(openParen + 1, choStem.length() - 1);
		if (between.isEmpty())
		{
			return null;
		}
		for (int i = 0; i < between.length(); i++)
		{
			if (!Character.isDigit(between.charAt(i)))
			{
				return null;
			}
		}
		String baseStem = choStem.substring(0, openParen).stripTrailing();
		if (baseStem.isEmpty())
		{
			return null;
		}
		return baseStem + choExt;
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
}
