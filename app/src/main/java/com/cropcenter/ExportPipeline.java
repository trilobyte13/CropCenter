package com.cropcenter;

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.cropcenter.crop.CropExporter;
import com.cropcenter.model.CropState;
import com.cropcenter.model.ExportConfig;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.UltraHdrCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Encode → write → verify → report pipeline used by SaveController for plain saves and by
 * ReplaceStrategy for the write-first-then-swap flow. Busy-gates, spawns the background
 * worker, and reports success/failure toasts. Knows nothing about collision handling or
 * SAF picker routing — that lives in SaveController / ReplaceStrategy.
 */
final class ExportPipeline
{
	private static final String TAG = "ExportPipeline";

	private final SaveHost host;
	private final SafFileHelper safFiles;

	ExportPipeline(SaveHost host, SafFileHelper safFiles)
	{
		this.host = host;
		this.safFiles = safFiles;
	}

	void exportTo(Uri uri)
	{
		// Default: caller is confident the URI is a fresh document (plain Save As, case C
		// user-renamed, case B Keep). Cleanup a partial write on failure by deleting the URI.
		exportTo(uri, null, null, false, null);
	}

	/**
	 * Variant for the narrow fallback path in SaveController case A where the provider returned
	 * an ACTION_CREATE_DOCUMENT URI that might point at an existing file and we couldn't
	 * create a sibling placeholder to route through the crash-safe Replace flow. Verification
	 * failure MUST NOT delete the URI — it could destroy the user's original. The residual
	 * cost is a partial-write file left on disk when the URI was actually a fresh document on
	 * a provider that doesn't support createDocument; acceptable since both intersections
	 * (opaque-ID provider + fresh-doc create + verify failure) are rare.
	 */
	void exportToPreserving(Uri uri)
	{
		exportTo(uri, null, null, true, null);
	}

	/**
	 * Overwrite fallback for SaveController case A when the target is a confirmed existing
	 * file (priorSize > 0) AND sibling placeholder creation wasn't available (opaque-ID
	 * provider). Runs the Samsung Gallery Revert backup hook on the bg thread BEFORE the
	 * encoder so the SEFT trailer can claim a backup path the user can recover through
	 * Gallery's Revert UI, then direct-writes to `uri` with preserveOnFailure=true so a
	 * verification failure doesn't destroy the original. `replacedName` is used for the
	 * success toast ("Replaced <name>") so the user gets overwrite-specific confirmation
	 * that matches the backup / SEFT semantics this path ran — a generic "Saved N KB"
	 * would misrepresent a confirmed overwrite. Not as crash-safe as Replace's write-then-
	 * swap — the destructive write is unavoidable on a provider that won't give us a
	 * sibling placeholder — but the backup gives the user a recoverable path from the raw
	 * bytes if the in-place write corrupts the target.
	 */
	void exportOverwriteWithBackup(Uri uri, BooleanSupplier preEncodeBg, String replacedName)
	{
		exportTo(uri, preEncodeBg, null, true, replacedName);
	}

	/**
	 * Encode-and-write on a background thread.
	 *
	 * `preEncodeBg` (optional): runs first on the background thread, before the encoder. Used
	 * by the Replace flow to write the Samsung Gallery Revert backup BEFORE the SEFT trailer
	 * is encoded — the supplier's boolean return controls whether the encoded SEFT claims a
	 * backup exists. Without this pre-step, the SEFT would claim a backup that might not
	 * actually get written (or might fail to write) in `onSavedBg`.
	 *
	 * `onSavedBg` (optional): runs after the write verifies. The callback receives the exact
	 * bytes written so the Replace flow can drop them onto the target file without re-reading
	 * the placeholder (FUSE/MediaStore caching makes that read unreliable). When onSavedBg is
	 * present, doExport's "Saved" toast is suppressed — the callback issues its own
	 * final-outcome message (success or failure dialog).
	 */
	void exportTo(Uri uri, BooleanSupplier preEncodeBg, Consumer<byte[]> onSavedBg)
	{
		// Replace flow: the placeholder URI is known-fresh (auto-rename or programmatic
		// createDocument) so delete-on-failure is the correct cleanup. If a future caller
		// needs preserveOnFailure + preEncode + onSavedBg together, add another overload.
		exportTo(uri, preEncodeBg, onSavedBg, false, null);
	}

	private void exportTo(Uri uri, BooleanSupplier preEncodeBg, Consumer<byte[]> onSavedBg,
		boolean preserveOnFailure, String replacedName)
	{
		if (host.getState().getSourceImage() == null)
		{
			return;
		}
		if (!host.getBusy().compareAndSet(false, true))
		{
			host.showBusyToast();
			return;
		}
		// Between the busy acquire and the runInBackground enqueue, any throw from the UI
		// setup (setBusyUi / showProgress can hit findViewById / setText during an unusual
		// view-tree state) would otherwise strand busy=true forever — the background finally
		// never runs because the Runnable was never submitted. Clear busy + hide UI before
		// propagating so a second Save tap isn't permanently rejected with "Busy — try again".
		try
		{
			host.setBusyUi(true);
			host.showProgress("Saving\u2026");
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "pre-enqueue UI setup threw; releasing busy flag", e);
			host.getBusy().set(false);
			host.setBusyUi(false);
			host.hideProgress();
			throw e;
		}
		boolean isReplaceSave = onSavedBg != null;
		host.runInBackground(() ->
		{
			try
			{
				// Pre-encode hook: Replace writes the backup here so the upcoming SEFT encode
				// knows whether to claim a backup exists. Failure is surfaced via the hook's
				// return value; the hook itself is responsible for warning the user.
				boolean includeBackupInSeft = false;
				if (preEncodeBg != null)
				{
					try
					{
						includeBackupInSeft = preEncodeBg.getAsBoolean();
					}
					catch (Exception e)
					{
						Log.w(TAG, "pre-encode hook threw", e);
					}
				}
				byte[] data = doExport(uri, isReplaceSave, includeBackupInSeft,
					preserveOnFailure, replacedName);
				if (data != null && onSavedBg != null)
				{
					try
					{
						onSavedBg.accept(data);
					}
					catch (Exception e)
					{
						// The replace callback normally owns its own success/failure toast, so
						// doExport suppresses the "Saved" toast when onSavedBg is set. If the
						// callback itself throws before firing its outcome message, that
						// suppression leaves the user with no feedback — silent save, no
						// dialog. Fire an explicit failure toast here so the user knows
						// something went wrong; the exception detail is in the log.
						Log.w(TAG, "post-save step threw", e);
						host.runOnUiThread(() -> host.toastIfAlive(
							"Save step failed \u2014 check log", Toast.LENGTH_LONG));
					}
				}
			}
			finally
			{
				host.getBusy().set(false);
				host.runOnUiThread(() -> host.setBusyUi(false));
				host.hideProgress();
			}
		});
	}

	/**
	 * Export pipeline: encode → write → verify → report.
	 *
	 * Success signal hierarchy:
	 *   1. Write path completes without exception → definitively saved.
	 *   2. Write path threw → read the file back to count persisted bytes. Many SAF providers
	 *      throw harmless EPIPE/IOException on close yet persist the full payload, so readback is
	 *      the ground truth.
	 *   3. Neither → genuine failure; delete the partial file.
	 * Returns the exact bytes written when the file on disk is verified to hold the full payload,
	 * or null on failure. Callers that need to run a post-write step (e.g. Replace's File-I/O
	 * swap) use the returned bytes so they don't have to re-read the placeholder from the
	 * filesystem — that read can go through FUSE/MediaStore layers that aren't necessarily in
	 * sync with the SAF write we just did.
	 *
	 * `isReplaceSave` — true when this export is the placeholder write of a Replace flow.
	 * Suppresses doExport's "Saved" toast because the Replace swap that follows fires its own
	 * outcome message. Applies regardless of whether the backup succeeded.
	 *
	 * `includeBackupInSeft` — true ONLY when the Replace flow has already successfully written
	 * the Samsung Gallery Revert backup file (via ExportPipeline's pre-encode hook). Gates
	 * SEFT backup-path generation in the encoder: Samsung Gallery reads that path and offers
	 * Revert if the file exists, so claiming a backup that doesn't exist would surface Revert
	 * on a non-revertable file. Always false for plain Save As / Keep, and also false for
	 * Replace when the backup write failed.
	 *
	 * `preserveOnFailure` — when verifyPhase rejects the write, true leaves the partial file
	 * on disk; false deletes it. Set by the caller based on whether the URI might point at
	 * user data (e.g. SaveController's case-A fallback when we can't determine the URI is a
	 * fresh doc) vs. being a known-fresh placeholder (Replace flow, plain Save As, etc.).
	 *
	 * `replacedName` — when non-null AND isReplaceSave is false, a successful save emits
	 * "Replaced <replacedName>" instead of the generic "Saved N KB" toast. Used by the
	 * opaque-ID overwrite-fallback path (exportOverwriteWithBackup) so a confirmed
	 * overwrite announces itself as an overwrite, matching the backup / SEFT semantics
	 * that path ran. Null for plain Save As and the fallback preservation path.
	 *
	 * Failure toasts fire regardless of the flags.
	 */
	private byte[] doExport(Uri uri, boolean isReplaceSave, boolean includeBackupInSeft,
		boolean preserveOnFailure, String replacedName)
	{
		boolean isPng = ExportConfig.FORMAT_PNG.equals(host.getState().getExportConfig().format());
		boolean srcHadHdr = host.getState().getGainMap() != null
			&& host.getState().getGainMap().length > 0;

		byte[] data = encodePhase(isPng, srcHadHdr, includeBackupInSeft);
		if (data == null)
		{
			return null;
		}
		WriteOutcome write = writePhase(uri, data);
		if (!verifyPhase(uri, data, write))
		{
			reportFailure(uri, write.exception(), preserveOnFailure);
			return null;
		}
		if (!isReplaceSave)
		{
			if (replacedName != null)
			{
				reportReplaced(replacedName);
			}
			else
			{
				reportSuccess(data, srcHadHdr, isPng);
			}
		}
		return data;
	}

	/**
	 * Phase 1 — encode. Runs CropExporter on the current state and returns the JPEG /
	 * PNG bytes, or null when encoding failed (in which case a failure toast is already
	 * queued). Backup writing used to live here but now runs only when the Replace
	 * flow needs it (ReplaceStrategy.replaceColliding) — plain Save As / Keep paths
	 * no longer pay the backup I/O for a save that isn't actually overwriting the
	 * original.
	 *
	 * Bypass: when the user has applied no transformations (no crop, no rotation, no
	 * grid bake-in, JPEG-to-JPEG round-trip), write `state.originalFileBytes` verbatim
	 * instead of canvas-encoding. This preserves byte-perfect fidelity for re-saves AND
	 * for the graft flow's "apply external edit then save without further editing" path.
	 * The canvas re-encode + ExifPatcher pipeline (especially the IFD1 thumbnail
	 * regeneration) appears to break Samsung Gallery's Revert action when the source's
	 * SEFT trailer references a backup with different pixel content — bypassing them
	 * keeps the saved file structurally identical to what Gallery's Revert chain
	 * already accepts.
	 */
	private byte[] encodePhase(boolean isPng, boolean srcHadHdr, boolean includeBackupInSeft)
	{
		try
		{
			if (canBypassEncode(host.getState(), isPng))
			{
				byte[] data = host.getState().getOriginalFileBytes();
				Log.d(TAG, "Bypassed encode (no transforms applied) — " + data.length + " bytes");
				return data;
			}
			byte[] data = CropExporter.export(
				host.getState(), host.getActivity().getCacheDir(), includeBackupInSeft).data();
			Log.d(TAG, "Encoded " + data.length + " bytes (srcHdr=" + srcHadHdr
				+ " isPng=" + isPng + " seftBackup=" + includeBackupInSeft + ")");
			return data;
		}
		catch (Exception e)
		{
			Log.e(TAG, "Encode failed", e);
			final String emsg = "Export failed: " + e.getMessage();
			host.runOnUiThread(() -> host.toastIfAlive(emsg, Toast.LENGTH_SHORT));
			return null;
		}
	}

	/**
	 * Decide whether the current save is a no-op transformation that lets us write
	 * `state.originalFileBytes` verbatim instead of canvas-encoding. Conditions:
	 *   - Output format is JPEG (PNG has its own encode path; can't bypass).
	 *   - Source format is JPEG (can't bypass-encode a PNG source as JPEG).
	 *   - The image is NOT a graft. Graft saves carry the edit's foreign ICC profile in
	 *     `originalFileBytes`; bypassing skips the canvas-managed Display P3 conversion
	 *     that the cropped graft path runs through CropExporter and produces a slightly
	 *     different output structure than save-after-crop. Forcing graft saves through
	 *     the full encode keeps save-without-crop and save-after-crop byte-similar.
	 *   - No rotation applied.
	 *   - No grid bake-in.
	 *   - Source bytes available.
	 *   - Crop is either uninitialised (state.hasCenter() == false) OR is the trivial
	 *     full-image crop (cropW/cropH match the source bitmap dimensions). The full-
	 *     image case matters because applying a graft auto-initialises a centered crop
	 *     during applyStateToUi → ensureCropCenter, even though the user has applied no
	 *     real edit. Without the full-image carve-out, every graft save would fall into
	 *     the canvas-encode path even when the user never touched the crop tool.
	 *     (Now defensive — the graft-applied check above already excludes that case.)
	 *
	 * If all hold, the canvas-encoded primary would be a re-encoded near-copy of
	 * state.originalFileBytes — close to byte-identical but with different DCT/Huffman
	 * tables and a regenerated EXIF thumbnail. Writing the source bytes verbatim
	 * preserves structure exactly.
	 */
	private static boolean canBypassEncode(CropState state, boolean isPng)
	{
		if (isPng)
		{
			return false;
		}
		if (!ExportConfig.FORMAT_JPEG.equals(state.getSourceFormat()))
		{
			return false;
		}
		if (state.isGraftApplied())
		{
			return false;
		}
		if (state.getRotationDegrees() != 0f)
		{
			return false;
		}
		if (state.getGridConfig().includeInExport())
		{
			return false;
		}
		if (state.getOriginalFileBytes() == null)
		{
			return false;
		}
		if (state.hasCenter())
		{
			Bitmap src = state.getSourceImage();
			if (src == null)
			{
				return false;
			}
			if (state.getCropW() != src.getWidth() || state.getCropH() != src.getHeight())
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Phase 2 — write. Uses try-with-resources so close() runs after writeReturned=true
	 * and a close-only failure can't invalidate a successful write. Returns the outcome
	 * so phase 3 can decide whether to trust it or fall through to a readback.
	 */
	private WriteOutcome writePhase(Uri uri, byte[] data)
	{
		boolean writeReturned = false;
		Exception writeException = null;
		try (OutputStream os = host.getActivity().getContentResolver().openOutputStream(uri, "w"))
		{
			if (os == null)
			{
				throw new IOException("openOutputStream returned null");
			}
			os.write(data);
			writeReturned = true;
		}
		catch (Exception e)
		{
			writeException = e;
			Log.w(TAG, "Write path threw (may still have persisted)", e);
		}
		return new WriteOutcome(writeException, writeReturned);
	}

	/**
	 * Phase 3 — verify. Trust a clean write only after a cheap size-query confirms the file
	 * on disk matches the payload length. Some SAF providers don't truncate on "w" mode, so a
	 * write that's shorter than a prior version leaves stale trailing bytes behind — the
	 * write returns cleanly but the file is corrupt. Three fall-through conditions drop to
	 * byte-by-byte content readback:
	 *   - size query disagrees with payload length (likely un-truncated prior version)
	 *   - size query fails / provider doesn't expose SIZE (can't fast-path, must verify)
	 *   - write path threw (many providers throw harmless EPIPE on close yet persist the
	 *     full payload — readback is the ground truth)
	 * Returns true when the save is good, false when it's genuinely lost.
	 */
	private boolean verifyPhase(Uri uri, byte[] data, WriteOutcome write)
	{
		boolean savedOk = write.writeReturned();
		long sizeCheck = -1;
		long verifiedBytes = -1;
		if (savedOk)
		{
			sizeCheck = safFiles.querySafFileSize(uri);
			if (sizeCheck < 0)
			{
				Log.d(TAG, "Clean write but provider omits SIZE; verifying content");
				savedOk = false;
			}
			else if (sizeCheck != data.length)
			{
				Log.w(TAG, "Clean write but size " + sizeCheck + " != expected "
					+ data.length + " — provider may not have truncated; verifying content");
				savedOk = false;
			}
		}
		if (!savedOk)
		{
			verifiedBytes = safFiles.readbackByteCount(uri, data);
			savedOk = verifiedBytes == data.length;
			if (savedOk)
			{
				Log.d(TAG, "Recovered via content-verified readback: " + verifiedBytes + " bytes");
			}
		}
		Log.d(TAG, "Save result: writeReturned=" + write.writeReturned()
			+ " sizeCheck=" + sizeCheck + " verifiedBytes=" + verifiedBytes
			+ " expected=" + data.length + " → savedOk=" + savedOk);
		return savedOk;
	}

	/**
	 * Phase 4 — success report. "Saved NKB [HDR OK]" / "[HDR dropped]" on a save that
	 * dropped an HDR source (only for JPEG — PNG can't carry gain maps, which is a
	 * format limitation, not a failure, so the suffix is suppressed).
	 */
	private void reportSuccess(byte[] data, boolean srcHadHdr, boolean isPng)
	{
		final String hdrSuffix;
		if (!srcHadHdr || isPng)
		{
			hdrSuffix = "";
		}
		else if (UltraHdrCompat.containsHdrgm(data))
		{
			hdrSuffix = " [HDR OK]";
		}
		else
		{
			hdrSuffix = " [HDR dropped]";
		}
		final String msg = "Saved " + data.length / 1024 + "KB" + hdrSuffix;
		host.runOnUiThread(() -> host.toastIfAlive(msg, Toast.LENGTH_SHORT));
	}

	/**
	 * Phase 4 — overwrite-specific success report. Fires "Replaced <name>" when the caller
	 * is the opaque-ID overwrite-fallback path, which did the overwrite-specific work (pre-
	 * encode Samsung backup, SEFT backup-path claim) and deserves a matching user message.
	 * The full Replace flow (with placeholder + swap) does not use this path — its success
	 * toast is fired by ReplaceStrategy after verifyReplace confirms the final state.
	 */
	private void reportReplaced(String name)
	{
		final String msg = "Replaced " + name;
		host.runOnUiThread(() -> host.toastIfAlive(msg, Toast.LENGTH_SHORT));
	}

	/**
	 * Phase 4 — failure report. Toasts the exception message when available; deletes
	 * the partially-written SAF doc so a failed save doesn't leave a truncated file
	 * behind — but ONLY when `preserveOnFailure` is false. When the URI pointed at an
	 * existing file before the write (provider-confirmed overwrite), deletion would
	 * turn the verification failure into data loss of the user's original; we leave
	 * the (now partial) file on disk and rely on the failure toast so the user knows
	 * to re-save. Partial-write corruption stays preferable to outright deletion of
	 * a file the user didn't explicitly tell us to destroy.
	 */
	private void reportFailure(Uri uri, Exception writeException, boolean preserveOnFailure)
	{
		final String emsg = writeException != null
			? "Export failed: " + writeException.getMessage()
			: "Export failed";
		host.runOnUiThread(() -> host.toastIfAlive(emsg, Toast.LENGTH_SHORT));
		if (preserveOnFailure)
		{
			Log.w(TAG, "preserving " + uri + " on failure (had prior content)");
			return;
		}
		safFiles.tryDeleteSafDocument(uri);
	}

	/**
	 * Result of phase 2. exception is non-null when openOutputStream / write / close
	 * threw; writeReturned is true when close() succeeded (close-after-write is the
	 * final barrier between "written" and "thrown"). Field order follows CLAUDE.md's
	 * uppercase-type-before-primitive rule.
	 */
	private record WriteOutcome(Exception exception, boolean writeReturned)
	{
	}

}
