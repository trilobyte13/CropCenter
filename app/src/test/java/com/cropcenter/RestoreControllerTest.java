package com.cropcenter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.Graft;

import org.junit.Test;

/**
 * Tests for RestoreController's bundle-marshalling contract — the parts that don't depend on
 * reading actual values out of a Bundle (Android stubs return defaults under
 * unitTests.returnDefaultValues = true, so applyRestoreBundle's read paths can't be exercised
 * end-to-end here without Robolectric). Focuses on three invariants worth pinning:
 *
 *  - Outcome.NONE is the singleton "no bundle was consumed" return, and its component layout
 *    (positional constructor) maps select-pref → second arg, move-pref → third arg. Reversing
 *    the two would silently apply Move's lock axis to Select on every restore.
 *  - applyIfPending consumes the stashed bundle exactly once: a second call returns NONE even
 *    if the first call returned consumed=true. Without this, a controller that calls
 *    applyIfPending twice (defensive double-tap from a future caller) would re-apply the
 *    snapshot, undoing any geometry the user changed between the two calls.
 *  - clearPendingIfUnconsumed is a safe no-op when nothing is stashed — it's called on
 *    setBusyUi(false) regardless of whether a restore was queued.
 */
public final class RestoreControllerTest
{
	@Test
	public void applyIfPendingReturnsNoneAfterSecondCallEvenIfFirstConsumed()
	{
		// Defensive against a future double-call regression: a caller that runs applyIfPending
		// twice (e.g., installImageOnUi paired with a redundant post-load re-sync) must NOT
		// re-apply the stale bundle on the second call. The bundle is nulled in-place after
		// the first consumption, so the second call sees pendingRestoreBundle == null and
		// short-circuits to NONE.
		CropState state = new CropState();
		RestoreController controller = new RestoreController(state);
		Bundle bundle = new Bundle();
		controller.stash(bundle);
		// First call consumes the stash — returnDefaultValues means every getString /
		// getFloat / getBoolean reads as default, so applyRestoreBundle's branches all
		// short-circuit (no setCenter, no selection rebuild), but the bundle IS consumed.
		RestoreController.Outcome first = controller.applyIfPending();
		assertTrue("first applyIfPending consumes the stashed bundle", first.consumed());
		// Second call: bundle was nulled — must return NONE.
		RestoreController.Outcome second = controller.applyIfPending();
		assertSame("second applyIfPending must return the singleton NONE",
			RestoreController.Outcome.NONE, second);
	}

	@Test
	public void applyIfPendingReturnsNoneWhenNothingStashed()
	{
		// Default-constructed controller has no pending bundle. applyIfPending must return
		// the singleton NONE — same reference, not just an equal value — so callers checking
		// `outcome == Outcome.NONE` (cheaper than .consumed()) stay correct.
		CropState state = new CropState();
		RestoreController controller = new RestoreController(state);
		assertSame("no-stash applyIfPending returns the singleton NONE",
			RestoreController.Outcome.NONE, controller.applyIfPending());
	}

	@Test
	public void clearPendingIfUnconsumedNoOpWhenNothingStashed()
	{
		// Called unconditionally on every setBusyUi(false) — must be safe when no bundle was
		// stashed (fresh launch, or a previous applyIfPending already consumed it). The body
		// has a null guard; this test pins the public contract that the call doesn't throw
		// AND that a subsequent applyIfPending still returns NONE (no side effect that
		// re-pends a bundle).
		CropState state = new CropState();
		RestoreController controller = new RestoreController(state);
		controller.clearPendingIfUnconsumed();
		assertSame("clearPendingIfUnconsumed must not re-pend a bundle",
			RestoreController.Outcome.NONE, controller.applyIfPending());
	}

	@Test
	public void outcomeNoneCarriesUnconsumedAndNullPrefs()
	{
		// Pin Outcome.NONE's component values. A regression that wrote NONE as
		// `new Outcome(true, ...)` (consumed=true) would silently flip the caller's
		// "should we re-sync the toolbar?" gate, re-running a sync on every load that doesn't
		// have a stashed bundle. Likewise, non-null restoredSelectPref / restoredMovePref on
		// NONE would have MainActivity overwriting the real user prefs with whatever NONE
		// carries, on every fresh load.
		assertFalse("NONE must not signal consumed", RestoreController.Outcome.NONE.consumed());
		assertNull("NONE must carry null restoredSelectPref",
			RestoreController.Outcome.NONE.restoredSelectPref());
		assertNull("NONE must carry null restoredMovePref",
			RestoreController.Outcome.NONE.restoredMovePref());
	}

	@Test
	public void outcomePositionalConstructorMapsSelectFirstMoveSecond()
	{
		// Pin the (consumed, selectPref, movePref) positional layout. Two CenterMode values
		// in adjacent constructor slots is exactly the footgun that motivates this pin — a
		// future refactor that swaps the parameter order would compile cleanly and silently
		// apply Move-mode's axis to Select-mode (and vice versa) on every restore. Picking
		// HORIZONTAL vs VERTICAL for the two args makes the swap detectable: if order ever
		// gets flipped, both .restoredSelectPref() and .restoredMovePref() invert, and these
		// asserts surface it.
		RestoreController.Outcome out = new RestoreController.Outcome(
			true, CenterMode.HORIZONTAL, CenterMode.VERTICAL);
		assertTrue("first arg → consumed", out.consumed());
		assertSame("second arg → restoredSelectPref", CenterMode.HORIZONTAL, out.restoredSelectPref());
		assertSame("third arg → restoredMovePref", CenterMode.VERTICAL, out.restoredMovePref());
	}

	@Test
	public void readSourceUriReturnsNullForNullBundle()
	{
		// MainActivity.onCreate calls readSourceUri before constructing a RestoreController so
		// the "is there a saved state to restore from?" decision can be made statically. A
		// null savedInstanceState is the fresh-launch case and must return null cleanly
		// (without NPE) so the call site's null check drives down the normal-launch branch.
		assertNull("null bundle → null URI", RestoreController.readSourceUri(null));
	}

	@Test
	public void writeToShortCircuitsWhenGraftApplied()
	{
		// Graft sessions are in-memory only; lastLoadedUri still points at the pre-graft source
		// on disk. The skip-on-graft gate inside writeTo MUST short-circuit before touching
		// outState, because a restore from the pre-graft URI would silently reload the wrong
		// image, replay the user's geometry against it (presenting it as if the external edit
		// were still applied), and the user would save a crop of the original instead.
		//
		// Limitation: this test passes `null` for both outState and sourceUri because the
		// project's pure-JVM test setup (unitTests.returnDefaultValues = true, no Robolectric)
		// can't construct a non-null android.net.Uri — Uri.parse / Uri.fromFile both return
		// null under the stubbed runtime. The null-URI branch of the gate fires before the
		// graft-skip branch, so this test technically proves only that writeTo with a graft
		// applied + null URI doesn't NPE; combined with writeToShortCircuitsWhenSourceUriIsNull
		// it establishes the "early-return shape works under graft state" contract. A direct
		// end-to-end pin of the graft-skip branch would require Robolectric.
		CropState state = new CropState();
		state.installGraft(new Graft(new byte[]{ 0x42 }, "test.jpg", null));
		assertTrue("setup precondition: installGraft must flip graftApplied", state.isGraftApplied());
		RestoreController.writeTo(null, null, state, null, null);
	}

	@Test
	public void writeToShortCircuitsWhenSourceUriIsNull()
	{
		// Null sourceUri short-circuits before any outState write. Verified by passing a null
		// outState — if writeTo touched outState (which it would for any non-null URI when
		// graft isn't applied), the test would NPE. The early-return contract protects the
		// no-loaded-image case (fresh launch, just-closed image) so the next launch falls
		// through to the normal Share-intent handling rather than carrying stale geometry.
		CropState state = new CropState();
		RestoreController.writeTo(null, null, state, null, null);
	}
}
