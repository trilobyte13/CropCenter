package com.cropcenter.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.cropcenter.util.AiRegionDetector.AiMask;

import org.junit.Test;

/**
 * Tests for the Graft record and its hasAiMask predicate. hasAiMask drives the inpaint branch in CropState.installGraft
 * — a regression that always returned true would force an unnecessary inpaint setup for SDR sources, while one that
 * always returned false would skip the inpaint on every HDR graft and let the gain map's ghost-of-removed- object
 * artifact survive in the output.
 */
public final class GraftTest
{
	@Test
	public void hasAiMaskFalseWhenAllPixelsClear()
	{
		// hasMaskedPixels delegates to the AiMask record's own check — a mask with no flagged pixels means
		// Photoshop didn't actually change anything in the AI detection threshold. Skip the inpaint entirely;
		// the splice itself is enough.
		AiMask emptyMask = AiMask.of(new boolean[4 * 4], 4, 4, 4);
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
		AiMask mask = AiMask.of(pixels, 4, 2, 4);
		Graft graft = new Graft(new byte[]{ 0x01 }, "n.jpg", mask);
		assertTrue(graft.hasAiMask());
	}
}
