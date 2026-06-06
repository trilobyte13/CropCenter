package com.cropcenter.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for EditorRenderer.shouldRenderSource — the proxy-vs-source render gate. At zoom ≥ 4 the renderer wants to
 * draw the full source bitmap for crisp pixel-grid pixels, but only when the source fits the GPU texture budget;
 * past either cap it must keep drawing the (smaller) display proxy or risk a frozen/failed texture upload. The bug
 * class this pins: the gate is a three-way AND over a long pixel-product and two axis comparisons, with a float
 * threshold — exactly the kind of boundary logic where a `>` vs `>=` slip or an int-overflow wrap silently flips
 * the decision. shouldRenderSource is package-private static so this runs Canvas-free.
 *
 * Caps (from BitmapUtils): MAX_SOURCE_RENDER_PIXELS = 64*1024*1024 = 67_108_864; MAX_SOURCE_RENDER_AXIS = 16384.
 */
public final class EditorRendererTest
{
	@Test
	public void atExactlyFourTimesZoomRendersInBudgetSource()
	{
		// scale >= 4f is inclusive — pin the exact boundary so a regression to `> 4f` (which would briefly keep
		// the proxy at the transition) fails here.
		assertTrue("exactly 4x, small source", EditorRenderer.shouldRenderSource(1000, 1000, 4f));
	}

	@Test
	public void axisCapRejectsPanoramaUnderPixelCap()
	{
		// The headline case the per-axis gate exists for: a 16385x1000 panorama is only ~16 MP (well under the
		// 64 MP pixel cap) but its width exceeds MAX_SOURCE_RENDER_AXIS, so it must NOT render as source.
		assertFalse("panorama over axis cap", EditorRenderer.shouldRenderSource(16385, 1000, 4f));
		// Exactly at the axis cap (16384) with a small other axis fits — pin the inclusive boundary.
		assertTrue("panorama exactly at axis cap", EditorRenderer.shouldRenderSource(16384, 1000, 4f));
		// Symmetric: tall panorama over the axis cap on height.
		assertFalse("tall panorama over axis cap", EditorRenderer.shouldRenderSource(1000, 16385, 4f));
	}

	@Test
	public void belowFourTimesZoomNeverRendersSource()
	{
		// The scale gate is the first guard: under 4× we always keep the proxy regardless of source size.
		assertFalse("just under 4x", EditorRenderer.shouldRenderSource(1000, 1000, 3.999f));
		assertFalse("1x", EditorRenderer.shouldRenderSource(1000, 1000, 1f));
	}

	@Test
	public void hugeSourceDoesNotOverflowIntoAFalsePass()
	{
		// 46341*46341 = 2_147_488_281 > Integer.MAX_VALUE: an int multiply would wrap to a NEGATIVE value and
		// spuriously pass `<= MAX_SOURCE_RENDER_PIXELS`. The long cast in shouldRenderSource keeps the product
		// correct, and (independently) the axis cap also rejects it. Pin false so a regression dropping the
		// (long) cast — or reordering so the wrapped product is consulted first — is caught.
		assertFalse("overflow-sized source", EditorRenderer.shouldRenderSource(46341, 46341, 4f));
	}

	@Test
	public void pixelCapIsInclusiveAtExactlySixtyFourMegapixels()
	{
		// 8192 * 8192 = 67_108_864 == MAX_SOURCE_RENDER_PIXELS exactly, and 8192 <= 16384 on both axes, so the
		// source fits (<= is inclusive). One pixel more must fail the pixel gate.
		assertTrue("exactly at pixel cap", EditorRenderer.shouldRenderSource(8192, 8192, 4f));
		// 8200*8200 = 67_240_000 > cap, both axes 8200 <= 16384 → fails the PIXEL gate alone (axes are fine).
		assertFalse("just over pixel cap", EditorRenderer.shouldRenderSource(8200, 8200, 4f));
	}
}
