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
 * end-to-end here without Robolectric). Focuses on five invariants worth pinning:
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
 *  - restoredCropFits gates crop-size restore by the interactive pipeline's corner geometry
 *    (RotatedCropClamp.cornersInside at the clamped restored center, ±0.5-px slack), with the
 *    rotated AABB as a cheap pre-filter — so a source file that changed between process death
 *    and restore can't resurrect a crop whose corners fall outside the rotated image (the size
 *    is left dirty and recomputed instead).
 *  - shouldPersist — the decision gate writeTo routes through — rejects grafted sessions even
 *    when a source URI is live, so a restore can never reload the pre-graft image and present
 *    the user's geometry as if the external edit were still applied.
 */
public final class RestoreControllerTest
{
	@Test
	public void applyIfPendingReturnsNoneAfterSecondCallEvenIfFirstConsumed()
	{
		// Defensive against a future double-call regression: a caller that runs applyIfPending twice (e.g.,
		// installImageOnUi paired with a redundant post-load re-sync) must NOT re-apply the stale bundle on the
		// second call. The bundle is nulled in-place after the first consumption, so the second call sees
		// pendingRestoreBundle == null and short-circuits to NONE.
		CropState state = new CropState();
		RestoreController controller = new RestoreController(state);
		Bundle bundle = new Bundle();
		controller.stash(bundle);
		// First call consumes the stash — returnDefaultValues means every getString / getFloat / getBoolean
		// reads as default, so applyRestoreBundle's branches all short-circuit (no setCenter, no selection
		// rebuild), but the bundle IS consumed.
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
		// Default-constructed controller has no pending bundle. applyIfPending must return the singleton NONE —
		// same reference, not just an equal value — so callers checking `outcome == Outcome.NONE` (cheaper than
		// .consumed()) stay correct.
		CropState state = new CropState();
		RestoreController controller = new RestoreController(state);
		assertSame("no-stash applyIfPending returns the singleton NONE",
			RestoreController.Outcome.NONE, controller.applyIfPending());
	}

	@Test
	public void clearPendingIfUnconsumedNoOpWhenNothingStashed()
	{
		// Called unconditionally on every setBusyUi(false) — must be safe when no bundle was stashed (fresh
		// launch, or a previous applyIfPending already consumed it). The body has a null guard; this test pins
		// the public contract that the call doesn't throw AND that a subsequent applyIfPending still returns
		// NONE (no side effect that re-pends a bundle).
		CropState state = new CropState();
		RestoreController controller = new RestoreController(state);
		controller.clearPendingIfUnconsumed();
		assertSame("clearPendingIfUnconsumed must not re-pend a bundle",
			RestoreController.Outcome.NONE, controller.applyIfPending());
	}

	@Test
	public void outcomeNoneCarriesUnconsumedAndNullPrefs()
	{
		// Pin Outcome.NONE's component values. A regression that wrote NONE as `new Outcome(true, ...)`
		// (consumed=true) would silently flip the caller's "should we re-sync the toolbar?" gate, re-running a
		// sync on every load that doesn't have a stashed bundle. Likewise, non-null restoredSelectPref /
		// restoredMovePref on NONE would have MainActivity overwriting the real user prefs with whatever NONE
		// carries, on every fresh load.
		assertFalse("NONE must not signal consumed", RestoreController.Outcome.NONE.consumed());
		assertNull("NONE must carry null restoredSelectPref",
			RestoreController.Outcome.NONE.restoredSelectPref());
		assertNull("NONE must carry null restoredMovePref", RestoreController.Outcome.NONE.restoredMovePref());
	}

	@Test
	public void outcomePositionalConstructorMapsSelectFirstMoveSecond()
	{
		// Pin the (consumed, selectPref, movePref) positional layout. Two CenterMode values in adjacent
		// constructor slots is exactly the footgun that motivates this pin — a future refactor that swaps the
		// parameter order would compile cleanly and silently apply Move-mode's axis to Select-mode (and vice
		// versa) on every restore. Picking HORIZONTAL vs VERTICAL for the two args makes the swap detectable:
		// if order ever gets flipped, both .restoredSelectPref() and .restoredMovePref() invert, and these
		// asserts surface it.
		RestoreController.Outcome out = new RestoreController.Outcome(
			true, CenterMode.HORIZONTAL, CenterMode.VERTICAL);
		assertTrue("first arg → consumed", out.consumed());
		assertSame("second arg → restoredSelectPref", CenterMode.HORIZONTAL, out.restoredSelectPref());
		assertSame("third arg → restoredMovePref", CenterMode.VERTICAL, out.restoredMovePref());
	}

	@Test
	public void readSourceUriReturnsEmptyForNullBundle()
	{
		// MainActivity.onCreate calls readSourceUri before constructing a RestoreController so the "is there a
		// saved state to restore from?" decision can be made statically. A null savedInstanceState is the
		// fresh-launch case and must return empty cleanly (without NPE) so the call site's isPresent branch
		// drives down the normal-launch path.
		assertTrue("null bundle → empty URI", RestoreController.readSourceUri(null).isEmpty());
	}

	@Test
	public void restoredCropFitsAcceptsExactFitAtZeroRotation()
	{
		// Full-image crop (the post-load default for FREE AR) restores against an unchanged image. Exact
		// equality must pass — a strict `<` regression would discard the crop size on every restore of an
		// untouched full-frame session. Zero-rotation behavior is unchanged by the corner-geometry check:
		// corners of an exact-fit crop sit on the image edges, inside the ±0.5-px slack.
		assertTrue("exact-fit crop must restore intact",
			RestoreController.restoredCropFits(4000, 3000, 2000f, 1500f, 4000, 3000, 0f));
	}

	@Test
	public void restoredCropFitsAcceptsRotatedCropWiderThanUnrotatedImage()
	{
		// A 1300×10 strip at 45° in a 1000×1000 image is legitimate interactive output: the strip lies along
		// the rotated diagonal, so its corners un-rotate to ~±463 px from center — inside the source — even
		// though 1300 exceeds the un-rotated image width. A regression that checks the raw image dims instead
		// of the rotated geometry would reject it and silently discard the user's pre-kill crop size on every
		// rotated-session restore.
		assertTrue("diagonal strip wider than the un-rotated image must fit at 45°",
			RestoreController.restoredCropFits(1300, 10, 500f, 500f, 1000, 1000, 45f));
	}

	@Test
	public void restoredCropFitsHonorsHalfPixelSlackAtBoundary()
	{
		// Pin the ±0.5-px slack shared with the interactive pipeline. A square crop of side s centered in a
		// 1000×1000 image at 45° un-rotates its corners to ±s/√2 from center: s=707 lands at 999.92 (inside
		// the 1000.5 slack bound), s=708 at 1000.63 (outside). A tighter check would reject legitimately
		// persisted dims that were rounded up from float extents; a looser one would let off-image crops
		// survive restore.
		assertTrue("707-px square at 45° sits inside the +0.5-px slack and must fit",
			RestoreController.restoredCropFits(707, 707, 500f, 500f, 1000, 1000, 45f));
		assertFalse("708-px square at 45° exceeds the slack and must be rejected",
			RestoreController.restoredCropFits(708, 708, 500f, 500f, 1000, 1000, 45f));
	}

	@Test
	public void restoredCropFitsRejectsAabbPassingCornerFailingCropAt45Degrees()
	{
		// The changed-file corner case the AABB check alone cannot catch: a 1000×1000 crop restored at 45°
		// against a 1000×1000 image passes the rotated-AABB pre-filter (~1414×1414) but its corners un-rotate
		// to ~±707 px from center — far outside the source. Accepting it would lock the size
		// (clearCropSizeDirty()), route recomputeCrop through its size-locked early return, and let a
		// corners-outside crop survive until the next gesture because recheckRotationFit never runs.
		assertFalse("AABB-passing, corner-failing crop must be rejected",
			RestoreController.restoredCropFits(1000, 1000, 500f, 500f, 1000, 1000, 45f));
	}

	@Test
	public void restoredCropFitsRejectsCropLargerThanShrunkImage()
	{
		// The shrunk-image restore case: geometry persisted against a 4000×3000 source, but the file was
		// replaced by a 2000×1500 version between process death and restore. The oversized dims must be
		// rejected so applyRestoreBundle leaves the crop size dirty and the post-restore recompute derives the
		// max fitting crop — instead of an off-image crop rect persisting until the next gesture.
		assertFalse("crop larger than the shrunk image must not fit",
			RestoreController.restoredCropFits(4000, 3000, 1000f, 750f, 2000, 1500, 0f));
		// One oversized axis is enough to reject — a width-only (or height-only) check would let a half-fitting
		// rect through.
		assertFalse("oversized width alone must reject",
			RestoreController.restoredCropFits(4000, 1000, 1000f, 750f, 2000, 1500, 0f));
		assertFalse("oversized height alone must reject",
			RestoreController.restoredCropFits(1000, 3000, 1000f, 750f, 2000, 1500, 0f));
	}

	@Test
	public void shouldPersistAcceptsLoadedNonGraftedState()
	{
		// The persist half of the contract: a loaded source with no graft is exactly the session writeTo exists
		// to capture. A regression to false here would make writeTo no-op on every process death, silently
		// discarding the user's geometry on every restore.
		CropState state = new CropState();
		assertTrue("loaded + non-grafted session must persist", RestoreController.shouldPersist(true, state));
	}

	@Test
	public void shouldPersistRejectsGraftedStateEvenWithSourceUri()
	{
		// The graft half of writeTo's skip gate, pinned directly (writeTo routes through shouldPersist). Graft
		// bytes are in-memory only — lastLoadedUri still points at the pre-graft file — so persisting would
		// make the restore reload the ORIGINAL image and replay the geometry over it as if the external edit
		// were still applied; the user would save a crop of the wrong image. hasSourceUri is true here, so the
		// null-URI term cannot mask the gate: deleting the isGraftApplied() term turns exactly this test red.
		CropState state = new CropState();
		state.installGraft(new Graft(new byte[]{ 0x42 }, "test.jpg", null));
		assertTrue("setup precondition: installGraft must flip graftApplied", state.isGraftApplied());
		assertFalse("grafted session must never persist, even with a live source URI",
			RestoreController.shouldPersist(true, state));
	}

	@Test
	public void shouldPersistRejectsMissingSourceUri()
	{
		// Fresh launch / just-closed image: nothing loaded → nothing to persist, regardless of the rest of the
		// state. Keeps the next onCreate on the normal Share-intent path.
		CropState state = new CropState();
		assertFalse("session without a source URI must not persist",
			RestoreController.shouldPersist(false, state));
	}

	@Test
	public void writeToShortCircuitsWhenGraftApplied()
	{
		// Graft sessions are in-memory only; lastLoadedUri still points at the pre-graft source on disk. The
		// skip-on-graft gate inside writeTo MUST short-circuit before touching outState, because a restore from
		// the pre-graft URI would silently reload the wrong image, replay the user's geometry against it
		// (presenting it as if the external edit were still applied), and the user would save a crop of the
		// original instead. This test passes `null` for both outState and sourceUri because the project's
		// pure-JVM test setup (unitTests.returnDefaultValues = true, no Robolectric) can't construct a non-null
		// android.net.Uri — Uri.parse / Uri.fromFile both return null under the stubbed runtime. It therefore
		// pins the early-return SHAPE under graft state (no NPE, no outState touch); the graft DECISION itself
		// is pinned directly by shouldPersistRejectsGraftedStateEvenWithSourceUri against the shouldPersist
		// seam that writeTo routes through.
		CropState state = new CropState();
		state.installGraft(new Graft(new byte[]{ 0x42 }, "test.jpg", null));
		assertTrue("setup precondition: installGraft must flip graftApplied", state.isGraftApplied());
		RestoreController.writeTo(null, null, state, null, null);
	}

	@Test
	public void writeToShortCircuitsWhenSourceUriIsNull()
	{
		// Null sourceUri short-circuits before any outState write. Verified by passing a null outState — if
		// writeTo touched outState (which it would for any non-null URI when graft isn't applied), the test
		// would NPE. The early-return contract protects the no-loaded-image case (fresh launch, just-closed
		// image) so the next launch falls through to the normal Share-intent handling rather than carrying
		// stale geometry.
		CropState state = new CropState();
		RestoreController.writeTo(null, null, state, null, null);
	}
}
