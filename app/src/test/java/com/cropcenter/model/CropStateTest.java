package com.cropcenter.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cropcenter.util.AiRegionDetector.AiMask;
import com.cropcenter.util.BitmapUtils;

import java.util.Arrays;
import java.util.Collections;

/**
 * Tests for the CropState behaviour that doesn't touch a Bitmap. The big-leverage one is setRotationDegrees: NaN /
 * infinity sanitization, ±180° clamp, and the sub-epsilon snap-to-zero that's the chokepoint for every rotation entry
 * point in the app. A bug in any of these would let a malformed input poison the rotation pipeline.
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
		state.setCropSizeDirty(false);
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
		// Documented short-circuit: an already-empty list skips the clear() + notifyChanged() to avoid
		// spurious listener fires. Pin so a regression that drops the empty-check would surface here as a
		// 1-fire vs 0-fire mismatch — and the editor would gain noisy redraws every time a no-op clear runs
		// (e.g., loading two images in a row).
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
		// Documented contract: with no center placed, the float-precision crop origin reads as 0 — not
		// centerX - cropW/2 evaluated on the uninitialized fields. Without this guard the renderer would
		// read NaN/inf during the load gap between sourceImage = bmp and the first applyStateToUi tick.
		CropState state = new CropState();
		assertFalse(state.hasCenter());
		assertEquals(0f, state.getCropImageXFloat(), 0f);
		assertEquals(0f, state.getCropImageYFloat(), 0f);
	}

	@Test
	public void getCropImageXFloatReturnsCenterMinusHalfWidth()
	{
		// Sub-pixel precision is the whole point of getCropImageXFloat — a smoothly rotating selection
		// midpoint produces smooth crop motion on screen instead of integer-snapped jitter. Pin the formula
		// so a refactor to integer arithmetic surfaces here.
		CropState state = new CropState();
		state.setCropSizeSilent(101, 73);
		state.setCenterUnclamped(150.25f, 200.75f);
		assertTrue(state.hasCenter());
		assertEquals("centerX - cropW / 2f = 150.25 - 50.5 = 99.75",
			99.75f, state.getCropImageXFloat(), 0f);
		assertEquals("centerY - cropH / 2f = 200.75 - 36.5 = 164.25",
			164.25f, state.getCropImageYFloat(), 0f);
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
		// ImageLoadController.applyBytes uses to publish the load-commit; the per-field setters that
		// previously seeded these were deleted in favour of this single atomic write. pngExifTiff is
		// included with a non-null seed so reset()'s pngExifTiff = null line is observably tested —
		// the earlier version of this test passed null on that argument, leaving the clear unverifiable.
		CropState state = new CropState();
		state.installLoadedImage(
			new byte[] { 0x10 },
			"foo.jpg",
			Format.PNG,
			Collections.emptyList(),
			new byte[] { 0x40 },
			new byte[] { 0x20 },
			new byte[] { 0x30 });
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
		// CropState.setCenter snapshots the volatile sourceImage at method entry. The snapshot exists
		// because a bg-thread reset() can null the field between this read and the getWidth()/Height()
		// reads inside the clamp branch — without the snapshot the UI thread NPEs on
		// snapshot.getWidth(). Pin the contract: setCenter with sourceImage == null must (a) NOT
		// throw, (b) NOT apply the clamp (the (x, y) pass through), (c) still set hasCenter and fire
		// the listener.
		CropState state = new CropState();
		// sourceImage stays null (default).
		// cropW/cropH stay 0 (default), so the clamp branch would be skipped even with a non-null
		// snapshot — but the test still exercises the snapshot path. Pre-fix the NPE was inside the
		// snapshot != null && cropW > 0 && cropH > 0 branch's sourceImage.getWidth() call; the
		// snapshot prevents it.
		state.setCenter(500f, 700f);
		assertEquals("centerX accepted without clamp (no source)", 500f, state.getCenterX(), 0f);
		assertEquals("centerY accepted without clamp (no source)", 700f, state.getCenterY(), 0f);
		assertTrue("hasCenter flips true regardless of source presence", state.hasCenter());
	}

	// ── installLoadedImage() ──

	@Test
	public void installLoadedImageJpegAfterPngOverridesPriorExportSeed()
	{
		// Subsequent JPEG load after PNG must update the export seed. Split from
		// installLoadedImagePngSeedsExportConfig so a regression in JPEG-override behaviour fails this
		// test specifically rather than being masked by the prior PNG-seed assertion. The
		// setSourceFormat seed-logic that pinned this contract was inlined into installLoadedImage when
		// the per-field setter was deleted; the assertion is identical, the path through the model is
		// the only thing that changed.
		CropState state = new CropState();
		state.installLoadedImage(null, null, Format.PNG, null, null, null, null);
		state.installLoadedImage(null, null, Format.JPEG, null, null, null, null);
		assertEquals(Format.JPEG, state.getExportConfig().format());
	}

	@Test
	public void installLoadedImageNullFormatPreservesPriorExportSeed()
	{
		// Null source format is a no-op for exportConfig — happens when ImageLoadController's format
		// detection bails on a loaded blob that's neither JPEG nor PNG. The export seed must NOT
		// regress just because the next load couldn't classify itself; the user's prior session's
		// exportConfig stays in place. Split from installLoadedImagePngSeedsExportConfig so this
		// no-op contract has its own failure isolation.
		//
		// Note: the second installLoadedImage call DOES wipe originalFileBytes / originalFilename /
		// jpegMeta / pngExifTiff / gainMap / seftTrailer back to (null, null, [], null, null, null) —
		// those writes are intentional in the contract (every load commits its own bundle). This test
		// only asserts the exportConfig invariant. resetClearsSourceAndMetadataFields above pins the
		// field-clearing behaviour separately.
		CropState state = new CropState();
		state.installLoadedImage(null, null, Format.PNG, null, null, null, null);
		state.installLoadedImage(null, null, null, null, null, null, null);
		assertEquals("null source format must not clobber prior exportConfig",
			Format.PNG, state.getExportConfig().format());
	}

	@Test
	public void installLoadedImagePngSeedsExportConfig()
	{
		// `installLoadedImage(..., PNG, ...)` must seed `exportConfig.format()` to PNG so the
		// SaveDialog's format toggle defaults to "save as PNG" for PNG sources without an intervening
		// `updateExportConfig` call. A regression that drops the seed (only stores `sourceFormat` and
		// forgets the `withFormat`) would silently change the user's save format from PNG to JPEG —
		// alpha loss + format conversion on save. The seed logic was previously inside setSourceFormat;
		// it was inlined into installLoadedImage when the per-field setter was deleted.
		CropState state = new CropState();
		assertEquals("default exportConfig is JPEG", Format.JPEG, state.getExportConfig().format());
		state.installLoadedImage(null, null, Format.PNG, null, null, null, null);
		assertEquals("PNG source seeds exportConfig to PNG immediately",
			Format.PNG, state.getExportConfig().format());
	}

	// ── reset() (additional invariants) ──

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
		// Spec: new loads start in Select mode with Both lock-axis. A previous Move + Pan combo must not leak
		// into the new image session.
		CropState state = new CropState();
		state.setEditorMode(EditorMode.MOVE);
		state.setCenterMode(CenterMode.HORIZONTAL);
		state.setCenterLocked(true);
		state.reset();
		assertEquals(EditorMode.SELECT_FEATURE, state.getEditorMode());
		assertEquals(CenterMode.BOTH, state.getCenterMode());
		assertFalse("centerLocked must be cleared", state.isCenterLocked());
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
}
