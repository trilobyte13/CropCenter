package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;

import com.cropcenter.model.CropState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pins the EditorHost.finishBusy() release-ordering contract that prevents the busy-handoff race: the busy flag must
 * clear LAST, after the UI teardown (setBusyUi(false) + hideProgress()), all inside one runOnUiThread block. A
 * regression that clears busy before the teardown reopens the window where an onNewIntent load acquires busy and is
 * then unmasked by the prior op's pending teardown. The MainActivity.setBusyUi(true) ->
 * RotationRulerView.cancelMomentum() half of the interlock needs a View plus Activity and isn't reachable from plain
 * JUnit (no Robolectric here), so it is not covered.
 */
public final class EditorHostFinishBusyTest
{
	/**
	 * Minimal EditorHost fake. Implements only the plumbing finishBusy() touches (runOnUiThread, setBusyUi,
	 * hideProgress, getBusy); every other surface throws so an accidental code-path drift surfaces instead of
	 * silently passing. runOnUiThread runs inline so the test asserts synchronously.
	 */
	private static final class FakeHost implements EditorHost
	{
		final AtomicBoolean busy = new AtomicBoolean(false);
		final List<String> order = new ArrayList<>();
		boolean busyDuringHideProgress;
		boolean busyDuringSetBusyUi;
		boolean ranOnUiThread;

		// Stub override returning null — the test never reads the result. SuppressWarnings keeps the
		// generic-method override quiet even though `return null` doesn't strictly require it.
		@Override
		@SuppressWarnings("unchecked")
		public <T extends View> T findViewById(int id) { return null; }

		@Override
		public Activity getActivity() { throw new UnsupportedOperationException(); }

		@Override
		public AtomicBoolean getBusy() { return busy; }

		@Override
		public CropState getState() { throw new UnsupportedOperationException(); }

		@Override
		public void hideProgress()
		{
			busyDuringHideProgress = busy.get();
			order.add("hideProgress");
		}

		@Override
		public boolean isDestroyed() { return false; }

		@Override
		public AlertDialog registerTransientDialog(AlertDialog newDialog) { return newDialog; }

		@Override
		public void runInBackground(Runnable task) { throw new UnsupportedOperationException(); }

		@Override
		public void runOnUiThread(Runnable task)
		{
			ranOnUiThread = true;
			task.run();
		}

		@Override
		public void setBusyUi(boolean busyUi)
		{
			busyDuringSetBusyUi = busy.get();
			order.add("setBusyUi(" + busyUi + ")");
		}

		@Override
		public void showBusyToast() {}

		@Override
		public void showProgress(String msg) { throw new UnsupportedOperationException(); }

		@Override
		public void toastIfAlive(String msg, int length) { throw new UnsupportedOperationException(); }
	}

	@Test
	public void finishBusyClearsBusyFlagAfterUiTeardown()
	{
		FakeHost host = new FakeHost();
		host.getBusy().set(true);

		host.finishBusy();

		assertFalse("busy must end cleared", host.getBusy().get());
		assertTrue("busy must still be held when setBusyUi(false) runs (cleared last)",
			host.busyDuringSetBusyUi);
		assertTrue("busy must still be held when hideProgress() runs (cleared last)",
			host.busyDuringHideProgress);
		assertEquals("UI teardown runs re-enable then hide, before the flag clears",
			List.of("setBusyUi(false)", "hideProgress"), host.order);
	}

	@Test
	public void finishBusyRoutesThroughRunOnUiThread()
	{
		FakeHost host = new FakeHost();
		host.getBusy().set(true);

		host.finishBusy();

		assertTrue("teardown must be posted through runOnUiThread", host.ranOnUiThread);
	}
}
