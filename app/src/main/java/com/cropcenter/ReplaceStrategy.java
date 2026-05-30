package com.cropcenter;

import android.app.AlertDialog;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;

import com.cropcenter.crop.ExportResult;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.StoragePermissionHelper;
import com.cropcenter.view.DialogStrings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Collision-replace policy: SAF ACTION_CREATE_DOCUMENT with a colliding filename is silently auto-renamed by the
 * framework; the user's "Replace" choice then needs the app to overwrite the original. Tries File-I/O first (fastest,
 * most reliable when MANAGE_EXTERNAL_STORAGE is granted), then SAF direct overwrite, then SAF delete-then-rename.
 * Verifies the end state and surfaces a failure dialog when disk doesn't match the intent.
 *
 * Lives downstream of the save flow — SaveController decides whether to invoke this. SEFT preservation is the
 * exporter's responsibility (see CropExporter.appendSeftFileToFile for the verbatim-re-append contract and the
 * why-we-don't-fabricate-fresh-SEFT rationale).
 */
final class ReplaceStrategy
{
	/**
	 * One failure outcome of verifyReplace — a (title, message) pair surfaced in a dialog. Per-case
	 * factory methods (truncated, twoFiles, etc.) own the wording so verifyReplace stays a clean
	 * router from "what's on disk" to a factory call. Package-private so
	 * ReplaceStrategyClassifierTest can distinguish branches by their dialog wording.
	 */
	record VerifyFailure(String title, String message) {}

	private static final String TAG = "ReplaceStrategy";
	// Smart-quoted "All files access" — the exact wording Android's Settings UI uses for the
	// MANAGE_EXTERNAL_STORAGE permission. Centralised here because 5 different verifyReplace failure messages
	// reference it; if Android renames the permission, this is the one place to change.
	private static final String ALL_FILES_ACCESS = "“All files access”";

	private final ExportPipeline exportPipeline;
	private final SafFileHelper safFiles;
	private final SaveHost host;
	private final StoragePermissionHelper permissions;
	// Side-effect output from replaceViaFileIo: set to true when the atomic-move target write
	// succeeded but the placeholder doc could not be deleted (rare; happens when SAF provider
	// holds an exclusive lock or returns false from delete). When true, the verify-skip
	// short-circuit must NOT fire because verifyReplace's duplicate-detection is the only path
	// that surfaces the "leftover placeholder" state to the user as a failure dialog. Reset to
	// false at the top of replaceViaFileIo so a prior save's value doesn't leak.
	private boolean fileIoPlaceholderRemains;

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
	 * (placeholder == target on disk) and the general two-files / one-missing fan-out. Deletes the
	 * corrupt target on truncation paths so the user isn't offered "Replace" on a bad file next save.
	 * Package-private + static so ReplaceStrategyClassifierTest can pin all six disk-state outcomes
	 * (clean, truncated, missingAfterRename, twoFiles, placeholderOnly, bothMissing). Mis-classification
	 * ships a corrupt file as "saved" or strands orphan auto-renames.
	 *
	 * @param placeholder    sibling placeholder file written by the crash-safe pipeline
	 * @param requestedName  filename the user typed in the picker
	 * @param expectedLength byte count the verify-step expects on disk for a successful write
	 * @return null when the outcome is clean; a populated VerifyFailure when the on-disk state is
	 *         missing, truncated, or split across two files
	 */
	static VerifyFailure classifyFilesystemOutcome(File placeholder, String requestedName, int expectedLength)
	{
		File parent = placeholder.getParentFile();
		File target = (parent != null) ? new File(parent, requestedName) : null;
		// Strategy C path: rename moved placeholder onto target's path. placeholder == target on disk.
		if (target != null && placeholder.getName().equals(target.getName()))
		{
			if (target.exists() && target.length() == expectedLength)
			{
				return null; // clean replace via Strategy C rename
			}
			if (target.exists())
			{
				long targetLen = target.length();
				deleteCorruptTarget(target);
				return truncated(requestedName, targetLen, expectedLength);
			}
			return missingAfterRename(requestedName);
		}
		boolean placeholderExists = placeholder.exists();
		boolean targetOk = target != null && target.exists() && target.length() == expectedLength;
		if (targetOk && !placeholderExists)
		{
			return null; // clean replace
		}
		boolean targetExists = target != null && target.exists();
		long targetLen = targetExists ? target.length() : -1;
		if (targetExists && !placeholderExists)
		{
			deleteCorruptTarget(target);
			return truncated(requestedName, targetLen, expectedLength);
		}
		if (placeholderExists && targetExists)
		{
			return twoFiles(requestedName, placeholder.getName());
		}
		if (placeholderExists)
		{
			return placeholderOnly(requestedName, placeholder.getName());
		}
		return bothMissing(requestedName, placeholder.getName());
	}

	/**
	 * Replace the colliding file in the user's chosen directory (NOT the opened file's directory).
	 *
	 * Write-first-then-swap: the new bytes go to the auto-renamed "(N)" SAF placeholder and are verified
	 * BEFORE we touch the original, so a failed encode/write leaves the original intact (worst case: a leftover
	 * placeholder). Then, in order of reliability:
	 *   A. File I/O (MANAGE_EXTERNAL_STORAGE) — bypasses SAF's inconsistent delete/rename semantics.
	 *   B. SAF direct overwrite — stream placeholder bytes into the colliding URI.
	 *   C. SAF delete-colliding + rename-placeholder — last resort.
	 *
	 * Ordering is critical: when File I/O succeeds it has already written the target, so the SAF paths'
	 * delete/rename calls could destroy it — File I/O short-circuits the rest, and SAF runs only when it
	 * couldn't touch the target. verifyReplace runs in both branches to catch partial states.
	 *
	 * @param newUri        SAF placeholder URI ("(N)" suffix) holding the freshly-written bytes
	 * @param requestedName the user-typed filename the placeholder should land at after the swap
	 * @param wasOverwrite  true on a confirmed-overwrite path (toast "Replaced X" vs "Saved X")
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
	 * Best-effort delete of a partial / wrong-length target file. Logs at WARN on failure but doesn't propagate —
	 * the caller is in the failure path already; failing to also clean up the corrupt file is recoverable (the user
	 * will see a stale file in the Files app at worst).
	 */
	private static void deleteCorruptTarget(File target)
	{
		if (!target.delete())
		{
			Log.w(TAG, "verifyReplace: failed to remove corrupt target " + target);
		}
	}

	private static VerifyFailure missingAfterRename(String requestedName)
	{
		return new VerifyFailure("Save may have failed",
			requestedName + " is not on disk after Replace. Check your save directory and try again.");
	}

	private static VerifyFailure placeholderOnly(String requestedName, String placeholderName)
	{
		return new VerifyFailure("Couldn't replace " + requestedName,
			"Your crop was saved as " + placeholderName + ". The original " + requestedName
				+ " is owned by a previous install of CropCenter and can't be replaced by this build."
				+ " Grant " + ALL_FILES_ACCESS + " and save again, or delete the original from the"
				+ " Files app.");
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
	 * Single factory for both truncated-write outcomes — the user-facing message and title are identical
	 * regardless of whether the placeholder lingers or got cleaned up. The two distinct call sites pin down
	 * different states of the swap-then-verify dance, but the failure dialog says the same thing in either
	 * case (re-save and consider granting MANAGE_EXTERNAL_STORAGE).
	 */
	private static VerifyFailure truncated(String requestedName, long actualLen, int expectedLen)
	{
		return new VerifyFailure("Replace produced an incomplete file",
			requestedName + " was " + actualLen + " bytes instead of the expected " + expectedLen
				+ " and has been removed. Re-save, and if it keeps happening, grant "
				+ ALL_FILES_ACCESS + " or move the save target to a folder you own.");
	}

	private static VerifyFailure twoFiles(String requestedName, String placeholderName)
	{
		return new VerifyFailure("Replace left two files",
			"Both " + requestedName + " and " + placeholderName + " now exist on disk. Grant "
				+ ALL_FILES_ACCESS + " so Replace can clean up automatically, or delete one manually"
				+ " from the Files app.");
	}

	/**
	 * SAF-only fallback when no filesystem path is available (cloud / SAF-only providers without
	 * MANAGE_EXTERNAL_STORAGE). Uses the placeholder URI's SAF display name to detect auto-rename.
	 */
	private VerifyFailure classifySafFallbackOutcome(Uri placeholderUri, String requestedName)
	{
		String finalName = safFiles.getDisplayName(placeholderUri);
		if (finalName != null && requestedName.equalsIgnoreCase(finalName))
		{
			return null; // clean replace per SAF
		}
		if (finalName != null)
		{
			return safAutoRenamed(requestedName, finalName);
		}
		return safUnverifiable(requestedName);
	}

	/**
	 * Classify the post-Replace disk state into a clean-success (null return) or a typed failure with pre-built
	 * dialog title + message. Side effect: deletes the corrupt target file when a truncation case is detected so
	 * the next Save attempt doesn't re-find the bad file. Filesystem checks run when MANAGE_EXTERNAL_STORAGE is
	 * granted (authoritative); falls back to a SAF display-name query otherwise.
	 *
	 * @param placeholderUri SAF URI of the just-written placeholder (may have been renamed onto target)
	 * @param requestedName  the user's requested target filename
	 * @param expectedLength the byte count the placeholder was written with
	 * @return null on clean replace, or a VerifyFailure carrying the dialog text for the detected case
	 */
	private VerifyFailure classifyVerifyOutcome(Uri placeholderUri, String requestedName, int expectedLength)
	{
		File placeholder = safFiles.fileFromSafUri(placeholderUri);
		if (placeholder != null)
		{
			return classifyFilesystemOutcome(placeholder, requestedName, expectedLength);
		}
		return classifySafFallbackOutcome(placeholderUri, requestedName);
	}

	/**
	 * UI-thread body of showReplaceFailureDialog. Skips the show when the Activity is destroyed (BadTokenException
	 * guard) and adds a "Grant access" neutral button when MANAGE_EXTERNAL_STORAGE isn't held — that's the
	 * permission most Replace failure modes need to resolve, so a one-tap shortcut into Settings is more useful
	 * than asking the user to navigate there manually.
	 *
	 * @param title   AlertDialog title
	 * @param message AlertDialog body text
	 */
	private void postReplaceFailureDialog(String title, String message)
	{
		if (host.isDestroyed())
		{
			return;
		}
		AlertDialog.Builder builder = new AlertDialog.Builder(host.getActivity())
			.setTitle(title)
			.setMessage(message)
			.setPositiveButton(DialogStrings.OK, null);
		if (!permissions.hasStoragePermission())
		{
			builder.setNeutralButton("Grant access",
				(dialog, which) -> permissions.openStoragePermissionSettings());
		}
		try
		{
			// BadTokenException guard: config-change races can land between the isDestroyed pre-check
			// and the actual show() call. The catch keeps the warning best-effort.
			// registerTransientDialog tracks the warning so a later image load / session can
			// dismiss it via dismissTransientDialogs() before the old Activity window leaks.
			AlertDialog dialog = builder.create();
			host.registerTransientDialog(dialog);
			dialog.show();
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "replace-failure dialog failed to show", e);
		}
	}

	/**
	 * Replace via plain java.io.File. Writes `data` directly to the target path.
	 *
	 * Write-verify-swap shape: bytes land in a temp sibling first, temp is length-verified, then an atomic rename
	 * swaps it onto the target. A direct FileOutputStream(target) would truncate the target the moment the stream
	 * opens — a mid-write failure would then leave the user's original corrupt or zero-length before the SAF
	 * fallback paths even run. The temp-first flow preserves the original on any failure: a write/verify error
	 * cleans up the temp and returns false, letting strategies B/C try their luck against the untouched target.
	 *
	 * Why the bytes come from memory, not the placeholder: reading the placeholder through FileInputStream could
	 * return stale or zero bytes on FUSE-backed storage whose view hasn't caught up with the SAF write. Using
	 * `data` (which ExportPipeline already verified byte-for-byte against the placeholder) bypasses that window.
	 *
	 * Placeholder deletion is best-effort after a successful swap; a leftover is caught and reported by
	 * verifyReplace.
	 */
	private boolean replaceViaFileIo(Uri placeholderUri, String requestedName, byte[] data)
	{
		// Reset the side-effect flag so a prior save's leftover state doesn't poison this call's
		// verify-skip decision.
		fileIoPlaceholderRemains = false;
		File placeholder = safFiles.fileFromSafUri(placeholderUri);
		if (placeholder == null)
		{
			return false;
		}
		File parent = placeholder.getParentFile();
		if (parent == null)
		{
			return false;
		}
		File target = new File(parent, requestedName);
		// nanoTime (not millis) for the temp suffix so two Replace attempts within the same millisecond
		// can't collide on the path — rapid retap surviving the savePending gate (or a test harness driving
		// the flow twice quickly) would build the identical tempFile, and the second FileOutputStream would
		// truncate the first writer's temp mid-flight. Mirrors ExportPipeline.tryDirectAtomicWrite's choice;
		// the two write paths now use a consistent uniqueness source.
		File tempFile = new File(parent, "." + requestedName + ".cropcenter-tmp-" + System.nanoTime());
		try (FileOutputStream out = new FileOutputStream(tempFile))
		{
			out.write(data);
			out.getFD().sync();
		}
		catch (Exception e)
		{
			Log.w(TAG, "replaceViaFileIo temp write to " + tempFile + " failed: " + e.getMessage());
			if (tempFile.exists() && !tempFile.delete())
			{
				Log.w(TAG, "couldn't clean up temp file " + tempFile);
			}
			return false;
		}
		long written = tempFile.length();
		if (written != data.length)
		{
			Log.w(TAG, "replaceViaFileIo: temp length " + written + " != expected "
				+ data.length + " — write didn't land; target untouched");
			tempFile.delete();
			return false;
		}
		// Atomic swap. Files.move with ATOMIC_MOVE + REPLACE_EXISTING swaps the temp onto the target in one
		// filesystem operation — if the rename fails, the target is still untouched. If the filesystem can't do
		// an atomic move (exotic FS on some devices), bail out rather than fall back to delete-then-rename:
		// that fallback would reintroduce the truncate-then-lose-original vulnerability we're fixing.
		// Strategies B/C run next on the still-intact target.
		try
		{
			Files.move(tempFile.toPath(), target.toPath(),
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (Exception e)
		{
			Log.w(TAG, "replaceViaFileIo atomic swap failed: " + e.getMessage()
				+ " — target preserved, falling through to SAF strategies");
			if (tempFile.exists() && !tempFile.delete())
			{
				Log.w(TAG, "couldn't clean up temp file " + tempFile);
			}
			return false;
		}
		// Fsync the parent directory so the rename's directory entry survives a crash / power loss
		// between move-success and report-success. The temp file's bytes were already fsync'd before
		// the rename, but ATOMIC_MOVE only guarantees the inode swap is atomic — not that the
		// containing directory's entry has hit disk. Without this, a crash window between move and
		// caller acknowledgement could leave the user seeing a "Replaced" toast against a target
		// whose new name hasn't been persisted. Mirrors ExportPipeline.tryDirectAtomicWrite's
		// parent-fsync so both direct-write paths have the same durability profile. Best-effort: a
		// parent-fsync failure is rare on a normal filesystem and the bytes have already landed in
		// the page cache, so we log and continue rather than fail the save. `parent` was bound
		// earlier in the method to placeholder.getParentFile() — same directory as the target
		// since the rename is in-folder.
		if (parent != null)
		{
			try (FileChannel parentChannel = FileChannel.open(parent.toPath(), StandardOpenOption.READ))
			{
				parentChannel.force(true);
			}
			catch (Exception e)
			{
				Log.w(TAG, "replaceViaFileIo parent fsync failed for " + parent
					+ ": " + e.getMessage());
			}
		}
		boolean placeholderGone = !placeholder.exists() || placeholder.delete();
		if (!placeholderGone)
		{
			Log.w(TAG, "replaceViaFileIo: target written but couldn't delete placeholder " + placeholder);
			// Surface the leftover to the verify path so the user gets the "Couldn't verify
			// replace" dialog (with the duplicate-file diagnosis inside verifyReplace) instead
			// of a misleading "Replaced X" toast that ignores the stray placeholder.
			fileIoPlaceholderRemains = true;
		}
		// Force mtime update even when the temp's bytes matched the prior target bytes. Samsung's
		// FUSE-backed storage has been observed to skip mtime refresh on dedup-detected
		// content-identical moves — leaving userspace observers convinced the save didn't run.
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
		return true;
	}

	/**
	 * Post an AlertDialog to the UI thread describing a failed/partial Replace. Unlike a Toast, this has no
	 * line-length cap and stays on screen until dismissed. Offers a one-tap link to the "All files access" Settings
	 * screen when the app doesn't hold that permission.
	 */
	private void showReplaceFailureDialog(String title, String message)
	{
		host.runOnUiThread(() -> postReplaceFailureDialog(title, message));
	}

	/**
	 * Best-effort rename of a SAF document. Returns the renamed URI on success, or null on any failure (collision
	 * with an existing name, provider doesn't support rename, auth dropped). Providers are free to return a
	 * different URI than the input — some rehash document IDs on rename, some relocate under a new authority — so
	 * the caller MUST use the returned URI for any subsequent operation on the document rather than re-using the
	 * old one, or `verifyReplace`'s follow-up query hits a stale ID and reports the save as unverified.
	 *
	 * When `DocumentsContract.renameDocument` itself returns null, the Android docs say that means failure — but
	 * some legacy providers return null even on success. To disambiguate, we query the input URI's current display
	 * name: if it already matches `newName`, a legacy success happened and we return the input URI. Otherwise we
	 * treat null as genuine failure so the caller's fallback path (delete-then-retry for strict providers that
	 * rejected the rename-to-existing collision) can kick in. Without this disambiguation, treating every null as
	 * success would skip the fallback entirely and leave Replace permanently stuck on strict providers.
	 */
	private Uri tryRename(Uri uri, String newName)
	{
		try
		{
			Uri renamedUri = DocumentsContract.renameDocument(
				host.getActivity().getContentResolver(), uri, newName);
			if (renamedUri != null)
			{
				return renamedUri;
			}
			String currentName = safFiles.getDisplayName(uri);
			if (currentName != null && currentName.equalsIgnoreCase(newName))
			{
				// Legacy "null-on-success" provider — the document really is renamed.
				return uri;
			}
			Log.w(TAG, "renameDocument(" + newName + ") returned null and display name "
				+ "is \"" + currentName + "\" — treating as failure");
			return null;
		}
		catch (Exception e)
		{
			Log.w(TAG, "renameDocument(" + newName + ") failed: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Check disk after a Replace attempt. Returns true when the end state is clean (target present at requestedName
	 * with the expected length AND placeholder absent). Anything else fires a failure dialog (not a toast — toasts
	 * get truncated on Android 11+) describing what's on disk and, when applicable, offers a direct link to the
	 * "All files access" Settings screen, and returns false. `expectedLength` guards against "file exists but
	 * empty" — an existence-only check was letting silent-zero-byte writes register as success. Runs on the bg
	 * thread; dialog is posted to UI thread.
	 *
	 * `placeholderUri` may have already been rewritten to the renamed-to-target URI by Strategy C
	 * (DocumentsContract.renameDocument relocates the document onto the target path). When that happens, the URI's
	 * resolved File has the same name as the target, meaning placeholder == target on disk. A naive existence check
	 * would see "placeholder exists AND target exists" and trigger the "two files" branch, falsely failing a clean
	 * Strategy C success. The same-name short-circuit at the top handles that case.
	 */
	private boolean verifyReplace(Uri placeholderUri, String requestedName, int expectedLength)
	{
		VerifyFailure failure = classifyVerifyOutcome(placeholderUri, requestedName, expectedLength);
		if (failure == null)
		{
			return true; // clean replace
		}
		Log.w(TAG, "verifyReplace: " + failure.title() + " — " + failure.message());
		showReplaceFailureDialog(failure.title(), failure.message());
		return false;
	}

	/**
	 * Bg-thread callback fired once ExportPipeline has written the placeholder. Owns the three-strategy ladder:
	 *   A. File I/O — writes `data` directly to the target (avoids re-reading the placeholder through
	 *      FUSE/MediaStore layers that may lag), verifies on-disk length, and on success SKIPS the SAF paths
	 *      (running them on the already-correct target could delete it).
	 *   B. SAF direct overwrite — verifies the colliding target byte-for-byte against `data` before deleting
	 *      the placeholder; on verify failure leaves the placeholder so the user keeps their save at the
	 *      auto-suffixed name and verifyReplace's "two files" branch surfaces it.
	 *   C. SAF rename-with-fallback — tries rename first, then delete-then-rename only if the provider rejects
	 *      rename-to-existing.
	 * On a verified end state fires the toast ("Replaced"/"Saved" per wasOverwrite); verifyReplace shows a
	 * failure dialog for unclean states.
	 *
	 * @param newUri        SAF placeholder URI ExportPipeline wrote `data` to
	 * @param requestedName the user's requested filename (the colliding target's display name)
	 * @param wasOverwrite  true when the colliding target held real prior content (toast "Replaced"); false
	 *                      when Replace was taken purely for crash-safety (toast "Saved")
	 * @param verifyUriBox  single-element box mutated to the post-rename URI when strategy C succeeds
	 * @param encoded       the verified exported payload. Tempfile-mode (PNG streaming) is materialised here
	 *                      since readbackByteCount / replaceViaFileIo need bytes; the rare Replace + 200 MP PNG
	 *                      combo may OOM, in which case we abort cleanly (the placeholder save is already
	 *                      verified, so the user has a copy and verifyReplace's "two files" dialog surfaces it)
	 */
	private void writeReplacementPayload(Uri newUri, String requestedName, boolean wasOverwrite,
		Uri[] verifyUriBox, ExportResult encoded)
	{
		byte[] data;
		if (encoded.bytes() != null)
		{
			data = encoded.bytes();
		}
		else
		{
			try
			{
				data = Files.readAllBytes(encoded.tempfile().toPath());
			}
			catch (IOException | OutOfMemoryError e)
			{
				Log.w(TAG, "Replace flow couldn't materialise encode tempfile bytes ("
					+ e.getMessage() + "); placeholder save is intact, aborting Replace");
				// Defer to verifyReplace which will see one file at the auto-suffixed placeholder
				// path and surface the "two files" / "couldn't replace" dialog. The placeholder is
				// the user's verified save; the colliding original is untouched. Cast size() to int
				// is safe because ExportResult already enforces size fits a JVM byte[] (≤ INT_MAX)
				// for the bytes-mode case; tempfile-mode sizes pass through the same int check at
				// the encode pipeline's final tempfile.length() guard.
				verifyReplace(verifyUriBox[0], requestedName, (int) encoded.size());
				return;
			}
		}
		boolean targetWrittenViaFile = replaceViaFileIo(newUri, requestedName, data);
		// Set to true when strategy B's byte-for-byte readback has already confirmed the colliding target holds
		// the exported bytes. verifyReplace's placeholder-URI-only fallback would otherwise falsely report
		// "Couldn't verify replace" for providers where fileFromSafUri returns null (non-primary-storage /
		// opaque-ID providers) — the placeholder was deleted by then, so getDisplayName(newUri) also returns
		// null, and the filesystem path can't be walked to find the colliding target.
		boolean collidingSafVerified = false;

		if (!targetWrittenViaFile)
		{
			// File I/O couldn't write the target — try SAF paths.
			Uri colliding = safFiles.deriveSiblingUri(newUri, requestedName);
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
				long verifiedBytes = safFiles.readbackByteCount(colliding, data);
				if (verifiedBytes == data.length)
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
					Log.w(TAG, "SAF overwrite reported success but readback verified "
						+ verifiedBytes + " of " + data.length + " bytes on colliding"
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
				// files" branch surfaces the situation — strictly better than the old order, which
				// destroyed the original before finding out if the rename would succeed. NO final
				// cleanup of `colliding` after success: `colliding` was derived from requestedName as a
				// sibling path URI, and after a successful rename the placeholder lives AT that path.
				// For path-addressed providers (the common case where deriveSiblingUri works at all),
				// `colliding` and the renamed-in-place target are now the same document — deleting
				// `colliding` would delete the just-saved file. The overwrite branch already
				// consumed/replaced the original, and the fallback branch already deleted it explicitly
				// before retrying the rename; no post-success cleanup needed.
				Uri renamedUri = tryRename(newUri, requestedName);
				if (renamedUri == null && colliding != null)
				{
					// Before destroying `colliding`, probe whether the placeholder still exists on
					// disk. On legacy providers that return null-on-success AND then throw
					// SecurityException on the follow-up display-name query, tryRename returns null
					// even though the rename worked — the placeholder has already moved onto
					// `colliding`'s path. Deleting `colliding` here would destroy the just-saved
					// file. If the filesystem probe confirms the placeholder is gone, treat the
					// first rename as a silent success and don't retry.
					File placeholderFile = safFiles.fileFromSafUri(newUri);
					if (placeholderFile != null && !placeholderFile.exists())
					{
						Log.w(TAG, "rename returned null but placeholder is gone — "
							+ "treating as silent success, skipping retry");
						renamedUri = colliding;
					}
					else
					{
						// Defensive directory guard: SaveController's merged-save / rename
						// callbacks already reject directory targets before the Replace flow
						// dispatches here, but providers could in principle hand back a
						// directory document for the colliding URI too. Refuse to delete
						// directories — leave renamedUri null so verifyReplace surfaces the
						// failure rather than destroying the folder.
						File collidingFile = safFiles.fileFromSafUri(colliding);
						if (collidingFile != null && collidingFile.isDirectory())
						{
							Log.w(TAG, "refusing to delete directory at " + colliding);
						}
						else
						{
							safFiles.tryDeleteSafDocument(colliding);
							renamedUri = tryRename(newUri, requestedName);
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
		//   - Strategy A clean success (`targetWrittenViaFile` AND `!fileIoPlaceholderRemains`):
		//     replaceViaFileIo verified the target's on-disk length matches data.length AND deleted
		//     the placeholder. Re-running verifyReplace would call fileFromSafUri(placeholderUri)
		//     which now resolves via /proc/self/fd readlink (resolveViaProcFd) — that opens the URI,
		//     which fails with ENOENT because the placeholder was just deleted, classifyVerifyOutcome
		//     falls through to its SAF-fallback path, and the user sees a false "Couldn't verify
		//     replace" dialog on a Replace that actually succeeded.
		//   - Strategy B success (`collidingSafVerified`): the colliding URI was byte-for-byte verified
		//     against the data, and the placeholder was deleted — same logic as Strategy A.
		// When `fileIoPlaceholderRemains` is true (Strategy A wrote the target but couldn't delete the
		// placeholder), we DELIBERATELY fall through to verifyReplace so its duplicate-file diagnosis
		// surfaces a failure dialog the user can act on (delete the stray placeholder themselves).
		boolean verified = (targetWrittenViaFile && !fileIoPlaceholderRemains)
			|| collidingSafVerified
			|| verifyReplace(verifyUriBox[0], requestedName, data.length);
		if (verified)
		{
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
