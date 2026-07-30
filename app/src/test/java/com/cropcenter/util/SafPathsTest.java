package com.cropcenter.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for SafPaths' pure-string SAF docId parsers. Lives in the public util package so it can hit the same surface
 * the cross-cutting SAF callers do (SafFileHelper, SaveController, ImageLoadController) without instantiating a
 * Context-bound helper.
 */
public final class SafPathsTest
{
	@Test
	public void hasImageSignatureAcceptsJpeg()
	{
		assertTrue(SafPaths.hasImageSignature(new byte[] { (byte) 0xFF, (byte) 0xD8, 0x00, 0x00 }));
	}

	@Test
	public void hasImageSignatureAcceptsPng()
	{
		assertTrue(SafPaths.hasImageSignature(new byte[] { (byte) 0x89, 'P', 'N', 'G' }));
	}

	@Test
	public void hasImageSignatureRejectsArbitraryBytes()
	{
		assertFalse(SafPaths.hasImageSignature(new byte[] { 'G', 'I', 'F', '8' }));
	}

	@Test
	public void hasImageSignatureRejectsNull()
	{
		// The null guard is load-bearing: reading `bytes.length` before it would NPE on the bg thread of any
		// caller that received `null` from a MediaStore / SAF stream read (file deleted mid-stream, permission
		// denied, cloud provider returned no payload). Returning false routes the caller through the SAF-stream
		// fallback instead. SafFileHelper's direct-read path is the documented caller.
		assertFalse("null input must return false (no NPE)", SafPaths.hasImageSignature(null));
	}

	@Test
	public void hasImageSignatureRejectsShortBuffer()
	{
		assertFalse(SafPaths.hasImageSignature(new byte[] { (byte) 0xFF, (byte) 0xD8, 0x00 }));
	}

	@Test
	public void hasParentTraversalSegmentAllowsCleanNestedPath()
	{
		assertFalse(SafPaths.hasParentTraversalSegment("DCIM/Camera/IMG_20250819.jpg"));
	}

	@Test
	public void hasParentTraversalSegmentAllowsDotDotSubstring()
	{
		// "IMG..edited.jpg" — Samsung's edited-photo filename pattern. The segment is the whole string, not
		// "..". A naive String.contains("..") rejects this as suspicious and sends Samsung loads through the
		// provider stream path, which mangles HDR metadata.
		assertFalse(SafPaths.hasParentTraversalSegment("IMG..edited.jpg"));
	}

	@Test
	public void hasParentTraversalSegmentAllowsEmptyString()
	{
		assertFalse(SafPaths.hasParentTraversalSegment(""));
	}

	@Test
	public void hasParentTraversalSegmentAllowsNestedDotDotSubstring()
	{
		assertFalse(SafPaths.hasParentTraversalSegment("DCIM/Camera/IMG..edited.jpg"));
	}

	@Test
	public void hasParentTraversalSegmentAllowsSingleDotSegment()
	{
		// "." is the current-directory token, harmless from a traversal perspective. Only ".." escapes.
		assertFalse(SafPaths.hasParentTraversalSegment("Pictures/./foo"));
	}

	@Test
	public void hasParentTraversalSegmentAllowsThreeDotSegment()
	{
		// "..." is not a path-traversal token (it's a regular filename in Unix). Only exact ".." matches.
		assertFalse(SafPaths.hasParentTraversalSegment("Pictures/.../foo"));
	}

	@Test
	public void hasParentTraversalSegmentDetectsBareDotDot()
	{
		assertTrue(SafPaths.hasParentTraversalSegment(".."));
	}

	@Test
	public void hasParentTraversalSegmentDetectsLeadingDotDot()
	{
		// "../etc/passwd" — first segment is "..", classic traversal.
		assertTrue(SafPaths.hasParentTraversalSegment("../etc/passwd"));
	}

	@Test
	public void hasParentTraversalSegmentDetectsMidPathDotDot()
	{
		// "Pictures/../../data/data/com.othertarget/foo" — middle ".." segment escapes the volume root.
		assertTrue(SafPaths.hasParentTraversalSegment("Pictures/../foo"));
	}

	@Test
	public void hasParentTraversalSegmentDetectsTrailingDotDot()
	{
		// "Pictures/.." — last segment is "..", lands at the volume root.
		assertTrue(SafPaths.hasParentTraversalSegment("Pictures/.."));
	}

	@Test
	public void lastSegmentSeparatorEndFallsBackToColon()
	{
		// "primary:foo.jpg" — file at the provider root, no slash. Colon is the segment separator.
		assertEquals(8, SafPaths.lastSegmentSeparatorEnd("primary:foo.jpg"));
	}

	@Test
	public void lastSegmentSeparatorEndPrefersSlashOverColon()
	{
		// "primary:DCIM/foo.jpg" — last segment is "foo.jpg" after the slash, NOT "DCIM/foo.jpg" after the
		// colon. Slash wins so sibling derivation lands one directory deep.
		assertEquals(13, SafPaths.lastSegmentSeparatorEnd("primary:DCIM/foo.jpg"));
	}

	@Test
	public void lastSegmentSeparatorEndRejectsDocumentScheme()
	{
		// Recent Samsung Files behaviour — the provider returns "document:12345" where the tail after ":" is a
		// numeric MediaStore handle, NOT a path. SaveController's Case-B flow relies on deriveSiblingUri
		// returning null here so the collision probe falls back to trusting the auto-rename pattern and
		// surfaces Replace/Keep/Cancel. Treating the colon as the volume separator instead would synthesise
		// "document:foo.jpg" — a bogus URI whose follow-up collision probe fails, silently auto-renaming the
		// save instead of asking.
		assertEquals(-1, SafPaths.lastSegmentSeparatorEnd("document:12345"));
	}

	@Test
	public void lastSegmentSeparatorEndRejectsImageScheme()
	{
		// media.documents provider's "image:12345" — the tail after ":" is a MediaStore-Images numeric ID, NOT
		// a filename. Treating the colon as a volume separator would let deriveSiblingUri build "image:foo.jpg"
		// which is a bogus URI pointing at no document. Pin the explicit reject so the caller routes to the
		// opaque-provider fallback instead.
		assertEquals(-1, SafPaths.lastSegmentSeparatorEnd("image:12345"));
	}

	@Test
	public void lastSegmentSeparatorEndRejectsMsfScheme()
	{
		// DownloadStorageProvider's "msf:12345" — the tail after ":" is a MediaStore-Files numeric ID, NOT a
		// path. Sibling-URI derivation would produce "msf:bar.jpg" which is a non-existent document, causing
		// SaveController's Case-B Replace dialog to silently skip in favour of an auto-rename Keep. Pin the
		// explicit reject. Regression scenario: user saves to Downloads, gets a collision, expects
		// Replace/Keep/Cancel — without this reject, the dialog never fires and the auto-renamed save lands
		// without the user's confirmation.
		assertEquals(-1, SafPaths.lastSegmentSeparatorEnd("msf:12345"));
	}

	@Test
	public void lastSegmentSeparatorEndRejectsPureNumericLegacyId()
	{
		// Legacy bare-numeric MediaStore reference (pre-msf: scheme). Same opaque-handle reasoning as msf:
		// above — no parseable path tail, so sibling derivation can't proceed.
		assertEquals(-1, SafPaths.lastSegmentSeparatorEnd("12345"));
	}

	@Test
	public void lastSegmentSeparatorEndRejectsQbScheme()
	{
		// Samsung's modified DownloadStorageProvider returns "qb:NNN" — a vendor-specific opaque handle, not a
		// path. Treating the colon as a volume separator would let deriveSiblingUri build "qb:foo.jpg", a bogus
		// URI pointing at no document; SaveController's Case-B collision probe would then miss the real
		// collision and silently accept the auto-rename instead of surfacing Replace/Keep/Cancel.
		assertEquals(-1, SafPaths.lastSegmentSeparatorEnd("qb:123"));
	}

	@Test
	public void lastSegmentSeparatorEndReturnsMinusOneForOpaqueId()
	{
		// Cloud-provider opaque IDs have neither separator — sibling derivation can't proceed.
		assertEquals(-1, SafPaths.lastSegmentSeparatorEnd("abc123def456"));
	}

	@Test
	public void parentDocIdOfLeadingSlashFallsBackToColon()
	{
		// docId starting with `/` has slash at index 0; the check `slash > 0` (NOT `>= 0`) means the slash
		// branch is skipped — root-slash paths are treated like the "no slash" case. Pin the existing behavior:
		// no colon → empty return. A regression that loosened the check to `>= 0` would silently start
		// returning "" for `/foo.jpg`, breaking SAF createDocument-against-parent flow on root-anchored docIds.
		assertTrue(SafPaths.parentDocIdOf("/foo.jpg").isEmpty());
	}

	@Test
	public void parentDocIdOfRejectsDocumentScheme()
	{
		// Same opaque-handle reasoning as the msf: / image: rejects — recent Samsung Files returns
		// "document:12345" where the numeric tail is a MediaStore handle, not a path segment. Must be empty,
		// not "document:" — createDocument against that parent would reject.
		assertTrue(SafPaths.parentDocIdOf("document:12345").isEmpty());
	}

	@Test
	public void parentDocIdOfRejectsImageScheme()
	{
		// Same opaque-handle reasoning as the msf: reject — "image:12345" is a MediaStore-Images numeric ID
		// with no derivable parent. createSiblingPlaceholder would otherwise build a parent URI of "image:"
		// which media.documents doesn't accept.
		assertTrue(SafPaths.parentDocIdOf("image:12345").isEmpty());
	}

	@Test
	public void parentDocIdOfRejectsMsfScheme()
	{
		// DownloadStorageProvider's "msf:12345" has no derivable parent — the entire tail after ":" is a
		// numeric handle, not a path. Must be empty, not "msf:" — createDocument against that parent would
		// reject, surfacing downstream as the silent auto-rename described in the
		// lastSegmentSeparatorEndRejectsMsfScheme test.
		assertTrue(SafPaths.parentDocIdOf("msf:12345").isEmpty());
	}

	@Test
	public void parentDocIdOfRejectsPureNumericLegacyId()
	{
		// Legacy bare-numeric MediaStore reference — same opaque-handle reasoning as msf:.
		assertTrue(SafPaths.parentDocIdOf("12345").isEmpty());
	}

	@Test
	public void parentDocIdOfRejectsQbScheme()
	{
		// Samsung's modified DownloadStorageProvider "qb:NNN" — the numeric tail is a vendor-specific handle
		// with no derivable parent. Must be empty, not "qb:" — createSiblingPlaceholder would otherwise ask the
		// provider to createDocument against a bogus "qb:" parent.
		assertTrue(SafPaths.parentDocIdOf("qb:123").isEmpty());
	}

	@Test
	public void parentDocIdOfReturnsEmptyForOpaqueId()
	{
		// Cloud-provider opaque IDs without either separator have no derivable parent.
		assertTrue(SafPaths.parentDocIdOf("abc123def456").isEmpty());
	}

	@Test
	public void parentDocIdOfRootLevelKeepsColon()
	{
		// Root-level file: parent is the volume prefix INCLUDING the colon — ExternalStorageProvider accepts
		// "primary:" as the root document URI for createDocument.
		assertEquals("primary:", SafPaths.parentDocIdOf("primary:foo.jpg").orElseThrow());
	}

	@Test
	public void parentDocIdOfStripsLastSlashSegment()
	{
		assertEquals("primary:DCIM/Camera",
			SafPaths.parentDocIdOf("primary:DCIM/Camera/foo.jpg").orElseThrow());
	}
}
