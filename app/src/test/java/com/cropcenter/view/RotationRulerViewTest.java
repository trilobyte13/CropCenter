package com.cropcenter.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for RotationRulerView.isDetentTick — the predicate that decides which minor tick carries the
 * heavy detent marker as the ruler is drawn. Pins the contract that a tick is heavy-marked only when it
 * coincides exactly with an on-grid detent, so the marker never advertises a value release-snap
 * (snapToDetentOrTick) leaves untouched.
 *
 * The bug this guards against: the old code marked the tick NEAREST each detent via signed tick index,
 * which at the coarsest zoom (minor=10°) promoted 50° to a heavy detent marker because 45° and 50° share
 * index round(4.5)=5. Snapping still targeted the exact 45°, so the heavy marker pointed at a non-sticky
 * value (50°) while hiding the sticky one. isDetentTick now reports only on-grid detents; the off-grid
 * ±45°-at-minor=10° markers are drawn by onDraw at their exact position (Canvas-dependent, not covered
 * here — see the off-grid loop in onDraw).
 *
 * minor is the current zoom's minor-tick spacing (TICK_CONFIGS: 10, 5, 1, 1, 0.5, 0.1, 0.05, 0.01);
 * DETENTS = { -180, -90, -45, 0, 45, 90, 180 }.
 */
public final class RotationRulerViewTest
{
	@Test
	public void coarsestZoomDoesNotPromoteFiftyToADetent()
	{
		// minor=10°: 45° is off-grid (45/10 = 4.5), so NEITHER the exact 45° nor the nearest tick 50° is
		// reported by isDetentTick. This is the core regression fix — the old nearest-tick logic marked
		// 50°, an angle release-snap never lands on. (onDraw draws the real ±45° marker separately.)
		assertFalse("50° must NOT be marked at minor=10 (it is not a detent)",
			RotationRulerView.isDetentTick(50f, 10f));
		assertFalse("-50° must NOT be marked at minor=10",
			RotationRulerView.isDetentTick(-50f, 10f));
		assertFalse("40° must NOT be marked at minor=10",
			RotationRulerView.isDetentTick(40f, 10f));
	}

	@Test
	public void coarsestZoomMarksOnGridDetents()
	{
		// 0° / ±90° / ±180° all divide 10° evenly, so they ARE on the grid and stay heavy-marked.
		assertTrue("0° detent at minor=10", RotationRulerView.isDetentTick(0f, 10f));
		assertTrue("90° detent at minor=10", RotationRulerView.isDetentTick(90f, 10f));
		assertTrue("-90° detent at minor=10", RotationRulerView.isDetentTick(-90f, 10f));
		assertTrue("180° detent at minor=10", RotationRulerView.isDetentTick(180f, 10f));
	}

	@Test
	public void fineZoomMarksOnlyExactDetents()
	{
		// minor=1°: every detent is on the grid; a neighbouring tick (44° / 46°) must not be marked.
		assertTrue("45° at minor=1", RotationRulerView.isDetentTick(45f, 1f));
		assertFalse("44° at minor=1", RotationRulerView.isDetentTick(44f, 1f));
		assertFalse("46° at minor=1", RotationRulerView.isDetentTick(46f, 1f));
	}

	@Test
	public void finestZoomKeepsDetentsExactAndNeighboursClear()
	{
		// minor=0.01° (max zoom): 45° is still exactly on the grid (4500 ticks) and an adjacent 0.01°
		// tick is not a detent — pins that the fine end didn't regress to a wide snap window.
		assertTrue("45.00° at minor=0.01", RotationRulerView.isDetentTick(45f, 0.01f));
		assertFalse("45.01° at minor=0.01", RotationRulerView.isDetentTick(45.01f, 0.01f));
	}

	@Test
	public void midZoomMarksExactDetentTicks()
	{
		// minor=5°: 45 / 5 = 9 exactly, so ±45° / 90° / 180° are on the grid and marked; 50° is not.
		assertTrue("45° at minor=5", RotationRulerView.isDetentTick(45f, 5f));
		assertTrue("-45° at minor=5", RotationRulerView.isDetentTick(-45f, 5f));
		assertTrue("90° at minor=5", RotationRulerView.isDetentTick(90f, 5f));
		assertFalse("50° is not a detent at minor=5", RotationRulerView.isDetentTick(50f, 5f));
	}

	@Test
	public void nonDetentTicksAreNeverMarked()
	{
		// A tick far from every detent stays a plain tick at any zoom.
		assertFalse("30° at minor=10", RotationRulerView.isDetentTick(30f, 10f));
		assertFalse("20° at minor=5", RotationRulerView.isDetentTick(20f, 5f));
		assertFalse("12° at minor=1", RotationRulerView.isDetentTick(12f, 1f));
	}
}
