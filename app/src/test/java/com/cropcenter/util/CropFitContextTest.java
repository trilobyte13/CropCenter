package com.cropcenter.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the CropFitContext factory and the negation invariant. The factory caches `cos(-θ)` and `sin(-θ)`
 * because RotatedCropClamp checks corners by un-rotating them — a sign flip in `of()` would silently corrupt
 * every rotated-crop clamp call across the codebase.
 */
public final class CropFitContextTest
{
	@Test
	public void ofAt45DegreesProducesIdenticalAbsValuesForSinAndCos()
	{
		// At 45° (or -45°), |sin| = |cos| = √2/2 ≈ 0.7071. Pin the magnitude with a wider tolerance so a
		// trig-table regression is caught.
		CropFitContext ctx45 = CropFitContext.of(100, 80, 45f, 1000, 800);
		assertEquals(Math.cos(Math.toRadians(-45)), ctx45.cosR(), 1e-9);
		assertEquals(Math.sin(Math.toRadians(-45)), ctx45.sinR(), 1e-9);
		// Magnitudes equal at ±45°.
		assertTrue(Math.abs(Math.abs(ctx45.cosR()) - Math.abs(ctx45.sinR())) < 1e-9);
	}

	@Test
	public void ofAt90DegreesNegatesSine()
	{
		// At +90° image rotation, the un-rotation factor is -90°. sin(-90°) = -1, cos(-90°) = 0. If a future
		// regression dropped the negation, sin(+90°) = +1 would be cached instead — this test catches that
		// since +1 != -1.
		CropFitContext ctx = CropFitContext.of(100, 80, 90f, 1000, 800);
		assertEquals(0.0, ctx.cosR(), 1e-9);
		assertEquals(-1.0, ctx.sinR(), 1e-9);
	}

	@Test
	public void ofAtNegative90DegreesProducesPositiveSine()
	{
		// Symmetric: -90° image rotation, un-rotation factor is +90°. sin(+90°) = +1.
		CropFitContext ctx = CropFitContext.of(100, 80, -90f, 1000, 800);
		assertEquals(0.0, ctx.cosR(), 1e-9);
		assertEquals(1.0, ctx.sinR(), 1e-9);
	}

	@Test
	public void ofAtZeroRotationProducesCosOneSinZero()
	{
		// Identity rotation — cos(0) = 1, sin(0) = 0. Pin so a regression that drops the negation never
		// shows up here because zero is its own negation.
		CropFitContext ctx = CropFitContext.of(100, 80, 0f, 1000, 800);
		assertEquals(1.0, ctx.cosR(), 1e-9);
		assertEquals(0.0, ctx.sinR(), 1e-9);
	}

	@Test
	public void ofComputesHalfDimensionsAsFloatNotInt()
	{
		// Odd cropW / cropH must produce half-pixel offsets — `cropW / 2f` not `cropW / 2` (integer
		// division). Pin so a regression that drops the float divisor surfaces here.
		CropFitContext ctx = CropFitContext.of(7, 5, 0f, 1000, 800);
		assertEquals(3.5f, ctx.halfWidth(), 0f);
		assertEquals(2.5f, ctx.halfHeight(), 0f);
	}

	@Test
	public void ofComputesImageMidpointAsFloat()
	{
		// Odd image dim → half-pixel midpoint. Same float-divisor concern as above.
		CropFitContext ctx = CropFitContext.of(100, 80, 0f, 1001, 801);
		assertEquals(500.5f, ctx.imageMidX(), 0f);
		assertEquals(400.5f, ctx.imageMidY(), 0f);
	}

	@Test
	public void ofInfinityRotationProducesNanTrig()
	{
		// Mirror NaN behavior — Infinity is the other non-finite input that could leak through if a
		// future caller bypassed CropState's setRotationDegrees sanitisation. Math.cos(Infinity) and
		// Math.sin(Infinity) both return NaN per JLS.
		CropFitContext ctx = CropFitContext.of(100, 80, Float.POSITIVE_INFINITY, 1000, 800);
		assertTrue(Double.isNaN(ctx.cosR()));
		assertTrue(Double.isNaN(ctx.sinR()));
		assertEquals(1000, ctx.imgW());
		assertEquals(800, ctx.imgH());
	}

	@Test
	public void ofNanRotationProducesNanTrig()
	{
		// CropState.setRotationDegrees sanitises NaN to 0, so production never reaches this helper
		// with NaN. But the helper is package-public static — a future refactor that bypasses
		// CropState could call it directly with NaN. Pin the actual behaviour (NaN propagates
		// through Math.cos/sin to both cosR/sinR) so callers can rely on "non-finite in → non-finite
		// out" rather than crash or coincidental zero.
		CropFitContext ctx = CropFitContext.of(100, 80, Float.NaN, 1000, 800);
		assertTrue(Double.isNaN(ctx.cosR()));
		assertTrue(Double.isNaN(ctx.sinR()));
		// imgW/imgH unaffected by rotation — confirm the non-rotation fields still pass through.
		assertEquals(1000, ctx.imgW());
		assertEquals(800, ctx.imgH());
	}

	@Test
	public void ofPreservesImageDimsAsInts()
	{
		// imgW / imgH stay as the input ints — pin to catch a refactor that accidentally widened them.
		CropFitContext ctx = CropFitContext.of(100, 80, 0f, 1234, 567);
		assertEquals(1234, ctx.imgW());
		assertEquals(567, ctx.imgH());
	}
}
