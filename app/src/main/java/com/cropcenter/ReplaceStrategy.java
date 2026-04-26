package com.cropcenter;

import android.app.AlertDialog;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;

import com.cropcenter.crop.CropExporter;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.StoragePermissionHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Collision-replace policy: SAF ACTION_CREATE_DOCUMENT with a colliding filename is silently
 * auto-renamed by the framework; the user's "Replace" choice then needs the app to overwrite
 * the original. Tries File-I/O first (fastest, most reliable when MANAGE_EXTERNAL_STORAGE is
 * granted), then SAF direct overwrite, then SAF delete-then-rename. Verifies the end state and
 * surfaces a failure dialog when disk doesn't match the intent.
 *
 * Samsung Gallery Revert backup runs in the ExportPipeline pre-encode hook — BEFORE the
 * placeholder bytes are encoded and written. This means the SEFT trailer baked into the
 * export can honestly claim a backup path only when the backup write actually succeeded; a
 * post-write backup (the old layout) would have produced files whose SEFT pointed at
 * backups that might never materialise. The hook's boolean return flows through to
 * CropExporter.export via includeBackupInSeft.
 *
 * Lives downstream of the save flow — SaveController decides whether to invoke this.
 */
final class ReplaceStrategy
{
	private static final String TAG = "ReplaceStrategy";

	private final ExportPipeline exportPipeline;
	private final SaveHost host;
	private final SafFileHelper safFiles;
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
	 * Replace the colliding file in the user's chosen directory (NOT the opened file's directory).
	 *
	 * Write-first-then-swap ordering: the new bytes are written to the SAF placeholder (which has
	 * a "(N)" auto-renamed suffix) and verified BEFORE we touch the original. This makes the
	 * operation crash-safe — if encode/write fails the original is preserved, and the worst case
	 * is a leftover placeholder with the auto-suffix name.
	 *
	 * Strategy after the verified write, tried in order of reliability:
	 *   A. File I/O via MANAGE_EXTERNAL_STORAGE — FileOutputStream bypasses SAF's inconsistent
	 *      delete/rename semantics entirely.
	 *   B. SAF direct overwrite — stream placeholder bytes into the colliding original URI when
	 *      the provider grants sibling access.
	 *   C. SAF delete-colliding + rename-placeholder — last resort.
	 *
	 * Ordering is critical: File I/O, when it succeeds, writes the TARGET file (the original at
	 * requestedName) with the new bytes. The SAF fallback paths that follow operate on the same
	 * two URIs, and their delete/rename calls could accidentally destroy the just-written target
	 * if we ran them unconditionally. So File I/O short-circuits the rest of the flow on a
	 * fully-successful outcome; SAF paths only run when File I/O couldn't touch the target.
	 * verifyReplace runs in both branches to catch partial states.
	 */
	void replaceColliding(Uri newUri, String requestedName, boolean wasOverwrite)
	{
		// Captures the post-rename URI when strategy C succeeds. Starts as newUri because
		// strategies A and B either keep the placeholder URI valid (A writes to a new path,
		// B leaves newUri until final delete) or a later verifier cares only about the
		// colliding URI. Strategy C overwrites this with whatever DocumentsContract.rename
		// returns — providers may relocate the document to a new URI, and verifyReplace's
		// follow-up query MUST use the fresh URI or it hits a stale ID.
		final Uri[] verifyUriBox = { newUri };
		exportPipeline.exportTo(newUri,
			() ->
			{
				// Samsung Gallery Revert backup runs BEFORE the encoder so the SEFT trailer
				// only claims a backup path when one actually exists on disk. Returning true
				// here tells the exporter to include PhotoEditor_Re_Edit_Data pointing at the
				// just-written `.cropcenter` file; returning false leaves SEFT silent about
				// backup so Gallery won't surface a Revert option that would fail.
				//
				// wasOverwrite=false means the caller (SaveController case A with unknown
				// or zero prior size) routed through Replace for crash-safety only, NOT
				// because an existing file needed overwriting. Skip the backup write
				// entirely: there's no prior target to revert to, and writing a backup
				// would cost I/O for no user-visible benefit. The SEFT stays silent, and
				// the success toast later announces "Saved" rather than "Replaced".
				if (!wasOverwrite)
				{
					return false;
				}
				CropExporter.BackupStatus backup = CropExporter.saveOriginalBackup(host.getState());
				if (backup == CropExporter.BackupStatus.FAILED)
				{
					host.runOnUiThread(() -> host.toastIfAlive(
						"Warning: couldn't write revert backup — Gallery Revert won't work",
						Toast.LENGTH_LONG));
				}
				// WRITTEN (freshly created) and ALREADY_EXISTS (backup was already on disk
				// from a prior session) both mean a valid file is at the SEFT's backup path.
				// NOT_APPLICABLE (no original path / media-store ID) and FAILED both mean
				// the SEFT must NOT claim a backup.
				return backup == CropExporter.BackupStatus.WRITTEN
					|| backup == CropExporter.BackupStatus.ALREADY_EXISTS;
			},
			data ->
		{
			// A. File I/O. Writes the encoded bytes directly from `data` — avoids re-reading the
			// placeholder through FUSE/MediaStore layers that may not be in sync yet. Verifies
			// the target's on-disk length matches before reporting success. When this path
			// succeeds, skip SAF paths entirely — running them on the already-correct target
			// could delete it.
			boolean targetWrittenViaFile = replaceViaFileIo(newUri, requestedName, data);
			// Set to true when strategy B's byte-for-byte readback has already confirmed the
			// colliding target holds the exported bytes. verifyReplace's placeholder-URI-only
			// fallback would otherwise falsely report "Couldn't verify replace" for providers
			// where fileFromSafUri returns null (non-primary-storage / opaque-ID providers) —
			// the placeholder was deleted by then, so getDisplayName(newUri) also returns
			// null, and the filesystem path can't be walked to find the colliding target.
			boolean collidingSafVerified = false;

			if (!targetWrittenViaFile)
			{
				// File I/O couldn't write the target — try SAF paths.
				Uri colliding = safFiles.deriveSiblingUri(newUri, requestedName);
				// B. SAF direct overwrite. Verify the colliding target actually holds our bytes
				// before deleting the verified placeholder — copyUriContents returning true only
				// confirms the stream didn't throw, and some providers lie about close success
				// while truncating or partial-writing. Size alone isn't enough: a provider that
				// leaves same-length stale content on a failed overwrite would pass a size check
				// yet serve the wrong file. Full byte-for-byte readback of `colliding` is the
				// ground-truth check; only on matching content do we delete the placeholder. If
				// verification fails, leave the placeholder in place so the user still has their
				// verified save at the auto-suffixed name — verifyReplace's "two files" branch
				// then surfaces the situation in a dialog.
				if (colliding != null && safFiles.copyUriContents(newUri, colliding))
				{
					long verifiedBytes = safFiles.readbackByteCount(colliding, data);
					if (verifiedBytes == data.length)
					{
						// tryDeleteSafDocument is best-effort. Only skip verifyReplace when the
						// provider explicitly confirmed placeholder deletion — otherwise we'd
						// toast "Replaced" while both files linger on disk. verifyReplace's
						// "two files" branch catches that when we let it run.
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
					// rename-to-existing (overwrite semantics) finish the replace in one step
					// without destroying the original document. If that fails (strict providers
					// reject the name collision), fall back to the destructive delete-then-rename
					// path. Rename-first preserves the original when both attempts fail: user
					// still has the colliding original on disk AND their verified save at the
					// auto-suffixed name, and verifyReplace's "two files" branch surfaces the
					// situation — strictly better than the old order, which destroyed the
					// original before finding out if the rename would succeed.
					//
					// NO final cleanup of `colliding` after success: `colliding` was derived
					// from requestedName as a sibling path URI, and after a successful rename
					// the placeholder lives AT that path. For path-addressed providers (the
					// common case where deriveSiblingUri works at all), `colliding` and the
					// renamed-in-place target are now the same document — deleting `colliding`
					// would delete the just-saved file. The overwrite branch already
					// consumed/replaced the original, and the fallback branch already deleted
					// it explicitly before retrying the rename; no post-success cleanup needed.
					Uri renamedUri = tryRename(newUri, requestedName);
					if (renamedUri == null && colliding != null)
					{
						// Before destroying `colliding`, probe whether the placeholder still
						// exists on disk. On legacy providers that return null-on-success AND
						// then throw SecurityException on the follow-up display-name query,
						// tryRename returns null even though the rename worked — the
						// placeholder has already moved onto `colliding`'s path. Deleting
						// `colliding` here would destroy the just-saved file. If the
						// filesystem probe confirms the placeholder is gone, treat the
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
							safFiles.tryDeleteSafDocument(colliding);
							renamedUri = tryRename(newUri, requestedName);
						}
					}
					if (renamedUri != null)
					{
						verifyUriBox[0] = renamedUri;
					}
				}
			}

			// Verify + announce. verifyReplace shows a failure dialog internally when the end
			// state isn't clean; on clean replace we issue the one and only "Replaced" toast
			// (doExport's "Saved" toast was suppressed for this exact reason). The expected
			// length is passed so existence + zero-byte files aren't mistaken for success.
			//
			// Strategy B's short-circuit: if the colliding URI has already been size-verified
			// through SAF, skip the placeholder-based verify (which needs fileFromSafUri to
			// resolve and the placeholder still present — neither is true here). This avoids
			// a false "Couldn't verify replace" dialog on opaque-ID providers.
			boolean verified = collidingSafVerified
				|| verifyReplace(verifyUriBox[0], requestedName, data.length);
			if (verified)
			{
				// "Replaced X" only when we actually overwrote an existing file. When the
				// caller routed through Replace purely for crash-safety on an unknown-size
				// target (SaveController case A, priorSize == -1), we can't honestly claim
				// a replace happened; say "Saved" instead. Keeps the toast truthful when
				// the Replace path ran over what turned out to be a fresh document.
				final String verb = wasOverwrite ? "Replaced " : "Saved ";
				final String announce = verb + requestedName;
				host.runOnUiThread(() -> host.toastIfAlive(announce, Toast.LENGTH_SHORT));
			}
		});
	}

	/**
	 * Replace via plain java.io.File. Writes `data` directly to the target path.
	 *
	 * Write-verify-swap shape: bytes land in a temp sibling first, temp is length-verified,
	 * then an atomic rename swaps it onto the target. A direct FileOutputStream(target)
	 * would truncate the target the moment the stream opens — a mid-write failure would
	 * then leave the user's original corrupt or zero-length before the SAF fallback paths
	 * even run. The temp-first flow preserves the original on any failure: a write/verify
	 * error cleans up the temp and returns false, letting strategies B/C try their luck
	 * against the untouched target.
	 *
	 * Why the bytes come from memory, not the placeholder: reading the placeholder through
	 * FileInputStream could return stale or zero bytes on FUSE-backed storage whose view
	 * hasn't caught up with the SAF write. Using `data` (which ExportPipeline already
	 * verified byte-for-byte against the placeholder) bypasses that window.
	 *
	 * Placeholder deletion is best-effort after a successful swap; a leftover is caught
	 * and reported by verifyReplace.
	 */
	private boolean replaceViaFileIo(Uri placeholderUri, String requestedName, byte[] data)
	{
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
		File tempFile = new File(parent,
			"." + requestedName + ".cropcenter-tmp-" + System.currentTimeMillis());
		try (FileOutputStream out = new FileOutputStream(tempFile))
		{
			out.write(data);
			out.getFD().sync();
		}
		catch (Exception e)
		{
			Log.w(TAG, "replaceViaFileIo temp write to " + tempFile + " failed: "
				+ e.getMessage());
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
		// Atomic swap. Files.move with ATOMIC_MOVE + REPLACE_EXISTING swaps the temp onto
		// the target in one filesystem operation — if the rename fails, the target is
		// still untouched. If the filesystem can't do an atomic move (exotic FS on some
		// devices), bail out rather than fall back to delete-then-rename: that fallback
		// would reintroduce the truncate-then-lose-original vulnerability we're fixing.
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
		boolean placeholderGone = !placeholder.exists() || placeholder.delete();
		if (!placeholderGone)
		{
			Log.w(TAG, "replaceViaFileIo: target written but couldn't delete placeholder "
				+ placeholder);
		}
		// Direct FileOutputStream writes bypass MediaStore, so its cached metadata (thumbnails,
		// date-modified used by Gallery / Files / Photos apps) doesn't refresh on its own for
		// same-path overwrites. Trigger a scan so the new content appears immediately in those
		// apps — without this, the on-disk file is correct but every MediaStore consumer keeps
		// showing the pre-overwrite thumbnail and looks like the save didn't happen.
		String[] pathsToScan = placeholderGone
			? new String[] { target.getAbsolutePath() }
			: new String[] { target.getAbsolutePath(), placeholder.getAbsolutePath() };
		MediaScannerConnection.scanFile(host.getActivity(), pathsToScan, null, null);
		return true;
	}

	/**
	 * Best-effort rename of a SAF document. Returns the renamed URI on success, or null
	 * on any failure (collision with an existing name, provider doesn't support rename,
	 * auth dropped). Providers are free to return a different URI than the input — some
	 * rehash document IDs on rename, some relocate under a new authority — so the caller
	 * MUST use the returned URI for any subsequent operation on the document rather than
	 * re-using the old one, or `verifyReplace`'s follow-up query hits a stale ID and
	 * reports the save as unverified.
	 *
	 * When `DocumentsContract.renameDocument` itself returns null, the Android docs say
	 * that means failure — but some legacy providers return null even on success. To
	 * disambiguate, we query the input URI's current display name: if it already matches
	 * `newName`, a legacy success happened and we return the input URI. Otherwise we
	 * treat null as genuine failure so the caller's fallback path (delete-then-retry for
	 * strict providers that rejected the rename-to-existing collision) can kick in.
	 * Without this disambiguation, treating every null as success would skip the
	 * fallback entirely and leave Replace permanently stuck on strict providers.
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
	 * Post an AlertDialog to the UI thread describing a failed/partial Replace. Unlike a Toast,
	 * this has no line-length cap and stays on screen until dismissed. Offers a one-tap link to
	 * the "All files access" Settings screen when the app doesn't hold that permission.
	 */
	private void showReplaceFailureDialog(String title, String message)
	{
		host.runOnUiThread(() ->
		{
			if (host.isDestroyed())
			{
				return;
			}
			AlertDialog.Builder builder = new AlertDialog.Builder(host.getActivity())
				.setTitle(title)
				.setMessage(message)
				.setPositiveButton("OK", null);
			if (!permissions.hasStoragePermission())
			{
				builder.setNeutralButton("Grant access",
					(dialog, which) -> permissions.openStoragePermissionSettings());
			}
			builder.show();
		});
	}

	/**
	 * Check disk after a Replace attempt. Returns true when the end state is clean (target
	 * present at requestedName with the expected length AND placeholder absent). Anything else
	 * fires a failure dialog (not a toast — toasts get truncated on Android 11+) describing
	 * what's on disk and, when applicable, offers a direct link to the "All files access"
	 * Settings screen, and returns false. `expectedLength` guards against "file exists but
	 * empty" — an existence-only check was letting silent-zero-byte writes register as success.
	 * Runs on the bg thread; dialog is posted to UI thread.
	 */
	private boolean verifyReplace(Uri placeholderUri, String requestedName, int expectedLength)
	{
		File placeholder = safFiles.fileFromSafUri(placeholderUri);
		// Prefer filesystem checks — authoritative when MANAGE_EXTERNAL_STORAGE is granted.
		if (placeholder != null)
		{
			File parent = placeholder.getParentFile();
			File target = (parent != null) ? new File(parent, requestedName) : null;
			boolean placeholderExists = placeholder.exists();
			boolean targetOk = target != null && target.exists()
				&& target.length() == expectedLength;
			if (targetOk && !placeholderExists)
			{
				return true; // clean replace
			}
			boolean targetExists = target != null && target.exists();
			long targetLen = targetExists ? target.length() : -1;
			final String title;
			final String message;
			if (targetExists && !placeholderExists)
			{
				// Target exists but wrong length — the write silently truncated or corrupted.
				// Delete the partial file so the next Save isn't offered "Replace" on a bad
				// file and the user doesn't unknowingly keep re-hitting the same failure. The
				// user is told explicitly in the dialog and can re-save.
				if (target != null && !target.delete())
				{
					Log.w(TAG, "verifyReplace: failed to remove corrupt target " + target);
				}
				title = "Replace produced an incomplete file";
				message = requestedName + " was " + targetLen + " bytes instead of the expected "
					+ expectedLength + " and has been removed. Re-save, and if it keeps "
					+ "happening, grant \u201CAll files access\u201D or move the save target "
					+ "to a folder you own.";
			}
			else if (placeholderExists && targetExists)
			{
				title = "Replace left two files";
				message = "Both " + requestedName + " and " + placeholder.getName()
					+ " now exist on disk. Grant \u201CAll files access\u201D so Replace can "
					+ "clean up automatically, or delete one manually from the Files app.";
			}
			else if (placeholderExists)
			{
				title = "Couldn't replace " + requestedName;
				message = "Your crop was saved as " + placeholder.getName()
					+ ". The original " + requestedName + " is owned by a previous install of "
					+ "CropCenter and can't be replaced by this build. Grant \u201CAll files "
					+ "access\u201D and save again, or delete the original from the Files app.";
			}
			else
			{
				title = "Save may have failed";
				message = "Neither " + requestedName + " nor " + placeholder.getName()
					+ " is on disk. Check your save directory and try again.";
			}
			Log.w(TAG, "verifyReplace: " + title + " — " + message);
			showReplaceFailureDialog(title, message);
			return false;
		}
		// No filesystem access — fall back to SAF query on the placeholder URI.
		String finalName = safFiles.getDisplayName(placeholderUri);
		if (finalName != null && requestedName.equalsIgnoreCase(finalName))
		{
			return true; // clean replace per SAF
		}
		final String title;
		final String message;
		if (finalName != null)
		{
			title = "Couldn't replace " + requestedName;
			message = "Your crop was saved as " + finalName + ". Grant \u201CAll files access\u201D "
				+ "so Replace can overwrite the existing file, or delete the original from the "
				+ "Files app and save again.";
		}
		else
		{
			title = "Couldn't verify replace";
			message = "Save may not have completed. Check your save directory for a "
				+ requestedName + " or auto-renamed copy.";
		}
		Log.w(TAG, "verifyReplace: " + title + " — " + message);
		showReplaceFailureDialog(title, message);
		return false;
	}
}
