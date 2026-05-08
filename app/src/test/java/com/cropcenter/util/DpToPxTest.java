package com.cropcenter.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for the dp→px conversion chokepoint. CLAUDE.md documents this as a load-bearing helper: the documented
 * regression is `(int) (1 * 0.75f)` truncating to 0, making 1dp dividers and 1px tick marks invisible on
 * density-0.75 screens. The Math.round path keeps 1dp at 1px there.
 */
public final class DpToPxTest
{
	@Test
	public void toPxAtLowDensityRoundsOneDpUpToOnePx()
	{
		// Documented regression target: density 0.75 with truncation produces (int)(1 * 0.75) = 0, hiding 1dp
		// dividers and tick marks. Math.round produces 1.
		assertEquals(1, DpToPx.toPx(1, 0.75f));
		assertEquals(1, DpToPx.toPx(1f, 0.75f));
	}

	@Test
	public void toPxRoundsHalfwayValuesToNearest()
	{
		// 3 * 1.5 = 4.5 — Math.round picks 5 (round-half-up). Truncation would give 4. Same expected result
		// for the int and float overloads.
		assertEquals(5, DpToPx.toPx(3, 1.5f));
		assertEquals(5, DpToPx.toPx(3f, 1.5f));
		// 1 * 1.5 = 1.5 — round-half-up to 2.
		assertEquals(2, DpToPx.toPx(1, 1.5f));
	}

	@Test
	public void toPxAtIntegerDensityIsExact()
	{
		// At density 1.0 / 2.0 / 3.0, the rounded result equals dp * density exactly — no boundary surprises.
		assertEquals(8, DpToPx.toPx(8, 1f));
		assertEquals(16, DpToPx.toPx(8, 2f));
		assertEquals(24, DpToPx.toPx(8, 3f));
		assertEquals(0, DpToPx.toPx(0, 2.5f));
	}
}
