package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.RejectedExecutionException;

/**
 * Regression test for the pre-enqueue dispatch-failure cleanup contract on ImageLoadController.load. Pins the bug where
 * a RejectedExecutionException from runInBackground (e.g. executor shutdown after onDestroy racing an Open tap) would
 * leave busy=true forever, indefinitely rejecting subsequent Open / Save taps with the "Busy — try again" toast.
 *
 * The contract: when host.runInBackground throws, load() must (a) clear the busy AtomicBoolean, (b) call
 * setBusyUi(false), (c) call hideProgress(), and (d) propagate the exception. This test exercises that contract via
 * the shared RecordingImageLoadHost fake with its runInBackgroundThrows flag set.
 */
public final class ImageLoadControllerDispatchFailureTest
{
	@Test
	public void loadDismissesTransientDialogsBeforeBusyCheck()
	{
		// Contract: dismissTransientDialogs runs BEFORE busy.compareAndSet so an open SettingsDialog (which
		// mutates state.gridConfig on the UI thread) is closed regardless of whether a parallel load is already
		// in flight. Pre-set busy=true to fail the cas, then assert the dismiss still ran. A regression that
		// moved the dismiss into the post-cas block would let an open Settings dialog keep racing the bg
		// state.reset's gridConfig clear.
		RecordingImageLoadHost fake = new RecordingImageLoadHost();
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
		RecordingImageLoadHost fake = new RecordingImageLoadHost();
		fake.runInBackgroundThrows = true;
		ImageLoadController controller = new ImageLoadController(fake, null);

		assertThrows(RejectedExecutionException.class, () -> controller.load(null));

		// Cleanup must have happened before the throw escaped:
		//   busy released, busy-ui disabled, progress hidden.
		assertFalse("busy AtomicBoolean must be cleared", fake.busy.get());
		assertEquals("setBusyUi must end with false", Boolean.FALSE, fake.lastSetBusyUiArg);
		assertTrue("hideProgress must have been called", fake.hideProgressCalled);
		// dismiss is OUTSIDE the try/catch that handles RejectedExecutionException, so a dispatch
		// failure must not skip the dismissal. A regression that moved dismiss inside the try would
		// leave an open SettingsDialog stranded across the failed dispatch + busy release.
		assertEquals("dismissTransientDialogs must run BEFORE the failed dispatch",
			1, fake.dismissTransientDialogsCount);
	}

	@Test
	public void loadShowsBusyToastAndReturnsWhenAlreadyBusy()
	{
		// Sanity check on the early-return path: when busy is already true, load() takes the busy-toast branch
		// and never enters the try/catch. This proves the failure-path cleanup applies only after the
		// compareAndSet(false, true) succeeds — re-entrant calls don't trigger the cleanup.
		RecordingImageLoadHost fake = new RecordingImageLoadHost();
		fake.busy.set(true);
		fake.runInBackgroundThrows = true; // would throw if reached; must not reach
		ImageLoadController controller = new ImageLoadController(fake, null);

		controller.load(null);

		assertTrue("busy must remain true (re-entrant call did not own it)", fake.busy.get());
		assertTrue("showBusyToast must have been called", fake.showBusyToastCalled);
		assertFalse("hideProgress must NOT have been called (cleanup path skipped)", fake.hideProgressCalled);
	}
}
