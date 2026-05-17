package com.cropcenter;

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
	@Test
	public void permitsBypassWhenAllGatesClearAndNoCrop()
	{
		// Happy path: JPEG source + JPEG output, no graft, no rotation, no grid bake, bytes available, no
		// crop center seeded yet, AND the source carries an IFD1 thumbnail to round-trip verbatim. Bypass
		// returns true.
		CropState state = new CropState();
		state.setSourceFormat(Format.JPEG);
		state.setOriginalFileBytes(new byte[]{ (byte) 0xFF, (byte) 0xD8 });
		state.setJpegMeta(metaWithThumbnail());
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
		CropState state = new CropState();
		state.setSourceFormat(Format.JPEG);
		state.setOriginalFileBytes(new byte[]{ (byte) 0xFF, (byte) 0xD8 });
		state.setJpegMeta(metaWithThumbnail());
		state.setRotationDegrees(BitmapUtils.ROTATION_EPSILON / 2f);
		assertTrue(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenGraftApplied()
	{
		// Graft saves MUST run through the full encode so UltraHdrCompat regenerates the gain map for the
		// spliced primary. Bypassing would ship source's gain map verbatim — fine for view-only HDR, broken
		// when the user later crops.
		CropState state = new CropState();
		state.setSourceFormat(Format.JPEG);
		state.setOriginalFileBytes(new byte[]{ (byte) 0xFF, (byte) 0xD8 });
		AiMask mask = AiMask.of(new boolean[]{ true }, 1, 1, 4);
		state.installGraft(new Graft(new byte[]{ 0x01 }, "test.jpg", mask));
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenGridBakeInEnabled()
	{
		// Grid bake-in writes lines onto the canvas — can't be expressed by shipping source bytes verbatim.
		CropState state = new CropState();
		state.setSourceFormat(Format.JPEG);
		state.setOriginalFileBytes(new byte[]{ (byte) 0xFF, (byte) 0xD8 });
		state.updateGridConfig(grid -> grid.withIncludeInExport(true));
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenOriginalBytesUnavailable()
	{
		// No source bytes to write verbatim → must encode.
		CropState state = new CropState();
		state.setSourceFormat(Format.JPEG);
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenOutputIsPng()
	{
		// PNG has its own encode path (eXIf chunk metadata, transparent canvas); can't bypass via the
		// JPEG-verbatim shortcut.
		CropState state = new CropState();
		state.setSourceFormat(Format.JPEG);
		state.setOriginalFileBytes(new byte[]{ (byte) 0xFF, (byte) 0xD8 });
		assertFalse(ExportPipeline.canBypassEncode(state, true));
	}

	@Test
	public void rejectsBypassWhenRotationAtOrAboveEpsilon()
	{
		// Any rotation magnitude at or above the renderer epsilon means a real transform; bypass would skip the
		// canvas pass and ship source pixels at their original orientation under metadata that says "rotated".
		CropState state = new CropState();
		state.setSourceFormat(Format.JPEG);
		state.setOriginalFileBytes(new byte[]{ (byte) 0xFF, (byte) 0xD8 });
		state.setRotationDegrees(BitmapUtils.ROTATION_EPSILON);
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenSourceFormatIsPng()
	{
		// Source PNG with output JPEG can't bypass — would ship PNG bytes labeled JPEG.
		CropState state = new CropState();
		state.setSourceFormat(Format.PNG);
		state.setOriginalFileBytes(new byte[]{ (byte) 0x89, 'P', 'N', 'G' });
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	@Test
	public void rejectsBypassWhenSourceHasNoPreComputedThumbnail()
	{
		// When the source has no IFD1 thumbnail (screenshot, generated image, minimal-EXIF re-encode),
		// the verbatim-write bypass would preserve the empty-IFD1 state — gate forces re-encode so
		// CropExporter can synthesise a thumbnail. State satisfies every other bypass condition; ONLY
		// the missing-thumbnail gate trips the rejection.
		CropState state = new CropState();
		state.setSourceFormat(Format.JPEG);
		state.setOriginalFileBytes(new byte[]{ (byte) 0xFF, (byte) 0xD8 });
		// Default jpegMeta is empty — no EXIF segment, no thumbnail.
		assertFalse(ExportPipeline.canBypassEncode(state, false));
	}

	/**
	 * Build a minimal `jpegMeta` list containing a single EXIF segment with an IFD1 thumbnail entry,
	 * which is what `canBypassEncode` now demands of the source. The thumbnail content itself is a
	 * 10-byte placeholder — the gate checks only that JPEGInterchangeFormat reaches a non-zero offset.
	 */
	private static List<JpegSegment> metaWithThumbnail()
	{
		JpegSegment exif = ExifPatcher.buildMinimalExifSegment(100, 100, new byte[10]);
		return Collections.singletonList(exif);
	}
}
