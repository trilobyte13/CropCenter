"""Standalone Ultra HDR JPEG dumper / extractor.

Pulls a multi-picture HDR JPEG apart into its constituent pieces — primary JPEG, gain-map JPEG, MPF
index, XMP HDR descriptor, SEFT trailer — and writes each to disk as a separate file. Useful when
the on-device HDR extractor isn't producing output, or when you want to inspect what's actually
inside a Samsung / Pixel Ultra HDR file vs what tooling reports.

Usage:
    py scripts/hdr_dump.py <input.jpg> [output_dir]
    py scripts/hdr_dump.py input.jpg                    # dumps to ./hdr-dump-<stem>/
    py scripts/hdr_dump.py input.jpg my-dump-dir/       # explicit output path

Output files (each is byte-exact slice from the input — never re-encoded):
    <stem>-primary.jpg     Primary JPEG (SOI through primary EOI)
    <stem>-gainmap.jpg     Gain-map JPEG (the secondary picture, SOI through gainmap EOI)
    <stem>-mpf.bin         APP2 MPF segment (FF E2 + length + "MPF\\0" + TIFF body)
    <stem>-xmp-hdr.xml     XMP HDR descriptor body (just the XML inside the APP1 XMP segment)
    <stem>-exif.bin        APP1 EXIF segment (FF E1 + length + "Exif\\0\\0" + TIFF body)
    <stem>-icc.icc         APP2 ICC profile body (just the .icc bytes, NOT the segment wrapper)
    <stem>-seft.bin        Samsung SEF trailer — full SEFH..SEFT span when both markers are
                           present, else just the 4-byte SEFT magic
    <stem>-summary.txt     Plain-text report (segment offsets, sizes, byte-orders, HDR detection)

When the input isn't an Ultra HDR JPEG (no gain-map, no MPF), the script still dumps whatever
segments are present and notes the SDR finding in the summary file.

No CropCenter imports — pure stdlib only. Run from anywhere with `py scripts/hdr_dump.py <path>`.
"""
import os
import struct
import sys


def find_primary_eoi(data, end_bound):
	"""Walk JPEG markers from SOI to the first EOI (FF D9). Returns the offset of the byte AFTER
	the EOI. Handles SOS / RST / stuffed-zero / fill-byte cases. Returns -1 when no EOI is found
	before end_bound — caller treats that as a malformed primary.
	"""
	pos = 2  # skip SOI
	while pos < end_bound - 1:
		if data[pos] != 0xFF:
			pos += 1
			continue
		marker = data[pos + 1]
		if marker == 0xD9:
			return pos + 2
		if marker == 0xDA:
			seglen = struct.unpack('>H', data[pos + 2:pos + 4])[0]
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
			break
		seglen = struct.unpack('>H', data[pos + 2:pos + 4])[0]
		pos += 2 + seglen
	return -1


def find_seft_trailer(data):
	"""Return the (start_offset, length) of the Samsung SEFT trailer when present, or None.

	Samsung's SEF trailer format is a pair: the 4-byte 'SEFH' header marks the start of the trailer
	block, and the 4-byte 'SEFT' magic marks the end. Between them live directory entries pointing
	at embedded thumbnails / depth maps / motion-photo segments. Earlier versions of this helper
	returned just the 4-byte 'SEFT' magic, which left every byte of the SEFH...SEFT body inside
	whatever the caller treated as "pre-trailer data" — for hdr_dump that meant the gainmap.jpg
	slice picked up SEFT payload bytes appended after the gainmap's actual EOI (Codex round-43 F3).
	Walk backward from SEFT looking for SEFH and report the full span. Cap the backward scan at
	1 MiB so a megabyte-scale gain-map JPEG can't false-match an inline SEFH literal in its entropy
	data; real Samsung SEF trailers run a few hundred bytes (depth-map directory entries are tiny).
	Falls back to the 4-byte magic when no SEFH is found.
	"""
	if len(data) < 4 or data[-4:] != b'SEFT':
		return None
	seft_pos = len(data) - 4
	search_start = max(0, seft_pos - 1024 * 1024)
	sefh_pos = data.rfind(b'SEFH', search_start, seft_pos)
	if sefh_pos < 0:
		return (seft_pos, 4)
	return (sefh_pos, len(data) - sefh_pos)


def find_last_eoi(data, start):
	"""Return the offset just past the LAST FF D9 marker in data at or after `start`, or -1.

	Used to bound the gain-map slice at the end of the gain-map JPEG, ignoring any trailer bytes
	(SEFT, scratch padding, manufacturer-specific footers) that may sit after the gain map's EOI.
	Samsung writes a SEFH...SEFT block between the gain-map EOI and the file end; relying on the
	SEFT magic's position would silently include that trailer in the gainmap.jpg dump. The last
	FF D9 in the file is unambiguously the gain map's EOI for Ultra HDR JPEGs (Codex round-43 F3).
	"""
	pos = data.rfind(b'\xff\xd9', start)
	if pos < 0:
		return -1
	return pos + 2


def list_segments(data, end_bound):
	"""Walk every APPn / COM segment between SOI and SOS, returning a list of dicts describing
	each segment's marker, offset, length, and a head excerpt for identification.
	"""
	segments = []
	pos = 2
	while pos < end_bound - 1:
		if data[pos] != 0xFF:
			pos += 1
			continue
		marker = data[pos + 1]
		if marker == 0xD9:  # EOI — done
			break
		if marker == 0xDA:  # SOS — entropy follows
			break
		if marker in (0x00, 0x01) or (0xD0 <= marker <= 0xD7):
			pos += 2
			continue
		if pos + 4 > end_bound:
			break
		seglen = struct.unpack('>H', data[pos + 2:pos + 4])[0]
		payload = data[pos + 4:pos + 2 + seglen]
		name = identify_segment(marker, payload)
		segments.append({
			'marker': marker,
			'name': name,
			'offset': pos,
			'length': seglen + 2,
			'payload_head': payload[:32],
		})
		pos += 2 + seglen
	return segments


def identify_segment(marker, payload):
	"""Guess a human-readable name for a JPEG APP segment based on its payload prefix. Recognises
	EXIF, XMP, Extended XMP, ICC, MPF, ISO 21496-1 (Ultra HDR primary identifier), Adobe JFIF/JFXX,
	and Samsung vendor blobs. Falls back to APPn / FFhh hex naming when unrecognised.
	"""
	if marker == 0xE0 and payload.startswith(b'JFIF\0'):
		return 'JFIF'
	if marker == 0xE0 and payload.startswith(b'JFXX\0'):
		return 'JFXX'
	if marker == 0xE1 and payload.startswith(b'Exif\0\0'):
		return 'EXIF'
	if marker == 0xE1 and payload.startswith(b'http://ns.adobe.com/xap/1.0/\0'):
		return 'XMP'
	if marker == 0xE1 and payload.startswith(b'http://ns.adobe.com/xmp/extension/\0'):
		return 'Extended-XMP'
	if marker == 0xE2 and payload.startswith(b'ICC_PROFILE\0'):
		return 'ICC'
	if marker == 0xE2 and payload.startswith(b'MPF\0'):
		return 'MPF'
	if marker == 0xE2 and payload.startswith(b'urn:iso:std:iso:ts:'):
		return 'ISO-21496-1 (Ultra HDR)'
	if 0xE0 <= marker <= 0xEF:
		return f'APP{marker - 0xE0}'
	if marker == 0xFE:
		return 'COM'
	return f'FF{marker:02X}'


def tiff_byte_order(payload):
	"""Decode the TIFF byte-order marker from an APP1 EXIF segment payload (which starts with
	'Exif\\0\\0' + TIFF header). Returns 'little', 'big', or 'unknown'. Also returns the magic word
	value to confirm TIFF integrity.
	"""
	if not payload.startswith(b'Exif\0\0') or len(payload) < 10:
		return ('n/a', 0)
	bo = payload[6:8]
	if bo == b'II':
		magic = struct.unpack('<H', payload[8:10])[0]
		return ('little', magic)
	if bo == b'MM':
		magic = struct.unpack('>H', payload[8:10])[0]
		return ('big', magic)
	return ('unknown', 0)


def write_file(path, data):
	"""Write `data` to `path`, creating parent directories as needed. Returns the absolute path of
	the written file for the summary log.
	"""
	os.makedirs(os.path.dirname(path), exist_ok=True)
	with open(path, 'wb') as f:
		f.write(data)
	return os.path.abspath(path)


def main(argv):
	if len(argv) < 2 or '-h' in argv or '--help' in argv:
		print(__doc__)
		return 2

	input_path = argv[1]
	if not os.path.isfile(input_path):
		print(f'ERROR: input file not found: {input_path}', file=sys.stderr)
		return 1

	stem = os.path.splitext(os.path.basename(input_path))[0]
	output_dir = argv[2] if len(argv) > 2 else f'hdr-dump-{stem}'

	with open(input_path, 'rb') as f:
		data = f.read()

	if len(data) < 4 or data[0] != 0xFF or data[1] != 0xD8:
		print(f'ERROR: {input_path} is not a JPEG (missing SOI marker)', file=sys.stderr)
		return 1

	# Find primary EOI and SEFT trailer first so we know the boundaries of the gain-map slice.
	# Bound the gain-map slice by the LAST FF D9 in the file rather than the SEFT trailer's start
	# offset — Samsung's SEFH...SEFT block sits AFTER the gain-map JPEG's EOI, so using SEFT as the
	# upper bound would include the trailer in the gainmap.jpg dump (Codex round-43 F3).
	seft_pos = find_seft_trailer(data)
	primary_eoi = find_primary_eoi(data, len(data))
	gainmap_eoi_search_start = primary_eoi if primary_eoi > 0 else 0
	gainmap_eoi = find_last_eoi(data, gainmap_eoi_search_start)
	# scan_end caps the gain-map slice — prefer the last JPEG EOI (cleanest cut), fall back to the
	# SEFT trailer start, then to EOF.
	if gainmap_eoi > 0:
		scan_end = gainmap_eoi
	elif seft_pos is not None:
		scan_end = seft_pos[0]
	else:
		scan_end = len(data)

	# Walk primary segments
	primary_segments = list_segments(data, primary_eoi if primary_eoi > 0 else scan_end)

	# Build summary lines as we extract — written to summary.txt at the end.
	summary = []
	summary.append(f'Input:          {os.path.abspath(input_path)}')
	summary.append(f'File size:      {len(data):,} bytes')
	summary.append(f'Primary EOI:    {primary_eoi:,}'
		if primary_eoi > 0 else 'Primary EOI:    NOT FOUND (file is structurally malformed)')
	summary.append(f'Gainmap EOI:    {gainmap_eoi:,}'
		if gainmap_eoi > 0 else 'Gainmap EOI:    absent (no secondary JPEG)')
	if seft_pos is None:
		summary.append('SEFT trailer:   absent')
	else:
		summary.append(f'SEFT trailer:   present at offset {seft_pos[0]:,} '
			f'({seft_pos[1]:,} bytes, '
			f'{"SEFH..SEFT span" if seft_pos[1] > 4 else "magic only — SEFH not found"})')
	summary.append('')
	summary.append('Primary segments:')
	exif_seg = None
	xmp_seg = None
	icc_seg = None
	mpf_seg = None
	iso_hdr_seg = None
	has_hdrgm_in_xmp = False
	for seg in primary_segments:
		bo = ''
		if seg['name'] == 'EXIF':
			order, magic = tiff_byte_order(data[seg['offset'] + 4:seg['offset'] + seg['length']])
			bo = f' byte-order={order} magic={magic}'
			exif_seg = seg
		if seg['name'] == 'XMP':
			xmp_seg = seg
			# Check for hdrgm in XMP body — that's how Ultra HDR is identified
			body = data[seg['offset'] + 4:seg['offset'] + seg['length']]
			has_hdrgm_in_xmp = b'hdrgm' in body
		if seg['name'] == 'ICC':
			icc_seg = seg
		if seg['name'] == 'MPF':
			mpf_seg = seg
		if seg['name'].startswith('ISO-21496-1'):
			iso_hdr_seg = seg
		summary.append(f'  {seg["name"]:24s} offset={seg["offset"]:8d} length={seg["length"]:7d}{bo}')

	is_ultra_hdr = mpf_seg is not None and has_hdrgm_in_xmp
	summary.append('')
	summary.append(f'Ultra HDR detection: {"YES" if is_ultra_hdr else "no"}  '
		f'(MPF present: {mpf_seg is not None}, hdrgm in XMP: {has_hdrgm_in_xmp})')
	summary.append('')

	# Extract files
	summary.append('Output files:')
	written = []

	# Primary JPEG — SOI through primary EOI
	if primary_eoi > 0:
		primary_bytes = data[:primary_eoi]
		path = write_file(os.path.join(output_dir, f'{stem}-primary.jpg'), primary_bytes)
		written.append(('primary', path, len(primary_bytes)))

	# Gain-map JPEG — bytes between primary EOI and SEFT (or EOF)
	if primary_eoi > 0 and primary_eoi < scan_end:
		gainmap_bytes = data[primary_eoi:scan_end]
		# Confirm the gain-map slice is actually a JPEG (FF D8 prefix). Some SDR sources have
		# trailing bytes after primary EOI that aren't a gain map — log but still dump.
		is_jpeg = len(gainmap_bytes) >= 2 and gainmap_bytes[0] == 0xFF and gainmap_bytes[1] == 0xD8
		path = write_file(os.path.join(output_dir, f'{stem}-gainmap.jpg'), gainmap_bytes)
		written.append((f'gainmap{"" if is_jpeg else " (NOT a JPEG — likely trailing noise)"}',
			path, len(gainmap_bytes)))

	# MPF segment — full segment bytes (FF E2 + length + payload)
	if mpf_seg is not None:
		mpf_bytes = data[mpf_seg['offset']:mpf_seg['offset'] + mpf_seg['length']]
		path = write_file(os.path.join(output_dir, f'{stem}-mpf.bin'), mpf_bytes)
		written.append(('mpf', path, len(mpf_bytes)))

	# XMP HDR descriptor — just the XML body (after the namespace identifier)
	if xmp_seg is not None:
		xmp_full = data[xmp_seg['offset'] + 4:xmp_seg['offset'] + xmp_seg['length']]
		# Strip the "http://ns.adobe.com/xap/1.0/\0" prefix to get just the XML
		nul = xmp_full.find(b'\0')
		xmp_body = xmp_full[nul + 1:] if nul >= 0 else xmp_full
		path = write_file(os.path.join(output_dir, f'{stem}-xmp-hdr.xml'), xmp_body)
		written.append(('xmp-hdr', path, len(xmp_body)))

	# EXIF segment — full segment bytes
	if exif_seg is not None:
		exif_bytes = data[exif_seg['offset']:exif_seg['offset'] + exif_seg['length']]
		path = write_file(os.path.join(output_dir, f'{stem}-exif.bin'), exif_bytes)
		written.append(('exif', path, len(exif_bytes)))

	# ICC profile — just the .icc bytes (strip the "ICC_PROFILE\0" + chunk-index/total prefix)
	if icc_seg is not None:
		icc_full = data[icc_seg['offset'] + 4:icc_seg['offset'] + icc_seg['length']]
		# ICC APP2: "ICC_PROFILE\0" (12 bytes) + chunkIdx (1 byte) + chunkTotal (1 byte) + profile
		if len(icc_full) > 14:
			icc_body = icc_full[14:]
			path = write_file(os.path.join(output_dir, f'{stem}-icc.icc'), icc_body)
			written.append(('icc', path, len(icc_body)))

	# SEFT trailer — full SEFH..SEFT span when both markers are present, else just the 4-byte magic.
	if seft_pos is not None:
		seft_bytes = data[seft_pos[0]:seft_pos[0] + seft_pos[1]]
		path = write_file(os.path.join(output_dir, f'{stem}-seft.bin'), seft_bytes)
		written.append(('seft', path, len(seft_bytes)))

	for label, path, size in written:
		summary.append(f'  {label:10s} {size:>12,} bytes  ->{path}')

	# Write summary
	summary_text = '\n'.join(summary)
	summary_path = write_file(os.path.join(output_dir, f'{stem}-summary.txt'),
		summary_text.encode('utf-8'))

	# Echo summary to stdout too so the caller sees results immediately
	print(summary_text)
	print(f'\nSummary written to: {summary_path}')
	return 0


if __name__ == '__main__':
	sys.exit(main(sys.argv))
