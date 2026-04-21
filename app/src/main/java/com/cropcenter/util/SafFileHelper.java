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
	/**
	 * Upper bound on readUriBytes input size. Modern HDR + gain-map JPEGs land well under
	 * 64 MiB; this cap catches pathological inputs before they OOM the heap or overflow the
	 * int cast in the size-to-length conversion.
	 */
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
		File cacheFile = new File(ctx.getCacheDir(), "input_raw");
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
	 * Best-effort delete of a SAF document URI. Silently ignores failures — used for
	 * placeholder cleanup after a cancelled or failed save, so swallowing exceptions matches
	 * the "try both paths, give up quietly" contract.
	 */
	public void tryDeleteSafDocument(Uri uri)
	{
		try
		{
			if (DocumentsContract.isDocumentUri(ctx, uri))
			{
				DocumentsContract.deleteDocument(ctx.getContentResolver(), uri);
				return;
			}
		}
		catch (Exception ignored)
		{
		}
		try
		{
			ctx.getContentResolver().delete(uri, null, null);
		}
		catch (Exception ignored)
		{
		}
	}
}
