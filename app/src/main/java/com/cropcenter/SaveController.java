package com.cropcenter;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.cropcenter.model.Format;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.StoragePermissionHelper;
import com.cropcenter.view.DialogStrings;
import com.cropcenter.view.SaveDialog;

import java.io.File;

/**
 * Top-level save flow router: permission prompt → SaveDialog (format/grid) → SAF picker → route result to
 * ExportPipeline (plain save) or ReplaceStrategy (collision). Holds the per-save state (pendingSaveName, savePending)
 * and owns the user-facing dialogs that guard the encode pipeline. Encode/write/verify itself lives in ExportPipeline;
 * collision policy in ReplaceStrategy.
 */
final class SaveController
{
	/**
	 * Snapshot of state.exportConfig.format / state.gridConfig.includeInExport taken in
	 * openSaveOptionsDialog before SaveDialog opens. SaveDialog's "Continue" tap commits the user's
	 * in-dialog selections to CropState directly, but the user can still cancel the SAF picker that follows —
	 * without this snapshot the cancelled choices would silently bake into the next save (e.g. "Export Grid"
	 * stays enabled, or the next default extension is .png even though the user chose .jpg for the abandoned
	 * save). Restored on every abort path: SAF cancel, launcher.launch RuntimeException, post-dialog
	 * busy-toast early return. Bundling the fields as a record makes "no live snapshot" a single null
	 * check (priorSnapshot == null) instead of the previous parallel-fields shape that needed an
	 * "if priorFormat == null return" guard plus symmetric clear of priorIncludeGrid.
	 *
	 * sourceImage pins the Bitmap reference that was loaded when the snapshot was taken. A Share/View
	 * intent that arrives with the SaveDialog still open runs ImageLoadController.applyBytes →
	 * state.reset() + state.setSourceImage(newBmp) on the bg executor; the snapshot's sourceImage field
	 * still points at the OLD bitmap reference, so restorePriorSaveSettings can identify a stale snapshot
	 * by bitmap-reference inequality and skip the rollback. Without this guard the rollback would
	 * overwrite the new image's fresh exportConfig.format (set by setSourceFormat during the load) with
	 * the old image's format, defaulting the next save to JPEG over a PNG source (or vice versa).
	 */
	private record PriorSaveSnapshot(Bitmap sourceImage, Format format, boolean includeGrid) {}

	private static final String TAG = "SaveController";

	private final ExportPipeline exportPipeline;
	private final ReplaceStrategy replaceStrategy;
	private final SafFileHelper safFiles;
	private final SaveHost host;

	private PriorSaveSnapshot priorSnapshot;
	// Filename we asked SAF to create. When SAF silently auto-renames to avoid a collision (e.g. "vacation.jpg" →
	// "vacation (1).jpg"), the returned URI's display name won't match this — that's how we detect the rename.
	private String pendingSaveName;
	// Set when we launch the SAF picker and cleared when its result arrives (URI or cancel) or the Replace
	// confirmation finishes. Gates rapid taps between launch and result, and also between the result and the
	// Replace dialog response — host.getBusy().get() doesn't flip until exportTo actually runs, so it isn't
	// sufficient on its own.
	private boolean savePending;

	SaveController(SaveHost host, SafFileHelper safFiles, StoragePermissionHelper permissions)
	{
		this.host = host;
		this.safFiles = safFiles;
		this.exportPipeline = new ExportPipeline(host, safFiles);
		// permissions is forwarded to ReplaceStrategy and not retained on this — SaveController itself never
		// prompts for permissions, so storing it would be dead state.
		this.replaceStrategy = new ReplaceStrategy(host, exportPipeline, safFiles, permissions);
	}

	/**
	 * Detect SAF's auto-rename pattern on `chosen` alone and return the inferred base name (what the user was
	 * actually trying to save). Returns null when chosen doesn't look like an auto-rename.
	 *
	 * Pattern: "X (N).ext" where X is any non-empty stem and N is 1+ digits, optionally
	 * preceded by whitespace. The caller uses the returned base as the `requested` name
	 * for the Replace dialog and for ReplaceStrategy, so this correctly handles both:
	 * (1) classical auto-rename (pendingSaveName = "crop.jpg", chosen = "crop (1).jpg" → base
	 *     = "crop.jpg", matches the original suggestion)
	 * (2) user-edited-then-collided (pendingSaveName = "crop.jpg", user typed "foo.jpg",
	 *     chosen = "foo (1).jpg" → base = "foo.jpg", the ACTUAL name that collided).
	 *
	 * A false positive from a user typing "(N)" intentionally without a real collision is filtered at the call site
	 * by querying whether the base name actually exists.
	 *
	 * @param chosen SAF-returned filename (e.g. "crop (1).jpg")
	 * @return inferred base name when chosen matches the auto-rename pattern, null otherwise
	 */
	static String autoRenameBaseName(String chosen)
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
		// Must end with "(digits)"; allow optional whitespace between the stem and the open paren to match SAF
		// variants that use "stem (1)" vs "stem(1)".
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
		if (between.isEmpty() || !between.chars().allMatch(Character::isDigit))
		{
			return null;
		}
		String baseStem = choStem.substring(0, openParen).stripTrailing();
		if (baseStem.isEmpty())
		{
			return null;
		}
		return baseStem + choExt;
	}

	/**
	 * Route the SAF-returned URI to the correct save path.
	 *
	 * SAF's ACTION_CREATE_DOCUMENT behaviour on filename collision is inconsistent across providers. The returned
	 * URI's display name tells us what actually happened.
	 *
	 * Preflight: if the user changed the extension to one that implies a different encoder (.jpg ↔ .png), the
	 * document's MIME — locked before the picker opened — no longer matches the bytes we would write. Reject with a
	 * dialog and leave newUri on disk. We do NOT delete the placeholder here: ACTION_CREATE_DOCUMENT may have
	 * returned an existing zero-byte file after the provider's own Replace prompt, and that case is
	 * indistinguishable from a fresh SAF-created empty doc. Deleting on rejection would risk destroying a real user
	 * file; a leftover fresh placeholder is acceptable fallout.
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
	 *
	 * @param newUri SAF document URI returned by the ACTION_CREATE_DOCUMENT picker
	 */
	void handleSaveAsResult(Uri newUri)
	{
		// Defensive try/finally guard: any unhandled exception from a SAF query during the result handler
		// (safFiles.getDisplayName, safFiles.deriveSiblingUri, siblingLooksLikeCollision,
		// applyFormatFromFilename) would otherwise escape with savePending stuck at true, leaving every
		// subsequent Save tap hitting "Busy — try again" until app restart. handleSaveAsResultBody returns
		// true ONLY when the Replace dialog branch successfully took ownership of the savePending lifecycle
		// (the dialog's per-button / cancel / BadTokenException handlers manage it); every other path
		// returns false and the finally clears the flag.
		boolean dialogTookOwnership = false;
		try
		{
			dialogTookOwnership = handleSaveAsResultBody(newUri);
		}
		finally
		{
			if (!dialogTookOwnership)
			{
				savePending = false;
			}
		}
	}
	/**
	 * SAF picker was cancelled — clear pending flags so Save re-enables.
	 */
	void onSaveCancelled()
	{
		savePending = false;
		pendingSaveName = null;
		// Roll back the format / grid-include selections SaveDialog committed before launching the picker.
		// Without this, a user who picked PNG + Export Grid in the dialog and then cancelled the SAF picker
		// would find PNG + Export Grid as the next default — silently changing the next save's encoding
		// based on a save the user explicitly abandoned.
		restorePriorSaveSettings();
	}

	/**
	 * Save button handler. Runs SaveDialog (format + grid-bake options); on its "Continue" the SAF picker opens
	 * with the correct extension pre-filled. Replace/Keep confirmation is handled downstream in
	 * handleSaveAsResult(); that's also where MANAGE_EXTERNAL_STORAGE is prompted if the user actually hits a
	 * collision — ordinary Save As flows no longer carry the overwrite-oriented permission UX.
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
		Format derived = Format.fromExtension(name);
		if (derived != null)
		{
			host.getState().updateExportConfig(c -> c.withFormat(derived));
		}
		// Unknown extension leaves the format unchanged (source default).
	}

	/**
	 * Cancel-time hook for SaveDialog: clear priorSnapshot when the dialog dismisses without Continue
	 * (user Cancel, back-press, outside-touch, or forced dismissTransientDialogs from a parallel load).
	 * The snapshot's sourceImage field holds a strong reference to the source Bitmap; without this clear,
	 * an abandoned dialog would pin the bitmap until the next openSaveOptionsDialog overwrites the field
	 * or the Activity tears down (Codex round-17 F4). The Continue path deliberately does NOT call this —
	 * handleSaveAsResultBody / onSaveCancelled / the launcher-exception path still own the snapshot
	 * rollback contract for a save that's reached SAF and then abandoned.
	 */
	private void clearPriorSnapshotOnCancel()
	{
		priorSnapshot = null;
	}

	/**
	 * Body of handleSaveAsResult — extracted so the public wrapper can manage the savePending lifecycle in
	 * try/finally. See the wrapper's comment for the ownership contract.
	 *
	 * @param newUri SAF document URI returned by the picker
	 * @return true when the Replace dialog branch took ownership of savePending and the wrapper must NOT clear
	 *         it; false on every other path (including the early-return mismatch and Cases A / C, which clear
	 *         savePending themselves before the wrapper's finally runs)
	 */
	private boolean handleSaveAsResultBody(Uri newUri)
	{
		String requested = pendingSaveName;
		pendingSaveName = null;
		// SAF returned a URI — the user committed to this save. Discard the format / grid-include snapshot
		// taken in openSaveOptionsDialog so a subsequent stray onSaveCancelled (e.g., a follow-up async
		// callback after a config change) can't roll back the now-baked choices.
		priorSnapshot = null;

		String chosen = safFiles.getDisplayName(newUri);

		// Extension-change guard: SAF set the document's MIME from `requested` before the picker opened. If the
		// user renamed ".jpg" → ".png" (or vice versa) — or to anything else like ".webp" / ".heic" — writing
		// the new format's bytes would land them in a document whose MIME still says the old type AND a
		// filename whose extension promises an encoding the encoder can't actually produce. Reject the save
		// and redirect the user to the Save dialog's format picker.
		//
		// The guard rejects whenever `chosen`'s extension doesn't match `requested`'s Format. This catches
		// three distinct misuses:
		//   - .jpg → .png (or vice versa): both map to known Formats but differ
		//   - .jpg → .webp (or .heic / any unknown image extension): chosen's Format lookup returns null
		//     but the extension is clearly NOT what the encoder will produce
		//   - .jpg → "filename-no-extension": ambiguous — let it through, the SAF MIME still says image/jpeg
		//     and the encoder's bytes are valid for that MIME
		Format requestedFormat = Format.fromExtension(requested);
		Format chosenFormat = Format.fromExtension(chosen);
		boolean chosenHasExt = chosen != null && chosen.lastIndexOf('.') > 0;
		boolean mismatch = requestedFormat != null && chosenHasExt && chosenFormat != requestedFormat;
		if (mismatch)
		{
			// Do NOT delete newUri on this rejection path. The same-name save logic below correctly treats
			// priorSize <= 0 as ambiguous — a zero-byte URI can be either a fresh SAF placeholder OR an
			// already-existing empty file the provider returned after its own Replace prompt. We can't tell
			// from SAF alone, and losing a real zero-byte file (unusual but valid) to a rejection cleanup
			// is strictly worse than leaving a disposable fresh placeholder behind. The dialog tells the
			// user to fix the format in the Save dialog; a leftover placeholder is a minor file-manager
			// annoyance, not data loss. The wrapper's finally clears savePending.
			showExtensionMismatchDialog(requested, chosen);
			return false;
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
			// savePending cleared by the wrapper's finally — the placeholder/overwrite/preserve dispatches
			// below are async and the user must be able to start a new Save after they return.
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
			boolean wasOverwrite = priorSize > 0 || (priorSize < 0 && safFiles.hasExistingContent(newUri));
			String mime = host.getState().getExportConfig().format().mimeType();
			String placeholderName = ".cropcenter-tmp-" + System.currentTimeMillis() + "-" + requested;
			Uri placeholder = safFiles.createSiblingPlaceholder(newUri, mime, placeholderName);
			if (placeholder != null)
			{
				replaceStrategy.replaceColliding(placeholder, requested, wasOverwrite);
			}
			else if (wasOverwrite)
			{
				// Opaque-ID + confirmed overwrite: can't placeholder. Direct overwrite with
				// preserve-on-failure. Pass `requested` so the success toast says "Replaced <name>" — a
				// generic "Saved N KB" would misrepresent a confirmed overwrite.
				exportPipeline.exportToOverwrite(newUri, requested);
			}
			else
			{
				// Opaque-ID + ambiguous: can't confirm existing content, can't placeholder. Preserve on
				// failure so we don't destroy a file the user might own.
				exportPipeline.exportToPreserving(newUri);
			}
			return false;
		}

		// Case (B): chosen has a "X (N).ext" auto-rename pattern. Detection works on `chosen` alone so it
		// catches the case where the user edited the filename in the picker and THAT name collided — the "X" in
		// "X (N)" doesn't have to match the original pendingSaveName. Verify the inferred base still lives in
		// the same directory before showing the Replace dialog so a user who intentionally typed "foo (1).jpg"
		// without a real collision doesn't get offered Replace on a phantom. getDisplayName is a more robust
		// "does this document exist" probe than querySafFileSize > 0 — it catches zero-byte files AND providers
		// that don't expose OpenableColumns.SIZE.
		String autoRenameBase = autoRenameBaseName(chosen);
		if (autoRenameBase != null)
		{
			Uri baseUri = safFiles.deriveSiblingUri(newUri, autoRenameBase);
			if (siblingLooksLikeCollision(baseUri))
			{
				// showReplaceDialog manages savePending across its button / cancel / BadTokenException
				// handlers. Return true so the wrapper's finally does NOT clear it — the dialog needs
				// savePending=true to gate parallel Save taps while the user decides.
				showReplaceDialog(newUri, autoRenameBase, chosen);
				return true;
			}
		}

		// Case (C): user changed the name intentionally (no "(N)" suffix, or "(N)" stripped doesn't collide
		// with anything in the picked directory). The wrapper's finally clears savePending after exportTo
		// dispatches its async write.
		exportPipeline.exportTo(newUri);
		return false;
	}

	/**
	 * Handle the Replace dialog's positive "Replace" button: full Replace semantics (write-then-swap +
	 * "Replaced" toast). Busy-rejection cleanup: when a parallel bg op (e.g. a Share/View intent's load
	 * that arrived while the user deliberated) holds busy, replaceColliding's downstream busy gate would
	 * silently reject and leave the SAF auto-rename target on disk. Run cleanupPlaceholder here so the
	 * orphan doesn't linger (R17-3).
	 *
	 * @param newUri             SAF auto-rename target URI ("foo (1).jpg")
	 * @param requested          user-typed name that collided (drives downstream toast wording)
	 * @param cleanupPlaceholder runs safFiles.tryDeleteSafDocument(newUri) on busy-rejection
	 */
	private void onReplaceConfirmed(Uri newUri, String requested, Runnable cleanupPlaceholder)
	{
		savePending = false;
		if (host.getBusy().get())
		{
			cleanupPlaceholder.run();
			host.toastIfAlive("Replace failed — try again", Toast.LENGTH_SHORT);
			return;
		}
		// Case B: user explicitly confirmed Replace on a real collision. wasOverwrite=true drives the
		// success toast wording ("Replaced X" vs "Saved X") downstream.
		replaceStrategy.replaceColliding(newUri, requested, true);
	}

	/**
	 * Handle the Replace dialog's neutral "Keep" button: commit the SAF auto-rename URI as the actual save
	 * (no overwrite of the colliding original). Same busy-rejection cleanup contract as onReplaceConfirmed
	 * — without it, exportTo's busy gate silently rejects and leaves the auto-rename SAF document
	 * stranded (R17-3).
	 *
	 * @param newUri             SAF auto-rename target URI (already has the SAF-assigned name)
	 * @param cleanupPlaceholder runs safFiles.tryDeleteSafDocument(newUri) on busy-rejection
	 */
	private void onReplaceKeep(Uri newUri, Runnable cleanupPlaceholder)
	{
		savePending = false;
		if (host.getBusy().get())
		{
			cleanupPlaceholder.run();
			host.toastIfAlive("Save failed — try again", Toast.LENGTH_SHORT);
			return;
		}
		exportPipeline.exportTo(newUri);
	}

	/**
	 * SaveDialog "Continue" handler — runs once the user has chosen format / grid options. Re-checks busy +
	 * savePending (the dialog interaction itself can race a parallel Save tap), derives the default filename from
	 * the source-image stem and the just-picked format extension, then launches the SAF CreateDocument picker.
	 * Releases the pending flag on launch failure so a missing-picker exception doesn't strand subsequent Save
	 * attempts behind a permanent "Busy" toast.
	 */
	private void onSaveDialogConfirmed()
	{
		if (host.getBusy().get() || savePending)
		{
			host.showBusyToast();
			// SaveDialog already committed the user's format / grid-include selections to state; rolling
			// them back here is the post-dialog equivalent of the SAF-cancel path. Without this, a busy
			// toast on the Continue tap would still leave the dialog's choices applied silently.
			restorePriorSaveSettings();
			return;
		}
		// Extension follows the format the user just picked in SaveDialog; if they change the extension in the
		// SAF picker, applyFormatFromFilename updates ExportConfig.format again before encode.
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
		String ext = host.getState().getExportConfig().format().extension();
		String name = stem + ext;
		pendingSaveName = name;
		savePending = true;
		try
		{
			host.getSaveAsLauncher().launch(name);
		}
		catch (RuntimeException e)
		{
			// ActivityNotFoundException (no SAF picker installed) or similar provider failure — without
			// this clear, savePending stays true forever and every subsequent Save tap hits the "Busy — try
			// again" toast. Mirror ExportPipeline's pre-enqueue guard: release the pending flag and rethrow
			// so the caller sees the real error. Also restore the dialog's mutations — the picker never
			// opened, so the user's format / grid-include picks shouldn't bleed into a future save.
			savePending = false;
			pendingSaveName = null;
			restorePriorSaveSettings();
			throw e;
		}
	}

	/**
	 * Drive the Save Options sub-dialog (format / grid-include picker) before the SAF picker. Snapshots
	 * the current export config + source bitmap into priorSnapshot BEFORE showing — SaveDialog mutates
	 * state directly on its Continue tap, and a subsequent SAF cancellation triggers
	 * restorePriorSaveSettings to unwind those mutations. Three failure paths the snapshot must clear
	 * to avoid leaking the source bitmap reference:
	 *   - the user Cancels / back-presses the dialog (clearPriorSnapshotOnCancel via the registered
	 *     transient-dialog tracker)
	 *   - a parallel load forces dismissTransientDialogs (same callback as user cancel)
	 *   - the dialog itself fails to show (BadTokenException from a config-change race) — the catch
	 *     below clears priorSnapshot directly because the OnCancelListener never registers (R17 F4)
	 *
	 * isDestroyed pre-check is the first line of defense against the config-change race; the
	 * try/catch around .show is the second (the race window between the check and the actual show is
	 * still open). Mirrors the pattern in showReplaceDialog and showExtensionMismatchDialog.
	 */
	private void openSaveOptionsDialog()
	{
		// BadTokenException guard — if onDestroy ran between the user's Save tap and this call (rare but
		// reachable on config change racing the handler), AlertDialog.Builder would throw on .show(). The
		// isDestroyed pre-check + try/catch around the show call mirror the pattern in showReplaceDialog,
		// showExtensionMismatchDialog, and GraftController.confirmOversizedThenApply — config-change races
		// can land between the pre-check and the actual show, so the catch is the second line of defense.
		if (host.isDestroyed())
		{
			return;
		}
		// Snapshot the current export-format and grid-include selections BEFORE the dialog opens.
		// SaveDialog.applySettings mutates state directly on its Continue tap; if the user then cancels the
		// SAF picker, restorePriorSaveSettings unwinds the mutation so the cancelled choices don't bleed
		// into the next save attempt.
		priorSnapshot = new PriorSaveSnapshot(
			host.getState().getSourceImage(),
			host.getState().getExportConfig().format(),
			host.getState().getGridConfig().includeInExport());
		try
		{
			// Register the dialog with the host's transient-dialog tracker so a Share/View intent or graft
			// apply that arrives mid-dialog dismisses it before bg state.reset() — without this, the user's
			// Continue tap would commit image A's format/grid choices onto image B's state (R17-1).
			// onClearPriorSnapshotOnCancel runs on every cancel path (user Cancel, back-press,
			// outside-touch, forced dismissTransientDialogs) so the snapshot — which holds the source
			// Bitmap reference — doesn't pin memory after an abandoned dialog (Codex round-17 F4).
			host.registerTransientDialog(SaveDialog.show(host.getActivity(), host.getState(),
				this::onSaveDialogConfirmed, this::clearPriorSnapshotOnCancel));
		}
		catch (RuntimeException e)
		{
			// On show / register failure the OnCancelListener is never installed, so
			// clearPriorSnapshotOnCancel won't fire — clear the snapshot here so the source bitmap
			// reference doesn't stay pinned until the next openSaveOptionsDialog or activity teardown
			// (Codex round-18 F2).
			Log.w(TAG, "save options dialog failed to show", e);
			priorSnapshot = null;
		}
	}

	/**
	 * Roll back the export-format and grid-include selections committed by SaveDialog when the user cancels
	 * (or otherwise abandons) the save before SAF accepts a URI. Idempotent — called on every abort path
	 * (SAF cancel, launcher exception, post-dialog busy-toast); the priorSnapshot null-check makes a stale
	 * second invocation a no-op rather than overwriting state with stale values.
	 *
	 * Skips the rollback when the live source bitmap differs from the one captured in the snapshot. That
	 * means a Share/View intent arrived between openSaveOptionsDialog and now, replacing the loaded image —
	 * the snapshot's format / includeGrid belong to the previous image and would silently overwrite the
	 * new image's freshly-set format (PNG sources defaulting to JPEG on the next save, or the inverse).
	 * The snapshot is cleared either way so a subsequent abort path doesn't retry against the same stale
	 * data.
	 */
	private void restorePriorSaveSettings()
	{
		PriorSaveSnapshot snapshot = priorSnapshot;
		if (snapshot == null)
		{
			return;
		}
		priorSnapshot = null;
		if (snapshot.sourceImage() != host.getState().getSourceImage())
		{
			return;
		}
		Format format = snapshot.format();
		boolean includeGrid = snapshot.includeGrid();
		host.getState().updateExportConfig(c -> c.withFormat(format));
		host.getState().updateGridConfig(g -> g.withIncludeInExport(includeGrid));
	}

	/**
	 * Warn the user that renaming the extension in the SAF picker would produce a MIME/content mismatch and tell
	 * them to change format in the Save dialog instead. The caller does NOT delete the placeholder —
	 * ACTION_CREATE_DOCUMENT can return an existing zero-byte file after the provider's own Replace prompt, and
	 * that case is indistinguishable from a fresh empty doc; deleting on rejection would risk data loss. A
	 * disposable fresh placeholder left on disk is acceptable fallout.
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
		try
		{
			// BadTokenException guard mirrors openSaveOptionsDialog / showReplaceDialog /
			// GraftController.confirmOversizedThenApply — config-change races can land between the
			// isDestroyed pre-check and the actual show() call (the Activity finishes after the
			// pre-check but before WindowManager accepts the dialog). The catch keeps the warning
			// best-effort instead of crashing the UI thread (Codex round-19 F2).
			new AlertDialog.Builder(host.getActivity())
				.setTitle("Change format in Save, not the picker")
				.setMessage(message)
				.setPositiveButton(DialogStrings.OK, null)
				.show();
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "extension-mismatch dialog failed to show", e);
		}
	}

	/**
	 * Build and show the Replace / Keep / Cancel dialog for case (B) of the save flow. Guards against the
	 * Activity finishing between the SAF result and this prompt: without the isDestroyed gate, .show() throws
	 * BadTokenException before any button / cancel listener runs, leaving savePending stuck and the SAF
	 * placeholder undeleted. Mirrors the same pattern in openSaveOptionsDialog and showExtensionMismatchDialog.
	 */
	private void showReplaceDialog(Uri newUri, String requested, String safName)
	{
		Runnable cleanupPlaceholder = () -> safFiles.tryDeleteSafDocument(newUri);
		if (host.isDestroyed())
		{
			Log.w(TAG, "skipping replace-collision dialog on destroyed activity; cleaning placeholder");
			cleanupPlaceholder.run();
			savePending = false;
			return;
		}
		String message = "A file with this name already exists in the selected location.\n\n"
			+ "Replace \u2014 overwrite it.\n" + "Keep \u2014 save as \"" + safName + "\" instead.\n"
			+ "Cancel \u2014 don't save.";
		try
		{
			// Register with the host's transient-dialog tracker so a Share/View intent or graft apply
			// dismisses this dialog before the bg state.reset(). Without this, a stale Replace prompt
			// could outlive the source image it was opened for — pressing Replace/Keep then would write
			// image B's state to image A's SAF target (Codex round-17 F1). dismissTransientDialogs uses
			// cancel(), which fires the OnCancelListener below — so the cleanupPlaceholder + savePending
			// reset still run even on forced dismissal.
			host.registerTransientDialog(new AlertDialog.Builder(host.getActivity())
				.setTitle("Replace " + requested + "?")
				.setMessage(message)
				.setPositiveButton("Replace", (dialog, which) ->
					onReplaceConfirmed(newUri, requested, cleanupPlaceholder))
				.setNeutralButton("Keep", (dialog, which) -> onReplaceKeep(newUri, cleanupPlaceholder))
				.setNegativeButton(DialogStrings.CANCEL, (dialog, which) ->
				{
					cleanupPlaceholder.run();
					savePending = false;
				})
				.setOnCancelListener(dialog -> // BACK / touch-outside / forced dismissTransientDialogs
				{
					cleanupPlaceholder.run();
					savePending = false;
				})
				.show());
		}
		catch (RuntimeException e)
		{
			// BadTokenException if the activity died between the isDestroyed check and show, or any
			// other UI-thread throw from the dialog plumbing. Don't strand savePending or leak the
			// placeholder.
			Log.w(TAG, "replace-collision dialog failed to show", e);
			cleanupPlaceholder.run();
			savePending = false;
		}
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
		// Both probes inconclusive. Without proof, prefer the false-negative outcome (save under the
		// SAF-assigned "(N)" name) over the false-positive Replace dialog.
		return false;
	}
}
