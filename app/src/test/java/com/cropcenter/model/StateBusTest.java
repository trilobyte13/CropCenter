package com.cropcenter.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for StateBus's listener-dispatch + batch-suppression protocol. Lives in com.cropcenter.model so the
 * package-private StateBus class is visible. The bus's contract is: notifyChanged outside a batch fires immediately;
 * inside a batch it sets a dirty flag; endBatch fires once if any notify landed during the batch; nested batches only
 * fire on the outermost end. A regression here silently breaks multi-field UI updates (no listener firing → no redraw,
 * but no crash and no log) so the cases need explicit pinning.
 */
public class StateBusTest
{
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
	public void notifyChangedNoOpWhenNoListenerRegistered()
	{
		// No listener attached — the bus must silently swallow the call. Without this guard a bg thread that
		// fires after Activity.onDestroy nulled the listener would NPE in fire().
		StateBus bus = new StateBus();
		bus.notifyChanged();   // must not throw
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
}
