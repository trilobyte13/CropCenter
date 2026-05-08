package com.cropcenter.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for HorizonPaintOverlay's stroke / cancel state machine. The render path uses android.graphics.Canvas /
 * Paint and isn't reachable from pure JUnit, but the active / drawing flags + the imagePoints bookkeeping are
 * pure Java and drive the auto-rotate UX directly: a regression here would either trigger false-positive
 * detections on aborted strokes (Codex round-15 finding) or strand paint mode after a cancel.
 */
public final class HorizonPaintOverlayTest
{
	@Test
	public void cancelStrokeKeepsActiveAndDoesNotInvokeOnDrawn()
	{
		// Codex round-15 contract: ACTION_CANCEL must NOT fire the detection callback. Active stays true so
		// the user can re-paint without re-tapping Auto. Pin both invariants so a regression that swaps
		// cancelStroke for setActive(false, ...) or for end(...) gets caught here.
		HorizonPaintOverlay overlay = new HorizonPaintOverlay();
		boolean[] onDrawnFired = { false };
		overlay.setActive(true, () -> onDrawnFired[0] = true);
		overlay.begin(10f, 20f, new float[] { 100f, 200f });
		overlay.extend(11f, 21f, new float[] { 110f, 210f });

		overlay.cancelStroke();

		assertFalse("onDrawn must NOT fire on ACTION_CANCEL", onDrawnFired[0]);
		assertTrue("paint mode stays active so user can re-paint", overlay.isActive());
		assertFalse("drawing flag clears so the in-progress stroke is gone", overlay.isDrawing());
		assertEquals("imagePoints must be empty after cancel", 0, overlay.getPoints().size());
	}

	@Test
	public void cancelStrokeWithoutActiveIsNoOp()
	{
		// Defensive: cancelStroke on an inactive overlay must not throw. Models the case where a stale
		// MotionEvent.ACTION_CANCEL arrives after paint mode already exited (e.g., via a competing reset
		// from MainActivity.installImageOnUi).
		HorizonPaintOverlay overlay = new HorizonPaintOverlay();
		overlay.cancelStroke();
		assertFalse(overlay.isActive());
		assertFalse(overlay.isDrawing());
	}

	@Test
	public void endInvokesOnDrawnAndExitsActive()
	{
		// Counterpart to cancelStroke: ACTION_UP routes to end(), which DOES fire detection AND exits paint
		// mode. Pin so a regression that aliased end / cancelStroke gets caught.
		HorizonPaintOverlay overlay = new HorizonPaintOverlay();
		boolean[] onDrawnFired = { false };
		overlay.setActive(true, () -> onDrawnFired[0] = true);
		overlay.begin(10f, 20f, new float[] { 100f, 200f });
		overlay.extend(11f, 21f, new float[] { 110f, 210f });

		overlay.end(new float[] { 120f, 220f });

		assertTrue("onDrawn must fire on ACTION_UP", onDrawnFired[0]);
		assertFalse("paint mode exits", overlay.isActive());
		assertFalse("drawing clears", overlay.isDrawing());
		// end() appends the final point — points list should NOT be empty (caller's detector consumes it
		// before the overlay's next setActive(true,...) clear).
		assertEquals("end appends the final image point", 3, overlay.getPoints().size());
	}

	@Test
	public void postCancelStrokeReachesEndAndFiresOriginalCallback()
	{
		// cancelStroke's Javadoc commits to "the user can try painting again without re-tapping Auto" — the
		// existing tests verify only the active-flag side. Pin the behaviour: a fresh begin/extend/end
		// AFTER cancelStroke must produce a clean point list (only the new stroke's points, none from the
		// cancelled one) and must fire the originally-installed onDrawn callback. A regression that left
		// drawing=true mid-cancel or zeroed onDrawn inside cancelStroke would pass the existing assertions
		// but silently break the next stroke.
		HorizonPaintOverlay overlay = new HorizonPaintOverlay();
		boolean[] onDrawnFired = { false };
		overlay.setActive(true, () -> onDrawnFired[0] = true);
		overlay.begin(10f, 20f, new float[] { 100f, 200f });
		overlay.cancelStroke();

		overlay.begin(30f, 40f, new float[] { 300f, 400f });
		overlay.extend(31f, 41f, new float[] { 310f, 410f });
		overlay.end(new float[] { 320f, 420f });

		assertTrue("post-cancel completion fires originally-installed callback", onDrawnFired[0]);
		assertEquals("post-cancel stroke captures only its own 3 points", 3, overlay.getPoints().size());
		assertFalse("end() exits paint mode after successful post-cancel stroke", overlay.isActive());
	}

	@Test
	public void setActiveTrueClearsPriorStrokeAndReplacesCallback()
	{
		// setActive's Javadoc says "Entering clears any previous stroke" and "the caller's previous onDrawn
		// is replaced". Without this test, a regression that drops imagePoints.clear() — or chains the new
		// callback after the old one instead of replacing it — would feed stale points into HorizonDetector
		// (wrong-angle auto-rotate identical to the cancelStroke regression) or fire two callbacks per
		// stroke. Pin both contracts here.
		HorizonPaintOverlay overlay = new HorizonPaintOverlay();
		boolean[] firstFired = { false };
		boolean[] secondFired = { false };
		overlay.setActive(true, () -> firstFired[0] = true);
		overlay.begin(10f, 20f, new float[] { 100f, 200f });
		overlay.extend(11f, 21f, new float[] { 110f, 210f });

		overlay.setActive(true, () -> secondFired[0] = true);

		assertEquals("re-entering paint mode wipes prior stroke points", 0, overlay.getPoints().size());
		assertFalse("drawing flag clears on re-entry", overlay.isDrawing());
		overlay.begin(0f, 0f, new float[] { 0f, 0f });
		overlay.end(new float[] { 1f, 1f });
		assertTrue("new callback fires on next end()", secondFired[0]);
		assertFalse("previous callback must be replaced, not chained", firstFired[0]);
	}
}
