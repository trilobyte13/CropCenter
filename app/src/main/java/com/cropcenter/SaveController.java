package com.cropcenter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import com.cropcenter.model.Format;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.StoragePermissionHelper;
import com.cropcenter.view.DialogStrings;
import com.cropcenter.view.FolderPickerDialog;
import com.cropcenter.view.SaveDialog;

import java.io.File;

/**
 * Top-level save flow router. Two code paths, gated by the MANAGE_EXTERNAL_STORAGE grant probed at Save-tap
 * time:
 *
 *   MES granted (primary path) — showMergedInAppDialog opens FolderPickerDialog with format chips + Export
 *     Grid + folder navigator + thumbnail grid all in one dialog; on Save here the picked folder is mapped
 *     to an externalstorage SAF document URI via SafFileHelper.buildExternalStorageDocumentUri and the write
 *     dispatches through dispatchInAppSave / routeCrashSafeSave with the same Replace / Rename / Cancel
 *     collision handling that the SAF flow uses. Bypasses Samsung's One UI ACTION_CREATE_DOCUMENT picker
 *     entirely (it hides every subfolder inside the internal-storage view).
 *
 *   MES not granted (legacy path) — openSaveOptionsDialog opens the legacy SaveDialog (format chips +
 *     Export Grid), then on Continue launches the system ACTION_CREATE_DOCUMENT picker; the picker's
 *     returned URI routes through handleSaveAsResult → Case A/B/C → routeCrashSafeSave or the Replace
 *     dialog.
 *
 * Holds the per-save state (pendingSaveName, savePending, priorSnapshot) and owns the user-facing dialogs
 * that guard the encode pipeline. Encode / write / verify itself lives in ExportPipeline; collision policy
 * in ReplaceStrategy.
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
	 * state.reset() + state.setSourceImage(newSource, newDisplay) on the bg executor; the snapshot's
	 * sourceImage field still points at the OLD bitmap reference, so restorePriorSaveSettings can identify
	 * a stale snapshot by bitmap-reference inequality and skip the rollback. Without this guard the
	 * rollback would overwrite the new image's fresh exportConfig.format (seeded by installLoadedImage
	 * during the load) with the old image's format, defaulting the next save to JPEG over a PNG source
	 * (or vice versa).
	 */
	private record PriorSaveSnapshot(Bitmap sourceImage, Format format, boolean includeGrid) {}

	private static final String TAG = "SaveController";

	private static final String KEY_LAST_LOAD_FOLDER = "last_load_folder";
	private static final String KEY_LAST_LOAD_FOLDER_TS = "last_load_folder_ts";
	private static final String KEY_LAST_SAVE_FOLDER = "last_save_folder";
	private static final String KEY_LAST_SAVE_FOLDER_TS = "last_save_folder_ts";
	private static final String PREFS_NAME = "cropcenter_save";

	private final ExportPipeline exportPipeline;
	private final ReplaceStrategy replaceStrategy;
	private final SafFileHelper safFiles;
	private final SaveHost host;
	private final StoragePermissionHelper permissions;

	private PriorSaveSnapshot priorSnapshot;
	// Filename we asked SAF to create. When SAF silently auto-renames to avoid a collision (e.g. "vacation.jpg" →
	// "vacation (1).jpg"), the returned URI's display name won't match this — that's how we detect the rename.
	private String pendingSaveName;
	// Set when a save is committed to a target — SAF picker launch on the legacy path, or
	// onMergedSaveConfirmed on the in-app (MES) path — and cleared on result / cancel / collision-dialog
	// resolution. Gates rapid taps between commit and outcome; host.getBusy().get() doesn't flip until
	// exportTo actually runs, so it isn't sufficient on its own.
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
	 * Kept in SaveController (rather than extracted to util/) because the "X (N).ext" pattern encoded here IS
	 * the SAF DocumentsUI auto-rename convention — it's save-flow-specific, not generic. Extracting to util/
	 * would either pull the convention into a utility layer that shouldn't know about it, or create a new
	 * class with one caller. Tested directly via SaveControllerTest.
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
	 * Initial folder for both the FolderPickerDialog (save flow) and the OpenPickerDialog (load flow).
	 * Picks whichever of (last-save-folder, last-load-folder) was MORE RECENTLY recorded by comparing
	 * the persisted timestamps. Falls back to whichever single folder is available if the other is
	 * missing, then to primary external storage. Each persisted path is sanity-checked for existence +
	 * directory-ness so a deleted folder doesn't strand the picker at a non-existent path; if the
	 * more-recent folder is missing on disk the other folder is tried before falling through to
	 * external storage.
	 *
	 * Rationale: a user who loaded a fresh image and immediately taps Save expects to land in the
	 * folder they just loaded from, not the folder of an old save from a previous session. The
	 * pre-timestamp logic always preferred save-folder, so a stale save folder from days ago would
	 * win over a load-folder from minutes ago. The timestamp comparison makes "most recent action"
	 * the tiebreaker. The same rationale applies to Open: tapping Open right after a save expects to
	 * land in the just-saved folder, not the older load folder. Static + Context-taking so MainActivity
	 * can compute the OpenPickerDialog's start dir without holding a SaveController reference.
	 *
	 * @param ctx any Context (Activity preferred); used only for getSharedPreferences
	 * @return the most-recently-recorded existing folder, or the primary external storage root when
	 *         no prior folder is usable
	 */
	static File loadInitialPickerFolder(Context ctx)
	{
		SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		File savedFolder = readFolder(prefs, KEY_LAST_SAVE_FOLDER);
		File loadedFolder = readFolder(prefs, KEY_LAST_LOAD_FOLDER);
		long savedTs = prefs.getLong(KEY_LAST_SAVE_FOLDER_TS, 0L);
		long loadedTs = prefs.getLong(KEY_LAST_LOAD_FOLDER_TS, 0L);
		File picked = pickInitialSaveFolder(savedFolder, savedTs, loadedFolder, loadedTs);
		return picked != null ? picked : Environment.getExternalStorageDirectory();
	}

	/**
	 * Find the first available "stem (N).ext" filename in `folder` that doesn't collide. Strips any
	 * existing "(N)" suffix from `original` first so renaming "foo (1).jpg" suggests "foo (2).jpg"
	 * rather than "foo (1) (1).jpg". Used by showInAppRenameDialog to pre-populate the input with a
	 * good default. Returns null when N=1..9999 are all taken — the caller pre-fills with the
	 * colliding name as a fallback so the user has to type a unique name manually (returning
	 * `original` here would silently re-suggest the same colliding name and the user-cycle Rename →
	 * OK → collision-dialog would never terminate on the auto-suggestion alone).
	 *
	 * @param folder   target directory to probe
	 * @param original colliding filename (e.g. "foo.jpg" or "foo (3).jpg")
	 * @return first non-colliding name in the form "stem (N).ext", or null if N=1..9999 are all taken
	 */
	static String nextAvailableNumberedName(File folder, String original)
	{
		// Detect + strip existing "(N)" suffix via the shared parser. autoRenameBaseName returns the
		// base ("foo.jpg" given "foo (1).jpg") or null if `original` doesn't match the pattern; we
		// fall back to the original name in that case.
		String base = autoRenameBaseName(original);
		String startFrom = base != null ? base : original;
		int dot = startFrom.lastIndexOf('.');
		String stem = dot > 0 ? startFrom.substring(0, dot) : startFrom;
		String ext = dot > 0 ? startFrom.substring(dot) : "";
		for (int n = 1; n < 10000; n++)
		{
			String candidate = stem + " (" + n + ")" + ext;
			if (!new File(folder, candidate).exists())
			{
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Pure-function priority helper for loadInitialPickerFolder — extracted so the timestamp + existence
	 * fallback logic can be unit-tested without instantiating SharedPreferences. Prefers whichever
	 * of (savedFolder, loadedFolder) has the larger timestamp; on a tie, prefers savedFolder; if
	 * the more-recent folder reference is null (caller's existence check rejected it as missing /
	 * not-a-directory), falls back to the other folder. Returns null when both folder references
	 * are null — caller routes that to Environment.getExternalStorageDirectory().
	 *
	 * @param savedFolder  folder reference for the last-save path, or null when missing/deleted
	 * @param savedTs      timestamp of the last save (0 when never recorded)
	 * @param loadedFolder folder reference for the last-load path, or null when missing/deleted
	 * @param loadedTs     timestamp of the last load (0 when never recorded)
	 * @return the preferred non-null folder, or null when both are null
	 */
	static File pickInitialSaveFolder(File savedFolder, long savedTs,
		File loadedFolder, long loadedTs)
	{
		// Prefer the folder with the larger timestamp; ties go to save (current behaviour: the
		// equality-of-timestamps case is essentially impossible in practice — we'd need two
		// system-clock-identical actions — and arbitrarily preferring save matches the prior
		// no-timestamp behaviour for backward compatibility on a degenerate edge case).
		boolean preferLoad = loadedTs > savedTs;
		File primary = preferLoad ? loadedFolder : savedFolder;
		File secondary = preferLoad ? savedFolder : loadedFolder;
		if (primary != null)
		{
			return primary;
		}
		return secondary;
	}

	/**
	 * Persist the parent folder of a just-loaded file so the next FolderPickerDialog open can land
	 * there when no save folder has been recorded yet. ImageLoadController calls this on successful
	 * load with whatever SafFileHelper.fileFromSafUri resolved the URI to; cloud / SAF-only URIs
	 * that don't resolve to a filesystem path pass `loadedFile == null` and the call is a no-op.
	 *
	 * @param ctx        any Context (Activity preferred); used only for getSharedPreferences
	 * @param loadedFile resolved file the user just opened, or null when the URI didn't resolve to
	 *                   a filesystem path
	 */
	static void recordLoadFolder(Context ctx, File loadedFile)
	{
		if (loadedFile == null)
		{
			return;
		}
		File parent = loadedFile.getParentFile();
		if (parent == null || !parent.isDirectory())
		{
			return;
		}
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			.edit()
			.putString(KEY_LAST_LOAD_FOLDER, parent.getAbsolutePath())
			.putLong(KEY_LAST_LOAD_FOLDER_TS, System.currentTimeMillis())
			.apply();
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
	 *     from the URI alone, route through routeCrashSafeSave: create a sibling placeholder
	 *     and write-then-swap via ReplaceStrategy when possible, falling back to direct
	 *     overwrite (with preserveOnFailure) or preserving write on opaque-ID providers
	 *     that can't placeholder. The wasOverwrite flag — derived from a SIZE probe plus
	 *     a content-stream fallback for no-SIZE providers — drives the "Replaced" vs
	 *     "Saved" toast wording.
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
	 *     different name in the picker. Route through the same routeCrashSafeSave path as
	 *     Case A: the provider may still have shown its own Replace prompt for the user-typed
	 *     name and returned a URI pointing at pre-existing content, so a non-preserving
	 *     direct write would risk truncating a real user file on encoder failure. `chosen`
	 *     drives the toast wording so a provider-confirmed overwrite announces as
	 *     "Replaced <chosen>" rather than the misleading "Saved <chosen>".
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
	 * Save button handler. With MANAGE_EXTERNAL_STORAGE granted, runs the merged in-app FolderPickerDialog
	 * directly — format chips + Export Grid checkbox live in the same dialog as the folder navigator and
	 * thumbnail grid, so the user makes all choices in one place. Without MES, falls back to the legacy
	 * two-step flow: SaveDialog (format + grid) then ACTION_CREATE_DOCUMENT SAF picker (because SAF can't
	 * be navigated inside our app).
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
		if (permissions.hasStoragePermission())
		{
			showMergedInAppDialog();
		}
		else
		{
			openSaveOptionsDialog();
		}
	}

	/**
	 * Read + validate a persisted folder path from the shared preferences. Returns the resolved File
	 * only when the persisted path still exists AND is a directory — so a folder the user deleted
	 * between sessions doesn't strand the picker at a non-existent path.
	 *
	 * @param prefs SharedPreferences instance to read from
	 * @param key   preference key holding the absolute folder path
	 * @return the File at the persisted path when it exists and is a directory; null otherwise (no
	 *         pref, deleted folder, or non-directory path)
	 */
	private static File readFolder(SharedPreferences prefs, String key)
	{
		String path = prefs.getString(key, null);
		if (path == null)
		{
			return null;
		}
		File folder = new File(path);
		return (folder.exists() && folder.isDirectory()) ? folder : null;
	}

	/**
	 * Pick encoder based on the extension the user typed in the SAF picker.
	 *
	 * @param name SAF-returned filename whose extension drives the format choice; null and unrecognised
	 *             extensions are no-ops (state.exportConfig.format() retains whatever the dialog set)
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
	 * or the Activity tears down. The Continue path deliberately does NOT call this —
	 * handleSaveAsResultBody / onSaveCancelled / the launcher-exception path still own the snapshot
	 * rollback contract for a save that's reached SAF and then abandoned.
	 */
	private void clearPriorSnapshotOnCancel()
	{
		priorSnapshot = null;
	}

	/**
	 * Dispatch the actual write for an in-app folder picker save. Routes through routeCrashSafeSave
	 * so the sibling-placeholder + atomic-rename crash-safe write path runs uniformly for both
	 * new-file and overwrite cases. probeWasOverwrite inside routeCrashSafeSave re-checks file
	 * existence — the in-app Rename/Replace dialog only filters whether we got here, not what
	 * routeCrashSafeSave does once we arrive. savePending is cleared after the dispatch returns
	 * (routeCrashSafeSave kicks off async work; the post-kick clear matches Case A/C's wrapper-
	 * finally behaviour in handleSaveAsResult).
	 *
	 * @param folder folder the user picked
	 * @param name   filename to save as (post-Rename if applicable)
	 */
	private void dispatchInAppSave(File folder, String name)
	{
		File target = new File(folder, name);
		Uri uri = SafFileHelper.buildExternalStorageDocumentUri(target);
		if (uri == null)
		{
			Log.w(TAG, "buildExternalStorageDocumentUri returned null for " + target);
			host.toastIfAlive("Picked folder isn't on primary storage", Toast.LENGTH_LONG);
			onSaveCancelled();
			return;
		}
		// pendingSaveName is the LEGACY-SAF-path's collision-detection signal (handleSaveAsResultBody
		// reads it to spot SAF auto-renames); the in-app path doesn't consult it. Clear savePending
		// after the dispatch fires — routeCrashSafeSave kicks off async work, matching Case A/C's
		// wrapper-finally behaviour in handleSaveAsResult.
		routeCrashSafeSave(uri, name);
		savePending = false;
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
		// Do NOT clear `priorSnapshot` here. The save isn't actually committed yet — the user can still
		// cancel from the Replace / Overwrite-Confirm dialog (Cases A, B, C). On those Cancel paths we
		// want to restore the prior format / grid-include choices so a user who decided "no, don't
		// overwrite" doesn't end up with the next image stuck on the PNG-with-grid settings they
		// picked for the abandoned save. The snapshot stays until either the save actually starts
		// (routeCrashSafeSave / replaceColliding) or the dialog's Cancel handler restores it.

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
			//
			// Roll back the SaveDialog choices (format / Export Grid) before returning — the user
			// abandoned this save by mismatching extensions, so the format / grid they picked is
			// uncommitted intent. Without this, the rejected format leaks into the next save's
			// defaults AND priorSnapshot.sourceImage stays pinned in memory until a later save/load
			// clears it.
			restorePriorSaveSettings();
			showExtensionMismatchDialog(requested, chosen);
			return false;
		}

		applyFormatFromFilename(chosen);

		// Case (A): SAF accepted the requested name exactly. Either the file didn't exist (new file) or
		// SAF prompted "Replace?" and the user accepted OR (on providers that don't show their own
		// prompt — Samsung's recent Files app behavior) the picker is silently returning a URI to
		// pre-existing content. The user has no in-app confirmation that they're about to overwrite,
		// which is data-loss-adjacent on a save flow. Probe for prior content here; on a confirmed
		// overwrite, surface the Replace / Cancel dialog so the user explicitly confirms.
		if (requested != null && chosen != null && requested.equalsIgnoreCase(chosen))
		{
			boolean wasOverwrite = probeWasOverwrite(newUri);
			Log.d(TAG, "Save Case A: chosen=" + chosen + " requested=" + requested
				+ " wasOverwrite=" + wasOverwrite);
			if (wasOverwrite)
			{
				showOverwriteConfirmDialog(newUri, requested);
				return true;
			}
			routeCrashSafeSave(newUri, requested);
			return false;
		}

		// Case (B): chosen has a "X (N).ext" auto-rename pattern. Detection works on `chosen` alone so it
		// catches the case where the user edited the filename in the picker and THAT name collided — the "X"
		// in "X (N)" doesn't have to match the original pendingSaveName. Verify the inferred base still
		// lives in the same directory AND has real content before showing the Replace dialog so a user who
		// intentionally typed "foo (1).jpg" without a real collision doesn't get offered Replace on a
		// phantom, and so a 0-byte placeholder left by an interrupted prior save isn't treated as content to
		// preserve. See siblingLooksLikeCollision for the existence + size probe order; -1 / unknown size
		// still counts as collision on providers that don't expose OpenableColumns.SIZE.
		String autoRenameBase = autoRenameBaseName(chosen);
		Log.d(TAG, "Save Case B probe: chosen=" + chosen + " requested=" + requested
			+ " autoRenameBase=" + autoRenameBase);
		if (autoRenameBase != null)
		{
			// Sibling-collision probe. On providers where the docId encodes a path (Samsung primary
			// storage's ExternalStorageProvider), `deriveSiblingUri` builds the un-suffixed sibling
			// URI and `siblingLooksLikeCollision` confirms the original exists. On providers with
			// opaque doc IDs (MediaStore-backed pickers — the user's recent Samsung Files behavior
			// where docId is "document:12345" with no parseable path), `deriveSiblingUri` returns
			// null and the probe can't run. In that case, fall back to trusting the auto-rename
			// pattern alone — SAF only suffixes "(N)" on a real collision, so the false-positive
			// risk (user intentionally typed "foo (3).jpg" without an existing "foo.jpg") is
			// strictly less bad than the false-negative (silent overwrite without confirmation).
			Uri baseUri = safFiles.deriveSiblingUri(newUri, autoRenameBase);
			boolean siblingCollision = baseUri != null
				? siblingLooksLikeCollision(baseUri)
				: true; // opaque-ID provider — trust the auto-rename pattern
			Log.d(TAG, "Save Case B: baseUri=" + baseUri + " siblingCollision=" + siblingCollision);
			if (siblingCollision)
			{
				// showReplaceDialog manages savePending across its button / cancel / BadTokenException
				// handlers. Return true so the wrapper's finally does NOT clear it — the dialog needs
				// savePending=true to gate parallel Save taps while the user decides.
				showReplaceDialog(newUri, autoRenameBase, chosen);
				return true;
			}
		}

		// Case (C): user changed the name intentionally (no "(N)" suffix, or "(N)" stripped doesn't collide
		// with anything in the picked directory). The user-typed name might still match a pre-existing file —
		// many providers handle a user-typed collision with their own Replace prompt and return a URI pointing
		// at existing content with `chosen` equal to the user-typed (colliding) name. Same overwrite-confirm
		// gate as Case A — when wasOverwrite=true, show the Replace / Cancel dialog before destructive write.
		// `requested` is the fallback name when chosen is null (rare opaque-name provider — chosen is null in
		// practice only on providers that also refuse SIZE, so wasOverwrite resolves to false and the fallback
		// preserving write is the safe choice).
		String saveName = chosen != null ? chosen : requested;
		boolean wasOverwriteC = probeWasOverwrite(newUri);
		Log.d(TAG, "Save Case C: saveName=" + saveName + " wasOverwrite=" + wasOverwriteC);
		if (wasOverwriteC)
		{
			showOverwriteConfirmDialog(newUri, saveName);
			return true;
		}
		routeCrashSafeSave(newUri, saveName);
		return false;
	}


	/**
	 * Result handler for the merged in-app FolderPickerDialog (format + grid options + folder + thumbnails).
	 * On Save here (choices non-null), applies the user's format / grid selections to CropState, computes
	 * the filename from state.originalFilename + format extension, persists the picked folder as the next
	 * launch's starting location, sets pendingSaveName + savePending, then dispatches to either the write
	 * path or the Rename/Replace/Cancel collision dialog depending on existing-file presence. The merged
	 * dialog commits state once at confirm (the picker doesn't mutate CropState mid-show), so a
	 * priorSnapshot is captured BEFORE updateExportConfig / updateGridConfig fire — if the user cancels
	 * at the in-app collision / rename dialog, onSaveCancelled → restorePriorSaveSettings rolls the
	 * format / grid-include selections back. On null choices (Cancel / external dismiss from the picker
	 * itself) nothing was mutated, so we just return.
	 *
	 * @param choices SaveChoices from the merged dialog; null on Cancel / back / external dismiss
	 */
	private void onMergedSaveConfirmed(FolderPickerDialog.SaveChoices choices)
	{
		if (choices == null)
		{
			// Cancel / external dismiss — savePending was never set, nothing to clear.
			return;
		}
		// Re-entrancy guard: if a previous save is still in flight (savePending=true), a second
		// confirm-tap or programmatic re-fire would otherwise overwrite priorSnapshot with the
		// already-mutated state from the first call, breaking the rollback contract. The user-
		// visible symptom would be: rapid Save here taps → user cancels at the collision dialog →
		// format/grid don't roll back to the truly-original values because the snapshot now points
		// at the first call's already-applied format/grid. Drop the re-entry silently; the first
		// save's lifecycle still owns savePending.
		if (savePending)
		{
			Log.d(TAG, "onMergedSaveConfirmed: re-entry while savePending=true, dropping");
			return;
		}
		// The picker validates the filename for path separators and empty/traversal segments before
		// firing this callback, but defensively sanitise here too — a buggy/malicious test fake of the
		// picker shouldn't be able to direct the save outside the picked folder via a crafted
		// `choices.filename()`. Validate BEFORE the state mutations below so a bad name doesn't commit
		// format / grid changes the user never had a chance to confirm.
		String name = choices.filename();
		if (name == null || !FolderPickerDialog.isValidFilename(name))
		{
			host.toastIfAlive(DialogStrings.INVALID_FILENAME, Toast.LENGTH_SHORT);
			return;
		}
		// Wrap the entire state-mutating section in try/catch so a thrown exception anywhere —
		// updateExportConfig / updateGridConfig (StateBus listener throwing, OOM in lambda capture),
		// saveLastSaveFolder (SharedPreferences disk-full), File.exists (rare SecurityException), or
		// the dialog constructors (BadTokenException from a config-change race) — can't strand
		// savePending=true forever or leave format/grid half-mutated. Snapshot capture is INSIDE the
		// try so a snapshot followed by a thrown updateXxx still routes through onSaveCancelled →
		// restorePriorSaveSettings for a clean rollback.
		try
		{
			// Snapshot the export/grid state BEFORE mutating so an in-app collision-dialog Cancel
			// can roll back. Without this, a user who picks PNG + Export Grid in the merged dialog,
			// taps Save here on a colliding name, then taps Cancel in the collision dialog, would
			// find PNG + Export Grid stuck as the next default — silently changing the next save's
			// encoding based on a save they explicitly abandoned. routeCrashSafeSave clears the
			// snapshot once the write actually commits.
			priorSnapshot = new PriorSaveSnapshot(
				host.getState().getSourceImage(),
				host.getState().getExportConfig().format(),
				host.getState().getGridConfig().includeInExport());
			host.getState().updateExportConfig(c -> c.withFormat(choices.format()));
			host.getState().updateGridConfig(g -> g.withIncludeInExport(choices.bakeGrid()));
			pendingSaveName = name;
			savePending = true;
			saveLastSaveFolder(choices.folder());
			File target = new File(choices.folder(), name);
			if (target.isDirectory())
			{
				// A directory at the requested path is "name unavailable" — the Replace flow
				// downstream would route through ReplaceStrategy's delete-then-rename fallback,
				// which on permissive SAF providers could actually delete the user's folder.
				// Reject here BEFORE the collision dialog so Replace is never offered against
				// a directory target. File.isFile() below excludes directories symmetrically so
				// the otherwise-existence-driven collision path can only fire on regular files.
				host.toastIfAlive("That name is a folder — pick another", Toast.LENGTH_SHORT);
				onSaveCancelled();
				return;
			}
			if (target.isFile())
			{
				showInAppCollisionDialog(choices.folder(), name);
			}
			else
			{
				dispatchInAppSave(choices.folder(), name);
			}
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "onMergedSaveConfirmed dispatch failed", e);
			onSaveCancelled();
			host.toastIfAlive("Save failed — try again", Toast.LENGTH_SHORT);
		}
	}

	/**
	 * OK-button handler for showInAppRenameDialog. Validates the typed name (rejecting empty,
	 * traversal segments, and path separators with an "Invalid filename" toast that keeps the dialog
	 * open for correction), normalises the extension against the selected format so gallery / file-
	 * manager type inference doesn't lie, then dispatches to either the save path or the recursive
	 * collision dialog. Extracted from showInAppRenameDialog's OnShowListener override so the lambda
	 * body stays under the 3-line cap.
	 *
	 * @param dialog the Rename dialog itself; dismissed only on the valid-name path
	 * @param input  the user-facing EditText whose current text drives validation and dispatch
	 * @param folder folder the picker landed in; the rename target is constructed as new File(folder,
	 *               normalisedName)
	 */
	private void onRenameOkClicked(AlertDialog dialog, EditText input, File folder)
	{
		String typed = input.getText().toString().trim();
		// Reject empty, traversal segments, and any path separator — must be a SINGLE filename in the
		// folder the user just picked. Without this, "subdir/foo.jpg" or "../foo.jpg" would target a
		// different folder than the picker landed in (or get rejected later by
		// SafFileHelper.fileFromSafUri's docId traversal guard, producing a confusing failure).
		// Mirrors the sanitisation onMergedSaveConfirmed applies to the provider-supplied stem via
		// the same shared predicate.
		if (!FolderPickerDialog.isValidFilename(typed))
		{
			host.toastIfAlive(DialogStrings.INVALID_FILENAME, Toast.LENGTH_SHORT);
			return;
		}
		// Normalise the extension to match the selected format — same rule the merged dialog's
		// positive button applies. Without this, a user who renames "vacation.jpg" to "vacation.heic"
		// in the in-app Rename dialog saves JPEG bytes under .heic, and gallery / file-manager type
		// inference would misclassify the file.
		String normalised = FolderPickerDialog.normaliseExtension(typed,
			host.getState().getExportConfig().format());
		File target = new File(folder, normalised);
		if (target.isDirectory())
		{
			// Same rationale as onMergedSaveConfirmed — never route directory targets into the
			// Replace flow, which on permissive SAF providers could delete the folder. Toast and
			// leave the rename dialog open (don't dismiss) so the user can correct the name.
			host.toastIfAlive("That name is a folder — pick another", Toast.LENGTH_SHORT);
			return;
		}
		dialog.dismiss();
		if (target.isFile())
		{
			showInAppCollisionDialog(folder, normalised);
		}
		else
		{
			dispatchInAppSave(folder, normalised);
		}
	}

	/**
	 * Handle the Replace dialog's positive "Replace" button: full Replace semantics (write-then-swap +
	 * "Replaced" toast). Busy-rejection cleanup: when a parallel bg op (e.g. a Share/View intent's load
	 * that arrived while the user deliberated) holds busy, replaceColliding's downstream busy gate would
	 * silently reject and leave the SAF auto-rename target on disk. Run cleanupPlaceholder here so the
	 * orphan doesn't linger.
	 *
	 * @param newUri             SAF auto-rename target URI ("foo (1).jpg")
	 * @param requested          user-typed name that collided (drives downstream toast wording)
	 * @param cleanupPlaceholder runs safFiles.tryDeleteSafDocument(newUri) on busy-rejection
	 */
	private void onReplaceConfirmed(Uri newUri, String requested, Runnable cleanupPlaceholder)
	{
		savePending = false;
		priorSnapshot = null;
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
	 * — without it, exportTo's busy gate silently rejects and leaves the auto-rename SAF document stranded.
	 *
	 * @param newUri             SAF auto-rename target URI (already has the SAF-assigned name)
	 * @param cleanupPlaceholder runs safFiles.tryDeleteSafDocument(newUri) on busy-rejection
	 */
	private void onReplaceKeep(Uri newUri, Runnable cleanupPlaceholder)
	{
		savePending = false;
		priorSnapshot = null;
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
		// Extension follows the format the user just picked in SaveDialog. The SAF result path validates
		// the picker-edited name against the requested format: matching (or extensionless) names
		// continue and applyFormatFromFilename re-derives the format; mismatched known/unknown
		// extensions are rejected up-front via showExtensionMismatchDialog to avoid MIME/type
		// disagreements between the SAF document and the encoder's output bytes.
		String stem = host.getState().getOriginalFilename();
		if (stem == null || stem.isEmpty())
		{
			stem = "crop";
		}
		stem = Format.stripExtension(stem);
		String ext = host.getState().getExportConfig().format().extension();
		String name = stem + ext;
		pendingSaveName = name;
		savePending = true;
		// Legacy SAF path (MES not granted at Save-tap time → showSaveDialog routed here through
		// openSaveOptionsDialog / SaveDialog). When MES IS granted, showSaveDialog routes directly to
		// showMergedInAppDialog, skipping this method entirely.
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
	 *     below clears priorSnapshot directly because the OnCancelListener never registers
	 *
	 * isDestroyed pre-check is the first line of defense against the config-change race; the
	 * try/catch around .show is the second (the race window between the check and the actual show is
	 * still open).
	 */
	private void openSaveOptionsDialog()
	{
		// BadTokenException guard — if onDestroy ran between the user's Save tap and this call (config
		// change racing the handler), AlertDialog.Builder throws on .show(). isDestroyed is the first
		// line of defense; the try/catch is the second (the race window between them is still open).
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
			// Continue tap would commit image A's format/grid choices onto image B's state.
			// clearPriorSnapshotOnCancel runs on every cancel path (user Cancel, back-press,
			// outside-touch, forced dismissTransientDialogs) so the snapshot — which holds the source
			// Bitmap reference — doesn't pin memory after an abandoned dialog.
			host.registerTransientDialog(SaveDialog.show(host.getActivity(), host.getState(),
				this::onSaveDialogConfirmed, this::clearPriorSnapshotOnCancel));
		}
		catch (RuntimeException e)
		{
			// On show / register failure the OnCancelListener is never installed, so
			// clearPriorSnapshotOnCancel won't fire — clear the snapshot here so the source bitmap
			// reference doesn't stay pinned until the next openSaveOptionsDialog or activity teardown.
			Log.w(TAG, "save options dialog failed to show", e);
			priorSnapshot = null;
		}
	}

	/**
	 * Probe whether the SAF-returned URI already points at pre-existing content. Same classification as
	 * `routeCrashSafeSave` so the upstream overwrite-confirm dialog gate and the downstream save path
	 * agree on what "overwrite" means:
	 *   priorSize  >  0           → confirmed overwrite
	 *   priorSize == 0            → ambiguous (treat as not-overwrite; nearly always an empty placeholder)
	 *   priorSize == -1 (no-SIZE) → fall back to a content-stream probe (one byte served = real file)
	 *
	 * Used by `handleSaveAsResultBody` Cases A and C to decide whether to surface the
	 * `showOverwriteConfirmDialog` before silently overwriting — Samsung's recent SAF Files-app behavior
	 * returns the same-name URI to existing content without asking the user, so without this gate every
	 * collision overwrite happens silently and the user has no in-app confirmation that data was about
	 * to be destroyed.
	 *
	 * @param newUri SAF document URI returned by the picker
	 * @return true when newUri points at content that an overwrite would destroy; false when newUri is
	 *         a fresh / empty / unverifiable placeholder
	 */
	private boolean probeWasOverwrite(Uri newUri)
	{
		// Three independent probes; ANY positive signal counts as a confirmed-overwrite. The
		// path-resolved File.exists() + length > 0 check (third probe) handles SAF providers that
		// expose neither OpenableColumns.SIZE nor a readable stream over the URI — common for
		// MediaStore-backed pickers on Samsung where the provider quirks around metadata visibility
		// vs the filesystem layer. We deliberately require non-zero file length on the third probe:
		// the SAF picker often pre-creates a 0-byte placeholder at the user's chosen name BEFORE
		// returning the URI, and counting that empty placeholder as "overwrite" would surface the
		// Replace dialog on every new-file save. Files with real content on disk pre-existing the
		// SAF call are real overwrites. Diagnostic Log.d covers each probe's contribution so a user
		// reporting "dialog didn't fire" can correlate the three signals against expected provider
		// behaviour.
		long priorSize = safFiles.querySafFileSize(newUri);
		boolean sizePositive = priorSize > 0;
		boolean hasContent = priorSize < 0 && safFiles.hasExistingContent(newUri);
		File asFile = safFiles.fileFromSafUri(newUri);
		boolean fileHasContent = asFile != null && asFile.exists() && asFile.length() > 0;
		boolean result = sizePositive || hasContent || fileHasContent;
		Log.d(TAG, "probeWasOverwrite priorSize=" + priorSize + " hasContent=" + hasContent
			+ " fileHasContent=" + fileHasContent + " → " + result);
		return result;
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
	 * Probe the SAF-returned URI for pre-existing content and dispatch to the appropriate save path. The URI
	 * may point to a fresh placeholder (brand-new save) OR to an existing document the provider returned
	 * after its own Replace prompt. The prior-content status is ambiguous from the URI alone, so the
	 * crash-safe write-then-swap pattern via a sibling placeholder is the conservative default. The
	 * wasOverwrite classification:
	 *   priorSize  >  0           → confirmed overwrite
	 *   priorSize == 0            → ambiguous (treat as not-overwrite; nearly always an empty placeholder)
	 *   priorSize == -1 (no-SIZE) → fall back to a content-stream probe (one byte served = real file)
	 *
	 * Shared by Case A (exact-name match) and Case C (user-renamed without "(N)" pattern).
	 *
	 * @param newUri SAF document URI returned by the picker
	 * @param name   user's intended filename — Case A: original `requested`; Case C: `chosen`
	 */
	private void routeCrashSafeSave(Uri newUri, String name)
	{
		// Save is actually committing now. Discard the format / grid-include snapshot so a subsequent
		// stray onSaveCancelled (e.g., follow-up async callback after a config change) can't roll
		// back the now-baked choices. Mirrors the equivalent commit point on the Replace flow
		// (onReplaceConfirmed / onReplaceKeep both clear savePending before dispatching here).
		priorSnapshot = null;
		// Route the SAME `probeWasOverwrite` classification used by handleSaveAsResultBody's Case A/C
		// gate so the dialog-shown branch (which dispatched here on Replace) doesn't get the answer
		// reclassified to "not an overwrite" downstream. The 3-probe path (size + content-stream +
		// path-resolved File.length > 0) catches Samsung Downloads-provider URIs where SIZE and
		// hasExistingContent both refuse but `fileFromSafUri` resolves real content via the readlink
		// fallback; a recompute that only ran the first two probes would route through
		// `exportToPreserving` and toast "Saved" on a confirmed overwrite the user just chose to
		// Replace — toast wording and write-strategy would then disagree with the dialog the user saw.
		boolean wasOverwrite = probeWasOverwrite(newUri);
		String mime = host.getState().getExportConfig().format().mimeType();
		// nanoTime (not currentTimeMillis) for uniqueness to match ExportPipeline.tryDirectAtomicWrite +
		// ReplaceStrategy.replaceViaFileIo — two saves within the same millisecond would otherwise collide
		// here. savePending gates rapid taps in practice, but the lock isn't airtight across all paths.
		String placeholderName = ".cropcenter-tmp-" + System.nanoTime() + "-" + name;
		Uri placeholder = safFiles.createSiblingPlaceholder(newUri, mime, placeholderName);
		if (placeholder != null)
		{
			replaceStrategy.replaceColliding(placeholder, name, wasOverwrite);
		}
		else if (wasOverwrite)
		{
			// Opaque-ID + confirmed overwrite: can't placeholder. Direct overwrite with
			// preserve-on-failure. Pass `name` so the success toast says "Replaced <name>" — a
			// generic "Saved N KB" would misrepresent a confirmed overwrite.
			exportPipeline.exportToOverwrite(newUri, name);
		}
		else
		{
			// Opaque-ID + ambiguous: can't confirm existing content, can't placeholder. Preserve on failure
			// so we don't destroy a file the user might own.
			exportPipeline.exportToPreserving(newUri);
		}
	}

	/**
	 * Persist the folder the user just saved into as the next launch's starting location. Called from
	 * the FolderPickerDialog callback on Save Here, before the collision check + write dispatch — so
	 * even if the user cancels at the Rename/Replace/Cancel step, the folder they navigated to is
	 * remembered for next time.
	 *
	 * @param folder folder to persist as the new starting location
	 */
	private void saveLastSaveFolder(File folder)
	{
		host.getActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			.edit()
			.putString(KEY_LAST_SAVE_FOLDER, folder.getAbsolutePath())
			.putLong(KEY_LAST_SAVE_FOLDER_TS, System.currentTimeMillis())
			.apply();
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
			// BadTokenException guard: config-change races can land between the isDestroyed pre-check
			// and the actual show() call (the Activity finishes after the pre-check but before
			// WindowManager accepts the dialog). The catch keeps the warning best-effort.
			// registerTransientDialog tracks the warning so a later image load / session can
			// dismiss it via dismissTransientDialogs() before the old Activity window leaks.
			AlertDialog dialog = new AlertDialog.Builder(host.getActivity())
				.setTitle("Change format in Save, not the picker")
				.setMessage(message)
				.setPositiveButton(DialogStrings.OK, null)
				.create();
			host.registerTransientDialog(dialog);
			dialog.show();
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "extension-mismatch dialog failed to show", e);
		}
	}

	/**
	 * Show the Rename / Replace / Cancel dialog for an in-app folder picker collision. Replace
	 * dispatches the write directly (routeCrashSafeSave handles the actual overwrite via the
	 * sibling-placeholder + atomic-rename path); Rename opens a filename input; Cancel rolls back.
	 *
	 * @param folder folder the user picked
	 * @param name   filename that collides in `folder`
	 */
	private void showInAppCollisionDialog(File folder, String name)
	{
		if (host.isDestroyed())
		{
			onSaveCancelled();
			return;
		}
		try
		{
			AlertDialog collisionDialog = new AlertDialog.Builder(host.getActivity())
				.setTitle("File already exists")
				.setMessage("\"" + name + "\" already exists in this folder. What do you want to do?")
				// Replace re-dispatches through the normal in-app save path. routeCrashSafeSave's
				// own wasOverwrite probe (placeholder.exists() + length > 0) handles the toast
				// wording on every reachable race outcome: dialog confirms Replace → file still
				// there → "Replaced X"; dialog confirms Replace → file vanished mid-decision →
				// "Saved N KB" (no data destroyed). The earlier dispatchInAppSaveAsOverwrite
				// wrapper promised an authoritative overwrite hint it didn't actually wire
				// through; deleted in favour of the direct call.
				.setPositiveButton("Replace",
					(dialog, which) -> dispatchInAppSave(folder, name))
				.setNeutralButton("Rename",
					(dialog, which) -> showInAppRenameDialog(folder, name))
				.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> onSaveCancelled())
				.setOnCancelListener(dialog -> onSaveCancelled())
				.create();
			host.registerTransientDialog(collisionDialog);
			collisionDialog.show();
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "in-app collision dialog failed to show", e);
			onSaveCancelled();
		}
	}

	/**
	 * Filename input dialog reached from the Rename branch of showInAppCollisionDialog. Pre-fills
	 * with nextAvailableNumberedName's "(N)" suggestion (e.g. `foo (1).jpg` for a colliding `foo.jpg`,
	 * or `foo (2).jpg` if `foo (1).jpg` is also taken), falling back to the colliding name itself if
	 * the (N) loop overflows. selectAll lets the user replace or keep the suggestion in one tap.
	 * Recurses through the collision check on confirm — a renamed-to-another-existing-file still
	 * surfaces Rename/Replace/Cancel rather than silently overwriting. If the new name lacks an
	 * extension, the current export format's extension is appended.
	 *
	 * @param folder   folder the picker landed in
	 * @param original colliding filename — used as the suggestion-stem and as the overflow fallback
	 */
	private void showInAppRenameDialog(File folder, String original)
	{
		if (host.isDestroyed())
		{
			onSaveCancelled();
			return;
		}
		try
		{
			EditText input = new EditText(host.getActivity());
			// Pre-fill with the next available "(N)" suffix per Samsung / Android Files-app convention.
			// Strips an existing "(N)" suffix first so renaming "foo (1).jpg" suggests "foo (2).jpg"
			// rather than "foo (1) (1).jpg". selectAll lets the user replace the suggestion in one
			// tap. Falls back to `original` on the unlikely N=1..9999-all-taken overflow so the input
			// still shows something the user can edit, rather than a blank field.
			String suggested = nextAvailableNumberedName(folder, original);
			input.setText(suggested != null ? suggested : original);
			input.selectAll();
			// Install the positive button with a null handler so AlertDialog doesn't auto-dismiss after
			// the click — the OnShowListener override below validates and dismisses only on a valid
			// name, mirroring FolderPickerDialog.show(). Without this, a correctable typo (empty,
			// traversal, separator) would auto-close the dialog and abort the whole save flow.
			AlertDialog renameDialog = new AlertDialog.Builder(host.getActivity())
				.setTitle("Save as")
				.setView(input)
				.setPositiveButton(DialogStrings.OK, null)
				.setNegativeButton(DialogStrings.CANCEL,
					(dialog, which) -> onSaveCancelled())
				.setOnCancelListener(dialog -> onSaveCancelled())
				.create();
			renameDialog.setOnShowListener(shown ->
				renameDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
					onRenameOkClicked(renameDialog, input, folder)));
			host.registerTransientDialog(renameDialog);
			renameDialog.show();
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "rename dialog failed to show", e);
			onSaveCancelled();
		}
	}

	/**
	 * Show the merged in-app save dialog (format + Export Grid + folder picker + thumbnail grid). Used as
	 * the primary Save flow when MANAGE_EXTERNAL_STORAGE is granted — bypasses Samsung's broken One UI
	 * ACTION_CREATE_DOCUMENT picker by browsing the filesystem via java.io.File and writing directly.
	 * Initial folder is the last folder the user saved into (SharedPreferences) — falls back to primary
	 * external storage root on first save / cleared app data / when the previously-used folder no longer
	 * exists. Initial format + grid pre-populate from the current CropState's exportConfig / gridConfig.
	 *
	 * Tracking is done via host.setActiveTransientDialog (not registerTransientDialog) because the
	 * FolderPickerDialog installs its own composite OnDismissListener — registerTransientDialog would
	 * wrap that listener and break the savePending + executor-shutdown cleanup. The host clear callback
	 * is passed through so the host's activeTransientDialog field still releases on dismiss.
	 */
	private void showMergedInAppDialog()
	{
		if (host.isDestroyed())
		{
			return;
		}
		File startDir = loadInitialPickerFolder(host.getActivity());
		Format initialFormat = host.getState().getExportConfig().format();
		boolean initialBakeGrid = host.getState().getGridConfig().includeInExport();
		// Compute the source's display-stem + initial-format extension as the filename pre-fill.
		// state.getOriginalFilename is provider-controlled so we strip any path-separator characters
		// + reject "."/".." segments before letting the value flow into the picker's EditText (which
		// then re-validates on Save here). Mirrors onMergedSaveConfirmed's defensive sanitisation
		// path so the field never starts in a state the validator would reject.
		String stem = host.getState().getOriginalFilename();
		if (stem == null || stem.isEmpty())
		{
			stem = "crop";
		}
		stem = Format.stripExtension(stem);
		stem = stem.replace('/', '_').replace('\\', '_');
		if (stem.equals(".") || stem.equals("..") || stem.isEmpty())
		{
			stem = "crop";
		}
		String initialFilename = stem + initialFormat.extension();
		try
		{
			AlertDialog dialog = new FolderPickerDialog(host.getActivity(), startDir,
				initialFilename, initialFormat, initialBakeGrid,
				this::onMergedSaveConfirmed,
				host::clearTransientDialog).show();
			host.setActiveTransientDialog(dialog);
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "merged save dialog failed to show", e);
		}
	}

	/**
	 * Build and show the Replace / Cancel dialog for cases (A) and (C) — the SAF picker returned a URI
	 * pointing at pre-existing content (probeWasOverwrite returned true) but no auto-rename happened.
	 * Two buttons only: Replace (commit the overwrite via routeCrashSafeSave's existing crash-safe path)
	 * and Cancel (cleanup the placeholder if one exists). Unlike `showReplaceDialog`, there's no Keep
	 * option — the URI's filename is exactly what the user asked for, so "keep as auto-renamed" doesn't
	 * apply.
	 *
	 * On Cancel, we do NOT delete the SAF document: Case A's URI may point at the user's pre-existing
	 * file (the SAF picker handed it back without auto-rename when the user picked Replace in the
	 * provider's own prompt, OR when the provider silently overwrites). Deleting it on Cancel would
	 * destroy the user's data. The same conservative reasoning as `showExtensionMismatchDialog` —
	 * orphaned-fresh-placeholder fallout is acceptable; data-loss is not.
	 *
	 * Activity-destroyed and BadTokenException guards mirror `showReplaceDialog` so a config-change race
	 * doesn't strand savePending or skip the cleanup logic.
	 *
	 * @param newUri SAF document URI returned by the picker — points at existing content per the
	 *               probeWasOverwrite gate
	 * @param name   user's intended filename — Case A: original `requested`; Case C: `chosen`
	 */
	private void showOverwriteConfirmDialog(Uri newUri, String name)
	{
		if (host.isDestroyed())
		{
			Log.w(TAG, "skipping overwrite-confirm dialog on destroyed activity");
			savePending = false;
			restorePriorSaveSettings();
			return;
		}
		String message = "A file named \"" + name + "\" already exists.\n\n"
			+ "Replace — overwrite it.\n"
			+ "Cancel — don't save.";
		// Cancel / BACK / touch-outside / forced dismiss / show-failure all roll back the SaveDialog
		// choices (format / Export Grid) before clearing savePending. The user abandoned this save
		// (chose Cancel rather than Replace), so the picked format / grid is uncommitted intent and
		// must NOT leak into the next save's defaults. Hoisted to a single Runnable so the three
		// abort sites can't drift apart — mirrors showReplaceDialog's rollbackOnAbort pattern.
		Runnable rollbackOnAbort = () ->
		{
			savePending = false;
			restorePriorSaveSettings();
		};
		try
		{
			host.registerTransientDialog(new AlertDialog.Builder(host.getActivity())
				.setTitle("Replace " + name + "?")
				.setMessage(message)
				.setPositiveButton("Replace", (dialog, which) ->
				{
					savePending = false;
					if (host.getBusy().get())
					{
						host.toastIfAlive("Replace failed — try again", Toast.LENGTH_SHORT);
						return;
					}
					// Same crash-safe path the silent overwrite took before — routeCrashSafeSave's
					// own wasOverwrite probe will re-confirm and pick the correct write strategy
					// (sibling-placeholder vs direct-overwrite vs preserving-write).
					routeCrashSafeSave(newUri, name);
				})
				.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> rollbackOnAbort.run())
				.setOnCancelListener(dialog -> rollbackOnAbort.run())
				.show());
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "overwrite-confirm dialog failed to show", e);
			rollbackOnAbort.run();
		}
	}

	/**
	 * Build and show the Replace / Keep / Cancel dialog for case (B) of the save flow. Guards against the
	 * Activity finishing between the SAF result and this prompt: without the isDestroyed gate, .show() throws
	 * BadTokenException before any button / cancel listener runs, leaving savePending stuck and the SAF
	 * placeholder undeleted.
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
			+ "Replace — overwrite it.\n" + "Keep — save as \"" + safName + "\" instead.\n"
			+ "Cancel — don't save.";
		try
		{
			// Register with the host's transient-dialog tracker so a Share/View intent or graft apply
			// dismisses this dialog before bg state.reset(). Without this, a stale Replace prompt could
			// outlive its source image — Replace/Keep would write image B's state to image A's SAF
			// target. dismissTransientDialogs uses cancel(), so cleanupPlaceholder + savePending reset
			// still run on forced dismissal.
			// Cancel / BACK / touch-outside / forced dismiss all roll back the SaveDialog choices
			// (format / Export Grid) before clearing savePending. The user abandoned this save
			// (chose Cancel rather than Replace or Keep), so the picked format / grid is uncommitted
			// intent and must NOT leak into the next save's defaults. restorePriorSaveSettings also
			// nulls priorSnapshot.sourceImage so the Bitmap reference doesn't pin until a later
			// save/load clears it.
			Runnable rollbackOnAbort = () ->
			{
				cleanupPlaceholder.run();
				restorePriorSaveSettings();
				savePending = false;
			};
			host.registerTransientDialog(new AlertDialog.Builder(host.getActivity())
				.setTitle("Replace " + requested + "?")
				.setMessage(message)
				.setPositiveButton("Replace", (dialog, which) ->
					onReplaceConfirmed(newUri, requested, cleanupPlaceholder))
				.setNeutralButton("Keep", (dialog, which) -> onReplaceKeep(newUri, cleanupPlaceholder))
				.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> rollbackOnAbort.run())
				.setOnCancelListener(dialog -> rollbackOnAbort.run())
				.show());
		}
		catch (RuntimeException e)
		{
			// BadTokenException if the activity died between the isDestroyed check and show, or any
			// other UI-thread throw from the dialog plumbing. Don't strand savePending, leak the
			// placeholder, OR leak rejected SaveDialog choices into the next save's defaults.
			Log.w(TAG, "replace-collision dialog failed to show", e);
			cleanupPlaceholder.run();
			restorePriorSaveSettings();
			savePending = false;
		}
	}

	/**
	 * Decide whether a sibling URI (the inferred pre-auto-rename target) actually has a
	 * colliding document with real content behind it. The "(N)" suffix on `chosen` could be either
	 * SAF auto-renaming around a real collision OR the user typing "(N)" intentionally in the
	 * picker — we can't tell from the suffix alone. Probe order:
	 *   1. getDisplayName non-null → document accessible and named → existence confirmed.
	 *      Then probe querySafFileSize: a 0-byte sibling is a placeholder from an interrupted
	 *      prior save (or some providers' pre-creation behaviour) with no real content the user
	 *      needs to preserve, so skip the Replace dialog. -1 means the provider doesn't expose
	 *      OpenableColumns.SIZE — trust existence in that case (no negative signal). This aligns
	 *      with probeWasOverwrite's size > 0 gate for Case A/C, restoring a consistent
	 *      "real-content == collision" predicate across all three cases.
	 *   2. fileFromSafUri resolves to a File: File.exists() + length > 0 is the authoritative
	 *      filesystem answer.
	 *   3. baseUri == null (opaque-ID provider can't derive the sibling) OR every probe is
	 *      inconclusive: return false. We have no proof of real-content collision and asserting
	 *      one surfaces a Replace dialog about a phantom file when the user just typed "(N)"
	 *      themselves. The fall-through path saves the user's "(N)" name as-is; a real collision
	 *      in this rare opaque-provider case becomes a duplicate save under a different name (no
	 *      data loss), not silent overwrite.
	 */
	private boolean siblingLooksLikeCollision(Uri baseUri)
	{
		if (baseUri == null)
		{
			return false;
		}
		if (safFiles.getDisplayName(baseUri) != null)
		{
			// Existence confirmed. Filter out 0-byte placeholders by probing size: querySafFileSize
			// returns >0 for real content, 0 for placeholder, -1 when the provider doesn't expose
			// SIZE. `size != 0` accepts both real-content and unknown-size as collision, only
			// excluding the explicit 0-byte case.
			return safFiles.querySafFileSize(baseUri) != 0;
		}
		File baseFile = safFiles.fileFromSafUri(baseUri);
		if (baseFile != null)
		{
			// Filesystem accessible — authoritative answer regardless of SAF query result. Same
			// size > 0 gate as above so an FS-visible 0-byte placeholder also doesn't trigger
			// Replace.
			return baseFile.exists() && baseFile.length() > 0;
		}
		// Both probes inconclusive. Without proof, prefer the false-negative outcome (save under the
		// SAF-assigned "(N)" name) over the false-positive Replace dialog.
		return false;
	}
}
