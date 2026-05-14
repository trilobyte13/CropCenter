package com.cropcenter.model;

/**
 * Listener-dispatch and batch-suppression helper extracted from CropState. Owns the single state-change listener and
 * the batch protocol that lets the Activity wrap recomputeCrop + UI updates without re-entering the listener for each
 * inner setter call.
 *
 * Why split out: CropState mixed source-image data, crop geometry, editor prefs, graft state, output config, AND the
 * listener / batch dispatch. The dispatch protocol is genuinely orthogonal to the rest of CropState's state — its
 * fields (batchDepth, batchDirty, listener) and methods (beginBatch, endBatch, notify, setListener) form a closed
 * cohesive sub-system that can be reasoned about and tested in isolation.
 *
 * CropState holds a single StateBus instance and delegates to it. Setters call bus.notifyChanged() instead of the
 * private notifyChanged method that previously lived on CropState.
 */
final class StateBus
{
	// `batchDepth` and `batchDirty` are guarded by `batchLock`. The previous AtomicInteger / volatile-boolean
	// pair had each field atomic in isolation but their PAIR was not: a bg-thread notifyChanged could read
	// batchDepth > 0, the UI thread could endBatch through zero and observe batchDirty == false (the bg thread
	// hadn't set it yet), then the bg thread set batchDirty = true after the flush window had closed. The
	// stranded dirty flag survived until the next batch — listeners missed the just-completed change.
	// Putting depth check, depth mutation, and dirty read/write in one synchronized section
	// makes the (depth, dirty) transition atomic, so endBatch either flushes a dirty flag set during this
	// batch or none was set; there is no "set just after the flush window" window. `fire()` runs outside the
	// lock so a slow listener can't block bg setters; double-fire on a racing fire path remains acceptable
	// because applyStateToUi's `applyingStateToUi` re-entrancy flag (MainActivity:601-604) collapses two
	// posts into one effective UI update.
	private final Object batchLock = new Object();
	private volatile CropState.OnStateChangedListener listener;
	private boolean batchDirty;
	private int batchDepth;

	/**
	 * Start a batch: any notifyChanged calls until the matching endBatch record a dirty flag instead of firing the
	 * listener. Nested batches are supported — only the outermost endBatch fires.
	 */
	void beginBatch()
	{
		synchronized (batchLock)
		{
			batchDepth++;
		}
	}

	/**
	 * End a batch started by beginBatch. Fires the listener once if any notifyChanged call landed during the batch;
	 * otherwise silent.
	 */
	void endBatch()
	{
		boolean shouldFire = false;
		synchronized (batchLock)
		{
			if (batchDepth <= 0)
			{
				return;
			}
			batchDepth--;
			if (batchDepth == 0 && batchDirty)
			{
				batchDirty = false;
				shouldFire = true;
			}
		}
		if (shouldFire)
		{
			fire();
		}
	}

	/**
	 * Listener dispatch with batch suppression. Inside an open batch (batchDepth > 0) sets the dirty flag and
	 * returns; endBatch will fire once on close. Outside a batch fires immediately.
	 */
	void notifyChanged()
	{
		synchronized (batchLock)
		{
			if (batchDepth > 0)
			{
				batchDirty = true;
				return;
			}
		}
		fire();
	}

	/**
	 * Register (or clear via null) the single state-change listener.
	 *
	 * @param listener the new listener, or null to clear (Activity.onDestroy uses null to detach
	 *                 before a bg thread that holds the old reference can fire on a destroyed View tree)
	 */
	void setListener(CropState.OnStateChangedListener listener)
	{
		this.listener = listener;
	}

	/**
	 * Invoke the registered listener if one is set. No-op when no listener has been attached (e.g. between Activity
	 * onDestroy clearing the listener and a bg thread finishing its in-flight task).
	 *
	 * Snapshot the volatile field to a local before the null check + invoke: re-reading
	 * `this.listener` twice would let a setListener(null) racing between the check and the call dereference a
	 * cleared listener. Activity.onDestroy clears via setListener(null) precisely to detach before the View
	 * tree is gone, and bg load / save / graft worker threads can still fire notifyChanged → fire() on the
	 * way down. A local snapshot pins whichever value was current at entry; if it's null we no-op, if it's
	 * non-null we invoke that exact instance regardless of what happens to the field afterward. The listener
	 * itself is responsible for handling a "Activity already destroyed" callback (typically a no-op guard via
	 * Activity.isFinishing / isDestroyed), so invoking a stale-but-non-null reference is safe; dereferencing
	 * null is what we have to prevent.
	 */
	private void fire()
	{
		CropState.OnStateChangedListener snapshot = listener;
		if (snapshot != null)
		{
			snapshot.onStateChanged();
		}
	}
}
