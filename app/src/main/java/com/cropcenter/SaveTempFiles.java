package com.cropcenter;

import android.util.Log;

import androidx.annotation.WorkerThread;

import java.io.File;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Single chokepoint for the crash-safe save pipeline's temp-artifact naming and reclamation. Three writers create
 * hidden siblings in the user's save directory — ExportPipeline.tryDirectAtomicWrite and
 * ReplaceStrategy.replaceViaFileIo (write-then-atomic-rename temps) and SaveController.routeCrashSafeSave (the
 * crash-safe SAF placeholder) — and all of them MUST build the name through tempName so the startup sweep
 * (sweepStaleTempArtifacts, wired via SaveController.sweepStaleSaveTemps) can match what they wrote, and MUST journal
 * the temp's absolute path (SaveController.journalInflightTemp) before creating it so the sweep's delete reach covers
 * whatever folder the temp sits in, not just the persisted last-save folder. A hard process kill (OOM killer
 * mid-save, force-stop, battery pull) bypasses every in-process cleanup path, and a stranded temp is invisible to
 * Gallery and most file managers — without the sweep it leaks its full payload (400-600 MB for a 200 MP PNG)
 * permanently. Public solely so the view package's filename validator (FolderPickerDialog.isValidFilename) can
 * consult isReservedName — every other member stays package-private.
 */
public final class SaveTempFiles
{
	private static final String TAG = "SaveTempFiles";
	// Hidden-name marker shared by the builder and the matcher. The leading dot keeps in-flight temps out of
	// Gallery / MediaStore; the marker substring is what the sweep keys on.
	static final String TEMP_NAME_MARKER = ".cropcenter-tmp-";
	// Minimum age before the sweep deletes a temp artifact. A live save completes in seconds; one hour of slack
	// guarantees a concurrent in-flight save's temp is never swept out from under it.
	static final long STALE_TEMP_MIN_AGE_MS = 60L * 60L * 1000L;

	private SaveTempFiles() {}

	/**
	 * Reserved-namespace predicate for user-supplied filenames: true when the name sits anywhere in the
	 * crash-safe temp namespace — an exact isTempArtifact shape (current or legacy), the raw marker prefix, or
	 * any marker-substring shape one nonce field short of matching. The save dialogs reject these names
	 * outright: a user file inside the namespace would collide with an in-flight save's temp or become
	 * claimable by the sweep's matcher, so the whole marker substring is reserved rather than only the exact
	 * deletable shapes.
	 *
	 * @param name bare filename to test (no path); null returns false
	 * @return true when the name contains TEMP_NAME_MARKER and must be rejected as a user filename
	 */
	public static boolean isReservedName(String name)
	{
		return name != null && name.contains(TEMP_NAME_MARKER);
	}

	/**
	 * Best-effort delete-with-warn for an abandoned save-pipeline tempfile. Deletion failure isn't actionable
	 * beyond the log line (the temp-path journal + startup sweep are the backstops; deleteOnExit covers only the
	 * cacheDir encode temps, never the save-folder temps deleted here), so no status is returned; a
	 * non-existent file is a silent no-op.
	 *
	 * @param file    tempfile to delete
	 * @param tag     caller's log TAG so the warn attributes to the owning class
	 * @param context short label naming the cleanup path, included in the warn
	 */
	static void deleteQuietly(File file, String tag, String context)
	{
		if (file.exists() && !file.delete())
		{
			Log.w(tag, context + ": couldn't clean up temp file " + file);
		}
	}

	/**
	 * Matcher for names produced by tempName, parsed strictly by shape. Current shape: the marker prefix, a
	 * nanoTime digit run, a "-" separator, then a NON-EMPTY visible name (".cropcenter-tmp-<nanos>-<visibleName>"
	 * — a name ending at the separator matches nothing tempName builds and is consistent with visibleNameOf's
	 * empty return for it). Legacy shape from earlier builds (stale artifacts still sit on user devices): a
	 * hidden name carrying the marker mid-name with a digits-only tail (".<name>.cropcenter-tmp-<nanos>").
	 * Anything else does not match — including a hidden user file that merely contains the marker substring —
	 * because this predicate gates deletion in the user's save folder, so both nonce fields are validated rather
	 * than substring-matched.
	 *
	 * @param name bare filename to test (no path); null returns false
	 * @return true when name is a crash-safe save temp artifact (current or legacy shape)
	 */
	static boolean isTempArtifact(String name)
	{
		if (name == null)
		{
			return false;
		}
		if (name.startsWith(TEMP_NAME_MARKER))
		{
			// Current shape: marker + nanoTime digits + "-" + non-empty visible name.
			int digitsEnd = endOfDigitRun(name, TEMP_NAME_MARKER.length());
			return digitsEnd > TEMP_NAME_MARKER.length() && digitsEnd < name.length()
				&& name.charAt(digitsEnd) == '-' && digitsEnd + 1 < name.length();
		}
		// Legacy shape: hidden name + marker mid-name + digits-only tail.
		int markerStart = name.indexOf(TEMP_NAME_MARKER);
		if (name.startsWith(".") && markerStart > 0)
		{
			int tailStart = markerStart + TEMP_NAME_MARKER.length();
			return tailStart < name.length() && endOfDigitRun(name, tailStart) == name.length();
		}
		return false;
	}

	/**
	 * Delete stale temp artifacts and resolve both journals against their entries' OWN folders. Journal
	 * entries are absolute paths (SaveController.journalKeptRecovery / journalInflightTemp), so the sweep
	 * visits every journaled parent independently: an entry vanish-drops only when its own parent directory is
	 * present to verify against and the file is gone from it (a missing parent — unmounted volume — retains
	 * the entry, for either journal). A kept-recovery entry's file is NEVER deleted — it is a recovery a
	 * Replace failure dialog promised the user was kept — and once stale it is promoted via promoteKept
	 * wherever it sits; the entry drops on successful promotion, and a failed promotion retains both file and
	 * entry for the next launch's retry. An in-flight temp entry is the opposite lifecycle: its file — stale,
	 * a regular file, and matching isTempArtifact — is DELETED wherever it sits and the entry dropped (a fresh
	 * file is retained by the age guard, and a failed delete retains the entry for a retry). The kept journal
	 * takes explicit precedence: a path journaled in both sets (a placeholder that became a promised kept
	 * recovery) is never deletable through the temp pass, guarded by path and by bare filename. The
	 * unjournaled delete pass runs ONLY in lastSaveDir — reclaiming pre-journal legacy orphans — on regular
	 * files matching isTempArtifact, older than STALE_TEMP_MIN_AGE_MS, and not journaled as kept; user files
	 * never match the name predicate. The kept-name guards compare bare filenames as well as paths so two
	 * path spellings of the same mount can't defeat them (the nanoTime nonce in every temp name makes a
	 * cross-folder bare-name collision practically impossible, so the wider net costs nothing). Both sets are
	 * mutated in place; the caller persists the shrunken sets. Kept Context-free (the journal sets and the
	 * promotion callback are passed in by SaveController.sweepStaleSaveTemps, mirroring nowMs) so the class
	 * stays plain-JVM testable. Silent per-file failure (log + continue) mirrors
	 * UltraHdrCompat.sweepStaleCacheFiles.
	 *
	 * @param lastSaveDir       last-save directory whose unjournaled stale temps are deleted; null or
	 *                          non-directory skips that delete pass only — journal entries are still
	 *                          resolved against their own parents
	 * @param nowMs             current wall-clock millis the age guard compares each file's lastModified
	 *                          against
	 * @param keptRecoveryPaths mutable set of journaled kept-recovery absolute paths; entries are removed in
	 *                          place when resolved (promoted, or vanished from their own folder)
	 * @param promoteKept       promotion attempt for a stale journaled kept recovery; returns true when the
	 *                          file no longer sits under its hidden name (the entry is then dropped)
	 * @param inflightTempPaths mutable set of journaled in-flight temp absolute paths; entries are removed
	 *                          in place when resolved (deleted, vanished from their own folder, or
	 *                          never-deletable by shape)
	 * @return number of artifacts deleted, journaled and unjournaled combined (promotions are not counted)
	 */
	@WorkerThread
	static int sweepStaleTempArtifacts(File lastSaveDir, long nowMs, Set<String> keptRecoveryPaths,
		Predicate<File> promoteKept, Set<String> inflightTempPaths)
	{
		// Vanish check against each entry's OWN folder — never against lastSaveDir. Resolving against the
		// current sweep folder would drop entries for recoveries kept in previously-used save folders, and
		// the next sweep of THAT folder would then delete a file a dialog promised the user was kept.
		keptRecoveryPaths.removeIf(SaveTempFiles::journaledFileVanished);
		for (String keptPath : Set.copyOf(keptRecoveryPaths))
		{
			// Kept recovery — a dialog promised the user this file was kept. Promote once stale, in
			// whatever folder it sits in; drop the entry only once the file is out from under its
			// hidden name.
			File kept = new File(keptPath);
			if (!kept.isFile() || nowMs - kept.lastModified() < STALE_TEMP_MIN_AGE_MS)
			{
				continue;
			}
			if (promoteKept.test(kept))
			{
				keptRecoveryPaths.remove(keptPath);
			}
		}
		// Kept-name guard shared by the temp-journal pass and the lastSaveDir delete pass, computed AFTER
		// the promote pass so a just-promoted recovery's dropped entry no longer widens the guard.
		Set<String> keptNames = new HashSet<>();
		for (String keptPath : keptRecoveryPaths)
		{
			keptNames.add(new File(keptPath).getName());
		}
		int deleted = 0;
		inflightTempPaths.removeIf(SaveTempFiles::journaledFileVanished);
		for (String tempPath : Set.copyOf(inflightTempPaths))
		{
			File temp = new File(tempPath);
			// Kept-recovery precedence: a path journaled in both sets is a placeholder a failure
			// dialog later promised was KEPT — the kept lifecycle (promote, never delete) owns it,
			// and this entry resolves as vanished once the promotion lands.
			if (keptRecoveryPaths.contains(tempPath) || keptNames.contains(temp.getName()))
			{
				continue;
			}
			// A file the vanish check retained without a parent to verify against (unmounted volume),
			// or a non-regular-file oddity — nothing deletable here; keep the entry.
			if (!temp.isFile())
			{
				continue;
			}
			// Defensive shape guard: the journal only ever holds tempName-generated names, but this
			// pass gates deletion in the user's folders, so a non-matching name is never deleted —
			// and its shape can't change, so the entry would otherwise linger forever.
			if (!isTempArtifact(temp.getName()))
			{
				inflightTempPaths.remove(tempPath);
				continue;
			}
			if (nowMs - temp.lastModified() < STALE_TEMP_MIN_AGE_MS)
			{
				continue; // fresh — possibly a live save's in-flight temp
			}
			if (temp.delete())
			{
				inflightTempPaths.remove(tempPath);
				deleted++;
			}
			else
			{
				Log.w(TAG, "sweepStaleTempArtifacts: failed to delete journaled " + temp.getName());
			}
		}
		deleted += sweepUnjournaledLastSaveDir(lastSaveDir, nowMs, keptNames);
		if (deleted > 0)
		{
			Log.d(TAG, "sweepStaleTempArtifacts: reclaimed " + deleted + " stale save temp(s)");
		}
		return deleted;
	}

	/**
	 * Build the hidden temp name for a save-pipeline artifact standing in for visibleName. nanoTime (not millis) so
	 * two saves within the same millisecond can't collide on the temp path — rapid retaps surviving the savePending
	 * gate would otherwise build the identical name and the second writer would truncate the first writer's temp
	 * mid-flight.
	 *
	 * @param visibleName the user-facing filename the artifact will eventually land at
	 * @return hidden sibling name in the TEMP_NAME_MARKER + nanos + "-" + visibleName shape
	 */
	static String tempName(String visibleName)
	{
		return TEMP_NAME_MARKER + System.nanoTime() + "-" + visibleName;
	}

	/**
	 * Recover the visible filename a tempName-shaped artifact stands in for — the inverse of tempName. The
	 * sweep's promotion path (SaveController.promoteJournaledRecovery) uses it to derive the "stem (N).ext"
	 * candidate a journaled kept recovery should land at. Only the current marker-prefix shape is parseable; the
	 * legacy ".name.cropcenter-tmp-nanos" suffix shape carries its name in an ambiguous position and returns
	 * empty (journal entries are only ever written for current-shape names).
	 *
	 * @param tempName temp artifact name to parse; null tolerated
	 * @return the trailing visible name when tempName has the marker + nanos + "-" + name shape; empty
	 *         when the shape doesn't match (null, legacy suffix shape, user file, truncated name)
	 */
	static Optional<String> visibleNameOf(String tempName)
	{
		if (tempName == null || !tempName.startsWith(TEMP_NAME_MARKER))
		{
			return Optional.empty();
		}
		int nanosStart = TEMP_NAME_MARKER.length();
		int separator = tempName.indexOf('-', nanosStart);
		if (separator <= nanosStart || separator + 1 >= tempName.length())
		{
			return Optional.empty();
		}
		String nanos = tempName.substring(nanosStart, separator);
		if (!nanos.chars().allMatch(Character::isDigit))
		{
			return Optional.empty();
		}
		return Optional.of(tempName.substring(separator + 1));
	}

	/**
	 * End offset (exclusive) of the ASCII digit run starting at start. Bounds the nanoTime nonce fields both
	 * isTempArtifact shapes validate.
	 *
	 * @param name  filename being validated
	 * @param start inclusive offset the run begins at
	 * @return exclusive end of the digit run; equals start when name.charAt(start) is not a digit
	 */
	private static int endOfDigitRun(String name, int start)
	{
		int i = start;
		while (i < name.length() && name.charAt(i) >= '0' && name.charAt(i) <= '9')
		{
			i++;
		}
		return i;
	}

	/**
	 * Vanish predicate for a journal-entry path (kept recovery or in-flight temp): true only when the entry's
	 * own parent directory is present to verify against and the file is gone from it. A missing parent
	 * (unmounted volume, folder removed wholesale) retains the entry — nothing can be verified, and dropping a
	 * kept entry would leave the recovery unprotected if the folder comes back and is later swept as the
	 * last-save folder (a retained temp entry symmetrically keeps a remounted volume's stranded temp reclaimable).
	 *
	 * @param journaledPath absolute path of a journal entry
	 * @return true when the entry is resolved as vanished from its own folder and can be dropped
	 */
	private static boolean journaledFileVanished(String journaledPath)
	{
		File journaled = new File(journaledPath);
		File parent = journaled.getParentFile();
		return parent != null && parent.isDirectory() && !journaled.exists();
	}

	/**
	 * The unjournaled delete pass of sweepStaleTempArtifacts: reclaim pre-journal legacy orphans from the
	 * last-save directory — the one folder reachable without a journal entry. Deletes regular files matching
	 * isTempArtifact, older than STALE_TEMP_MIN_AGE_MS, and not carrying a journaled kept-recovery's bare
	 * name; already-journaled temps deleted by the temp-journal pass are simply no longer listed here.
	 *
	 * @param lastSaveDir last-save directory to reclaim; null or non-directory is a no-op
	 * @param nowMs       current wall-clock millis the age guard compares each file's lastModified against
	 * @param keptNames   bare filenames of the surviving kept-recovery entries — a matching name keeps a
	 *                    failed-promotion recovery out of this pass even when the journal spells its folder
	 *                    differently than lastSaveDir (symlinked mounts)
	 * @return number of artifacts deleted
	 */
	private static int sweepUnjournaledLastSaveDir(File lastSaveDir, long nowMs, Set<String> keptNames)
	{
		if (lastSaveDir == null || !lastSaveDir.isDirectory())
		{
			return 0;
		}
		File[] entries = lastSaveDir.listFiles((parent, name) -> isTempArtifact(name));
		if (entries == null)
		{
			return 0;
		}
		int deleted = 0;
		for (File entry : entries)
		{
			if (!entry.isFile() || nowMs - entry.lastModified() < STALE_TEMP_MIN_AGE_MS
				|| keptNames.contains(entry.getName()))
			{
				continue;
			}
			if (entry.delete())
			{
				deleted++;
			}
			else
			{
				Log.w(TAG, "sweepStaleTempArtifacts: failed to delete " + entry.getName());
			}
		}
		return deleted;
	}
}
