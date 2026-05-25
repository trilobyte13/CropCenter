package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;

/**
 * Tests for SaveController.pickInitialSaveFolder — the pure-function priority helper extracted from
 * loadLastSaveFolder so the timestamp + existence fallback logic can be unit-tested without
 * SharedPreferences. The real loadLastSaveFolder wraps this with prefs reads + the
 * Environment.getExternalStorageDirectory fallback; this test pins the priority math.
 */
public final class SaveControllerPickInitialFolderTest
{
	private static final File LOAD = new File("/storage/emulated/0/DCIM");
	private static final File SAVE = new File("/storage/emulated/0/Pictures");

	@Test
	public void bothNullReturnsNull()
	{
		// Caller routes this to Environment.getExternalStorageDirectory(); the helper returns null
		// to signal "no usable persisted folder".
		assertNull(SaveController.pickInitialSaveFolder(null, 0L, null, 0L));
	}

	@Test
	public void equalTimestampsPreferSaveFolder()
	{
		// Degenerate tie — both stamps are 0 (no prefs recorded) OR both happen to land on the
		// same millisecond. Documented behaviour: prefer save folder, matching the prior no-
		// timestamp logic's default branch.
		File picked = SaveController.pickInitialSaveFolder(SAVE, 5L, LOAD, 5L);
		assertEquals(SAVE, picked);
	}

	@Test
	public void loadFolderMoreRecentWinsOverSave()
	{
		// Core "fresh load wins over stale save" case — the bug the timestamp logic fixed.
		// User loaded DCIM 10 seconds ago, last saved to Pictures hours ago: Save dialog should
		// land in DCIM.
		long now = 1_000_000L;
		File picked = SaveController.pickInitialSaveFolder(SAVE, now - 10_000_000L, LOAD, now);
		assertEquals(LOAD, picked);
	}

	@Test
	public void moreRecentNullFallsBackToOther()
	{
		// User loaded a folder more recently than their last save, but that load folder has since
		// been deleted (caller's readFolder returned null). Fall back to the older but still-extant
		// save folder rather than dropping all the way to external storage.
		long now = 1_000_000L;
		File picked = SaveController.pickInitialSaveFolder(SAVE, now - 1_000L, null, now);
		assertEquals(SAVE, picked);
	}

	@Test
	public void onlyLoadFolderPresentReturnsLoad()
	{
		// Symmetric case for the user who's loaded but never saved this session.
		assertEquals(LOAD,
			SaveController.pickInitialSaveFolder(null, 0L, LOAD, 12345L));
	}

	@Test
	public void onlySaveFolderPresentReturnsSave()
	{
		// User has only ever saved (never loaded — or load record exists at ts=0). The save
		// folder wins regardless of its timestamp.
		assertEquals(SAVE,
			SaveController.pickInitialSaveFolder(SAVE, 12345L, null, 0L));
	}

	@Test
	public void saveFolderMoreRecentWinsOverLoad()
	{
		// User saved to Pictures 5 seconds ago, the load that led to that save was 30 seconds ago:
		// next Save dialog lands in Pictures (the user's most-recent destination).
		long now = 1_000_000L;
		File picked = SaveController.pickInitialSaveFolder(SAVE, now, LOAD, now - 25_000L);
		assertEquals(SAVE, picked);
	}
}
