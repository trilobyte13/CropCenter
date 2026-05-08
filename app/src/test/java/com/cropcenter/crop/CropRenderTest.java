package com.cropcenter.crop;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for the CropRender record's derived accessors. The `srcX()` / `srcY()` formulas are the chokepoint
 * for crop-origin computation in the export pipeline; a sign flip would silently mis-place the gain map's
 * spatial alignment relative to the primary scan and produce HDR halos around every cropped output.
 */
public final class CropRenderTest
{
	@Test
	public void srcXSubtractsHalfCropWFromCenterX()
	{
		// Even crop width: srcX = centerX - cropW/2 lands on integer pixel.
		CropRender even = new CropRender(100f, 50f, 80, 60, 800, 1200, 0f);
		assertEquals(70f, even.srcX(), 0f);
	}

	@Test
	public void srcYSubtractsHalfCropHFromCenterY()
	{
		CropRender even = new CropRender(100f, 50f, 80, 60, 800, 1200, 0f);
		assertEquals(10f, even.srcY(), 0f);
	}

	@Test
	public void srcXProducesHalfPixelOffsetForOddCropWidth()
	{
		// Odd cropW = 5: srcX = centerX - 2.5. The half-pixel offset is intentional — CropExporter rounds
		// later. Pin so a regression that changes cropW / 2f to cropW / 2 (integer division) is caught.
		CropRender odd = new CropRender(10f, 10f, 5, 5, 100, 100, 0f);
		assertEquals(7.5f, odd.srcX(), 0f);
		assertEquals(7.5f, odd.srcY(), 0f);
	}

	@Test
	public void srcXAndYAtZeroSizedCrop()
	{
		// cropW = cropH = 0 → srcX = centerX, srcY = centerY. Defensive check that the formula doesn't
		// special-case zero.
		CropRender zero = new CropRender(42.5f, 17.25f, 0, 0, 100, 100, 0f);
		assertEquals(42.5f, zero.srcX(), 0f);
		assertEquals(17.25f, zero.srcY(), 0f);
	}

	@Test
	public void recordPreservesAllComponents()
	{
		// Pin the (cropH, cropW) ordering — the record's ordering is non-conventional (H before W) and
		// callers downstream rely on the documented argument order.
		CropRender render = new CropRender(1f, 2f, 3, 4, 5, 6, 7f);
		assertEquals(1f, render.centerX(), 0f);
		assertEquals(2f, render.centerY(), 0f);
		assertEquals(3, render.cropH());
		assertEquals(4, render.cropW());
		assertEquals(5, render.imgH());
		assertEquals(6, render.imgW());
		assertEquals(7f, render.rotation(), 0f);
	}

	@Test
	public void factoryAppliesWhSwapToReachCanonicalComponentOrder()
	{
		// CropRender.of(...) takes (cropW, cropH, imgW, imgH) per the codebase's (W, H) convention and
		// forwards to the canonical record constructor's (cropH, cropW, imgH, imgW) alphabetical order
		// with the swap applied. The only production caller (CropExporter.buildCroppedGainMap) routes
		// through this factory; a regression that drops the swap (e.g., a refactor making `of()` a
		// pass-through) would silently transpose every export's gain-map dimensions, producing visible
		// HDR halos around every cropped output. Pass DISTINCT W and H values so the swap is observable.
		CropRender render = CropRender.of(50f, 60f, 100, 200, 1000, 2000, 30f);
		assertEquals(50f, render.centerX(), 0f);
		assertEquals(60f, render.centerY(), 0f);
		assertEquals("cropW must equal the W passed to of(...)", 100, render.cropW());
		assertEquals("cropH must equal the H passed to of(...)", 200, render.cropH());
		assertEquals("imgW must equal the W passed to of(...)", 1000, render.imgW());
		assertEquals("imgH must equal the H passed to of(...)", 2000, render.imgH());
		assertEquals(30f, render.rotation(), 0f);
	}
}
