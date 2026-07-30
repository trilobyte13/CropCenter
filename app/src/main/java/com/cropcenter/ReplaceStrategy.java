package com.cropcenter;

import android.app.AlertDialog;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.WorkerThread;

import com.cropcenter.crop.ExportResult;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.StoragePermissionHelper;
import com.cropcenter.view.DialogStrings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Collision-replace policy: SAF ACTION_CREATE_DOCUMENT with a colliding filename is silently auto-renamed by the
 * framework; the user's "Replace" choice then needs the app to overwrite the original. Tries File-I/O first (fastest,
 * most reliable when MANAGE_EXTERNAL_STORAGE is granted), then SAF direct overwrite, then SAF rename-with-fallback
 * (rename the placeholder onto the target first; delete-then-rename only when the provider rejects rename-to-existing,
 * so a failed rename never destroys the original). Verifies the end state and surfaces a failure dialog when disk
 * doesn't match the intent.
 *
 * Lives downstream of the save flow — SaveController decides whether to invoke this. SEFT preservation is the
 * exporter's responsibility (see CropExporter.appendSeftFileToFile for the verbatim-re-append contract and the
 * why-we-don't-fabricate-fresh-SEFT rationale).
 */
final class ReplaceStrategy
{
	/**
	 * One failure outcome of verifyReplace. Per-case factory methods (truncated, twoFiles, etc.) own the wording so
	 * verifyReplace stays a clean router from "what's on disk" to a factory call. Package-private so
	 * ReplaceStrategyClassifierTest can distinguish branches by their dialog wording.
	 *
	 * @param title          AlertDialog title for the failure case
	 * @param message        AlertDialog body text describing what's on disk and how to recover
	 * @param keptHiddenName filename of a kept recovery still on disk under its hidden SaveTempFiles temp
	 *                       name after every promotion attempt failed — verifyReplace journals it so the
	 *                       startup sweep promotes it to a visible name instead of deleting it; null when
	 *                       no kept recovery sits under a hidden name
	 */
	record VerifyFailure(String title, String message, String keptHiddenName)
	{
		/**
		 * Failure without a kept hidden recovery — the shape every factory except the hidden-kept
		 * wordings builds.
		 *
		 * @param title   AlertDialog title for the failure case
		 * @param message AlertDialog body text describing what's on disk and how to recover
		 */
		VerifyFailure(String title, String message)
		{
			this(title, message, null);
		}
	}

	/**
	 * Outcome of replaceViaFileIo's strategy-A attempt. WRITTEN_CLEAN lets writeReplacementPayload skip the
	 * placeholder-based verifyReplace (target length verified on disk AND placeholder deleted).
	 * WRITTEN_PLACEHOLDER_REMAINS means the target was written but the placeholder delete failed — the caller must
	 * fall through to verifyReplace so its duplicate-file diagnosis surfaces the leftover to the user. NOT_WRITTEN
	 * means strategy A couldn't touch the target (SAF strategies run next). ABORTED_TARGET_APPEARED means a
	 * NEW-FILE save found a file at the target name immediately before the atomic swap — the caller must skip the
	 * SAF strategies too (their overwrite / delete-then-rename would clobber the appeared file) and let
	 * verifyReplace name what is on disk.
	 */
	private enum FileIoOutcome
	{
		ABORTED_TARGET_APPEARED, NOT_WRITTEN, WRITTEN_CLEAN, WRITTEN_PLACEHOLDER_REMAINS
	}

	/**
	 * Outcome of classifyRenameFallback — strategy C's decision on what may follow a rename that returned empty
	 * while a colliding original still holds the target name. SILENT_SUCCESS treats the rename as having landed
	 * (skip the retry); DELETE_THEN_RETRY sanctions the destructive delete-then-rename fallback; FAIL_CLOSED
	 * forbids the delete and leaves the end state to verifyReplace. Package-private so
	 * ReplaceStrategyClassifierTest can pin the ladder.
	 */
	enum RenameFallbackDecision
	{
		DELETE_THEN_RETRY, FAIL_CLOSED, SILENT_SUCCESS
	}

	private static final String TAG = "ReplaceStrategy";
	// Smart-quoted "All files access" — the exact wording Android's Settings UI uses for the
	// MANAGE_EXTERNAL_STORAGE permission. Centralised here because 5 different verifyReplace failure messages
	// reference it; if Android renames the permission, this is the one place to change.
	private static final String ALL_FILES_ACCESS = "“All files access”";

	private final ExportPipeline exportPipeline;
	private final SafFileHelper safFiles;
	private final SaveHost host;
	private final StoragePermissionHelper permissions;

	ReplaceStrategy(SaveHost host, ExportPipeline exportPipeline, SafFileHelper safFiles,
		StoragePermissionHelper permissions)
	{
		this.host = host;
		this.exportPipeline = exportPipeline;
		this.safFiles = safFiles;
		this.permissions = permissions;
	}

	/**
	 * Filesystem-authoritative classifier. Handles two structural cases: Strategy-C rename
	 * (placeholder == target on disk) and the general two-files / one-missing fan-out. Owns two disk
	 * side effects so no verified save is ever left claiming a name it doesn't have:
	 *   - completes the swap itself when only the length-verified placeholder survived (renames it onto
	 *     the free target name — clean), and deletes a redundant placeholder when the target verified ok
	 *   - promotes a kept hidden crash-safe placeholder (SaveTempFiles naming) to a visible "(N)" sibling
	 *     before any failure dialog names it — dialogs never claim a save under a hidden dotfile
	 * A sole surviving wrong-length target is never deleted: the length mismatch proves the save didn't
	 * verify, not that the file is ours — it may be the untouched pre-Replace original (FUSE-view lag on
	 * the placeholder probe) or a file another process created — so both wrong-length branches keep it
	 * and the truncated wording names it as preserved. The only file this classifier ever deletes is a
	 * redundant placeholder sitting next to a target verified at the expected length.
	 * knownBadTarget overrides every length-only target check: a target strategy B's byte-for-byte readback
	 * proved wrong can never be reclassified as success just because its length matches, the placeholder is
	 * never deleted as a redundant duplicate of it, and the proven-wrong target is never deleted either —
	 * when the placeholder is gone its stale content is all the user has left. Strategy B never renames the
	 * placeholder, so the same-name Strategy-C branch is unreachable with the flag set.
	 * Package-private + static so ReplaceStrategyClassifierTest can pin the disk-state outcomes.
	 * Mis-classification ships a corrupt file as "saved" or strands orphan auto-renames.
	 *
	 * @param placeholder    sibling placeholder file written by the crash-safe pipeline
	 * @param requestedName  filename the user typed in the picker
	 * @param expectedLength byte count the verify-step expects on disk for a successful write
	 * @param knownBadTarget true when strategy B's byte-for-byte readback already proved the target's
	 *                       content wrong — the target's length carries no evidence of success, no branch
	 *                       may report clean off it, and neither the target nor the placeholder is deleted
	 * @param wasOverwrite   false when the save was classified NEW-FILE at collision-probe time — the
	 *                       sole-survivor completion rename then probes target existence immediately before
	 *                       the rename (promoteSoleSurvivor) and never replaces a file that appeared after
	 *                       classification; true preserves replace semantics
	 * @return empty when the outcome is clean (including the completed-by-rename and
	 *         redundant-placeholder-deleted recoveries); a populated VerifyFailure when the on-disk state
	 *         is missing, truncated, split across two files, or content-proven wrong
	 */
	static Optional<VerifyFailure> classifyFilesystemOutcome(File placeholder, String requestedName,
		int expectedLength, boolean knownBadTarget, boolean wasOverwrite)
	{
		File parent = placeholder.getParentFile();
		File target = (parent != null) ? new File(parent, requestedName) : null;
		// Strategy C path: rename moved placeholder onto target's path. placeholder == target on disk.
		if (target != null && placeholder.getName().equals(target.getName()))
		{
			if (target.exists() && target.length() == expectedLength)
			{
				return Optional.empty(); // clean replace via Strategy C rename
			}
			if (target.exists())
			{
				// Wrong length with no other copy on disk. The length mismatch alone can't prove the
				// file is our failed write — SILENT_SUCCESS's placeholder-gone inference can misfire
				// under FUSE-view lag (see skipExactly), in which case this IS the untouched
				// pre-Replace original. Keep it and say so.
				return Optional.of(truncated(requestedName, target.length(), expectedLength));
			}
			return Optional.of(missingAfterRename(requestedName));
		}
		boolean placeholderExists = placeholder.exists();
		if (knownBadTarget && target != null && target.exists())
		{
			// Strategy B's readback proved the target's content wrong, so its on-disk length carries no
			// evidence of success — same-length stale content is the exact case the readback caught.
			if (placeholderExists)
			{
				return Optional.of(contentMismatch(requestedName,
					promoteToVisibleName(placeholder, requestedName)));
			}
			// No backup left. Keep the proven-wrong target (its stale content may be the pre-Replace
			// original) — deleting it would destroy the only copy the user still has.
			return Optional.of(contentMismatchNoBackup(requestedName));
		}
		boolean targetOk = target != null && target.exists() && target.length() == expectedLength;
		if (targetOk && !placeholderExists)
		{
			return Optional.empty(); // clean replace
		}
		if (targetOk)
		{
			// Target verified at the expected length, so the leftover placeholder is a redundant duplicate
			// (strategy A wrote the target but couldn't delete the placeholder). Deleting it completes the
			// clean end state; if the delete still fails, promote it to a visible name so the two-files
			// dialog names a file the user can actually see and remove.
			if (placeholder.delete())
			{
				return Optional.empty();
			}
			return Optional.of(twoFiles(requestedName, promoteToVisibleName(placeholder, requestedName)));
		}
		boolean targetExists = target != null && target.exists();
		long targetLen = targetExists ? target.length() : -1;
		if (targetExists && !placeholderExists)
		{
			// The wrong-length target is the ONLY file left. The app's own writers can't produce this
			// shape (strategy A lands only a length-verified atomic move; strategy B mismatches route
			// through knownBadTarget), so the file is foreign or ambiguous — on a NEW-FILE save it
			// appeared at the target name after classification. Keep it; deleting would destroy the
			// only copy the user has left.
			return Optional.of(truncated(requestedName, targetLen, expectedLength));
		}
		if (placeholderExists && targetExists)
		{
			return Optional.of(twoFiles(requestedName, promoteToVisibleName(placeholder, requestedName)));
		}
		if (placeholderExists)
		{
			// Only the placeholder survived and the target name was free above. When the placeholder's
			// length matches the verified write, renaming it onto the target completes exactly the end
			// state the user asked for — report clean rather than a failure dialog about a file the user
			// would have to rename manually. promoteSoleSurvivor owns the length gate (a corrupted / stale
			// placeholder never lands at the visible requested name) and the pre-rename probe (a NEW-FILE
			// save never replaces a file that appeared at the target name after classification).
			if (target != null && promoteSoleSurvivor(placeholder, target, expectedLength, wasOverwrite))
			{
				return Optional.empty();
			}
			String keptName = promoteToVisibleName(placeholder, requestedName);
			if (target != null && target.exists())
			{
				// The target name filled in behind the probes above — both files are on disk, so the
				// two-files wording names them both; placeholderOnly's rename-it-yourself advice
				// would aim at the now-occupied name.
				return Optional.of(twoFiles(requestedName, keptName));
			}
			return Optional.of(placeholderOnly(requestedName, keptName));
		}
		return Optional.of(bothMissing(requestedName, placeholder.getName()));
	}

	/**
	 * Fail-closed ladder for strategy C's rename-returned-empty fallback: decide whether the destructive
	 * delete-then-retry may run. After a silently-successful rename (legacy null-on-success providers whose
	 * follow-up display-name query also failed), the colliding URI IS the just-renamed export — deleting it on
	 * ambiguity would destroy the user's only verified copy. The ladder therefore demands positive evidence that
	 * the placeholder still exists under its own name before sanctioning the delete:
	 *   1. Filesystem probe available: placeholder gone means the rename landed (SILENT_SUCCESS); placeholder
	 *      present means the provider really rejected it (DELETE_THEN_RETRY).
	 *   2. No filesystem path (opaque provider): the display-name probe decides — a name other than
	 *      requestedName proves the placeholder still sits under its own name (DELETE_THEN_RETRY); a name equal
	 *      to requestedName proves the rename landed (SILENT_SUCCESS).
	 *   3. Both probes unavailable: FAIL_CLOSED — no delete, no retry; verifyReplace then surfaces the
	 *      two-files / placeholder outcome instead of risking the just-renamed export.
	 * Package-private + static so ReplaceStrategyClassifierTest can pin the ladder without a ContentResolver.
	 *
	 * @param probeAvailable    true when fileFromSafUri resolved the placeholder URI to a filesystem path
	 * @param placeholderExists filesystem probe result; consulted only when probeAvailable is true
	 * @param displayName       the placeholder URI's current SAF display name, or null when that probe is
	 *                          unavailable too; consulted only when probeAvailable is false
	 * @param requestedName     the user's requested target filename the rename aimed at (match is
	 *                          case-insensitive, same convention as tryRename's legacy-success check)
	 * @return the sanctioned fallback action per the ladder above
	 */
	static RenameFallbackDecision classifyRenameFallback(boolean probeAvailable, boolean placeholderExists,
		String displayName, String requestedName)
	{
		if (probeAvailable)
		{
			return placeholderExists ? RenameFallbackDecision.DELETE_THEN_RETRY
				: RenameFallbackDecision.SILENT_SUCCESS;
		}
		if (displayName == null)
		{
			return RenameFallbackDecision.FAIL_CLOSED;
		}
		return displayName.equalsIgnoreCase(requestedName) ? RenameFallbackDecision.SILENT_SUCCESS
			: RenameFallbackDecision.DELETE_THEN_RETRY;
	}

	/**
	 * Degrade a kept-name failure's sweep-protection promise when the journal write failed. An unjournaled
	 * hidden recovery is exactly what the startup sweep deletes once it passes the stale age guard, so the
	 * dialog must not imply the file is safe indefinitely — the degraded wording warns that the file may be
	 * cleaned up automatically and tells the user to act promptly. Failures without a kept hidden name, and
	 * kept names whose journal entry landed, pass through unchanged. Package-private static so
	 * ReplaceStrategyClassifierTest can pin the degradation without a Context-backed journal.
	 *
	 * @param failure   the classified failure whose dialog is about to show
	 * @param journaled true when journalKeptRecovery durably recorded the kept recovery
	 * @return the failure to show — unchanged when the kept promise holds, reworded when unjournaled
	 */
	static VerifyFailure degradeKeptPromiseWhenUnjournaled(VerifyFailure failure, boolean journaled)
	{
		if (failure.keptHiddenName() == null || journaled)
		{
			return failure;
		}
		return new VerifyFailure(failure.title(), failure.message()
			+ " This kept file couldn't be protected — it may be cleaned up automatically after an"
			+ " hour; rescue or re-save it soon.", failure.keptHiddenName());
	}

	/**
	 * Failure outcome for a kept placeholder that is still under its hidden crash-safe temp name after every rename
	 * attempt failed. The wording names the actual hidden filename and says it won't appear in Gallery — the dialog
	 * must never claim the crop was saved under a name that isn't on disk. Carries keptName as the VerifyFailure's
	 * keptHiddenName: verifyReplace journals it so the startup sweep promotes the kept recovery to a visible name
	 * instead of deleting it once it goes stale — the sweep must never reclaim a file this dialog promised was
	 * kept. Package-private static so ReplaceStrategyClassifierTest can pin the journal signal directly (a
	 * promotion failure can't be forced portably through the filesystem).
	 *
	 * @param requestedName the user's requested target filename
	 * @param keptName      the hidden temp name the placeholder is actually on disk under
	 * @return populated VerifyFailure carrying the honest hidden-file dialog text and keptName as
	 *         keptHiddenName
	 */
	static VerifyFailure hiddenPlaceholderKept(String requestedName, String keptName)
	{
		return new VerifyFailure("Couldn't replace " + requestedName,
			"Your crop was kept as a hidden file named " + keptName + " in the save folder — hidden"
				+ " files don't appear in Gallery. Save again, or grant " + ALL_FILES_ACCESS
				+ " so Replace can finish under the right name.", keptName);
	}

	/**
	 * Fail-closed ordering contract of strategy C's destructive fallback: journal a temp-shaped placeholder as
	 * a kept recovery, and delete the colliding original ONLY under a durable journal entry. Between that
	 * delete and the retry rename the hidden placeholder is the user's only copy — a hard kill in the window
	 * strands it invisible to Gallery, and unjournaled the startup sweep would delete it once stale — so a
	 * false journalKept result (journalKeptRecovery couldn't durably commit) skips the delete entirely. The
	 * caller's retry rename then runs against the still-existing colliding document; a provider on this branch
	 * already rejected rename-to-existing once, so the retry fails the same way and verifyReplace surfaces the
	 * two-files outcome (placeholder + original both on disk) — a recoverable dialog instead of an unprotected
	 * crash window. Visible placeholder names (Case B "(N)" auto-renames) are outside the sweep's matcher and
	 * skip the journal; their delete runs unconditionally. Package-private static so
	 * ReplaceStrategyClassifierTest can pin the gate and the ordering with recording callbacks.
	 *
	 * @param placeholderName the placeholder's current on-disk name; null (undeterminable) skips the journal
	 * @param journalKept     journals the placeholder as a kept recovery (synchronous commit — the caller is
	 *                        on the bg executor) and reports journalKeptRecovery's durability signal; runs
	 *                        only for temp-shaped names, always before deleteColliding
	 * @param deleteColliding deletes the colliding original — runs unconditionally for visible names, and for
	 *                        temp-shaped names only after journalKept reported a durable entry
	 * @return true when the placeholder was temp-shaped, durably journaled, and the delete ran (the caller
	 *         un-journals the entry again on a verified clean promotion); false when the journal was skipped
	 *         (visible name — delete still ran) or failed (delete skipped)
	 */
	static boolean journalThenDeleteColliding(String placeholderName, BooleanSupplier journalKept,
		Runnable deleteColliding)
	{
		if (SaveTempFiles.isTempArtifact(placeholderName))
		{
			if (!journalKept.getAsBoolean())
			{
				Log.w(TAG, "kept-recovery journal failed for " + placeholderName
					+ " — skipping colliding delete (fail closed)");
				return false;
			}
			deleteColliding.run();
			return true;
		}
		deleteColliding.run();
		return false;
	}

	/**
	 * Sole-survivor completion rename with the new-file collision guard: probe target existence immediately
	 * before renaming the length-verified placeholder onto the (previously free) target name. On a NEW-FILE
	 * save (wasOverwrite false) a file at the target name now was created by another process after the save was
	 * classified — the promotion must never replace it (File.renameTo replaces existing targets on POSIX
	 * filesystems), so the rename is refused and the caller surfaces the two-files outcome. Overwrite saves
	 * keep replace semantics. Package-private static so ReplaceStrategyClassifierTest can pin the abort with a
	 * recording placeholder — the appear-between-probe-and-rename window is not reachable through
	 * classifyFilesystemOutcome single-threaded.
	 *
	 * @param placeholder    sole-survivor placeholder holding the verified bytes
	 * @param target         target file the placeholder should land at, free at classification time
	 * @param expectedLength byte count the verify-step expects — a placeholder at any other length is never
	 *                       renamed onto the visible requested name
	 * @param wasOverwrite   false when the save was classified NEW-FILE at collision-probe time — the
	 *                       pre-rename probe then refuses an existing target; true preserves replace
	 *                       semantics
	 * @return true when the placeholder now lives at the target name; false when the probe refused the
	 *         promotion or the length gate / rename did
	 */
	static boolean promoteSoleSurvivor(File placeholder, File target, int expectedLength, boolean wasOverwrite)
	{
		if (!wasOverwrite && target.exists())
		{
			return false;
		}
		return placeholder.length() == expectedLength && placeholder.renameTo(target);
	}

	/**
	 * Strategy A's temp-onto-target swap with the two-gate new-file collision guard. On a NEW-FILE save
	 * (wasOverwrite false) a file at the target name now was created by another process after the save was
	 * classified, and the swap must never clobber it: gate one is the userspace existence probe immediately
	 * before the move; gate two is the move itself run WITHOUT REPLACE_EXISTING, so a file that appears
	 * between probe and move surfaces as FileAlreadyExistsException and gets the same refusal. ATOMIC_MOVE is
	 * dropped alongside on that branch — it maps to a bare rename that silently replaces an existing POSIX
	 * target (exactly the clobber gate two exists to refuse) and makes Files.move ignore REPLACE_EXISTING's
	 * absence; temp and target are same-directory siblings, so the plain move is still a single rename in
	 * practice. The kernel-level window between gate two's check and the underlying rename is accepted as a
	 * residual (REQUIREMENTS.md Known Limitations). Overwrite saves keep REPLACE_EXISTING + ATOMIC_MOVE:
	 * replacing the existing target is exactly the Replace the user confirmed. Package-private static so
	 * ReplaceStrategyClassifierTest can pin both gates on real files — replaceViaFileIo itself is SAF-bound
	 * and not drivable headlessly.
	 *
	 * @param tempFile     fully-written, length-verified temp sibling holding the payload
	 * @param target       target file the temp swaps onto
	 * @param wasOverwrite false when the save was classified NEW-FILE at collision-probe time — both gates
	 *                     then refuse an existing target; true preserves replace semantics
	 * @return true when the move ran; false when either gate refused it (temp left in place for the caller
	 *         to discard)
	 * @throws IOException when the move fails for any reason other than gate two's existence refusal
	 */
	static boolean promoteTempOntoTarget(File tempFile, File target, boolean wasOverwrite) throws IOException
	{
		if (wasOverwrite)
		{
			Files.move(tempFile.toPath(), target.toPath(),
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		}
		if (target.exists())
		{
			return false;
		}
		// Gate two: moveWithoutReplacing surfaces a target that appeared between the probe above and the
		// move as the same refusal, one existence check closer to the rename.
		return moveWithoutReplacing(tempFile, target);
	}

	/**
	 * Give a kept placeholder its final user-visible name. Hidden crash-safe temps (SaveTempFiles naming) are
	 * renamed to the first available "stem (N).ext" sibling derived from requestedName; already-visible
	 * placeholders (Case B SAF "(N)" auto-renames) pass through unchanged. The rename itself never replaces: a
	 * file another process creates at the probed "(N)" name between probe and move is refused, and the promotion
	 * retries onto the next free candidate (retryPromotionOntoFreeSibling). Returns the name the file is ACTUALLY
	 * on disk under after the attempt — the failure-dialog factories key their wording off whether that name is
	 * still hidden, so no dialog ever claims a visible save while the bytes sit in a dotfile. Package-private
	 * static because it is also the startup sweep's promotion helper: SaveController.promoteJournaledRecovery
	 * routes a journaled kept recovery through this exact rename so the sweep and the failure dialogs can't drift
	 * on what "promoted" means.
	 *
	 * @param placeholder   kept placeholder file (exists on disk)
	 * @param requestedName the user's requested target filename the visible name derives from
	 * @return the placeholder's on-disk name after the promotion attempt (visible on success, the
	 *         original hidden temp name when no candidate was available or the rename failed)
	 */
	static String promoteToVisibleName(File placeholder, String requestedName)
	{
		if (!SaveTempFiles.isTempArtifact(placeholder.getName()))
		{
			return placeholder.getName();
		}
		File parent = placeholder.getParentFile();
		String candidate = parent != null
			? SaveController.nextAvailableNumberedName(parent, requestedName).orElse(null)
			: null;
		if (candidate != null)
		{
			String landed = retryPromotionOntoFreeSibling(placeholder, parent, candidate);
			if (landed != null)
			{
				return landed;
			}
		}
		Log.w(TAG, "couldn't promote hidden placeholder " + placeholder + " to a visible name");
		return placeholder.getName();
	}

	/**
	 * Bounded no-replace retry loop of promoteToVisibleName. nextAvailableNumberedName's exists() probe and the
	 * move are not one atomic operation, and a bare rename would silently destroy a file another process created
	 * at the probed "(N)" name in that window (File.renameTo maps to POSIX rename(2), which replaces). The move
	 * therefore runs without REPLACE_EXISTING (moveWithoutReplacing); a candidate that filled in surfaces as a
	 * refusal and the loop re-probes onto the next free "(N)" sibling. SaveController.MAX_RENAME_SUFFIX bounds
	 * the retries — the probe space itself is capped at that many candidates, so the loop can never out-probe it.
	 * Package-private static so ReplaceStrategyClassifierTest can drive the collision retry with a stale first
	 * candidate — the fill-in-between-probe-and-move window is not reachable single-threaded.
	 *
	 * @param placeholder kept placeholder file to promote (exists on disk)
	 * @param parent      the placeholder's parent directory, also the probe directory
	 * @param candidate   first probed free "stem (N).ext" sibling name to try
	 * @return the visible name the placeholder landed at, or null when every candidate was refused or a
	 *         move failed for a non-collision reason (caller keeps the hidden name)
	 */
	static String retryPromotionOntoFreeSibling(File placeholder, File parent, String candidate)
	{
		for (int attempt = 0; candidate != null && attempt < SaveController.MAX_RENAME_SUFFIX; attempt++)
		{
			try
			{
				if (moveWithoutReplacing(placeholder, new File(parent, candidate)))
				{
					return candidate;
				}
			}
			catch (IOException e)
			{
				Log.w(TAG, "promotion move of " + placeholder + " onto " + candidate + " failed: "
					+ e.getMessage());
				return null;
			}
			// The probed candidate filled in between probe and move — re-probe past it instead of
			// replacing the appeared file.
			candidate = SaveController.nextAvailableNumberedName(parent, candidate).orElse(null);
		}
		return null;
	}

	/**
	 * Replace the colliding file in the user's chosen directory (NOT the opened file's directory).
	 *
	 * Write-first-then-swap: the new bytes go to the auto-renamed "(N)" SAF placeholder and are verified
	 * BEFORE we touch the original, so a failed encode/write leaves the original intact — and ExportPipeline
	 * deletes the fresh placeholder on every pre-write abort and write failure, so a failed save leaves no
	 * orphan either (hard kills are reclaimed by the startup sweep). Then, in order of reliability:
	 *   A. File I/O (MANAGE_EXTERNAL_STORAGE) — bypasses SAF's inconsistent delete/rename semantics.
	 *   B. SAF direct overwrite — stream placeholder bytes into the colliding URI.
	 *   C. SAF rename-with-fallback — rename the placeholder onto the target first; delete-colliding +
	 *      retry-rename only when the provider rejects rename-to-existing (a failed rename never
	 *      destroys the original).
	 *
	 * Ordering is critical: when File I/O succeeds it has already written the target, so the SAF paths'
	 * delete/rename calls could destroy it — File I/O short-circuits the rest, and SAF runs only when it couldn't
	 * touch the target. verifyReplace runs in both branches to catch partial states.
	 *
	 * @param newUri        SAF placeholder URI (auto-rename "(N)" name, or the crash-safe hidden
	 *                      SaveTempFiles name) holding the freshly-written bytes
	 * @param requestedName the user-typed filename the placeholder should land at after the swap
	 * @param wasOverwrite  true on a confirmed-overwrite path (toast "Replaced X" vs "Saved X"); false
	 *                      additionally forbids every promotion from replacing a file that appeared at the
	 *                      target name after the save was classified NEW-FILE (pre-move probes in
	 *                      promoteTempOntoTarget / promoteSoleSurvivor)
	 */
	void replaceColliding(Uri newUri, String requestedName, boolean wasOverwrite)
	{
		// Captures the post-rename URI when strategy C succeeds. Starts as newUri because strategies A and B
		// either keep the placeholder URI valid (A writes to a new path, B leaves newUri until final delete) or
		// a later verifier cares only about the colliding URI. Strategy C overwrites this with whatever
		// DocumentsContract.rename returns — providers may relocate the document to a new URI, and
		// verifyReplace's follow-up query MUST use the fresh URI or it hits a stale ID.
		final Uri[] verifyUriBox = { newUri };
		exportPipeline.exportTo(newUri,
			result -> writeReplacementPayload(newUri, requestedName, wasOverwrite, verifyUriBox, result));
	}

	// ── VerifyFailure factory methods ── one per failure case so wording lives in one place.

	private static VerifyFailure bothMissing(String requestedName, String placeholderName)
	{
		return new VerifyFailure("Save may have failed", "Neither " + requestedName + " nor " + placeholderName
			+ " is on disk. Check your save directory and try again.");
	}

	/**
	 * Failure outcome when strategy B's byte-for-byte readback proved the target's content wrong and the kept
	 * placeholder is the user's verified save. Routes to the hidden-file wording when the kept name is still a
	 * crash-safe temp — that branch carries keptName as keptHiddenName so verifyReplace journals the kept
	 * recovery (same promise, same sweep protection as hiddenPlaceholderKept); either way the dialog states that
	 * Replace wrote wrong content to the target and names the placeholder as the good copy.
	 *
	 * @param requestedName the user's requested target filename (content proven wrong on disk)
	 * @param keptName      the name the good-copy placeholder is actually on disk under after promotion
	 * @return populated VerifyFailure carrying the content-mismatch dialog text
	 */
	private static VerifyFailure contentMismatch(String requestedName, String keptName)
	{
		if (SaveTempFiles.isTempArtifact(keptName))
		{
			return new VerifyFailure("Replace wrote wrong content",
				"Replace wrote wrong content to " + requestedName + " — reading it back returned"
					+ " different bytes than your crop. Your verified crop was kept as a hidden"
					+ " file named " + keptName + " — hidden files don't appear in Gallery. Save"
					+ " again, or grant " + ALL_FILES_ACCESS + " so Replace can finish under the"
					+ " right name.", keptName);
		}
		return new VerifyFailure("Replace wrote wrong content",
			"Replace wrote wrong content to " + requestedName + " — reading it back returned different"
				+ " bytes than your crop. Your verified crop is " + keptName + ". Keep or rename that"
				+ " file in the Files app, or grant " + ALL_FILES_ACCESS + " and save again.");
	}

	/**
	 * Failure outcome when strategy B's byte-for-byte readback proved the target's content wrong and no
	 * placeholder backup survived. The target is deliberately NOT deleted — its stale content (possibly the
	 * pre-Replace original) is the only copy the user has left — and the wording says so.
	 *
	 * @param requestedName the user's requested target filename (content proven wrong, kept on disk)
	 * @return populated VerifyFailure carrying the no-backup content-mismatch dialog text
	 */
	private static VerifyFailure contentMismatchNoBackup(String requestedName)
	{
		return new VerifyFailure("Replace wrote wrong content",
			"Replace wrote wrong content to " + requestedName + " — reading it back returned different"
				+ " bytes than your crop, and the backup copy is no longer on disk. The file was left"
				+ " in place because it may hold the previous photo. Save your crop again under a"
				+ " different name.");
	}

	private static VerifyFailure missingAfterRename(String requestedName)
	{
		return new VerifyFailure("Save may have failed",
			requestedName + " is not on disk after Replace. Check your save directory and try again.");
	}

	/**
	 * Guarded move that refuses to replace an existing destination. Runs Files.move WITHOUT REPLACE_EXISTING so
	 * a file that appears at dest between the caller's own existence probe and the move surfaces as the
	 * filesystem's refusal instead of being silently destroyed (a bare rename maps to POSIX rename(2), which
	 * replaces). Both no-replace promotion sites route here — promoteTempOntoTarget's gate two and
	 * retryPromotionOntoFreeSibling's per-candidate move — so the refusal semantics can't drift. Source and dest
	 * are same-directory siblings at every call site, so the plain move is a single rename in practice; the
	 * kernel-level window between the filesystem's check and the underlying rename is the accepted residual
	 * documented in REQUIREMENTS.md Known Limitations.
	 *
	 * @param source file to move (left in place on refusal)
	 * @param dest   destination the move must not replace
	 * @return true when the move ran; false when a file already sat at dest
	 * @throws IOException when the move fails for any reason other than the existence refusal
	 */
	private static boolean moveWithoutReplacing(File source, File dest) throws IOException
	{
		try
		{
			Files.move(source.toPath(), dest.toPath());
		}
		catch (FileAlreadyExistsException ignored)
		{
			// The filesystem's own existence check refused the replace — dest filled in since the
			// caller's probe; the caller treats this exactly like its userspace probe refusal.
			return false;
		}
		return true;
	}

	/**
	 * Failure outcome when only the kept placeholder is on disk and it couldn't be renamed onto the free target
	 * name. Routes to the hidden-file wording when the kept name is still a crash-safe temp; otherwise names the
	 * visible file the crop actually lives at.
	 *
	 * @param requestedName the user's requested target filename
	 * @param keptName      the name the placeholder is actually on disk under after promotion
	 * @return populated VerifyFailure carrying the dialog text for the detected case
	 */
	private static VerifyFailure placeholderOnly(String requestedName, String keptName)
	{
		if (SaveTempFiles.isTempArtifact(keptName))
		{
			return hiddenPlaceholderKept(requestedName, keptName);
		}
		return new VerifyFailure("Couldn't replace " + requestedName,
			"Your crop was saved as " + keptName + " instead. Rename it to " + requestedName
				+ " in the Files app, or grant " + ALL_FILES_ACCESS + " and save again.");
	}

	private static VerifyFailure safAutoRenamed(String requestedName, String finalName)
	{
		return new VerifyFailure("Couldn't replace " + requestedName,
			"Your crop was saved as " + finalName + ". Grant " + ALL_FILES_ACCESS
				+ " so Replace can overwrite the existing file, or delete the original from the Files"
				+ " app and save again.");
	}

	private static VerifyFailure safUnverifiable(String requestedName)
	{
		return new VerifyFailure("Couldn't verify replace",
			"Save may not have completed. Check your save directory for a " + requestedName
				+ " or auto-renamed copy.");
	}

	/**
	 * Single factory for both wrong-length sole-survivor outcomes — the user-facing message and title are
	 * identical for the same-name (Strategy C) and separate-target call sites. Both branches KEEP the file: a
	 * length mismatch proves the save didn't verify, not that the file is ours to delete — it may hold the
	 * pre-Replace original — so the wording names what is on disk and that it was preserved.
	 *
	 * @param requestedName the user's requested target filename (the wrong-length file kept on disk)
	 * @param actualLen     byte count found on disk
	 * @param expectedLen   byte count the payload was written with
	 * @return populated VerifyFailure carrying the incomplete-file dialog text
	 */
	private static VerifyFailure truncated(String requestedName, long actualLen, int expectedLen)
	{
		return new VerifyFailure("Replace produced an incomplete file",
			requestedName + " is " + actualLen + " bytes instead of the expected " + expectedLen
				+ ". The file was left on disk because it may hold your previous photo — check it"
				+ " before deleting anything, then re-save. If this keeps happening, grant "
				+ ALL_FILES_ACCESS + " or move the save target to a folder you own.");
	}

	/**
	 * Failure outcome when both the target and the kept placeholder are on disk. Routes to the hidden-file wording
	 * when the kept name is still a crash-safe temp (the user can't see — let alone delete — a dotfile from Gallery
	 * or the Files app); otherwise names both visible files.
	 *
	 * @param requestedName the user's requested target filename
	 * @param keptName      the name the placeholder is actually on disk under after promotion
	 * @return populated VerifyFailure carrying the dialog text for the detected case
	 */
	private static VerifyFailure twoFiles(String requestedName, String keptName)
	{
		if (SaveTempFiles.isTempArtifact(keptName))
		{
			return hiddenPlaceholderKept(requestedName, keptName);
		}
		return new VerifyFailure("Replace left two files",
			"Both " + requestedName + " and " + keptName + " now exist on disk. Grant "
				+ ALL_FILES_ACCESS + " so Replace can clean up automatically, or delete one manually"
				+ " from the Files app.");
	}

	/**
	 * Build the Replace-failure dialog for postReplaceFailureDialog: OK button always, plus a "Grant access"
	 * neutral shortcut into Settings when MANAGE_EXTERNAL_STORAGE isn't held — that's the permission most
	 * Replace failure modes need to resolve, so a one-tap shortcut is more useful than asking the user to
	 * navigate there manually.
	 *
	 * @param title   AlertDialog title
	 * @param message AlertDialog body text
	 * @return the ready-to-show dialog
	 */
	private AlertDialog buildReplaceFailureDialog(String title, String message)
	{
		AlertDialog.Builder builder = new AlertDialog.Builder(host.getActivity())
			.setTitle(title)
			.setMessage(message)
			.setPositiveButton(DialogStrings.OK, null);
		if (!permissions.hasStoragePermission())
		{
			builder.setNeutralButton("Grant access",
				(dialog, which) -> permissions.openStoragePermissionSettings());
		}
		return builder.create();
	}

	/**
	 * SAF-only fallback when no filesystem path is available (cloud / SAF-only providers without
	 * MANAGE_EXTERNAL_STORAGE). Uses the placeholder URI's SAF display name to detect auto-rename. A kept
	 * crash-safe placeholder still under its hidden temp name gets one SAF rename attempt onto the requested name —
	 * permissive providers overwrite the colliding original (the Replace the user asked for), strict providers
	 * reject and the honest hidden-file dialog fires instead of a message claiming the crop was "saved as" a
	 * dotfile Gallery never shows.
	 *
	 * @param placeholderUri SAF URI of the just-written placeholder (may have been renamed onto target)
	 * @param requestedName  the user's requested target filename
	 * @return empty on clean replace per SAF, or a VerifyFailure carrying the dialog text for the
	 *         detected case
	 */
	private Optional<VerifyFailure> classifySafFallbackOutcome(Uri placeholderUri, String requestedName)
	{
		String finalName = safFiles.getDisplayName(placeholderUri).orElse(null);
		if (finalName != null && requestedName.equalsIgnoreCase(finalName))
		{
			return Optional.empty(); // clean replace per SAF
		}
		if (finalName != null && SaveTempFiles.isTempArtifact(finalName))
		{
			if (tryRename(placeholderUri, requestedName).isPresent())
			{
				return Optional.empty(); // placeholder now lives at the requested visible name
			}
			return Optional.of(hiddenPlaceholderKept(requestedName, finalName));
		}
		if (finalName != null)
		{
			return Optional.of(safAutoRenamed(requestedName, finalName));
		}
		return Optional.of(safUnverifiable(requestedName));
	}

	/**
	 * Classify the post-Replace disk state into a clean-success (empty return) or a typed failure with pre-built
	 * dialog title + message. Side effects (see classifyFilesystemOutcome): completes the swap by renaming a
	 * length-verified sole-survivor placeholder onto the free target name, and promotes kept hidden placeholders
	 * to visible names before any dialog names them; a sole surviving wrong-length target is kept. Filesystem
	 * checks run when MANAGE_EXTERNAL_STORAGE is granted (authoritative); falls back to a SAF display-name query
	 * otherwise. knownBadTarget only steers the filesystem classifier — the SAF fallback is display-name-based, so
	 * its clean returns require the placeholder to actually sit at the requested name (never a length-only check).
	 *
	 * @param placeholderUri SAF URI of the just-written placeholder (may have been renamed onto target)
	 * @param requestedName  the user's requested target filename
	 * @param expectedLength the byte count the placeholder was written with
	 * @param knownBadTarget true when strategy B's byte-for-byte readback already proved the target's
	 *                       content wrong — see classifyFilesystemOutcome for the invariants this enforces
	 * @param wasOverwrite   false when the save was classified NEW-FILE at collision-probe time — the
	 *                       filesystem classifier's completion rename then never replaces a file that
	 *                       appeared after classification (see classifyFilesystemOutcome)
	 * @return empty on clean replace, or a VerifyFailure carrying the dialog text for the detected case
	 */
	private Optional<VerifyFailure> classifyVerifyOutcome(Uri placeholderUri, String requestedName,
		int expectedLength, boolean knownBadTarget, boolean wasOverwrite)
	{
		File placeholder = safFiles.fileFromSafUri(placeholderUri).orElse(null);
		if (placeholder != null)
		{
			return classifyFilesystemOutcome(placeholder, requestedName, expectedLength, knownBadTarget,
				wasOverwrite);
		}
		return classifySafFallbackOutcome(placeholderUri, requestedName);
	}

	/**
	 * Dispose an aborted in-flight temp: best-effort delete plus its journal-entry removal. The three
	 * replaceViaFileIo failure paths route here so the journal can't hold an entry for a temp that was already
	 * cleaned in-process.
	 *
	 * @param tempFile journaled temp sibling to delete
	 * @param context  short label naming the cleanup path, forwarded to deleteQuietly's warn
	 */
	private void discardInflightTemp(File tempFile, String context)
	{
		SaveTempFiles.deleteQuietly(tempFile, TAG, context);
		SaveController.unjournalInflightTemp(host.getActivity(), tempFile.getParentFile(), tempFile.getName());
	}

	/**
	 * UI-thread body of showReplaceFailureDialog: shows buildReplaceFailureDialog's dialog through the guarded
	 * transient-dialog ceremony (destroyed-Activity skip + BadTokenException-tolerant show).
	 *
	 * @param title   AlertDialog title
	 * @param message AlertDialog body text
	 */
	private void postReplaceFailureDialog(String title, String message)
	{
		EditorHost.showTransientGuarded(host, TAG, "replace-failure dialog", () -> {}, () -> {},
			() -> buildReplaceFailureDialog(title, message));
	}

	/**
	 * Replace via plain File I/O. Streams the verified payload directly to the target path.
	 *
	 * Write-verify-swap shape: bytes land in a temp sibling first, temp is length-verified, then an atomic rename
	 * swaps it onto the target. A direct FileOutputStream(target) would truncate the target the moment the stream
	 * opens — a mid-write failure would then leave the user's original corrupt or zero-length before the SAF
	 * fallback paths even run. The temp-first flow preserves the original on any failure: a write/verify error
	 * cleans up the temp and returns false, letting strategies B/C try their luck against the untouched target.
	 *
	 * Why the payload comes from the export pipeline's own copy (in-heap bytes or the local encode tempfile in
	 * cacheDir), not the placeholder: reading the placeholder through FileInputStream could return stale or zero
	 * bytes on FUSE-backed storage whose view hasn't caught up with the SAF write. The pipeline's copy was already
	 * verified against the placeholder, and cacheDir isn't FUSE-backed. Tempfile-mode payloads stream through
	 * ExportPipeline.writePayloadToStream in 64 KB chunks, so a 400-600 MB PNG never materialises as a byte[] here.
	 *
	 * Placeholder deletion is best-effort after a successful swap; a leftover is caught and reported by
	 * verifyReplace.
	 *
	 * @param placeholderUri SAF placeholder URI whose resolved path anchors the target's directory
	 * @param requestedName  the user's requested target filename in that directory
	 * @param wasOverwrite   false when the save was classified NEW-FILE at collision-probe time — the
	 *                       pre-move probe (promoteTempOntoTarget) then aborts the promotion when a file
	 *                       appeared at the target name; true preserves replace semantics
	 * @param encoded        verified exported payload (bytes-mode or tempfile-mode)
	 * @return WRITTEN_CLEAN when the target holds the payload via the atomic swap and the placeholder is
	 *         gone; WRITTEN_PLACEHOLDER_REMAINS when the target was written but the placeholder delete
	 *         failed; ABORTED_TARGET_APPEARED when a NEW-FILE save refused to replace a file that appeared
	 *         at the target name (temp discarded, placeholder kept — the caller must skip the SAF
	 *         strategies and let verifyReplace surface the disk state); NOT_WRITTEN on any failure (target
	 *         untouched — strategies B/C run next)
	 */
	private FileIoOutcome replaceViaFileIo(Uri placeholderUri, String requestedName, boolean wasOverwrite,
		ExportResult encoded)
	{
		File placeholder = safFiles.fileFromSafUri(placeholderUri).orElse(null);
		if (placeholder == null)
		{
			return FileIoOutcome.NOT_WRITTEN;
		}
		File parent = placeholder.getParentFile();
		if (parent == null)
		{
			return FileIoOutcome.NOT_WRITTEN;
		}
		File target = new File(parent, requestedName);
		long expected = encoded.size();
		File tempFile = new File(parent, SaveTempFiles.tempName(requestedName));
		// Journal the temp's path BEFORE creating the file: a hard kill mid-write strands the temp in
		// `parent`, which need not be the folder the startup sweep's last-save delete pass visits. A failed
		// journal commit never aborts the save (journalInflightTemp logs it) — every non-kill path below
		// disposes the temp in-process, so the journal only covers the kill window.
		SaveController.journalInflightTemp(host.getActivity(), parent, tempFile.getName());
		try (FileOutputStream out = new FileOutputStream(tempFile))
		{
			ExportPipeline.writePayloadToStream(encoded, out);
			out.getFD().sync();
		}
		catch (Exception e)
		{
			Log.w(TAG, "replaceViaFileIo temp write to " + tempFile + " failed: " + e.getMessage());
			discardInflightTemp(tempFile, "replaceViaFileIo failed write");
			return FileIoOutcome.NOT_WRITTEN;
		}
		long written = tempFile.length();
		if (written != expected)
		{
			Log.w(TAG, "replaceViaFileIo: temp length " + written + " != expected "
				+ expected + " — write didn't land; target untouched");
			discardInflightTemp(tempFile, "replaceViaFileIo short write");
			return FileIoOutcome.NOT_WRITTEN;
		}
		// Swap. On overwrite saves Files.move with ATOMIC_MOVE + REPLACE_EXISTING swaps the temp onto the
		// target in one filesystem operation — if the rename fails, the target is still untouched; if the
		// filesystem can't do an atomic move (exotic FS on some devices), bail out rather than fall back to
		// delete-then-rename: that fallback would reintroduce the truncate-then-lose-original vulnerability
		// we're fixing. Strategies B/C run next on the still-intact target. NEW-FILE saves route through
		// promoteTempOntoTarget's two existence gates instead (probe, then a no-REPLACE_EXISTING move): a
		// hit at either aborts the whole promotion — the SAF strategies would clobber the appeared file
		// too, so the caller skips them and verifyReplace names what is on disk.
		try
		{
			if (!promoteTempOntoTarget(tempFile, target, wasOverwrite))
			{
				Log.w(TAG, "replaceViaFileIo: " + target
					+ " appeared during a new-file save — aborting promotion");
				discardInflightTemp(tempFile, "replaceViaFileIo new-file collision");
				return FileIoOutcome.ABORTED_TARGET_APPEARED;
			}
		}
		catch (Exception e)
		{
			Log.w(TAG, "replaceViaFileIo atomic swap failed: " + e.getMessage()
				+ " — target preserved, falling through to SAF strategies");
			discardInflightTemp(tempFile, "replaceViaFileIo failed swap");
			return FileIoOutcome.NOT_WRITTEN;
		}
		// The move disposed the temp name — drop its journal entry.
		SaveController.unjournalInflightTemp(host.getActivity(), parent, tempFile.getName());
		// Fsync the parent directory so the rename's directory entry survives a crash / power loss between
		// move-success and report-success. The temp file's bytes were already fsync'd before the rename, but
		// ATOMIC_MOVE only guarantees the inode swap is atomic — not that the containing directory's entry has
		// hit disk. Without this, a crash window between move and caller acknowledgement could leave the user
		// seeing a "Replaced" toast against a target whose new name hasn't been persisted. Mirrors
		// ExportPipeline.tryDirectAtomicWrite's parent-fsync so both direct-write paths have the same
		// durability profile. Best-effort: a parent-fsync failure is rare on a normal filesystem and the bytes
		// have already landed in the page cache, so we log and continue rather than fail the save. `parent` is
		// the target's own directory since the rename is in-folder.
		try (FileChannel parentChannel = FileChannel.open(parent.toPath(), StandardOpenOption.READ))
		{
			parentChannel.force(true);
		}
		catch (Exception e)
		{
			Log.w(TAG, "replaceViaFileIo parent fsync failed for " + parent
				+ ": " + e.getMessage());
		}
		boolean placeholderGone = !placeholder.exists() || placeholder.delete();
		if (!placeholderGone)
		{
			// Reported as WRITTEN_PLACEHOLDER_REMAINS below so the caller routes through verifyReplace,
			// whose duplicate-file diagnosis shows the leftover to the user instead of a misleading
			// "Replaced X" toast that ignores the stray placeholder.
			Log.w(TAG, "replaceViaFileIo: target written but couldn't delete placeholder " + placeholder);
		}
		// Force mtime update even when the temp's bytes matched the prior target bytes. Samsung's FUSE-backed
		// storage has been observed to skip mtime refresh on dedup-detected content-identical moves — leaving
		// userspace observers convinced the save didn't run.
		if (!target.setLastModified(System.currentTimeMillis()))
		{
			Log.w(TAG, "setLastModified failed on " + target + " — mtime may show stale");
		}
		// Direct FileOutputStream writes bypass MediaStore, so its cached metadata (thumbnails, date-modified
		// used by Gallery / Files / Photos apps) doesn't refresh on its own for same-path overwrites. Trigger a
		// scan so the new content appears immediately in those apps — without this, the on-disk file is correct
		// but every MediaStore consumer keeps showing the pre-overwrite thumbnail and looks like the save
		// didn't happen.
		String[] pathsToScan = placeholderGone
			? new String[] { target.getAbsolutePath() }
			: new String[] { target.getAbsolutePath(), placeholder.getAbsolutePath() };
		// Pass the application context (not the Activity) — scanFile holds its Context for the lifetime of the
		// MediaScannerConnection. Using the Activity would keep a destroyed Activity alive when a save
		// coincides with a config change, slowly leaking memory across many saves. The scan itself is a
		// fire-and-forget MediaStore rebuild; it doesn't need Activity-scoped state.
		MediaScannerConnection.scanFile(host.getActivity().getApplicationContext(), pathsToScan, null, null);
		return placeholderGone ? FileIoOutcome.WRITTEN_CLEAN : FileIoOutcome.WRITTEN_PLACEHOLDER_REMAINS;
	}

	/**
	 * Post an AlertDialog to the UI thread describing a failed/partial Replace. Unlike a Toast, this has no
	 * line-length cap and stays on screen until dismissed. Offers a one-tap link to the "All files access" Settings
	 * screen when the app doesn't hold that permission.
	 *
	 * @param title   AlertDialog title
	 * @param message AlertDialog body text
	 */
	private void showReplaceFailureDialog(String title, String message)
	{
		host.runOnUiThread(() -> postReplaceFailureDialog(title, message));
	}

	/**
	 * Best-effort rename of a SAF document. Providers are free to return a different URI than the input — some
	 * rehash document IDs on rename, some relocate under a new authority — so the caller MUST use the returned URI
	 * for any subsequent operation on the document rather than re-using the old one, or `verifyReplace`'s follow-up
	 * query hits a stale ID and reports the save as unverified.
	 *
	 * When `DocumentsContract.renameDocument` itself returns null, the Android docs say that means failure — but
	 * some legacy providers return null even on success. To disambiguate, we query the input URI's current display
	 * name: if it already matches `newName`, a legacy success happened and we return the input URI. Otherwise we
	 * treat null as genuine failure so the caller's fallback path (delete-then-retry for strict providers that
	 * rejected the rename-to-existing collision) can kick in. Without this disambiguation, treating every null as
	 * success would skip the fallback entirely and leave Replace permanently stuck on strict providers.
	 *
	 * @param uri     SAF document to rename
	 * @param newName display name to rename the document to
	 * @return the renamed document's URI on success (may differ from the input); empty on any failure
	 *         (collision with an existing name, provider doesn't support rename, auth dropped)
	 */
	private Optional<Uri> tryRename(Uri uri, String newName)
	{
		try
		{
			Uri renamedUri = DocumentsContract.renameDocument(
				host.getActivity().getContentResolver(), uri, newName);
			if (renamedUri != null)
			{
				return Optional.of(renamedUri);
			}
			String currentName = safFiles.getDisplayName(uri).orElse(null);
			if (currentName != null && currentName.equalsIgnoreCase(newName))
			{
				// Legacy "null-on-success" provider — the document really is renamed.
				return Optional.of(uri);
			}
			Log.w(TAG, "renameDocument(" + newName + ") returned null and display name "
				+ "is \"" + currentName + "\" — treating as failure");
			return Optional.empty();
		}
		catch (Exception e)
		{
			Log.w(TAG, "renameDocument(" + newName + ") failed: " + e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Check disk after a Replace attempt. A clean end state is target present at requestedName with the expected
	 * length AND placeholder absent — including the recoveries classifyFilesystemOutcome completes itself (verified
	 * placeholder renamed onto a free target name, redundant placeholder deleted next to a verified target).
	 * Anything else fires a failure dialog (not a toast — toasts get truncated on Android 11+) describing what's on
	 * disk and, when applicable, offers a direct link to the "All files access" Settings screen. Runs on the bg
	 * thread; dialog is posted to UI thread.
	 *
	 * `placeholderUri` may have already been rewritten to the renamed-to-target URI by Strategy C
	 * (DocumentsContract.renameDocument relocates the document onto the target path). When that happens, the URI's
	 * resolved File has the same name as the target, meaning placeholder == target on disk. A naive existence check
	 * would see "placeholder exists AND target exists" and trigger the "two files" branch, falsely failing a clean
	 * Strategy C success. The same-name short-circuit at the top handles that case.
	 *
	 * @param placeholderUri SAF URI of the just-written placeholder (possibly renamed onto the target)
	 * @param requestedName  the user's requested target filename
	 * @param expectedLength byte count the placeholder was written with — guards against "file exists but
	 *                       empty" (an existence-only check was letting silent-zero-byte writes register
	 *                       as success)
	 * @param knownBadTarget true when strategy B's byte-for-byte readback already proved the target's
	 *                       content wrong — the classifier then refuses every length-only clean return
	 *                       against that target and deletes neither file
	 * @param wasOverwrite   false when the save was classified NEW-FILE at collision-probe time — the
	 *                       classifier's completion rename then never replaces a file that appeared at the
	 *                       target name after classification
	 * @return true when the end state is clean; false after showing the failure dialog
	 */
	private boolean verifyReplace(Uri placeholderUri, String requestedName, int expectedLength,
		boolean knownBadTarget, boolean wasOverwrite)
	{
		Optional<VerifyFailure> outcome = classifyVerifyOutcome(placeholderUri, requestedName,
			expectedLength, knownBadTarget, wasOverwrite);
		if (outcome.isEmpty())
		{
			return true; // clean replace
		}
		VerifyFailure failure = outcome.orElseThrow();
		if (failure.keptHiddenName() != null)
		{
			// The dialog is about to promise this hidden recovery was KEPT. Journal it folder-scoped so
			// the startup sweep promotes it to a visible name instead of deleting it once it passes the
			// stale age guard — otherwise the app would silently reclaim a file it told the user it
			// kept. The placeholder's resolved parent is the folder the file actually sits in; opaque
			// providers resolve no path, and journalKeptRecovery then falls back to the persisted
			// last-save folder. When the journal write fails (no resolvable folder, commit failure) the
			// sweep WILL treat the file as an unjournaled stale temp — degrade the promise so the
			// wording stays honest about the one-hour cleanup window.
			File keptFolder = safFiles.fileFromSafUri(placeholderUri)
				.map(File::getParentFile).orElse(null);
			boolean journaled = SaveController.journalKeptRecovery(host.getActivity(), keptFolder,
				failure.keptHiddenName());
			failure = degradeKeptPromiseWhenUnjournaled(failure, journaled);
		}
		Log.w(TAG, "verifyReplace: " + failure.title() + " — " + failure.message());
		showReplaceFailureDialog(failure.title(), failure.message());
		return false;
	}

	/**
	 * Bg-thread callback fired once ExportPipeline has written the placeholder. Owns the three-strategy ladder:
	 *   A. File I/O — writes the payload directly to the target (avoids re-reading the placeholder through
	 *      FUSE/MediaStore layers that may lag), verifies on-disk length, and on success SKIPS the SAF paths
	 *      (running them on the already-correct target could delete it). A NEW-FILE save that finds a file
	 *      at the target name immediately before the atomic swap ABORTS the promotion instead — the SAF
	 *      paths are skipped too (they would clobber the appeared file) and verifyReplace names the disk
	 *      state.
	 *   B. SAF direct overwrite — verifies the colliding target byte-for-byte against the payload before
	 *      deleting the placeholder; on verify failure leaves the placeholder so the user keeps their save
	 *      and records the target as proven-bad (knownBadTarget) so verifyReplace can never reclassify it
	 *      as success off its length alone (placeholder promoted to a visible name before any dialog
	 *      claims it).
	 *   C. SAF rename-with-fallback — tries rename first, then delete-then-rename only if the provider rejects
	 *      rename-to-existing AND classifyRenameFallback's fail-closed ladder finds positive evidence the
	 *      placeholder still exists under its own name (an ambiguous rename outcome never deletes — after a
	 *      silent null-on-success rename the colliding URI IS the just-renamed export). The destructive
	 *      fallback journals a temp-shaped placeholder as a kept recovery BEFORE deleting the colliding
	 *      original, and the delete runs only under a durable journal entry (journalThenDeleteColliding —
	 *      a failed commit skips the delete and the retry rename fails into verifyReplace's two-files
	 *      outcome) — a hard kill between the delete and the retry rename leaves the hidden placeholder as
	 *      the user's only copy, and unjournaled the startup sweep would delete it once stale; a verified
	 *      clean outcome un-journals it again.
	 * On a verified end state fires the toast ("Replaced"/"Saved" per wasOverwrite); verifyReplace shows a
	 * failure dialog for unclean states.
	 *
	 * The payload is consumed in whatever mode ExportResult carries it: bytes-mode writes / verifies from the
	 * in-heap array, tempfile-mode streams from the retained encode tempfile in
	 * JpegMetadataInjector.STREAM_CHUNK_SIZE chunks (ExportPipeline.writePayloadToStream for strategy A's temp
	 * write, ExportPipeline.streamCompareUriToFile for strategy B's readback) — the 400-600 MB PNG payload is never
	 * materialised as a byte[] on this path. runExportBg keeps the tempfile alive until this callback returns.
	 *
	 * @param newUri        SAF placeholder URI ExportPipeline wrote the payload to
	 * @param requestedName the user's requested filename (the colliding target's display name)
	 * @param wasOverwrite  true when the colliding target held real prior content (toast "Replaced"); false
	 *                      when Replace was taken purely for crash-safety (toast "Saved") — the promotion
	 *                      probes then never replace a file that appeared at the target name after
	 *                      classification (strategy A aborts, the classifier's completion rename refuses)
	 * @param verifyUriBox  single-element box mutated to the post-rename URI when strategy C succeeds
	 * @param encoded       the verified exported payload (bytes-mode or tempfile-mode)
	 */
	@WorkerThread
	private void writeReplacementPayload(Uri newUri, String requestedName, boolean wasOverwrite,
		Uri[] verifyUriBox, ExportResult encoded)
	{
		long expected = encoded.size();
		// The placeholder's identity, resolved BEFORE any strategy runs: on a verified clean outcome the
		// placeholder has left its hidden temp name (deleted, or renamed onto its final name), which resolves
		// its in-flight temp journal entry — and resolving the URI after disposal fails (fileFromSafUri opens
		// the URI). Null for opaque providers and for Case B's visible "(N)" placeholders; unresolved entries
		// self-heal at the next sweep (file gone from its journaled path — entry dropped).
		File inflightTempAtEntry = safFiles.fileFromSafUri(newUri)
			.filter(file -> SaveTempFiles.isTempArtifact(file.getName())).orElse(null);
		FileIoOutcome fileIoOutcome = replaceViaFileIo(newUri, requestedName, wasOverwrite, encoded);
		// Set to true when strategy B's byte-for-byte readback has already confirmed the colliding target holds
		// the exported bytes. verifyReplace's placeholder-URI-only fallback would otherwise falsely report
		// "Couldn't verify replace" for providers where fileFromSafUri returns empty (non-primary-storage /
		// opaque-ID providers) — the placeholder was deleted by then, so getDisplayName(newUri) also comes
		// back empty, and the filesystem path can't be walked to find the colliding target.
		boolean collidingSafVerified = false;
		// Set to true when strategy B's readback PROVED the colliding target's content wrong
		// (copyUriContents reported success but the byte-for-byte readback disagreed). Threaded into
		// verifyReplace so the length-only disk-state checks can never reclassify the proven-bad target as
		// success — same-length stale content would otherwise pass the length gate, delete the placeholder
		// (the only good copy), and toast "Replaced" over wrong bytes.
		boolean knownBadTarget = false;
		// Set when strategy C's fallback journaled the temp-shaped placeholder before deleting the colliding
		// original (the delete-to-retry-rename crash window). A verified clean outcome un-journals the entry
		// so the journal doesn't accumulate resolved entries; unclean outcomes leave it for the sweep's own
		// entry lifecycle (vanish-drop / promote).
		File preDeleteKeptFolder = null;
		String preDeleteKeptName = null;

		if (fileIoOutcome == FileIoOutcome.NOT_WRITTEN)
		{
			// File I/O couldn't write the target — try SAF paths.
			Uri colliding = safFiles.deriveSiblingUri(newUri, requestedName).orElse(null);
			// B. SAF direct overwrite. Verify the colliding target actually holds our bytes before deleting
			// the verified placeholder — copyUriContents returning true only confirms the stream didn't
			// throw, and some providers lie about close success while truncating or partial-writing. Size
			// alone isn't enough: a provider that leaves same-length stale content on a failed overwrite
			// would pass a size check yet serve the wrong file. Full byte-for-byte readback of `colliding`
			// is the ground-truth check; only on matching content do we delete the placeholder. If
			// verification fails, leave the placeholder in place so the user still has their verified save
			// at the auto-suffixed name — verifyReplace's "two files" branch then surfaces the situation in
			// a dialog.
			if (colliding != null && safFiles.copyUriContents(newUri, colliding))
			{
				long verifiedBytes = encoded.bytes() != null
					? safFiles.readbackByteCount(colliding, encoded.bytes())
					: exportPipeline.streamCompareUriToFile(colliding, encoded.tempfile(),
						expected);
				if (verifiedBytes == expected)
				{
					// tryDeleteSafDocument is best-effort. Only skip verifyReplace when the
					// provider explicitly confirmed placeholder deletion — otherwise we'd toast
					// "Replaced" while both files linger on disk. verifyReplace's "two files"
					// branch catches that when we let it run.
					if (safFiles.tryDeleteSafDocument(newUri))
					{
						collidingSafVerified = true;
					}
					else
					{
						Log.w(TAG, "SAF overwrite verified but placeholder delete "
							+ "unconfirmed — deferring to verifyReplace");
					}
				}
				else
				{
					knownBadTarget = true;
					Log.w(TAG, "SAF overwrite reported success but readback verified "
						+ verifiedBytes + " of " + expected + " bytes on colliding"
						+ " — keeping placeholder as verified backup");
				}
			}
			else
			{
				// C. SAF rename-with-fallback. Try the rename FIRST — providers that allow
				// rename-to-existing (overwrite semantics) finish the replace in one step without
				// destroying the original document. If that fails (strict providers reject the name
				// collision), fall back to the destructive delete-then-rename path. Rename-first
				// preserves the original when both attempts fail: user still has the colliding original
				// on disk AND their verified save at the auto-suffixed name, and verifyReplace's "two
				// files" branch surfaces the situation. Rename-first (not delete-first) so a failed
				// rename never destroys the original before the swap is known to succeed. NO final
				// cleanup of `colliding` after success: `colliding` was derived from requestedName as a
				// sibling path URI, and after a successful rename the placeholder lives AT that path.
				// For path-addressed providers (the common case where deriveSiblingUri works at all),
				// `colliding` and the renamed-in-place target are now the same document — deleting
				// `colliding` would delete the just-saved file. The overwrite branch already
				// consumed/replaced the original, and the fallback branch already deleted it explicitly
				// before retrying the rename; no post-success cleanup needed.
				Uri renamedUri = tryRename(newUri, requestedName).orElse(null);
				if (renamedUri == null && colliding != null)
				{
					// classifyRenameFallback owns the fail-closed ladder: on legacy providers
					// that return null-on-success AND then reject the follow-up display-name
					// query, tryRename comes back empty even though the placeholder already
					// moved onto `colliding`'s path — deleting `colliding` here would destroy
					// the just-saved file. Evidence gathered for the ladder: the filesystem
					// probe when a path resolves, else one fresh display-name query.
					File placeholderFile = safFiles.fileFromSafUri(newUri).orElse(null);
					String probedName = placeholderFile == null
						? safFiles.getDisplayName(newUri).orElse(null) : null;
					RenameFallbackDecision decision = classifyRenameFallback(
						placeholderFile != null,
						placeholderFile != null && placeholderFile.exists(), probedName,
						requestedName);
					if (decision == RenameFallbackDecision.SILENT_SUCCESS)
					{
						// Filesystem evidence means the placeholder moved onto `colliding`'s
						// path; display-name evidence means newUri already answers to the
						// requested name and stays the live document.
						Log.w(TAG, "rename returned null but the placeholder verifiably left"
							+ " its own name — treating as silent success, skipping retry");
						renamedUri = placeholderFile != null ? colliding : newUri;
					}
					else if (decision == RenameFallbackDecision.FAIL_CLOSED)
					{
						// Neither probe can say whether the placeholder still exists, and
						// after a silent rename `colliding` would BE the export. Leave
						// renamedUri null so verifyReplace surfaces the two-files /
						// placeholder outcome instead of risking the just-renamed file.
						Log.w(TAG, "rename returned null and no probe is available — "
							+ "failing closed, not deleting " + colliding);
					}
					else
					{
						// DELETE_THEN_RETRY: positive evidence the placeholder still sits
						// under its own name — the provider really rejected the collision.
						// Defensive directory guard: SaveController's merged-save / rename
						// callbacks already reject directory targets before the Replace flow
						// dispatches here, but providers could in principle hand back a
						// directory document for the colliding URI too. Refuse to delete
						// directories — leave renamedUri null so verifyReplace surfaces the
						// failure rather than destroying the folder.
						File collidingFile = safFiles.fileFromSafUri(colliding).orElse(null);
						if (collidingFile != null && collidingFile.isDirectory())
						{
							Log.w(TAG, "refusing to delete directory at " + colliding);
						}
						else
						{
							// Journal the temp-shaped placeholder BEFORE destroying the
							// colliding original: between the delete and the retry rename
							// it is the user's only copy, and a hard kill in that window
							// would strand it hidden and unjournaled — the startup sweep
							// would then delete it once stale. A failed journal commit
							// skips the delete (fail closed): the retry rename below then
							// fails against the still-existing original and verifyReplace
							// surfaces the two-files outcome. Verified clean outcomes
							// un-journal the entry again below.
							final File placeholderFolder = placeholderFile != null
								? placeholderFile.getParentFile() : null;
							final String placeholderName = placeholderFile != null
								? placeholderFile.getName() : probedName;
							if (journalThenDeleteColliding(placeholderName,
								() -> SaveController.journalKeptRecovery(
									host.getActivity(), placeholderFolder,
									placeholderName),
								() -> safFiles.tryDeleteSafDocument(colliding)))
							{
								preDeleteKeptFolder = placeholderFolder;
								preDeleteKeptName = placeholderName;
							}
							renamedUri = tryRename(newUri, requestedName).orElse(null);
						}
					}
				}
				if (renamedUri != null)
				{
					verifyUriBox[0] = renamedUri;
				}
			}
		}

		// Verify + announce. verifyReplace shows a failure dialog internally when the end state isn't clean; on
		// clean replace we issue the one and only "Replaced" toast (doExport's "Saved" toast was suppressed for
		// this exact reason). The expected length is passed so existence + zero-byte files aren't mistaken for
		// success. Two short-circuits skip the placeholder-based verifyReplace:
		//   - Strategy A clean success (`fileIoOutcome == WRITTEN_CLEAN`): replaceViaFileIo verified the
		//     target's on-disk length matches the payload size AND deleted the placeholder. Re-running
		//     verifyReplace would call fileFromSafUri(placeholderUri) which now resolves via /proc/self/fd
		//     readlink (resolveViaProcFd) — that opens the URI, which fails with ENOENT because the
		//     placeholder was just deleted, classifyVerifyOutcome falls through to its SAF-fallback path,
		//     and the user sees a false "Couldn't verify replace" dialog on a Replace that actually
		//     succeeded.
		//   - Strategy B success (`collidingSafVerified`): the colliding URI was byte-for-byte verified
		//     against the payload, and the placeholder was deleted — same logic as Strategy A.
		// WRITTEN_PLACEHOLDER_REMAINS (Strategy A wrote the target but couldn't delete the placeholder)
		// DELIBERATELY falls through to verifyReplace so its duplicate-file diagnosis surfaces a failure
		// dialog the user can act on (delete the stray placeholder themselves). ABORTED_TARGET_APPEARED
		// (a NEW-FILE save refused to replace a file that appeared at the target name) skipped the SAF
		// strategies above and also falls through — verifyReplace's two-files wording then names the
		// appeared file and the kept placeholder.
		// The int cast can't truncate: ExportResult's compact constructor rejects tempfile payloads
		// over Integer.MAX_VALUE, and bytes-mode is inherently byte[]-bounded.
		boolean verified = fileIoOutcome == FileIoOutcome.WRITTEN_CLEAN || collidingSafVerified
			|| verifyReplace(verifyUriBox[0], requestedName, (int) expected, knownBadTarget, wasOverwrite);
		if (verified)
		{
			if (preDeleteKeptName != null)
			{
				// Verified clean promotion: the placeholder verifiably left its hidden temp name, so
				// the pre-delete journal entry is resolved — drop it now rather than leave it for the
				// sweep's vanish pass to garbage-collect at some later launch.
				SaveController.unjournalKeptRecovery(host.getActivity(), preDeleteKeptFolder,
					preDeleteKeptName);
			}
			if (inflightTempAtEntry != null)
			{
				// Same resolution for the in-flight temp journal: a verified clean outcome means the
				// placeholder no longer sits under its hidden temp name.
				SaveController.unjournalInflightTemp(host.getActivity(),
					inflightTempAtEntry.getParentFile(), inflightTempAtEntry.getName());
			}
			// "Replaced X" only when we actually overwrote an existing file. When the caller routed through
			// Replace purely for crash-safety on an unknown-size target (SaveController case A, priorSize
			// == -1), we can't honestly claim a replace happened; say "Saved" instead. Keeps the toast
			// truthful when the Replace path ran over what turned out to be a fresh document.
			String verb = wasOverwrite ? "Replaced " : "Saved ";
			final String announce = verb + requestedName;
			host.runOnUiThread(() -> host.toastIfAlive(announce, Toast.LENGTH_SHORT));
		}
	}
}
