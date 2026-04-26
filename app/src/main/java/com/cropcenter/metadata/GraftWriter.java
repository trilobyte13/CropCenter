package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Build a "minimal pixel graft" — a JPEG identical to the original in every byte EXCEPT
 * the primary entropy-coded scan, which is replaced by the external edit's. Used by the
 * "Apply External Edit" feature to recover Samsung Gallery's Revert button for photos
 * round-tripped through Lightroom or similar editors that strip the SEFT trailer.
 *
 * Default output structure: original's complete byte stream with only the primary scan
 * substituted. See SWAP_* constants below to selectively substitute additional segments
 * from the edit; each swap is a bisection toggle to identify which segment substitutions
 * Samsung Gallery's Revert action tolerates. Default (all false) is the only known-good
 * production configuration.
 *
 * Both inputs must be JPEGs and must share the same SOF0 dimensions and EXIF orientation —
 * otherwise the output's metadata (from original) describes different pixels than the SOF
 * (from edit) carries, producing an incoherent decoder result. The caller validates this
 * before invoking; GraftWriter itself trusts the caller and throws IOException only on
 * structural malformation (missing SOI, missing primary EOI, etc.).
 *
 * The minimal "modify only pixel content" approach replaces an earlier "split rule" design
 * that took XMP / ICC / MPF / gain map from the edit; that approach broke Samsung Gallery's
 * Revert action (endless hang on tap). The bisection toggles below let us add individual
 * swaps back one at a time to learn which one Gallery doesn't tolerate.
 */
public final class GraftWriter
{
	private static final String TAG = "GraftWriter";

	// ── Bisection toggles ────────────────────────────────────────────────────────────
	// Confirmed test results (cumulative — once a swap is confirmed safe, it stays true
	// while the next swap is tested):
	//   SWAP_ICC          = true → SAFE. Edit's APP2/ICC profile substituted.
	//   SWAP_XMP          = true → SAFE (with SWAP_ICC also true). Edit's APP1/XMP packet
	//                              substituted; carries Lightroom's hdrgm coefficients,
	//                              edit history, and any custom namespaces.
	//   SWAP_HDR_GAINMAP + SWAP_HDR_MPF (paired, both true together) →
	//                              BREAKS Samsung Gallery's Revert (endless hang on tap).
	//                              Bisecting which half is the actual culprit.
	//   SWAP_HDR_GAINMAP  = true → untested in isolation. Substitutes ONLY edit's gain
	//                              map JPEG; MPF stays original's (its attribute /
	//                              dependent-images fields preserved, only size/offset
	//                              patched for the new layout). Tests whether the gain
	//                              map JPEG content (size, dimensions, channels, internal
	//                              APP segments) is the problem.
	//   SWAP_HDR_MPF      = true → untested in isolation. Substitutes ONLY edit's MPF
	//                              segment; gain map stays original's. Tests whether MPF
	//                              entry attributes / count / endianness is the problem.
	//
	// To bisect: flip ONE constant to true, build, test on device, restore to false if
	// Revert breaks.
	private static final boolean SWAP_EXIF = false;
	private static final boolean SWAP_HDR_GAINMAP = false;
	private static final boolean SWAP_HDR_MPF = false;
	private static final boolean SWAP_ICC = true;
	private static final boolean SWAP_XMP = false;
	// Keep Samsung's vendor APP3-APP15 segments from original — confirmed no rendering
	// effect, and they carry Samsung-specific identity data (sensor hints, scene labels)
	// the user wants preserved.
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
		if (gainMapToWrite != null && haveMpfInOutput)
		{
			boolean patched = MpfPatcher.patch(preSeftBytes, primarySize);
			if (!patched)
			{
				Log.w(TAG, "MPF patch failed; gain-map offset may be incorrect");
			}
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
