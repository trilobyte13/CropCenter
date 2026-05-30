package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Build a "minimal pixel graft" — a JPEG taking the external edit's entropy-coded scan but keeping the
 * original's identity metadata (EXIF, XMP, MPF) and HDR trailer (gain map + SEFT). Powers "Apply External
 * Edit". SEFT is copied verbatim like every identity segment, so the source's Samsung Revert chain survives.
 *
 * Caller-enforced precondition: both inputs MUST be JPEGs AND already share the same stored SOF0 dimensions —
 * else the output's metadata (from original) describes a different pixel count than the SOF (from edit),
 * producing an incoherent decode. GraftWriter checks only the JPEG signature + structural well-formedness;
 * the dimension check + re-rotation lives in EditAligner.align (Photoshop bakes orientation into pixels and
 * writes orientation=1, so a portrait-display source from a landscape-stored Samsung original and the edit
 * match in DISPLAY but not STORED dims — EditAligner re-rotates the edit to the source's stored layout first).
 * Throws IOException only on structural malformation (missing SOI / primary EOI, etc.).
 *
 * Per-segment provenance (see SWAP_* constants):
 *   - from original: APP1/EXIF, APP1/XMP, APP2/ICC, APP2/MPF, gain map, SEFT — identity, color coherence with
 *     the kept gain map, and Samsung Revert pre-flight all need them.
 *   - from edit: DQT, DHT, SOF, SOS+scan, EOI — the AI-edited pixels.
 *
 * ICC stays original-side because the recommended editor (Photoshop, Camera Raw off) keeps source pixel values
 * verbatim, so the edit's pixels are P3-numerical even with no ICC tag. When EditAligner.reorientEdit
 * re-encodes through Bitmap.compress, Skia injects a synthetic sRGB profile describing its container, not the
 * pixels — trusting it would tag P3 pixels as sRGB and misalign the P3-calibrated gain map (washed-out HDR).
 * Keeping source ICC keeps (pixels, ICC, gain map) self-consistent. MPF and gain map likewise stay
 * original-shape — substituting the edit's has been observed to hang Samsung Gallery's Revert pre-flight
 * (Adobe writes a different MPType); only MPF entry offsets get patched for the new scan size.
 */
public final class GraftWriter
{
	private static final String TAG = "GraftWriter";

	// Per-segment substitution toggles. Current production configuration is locked in (see class Javadoc per
	// segment); toggles kept as named constants so a future Samsung firmware change can be tested by flipping one
	// flag without restructuring the splice loop.
	private static final boolean STRIP_VENDOR_APPS = false;
	private static final boolean SWAP_EXIF = false;
	private static final boolean SWAP_HDR_GAINMAP = false;
	private static final boolean SWAP_HDR_MPF = false;
	private static final boolean SWAP_ICC = false;
	private static final boolean SWAP_XMP = false;

	private GraftWriter() {}

	/**
	 * Splice the edit's pixel content into the original's container. Returns the assembled JPEG bytes. Ships
	 * source's gain map verbatim — the AI-region gain-map inpaint runs later in the save pipeline (UltraHdrCompat)
	 * where the gain map is decoded into a Bitmap that preserves source's single-channel format.
	 *
	 * @param original source JPEG providing identity metadata + HDR package + SEFT trailer
	 * @param edit     external-edit JPEG providing the primary scan; must already share
	 *                 original's stored layout (caller's responsibility — see EditAligner.align)
	 * @return assembled JPEG bytes
	 * @throws IOException when either input fails structural validation (not a JPEG, missing primary EOI,
	 *                     malformed segments)
	 */
	public static byte[] graft(byte[] original, byte[] edit) throws IOException
	{
		if (original == null || edit == null)
		{
			throw new IOException("null input");
		}
		if (!isJpeg(original))
		{
			throw new IOException("Original is not a JPEG");
		}
		if (!isJpeg(edit))
		{
			throw new IOException("Edit is not a JPEG");
		}

		List<JpegSegment> origSegments = JpegMetadataExtractor.extract(original);
		// Skip the edit-segment scan entirely when no segment swap is enabled — saves a parse pass on the edit
		// file's APP block in the default-minimal case. SWAP_HDR_GAINMAP is included even though the gain map
		// itself sits in the file trailer (not in the APP-block segment list): editHasMpf / editIsHdr below
		// gate GainMapExtractor.extract on the edit's MPF + XMP-hdrgm signature, both of which are read from
		// editSegments. Without this clause, flipping SWAP_HDR_GAINMAP alone silently no-ops — editSegments
		// stays empty, hasMpf returns false, editIsHdr stays false, and editGainMap is always null.
		List<JpegSegment> editSegments = (SWAP_EXIF || SWAP_HDR_GAINMAP || SWAP_HDR_MPF || SWAP_ICC || SWAP_XMP)
			? JpegMetadataExtractor.extract(edit)
			: Collections.emptyList();
		// HDR gate: MPF AND XMP-hdrgm — see HdrSignature.hasHdrgmInXmp for the rationale. hasMpf is the
		// cheap pre-filter (segment-list scan, near-free); the XMP-only hdrgm scan runs only when MPF
		// is present.
		boolean origHasMpf = hasMpf(origSegments);
		boolean editHasMpf = SWAP_HDR_GAINMAP && hasMpf(editSegments);
		boolean origIsHdr = origHasMpf && HdrSignature.hasHdrgmInXmp(origSegments);
		boolean editIsHdr = editHasMpf && HdrSignature.hasHdrgmInXmp(editSegments);
		byte[] origGainMap = GainMapExtractor.extract(original, origIsHdr);
		byte[] editGainMap = SWAP_HDR_GAINMAP ? GainMapExtractor.extract(edit, editIsHdr) : null;
		byte[] origSeft = SeftExtractor.extract(original, origGainMap != null);

		int editPixelStart = findFirstNonAppNonCom(edit);
		int editPixelEnd = JpegMarkerWalker.findPrimaryEoi(edit, edit.length);
		if (editPixelStart < 0 || editPixelEnd <= editPixelStart)
		{
			throw new IOException("Edit JPEG has no recoverable primary scan");
		}

		// Resolve which segments we will substitute from edit. Each is null if the SWAP flag is off OR the edit
		// doesn't carry that segment. The HDR_GAINMAP and HDR_MPF toggles are independent: gain map can be
		// substituted while MPF stays original's (and vice versa) — MpfPatcher correctly updates whichever MPF
		// ends up in the output for the new gain map's actual size and offset.
		JpegSegment editExifSeg = SWAP_EXIF ? findExif(editSegments) : null;
		JpegSegment editMpfSeg = SWAP_HDR_MPF ? findMpf(editSegments) : null;
		JpegSegment editIccSeg = SWAP_ICC ? findIcc(editSegments) : null;
		JpegSegment editXmpSeg = SWAP_XMP ? findXmp(editSegments) : null;
		byte[] gainMapToWrite = editGainMap != null ? editGainMap : origGainMap;

		// Build through the gain map but BEFORE the SEFT trailer. MpfPatcher computes gainMapSize as
		// (file.length - primarySize), which is only correct when the file ends at the gain map. Patch first,
		// then append SEFT.
		ByteArrayOutputStream out = new ByteArrayOutputStream(
			Math.max(original.length, edit.length) + (origSeft == null ? 0 : origSeft.length));
		out.write(JpegMarker.PREFIX);
		out.write(JpegMarker.SOI);

		// When the assembled output won't carry a gain map (origGainMap == null AND editGainMap == null —
		// i.e., a non-HDR original whose MPF describes a non-HDR multi-picture layout like Samsung "Best
		// Photo" burst / focus-stacked panorama), passing source's MPF verbatim into the output leaves it
		// pointing at secondary images that no longer exist in the grafted file. Strict decoders' MPF
		// pre-flight rejects the orphan, lenient decoders walk past malformed entries. Drop MPF in that
		// case so the output's metadata stays honest about what it actually carries.
		// HDR grafts (gainMapToWrite != null) preserve MPF — MpfPatcher.patch below rewrites the entries
		// for the post-graft primary size and gain-map offset.
		boolean dropOrphanMpf = gainMapToWrite == null;
		boolean wroteEditExif = false;
		boolean wroteEditMpf = false;
		boolean wroteEditIcc = false;
		boolean wroteEditXmp = false;
		for (JpegSegment seg : origSegments)
		{
			if (editExifSeg != null && seg.isExif())
			{
				out.write(editExifSeg.data(), 0, editExifSeg.data().length);
				wroteEditExif = true;
			}
			else if (editMpfSeg != null && seg.isMpf())
			{
				out.write(editMpfSeg.data(), 0, editMpfSeg.data().length);
				wroteEditMpf = true;
			}
			else if (dropOrphanMpf && seg.isMpf())
			{
				Log.d(TAG, "Dropping orphan MPF segment — no gain map / secondary image to anchor");
				continue;
			}
			else if (editIccSeg != null && seg.isIcc())
			{
				out.write(editIccSeg.data(), 0, editIccSeg.data().length);
				wroteEditIcc = true;
			}
			else if (editXmpSeg != null && seg.isXmp())
			{
				out.write(editXmpSeg.data(), 0, editXmpSeg.data().length);
				wroteEditXmp = true;
			}
			else if (STRIP_VENDOR_APPS && isVendorApp(seg))
			{
				// Skip Samsung-specific APP3-APP15 vendor segments. Recognised APP1/APP2 signatures
				// (EXIF, XMP, ICC, MPF) are handled by the branches above and don't reach here; vendor
				// segments at the APP1/APP2 marker level (rare, e.g. Adobe APP1 on legacy files) are
				// also passed through unchanged.
				continue;
			}
			else
			{
				out.write(seg.data(), 0, seg.data().length);
			}
		}
		// If original lacked a segment we wanted to substitute, append edit's at the end of the segment block
		// so downstream parsers find it before the pixel content.
		if (editExifSeg != null && !wroteEditExif)
		{
			out.write(editExifSeg.data(), 0, editExifSeg.data().length);
		}
		if (editMpfSeg != null && !wroteEditMpf)
		{
			out.write(editMpfSeg.data(), 0, editMpfSeg.data().length);
		}
		if (editIccSeg != null && !wroteEditIcc)
		{
			out.write(editIccSeg.data(), 0, editIccSeg.data().length);
		}
		if (editXmpSeg != null && !wroteEditXmp)
		{
			out.write(editXmpSeg.data(), 0, editXmpSeg.data().length);
		}

		int primarySize = out.size() + (editPixelEnd - editPixelStart);
		out.write(edit, editPixelStart, editPixelEnd - editPixelStart);
		if (gainMapToWrite != null)
		{
			out.write(gainMapToWrite, 0, gainMapToWrite.length);
		}
		byte[] preSeftBytes = out.toByteArray();

		// Patch the MPF segment (whichever ended up in the output — edit's substitute or original's verbatim)
		// for the new layout. New primary scan size differs from original's, shifting the gain map's offset in
		// the assembled file. MpfPatcher rewrites entry[0] (primary size) and entry[1] (gain map offset/size)
		// based on the actual primarySize and the gain map's position right after it. Other entry fields
		// (attribute, dependent images) are preserved — that's where the edit's MPF differs from original's
		// when SWAP_HDR_MPF is on.
		// Reflect the dropOrphanMpf decision above — `hasMpf(origSegments)` looks at the source's segment
		// list, but the output skips original's MPF when dropOrphanMpf is true. Keeping haveMpfInOutput
		// honest matters for the MpfPatcher.patch guard below: patching an MPF that isn't in the output
		// would fail-closed inside the patcher rather than fall through to the no-MPF warning branch.
		boolean haveMpfInOutput = (editMpfSeg != null) || (hasMpf(origSegments) && !dropOrphanMpf);
		if (gainMapToWrite != null && !haveMpfInOutput)
		{
			// Degenerate config: gain map written but no MPF segment to anchor it. Strict-MPF decoders
			// (Samsung Gallery, Photos) won't find the gain map and may render the file as plain SDR;
			// lenient decoders that scan for the hdrgm signature will still find it. Cannot trigger under
			// current toggles (SWAP_HDR_GAINMAP=false → gainMapToWrite is original's gain map, which
			// implies original has MPF in any well-formed Ultra HDR JPEG); the warning is a future-proofing
			// tripwire if someone flips SWAP_HDR_GAINMAP=true on a non-Ultra-HDR original.
			Log.w(TAG, "Gain map written but no MPF segment to anchor it; HDR may "
				+ "degrade in strict-MPF decoders");
		}
		else if (gainMapToWrite != null && !MpfPatcher.patch(preSeftBytes, primarySize))
		{
			throw new IOException("MPF patch failed: cannot anchor "
				+ gainMapToWrite.length + "-byte gain map at primary offset " + primarySize
				+ " (MPF segment present but malformed?)");
		}

		byte[] result;
		if (origSeft == null)
		{
			result = preSeftBytes;
		}
		else
		{
			result = new byte[preSeftBytes.length + origSeft.length];
			System.arraycopy(preSeftBytes, 0, result, 0, preSeftBytes.length);
			System.arraycopy(origSeft, 0, result, preSeftBytes.length, origSeft.length);
		}

		Log.d(TAG, "Graft (swapExif=" + (editExifSeg != null) + " swapGainmap=" + (editGainMap != null)
			+ " swapMpf=" + (editMpfSeg != null) + " swapIcc=" + (editIccSeg != null)
			+ " swapXmp=" + (editXmpSeg != null)
			+ " stripVendor=" + STRIP_VENDOR_APPS + ") produced " + result.length
			+ " bytes (primary=" + primarySize
			+ ", gainMap=" + (gainMapToWrite == null ? 0 : gainMapToWrite.length)
			+ ", origSeft=" + (origSeft == null ? 0 : origSeft.length) + ")");
		return result;
	}

	private static JpegSegment findExif(List<JpegSegment> segments)
	{
		return segments.stream().filter(JpegSegment::isExif).findFirst().orElse(null);
	}

	/**
	 * Find the byte offset where the JPEG's primary pixel content begins — i.e., the first non-APP, non-COM,
	 * non-standalone marker after SOI. Typically this is the first DQT (FF DB) but could be DHT (FF C4) or SOF (FF
	 * C0) depending on encoder ordering.
	 *
	 * @param file full JPEG byte array
	 * @return offset of the first pixel-content marker, or -1 when none is found before SOS/EOI (file has only
	 *         APP/COM segments — not a valid JPEG)
	 */
	private static int findFirstNonAppNonCom(byte[] file)
	{
		int off = 2; // skip SOI
		while (off < file.length - 3)
		{
			if ((file[off] & 0xFF) != 0xFF)
			{
				return -1;
			}
			int markerByteOff = JpegMarkerWalker.skipFillBytes(file, off, file.length);
			if (markerByteOff < 0)
			{
				return -1;
			}
			int marker = file[markerByteOff] & 0xFF;
			int afterMarker = markerByteOff + 1;
			if (marker == JpegMarker.EOI || marker == JpegMarker.SOS)
			{
				// EOI (impossible — we'd have only APP/COM segments) or SOS without any preceding
				// DQT/DHT/SOF — malformed, can't proceed.
				return -1;
			}
			if (marker == JpegMarker.STUFFING || marker == JpegMarker.TEM
				|| (marker >= JpegMarker.RST_FIRST && marker <= JpegMarker.RST_LAST))
			{
				off = afterMarker;
				continue;
			}
			boolean isAppOrCom = (marker >= JpegMarker.APP0 && marker <= JpegMarker.APP_LAST)
				|| marker == JpegMarker.COM;
			if (!isAppOrCom)
			{
				// Return the leading 0xFF (including any fill bytes) so the caller's splice keeps the
				// fill bytes with the marker they precede — they still belong with "the next marker".
				return off;
			}
			if (afterMarker + 2 > file.length)
			{
				return -1;
			}
			int segLen = ByteBufferUtils.readU16BE(file, afterMarker);
			if (segLen < 2)
			{
				return -1;
			}
			// Wrap-overflow guard — same defensive pattern as JpegMarkerWalker.findPrimaryEoi. With segLen
			// near 65535 and afterMarker near MAX_INT, the addition would wrap negative and the next loop
			// iteration would index into negative offsets.
			long next = (long) afterMarker + segLen;
			if (next > file.length || next > Integer.MAX_VALUE)
			{
				return -1;
			}
			off = (int) next;
		}
		return -1;
	}

	private static JpegSegment findIcc(List<JpegSegment> segments)
	{
		return segments.stream().filter(JpegSegment::isIcc).findFirst().orElse(null);
	}

	private static JpegSegment findMpf(List<JpegSegment> segments)
	{
		return segments.stream().filter(JpegSegment::isMpf).findFirst().orElse(null);
	}

	private static JpegSegment findXmp(List<JpegSegment> segments)
	{
		return segments.stream().filter(JpegSegment::isXmp).findFirst().orElse(null);
	}

	private static boolean hasMpf(List<JpegSegment> segs)
	{
		return segs.stream().anyMatch(JpegSegment::isMpf);
	}

	private static boolean isJpeg(byte[] file)
	{
		return file.length >= 4 && (file[0] & 0xFF) == JpegMarker.PREFIX
			&& (file[1] & 0xFF) == JpegMarker.SOI;
	}

	/**
	 * Vendor APP segment = APP3 through APP15. APP0 carries JFIF / JFXX (treated as identity-side, kept),
	 * APP1 carries EXIF or XMP, APP2 carries ICC or MPF — those are handled by the dedicated SWAP branches
	 * in graft().
	 *
	 * @param seg JPEG segment to classify
	 * @return true when seg's marker is in the APP3..APP15 vendor range
	 */
	private static boolean isVendorApp(JpegSegment seg)
	{
		int marker = seg.marker();
		return marker >= JpegMarker.APP3 && marker <= JpegMarker.APP_LAST;
	}
}
