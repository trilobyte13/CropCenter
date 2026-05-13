package com.cropcenter.metadata;

import android.util.Log;

import com.cropcenter.util.ByteBufferUtils;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Patches EXIF metadata segments in-place:
 *  - Sets Orientation tag to 1 (Normal)
 *  - Updates ImageWidth, ImageLength, PixelXDimension, PixelYDimension
 *  - Replaces thumbnail JPEG with new one (or strips it — see `patch`'s thumbnail param)
 *  - Preserves all other tags and data verbatim
 */
public final class ExifPatcher
{
	private static final String TAG = "ExifPatcher";
	private static final int TIFF_HEADER_OFFSET = 10; // bytes from start of APP1 data to the TIFF header

	/**
	 * Sentinel for `patch`'s `thumbnail` param meaning "strip the IFD1 thumbnail from the segment so
	 * the saved file carries no embedded preview at all". Use this instead of `null` (which preserves
	 * the source's IFD1 thumbnail) when fresh thumbnail generation fails — without it, the saved
	 * cropped / rotated / grafted file would carry the SOURCE's pre-edit thumbnail, leaking pre-crop
	 * content via any EXIF-thumbnail-aware viewer (Codex round-31 F1).
	 */
	public static final byte[] STRIP_IFD1_THUMBNAIL = new byte[0];

	private ExifPatcher() {}

	/**
	 * Build a complete EXIF APP1 segment from scratch — IFD0 (Orientation=1, ImageWidth=newW,
	 * ImageLength=newH) + IFD1 (Compression=JPEG, JPEGInterchangeFormat, JPEGInterchangeFormatLength)
	 * + the supplied thumbnail bytes. Always little-endian. Returns null when the resulting segment
	 * would exceed the APP1 cap (65,535 bytes) — caller must drop the thumbnail in that case rather
	 * than ship an over-cap segment.
	 *
	 * Used by `patch` when the source carried no EXIF segment at all, so the freshly-generated IFD1
	 * thumbnail still makes it into the saved file (round-36 user-reported bug).
	 *
	 * @param newW      image width to write into IFD0's ImageWidth entry
	 * @param newH      image height to write into IFD0's ImageLength entry
	 * @param thumbnail JPEG-compressed thumbnail bytes; caller must pass non-null, non-empty
	 * @return synthesised APP1 JpegSegment, or null when the segment would exceed the APP1 cap
	 */
	public static JpegSegment buildMinimalExifSegment(int newW, int newH, byte[] thumbnail)
	{
		// Layout (little-endian throughout the TIFF body; segLen is big-endian per JPEG marker spec):
		//   [0..1]   FF E1                   APP1 marker
		//   [2..3]   segLen (u16 BE)
		//   [4..9]   "Exif\0\0"              EXIF identifier
		//   [10..13] II*\0                   TIFF byte-order + magic 42
		//   [14..17] u32 IFD0 offset = 8     (relative to TIFF start)
		//   [18..59] IFD0 (count=3, 3 entries: Orientation/IMAGE_WIDTH/IMAGE_LENGTH, next-IFD = IFD1)
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
			return null;
		}
		byte[] data = new byte[2 + segLenValue];
		data[0] = (byte) 0xFF;
		data[1] = (byte) 0xE1;
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
		ByteBufferUtils.writeU16(data, ifd0Abs, 3, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 2, TiffTag.ORIENTATION, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 4, TiffTag.TYPE_SHORT, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 6, 1, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 10, 1, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 14, TiffTag.IMAGE_WIDTH, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 16, TiffTag.TYPE_LONG, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 18, 1, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 22, newW, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 26, TiffTag.IMAGE_LENGTH, true);
		ByteBufferUtils.writeU16(data, ifd0Abs + 28, TiffTag.TYPE_LONG, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 30, 1, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 34, newH, true);
		ByteBufferUtils.writeU32(data, ifd0Abs + 38, ifd1TiffOff, true);

		int ifd1Abs = tiffStart + ifd1TiffOff;
		byte[] ifd1Header = buildFreshIfd1Header(ifd1TiffOff, thumbnail.length, true);
		System.arraycopy(ifd1Header, 0, data, ifd1Abs, ifd1Header.length);

		System.arraycopy(thumbnail, 0, data, tiffStart + thumbTiffOff, thumbnail.length);

		Log.d(TAG, "Synthesised EXIF segment: " + (2 + segLenValue) + " bytes (thumb "
			+ thumbnail.length + " B)");
		return new JpegSegment(0xE1, data);
	}

	/**
	 * Detect whether any segment in `segments` carries an EXIF IFD1 thumbnail reachable through the
	 * spec parse chain: EXIF APP1 → TIFF header → IFD0 → next-IFD pointer != 0 → IFD1 →
	 * JPEGInterchangeFormat tag (0x0201) with a non-zero offset value. Used by
	 * `ExportPipeline.canBypassEncode` to disqualify the verbatim-write bypass when the source has no
	 * pre-computed thumbnail — forcing the full re-encode path so `CropExporter` can synthesise one
	 * (round-36 user-reported bug: bypass saves of screenshots / generated images / minimal-EXIF
	 * sources preserved the source's empty-IFD1 state instead of adding a fresh thumbnail).
	 *
	 * Pure parse — does not modify segments. Returns false on any structural rejection (malformed
	 * byte order, out-of-bounds IFD offsets, IFD1 missing JPEGInterchangeFormat) so the caller treats
	 * "ambiguous" as "no thumbnail" and routes through the re-encode path that handles all those
	 * cases via the synthesise / append-fresh fallbacks in `replaceThumbnail`.
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
		// Outer try/catch defaults to false (bypass disabled = safe) on any unexpected parse failure —
		// the caller (`ExportPipeline.canBypassEncode`) runs on the UI thread for every Save tap, so an
		// uncaught exception here would crash save-prep. Mirrors the round-34 P0 leak-prevention shape
		// in `replaceThumbnail`: treat "I don't understand this EXIF" as "no thumbnail, re-encode".
		try
		{
			for (JpegSegment seg : segments)
			{
				if (!seg.isExif())
				{
					continue;
				}
				byte[] data = seg.data();
				if (TIFF_HEADER_OFFSET + 8 > data.length)
				{
					continue;
				}
				int byteOrderHi = data[TIFF_HEADER_OFFSET] & 0xFF;
				int byteOrderLo = data[TIFF_HEADER_OFFSET + 1] & 0xFF;
				if (!((byteOrderHi == 0x49 && byteOrderLo == 0x49)
					|| (byteOrderHi == 0x4D && byteOrderLo == 0x4D)))
				{
					continue;
				}
				boolean isLittleEndian = byteOrderHi == 0x49;

				long ifd0Rel = ByteBufferUtils.readU32(data, TIFF_HEADER_OFFSET + 4, isLittleEndian);
				long absIfd0 = TIFF_HEADER_OFFSET + ifd0Rel;
				if (ifd0Rel < 0 || absIfd0 < TIFF_HEADER_OFFSET || absIfd0 + 2 > data.length)
				{
					continue;
				}
				int ifd0 = (int) absIfd0;
				int ifd0EntryCount = ByteBufferUtils.readU16(data, ifd0, isLittleEndian);
				long nextIfdPointerLong = (long) ifd0 + 2 + (long) ifd0EntryCount * 12;
				if (nextIfdPointerLong + 4 > data.length)
				{
					continue;
				}
				long ifd1Rel = ByteBufferUtils.readU32(data, (int) nextIfdPointerLong, isLittleEndian);
				if (ifd1Rel == 0)
				{
					continue;
				}
				long absIfd1 = TIFF_HEADER_OFFSET + ifd1Rel;
				if (absIfd1 < TIFF_HEADER_OFFSET || absIfd1 + 2 > data.length)
				{
					continue;
				}
				int ifd1 = (int) absIfd1;
				int ifd1EntryCount = ByteBufferUtils.readU16(data, ifd1, isLittleEndian);
				for (int i = 0; i < ifd1EntryCount; i++)
				{
					// Long-arithmetic stride matches round-35 hardening in sister walkers.
					long entryOffsetLong = (long) ifd1 + 2 + (long) i * 12;
					if (entryOffsetLong + 12 > data.length)
					{
						break;
					}
					int entryOffset = (int) entryOffsetLong;
					int tag = ByteBufferUtils.readU16(data, entryOffset, isLittleEndian);
					if (tag == TiffTag.JPEG_INTERCHANGE_FORMAT)
					{
						long off = ByteBufferUtils.readU32(data, entryOffset + 8,
							isLittleEndian);
						if (off > 0 && off <= Integer.MAX_VALUE)
						{
							return true;
						}
					}
				}
			}
			return false;
		}
		catch (RuntimeException ignored)
		{
			// Defensive default for any parse failure not caught by the explicit bounds checks above —
			// e.g. an EXIF segment whose IFD1 entry count or stride wraps int arithmetic and slips past
			// a check. Returning false routes the caller through the re-encode path which synthesises a
			// fresh thumbnail rather than verbatim-shipping the source's malformed bytes.
			return false;
		}
	}

	/**
	 * Estimate max thumbnail bytes that will fit in the EXIF APP1 segment. Measures the EXIF size excluding the
	 * existing thumbnail, then returns the remaining space within the 65535-byte APP1 limit.
	 *
	 * @param segments JPEG metadata as captured by JpegMetadataExtractor
	 * @return remaining APP1 budget after EXIF overhead, or DEFAULT_THUMB_BUDGET when EXIF
	 *         is missing / unparseable / the byte-order field is malformed
	 */
	public static int maxThumbnailBytes(List<JpegSegment> segments)
	{
		// defaultThumbBudget — fallback when we can't measure the EXIF segment (no EXIF present, malformed
		// byte-order, IFD-offset math fails). 20KB is conservative: most camera thumbnails are 5-15KB and the
		// caller (CropExporter.buildEmbeddedThumbnail) re-checks the actual encoded size against the APP1 cap
		// before injection.
		int defaultThumbBudget = 20_000;
		// ifd1EstimatedOverhead — rough byte cost of synthesising an IFD1 header (entry count + 2 entries for
		// JPEGInterchangeFormat / Length + 4-byte next-IFD pointer + the 2 length bytes). Used when EXIF
		// exists but has no IFD1, so we'll add one alongside the new thumbnail.
		int ifd1EstimatedOverhead = 42;
		for (JpegSegment seg : segments)
		{
			if (!seg.isExif())
			{
				continue;
			}
			byte[] data = seg.data();
			if (TIFF_HEADER_OFFSET + 8 > data.length)
			{
				continue;
			}
			// TIFF byte-order marker is 2 bytes — "II" (little) or "MM" (big). Validate both halves; a
			// malformed "IM" / "MI" treated as little-endian would parse every subsequent u32 with wrong
			// byte order and corrupt offset arithmetic.
			int byteOrderHi = data[TIFF_HEADER_OFFSET] & 0xFF;
			int byteOrderLo = data[TIFF_HEADER_OFFSET + 1] & 0xFF;
			if (!((byteOrderHi == 0x49 && byteOrderLo == 0x49)
				|| (byteOrderHi == 0x4D && byteOrderLo == 0x4D)))
			{
				continue;
			}
			boolean isLittleEndian = byteOrderHi == 0x49;

			// IFD0 → IFD1 walk to locate the existing thumbnail entry. On any parse failure here we
			// fall back to defaultThumbBudget rather than zero — corrupt-IFD source EXIF is still
			// preserve-worthy at load / save round-trip, and a non-zero budget keeps the embedded-
			// thumbnail injection from silently dropping just because we couldn't measure it.
			long ifd0Rel = ByteBufferUtils.readU32(data, TIFF_HEADER_OFFSET + 4, isLittleEndian);
			// Long-arithmetic guard before the int cast — match the rest of ExifPatcher (patch,
			// replaceThumbnail, stripIfd1Thumbnail) and the sister walkers in BitmapUtils /
			// MpfPatcher / PngMetadataExtractor. A u32 ifd0Rel near 0xFFFFFFFF would truncate to a
			// small-positive int that passes `< TIFF_HEADER_OFFSET` only by luck; this function is in
			// the round-34 F1 critical path for the PNG strip-vs-splice decision, so the cast-first
			// regression would silently bypass the strip fallback on adversarial inputs (round-35
			// logic-audit P0).
			long absIfd0 = TIFF_HEADER_OFFSET + ifd0Rel;
			if (ifd0Rel < 0 || absIfd0 < TIFF_HEADER_OFFSET || absIfd0 + 2 > data.length)
			{
				return defaultThumbBudget;
			}
			int ifd0 = (int) absIfd0;
			int ifd0EntryCount = ByteBufferUtils.readU16(data, ifd0, isLittleEndian);
			// `nextIfdPointer` arithmetic via long so a near-MAX_INT `ifd0` paired with
			// `ifd0EntryCount = 0xFFFF` (786,420-byte stride) doesn't overflow int and then evaluate
			// `nextIfdPointer + 4 > data.length` against a wrap-negative LHS. Same long-first pattern
			// guards the parallel walk in `replaceThumbnail` and `stripIfd1Thumbnail`.
			long nextIfdPointerLong = (long) ifd0 + 2 + (long) ifd0EntryCount * 12;
			if (nextIfdPointerLong + 4 > data.length)
			{
				return defaultThumbBudget;
			}
			int nextIfdPointer = (int) nextIfdPointerLong;
			long ifd1Rel = ByteBufferUtils.readU32(data, nextIfdPointer, isLittleEndian);

			if (ifd1Rel == 0)
			{
				// No IFD1: EXIF overhead = current segment + new IFD1 header we'd add. Clamp at 0 — if
				// the current segment alone nearly fills the APP1 budget, there's no room for a
				// thumbnail and we should say so honestly rather than return a negative that relies on
				// the caller to clamp.
				return Math.max(0, JpegSegment.MAX_SEGMENT_BYTES - (data.length + ifd1EstimatedOverhead));
			}

			long absIfd1 = TIFF_HEADER_OFFSET + ifd1Rel;
			if (ifd1Rel < 0 || absIfd1 < TIFF_HEADER_OFFSET || absIfd1 + 2 > data.length)
			{
				return defaultThumbBudget;
			}
			int ifd1 = (int) absIfd1;
			int ifd1EntryCount = ByteBufferUtils.readU16(data, ifd1, isLittleEndian);
			int oldThumbLen = 0;
			for (int i = 0; i < ifd1EntryCount; i++)
			{
				// Long-arithmetic stride matches the rest of ExifPatcher (round-35/37/38 hardening
				// sweep). Reachable on uncapped PNG eXIf where data.length can be ~2GB.
				long entryOffsetLong = (long) ifd1 + 2 + (long) i * 12;
				if (entryOffsetLong + 12 > data.length)
				{
					break;
				}
				int entryOffset = (int) entryOffsetLong;
				int tag = ByteBufferUtils.readU16(data, entryOffset, isLittleEndian);
				if (tag == TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH)
				{
					oldThumbLen = (int) ByteBufferUtils.readU32(
						data, entryOffset + 8, isLittleEndian);
					break;
				}
			}
			// Sanity-clamp: corrupt EXIF can report a thumbnail length beyond the segment itself (or
			// negative after the u32→int cast). Either case produces a negative exifOverhead which inflates
			// the returned budget above JpegSegment.MAX_SEGMENT_BYTES — downstream writers then overflow the
			// 65535-byte APP1 cap. Clamp to [0, data.length].
			if (oldThumbLen < 0 || oldThumbLen > data.length)
			{
				oldThumbLen = 0;
			}
			// Available = JpegSegment.MAX_SEGMENT_BYTES - (current segment size - old thumbnail size)
			int exifOverhead = data.length - oldThumbLen;
			return Math.max(0, JpegSegment.MAX_SEGMENT_BYTES - exifOverhead);
		}
		return defaultThumbBudget; // no EXIF segment found
	}

	/**
	 * Patch the EXIF dimensions to newW×newH, normalise orientation to 1 (upright — we bake rotation
	 * into the primary JPEG), and replace / strip / preserve the IFD1 thumbnail per the documented
	 * four-state thumbnail contract:
	 *
	 *   - null         → preserve the source's IFD1 thumbnail verbatim. Only safe when the saved
	 *                    pixels are byte-identical to the source's pixels (e.g. metadata-only
	 *                    rewrites). Cropped / rotated / grafted exports MUST NOT pass null —
	 *                    preserving leaks pre-edit content via the embedded preview.
	 *   - byte[0]      → strip the IFD1 thumbnail (sets next-IFD pointer to 0; the IFD1 entries
	 *                    and thumbnail bytes are no longer reachable through the normal TIFF parse
	 *                    chain). Use this (or the STRIP_IFD1_THUMBNAIL constant) when fresh
	 *                    thumbnail generation fails. Codex round-31 F1.
	 *   - byte[N > 0]  → replace the thumbnail with these bytes. Existing IFD1 (if any) is rewritten
	 *                    to point at the new bytes; if no IFD1 existed a fresh one is appended.
	 *   - no EXIF in input AND non-empty thumbnail → synthesise a fresh APP1 EXIF segment from scratch
	 *                    via `buildMinimalExifSegment` and prepend it to the output. Sources without
	 *                    any EXIF (screenshots, generated images, files re-encoded by minimal tools)
	 *                    would otherwise lose the freshly-generated IFD1 preview on save (round-36).
	 *
	 * @param segments  source-file segments; EXIF entries are cloned and mutated, all others pass
	 *                  through verbatim
	 * @param newW      post-crop EXIF width
	 * @param newH      post-crop EXIF height
	 * @param thumbnail null to preserve, byte[0] / STRIP_IFD1_THUMBNAIL to strip, or the new JPEG
	 *                  thumbnail bytes to replace with
	 * @return new list with EXIF dimensions / orientation / thumbnail action applied; non-EXIF
	 *         segments are returned by reference
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
			if (TIFF_HEADER_OFFSET + 8 > data.length)
			{
				result.add(seg);
				continue;
			}
			// TIFF byte-order marker is 2 bytes — "II" (little) or "MM" (big). Validate both halves; a
			// malformed "IM" / "MI" treated as little-endian would parse every subsequent u32 with wrong
			// byte order and corrupt offset arithmetic.
			int byteOrderHi = data[TIFF_HEADER_OFFSET] & 0xFF;
			int byteOrderLo = data[TIFF_HEADER_OFFSET + 1] & 0xFF;
			if (!((byteOrderHi == 0x49 && byteOrderLo == 0x49)
				|| (byteOrderHi == 0x4D && byteOrderLo == 0x4D)))
			{
				result.add(seg);
				continue;
			}
			boolean isLittleEndian = byteOrderHi == 0x49;

			long ifdOffRel = ByteBufferUtils.readU32(data, TIFF_HEADER_OFFSET + 4, isLittleEndian);
			// Same long-arithmetic guard as scanIfd's SubIFD pointer: ifdOffRel is u32 (range 0..2^32-1)
			// and the addition `TIFF_HEADER_OFFSET + ifdOffRel` can exceed Integer.MAX_VALUE on adversarial
			// EXIF. Validate the long sum before casting — a malicious 0xFFFFFFFF would truncate to a
			// small-positive int that lands inside the buffer, bypassing the bounds check below.
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
				// eXIf input (uncapped u31 length). Mirror the round-34 P0 outer-fence pattern in
				// replaceThumbnail: degrade to "ship the cloned segment with whatever in-place
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
		// Synthesize a minimal EXIF segment when the source carried none and the caller wants a fresh
		// thumbnail embedded. Sources without any EXIF (screenshots, generated images, files re-encoded
		// by minimal tools that strip metadata) would otherwise lose the freshly-generated IFD1 preview
		// on save — the user-reported "no new thumbnail when source has no pre-computed thumbnail" bug.
		// Prepend so EXIF lands early in segment order; JpegMetadataInjector writes all segments after
		// SOI before image data, so APP order within that prefix is flexible per JPEG spec.
		if (!foundExif && thumbnail != null && thumbnail.length > 0)
		{
			JpegSegment synthesized = buildMinimalExifSegment(newW, newH, thumbnail);
			if (synthesized != null)
			{
				result.add(0, synthesized);
			}
		}
		return result;
	}

	/**
	 * Replace the EXIF thumbnail JPEG. Rebuilds the APP1 segment with new thumbnail bytes. Finds IFD1's
	 * JPEGInterchangeFormat/Length, replaces the old thumbnail data, and updates the segment length and
	 * tag values.
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
	 *      chain (round-36 P0: source with IFD1-but-no-thumbnail-tags previously stripped instead of
	 *      adding a fresh thumbnail, so saved files from minimal-EXIF sources carried no preview at
	 *      all — the user reported "no new thumbnail when source has no pre-computed thumbnail").
	 *
	 * Final fallback: if BOTH splice and append return `data` unchanged (e.g., append's own APP1-cap
	 * check also fires), `stripIfd1Thumbnail` zeros IFD0's next-IFD pointer so any surviving source
	 * IFD1 bytes are unreachable. This preserves the round-34 P0 leak-prevention contract: the saved
	 * file never carries the SOURCE's pre-edit thumbnail even when no fresh thumbnail can be added.
	 *
	 * Outer `catch (Exception)` also strips for the same leak-prevention reason — any unexpected throw
	 * during the rebuild must not silently return the cloned source `data` with an intact source IFD1.
	 */
	private static byte[] replaceThumbnail(byte[] data, int tiffStart, boolean isLittleEndian, byte[] newThumb)
	{
		try
		{
			long ifd0Rel = ByteBufferUtils.readU32(data, tiffStart + 4, isLittleEndian);
			// Long-arithmetic guard before the int cast — see ExifPatcher.patch and scanIfd for the full
			// rationale. A u32 ifd0Rel of 0xFFFFFFFF would truncate to a small-positive int that satisfies
			// `>= tiffStart` only by luck; computing the long sum first rules out the overflow case.
			long absIfd0 = tiffStart + ifd0Rel;
			if (ifd0Rel < 0 || absIfd0 < tiffStart || absIfd0 + 2 > data.length)
			{
				return stripIfd1Thumbnail(data, tiffStart, isLittleEndian);
			}
			int ifd0 = (int) absIfd0;
			int ifd0EntryCount = ByteBufferUtils.readU16(data, ifd0, isLittleEndian);
			// Long-arithmetic on the IFD0 entry stride so a u16 ifd0EntryCount = 0xFFFF paired with
			// a near-MAX_INT ifd0 (only reachable via the uncapped PNG eXIf path) doesn't overflow int
			// and then evaluate `nextIfdPointer + 4 > data.length` against a wrap-negative LHS.
			long nextIfdPointerLong = (long) ifd0 + 2 + (long) ifd0EntryCount * 12;
			if (nextIfdPointerLong + 4 > data.length)
			{
				return stripIfd1Thumbnail(data, tiffStart, isLittleEndian);
			}
			int nextIfdPointer = (int) nextIfdPointerLong;
			long ifd1Rel = ByteBufferUtils.readU32(data, nextIfdPointer, isLittleEndian);
			byte[] rebuilt;
			if (ifd1Rel == 0)
			{
				rebuilt = appendFreshIfd1WithThumbnail(data, tiffStart, nextIfdPointer,
					isLittleEndian, newThumb);
			}
			else
			{
				long absIfd1 = tiffStart + ifd1Rel;
				if (ifd1Rel < 0 || absIfd1 < tiffStart || absIfd1 + 2 > data.length)
				{
					return stripIfd1Thumbnail(data, tiffStart, isLittleEndian);
				}
				rebuilt = spliceExistingThumbnail(data, tiffStart, (int) absIfd1,
					isLittleEndian, newThumb);
				// Splice rejected — try appending a fresh IFD1 at end-of-segment using IFD0's
				// existing next-IFD-pointer slot. Works when the source's IFD1 lacks
				// JPEGInterchangeFormat/Length tags (so findThumbnailTags returned null and splice
				// had nowhere to write the new thumbnail bytes — typical of minimal-EXIF encoders
				// that emit IFD1 carrying only XResolution/YResolution or empty entries). The
				// original IFD1 bytes orphan in the segment but become unreachable because IFD0's
				// next-IFD pointer now lands on the new IFD1 (round-36 P0 fix). Reference equality
				// (`rebuilt == data`) is the right signal: every splice / append reject path
				// returns the same input reference; success paths allocate a new array.
				if (rebuilt == data)
				{
					rebuilt = appendFreshIfd1WithThumbnail(data, tiffStart, nextIfdPointer,
						isLittleEndian, newThumb);
				}
			}
			// Final fallback: if BOTH paths failed (e.g., appendFresh's own APP1 cap also rejects)
			// the saved file gets no thumbnail at all — but `stripIfd1Thumbnail` zeros IFD0's
			// next-IFD pointer so the SOURCE's pre-edit thumbnail still can't leak through the
			// spec parse chain (round-34 P0 leak-prevention contract).
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
	 * IFD1 does not exist — append a minimal one (3 entries: Compression, JPEGInterchangeFormat,
	 * JPEGInterchangeFormatLength) plus the thumbnail bytes at the end of the EXIF payload, updating IFD0's
	 * next-IFD pointer and the APP1 segment length. No-op return (unchanged `data`) when the thumbnail is absent or
	 * the result would exceed the APP1 payload cap.
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
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(data);

			int ifd1Off = data.length - tiffStart; // offset relative to TIFF header
			byte[] updated = out.toByteArray();
			ByteBufferUtils.writeU32(updated, nextIfdPointer, ifd1Off, isLittleEndian);

			out.reset();
			out.write(updated);
			out.write(buildFreshIfd1Header(ifd1Off, newThumb.length, isLittleEndian));
			out.write(newThumb);

			byte[] result = out.toByteArray();
			// newSegLen is the value written into the 2-byte length field, which per JPEG spec includes the
			// 2 length bytes themselves (so newSegLen == 2 + payload). Cap is 65535 — the maximum value
			// representable in the 2-byte field.
			int newSegLen = result.length - 2;
			if (newSegLen > JpegSegment.MAX_SEGMENT_BYTES)
			{
				return data;
			}
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
	 */
	private static byte[] buildFreshIfd1Header(int ifd1Off, int thumbnailBytes, boolean isLittleEndian)
	{
		byte[] ifd1Buf = new byte[2 + 3 * 12 + 4]; // count + 3 entries + next IFD
		ByteBufferUtils.writeU16(ifd1Buf, 0, 3, isLittleEndian);

		// Tag 0x0103: Compression = 6 (JPEG)
		ByteBufferUtils.writeU16(ifd1Buf, 2, 0x0103, isLittleEndian);
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
	 * IFD1 exists — locate its JPEGInterchangeFormat / Length tags, splice the new thumbnail bytes in place of the
	 * old ones, and update the length tag + the APP1 segment-length header. Returns unchanged `data` in four cases:
	 * the IFD1 offset falls outside the segment buffer; the thumbnail tags (0x0201 / 0x0202) are missing or record
	 * a zero offset; the recorded thumbnail offset / length falls outside the segment buffer; or the new thumbnail
	 * wouldn't fit under JpegSegment.MAX_SEGMENT_BYTES (caller retries with a smaller thumbnail).
	 */
	private static byte[] spliceExistingThumbnail(byte[] data, int tiffStart, int ifd1,
		boolean isLittleEndian, byte[] newThumb)
	{
		if (ifd1 < tiffStart || ifd1 + 2 > data.length)
		{
			return data;
		}

		int[] thumbTags = findThumbnailTags(data, ifd1, isLittleEndian);
		if (thumbTags == null)
		{
			return data;
		}
		int thumbOffTag = thumbTags[0];
		int thumbLenTag = thumbTags[1];
		int oldThumbOff = thumbTags[2];
		int oldThumbLen = thumbTags[3];

		int absOldOff = tiffStart + oldThumbOff;
		// Long-arithmetic guard: `findThumbnailTags` clamps `oldThumbLen` to `[0, Integer.MAX_VALUE]`,
		// so an int `absOldOff + oldThumbLen` can wrap negative on adversarial input where
		// `oldThumbLen` is near `Integer.MAX_VALUE`. The `> data.length` check would then evaluate
		// false (negative > positive) and the subsequent `data.length - afterStart` arithmetic would
		// blow up downstream — caught at `replaceThumbnail`'s catch which (after the round-34 logic-
		// audit P0 fix) now strips IFD1, but routing through here cleanly avoids the throw entirely.
		if (absOldOff < 0 || (long) absOldOff + oldThumbLen > data.length)
		{
			return data;
		}

		// Splice: [...before...][newThumb][...after (usually empty)...]
		int afterStart = absOldOff + oldThumbLen;
		int afterLen = data.length - afterStart;
		// Codex round-41 P2: when the thumbnail is followed by non-empty trailing data AND the new
		// thumbnail length differs from the old, the splice shifts every byte after the thumbnail by
		// (newThumb.length - oldThumbLen) bytes. Any TIFF offset stored elsewhere in the EXIF that
		// references those trailing bytes (a MakerNote value block, SubIFD value data, GPS offset-
		// referenced data, etc.) would still point at the OLD position, corrupting the reference. The
		// typical Samsung layout has the thumbnail at EOF (afterLen=0) so the splice is safe — same
		// length is also safe because no shift occurs. For any other case fall back to appendFresh,
		// which orphans the old IFD1 in place and leaves all offset-referenced data stable; the
		// caller (replaceThumbnail) detects `rebuilt == data` and routes through appendFresh.
		if (afterLen > 0 && newThumb.length != oldThumbLen)
		{
			Log.d(TAG, "Splice would shift " + afterLen + " trailing bytes (oldLen=" + oldThumbLen
				+ " newLen=" + newThumb.length + "); falling back to appendFresh to preserve offsets");
			return data;
		}
		byte[] newData = new byte[absOldOff + newThumb.length + afterLen];
		System.arraycopy(data, 0, newData, 0, absOldOff);
		System.arraycopy(newThumb, 0, newData, absOldOff, newThumb.length);
		if (afterLen > 0)
		{
			System.arraycopy(data, afterStart, newData, absOldOff + newThumb.length, afterLen);
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
	 * Strip the IFD1 thumbnail by setting IFD0's next-IFD pointer to 0 — soft strip via the parse
	 * chain. Standard EXIF parsers walk IFD0 → next-IFD-pointer → IFD1 → JPEGInterchangeFormat /
	 * Length to find the thumbnail; with the pointer cleared, the IFD1 entries and thumbnail bytes
	 * are unreachable through any spec-compliant parse, so the thumbnail won't display in any
	 * EXIF-aware viewer. The IFD1 / thumbnail bytes remain in the segment as orphaned trailing
	 * bytes — a forensic byte-level analyzer could still recover them, but that's beyond this
	 * helper's scope (and beyond the threat model of a normal save). Length-preserving so the APP1
	 * segLen field doesn't need updating; if the pre-existing IFD1 was already absent, this is a
	 * no-op.
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
			long ifd0Rel = ByteBufferUtils.readU32(data, tiffStart + 4, isLittleEndian);
			long absIfd0 = tiffStart + ifd0Rel;
			if (ifd0Rel < 0 || absIfd0 < tiffStart || absIfd0 + 2 > data.length)
			{
				return data;
			}
			int ifd0 = (int) absIfd0;
			int ifd0EntryCount = ByteBufferUtils.readU16(data, ifd0, isLittleEndian);
			// Long-arithmetic on the IFD0 entry stride; see replaceThumbnail / maxThumbnailBytes for
			// the full overflow rationale. Reachable only via the uncapped PNG eXIf round-trip path.
			long nextIfdPointerLong = (long) ifd0 + 2 + (long) ifd0EntryCount * 12;
			if (nextIfdPointerLong + 4 > data.length)
			{
				return data;
			}
			int nextIfdPointer = (int) nextIfdPointerLong;
			// Zero the next-IFD pointer — the only field needed to make IFD1 invisible to spec
			// parsers. Mutates `data` in place; the caller already cloned the original segment
			// data above so this does not corrupt the source meta.
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
	 * Scan IFD1 for JPEGInterchangeFormat (0x0201) and JPEGInterchangeFormatLength (0x0202). Returns {thumbOffTag,
	 * thumbLenTag, oldThumbOff, oldThumbLen} or null when either tag is missing — also null when oldThumbOff is
	 * zero (IFD1 exists but no thumbnail is recorded).
	 */
	private static int[] findThumbnailTags(byte[] data, int ifd1, boolean isLittleEndian)
	{
		int ifd1EntryCount = ByteBufferUtils.readU16(data, ifd1, isLittleEndian);
		int thumbOffTag = -1;
		int thumbLenTag = -1;
		int oldThumbOff = 0;
		int oldThumbLen = 0;

		for (int i = 0; i < ifd1EntryCount; i++)
		{
			// Long-arithmetic stride matches the round-35 hardening in sister walkers. A u16
			// ifd1EntryCount paired with a near-MAX_INT ifd1 (only reachable via uncapped PNG eXIf)
			// would wrap int and bypass the bounds check; outer `replaceThumbnail` try/catch would
			// then strip IFD1 — graceful but inconsistent with the rest of the file's hardening.
			long entryOffsetLong = (long) ifd1 + 2 + (long) i * 12;
			if (entryOffsetLong + 12 > data.length)
			{
				break;
			}
			int entryOffset = (int) entryOffsetLong;
			int tag = ByteBufferUtils.readU16(data, entryOffset, isLittleEndian);
			if (tag == TiffTag.JPEG_INTERCHANGE_FORMAT)
			{
				thumbOffTag = entryOffset;
				// Read as long; reject values past Integer.MAX_VALUE before the int cast so a u32
				// ≥ 0x80000000 (adversarial / corrupt EXIF) doesn't sign-extend to negative and
				// bypass spliceExistingThumbnail's `absOldOff + oldThumbLen > data.length` bounds
				// check via tiffStart + (negative) = small-positive (Codex round-32 logic L2).
				long off = ByteBufferUtils.readU32(data, entryOffset + 8, isLittleEndian);
				if (off < 0 || off > Integer.MAX_VALUE)
				{
					return null;
				}
				oldThumbOff = (int) off;
			}
			else if (tag == TiffTag.JPEG_INTERCHANGE_FORMAT_LENGTH)
			{
				thumbLenTag = entryOffset;
				long len = ByteBufferUtils.readU32(data, entryOffset + 8, isLittleEndian);
				if (len < 0 || len > Integer.MAX_VALUE)
				{
					return null;
				}
				oldThumbLen = (int) len;
			}
		}
		if (thumbOffTag < 0 || thumbLenTag < 0 || oldThumbOff == 0)
		{
			return null;
		}
		return new int[] { thumbOffTag, thumbLenTag, oldThumbOff, oldThumbLen };
	}

	private static void scanIfd(byte[] data, int ifdOff, int tiffStart, boolean isLittleEndian,
		int newW, int newH, int orientation, int depth)
	{
		// Cycle / depth guard. Real-world EXIF has a single ExifSubIFD pointer (0x8769) from IFD0 → ExifSubIFD;
		// corrupted or adversarial files can chain pointers in cycles or deeply nested fan-out, both of which
		// would unbounded-recurse and throw StackOverflowError. Error is uncatchable in our normal Exception
		// catches, so the bg thread crashes and the whole save/apply pipeline dies silently. Cap at 4 levels —
		// well above the legitimate IFD0 → ExifSubIFD → InteropIFD chain.
		if (depth > 4 || ifdOff < 0 || ifdOff + 2 > data.length)
		{
			return;
		}
		int entryCount = ByteBufferUtils.readU16(data, ifdOff, isLittleEndian);

		for (int i = 0; i < entryCount; i++)
		{
			// Long-arithmetic stride matches the round-35/37 hardening in sister walkers
			// (hasIfd1Thumbnail / findThumbnailTags / maxThumbnailBytes / replaceThumbnail /
			// stripIfd1Thumbnail). Reachable via the uncapped PNG eXIf path where data.length can be
			// ~2GB and ifdOff can be near Integer.MAX_VALUE; the int stride would wrap and bypass the
			// bound check. The patch caller wraps scanIfd in its own try/catch (added in this round)
			// so any residual throw degrades to "ship unmodified data" rather than crashing the bg
			// thread on the save path.
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
				case TiffTag.ORIENTATION ->
					ByteBufferUtils.writeU16(data, valueOff, orientation, isLittleEndian);
				case TiffTag.IMAGE_WIDTH ->
					writeValue(data, valueOff, type, isLittleEndian, newW);
				case TiffTag.IMAGE_LENGTH ->
					writeValue(data, valueOff, type, isLittleEndian, newH);
				case TiffTag.PIXEL_X_DIMENSION ->
					writeValue(data, valueOff, type, isLittleEndian, newW);
				case TiffTag.PIXEL_Y_DIMENSION ->
					writeValue(data, valueOff, type, isLittleEndian, newH);
				case TiffTag.EXIF_SUB_IFD ->
				{
					long off = ByteBufferUtils.readU32(data, entryOffset + 8, isLittleEndian);
					// `off` is u32 (range 0..2^32-1) read into a long, so the addition `tiffStart +
					// off` can exceed Integer.MAX_VALUE on adversarial EXIF. Validate the
					// long-arithmetic result FIRST and only cast to int after we know it fits —
					// without this guard, a malicious off like 0xFFFFFFFF would cast to a
					// small-positive int that lands inside the buffer (e.g. (10 + 0xFFFFFFFFL)
					// truncates to 9), which would re-enter scanIfd reading EXIF header bytes as
					// IFD entries and writing orientation=1 over arbitrary data.
					long absSubIfd = tiffStart + off;
					// Require absSubIfd >= tiffStart so a sub-tiffStart pointer (e.g., 4) can't
					// recurse into the EXIF/TIFF header bytes — otherwise scanIfd would treat
					// header bytes as IFD entries and write orientation = 1 over the byte-order
					// marker / magic, corrupting the EXIF segment. Matches the IFD0 guard in
					// patch() and replaceThumbnail.
					if (off >= 0 && absSubIfd >= tiffStart && absSubIfd + 2 <= data.length)
					{
						scanIfd(data, (int) absSubIfd, tiffStart, isLittleEndian,
							newW, newH, orientation, depth + 1);
					}
				}
				// other tags left unchanged
				default -> {}
			}
		}

		// Don't follow the next-IFD link (IFD0 → IFD1). IFD1 is the thumbnail IFD — its dimension/orientation
		// tags describe the thumbnail, not the primary. replaceThumbnail() handles the thumbnail data
		// separately.
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
