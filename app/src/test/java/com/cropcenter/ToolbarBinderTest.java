package com.cropcenter;

import static org.junit.Assert.assertEquals;

import com.cropcenter.model.AspectRatio;

import org.junit.Test;

/**
 * Tests for ToolbarBinder.arLabel — the chokepoint that turns a model AspectRatio into the compact text shown on the
 * AR chip (and, via UiSync.updateAspectRatioButton, kept in sync with the model). The risk this pins: arLabel maps a
 * preset to its label by indexing AR_LABELS with the index of the matching entry in AR_VALUES, and those two arrays
 * are hand-maintained in parallel. A future edit that inserts or reorders one array without the other would silently
 * mislabel every chip from the edit point on — exactly the kind of pure-logic drift a unit test should catch. Also
 * pins the custom-AR numeric fallback (round, not truncate) and the FREE / null → "Full" contract.
 *
 * arLabel is package-private static, so this test calls it directly with no extraction; the preset-index branch
 * exercises indexOfAspectRatio (private) transitively, and the numeric branch exercises the Custom-sentinel path.
 */
public final class ToolbarBinderTest
{
	@Test
	public void arLabelFreeAndNullAreFull()
	{
		// Both the canonical FREE and a null model AR collapse to "Full" before any array lookup.
		assertEquals("Full", ToolbarBinder.arLabel(null));
		assertEquals("Full", ToolbarBinder.arLabel(AspectRatio.FREE));
	}

	@Test
	public void arLabelMapsAsymmetricPresetsToTheCorrectLabel()
	{
		// 4:5 and 5:4 sit at different AR_VALUES indices — the pair most likely to expose an array
		// misalignment, since swapping them still "looks plausible". Pin both directions.
		assertEquals("4:5", ToolbarBinder.arLabel(AspectRatio.R4_5));
		assertEquals("5:4", ToolbarBinder.arLabel(AspectRatio.R5_4));
	}

	@Test
	public void arLabelMapsEachPresetToItsParallelArrayLabel()
	{
		// Walk every real preset so a one-slot shift in either hand-maintained array fails here. Order must
		// match AR_VALUES[1..9] / AR_LABELS[1..9] (index 0 = FREE/"Full", index 10 = Custom sentinel).
		assertEquals("16:9", ToolbarBinder.arLabel(AspectRatio.R16_9));
		assertEquals("3:2", ToolbarBinder.arLabel(AspectRatio.R3_2));
		assertEquals("4:3", ToolbarBinder.arLabel(AspectRatio.R4_3));
		assertEquals("1:1", ToolbarBinder.arLabel(AspectRatio.R1_1));
		assertEquals("3:4", ToolbarBinder.arLabel(AspectRatio.R3_4));
		assertEquals("2:3", ToolbarBinder.arLabel(AspectRatio.R2_3));
		assertEquals("9:16", ToolbarBinder.arLabel(AspectRatio.R9_16));
	}

	@Test
	public void arLabelRoundsFractionalCustomRatio()
	{
		// A cinematic 2.39:1 isn't a preset → numeric fallback via Math.round on each component. round(2.39)=2,
		// round(1)=1 → "2:1". Pins that the fallback rounds rather than truncates (truncation also gives "2:1"
		// here, so use a value where they differ: 2.6 rounds to 3, truncates to 2).
		assertEquals("2:1", ToolbarBinder.arLabel(new AspectRatio(2.39f, 1f)));
		assertEquals("3:1", ToolbarBinder.arLabel(new AspectRatio(2.6f, 1f)));
	}

	@Test
	public void arLabelUsesNumericFallbackForUnlistedIntegerRatio()
	{
		// An integer ratio that is NOT one of the presets (21:9 ultrawide) falls through indexOfAspectRatio's
		// no-match path to the Custom sentinel index, then to the numeric "W:H" form.
		assertEquals("21:9", ToolbarBinder.arLabel(new AspectRatio(21, 9)));
	}
}
