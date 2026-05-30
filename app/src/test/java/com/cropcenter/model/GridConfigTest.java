package com.cropcenter.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for GridConfig.defaults() — the values applied to every freshly-loaded image. The footgun-prevention
 * rule the spec calls out is `includeInExport=false`: a regression that flipped this to true would silently
 * bake grids into every saved image even when the user hadn't checked the toggle. Pin every default value
 * so a wrong-default change is visible in test output.
 */
public final class GridConfigTest
{
	@Test
	public void defaultsHaveDocumentedValues()
	{
		GridConfig defaults = GridConfig.defaults();
		assertTrue("grid is visible by default", defaults.enabled());
		assertFalse("grid bake-in is OFF by default — saving must not silently embed a grid",
			defaults.includeInExport());
		assertTrue("pixel grid is on (kicks in at high zoom)", defaults.showPixelGrid());
		assertEquals("default line width = 1px", 1f, defaults.lineWidth(), 0f);
		assertEquals("default grid color = white", 0xFFFFFFFF, defaults.color());
		assertEquals("default columns = 4 (rule of thirds + 1)", 4, defaults.columns());
		assertEquals("default rows = 4", 4, defaults.rows());
		assertEquals("default pixel-grid color = black", 0xFF000000, defaults.pixelGridColor());
		assertEquals("default selection color = 50% blue", 0x800000FF, defaults.selectionColor());
	}

	@Test
	public void withEnabledTogglesMasterFlagAndPreservesSiblings()
	{
		// The master toggle. Pin: flipping it from default-true to false rounds-trips through every
		// sibling field (cols/rows/color/lineWidth/pixelGridColor/selectionColor) without touching them.
		// A regression in the canonical constructor parameter order — easy with 9 boolean / int / float
		// positional args — would silently reassign one field to another and surface here.
		GridConfig original = GridConfig.defaults().withColumns(8).withSelectionColor(0xAA00FFFF);
		GridConfig hidden = original.withEnabled(false);
		assertFalse(hidden.enabled());
		assertEquals(8, hidden.columns());
		assertEquals(0xAA00FFFF, hidden.selectionColor());
		assertEquals("lineWidth must survive enabled toggle", original.lineWidth(), hidden.lineWidth(), 0f);
	}

	@Test
	public void withIncludeInExportTogglesIndependently()
	{
		// Bake-in is the per-save toggle that defaults() enforces off. The transformer must round-trip
		// without affecting other fields.
		GridConfig baked = GridConfig.defaults().withIncludeInExport(true);
		assertTrue(baked.includeInExport());
		assertEquals("columns must survive bake-in toggle", 4, baked.columns());
		assertEquals("color must survive bake-in toggle", 0xFFFFFFFF, baked.color());
	}

	@Test
	public void withLineWidthReplacesStrokeAndPreservesSiblings()
	{
		// Grid line stroke width in image pixels. The toolbar's grid-thickness slider routes through this.
		// Pin both the replacement (new value lands) AND the sibling preservation (cols / rows / colors
		// don't get clobbered).
		GridConfig original = GridConfig.defaults().withColumns(5).withSelectionColor(0xAA00FFFF);
		GridConfig thicker = original.withLineWidth(3.5f);
		assertEquals(3.5f, thicker.lineWidth(), 0f);
		assertEquals(5, thicker.columns());
		assertEquals(0xAA00FFFF, thicker.selectionColor());
	}

	@Test
	public void withSelectionColorReplacesColorAndPreservesSiblings()
	{
		// The shared color for selection points, polygon fill, and horizon paint. A regression that
		// dropped this from the transformer would leave the paint color stuck at the previous value
		// across user changes — pin so the round-trip is observable here.
		GridConfig original = GridConfig.defaults().withColumns(7);
		GridConfig recolored = original.withSelectionColor(0x80FF00FF);
		assertEquals(0x80FF00FF, recolored.selectionColor());
		assertEquals("columns must survive selectionColor swap", 7, recolored.columns());
		assertEquals("color must survive selectionColor swap", 0xFFFFFFFF, recolored.color());
	}

	@Test
	public void withShowPixelGridTogglesPixelGridFlagAndPreservesSiblings()
	{
		// The per-pixel grid overlay that fires at high zoom (~3dp per source pixel). Independent toggle
		// from `enabled`. Pin that flipping it doesn't disturb the main grid's own enabled state or any
		// color / size siblings.
		GridConfig original = GridConfig.defaults().withColumns(8).withColor(0xFF0000FF);
		GridConfig hidden = original.withShowPixelGrid(false);
		assertFalse(hidden.showPixelGrid());
		assertTrue("main grid enabled flag must survive pixel-grid toggle", hidden.enabled());
		assertEquals(8, hidden.columns());
		assertEquals(0xFF0000FF, hidden.color());
	}

	@Test
	public void withTransformersChainCorrectly()
	{
		// Pin that withXxx transformers preserve all unrelated fields. Without this, a regression that
		// dropped `pixelGridColor` from `withColumns` would silently reset the pixel-grid color back to
		// black on every column-count change.
		GridConfig original = GridConfig.defaults().withPixelGridColor(0xFFFF0000);
		GridConfig changed = original.withColumns(7).withRows(9).withColor(0xFF00FF00);
		assertEquals(7, changed.columns());
		assertEquals(9, changed.rows());
		assertEquals(0xFF00FF00, changed.color());
		assertEquals("pixelGridColor must survive column / row / color updates",
			0xFFFF0000, changed.pixelGridColor());
		assertEquals("selectionColor must survive", original.selectionColor(), changed.selectionColor());
	}
}
