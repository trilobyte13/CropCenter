package com.cropcenter.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Cardinality + ordering pin for CenterMode. The enum drives both ToolbarBinder's onLockButtonClick dispatch (BOTH /
 * HORIZONTAL / VERTICAL → lock-axis buttons) and CropEngine's crop-centring math (HORIZONTAL locks X-axis symmetry,
 * VERTICAL locks Y, BOTH locks both, LOCKED freezes the whole crop for Move mode). A reorder or rename here without
 * touching those consumers would silently mis-route the lock affordance. MainActivity.applyRestoreBundle's
 * CenterMode.valueOf tolerance is implicitly covered: the unknown-enum path falls back to default, but every existing
 * constant must round-trip.
 */
public final class CenterModeTest
{
	@Test
	public void valueOfRoundTripsForAllConstants()
	{
		for (CenterMode mode : CenterMode.values())
		{
			assertEquals(mode, CenterMode.valueOf(mode.name()));
		}
	}

	@Test
	public void valuesAreInExpectedOrder()
	{
		assertArrayEquals(new CenterMode[] { CenterMode.BOTH, CenterMode.HORIZONTAL,
				CenterMode.VERTICAL, CenterMode.LOCKED }, CenterMode.values());
	}

	@Test
	public void valuesContainsExactlyFourModes()
	{
		// Pin cardinality + names — adding a fifth mode requires updating the lock-axis button dispatch in
		// ToolbarBinder.onLockButtonClick AND the crop-centring math in CropEngine.
		assertEquals(4, CenterMode.values().length);
		assertEquals("BOTH", CenterMode.BOTH.name());
		assertEquals("HORIZONTAL", CenterMode.HORIZONTAL.name());
		assertEquals("VERTICAL", CenterMode.VERTICAL.name());
		assertEquals("LOCKED", CenterMode.LOCKED.name());
	}
}
