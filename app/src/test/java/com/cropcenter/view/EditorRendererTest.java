package com.cropcenter.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for EditorRenderer's render-path gate. At zoom ≥ 4 every source reaches crisp 1:1 pixels: an in-budget source
 * via the whole-source upload (shouldRenderSource), an over-budget source via the visible-region tile
 * (shouldRenderSourceTile). Below 4× both are false and the display proxy draws. The bug class this pins: the gate is a
 * three-way AND over a long pixel-product and two axis comparisons against a float threshold — exactly where a `>` vs
 * `>=` slip or an int-overflow wrap silently flips the decision — plus the partition invariant that exactly one crisp
 * path fires at scale ≥ 4 (a gap there is the original un-peekable-200-MP bug). tileCovers pins the region-tile cache's
 * containment-only validity: rotation is deliberately absent from the predicate's signature (the tile holds raw
 * axis-aligned source pixels positioned by the draw matrix), so a rotation change alone must NOT force a re-cut — its
 * absence from the signature is the pin. All predicates are package-private static so this runs Canvas-free.
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

	@Test
	public void tileAndWholeSourcePartitionSourcesAtFourX()
	{
		// At scale ≥ 4 every source reaches true 1:1 pixels by exactly ONE crisp path: in-budget via the
		// whole-source upload, over-budget via the tile. Pin the partition (exactly-one-true via XOR) so a
		// future cap tweak can't open a gap where neither fires (proxy stuck at high zoom = the 200 MP bug) nor
		// a state where both fire.
		int[][] sizes = {
			{1000, 1000},      // tiny, in-budget
			{8192, 8192},      // exactly 64 MP, in-budget
			{8200, 8200},      // just over the pixel cap, over-budget
			{16384, 12288},    // 200 MP capture, over-budget on pixels
			{16385, 1000},     // panorama over the axis cap, over-budget on axis
		};
		for (int[] size : sizes)
		{
			boolean whole = EditorRenderer.shouldRenderSource(size[0], size[1], 4f);
			boolean tile = EditorRenderer.shouldRenderSourceTile(size[0], size[1], 4f);
			assertTrue(size[0] + "x" + size[1] + " must take exactly one crisp path at 4x", whole ^ tile);
		}
	}

	@Test
	public void tileCoversContainedAabbIncludingExactBoundaries()
	{
		// Interior containment reuses the tile (no re-cut) — the steady-state for small pans and for every
		// rotation-only change at high zoom.
		assertTrue("interior AABB", EditorRenderer.tileCovers(100, 200, 400, 300, 150, 250, 450, 450));
		// Exact edge coincidence on all four boundaries is still contained (>= / <= are inclusive) — a
		// regression to strict comparisons would re-cut every frame the viewport rests on the tile edge.
		assertTrue("AABB == tile rect", EditorRenderer.tileCovers(100, 200, 400, 300, 100, 200, 500, 500));
	}

	@Test
	public void tileCoversRejectsEscapeOnAnyEdge()
	{
		// One pixel past any single edge forces a re-cut — each escape direction is pinned independently so a
		// dropped containment term (the cache-validity bug class) fails exactly one assertion.
		assertFalse("left escape", EditorRenderer.tileCovers(100, 200, 400, 300, 99, 250, 450, 450));
		assertFalse("top escape", EditorRenderer.tileCovers(100, 200, 400, 300, 150, 199, 450, 450));
		assertFalse("right escape", EditorRenderer.tileCovers(100, 200, 400, 300, 150, 250, 501, 450));
		assertFalse("bottom escape", EditorRenderer.tileCovers(100, 200, 400, 300, 150, 250, 450, 501));
	}

	@Test
	public void tilePathActivatesForOverBudgetSourceAtFourX()
	{
		// The 200 MP capture (16384×12288 = 201 MP) the whole-source upload rejects on the pixel cap must take
		// the tile path at the pixel-peep transition — otherwise a 200 MP source is un-peekable at zoom ≥ 4.
		assertFalse("200 MP not whole-uploaded", EditorRenderer.shouldRenderSource(16384, 12288, 4f));
		assertTrue("200 MP tiles at 4x", EditorRenderer.shouldRenderSourceTile(16384, 12288, 4f));
		// The axis-over panorama and an int-overflow-sized source also route to the tile path, not the proxy.
		assertTrue("axis-over panorama tiles", EditorRenderer.shouldRenderSourceTile(16385, 1000, 4f));
		assertTrue("overflow-sized source tiles", EditorRenderer.shouldRenderSourceTile(46341, 46341, 4f));
	}

	@Test
	public void tilePathInactiveBelowFourXAndForInBudgetSources()
	{
		// Below the transition the proxy handles every source — the tile path must stay off so the grid never
		// paints over the bilinear proxy.
		assertFalse("over-budget just under 4x", EditorRenderer.shouldRenderSourceTile(16384, 12288, 3.999f));
		// An in-budget source is served by the whole-source upload, never the tile.
		assertFalse("in-budget never tiles", EditorRenderer.shouldRenderSourceTile(8192, 8192, 4f));
	}
}
