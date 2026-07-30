"""Per-file HDR-structure analyser for Samsung Ultra HDR JPEGs.

Usage:
    python scripts/hdr_analyze.py <input.jpg> [more.jpg ...]

For each input JPEG, walks the file in-place (no separate dump step required) and prints a multi-section human-readable
report. Section header is `=== <stem> ===`; within the section, each diagnostic line is `key=value` (sometimes
multi-line for nested mpf_entry rows). The report is grep-friendly but not strictly one-record-per-file machine-readable
— designed for eyeball scanning of structural anomalies, not pipeline consumption. A file that fails to decode prints an
`ERROR <file>: ...` line in its own section and the batch continues (exit code 1 when any file errored).

Extracted per file:
  - file_size, primary_eoi, gainmap_eoi, gainmap_size_actual
  - primary_sof = (width, height, components)
  - gainmap_sof + gainmap_segments (own EXIF / XMP / ICC?)
  - gainmap_xmp_len / sample (when the gain map carries its own XMP packet)
  - MPF: byte order, num_images, num_entries, per-entry (mp_type, size, dataOffset), plus mpf_malformed lines for
    structural anomalies that leave partial results (truncated IFD0 / MPEntry tables, off-spec MPEntry byte count)
  - ISO-21496-1 36-byte signaling block (hex)
  - XMP body: total bytes, text length, parsed attributes (hdrgm:*, Item:Length, GContainer entries),
    plus extended_xmp_present + length when an Adobe Extended XMP chunk is present.

Pure stdlib (plus the sibling seft_lib helper); no PIL. Note: sibling `hdr_dump.py` writes per-file dump directories of
raw segment bytes for offline inspection — `hdr_analyze.py` does its own walking and does NOT consume those dump dirs.
"""
import os
import re
import struct
import sys

import jpeg_lib
import seft_lib


def find_sof(jpeg_bytes, end_bound=None):
	"""Return (width, height, components, sof_offset) of the first SOF marker (FFC0..FFC3, FFC5..FFC7, FFC9..FFCB,
	FFCD..FFCF) in jpeg_bytes, or None.
	"""
	if end_bound is None:
		end_bound = len(jpeg_bytes)
	pos = 2
	while pos < end_bound - 1:
		if jpeg_bytes[pos] != 0xFF:
			pos += 1
			continue
		marker = jpeg_bytes[pos + 1]
		if marker == 0xD9 or marker == 0xDA:
			return None
		if marker in (0x00, 0x01) or (0xD0 <= marker <= 0xD7):
			pos += 2
			continue
		if pos + 4 > end_bound:
			return None
		seglen = struct.unpack('>H', jpeg_bytes[pos + 2:pos + 4])[0]
		# SOF markers: C0..CF excluding C4 (DHT), C8 (JPG), CC (DAC)
		if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
			# SOF: precision (1), height (2), width (2), components (1)
			height = struct.unpack('>H', jpeg_bytes[pos + 5:pos + 7])[0]
			width = struct.unpack('>H', jpeg_bytes[pos + 7:pos + 9])[0]
			components = jpeg_bytes[pos + 9]
			return (width, height, components, pos)
		pos += 2 + seglen
	return None


def list_segments(jpeg_bytes):
	"""Walk all APP/COM segments in jpeg_bytes between SOI and SOS."""
	segments = []
	pos = 2
	end_bound = len(jpeg_bytes)
	while pos < end_bound - 1:
		if jpeg_bytes[pos] != 0xFF:
			pos += 1
			continue
		marker = jpeg_bytes[pos + 1]
		if marker == 0xD9 or marker == 0xDA:
			break
		if marker in (0x00, 0x01) or (0xD0 <= marker <= 0xD7):
			pos += 2
			continue
		if pos + 4 > end_bound:
			break
		seglen = struct.unpack('>H', jpeg_bytes[pos + 2:pos + 4])[0]
		payload = jpeg_bytes[pos + 4:pos + 2 + seglen]
		segments.append({'marker': marker, 'offset': pos, 'length': seglen + 2, 'payload': payload})
		pos += 2 + seglen
	return segments


def identify(marker, payload):
	if marker == 0xE0 and payload.startswith(b'JFIF\0'):
		return 'JFIF'
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
		return 'ISO-21496-1'
	if marker == 0xE0 and payload.startswith(b'JFXX\0'):
		return 'JFXX'
	if 0xE0 <= marker <= 0xEF:
		return f'APP{marker - 0xE0}'
	return f'FF{marker:02X}'


def decode_mpf(mpf_seg_bytes):
	"""Decode the MPF segment payload. Returns a dict with entries list, header info, and a 'malformed' list of
	structural-anomaly strings (empty when clean): a truncated IFD0 entry table, an MPEntry byte count not divisible
	by 16, and an MPEntry table running past the TIFF body each land there, so partial results are explicitly
	labelled instead of looking complete. Returns a dict carrying only an 'error' key when the TIFF body is
	truncated, the byte-order marker or magic is invalid, or the IFD0 offset points out of range — bounds-checked so
	a malformed segment degrades to an mpf_error report line instead of a struct.error aborting the file's report.
	"""
	# header is 4 bytes (FFE2 + length(2) ) + 'MPF\0' (4 bytes) = 8 bytes
	tiff = mpf_seg_bytes[8:]
	if len(tiff) < 8:
		return {'error': f'TIFF body truncated ({len(tiff)} bytes)'}
	bo = tiff[:2]
	if bo not in (b'II', b'MM'):
		return {'error': f'bad TIFF byte-order marker {bo!r}'}
	order = '<' if bo == b'II' else '>'
	magic = struct.unpack(order + 'H', tiff[2:4])[0]
	if magic != 42:
		return {'error': f'bad TIFF magic {magic} (expected 42)'}
	ifd0_off = struct.unpack(order + 'I', tiff[4:8])[0]
	if ifd0_off + 2 > len(tiff):
		return {'error': f'IFD0 offset {ifd0_off} out of range ({len(tiff)}-byte TIFF body)'}
	malformed = []
	n = struct.unpack(order + 'H', tiff[ifd0_off:ifd0_off + 2])[0]
	ent_base = ifd0_off + 2
	tags = {}
	for i in range(n):
		e = ent_base + i * 12
		if e + 12 > len(tiff):
			# Truncated entry table — keep the tags parsed so far, but say so instead of letting the partial
			# tag set read as a complete IFD.
			malformed.append(f'IFD0 entry table truncated ({i} of {n} entries in TIFF body)')
			break
		tag = struct.unpack(order + 'H', tiff[e:e + 2])[0]
		typ = struct.unpack(order + 'H', tiff[e + 2:e + 4])[0]
		cnt = struct.unpack(order + 'I', tiff[e + 4:e + 8])[0]
		val_off = struct.unpack(order + 'I', tiff[e + 8:e + 12])[0]
		tags[tag] = (typ, cnt, val_off)
	# Number of images is tag 0xB001
	num_images = tags.get(0xB001, (0, 0, 0))[2]
	# MPEntry tag 0xB002 — the data is at val_off in the TIFF; each entry is 16 bytes
	entries = []
	if 0xB002 in tags:
		typ, cnt, val_off = tags[0xB002]
		# val_off is offset from TIFF start; each entry = 16 bytes
		ent_off = val_off
		if cnt % 16 != 0:
			malformed.append(f'MPEntry byte count {cnt} not divisible by 16')
		num_entries = cnt // 16
		for i in range(num_entries):
			off = ent_off + i * 16
			if off + 16 > len(tiff):
				# Entry table runs past the TIFF body — keep the entries parsed so far, explicitly
				# flagged as a partial table.
				malformed.append(
					f'MPEntry table truncated ({i} of {num_entries} entries in TIFF body)')
				break
			# Each entry: ImageAttr(4) + ImageSize(4) + DataOffset(4) + Dependent1(2) + Dependent2(2)
			img_attr = struct.unpack(order + 'I', tiff[off:off + 4])[0]
			img_size = struct.unpack(order + 'I', tiff[off + 4:off + 8])[0]
			data_off = struct.unpack(order + 'I', tiff[off + 8:off + 12])[0]
			dep1 = struct.unpack(order + 'H', tiff[off + 12:off + 14])[0]
			dep2 = struct.unpack(order + 'H', tiff[off + 14:off + 16])[0]
			# MPType is the lower 24 bits of ImageAttr; upper byte is flags
			mp_type = img_attr & 0xFFFFFF
			flags = (img_attr >> 24) & 0xFF
			entries.append({
				'index': i,
				'image_attr': img_attr,
				'mp_type': mp_type,
				'flags': flags,
				'image_size': img_size,
				'data_offset': data_off,
				'dep1': dep1,
				'dep2': dep2,
			})
	return {
		'byte_order': 'little' if order == '<' else 'big',
		'magic': magic,
		'ifd0_offset': ifd0_off,
		'tags': tags,
		'num_images': num_images,
		'entries': entries,
		'malformed': malformed,
	}


def decode_iso_hdr(seg_bytes):
	"""Decode the ISO 21496-1 36-byte signaling segment payload (after the FFE2/length header). The full segment
	body (after the urn: identifier) is what we want; length is the segment's len.
	"""
	# Segment is FFE2 + length + 'urn:iso:std:iso:ts:21496:-1\0' + body. The NUL search finds the end of the
	# identifier.
	nul = seg_bytes.find(b'\0', 4)
	body = seg_bytes[nul + 1:]
	return body


def parse_xmp_attrs(xmp):
	"""Pull HDR-relevant attributes out of the XMP body using regex (no XML parser dep)."""
	attrs = {}
	if isinstance(xmp, bytes):
		xmp = xmp.decode('utf-8', errors='replace')
	# Look for the hdrgm namespace declaration
	attrs['has_hdrgm_ns'] = 'xmlns:hdrgm=' in xmp or 'hdrgm:Version' in xmp
	attrs['has_container_ns'] = 'xmlns:Container=' in xmp or 'Container:' in xmp
	attrs['has_item_ns'] = 'xmlns:Item=' in xmp or 'Item:' in xmp
	# Common hdrgm and Item attributes
	for attr in [
		'hdrgm:Version',
		'hdrgm:GainMapMin',
		'hdrgm:GainMapMax',
		'hdrgm:Gamma',
		'hdrgm:OffsetSDR',
		'hdrgm:OffsetHDR',
		'hdrgm:HDRCapacityMin',
		'hdrgm:HDRCapacityMax',
		'hdrgm:BaseRenditionIsHDR',
		'Item:Length',
		'Item:Semantic',
		'Item:Mime',
		'Container:Directory',
	]:
		# Match either "attr=" or attr-as-element ("<attr>") — we only check presence and try to pull attribute
		# values for Item:Length specifically.
		if attr + '=' in xmp:
			# Pull all values for that attr name (may appear multiple times — Item:Length appears once per
			# directory item)
			vals = re.findall(re.escape(attr) + r'\s*=\s*"([^"]*)"', xmp)
			attrs[attr] = vals
	# HasExtendedXMP — Adobe convention: the standard XMP packet declares xmpNote:HasExtendedXMP="GUID"
	guid = re.search(r'xmpNote:HasExtendedXMP\s*=\s*"([^"]*)"', xmp)
	if guid:
		attrs['HasExtendedXMP'] = guid.group(1)
	# Roll / Tilt — used by HorizonDetector
	for attr in ['exif:CameraRoll', 'exif:CameraTilt', 'tiff:Roll', 'tiff:Tilt']:
		m = re.search(re.escape(attr) + r'\s*=\s*"([^"]*)"', xmp)
		if m:
			attrs[attr] = m.group(1)
	# Look for any *:Roll= or *:Tilt= attribute (Samsung uses non-standard prefixes sometimes)
	rolls = re.findall(r'(\w+:Roll)\s*=\s*"([^"]*)"', xmp)
	tilts = re.findall(r'(\w+:Tilt)\s*=\s*"([^"]*)"', xmp)
	if rolls:
		attrs['_rolls'] = rolls
	if tilts:
		attrs['_tilts'] = tilts
	return attrs


def analyze_file(input_path):
	"""Analyse one JPEG and print its `=== <stem> ===` report section. Raises on unreadable or structurally hostile
	input — main() converts that into a per-file ERROR line and continues the batch.
	"""
	stem = os.path.splitext(os.path.basename(input_path))[0]
	print(f'\n=== {stem} ===')
	# Load the original to recompute byte layout
	with open(input_path, 'rb') as f:
		data = f.read()
	print(f'file_size={len(data)}')
	# Walk all APP segments up to SOS / EOI; the MPF / ISO-21496-1 / XMP segments are picked out of the list below.
	segs = list_segments(data)
	primary_eoi = jpeg_lib.find_primary_eoi(data)
	# Last EOI in the content span = gain-map EOI. The scan is bounded at the SEFT trailer start (SEFH directory
	# walk via seft_lib) — SEF data blocks can embed whole JPEGs and MP4 bytes, so an unbounded rfind could land on
	# an FFD9 inside the trailer. A None content end means the SEFT footer is present but the SEFH directory doesn't
	# parse: the trailer start is unresolvable, so fail closed and skip the scan.
	scan_end = seft_lib.content_end(data)
	if scan_end is None:
		print('SEF trailer unparseable — gain-map scan skipped')
		gainmap_eoi = -1
	else:
		gainmap_eoi = seft_lib.find_last_eoi(data, max(primary_eoi, 0), scan_end)
	print(f'primary_eoi={primary_eoi}')
	print(f'gainmap_eoi={gainmap_eoi}')
	if primary_eoi > 0 and gainmap_eoi > 0:
		gainmap_size_actual = gainmap_eoi - primary_eoi
	else:
		gainmap_size_actual = 0
	print(f'gainmap_size_actual={gainmap_size_actual}')
	# Primary SOF
	prim = find_sof(data[:primary_eoi]) if primary_eoi > 0 else None
	print(f'primary_sof={prim}')
	# Gain-map SOF — look in the gain-map slice
	if primary_eoi > 0 and gainmap_eoi > primary_eoi:
		gm = data[primary_eoi:gainmap_eoi]
		gsof = find_sof(gm)
		print(f'gainmap_sof={gsof}')
		# Walk gain-map's own segments
		gm_segs = list_segments(gm)
		gm_seg_names = [identify(s['marker'], s['payload']) for s in gm_segs]
		print(f'gainmap_segments={gm_seg_names}')
		# If gain-map has its own XMP — print its body
		for s in gm_segs:
			name = identify(s['marker'], s['payload'])
			if name in ('XMP', 'Extended-XMP'):
				body = s['payload']
				nul = body.find(b'\0')
				body = body[nul + 1:] if nul >= 0 else body
				print(f'gainmap_xmp_len={len(body)}')
				sample = body[:600].decode('utf-8', errors='replace').replace('\n', ' ')
				print(f'gainmap_xmp_sample={sample!r}')
	# MPF
	mpf_seg = None
	iso_seg = None
	xmp_seg = None
	ext_xmp_seg = None
	for seg in segs:
		n = identify(seg['marker'], seg['payload'])
		if n == 'MPF':
			mpf_seg = seg
		elif n == 'ISO-21496-1':
			iso_seg = seg
		elif n == 'XMP':
			xmp_seg = seg
		elif n == 'Extended-XMP':
			ext_xmp_seg = seg
	# MPF decode
	if mpf_seg:
		full_mpf = data[mpf_seg['offset']:mpf_seg['offset'] + mpf_seg['length']]
		mpf = decode_mpf(full_mpf)
		if 'error' in mpf:
			print(f'mpf_error={mpf["error"]}')
		else:
			print(f'mpf_byte_order={mpf["byte_order"]} num_images={mpf["num_images"]}'
				f' num_entries={len(mpf["entries"])}')
			# Structural anomalies from a decode that still yielded partial results (truncated IFD0 /
			# MPEntry tables, byte count not divisible by 16) — printed per anomaly so a partial table can't
			# read as a complete one.
			for issue in mpf['malformed']:
				print(f'mpf_malformed={issue}')
			# MPF entry dataOffset is relative to the MP Endian field START — mpf_seg.offset + 8, past the
			# FF E2 marker, u16 segment length, and 'MPF\0' id — matching MpfPatcher. Primary entries
			# (MPType 0x030000) store dataOffset 0.
			mpf_data_base = mpf_seg['offset'] + 8  # MP Endian field position
			for ent in mpf['entries']:
				abs_off = mpf_data_base + ent['data_offset'] if ent['data_offset'] else 0
				marker_at = data[abs_off:abs_off + 2] if abs_off > 0 else b''
				print(f'  mpf_entry idx={ent["index"]} mp_type=0x{ent["mp_type"]:06X} '
					f'flags=0x{ent["flags"]:02X} image_size={ent["image_size"]} '
					f'data_offset={ent["data_offset"]} abs_off={abs_off} '
					f'marker_at={marker_at.hex() if marker_at else "n/a"} '
					f'dep=({ent["dep1"]},{ent["dep2"]})')
	# ISO 21496-1
	if iso_seg:
		body = decode_iso_hdr(data[iso_seg['offset']:iso_seg['offset'] + iso_seg['length']])
		print(f'iso21496_body_len={len(body)} body_hex={body.hex()}')
	# XMP
	if xmp_seg:
		full = data[xmp_seg['offset'] + 4:xmp_seg['offset'] + xmp_seg['length']]
		nul = full.find(b'\0')
		body = full[nul + 1:] if nul >= 0 else full
		# Strip trailing whitespace padding for display
		text = body.decode('utf-8', errors='replace')
		# Note actual text length vs total body length
		text_stripped = text.rstrip(' \t\n\0')
		print(f'xmp_body_total={len(body)} xmp_text_len={len(text_stripped)}')
		attrs = parse_xmp_attrs(text)
		for k, v in attrs.items():
			print(f'  xmp.{k} = {v}')
	else:
		print('xmp_seg=ABSENT')
	if ext_xmp_seg:
		print(f'extended_xmp_present length={ext_xmp_seg["length"]}')


def main(argv):
	if len(argv) < 2:
		print('usage: hdr_analyze.py <input.jpg> [more.jpg ...]', file=sys.stderr)
		return 2
	had_errors = False
	for input_path in argv[1:]:
		try:
			analyze_file(input_path)
		except Exception as e:
			# One malformed file (unreadable path, truncated segment, out-of-range offset) must not kill the
			# batch — report it inside the file's own section and keep analysing the rest of argv.
			print(f'ERROR {input_path}: {e}')
			had_errors = True
	return 1 if had_errors else 0


if __name__ == '__main__':
	sys.exit(main(sys.argv))
