"""Shared bounded JPEG-structure helpers for the diagnostic scripts.

Two walker families that every JPEG diagnostic needs live here as single chokepoints (mirroring the Java side's
JpegMarkerWalker / TiffIfd0 canonical helpers and the sibling seft_lib trailer chokepoint):

  - find_primary_eoi — the SOI -> segments -> SOS -> entropy walk that locates the byte just past the primary JPEG's
    EOI, refusing (-1) a head that fails the has_soi predicate. Consumed by compare_export, corpus_audit, graft_lib,
    hdr_dump, and hdr_analyze.
  - The TIFF IFD entry walk (ifd_entry_count / walk_ifd_entries / read_short_value / next_ifd_offset) — bounds-checked,
    endian-correct iteration over 12-byte IFD entries. Consumed by compare_export's EXIF mini-parser and corpus_audit's
    parse_exif_segment.

The inline-SHORT subtlety the shared walk owns: TIFF left-justifies values shorter than 4 bytes in the entry's value
field, so a SHORT must be read as a u16 at entry+8 in the file's own byte order. Masking the u32 value field with 0xFFFF
reads the WRONG half on big-endian (MM) files — e.g. Orientation 6 stored as 00 06 00 00 masks to 0.

Pure stdlib — importable from the pure-stdlib scripts and from the PIL/numpy-based graft_lib alike.
"""
import struct


def find_primary_eoi(data, end_bound=None):
	"""Walk JPEG markers from SOI to the primary's EOI (FF D9). Returns the offset of the byte AFTER the EOI, or -1
	when data doesn't start with an SOI (has_soi — walking a misaligned head would return offsets into garbage) or
	when no EOI is reached before end_bound (defaults to len(data)). Entropy data after SOS uses FF 00 byte-stuffing
	and may contain RST markers (FF D0-D7) between restart intervals; any other FF xx ends the entropy scan and
	resumes the segment walk (progressive files carry DHT / SOS chains between scans). FF FF fill bytes advance one
	byte at a time so a fill-padded marker is still seen; a segment length under the 2-byte spec minimum or a
	truncated segment header ends the walk.
	"""
	if not has_soi(data):
		return -1
	if end_bound is None:
		end_bound = len(data)
	pos = 2
	while pos < end_bound - 1:
		if data[pos] != 0xFF:
			pos += 1
			continue
		marker = data[pos + 1]
		if marker == 0xFF:
			pos += 1  # fill byte — the next byte pair may hold the real marker
			continue
		if marker == 0xD9:
			return pos + 2
		if marker == 0xDA:
			if pos + 4 > end_bound:
				return -1
			seglen = struct.unpack('>H', data[pos + 2:pos + 4])[0]
			if seglen < 2:
				return -1
			scan = pos + 2 + seglen
			while scan < end_bound - 1:
				if data[scan] != 0xFF:
					scan += 1
					continue
				nxt = data[scan + 1]
				if nxt == 0xD9:
					return scan + 2
				if nxt == 0x00 or (0xD0 <= nxt <= 0xD7):
					scan += 2
					continue
				break
			pos = scan
			continue
		if marker in (0x00, 0x01) or (0xD0 <= marker <= 0xD7):
			pos += 2
			continue
		if pos + 4 > end_bound:
			return -1
		seglen = struct.unpack('>H', data[pos + 2:pos + 4])[0]
		if seglen < 2:
			return -1
		pos += 2 + seglen
	return -1


def has_soi(data):
	"""True when data starts with the JPEG SOI marker (FF D8) — the head-alignment precondition every offset-2
	walker in this module assumes. The single chokepoint for the check: find_primary_eoi refuses a non-SOI head via
	this predicate, and corpus_audit reports a missing SOI as its own P0 anomaly.
	"""
	return len(data) >= 2 and data[0] == 0xFF and data[1] == 0xD8


def ifd_entry_count(data, ifd_off, le):
	"""Declared 12-byte-entry count of the IFD at ifd_off, or None when the 2-byte count field doesn't fit in data
	(out-of-range IFD pointer, truncated segment).
	"""
	if ifd_off < 0 or ifd_off + 2 > len(data):
		return None
	return read_u16(data, ifd_off, le)


def next_ifd_offset(data, ifd_off, le):
	"""Raw next-IFD pointer stored just past the IFD's declared entry table (IFD0 -> IFD1 chain), or 0 when the
	pointer — or the IFD's own count field — doesn't fit in data.
	"""
	count = ifd_entry_count(data, ifd_off, le)
	if count is None:
		return 0
	tail = ifd_off + 2 + count * 12
	if tail + 4 > len(data):
		return 0
	return read_u32(data, tail, le)


def read_short_value(data, entry_off, le):
	"""Endian-correct inline SHORT value of the IFD entry at entry_off: a u16 at entry_off + 8 in the file's own
	byte order (see the module docstring for why a 0xFFFF mask on the u32 value field is wrong on MM files).
	"""
	return read_u16(data, entry_off + 8, le)


def read_u16(data, off, le):
	"""u16 at off in little-endian order when le is truthy, big-endian otherwise."""
	return struct.unpack('<H' if le else '>H', data[off:off + 2])[0]


def read_u32(data, off, le):
	"""u32 at off in little-endian order when le is truthy, big-endian otherwise."""
	return struct.unpack('<I' if le else '>I', data[off:off + 4])[0]


def walk_ifd_entries(data, ifd_off, le):
	"""Yield (entry_off, tag, entry_type, count, value_field) for each 12-byte entry of the IFD at ifd_off.

	value_field is the raw u32 of the entry's value/offset field; inline SHORTs must instead be read via
	read_short_value(data, entry_off, le). Every entry is bounds-checked against len(data): the walk stops silently
	at the first entry that would run past the buffer, and yields nothing when the 2-byte count field itself doesn't
	fit — callers needing the declared count read it via ifd_entry_count.
	"""
	count = ifd_entry_count(data, ifd_off, le)
	if count is None:
		return
	for i in range(count):
		entry = ifd_off + 2 + i * 12
		if entry + 12 > len(data):
			return
		yield (entry, read_u16(data, entry, le), read_u16(data, entry + 2, le),
			read_u32(data, entry + 4, le), read_u32(data, entry + 8, le))
