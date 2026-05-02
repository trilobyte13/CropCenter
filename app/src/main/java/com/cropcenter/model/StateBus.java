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
	private CropState.OnStateChangedListener listener;
	private boolean batchDirty;
	private int batchDepth;

	/**
	 * Start a batch: any notifyChanged calls until the matching endBatch record a dirty flag instead of firing the
	 * listener. Nested batches are supported — only the outermost endBatch fires.
	 */
	void beginBatch()
	{
		batchDepth++;
	}

	/**
	 * End a batch started by beginBatch. Fires the listener once if any notifyChanged call landed during the batch;
	 * otherwise silent.
	 */
	void endBatch()
	{
		if (batchDepth <= 0)
		{
			return;
		}
		if (--batchDepth == 0 && batchDirty)
		{
			batchDirty = false;
			fire();
		}
	}

	/**
	 * Listener dispatch with batch suppression. Inside an open batch (batchDepth > 0) sets the dirty flag and
	 * returns; endBatch will fire once on close. Outside a batch fires immediately.
	 */
	void notifyChanged()
	{
		if (batchDepth > 0)
		{
			batchDirty = true;
			return;
		}
		fire();
	}

	/**
	 * Register (or clear via null) the single state-change listener.
	 */
	void setListener(CropState.OnStateChangedListener listener)
	{
		this.listener = listener;
	}

	/**
	 * Invoke the registered listener if one is set. No-op when no listener has been attached (e.g. between Activity
	 * onDestroy clearing the listener and a bg thread finishing its in-flight task).
	 */
	private void fire()
	{
		if (listener != null)
		{
			listener.onStateChanged();
		}
	}
}
