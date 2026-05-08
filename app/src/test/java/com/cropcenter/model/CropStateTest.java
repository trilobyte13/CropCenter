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
		// Source-image-related state: source bytes, filename, format, gain map, SEFT trailer, jpegMeta.
		CropState state = new CropState();
		state.setOriginalFileBytes(new byte[] { 0x10 });
		state.setOriginalFilename("foo.jpg");
		state.setSourceFormat(Format.PNG);
		state.setGainMap(new byte[] { 0x20 });
		state.setSeftTrailer(new byte[] { 0x30 });
		state.reset();
		assertNull(state.getOriginalFileBytes());
		assertNull(state.getOriginalFilename());
		assertNull(state.getSourceFormat());
		assertNull(state.getGainMap());
		assertNull(state.getSeftTrailer());
		assertEquals("jpegMeta is replaced with empty list, not nulled", 0, state.getJpegMeta().size());
	}

	@Test
	public void setSourceFormatSeedsExportConfig()
	{
		// Codex round-26 T4 — `setSourceFormat(PNG)` must seed `exportConfig.format()` to PNG so the
		// SaveDialog's format toggle defaults to "save as PNG" for PNG sources without an intervening
		// `updateExportConfig` call. A regression that drops the seed (only stores `sourceFormat` and
		// forgets the `withFormat`) would silently change the user's save format from PNG to JPEG —
		// alpha loss + format conversion on save.
		CropState state = new CropState();
		assertEquals("default exportConfig is JPEG", Format.JPEG, state.getExportConfig().format());
		state.setSourceFormat(Format.PNG);
		assertEquals("PNG source seeds exportConfig to PNG immediately",
			Format.PNG, state.getExportConfig().format());
		// JPEG seed pin (the spec calls out JPEG specifically as the override case after a PNG load).
		state.setSourceFormat(Format.JPEG);
		assertEquals("subsequent JPEG source updates the seed",
			Format.JPEG, state.getExportConfig().format());
		// Null source format is a no-op — happens when ImageLoadController's format detection bails on a
		// loaded blob that's neither JPEG nor PNG; exportConfig must NOT regress.
		state.setSourceFormat(null);
		assertEquals("null source format must not clobber prior exportConfig",
			Format.JPEG, state.getExportConfig().format());
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
}
