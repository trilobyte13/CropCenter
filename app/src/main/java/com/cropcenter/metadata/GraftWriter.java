package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Build a "minimal pixel graft" — a JPEG that takes the external edit's primary
 * entropy-coded scan but keeps the original's identity metadata (EXIF, XMP, MPF) and HDR
 * trailer (gain map + SEFT). Used by the "Apply External Edit" feature to round-trip a
 * Photoshop Generative Fill / Generative Remove edit through CropCenter while preserving
 * Samsung Gallery's Revert button.
 *
 * Both inputs must be JPEGs and must share the same SOF0 dimensions and EXIF orientation —
 * otherwise the output's metadata (from original) describes different pixels than the SOF
 * (from edit) carries, producing an incoherent decoder result. The caller validates this
 * before invoking; GraftWriter itself trusts the caller and throws IOException only on
 * structural malformation (missing SOI, missing primary EOI, etc.).
 *
 * Per-segment provenance (see SWAP_* constants):
 *   - APP1/EXIF, APP1/XMP, APP2/ICC, APP2/MPF, gain map, SEFT trailer: from original —
 *     identity, color-space coherence with the kept gain map, and Samsung Revert pre-
 *     flight all need them.
 *   - DQT, DHT, SOF, SOS+scan, EOI: from edit — the AI-edited pixels themselves.
 *
 * Why ICC stays original-side: the recommended editor (Photoshop with Camera Raw
 * disabled — see GraftController.start) preserves the source's pixel values verbatim
 * outside the AI fill, so the edit's pixels are P3-numerical even though Photoshop
 * doesn't write any ICC tag. When GraftController.reorientEdit re-encodes the edit
 * through Bitmap.compress to fix a stored-layout mismatch, Skia injects its own
 * synthetic 456-byte sRGB profile — that ICC describes Skia's container, not the
 * actual pixel encoding. Trusting it would tag the spliced output as sRGB while the
 * pixels remain P3-numerical and the gain map is calibrated for P3, producing washed-
 * out HDR composition (sRGB-tagged pixels read as smaller-gamut, gain map boost
 * misaligned). Keeping the source's ICC keeps the encoding triplet (pixels, ICC, gain
 * map) self-consistent.
 *
 * Substituting the edit's MPF segment alone has been observed to hang Samsung Gallery's
 * Revert pre-flight (Adobe writes a different MPType for the gain-map entry); same for
 * substituting the edit's gain map. Both stay original-shape; only MPF entry offsets get
 * patched for the new primary scan size.
 */
public final class GraftWriter
{
	private static final String TAG = "GraftWriter";

	// Per-segment substitution toggles. Current production configuration is locked in
	// — see the class Javadoc for rationale per segment. Toggles remain as named
	// constants rather than baked-in branches so a future Samsung firmware change can
	// be tested by flipping one flag without restructuring the splice loop.
	private static final boolean SWAP_EXIF = false;
	private static final boolean SWAP_HDR_GAINMAP = false;
	private static final boolean SWAP_HDR_MPF = false;
	private static final boolean SWAP_ICC = false;
	private static final boolean SWAP_XMP = false;
	private static final boolean STRIP_VENDOR_APPS = false;

	private GraftWriter() {}

	/**
	 * Splice the edit's pixel content into the original's container. Returns the
	 * assembled JPEG bytes.
	 *
	 * @throws IOException when either input fails structural validation (not a JPEG,
	 *                     missing primary EOI, malformed segments).
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
		// Skip the edit-segment scan entirely when no segment swap is enabled — saves a
		// parse pass on the edit file's APP block in the default-minimal case.
		List<JpegSegment> editSegments = (SWAP_EXIF || SWAP_HDR_MPF || SWAP_ICC || SWAP_XMP)
			? JpegMetadataExtractor.extract(edit)
			: Collections.emptyList();
		byte[] origGainMap = GainMapExtractor.extract(original);
		byte[] editGainMap = SWAP_HDR_GAINMAP ? GainMapExtractor.extract(edit) : null;
		byte[] origSeft = SeftExtractor.extract(original);

		int editPixelStart = findFirstNonAppNonCom(edit);
		int editPixelEnd = findPrimaryEoi(edit);
		if (editPixelStart < 0 || editPixelEnd <= editPixelStart)
		{
			throw new IOException("Edit JPEG has no recoverable primary scan");
		}

		// Resolve which segments we will substitute from edit. Each is null if the
		// SWAP flag is off OR the edit doesn't carry that segment. The HDR_GAINMAP and
		// HDR_MPF toggles are independent: gain map can be substituted while MPF stays
		// original's (and vice versa) — MpfPatcher correctly updates whichever MPF ends
		// up in the output for the new gain map's actual size and offset.
		JpegSegment editExifSeg = SWAP_EXIF ? findExif(editSegments) : null;
		JpegSegment editMpfSeg = SWAP_HDR_MPF ? findMpf(editSegments) : null;
		JpegSegment editIccSeg = SWAP_ICC ? findIcc(editSegments) : null;
		JpegSegment editXmpSeg = SWAP_XMP ? findXmp(editSegments) : null;
		byte[] gainMapToWrite = editGainMap != null ? editGainMap : origGainMap;

		// Build through the gain map but BEFORE the SEFT trailer. MpfPatcher computes
		// gainMapSize as (file.length - primarySize), which is only correct when the file
		// ends at the gain map. Patch first, then append SEFT.
		ByteArrayOutputStream out = new ByteArrayOutputStream(
			Math.max(original.length, edit.length)
				+ (origSeft == null ? 0 : origSeft.length));
		out.write(0xFF);
		out.write(0xD8);

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
				// Skip Samsung-specific APP3-APP15 vendor segments. Recognised APP1/APP2
				// signatures (EXIF, XMP, ICC, MPF) are handled by the branches above and
				// don't reach here; vendor segments at the APP1/APP2 marker level (rare,
				// e.g. Adobe APP1 on legacy files) are also passed through unchanged.
				continue;
			}
			else
			{
				out.write(seg.data(), 0, seg.data().length);
			}
		}
		// If original lacked a segment we wanted to substitute, append edit's at the end
		// of the segment block so downstream parsers find it before the pixel content.
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

		// Patch the MPF segment (whichever ended up in the output — edit's substitute or
		// original's verbatim) for the new layout. New primary scan size differs from
		// original's, shifting the gain map's offset in the assembled file. MpfPatcher
		// rewrites entry[0] (primary size) and entry[1] (gain map offset/size) based on
		// the actual primarySize and the gain map's position right after it. Other entry
		// fields (attribute, dependent images) are preserved — that's where the edit's
		// MPF differs from original's when SWAP_HDR_MPF is on.
		boolean haveMpfInOutput = (editMpfSeg != null) || hasMpf(origSegments);
		if (gainMapToWrite != null && !haveMpfInOutput)
		{
			// Degenerate config: gain map written but no MPF segment to anchor it.
			// Strict-MPF decoders (Samsung Gallery, Photos) won't find the gain map and
			// may render the file as plain SDR; lenient decoders that scan for the
			// hdrgm signature will still find it. Cannot trigger under current toggles
			// (SWAP_HDR_GAINMAP=false → gainMapToWrite is original's gain map, which
			// implies original has MPF in any well-formed Ultra HDR JPEG); the warning
			// is a future-proofing tripwire if someone flips SWAP_HDR_GAINMAP=true on
			// a non-Ultra-HDR original.
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

		Log.d(TAG, "Graft (swapExif=" + (editExifSeg != null)
			+ " swapGainmap=" + (editGainMap != null)
			+ " swapMpf=" + (editMpfSeg != null)
			+ " swapIcc=" + (editIccSeg != null)
			+ " swapXmp=" + (editXmpSeg != null)
			+ " stripVendor=" + STRIP_VENDOR_APPS + ") produced " + result.length
			+ " bytes (primary=" + primarySize
			+ ", gainMap=" + (gainMapToWrite == null ? 0 : gainMapToWrite.length)
			+ ", origSeft=" + (origSeft == null ? 0 : origSeft.length) + ")");
		return result;
	}

	private static JpegSegment findExif(List<JpegSegment> segments)
	{
		for (JpegSegment seg : segments)
		{
			if (seg.isExif())
			{
				return seg;
			}
		}
		return null;
	}

	/**
	 * Find the byte offset where the JPEG's primary pixel content begins — i.e., the first
	 * non-APP, non-COM, non-standalone marker after SOI. Typically this is the first DQT
	 * (FF DB) but could be DHT (FF C4) or SOF (FF C0) depending on encoder ordering.
	 *
	 * Returns -1 if no such marker is found before SOS or EOI (which would mean the file has
	 * only APP/COM segments — not a valid JPEG).
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
			int marker = file[off + 1] & 0xFF;
			if (marker == 0xD9 || marker == 0xDA)
			{
				// EOI (impossible — we'd have only APP/COM segments) or SOS without any
				// preceding DQT/DHT/SOF — malformed, can't proceed.
				return -1;
			}
			if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7))
			{
				off += 2;
				continue;
			}
			boolean isAppOrCom = (marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE;
			if (!isAppOrCom)
			{
				return off;
			}
			int segLen = ByteBufferUtils.readU16BE(file, off + 2);
			if (segLen < 2)
			{
				return -1;
			}
			off += 2 + segLen;
		}
		return -1;
	}

	private static JpegSegment findIcc(List<JpegSegment> segments)
	{
		for (JpegSegment seg : segments)
		{
			if (seg.isIcc())
			{
				return seg;
			}
		}
		return null;
	}

	private static JpegSegment findMpf(List<JpegSegment> segments)
	{
		for (JpegSegment seg : segments)
		{
			if (seg.isMpf())
			{
				return seg;
			}
		}
		return null;
	}

	/**
	 * Find the byte offset just past the primary JPEG's EOI (FF D9). Walks markers and
	 * scans SOS entropy-coded data including byte-stuffing (FF 00) and restart markers
	 * (FF D0..D7). Handles progressive JPEGs with multiple SOS segments. Returns -1 if no
	 * clean EOI is found.
	 *
	 * Bounded by file length rather than by SEFT detection — the caller is expected to be
	 * passing an external edit which generally doesn't have a SEFT trailer; if it does, the
	 * trailer-bounding logic is moot because we drop everything past the primary EOI anyway.
	 */
	private static int findPrimaryEoi(byte[] file)
	{
		int off = 2;
		while (off < file.length - 1)
		{
			if ((file[off] & 0xFF) != 0xFF)
			{
				off++;
				continue;
			}
			int marker = file[off + 1] & 0xFF;
			if (marker == 0xD9)
			{
				return off + 2;
			}
			if (marker == 0xDA)
			{
				int sosLen = ByteBufferUtils.readU16BE(file, off + 2);
				int scanOff = off + 2 + sosLen;
				while (scanOff < file.length - 1)
				{
					if ((file[scanOff] & 0xFF) != 0xFF)
					{
						scanOff++;
						continue;
					}
					int next = file[scanOff + 1] & 0xFF;
					if (next == 0xD9)
					{
						return scanOff + 2;
					}
					if (next == 0x00 || (next >= 0xD0 && next <= 0xD7))
					{
						scanOff += 2;
						continue;
					}
					// Real marker — fall through to outer loop for multi-scan progressive.
					break;
				}
				off = scanOff;
				continue;
			}
			if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7))
			{
				off += 2;
				continue;
			}
			if (off + 3 < file.length)
			{
				int segLen = ByteBufferUtils.readU16BE(file, off + 2);
				off += 2 + segLen;
			}
			else
			{
				off += 2;
			}
		}
		return -1;
	}

	private static JpegSegment findXmp(List<JpegSegment> segments)
	{
		for (JpegSegment seg : segments)
		{
			if (seg.isXmp())
			{
				return seg;
			}
		}
		return null;
	}

	private static boolean hasMpf(List<JpegSegment> segs)
	{
		for (JpegSegment seg : segs)
		{
			if (seg.isMpf())
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isJpeg(byte[] file)
	{
		return file.length >= 4
			&& (file[0] & 0xFF) == 0xFF && (file[1] & 0xFF) == 0xD8;
	}

	/**
	 * Vendor APP segment = APP3 through APP15 (markers 0xE3..0xEF). APP0 carries JFIF /
	 * JFXX (treated as identity-side, kept), APP1 carries EXIF or XMP, APP2 carries ICC
	 * or MPF — those are handled by the dedicated SWAP branches in graft().
	 */
	private static boolean isVendorApp(JpegSegment seg)
	{
		int marker = seg.marker();
		return marker >= 0xE3 && marker <= 0xEF;
	}
}
