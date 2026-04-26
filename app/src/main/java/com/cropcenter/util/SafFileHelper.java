package com.cropcenter.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * SAF / MediaStore URI utilities extracted from MainActivity. All methods are resilient to
 * SecurityException and provider quirks — callers get null / false on failure rather than
 * exceptions, and log detail is captured at warn level.
 */
public final class SafFileHelper
{
	// Upper bound on readUriBytes input size. Modern HDR + gain-map JPEGs land well under
	// 64 MiB; this cap catches pathological inputs before they OOM the heap or overflow the
	// int cast in the size-to-length conversion.
	public static final long MAX_READ_BYTES = 128L * 1024 * 1024;

	private static final String TAG = "SafFileHelper";

	private final Context ctx;

	public SafFileHelper(Context ctx)
	{
		this.ctx = ctx;
	}

	/**
	 * Stream the contents of `src` into `dst`, truncating whatever was at `dst`. Returns true
	 * on a fully successful copy, false on any error (permission denied, provider doesn't grant
	 * sibling write access, etc.). Used by the Replace flow to overwrite the original file's URI
	 * directly — skipping the delete/rename dance that some providers silently fail.
	 */
	public boolean copyUriContents(Uri src, Uri dst)
	{
		try (InputStream in = ctx.getContentResolver().openInputStream(src);
				OutputStream out = ctx.getContentResolver().openOutputStream(dst, "w"))
		{
			if (in == null || out == null)
			{
				return false;
			}
			byte[] buf = new byte[ByteBufferUtils.IO_BUFFER];
			int n;
			while ((n = in.read(buf)) != -1)
			{
				out.write(buf, 0, n);
			}
			return true;
		}
		catch (Exception e)
		{
			Log.w(TAG, "copyUriContents " + src + " -> " + dst + " failed: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Programmatically create a new SAF document in the same directory as `docUri`, with
	 * `placeholderName` and `mimeType`. Used by the Save flow to turn a provider-confirmed
	 * overwrite (SAF ACTION_CREATE_DOCUMENT that returned an existing document rather than
	 * auto-renaming) into the crash-safe Replace pattern: write+verify the placeholder,
	 * then swap onto the target.
	 *
	 * Derives the parent document URI from `docUri`'s document ID by stripping the last
	 * `/`-delimited segment — works for path-addressed providers (ExternalStorageProvider's
	 * "primary:Pictures/foo.jpg"). Returns null when `docUri` has no document ID, an opaque
	 * ID without slashes, or when the provider rejects createDocument (doesn't support
	 * FLAG_DIR_SUPPORTS_CREATE). The caller must have a fallback plan for null.
	 */
	public Uri createSiblingPlaceholder(Uri docUri, String mimeType, String placeholderName)
	{
		try
		{
			String docId = DocumentsContract.getDocumentId(docUri);
			if (docId == null)
			{
				return null;
			}
			int slash = docId.lastIndexOf('/');
			if (slash <= 0)
			{
				return null;
			}
			String parentDocId = docId.substring(0, slash);
			Uri parentUri = DocumentsContract.buildDocumentUri(
				docUri.getAuthority(), parentDocId);
			return DocumentsContract.createDocument(
				ctx.getContentResolver(), parentUri, mimeType, placeholderName);
		}
		catch (Exception e)
		{
			Log.w(TAG, "createSiblingPlaceholder " + placeholderName + " failed: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Build a sibling document URI by swapping the last path segment of src's document ID for
	 * siblingName. Works on providers that encode paths in their document IDs (notably the
	 * built-in external-storage provider). Returns null for opaque-ID providers or providers
	 * that don't expose a document ID.
	 */
	public Uri deriveSiblingUri(Uri src, String siblingName)
	{
		try
		{
			String docId = DocumentsContract.getDocumentId(src);
			if (docId == null)
			{
				return null;
			}
			int slash = docId.lastIndexOf('/');
			if (slash < 0)
			{
				return null;
			}
			return DocumentsContract.buildDocumentUri(src.getAuthority(),
				docId.substring(0, slash + 1) + siblingName);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * Translate a SAF or MediaStore URI to a java.io.File in shared storage. Returns null when
	 * the URI isn't path-addressable. Supported docId formats:
	 *   - "primary:relative/path/file.ext"  — ExternalStorageProvider (DCIM, Pictures, …)
	 *   - "raw:/absolute/filesystem/path"   — DownloadStorageProvider when the file lives on
	 *                                         the real filesystem (Download/...). The "raw"
	 *                                         prefix is literal — the rest is an absolute path.
	 * Plus a MediaStore _data column fallback. getFilePathAndId can throw SecurityException for
	 * URIs the app doesn't have active read permission on (common post-uninstall/reinstall for
	 * non-app-owned documents) — that's expected, we just return null and let the SAF paths
	 * try their luck.
	 */
	public File fileFromSafUri(Uri uri)
	{
		String docId = null;
		try
		{
			if (DocumentsContract.isDocumentUri(ctx, uri))
			{
				docId = DocumentsContract.getDocumentId(uri);
			}
		}
		catch (Exception ignored)
		{
			// DocumentsContract misbehaved — fall through to the MediaStore fallback.
		}
		int colon = (docId == null) ? -1 : docId.indexOf(':');
		if (colon > 0)
		{
			String volume = docId.substring(0, colon);
			String tail = docId.substring(colon + 1);
			if ("primary".equalsIgnoreCase(volume))
			{
				File primaryRoot = Environment.getExternalStorageDirectory();
				return new File(primaryRoot, tail);
			}
			// DownloadStorageProvider "raw:<absolute path>" — use the path as-is.
			if ("raw".equalsIgnoreCase(volume))
			{
				return new File(tail);
			}
		}
		// Fall back to MediaStore _data column.
		String[] pathAndId = getFilePathAndId(uri);
		if (pathAndId != null && pathAndId[0] != null)
		{
			return new File(pathAndId[0]);
		}
		return null;
	}

	public String getDisplayName(Uri uri)
	{
		try (Cursor cursor = ctx.getContentResolver().query(uri,
			new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null))
		{
			if (cursor != null && cursor.moveToFirst())
			{
				int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (idx >= 0)
				{
					return cursor.getString(idx);
				}
			}
		}
		catch (SecurityException ignored)
		{
			// Expected when the app doesn't hold read permission for this URI (common for
			// sibling/derived URIs we constructed ourselves).
		}
		catch (Exception e)
		{
			Log.w(TAG, "getDisplayName query failed for " + uri, e);
		}
		return null;
	}

	/**
	 * Query MediaStore for file path and _ID. Returns [path, id] or null.
	 */
	public String[] getFilePathAndId(Uri uri)
	{
		final String COL_ID = "_id";
		final String COL_DATA = "_data";
		try
		{
			try (Cursor cursor = ctx.getContentResolver().query(uri,
				new String[] { COL_ID, COL_DATA }, null, null, null))
			{
				if (cursor != null && cursor.moveToFirst())
				{
					int idIdx = cursor.getColumnIndex(COL_ID);
					int dataIdx = cursor.getColumnIndex(COL_DATA);
					String id = idIdx >= 0 ? cursor.getString(idIdx) : null;
					String path = dataIdx >= 0 ? cursor.getString(dataIdx) : null;
					if (path != null && id != null)
					{
						Log.d(TAG, "MediaStore: path=" + path + " id=" + id);
						return new String[] { path, id };
					}
				}
			}
			// For SAF URIs, try to extract document ID and look up path in MediaStore
			if ("com.android.providers.media.documents".equals(uri.getAuthority()))
			{
				String docId = DocumentsContract.getDocumentId(uri);
				if (docId != null && docId.startsWith("image:")) // docId format: "image:12345"
				{
					String msId = docId.substring(6);
					Uri msUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
					String[] projection = { COL_DATA };
					String[] selectionArgs = { msId };
					try (Cursor cursor = ctx.getContentResolver().query(msUri, projection,
						COL_ID + "=?", selectionArgs, null))
					{
						if (cursor != null && cursor.moveToFirst())
						{
							String path = cursor.getString(0);
							if (path != null)
							{
								return new String[] { path, msId };
							}
						}
					}
				}
			}
		}
		catch (SecurityException ignored)
		{
			// Expected when the URI belongs to a provider we don't hold read permission on
			// (e.g. constructed sibling of a foreign document).
		}
		catch (Exception e)
		{
			Log.w(TAG, "getFilePathAndId failed", e);
		}
		return null;
	}

	/**
	 * Stream probe: opens `uri` for reading and returns true when we can read at least one
	 * byte. Used as a fallback signal for providers that don't expose OpenableColumns.SIZE
	 * (querySafFileSize returns -1): for a fresh-created document, the stream yields EOF
	 * immediately; for an existing non-empty document, we get at least one byte back. Can't
	 * disambiguate empty-fresh from empty-existing — both return false — which matches the
	 * inherent SAF ambiguity at that point. Exceptions (provider refuses open, security
	 * check) surface as false; callers treat false as "can't prove there's content" and
	 * decide their own fallback posture.
	 */
	public boolean hasExistingContent(Uri uri)
	{
		try (InputStream in = ctx.getContentResolver().openInputStream(uri))
		{
			return in != null && in.read() != -1;
		}
		catch (Exception e)
		{
			Log.w(TAG, "hasExistingContent probe failed: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Query the SAF document size via OpenableColumns.SIZE. Much cheaper than a full content
	 * readback — a single metadata query against the provider. Returns the reported size, or
	 * -1 when the provider doesn't expose SIZE (some MediaStore paths omit it, some third-party
	 * providers return a cursor with no rows). Callers treat -1 as "size unknown" — up to them
	 * whether that means trust the write or fall through to full verification.
	 */
	public long querySafFileSize(Uri uri)
	{
		try (Cursor cursor = ctx.getContentResolver().query(uri,
			new String[] { OpenableColumns.SIZE }, null, null, null))
		{
			if (cursor != null && cursor.moveToFirst())
			{
				int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
				if (sizeIdx >= 0 && !cursor.isNull(sizeIdx))
				{
					return cursor.getLong(sizeIdx);
				}
			}
		}
		catch (Exception e)
		{
			Log.w(TAG, "querySafFileSize " + uri + " failed: " + e.getMessage());
		}
		return -1;
	}

	/**
	 * Byte-for-byte verify the file at `uri` matches `expected`. Ground-truth content
	 * verification — used when a write path threw a harmless EPIPE/IOException on close
	 * yet persisted the full payload, or when a cheap size-only check isn't enough to
	 * prove the bytes on disk are really the ones we intended to write.
	 *
	 * Returns:
	 *   full bytes verified equal  → expected.length (save ok)
	 *   any mismatch or short file → number of bytes read before divergence/EOF (save failed)
	 *   trailing bytes (provider didn't truncate) → expected.length + trailing (save failed)
	 *   provider can't serve file, or EOF check threw before confirming no trailing bytes → -1
	 * Callers MUST use strict equality against expected.length — any other result means
	 * the save is unverified, regardless of whether the numeric value is higher or lower.
	 */
	public long readbackByteCount(Uri uri, byte[] expected)
	{
		long total = 0;
		try (InputStream is = ctx.getContentResolver().openInputStream(uri))
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
					// All bytes matched. Confirm EOF — trailing bytes would mean a stale
					// longer payload wasn't truncated. Wrap the EOF-check read in its OWN
					// try so a throw here doesn't fall through to the outer catch, which
					// returns `total` (== expected.length) and would falsely claim the
					// save is verified despite never confirming EOF.
					int trailing;
					try
					{
						trailing = is.read(buf);
					}
					catch (Exception eofException)
					{
						Log.w(TAG, "readback: EOF-check threw, treating as unverified: "
							+ eofException.getMessage());
						return -1;
					}
					if (trailing > 0)
					{
						// Provider served MORE than we wrote — stale trailing bytes that
						// never got truncated. MUST NOT return `total` here because
						// callers check `verifiedBytes == expected.length` and would
						// mistake this for a clean save. Return a value > expected.length
						// so equality fails; verifyPhase then reports the save as lost.
						Log.w(TAG, "readback: unexpected trailing " + trailing + " bytes");
						return total + trailing;
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

	/**
	 * Copy the URI to a cache file, then slurp raw bytes. The two-step routing (stream → cache file
	 * → in-memory byte[]) is deliberate: some ContentProviders (notably Samsung MediaStore) strip
	 * post-EOI bytes from JPEGs when streaming, which would lose the HDR gain map. Materialising
	 * to a local file first bypasses that.
	 *
	 * Throws IOException when the input exceeds MAX_READ_BYTES — the byte[] allocation in the
	 * second phase would otherwise risk OutOfMemoryError on mid-range devices and a negative-
	 * size allocation if fileLen exceeded Integer.MAX_VALUE.
	 */
	public byte[] readUriBytes(Uri uri) throws IOException
	{
		// Unique per call — a shared fixed path ("input_raw") would let two overlapping reads
		// corrupt each other's cache file if a second load entry point ever bypassed the
		// Activity's busy gate. createTempFile gives each call its own path by construction;
		// the finally block below deletes it regardless of outcome.
		File cacheFile = File.createTempFile("input_raw_", ".bin", ctx.getCacheDir());
		try
		{
			long written = 0;
			try (InputStream is = ctx.getContentResolver().openInputStream(uri);
					FileOutputStream fos = new FileOutputStream(cacheFile))
			{
				if (is == null)
				{
					throw new IOException("Cannot open URI");
				}
				byte[] buf = new byte[ByteBufferUtils.IO_BUFFER];
				int n;
				while ((n = is.read(buf)) != -1)
				{
					if (written + n > MAX_READ_BYTES)
					{
						throw new IOException("Input exceeds " + MAX_READ_BYTES
							+ " byte limit (stopped at " + (written + n) + ")");
					}
					fos.write(buf, 0, n);
					written += n;
				}
			}
			try (FileInputStream fis = new FileInputStream(cacheFile))
			{
				long fileLen = cacheFile.length();
				if (fileLen <= 0)
				{
					throw new IOException("Empty input: " + fileLen);
				}
				if (fileLen > MAX_READ_BYTES)
				{
					// Redundant with the copy-time check but guards against a TOCTOU where the
					// cache file grew between write-close and length() (shouldn't happen, but
					// the int cast below has no safe failure mode).
					throw new IOException("Cache file too large: " + fileLen + " bytes");
				}
				byte[] bytes = new byte[(int) fileLen];
				int read = 0;
				while (read < bytes.length)
				{
					int n = fis.read(bytes, read, bytes.length - read);
					if (n < 0)
					{
						break;
					}
					read += n;
				}
				return bytes;
			}
		}
		finally
		{
			// Ensure cache file is cleaned even when the read throws.
			if (cacheFile.exists() && !cacheFile.delete())
			{
				Log.d(TAG, "couldn't delete cache file " + cacheFile);
			}
		}
	}

	/**
	 * Best-effort delete of a SAF document URI. Returns true when a provider explicitly
	 * confirmed the deletion (DocumentsContract.deleteDocument returned true, or the
	 * ContentResolver delete reported > 0 rows affected); false when both paths failed OR
	 * silently reported ambiguous results. Callers that NEED the document gone before
	 * proceeding (e.g. Replace flow's placeholder cleanup after a direct SAF overwrite)
	 * must check the return value — a false here means the file may still be on disk,
	 * and short-circuiting the follow-up verifier would claim success while leaving a
	 * duplicate. Callers that only want best-effort cleanup (e.g. post-failure placeholder
	 * sweep) can ignore the result.
	 */
	public boolean tryDeleteSafDocument(Uri uri)
	{
		try
		{
			if (DocumentsContract.isDocumentUri(ctx, uri))
			{
				return DocumentsContract.deleteDocument(ctx.getContentResolver(), uri);
			}
		}
		catch (Exception ignored)
		{
		}
		try
		{
			int rows = ctx.getContentResolver().delete(uri, null, null);
			return rows > 0;
		}
		catch (Exception ignored)
		{
		}
		return false;
	}
}
