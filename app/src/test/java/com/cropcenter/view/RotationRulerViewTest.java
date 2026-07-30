package com.cropcenter.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Canvas-free pins for RotationRulerView's package-private decision seams.
 *
 * isDetentTick — the predicate that decides which minor tick carries the heavy detent marker as the ruler is drawn.
 * Pins the contract that a tick is heavy-marked only when it coincides exactly with an on-grid detent, so the marker
 * never advertises a value release-snap (snapToDetentOrTick) leaves untouched. A nearest-tick marking would misbehave
 * at the coarsest zoom (minor=10°), where 45° and 50° share signed tick index round(4.5)=5: the heavy marker would land
 * on 50°, a value release-snap never targets, while hiding the sticky 45°. The off-grid ±45°-at-minor=10° markers are
 * drawn by onDraw at their exact position (Canvas-dependent, not covered here). minor is the current zoom's minor-tick
 * spacing (TICK_CONFIGS: 10, 5, 1, 1, 0.5, 0.1, 0.05, 0.01); DETENTS = { -180, -90, -45, 0, 45, 90, 180 }.
 *
 * promoteActivePointer — the pointer-transition math for ACTION_POINTER_UP of the active (drag-owning) pointer. Pins
 * that promotion hands the drag to the surviving finger AND rebases the drag baseline to the survivor's own x, so the
 * first post-promotion MOVE contributes a zero rotation delta rather than the full inter-finger distance.
 */
public final class RotationRulerViewTest
{
	@Test
	public void coarsestZoomDoesNotPromoteFiftyToADetent()
	{
		// minor=10°: 45° is off-grid (45/10 = 4.5), so NEITHER the exact 45° nor the nearest tick 50° is
		// reported by isDetentTick. Pins that an off-grid detent marks NO tick — nearest-tick promotion would
		// heavy-mark 50°, an angle release-snap never lands on. (onDraw draws the real ±45° marker separately.)
		assertFalse("50° must NOT be marked at minor=10 (it is not a detent)",
			RotationRulerView.isDetentTick(50f, 10f));
		assertFalse("-50° must NOT be marked at minor=10", RotationRulerView.isDetentTick(-50f, 10f));
		assertFalse("40° must NOT be marked at minor=10", RotationRulerView.isDetentTick(40f, 10f));
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
		// minor=0.01° (max zoom): 45° is still exactly on the grid (4500 ticks) and an adjacent 0.01° tick is
		// not a detent — pins that the fine end didn't regress to a wide snap window.
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

	@Test
	public void promotionAfterActiveFingerLiftYieldsZeroFirstDelta()
	{
		// Active finger (id 5) at index 0, x=100; second finger (id 9) at index 1, x=400. Lifting the active
		// finger promotes id 9 and rebases the drag baseline to the survivor's OWN x, so the first
		// post-promotion MOVE at the survivor's unchanged position contributes exactly zero delta — not the 300
		// px inter-finger distance (an uncommanded rotation jump of up to ~20° at minimum zoom, committed live
		// and never rolled back).
		RotationRulerView.PointerPromotion promotion =
			RotationRulerView.promoteActivePointer(0, 5, 9, 100f, 400f);
		assertEquals("survivor id becomes the active pointer", 9, promotion.pointerId());
		assertEquals("first post-promotion MOVE delta must be zero", 0f, 400f - promotion.lastTouchX(), 0f);
	}

	@Test
	public void promotionOfNonZeroLiftedIndexSelectsIndexZeroSurvivor()
	{
		// Lifted finger at index 1 (or any non-zero index) → the survivor is index 0: its id and its x, again
		// making the survivor's first MOVE delta zero.
		RotationRulerView.PointerPromotion promotion =
			RotationRulerView.promoteActivePointer(1, 5, 9, 100f, 400f);
		assertEquals("survivor id becomes the active pointer", 5, promotion.pointerId());
		assertEquals("first post-promotion MOVE delta must be zero", 0f, 100f - promotion.lastTouchX(), 0f);
	}
}
