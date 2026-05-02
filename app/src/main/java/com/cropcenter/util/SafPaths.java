package com.cropcenter.util;

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
		if (bytes.length < 4)
		{
			return false;
		}
		// JPEG SOI
		if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8)
		{
			return true;
		}
		// PNG signature start (89 50 4E 47)
		return (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
	}

	/**
	 * Index just past the separator that ends the parent's segment of a path-addressed SAF document ID. For nested
	 * files ("primary:Pictures/foo.jpg") this is the position after the last "/", so docId.substring(0, end) +
	 * child yields the sibling. For files at the provider root ("primary:foo.jpg") it falls back to the position
	 * after the volume ":". Returns -1 for opaque IDs that have neither separator.
	 *
	 * @param docId document ID to parse
	 * @return index just past the separator, or -1 when neither separator is present
	 */
	public static int lastSegmentSeparatorEnd(String docId)
	{
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
	 * Parent document ID of a path-addressed SAF document ID, or null when the ID is opaque. Strips the trailing
	 * "/segment" for nested paths; for root-level files the parent is the volume prefix including the ":"
	 * ("primary:foo.jpg" → "primary:"), which ExternalStorageProvider accepts as the root document.
	 *
	 * @param docId document ID to parse
	 * @return parent document ID, or null when no parent is derivable
	 */
	public static String parentDocIdOf(String docId)
	{
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
}
