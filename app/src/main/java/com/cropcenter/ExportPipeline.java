package com.cropcenter;

import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.cropcenter.crop.CropExporter;
import com.cropcenter.model.ExportConfig;
import com.cropcenter.util.ByteBufferUtils;
import com.cropcenter.util.SafFileHelper;
import com.cropcenter.util.UltraHdrCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
		exportTo(uri, null);
	}

	/**
	 * Encode-and-write on a background thread. If onSavedBg is non-null and the write verifies
	 * successfully, it runs on the SAME background thread before the busy flag is released. The
	 * callback receives the exact bytes that were written — so the Replace flow can drop them
	 * straight onto the target file instead of re-reading the placeholder (FUSE/MediaStore
	 * caching can make that read unreliable). Because onSavedBg's follow-up work may still fail
	 * (e.g. Replace leaves an extra copy), doExport's "Saved" toast is suppressed when onSavedBg
	 * is present — the callback issues its own final-outcome message.
	 */
	void exportTo(Uri uri, Consumer<byte[]> onSavedBg)
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
		host.setBusyUi(true);
		host.showProgress("Saving\u2026");
		host.runInBackground(() ->
		{
			try
			{
				byte[] data = doExport(uri, onSavedBg != null);
				if (data != null && onSavedBg != null)
				{
					try
					{
						onSavedBg.accept(data);
					}
					catch (Exception e)
					{
						Log.w(TAG, "post-save step threw", e);
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
	 * `suppressSuccessToast` is set by the Replace flow so doExport doesn't announce "Saved" for
	 * what is only the placeholder write — the Replace swap that follows may still fail, and
	 * verifyReplace will fire the real outcome message. Failure toasts always fire regardless.
	 */
	private byte[] doExport(Uri uri, boolean suppressSuccessToast)
	{
		boolean isPng = ExportConfig.FORMAT_PNG.equals(host.getState().getExportConfig().format());

		// ── Phase 1: encode ──
		// Backup writing used to live in this phase but now runs only when the Replace flow
		// needs it (ReplaceStrategy.replaceColliding). Plain Save As / Keep paths no longer
		// pay the backup I/O for a save that isn't actually overwriting the original.
		byte[] data;
		boolean srcHadHdr;
		try
		{
			data = CropExporter.export(host.getState(), host.getActivity().getCacheDir()).data();
			srcHadHdr = host.getState().getGainMap() != null
				&& host.getState().getGainMap().length > 0;
			Log.d(TAG, "Encoded " + data.length + " bytes (srcHdr=" + srcHadHdr
				+ " isPng=" + isPng + ")");
		}
		catch (Exception e)
		{
			Log.e(TAG, "Encode failed", e);
			final String emsg = "Export failed: " + e.getMessage();
			host.runOnUiThread(() -> host.toastIfAlive(emsg, Toast.LENGTH_SHORT));
			return null;
		}

		// ── Phase 2: write ──
		// try-with-resources: close() runs after writeReturned=true, so close failures can't
		// invalidate a successful write (the catch block sets writeException but writeReturned is
		// already locked true).
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

		// ── Phase 3: verify ──
		// If the write returned cleanly, trust it. If it threw, read the file back and
		// compare byte-for-byte against what we meant to write — this is the ground-truth
		// verification because SAF providers often throw on close yet persist the full payload.
		boolean savedOk = writeReturned;
		long verifiedBytes = -1;
		if (!savedOk)
		{
			verifiedBytes = readbackByteCount(uri, data);
			savedOk = verifiedBytes == data.length;
			if (savedOk)
			{
				Log.d(TAG, "Recovered via content-verified readback: " + verifiedBytes + " bytes");
			}
		}
		Log.d(TAG, "Save result: writeReturned=" + writeReturned
			+ " verifiedBytes=" + verifiedBytes + " expected=" + data.length
			+ " → savedOk=" + savedOk);

		// ── Phase 4: report ──
		if (savedOk)
		{
			// HDR suffix is informational. PNG can't carry gain maps — that's a format limitation,
			// NOT a failure, so suppress the suffix in that case. "[HDR dropped]" only fires when
			// JPEG export dropped an HDR source.
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

			if (!suppressSuccessToast)
			{
				final String msg = "Saved " + data.length / 1024 + "KB" + hdrSuffix;
				host.runOnUiThread(() -> host.toastIfAlive(msg, Toast.LENGTH_SHORT));
			}
			return data;
		}
		final String emsg = writeException != null
			? "Export failed: " + writeException.getMessage()
			: "Export failed";
		host.runOnUiThread(() -> host.toastIfAlive(emsg, Toast.LENGTH_SHORT));
		safFiles.tryDeleteSafDocument(uri);
		return null;
	}

	/**
	 * Byte-for-byte verify the file at `uri` matches `expected`. Used as ground-truth verification
	 * when the write path threw — many SAF providers throw harmless EPIPE/IOException on close yet
	 * persist the full payload. A byte-count match was previously considered "good enough" but that
	 * can mask truncation/corruption where the stored byte count happens to reach `expected.length`
	 * with divergent content, so we now compare content too. Returns:
	 *   full bytes verified equal  → expected.length (save ok)
	 *   any mismatch or short file → number of bytes read before divergence/EOF (save failed)
	 *   provider can't serve file  → -1
	 */
	private long readbackByteCount(Uri uri, byte[] expected)
	{
		long total = 0;
		try (InputStream is = host.getActivity().getContentResolver().openInputStream(uri))
		{
			if (is == null)
			{
				return -1;
			}
			byte[] buf = new byte[ByteBufferUtils.IO_BUFFER];
			int n;
			while ((n = is.read(buf)) != -1)
			{
				if (total + n > expected.length)
				{
					// Trailing bytes beyond what we wrote — treat as corruption.
					Log.w(TAG, "readback: provider returned more bytes than written");
					return total;
				}
				for (int i = 0; i < n; i++)
				{
					if (buf[i] != expected[(int) total + i])
					{
						Log.w(TAG, "readback: byte mismatch at offset " + (total + i));
						return total + i;
					}
				}
				total += n;
				if (total == expected.length)
				{
					// Verify nothing follows the expected bytes.
					int trailing = is.read(buf);
					if (trailing > 0)
					{
						Log.w(TAG, "readback: unexpected trailing " + trailing + " bytes");
						return total;
					}
					return total;
				}
			}
			return total;
		}
		catch (Exception e)
		{
			Log.w(TAG, "readbackByteCount: " + e.getMessage());
			return total > 0 ? total : -1;
		}
	}
}
