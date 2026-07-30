package com.cropcenter.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins for the thumbnail-cache eviction epoch protocol: evictThumbnailCache's increment+evictAll and
 * decodeThumbnail's snapshot / check+insert must be mutually atomic under FolderBrowser.CACHE_LOCK. Without one
 * lock over both sides, a decode could pass its generation check, lose the CPU to an eviction, and land a stale
 * insert after the evictAll — silently re-growing the cache the picker-dismiss / export-dispatch eviction just
 * reclaimed. The suite drives the package-private protocol seams (snapshotCacheGeneration /
 * putThumbnailIfCurrentGeneration) directly and pins the lock contract by contending on CACHE_LOCK from a second
 * thread — the decode itself (framework-native BitmapFactory) stays device-only.
 *
 * The Bitmap argument is null throughout: the headless android.jar LruCache stub is a no-op, and the protocol
 * under test never dereferences the bitmap.
 */
public final class FolderBrowserCacheGenerationTest
{
	@Test
	public void currentGenerationInsertLands()
	{
		int gen = FolderBrowser.snapshotCacheGeneration();
		// No eviction between snapshot and insert — the gate must admit the put (a gate that always
		// refuses would disable thumbnail caching entirely and every navigation would re-decode).
		assertTrue(FolderBrowser.putThumbnailIfCurrentGeneration("current-key", null, gen));
	}

	@Test
	public void evictionBumpsAndEvictsAtomicallyUnderCacheLock() throws InterruptedException
	{
		Thread evictor = new Thread(FolderBrowser::evictThumbnailCache);
		int genBefore;
		synchronized (FolderBrowser.CACHE_LOCK)
		{
			genBefore = FolderBrowser.snapshotCacheGeneration();
			evictor.start();
			awaitBlocked(evictor);
			// While this thread holds CACHE_LOCK, the eviction must park BLOCKED on it — an eviction
			// that bumps or evicts outside the lock runs to completion here, reopening the
			// check-then-stale-put window.
			assertEquals(Thread.State.BLOCKED, evictor.getState());
			// And the bump must not have landed yet: an increment hoisted ahead of the lock would be
			// visible now, meaning a concurrent snapshot could observe the new epoch before the
			// evictAll ran.
			assertEquals(genBefore, FolderBrowser.snapshotCacheGeneration());
		}
		evictor.join(5000);
		assertFalse(evictor.isAlive());
		assertEquals(genBefore + 1, FolderBrowser.snapshotCacheGeneration());
	}

	@Test
	public void insertHoldsCacheLockAcrossCheckAndPut() throws InterruptedException
	{
		boolean[] inserted = new boolean[1];
		int gen = FolderBrowser.snapshotCacheGeneration();
		Thread inserter = new Thread(() ->
		{
			inserted[0] = FolderBrowser.putThumbnailIfCurrentGeneration("locked-key", null, gen);
		});
		synchronized (FolderBrowser.CACHE_LOCK)
		{
			inserter.start();
			awaitBlocked(inserter);
			// The check+put side must contend on the same lock the eviction holds — a check that runs
			// outside CACHE_LOCK would complete here and could interleave mid-eviction on device.
			assertEquals(Thread.State.BLOCKED, inserter.getState());
		}
		inserter.join(5000);
		assertFalse(inserter.isAlive());
		// No eviction happened, so once the lock was released the insert must have been admitted.
		assertTrue(inserted[0]);
	}

	@Test
	public void staleGenerationInsertIsRefusedAfterEviction()
	{
		int gen = FolderBrowser.snapshotCacheGeneration();
		FolderBrowser.evictThumbnailCache();
		// The snapshot predates the eviction, so the insert must be refused — an admitted put here is
		// exactly the straggler re-growing the evicted cache.
		assertFalse(FolderBrowser.putThumbnailIfCurrentGeneration("stale-key", null, gen));
	}

	/**
	 * Spin until thread parks BLOCKED on a monitor, or ~5 seconds pass. The caller asserts the resulting state,
	 * so a thread that never blocks (the atomicity regression) turns into a deterministic assertion failure
	 * rather than a hang.
	 *
	 * @param thread thread expected to contend on CACHE_LOCK
	 * @throws InterruptedException when the polling sleep is interrupted
	 */
	private static void awaitBlocked(Thread thread) throws InterruptedException
	{
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
		{
			Thread.sleep(5);
		}
	}
}
