package com.cropcenter.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins TouchGestureHandler.shouldConsumeScale — the onScale deadzone gate whose return value doubles as
 * ScaleGestureDetector's "handled" flag. Consuming (true) fires onZoom and resets the detector's span baseline;
 * suppressing (false) leaves the baseline in place so sub-threshold motion accumulates across events. Both directions
 * of the gate are load-bearing: an always-true regression resets the baseline on every event and a slow steady pinch
 * (per-event deltas ~1–1.2% at 60–120 Hz) never zooms, while a widened band lets hold-still jitter drift the viewport.
 *
 * The band edge is 1.5% (SCALE_DEADZONE = 0.015f). No float scaleFactor lands exactly ON the band edge — neither 1f +
 * 0.015f nor 1f - 0.015f round-trips through the subtraction to 0.015f — so the boundary tests pin the nearest
 * representable inputs on each side.
 */
public final class TouchGestureHandlerTest
{
	@Test
	public void factorsAtDeadzoneBoundaryAreSuppressed()
	{
		// 1.015f / 0.985f — the closest float inputs to "exactly 1.5%" — both land a hair inside the band after
		// the |scaleFactor - 1| subtraction, so they read as jitter and stay suppressed.
		assertFalse("1.015 is jitter", TouchGestureHandler.shouldConsumeScale(1.015f));
		assertFalse("0.985 is jitter", TouchGestureHandler.shouldConsumeScale(0.985f));
	}

	@Test
	public void factorsInsideDeadzoneAreSuppressed()
	{
		// Hand tremor / sensor noise sits under 1% — squarely inside the band in both directions.
		assertFalse("no-op factor", TouchGestureHandler.shouldConsumeScale(1f));
		assertFalse("+1% jitter", TouchGestureHandler.shouldConsumeScale(1.01f));
		assertFalse("-1% jitter", TouchGestureHandler.shouldConsumeScale(0.99f));
		assertFalse("+1.4% jitter", TouchGestureHandler.shouldConsumeScale(1.014f));
		assertFalse("-1.4% jitter", TouchGestureHandler.shouldConsumeScale(0.986f));
	}

	@Test
	public void factorsOutsideDeadzoneAreConsumed()
	{
		// Just past the band edge and up through an intentional-pinch 2–10% per-event delta.
		assertTrue("+1.6% crosses the gate", TouchGestureHandler.shouldConsumeScale(1.016f));
		assertTrue("-1.6% crosses the gate", TouchGestureHandler.shouldConsumeScale(0.984f));
		assertTrue("+5% intentional pinch", TouchGestureHandler.shouldConsumeScale(1.05f));
		assertTrue("-5% intentional pinch", TouchGestureHandler.shouldConsumeScale(0.95f));
	}
}
