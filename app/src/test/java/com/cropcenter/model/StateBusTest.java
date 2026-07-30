package com.cropcenter.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for StateBus's listener-dispatch + batch-suppression protocol. Lives in com.cropcenter.model so the
 * package-private StateBus class is visible. The bus's contract is: notifyChanged outside a batch fires immediately;
 * inside a batch it sets a dirty flag; endBatch fires once if any notify landed during the batch; nested batches only
 * fire on the outermost end. A regression here silently breaks multi-field UI updates (no listener firing → no redraw,
 * but no crash and no log) so the cases need explicit pinning.
 */
public final class StateBusTest
{
	@Test
	public void concurrentNotifyAcrossEndBatchNeverLosesDirtyFlag() throws Exception
	{
		// Race: bg thread enters notifyChanged() and reads batchDepth > 0; UI thread interleaves endBatch()
		// that decrements depth to 0 and reads batchDirty == false (bg hasn't set it yet); bg sets batchDirty =
		// true and returns. The dirty flag is stranded — endBatch already passed its check, only the next batch
		// flushes it. notifyChanged + endBatch share one synchronized section so endBatch either sees a dirty
		// flag inside the lock-protected window or sees that no notify is in flight. Test: fresh bus per trial;
		// main opens a batch; bg + ui race notifyChanged against endBatch; every trial must produce at least
		// one fire (no stranding).
		int trials = 1000;
		int strandedTrials = 0;
		for (int trial = 0; trial < trials; trial++)
		{
			StateBus bus = new StateBus();
			AtomicInteger fired = new AtomicInteger();
			bus.setListener(fired::incrementAndGet);
			bus.beginBatch();
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch go = new CountDownLatch(1);
			Thread bg = new Thread(() ->
			{
				ready.countDown();
				try
				{
					go.await();
				}
				catch (InterruptedException ignored)
				{
					// Unreachable in JUnit flow — checked-exception escape only. A force-kill
					// returns here and the trial aborts without its notify; strand assertion would
					// then flag the unusual run rather than silently retry.
					Thread.currentThread().interrupt();
					return;
				}
				bus.notifyChanged();
			});
			Thread ui = new Thread(() ->
			{
				ready.countDown();
				try
				{
					go.await();
				}
				catch (InterruptedException ignored)
				{
					// Same checked-exception escape hatch as the bg thread above — never reached in
					// normal JUnit flow; force-kill aborts the trial without an endBatch.
					Thread.currentThread().interrupt();
					return;
				}
				bus.endBatch();
			});
			bg.start();
			ui.start();
			ready.await();
			go.countDown();
			bg.join();
			ui.join();
			if (fired.get() == 0)
			{
				strandedTrials++;
			}
		}
		assertEquals("notify across endBatch must never be stranded (race regression)", 0, strandedTrials);
	}

	@Test
	public void concurrentSetListenerNullDuringFireNeverNpes() throws Exception
	{
		// Race: bg enters fire(), reads `this.listener != null` in the guard (non-null); UI interleaves
		// setListener(null); bg re-reads at the invocation site → NPE. Activity.onDestroy clears the listener
		// precisely so a late-firing bg worker can no-op safely, so an NPE here defeats the lifecycle guard.
		// fire() snapshots the volatile field to a local before the guard+invoke pair. Test: bg spams
		// notifyChanged() while UI alternates setListener(non-null) / setListener(null). Any NPE caught
		// surfaces as a non-zero failure count.
		int iterations = 5000;
		StateBus bus = new StateBus();
		AtomicInteger fires = new AtomicInteger();
		AtomicInteger npesCaught = new AtomicInteger();
		bus.setListener(fires::incrementAndGet);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);
		Thread bg = new Thread(() ->
		{
			ready.countDown();
			try
			{
				go.await();
			}
			catch (InterruptedException ignored)
			{
				// Unreachable in JUnit flow — checked-exception escape only.
				Thread.currentThread().interrupt();
				return;
			}
			for (int i = 0; i < iterations; i++)
			{
				try
				{
					bus.notifyChanged();
				}
				catch (NullPointerException npe)
				{
					npesCaught.incrementAndGet();
				}
			}
		});
		Thread ui = new Thread(() ->
		{
			ready.countDown();
			try
			{
				go.await();
			}
			catch (InterruptedException ignored)
			{
				// Same checked-exception escape hatch as the bg thread above.
				Thread.currentThread().interrupt();
				return;
			}
			for (int i = 0; i < iterations; i++)
			{
				bus.setListener((i % 2 == 0) ? null : fires::incrementAndGet);
			}
		});
		bg.start();
		ui.start();
		ready.await();
		go.countDown();
		bg.join();
		ui.join();
		assertEquals("setListener(null) racing fire() must not surface as NPE", 0, npesCaught.get());
	}

	@Test
	public void endBatchSilentWhenNoNotifyLanded()
	{
		// beginBatch / endBatch with no intervening notify must not fire the listener — would cause spurious
		// redraws on no-op state passes.
		StateBus bus = new StateBus();
		int[] fireCount = { 0 };
		bus.setListener(() -> fireCount[0]++);
		bus.beginBatch();
		bus.endBatch();
		assertEquals(0, fireCount[0]);
	}

	@Test
	public void endBatchWithoutBeginBatchIsNoOp()
	{
		// Defensive: a stray endBatch call (e.g. from a buggy try/finally that ran the finally before the try
		// body's beginBatch executed) must not fire the listener or underflow batchDepth into negative
		// territory.
		StateBus bus = new StateBus();
		int[] fireCount = { 0 };
		bus.setListener(() -> fireCount[0]++);
		bus.endBatch();
		assertEquals(0, fireCount[0]);
		// Subsequent normal usage still works — batchDepth wasn't underflowed.
		bus.notifyChanged();
		assertEquals(1, fireCount[0]);
	}

	@Test
	public void freshBatchAfterEndBatchHasCleanDirtyFlag()
	{
		// After a batch flush, the next batch must start with a clean dirty flag — otherwise a no-op batch
		// would immediately fire a stale notify from the previous batch.
		StateBus bus = new StateBus();
		int[] fireCount = { 0 };
		bus.setListener(() -> fireCount[0]++);
		bus.beginBatch();
		bus.notifyChanged();
		bus.endBatch();
		assertEquals(1, fireCount[0]);
		// Second batch with no notify — must not re-fire the previous notify's residual dirty flag.
		bus.beginBatch();
		bus.endBatch();
		assertEquals("no notify in second batch — listener must not fire", 1, fireCount[0]);
	}

	@Test
	public void listenerSwapMidBatchFiresNewListenerOnEnd()
	{
		// Document what happens when the listener is replaced mid-batch — the listener registered AT
		// endBatch-time fires (not the one that was set when the notify happened). This is the contract the bus
		// exposes; pin it so future readers don't have to dig.
		StateBus bus = new StateBus();
		int[] firstCount = { 0 };
		int[] secondCount = { 0 };
		bus.setListener(() -> firstCount[0]++);
		bus.beginBatch();
		bus.notifyChanged();
		bus.setListener(() -> secondCount[0]++);
		bus.endBatch();
		assertEquals("first listener should not fire after being replaced", 0, firstCount[0]);
		assertEquals("second listener fires on endBatch", 1, secondCount[0]);
	}

	@Test
	public void multipleNotifiesInsideBatchCoalesceToOneFire()
	{
		// Three notifies during the same batch fire the listener exactly once when endBatch closes — the whole
		// point of the batch protocol (CropEngine.recomputeCrop hits N setters for one logical change).
		StateBus bus = new StateBus();
		int[] fireCount = { 0 };
		bus.setListener(() -> fireCount[0]++);
		bus.beginBatch();
		bus.notifyChanged();
		bus.notifyChanged();
		bus.notifyChanged();
		bus.endBatch();
		assertEquals(1, fireCount[0]);
	}

	@Test
	public void nestedBatchesFireOnlyOnOutermostEnd()
	{
		// 3 levels of nesting + one notify deep inside fires exactly once when the outermost batch closes.
		// Inner endBatch calls must NOT flush the dirty flag — that would break the Activity-level batch the
		// outer listener wraps recomputeCrop in.
		StateBus bus = new StateBus();
		int[] fireCount = { 0 };
		bus.setListener(() -> fireCount[0]++);
		bus.beginBatch();
		bus.beginBatch();
		bus.beginBatch();
		bus.notifyChanged();
		bus.endBatch();
		assertEquals("inner endBatch must not fire", 0, fireCount[0]);
		bus.endBatch();
		assertEquals("middle endBatch must not fire", 0, fireCount[0]);
		bus.endBatch();
		assertEquals("outermost endBatch should flush", 1, fireCount[0]);
	}

	@Test
	public void notifyChangedFiresImmediatelyOutsideBatch()
	{
		StateBus bus = new StateBus();
		int[] fireCount = { 0 };
		bus.setListener(() -> fireCount[0]++);
		bus.notifyChanged();
		assertEquals(1, fireCount[0]);
	}

	@Test
	public void notifyChangedInsideBatchDefersUntilEndBatch()
	{
		// Single notify inside a batch fires once on endBatch — pin the deferral semantic that the whole batch
		// protocol exists for.
		StateBus bus = new StateBus();
		int[] fireCount = { 0 };
		bus.setListener(() -> fireCount[0]++);
		bus.beginBatch();
		bus.notifyChanged();
		assertEquals("listener should not fire mid-batch", 0, fireCount[0]);
		bus.endBatch();
		assertEquals("endBatch should flush the deferred notify", 1, fireCount[0]);
	}

	@Test
	public void notifyChangedNoOpWhenNoListenerRegistered()
	{
		// No listener attached — the bus must silently swallow the call. Without this guard a bg thread that
		// fires after Activity.onDestroy nulled the listener would NPE in fire().
		StateBus bus = new StateBus();
		bus.notifyChanged();   // must not throw
	}

	@Test
	public void setListenerNullClearsExistingListener()
	{
		// Activity.onDestroy clears the listener via setListener(null). Subsequent notify must no-op.
		StateBus bus = new StateBus();
		int[] fireCount = { 0 };
		bus.setListener(() -> fireCount[0]++);
		bus.notifyChanged();
		assertEquals(1, fireCount[0]);
		bus.setListener(null);
		bus.notifyChanged();
		assertEquals("listener cleared — should not fire", 1, fireCount[0]);
	}
}
