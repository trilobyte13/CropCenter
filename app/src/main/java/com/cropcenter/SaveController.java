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

import androidx.annotation.AnyThread;
import androidx.annotation.WorkerThread;

import com.cropcenter.model.Format;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.StoragePermissionHelper;
import com.cropcenter.view.DialogStrings;
import com.cropcenter.view.FolderPickerDialog;
import com.cropcenter.view.SaveDialog;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

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
 * Holds the per-save state (pendingSaveName, savePending, priorSnapshot) and owns the user-facing dialogs that guard
 * the encode pipeline. Encode / write / verify itself lives in ExportPipeline; collision policy in ReplaceStrategy.
 */
final class SaveController
{
	/**
	 * Snapshot of state.exportConfig.format / state.gridConfig.includeInExport taken in openSaveOptionsDialog
	 * before SaveDialog opens. SaveDialog's "Continue" commits the user's in-dialog selections to CropState
	 * directly, but the user can still cancel the SAF picker that follows — without this snapshot the cancelled
	 * choices would bake into the next save (Export Grid stays on, or the next default extension is .png after the
	 * user chose .jpg for the abandoned save). Restored on every abort path: SAF cancel, launcher.launch
	 * RuntimeException, post-dialog busy-toast early return.
	 *
	 * @param sourceImage the Bitmap loaded when the snapshot was taken — pinned so restorePriorSaveSettings
	 *                    can detect a stale snapshot and skip the rollback: a Share/View intent arriving
	 *                    with the dialog open runs applyBytes → reset() + setSourceImage(new) on the bg
	 *                    executor, leaving this field pointing at the OLD bitmap. Without the
	 *                    bitmap-reference check the rollback would overwrite the new image's fresh
	 *                    exportConfig.format with the old one's, defaulting the next save to the wrong format
	 * @param format      exportConfig.format to restore on abort
	 * @param includeGrid gridConfig.includeInExport to restore on abort
	 */
	private record PriorSaveSnapshot(Bitmap sourceImage, Format format, boolean includeGrid) {}

	private static final String TAG = "SaveController";

	// Serializes every read-modify-write-commit on the two journal StringSets (KEY_INFLIGHT_TEMP_PATHS and
	// KEY_KEPT_RECOVERY_PATHS). Static because the contention is CROSS-INSTANCE within one process: an
	// Activity recreation (uiMode / locale / multi-window — the manifest handles rotation) leaves an
	// in-flight save running on the OLD instance's executor while the NEW instance's onCreate posts the
	// startup sweep on a fresh executor; unsynchronized, one side's commit silently erases the other's
	// fresh entry — un-protecting a kept recovery a dialog promised was safe, or un-journaling an in-flight
	// temp past the hard-kill backstop. Never held across filesystem work: the sweep locks only its journal
	// snapshot and its remove-only merge-commit (sweepStaleSaveTemps / commitSweptJournal), so writers —
	// including unjournalInflightTemp's @AnyThread UI-thread disposal sites — block at worst for a set copy
	// plus a small synchronous prefs commit, never for the sweep's unbounded folder listing.
	private static final Object JOURNAL_LOCK = new Object();

	// StringSet of ABSOLUTE PATHS of in-flight save temps, journaled at creation (journalInflightTemp) and
	// removed on every in-process disposal. The hard-kill backstop that lets the startup sweep DELETE a
	// stranded temp in whatever folder it sits, not just the current last-save folder. Deliberately a separate
	// set from KEY_KEPT_RECOVERY_PATHS — temps are DELETABLE once stale, kept recoveries are PROMOTABLE — and
	// the sweep guards the kept set's precedence explicitly when a path appears in both.
	private static final String KEY_INFLIGHT_TEMP_PATHS = "inflight_temp_paths";
	// StringSet of ABSOLUTE PATHS of hidden temp files a Replace failure dialog promised were KEPT
	// (journalKeptRecovery). Folder-scoped so the startup sweep resolves each entry against the folder the
	// recovery actually sits in, whatever the current last-save folder is; the sweep promotes these to visible
	// names instead of deleting them. No other entry shape needs parsing: no release ever shipped this journal,
	// so there are no legacy bare-name entries to migrate.
	private static final String KEY_KEPT_RECOVERY_PATHS = "kept_recovery_paths";
	private static final String KEY_LAST_LOAD_FOLDER = "last_load_folder";
	private static final String KEY_LAST_LOAD_FOLDER_TS = "last_load_folder_ts";
	private static final String KEY_LAST_SAVE_FOLDER = "last_save_folder";
	private static final String KEY_LAST_SAVE_FOLDER_TS = "last_save_folder_ts";
	private static final String PREFS_NAME = "cropcenter_save";
	// Highest "(N)" probed before the empty give-up. Package-private: also bounds ReplaceStrategy's
	// no-replace promotion retry loop (retryPromotionOntoFreeSibling) so probe and retry share one cap.
	static final int MAX_RENAME_SUFFIX = 9999;

	private final ExportPipeline exportPipeline;
	private final ReplaceStrategy replaceStrategy;
	private final SafFileHelper safFiles;
	private final SaveHost host;
	private final StoragePermissionHelper permissions;

	private PriorSaveSnapshot priorSnapshot;
	// Filename we asked SAF to create. When SAF silently auto-renames to avoid a collision (e.g. "vacation.jpg" →
	// "vacation (1).jpg"), the returned URI's display name won't match this — that's how we detect the rename.
	private String pendingSaveName;
	// Set when a save is committed to a target — SAF picker launch on the legacy path, or onMergedSaveConfirmed on
	// the in-app (MES) path — and cleared on result / cancel / collision-dialog resolution. Gates rapid taps
	// between commit and outcome; host.getBusy().get() doesn't flip until exportTo actually runs, so it isn't
	// sufficient on its own.
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
	 * Identity gate binding a bg-hop save continuation to the image the save was initiated for, running the abort
	 * action when the binding is broken. Reference identity (==, not equals) is the contract — the same check
	 * restorePriorSaveSettings applies to its snapshot: a Share/View load that completes while a probe is on the
	 * bg executor installs a different Bitmap instance via CropState.setSourceImage, and a continuation still
	 * holding the initiation-time reference would export the NEW image to the OLD image's destination. Object-typed
	 * rather than Bitmap-typed because the decision is pure reference identity and the headless suite cannot
	 * construct Bitmap instances; production callers pass the captured and live source bitmaps. Tested via
	 * SaveControllerTest.
	 *
	 * @param initiatedFor source-image reference captured on the UI thread when the save flow entered its bg hop
	 * @param liveSource   source-image reference installed in CropState when the continuation runs
	 * @param abortStale   abort action (placeholder cleanup + savePending clear + settings rollback + toast); runs
	 *                     exactly once when the references differ, never when they match
	 * @return true when the continuation must stop (the abort action has run); false when the save may
	 *         proceed against the still-live source
	 */
	static boolean abortIfSourceChanged(Object initiatedFor, Object liveSource, Runnable abortStale)
	{
		if (initiatedFor == liveSource)
		{
			return false;
		}
		abortStale.run();
		return true;
	}

	/**
	 * Detect SAF's auto-rename pattern on `chosen` alone and return the inferred base name (what the user was
	 * actually trying to save), or empty when chosen doesn't look like an auto-rename. Pattern: "X (N).ext" —
	 * non-empty stem, 1+ digits, optional leading whitespace.
	 *
	 * Deriving the base from `chosen` (not pendingSaveName) handles both the classical auto-rename ("crop.jpg" →
	 * "crop (1).jpg" → base "crop.jpg") and user-edited-then-collided (user typed "foo.jpg", SAF returned "foo
	 * (1).jpg" → base "foo.jpg", the name that ACTUALLY collided). A false positive from a user typing "(N)"
	 * without a real collision is filtered at the call site by checking the base actually exists.
	 *
	 * Kept here, not in util/, because the "X (N).ext" pattern IS the SAF DocumentsUI convention —
	 * save-flow-specific, not generic. Tested via SaveControllerTest.
	 *
	 * @param chosen SAF-returned filename (e.g. "crop (1).jpg")
	 * @return inferred base name when chosen matches the auto-rename pattern, empty otherwise
	 */
	static Optional<String> autoRenameBaseName(String chosen)
	{
		if (chosen == null)
		{
			return Optional.empty();
		}
		int choDot = chosen.lastIndexOf('.');
		if (choDot <= 0)
		{
			return Optional.empty();
		}
		String choStem = chosen.substring(0, choDot);
		String choExt = chosen.substring(choDot);
		// Must end with "(digits)"; allow optional whitespace between the stem and the open paren to match SAF
		// variants that use "stem (1)" vs "stem(1)".
		if (!choStem.endsWith(")"))
		{
			return Optional.empty();
		}
		int openParen = choStem.lastIndexOf('(');
		if (openParen <= 0)
		{
			return Optional.empty();
		}
		String between = choStem.substring(openParen + 1, choStem.length() - 1);
		if (between.isEmpty() || !between.chars().allMatch(Character::isDigit))
		{
			return Optional.empty();
		}
		String baseStem = choStem.substring(0, openParen).stripTrailing();
		if (baseStem.isEmpty())
		{
			return Optional.empty();
		}
		return Optional.of(baseStem + choExt);
	}

	/**
	 * Pure decision core of probeWasOverwrite: combine the three prior-content signals into the overwrite
	 * classification. ANY positive signal confirms. The content-stream probe is consulted only when the provider
	 * omitted SIZE (priorSize negative) — an explicit 0 already answered "empty placeholder", and opening a stream
	 * on it would add nothing. The filesystem signal overrides even an explicit 0: a path-resolved file with real
	 * bytes is authoritative over provider metadata (Samsung MediaStore-backed pickers under-report both SIZE and
	 * stream access). Tested via SaveControllerTest's truth table.
	 *
	 * @param priorSize           provider-reported document size: greater than 0 means real content, 0 means
	 *                            explicitly empty, negative means the provider doesn't expose
	 *                            OpenableColumns.SIZE
	 * @param streamServesContent lazy content-stream probe, true when openInputStream serves at least one
	 *                            byte; invoked only when priorSize is negative
	 * @param fileHasContent      true when the path-resolved File exists on disk with length greater than 0
	 * @return true when any probe confirms content an overwrite would destroy
	 */
	static boolean classifyOverwrite(long priorSize, BooleanSupplier streamServesContent, boolean fileHasContent)
	{
		if (priorSize > 0)
		{
			return true;
		}
		if (fileHasContent)
		{
			return true;
		}
		return priorSize < 0 && streamServesContent.getAsBoolean();
	}

	/**
	 * Pure decision core of siblingLooksLikeCollision: decide whether the inferred pre-auto-rename base
	 * document is a real collision worth a Replace dialog. Filesystem first: a path-resolved base answers with
	 * exists() + length() > 0 and the SAF probes are never consulted — getDisplayName's path-first shortcut
	 * string-parses path-addressable URIs with no existence check, so display-name presence there would
	 * confirm a phantom base the user merely implied by typing "(N)". With no path resolved, presence came
	 * from the real ContentResolver query (an actual existence proof) and querySafFileSize filters 0-byte
	 * placeholders. Tested via SaveControllerTest's truth table.
	 *
	 * @param baseFile           path-resolved File for the sibling URI, or null when the URI isn't
	 *                           path-addressable
	 * @param displayNamePresent lazy display-name probe, true when the provider query names the document;
	 *                           invoked only when baseFile is null
	 * @param safSize            lazy provider-size probe (greater than 0 real content, 0 explicitly empty,
	 *                           negative when the provider doesn't expose OpenableColumns.SIZE); invoked only
	 *                           when baseFile is null and the display name is present
	 * @return true when the sibling exists with real (or unknown-size) content; false when it is absent, a
	 *         0-byte placeholder, or every probe is inconclusive
	 */
	static boolean classifySiblingCollision(File baseFile, BooleanSupplier displayNamePresent, LongSupplier safSize)
	{
		if (baseFile != null)
		{
			// Filesystem accessible — authoritative regardless of SAF query results. Same size > 0 gate as
			// classifyOverwrite so an FS-visible 0-byte placeholder doesn't trigger Replace.
			return baseFile.exists() && baseFile.length() > 0;
		}
		if (displayNamePresent.getAsBoolean())
		{
			// Existence confirmed by the ContentResolver query. querySafFileSize returns > 0 for real
			// content, 0 for placeholder, -1 when the provider doesn't expose SIZE; != 0 accepts both
			// real-content and unknown-size as collision, only excluding the explicit 0-byte case.
			return safSize.getAsLong() != 0;
		}
		// Both probes inconclusive. Without proof, prefer the false-negative outcome (save under the
		// SAF-assigned "(N)" name) over the false-positive Replace dialog on a phantom.
		return false;
	}

	/**
	 * Commit one journal's sweep result with remove-only merge semantics: the committed set is the CURRENT
	 * live set minus the entries the sweep resolved (present in preSweep, gone from postSweep). The sweep's
	 * filesystem passes run on snapshot copies outside JOURNAL_LOCK, so a save journaling concurrently adds
	 * entries the sweep never saw — subtracting only the resolved entries from a fresh read (instead of
	 * committing the swept copy wholesale) preserves them. The sweep only ever removes entries, so an
	 * unchanged merge means nothing to commit. commit(), not apply(): runs on the bg executor, and the
	 * durable write keeps the journal in step with the disk state the sweep just produced; a failed commit
	 * degrades to a warn — the next sweep re-resolves the same entries. Callers must hold JOURNAL_LOCK; the
	 * headless suite drives this seam single-threaded (SaveControllerTest).
	 *
	 * @param prefs     the save-flow SharedPreferences holding the journal
	 * @param key       journal StringSet key to merge and commit
	 * @param preSweep  journal snapshot taken under JOURNAL_LOCK before the sweep's filesystem passes ran
	 * @param postSweep the swept copy of that snapshot, minus the entries the sweep resolved (promoted,
	 *                  deleted, or vanished)
	 * @param label     short journal name interpolated into the failed-commit warn log
	 */
	static void commitSweptJournal(SharedPreferences prefs, String key, Set<String> preSweep,
		Set<String> postSweep, String label)
	{
		Set<String> live = new HashSet<>(prefs.getStringSet(key, Collections.emptySet()));
		Set<String> merged = new HashSet<>(live);
		for (String entry : preSweep)
		{
			if (!postSweep.contains(entry))
			{
				merged.remove(entry);
			}
		}
		if (!merged.equals(live) && !prefs.edit().putStringSet(key, merged).commit())
		{
			Log.w(TAG, "sweepStaleSaveTemps: " + label + " journal commit failed");
		}
	}

	/**
	 * Derive the default save-name stem from the source image's original filename: absent/empty falls back to
	 * "crop", a reserved temp-namespace name is de-reserved first, and any recognised extension is stripped so
	 * the caller can append the export format's own. The de-reservation covers a rescued hidden recovery
	 * re-shared into the app: its temp-shaped filename would pre-fill the save field with a name the
	 * reserved-namespace validation can only reject, so the current marker-prefix shape strips to its embedded
	 * visible name (SaveTempFiles.visibleNameOf) and every unparseable reserved shape (legacy suffix temps,
	 * one-nonce-short marker names) falls back to the default stem. Package-private static so
	 * SaveControllerTest can pin the de-reservation.
	 *
	 * @param originalFilename the loaded source's provider-reported filename, or null when none was recorded
	 * @return extensionless default stem (callers appending an extension or sanitising provider-controlled
	 *         characters do so at their own site)
	 */
	static String defaultSaveStem(String originalFilename)
	{
		String stem = originalFilename;
		if (stem == null || stem.isEmpty())
		{
			stem = "crop";
		}
		else if (SaveTempFiles.isReservedName(stem))
		{
			stem = SaveTempFiles.visibleNameOf(stem).orElse("crop");
		}
		return Format.stripExtension(stem);
	}

	/**
	 * Extension-mismatch reject predicate for the legacy SAF save flow — true when `chosen`'s extension promises an
	 * encoding other than what `requested`'s Format will actually produce. Rejects two misuse shapes and lets two
	 * ambiguous shapes through:
	 *   - .jpg → .png (or vice versa): both map to known Formats but differ → reject
	 *   - .jpg → .webp (or .heic / any unknown image extension): chosen's Format lookup comes back empty but the
	 *     extension is clearly NOT what the encoder will produce → reject
	 *   - .jpg → "filename-no-extension": ambiguous — let it through, the SAF MIME still says image/jpeg and the
	 *     encoder's bytes are valid for that MIME
	 *   - chosen = ".png" (lastIndexOf('.') == 0): a bare-dot leading name has no separable extension (same
	 *     convention as Format.stripExtension) — treated as extensionless and let through
	 * A `requested` with no recognised Format (extensionless / unknown) never rejects — there's no promised
	 * encoding to mismatch against. Package-private static so SaveControllerTest can pin the truth table.
	 *
	 * @param requested the filename the user typed before the picker opened (may be null)
	 * @param chosen    the display name SAF returned for the created document (may be null)
	 * @return true when the save must be rejected as a cross-format rename
	 */
	static boolean extensionMismatch(String requested, String chosen)
	{
		Optional<Format> requestedFormat = Format.fromExtension(requested);
		Optional<Format> chosenFormat = Format.fromExtension(chosen);
		boolean chosenHasExt = chosen != null && chosen.lastIndexOf('.') > 0;
		// An empty chosenFormat with a real extension (.webp / .heic) still mismatches a present
		// requestedFormat — Optional.equals compares emptiness as well as the wrapped constants, so the
		// reject predicate holds.
		return requestedFormat.isPresent() && chosenHasExt && !chosenFormat.equals(requestedFormat);
	}

	/**
	 * Journal an in-flight save temp's absolute path BEFORE the temp file is created — the hard-kill backstop
	 * that lets the startup sweep delete a stranded temp in whatever folder it sits (the unjournaled delete
	 * pass reaches only the last-save folder). All three temp writers call this ahead of creating their
	 * artifact: ExportPipeline.tryDirectAtomicWrite, ReplaceStrategy.replaceViaFileIo, and
	 * probeAndPlaceholderOnBg's crash-safe SAF placeholder. A false return (no resolvable folder, or the
	 * synchronous commit failed) must never abort the save — the temp is still cleaned in-process on every
	 * non-kill path; the journal only covers the kill window. Entries are removed again on every in-process
	 * disposal (unjournalInflightTemp) and resolved by the sweep otherwise.
	 *
	 * @param ctx        any Context (Activity preferred); used only for getSharedPreferences
	 * @param tempFolder folder the temp will be created in, or null when the caller couldn't resolve one
	 *                   (opaque SAF provider) — the persisted last-save folder stands in, mirroring
	 *                   journalKeptRecovery; a temp in a folder no path resolves to is out of the path-based
	 *                   sweep's reach regardless, so its self-healing stale entry costs one sweep check
	 * @param tempName   the hidden SaveTempFiles temp filename about to be created
	 * @return true when the entry is durably journaled (including the already-journaled no-op); false when
	 *         no folder was resolvable or the synchronous commit failed — logged, never save-fatal
	 */
	@WorkerThread
	static boolean journalInflightTemp(Context ctx, File tempFolder, String tempName)
	{
		return journalInflightTemp(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), tempFolder,
			tempName);
	}

	/**
	 * Prefs-seam core of journalInflightTemp(Context, File, String) — the Context overload resolves the app's
	 * save prefs; the headless suite drives this overload with an in-memory SharedPreferences fake. Same
	 * commit() rationale as journalKeptRecovery: the temp is created right after this returns, and an apply()
	 * still in flight when a process kill lands would leave the stranded temp unjournaled.
	 *
	 * @param prefs      the save-flow SharedPreferences holding the in-flight temp journal
	 * @param tempFolder folder the temp will be created in, or null for the last-save-folder fallback
	 * @param tempName   the hidden SaveTempFiles temp filename about to be created
	 * @return true when the entry is durably journaled (including the already-journaled no-op); false when
	 *         no folder was resolvable or the synchronous commit failed
	 */
	@WorkerThread
	static boolean journalInflightTemp(SharedPreferences prefs, File tempFolder, String tempName)
	{
		String tempPath = resolveJournalPath(prefs, tempFolder, tempName);
		if (tempPath == null)
		{
			Log.w(TAG, "journalInflightTemp: no folder resolvable for " + tempName + " — unjournaled");
			return false;
		}
		synchronized (JOURNAL_LOCK)
		{
			Set<String> paths = new HashSet<>(prefs.getStringSet(KEY_INFLIGHT_TEMP_PATHS,
				Collections.emptySet()));
			if (!paths.add(tempPath))
			{
				return true; // already journaled — the durable entry is in place
			}
			if (!prefs.edit().putStringSet(KEY_INFLIGHT_TEMP_PATHS, paths).commit())
			{
				Log.w(TAG, "journalInflightTemp: commit failed for " + tempPath);
				return false;
			}
			return true;
		}
	}

	/**
	 * Journal a kept recovery that is still on disk under its hidden SaveTempFiles temp name. Called by
	 * ReplaceStrategy.verifyReplace right before a failure dialog promises the user the file was KEPT — the
	 * journal is what stops the startup sweep from deleting that exact file once it passes the stale age guard:
	 * sweepStaleSaveTemps hands the journaled paths to SaveTempFiles.sweepStaleTempArtifacts, which promotes
	 * them to visible "stem (N).ext" siblings instead of deleting them. Also called by ReplaceStrategy's
	 * strategy-C fallback BEFORE it deletes the colliding original, protecting the placeholder across the
	 * delete-to-retry-rename crash window. Entries are the hidden file's ABSOLUTE PATH, pinning the protection
	 * to the folder the recovery sits in — a later save into a different folder can neither vanish-drop the
	 * entry nor expose the file to that folder's sweep. Idempotent per path. The return value is the honesty
	 * signal for the caller's dialog wording: a false return means the sweep WILL treat the file as an
	 * unjournaled stale temp, so a "was kept" promise must degrade rather than imply protection.
	 *
	 * @param ctx        any Context (Activity preferred); used only for getSharedPreferences
	 * @param keptFolder folder the kept file sits in, or null when the caller couldn't resolve one (opaque
	 *                   SAF provider) — the persisted last-save folder stands in, and when even that is
	 *                   missing the recovery goes unjournaled: a folder no path resolves to is also one the
	 *                   path-based sweep can never list, so the file is out of the sweep's reach regardless
	 * @param keptName   the hidden temp filename the dialog promised was kept
	 * @return true when the entry is durably journaled (including the already-journaled no-op); false when
	 *         no folder was resolvable or the synchronous commit failed — the recovery is unprotected
	 */
	@WorkerThread
	static boolean journalKeptRecovery(Context ctx, File keptFolder, String keptName)
	{
		SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		String keptPath = resolveJournalPath(prefs, keptFolder, keptName);
		if (keptPath == null)
		{
			Log.w(TAG, "journalKeptRecovery: no folder resolvable for " + keptName + " — unjournaled");
			return false;
		}
		synchronized (JOURNAL_LOCK)
		{
			Set<String> paths = new HashSet<>(prefs.getStringSet(KEY_KEPT_RECOVERY_PATHS,
				Collections.emptySet()));
			if (!paths.add(keptPath))
			{
				return true; // already journaled — the durable entry is in place
			}
			// commit(), not apply(): the caller shows the "kept" dialog right after this returns, and
			// the journal is what protects the promised file from the startup sweep — an apply() still
			// in flight when a process kill lands would leave the recovery unjournaled. Already on the
			// bg executor, so the synchronous write costs nothing user-visible.
			if (!prefs.edit().putStringSet(KEY_KEPT_RECOVERY_PATHS, paths).commit())
			{
				Log.w(TAG, "journalKeptRecovery: commit failed for " + keptPath);
				return false;
			}
			return true;
		}
	}

	/**
	 * Initial folder for both the FolderPickerDialog (save flow) and the OpenPickerDialog (load flow). Picks
	 * whichever of (last-save-folder, last-load-folder) was MORE RECENTLY recorded by comparing the persisted
	 * timestamps. Falls back to whichever single folder is available if the other is missing, then to primary
	 * external storage. Each persisted path is sanity-checked for existence + directory-ness so a deleted folder
	 * doesn't strand the picker at a non-existent path; if the more-recent folder is missing on disk the other
	 * folder is tried before falling through to external storage.
	 *
	 * Rationale: a user who loaded a fresh image and immediately taps Save expects to land in the folder they just
	 * loaded from, not the folder of an old save from a previous session — the timestamp comparison makes "most
	 * recent action" the tiebreaker (an unconditional save-folder preference would let a stale save folder from
	 * days ago beat a load folder from minutes ago). The same rationale applies to Open: tapping
	 * Open right after a save expects to land in the just-saved folder, not the older load folder. Static +
	 * Context-taking so MainActivity can compute the OpenPickerDialog's start dir without holding a SaveController
	 * reference.
	 *
	 * @param ctx any Context (Activity preferred); used only for getSharedPreferences
	 * @return the most-recently-recorded existing folder, or the primary external storage root when
	 *         no prior folder is usable
	 */
	static File loadInitialPickerFolder(Context ctx)
	{
		SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		File savedFolder = readFolder(prefs, KEY_LAST_SAVE_FOLDER).orElse(null);
		File loadedFolder = readFolder(prefs, KEY_LAST_LOAD_FOLDER).orElse(null);
		long savedTs = prefs.getLong(KEY_LAST_SAVE_FOLDER_TS, 0L);
		long loadedTs = prefs.getLong(KEY_LAST_LOAD_FOLDER_TS, 0L);
		File picked = pickInitialSaveFolder(savedFolder, savedTs, loadedFolder, loadedTs);
		return picked != null ? picked : Environment.getExternalStorageDirectory();
	}

	/**
	 * Find the first available "stem (N).ext" filename in `folder` that doesn't collide. Strips any existing "(N)"
	 * suffix from `original` first so renaming "foo (1).jpg" suggests "foo (2).jpg" rather than "foo (1) (1).jpg".
	 * Used by showInAppRenameDialog to pre-populate the input with a good default. Returns empty when N=1..9999
	 * are all taken — the caller pre-fills with the colliding name as a fallback so the user has to type a unique
	 * name manually (returning `original` here would silently re-suggest the same colliding name and the
	 * user-cycle Rename → OK → collision-dialog would never terminate on the auto-suggestion alone).
	 *
	 * @param folder   target directory to probe
	 * @param original colliding filename (e.g. "foo.jpg" or "foo (3).jpg")
	 * @return first non-colliding name in the form "stem (N).ext", or empty if N=1..9999 are all taken
	 */
	static Optional<String> nextAvailableNumberedName(File folder, String original)
	{
		return nextAvailableNumberedName(folder, original, MAX_RENAME_SUFFIX);
	}

	/**
	 * Bounded-probe core of nextAvailableNumberedName(File, String) — the two-arg overload passes
	 * MAX_RENAME_SUFFIX; tests pass a small bound to exercise the exhaustion path without creating thousands of
	 * files.
	 *
	 * @param folder    target directory to probe
	 * @param original  colliding filename (e.g. "foo.jpg" or "foo (3).jpg")
	 * @param maxSuffix highest N probed (inclusive)
	 * @return first non-colliding name in the form "stem (N).ext", or empty if N=1..maxSuffix are all
	 *         taken
	 */
	static Optional<String> nextAvailableNumberedName(File folder, String original, int maxSuffix)
	{
		// Detect + strip existing "(N)" suffix via the shared parser. autoRenameBaseName returns the base
		// ("foo.jpg" given "foo (1).jpg") or empty if `original` doesn't match the pattern; we fall back to
		// the original name in that case.
		String startFrom = autoRenameBaseName(original).orElse(original);
		int dot = startFrom.lastIndexOf('.');
		String stem = dot > 0 ? startFrom.substring(0, dot) : startFrom;
		String ext = dot > 0 ? startFrom.substring(dot) : "";
		for (int n = 1; n <= maxSuffix; n++)
		{
			String candidate = stem + " (" + n + ")" + ext;
			if (!new File(folder, candidate).exists())
			{
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	/**
	 * Pure-function priority helper for loadInitialPickerFolder — extracted so the timestamp + existence fallback
	 * logic can be unit-tested without instantiating SharedPreferences. Prefers whichever of (savedFolder,
	 * loadedFolder) has the larger timestamp; on a tie, prefers savedFolder; if the more-recent folder reference is
	 * null (caller's existence check rejected it as missing / not-a-directory), falls back to the other folder.
	 * Returns null when both folder references are null — caller routes that to
	 * Environment.getExternalStorageDirectory().
	 *
	 * @param savedFolder  folder reference for the last-save path, or null when missing/deleted
	 * @param savedTs      timestamp of the last save (0 when never recorded)
	 * @param loadedFolder folder reference for the last-load path, or null when missing/deleted
	 * @param loadedTs     timestamp of the last load (0 when never recorded)
	 * @return the preferred non-null folder, or null when both are null
	 */
	static File pickInitialSaveFolder(File savedFolder, long savedTs, File loadedFolder, long loadedTs)
	{
		// Prefer the folder with the larger timestamp; ties go to save. The equality case is essentially
		// impossible in practice — it needs two system-clock-identical actions — so the tie-break is
		// arbitrary but pinned (tests rely on it staying deterministic).
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
	 * Promotion callback the startup sweep runs on a stale journaled kept recovery instead of deleting it.
	 * Derives the original visible filename back out of the hidden temp name (SaveTempFiles.visibleNameOf) and
	 * routes through ReplaceStrategy.promoteToVisibleName — the same rename the Replace failure dialogs use — so
	 * the recovery lands at the first free "stem (N).ext" sibling. Package-private static so SaveTempFilesTest
	 * can drive the sweep with the production callback.
	 *
	 * @param keptFile journaled kept recovery, on disk under its hidden temp name
	 * @return true when the file no longer sits under a hidden temp name (journal entry can be dropped);
	 *         false when the name couldn't be parsed or the rename failed (entry retained for a retry at
	 *         the next launch)
	 */
	static boolean promoteJournaledRecovery(File keptFile)
	{
		String visibleName = SaveTempFiles.visibleNameOf(keptFile.getName()).orElse(null);
		if (visibleName == null)
		{
			return false;
		}
		return !SaveTempFiles.isTempArtifact(ReplaceStrategy.promoteToVisibleName(keptFile, visibleName));
	}

	/**
	 * Persist the parent folder of a just-loaded file so the next FolderPickerDialog open can land there when no
	 * save folder has been recorded yet. ImageLoadController calls this on successful load with whatever
	 * SafFileHelper.fileFromSafUri resolved the URI to; cloud / SAF-only URIs that don't resolve to a filesystem
	 * path pass `loadedFile == null` and the call is a no-op.
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
	 * Guard for the legacy-SAF result path: a provider-supplied display name is untrusted input to filesystem
	 * sinks — Case C hands it verbatim to routeCrashSafeSave, where it becomes SaveTempFiles.tempName input, a
	 * sibling-placeholder document name, and a raw new File(parent, name) path component. Reject any non-null
	 * name the app's own typed-name validation (FolderPickerDialog.isValidFilename — path separators, traversal
	 * segments, the reserved crash-safe temp namespace, over-length) would refuse. A null name passes: the
	 * opaque-provider fallback saves under the already-validated requested name. Package-private + static so
	 * the test class can pin the rejection without a ContentResolver.
	 *
	 * @param chosen display name the provider returned for the picked document, or null when the
	 *               query failed
	 * @return true when the save must abort with the "Invalid filename" toast; false when the name is
	 *         safe (or absent) and the result routing may proceed
	 */
	static boolean rejectsProviderDisplayName(String chosen)
	{
		return chosen != null && !FolderPickerDialog.isValidFilename(chosen);
	}

	/**
	 * Run a bg-hop save continuation that was posted to the main looper, converting the two failure shapes a
	 * posted runnable cannot legally propagate into clean aborts. Continuations posted via runOnUiThread from
	 * the bg probe hop run even when the Activity was destroyed mid-hop (back-press, uiMode / locale change,
	 * multi-window entry — Activity.runOnUiThread posts unconditionally), and any exception escaping a posted
	 * runnable is an uncaught main-looper exception that kills the whole process. Two conversions:
	 *   - destroyed host: onDestroy already ran, so the executor is shut down and no dialog may show — run
	 *     onDestroyed (the caller's cleanup: placeholder delete, savePending clear, settings rollback; no
	 *     toast — there is no live UI to inform) and skip the body entirely
	 *   - body throw: the dispatch guards in routeCrashSafeSave and ExportPipeline.exportTo clean up
	 *     (savePending cleared, placeholder deleted, busy released) and then RETHROW for their genuinely
	 *     synchronous callers; a posted continuation has no caller to see the rethrow, so it is absorbed
	 *     here after that cleanup instead of crossing into the main looper
	 * Both posted continuations (dispatchCrashSafeWrite, onOverwriteProbed) route through this seam.
	 * Package-private static so SaveControllerTest can pin both conversions with a fake host — the same seam
	 * pattern as abortIfSourceChanged.
	 *
	 * @param host        host whose destroyed state gates the continuation, checked on the UI thread at run
	 *                    time
	 * @param label       short continuation name interpolated into the two warn logs
	 * @param onDestroyed cleanup action run instead of the body when the host is already destroyed
	 * @param body        the continuation body; a RuntimeException it lets escape (the dispatch-guard
	 *                    rethrow, cleanup already run) is logged, never propagated
	 */
	static void runPostedSaveContinuation(EditorHost host, String label, Runnable onDestroyed, Runnable body)
	{
		if (host.isDestroyed())
		{
			Log.w(TAG, label + ": host destroyed during bg hop — aborting posted continuation");
			onDestroyed.run();
			return;
		}
		try
		{
			body.run();
		}
		catch (RuntimeException e)
		{
			// Expected shape: RejectedExecutionException rethrown by a dispatch guard whose cleanup
			// already ran. Anything else is a bug this log records — still strictly better than an
			// uncaught main-looper exception killing the process mid-save.
			Log.w(TAG, label + ": posted save continuation failed after cleanup", e);
		}
	}

	/**
	 * Reclaim stale crash-safe save temps (hidden SaveTempFiles artifacts) and resolve both journals against
	 * every folder they name. Hard process kills strand the write-temp / placeholder siblings the save
	 * pipeline creates in the user's save directory — hidden from Gallery and file managers, they'd otherwise
	 * leak permanently (a 200 MP PNG temp is 400-600 MB). Journal entries are absolute paths, so the sweep
	 * visits every journaled parent independently. The in-flight temp journal (journalInflightTemp — every
	 * temp's path, recorded before creation) extends the DELETE reach to whatever folder a stranded temp sits
	 * in; the last-save folder's unjournaled delete pass remains for pre-journal legacy orphans. A
	 * kept-recovery entry (journalKeptRecovery — a recovery a Replace failure dialog promised was kept) is
	 * never deleted, even when its path also sits in the temp journal: once stale it is promoted to a visible
	 * "stem (N).ext" sibling via promoteJournaledRecovery, and its entry is dropped on successful promotion or
	 * when the file vanished from its own folder. Called once at startup on the bg executor
	 * (MainActivity.onCreate posts it — the save-folder listing is unbounded), mirroring
	 * UltraHdrCompat.sweepStaleCacheFiles for cacheDir.
	 *
	 * NO filesystem work runs under JOURNAL_LOCK: the lock is held only to snapshot both journal sets, and
	 * again after the sweep to merge the result back (commitSweptJournal — remove-only, so entries a save
	 * journals concurrently with the unlocked sweep survive). unjournalInflightTemp is @AnyThread — an
	 * in-flight save's UI-thread disposal (abortSaveContinuation during an Activity recreation, exactly when
	 * the new instance's sweep runs) must never block behind the unbounded folder listing. The unlocked
	 * filesystem passes are safe against files created while they run: SaveTempFiles.STALE_TEMP_MIN_AGE_MS
	 * (1 hour) retains anything fresh, so a concurrently-created temp can never be deleted — only its journal
	 * entry needs the merge's remove-only protection.
	 *
	 * @param ctx any Context (Activity preferred); used only for getSharedPreferences
	 */
	@WorkerThread
	static void sweepStaleSaveTemps(Context ctx)
	{
		SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		Set<String> keptSnapshot;
		Set<String> tempsSnapshot;
		synchronized (JOURNAL_LOCK)
		{
			keptSnapshot = new HashSet<>(prefs.getStringSet(KEY_KEPT_RECOVERY_PATHS,
				Collections.emptySet()));
			tempsSnapshot = new HashSet<>(prefs.getStringSet(KEY_INFLIGHT_TEMP_PATHS,
				Collections.emptySet()));
		}
		// The sweep mutates its sets in place; run it on copies so the snapshots keep the pre-sweep state
		// the merge diffs against.
		Set<String> keptSwept = new HashSet<>(keptSnapshot);
		Set<String> tempsSwept = new HashSet<>(tempsSnapshot);
		SaveTempFiles.sweepStaleTempArtifacts(readFolder(prefs, KEY_LAST_SAVE_FOLDER).orElse(null),
			System.currentTimeMillis(), keptSwept,
			SaveController::promoteJournaledRecovery, tempsSwept);
		synchronized (JOURNAL_LOCK)
		{
			commitSweptJournal(prefs, KEY_KEPT_RECOVERY_PATHS, keptSnapshot, keptSwept, "kept-recovery");
			commitSweptJournal(prefs, KEY_INFLIGHT_TEMP_PATHS, tempsSnapshot, tempsSwept, "in-flight temp");
		}
	}

	/**
	 * Remove an in-flight temp journal entry once an in-process cleanup or rename disposed the temp (deleted,
	 * or renamed onto its final name). Every non-kill disposal path un-journals so the journal tracks only
	 * temps that could actually be stranded; a removal lost to a crash costs one extra sweep check (the sweep
	 * drops entries whose file is gone), which is why apply() suffices here where the journaling side needs
	 * commit(). Resolves the entry path exactly as journalInflightTemp does (resolveJournalPath) so the add /
	 * remove pair always targets the same entry; a missing entry or unresolvable folder is a harmless no-op.
	 *
	 * @param ctx        any Context (Activity preferred); used only for getSharedPreferences
	 * @param tempFolder folder the temp sat in, or null when the journaling caller couldn't resolve one (the
	 *                   persisted last-save folder stands in, mirroring journalInflightTemp)
	 * @param tempName   the hidden temp filename that was journaled
	 */
	@AnyThread
	static void unjournalInflightTemp(Context ctx, File tempFolder, String tempName)
	{
		unjournalInflightTemp(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), tempFolder, tempName);
	}

	/**
	 * Prefs-seam core of unjournalInflightTemp(Context, File, String) — the Context overload resolves the
	 * app's save prefs; the headless suite drives this overload with an in-memory SharedPreferences fake.
	 * apply(), not commit(): removals are safe to lose (one extra sweep check), and several disposal sites run
	 * on the UI thread where a synchronous commit would jank.
	 *
	 * @param prefs      the save-flow SharedPreferences holding the in-flight temp journal
	 * @param tempFolder folder the temp sat in, or null for the last-save-folder fallback
	 * @param tempName   the hidden temp filename that was journaled
	 */
	@AnyThread
	static void unjournalInflightTemp(SharedPreferences prefs, File tempFolder, String tempName)
	{
		String tempPath = resolveJournalPath(prefs, tempFolder, tempName);
		if (tempPath == null)
		{
			return;
		}
		synchronized (JOURNAL_LOCK)
		{
			Set<String> paths = new HashSet<>(prefs.getStringSet(KEY_INFLIGHT_TEMP_PATHS,
				Collections.emptySet()));
			if (paths.remove(tempPath))
			{
				prefs.edit().putStringSet(KEY_INFLIGHT_TEMP_PATHS, paths).apply();
			}
		}
	}

	/**
	 * Remove a kept-recovery journal entry once the recovery verifiably left its hidden temp name (a verified
	 * clean promotion). The sweep's own entry lifecycle would vanish-drop the entry at some later launch; the
	 * explicit removal keeps the journal from accumulating resolved entries in between. Resolves the entry path
	 * exactly as journalKeptRecovery does (resolveJournalPath) so the add / remove pair always targets the same
	 * entry. A missing entry, an unresolvable folder, or a failed commit is a harmless no-op — the sweep's
	 * lifecycle remains the backstop.
	 *
	 * @param ctx        any Context (Activity preferred); used only for getSharedPreferences
	 * @param keptFolder folder the kept file sat in when it was journaled, or null when the journaling caller
	 *                   couldn't resolve one (the persisted last-save folder stands in, mirroring
	 *                   journalKeptRecovery)
	 * @param keptName   the hidden temp filename that was journaled
	 */
	@WorkerThread
	static void unjournalKeptRecovery(Context ctx, File keptFolder, String keptName)
	{
		SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		String keptPath = resolveJournalPath(prefs, keptFolder, keptName);
		if (keptPath == null)
		{
			return;
		}
		synchronized (JOURNAL_LOCK)
		{
			Set<String> paths = new HashSet<>(prefs.getStringSet(KEY_KEPT_RECOVERY_PATHS,
				Collections.emptySet()));
			if (paths.remove(keptPath)
				&& !prefs.edit().putStringSet(KEY_KEPT_RECOVERY_PATHS, paths).commit())
			{
				Log.w(TAG, "unjournalKeptRecovery: commit failed for " + keptPath);
			}
		}
	}

	/**
	 * Route the SAF-returned URI to the correct save path. ACTION_CREATE_DOCUMENT's collision behaviour varies
	 * across providers, so the returned URI's display name is what tells us what actually happened.
	 *
	 * Preflight: if the user changed the extension to one implying a different encoder (.jpg ↔ .png), the
	 * document's MIME — locked before the picker opened — no longer matches the bytes we'd write. Reject with a
	 * dialog and leave newUri on disk. Do NOT delete the placeholder: the picker may return an existing zero-byte
	 * file after the provider's own Replace prompt, indistinguishable from a fresh empty doc, so deleting on
	 * rejection could destroy a real user file — a leftover placeholder is the safer fallout.
	 *
	 * Otherwise, by the returned name:
	 *
	 * (A) chosen == requested — SAF kept the name (new file, or the user accepted SAF's Replace prompt; the
	 *     URI can't tell which). Route through routeCrashSafeSave (sibling placeholder + write-then-swap,
	 *     falling back to preserve-on-failure direct overwrite on opaque-ID providers that can't placeholder).
	 *     probeWasOverwrite's 3-probe classification (run on the bg executor) gates the overwrite-confirm
	 *     dialog and sets wasOverwrite for the toast.
	 *
	 * (B) chosen has an "(N)" suffix AND the stripped base still exists in the directory — SAF silently
	 *     auto-renamed to dodge a collision (detected from `chosen` alone, so a user who edited the name and
	 *     still collided also lands here). Offer Replace / Keep / Cancel on the collided name.
	 *
	 * (C) chosen differs but isn't an auto-rename (or the stripped base doesn't collide) — the user typed a
	 *     different name. Same routeCrashSafeSave path as A, since the provider may still have shown its own
	 *     Replace prompt and returned pre-existing content; `chosen` drives the toast wording.
	 *
	 * @param newUri SAF document URI returned by the ACTION_CREATE_DOCUMENT picker
	 */
	void handleSaveAsResult(Uri newUri)
	{
		// Defensive try/finally guard: any unhandled exception from a SAF query during the result handler
		// (safFiles.getDisplayName, safFiles.deriveSiblingUri, siblingLooksLikeCollision,
		// applyFormatFromFilename) would otherwise escape with savePending stuck at true, leaving every
		// subsequent Save tap hitting "Busy — try again" until app restart. handleSaveAsResultBody returns true
		// ONLY when a downstream owner successfully took over the savePending lifecycle — the Case B Replace
		// dialog (its per-button / cancel / BadTokenException handlers manage the flag) or the Case A / C bg
		// overwrite probe (its UI continuation hands the flag to the overwrite-confirm dialog or clears it once
		// the write dispatches); every other path returns false and the finally clears the flag.
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
		// would find PNG + Export Grid as the next default — silently changing the next save's encoding based
		// on a save the user explicitly abandoned.
		restorePriorSaveSettings();
	}

	/**
	 * Save button handler. With MANAGE_EXTERNAL_STORAGE granted, runs the merged in-app FolderPickerDialog directly
	 * — format chips + Export Grid checkbox live in the same dialog as the folder navigator and thumbnail grid, so
	 * the user makes all choices in one place. Without MES, falls back to the legacy two-step flow: SaveDialog
	 * (format + grid) then ACTION_CREATE_DOCUMENT SAF picker (because SAF can't be navigated inside our app).
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
	 * Read + validate a persisted folder path from the shared preferences. Returns the resolved File only when the
	 * persisted path still exists AND is a directory — so a folder the user deleted between sessions doesn't strand
	 * the picker at a non-existent path.
	 *
	 * @param prefs SharedPreferences instance to read from
	 * @param key   preference key holding the absolute folder path
	 * @return the File at the persisted path when it exists and is a directory; empty otherwise (no
	 *         pref, deleted folder, or non-directory path)
	 */
	private static Optional<File> readFolder(SharedPreferences prefs, String key)
	{
		String path = prefs.getString(key, null);
		if (path == null)
		{
			return Optional.empty();
		}
		File folder = new File(path);
		return (folder.exists() && folder.isDirectory()) ? Optional.of(folder) : Optional.empty();
	}

	/**
	 * Resolve the journal-entry absolute path for a kept recovery or an in-flight temp: the caller-resolved
	 * folder when present, else the persisted last-save folder — written by the save flow when it came through
	 * the in-app path, the best remaining anchor on opaque providers. Shared by both journals' add / remove
	 * pairs (journalKeptRecovery / unjournalKeptRecovery, journalInflightTemp / unjournalInflightTemp) so a
	 * pair can never target different spellings of the same entry.
	 *
	 * @param prefs      the save-flow SharedPreferences holding the persisted last-save folder
	 * @param folder     folder the journaled file sits in, or null when the caller couldn't resolve one
	 * @param name       the hidden temp filename being journaled / unjournaled
	 * @return absolute path of the journal entry, or null when no folder is resolvable
	 */
	private static String resolveJournalPath(SharedPreferences prefs, File folder, String name)
	{
		File resolved = folder != null ? folder : readFolder(prefs, KEY_LAST_SAVE_FOLDER).orElse(null);
		return resolved != null ? new File(resolved, name).getAbsolutePath() : null;
	}

	/**
	 * Shared cleanup core for every bg-hop continuation abort: delete the just-created sibling placeholder when
	 * one exists — the no-orphan-on-abort invariant, a fresh placeholder must not survive an aborted save — and
	 * drop its in-flight temp journal entry with it, then reuse onSaveCancelled's abort chokepoint (savePending
	 * + pendingSaveName clear, restorePriorSaveSettings). The path resolution runs BEFORE the delete:
	 * fileFromSafUri opens the URI, which fails once the document is gone. Deliberately toast-free — the
	 * destroyed-host abort (runPostedSaveContinuation) has no live UI to inform; abortStaleSave layers its
	 * user-facing toast on top for the live-host stale-source case.
	 *
	 * @param placeholder hidden sibling placeholder created by probeAndPlaceholderOnBg, or null when the
	 *                    aborting continuation runs before placeholder creation
	 */
	private void abortSaveContinuation(Uri placeholder)
	{
		if (placeholder != null)
		{
			File resolved = safFiles.fileFromSafUri(placeholder).orElse(null);
			safFiles.tryDeleteSafDocument(placeholder);
			if (resolved != null && SaveTempFiles.isTempArtifact(resolved.getName()))
			{
				unjournalInflightTemp(host.getActivity(), resolved.getParentFile(), resolved.getName());
			}
		}
		onSaveCancelled();
	}

	/**
	 * Abort action for a bg-hop save continuation whose source image was replaced mid-hop (see
	 * abortIfSourceChanged): the shared abortSaveContinuation cleanup (placeholder delete + journal-entry drop,
	 * savePending + pendingSaveName clear, restorePriorSaveSettings — which skips the rollback when the live
	 * image changed, precisely this case, but still clears the snapshot so the stale Bitmap reference doesn't
	 * stay pinned) plus a toast telling the user why nothing was saved.
	 *
	 * @param placeholder hidden sibling placeholder created by probeAndPlaceholderOnBg, or null when the aborting
	 *                    continuation runs before placeholder creation
	 */
	private void abortStaleSave(Uri placeholder)
	{
		abortSaveContinuation(placeholder);
		host.toastIfAlive("Save cancelled — image changed", Toast.LENGTH_SHORT);
	}

	/**
	 * Pick encoder based on the extension the user typed in the SAF picker.
	 *
	 * @param name SAF-returned filename whose extension drives the format choice; null and unrecognised
	 *             extensions are no-ops (state.exportConfig.format() retains whatever the dialog set)
	 */
	private void applyFormatFromFilename(String name)
	{
		// Unknown / absent extension leaves the format unchanged (source default) — fromExtension is empty.
		Format.fromExtension(name).ifPresent(derived ->
			host.getState().updateExportConfig(c -> c.withFormat(derived)));
	}

	/**
	 * Build showInAppRenameDialog's filename-input dialog: pre-filled EditText plus a positive button whose real
	 * validation handler is installed on show.
	 *
	 * @param folder   folder the picker landed in; forwarded to the OK-click validator
	 * @param original colliding filename — used as the suggestion-stem and as the overflow fallback
	 * @return the ready-to-show dialog
	 */
	private AlertDialog buildInAppRenameDialog(File folder, String original)
	{
		EditText input = new EditText(host.getActivity());
		// Pre-fill with the next available "(N)" suffix per Samsung / Android Files-app convention.
		// Strips an existing "(N)" suffix first so renaming "foo (1).jpg" suggests "foo (2).jpg" rather
		// than "foo (1) (1).jpg". selectAll lets the user replace the suggestion in one tap. Falls back
		// to `original` on the unlikely N=1..9999-all-taken overflow so the input still shows something
		// the user can edit, rather than a blank field.
		input.setText(nextAvailableNumberedName(folder, original).orElse(original));
		input.selectAll();
		// Install the positive button with a null handler so AlertDialog doesn't auto-dismiss after the
		// click — the OnShowListener override below validates and dismisses only on a valid name,
		// mirroring FolderPickerDialog.show(). Without this, a correctable typo (empty, traversal,
		// separator) would auto-close the dialog and abort the whole save flow.
		AlertDialog renameDialog = new AlertDialog.Builder(host.getActivity())
			.setTitle("Save as")
			.setView(input)
			.setPositiveButton(DialogStrings.OK, null)
			.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> onSaveCancelled())
			.setOnCancelListener(dialog -> onSaveCancelled())
			.create();
		renameDialog.setOnShowListener(shown ->
			renameDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				onRenameOkClicked(renameDialog, input, folder)));
		return renameDialog;
	}

	/**
	 * Cancel-time hook for SaveDialog: clear priorSnapshot when the dialog dismisses without Continue (user Cancel,
	 * back-press, outside-touch, or forced dismissTransientDialogs from a parallel load). The snapshot's
	 * sourceImage field holds a strong reference to the source Bitmap; without this clear, an abandoned dialog
	 * would pin the bitmap until the next openSaveOptionsDialog overwrites the field or the Activity tears down.
	 * The Continue path deliberately does NOT call this — handleSaveAsResultBody / onSaveCancelled / the
	 * launcher-exception path still own the snapshot rollback contract for a save that's reached SAF and then
	 * abandoned.
	 */
	private void clearPriorSnapshotOnCancel()
	{
		priorSnapshot = null;
	}

	/**
	 * UI-thread continuation of routeCrashSafeSave, posted from the bg probe hop — so it runs through
	 * runPostedSaveContinuation: on a destroyed host it aborts with the shared cleanup (placeholder delete,
	 * savePending clear, settings rollback, no toast) instead of dispatching against the shut-down executor,
	 * and a dispatch-guard rethrow (cleanup already run inside ExportPipeline.exportTo) is absorbed rather
	 * than escaping the posted runnable. dispatchCrashSafeWriteBody carries the live-host dispatch.
	 *
	 * @param newUri       SAF document URI the save targets
	 * @param name         user's intended filename (drives toast wording + placeholder promotion)
	 * @param wasOverwrite prior-content classification: the caller's confirmed-Replace fact OR the bg-probed
	 *                     probeWasOverwrite result
	 * @param placeholder  hidden sibling placeholder document, or null when the provider can't create one
	 * @param initiatedFor source bitmap captured at save initiation; the save aborts — deleting placeholder —
	 *                     when it is no longer the live CropState source
	 */
	private void dispatchCrashSafeWrite(Uri newUri, String name, boolean wasOverwrite, Uri placeholder,
		Bitmap initiatedFor)
	{
		runPostedSaveContinuation(host, "dispatchCrashSafeWrite", () -> abortSaveContinuation(placeholder),
			() -> dispatchCrashSafeWriteBody(newUri, name, wasOverwrite, placeholder, initiatedFor));
	}

	/**
	 * Live-host body of dispatchCrashSafeWrite: verify the save is still bound to the image it was initiated
	 * for, then dispatch the classification to the verified write. The identity guard runs FIRST — the
	 * export reads the LIVE CropState source bitmap, so a Share/View load completing during the bg hop would
	 * otherwise send the new image to the old image's destination with no confirmation; the abort deletes the
	 * placeholder the hop just created (no-orphan-on-abort). Placeholder created → crash-safe write-then-swap via
	 * ReplaceStrategy. No placeholder (opaque-ID provider) → direct overwrite with preserve-on-failure when the
	 * probe confirmed content, else the preserving write (never destroy a file the user might own). savePending
	 * clears in the finally — the flag is held across the bg probe hop to gate parallel Save taps, and must
	 * release even when a dispatch path throws (ExportPipeline's pre-enqueue guard rethrows after its own
	 * cleanup; the wrapping runPostedSaveContinuation absorbs the rethrow).
	 *
	 * @param newUri       SAF document URI the save targets
	 * @param name         user's intended filename (drives toast wording + placeholder promotion)
	 * @param wasOverwrite prior-content classification (confirmed-Replace OR probe)
	 * @param placeholder  hidden sibling placeholder document, or null when the provider can't create one
	 * @param initiatedFor source bitmap captured at save initiation; the save aborts — deleting placeholder —
	 *                     when it is no longer the live CropState source
	 */
	private void dispatchCrashSafeWriteBody(Uri newUri, String name, boolean wasOverwrite, Uri placeholder,
		Bitmap initiatedFor)
	{
		if (abortIfSourceChanged(initiatedFor, host.getState().getSourceImage(),
			() -> abortStaleSave(placeholder)))
		{
			return;
		}
		try
		{
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
				// Opaque-ID + ambiguous: can't confirm existing content, can't placeholder. Preserve on
				// failure so we don't destroy a file the user might own.
				exportPipeline.exportToPreserving(newUri);
			}
		}
		finally
		{
			savePending = false;
		}
	}

	/**
	 * Dispatch the actual write for an in-app folder picker save. Routes through routeCrashSafeSave so the
	 * sibling-placeholder + atomic-rename crash-safe write path runs uniformly for both new-file and overwrite
	 * cases. probeWasOverwrite inside routeCrashSafeSave re-checks file existence — the in-app Rename/Replace
	 * dialog only filters whether we got here, not what routeCrashSafeSave does once we arrive — but a
	 * dialog-confirmed Replace is threaded through as a fact the probe can only widen, never downgrade: the
	 * collision dialog fires on bare target.isFile(), so its target may be a 0-byte file the probe's
	 * explicit-0 rule classifies as NOT-overwrite, and without the confirmation the NEW-FILE promotion guard
	 * would then refuse the very Replace the user confirmed. savePending stays set across
	 * routeCrashSafeSave's bg probe hop (its continuation clears it) so a parallel Save tap can't start a
	 * second flow while the dispatch is in flight.
	 *
	 * @param folder           folder the user picked
	 * @param name             filename to save as (post-Rename if applicable)
	 * @param confirmedReplace true only when the user explicitly confirmed Replace in the in-app collision
	 *                         dialog — the write then keeps replace semantics regardless of what the probe
	 *                         reads at write time; false for collision-free dispatches
	 */
	private void dispatchInAppSave(File folder, String name, boolean confirmedReplace)
	{
		File target = new File(folder, name);
		Uri uri = SafFileHelper.buildExternalStorageDocumentUri(target).orElse(null);
		if (uri == null)
		{
			Log.w(TAG, "buildExternalStorageDocumentUri returned empty for " + target);
			host.toastIfAlive("Picked folder isn't on primary storage", Toast.LENGTH_LONG);
			onSaveCancelled();
			return;
		}
		// pendingSaveName is the LEGACY-SAF-path's collision-detection signal (handleSaveAsResultBody reads it
		// to spot SAF auto-renames); the in-app path doesn't consult it. The live source image is captured
		// here — the last UI-thread point before routeCrashSafeSave's bg hop — as the identity the save is
		// bound to (see abortIfSourceChanged).
		routeCrashSafeSave(uri, name, host.getState().getSourceImage(), confirmedReplace);
	}

	/**
	 * Body of handleSaveAsResult — extracted so the public wrapper can manage the savePending lifecycle in
	 * try/finally. See the wrapper's comment for the ownership contract.
	 *
	 * @param newUri SAF document URI returned by the picker
	 * @return true when a downstream owner took over savePending and the wrapper must NOT clear it — the
	 *         Case B Replace dialog, or the Case A / C bg overwrite probe whose continuation either hands
	 *         ownership to the overwrite-confirm dialog or clears the flag once the write dispatches; false
	 *         on the display-name-rejection and extension-mismatch early returns, which the wrapper's
	 *         finally cleans up
	 */
	private boolean handleSaveAsResultBody(Uri newUri)
	{
		String requested = pendingSaveName;
		pendingSaveName = null;
		// Do NOT clear `priorSnapshot` here. The save isn't actually committed yet — the user can still cancel
		// from the Replace / Overwrite-Confirm dialog (Cases A, B, C). On those Cancel paths we want to restore
		// the prior format / grid-include choices so a user who decided "no, don't overwrite" doesn't end up
		// with the next image stuck on the PNG-with-grid settings they picked for the abandoned save. The
		// snapshot stays until either the save actually starts (routeCrashSafeSave / replaceColliding) or the
		// dialog's Cancel handler restores it.

		String chosen = safFiles.getDisplayName(newUri).orElse(null);

		// Display-name guard: reject provider-returned names the app's own validation would refuse BEFORE
		// `chosen` reaches any downstream sink (see rejectsProviderDisplayName). The returned document is
		// left undeleted for the same reason as the extension-mismatch guard below: the provider's own
		// Replace prompt can return a real pre-existing user file, and deleting on rejection could destroy
		// it. The wrapper's finally clears savePending; the settings rollback keeps the abandoned save's
		// format / grid choices out of the next save's defaults.
		if (rejectsProviderDisplayName(chosen))
		{
			restorePriorSaveSettings();
			host.toastIfAlive(DialogStrings.INVALID_FILENAME, Toast.LENGTH_SHORT);
			return false;
		}

		// Extension-change guard: SAF set the document's MIME from `requested` before the picker opened. If the
		// user renamed ".jpg" → ".png" (or vice versa) — or to anything else like ".webp" / ".heic" — writing
		// the new format's bytes would land them in a document whose MIME still says the old type AND a
		// filename whose extension promises an encoding the encoder can't actually produce. Reject the save
		// and redirect the user to the Save dialog's format picker. See extensionMismatch for the reject
		// predicate's exact truth table.
		if (extensionMismatch(requested, chosen))
		{
			// Do NOT delete newUri on this rejection path. The same-name save logic below correctly treats
			// priorSize <= 0 as ambiguous — a zero-byte URI can be either a fresh SAF placeholder OR an
			// already-existing empty file the provider returned after its own Replace prompt. We can't tell
			// from SAF alone, and losing a real zero-byte file (unusual but valid) to a rejection cleanup
			// is strictly worse than leaving a disposable fresh placeholder behind. The dialog tells the
			// user to fix the format in the Save dialog; a leftover placeholder is a minor file-manager
			// annoyance, not data loss. The wrapper's finally clears savePending. Roll back the SaveDialog
			// choices (format / Export Grid) before returning — the user abandoned this save by mismatching
			// extensions, so the format / grid they picked is uncommitted intent. Without this, the
			// rejected format leaks into the next save's defaults AND priorSnapshot.sourceImage stays
			// pinned in memory until a later save/load clears it.
			restorePriorSaveSettings();
			showExtensionMismatchDialog(requested, chosen);
			return false;
		}

		applyFormatFromFilename(chosen);

		// Case (A): SAF accepted the requested name exactly. Either the file didn't exist (new file) or SAF
		// prompted "Replace?" and the user accepted OR (on providers that don't show their own prompt —
		// Samsung's recent Files app behavior) the picker is silently returning a URI to pre-existing content.
		// The user has no in-app confirmation that they're about to overwrite, which is data-loss-adjacent on a
		// save flow. Probe for prior content on the bg executor (synchronous provider IO — a slow cloud
		// provider would freeze this result handler); the UI continuation surfaces the Replace / Cancel dialog
		// on a confirmed overwrite so the user explicitly confirms, and dispatches the crash-safe write
		// otherwise.
		if (requested != null && chosen != null && requested.equalsIgnoreCase(chosen))
		{
			Log.d(TAG, "Save Case A: chosen=" + chosen + " requested=" + requested);
			probeOverwriteThenDispatch(newUri, requested);
			return true;
		}

		// Case (B): chosen has a "X (N).ext" auto-rename pattern. Detection works on `chosen` alone so it
		// catches the case where the user edited the filename in the picker and THAT name collided — the "X" in
		// "X (N)" doesn't have to match the original pendingSaveName. Verify the inferred base still lives in
		// the same directory AND has real content before showing the Replace dialog so a user who intentionally
		// typed "foo (1).jpg" without a real collision doesn't get offered Replace on a phantom, and so a
		// 0-byte placeholder left by an interrupted prior save isn't treated as content to preserve. See
		// siblingLooksLikeCollision for the existence + size probe order; -1 / unknown size still counts as
		// collision on providers that don't expose OpenableColumns.SIZE.
		String autoRenameBase = autoRenameBaseName(chosen).orElse(null);
		Log.d(TAG, "Save Case B probe: chosen=" + chosen + " requested=" + requested
			+ " autoRenameBase=" + autoRenameBase);
		if (autoRenameBase != null)
		{
			// Sibling-collision probe. On providers where the docId encodes a path (Samsung primary
			// storage's ExternalStorageProvider), `deriveSiblingUri` builds the un-suffixed sibling URI and
			// `siblingLooksLikeCollision` confirms the original exists. On providers with opaque doc IDs
			// (MediaStore-backed pickers — the user's recent Samsung Files behavior where docId is
			// "document:12345" with no parseable path), `deriveSiblingUri` returns empty and the probe
			// can't run. In that case, fall back to trusting the auto-rename pattern alone — SAF only
			// suffixes "(N)" on a real collision, so the false-positive risk (user intentionally typed
			// "foo (3).jpg" without an existing "foo.jpg") is strictly less bad than the false-negative
			// (silent overwrite without confirmation).
			Uri baseUri = safFiles.deriveSiblingUri(newUri, autoRenameBase).orElse(null);
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
		// at existing content with `chosen` equal to the user-typed (colliding) name. Same bg overwrite-probe
		// gate as Case A — on a confirmed overwrite the continuation shows the Replace / Cancel dialog before
		// any destructive write. `requested` is the fallback name when chosen is null (rare opaque-name
		// provider — those typically also refuse SIZE and streams, so the probe resolves false and the fallback
		// preserving write is the safe choice).
		String saveName = chosen != null ? chosen : requested;
		Log.d(TAG, "Save Case C: saveName=" + saveName);
		probeOverwriteThenDispatch(newUri, saveName);
		return true;
	}

	/**
	 * Result handler for the merged in-app FolderPickerDialog (format + grid options + folder + thumbnails). On
	 * Save here (choices non-null), applies the user's format / grid selections to CropState, computes the filename
	 * from state.originalFilename + format extension, persists the picked folder as the next launch's starting
	 * location, sets pendingSaveName + savePending, then dispatches to either the write path or the
	 * Rename/Replace/Cancel collision dialog depending on existing-file presence. The merged dialog commits state
	 * once at confirm (the picker doesn't mutate CropState mid-show), so a priorSnapshot is captured BEFORE
	 * updateExportConfig / updateGridConfig fire — if the user cancels at the in-app collision / rename dialog,
	 * onSaveCancelled → restorePriorSaveSettings rolls the format / grid-include selections back. On null choices
	 * (Cancel / external dismiss from the picker itself) nothing was mutated, so we just return.
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
		// Re-entrancy guard: if a previous save is still in flight (savePending=true), a second confirm-tap or
		// programmatic re-fire would otherwise overwrite priorSnapshot with the already-mutated state from the
		// first call, breaking the rollback contract. The user-visible symptom would be: rapid Save here taps →
		// user cancels at the collision dialog → format/grid don't roll back to the truly-original values
		// because the snapshot now points at the first call's already-applied format/grid. Drop the re-entry
		// silently; the first save's lifecycle still owns savePending.
		if (savePending)
		{
			Log.d(TAG, "onMergedSaveConfirmed: re-entry while savePending=true, dropping");
			return;
		}
		// The picker validates the filename for path separators and empty/traversal segments before firing this
		// callback, but defensively sanitise here too — a buggy/malicious test fake of the picker shouldn't be
		// able to direct the save outside the picked folder via a crafted `choices.filename()`. Validate BEFORE
		// the state mutations below so a bad name doesn't commit format / grid changes the user never had a
		// chance to confirm.
		String name = choices.filename();
		if (name == null || !FolderPickerDialog.isValidFilename(name))
		{
			host.toastIfAlive(DialogStrings.INVALID_FILENAME, Toast.LENGTH_SHORT);
			return;
		}
		// Wrap the entire state-mutating section in try/catch so a thrown RuntimeException anywhere —
		// updateExportConfig / updateGridConfig (StateBus listener throwing),
		// saveLastSaveFolder (SharedPreferences disk-full), File.exists (rare SecurityException), or the dialog
		// constructors (BadTokenException from a config-change race) — can't strand savePending=true forever or
		// leave format/grid half-mutated. Snapshot capture is INSIDE the try so a snapshot followed by a thrown
		// updateXxx still routes through onSaveCancelled → restorePriorSaveSettings for a clean rollback.
		try
		{
			// Snapshot the export/grid state BEFORE mutating so an in-app collision-dialog Cancel can roll
			// back. Without this, a user who picks PNG + Export Grid in the merged dialog, taps Save here
			// on a colliding name, then taps Cancel in the collision dialog, would find PNG + Export Grid
			// stuck as the next default — silently changing the next save's encoding based on a save they
			// explicitly abandoned. routeCrashSafeSave clears the snapshot once the write actually commits.
			priorSnapshot = new PriorSaveSnapshot(host.getState().getSourceImage(),
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
				// A directory at the requested path is "name unavailable" — the Replace flow downstream
				// would route through ReplaceStrategy's delete-then-rename fallback, which on
				// permissive SAF providers could actually delete the user's folder. Reject here BEFORE
				// the collision dialog so Replace is never offered against a directory target.
				// File.isFile() below excludes directories symmetrically so the
				// otherwise-existence-driven collision path can only fire on regular files.
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
				dispatchInAppSave(choices.folder(), name, false);
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
	 * UI-thread continuation of probeOverwriteThenDispatch, posted from the bg probe hop — so it runs through
	 * runPostedSaveContinuation: on a destroyed host it aborts with the shared cleanup (savePending clear,
	 * settings rollback; no placeholder exists yet, no toast) instead of dispatching against the shut-down
	 * executor, and routeCrashSafeSave's dispatch-guard rethrow (savePending already cleared) is absorbed
	 * rather than escaping the posted runnable. onOverwriteProbedBody carries the live-host routing.
	 *
	 * @param newUri       SAF document URI returned by the picker
	 * @param name         user's intended filename
	 * @param wasOverwrite bg-probed classification from probeWasOverwrite
	 * @param initiatedFor source bitmap captured at save initiation; the save aborts (no placeholder exists
	 *                     yet on this continuation) when it is no longer the live CropState source
	 */
	private void onOverwriteProbed(Uri newUri, String name, boolean wasOverwrite, Bitmap initiatedFor)
	{
		runPostedSaveContinuation(host, "onOverwriteProbed", () -> abortSaveContinuation(null),
			() -> onOverwriteProbedBody(newUri, name, wasOverwrite, initiatedFor));
	}

	/**
	 * Live-host body of onOverwriteProbed: verify the save is still bound to the image it was initiated for,
	 * then route. The identity guard runs FIRST — a Share/View load completing during the bg probe replaces
	 * the source, and this continuation's dialog was not yet registered when that load dismissed transient
	 * dialogs, so without the guard the dialog would still post (and the no-dialog branch would export the
	 * new image to the old image's destination). A confirmed overwrite must be user-confirmed before any
	 * destructive dispatch, so it routes to the Replace / Cancel dialog (which takes over the savePending
	 * lifecycle); anything else routes straight to the crash-safe write — nothing confirmed here, so the
	 * write-time classification is the probe's alone — whose continuation clears savePending once the write
	 * dispatches.
	 *
	 * @param newUri       SAF document URI returned by the picker
	 * @param name         user's intended filename
	 * @param wasOverwrite bg-probed classification from probeWasOverwrite
	 * @param initiatedFor source bitmap captured at save initiation; the save aborts (no placeholder exists
	 *                     yet on this continuation) when it is no longer the live CropState source
	 */
	private void onOverwriteProbedBody(Uri newUri, String name, boolean wasOverwrite, Bitmap initiatedFor)
	{
		if (abortIfSourceChanged(initiatedFor, host.getState().getSourceImage(), () -> abortStaleSave(null)))
		{
			return;
		}
		if (wasOverwrite)
		{
			showOverwriteConfirmDialog(newUri, name, initiatedFor);
		}
		else
		{
			routeCrashSafeSave(newUri, name, initiatedFor, false);
		}
	}

	/**
	 * Replace-button handler for showOverwriteConfirmDialog: dispatch the confirmed overwrite through the
	 * crash-safe write path. The identity recheck is defense in depth — the dialog is transient-registered, so a
	 * Share/View load normally dismisses it before the image swaps, but the dismissal and the button tap can
	 * race; rechecking the initiation binding here closes that window before any dispatch. savePending stays true
	 * across routeCrashSafeSave's bg probe hop (its continuation clears it) so a parallel Save tap can't start a
	 * second flow while the dispatch is in flight; the busy rejection mirrors onReplaceConfirmed's. The user just
	 * confirmed Replace, so the confirmation is threaded into the write-time classification — the re-probe can
	 * widen it but never downgrade a confirmed Replace to a new-file save.
	 *
	 * @param newUri       SAF document URI pointing at the pre-existing content the user confirmed replacing
	 * @param name         user's intended filename (drives downstream toast wording)
	 * @param initiatedFor source bitmap captured at save initiation; the save aborts when it is no longer the
	 *                     live CropState source
	 */
	private void onOverwriteReplaceConfirmed(Uri newUri, String name, Bitmap initiatedFor)
	{
		if (abortIfSourceChanged(initiatedFor, host.getState().getSourceImage(), () -> abortStaleSave(null)))
		{
			return;
		}
		if (host.getBusy().get())
		{
			savePending = false;
			host.toastIfAlive("Replace failed — try again", Toast.LENGTH_SHORT);
			return;
		}
		routeCrashSafeSave(newUri, name, initiatedFor, true);
	}

	/**
	 * OK-button handler for showInAppRenameDialog. Validates the typed name (rejecting empty, traversal segments,
	 * and path separators with an "Invalid filename" toast that keeps the dialog open for correction), normalises
	 * the extension against the selected format so gallery / file-manager type inference doesn't lie, then
	 * dispatches to either the save path or the recursive collision dialog. A named method rather than inline in
	 * showInAppRenameDialog's OnShowListener so the lambda body stays under the 3-line cap.
	 *
	 * @param dialog the Rename dialog itself; dismissed only on the valid-name path
	 * @param input  the user-facing EditText whose current text drives validation and dispatch
	 * @param folder folder the picker landed in; the rename target is constructed as new File(folder,
	 *               normalisedName)
	 */
	private void onRenameOkClicked(AlertDialog dialog, EditText input, File folder)
	{
		String typed = input.getText().toString().trim();
		// Reject empty, traversal segments, and any path separator — must be a SINGLE filename in the folder
		// the user just picked. Without this, "subdir/foo.jpg" or "../foo.jpg" would target a different folder
		// than the picker landed in (or get rejected later by SafFileHelper.fileFromSafUri's docId traversal
		// guard, producing a confusing failure). Mirrors the sanitisation onMergedSaveConfirmed applies to the
		// provider-supplied stem via the same shared predicate.
		if (!FolderPickerDialog.isValidFilename(typed))
		{
			host.toastIfAlive(DialogStrings.INVALID_FILENAME, Toast.LENGTH_SHORT);
			return;
		}
		// Normalise the extension to match the selected format — same rule the merged dialog's positive button
		// applies. Without this, a user who renames "vacation.jpg" to "vacation.heic" in the in-app Rename
		// dialog saves JPEG bytes under .heic, and gallery / file-manager type inference would misclassify the
		// file.
		String normalised = FolderPickerDialog.normaliseExtension(typed,
			host.getState().getExportConfig().format());
		File target = new File(folder, normalised);
		if (target.isDirectory())
		{
			// Same rationale as onMergedSaveConfirmed — never route directory targets into the Replace
			// flow, which on permissive SAF providers could delete the folder. Toast and leave the rename
			// dialog open (don't dismiss) so the user can correct the name.
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
			dispatchInAppSave(folder, normalised, false);
		}
	}

	/**
	 * Handle the Replace dialog's positive "Replace" button: full Replace semantics (write-then-swap + "Replaced"
	 * toast). Busy-rejection cleanup: when a parallel bg op (e.g. a Share/View intent's load that arrived while the
	 * user deliberated) holds busy, replaceColliding's downstream busy gate would silently reject and leave the SAF
	 * auto-rename target on disk. Run cleanupPlaceholder here so the orphan doesn't linger.
	 *
	 * @param newUri             SAF auto-rename target URI ("foo (1).jpg")
	 * @param requested          user-typed name that collided (drives downstream toast wording)
	 * @param cleanupPlaceholder runs safFiles.tryDeleteSafDocument(newUri) on busy-rejection
	 */
	private void onReplaceConfirmed(Uri newUri, String requested, Runnable cleanupPlaceholder)
	{
		// Case B: user explicitly confirmed Replace on a real collision. wasOverwrite=true drives the success
		// toast wording ("Replaced X" vs "Saved X") downstream.
		onReplaceDecision(() -> replaceStrategy.replaceColliding(newUri, requested, true),
			"Replace failed — try again", cleanupPlaceholder);
	}

	/**
	 * Shared Replace-dialog decision preamble: clear the pending-save flag and prior snapshot, busy-gate (cleaning
	 * up the SAF auto-rename placeholder and toasting on rejection), then run the button's dispatch. Single
	 * chokepoint so the Replace and Keep buttons can't drift in their busy-rejection cleanup contract.
	 *
	 * @param dispatch           the button's save/replace action, run only when not busy
	 * @param failToast          toast text shown when the busy gate rejects
	 * @param cleanupPlaceholder runs safFiles.tryDeleteSafDocument(newUri) on busy-rejection
	 */
	private void onReplaceDecision(Runnable dispatch, String failToast, Runnable cleanupPlaceholder)
	{
		savePending = false;
		priorSnapshot = null;
		if (host.getBusy().get())
		{
			cleanupPlaceholder.run();
			host.toastIfAlive(failToast, Toast.LENGTH_SHORT);
			return;
		}
		dispatch.run();
	}

	/**
	 * Handle the Replace dialog's neutral "Keep" button: commit the SAF auto-rename URI as the actual save (no
	 * overwrite of the colliding original). Same busy-rejection cleanup contract as onReplaceConfirmed — without
	 * it, exportTo's busy gate silently rejects and leaves the auto-rename SAF document stranded.
	 *
	 * @param newUri             SAF auto-rename target URI (already has the SAF-assigned name)
	 * @param cleanupPlaceholder runs safFiles.tryDeleteSafDocument(newUri) on busy-rejection
	 */
	private void onReplaceKeep(Uri newUri, Runnable cleanupPlaceholder)
	{
		onReplaceDecision(() -> exportPipeline.exportTo(newUri), "Save failed — try again", cleanupPlaceholder);
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
		// Extension follows the format the user just picked in SaveDialog. The SAF result path validates the
		// picker-edited name against the requested format: matching (or extensionless) names continue and
		// applyFormatFromFilename re-derives the format; mismatched known/unknown extensions are rejected
		// up-front via showExtensionMismatchDialog to avoid MIME/type disagreements between the SAF document
		// and the encoder's output bytes.
		String name = defaultSaveStem(host.getState().getOriginalFilename())
			+ host.getState().getExportConfig().format().extension();
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
	 * isDestroyed pre-check is the first line of defense against the config-change race; the try/catch around .show
	 * is the second (the race window between the check and the actual show is still open).
	 */
	private void openSaveOptionsDialog()
	{
		// BadTokenException guard — if onDestroy ran between the user's Save tap and this call (config change
		// racing the handler), AlertDialog.Builder throws on .show(). isDestroyed is the first line of defense;
		// the try/catch is the second (the race window between them is still open).
		if (host.isDestroyed())
		{
			return;
		}
		// Snapshot the current export-format and grid-include selections BEFORE the dialog opens.
		// SaveDialog.applySettings mutates state directly on its Continue tap; if the user then cancels the SAF
		// picker, restorePriorSaveSettings unwinds the mutation so the cancelled choices don't bleed into the
		// next save attempt.
		priorSnapshot = new PriorSaveSnapshot(host.getState().getSourceImage(),
			host.getState().getExportConfig().format(), host.getState().getGridConfig().includeInExport());
		try
		{
			// Register the dialog with the host's transient-dialog tracker so a Share/View intent or graft
			// apply that arrives mid-dialog dismisses it before bg state.reset() — without this, the user's
			// Continue tap would commit image A's format/grid choices onto image B's state.
			// clearPriorSnapshotOnCancel runs on every cancel path (user Cancel, back-press, outside-touch,
			// forced dismissTransientDialogs) so the snapshot — which holds the source Bitmap reference —
			// doesn't pin memory after an abandoned dialog.
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
	 * Bg-executor body of routeCrashSafeSave: run the 3-probe overwrite classification, journal the
	 * placeholder's would-be path, and create the hidden sibling placeholder (probe and create are synchronous
	 * provider IO — the reason this runs off the UI thread), then post dispatchCrashSafeWrite to the UI thread
	 * with the results. The write-time classification is confirmed-OR-probe: a Replace the user explicitly
	 * confirmed keeps replace semantics even when the probe reads the colliding target as empty (a 0-byte
	 * file's explicit-0 downgrade would otherwise reclassify the save NEW-FILE and the promotion guard would
	 * refuse the confirmed Replace forever). The journal entry lands BEFORE the createDocument call
	 * (journalInflightTemp, synchronous commit) so a hard kill at any later point leaves a sweepable entry in
	 * whatever folder the placeholder sits; a failed journal never aborts the save, and a failed placeholder
	 * creation drops the entry again immediately (the degenerate disposal — nothing was created).
	 *
	 * @param newUri           SAF document URI the save targets
	 * @param name             user's intended filename
	 * @param mime             MIME type for the placeholder createDocument call
	 * @param placeholderName  hidden SaveTempFiles sibling name for the placeholder
	 * @param confirmedReplace true when a collision / overwrite dialog's Replace button committed this save —
	 *                         ORed with the probe so the confirmation can never be downgraded
	 * @param initiatedFor     source bitmap captured at save initiation, threaded through to the
	 *                         continuation's identity guard
	 */
	@WorkerThread
	private void probeAndPlaceholderOnBg(Uri newUri, String name, String mime, String placeholderName,
		boolean confirmedReplace, Bitmap initiatedFor)
	{
		boolean wasOverwrite = confirmedReplace || probeWasOverwrite(newUri);
		File targetFolder = safFiles.fileFromSafUri(newUri).map(File::getParentFile).orElse(null);
		journalInflightTemp(host.getActivity(), targetFolder, placeholderName);
		Uri placeholder = safFiles.createSiblingPlaceholder(newUri, mime, placeholderName).orElse(null);
		if (placeholder == null)
		{
			unjournalInflightTemp(host.getActivity(), targetFolder, placeholderName);
		}
		host.runOnUiThread(() -> dispatchCrashSafeWrite(newUri, name, wasOverwrite, placeholder, initiatedFor));
	}

	/**
	 * Run probeWasOverwrite on the bg executor, then continue on the UI thread via onOverwriteProbed — confirmed
	 * overwrite → overwrite-confirm dialog, otherwise → crash-safe write dispatch. The probe's resolver SIZE query,
	 * content-stream open, and File.length are synchronous provider IO that would freeze the UI thread inside the
	 * CREATE_DOCUMENT result handler on a slow cloud provider. savePending stays true across the hop (the Case A /
	 * C caller returns ownership-taken to handleSaveAsResult's wrapper) so rapid Save taps stay gated while the
	 * probe runs. The source image is captured here, before the hop, and threaded through every continuation —
	 * a Share/View load completing while the probe is on the executor replaces the source, and the continuation
	 * must abort rather than dialog / dispatch against the wrong image (see abortIfSourceChanged).
	 *
	 * @param newUri SAF document URI returned by the picker
	 * @param name   user's intended filename for dialog wording and the downstream write
	 */
	private void probeOverwriteThenDispatch(Uri newUri, String name)
	{
		Bitmap initiatedFor = host.getState().getSourceImage();
		host.runInBackground(() ->
		{
			boolean wasOverwrite = probeWasOverwrite(newUri);
			host.runOnUiThread(() -> onOverwriteProbed(newUri, name, wasOverwrite, initiatedFor));
		});
	}

	/**
	 * Probe whether the SAF-returned URI already points at pre-existing content. Three independent probes;
	 * ANY positive signal confirms the overwrite (classifyOverwrite is the pure decision core):
	 *   priorSize > 0                                  → confirmed (provider-reported size)
	 *   priorSize == -1 (no SIZE) + stream serves a byte → confirmed (content-stream fallback)
	 *   path-resolved File exists with length > 0      → confirmed (filesystem-authoritative; catches
	 *                                                    providers exposing neither SIZE nor a readable
	 *                                                    stream, and overrides an explicit 0)
	 * A provider-reported 0 with no filesystem contradiction stays not-overwrite: the SAF picker often
	 * pre-creates a 0-byte placeholder at the chosen name before returning the URI, and counting it as
	 * overwrite would surface the Replace dialog on every new-file save. The diagnostic Log.d records the
	 * raw signals so a "dialog didn't fire" report can be correlated against provider behaviour.
	 *
	 * Synchronous provider IO — UI-thread callers must route through probeOverwriteThenDispatch or
	 * routeCrashSafeSave's bg hop rather than calling this directly. Both the Case A / C dialog gate and
	 * routeCrashSafeSave use this same predicate so dialog wording and write strategy can't disagree.
	 *
	 * @param newUri SAF document URI returned by the picker
	 * @return true when newUri points at content that an overwrite would destroy; false when newUri is
	 *         a fresh / empty / unverifiable placeholder
	 */
	private boolean probeWasOverwrite(Uri newUri)
	{
		long priorSize = safFiles.querySafFileSize(newUri);
		File asFile = safFiles.fileFromSafUri(newUri).orElse(null);
		boolean fileHasContent = asFile != null && asFile.exists() && asFile.length() > 0;
		boolean result = classifyOverwrite(priorSize, () -> safFiles.hasExistingContent(newUri),
			fileHasContent);
		Log.d(TAG, "probeWasOverwrite priorSize=" + priorSize + " fileHasContent=" + fileHasContent
			+ " → " + result);
		return result;
	}

	/**
	 * Roll back the export-format and grid-include selections committed by SaveDialog when the user cancels (or
	 * otherwise abandons) the save before SAF accepts a URI. Idempotent — called on every abort path (SAF cancel,
	 * launcher exception, post-dialog busy-toast); the priorSnapshot null-check makes a stale second invocation a
	 * no-op rather than overwriting state with stale values.
	 *
	 * Skips the rollback when the live source bitmap differs from the one captured in the snapshot. That means a
	 * Share/View intent arrived between openSaveOptionsDialog and now, replacing the loaded image — the snapshot's
	 * format / includeGrid belong to the previous image and would silently overwrite the new image's freshly-set
	 * format (PNG sources defaulting to JPEG on the next save, or the inverse). The snapshot is cleared either way
	 * so a subsequent abort path doesn't retry against the same stale data.
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
	 * Probe the SAF-returned URI for pre-existing content, create the crash-safe sibling placeholder, and dispatch
	 * to the appropriate save path. The URI may point to a fresh placeholder (brand-new save) OR to an existing
	 * document the provider returned after its own Replace prompt. The prior-content status is ambiguous from the
	 * URI alone, so the crash-safe write-then-swap pattern via a sibling placeholder is the conservative default.
	 * The wasOverwrite classification is confirmed-OR-probe: a dialog-confirmed Replace (confirmedReplace) is a
	 * fact the bg probe can only widen, never downgrade — probeWasOverwrite's explicit-0 rule reads a 0-byte
	 * colliding file as NOT-overwrite, and a downgrade would hand the confirmed Replace to the NEW-FILE promotion
	 * guard, which then refuses it deterministically. Unconfirmed dispatches classify by the probe alone — the
	 * same 3-probe predicate the Case A / C dialog gate ran, so the dialog-shown branch can't get the answer
	 * reclassified downstream: a narrower recompute would route a Samsung Downloads-provider URI (SIZE and stream
	 * both refused, `fileFromSafUri` resolves real content) through `exportToPreserving` and toast "Saved" on an
	 * overwrite the user just chose to Replace.
	 *
	 * The probe and the placeholder createDocument call are synchronous provider IO, so both run on the bg executor
	 * with dispatchCrashSafeWrite as the UI-thread continuation. No-orphan-on-abort guarantee: the busy pre-check
	 * runs BEFORE the placeholder exists (every busy acquirer is UI-thread, so the check can't race an acquisition
	 * from another thread), and any abort after creation — busy CAS lost to a load that started during the hop,
	 * missing source image, destroyed host at continuation time, encode failure, pre-enqueue dispatch throw —
	 * deletes the fresh placeholder inside ExportPipeline or the continuation's abort. The startup sweep remains a
	 * hard-kill backstop only. savePending is held across the hop and cleared by the continuation (or the abort
	 * branches here).
	 *
	 * Shared by Case A (exact-name match), Case C (user-renamed without "(N)" pattern), the overwrite-confirm
	 * dialog's Replace button, and the in-app dispatch.
	 *
	 * @param newUri           SAF document URI returned by the picker (or built for the in-app folder save)
	 * @param name             user's intended filename — Case A: original `requested`; Case C: `chosen`
	 * @param initiatedFor     source bitmap the save is bound to, captured on the UI thread at save
	 *                         initiation; dispatchCrashSafeWrite aborts (deleting the fresh placeholder) when
	 *                         a load replaces the source during the bg hop
	 * @param confirmedReplace true only when a collision / overwrite dialog's Replace button committed this
	 *                         save — the write keeps replace semantics regardless of the write-time probe
	 */
	private void routeCrashSafeSave(Uri newUri, String name, Bitmap initiatedFor, boolean confirmedReplace)
	{
		// Save is actually committing now. Discard the format / grid-include snapshot so a subsequent stray
		// onSaveCancelled (e.g., follow-up async callback after a config change) can't roll back the now-baked
		// choices. Mirrors the equivalent commit point on the Replace flow (onReplaceConfirmed / onReplaceKeep
		// both clear savePending before dispatching here).
		priorSnapshot = null;
		// Busy pre-check BEFORE the placeholder document exists — a busy-rejected export downstream would
		// otherwise strand the just-created placeholder in the user's folder.
		if (host.getBusy().get())
		{
			host.showBusyToast();
			savePending = false;
			return;
		}
		String mime = host.getState().getExportConfig().format().mimeType();
		// Hidden SaveTempFiles name keeps the in-flight placeholder out of Gallery; every ReplaceStrategy
		// branch that keeps it as the final save renames it to a visible name before claiming success, and the
		// startup sweep (sweepStaleSaveTemps) reclaims hard-kill orphans by the same name shape.
		String placeholderName = SaveTempFiles.tempName(name);
		try
		{
			host.runInBackground(() -> probeAndPlaceholderOnBg(newUri, name, mime, placeholderName,
				confirmedReplace, initiatedFor));
		}
		catch (RuntimeException e)
		{
			// Executor rejection (onDestroy shutdown racing the dispatch) — release the pending flag so
			// subsequent Save taps aren't stuck behind "Busy — try again", then propagate. Mirrors
			// ExportPipeline's pre-enqueue guard. No placeholder exists yet, so nothing to clean up.
			// The rethrow is for the genuinely SYNCHRONOUS callers, which have a live call stack to see
			// the real error: dispatchInAppSave (onMergedSaveConfirmed's guarded dispatch plus the
			// in-app collision / rename dialog buttons) and onOverwriteReplaceConfirmed (overwrite-
			// confirm dialog button). The one POSTED caller — onOverwriteProbed's continuation — must
			// never let it reach the main looper; runPostedSaveContinuation absorbs it there.
			savePending = false;
			throw e;
		}
	}

	/**
	 * Persist the folder the user just saved into as the next launch's starting location. Called from the
	 * FolderPickerDialog callback on Save Here, before the collision check + write dispatch — so even if the user
	 * cancels at the Rename/Replace/Cancel step, the folder they navigated to is remembered for next time.
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
	 *
	 * @param requested filename the save flow asked the picker to create (carries the format's extension);
	 *                  null-safe — a missing extension renders as "?"
	 * @param chosen    filename the user actually confirmed in the picker; null-safe likewise
	 */
	private void showExtensionMismatchDialog(String requested, String chosen)
	{
		int reqDot = requested == null ? -1 : requested.lastIndexOf('.');
		int choDot = chosen == null ? -1 : chosen.lastIndexOf('.');
		String reqExt = reqDot < 0 ? "?" : requested.substring(reqDot);
		String choExt = choDot < 0 ? "?" : chosen.substring(choDot);
		String message = "The picker-created document was typed as " + reqExt
			+ ", but you renamed it to " + choExt + ". The document's MIME type was "
			+ "locked when the picker opened, so writing the new format's bytes would "
			+ "leave a file whose content and type disagree. Re-open Save and change "
			+ "the format in the format picker instead.";
		EditorHost.showTransientGuarded(host, TAG, "extension-mismatch dialog", () -> {}, () -> {}, () ->
			new AlertDialog.Builder(host.getActivity())
				.setTitle("Change format in Save, not the picker")
				.setMessage(message)
				.setPositiveButton(DialogStrings.OK, null)
				.create());
	}

	/**
	 * Show the Rename / Replace / Cancel dialog for an in-app folder picker collision. Replace dispatches the write
	 * directly (routeCrashSafeSave handles the actual overwrite via the sibling-placeholder + atomic-rename path);
	 * Rename opens a filename input; Cancel rolls back.
	 *
	 * @param folder folder the user picked
	 * @param name   filename that collides in `folder`
	 */
	private void showInAppCollisionDialog(File folder, String name)
	{
		EditorHost.showTransientGuarded(host, TAG, "in-app collision dialog", this::onSaveCancelled,
			this::onSaveCancelled, () -> new AlertDialog.Builder(host.getActivity())
				.setTitle("File already exists")
				.setMessage("\"" + name + "\" already exists in this folder. What do you want to do?")
				// Replace re-dispatches through the normal in-app save path with the confirmation
				// threaded through: the dialog's gate is bare target.isFile(), so the colliding file
				// may be 0 bytes — a shape routeCrashSafeSave's probe classifies NOT-overwrite — and
				// without the confirmed flag the NEW-FILE promotion guard would refuse the very
				// Replace the user just confirmed. The confirmation also names the toast ("Replaced
				// X") even when the file vanishes mid-decision — the user's stated intent, no data
				// destroyed either way.
				.setPositiveButton("Replace",
					(dialog, which) -> dispatchInAppSave(folder, name, true))
				.setNeutralButton("Rename", (dialog, which) -> showInAppRenameDialog(folder, name))
				.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> onSaveCancelled())
				.setOnCancelListener(dialog -> onSaveCancelled())
				.create());
	}

	/**
	 * Filename input dialog reached from the Rename branch of showInAppCollisionDialog. Pre-fills with
	 * nextAvailableNumberedName's "(N)" suggestion (e.g. `foo (1).jpg` for a colliding `foo.jpg`, or `foo (2).jpg`
	 * if `foo (1).jpg` is also taken), falling back to the colliding name itself if the (N) loop overflows.
	 * selectAll lets the user replace or keep the suggestion in one tap. Recurses through the collision check on
	 * confirm — a renamed-to-another-existing-file still surfaces Rename/Replace/Cancel rather than silently
	 * overwriting. If the new name lacks an extension, the current export format's extension is appended.
	 *
	 * @param folder   folder the picker landed in
	 * @param original colliding filename — used as the suggestion-stem and as the overflow fallback
	 */
	private void showInAppRenameDialog(File folder, String original)
	{
		EditorHost.showTransientGuarded(host, TAG, "rename dialog", this::onSaveCancelled,
			this::onSaveCancelled, () -> buildInAppRenameDialog(folder, original));
	}

	/**
	 * Show the merged in-app save dialog (format + Export Grid + folder picker + thumbnail grid). Used as the
	 * primary Save flow when MANAGE_EXTERNAL_STORAGE is granted — bypasses Samsung's broken One UI
	 * ACTION_CREATE_DOCUMENT picker by browsing the filesystem via File I/O and writing directly. Initial folder is
	 * the last folder the user saved into (SharedPreferences) — falls back to primary external storage root on
	 * first save / cleared app data / when the previously-used folder no longer exists. Initial format + grid
	 * pre-populate from the current CropState's exportConfig / gridConfig.
	 *
	 * Tracking is done via host.setActiveTransientDialog (not registerTransientDialog) because the
	 * FolderPickerDialog installs its own composite OnDismissListener — registerTransientDialog would wrap that
	 * listener and break the savePending + executor-shutdown cleanup. The host clear callback is passed through so
	 * the host's activeTransientDialog field still releases on dismiss.
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
		// state.getOriginalFilename is provider-controlled so we strip any path-separator characters + reject
		// "."/".." segments before letting the value flow into the picker's EditText (which then re-validates
		// on Save here). Mirrors onMergedSaveConfirmed's defensive sanitisation path so the field never starts
		// in a state the validator would reject — defaultSaveStem's de-reservation covers the temp-namespace
		// rejection the same way (a rescued recovery's temp-shaped name strips to its visible name).
		String stem = defaultSaveStem(host.getState().getOriginalFilename());
		stem = stem.replace('/', '_').replace('\\', '_');
		if (stem.equals(".") || stem.equals("..") || stem.isEmpty())
		{
			stem = "crop";
		}
		String initialFilename = stem + initialFormat.extension();
		try
		{
			AlertDialog dialog = new FolderPickerDialog(host.getActivity(), startDir,
				initialFilename, initialFormat, initialBakeGrid, this::onMergedSaveConfirmed,
				host::clearTransientDialog).show();
			host.setActiveTransientDialog(dialog);
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "merged save dialog failed to show", e);
		}
	}

	/**
	 * Build and show the Replace / Cancel dialog for cases (A) and (C) — the SAF picker returned a URI pointing at
	 * pre-existing content (probeWasOverwrite returned true) but no auto-rename happened. Two buttons only: Replace
	 * (commit the overwrite via routeCrashSafeSave's existing crash-safe path) and Cancel (cleanup the placeholder
	 * if one exists). Unlike `showReplaceDialog`, there's no Keep option — the URI's filename is exactly what the
	 * user asked for, so "keep as auto-renamed" doesn't apply.
	 *
	 * On Cancel, we do NOT delete the SAF document: Case A's URI may point at the user's pre-existing file (the SAF
	 * picker handed it back without auto-rename when the user picked Replace in the provider's own prompt, OR when
	 * the provider silently overwrites). Deleting it on Cancel would destroy the user's data. The same conservative
	 * reasoning as `showExtensionMismatchDialog` — orphaned-fresh-placeholder fallout is acceptable; data-loss is
	 * not.
	 *
	 * Activity-destroyed and BadTokenException guards mirror `showReplaceDialog` so a config-change race doesn't
	 * strand savePending or skip the cleanup logic.
	 *
	 * @param newUri       SAF document URI returned by the picker — points at existing content per the
	 *                     probeWasOverwrite gate
	 * @param name         user's intended filename — Case A: original `requested`; Case C: `chosen`
	 * @param initiatedFor source bitmap the save is bound to; handed to the Replace button so
	 *                     onOverwriteReplaceConfirmed can recheck the binding before dispatching
	 */
	private void showOverwriteConfirmDialog(Uri newUri, String name, Bitmap initiatedFor)
	{
		String message = "A file named \"" + name + "\" already exists.\n\n" + "Replace — overwrite it.\n"
			+ "Cancel — don't save.";
		// Cancel / BACK / touch-outside / forced dismiss / show-failure / destroyed-Activity skip all roll back
		// the SaveDialog choices (format / Export Grid) before clearing savePending. The user abandoned this
		// save (chose Cancel rather than Replace), so the picked format / grid is uncommitted intent and must
		// NOT leak into the next save's defaults. Hoisted to a single Runnable so the abort sites can't drift
		// apart — mirrors showReplaceDialog's rollbackOnAbort pattern.
		Runnable rollbackOnAbort = () ->
		{
			savePending = false;
			restorePriorSaveSettings();
		};
		Runnable abortOnDestroyed = () ->
		{
			Log.w(TAG, "skipping overwrite-confirm dialog on destroyed activity");
			rollbackOnAbort.run();
		};
		EditorHost.showTransientGuarded(host, TAG, "overwrite-confirm dialog", abortOnDestroyed,
			rollbackOnAbort, () -> new AlertDialog.Builder(host.getActivity())
				.setTitle("Replace " + name + "?")
				.setMessage(message)
				.setPositiveButton("Replace",
					(dialog, which) -> onOverwriteReplaceConfirmed(newUri, name, initiatedFor))
				.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> rollbackOnAbort.run())
				.setOnCancelListener(dialog -> rollbackOnAbort.run())
				.create());
	}

	/**
	 * Build and show the Replace / Keep / Cancel dialog for case (B) of the save flow. Guards against the Activity
	 * finishing between the SAF result and this prompt: without the isDestroyed gate, .show() throws
	 * BadTokenException before any button / cancel listener runs, leaving savePending stuck and the SAF placeholder
	 * undeleted.
	 *
	 * @param newUri    SAF placeholder document the picker created (auto-renamed); deleted on every abort
	 *                  path and handed to onReplaceConfirmed / onReplaceKeep otherwise
	 * @param requested filename the user originally asked for (the colliding name Replace overwrites)
	 * @param safName   the picker's auto-renamed display name ("stem (N).ext") Keep would save under
	 */
	private void showReplaceDialog(Uri newUri, String requested, String safName)
	{
		Runnable cleanupPlaceholder = () -> safFiles.tryDeleteSafDocument(newUri);
		String message = "A file with this name already exists in the selected location.\n\n"
			+ "Replace — overwrite it.\n" + "Keep — save as \"" + safName + "\" instead.\n"
			+ "Cancel — don't save.";
		// Registration lets a Share/View intent or graft apply dismiss this dialog before bg state.reset().
		// Without it, a stale Replace prompt could outlive its source image — Replace/Keep would write image
		// B's state to image A's SAF target. Forced dismissal uses cancel(), so cleanupPlaceholder +
		// savePending reset still run. Cancel / BACK / touch-outside / forced dismiss / show-failure all roll
		// back the SaveDialog choices (format / Export Grid) before clearing savePending — the user abandoned
		// this save, so the picked format / grid is uncommitted intent that must NOT leak into the next save's
		// defaults. restorePriorSaveSettings also nulls priorSnapshot.sourceImage so the Bitmap reference
		// doesn't pin until a later save/load clears it. The destroyed-Activity skip only cleans the
		// placeholder + pending flag: there is no shown dialog whose choices need rolling back there.
		Runnable rollbackOnAbort = () ->
		{
			cleanupPlaceholder.run();
			restorePriorSaveSettings();
			savePending = false;
		};
		Runnable abortOnDestroyed = () ->
		{
			Log.w(TAG, "skipping replace-collision dialog on destroyed activity; cleaning placeholder");
			cleanupPlaceholder.run();
			savePending = false;
		};
		EditorHost.showTransientGuarded(host, TAG, "replace-collision dialog", abortOnDestroyed,
			rollbackOnAbort, () -> new AlertDialog.Builder(host.getActivity())
				.setTitle("Replace " + requested + "?")
				.setMessage(message)
				.setPositiveButton("Replace", (dialog, which) ->
					onReplaceConfirmed(newUri, requested, cleanupPlaceholder))
				.setNeutralButton("Keep", (dialog, which) -> onReplaceKeep(newUri, cleanupPlaceholder))
				.setNegativeButton(DialogStrings.CANCEL, (dialog, which) -> rollbackOnAbort.run())
				.setOnCancelListener(dialog -> rollbackOnAbort.run())
				.create());
	}

	/**
	 * Decide whether a sibling URI (the inferred pre-auto-rename target) actually has a
	 * colliding document with real content behind it. The "(N)" suffix on `chosen` could be either
	 * SAF auto-renaming around a real collision OR the user typing "(N)" intentionally in the
	 * picker — we can't tell from the suffix alone. Binds the live SafFileHelper probes to
	 * classifySiblingCollision, the pure decision core: filesystem answer first (exists + length
	 * > 0, aligning with probeWasOverwrite's size > 0 gate for Case A/C so "real-content ==
	 * collision" holds across all three cases), display-name + SIZE probes only when no path
	 * resolves. The fall-through false saves the user's "(N)" name as-is — a real collision
	 * becomes a duplicate save under a different name (no data loss), not a silent overwrite.
	 * (The opaque-ID provider case where no sibling URI can be derived never reaches this method —
	 * the caller substitutes true for a null baseUri, trusting the auto-rename pattern.)
	 *
	 * @param baseUri sibling document URI to probe; never null — the sole caller guards null with
	 *                its own trust-the-auto-rename-pattern fallback
	 * @return true when the sibling exists with real (or unknown-size) content; false when it is
	 *         absent, a 0-byte placeholder, or every probe is inconclusive
	 */
	private boolean siblingLooksLikeCollision(Uri baseUri)
	{
		return classifySiblingCollision(safFiles.fileFromSafUri(baseUri).orElse(null),
			() -> safFiles.getDisplayName(baseUri).isPresent(), () -> safFiles.querySafFileSize(baseUri));
	}
}
