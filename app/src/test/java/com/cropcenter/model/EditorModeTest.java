package com.cropcenter.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Cardinality + ordering pin for EditorMode. The enum drives switch dispatches in CropEngine and CropEditorView; adding
 * or reordering values without updating those switches would silently mis-route mode-dependent behaviour. This test
 * fails fast on a values() change so the consumer sites get touched in the same commit.
 *
 * Also pins MainActivity.applyRestoreBundle's tolerance for unknown enum names (via the IllegalArgumentException catch
 * around EditorMode.valueOf) — a saved bundle from an older app version with a renamed constant must continue to
 * round-trip.
 */
public final class EditorModeTest
{
	@Test
	public void valueOfRoundTripsForAllConstants()
	{
		// Bundle restore reads back via EditorMode.valueOf(name); make sure every name() round-trips to the
		// same enum instance.
		for (EditorMode mode : EditorMode.values())
		{
			assertEquals(mode, EditorMode.valueOf(mode.name()));
		}
	}

	@Test
	public void valuesAreInExpectedOrder()
	{
		// Ordinal order matters for switch jump-table compilation; freezing it prevents a silent reorder from
		// drifting the saved-bundle backward compatibility behaviour.
		assertArrayEquals(new EditorMode[] { EditorMode.MOVE, EditorMode.SELECT_FEATURE }, EditorMode.values());
	}

	@Test
	public void valuesContainsExactlyMoveAndSelectFeature()
	{
		// Pin cardinality + names — adding a third mode (e.g. PAINT_HORIZON) requires touching the switch sites
		// that consume this enum, which this assertion catches at test-time.
		assertEquals(2, EditorMode.values().length);
		assertEquals("MOVE", EditorMode.MOVE.name());
		assertEquals("SELECT_FEATURE", EditorMode.SELECT_FEATURE.name());
	}
}
