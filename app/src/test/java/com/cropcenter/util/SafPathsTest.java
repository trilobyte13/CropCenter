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
	public void hasImageSignatureRejectsShortBuffer()
	{
		assertFalse(SafPaths.hasImageSignature(new byte[] { (byte) 0xFF, (byte) 0xD8, 0x00 }));
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
	public void hasParentTraversalSegmentDetectsBareDotDot()
	{
		assertTrue(SafPaths.hasParentTraversalSegment(".."));
	}

	@Test
	public void hasParentTraversalSegmentAllowsDotDotSubstring()
	{
		// "IMG..edited.jpg" — Samsung's edited-photo filename pattern. The segment is the whole string, not
		// "..". The pre-fix String.contains("..") rejected this as suspicious, sending Samsung loads through
		// the provider stream path which mangles HDR metadata. Round-40 F2 fix: segment-aware check passes.
		assertFalse(SafPaths.hasParentTraversalSegment("IMG..edited.jpg"));
	}

	@Test
	public void hasParentTraversalSegmentAllowsNestedDotDotSubstring()
	{
		assertFalse(SafPaths.hasParentTraversalSegment("DCIM/Camera/IMG..edited.jpg"));
	}

	@Test
	public void hasParentTraversalSegmentAllowsThreeDotSegment()
	{
		// "..." is not a path-traversal token (it's a regular filename in Unix). Only exact ".." matches.
		assertFalse(SafPaths.hasParentTraversalSegment("Pictures/.../foo"));
	}

	@Test
	public void hasParentTraversalSegmentAllowsSingleDotSegment()
	{
		// "." is the current-directory token, harmless from a traversal perspective. Only ".." escapes.
		assertFalse(SafPaths.hasParentTraversalSegment("Pictures/./foo"));
	}

	@Test
	public void hasParentTraversalSegmentAllowsEmptyString()
	{
		assertFalse(SafPaths.hasParentTraversalSegment(""));
	}

	@Test
	public void hasParentTraversalSegmentAllowsCleanNestedPath()
	{
		assertFalse(SafPaths.hasParentTraversalSegment("DCIM/Camera/IMG_20250819.jpg"));
	}

	@Test
	public void lastSegmentSeparatorEndPrefersSlashOverColon()
	{
		// "primary:DCIM/foo.jpg" — last segment is "foo.jpg" after the slash, NOT "DCIM/foo.jpg" after the
		// colon. Slash wins so sibling derivation lands one directory deep.
		assertEquals(13, SafPaths.lastSegmentSeparatorEnd("primary:DCIM/foo.jpg"));
	}

	@Test
	public void lastSegmentSeparatorEndFallsBackToColon()
	{
		// "primary:foo.jpg" — file at the provider root, no slash. Colon is the segment separator.
		assertEquals(8, SafPaths.lastSegmentSeparatorEnd("primary:foo.jpg"));
	}

	@Test
	public void lastSegmentSeparatorEndReturnsMinusOneForOpaqueId()
	{
		// Cloud-provider opaque IDs have neither separator — sibling derivation can't proceed.
		assertEquals(-1, SafPaths.lastSegmentSeparatorEnd("abc123def456"));
	}

	@Test
	public void parentDocIdOfStripsLastSlashSegment()
	{
		assertEquals("primary:DCIM/Camera", SafPaths.parentDocIdOf("primary:DCIM/Camera/foo.jpg"));
	}

	@Test
	public void parentDocIdOfRootLevelKeepsColon()
	{
		// Root-level file: parent is the volume prefix INCLUDING the colon — ExternalStorageProvider
		// accepts "primary:" as the root document URI for createDocument.
		assertEquals("primary:", SafPaths.parentDocIdOf("primary:foo.jpg"));
	}

	@Test
	public void parentDocIdOfReturnsNullForOpaqueId()
	{
		// Cloud-provider opaque IDs without either separator have no derivable parent.
		assertEquals(null, SafPaths.parentDocIdOf("abc123def456"));
	}
}
