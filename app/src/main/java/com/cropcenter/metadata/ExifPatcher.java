package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Patches EXIF metadata segments in-place:
 *  - Sets Orientation tag to 1 (Normal)
 *  - Updates ImageWidth, ImageLength, PixelXDimension, PixelYDimension
 *  - Replaces thumbnail JPEG with new one (or strips it — see `patch`'s thumbnail param)
 *  - Preserves all other tags and data verbatim
 */
public final class ExifPatcher
{
	/**
	 * IFD1 thumbnail-tag scan result from findThumbnailTags. Named accessors keep the entry-offset-vs-value
	 * pairing a compile-time contract — a thumbOffTag-vs-oldThumbOff transposition in the TIFF pointer math
	 * would be a silent wrong-offset bug with bare int[] indexing.
	 *
	 * @param thumbOffTag absolute offset of the JPEGInterchangeFormat entry within the segment
	 * @param thumbLenTag absolute offset of the JPEGInterchangeFormatLength entry within the segment
	 * @param oldThumbOff recorded thumbnail offset (relative to the TIFF header); non-zero by construction
	 * @param oldThumbLen recorded thumbnail length in bytes
	 */
	private record ThumbnailTags(int thumbOffTag, int thumbLenTag, int oldThumbOff, int oldThumbLen) {}

	/**
	 * IFD0 → next-IFD-pointer → IFD1 walk result from locateIfd1. Raw facts only — the recorded IFD1 offset is
	 * reported as-is so each caller keeps its own no-IFD1 / out-of-bounds fallback.
	 *
	 * @param nextIfdPointer absolute offset of IFD0's next-IFD pointer slot (4 readable bytes guaranteed)
	 * @param ifd1Rel        u32 value of that slot — the IFD1 offset relative to the TIFF header; 0 means the
	 *                       source records no IFD1
	 * @param ifd1Abs        absolute offset of IFD1 within the segment, or -1 when ifd1Rel is 0 or the
	 *                       recorded offset leaves no room for IFD1's entry count inside the segment
	 */
	private record Ifd1Chain(int nextIfdPointer, long ifd1Rel, int ifd1Abs) {}

	/**
	 * One IFD1 thumbnail-tag entry from scanIfd1ThumbTags. rawValue is the unvalidated u32 read — each caller
	 * applies its own validity discipline (see scanIfd1ThumbTags).
	 *
	 * @param tag         TiffTag.JPEG_INTERCHANGE_FORMAT or TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH
	 * @param entryOffset absolute offset of the 12-byte IFD entry within the segment
	 * @param rawValue    the entry's u32 value field as read, in [0, 0xFFFFFFFF]
	 */
	private record Ifd1ThumbEntry(int tag, int entryOffset, long rawValue) {}

	private static final String TAG = "ExifPatcher";

	// Sentinel for `patch`'s `thumbnail` param meaning "strip the IFD1 thumbnail from the segment so the saved file
	// carries no embedded preview at all". Use this instead of `null` (which preserves the source's IFD1 thumbnail)
	// when fresh thumbnail generation fails — without it, the saved cropped / rotated / grafted file would carry
	// the SOURCE's pre-edit thumbnail, leaking pre-crop content via any EXIF-thumbnail-aware viewer.
	public static final byte[] STRIP_IFD1_THUMBNAIL = new byte[0];

	// Fallback budget when maxThumbnailBytes can't measure the EXIF segment (no EXIF present, malformed byte-order,
	// IFD-offset math fails). 20 KB is conservative: most camera thumbnails are 5-15 KB, and the sole caller
	// (CropExporter.patchPngExifTiff) uses the returned budget only as a strip-vs-splice gate — an over-budget
	// fresh thumbnail is forced to STRIP_IFD1_THUMBNAIL, and the splice path re-enforces the APP1 cap on the
	// rebuilt segment regardless.
	private static final int DEFAULT_THUMB_BUDGET = 20_000;
	// Byte size of a synthesised IFD1 header (entry count + 3 × 12-byte entries + 4-byte next-IFD pointer = 2 + 36
	// + 4 = 42). Used both as a budget-estimate overhead in maxThumbnailBytes and as the exact slot allocation in
	// patchedNonThumbBytes' IFD1 builder. Centralised so the two callers can't drift on a future entry-count change
	// (e.g., adding a fourth IFD1 entry would bump this to 54; the constant marks every site that depends on the
	// count).
	private static final int IFD1_HEADER_BYTES = 42;
	private static final int TIFF_HEADER_OFFSET = 10; // bytes from start of APP1 data to the TIFF header

	private ExifPatcher() {}

	/**
	 * Build a complete EXIF APP1 segment from scratch — IFD0 (ImageWidth=newW, ImageLength=newH, Orientation=1) +
	 * IFD1 (Compression=JPEG, JPEGInterchangeFormat, JPEGInterchangeFormatLength) + the supplied thumbnail bytes.
	 * Always little-endian. Returns empty when the resulting segment would exceed the APP1 cap (65,535 bytes) —
	 * caller must drop the thumbnail in that case rather than ship an over-cap segment.
	 *
	 * Used by `patch` when the source carried no EXIF segment at all, so the freshly-generated IFD1 thumbnail still
	 * makes it into the saved file.
	 *
	 * @param newW      image width to write into IFD0's ImageWidth entry
	 * @param newH      image height to write into IFD0's ImageLength entry
	 * @param thumbnail JPEG-compressed thumbnail bytes; caller must pass non-null, non-empty
	 * @return synthesised APP1 JpegSegment, or empty when the segment would exceed the APP1 cap
	 */
	public static Optional<JpegSegment> buildMinimalExifSegment(int newW, int newH, byte[] thumbnail)
	{
		// Layout (little-endian throughout the TIFF body; segLen is big-endian per JPEG marker spec):
		//   [0..1]   FF E1                   APP1 marker
		//   [2..3]   segLen (u16 BE)
		//   [4..9]   "Exif\0\0"              EXIF identifier
		//   [10..13] II*\0                   TIFF byte-order + magic 42
		//   [14..17] u32 IFD0 offset = 8     (relative to TIFF start)
		//   [18..59] IFD0 (count=3, 3 entries ascending per TIFF 6.0: IMAGE_WIDTH/IMAGE_LENGTH/
		//            ORIENTATION, next-IFD = IFD1)
		//   [60..101] IFD1 (count=3 via buildFreshIfd1Header, next-IFD = 0)
		//   [102..]  Thumbnail bytes
		int ifd0SizeBytes = 2 + 3 * 12 + 4;
		int ifd1SizeBytes = 2 + 3 * 12 + 4;
		int ifd0TiffOff = 8;
		int ifd1TiffOff = ifd0TiffOff + ifd0SizeBytes;
		int thumbTiffOff = ifd1TiffOff + ifd1SizeBytes;
		int tiffSize = thumbTiffOff + thumbnail.length;
		int dataPayloadSize = 6 + tiffSize;
		int segLenValue = 2 + dataPayloadSize;
		if (segLenValue > JpegSegment.MAX_SEGMENT_BYTES)
		{
			Log.w(TAG, "Synthesised EXIF segment would exceed APP1 cap: " + segLenValue
				+ " > " + JpegSegment.MAX_SEGMENT_BYTES + "; dropping fresh thumbnail");
			return Optional.empty();
		}
		byte[] data = new byte[2 + segLenValue];
		data[0] = (byte) JpegMarker.PREFIX;
		data[1] = (byte) JpegMarker.APP1;
		data[2] = (byte) ((segLenValue >> 8) & 0xFF);
		data[3] = (byte) (segLenValue & 0xFF);
		data[4] = 'E';
		data[5] = 'x';
		data[6] = 'i';
		data[7] = 'f';
		data[8] = 0;
		data[9] = 0;

		int tiffStart = TIFF_HEADER_OFFSET;
		data[tiffStart] = 'I';
		data[tiffStart + 1] = 'I';
		data[tiffStart + 2] = (byte) 42;
		data[tiffStart + 3] = 0;
		ByteBufferUtils.writeU32(data, tiffStart + 4, ifd0TiffOff, true);

		int ifd0Abs = tiffStart + ifd0TiffOff;
		// TIFF 6.0 requires IFD entries sorted ascending by tag — strict / binary-searching parsers (exiftool
		// warns "entries out of sequence") may otherwise miss the tags.
		ByteBufferUtils.writeU16(data, ifd0Abs, 3, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 2, TiffTag.IMAGE_WIDTH, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 4, TiffTag.TYPE_LONG, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 6, 1, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 10, newW, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 14, TiffTag.IMAGE_LENGTH, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 16, TiffTag.TYPE_LONG, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 18, 1, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 22, newH, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 26, TiffTag.ORIENTATION, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 28, TiffTag.TYPE_SHORT, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 30, 1, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 34, 1, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 38, ifd1TiffOff, true);

		int ifd1Abs = tiffStart + ifd1TiffOff;
		byte[] ifd1Header = buildFreshIfd1Header(ifd1TiffOff, thumbnail.length, true);
		System.arraycopy(ifd1Header, 0, data, ifd1Abs, ifd1Header.length);

		System.arraycopy(thumbnail, 0, data, tiffStart + thumbTiffOff, thumbnail.length);

		Log.d(TAG, "Synthesised EXIF segment: " + (2 + segLenValue) + " bytes (thumb "
			+ thumbnail.length + " B)");
		return Optional.of(new JpegSegment(JpegMarker.APP1, data));
	}

	/**
	 * Detect whether any segment in `segments` carries an EXIF IFD1 thumbnail reachable through the spec parse
	 * chain: EXIF APP1 → TIFF header → IFD0 → next-IFD pointer != 0 → IFD1 → JPEGInterchangeFormat tag (0x0201)
	 * with a non-zero offset value. Used by `ExportPipeline.canBypassEncode` to disqualify the verbatim-write
	 * bypass when the source has no pre-computed thumbnail — forcing the full re-encode path so `CropExporter` can
	 * synthesise one (user-reported bug: bypass saves of screenshots / generated images / minimal-EXIF sources
	 * would preserve the source's empty-IFD1 state instead of adding a fresh thumbnail).
	 *
	 * Pure parse — does not modify segments. Returns false on any structural rejection (malformed byte order,
	 * out-of-bounds IFD offsets, IFD1 missing JPEGInterchangeFormat) so the caller treats "ambiguous" as "no
	 * thumbnail" and routes through the re-encode path that handles all those cases via the synthesise /
	 * append-fresh fallbacks in `replaceThumbnail`.
	 *
	 * @param segments JPEG metadata as captured by JpegMetadataExtractor; null treated as empty
	 * @return true when at least one EXIF segment carries a parse-reachable IFD1 thumbnail
	 */
	public static boolean hasIfd1Thumbnail(List<JpegSegment> segments)
	{
		if (segments == null)
		{
			return false;
		}
		// Outer try/catch defaults to false (bypass disabled = safe) on any unexpected parse failure — the
		// caller (`ExportPipeline.canBypassEncode`) runs on the UI thread for every Save tap, so an uncaught
		// exception here would crash save-prep. Treat "I don't understand this EXIF" as "no thumbnail,
		// re-encode".
		try
		{
			for (JpegSegment seg : segments)
			{
				if (!seg.isExif())
				{
					continue;
				}
				byte[] data = seg.data();
				Optional<Boolean> byteOrder = tiffByteOrder(data);
				if (byteOrder.isEmpty())
				{
					continue;
				}
				boolean isLittleEndian = byteOrder.orElseThrow();
				// TIFF magic = 42 (0x002A). A coincidental II/MM match without the magic means the
				// chunk isn't actually TIFF; without this check a malformed APP1 with valid II plus
				// garbage at TIFF+2 would read IFD0 offset from random bytes.
				int magic = ByteBufferUtils.readU16(data, TIFF_HEADER_OFFSET + 2, isLittleEndian);
				if (magic != TiffTag.MAGIC)
				{
					continue;
				}
				Optional<Ifd1Chain> maybeChain = locateIfd1(data, TIFF_HEADER_OFFSET, isLittleEndian);
				if (maybeChain.isEmpty() || maybeChain.orElseThrow().ifd1Abs() < 0)
				{
					continue;
				}
				int ifd1 = maybeChain.orElseThrow().ifd1Abs();
				for (Ifd1ThumbEntry entry : scanIfd1ThumbTags(data, ifd1, isLittleEndian))
				{
					// Validity discipline of this predicate: a recorded offset is a thumbnail iff
					// it is non-zero and fits int.
					if (entry.tag() == TiffTag.JPEG_INTERCHANGE_FORMAT
						&& entry.rawValue() > 0 && entry.rawValue() <= Integer.MAX_VALUE)
					{
						return true;
					}
				}
			}
			return false;
		}
		catch (RuntimeException ignored)
		{
			// Defensive default for any parse failure not caught by the explicit bounds checks above — e.g.
			// an EXIF segment whose IFD1 entry count or stride wraps int arithmetic and slips past a check.
			// Returning false routes the caller through the re-encode path which synthesises a fresh
			// thumbnail rather than verbatim-shipping the source's malformed bytes.
			return false;
		}
	}

	/**
	 * Estimate max thumbnail bytes that will fit in the EXIF APP1 segment. Measures the EXIF size excluding the
	 * existing thumbnail, then returns the remaining space within the 65535-byte APP1 limit. On the splice shape
	 * (both IFD1 thumbnail tags present with a non-zero offset) with trailing data after the thumbnail (Samsung
	 * MakerNote shape), the result is additionally clamped at the old thumbnail's length because the padded
	 * splice cannot widen the slot — matching patchedNonThumbBytes' trailing-bytes constraint so the two budget
	 * predictors agree on every source shape, including an incomplete IFD1 that patch() routes through append.
	 *
	 * @param segments JPEG metadata as captured by JpegMetadataExtractor
	 * @return remaining APP1 budget after EXIF overhead (clamped at the old thumbnail length when trailing
	 *         data follows it), or DEFAULT_THUMB_BUDGET when EXIF is missing / unparseable / the byte-order
	 *         field is malformed
	 */
	public static int maxThumbnailBytes(List<JpegSegment> segments)
	{
		for (JpegSegment seg : segments)
		{
			if (!seg.isExif())
			{
				continue;
			}
			byte[] data = seg.data();
			// Validate byte-order (II/MM, not "IM"/"MI") and TIFF magic = 42 before reading anything else —
			// a malformed APP1 with garbage at TIFF+0..3 would otherwise return a budget from random bytes.
			Optional<Boolean> byteOrder = tiffByteOrder(data);
			if (byteOrder.isEmpty())
			{
				continue;
			}
			boolean isLittleEndian = byteOrder.orElseThrow();
			if (ByteBufferUtils.readU16(data, TIFF_HEADER_OFFSET + 2, isLittleEndian) != TiffTag.MAGIC)
			{
				continue;
			}

			// IFD0 → IFD1 walk to locate the existing thumbnail entry. On any parse failure we fall
			// back to DEFAULT_THUMB_BUDGET rather than zero — corrupt-IFD source EXIF is still
			// preserve-worthy at load / save round-trip, and a non-zero budget keeps the embedded-thumbnail
			// injection from silently dropping just because we couldn't measure it.
			Optional<Ifd1Chain> maybeChain = locateIfd1(data, TIFF_HEADER_OFFSET, isLittleEndian);
			if (maybeChain.isEmpty())
			{
				return DEFAULT_THUMB_BUDGET;
			}
			Ifd1Chain chain = maybeChain.orElseThrow();
			if (chain.ifd1Rel() == 0)
			{
				// No IFD1: EXIF overhead = current segment + new IFD1 header we'd add. Clamp at 0 — if
				// the current segment alone nearly fills the APP1 budget, there's no room for a
				// thumbnail and we should say so honestly rather than return a negative that relies on
				// the caller to clamp.
				return Math.max(0, JpegSegment.MAX_SEGMENT_BYTES - (data.length + IFD1_HEADER_BYTES));
			}
			if (chain.ifd1Abs() < 0)
			{
				return DEFAULT_THUMB_BUDGET;
			}
			boolean hasFormatTag = false;
			boolean hasLengthTag = false;
			int oldThumbLen = 0;
			int oldThumbOff = 0;
			for (Ifd1ThumbEntry entry : scanIfd1ThumbTags(data, chain.ifd1Abs(), isLittleEndian))
			{
				// Validity discipline of this scan: offsets count only when non-zero and int-sized;
				// the raw length is taken as-is and sanity-clamped after the loop. Tag presence is
				// recorded regardless of value validity, matching patchedNonThumbBytes.
				if (entry.tag() == TiffTag.JPEG_INTERCHANGE_FORMAT)
				{
					hasFormatTag = true;
					if (entry.rawValue() > 0 && entry.rawValue() <= Integer.MAX_VALUE)
					{
						oldThumbOff = (int) entry.rawValue();
					}
				}
				else
				{
					hasLengthTag = true;
					oldThumbLen = (int) entry.rawValue();
				}
			}
			// Sanity-clamp: corrupt EXIF can report a thumbnail length beyond the segment itself (or
			// negative after the u32→int cast). Either case produces a negative exifOverhead which inflates
			// the returned budget above JpegSegment.MAX_SEGMENT_BYTES — downstream writers then overflow
			// the 65535-byte APP1 cap. Clamp to [0, data.length].
			if (oldThumbLen < 0 || oldThumbLen > data.length)
			{
				oldThumbLen = 0;
			}
			// Available = JpegSegment.MAX_SEGMENT_BYTES - (current segment size - old thumbnail size)
			int exifOverhead = data.length - oldThumbLen;
			int budget = Math.max(0, JpegSegment.MAX_SEGMENT_BYTES - exifOverhead);
			// Trailing-data clamp mirroring patchedNonThumbBytes: when bytes follow the IFD1 thumbnail
			// (Samsung MakerNote / SubIFD value blocks), the splice can only accept new thumbnails <=
			// oldThumbLen — anything larger would shift the trailing bytes and corrupt TIFF offsets
			// referencing them. Without the clamp this predictor over-reports the budget and the caller
			// picks a thumbnail the splice then rejects, degrading to strip (no preview) where a smaller
			// splice would fit. Gated on the same splice discipline patchedNonThumbBytes applies (both
			// thumbnail tags present AND a non-zero offset): an IFD1 missing either tag routes patch()
			// through append, which oldThumbLen does not constrain, so clamping there would force-strip
			// a thumbnail the append path embeds — the two predictors must agree on every source shape.
			if (hasFormatTag && hasLengthTag && oldThumbOff != 0
				&& (long) TIFF_HEADER_OFFSET + oldThumbOff + oldThumbLen < data.length)
			{
				return Math.min(oldThumbLen, budget);
			}
			return budget;
		}
		return DEFAULT_THUMB_BUDGET; // no EXIF segment found
	}

	/**
	 * Patch the EXIF dimensions to newW×newH, normalise orientation to 1 (we bake rotation into the
	 * primary), and replace / strip / preserve the IFD1 thumbnail per the four-state contract:
	 *
	 *   - null        → preserve the source's IFD1 thumbnail. Safe ONLY when saved pixels are byte-identical
	 *                   to the source (metadata-only rewrites); cropped / rotated / grafted exports MUST NOT
	 *                   pass null — it leaks pre-edit content via the preview.
	 *   - byte[0]     → strip the thumbnail (next-IFD pointer → 0). Use STRIP_IFD1_THUMBNAIL when fresh
	 *                   thumbnail generation fails.
	 *   - byte[N > 0] → replace with these bytes; rewrites IFD1 to point at them, or appends a fresh IFD1.
	 *   - no EXIF in input + non-empty thumbnail → synthesise a fresh APP1 EXIF via buildMinimalExifSegment,
	 *                   so sources without EXIF (screenshots, generated images) don't lose the new preview.
	 *
	 * @param segments  source-file segments; EXIF entries are cloned and mutated, others pass through verbatim
	 * @param newW      post-crop EXIF width
	 * @param newH      post-crop EXIF height
	 * @param thumbnail null to preserve, byte[0] / STRIP_IFD1_THUMBNAIL to strip, or new JPEG bytes to replace
	 * @return new list with EXIF dimensions / orientation / thumbnail applied; non-EXIF segments by reference
	 */
	public static List<JpegSegment> patch(List<JpegSegment> segments, int newW, int newH, byte[] thumbnail)
	{
		int orientation = 1; // always upright — rotation is baked into the pixels
		boolean foundExif = false;
		int initialCapacity = (segments != null ? segments.size() : 0) + 1;
		List<JpegSegment> result = new ArrayList<>(initialCapacity);
		List<JpegSegment> safeSegments = (segments != null) ? segments : Collections.emptyList();
		for (JpegSegment seg : safeSegments)
		{
			if (!seg.isExif())
			{
				result.add(seg);
				continue;
			}
			foundExif = true;
			byte[] data = seg.data().clone();
			// Validate byte-order (II/MM) and magic-42 before parsing — see hasIfd1Thumbnail for rationale.
			// The fallbacks differ deliberately: a segment too short for a TIFF header or with a bad byte
			// order passes the ORIGINAL segment through by reference; a bad magic passes the unmodified
			// clone.
			Optional<Boolean> byteOrder = tiffByteOrder(data);
			if (byteOrder.isEmpty())
			{
				result.add(seg);
				continue;
			}
			boolean isLittleEndian = byteOrder.orElseThrow();
			if (ByteBufferUtils.readU16(data, TIFF_HEADER_OFFSET + 2, isLittleEndian) != TiffTag.MAGIC)
			{
				result.add(new JpegSegment(seg.marker(), data));
				continue;
			}

			long ifdOffRel = ByteBufferUtils.readU32(data, TIFF_HEADER_OFFSET + 4, isLittleEndian);
			long absIfdOff = TIFF_HEADER_OFFSET + ifdOffRel;
			if (ifdOffRel < 0 || absIfdOff < TIFF_HEADER_OFFSET || absIfdOff + 2 > data.length)
			{
				result.add(new JpegSegment(seg.marker(), data));
				continue;
			}
			int ifdOff = (int) absIfdOff;

			try
			{
				scanIfd(data, ifdOff, TIFF_HEADER_OFFSET, isLittleEndian, newW, newH, orientation, 0);
			}
			catch (RuntimeException e)
			{
				// scanIfd has its own internal bounds checks but the recursive SubIFD walk and the
				// IFD-entry stride arithmetic could surface an unexpected throw on adversarial PNG
				// eXIf input (uncapped u31 length). Mirror replaceThumbnail's outer-fence pattern:
				// degrade to "ship the cloned segment with whatever in-place
				// updates landed before the throw" rather than letting it propagate to the bg-thread
				// save pipeline. Worst case the saved EXIF has stale dimensions; better than a toast.
				Log.w(TAG, "scanIfd threw; preserving partial in-place updates", e);
			}
			if (thumbnail != null)
			{
				if (thumbnail.length == 0)
				{
					data = stripIfd1Thumbnail(data, TIFF_HEADER_OFFSET, isLittleEndian);
				}
				else
				{
					data = replaceThumbnail(data, TIFF_HEADER_OFFSET, isLittleEndian, thumbnail);
				}
			}

			result.add(new JpegSegment(seg.marker(), data));
		}
		// Synthesize a minimal EXIF segment when the source carried none and the caller wants a fresh thumbnail
		// embedded. Sources without any EXIF (screenshots, generated images, files re-encoded by minimal tools
		// that strip metadata) would otherwise lose the freshly-generated IFD1 preview on save — the
		// user-reported "no new thumbnail when source has no pre-computed thumbnail" bug. Prepend so EXIF lands
		// early in segment order; JpegMetadataInjector writes all segments after SOI before image data, so APP
		// order within that prefix is flexible per JPEG spec.
		if (!foundExif && thumbnail != null && thumbnail.length > 0)
		{
			buildMinimalExifSegment(newW, newH, thumbnail)
				.ifPresent(synthesized -> result.add(0, synthesized));
		}
		return result;
	}

	/**
	 * Predict the exact byte count of the patched EXIF APP1 segment EXCLUDING the new thumbnail — the headroom a
	 * freshly-generated thumbnail can fill under the APP1 cap. Used by CropExporter.buildEmbeddedThumbnail for an
	 * exact budget with no estimation slack.
	 *
	 * Mirrors patch()'s decision tree to predict which write path fires and its non-thumbnail size:
	 *   - splice (source has a valid IFD1 with both JPEGInterchangeFormat[+Length] tags and a non-zero
	 *     offset): data.length minus the old thumbnail bytes (EXIF cloned verbatim, only thumb swapped).
	 *   - append (no IFD1, or missing a thumbnail tag, or zero / out-of-bounds offset): data.length + 42 —
	 *     appendFreshIfd1WithThumbnail adds a 42-byte IFD1 header at end-of-segment.
	 *   - synthesise (no EXIF segment at all): 102 — marker(2) + segLen(2) + "Exif\0\0"(6) + TIFF(8) +
	 *     IFD0(42) + IFD1(42).
	 *
	 * Parse failures fall through honestly: out-of-range IFD offsets → the append estimate (+42); whole-segment
	 * failures (bad byte order / magic / too short) → the synthesise estimate (102). In the malformed case patch()
	 * preserves the segment without a thumbnail, so the budget is unused at worst. (maxThumbnailBytes stays for the
	 * PNG eXIf splice/strip decision, which has different invariants.)
	 *
	 * @param segments source JPEG metadata from JpegMetadataExtractor; null treated as empty (→ synthesise, 102)
	 * @return exact predicted non-thumbnail byte count of the post-patch EXIF APP1 segment
	 */
	public static int patchedNonThumbBytes(List<JpegSegment> segments)
	{
		// Synthesised-from-scratch fallback layout, matching `buildMinimalExifSegment`:
		//   FF E1 marker (2) + segLen field (2) + "Exif\0\0" (6) + TIFF II*\0 + IFD0 offset (8)
		//   + IFD0 (count + 3 entries + nextIfd = 42) + IFD1 (count + 3 entries + nextIfd = 42)
		//   = 102 bytes of non-thumbnail overhead. Used when `segments` is null/empty or carries
		//   no EXIF segment.
		int synthesizedNonThumb = 102;
		if (segments == null)
		{
			return synthesizedNonThumb;
		}
		// Outer try/catch defends the bg-thread save pipeline against an unexpected RuntimeException slipping
		// past the explicit bounds / long-arithmetic guards below — caller would crash save-prep otherwise.
		// Generous-budget fallback at worst wastes the thumb encode if the actual patch silently drops the
		// thumbnail (malformed source EXIF case).
		try
		{
			for (JpegSegment seg : segments)
			{
				if (!seg.isExif())
				{
					continue;
				}
				byte[] data = seg.data();
				// Whole-segment failures (too short, bad byte order, bad magic) — patch() preserves
				// as-is without adding a thumbnail (foundExif=true blocks the synthesise path). The
				// actual budget is irrelevant because no thumb gets embedded; skip to the next segment
				// in case there's another EXIF, otherwise we fall through to the synthesise estimate
				// which at worst wastes the encode.
				Optional<Boolean> byteOrder = tiffByteOrder(data);
				if (byteOrder.isEmpty())
				{
					continue;
				}
				boolean isLittleEndian = byteOrder.orElseThrow();
				int magic = ByteBufferUtils.readU16(data, TIFF_HEADER_OFFSET + 2, isLittleEndian);
				if (magic != TiffTag.MAGIC)
				{
					continue;
				}

				// Walk IFD0 → next-IFD pointer → IFD1, mirroring `replaceThumbnail`'s decision tree:
				// a failed chain walk, no IFD1, or an out-of-bounds IFD1 all route replaceThumbnail
				// through appendFreshIfd1WithThumbnail / strip, so the append estimate is the
				// conservative budget for each — if the actual patch falls through to strip, the
				// generated thumb just isn't embedded, same outcome as every cascade rung failing.
				Optional<Ifd1Chain> maybeChain = locateIfd1(data, TIFF_HEADER_OFFSET, isLittleEndian);
				if (maybeChain.isEmpty() || maybeChain.orElseThrow().ifd1Rel() == 0
					|| maybeChain.orElseThrow().ifd1Abs() < 0)
				{
					return data.length + IFD1_HEADER_BYTES;
				}
				boolean hasFormatTag = false;
				boolean hasLengthTag = false;
				int oldThumbOff = 0;
				int oldThumbLen = 0;
				for (Ifd1ThumbEntry entry : scanIfd1ThumbTags(data,
					maybeChain.orElseThrow().ifd1Abs(), isLittleEndian))
				{
					// Validity discipline of this scan: offsets count only when non-zero and
					// int-sized; lengths only when within the segment. Tag presence is recorded
					// regardless of value validity.
					if (entry.tag() == TiffTag.JPEG_INTERCHANGE_FORMAT)
					{
						hasFormatTag = true;
						if (entry.rawValue() > 0 && entry.rawValue() <= Integer.MAX_VALUE)
						{
							oldThumbOff = (int) entry.rawValue();
						}
					}
					else
					{
						hasLengthTag = true;
						if (entry.rawValue() >= 0 && entry.rawValue() <= data.length)
						{
							oldThumbLen = (int) entry.rawValue();
						}
					}
				}
				// Splice fires iff `findThumbnailTags` would succeed: both tags present AND a non-zero
				// JPEGInterchangeFormat offset (so spliceExistingThumbnail has a target to overwrite).
				// Otherwise replaceThumbnail falls through to append.
				if (hasFormatTag && hasLengthTag && oldThumbOff != 0)
				{
					// Two splice sub-paths set different effective max-thumb constraints. When the
					// thumbnail is followed by trailing data (Samsung HDR EXIF has ~7 KB of
					// MakerNote value blocks past the IFD1 thumbnail — `afterLen > 0` in
					// spliceExistingThumbnail), the splice can only ACCEPT new thumbnails ≤
					// oldThumbLen because anything larger would shift the trailing bytes (corrupt
					// offsets elsewhere in the EXIF) and the padding fix can't widen the slot. To
					// make the caller's budget math (`thumbBudget = CAP - predicted`) clamp at
					// oldThumbLen, return CAP - oldThumbLen here — predicted is conservatively
					// larger than the actual non-thumb byte count, but the goal of this method is
					// "tell the caller the budget they should reserve", and `CAP - predicted =
					// oldThumbLen` is the correct budget for the splice path that succeeds. Without
					// this cap, the cascade could pick a thumbnail larger than oldThumbLen, splice
					// would bail to appendFresh, appendFresh would reject for APP1-cap overflow
					// (the segment is already near 65535 on Samsung sources), and the final
					// fallback would strip the thumbnail — exactly the regression the padding fix
					// was supposed to close.
					int afterStart = TIFF_HEADER_OFFSET + oldThumbOff + oldThumbLen;
					boolean hasTrailingBytes = afterStart < data.length;
					if (hasTrailingBytes)
					{
						return JpegSegment.MAX_SEGMENT_BYTES - oldThumbLen;
					}
					return data.length - oldThumbLen;
				}
				return data.length + IFD1_HEADER_BYTES;
			}
			return synthesizedNonThumb;
		}
		catch (RuntimeException ignored)
		{
			// Defensive default for any parse failure not caught by the explicit bounds checks above —
			// matches `hasIfd1Thumbnail`'s shape. Returning the synthesise estimate gives the caller a
			// generous budget; the worst case is wasted thumb encode if patch silently drops the thumbnail
			// downstream, which is bounded compute (single encode pass).
			return synthesizedNonThumb;
		}
	}

	/**
	 * IFD1 does not exist — append a minimal one (3 entries: Compression, JPEGInterchangeFormat,
	 * JPEGInterchangeFormatLength) plus the thumbnail bytes at the end of the EXIF payload, updating IFD0's
	 * next-IFD pointer and the APP1 segment length.
	 *
	 * @param data           full APP1 segment bytes; never mutated — success allocates a new array
	 * @param tiffStart      offset of the TIFF header within data
	 * @param nextIfdPointer absolute offset of IFD0's next-IFD pointer slot, rewritten to reference the new IFD1
	 * @param isLittleEndian TIFF byte order
	 * @param newThumb       thumbnail JPEG bytes to embed
	 * @return new segment array with the IFD1 + thumbnail appended; the unchanged `data` reference (the caller's
	 *         reject signal) when the thumbnail is absent, the result would exceed the APP1 payload cap, or the
	 *         rebuild throws
	 */
	private static byte[] appendFreshIfd1WithThumbnail(byte[] data, int tiffStart,
		int nextIfdPointer, boolean isLittleEndian, byte[] newThumb)
	{
		if (newThumb == null || newThumb.length == 0)
		{
			return data;
		}
		try
		{
			int ifd1Off = data.length - tiffStart; // offset relative to TIFF header
			byte[] ifd1Header = buildFreshIfd1Header(ifd1Off, newThumb.length, isLittleEndian);
			// newSegLen is the value written into the 2-byte length field, which per JPEG spec includes the
			// 2 length bytes themselves (so newSegLen == 2 + payload). Cap is 65535 — the maximum value
			// representable in the 2-byte field. Checked before assembly so an over-cap segment is rejected
			// without allocating the rebuilt array.
			int newSegLen = data.length + ifd1Header.length + newThumb.length - 2;
			if (newSegLen > JpegSegment.MAX_SEGMENT_BYTES)
			{
				return data;
			}
			// Single sized allocation: copyOf grows data to the final size, then the pointer rewrite and
			// the IFD1 header + thumbnail tail land directly in the copy.
			byte[] result = Arrays.copyOf(data, newSegLen + 2);
			ByteBufferUtils.writeU32(result, nextIfdPointer, ifd1Off, isLittleEndian);
			System.arraycopy(ifd1Header, 0, result, data.length, ifd1Header.length);
			System.arraycopy(newThumb, 0, result, data.length + ifd1Header.length, newThumb.length);
			result[2] = (byte) ((newSegLen >> 8) & 0xFF);
			result[3] = (byte) (newSegLen & 0xFF);
			Log.d(TAG, "Created IFD1 with thumbnail: " + newThumb.length + " bytes");
			return result;
		}
		catch (Exception e)
		{
			Log.w(TAG, "Failed to create IFD1 for thumbnail", e);
			return data;
		}
	}

	/**
	 * Build the 42-byte IFD1 structure: count(2) + 3 entries(12 each) + next-IFD(4). Thumbnail data is expected to
	 * sit immediately after this structure in the assembled EXIF payload; thumbDataOff is computed as ifd1Off + the
	 * structure size.
	 *
	 * @param ifd1Off        offset of the new IFD1 relative to the TIFF header; JPEGInterchangeFormat is written
	 *                       as ifd1Off + 42 (the byte right after this structure)
	 * @param thumbnailBytes thumbnail length in bytes, written into JPEGInterchangeFormatLength
	 * @param isLittleEndian TIFF byte order
	 * @return freshly allocated 42-byte IFD1 block
	 */
	private static byte[] buildFreshIfd1Header(int ifd1Off, int thumbnailBytes, boolean isLittleEndian)
	{
		byte[] ifd1Buf = new byte[2 + 3 * 12 + 4]; // count + 3 entries + next IFD
		ByteBufferUtils.writeU16(ifd1Buf, 0, 3, isLittleEndian);

		// Compression entry
		ByteBufferUtils.writeU16(ifd1Buf, 2, TiffTag.COMPRESSION, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 4, TiffTag.TYPE_SHORT, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 6, 1, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 10, 6, isLittleEndian); // JPEG compression

		// JPEGInterchangeFormat (offset to thumbnail bytes)
		int thumbDataOff = ifd1Off + ifd1Buf.length;
		ByteBufferUtils.writeU16(ifd1Buf, 14, TiffTag.JPEG_INTERCHANGE_FORMAT, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 16, TiffTag.TYPE_LONG, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 18, 1, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 22, thumbDataOff, isLittleEndian);

		// JPEGInterchangeFormatLength
		ByteBufferUtils.writeU16(ifd1Buf, 26, TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH, isLittleEndian);
		ByteBufferUtils.writeU16(ifd1Buf, 28, TiffTag.TYPE_LONG, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 30, 1, isLittleEndian);
		ByteBufferUtils.writeU32(ifd1Buf, 34, thumbnailBytes, isLittleEndian);

		// Next IFD = 0 (IFD1 is the last IFD)
		ByteBufferUtils.writeU32(ifd1Buf, 38, 0, isLittleEndian);
		return ifd1Buf;
	}

	/**
	 * Scan IFD1 for JPEGInterchangeFormat (0x0201) and JPEGInterchangeFormatLength (0x0202).
	 *
	 * @param data           full APP1 segment bytes; read only
	 * @param ifd1           absolute offset of IFD1 within data
	 * @param isLittleEndian TIFF byte order
	 * @return the two entry offsets and the recorded thumbnail offset / length; empty when either tag is missing,
	 *         oldThumbOff is zero (IFD1 exists but no thumbnail is recorded), or a recorded u32 exceeds
	 *         Integer.MAX_VALUE (corrupt / adversarial EXIF)
	 */
	private static Optional<ThumbnailTags> findThumbnailTags(byte[] data, int ifd1, boolean isLittleEndian)
	{
		int thumbOffTag = -1;
		int thumbLenTag = -1;
		int oldThumbOff = 0;
		int oldThumbLen = 0;

		// Validity discipline of this scan: it fails CLOSED — any value past Integer.MAX_VALUE rejects the
		// whole scan before the int cast, so a u32 ≥ 0x80000000 (adversarial / corrupt EXIF) can't
		// sign-extend to negative and bypass spliceExistingThumbnail's `absOldOff + oldThumbLen >
		// data.length` bounds check via tiffStart + (negative) = small-positive.
		for (Ifd1ThumbEntry entry : scanIfd1ThumbTags(data, ifd1, isLittleEndian))
		{
			long rawValue = entry.rawValue();
			if (rawValue < 0 || rawValue > Integer.MAX_VALUE)
			{
				return Optional.empty();
			}
			if (entry.tag() == TiffTag.JPEG_INTERCHANGE_FORMAT)
			{
				thumbOffTag = entry.entryOffset();
				oldThumbOff = (int) rawValue;
			}
			else
			{
				thumbLenTag = entry.entryOffset();
				oldThumbLen = (int) rawValue;
			}
		}
		if (thumbOffTag < 0 || thumbLenTag < 0 || oldThumbOff == 0)
		{
			return Optional.empty();
		}
		return Optional.of(new ThumbnailTags(thumbOffTag, thumbLenTag, oldThumbOff, oldThumbLen));
	}

	/**
	 * Walk IFD0 → next-IFD pointer → IFD1 offset, the pointer chain shared by hasIfd1Thumbnail,
	 * maxThumbnailBytes, patchedNonThumbBytes, replaceThumbnail, and stripIfd1Thumbnail. Returns raw facts — the
	 * recorded IFD1 offset comes back as-is via Ifd1Chain.ifd1Rel with ifd1Abs already bounds-resolved, so each
	 * caller keeps its own no-IFD1 / out-of-bounds fallback (budget formula, append, strip, skip). All offset
	 * arithmetic is in long: u32 offsets plus a small base wrap into small-positive ints that would silently
	 * slip past TIFF_HEADER_OFFSET / data.length bounds.
	 *
	 * Caller must have validated the TIFF byte order first (tiffByteOrder), which guarantees the 8 header bytes
	 * this walk starts from.
	 *
	 * @param data           full APP1 segment bytes; read only
	 * @param tiffStart      offset of the TIFF header within data
	 * @param isLittleEndian TIFF byte order
	 * @return the chain facts, or empty when the IFD0 offset or the next-IFD pointer slot falls outside the
	 *         segment (the walk cannot even locate the pointer)
	 */
	private static Optional<Ifd1Chain> locateIfd1(byte[] data, int tiffStart, boolean isLittleEndian)
	{
		long ifd0Rel = ByteBufferUtils.readU32(data, tiffStart + 4, isLittleEndian);
		long absIfd0 = tiffStart + ifd0Rel;
		if (ifd0Rel < 0 || absIfd0 < tiffStart || absIfd0 + 2 > data.length)
		{
			return Optional.empty();
		}
		int ifd0 = (int) absIfd0;
		int ifd0EntryCount = ByteBufferUtils.readU16(data, ifd0, isLittleEndian);
		long nextIfdPointerLong = (long) ifd0 + 2 + (long) ifd0EntryCount * 12;
		if (nextIfdPointerLong + 4 > data.length)
		{
			return Optional.empty();
		}
		int nextIfdPointer = (int) nextIfdPointerLong;
		long ifd1Rel = ByteBufferUtils.readU32(data, nextIfdPointer, isLittleEndian);
		long absIfd1 = tiffStart + ifd1Rel;
		boolean ifd1InBounds = ifd1Rel > 0 && absIfd1 + 2 <= data.length;
		return Optional.of(new Ifd1Chain(nextIfdPointer, ifd1Rel, ifd1InBounds ? (int) absIfd1 : -1));
	}

	/**
	 * Replace the EXIF thumbnail JPEG. Rebuilds the APP1 segment with new thumbnail bytes. Finds IFD1's
	 * JPEGInterchangeFormat/Length, replaces the old thumbnail data, and updates the segment length and tag values.
	 *
	 * Three-stage rebuild contract:
	 *   1. ifd1Rel == 0 (no IFD1 in source) → `appendFreshIfd1WithThumbnail` adds a new IFD1 at end-of-
	 *      segment carrying the fresh thumbnail.
	 *   2. ifd1Rel != 0 with valid thumbnail tags → `spliceExistingThumbnail` replaces the existing
	 *      thumbnail bytes in place.
	 *   3. ifd1Rel != 0 BUT splice rejects (IFD1 lacks JPEGInterchangeFormat/Length, or the recorded
	 *      thumbnail offset/length is malformed, or the rebuilt segment would exceed the APP1 cap) →
	 *      fall back to `appendFreshIfd1WithThumbnail` using IFD0's existing next-IFD-pointer slot.
	 *      The new IFD1 lands at end-of-segment and IFD0's pointer is redirected to it; the orphaned
	 *      original IFD1 + thumbnail bytes remain byte-present but unreachable through the spec parse
	 *      chain. Without this append fallback, sources with IFD1-but-no-thumbnail-tags would strip
	 *      instead of adding a fresh thumbnail, leaving saved files from minimal-EXIF sources with
	 *      no preview at all.
	 *
	 * Final fallback: if BOTH splice and append return `data` unchanged (e.g., append's own APP1-cap check also
	 * fires), `stripIfd1Thumbnail` zeros IFD0's next-IFD pointer so any surviving source IFD1 bytes are
	 * unreachable. This preserves the leak-prevention contract: the saved file never carries the SOURCE's pre-edit
	 * thumbnail even when no fresh thumbnail can be added.
	 *
	 * Outer `catch (Exception)` also strips for the same leak-prevention reason — any unexpected throw during the
	 * rebuild must not silently return the cloned source `data` with an intact source IFD1.
	 *
	 * @param data           full APP1 segment bytes; never mutated — every path returns a new array or strips
	 * @param tiffStart      offset of the TIFF header within data
	 * @param isLittleEndian TIFF byte order
	 * @param newThumb       fresh thumbnail JPEG bytes to embed
	 * @return rebuilt segment carrying the new thumbnail, or the stripped-IFD1 segment when no rebuild path fits
	 *         — never the source segment with its pre-edit thumbnail reachable
	 */
	private static byte[] replaceThumbnail(byte[] data, int tiffStart, boolean isLittleEndian, byte[] newThumb)
	{
		try
		{
			Optional<Ifd1Chain> maybeChain = locateIfd1(data, tiffStart, isLittleEndian);
			if (maybeChain.isEmpty())
			{
				return stripIfd1Thumbnail(data, tiffStart, isLittleEndian);
			}
			Ifd1Chain chain = maybeChain.orElseThrow();
			int nextIfdPointer = chain.nextIfdPointer();
			byte[] rebuilt;
			if (chain.ifd1Rel() == 0)
			{
				rebuilt = appendFreshIfd1WithThumbnail(data, tiffStart, nextIfdPointer,
					isLittleEndian, newThumb);
			}
			else
			{
				if (chain.ifd1Abs() < 0)
				{
					return stripIfd1Thumbnail(data, tiffStart, isLittleEndian);
				}
				rebuilt = spliceExistingThumbnail(data, tiffStart, chain.ifd1Abs(),
					isLittleEndian, newThumb);
				// Splice rejected — try appending a fresh IFD1 at end-of-segment using IFD0's existing
				// next-IFD-pointer slot. Works when the source's IFD1 lacks
				// JPEGInterchangeFormat/Length tags (so findThumbnailTags returned empty and splice had
				// nowhere to write the new thumbnail bytes — typical of minimal-EXIF encoders that emit
				// IFD1 carrying only XResolution/YResolution or empty entries). The original IFD1 bytes
				// orphan in the segment but become unreachable because IFD0's next-IFD pointer now
				// lands on the new IFD1. Reference equality (`rebuilt == data`) is the right signal:
				// every splice / append reject path returns the same input reference; success paths
				// allocate a new array.
				if (rebuilt == data)
				{
					rebuilt = appendFreshIfd1WithThumbnail(data, tiffStart, nextIfdPointer,
						isLittleEndian, newThumb);
				}
			}
			// Final fallback: if BOTH paths failed (e.g., appendFresh's own APP1 cap also rejects) the
			// saved file gets no thumbnail at all — but `stripIfd1Thumbnail` zeros IFD0's next-IFD pointer
			// so the SOURCE's pre-edit thumbnail still can't leak through the spec parse chain
			// (leak-prevention contract).
			if (rebuilt == data)
			{
				return stripIfd1Thumbnail(data, tiffStart, isLittleEndian);
			}
			return rebuilt;
		}
		catch (Exception e)
		{
			Log.w(TAG, "Thumbnail replacement failed; stripping IFD1 to prevent source-thumb leak", e);
			return stripIfd1Thumbnail(data, tiffStart, isLittleEndian);
		}
	}

	/**
	 * Walk one IFD, rewriting dimension / orientation entries IN PLACE in `data`, and recurse into the ExifSubIFD
	 * pointer (0x8769). Zeroes IFD1-only thumbnail tags (0x0103 / 0x0201 / 0x0202) that leaked into IFD0 so strict
	 * parsers can't follow stale thumbnail offsets. Does not follow the next-IFD link — IFD1's tags describe the
	 * thumbnail, which replaceThumbnail handles separately.
	 *
	 * @param data           full APP1 segment bytes, mutated in place
	 * @param ifdOff         absolute offset of the IFD to walk; out-of-bounds offsets return silently
	 * @param tiffStart      offset of the TIFF header within data; sub-IFD pointers below it are rejected so a
	 *                       corrupt pointer can't overwrite the byte-order marker
	 * @param isLittleEndian TIFF byte order
	 * @param newW           cropped output width written into ImageWidth / PixelXDimension
	 * @param newH           cropped output height written into ImageLength / PixelYDimension
	 * @param orientation    orientation value written into the Orientation entry (always 1 in practice — saved
	 *                       pixels are upright)
	 * @param depth          recursion depth; capped at 4 so cyclic / fan-out sub-IFD pointers in adversarial EXIF
	 *                       can't StackOverflowError the bg thread (uncatchable by the pipeline's Exception
	 *                       catches)
	 */
	private static void scanIfd(byte[] data, int ifdOff, int tiffStart, boolean isLittleEndian,
		int newW, int newH, int orientation, int depth)
	{
		// Cycle / depth guard: cap at 4 levels — well above the legitimate IFD0 → ExifSubIFD → InteropIFD
		// chain.
		if (depth > 4 || ifdOff < 0 || ifdOff + 2 > data.length)
		{
			return;
		}
		int entryCount = ByteBufferUtils.readU16(data, ifdOff, isLittleEndian);

		for (int i = 0; i < entryCount; i++)
		{
			long entryOffsetLong = (long) ifdOff + 2 + (long) i * 12;
			if (entryOffsetLong + 12 > data.length)
			{
				break;
			}
			int entryOffset = (int) entryOffsetLong;
			int tag = ByteBufferUtils.readU16(data, entryOffset, isLittleEndian);
			int type = ByteBufferUtils.readU16(data, entryOffset + 2, isLittleEndian);

			int valueOff = entryOffset + 8;
			switch (tag)
			{
				// Type-aware like the dimension tags below: Orientation is SHORT per spec, but a
				// non-standard LONG-typed entry would keep 2 stale value bytes under a fixed writeU16
				// (big-endian 6 → 0x00010006 instead of 1).
				case TiffTag.ORIENTATION ->
					writeValue(data, valueOff, type, isLittleEndian, orientation);
				case TiffTag.IMAGE_WIDTH ->
					writeValue(data, valueOff, type, isLittleEndian, newW);
				case TiffTag.IMAGE_LENGTH ->
					writeValue(data, valueOff, type, isLittleEndian, newH);
				case TiffTag.PIXEL_X_DIMENSION ->
					writeValue(data, valueOff, type, isLittleEndian, newW);
				case TiffTag.PIXEL_Y_DIMENSION ->
					writeValue(data, valueOff, type, isLittleEndian, newH);
				case TiffTag.COMPRESSION, TiffTag.JPEG_INTERCHANGE_FORMAT,
					TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH ->
				{
					// Tags 0x0103 / 0x0201 / 0x0202 are IFD1-only per the EXIF spec — Compression
					// describes the thumbnail's scheme, JPEGInterchangeFormat/Length point at the
					// thumbnail bytes. Some EXIF round-trips (Samsung Gallery, earlier CropCenter)
					// leak them into IFD0: 0x0201 / 0x0202 carry STALE offsets pointing at the
					// source's pre-edit thumbnail (or at byte ranges outside the export's EXIF
					// segment entirely) which strict parsers follow into garbage; 0x0103 carries a
					// harmlessly-redundant value=6 (the JPEG primary's compression is already
					// implied by FF D8). Zero the entire 12-byte entry in IFD0 so parsers see
					// unknown tag 0x0000 and skip — stronger than zeroing just the value field
					// because (a) Compression=0 alone is invalid TIFF and triggers parser-specific
					// fallbacks, and (b) an unknown-tag entry can't be misinterpreted by tools that
					// only check the value for "no thumbnail" sentinels on the 0x0201/0x0202 tags
					// specifically. IFD1's real fresh thumbnail pointers from buildFreshIfd1Header
					// / spliceExistingThumbnail are untouched. Restricted to IFD0 (depth=0) because
					// Sub-IFDs may legitimately reuse these tag values for non-thumbnail purposes
					// (rare but spec-permitted).
					if (depth == 0)
					{
						for (int j = 0; j < 12; j++)
						{
							data[entryOffset + j] = 0;
						}
					}
				}
				case TiffTag.EXIF_SUB_IFD ->
				{
					long off = ByteBufferUtils.readU32(data, entryOffset + 8, isLittleEndian);
					long absSubIfd = tiffStart + off;
					// absSubIfd >= tiffStart prevents a sub-tiffStart pointer recursing into the
					// EXIF/TIFF header bytes (would let scanIfd write orientation=1 over the
					// byte-order marker / magic, corrupting the segment).
					if (off >= 0 && absSubIfd >= tiffStart && absSubIfd + 2 <= data.length)
					{
						scanIfd(data, (int) absSubIfd, tiffStart, isLittleEndian,
							newW, newH, orientation, depth + 1);
					}
				}
				default -> {}
			}
		}

		// Don't follow the next-IFD link (IFD0 → IFD1). IFD1 is the thumbnail IFD — its dimension/orientation
		// tags describe the thumbnail, not the primary. replaceThumbnail() handles the thumbnail data
		// separately.
	}

	/**
	 * Collect IFD1's JPEGInterchangeFormat / JPEGInterchangeFormatLength entries in IFD order — the 12-byte-entry
	 * loop shared by hasIfd1Thumbnail, maxThumbnailBytes, patchedNonThumbBytes, and findThumbnailTags. Raw facts
	 * only: rawValue is the unvalidated u32 read, and every matching entry is reported (later duplicates
	 * included), so each caller folds the list under its own validity discipline — the disciplines differ
	 * per contract (predicate-gated last-wins, raw last-wins with post-clamp, fail-closed on any invalid value)
	 * and are documented at each fold. Long stride: u16 entry count × 12 can wrap int on uncapped PNG eXIf
	 * inputs; entries running past the segment end the scan.
	 *
	 * @param data           full APP1 segment bytes; read only
	 * @param ifd1           absolute offset of IFD1 within data (entry-count u16 readable by caller contract)
	 * @param isLittleEndian TIFF byte order
	 * @return thumbnail-tag entries in IFD order; empty list when IFD1 carries neither tag
	 */
	private static List<Ifd1ThumbEntry> scanIfd1ThumbTags(byte[] data, int ifd1, boolean isLittleEndian)
	{
		List<Ifd1ThumbEntry> entries = new ArrayList<>();
		int ifd1EntryCount = ByteBufferUtils.readU16(data, ifd1, isLittleEndian);
		for (int i = 0; i < ifd1EntryCount; i++)
		{
			long entryOffsetLong = (long) ifd1 + 2 + (long) i * 12;
			if (entryOffsetLong + 12 > data.length)
			{
				break;
			}
			int entryOffset = (int) entryOffsetLong;
			int tag = ByteBufferUtils.readU16(data, entryOffset, isLittleEndian);
			if (tag == TiffTag.JPEG_INTERCHANGE_FORMAT || tag == TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH)
			{
				entries.add(new Ifd1ThumbEntry(tag, entryOffset,
					ByteBufferUtils.readU32(data, entryOffset + 8, isLittleEndian)));
			}
		}
		return entries;
	}

	/**
	 * IFD1 exists — locate its JPEGInterchangeFormat / Length tags, splice the new thumbnail bytes in place of the
	 * old ones, and update the length tag + the APP1 segment-length header.
	 *
	 * @param data           full APP1 segment bytes; never mutated — success allocates a new array
	 * @param tiffStart      offset of the TIFF header within data
	 * @param ifd1           absolute offset of IFD1 within data
	 * @param isLittleEndian TIFF byte order
	 * @param newThumb       fresh thumbnail JPEG bytes to splice in
	 * @return new segment array with the thumbnail spliced (zero-padded when shrinking so trailing data keeps its
	 *         absolute offsets); the unchanged `data` reference (the caller's reject signal) in five cases: the
	 *         IFD1 offset falls outside the segment buffer; the thumbnail tags (0x0201 / 0x0202) are missing or
	 *         record a zero offset; the recorded thumbnail offset / length falls outside the segment buffer; the
	 *         new thumbnail is larger than the old slot with non-empty trailing data (widening would shift
	 *         offset-referenced bytes); or the result would exceed JpegSegment.MAX_SEGMENT_BYTES (caller retries
	 *         with a smaller thumbnail)
	 */
	private static byte[] spliceExistingThumbnail(byte[] data, int tiffStart, int ifd1,
		boolean isLittleEndian, byte[] newThumb)
	{
		if (ifd1 < tiffStart || ifd1 + 2 > data.length)
		{
			return data;
		}

		Optional<ThumbnailTags> maybeTags = findThumbnailTags(data, ifd1, isLittleEndian);
		if (maybeTags.isEmpty())
		{
			return data;
		}
		ThumbnailTags thumbTags = maybeTags.orElseThrow();
		int thumbLenTag = thumbTags.thumbLenTag();
		int oldThumbOff = thumbTags.oldThumbOff();
		int oldThumbLen = thumbTags.oldThumbLen();

		int absOldOff = tiffStart + oldThumbOff;
		// Long sum: oldThumbLen can be near MAX_INT (uncapped PNG eXIf path), so int absOldOff + oldThumbLen
		// wraps negative and would silently pass `> data.length`.
		if (absOldOff < 0 || (long) absOldOff + oldThumbLen > data.length)
		{
			return data;
		}

		// Splice: [...before...][newThumb][zero-pad if shrinking][...after (usually empty)...]
		int afterStart = absOldOff + oldThumbLen;
		int afterLen = data.length - afterStart;
		// When the thumbnail is followed by non-empty trailing data, the splice can't widen the
		// thumbnail slot without shifting every byte after it by (newThumb.length - oldThumbLen)
		// bytes — any TIFF offset stored elsewhere in the EXIF that references those trailing bytes
		// (a MakerNote value block, SubIFD value data, GPS offset-referenced data, etc.) would still
		// point at the OLD position, corrupting the reference. Two cases the splice handles cleanly:
		//   afterLen == 0 — no trailing data, the segment grows or shrinks freely.
		//   newThumb.length <= oldThumbLen — fits in the existing slot. When the new thumbnail is
		//     SMALLER than the old one, pad the gap with zero bytes so the trailing data stays at
		//     its original absolute offset. The JPEGInterchangeFormatLength tag (updated below)
		//     tells EXIF decoders to read only newThumb.length bytes; the padding sits between the
		//     new thumbnail's EOI and the trailing data, unreached through any spec-compliant parse.
		// Only newThumb.length > oldThumbLen with afterLen > 0 needs the appendFresh fallback —
		// there's no way to widen the slot without shifting. Samsung HDR captures fall into the
		// "shrink with trailing data" case (afterLen ≈ 7 KB of MakerNote value data after the
		// thumbnail); without this padding branch, every Samsung HDR save would strip the
		// thumbnail because appendFresh's APP1-cap check also rejects on already-full segments.
		if (afterLen > 0 && newThumb.length > oldThumbLen)
		{
			Log.d(TAG, "Splice would shift " + afterLen + " trailing bytes (oldLen=" + oldThumbLen
				+ " newLen=" + newThumb.length + "); falling back to appendFresh to preserve offsets");
			return data;
		}
		// Pad length is non-negative: zero when newThumb.length == oldThumbLen (same-size splice, no padding)
		// and positive when newThumb.length < oldThumbLen (zero-fill the residual slot). Negative-pad can't
		// occur because the > oldThumbLen branch above already returned.
		int padLen = (afterLen > 0) ? oldThumbLen - newThumb.length : 0;
		byte[] newData = new byte[absOldOff + newThumb.length + padLen + afterLen];
		System.arraycopy(data, 0, newData, 0, absOldOff);
		System.arraycopy(newThumb, 0, newData, absOldOff, newThumb.length);
		// Pad bytes are zero-initialized by `new byte[...]` — no explicit fill needed.
		if (afterLen > 0)
		{
			System.arraycopy(data, afterStart, newData, absOldOff + newThumb.length + padLen, afterLen);
		}

		// newSegLen is the value written into the 2-byte length field, which per JPEG spec includes the 2
		// length bytes themselves (so newSegLen == 2 + payload). Cap is 65535 — the maximum value representable
		// in the 2-byte field.
		int newSegLen = newData.length - 2;
		if (newSegLen > JpegSegment.MAX_SEGMENT_BYTES)
		{
			int overhead = newData.length - newThumb.length;
			int available = JpegSegment.MAX_SEGMENT_BYTES - overhead;
			Log.w(TAG, "Thumbnail " + newThumb.length + " too large (avail=" + available
				+ "), caller should retry smaller");
			return data;
		}

		ByteBufferUtils.writeU32(newData, thumbLenTag + 8, newThumb.length, isLittleEndian);
		newData[2] = (byte) ((newSegLen >> 8) & 0xFF);
		newData[3] = (byte) (newSegLen & 0xFF);
		Log.d(TAG, "Thumbnail replaced: " + oldThumbLen + " → " + newThumb.length
			+ " bytes (APP1=" + newSegLen + ")");
		return newData;
	}

	/**
	 * Strip the IFD1 thumbnail by setting IFD0's next-IFD pointer to 0 — soft strip via the parse chain. Standard
	 * EXIF parsers walk IFD0 → next-IFD-pointer → IFD1 → JPEGInterchangeFormat / Length to find the thumbnail; with
	 * the pointer cleared, the IFD1 entries and thumbnail bytes are unreachable through any spec-compliant parse,
	 * so the thumbnail won't display in any EXIF-aware viewer. The IFD1 / thumbnail bytes remain in the segment as
	 * orphaned trailing bytes — a forensic byte-level analyzer could still recover them, but that's beyond this
	 * helper's scope (and beyond the threat model of a normal save). Length-preserving so the APP1 segLen field
	 * doesn't need updating; if the pre-existing IFD1 was already absent, this is a no-op.
	 *
	 * @param data            full APP1 segment bytes (FF E1 + segLen + Exif\0\0 + TIFF body)
	 * @param tiffStart       offset within data where the TIFF header begins (10 — past the
	 *                        FF E1 segLen + Exif\0\0 prefix)
	 * @param isLittleEndian  byte-order flag from the TIFF header
	 * @return the data array with IFD0's next-IFD pointer zeroed (mutated in place when the
	 *         pointer is reachable; returned verbatim if the parse can't locate it)
	 */
	private static byte[] stripIfd1Thumbnail(byte[] data, int tiffStart, boolean isLittleEndian)
	{
		try
		{
			Optional<Ifd1Chain> maybeChain = locateIfd1(data, tiffStart, isLittleEndian);
			if (maybeChain.isEmpty())
			{
				return data;
			}
			int nextIfdPointer = maybeChain.orElseThrow().nextIfdPointer();
			// Zero the next-IFD pointer — the only field needed to make IFD1 invisible to spec parsers.
			// Mutates `data` in place; the caller already cloned the original segment data above so this
			// does not corrupt the source meta.
			ByteBufferUtils.writeU32(data, nextIfdPointer, 0L, isLittleEndian);
			Log.d(TAG, "IFD1 thumbnail stripped (next-IFD pointer at offset " + nextIfdPointer + " → 0)");
			return data;
		}
		catch (Exception e)
		{
			Log.w(TAG, "Thumbnail strip failed", e);
			return data;
		}
	}

	/**
	 * Validate the APP1 segment's TIFF header floor and byte-order marker — the preamble shared by
	 * hasIfd1Thumbnail, maxThumbnailBytes, patch, and patchedNonThumbBytes. Requires 8 readable bytes at
	 * TIFF_HEADER_OFFSET (byte order + magic + IFD0 offset) and an exact II / MM pair — a mismatched pair
	 * ("IM" / "MI") would silently be treated as little-endian and every subsequent offset read with the wrong
	 * byte order. The magic-42 check stays at each call site because the fallbacks diverge there (patch
	 * distinguishes it from the earlier failures).
	 *
	 * @param data full APP1 segment bytes (FF E1 + segLen + Exif\0\0 + TIFF body)
	 * @return the byte order (true = little-endian) when the header floor and II / MM pair validate; empty
	 *         when the segment is too short or the pair is malformed
	 */
	private static Optional<Boolean> tiffByteOrder(byte[] data)
	{
		if (TIFF_HEADER_OFFSET + 8 > data.length)
		{
			return Optional.empty();
		}
		int byteOrderHi = data[TIFF_HEADER_OFFSET] & 0xFF;
		int byteOrderLo = data[TIFF_HEADER_OFFSET + 1] & 0xFF;
		if (!((byteOrderHi == 0x49 && byteOrderLo == 0x49)
			|| (byteOrderHi == 0x4D && byteOrderLo == 0x4D)))
		{
			return Optional.empty();
		}
		return Optional.of(byteOrderHi == 0x49);
	}

	private static void writeValue(byte[] data, int off, int type, boolean isLittleEndian, int value)
	{
		if (type == TiffTag.TYPE_SHORT)
		{
			ByteBufferUtils.writeU16(data, off, value, isLittleEndian);
		}
		else
		{
			ByteBufferUtils.writeU32(data, off, value, isLittleEndian);
		}
	}
}
