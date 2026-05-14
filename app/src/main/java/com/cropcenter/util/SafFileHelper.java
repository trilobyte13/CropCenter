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
 * SAF / MediaStore URI utilities extracted from MainActivity. All methods are resilient to SecurityException and
 * provider quirks — callers get null / false on failure rather than exceptions, and log detail is captured at warn
 * level.
 */
public final class SafFileHelper
{
	private static final String TAG = "SafFileHelper";
	// Upper bound on readUriBytes input size. Modern HDR + gain-map JPEGs land well under 64 MiB; this cap catches
	// pathological inputs before they OOM the heap or overflow the int cast in the size-to-length conversion.
	public static final long MAX_READ_BYTES = 128L * 1024 * 1024;

	private final Context ctx;

	public SafFileHelper(Context ctx)
	{
		this.ctx = ctx;
	}

	/**
	 * Stream the contents of `src` into `dst`, truncating whatever was at `dst`. Returns true on a fully successful
	 * copy, false on any error (permission denied, provider doesn't grant sibling write access, etc.). Used by the
	 * Replace flow to overwrite the original file's URI directly — skipping the delete/rename dance that some
	 * providers silently fail.
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
			in.transferTo(out);
			return true;
		}
		catch (Exception e)
		{
			Log.w(TAG, "copyUriContents " + src + " -> " + dst + " failed: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Programmatically create a new SAF document in the same directory as `docUri`, with `placeholderName` and
	 * `mimeType`. Used by the Save flow to turn a provider-confirmed overwrite (SAF ACTION_CREATE_DOCUMENT that
	 * returned an existing document rather than auto-renaming) into the crash-safe Replace pattern: write+verify
	 * the placeholder, then swap onto the target.
	 *
	 * Derives the parent document URI from `docUri`'s document ID. For path-addressed providers like
	 * ExternalStorageProvider, the parent is the prefix up to the last `/` ("primary:Pictures/foo.jpg" →
	 * "primary:Pictures"); when the file lives at the provider root the volume `:` separator stands in
	 * ("primary:foo.jpg" → "primary:") so root-level documents still get the crash-safe sibling-replace path
	 * instead of falling onto the in-place fallback. Returns null when `docUri` has no document ID, an opaque ID
	 * without either separator, or when the provider rejects createDocument (doesn't support
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
			String parentDocId = SafPaths.parentDocIdOf(docId);
			if (parentDocId == null)
			{
				return null;
			}
			Uri parentUri = DocumentsContract.buildDocumentUri(docUri.getAuthority(), parentDocId);
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
	 * Build a sibling document URI by swapping the last segment of src's document ID for siblingName. Works on
	 * providers that encode paths in their document IDs (notably ExternalStorageProvider). Handles files at the
	 * provider root by treating the volume `:` as the segment separator ("primary:foo.jpg" → "primary:siblingName")
	 * so root-level documents get the same sibling-derivation as nested ones. Returns null for opaque-ID providers
	 * or providers that don't expose a document ID.
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
			int sepEnd = SafPaths.lastSegmentSeparatorEnd(docId);
			if (sepEnd < 0)
			{
				return null;
			}
			return DocumentsContract.buildDocumentUri(src.getAuthority(),
				docId.substring(0, sepEnd) + siblingName);
		}
		catch (Exception e)
		{
			Log.w(TAG, "deriveSiblingUri " + siblingName + " failed: " + e.getMessage());
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
				// Path-traversal guard: a malicious app sending a Share intent with a crafted docId
				// like "primary:../../data/data/com.othertarget/foo" would otherwise produce a File
				// pointing outside the volume root. The getFilePathAndId branch (the
				// MediaStore-Documents path) already applies this same guard; missing it here let the
				// shorter primary-handler reach the raw filesystem on a rooted device. Reject anything
				// that looks like it would escape the volume. Segment-aware ".." check so legitimate
				// filenames containing ".." characters (Samsung's "IMG..edited.jpg" pattern) pass.
				if (SafPaths.hasParentTraversalSegment(tail) || tail.startsWith("/"))
				{
					Log.w(TAG, "fileFromSafUri rejected suspicious docId tail: " + tail);
					return null;
				}
				File primaryRoot = Environment.getExternalStorageDirectory();
				return new File(primaryRoot, tail);
			}
			// DownloadStorageProvider "raw:<absolute path>" — use the path as-is. The "raw" form is by spec
			// an absolute path, so we don't reject "/" here, but we still guard against ".." path segments
			// which have no legitimate use in a docId. Substring ".." is allowed (filename).
			if ("raw".equalsIgnoreCase(volume))
			{
				if (SafPaths.hasParentTraversalSegment(tail))
				{
					Log.w(TAG, "fileFromSafUri rejected raw docId with .. segment: " + tail);
					return null;
				}
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

	/**
	 * Query the document's display name (filename) via OpenableColumns. Returns null when the URI doesn't support
	 * the column, the cursor is empty, the column is null, or the provider throws SecurityException (common for
	 * un-persisted URIs after process restart). Used by SaveController to detect SAF auto-rename collisions and by
	 * ImageLoadController to seed CropState.originalFilename.
	 *
	 * @param uri SAF or content URI to query
	 * @return display name or null when unavailable
	 */
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
			// Expected when the app doesn't hold read permission for this URI (common for sibling/derived
			// URIs we constructed ourselves).
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
		String colId = "_id";
		String colData = "_data";
		try
		{
			try (Cursor cursor = ctx.getContentResolver().query(uri,
				new String[] { colId, colData }, null, null, null))
			{
				if (cursor != null && cursor.moveToFirst())
				{
					int idIdx = cursor.getColumnIndex(colId);
					int dataIdx = cursor.getColumnIndex(colData);
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
					String[] projection = { colData };
					String[] selectionArgs = { msId };
					try (Cursor cursor = ctx.getContentResolver().query(msUri, projection,
						colId + "=?", selectionArgs, null))
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
			// External Storage SAF provider: docId format is "<volumeId>:<relPath>", e.g.
			// "primary:DCIM/Camera/IMG.jpg". The "primary" volume maps to /storage/emulated/0; non-primary
			// UUIDs map to /storage/<UUID>. With MANAGE_EXTERNAL_STORAGE we can read those paths directly
			// via FileInputStream, bypassing the ContentProvider's EXIF-mangling openInputStream stream —
			// which is the entire reason this resolver exists. MediaStore _id is unavailable on this path
			// (the provider doesn't expose it), so we return path-only with id=null — the id slot is
			// preserved in the return shape for symmetry with the MediaStore-Documents branch below, even
			// though no current caller reads it.
			if ("com.android.externalstorage.documents".equals(uri.getAuthority()))
			{
				String docId = DocumentsContract.getDocumentId(uri);
				int colon = docId == null ? -1 : docId.indexOf(':');
				if (colon > 0)
				{
					String volumeId = docId.substring(0, colon);
					String relPath = docId.substring(colon + 1);
					// Defensive: reject paths that escape via ".." segment or claim to be absolute.
					// SAF picker doesn't produce such docIds for legitimate picks; a malicious app
					// could via Share intent. Fall through to null → caller takes the SAF stream
					// path which uses the URI verbatim via ContentResolver (its own access checks
					// gate the read). Segment-aware so "IMG..edited.jpg" — a legit Samsung name —
					// survives.
					if (SafPaths.hasParentTraversalSegment(relPath) || relPath.startsWith("/"))
					{
						return null;
					}
					String volumeRoot = "primary".equalsIgnoreCase(volumeId)
						? "/storage/emulated/0"
						: "/storage/" + volumeId;
					String absPath = volumeRoot + "/" + relPath;
					return new String[] { absPath, null };
				}
			}
		}
		catch (SecurityException ignored)
		{
			// Expected when the URI belongs to a provider we don't hold read permission on (e.g.
			// constructed sibling of a foreign document).
		}
		catch (Exception e)
		{
			Log.w(TAG, "getFilePathAndId failed", e);
		}
		return null;
	}

	/**
	 * Stream probe: opens `uri` for reading and returns true when we can read at least one byte. Used as a fallback
	 * signal for providers that don't expose OpenableColumns.SIZE (querySafFileSize returns -1): for a
	 * fresh-created document, the stream yields EOF immediately; for an existing non-empty document, we get at
	 * least one byte back. Can't disambiguate empty-fresh from empty-existing — both return false — which matches
	 * the inherent SAF ambiguity at that point. Exceptions (provider refuses open, security check) surface as
	 * false; callers treat false as "can't prove there's content" and decide their own fallback posture.
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
	 * Query the SAF document size via OpenableColumns.SIZE. Much cheaper than a full content readback — a single
	 * metadata query against the provider. Returns the reported size, or -1 when the provider doesn't expose SIZE
	 * (some MediaStore paths omit it, some third-party providers return a cursor with no rows). Callers treat -1 as
	 * "size unknown" — up to them whether that means trust the write or fall through to full verification.
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
	 * Copy the URI to a cache file, then slurp raw bytes. The two-step routing (stream → cache file
	 * → in-memory byte[]) is deliberate: some ContentProviders (notably Samsung MediaStore) strip
	 * post-EOI bytes from JPEGs when streaming, which would lose the HDR gain map. Materialising
	 * to a local file first bypasses that.
	 *
	 * Throws IOException when the input exceeds MAX_READ_BYTES — the byte[] allocation in the second phase would
	 * otherwise risk OutOfMemoryError on mid-range devices and a negative-size allocation if fileLen exceeded
	 * Integer.MAX_VALUE.
	 */
	public byte[] readUriBytes(Uri uri) throws IOException
	{
		// Try the on-disk path first. Samsung's MediaStore ContentProvider mutates EXIF as it streams JPEG
		// bytes through openInputStream — repacks IFD0 in HashMap iteration order (not sorted), strips GPS
		// coordinates and LensModel, shrinks the EXIF segment by ~440 bytes. Diagnosed via logcat trace: the
		// bytes that arrive at applyBytes already have scrambled tag order before any of our code touches them.
		// Direct file read from the resolved DATA column bypasses the ContentProvider entirely, returning
		// pristine on-disk bytes. Requires MANAGE_EXTERNAL_STORAGE for paths under /storage/emulated — the same
		// permission ReplaceStrategy needs for its File-I/O atomic-move strategy. Falls back to the SAF stream
		// copy below when no path is resolvable (cloud / SAF-only URIs).
		byte[] direct = tryReadDirectlyFromPath(uri);
		if (direct != null)
		{
			return direct;
		}

		// Unique per call — a shared fixed path ("input_raw") would let two overlapping reads corrupt each
		// other's cache file if a second load entry point ever bypassed the Activity's busy gate.
		// createTempFile gives each call its own path by construction; the finally block below deletes it
		// regardless of outcome.
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
					// cache file grew between write-close and length() (shouldn't happen, but the
					// int cast below has no safe failure mode).
					throw new IOException("Cache file too large: " + fileLen + " bytes");
				}
				return fis.readNBytes((int) fileLen);
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
	 * Byte-for-byte verify the file at `uri` matches `expected`. Ground-truth content verification — used when a
	 * write path threw a harmless EPIPE/IOException on close yet persisted the full payload, or when a cheap
	 * size-only check isn't enough to prove the bytes on disk are really the ones we intended to write.
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
		try (InputStream is = ctx.getContentResolver().openInputStream(uri))
		{
			if (is == null)
			{
				return -1;
			}
			return readbackByteCountFromStream(is, expected);
		}
		catch (Exception e)
		{
			Log.w(TAG, "readbackByteCount: " + e.getMessage());
			// Any exception (open failure, mid-stream read failure, close throwing AFTER the helper's
			// success-return value was queued) lands here. The helper either returns a valid byte-count
			// contract value or throws — the only path to expected.length is via a successful return that,
			// if preceded by a close throw, must NOT be reported as verified. Always return -1 from this
			// catch; the contract for callers is "strict equality vs expected.length", which -1 always
			// fails. The contract is "never return expected.length from the outer catch" — anything that
			// reached this catch is unverified.
			return -1;
		}
	}

	/**
	 * Best-effort delete of a SAF document URI. Returns true when a provider explicitly confirmed the deletion
	 * (DocumentsContract.deleteDocument returned true, or the ContentResolver delete reported > 0 rows affected);
	 * false when both paths failed OR silently reported ambiguous results. Callers that NEED the document gone
	 * before proceeding (e.g. Replace flow's placeholder cleanup after a direct SAF overwrite) must check the
	 * return value — a false here means the file may still be on disk, and short-circuiting the follow-up verifier
	 * would claim success while leaving a duplicate. Callers that only want best-effort cleanup (e.g. post-failure
	 * placeholder sweep) can ignore the result.
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
		catch (Exception e)
		{
			// Provider that doesn't implement deleteDocument (UnsupportedOperationException), or a
			// permission issue (SecurityException). Log at debug — useful for diagnosing why a Replace
			// cleanup left an orphan file behind without spamming the log on every save.
			Log.d(TAG, "deleteDocument fallback path: " + e.getClass().getSimpleName());
		}
		try
		{
			int rows = ctx.getContentResolver().delete(uri, null, null);
			return rows > 0;
		}
		catch (Exception e)
		{
			Log.d(TAG, "ContentResolver.delete fallback path: " + e.getClass().getSimpleName());
		}
		return false;
	}

	/**
	 * Stream-only core of readbackByteCount, exposed package-private for unit testing (tests can pass a
	 * ByteArrayInputStream subclass that throws on close / read to exercise error paths without an Android
	 * Context).
	 *
	 * Returns the same value classes documented on readbackByteCount: expected.length for clean match + EOF, total
	 * + trailing or total + n for trailing bytes, total + i for mismatch, total for short stream, -1 if the
	 * EOF-check read throws after the byte-by-byte comparison passed.
	 *
	 * @param is       input stream to drain
	 * @param expected expected bytes to compare against
	 * @return verified byte count, or a sentinel value indicating mismatch / trailing / short — see
	 *         readbackByteCount Javadoc for the full classification
	 * @throws IOException when the main read loop (NOT the EOF check) errors; the outer caller catches
	 *                     and returns -1
	 */
	static long readbackByteCountFromStream(InputStream is, byte[] expected) throws IOException
	{
		long total = 0;
		byte[] buf = new byte[ByteBufferUtils.IO_BUFFER];
		int n;
		while ((n = is.read(buf)) != -1)
		{
			if (total + n > expected.length)
			{
				// Trailing bytes beyond what we wrote — treat as corruption. Return total + n (a value
				// strictly > expected.length) so callers checking `verifiedBytes == expected.length`
				// see the mismatch via the same idiom as the EOF-check branch below.
				Log.w(TAG, "readback: provider returned more bytes than written");
				return total + n;
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
				// All bytes matched. Confirm EOF — trailing bytes would mean a stale longer payload
				// wasn't truncated. Wrap the EOF-check read in its OWN try so a throw here doesn't
				// propagate to the helper's caller, which would return total (== expected.length) via
				// the outer success path and falsely claim the save is verified despite never
				// confirming EOF.
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
					// Provider served MORE than we wrote — stale trailing bytes that never got
					// truncated. Return a value > expected.length so equality fails; verifyPhase
					// then reports the save as lost.
					Log.w(TAG, "readback: unexpected trailing " + trailing + " bytes");
					return total + trailing;
				}
				return total;
			}
		}
		return total;
	}

	/**
	 * Attempt to read the URI's bytes directly from the underlying filesystem path, bypassing the ContentProvider
	 * stream entirely. Samsung's MediaStore openInputStream rewrites the EXIF segment as it streams (zeros out the
	 * GPS sub-IFD's value blocks, reorders IFD0 entries, shrinks the segment by ~440 bytes — likely a
	 * privacy-driven sanitisation pass). Direct read via FileInputStream gives the pristine on-disk bytes that
	 * still carry GPS.
	 *
	 * Returns null when the URI doesn't resolve to an accessible filesystem path (cloud / SAF-only providers,
	 * opaque-ID URIs without a DATA column, paths under scoped storage we don't have read access to). Caller falls
	 * back to the SAF stream copy in that case — accepting the EXIF mangling for non-MediaStore sources where the
	 * alternative is not loading the file at all.
	 *
	 * Validates the resolved path against existence / readability / size before returning so a stale MediaStore row
	 * pointing at a missing or oversized file doesn't poison the load.
	 */
	private byte[] tryReadDirectlyFromPath(Uri uri)
	{
		String[] pathAndId;
		try
		{
			pathAndId = getFilePathAndId(uri);
		}
		catch (Exception e)
		{
			Log.d(TAG, "direct-read: getFilePathAndId threw for " + uri + ": " + e.getMessage());
			return null;
		}
		if (pathAndId == null || pathAndId[0] == null || pathAndId[0].isEmpty())
		{
			// Common case for cloud / SAF-only URIs without a DATA column. Caller falls back to the SAF
			// stream copy. No log here — would fire on every load from such sources, which is noisy and not
			// actionable.
			return null;
		}
		File file = new File(pathAndId[0]);
		if (!file.isFile() || !file.canRead())
		{
			Log.d(TAG, "direct-read: " + pathAndId[0] + " not readable (isFile=" + file.isFile()
				+ " canRead=" + file.canRead() + ")");
			return null;
		}
		long len = file.length();
		if (len <= 0 || len > MAX_READ_BYTES)
		{
			Log.d(TAG, "direct-read: " + pathAndId[0] + " size out of range: " + len);
			return null;
		}
		try (FileInputStream fis = new FileInputStream(file))
		{
			// readNBytes is the modern replacement for the manual read-loop pattern — same semantics
			// (returns when len bytes are read or EOF), matches readUriBytes' existing usage.
			byte[] bytes = fis.readNBytes((int) len);
			if (bytes.length != len)
			{
				Log.w(TAG, "direct-read: short read "
					+ bytes.length + "/" + len + " on " + pathAndId[0]);
				return null;
			}
			if (!SafPaths.hasImageSignature(bytes))
			{
				// MediaStore _data row may be stale (file deleted then replaced with non-image content)
				// — fall back to the SAF stream which would also fail downstream but at least matches
				// what the picker thought it granted. Prevents loading garbage bytes that BitmapFactory
				// would silently reject with a useless "Failed to decode" toast.
				Log.w(TAG, "direct-read: " + pathAndId[0]
					+ " has no JPEG/PNG signature; falling back to SAF stream");
				return null;
			}
			Log.d(TAG, "direct-read: " + pathAndId[0] + " (" + bytes.length + " bytes)");
			return bytes;
		}
		catch (IOException e)
		{
			Log.w(TAG, "direct-read: read failed for " + pathAndId[0], e);
			return null;
		}
	}
}
