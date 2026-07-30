"""Shared helpers for the graft-diagnostic scripts.

Pulls the JPEG marker walker, SEFT-aware EOI scan, EXIF orientation lookup, gain-map extraction, and heat color ramp out
of the per-script boilerplate that grew during the HDR-graft / inpainter investigation. Each graft_*.py script under
scripts/ is a thin wrapper around these primitives plus a question-specific report or visualization. The primary-EOI
walk itself lives in the sibling jpeg_lib (shared with the pure-stdlib diagnostic scripts).

No CropCenter imports — pure stdlib + PIL + numpy + piexif so the scripts stay runnable without an Android build
environment.
"""
import io
import os
import struct

import numpy as np
import piexif
from PIL import Image

import jpeg_lib
import seft_lib


# Apply-EXIF transposes keyed by orientation tag value (1..8). Mirrors what Image.exif_transpose does for a single tag,
# but lets us apply a known orientation to gain-map bitmaps that PIL won't auto-rotate (no EXIF on the gain-map JPEG
# itself).
_TRANSPOSE = {
	1: None,
	2: Image.FLIP_LEFT_RIGHT,
	3: Image.ROTATE_180,
	4: Image.FLIP_TOP_BOTTOM,
	5: Image.TRANSPOSE,
	6: Image.ROTATE_270,  # source stored landscape → display portrait
	7: Image.TRANSVERSE,
	8: Image.ROTATE_90,
}


def diff_to_heat_image(diff_arr, max_val=30):
	"""Render a 2D uint8 diff array as a heat-colored PIL Image (0=black, 1..max_val scaled through
	red→yellow→white). Pixels above max_val saturate white. Matches the scale we've been using for gain-map diff
	visualization where most non-zero pixels land in the 1-2 range and the eye benefits from low-end visibility.
	"""
	h, w = diff_arr.shape
	out = np.zeros((h, w, 3), dtype=np.uint8)
	if diff_arr.max() == 0:
		return Image.fromarray(out)
	for v in range(int(diff_arr.max()) + 1):
		if v == 0:
			continue
		out[diff_arr == v] = heat(v, max_val)
	return Image.fromarray(out)


def extract_dqt_tables(jpeg_bytes):
	"""Pull all 64-entry quantization tables out of DQT (FFDB) segments. Returns a list of (table_id, precision,
	np.array) tuples. Used by the JPEG noise control to compare source's Samsung Q tables against the graft's Skia Q
	tables — the all-1s Skia tables confirm Skia is encoding the gain-map at effectively quality 100 regardless of
	the primary's quality parameter.
	"""
	tables = []
	pos = 2
	while pos < len(jpeg_bytes) - 1:
		if jpeg_bytes[pos] != 0xFF:
			pos += 1
			continue
		marker = jpeg_bytes[pos + 1]
		if marker == 0xD9 or marker == 0xDA:
			break
		if marker == 0xDB:
			seglen = struct.unpack('>H', jpeg_bytes[pos + 2:pos + 4])[0]
			payload = jpeg_bytes[pos + 4:pos + 2 + seglen]
			i = 0
			while i < len(payload):
				pq_tq = payload[i]
				precision = pq_tq >> 4
				table_id = pq_tq & 0x0F
				i += 1
				qlen = 128 if precision == 1 else 64
				dtype = np.uint16 if precision == 1 else np.uint8
				table = np.frombuffer(payload[i:i + qlen], dtype=dtype).copy()
				tables.append((table_id, precision, table))
				i += qlen
			pos += 2 + seglen
			continue
		if marker in (0x00, 0x01) or (0xD0 <= marker <= 0xD7):
			pos += 2
			continue
		if pos + 4 > len(jpeg_bytes):
			break
		seglen = struct.unpack('>H', jpeg_bytes[pos + 2:pos + 4])[0]
		pos += 2 + seglen
	return tables


def extract_gain_map(path):
	"""Return the gain-map JPEG bytes embedded after the primary's EOI, or None when the source carries no gain map
	or the content boundary can't be established (unparseable SEF trailer — fail closed rather than slicing SEF data
	blocks, which can embed whole JPEGs, into the "gain map"). Strips the SEFT trailer first via find_end_boundary.
	"""
	data = read_bytes(path)
	end = find_end_boundary(data)
	if end is None:
		return None
	pe = jpeg_lib.find_primary_eoi(data, end)
	return data[pe:end] if pe >= 0 else None


def find_end_boundary(data):
	"""Return the offset just past the LAST FFD9 in the content span (primary + gain-map block), bounded at the SEFT
	trailer start when a trailer is present. SEFT data blocks can embed whole JPEGs, so an unbounded backward FFD9
	scan could land on an EOI inside the trailer — seft_lib walks the SEFH directory for the true trailer start.
	Falls back to the trailer start (or the file length) when no EOI lands in the content span. Returns None when a
	SEFT footer is present but the SEFH directory doesn't parse — the trailer start is unresolvable and callers must
	fail closed (treat the gain-map region as unknown).
	"""
	end = seft_lib.content_end(data)
	if end is None:
		return None
	eoi = seft_lib.find_last_eoi(data, 0, end)
	return eoi if eoi > 0 else end


def heat(value, max_val=30):
	"""RGB triple along a black → red → yellow → white ramp scaled so 0..max_val covers the range and values above
	saturate white. Same ramp diff_to_heat_image uses.
	"""
	v = min(value / max_val, 1.0)
	if v < 0.33:
		t = v / 0.33
		return (int(255 * t), 0, 0)
	if v < 0.66:
		t = (v - 0.33) / 0.33
		return (255, int(255 * t), 0)
	t = (v - 0.66) / 0.34
	return (255, 255, int(255 * t))


def list_jpeg_segments(data, end_bound):
	"""Walk every APPn / SOS / EOI marker in the JPEG bytes up to end_bound and return a list of (name, offset,
	length, payload_head_30) tuples. Used by the analyzer to dump the segment shape for visual inspection — the
	analyzer's "Primary APP segments" report relies on this to confirm UHDR APPs (XMP hdrgm, ISO 21496-1, MPF) are
	present.
	"""
	out = []
	pos = 2
	while pos < end_bound - 1:
		if data[pos] != 0xFF:
			pos += 1
			continue
		marker = data[pos + 1]
		if marker == 0xD9:
			out.append(('EOI', pos, 2, b''))
			pos += 2
			continue
		if marker == 0xDA:
			seglen = struct.unpack('>H', data[pos + 2:pos + 4])[0]
			payload = data[pos + 4:pos + 2 + seglen][:30]
			out.append(('SOS', pos, seglen + 2, payload))
			scan = pos + 2 + seglen
			while scan < end_bound - 1:
				if data[scan] != 0xFF:
					scan += 1
					continue
				nxt = data[scan + 1]
				if nxt == 0xD9 or (0xC0 <= nxt <= 0xCF and nxt not in (0xC4, 0xC8, 0xCC)):
					break
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
		payload = data[pos + 4:pos + 2 + seglen][:30]
		name = f'APP{marker - 0xE0}' if 0xE0 <= marker <= 0xEF else f'FF{marker:02X}'
		out.append((name, pos, seglen + 2, payload))
		pos += 2 + seglen
	return out


def load_stored_downsampled(path, sample_size):
	"""Load an image WITHOUT applying EXIF orientation, downsampled by sample_size. Mirrors what Java's
	BitmapFactory.decodeByteArray does with inSampleSize — used by the AI mask reconstruction step where we need
	source coordinates aligned to the stored byte stream the Java decoder sees, not the display orientation PIL
	would auto-apply.
	"""
	img = Image.open(path)
	w, h = img.size
	resized = img.resize((max(1, w // sample_size), max(1, h // sample_size)),
		Image.LANCZOS).convert('RGB')
	return np.asarray(resized, dtype=np.int16)


def primary_orient(path):
	"""Read the primary's EXIF Orientation tag (1..8). Defaults to 1 when piexif can't parse (raw-bytes-only
	sources, malformed EXIF). Used to reorient gain-map bitmaps for diff comparison since PIL doesn't auto-rotate
	gain-map data on its own.
	"""
	try:
		ex = piexif.load(path)
		return (ex.get('0th') or {}).get(piexif.ImageIFD.Orientation, 1) or 1
	except Exception:
		return 1


def read_bytes(path):
	"""Read a file as bytes — trivial wrapper for symmetry with the other I/O helpers."""
	with open(path, 'rb') as f:
		return f.read()


def reorient(img, orientation):
	"""Apply an EXIF orientation transpose to a PIL Image. Returns the input unchanged for orientation=1 or
	unrecognized values.
	"""
	op = _TRANSPOSE.get(orientation)
	return img.transpose(op) if op is not None else img


def reorient_to_match_source(stored_arr, src_orient, edit_orient, target_shape):
	"""Reorient the edit's stored array so its shape matches source's stored shape.

	When source is orient=6 (landscape stored) and edit is orient=1 (portrait stored), or any other shape-swap pair,
	GraftController.alignEditToOriginalLayout has already rewritten the edit JPEG to source's stored layout. The
	diag scripts that load the original e1 (pre-graft) need to apply the same alignment locally so the per-pixel
	diff is well-defined. Returns a numpy int16 RGB array in source's stored shape.
	"""
	if stored_arr.shape == target_shape:
		return stored_arr
	# Rotation direction is determined by the orient pair. The mapping below covers the Samsung pairs we've actually
	# hit (orient=6 landscape source vs orient=1 portrait edit, and the reverse). Other combinations fall back to a
	# 90° CCW which matches the most common Samsung→Photoshop interaction.
	if src_orient == 6 and edit_orient == 1:
		op = Image.ROTATE_90
	elif src_orient == 1 and edit_orient == 6:
		op = Image.ROTATE_270
	elif src_orient == 8 and edit_orient == 1:
		op = Image.ROTATE_270
	else:
		op = Image.ROTATE_90
	pil = Image.fromarray(stored_arr.astype(np.uint8)).transpose(op)
	return np.asarray(pil.convert('RGB'), dtype=np.int16)


def stem_paths(stem, base_dir):
	"""Resolve the (source, edit, graft) absolute paths for a stem. Convention:
	  <stem>.jpg          source primary
	  <stem>e1.jpg        Photoshop AI edit (the e1 stands for "edit 1")
	  <stem>-graft.jpg    CropCenter graft output
	Returns a 3-tuple of absolute paths (no existence check — caller decides).
	"""
	return (
		os.path.join(base_dir, f'{stem}.jpg'),
		os.path.join(base_dir, f'{stem}e1.jpg'),
		os.path.join(base_dir, f'{stem}-graft.jpg'),
	)
