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

/**
 * Collision-replace policy: SAF ACTION_CREATE_DOCUMENT with a colliding filename is silently
 * auto-renamed by the framework; the user's "Replace" choice then needs the app to overwrite
 * the original. Tries File-I/O first (fastest, most reliable when MANAGE_EXTERNAL_STORAGE is
 * granted), then SAF direct overwrite, then SAF delete-then-rename. Verifies the end state and
 * surfaces a failure dialog when disk doesn't match the intent.
 *
 * Also writes the Samsung Gallery Revert backup of the original file — the backup runs inside
 * the ExportPipeline.exportTo onSavedBg callback so it executes on the background thread
 * after the placeholder write but before any of the fallback paths below touch the original.
 * Lives downstream of the save flow — SaveController.showReplaceDialog decides whether to
 * invoke this.
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
	void replaceColliding(Uri newUri, String requestedName)
	{
		exportPipeline.exportTo(newUri, data ->
		{
			// Samsung Gallery Revert backup happens here, on the background-executor thread, NOT
			// in every Save As — the backup is only needed when we're actually overwriting the
			// original. Runs after doExport's verified placeholder write but BEFORE any of the
			// file-I/O / SAF-fallback paths below touch the original on disk. If the backup
			// write fails we still proceed (user already chose Replace) but warn so they know
			// Revert is dead.
			CropExporter.BackupStatus backup = CropExporter.saveOriginalBackup(host.getState());
			if (backup == CropExporter.BackupStatus.FAILED)
			{
				host.runOnUiThread(() -> host.toastIfAlive(
					"Warning: couldn't write revert backup — Gallery Revert won't work",
					Toast.LENGTH_LONG));
			}

			// A. File I/O. Writes the encoded bytes directly from `data` — avoids re-reading the
			// placeholder through FUSE/MediaStore layers that may not be in sync yet. Verifies
			// the target's on-disk length matches before reporting success. When this path
			// succeeds, skip SAF paths entirely — running them on the already-correct target
			// could delete it.
			boolean targetWrittenViaFile = replaceViaFileIo(newUri, requestedName, data);

			if (!targetWrittenViaFile)
			{
				// File I/O couldn't write the target — try SAF paths.
				Uri colliding = safFiles.deriveSiblingUri(newUri, requestedName);
				// B. SAF direct overwrite.
				if (colliding != null && safFiles.copyUriContents(newUri, colliding))
				{
					safFiles.tryDeleteSafDocument(newUri);
				}
				else
				{
					// C. SAF delete-then-rename.
					if (colliding != null)
					{
						safFiles.tryDeleteSafDocument(colliding);
					}
					try
					{
						DocumentsContract.renameDocument(
							host.getActivity().getContentResolver(), newUri, requestedName);
					}
					catch (Exception e)
					{
						Log.w(TAG, "renameDocument(" + requestedName + ") failed: "
							+ e.getMessage());
					}
				}
			}

			// Verify + announce. verifyReplace shows a failure dialog internally when the end
			// state isn't clean; on clean replace we issue the one and only "Replaced" toast
			// (doExport's "Saved" toast was suppressed for this exact reason). The expected
			// length is passed so existence + zero-byte files aren't mistaken for success.
			if (verifyReplace(newUri, requestedName, data.length))
			{
				final String announce = "Replaced " + requestedName;
				host.runOnUiThread(() -> host.toastIfAlive(announce, Toast.LENGTH_SHORT));
			}
		});
	}

	/**
	 * Replace via plain java.io.File. Writes `data` directly to the target path — reading the
	 * placeholder through FileInputStream could return stale or zero bytes on FUSE-backed
	 * storage whose view hasn't caught up with the SAF write. Verifies the written length
	 * against `data.length` before claiming success so a silent partial/empty write can't pass.
	 * Placeholder deletion is best-effort; a leftover is caught and reported by verifyReplace.
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
		try (FileOutputStream out = new FileOutputStream(target))
		{
			out.write(data);
			out.getFD().sync();
		}
		catch (Exception e)
		{
			Log.w(TAG, "replaceViaFileIo write to " + target + " failed: " + e.getMessage());
			return false;
		}
		long written = target.length();
		if (written != data.length)
		{
			Log.w(TAG, "replaceViaFileIo: target length " + written + " != expected "
				+ data.length + " — write didn't land");
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
				title = "Replace produced an incomplete file";
				message = requestedName + " exists on disk but is " + targetLen + " bytes instead "
					+ "of the expected " + expectedLength + ". The write didn't land correctly. "
					+ "Re-save, and if it keeps happening, grant \u201CAll files access\u201D or "
					+ "move the save target to a folder you own.";
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
