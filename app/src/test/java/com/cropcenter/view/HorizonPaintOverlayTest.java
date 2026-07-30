package com.cropcenter.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Tests for HorizonPaintOverlay's stroke / cancel state machine and the isStrokeTooShort degenerate-stroke predicate.
 * The render path uses android.graphics.Canvas / Paint and isn't reachable from pure JUnit, but the active / drawing
 * flags + the imagePoints bookkeeping are pure Java and drive the auto-rotate UX directly: a regression here would
 * either trigger false-positive detections on aborted strokes or strand paint mode after a cancel.
 */
public final class HorizonPaintOverlayTest
{
	@Test
	public void cancelStrokeExitsPaintModeAndDoesNotInvokeOnDrawn()
	{
		// Contract: ACTION_CANCEL must NOT fire the detection callback, AND it MUST exit paint mode by clearing
		// active=false — a clean exit; the user re-taps Auto to retry. Keeping active=true ("so the user can
		// re-paint") would strand the overlay consuming touches when a pre-stroke CANCEL fires (system gesture,
		// multi-touch race), leaving the user unable to escape until they notice the still-Cancel-labelled Auto
		// button. Pin onDrawn-NOT-fired AND active-cleared so a regression in either direction surfaces.
		HorizonPaintOverlay overlay = new HorizonPaintOverlay();
		boolean[] onDrawnFired = { false };
		overlay.setActive(true, () -> onDrawnFired[0] = true);
		overlay.begin(10f, 20f, new float[] { 100f, 200f });
		overlay.extend(11f, 21f, new float[] { 110f, 210f });

		overlay.cancelStroke();

		assertFalse("onDrawn must NOT fire on ACTION_CANCEL", onDrawnFired[0]);
		assertFalse("paint mode exits on CANCEL (was stranding user before this fix)", overlay.isActive());
		assertFalse("drawing flag clears so the in-progress stroke is gone", overlay.isDrawing());
		assertEquals("imagePoints must be empty after cancel", 0, overlay.getPoints().size());
	}

	@Test
	public void cancelStrokeFiresOnCancelCallbackForHostCleanup()
	{
		// New contract: cancelStroke fires the onCancel callback so the host can reset external UI (e.g.
		// AutoRotateBinder resets the "Cancel" → "Auto" button label). The callback is one-shot (cleared after
		// fire) so a subsequent end() doesn't double-fire it. Pin firing AND one-shot semantics.
		HorizonPaintOverlay overlay = new HorizonPaintOverlay();
		int[] cancelCount = { 0 };
		overlay.setActive(true, () -> { }, () -> cancelCount[0]++);
		overlay.cancelStroke();
		assertEquals("onCancel must fire exactly once on CANCEL", 1, cancelCount[0]);
		// Re-enter paint mode without a cancel callback; a second cancelStroke must not throw / re-fire
		overlay.setActive(true, () -> { });
		overlay.cancelStroke();
		assertEquals("onCancel from prior entry must not persist across setActive", 1, cancelCount[0]);
	}

	@Test
	public void cancelStrokeWithoutActiveIsNoOp()
	{
		// Defensive: cancelStroke on an inactive overlay must not throw. Models the case where a stale
		// MotionEvent.ACTION_CANCEL arrives after paint mode already exited (e.g., via a competing reset from
		// MainActivity.installImageOnUi).
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
		// end() appends the final point — points list should NOT be empty (caller's detector consumes it before
		// the overlay's next setActive(true,...) clear).
		assertEquals("end appends the final image point", 3, overlay.getPoints().size());
	}

	@Test
	public void isStrokeTooShortAcceptsRealStroke()
	{
		// A genuine horizon stroke — multiple points spanning well past the brush radius — must reach
		// detection. Pin the accept side so the gate can't regress into rejecting every stroke.
		List<float[]> stroke = List.of(new float[] { 100f, 200f },
			new float[] { 180f, 205f }, new float[] { 260f, 210f }, new float[] { 340f, 215f });
		assertFalse("real stroke spanning past minSpan must be accepted",
			HorizonPaintOverlay.isStrokeTooShort(stroke, 30f));
	}

	@Test
	public void isStrokeTooShortAcceptsSpanExactlyAtThreshold()
	{
		// Boundary pin: the comparison is strict (span < minSpan rejects), so a stroke spanning EXACTLY minSpan
		// is accepted. A regression to <= would reject at-threshold strokes.
		List<float[]> stroke = List.of(new float[] { 0f, 0f }, new float[] { 30f, 0f });
		assertFalse("span exactly equal to minSpan must be accepted",
			HorizonPaintOverlay.isStrokeTooShort(stroke, 30f));
	}

	@Test
	public void isStrokeTooShortMeasuresBoundingBoxNotEndpointDistance()
	{
		// A V-shaped stroke whose begin and end points coincide has zero endpoint distance but a real painted
		// extent. Pin the bounding-box measurement so a regression to first-vs-last distance (which would
		// reject this valid stroke) surfaces.
		List<float[]> stroke = List.of(new float[] { 0f, 0f },
			new float[] { 40f, 40f }, new float[] { 0f, 0f });
		assertFalse("V-stroke with coincident endpoints spans its bounding box, not 0",
			HorizonPaintOverlay.isStrokeTooShort(stroke, 30f));
	}

	@Test
	public void isStrokeTooShortRejectsCoincidentTapStroke()
	{
		// The real-gesture degenerate case: a bare tap routes ACTION_DOWN → begin and ACTION_UP → end,
		// contributing exactly 2 COINCIDENT points — so a point-count gate can never fire. The distance gate
		// must reject the zero-span pair or a tap dispatches a full bg Hough detection.
		List<float[]> tap = List.of(new float[] { 100.5f, 200.25f }, new float[] { 100.5f, 200.25f });
		assertTrue("coincident begin/end points (bare tap) must be rejected",
			HorizonPaintOverlay.isStrokeTooShort(tap, 30f));
	}

	@Test
	public void isStrokeTooShortRejectsFewerThanTwoPoints()
	{
		// Defensive: empty / single-point lists can't span anything. Unreachable through the overlay's
		// begin/end pairing but pinned so direct callers can rely on it.
		assertTrue("empty point list is too short", HorizonPaintOverlay.isStrokeTooShort(List.of(), 30f));
		assertTrue("single point is too short",
			HorizonPaintOverlay.isStrokeTooShort(List.of(new float[] { 5f, 5f }), 30f));
	}

	@Test
	public void isStrokeTooShortRejectsSpanJustUnderThreshold()
	{
		// Jitter case: finger wobble during a tap produces a small nonzero span. Anything under the brush
		// radius is an isotropic blob with no direction for the line fit — reject.
		List<float[]> jitter = List.of(new float[] { 0f, 0f }, new float[] { 0f, 29.9f });
		assertTrue("span just under minSpan must be rejected",
			HorizonPaintOverlay.isStrokeTooShort(jitter, 30f));
	}

	@Test
	public void postCancelStrokeReachesEndAndFiresOriginalCallback()
	{
		// cancelStroke's Javadoc commits to "the user can try painting again without re-tapping Auto" — the
		// existing tests verify only the active-flag side. Pin the behaviour: a fresh begin/extend/end AFTER
		// cancelStroke must produce a clean point list (only the new stroke's points, none from the cancelled
		// one) and must fire the originally-installed onDrawn callback. A regression that left drawing=true
		// mid-cancel or zeroed onDrawn inside cancelStroke would pass the existing assertions but silently
		// break the next stroke.
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
		// setActive's Javadoc says "Entering clears any previous stroke" and "the caller's previous onDrawn is
		// replaced". Without this test, a regression that drops imagePoints.clear() — or chains the new
		// callback after the old one instead of replacing it — would feed stale points into HorizonDetector
		// (wrong-angle auto-rotate identical to the cancelStroke regression) or fire two callbacks per stroke.
		// Pin both contracts here.
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
