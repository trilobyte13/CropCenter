package com.cropcenter.util;

import com.cropcenter.metadata.JpegMarker;

/**
 * Pure-string helpers for SAF document IDs. Extracted from SafFileHelper so the path-vs-volume-vs-opaque parsing logic
 * is testable and reusable without instantiating a Context-bound SafFileHelper. Static, no state.
 *
 * SAF document IDs come in two flavors: path-addressed ("primary:Pictures/foo.jpg" — provider is
 * ExternalStorageProvider) where the volume colon and slash separators carry semantics; and opaque ("abc123" — many
 * cloud providers) where the bytes are an unparseable handle. The helpers here distinguish the cases so callers can
 * route to the sibling-replace path (path-addressed) or the in-place fallback (opaque) without having to re-derive the
 * parsing rules.
 */
public final class SafPaths
{
	private SafPaths() {}

	/**
	 * True when bytes start with a recognised JPEG (FF D8) or PNG (89 50 4E 47) magic sequence. Cheap upfront
	 * filter for callers that read directly from a path so a stale MediaStore row pointing at a non-image file can
	 * fall back to SAF rather than poison the load with garbage bytes.
	 *
	 * @param bytes file head bytes (only the first 4 are inspected; longer buffers are accepted)
	 * @return true when the bytes look like JPEG or PNG
	 */
	public static boolean hasImageSignature(byte[] bytes)
	{
		// Null-safe entry: callers may pass null when a MediaStore / SAF read returned no bytes
		// (deleted file, permission denied mid-stream). Returning false routes the caller through
		// the SAF-stream fallback rather than NPE-crashing the bg save / load thread.
		if (bytes == null || bytes.length < 4)
		{
			return false;
		}
		if ((bytes[0] & 0xFF) == JpegMarker.PREFIX && (bytes[1] & 0xFF) == JpegMarker.SOI)
		{
			return true;
		}
		// PNG signature start (89 50 4E 47)
		return (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
	}

	/**
	 * True when `relativePath` contains a `..` path SEGMENT (separator: `/`). The original sites used
	 * `String.contains("..")` which rejected legitimate filenames whose characters happened to include `..` —
	 * `IMG..edited.jpg` is a single legitimate segment, not a parent-directory escape, so callers like Samsung's
	 * ExternalStorageProvider relPath were losing the direct-filesystem path and falling back to provider streams
	 * onto provider streams. Segments around the substring keep their literal text; only an exact `..` between
	 * `/` boundaries (or as the whole string) trips the guard.
	 *
	 * @param relativePath SAF docId tail or relPath to check ("DCIM/Camera/IMG.jpg",
	 *                     "Pictures/IMG..edited.jpg", "../etc/passwd")
	 * @return true when any `/`-separated segment is exactly the two-character string `..`
	 */
	public static boolean hasParentTraversalSegment(String relativePath)
	{
		int start = 0;
		int len = relativePath.length();
		for (int i = 0; i <= len; i++)
		{
			if (i == len || relativePath.charAt(i) == '/')
			{
				if (i - start == 2
					&& relativePath.charAt(start) == '.'
					&& relativePath.charAt(start + 1) == '.')
				{
					return true;
				}
				start = i + 1;
			}
		}
		return false;
	}

	/**
	 * Index just past the separator that ends the parent's segment of a PATH-ADDRESSED SAF document ID. For
	 * nested files ("primary:Pictures/foo.jpg") this is the position after the last "/", so
	 * docId.substring(0, end) + child yields the sibling. For root-level files in a path-addressed scheme
	 * ("primary:foo.jpg") it falls back to the position after the volume ":". Returns -1 for opaque schemes
	 * ("msf:12345", "image:12345", pure-numeric MediaStore IDs) where the tail after ":" is an unparseable
	 * handle — sibling URIs can't be built by string substitution, so callers route to the opaque-provider
	 * fallback (which presents Replace/Keep/Cancel via probe rather than auto-rename).
	 *
	 * @param docId document ID to parse
	 * @return index just past the separator, or -1 for opaque schemes / docIds without a parent separator
	 */
	public static int lastSegmentSeparatorEnd(String docId)
	{
		if (isOpaqueDocId(docId))
		{
			return -1;
		}
		int slash = docId.lastIndexOf('/');
		if (slash >= 0)
		{
			return slash + 1;
		}
		int colon = docId.indexOf(':');
		if (colon >= 0)
		{
			return colon + 1;
		}
		return -1;
	}

	/**
	 * Parent document ID of a PATH-ADDRESSED SAF document ID, or null when the ID is opaque. Strips the
	 * trailing "/segment" for nested paths; for root-level files in a path-addressed scheme the parent is
	 * the volume prefix including the ":" ("primary:foo.jpg" → "primary:"), which ExternalStorageProvider
	 * accepts as the root document. Returns null for opaque schemes (msf:, image:, pure-numeric) because a
	 * "parent" of an opaque handle is meaningless — there is no sibling-creation flow on those providers
	 * (callers handle collisions via the in-place fallback instead).
	 *
	 * @param docId document ID to parse
	 * @return parent document ID, or null when no parent is derivable
	 */
	public static String parentDocIdOf(String docId)
	{
		if (isOpaqueDocId(docId))
		{
			return null;
		}
		int slash = docId.lastIndexOf('/');
		if (slash > 0)
		{
			return docId.substring(0, slash);
		}
		int colon = docId.indexOf(':');
		if (colon >= 0)
		{
			return docId.substring(0, colon + 1);
		}
		return null;
	}

	/**
	 * Recognise opaque document-ID schemes whose tail after ":" is an unparseable handle, not a path
	 * segment. The two known opaque scheme prefixes are "msf:" (DownloadStorageProvider MediaStore-Files
	 * numeric ID) and "image:" (media.documents provider MediaStore-Images numeric ID); a pure-numeric
	 * docId (no colon, all digits) is the legacy form of the same MediaStore reference.
	 *
	 * The distinction matters for sibling-URI derivation: a path-addressed docId ("primary:foo.jpg")
	 * lets `deriveSiblingUri` build `primary:bar.jpg` as a sibling, but `msf:12345` swapped to
	 * `msf:bar.jpg` is a bogus URI pointing at no document. Without this check, SaveController's Case-B
	 * collision flow on Downloads providers can synthesize bogus sibling URIs and silently auto-rename
	 * instead of surfacing the Replace/Keep/Cancel dialog.
	 *
	 * @param docId document ID to classify
	 * @return true when docId is one of the recognised opaque schemes
	 */
	private static boolean isOpaqueDocId(String docId)
	{
		if (docId.startsWith("msf:") || docId.startsWith("image:"))
		{
			return true;
		}
		// Pure-numeric (no colon, all digits) — legacy MediaStore numeric ID. Empty string isn't
		// numeric in the meaningful sense; fall through to the existing path-addressed parse which
		// returns -1 / null on it anyway.
		if (docId.isEmpty() || docId.indexOf(':') >= 0)
		{
			return false;
		}
		for (int i = 0; i < docId.length(); i++)
		{
			if (!Character.isDigit(docId.charAt(i)))
			{
				return false;
			}
		}
		return true;
	}
}
