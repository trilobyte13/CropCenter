package com.cropcenter.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for the CropRender value class's derived accessors. The `srcX()` / `srcY()` formulas are the chokepoint
 * for crop-origin computation in the export pipeline; a sign flip would silently mis-place the gain map's
 * spatial alignment relative to the primary scan and produce HDR halos around every cropped output.
 *
 * All construction goes through CropRender.of(...) — the canonical positional constructor is private precisely to
 * keep callers (including tests) from accidentally transposing (W, H) pairs against storage's (H, W) alphabetical
 * order. Tests using the public factory exercise the same compile-time-enforced API surface as production code.
 */
public final class CropRenderTest
{
	@Test
	public void factoryParameterOrderMatchesAccessorReadback()
	{
		// Pin the factory's argument-to-field mapping. Pass DISTINCT W and H values so a regression
		// that drops the (W, H) → (H, W) swap inside of() would silently transpose every export's
		// gain-map dimensions and surface as visible HDR halos around cropped outputs.
		CropRender render = CropRender.of(50f, 60f, 100, 200, 1000, 2000, 30f);
		assertEquals(50f, render.centerX(), 0f);
		assertEquals(60f, render.centerY(), 0f);
		assertEquals("cropW must equal the W passed to of(...)", 100, render.cropW());
		assertEquals("cropH must equal the H passed to of(...)", 200, render.cropH());
		assertEquals("imgW must equal the W passed to of(...)", 1000, render.imgW());
		assertEquals("imgH must equal the H passed to of(...)", 2000, render.imgH());
		assertEquals(30f, render.rotation(), 0f);
	}

	@Test
	public void srcXAndYAtZeroSizedCrop()
	{
		// cropW = cropH = 0 → srcX = centerX, srcY = centerY. Defensive check that the formula doesn't
		// special-case zero.
		CropRender zero = CropRender.of(42.5f, 17.25f, 0, 0, 100, 100, 0f);
		assertEquals(42.5f, zero.srcX(), 0f);
		assertEquals(17.25f, zero.srcY(), 0f);
	}

	@Test
	public void srcXProducesHalfPixelOffsetForOddCropWidth()
	{
		// Odd cropW = 5: srcX = centerX - 2.5. The half-pixel offset is intentional — CropExporter rounds
		// later. Pin so a regression that changes cropW / 2f to cropW / 2 (integer division) is caught.
		CropRender odd = CropRender.of(10f, 10f, 5, 5, 100, 100, 0f);
		assertEquals(7.5f, odd.srcX(), 0f);
		assertEquals(7.5f, odd.srcY(), 0f);
	}

	@Test
	public void srcXSubtractsHalfCropWFromCenterX()
	{
		// Even crop width: srcX = centerX - cropW/2 lands on integer pixel.
		CropRender even = CropRender.of(100f, 50f, 60, 80, 1200, 800, 0f);
		assertEquals(70f, even.srcX(), 0f);
	}

	@Test
	public void srcYSubtractsHalfCropHFromCenterY()
	{
		CropRender even = CropRender.of(100f, 50f, 60, 80, 1200, 800, 0f);
		assertEquals(10f, even.srcY(), 0f);
	}
}
