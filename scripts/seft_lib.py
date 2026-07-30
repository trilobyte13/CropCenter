"""Shared Samsung-SEF-trailer boundary helpers for the JPEG diagnostic scripts.

SEF layout at end of file: [data blocks][SEFH directory][u32 LE dirLen]['SEFT']. The footer u32 is the SEFH DIRECTORY
length only — the data blocks (embedded thumbnails / depth maps / edit-history blobs) PRECEDE the directory and are
addressed by per-entry backward offsets relative to the SEFH start. Data blocks can embed whole JPEGs (thumbnails,
edit-history backups) and motion-photo MP4 bytes, so any gain-map SOI / EOI scan that isn't bounded at the true trailer
start can land on a marker INSIDE the trailer and pull trailer bytes into the "gain map". Every script that slices the
gain-map region (hdr_dump, hdr_analyze, compare_export, corpus_audit, graft_lib) routes through this module so the
boundary logic lives in one place.

Pure stdlib — importable from the pure-stdlib scripts and from the PIL/numpy-based graft_lib alike.
"""
import struct


def content_end(data):
	"""Return the offset where JPEG content (primary + optional gain map) ends: the SEFT trailer start when a
	parsed trailer is present, the file length when no trailer is present, or None when a SEFT footer is present
	but the SEFH directory doesn't parse (the trailer start cannot be established). The right end bound for every
	gain-map SOI / EOI scan — on None, callers must fail closed and skip the scan (report the malformed trailer)
	rather than scan bytes that can't be split into JPEG content vs SEF data blocks.
	"""
	trailer = find_seft_trailer(data)
	if trailer is None:
		return len(data)
	return trailer[0]


def find_last_eoi(data, start, end):
	"""Return the offset just past the LAST FF D9 marker in data[start:end], or -1.

	Used to bound the gain-map slice at the end of the gain-map JPEG. `end` must exclude the SEF trailer when one is
	present (pass content_end(data)); with the trailer excluded, the last FF D9 in the remaining span is the gain
	map's EOI for Ultra HDR JPEGs.
	"""
	pos = data.rfind(b'\xff\xd9', start, end)
	if pos < 0:
		return -1
	return pos + 2


def find_seft_trailer(data):
	"""Return the Samsung SEF trailer state: None when no SEFT footer is present, (start_offset, length) when the
	SEFH directory parses, or (None, 8) when the footer is present but the directory does NOT parse (dirLen < 12,
	SEFH position out of range, SEFH magic absent at end-8-dirLen, dirLen not matching the declared entry table,
	or any referenced block span out of range).

	Parse: read dirLen from end-8, verify 'SEFH' at end-8-dirLen, require dirLen to exactly hold the declared
	entry table (12-byte SEFH header + 12 bytes per declared entry: u32 type-ish field, u32 backward offset, u32
	block length — Samsung SEF v107 directories are exactly this shape), require every referenced block span to
	lie inside [0, SEFH start), and take the smallest data-block start as the trailer start. A None start is a
	fail-closed signal: the true trailer start cannot be established, and SEF data blocks can embed whole JPEGs
	and MP4 bytes, so callers must NOT scan the bytes before the footer as gain-map candidates — report the
	malformed trailer instead. A truncated table or an out-of-range span fails closed the same way rather than
	yielding a best-effort boundary from the entries that happened to parse.
	"""
	if len(data) < 12 or data[-4:] != b'SEFT':
		return None
	dir_len = struct.unpack('<I', data[-8:-4])[0]
	sefh_pos = len(data) - 8 - dir_len
	if dir_len < 12 or sefh_pos < 0 or data[sefh_pos:sefh_pos + 4] != b'SEFH':
		return (None, 8)
	trailer_start = sefh_pos
	count = struct.unpack('<I', data[sefh_pos + 8:sefh_pos + 12])[0]
	if 12 + 12 * count != dir_len:
		# Declared entry table doesn't match the directory length (truncated table or trailing slack) — the
		# entry walk below would read past the directory or miss entries, so the boundary is untrustworthy.
		return (None, 8)
	for i in range(count):
		entry = sefh_pos + 12 + 12 * i
		back_off = struct.unpack('<I', data[entry + 4:entry + 8])[0]
		block_len = struct.unpack('<I', data[entry + 8:entry + 12])[0]
		block_start = sefh_pos - back_off
		if block_start < 0 or block_start + block_len > sefh_pos:
			# Referenced block span escapes [0, SEFH start) — the directory lies about where its data
			# blocks live, so no entry can be trusted to bound the trailer.
			return (None, 8)
		if block_start < trailer_start:
			trailer_start = block_start
	return (trailer_start, len(data) - trailer_start)
