package com.cropcenter.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.cropcenter.util.AiRegionDetector.AiMask;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.util.RotatedCropClamp;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

/**
 * Tests for the CropState behaviour that doesn't touch a Bitmap. The big-leverage ones: setRotationDegrees (NaN /
 * infinity sanitization, ±180° clamp, and the sub-epsilon snap-to-zero that's the chokepoint for every rotation entry
 * point in the app) and the load-epoch guard, which the tests drive deterministically through the package-private
 * preCommitHook seam — reset() runs inside a mutator's capture-to-commit window, exactly where a bg image load races an
 * in-flight UI gesture.
 */
public final class CropStateTest
{
	private static final float EPS = BitmapUtils.ROTATION_EPSILON;

	@Test
	public void clampNegativeOutOfRange()
	{
		CropState state = new CropState();
		state.setRotationDegrees(-2000f);
		assertEquals(-180f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void clampPositiveOutOfRange()
	{
		CropState state = new CropState();
		state.setRotationDegrees(2000f);
		assertEquals(180f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void exactBoundaryValuesAreKept()
	{
		// ±180 is in-range; ±EPSILON is above the snap threshold so it survives.
		CropState state = new CropState();
		state.setRotationDegrees(180f);
		assertEquals(180f, state.getRotationDegrees(), 0f);
		state.setRotationDegrees(-180f);
		assertEquals(-180f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void infinityCollapsesToZero()
	{
		CropState state = new CropState();
		state.setRotationDegrees(Float.POSITIVE_INFINITY);
		assertEquals(0f, state.getRotationDegrees(), 0f);
		state.setRotationDegrees(Float.NEGATIVE_INFINITY);
		assertEquals(0f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void markCropSizeDirtyAfterRotationChange()
	{
		// Rotation changes the rotated-AABB size, so the crop has to be re-fitted. CropState owns this dirty
		// flag and EVERY rotation entry point is expected to flow through this setter so the flag is set
		// uniformly. A regression that bypassed it would leave a stale crop visible after a rotation.
		CropState state = new CropState();
		state.clearCropSizeDirty();
		assertFalse(state.isCropSizeDirty());
		state.setRotationDegrees(15f);
		assertTrue(state.isCropSizeDirty());
	}

	@Test
	public void nanCollapsesToZero()
	{
		// NaN inputs (e.g., a bad formula in the horizon detector) are sanitized to 0. Without this, NaN would
		// survive the clamp (Math.clamp(NaN, ..) = NaN) and poison every downstream consumer until the next
		// setRotationDegrees call.
		CropState state = new CropState();
		state.setRotationDegrees(Float.NaN);
		assertEquals(0f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void normalRotationIsKept()
	{
		CropState state = new CropState();
		state.setRotationDegrees(15.5f);
		assertEquals(15.5f, state.getRotationDegrees(), 0f);
		state.setRotationDegrees(-90f);
		assertEquals(-90f, state.getRotationDegrees(), 0f);
		state.setRotationDegrees(0.05f);
		assertEquals(0.05f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void subEpsilonNegativeAlsoSnapsToZero()
	{
		CropState state = new CropState();
		state.setRotationDegrees(-EPS / 2f);
		assertEquals(0f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void subEpsilonPositiveSnapsToZero()
	{
		// The chokepoint behaviour: any |deg| < ROTATION_EPSILON collapses to exactly 0. Without this, a 0.003°
		// value from the horizon detector or the precise dialog would survive (CropState stores it), then
		// UiSync would hide the readout AND CropEngine would treat as zero — UI lying about state,
		// ExportPipeline forced into a needless re-encode. The snap is the single chokepoint that prevents
		// that. Verify both directions of sub-epsilon.
		CropState state = new CropState();
		state.setRotationDegrees(EPS / 2f);
		assertEquals(0f, state.getRotationDegrees(), 0f);
	}

	@Test
	public void valueAtEpsilonBoundaryIsKept()
	{
		// Strict less-than in the snap: exactly EPSILON survives. (The boundary value itself is a real-world
		// rotation, not noise — the snap exists to reject values BELOW the smallest user-controllable step.)
		CropState state = new CropState();
		state.setRotationDegrees(EPS);
		assertEquals(EPS, state.getRotationDegrees(), 0f);
	}

	// ── installGraft ──

	@Test
	public void installGraftSetsGraftAppliedAndAiMaskAtomically()
	{
		// Pin the "two state writes, both required" invariant — without graftApplied,
		// ExportPipeline.canBypassEncode short-circuits the canvas pass; without aiMask, the gain-map inpaint
		// is a no-op. installGraft is the single chokepoint guaranteeing both writes happen together.
		CropState state = new CropState();
		assertFalse(state.isGraftApplied());
		assertNull(state.getAiMask());
		// Create a mask with at least one true bit so hasMaskedPixels() returns true; the all-false case hits
		// the "no fill detected" branch which intentionally doesn't install the mask.
		boolean[] maskBits = new boolean[100];
		maskBits[42] = true;
		AiMask mask = AiMask.of(maskBits, 10, 10, 1);
		Graft graft = new Graft(new byte[] { 0x10, 0x20 }, "edited.jpg", mask);
		state.installGraft(graft);
		assertTrue("graftApplied must be set", state.isGraftApplied());
		assertNotNull("aiMask must be installed when graft has one", state.getAiMask());
	}

	@Test
	public void installGraftWithoutMaskStillSetsGraftApplied()
	{
		// SDR sources don't get an AI mask (UltraHdrCompat skips detection when there's no gain map). Pin that
		// the graftApplied flag still flips — canBypassEncode must still be refused so the canvas re-encode
		// runs.
		CropState state = new CropState();
		Graft graft = new Graft(new byte[] { 0x10 }, "edited.jpg", null);
		state.installGraft(graft);
		assertTrue(state.isGraftApplied());
		assertNull("no mask supplied — aiMask stays null", state.getAiMask());
	}

	// ── clearSelectionPoints ──

	@Test
	public void clearSelectionPointsEmptiesListAndFiresListenerOnce()
	{
		// Documented contract: when there's at least one point, the clear empties the list and fires the
		// listener exactly once. The path is used by reset() and by some UI gestures that wipe the user's
		// selection without re-loading the image.
		CropState state = new CropState();
		state.addSelectionPoint(new SelectionPoint(10f, 20f));
		state.addSelectionPoint(new SelectionPoint(30f, 40f));
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		state.clearSelectionPoints();
		assertTrue("selection list must be empty after clear", state.getSelectionPoints().isEmpty());
		assertEquals("listener fires exactly once", 1, fireCount[0]);
	}

	@Test
	public void clearSelectionPointsOnEmptyListIsNoOp()
	{
		// Documented short-circuit: an already-empty list skips the clear() + notifyChanged() to avoid spurious
		// listener fires. Pin so a regression that drops the empty-check would surface here as a 1-fire vs
		// 0-fire mismatch — and the editor would gain noisy redraws every time a no-op clear runs (e.g.,
		// loading two images in a row).
		CropState state = new CropState();
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		state.clearSelectionPoints();
		assertTrue(state.getSelectionPoints().isEmpty());
		assertEquals("listener must not fire when there's nothing to clear", 0, fireCount[0]);
	}

	// ── getCropImageXFloat / getCropImageYFloat ──

	@Test
	public void getCropImageXFloatReturnsZeroBeforeCenterIsPlaced()
	{
		// Documented contract: with no center placed, the float-precision crop origin reads as 0 — not centerX
		// - cropW/2 evaluated on the uninitialized fields. Without this guard the renderer would read NaN/inf
		// during the load gap between sourceImage = bmp and the first applyStateToUi tick.
		CropState state = new CropState();
		assertFalse(state.hasCenter());
		assertEquals(0f, state.getCropImageXFloat(), 0f);
		assertEquals(0f, state.getCropImageYFloat(), 0f);
	}

	@Test
	public void getCropImageXFloatReturnsCenterMinusHalfWidth()
	{
		// Sub-pixel precision is the whole point of getCropImageXFloat — a smoothly rotating selection midpoint
		// produces smooth crop motion on screen instead of integer-snapped jitter. Pin the formula so a
		// refactor to integer arithmetic surfaces here.
		CropState state = new CropState();
		state.setCropSizeSilent(101, 73);
		state.setCenterUnclamped(150.25f, 200.75f);
		assertTrue(state.hasCenter());
		assertEquals("centerX - cropW / 2f = 150.25 - 50.5 = 99.75", 99.75f, state.getCropImageXFloat(), 0f);
		assertEquals("centerY - cropH / 2f = 200.75 - 36.5 = 164.25", 164.25f, state.getCropImageYFloat(), 0f);
	}

	// ── removeSelectionPoint ──

	@Test
	public void removeSelectionPointAbsentReturnsFalseWithoutFire()
	{
		// Absent point: false, list untouched, zero listener fires. A notify-on-absent regression would run a
		// full recompute pass for a no-op; a boolean inversion would make onTap / onLongPress treat a failed
		// removal as a successful destructive edit.
		CropState state = new CropState();
		state.addSelectionPoint(new SelectionPoint(10f, 20f));
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		assertFalse("no equal point exists — must report false",
			state.removeSelectionPoint(new SelectionPoint(99f, 99f)));
		assertEquals("list must be unchanged", 1, state.getSelectionPoints().size());
		assertEquals("listener must not fire for a no-op removal", 0, fireCount[0]);
	}

	@Test
	public void removeSelectionPointPresentRemovesAndFiresOnce()
	{
		// Happy path of the only selection mutator without one: a present point reports true, the list shrinks
		// by exactly that point, and the listener fires exactly once — the fire drives the crop recompute that
		// follows every removal, so remove-without-notify desynchronizes the crop from the selection.
		CropState state = new CropState();
		SelectionPoint kept = new SelectionPoint(10f, 20f);
		SelectionPoint target = new SelectionPoint(30f, 40f);
		state.addSelectionPoint(kept);
		state.addSelectionPoint(target);
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		assertTrue("present point must report removed", state.removeSelectionPoint(target));
		assertEquals("exactly one point removed", 1, state.getSelectionPoints().size());
		assertEquals("the surviving point is the one NOT removed", kept, state.getSelectionPoints().get(0));
		assertEquals("listener fires exactly once", 1, fireCount[0]);
	}

	// ── replaceSelectionPoints ──

	@Test
	public void replaceSelectionPointsAtomicallyClearsAndRefills()
	{
		// Used by undo/redo snapshot restores — the entire selection list is replaced in one operation. Pin
		// that the previous points are gone and only the new ones remain.
		CropState state = new CropState();
		state.addSelectionPoint(new SelectionPoint(10f, 20f));
		state.addSelectionPoint(new SelectionPoint(30f, 40f));
		assertEquals(2, state.getSelectionPoints().size());
		state.replaceSelectionPoints(Arrays.asList(new SelectionPoint(50f, 60f),
			new SelectionPoint(70f, 80f), new SelectionPoint(90f, 100f)));
		assertEquals(3, state.getSelectionPoints().size());
		assertEquals(50f, state.getSelectionPoints().get(0).x(), 0f);
		assertEquals(90f, state.getSelectionPoints().get(2).x(), 0f);
	}

	@Test
	public void replaceSelectionPointsAcceptsEmptyCollection()
	{
		// Pin: replacing with empty == clearing.
		CropState state = new CropState();
		state.addSelectionPoint(new SelectionPoint(10f, 20f));
		state.replaceSelectionPoints(Collections.emptyList());
		assertTrue(state.getSelectionPoints().isEmpty());
	}

	// ── updateExportConfig / updateGridConfig ──

	@Test
	public void updateExportConfigAppliesTransformer()
	{
		CropState state = new CropState();
		assertEquals(Format.JPEG, state.getExportConfig().format());
		state.updateExportConfig(c -> c.withFormat(Format.PNG));
		assertEquals(Format.PNG, state.getExportConfig().format());
	}

	@Test
	public void updateGridConfigAppliesTransformer()
	{
		CropState state = new CropState();
		assertFalse(state.getGridConfig().includeInExport());
		state.updateGridConfig(g -> g.withIncludeInExport(true));
		assertTrue(state.getGridConfig().includeInExport());
	}

	@Test
	public void updateGridConfigAllowsChainedTransformers()
	{
		// Multi-field grid changes fold into one transformer so the listener fires once. Verify both fields
		// land — a regression that returned a partial GridConfig from one of the withXxx calls would only
		// commit one update.
		CropState state = new CropState();
		state.updateGridConfig(g -> g.withColumns(7).withRows(9));
		assertEquals(7, state.getGridConfig().columns());
		assertEquals(9, state.getGridConfig().rows());
	}

	// ── reset() ──

	@Test
	public void resetClearsSourceAndMetadataFields()
	{
		// Source-image-related state: source bytes, filename, format, gain map, SEFT trailer, jpegMeta,
		// pngExifTiff. Seeding via installLoadedImage matches the single production entry-point
		// ImageLoadController.applyBytes uses to publish the load-commit (there are no per-field setters for
		// these). pngExifTiff is seeded non-null so reset()'s pngExifTiff = null line is observably tested — a
		// null seed would leave the clear unverifiable.
		CropState state = new CropState();
		state.installLoadedImage(new byte[] { 0x10 }, "foo.jpg", Format.PNG,
			Collections.emptyList(), new byte[] { 0x40 }, new byte[] { 0x20 }, new byte[] { 0x30 });
		state.reset();
		assertNull(state.getOriginalFileBytes());
		assertNull(state.getOriginalFilename());
		assertNull(state.getSourceFormat());
		assertNull(state.getPngExifTiff());
		assertNull(state.getGainMap());
		assertNull(state.getSeftTrailer());
		assertEquals("jpegMeta is replaced with empty list, not nulled", 0, state.getJpegMeta().size());
	}

	// ── setCenter() ──

	@Test
	public void setCenterWithNullSourceImageSkipsClampAndDoesNotNpe()
	{
		// Passthrough contract with no image loaded: setCenter must (a) not throw, (b) skip the clamp so the
		// (x, y) pass through verbatim, (c) still set hasCenter, and (d) fire the listener exactly once. The
		// clamp branch itself is unreachable here — the JVM test source set can't construct a Bitmap — so the
		// clamp dispatch is pinned separately via the pure clampCenterToImage helper (its own section below);
		// the NPE-avoidance snapshot inside setCenter is documented at the call site and covered by the
		// load-epoch abandon tests.
		CropState state = new CropState();
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		state.setCenter(500f, 700f);
		assertEquals("centerX accepted without clamp (no source)", 500f, state.getCenterX(), 0f);
		assertEquals("centerY accepted without clamp (no source)", 700f, state.getCenterY(), 0f);
		assertTrue("hasCenter flips true regardless of source presence", state.hasCenter());
		assertEquals("committed setCenter fires the listener once", 1, fireCount[0]);
	}

	// ── setAspectRatio / mode setters (cropSizeDirty truth table) ──

	@Test
	public void setAspectRatioMarksCropSizeDirtyButModeSettersDoNot()
	{
		// Both sides of the dirty-flag truth table. setAspectRatio MUST mark the crop size dirty — without the
		// mark, aspect-ratio picks stop resizing the crop (the size-locked recompute branch keeps stale dims)
		// until the next rotation re-arms the flag. setCenterMode / setEditorMode deliberately do NOT dirty:
		// the lock-button handler calls recomputeForLockChange explicitly, and a dirty-mark in the setter would
		// race that handler's recompute with a listener-driven one using the wrong center (documented at both
		// setters). Pin the two directions together so neither half can silently flip.
		CropState state = new CropState();
		state.clearCropSizeDirty();
		state.setAspectRatio(AspectRatio.R16_9);
		assertTrue("setAspectRatio must mark crop size dirty", state.isCropSizeDirty());
		state.clearCropSizeDirty();
		state.setCenterMode(CenterMode.HORIZONTAL);
		assertFalse("setCenterMode must not mark crop size dirty", state.isCropSizeDirty());
		state.setEditorMode(EditorMode.MOVE);
		assertFalse("setEditorMode must not mark crop size dirty", state.isCropSizeDirty());
	}

	// ── installLoadedImage() ──

	@Test
	public void installLoadedImageJpegAfterPngOverridesPriorExportSeed()
	{
		// Subsequent JPEG load after PNG must update the export seed. Split from
		// installLoadedImagePngSeedsExportConfig so a regression in JPEG-override behaviour fails this test
		// specifically rather than being masked by the prior PNG-seed assertion. The seed logic lives inline in
		// installLoadedImage — the model's only load-commit path.
		CropState state = new CropState();
		state.installLoadedImage(null, null, Format.PNG, null, null, null, null);
		state.installLoadedImage(null, null, Format.JPEG, null, null, null, null);
		assertEquals(Format.JPEG, state.getExportConfig().format());
	}

	@Test
	public void installLoadedImageNullFormatPreservesPriorExportSeed()
	{
		// Null source format is a no-op for exportConfig — happens when ImageLoadController's format detection
		// bails on a loaded blob that's neither JPEG nor PNG. The export seed must NOT regress just because the
		// next load couldn't classify itself; the user's prior session's exportConfig stays in place. Split
		// from installLoadedImagePngSeedsExportConfig so this no-op contract has its own failure isolation.
		// Note: the second installLoadedImage call DOES wipe originalFileBytes / originalFilename / jpegMeta /
		// pngExifTiff / gainMap / seftTrailer back to (null, null, [], null, null, null) — those writes are
		// intentional in the contract (every load commits its own bundle). This test only asserts the
		// exportConfig invariant. resetClearsSourceAndMetadataFields above pins the field-clearing behaviour
		// separately.
		CropState state = new CropState();
		state.installLoadedImage(null, null, Format.PNG, null, null, null, null);
		state.installLoadedImage(null, null, null, null, null, null, null);
		assertEquals("null source format must not clobber prior exportConfig",
			Format.PNG, state.getExportConfig().format());
	}

	@Test
	public void installLoadedImageNullJpegMetaCoercesToEmptyList()
	{
		// The documented "null tolerated" contract for jpegMeta: a null must be coerced to an empty list
		// (List.of), preserving the field's never-null immutable-list contract that every downstream
		// jpegMeta read relies on. Production's sole caller never passes null today; this pins the contract
		// the parameter Javadoc promises to future callers.
		CropState state = new CropState();
		state.installLoadedImage(null, null, null, null, null, null, null);
		assertNotNull("getJpegMeta must never return null", state.getJpegMeta());
		assertTrue("null jpegMeta must coerce to an empty list", state.getJpegMeta().isEmpty());
	}

	@Test
	public void installLoadedImagePngSeedsExportConfig()
	{
		// `installLoadedImage(..., PNG, ...)` must seed `exportConfig.format()` to PNG so the SaveDialog's
		// format toggle defaults to "save as PNG" for PNG sources without an intervening `updateExportConfig`
		// call. A regression that drops the seed (only stores `sourceFormat` and forgets the `withFormat`)
		// would silently change the user's save format from PNG to JPEG — alpha loss + format conversion on
		// save. The seed logic lives inline in installLoadedImage.
		CropState state = new CropState();
		assertEquals("default exportConfig is JPEG", Format.JPEG, state.getExportConfig().format());
		state.installLoadedImage(null, null, Format.PNG, null, null, null, null);
		assertEquals("PNG source seeds exportConfig to PNG immediately",
			Format.PNG, state.getExportConfig().format());
	}

	// ── reset() (additional invariants) ──

	@Test
	public void resetClearsCropGeometryRotationAnchorAndSelection()
	{
		// The crop-session fields a new image must never inherit: center (hasCenter + coords), crop dims,
		// rotation, anchor, selection points, plus the cropSizeDirty re-arm. Deleting any single clear from
		// reset() — most critically `hasCenter = false`, which gates MainActivity.ensureCropCenter's midpoint
		// seed for the new image — must fail here.
		CropState state = new CropState();
		state.setCenter(500f, 700f);
		state.setAnchor(11f, 22f);
		state.setCropSizeSilent(101, 73);
		state.setRotationDegrees(15f);
		state.addSelectionPoint(new SelectionPoint(10f, 20f));
		state.reset();
		assertFalse("hasCenter must clear so ensureCropCenter re-seeds the midpoint", state.hasCenter());
		assertEquals(0f, state.getCenterX(), 0f);
		assertEquals(0f, state.getCenterY(), 0f);
		assertEquals(0, state.getCropW());
		assertEquals(0, state.getCropH());
		assertEquals(0f, state.getRotationDegrees(), 0f);
		assertEquals(0f, state.getAnchorX(), 0f);
		assertEquals(0f, state.getAnchorY(), 0f);
		assertTrue("selection points must clear", state.getSelectionPoints().isEmpty());
		assertTrue("crop size must be marked dirty for the next image", state.isCropSizeDirty());
	}

	@Test
	public void resetReturnsExportConfigToDefaults()
	{
		// Save-time format choice is transient — the next image starts at JPEG (the documented default).
		// extractMetadata in ImageLoadController will override per-source.
		CropState state = new CropState();
		state.updateExportConfig(c -> c.withFormat(Format.PNG));
		state.reset();
		assertEquals(Format.JPEG, state.getExportConfig().format());
	}

	@Test
	public void resetClearsIncludeInExportButPreservesOtherGridFields()
	{
		// Documented footgun-prevention: bake-grid is per-save; the colors/cols/rows are user prefs that should
		// bleed across loads. Verify both halves of the rule.
		CropState state = new CropState();
		state.updateGridConfig(g ->
			g.withIncludeInExport(true).withColumns(7).withRows(9).withColor(0xFFFF0000));
		state.reset();
		assertFalse("includeInExport must be cleared", state.getGridConfig().includeInExport());
		assertEquals("columns must be preserved", 7, state.getGridConfig().columns());
		assertEquals("rows must be preserved", 9, state.getGridConfig().rows());
		assertEquals("color must be preserved", 0xFFFF0000, state.getGridConfig().color());
	}

	@Test
	public void resetReturnsModesToDocumentedDefaults()
	{
		// Spec: new loads start in Select mode with Both lock-axis. A previous Move + Pin combo must not leak
		// into the new image session.
		CropState state = new CropState();
		state.setEditorMode(EditorMode.MOVE);
		state.setCenterMode(CenterMode.HORIZONTAL);
		state.reset();
		assertEquals(EditorMode.SELECT_FEATURE, state.getEditorMode());
		assertEquals(CenterMode.BOTH, state.getCenterMode());
	}

	@Test
	public void resetClearsGraftStateAndAiMask()
	{
		// Graft state from the previous image must not leak — installGraft sets graftApplied + aiMask, and
		// reset must clear both so the next load starts fresh.
		CropState state = new CropState();
		// Create a mask with at least one true bit so hasMaskedPixels() returns true; the all-false case hits
		// the "no fill detected" branch which intentionally doesn't install the mask.
		boolean[] maskBits = new boolean[100];
		maskBits[42] = true;
		AiMask mask = AiMask.of(maskBits, 10, 10, 1);
		state.installGraft(new Graft(new byte[] { 0x10 }, "edited.jpg", mask));
		assertTrue(state.isGraftApplied());
		assertNotNull(state.getAiMask());
		state.reset();
		assertFalse(state.isGraftApplied());
		assertNull(state.getAiMask());
	}

	@Test
	public void resetPreservesAspectRatioAsUserPreference()
	{
		// AspectRatio is a UI preference (4:5 default but the user can pick 16:9 etc.) — should survive across
		// loads. Pin the documented exception to the "reset everything" rule.
		CropState state = new CropState();
		state.setAspectRatio(AspectRatio.R16_9);
		state.reset();
		assertEquals(AspectRatio.R16_9, state.getAspectRatio());
	}

	@Test
	public void beginBatchDelegatesToBusSoMultipleSettersCoalesceToOneListenerFire()
	{
		// CropState.beginBatch / endBatch route to the underlying StateBus instance and the field setters call
		// bus.notifyChanged(). A regression that wired a setter to fire the listener directly (bypassing the
		// bus) — or that broke the delegation entirely — would surface as either zero fires (no notify reached
		// the bus) or N fires (each setter bypassing the batch). Pin the contract that three setter calls
		// inside one batch produce exactly one fire on endBatch.
		CropState state = new CropState();
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		state.beginBatch();
		state.setRotationDegrees(15f);
		state.setAspectRatio(AspectRatio.R16_9);
		state.setEditorMode(EditorMode.MOVE);
		assertEquals("setters inside batch must not fire the listener", 0, fireCount[0]);
		state.endBatch();
		assertEquals("endBatch must flush the coalesced batch as one fire", 1, fireCount[0]);
	}

	@Test
	public void settersOutsideBatchFireListenerImmediately()
	{
		// Counter-test to the above: outside a batch each setter fires the listener directly. A regression that
		// always-batches (forgetting to clear the dirty flag, say) would coalesce N fires into 1 here.
		CropState state = new CropState();
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		state.setRotationDegrees(15f);
		state.setAspectRatio(AspectRatio.R16_9);
		assertEquals("each setter outside a batch fires the listener", 2, fireCount[0]);
	}

	// ── reset() vs in-flight mutators (load-epoch guard) ──

	@Test
	public void setCenterAbandonsCommitWhenResetInterleaves()
	{
		// Deterministic interleaving via the preCommitHook seam: reset() runs after setCenter captures the load
		// epoch but before its commit — the shape of a bg image load racing an in-flight gesture callback. The
		// commit must be abandoned wholesale: no center writes, hasCenter stays false, and the listener does
		// not fire (a fire would run a recompute pass against the half-initialized new-image state).
		CropState state = new CropState();
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		state.preCommitHook = state::reset;
		state.setCenter(500f, 700f);
		assertFalse("stale commit must not set hasCenter on the reset state", state.hasCenter());
		assertEquals(0f, state.getCenterX(), 0f);
		assertEquals(0f, state.getCenterY(), 0f);
		assertEquals("abandoned commit must not fire the listener", 0, fireCount[0]);
	}

	@Test
	public void setCenterEnteredAfterResetCommitsAgainstTheNewEpoch()
	{
		// Counter-test: the guard rejects only commits whose epoch capture predates reset(). A setCenter
		// entered after reset() completes captures the new epoch and commits normally.
		CropState state = new CropState();
		state.preCommitHook = state::reset;
		state.setCenter(500f, 700f);
		state.preCommitHook = null;
		state.setCenter(42f, 43f);
		assertTrue(state.hasCenter());
		assertEquals(42f, state.getCenterX(), 0f);
		assertEquals(43f, state.getCenterY(), 0f);
	}

	@Test
	public void setCenterUnclampedAbandonsCommitWhenResetInterleaves()
	{
		// Same guard as setCenter — recomputeCrop's silent pre-pass write must not re-assert the previous
		// image's center over a fresh reset either.
		CropState state = new CropState();
		state.preCommitHook = state::reset;
		state.setCenterUnclamped(500f, 700f);
		assertFalse(state.hasCenter());
		assertEquals(0f, state.getCenterX(), 0f);
		assertEquals(0f, state.getCenterY(), 0f);
	}

	@Test
	public void addSelectionPointAbandonsCommitWhenResetInterleaves()
	{
		// The copy-swap shape of the stale commit: the mutator copies the OLD image's list, reset() swaps in a
		// fresh empty list, and the stale copy (old points + new point) would clobber it. The guard must leave
		// reset()'s empty list in place and skip the listener fire.
		CropState state = new CropState();
		state.addSelectionPoint(new SelectionPoint(10f, 20f));
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		state.preCommitHook = state::reset;
		state.addSelectionPoint(new SelectionPoint(30f, 40f));
		assertTrue("reset()'s fresh empty list must survive the stale swap",
			state.getSelectionPoints().isEmpty());
		assertEquals("abandoned commit must not fire the listener", 0, fireCount[0]);
	}

	@Test
	public void removeSelectionPointAbandonsCommitAndReturnsFalseWhenResetInterleaves()
	{
		// The boolean contract folds the abandon into "nothing was removed": the point WAS in the pre-reset
		// copy, but the commit never landed, so callers must see false and no listener fire.
		CropState state = new CropState();
		SelectionPoint point = new SelectionPoint(10f, 20f);
		state.addSelectionPoint(point);
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		state.preCommitHook = state::reset;
		assertFalse("superseded removal must report false", state.removeSelectionPoint(point));
		assertEquals("abandoned commit must not fire the listener", 0, fireCount[0]);
	}

	@Test
	public void clearSelectionPointsAbandonsCommitWhenResetInterleaves()
	{
		// reset() already emptied the list; the superseded clear must not fire the listener on top of it — a
		// spurious fire would run a recompute pass against the half-initialized new-image state.
		CropState state = new CropState();
		state.addSelectionPoint(new SelectionPoint(10f, 20f));
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		state.preCommitHook = state::reset;
		state.clearSelectionPoints();
		assertTrue(state.getSelectionPoints().isEmpty());
		assertEquals("abandoned commit must not fire the listener", 0, fireCount[0]);
	}

	@Test
	public void replaceSelectionPointsAbandonsCommitWhenResetInterleaves()
	{
		// An undo/redo restore snapshotted from the previous image must not repopulate the reset state.
		CropState state = new CropState();
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		state.preCommitHook = state::reset;
		state.replaceSelectionPoints(Arrays.asList(new SelectionPoint(50f, 60f)));
		assertTrue("stale restore must not land on the reset state", state.getSelectionPoints().isEmpty());
		assertEquals("abandoned commit must not fire the listener", 0, fireCount[0]);
	}

	@Test
	public void setRotationDegreesAbandonsCommitWhenResetInterleaves()
	{
		// A ruler fling callback carrying the previous image's angle must not rotate the freshly reset state.
		// The commit must be abandoned wholesale: rotation stays at reset()'s 0 and the listener does not fire
		// (a fire would run a recompute pass against the half-initialized new-image state).
		CropState state = new CropState();
		int[] fireCount = { 0 };
		state.setListener(() -> fireCount[0]++);
		state.preCommitHook = state::reset;
		state.setRotationDegrees(15f);
		assertEquals("stale rotation must not land on the reset state", 0f, state.getRotationDegrees(), 0f);
		assertEquals("abandoned commit must not fire the listener", 0, fireCount[0]);
	}

	@Test
	public void setAnchorAbandonsCommitWhenResetInterleaves()
	{
		// setAnchor never fires the listener, so the abandon is observable only through the anchor fields:
		// reset()'s zeroed anchor must survive the stale write.
		CropState state = new CropState();
		state.preCommitHook = state::reset;
		state.setAnchor(11f, 22f);
		assertEquals("stale anchor X must not land on the reset state", 0f, state.getAnchorX(), 0f);
		assertEquals("stale anchor Y must not land on the reset state", 0f, state.getAnchorY(), 0f);
	}

	@Test
	public void setCropSizeSilentAbandonsCommitWhenResetInterleaves()
	{
		// A recompute or restore pass sized against the previous image must not install its dims onto the reset
		// state. Like setAnchor, the setter is silent — the abandon is observable only through the crop dims
		// staying at reset()'s 0.
		CropState state = new CropState();
		state.preCommitHook = state::reset;
		state.setCropSizeSilent(101, 73);
		assertEquals("stale crop width must not land on the reset state", 0, state.getCropW());
		assertEquals("stale crop height must not land on the reset state", 0, state.getCropH());
	}

	@Test
	public void concurrentResetVsSetCenterNeverCommitsTornState() throws Exception
	{
		// Probabilistic companion to the deterministic hook tests above: reset() (bg load executor
		// role) races setCenter (UI gesture role) with no seam. The commit section and reset()
		// synchronize on one lock, so every trial must end in exactly one of the two clean outcomes:
		// setCenter won (or entered after reset) and the full (centerX, centerY, hasCenter) triple is
		// live, or reset superseded it and the triple is fully cleared. Torn states — hasCenter true
		// with zeroed coordinates — must never appear.
		int trials = 1000;
		int tornTrials = 0;
		for (int trial = 0; trial < trials; trial++)
		{
			CropState state = new CropState();
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch go = new CountDownLatch(1);
			Thread bg = new Thread(() -> raceStep(ready, go, state::reset));
			Thread ui = new Thread(() -> raceStep(ready, go, () -> state.setCenter(500f, 700f)));
			bg.start();
			ui.start();
			ready.await();
			go.countDown();
			bg.join();
			ui.join();
			boolean committed = state.hasCenter()
				&& state.getCenterX() == 500f && state.getCenterY() == 700f;
			boolean cleared = !state.hasCenter() && state.getCenterX() == 0f && state.getCenterY() == 0f;
			if (!committed && !cleared)
			{
				tornTrials++;
			}
		}
		assertEquals("reset vs setCenter must never commit a torn/partial state", 0, tornTrials);
	}

	// ── clampCenterToImage ──

	@Test
	public void clampCenterToImageAxisAlignedClampsToValidCenterRange()
	{
		// Zero rotation dispatches to RotatedCropClamp.clampAxisAligned: a 100×100 crop in a 200×300 image
		// confines the center to [50, 150] × [50, 250], so an out-of-range request pins to the range maximum on
		// both axes.
		float[] clamped = CropState.clampCenterToImage(500f, 700f, 100, 100, 0f, 200, 300);
		assertEquals(150f, clamped[0], 0f);
		assertEquals(250f, clamped[1], 0f);
	}

	@Test
	public void clampCenterToImageRotatedDispatchesToClampRotated()
	{
		// 45° must dispatch to clampRotated, whose result on this fixture differs observably from
		// clampAxisAligned's (the rotated 200×200 image's AABB lets the center run past the axis-aligned 150
		// cap). Pin dispatch by identity with the canonical helper — its exact math is RotatedCropClampTest's
		// job — plus the observable divergence from the axis-aligned path.
		float[] clamped = CropState.clampCenterToImage(200f, 200f, 100, 100, 45f, 200, 200);
		float[] rotated = RotatedCropClamp.clampRotated(200f, 200f, 100, 100, 45f, 200, 200);
		float[] axisAligned = RotatedCropClamp.clampAxisAligned(200f, 200f, 100, 100, 200, 200);
		assertEquals("dispatch must route through clampRotated", rotated[0], clamped[0], 0f);
		assertEquals("dispatch must route through clampRotated", rotated[1], clamped[1], 0f);
		assertTrue("fixture must distinguish the two paths, or the dispatch pin is vacuous",
			clamped[0] != axisAligned[0]);
	}

	/**
	 * Race-trial thread body: signal readiness, wait for the shared go latch, run the step.
	 *
	 * @param ready counted down once this thread is staged at the start line
	 * @param go    released by the test body to start both racers as close together as scheduling allows
	 * @param step  the racing operation (reset or setCenter)
	 */
	private static void raceStep(CountDownLatch ready, CountDownLatch go, Runnable step)
	{
		ready.countDown();
		try
		{
			go.await();
		}
		catch (InterruptedException ignored)
		{
			// Unreachable in JUnit flow — checked-exception escape only. A force-kill aborts the trial
			// without its step; the outcome assertion then flags the unusual run.
			Thread.currentThread().interrupt();
			return;
		}
		step.run();
	}
}
