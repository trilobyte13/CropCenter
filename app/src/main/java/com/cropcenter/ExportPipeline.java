package com.cropcenter;

import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.WorkerThread;

import com.cropcenter.crop.CropExporter;
import com.cropcenter.crop.ExportResult;
import com.cropcenter.metadata.ExifPatcher;
import com.cropcenter.metadata.JpegMetadataInjector;
import com.cropcenter.model.CropState;
import com.cropcenter.model.Format;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.view.FolderBrowser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encode → write → verify → report pipeline used by SaveController for plain saves and by ReplaceStrategy for the
 * write-first-then-swap flow. Busy-gates, spawns the background worker, and reports success/failure toasts. Knows
 * nothing about collision handling or SAF picker routing — that lives in SaveController / ReplaceStrategy.
 */
final class ExportPipeline
{
	/**
	 * Result of phase 2 (writePhase).
	 *
	 * @param exception     non-null when openOutputStream / write / close threw
	 * @param directPath    non-null when phase 2 took the direct FileOutputStream branch — verifyPhase then
	 *                      confirms the write via this resolved filesystem path instead of querying SAF
	 *                      provider metadata that may not have flushed yet (a direct write bypasses the
	 *                      provider, so a stale provider-cached SIZE / cached input stream would false-fail
	 *                      a correct write and then delete it)
	 * @param writeReturned true only when the payload write AND close() both succeeded — close-after-write
	 *                      is the final barrier between "written" and "thrown"
	 */
	private record WriteOutcome(Exception exception, File directPath, boolean writeReturned)
	{
	}

	private static final String TAG = "ExportPipeline";

	private final SafFileHelper safFiles;
	private final SaveHost host;

	ExportPipeline(SaveHost host, SafFileHelper safFiles)
	{
		this.host = host;
		this.safFiles = safFiles;
	}

	/**
	 * Decide whether the current save is a no-op transformation that lets us write
	 * `state.originalFileBytes` verbatim instead of canvas-encoding. All must hold:
	 *   - Output format JPEG and source format JPEG (PNG has its own encode path).
	 *   - NOT a graft. Graft saves need a fresh gain map regenerated from the spliced primary — the splice
	 *     ships source's gain map verbatim, fine for view-only HDR but mis-aligned once the user crops; the
	 *     full encode keeps save-without-crop and save-after-crop both correct.
	 *   - No rotation, no grid bake-in, source bytes available.
	 *   - Crop uninitialised or the trivial full-image crop (cropW/cropH == source dims).
	 *
	 * When all hold, a canvas re-encode would be a near-copy of originalFileBytes (different DCT/Huffman tables +
	 * regenerated thumbnail); writing the source bytes verbatim preserves structure exactly.
	 *
	 * Lives here, not in CropState: it combines CropState reads with an ExifPatcher.hasIfd1Thumbnail probe, so
	 * moving it would invert the model/ → metadata/ layering. Static + package-private for the test.
	 *
	 * @param state current CropState — read for sourceFormat, graftApplied, rotationDegrees,
	 *              gridConfig.includeInExport, originalFileBytes, hasCenter, cropW/cropH
	 * @param isPng true when output format is PNG (always returns false — PNG never bypasses)
	 * @return true when all bypass conditions hold (caller writes originalFileBytes verbatim); false otherwise
	 */
	static boolean canBypassEncode(CropState state, boolean isPng)
	{
		if (isPng)
		{
			return false;
		}
		if (state.getSourceFormat() != Format.JPEG)
		{
			return false;
		}
		if (state.isGraftApplied())
		{
			return false;
		}
		// Honor the same epsilon the renderer uses — sub-epsilon rotation is a no-op there, so forcing a
		// re-encode for it would burn cycles + add quantization noise to a visually identical primary.
		// ROTATION_EPSILON (0.005°) is half the ruler's finest tick (0.01°), so every nonzero ruler value
		// clears the gate but float-noise residuals on a "back to zero" gesture collapse to no-op.
		if (Math.abs(state.getRotationDegrees()) >= BitmapUtils.ROTATION_EPSILON)
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
		// When the source carries no IFD1 thumbnail (screenshots, generated images, files re-encoded by minimal
		// tools that strip metadata), bypass would write the source bytes verbatim — preserving the empty-IFD1
		// state and never giving the re-encode path's `buildEmbeddedThumbnail` / `ExifPatcher.patch` synthesise
		// chain a chance to add a fresh thumbnail. Force the re-encode path so the saved file gains the
		// embedded preview the user expects. The re-encode trade-off (different DCT/Huffman tables vs source)
		// is worth it for the thumbnail; verbatim fidelity stays preserved for the common case where the source
		// DOES have a pre-computed thumbnail to round-trip.
		if (!ExifPatcher.hasIfd1Thumbnail(state.getJpegMeta()))
		{
			return false;
		}
		return true;
	}

	/**
	 * HDR suffix for the "Saved N KB" success toast — the only user-visible signal of gain-map survival, so the
	 * truth table is load-bearing: a save reported "[HDR OK]" when the gain map was actually dropped could lead the
	 * user to delete the HDR original trusting the toast. Package-private static so ExportPipelineTest can pin all
	 * eight rows.
	 *
	 * @param srcHadHdr   true when the source carried a gain map (false suppresses any suffix — nothing
	 *                    to preserve, nothing to report)
	 * @param isPng       true for PNG output (suffix suppressed regardless — PNG can't carry gain maps,
	 *                    a format limitation rather than a failure)
	 * @param hdrAttached true when the output structurally carries the Ultra HDR gain map
	 * @return "" when no suffix applies, " [HDR OK]" when the gain map survived, " [HDR dropped]" when
	 *         an HDR source lost its gain map on a JPEG save
	 */
	static String hdrSuffix(boolean srcHadHdr, boolean isPng, boolean hdrAttached)
	{
		if (!srcHadHdr || isPng)
		{
			return "";
		}
		return hdrAttached ? " [HDR OK]" : " [HDR dropped]";
	}

	/**
	 * Save-verification readback comparator — a one-line delegation to SafFileHelper.streamCompare, the shared
	 * classification body (see its Javadoc for the value classes). Kept as a package-private static seam so
	 * ExportPipelineStreamingTest keeps driving the pipeline's verify chokepoint directly; the instance wrapper
	 * streamCompareUriToFile owns resolver-open and the IOException-to-minus-1 mapping.
	 *
	 * @param uriIn    saved-document stream to read back
	 * @param fileIn   local encoded payload stream (the ground truth)
	 * @param expected total bytes the caller knows the encoded payload is
	 * @return verified byte count, or a sentinel value per SafFileHelper.streamCompare's classification
	 * @throws IOException when either stream errors; the instance wrapper catches and returns -1
	 */
	static long streamCompare(InputStream uriIn, InputStream fileIn, long expected) throws IOException
	{
		return SafFileHelper.streamCompare(uriIn, fileIn, expected);
	}

	/**
	 * Write the encoded payload to `os` — either a single `write(bytes)` for bytes-mode results, or a chunked
	 * stream-copy from the tempfile for tempfile-mode results. The tempfile path uses
	 * `JpegMetadataInjector.STREAM_CHUNK_SIZE` (64 KB) chunks so peak Java heap during the write is the chunk
	 * buffer only — no 400-600 MB byte[] materialised for the PNG streaming path. Returns the byte count written so
	 * the caller can log it. Package-private because ReplaceStrategy.replaceViaFileIo streams its strategy-A
	 * temp-sibling write through the same chokepoint.
	 *
	 * @param encoded ExportResult holding either bytes or tempfile (exactly-one-non-null invariant)
	 * @param os      output stream to write to; caller closes
	 * @return byte count written (== encoded.size())
	 * @throws IOException on read or write failure
	 */
	static long writePayloadToStream(ExportResult encoded, OutputStream os) throws IOException
	{
		if (encoded.bytes() != null)
		{
			os.write(encoded.bytes());
			return encoded.bytes().length;
		}
		try (FileInputStream fis = new FileInputStream(encoded.tempfile()))
		{
			return JpegMetadataInjector.copyRemaining(fis, os);
		}
	}

	void exportTo(Uri uri)
	{
		// Default: caller is confident the URI is a fresh document (plain Save As, case C user-renamed, case B
		// Keep). Cleanup a partial write on failure by deleting the URI.
		exportTo(uri, null, false, null);
	}

	/**
	 * Encode-and-write on a background thread. `onSavedBg` is optional and runs after the write verifies. The
	 * callback receives the encode result so the Replace flow can drop the same bytes onto the target file without
	 * re-reading the placeholder (FUSE/MediaStore caching makes that read unreliable). When the result is
	 * tempfile-mode (PNG streaming path), the callback streams from disk via ExportResult.tempfile(); when
	 * bytes-mode (JPEG / bypass), the callback reads bytes directly. When onSavedBg is present, doExport's "Saved"
	 * toast is suppressed — the callback issues its own final-outcome message (success or failure dialog).
	 *
	 * @param uri       SAF URI to write to (fresh document — delete-on-failure cleanup)
	 * @param onSavedBg optional bg-thread callback invoked with the encode result after write+verify;
	 *                  null suppresses the callback and lets doExport's own "Saved" toast fire
	 */
	void exportTo(Uri uri, Consumer<ExportResult> onSavedBg)
	{
		// Replace flow: the placeholder URI is known-fresh (auto-rename or programmatic createDocument) so
		// delete-on-failure is the correct cleanup — and the pre-write abort branches (missing source, busy
		// rejection, encode failure) delete it too, so an aborted Replace never orphans the placeholder. If a
		// future caller needs preserveOnFailure + onSavedBg together, add another overload.
		exportTo(uri, onSavedBg, false, null);
	}

	/**
	 * Overwrite fallback for SaveController case A when the target is a confirmed existing file (priorSize > 0) AND
	 * sibling placeholder creation wasn't available (opaque-ID provider). Direct-writes to uri with
	 * preserveOnFailure=true so a verification failure doesn't destroy the original. Not as crash-safe as Replace's
	 * write-then-swap — the destructive write is unavoidable on a provider that won't give us a sibling
	 * placeholder.
	 *
	 * @param uri          SAF URI to overwrite (existing document; verify-failure preserves original)
	 * @param replacedName user-facing name for the success toast ("Replaced X" — overwrite-specific
	 *                     wording matching what this path actually did, instead of a generic "Saved
	 *                     N KB" that would misrepresent a confirmed overwrite)
	 */
	void exportToOverwrite(Uri uri, String replacedName)
	{
		exportTo(uri, null, true, replacedName);
	}

	/**
	 * Variant for the narrow fallback path in SaveController case A where the provider returned an
	 * ACTION_CREATE_DOCUMENT URI that might point at an existing file and we couldn't create a sibling placeholder
	 * to route through the crash-safe Replace flow. Verification failure MUST NOT delete the URI — it could destroy
	 * the user's original. The residual cost is a partial-write file left on disk when the URI was actually a fresh
	 * document on a provider that doesn't support createDocument; acceptable since both intersections (opaque-ID
	 * provider + fresh-doc create + verify failure) are rare.
	 *
	 * @param uri ambiguous-status SAF URI; verify-failure preserves whatever was at uri (could be the
	 *            user's original document — never delete)
	 */
	void exportToPreserving(Uri uri)
	{
		exportTo(uri, null, true, null);
	}

	/**
	 * Stream-compare a SAF URI's content against a local tempfile, returning the verified byte count (or a sentinel
	 * value indicating mismatch / trailing / short, matching `readbackByteCount`'s contract — see the static
	 * `streamCompare` core for the full classification). Package-private because ReplaceStrategy's strategy-B
	 * readback verifies its tempfile-mode payloads through the same chokepoint.
	 *
	 * @param uri      SAF URI to read back
	 * @param tempfile local encoded payload on disk
	 * @param expected total bytes the caller knows the encoded payload is
	 * @return verified byte count, a sentinel value per streamCompare's contract, or -1 when an
	 *         IOException disrupted the comparison (or the resolver returned no stream)
	 */
	long streamCompareUriToFile(Uri uri, File tempfile, long expected)
	{
		try (InputStream uriIn = host.getActivity().getContentResolver().openInputStream(uri);
			FileInputStream fileIn = new FileInputStream(tempfile))
		{
			if (uriIn == null)
			{
				return -1;
			}
			return streamCompare(uriIn, fileIn, expected);
		}
		catch (IOException e)
		{
			Log.w(TAG, "stream-verify failed: " + e.getMessage());
			return -1;
		}
	}

	/**
	 * Delete a fresh save document and drop its in-flight temp journal entry when the document was a journaled
	 * crash-safe temp. The path resolution runs BEFORE the delete — fileFromSafUri opens the URI, which fails
	 * once the document is gone — and the un-journal is gated on the temp name shape so plain Save As documents
	 * (visible names, never journaled) skip the prefs round-trip.
	 *
	 * @param uri fresh document this save created, to delete on the abort / failure path
	 */
	private void deleteDocAndUnjournalTemp(Uri uri)
	{
		File resolved = safFiles.fileFromSafUri(uri).orElse(null);
		safFiles.tryDeleteSafDocument(uri);
		if (resolved != null && SaveTempFiles.isTempArtifact(resolved.getName()))
		{
			SaveController.unjournalInflightTemp(host.getActivity(), resolved.getParentFile(),
				resolved.getName());
		}
	}

	/**
	 * Delete the Replace flow's fresh placeholder document when an abort strikes before the write. The Replace
	 * flow's target (crash-safe hidden sibling or SAF auto-rename doc) is always a fresh document this save created
	 * — leaving it behind on a busy-rejection / missing-source abort would orphan a file in the user's folder that
	 * only the hard-kill startup sweep could reclaim. No-op for plain saves (isReplaceSave false): their URIs may
	 * be user-owned documents this flow must never delete on abort.
	 *
	 * @param uri           save target the aborted export would have written
	 * @param isReplaceSave true when uri is the Replace flow's fresh placeholder (onSavedBg present)
	 */
	private void deletePlaceholderOnAbort(Uri uri, boolean isReplaceSave)
	{
		if (isReplaceSave)
		{
			deleteDocAndUnjournalTemp(uri);
		}
	}

	/**
	 * Dispose an aborted in-flight temp: best-effort delete plus its journal-entry removal. The three
	 * tryDirectAtomicWrite failure paths route here so the journal can't hold an entry for a temp that was
	 * already cleaned in-process.
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
	 * Phase orchestrator: encode (phase 1) → write (phase 2) → verify (phase 3) → report (phase 4), all on the
	 * background worker.
	 *
	 * Tempfile ownership: a tempfile-mode encode result (PNG streaming path) is deleted in this method's finally on
	 * every path EXCEPT a verified Replace save (isReplaceSave AND write verified). On that path ownership
	 * transfers to runExportBg, whose onSavedBg callback streams from the tempfile and whose own finally deletes it
	 * after the callback returns — deleting here would pull the file out from under the callback.
	 *
	 * @param uri               save target; deleted on encode failure unless preserveOnFailure (fresh-document
	 *                          targets only — preserveOnFailure targets may hold user data)
	 * @param isReplaceSave     true when uri is the Replace flow's fresh placeholder — success suppresses this
	 *                          method's toasts (the Replace callback owns final-outcome messaging) and
	 *                          transfers tempfile ownership per the contract above
	 * @param preserveOnFailure true when the target may hold user data — no failure path deletes it
	 * @param replacedName      non-null on the overwrite-fallback path: success emits "Replaced <name>"
	 *                          instead of "Saved N KB"; read only when isReplaceSave is false
	 * @return the verified encode result; empty when encode, write, or verify failed (failure already
	 *         reported and the target cleaned up per preserveOnFailure)
	 */
	@WorkerThread
	private Optional<ExportResult> doExport(Uri uri, boolean isReplaceSave, boolean preserveOnFailure,
		String replacedName)
	{
		boolean isPng = host.getState().getExportConfig().format() == Format.PNG;
		boolean srcHadHdr = host.getState().getGainMap() != null && host.getState().getGainMap().length > 0;

		Optional<ExportResult> encodedOpt = encodePhase(isPng, srcHadHdr);
		if (encodedOpt.isEmpty())
		{
			// Encode failed before any bytes reached the target. Fresh-document targets (Replace-flow
			// placeholder, plain Save As doc) are deleted so the abort leaves no orphan in the user's
			// folder — mirrors reportFailure's delete-on-failure. preserveOnFailure targets may hold user
			// data and are never deleted.
			if (!preserveOnFailure)
			{
				deleteDocAndUnjournalTemp(uri);
			}
			return Optional.empty();
		}
		ExportResult encoded = encodedOpt.orElseThrow();
		// Tracks whether the verified-write succeeded — gates the Replace tempfile ownership transfer. Without
		// it, a failed Replace (verifyPhase=false, doExport returns empty) would skip cleanup in the finally
		// block but never reach the onSavedBg callback that would otherwise own the tempfile, leaking multi-MB
		// PNG cache files until next-startup sweepStaleCacheFiles.
		boolean writeVerified = false;
		try
		{
			WriteOutcome write = writePhase(uri, encoded, preserveOnFailure);
			if (!verifyPhase(uri, encoded, write))
			{
				reportFailure(uri, write.exception(), preserveOnFailure);
				return Optional.empty();
			}
			writeVerified = true;
			if (!isReplaceSave)
			{
				if (replacedName != null)
				{
					reportReplaced(replacedName);
				}
				else
				{
					reportSuccess(encoded.size(), srcHadHdr, isPng, encoded.hdrAttached());
				}
			}
			return Optional.of(encoded);
		}
		finally
		{
			// Tempfile cleanup: the PNG streaming path returns a tempfile-mode ExportResult, and
			// CropExporter explicitly transfers ownership to us (does NOT delete the file in its own
			// finally). On the Replace flow (isReplaceSave=true) the onSavedBg callback runs in runExportBg
			// AFTER doExport returns successfully — that callback owns the tempfile for its lifetime, so we
			// skip cleanup ONLY when both isReplaceSave AND writeVerified are true. On every other path
			// (non-Replace OR failed verify), we delete it here so a failed Replace doesn't leak the
			// tempfile. The bytes-mode ExportResult has no tempfile (encoded.tempfile() == null) so
			// deletion is a no-op.
			boolean replaceOwnsTempfile = isReplaceSave && writeVerified;
			if (!replaceOwnsTempfile && encoded.tempfile() != null)
			{
				SaveTempFiles.deleteQuietly(encoded.tempfile(), TAG, "encode tempfile");
			}
		}
	}

	/**
	 * Phase 1 — encode. Runs CropExporter on the current state and returns the JPEG / PNG bytes; empty when
	 * encoding failed (in which case a failure toast is already queued).
	 *
	 * Bypass: when the user has applied no transformations (no crop, no rotation, no grid bake-in, JPEG-to-JPEG
	 * round-trip on a NON-graft source), write `state.originalFileBytes` verbatim instead of canvas-encoding. This
	 * preserves byte-perfect fidelity for re-saves of an unedited file. The bypass is explicitly disabled for
	 * grafts (canBypassEncode rejects state.isGraftApplied) — graft saves need the canvas re-encode to regenerate
	 * the gain map from the spliced primary, so the HDR boost stays spatially aligned with the edit's pixels.
	 * Skipping that for a graft would ship source's gain map verbatim over the spliced primary and visibly
	 * mis-place the boost wherever the edit changed pixels.
	 *
	 * @param isPng     true when the output format is PNG (disables the bypass — PNG has its own encode path)
	 * @param srcHadHdr true when the source carried a gain map; threaded into the bypass path's ExportResult
	 *                  so hdrAttached reflects what the verbatim bytes actually carry
	 * @return encode result (bytes-mode or tempfile-mode); empty when encoding failed (failure toast
	 *         already queued)
	 */
	private Optional<ExportResult> encodePhase(boolean isPng, boolean srcHadHdr)
	{
		try
		{
			if (canBypassEncode(host.getState(), isPng))
			{
				byte[] data = host.getState().getOriginalFileBytes();
				Log.d(TAG, "Bypassed encode (no transforms applied) — " + data.length + " bytes");
				// Bypass writes original bytes verbatim, including the gain map when source was HDR.
				// canBypassEncode rejects grafts and any transform that would invalidate gain-map
				// alignment, so srcHadHdr accurately reflects what the bypassed bytes carry.
				return Optional.of(new ExportResult(data, srcHadHdr));
			}
			ExportResult result = CropExporter.export(host.getState(), host.getActivity().getCacheDir());
			// Use size() instead of bytes().length — PNG returns tempfile-mode with bytes()==null, so the
			// direct .length access NPEs. size() handles both modes (bytes.length for bytes-mode,
			// tempfile.length() for tempfile-mode).
			Log.d(TAG, "Encoded " + result.size() + " bytes (srcHdr=" + srcHadHdr
				+ " isPng=" + isPng + " hdrAttached=" + result.hdrAttached() + ")");
			return Optional.of(result);
		}
		catch (Exception | OutOfMemoryError e)
		{
			// Widened to OOM as well as Exception so a primary-bitmap or HDR-render allocation failure on a
			// multi-MP source still surfaces a user-facing "Export failed" toast — without OOM in the
			// catch, runExportBg's finally would hide the progress overlay while the worker died with an
			// uncaught Error, leaving the user staring at an unresponsive editor with no feedback.
			Log.e(TAG, "Encode failed", e);
			// Diagnostic heap snapshot logged on every failure — when a user reports "Export failed:
			// Failed to allocate N bytes" we can correlate N against the actual heap cap and used
			// bytes on their device. Runtime.maxMemory() is the Java heap cap (largeHeap-aware);
			// totalMemory is currently-committed; freeMemory is free WITHIN the committed pool.
			// Effective free heap = maxMemory - (totalMemory - freeMemory).
			Runtime rt = Runtime.getRuntime();
			long max = rt.maxMemory();
			long total = rt.totalMemory();
			long free = rt.freeMemory();
			long effectiveFree = max - (total - free);
			Log.e(TAG, "Java heap on failure: max=" + (max >> 20) + "MB committed="
				+ (total >> 20) + "MB effectiveFree=" + (effectiveFree >> 20) + "MB");
			// LENGTH_LONG because the message embeds the exception's own text (variable length — "Skia
			// rejected JPEG encode", "Image too large to save — try a smaller crop", etc.) and Android 11+
			// truncates LENGTH_SHORT toasts past ~50 chars on portrait phones. The user needs to read the
			// actionable suggestion at the end of these messages.
			final String emsg = "Export failed: " + e.getMessage();
			host.runOnUiThread(() -> host.toastIfAlive(emsg, Toast.LENGTH_LONG));
			return Optional.empty();
		}
	}

	private void exportTo(Uri uri, Consumer<ExportResult> onSavedBg, boolean preserveOnFailure, String replacedName)
	{
		// Replace flow (onSavedBg present): uri is a fresh placeholder this save created, and every abort
		// before the write must delete it — a rejected save leaves ZERO orphan in the user's folder; the
		// startup sweep is a hard-kill backstop, not a license to orphan.
		boolean isReplaceSave = onSavedBg != null;
		if (host.getState().getSourceImage() == null)
		{
			deletePlaceholderOnAbort(uri, isReplaceSave);
			return;
		}
		if (!host.getBusy().compareAndSet(false, true))
		{
			deletePlaceholderOnAbort(uri, isReplaceSave);
			host.showBusyToast();
			return;
		}
		// Free the picker thumbnail cache before the export's GB-class native allocations (200 MP HDR
		// sources): up to 64 MB of strongly-referenced bitmaps would otherwise stay unreclaimable for the
		// whole pipeline. Cheap no-op when a picker dismiss already evicted.
		FolderBrowser.evictThumbnailCache();
		// Between the busy acquire and the runInBackground enqueue, any throw from the UI setup (setBusyUi /
		// showProgress can hit findViewById / setText during an unusual view-tree state) OR from the executor
		// itself (RejectedExecutionException after onDestroy shutdown racing a save tap) would otherwise strand
		// busy=true forever — the background finally never runs because the Runnable was never submitted. Clear
		// busy + hide UI before propagating so a second Save tap isn't permanently rejected with "Busy — try
		// again". runInBackground sits inside the same try so an executor-level reject gets the same cleanup as
		// a view-tree throw — matches ImageLoadController.load and GraftController.onEditPicked. The rethrow
		// below is for the genuinely SYNCHRONOUS callers, which have a live call stack to see the real error:
		// the Case B Replace dialog's buttons (onReplaceConfirmed → replaceColliding, onReplaceKeep →
		// exportTo). The one POSTED caller — SaveController.dispatchCrashSafeWrite, reached via
		// replaceColliding / exportToOverwrite / exportToPreserving — must never let it reach the main looper;
		// SaveController.runPostedSaveContinuation absorbs it there after this cleanup has run.
		try
		{
			host.setBusyUi(true);
			host.showProgress("Saving…");
			host.runInBackground(() ->
				runExportBg(uri, isReplaceSave, preserveOnFailure, replacedName, onSavedBg));
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "pre-enqueue UI/dispatch threw; releasing busy flag", e);
			host.getBusy().set(false);
			host.setBusyUi(false);
			host.hideProgress();
			deletePlaceholderOnAbort(uri, isReplaceSave);
			throw e;
		}
	}

	/**
	 * Phase 4 — failure report. Toasts the exception message when available; deletes the partially-written SAF doc
	 * so a failed save doesn't leave a truncated file behind — but ONLY when `preserveOnFailure` is false. When the
	 * URI pointed at an existing file before the write (provider-confirmed overwrite), deletion would turn the
	 * verification failure into data loss of the user's original; we leave the (now partial) file on disk and rely
	 * on the failure toast so the user knows to re-save. Partial-write corruption stays preferable to outright
	 * deletion of a file the user didn't explicitly tell us to destroy.
	 *
	 * @param uri               target of the failed write; deleted unless preserveOnFailure
	 * @param writeException    exception from the write phase, embedded in the toast when non-null
	 * @param preserveOnFailure true leaves the (possibly partial) file on disk — the URI held prior content
	 */
	private void reportFailure(Uri uri, Exception writeException, boolean preserveOnFailure)
	{
		// LENGTH_LONG so the user can read the embedded exception message + suggested action ("try a smaller
		// crop", "permission denied", etc.). The SHORT default would clip ~50 chars in.
		final String emsg = writeException != null
			? "Export failed: " + writeException.getMessage()
			: "Export failed";
		host.runOnUiThread(() -> host.toastIfAlive(emsg, Toast.LENGTH_LONG));
		if (preserveOnFailure)
		{
			Log.w(TAG, "preserving " + uri + " on failure (had prior content)");
			return;
		}
		deleteDocAndUnjournalTemp(uri);
	}

	/**
	 * Phase 4 — overwrite-specific success report. Fires "Replaced <name>" when the caller is the opaque-ID
	 * overwrite-fallback path that direct-wrote to the target with preserve-on-failure (a real overwrite, not a
	 * fresh save), and deserves a matching user message. The full Replace flow (with placeholder + swap) does not
	 * use this path — its success toast is fired by ReplaceStrategy after verifyReplace confirms the final state.
	 *
	 * @param name user-facing display name of the overwritten file, embedded in the toast
	 */
	private void reportReplaced(String name)
	{
		final String msg = "Replaced " + name;
		host.runOnUiThread(() -> host.toastIfAlive(msg, Toast.LENGTH_SHORT));
	}

	/**
	 * Phase 4 — success report. "Saved NKB [HDR OK]" / "[HDR dropped]" on a save that dropped an HDR source (only
	 * for JPEG — PNG can't carry gain maps, which is a format limitation, not a failure, so the suffix is
	 * suppressed).
	 *
	 * `hdrAttached` is the structural answer threaded back through ExportResult — true when the encode actually
	 * appended the Ultra HDR gain map, false on the HDR-drop path (gain-map render failed, MPF patch rejected,
	 * etc.) and on the bypass path when srcHadHdr is false. The structural flag is the only trustworthy signal: a
	 * full-file substring scan for "hdrgm" false-positives on preserved trailers, stale metadata, and Extended-XMP
	 * segments.
	 *
	 * @param size         encoded output size in bytes (used for the "Saved N KB" toast)
	 * @param srcHadHdr    true when the source carried a gain map (gates whether to show any HDR suffix)
	 * @param isPng        true for PNG output (no HDR suffix regardless — PNG can't carry gain maps)
	 * @param hdrAttached  true when the output actually carries the Ultra HDR gain map (drives [HDR OK]
	 *                     vs [HDR dropped])
	 */
	private void reportSuccess(long size, boolean srcHadHdr, boolean isPng, boolean hdrAttached)
	{
		final String msg = "Saved " + size / 1024 + "KB" + hdrSuffix(srcHadHdr, isPng, hdrAttached);
		host.runOnUiThread(() -> host.toastIfAlive(msg, Toast.LENGTH_SHORT));
	}

	/**
	 * Background-thread body of exportTo: runs the encode/write/verify pipeline, fires the optional onSavedBg
	 * callback when the write verifies, and releases the busy flag in finally so a thrown callback doesn't strand
	 * Save / Open behind a permanent "Busy" toast.
	 *
	 * The replace callback owns its own success/failure messaging, so doExport suppresses the "Saved" toast when
	 * onSavedBg is set. If the callback itself throws before firing its outcome message, that suppression would
	 * otherwise leave the user with no feedback at all (silent save). Catch + log + post a generic failure toast so
	 * the user knows something went wrong; the exception detail is in the log.
	 *
	 * @param uri               SAF URI to write to
	 * @param isReplaceSave     true when this export is the placeholder write of a Replace flow
	 * @param preserveOnFailure when verifyPhase rejects the write, true leaves the partial file on disk
	 * @param replacedName      when non-null AND isReplaceSave is false, success emits "Replaced <name>"
	 * @param onSavedBg         optional post-write callback invoked with the verified bytes; null for plain saves
	 */
	@WorkerThread
	private void runExportBg(Uri uri, boolean isReplaceSave, boolean preserveOnFailure, String replacedName,
		Consumer<ExportResult> onSavedBg)
	{
		ExportResult result = null;
		try
		{
			result = doExport(uri, isReplaceSave, preserveOnFailure, replacedName).orElse(null);
			if (result != null && onSavedBg != null)
			{
				try
				{
					onSavedBg.accept(result);
				}
				catch (Exception | OutOfMemoryError e)
				{
					// OOM widening matches encodePhase's catch: even with the payload streaming
					// from disk, the Replace callback runs on a heap near its cap on 200 MP saves
					// and its readback / dialog allocations can still fail. Catching it surfaces
					// the failure toast and lets the finally release busy + delete the tempfile,
					// instead of the worker dying with an uncaught Error and no user feedback.
					Log.w(TAG, "post-save step threw", e);
					host.runOnUiThread(() -> host.toastIfAlive(
						"Save step failed — check log", Toast.LENGTH_LONG));
				}
			}
		}
		finally
		{
			// Tempfile cleanup for the Replace flow (isReplaceSave=true). doExport's own finally skips
			// deletion when isReplaceSave is true so the callback can stream from the tempfile; runExportBg
			// deletes it here once the callback has returned. The bytes-mode result has tempfile() == null
			// so deletion is a no-op.
			if (isReplaceSave && result != null && result.tempfile() != null)
			{
				SaveTempFiles.deleteQuietly(result.tempfile(), TAG, "encode tempfile post-callback");
			}
			// Clear busy LAST, on the UI thread, after the UI teardown — see EditorHost.finishBusy.
			// Clearing on the bg thread before the posted teardown would let an onNewIntent load acquire
			// busy in the gap and then get unmasked by this save's pending teardown.
			host.finishBusy();
		}
	}

	/**
	 * Direct file I/O via a temp sibling + fsync + atomic rename. Used when the caller flagged
	 * `preserveOnFailure=true` — the target URI may already contain user data, so opening it in truncate mode is
	 * unsafe (a disk-full / I/O error between open and full-write would leave the original zeroed). Writes the
	 * payload to a hidden SaveTempFiles.tempName sibling in the same parent directory, fsyncs, confirms byte count,
	 * and atomic-moves the temp onto the target via Files.move with ATOMIC_MOVE + REPLACE_EXISTING — target content
	 * stays intact on any pre-move failure. The temp's path is journaled (SaveController.journalInflightTemp)
	 * before the file is created and un-journaled on every disposal below, so a hard kill mid-write leaves a
	 * sweepable journal entry in the target's own folder rather than a permanently stranded hidden temp.
	 *
	 * On an empty return, writePhase converts it into a synthetic failed WriteOutcome rather than falling through
	 * to the SAF stream path — SAF "w" truncation behavior is provider-dependent (some truncate at open time), so a
	 * mid-stream failure after fall-through could zero the user's original. Surfaces as a clean "Export failed"
	 * toast with the target intact. When preserveOnFailure=false, this method isn't called at all — writePhase
	 * takes the simpler truncate-mode direct write since the URI is a known-fresh placeholder (Replace flow, plain
	 * Save As).
	 *
	 * @param target  resolved filesystem path of the save target; may hold user data — never truncated
	 * @param encoded verified payload to deliver (bytes-mode or streamed from its tempfile)
	 * @return success outcome (directPath set, writeReturned true); empty when any step failed with the
	 *         target left intact
	 */
	private Optional<WriteOutcome> tryDirectAtomicWrite(File target, ExportResult encoded)
	{
		File parent = target.getParentFile();
		if (parent == null)
		{
			return Optional.empty();
		}
		long expected = encoded.size();
		File tempFile = new File(parent, SaveTempFiles.tempName(target.getName()));
		// Journal the temp's path BEFORE creating the file: a hard kill at any later point strands the temp
		// in `parent`, which need not be the folder the startup sweep's last-save delete pass visits. A
		// failed journal commit never aborts the save (journalInflightTemp logs it) — every non-kill path
		// below disposes the temp in-process, so the journal only covers the kill window.
		SaveController.journalInflightTemp(host.getActivity(), parent, tempFile.getName());
		try (FileOutputStream fos = new FileOutputStream(tempFile))
		{
			writePayloadToStream(encoded, fos);
			fos.getFD().sync();
		}
		catch (Exception e)
		{
			Log.w(TAG, "Atomic write temp " + tempFile + " failed; target preserved: " + e.getMessage());
			discardInflightTemp(tempFile, "atomic-write failed write");
			return Optional.empty();
		}
		long written = tempFile.length();
		if (written != expected)
		{
			Log.w(TAG, "Atomic write temp produced " + written + " bytes; expected " + expected);
			discardInflightTemp(tempFile, "atomic-write short write");
			return Optional.empty();
		}
		try
		{
			Files.move(tempFile.toPath(), target.toPath(),
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (Exception e)
		{
			Log.w(TAG, "Atomic swap " + tempFile + " → " + target + " failed; target preserved: "
				+ e.getMessage());
			discardInflightTemp(tempFile, "atomic-write failed swap");
			return Optional.empty();
		}
		// The move disposed the temp name — drop its journal entry.
		SaveController.unjournalInflightTemp(host.getActivity(), parent, tempFile.getName());
		// Sync parent directory metadata so the rename's directory-entry update reaches disk. POSIX guarantees
		// `Files.move(ATOMIC_MOVE)` is atomic for visibility, but the dir-entry inode pointer only lives in the
		// page cache until the parent's metadata is fsynced — a power loss in that window can revert the dir
		// entry to the pre-write inode (target ships old content) or leave it unflushed (target appears empty
		// to next-boot VFS walks). The fos.getFD().sync() upstream covered the FILE inode; this covers the
		// DIRECTORY. Skip silently on failure — the rename already landed in the page cache, and parent-fsync
		// failure on a normal filesystem is rare enough not to block the save success path.
		try (FileChannel parentChannel = FileChannel.open(parent.toPath(), StandardOpenOption.READ))
		{
			parentChannel.force(true);
		}
		catch (Exception e)
		{
			Log.w(TAG, "Parent directory fsync failed for " + parent + ": " + e.getMessage());
		}
		Log.d(TAG, "Atomic direct write delivered " + expected + " bytes to " + target);
		if (!target.setLastModified(System.currentTimeMillis()))
		{
			Log.w(TAG, "setLastModified failed on " + target);
		}
		MediaScannerConnection.scanFile(host.getActivity().getApplicationContext(),
			new String[] { target.getAbsolutePath() }, null, null);
		return Optional.of(new WriteOutcome(null, target, true));
	}

	/**
	 * Phase 3 — verify. Trust a clean write only after a size query confirms the on-disk file matches the payload
	 * length: some SAF providers don't truncate on "w" mode, so a shorter rewrite leaves stale trailing bytes and
	 * the write returns cleanly over a corrupt file. Drop to byte-by-byte readback when the size query disagrees,
	 * fails / omits SIZE, or the write path threw (providers often throw a harmless EPIPE on close yet persist the
	 * full payload — readback is ground truth). Returns true when good.
	 *
	 * Direct-file shortcut: when writePhase took the direct FileOutputStream + fsync branch, the SAF provider's
	 * cached SIZE may lag the on-disk bytes (the post-write MediaScanner is async), so a SAF SIZE query can return
	 * the pre-write count and false-fail — after which reportFailure deletes a good file. When directPath is set,
	 * skip the SAF query and verify via File.length(); the fsync guarantees the inode is committed.
	 *
	 * Deliberate trade-off: when the write didn't throw AND SIZE matches, trust the provider and skip content
	 * readback. A provider returning success + correct count but storing same-length wrong content would slip
	 * through — accepted because it requires active provider corruption (no real provider does this), always-verify
	 * would double replace-save I/O, and the readback path still covers every SIZE-omitted / mismatched /
	 * write-threw branch (all real failure modes seen in the wild).
	 *
	 * @param uri     save target to verify (SAF SIZE query / content-readback side)
	 * @param encoded payload the write should have delivered — supplies the expected size and the readback
	 *                source (in-heap bytes or tempfile)
	 * @param write   phase-2 outcome: a non-null directPath switches to File.length() verification; a false
	 *                writeReturned forces content readback
	 * @return true when the on-disk content is confirmed to match the payload
	 */
	private boolean verifyPhase(Uri uri, ExportResult encoded, WriteOutcome write)
	{
		long expected = encoded.size();
		if (write.directPath() != null && write.writeReturned())
		{
			// Direct file I/O path: fos.getFD().sync() before close committed the bytes to the inode; trust
			// the kernel-level length over the SAF provider's cached metadata.
			long fileLen = write.directPath().length();
			boolean directOk = fileLen == expected;
			Log.d(TAG, "Direct-path verify: file=" + write.directPath() + " length=" + fileLen
				+ " expected=" + expected + " → savedOk=" + directOk);
			return directOk;
		}
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
			else if (sizeCheck != expected)
			{
				Log.w(TAG, "Clean write but size " + sizeCheck + " != expected "
					+ expected + " — provider may not have truncated; verifying content");
				savedOk = false;
			}
		}
		if (!savedOk)
		{
			// Content-verify path. For bytes-mode, readbackByteCount byte-walks the SAF URI against the
			// in-heap byte[]. For tempfile-mode (PNG streaming, byte[] would OOM on 200 MP PNG where the
			// payload is 400-600 MB), stream-compare the SAF URI against the tempfile in 64 KB chunks
			// without ever materialising the encoded bytes — same byte-equality contract as
			// `readbackByteCount`, just sourced from disk instead of heap.
			if (encoded.bytes() != null)
			{
				verifiedBytes = safFiles.readbackByteCount(uri, encoded.bytes());
			}
			else
			{
				verifiedBytes = streamCompareUriToFile(uri, encoded.tempfile(), expected);
			}
			savedOk = verifiedBytes == expected;
			if (savedOk)
			{
				Log.d(TAG, "Recovered via content-verified readback: " + verifiedBytes + " bytes");
			}
		}
		Log.d(TAG, "Save result: writeReturned=" + write.writeReturned()
			+ " sizeCheck=" + sizeCheck + " verifiedBytes=" + verifiedBytes
			+ " expected=" + expected + " → savedOk=" + savedOk);
		return savedOk;
	}

	/**
	 * Phase 2 — write. Tries a direct FileOutputStream first when the URI resolves to a real path via
	 * SafFileHelper.fileFromSafUri (needs MANAGE_EXTERNAL_STORAGE), bypassing the SAF stream. On Samsung the SAF
	 * stream path silently corrupts writes: openOutputStream("w") returns success and the correct byte count, but
	 * the disk content never changes (the provider buffers and never flushes), so Replace then propagates stale
	 * bytes onto the target. Direct I/O lands bytes via the kernel where the provider's caching can't intercept;
	 * the fsync forces durability before close, and a trailing MediaScannerConnection.scanFile reindexes MediaStore
	 * so Gallery sees the new content.
	 *
	 * Two direct-write modes:
	 *   - preserveOnFailure=true (overwrite / preserving): the URI may hold user data. Routes through
	 *     tryDirectAtomicWrite (temp sibling + fsync + atomic rename) so a mid-write failure leaves the
	 *     original intact — a plain FileOutputStream(target) would truncate at open and a disk-full error
	 *     would zero the user's file.
	 *   - preserveOnFailure=false (fresh placeholder / Save As): truncate-mode direct write is safe with no
	 *     prior data to lose.
	 *
	 * Falls back to the SAF stream when fileFromSafUri returns empty (cloud / opaque-ID / non-primary volumes) —
	 * those aren't Samsung MediaStore and don't show the silent-corruption pattern.
	 *
	 * Close-only failures: writeReturned is set AFTER the try-with-resources as (bodyWritten AND no exception), so
	 * a close throw flips it back to false even on a clean body — forcing verifyPhase to content-readback rather
	 * than trust SIZE, since some Samsung providers only flush in close() (close-throw means the bytes never
	 * reached disk).
	 *
	 * @param uri               save target
	 * @param encoded           payload to write (bytes-mode or streamed from its tempfile)
	 * @param preserveOnFailure true routes the direct branch through tryDirectAtomicWrite and forbids the
	 *                          truncate-mode fallbacks that could zero an existing file
	 * @return write outcome; never null — failures are encoded in exception / writeReturned
	 */
	private WriteOutcome writePhase(Uri uri, ExportResult encoded, boolean preserveOnFailure)
	{
		File directPath = safFiles.fileFromSafUri(uri).orElse(null);
		if (directPath != null)
		{
			if (preserveOnFailure)
			{
				Optional<WriteOutcome> atomic = tryDirectAtomicWrite(directPath, encoded);
				if (atomic.isPresent())
				{
					return atomic.orElseThrow();
				}
				// Atomic path failed AND preserveOnFailure is true — surface as a clean save failure
				// rather than falling through to openOutputStream("w") on the SAME possibly-existing
				// target. SAF "w" truncation behavior is provider-dependent: some providers truncate
				// the file at open time, so a write that fails mid-stream after the truncate-on-open
				// leaves the user's original zeroed or partially overwritten. That defeats the whole
				// point of taking the preserveOnFailure branch — the caller (exportToOverwrite /
				// exportToPreserving) flagged the URI as possibly containing user data we don't want to
				// destroy. Synthesizing a writeException + writeReturned=false routes through
				// verifyPhase's failure path, which calls reportFailure with preserveOnFailure=true
				// (per doExport's argument-passing), so the target on disk stays intact.
				return new WriteOutcome(new IOException(
					"Atomic direct-write to " + directPath + " failed; refusing to fall through to "
						+ "SAF truncate-mode write on preserveOnFailure=true target"),
					null, false);
			}
			else
			{
				try (FileOutputStream fos = new FileOutputStream(directPath))
				{
					long written = writePayloadToStream(encoded, fos);
					fos.getFD().sync();
					Log.d(TAG, "Direct file I/O wrote " + written + " bytes to " + directPath);
					// Force mtime update even when the new bytes match the old bytes. Samsung's
					// FUSE-backed scoped storage skips mtime refresh on content-identical writes
					// (likely dedup at the FUSE layer) — userspace observers see the old mtime,
					// creating ambiguity about whether the save actually ran. setLastModified after
					// the write + sync forces the timestamp regardless of dedup behaviour, so the
					// user always has a concrete signal that the save landed — deterministic encode
					// of identical inputs produces identical bytes, but mtime should still reflect
					// the save.
					if (!directPath.setLastModified(System.currentTimeMillis()))
					{
						Log.w(TAG, "setLastModified failed on " + directPath);
					}
					// Trigger MediaStore reindex so the new content surfaces in gallery apps. Use
					// the application context so a config-change race during the scan doesn't keep
					// a destroyed Activity alive (mirrors ReplaceStrategy.replaceViaFileIo's
					// pattern).
					MediaScannerConnection.scanFile(host.getActivity().getApplicationContext(),
						new String[] { directPath.getAbsolutePath() }, null, null);
					return new WriteOutcome(null, directPath, true);
				}
				catch (Exception e)
				{
					Log.w(TAG, "Direct file I/O failed; falling through to SAF stream: "
						+ e.getMessage());
				}
			}
		}
		boolean writeReturned = false;
		Exception writeException = null;
		// IMPORTANT: do NOT set writeReturned=true inside the try-with-resources block. Some SAF providers
		// buffer the entire write and only flush in close(), so a close() throw means the bytes never reached
		// disk even though `writePayloadToStream` returned cleanly. If we set the success flag before close, a
		// close throw would still mark the write returned + clean, and verifyPhase would trust the
		// OpenableColumns.SIZE size match (provider can report the expected size even though the bytes were
		// never flushed) and skip the content readback. Tracking close success in a separate flag and ANDing it
		// with the body-success flag forces the readback fallback in any close-throw case.
		boolean bodyWritten = false;
		try (OutputStream os = host.getActivity().getContentResolver().openOutputStream(uri, "w"))
		{
			if (os == null)
			{
				throw new IOException("openOutputStream returned null");
			}
			writePayloadToStream(encoded, os);
			bodyWritten = true;
		}
		catch (Exception e)
		{
			writeException = e;
			Log.w(TAG, "Write path threw (may still have persisted)", e);
		}
		// writeReturned reflects "the body wrote AND close did not throw". A close-only failure
		// (bodyWritten=true && writeException!=null) flips writeReturned back to false so verifyPhase takes the
		// content-readback path rather than trusting a possibly-stale size.
		writeReturned = bodyWritten && writeException == null;
		return new WriteOutcome(writeException, null, writeReturned);
	}

}
