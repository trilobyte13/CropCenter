package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;

import com.cropcenter.model.CropState;

import org.junit.Test;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Regression test for the pre-enqueue dispatch-failure cleanup contract on ImageLoadController.load. Pins the bug
 * where a RejectedExecutionException from runInBackground (e.g. executor shutdown after onDestroy racing an Open
 * tap) would leave busy=true forever, indefinitely rejecting subsequent Open / Save taps with the "Busy — try
 * again" toast.
 *
 * The contract: when host.runInBackground throws, load() must (a) clear the busy AtomicBoolean, (b) call
 * setBusyUi(false), (c) call hideProgress(), and (d) propagate the exception. This test exercises that contract
 * via a fake ImageLoadHost whose runInBackground unconditionally throws.
 */
public final class ImageLoadControllerDispatchFailureTest
{
	@Test
	public void loadDismissesTransientDialogsBeforeBusyCheck()
	{
		// Contract: dismissTransientDialogs runs BEFORE busy.compareAndSet so an open
		// SettingsDialog (which mutates state.gridConfig on the UI thread) is closed regardless of whether
		// a parallel load is already in flight. Pre-set busy=true to fail the cas, then assert the
		// dismiss still ran. A regression that moved the dismiss into the post-cas block would let an
		// open Settings dialog keep racing the bg state.reset's gridConfig clear.
		FakeImageLoadHost fake = new FakeImageLoadHost();
		fake.busy.set(true);
		ImageLoadController controller = new ImageLoadController(fake, null);

		controller.load(null);

		assertTrue("showBusyToast must have fired (cas rejected)", fake.showBusyToastCalled);
		assertEquals("dismissTransientDialogs must run BEFORE the busy gate so an open SettingsDialog "
			+ "is closed regardless of cas outcome", 1, fake.dismissTransientDialogsCount);
	}

	@Test
	public void loadReleasesBusyWhenRunInBackgroundRejects()
	{
		FakeImageLoadHost fake = new FakeImageLoadHost();
		fake.runInBackgroundThrows = true;
		ImageLoadController controller = new ImageLoadController(fake, null);

		try
		{
			controller.load(null);
			fail("expected RejectedExecutionException to propagate");
		}
		catch (RejectedExecutionException expected)
		{
			// Cleanup must have happened before the throw escaped:
			//   busy released, busy-ui disabled, progress hidden.
			assertFalse("busy AtomicBoolean must be cleared", fake.busy.get());
			assertEquals("setBusyUi must end with false", Boolean.FALSE, fake.lastSetBusyUiArg);
			assertTrue("hideProgress must have been called", fake.hideProgressCalled);
			// T17-2: dismiss is OUTSIDE the try/catch that handles RejectedExecutionException, so a
			// dispatch failure must not skip the dismissal. A regression that moved dismiss inside the
			// try would leave an open SettingsDialog stranded across the failed dispatch + busy release.
			assertEquals("dismissTransientDialogs must run BEFORE the failed dispatch",
				1, fake.dismissTransientDialogsCount);
		}
	}

	@Test
	public void loadShowsBusyToastAndReturnsWhenAlreadyBusy()
	{
		// Sanity check on the early-return path: when busy is already true, load() takes the busy-toast branch
		// and never enters the try/catch. This proves the failure-path cleanup applies only after the
		// compareAndSet(false, true) succeeds — re-entrant calls don't trigger the cleanup.
		FakeImageLoadHost fake = new FakeImageLoadHost();
		fake.busy.set(true);
		fake.runInBackgroundThrows = true; // would throw if reached; must not reach
		ImageLoadController controller = new ImageLoadController(fake, null);

		controller.load(null);

		assertTrue("busy must remain true (re-entrant call did not own it)", fake.busy.get());
		assertTrue("showBusyToast must have been called", fake.showBusyToastCalled);
		assertFalse("hideProgress must NOT have been called (cleanup path skipped)", fake.hideProgressCalled);
	}

	/**
	 * Minimal ImageLoadHost fake. Only the methods load() actually touches before runInBackground are wired with
	 * meaningful behaviour; the rest throw to surface any accidental call from a future load() refactor.
	 */
	private static final class FakeImageLoadHost implements ImageLoadHost
	{
		final AtomicBoolean busy = new AtomicBoolean(false);
		Boolean lastSetBusyUiArg;
		// Counter for dismissTransientDialogs invocations. The dismiss call runs BEFORE the busy gate so
		// an open SettingsDialog is closed regardless of whether a parallel load is in flight; the two
		// test cases verify the dismiss runs even when the busy.cas rejects and even when
		// runInBackground throws.
		int dismissTransientDialogsCount;
		boolean hideProgressCalled;
		boolean runInBackgroundThrows;
		boolean showBusyToastCalled;

		@Override
		public void dismissTransientDialogs()
		{
			dismissTransientDialogsCount++;
		}

		// Stub override returning null — the test never reads the result. SuppressWarnings keeps the
		// generic-method override quiet even though `return null` doesn't strictly require it.
		@Override
		@SuppressWarnings("unchecked")
		public <T extends View> T findViewById(int id) { return null; }

		@Override
		public Activity getActivity() { throw new UnsupportedOperationException(); }

		@Override
		public AtomicBoolean getBusy()
		{
			return busy;
		}

		@Override
		public ActivityResultLauncher<String> getSaveAsLauncher() { throw new UnsupportedOperationException(); }

		@Override
		public CropState getState() { throw new UnsupportedOperationException(); }

		@Override
		public void hideProgress()
		{
			hideProgressCalled = true;
		}

		@Override
		public void installImageOnUi(Bitmap bmp, String sizeInfo, String metaInfo)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isDestroyed() { return false; }

		@Override
		public AlertDialog registerTransientDialog(AlertDialog newDialog) { return newDialog; }

		@Override
		public void runInBackground(Runnable task)
		{
			if (runInBackgroundThrows)
			{
				throw new RejectedExecutionException("simulated executor shutdown after onDestroy");
			}
		}

		@Override
		public void runOnUiThread(Runnable r) { r.run(); }

		@Override
		public void setBusyUi(boolean busyUi)
		{
			lastSetBusyUiArg = busyUi;
		}

		@Override
		public void showBusyToast()
		{
			showBusyToastCalled = true;
		}

		@Override
		public void showProgress(String msg) {}

		@Override
		public void toastIfAlive(String msg, int length) {}
	}
}
