package com.cropcenter.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cropcenter.util.AiRegionDetector.AiMask;

/**
 * Tests for the Graft record and its hasAiMask predicate. hasAiMask drives the inpaint branch in CropState.installGraft
 * — a regression that always returned true would force an unnecessary inpaint setup for SDR sources, while one that
 * always returned false would skip the inpaint on every HDR graft and let the gain map's ghost-of-removed- object
 * artifact survive in the output.
 */
public class GraftTest
{
	@Test
	public void componentAccessorsReturnFields()
	{
		// Records auto-generate accessors but it's worth pinning them down — record component renames silently
		// break callers, and tests catch it earlier than runtime ClassCastException at the call site.
		byte[] bytes = { 0x01, 0x02, 0x03 };
		AiMask mask = new AiMask(new boolean[]{ true }, 1, 1, 4);
		Graft graft = new Graft(bytes, "name.jpg", mask);
		assertArrayEquals(bytes, graft.bytes());
		assertEquals("name.jpg", graft.displayName());
		assertEquals(mask, graft.aiMask());
	}

	@Test
	public void hasAiMaskFalseWhenAllPixelsClear()
	{
		// hasMaskedPixels delegates to the AiMask record's own check — a mask with no flagged pixels means
		// Photoshop didn't actually change anything in the AI detection threshold. Skip the inpaint entirely;
		// the splice itself is enough.
		AiMask emptyMask = new AiMask(new boolean[4 * 4], 4, 4, 4);
		Graft graft = new Graft(new byte[0], "n.jpg", emptyMask);
		assertFalse(graft.hasAiMask());
	}

	@Test
	public void hasAiMaskFalseWhenMaskIsNull()
	{
		// Null AiMask = no AI region detected (e.g., SDR source where detection was skipped entirely, or a
		// graft where the edit was identical to the source).
		Graft graft = new Graft(new byte[]{ 0x01 }, "no-mask.jpg", null);
		assertFalse(graft.hasAiMask());
		assertNull(graft.aiMask());
	}

	@Test
	public void hasAiMaskTrueWhenAtLeastOnePixelFlagged()
	{
		boolean[] pixels = new boolean[8];
		pixels[3] = true;
		AiMask mask = new AiMask(pixels, 4, 2, 4);
		Graft graft = new Graft(new byte[]{ 0x01 }, "n.jpg", mask);
		assertTrue(graft.hasAiMask());
	}
}
