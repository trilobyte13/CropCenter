package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cropcenter.metadata.ExifPatcher;
import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.model.CropState;
import com.cropcenter.model.Format;
import com.cropcenter.model.Graft;
import com.cropcenter.util.AiRegionDetector.AiMask;
import com.cropcenter.util.BitmapUtils;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * Tests for ExportPipeline.canBypassEncode — the gate that decides whether a save writes state.originalFileBytes
 * verbatim or runs the full canvas-encode + metadata- inject pipeline. Each disable condition is exercised
 * independently so a regression that breaks one gate doesn't slip through under the cover of another.
 *
 * The bypass is the byte-perfect-fidelity path for unmodified Samsung HDR JPEGs; incorrectly enabling it for grafts
 * (would ship source's gain map verbatim over the spliced primary) or for cropped/rotated saves (would skip the canvas
 * re-render) is the failure mode that motivates each test below.
 *
 * The hasCenter + bitmap-dimension-match happy path can't be unit-tested without a real Bitmap (BitmapFactory returns
 * null under unitTests.returnDefaultValues=true), but every disable path AND the no-center happy path are covered.
 */
public final class ExportPipelineTest
{
	private static final byte[] JPEG_HEADER = { (byte) 0xFF, (byte) 0xD8 };
	private static final byte[] PNG_HEADER = { (byte) 0x89, 'P', 'N', 'G' };

	@Test
	public void permitsBypassWhenAllGatesClearAndNoCrop()
	{
		// Happy path: JPEG source + JPEG output, no graft, no rotation, no grid bake, bytes available, no
		// crop center seeded yet, AND the source carries an IFD1 thumbnail to round-trip verbatim. Bypass
		// returns true.
		CropState state = jpegStateWithMeta(metaWithThumbnail());
		assertTrue(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void permitsBypassWhenRotationIsSubEpsilon()
	{
		// Sub-epsilon rotation snaps to exactly 0 in setRotationDegrees, so the renderer treats it as no
		// rotation. Bypass should be allowed (assuming all other gates pass) — anything else would force a
		// needless re-encode. hasCenter is false (default), so we don't need a Bitmap to reach the happy-path
		// return. State must carry an EXIF segment with an IFD1 thumbnail; the thumbnail-presence gate
		// disqualifies bypass when the source has no pre-computed thumbnail so the re-encode path can
		// synthesise one.
		CropState state = jpegStateWithMeta(metaWithThumbnail());
		state.setRotationDegrees(BitmapUtils.ROTATION_EPSILON / 2f);
		// Pin the snap-to-zero behavior separately from the bypass gate. Without this assert, a future
		// regression that disabled the sub-epsilon snap would STILL let this test pass (ε/2 is below
		// the bypass threshold even un-snapped) — the snap and the bypass gate would silently
		// co-validate, defeating the test's "both invariants hold" intent.
		assertEquals("setRotationDegrees must snap sub-epsilon to exactly 0",
			0f, state.getRotationDegrees(), 0f);
		assertTrue(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenGraftApplied()
	{
		// Graft saves MUST run through the full encode so UltraHdrCompat regenerates the gain map for the
		// spliced primary. Bypassing would ship source's gain map verbatim — fine for view-only HDR, broken
		// when the user later crops.
		CropState state = jpegStateNoMeta();
		AiMask mask = AiMask.of(new boolean[]{ true }, 1, 1, 4);
		state.installGraft(new Graft(new byte[]{ 0x01 }, "test.jpg", mask));
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenGridBakeInEnabled()
	{
		// Grid bake-in writes lines onto the canvas — can't be expressed by shipping source bytes verbatim.
		CropState state = jpegStateNoMeta();
		state.updateGridConfig(grid -> grid.withIncludeInExport(true));
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenOriginalBytesUnavailable()
	{
		// No source bytes to write verbatim → must encode. installLoadedImage with null fileBytes leaves the
		// state in the same shape ImageLoadController would produce on a bytes-failed-to-read load. Pass an
		// empty list for jpegMeta to match the default-construction baseline — null here would cause a
		// downstream NPE inside canBypassEncode's thumbnail probe instead of exercising the
		// missing-bytes gate that this test is meant to exercise.
		CropState state = new CropState();
		state.installLoadedImage(null, null, Format.JPEG, Collections.emptyList(), null, null, null);
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenOutputIsPng()
	{
		// PNG has its own encode path (eXIf chunk metadata, transparent canvas); can't bypass via the
		// JPEG-verbatim shortcut.
		CropState state = jpegStateNoMeta();
		assertFalse(ExportPipeline.canBypassEncode(state, true));
	}

	@Test
	public void rejectsBypassWhenRotationAtOrAboveEpsilon()
	{
		// Any rotation magnitude at or above the renderer epsilon means a real transform; bypass would skip the
		// canvas pass and ship source pixels at their original orientation under metadata that says "rotated".
		CropState state = jpegStateNoMeta();
		state.setRotationDegrees(BitmapUtils.ROTATION_EPSILON);
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenSourceFormatIsPng()
	{
		// Source PNG with output JPEG can't bypass — would ship PNG bytes labeled JPEG. Pass null
		// jpegMeta because PNG sources don't carry JPEG APP segments; CropState.installLoadedImage
		// coerces a null jpegMeta to an empty list (the field's "never null" contract), and the
		// format gate short-circuits before any downstream read of getJpegMeta runs anyway.
		CropState state = new CropState();
		state.installLoadedImage(PNG_HEADER.clone(), null, Format.PNG, null, null, null, null);
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenSourceHasNoPreComputedThumbnail()
	{
		// When the source has no IFD1 thumbnail (screenshot, generated image, minimal-EXIF re-encode),
		// the verbatim-write bypass would preserve the empty-IFD1 state — gate forces re-encode so
		// CropExporter can synthesise a thumbnail. State satisfies every other bypass condition; ONLY
		// the missing-thumbnail gate trips the rejection. Empty jpegMeta is the "JPEG load committed but
		// had no parseable APP segments" baseline equivalent to the default-constructed state.
		CropState state = jpegStateNoMeta();
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	/**
	 * Shorthand for the common case: `jpegStateWithMeta(Collections.emptyList())`. Used by the
	 * bypass-reject tests where the source is a valid JPEG (bytes + format committed) but carries no
	 * segments — equivalent to the default-constructed CropState's empty ArrayList baseline.
	 *
	 * @return JPEG-loaded CropState with `jpegMeta` set to an empty (non-null) list
	 */
	private static CropState jpegStateNoMeta()
	{
		return jpegStateWithMeta(Collections.emptyList());
	}

	/**
	 * Build a CropState populated with the two-byte JPEG SOI header and the supplied jpegMeta segments
	 * via installLoadedImage — the single chokepoint that replaces the previous setSourceFormat /
	 * setOriginalFileBytes / setJpegMeta triple-call. The bytes content is irrelevant to canBypassEncode
	 * (the gate checks `!= null`, not signature); the SOI bytes match what a real JPEG load would commit
	 * so a future test that DOES sniff the bytes won't trip.
	 *
	 * @param jpegMeta segment list (e.g. EXIF with IFD1 thumbnail for the bypass-permits path); pass
	 *                 Collections.emptyList() for the "JPEG with no parseable segments" baseline — null
	 *                 also works (installLoadedImage coerces to empty), but explicit emptyList is the
	 *                 documented test pattern
	 * @return JPEG-loaded CropState with `originalFileBytes`, `sourceFormat`, and `jpegMeta` populated
	 */
	private static CropState jpegStateWithMeta(List<JpegSegment> jpegMeta)
	{
		CropState state = new CropState();
		state.installLoadedImage(JPEG_HEADER.clone(), null, Format.JPEG, jpegMeta, null, null, null);
		return state;
	}

	/**
	 * Build a minimal `jpegMeta` list containing a single EXIF segment with an IFD1 thumbnail entry,
	 * which is what `canBypassEncode` now demands of the source. The thumbnail content itself is a
	 * 10-byte placeholder — the gate checks only that JPEGInterchangeFormat reaches a non-zero offset.
	 *
	 * @return singleton list wrapping one synthetic EXIF segment with an IFD1 thumbnail entry
	 */
	private static List<JpegSegment> metaWithThumbnail()
	{
		JpegSegment exif = ExifPatcher.buildMinimalExifSegment(100, 100, new byte[10]);
		return Collections.singletonList(exif);
	}
}
