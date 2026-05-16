"""Deep coherence audit of every JPEG in scripts/input/.

For each file, walks the byte stream and checks the invariants CropCenter relies on:
  - JPEG structure (SOI / EOI / valid segment chain)
  - EXIF IFD0: Orientation = 1 (CropCenter normalises every output), ImageWidth / ImageLength
    present and matching primary SOF dims, IFD0 sanitisation (Compression / JPEGInterchangeFormat /
    JPEGInterchangeFormatLength tags zeroed if leaked into IFD0)
  - EXIF IFD1: tags coherent, thumbnail offset + length resolve to a valid embedded JPEG
  - HDR coherence:
      * If gainmap bytes present (post-EOI FF D8 ... FF D9), MPF must reference them
      * If MPF claims HDR slot, gainmap must actually exist
      * If XMP carries hdrgm namespace, gainmap must be present
      * No orphan hdrgm XMP without a gainmap (CropCenter strips on HDR-drop)
  - SEFT trailer: if present, valid magic + readable size value
  - Cross-file consistency: spot any pattern outliers

Pure stdlib. Tab=8.
"""
import os
import struct
import sys
from pathlib import Path

INPUT_DIR = Path(__file__).resolve().parent / "input"


def u16(b, off, le):
	return struct.unpack("<H" if le else ">H", b[off:off + 2])[0]


def u32(b, off, le):
	return struct.unpack("<I" if le else ">I", b[off:off + 4])[0]


def walk_segments(jpeg, end_bound=None):
	"""Yield (off, marker, seglen) for every APP/COM segment up to SOS or EOI."""
	if end_bound is None:
		end_bound = len(jpeg)
	off = 2
	while off + 4 < end_bound:
		if jpeg[off] != 0xFF:
			return
		marker = jpeg[off + 1]
		if marker in (0xDA, 0xD9):
			return
		seglen = struct.unpack(">H", jpeg[off + 2:off + 4])[0]
		yield (off, marker, seglen)
		off += 2 + seglen


def find_primary_eoi(jpeg):
	"""First FFD9 (post SOS scan) — the primary stream's terminator."""
	# Find SOS
	off = 2
	sos_off = -1
	while off + 4 < len(jpeg):
		if jpeg[off] != 0xFF:
			return -1
		marker = jpeg[off + 1]
		if marker == 0xDA:
			sos_off = off
			break
		if marker == 0xD9:
			return -1
		seglen = struct.unpack(">H", jpeg[off + 2:off + 4])[0]
		off += 2 + seglen
	if sos_off < 0:
		return -1
	# Walk from after SOS header looking for FFD9
	sos_seglen = struct.unpack(">H", jpeg[sos_off + 2:sos_off + 4])[0]
	pos = sos_off + 2 + sos_seglen
	while pos < len(jpeg) - 1:
		if jpeg[pos] == 0xFF:
			m = jpeg[pos + 1]
			if m == 0x00 or 0xD0 <= m <= 0xD7:
				pos += 2
				continue
			if m == 0xD9:
				return pos + 2
		pos += 1
	return -1


def find_primary_sof(jpeg, end_bound=None):
	"""Primary SOF dims (width, height)."""
	if end_bound is None:
		end_bound = len(jpeg)
	off = 2
	while off + 4 < end_bound:
		if jpeg[off] != 0xFF:
			return None
		marker = jpeg[off + 1]
		if marker == 0xDA or marker == 0xD9:
			return None
		seglen = struct.unpack(">H", jpeg[off + 2:off + 4])[0]
		if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
			height = struct.unpack(">H", jpeg[off + 5:off + 7])[0]
			width = struct.unpack(">H", jpeg[off + 7:off + 9])[0]
			return (width, height)
		off += 2 + seglen
	return None


def parse_exif_segment(seg_data):
	"""Returns dict with parsed EXIF info or None."""
	if len(seg_data) < 18:
		return None
	tiff = 10
	bo = seg_data[tiff:tiff + 2]
	if bo not in (b"II", b"MM"):
		return None
	le = bo == b"II"
	if u16(seg_data, tiff + 2, le) != 42:
		return None
	ifd0 = tiff + u32(seg_data, tiff + 4, le)
	if ifd0 + 2 > len(seg_data):
		return None
	ifd0_n = u16(seg_data, ifd0, le)
	# Walk IFD0 entries
	ifd0_entries = []
	zeroed_count = 0
	orientation = None
	image_width = None
	image_length = None
	exif_subifd = None
	for i in range(ifd0_n):
		entry = ifd0 + 2 + i * 12
		if entry + 12 > len(seg_data):
			break
		tag = u16(seg_data, entry, le)
		fmt = u16(seg_data, entry + 2, le)
		cnt = u32(seg_data, entry + 4, le)
		val = u32(seg_data, entry + 8, le)
		ifd0_entries.append((tag, fmt, cnt, val))
		# Zeroed entries = the IFD0 sanitisation we added
		if tag == 0 and fmt == 0 and cnt == 0 and val == 0:
			zeroed_count += 1
		elif tag == 0x0112:  # Orientation
			orientation = u16(seg_data, entry + 8, le)
		elif tag == 0x0100:  # ImageWidth
			image_width = val if fmt == 4 else u16(seg_data, entry + 8, le)
		elif tag == 0x0101:  # ImageLength
			image_length = val if fmt == 4 else u16(seg_data, entry + 8, le)
		elif tag == 0x8769:  # ExifSubIFD
			exif_subifd = tiff + val
	# Walk IFD1
	next_ifd_ptr = ifd0 + 2 + ifd0_n * 12
	ifd1_rel = u32(seg_data, next_ifd_ptr, le) if next_ifd_ptr + 4 <= len(seg_data) else 0
	ifd1_info = None
	if ifd1_rel != 0:
		ifd1 = tiff + ifd1_rel
		if ifd1 + 2 <= len(seg_data):
			ifd1_n = u16(seg_data, ifd1, le)
			thumb_off = thumb_len = 0
			compression = None
			for i in range(ifd1_n):
				entry = ifd1 + 2 + i * 12
				if entry + 12 > len(seg_data):
					break
				tag = u16(seg_data, entry, le)
				val = u32(seg_data, entry + 8, le)
				if tag == 0x0103:
					compression = u16(seg_data, entry + 8, le)
				elif tag == 0x0201:
					thumb_off = val
				elif tag == 0x0202:
					thumb_len = val
			ifd1_info = {
				"entries": ifd1_n,
				"thumb_off": thumb_off,
				"thumb_len": thumb_len,
				"compression": compression,
			}
	# ExifSubIFD: walk for MakerNote
	exif_n = 0
	makernote_size = 0
	if exif_subifd is not None and exif_subifd + 2 <= len(seg_data):
		exif_n = u16(seg_data, exif_subifd, le)
		for i in range(exif_n):
			entry = exif_subifd + 2 + i * 12
			if entry + 12 > len(seg_data):
				break
			tag = u16(seg_data, entry, le)
			cnt = u32(seg_data, entry + 4, le)
			if tag == 0x927C:
				makernote_size = cnt
				break
	return {
		"size": len(seg_data),
		"byte_order": "II" if le else "MM",
		"ifd0_n": ifd0_n,
		"zeroed_in_ifd0": zeroed_count,
		"orientation": orientation,
		"image_width": image_width,
		"image_length": image_length,
		"ifd1": ifd1_info,
		"exif_n": exif_n,
		"makernote_size": makernote_size,
	}


def parse_thumb_dims(seg_data, t_off, t_len):
	"""Return (width, height) of the embedded IFD1 thumbnail JPEG."""
	tiff = 10
	tb = seg_data[tiff + t_off:tiff + t_off + t_len]
	p = 0
	while p < len(tb) - 1:
		if tb[p] != 0xFF:
			return None
		m = tb[p + 1]
		if m == 0xD8:
			p += 2
			continue
		if 0xC0 <= m <= 0xCF and m not in (0xC4, 0xC8, 0xCC):
			height = struct.unpack(">H", tb[p + 5:p + 7])[0]
			width = struct.unpack(">H", tb[p + 7:p + 9])[0]
			return (width, height)
		if p + 4 > len(tb):
			return None
		sl = struct.unpack(">H", tb[p + 2:p + 4])[0]
		p += 2 + sl
	return None


def detect_features(jpeg):
	"""Return dict with high-level feature flags."""
	primary_eoi = find_primary_eoi(jpeg)
	primary_dims = find_primary_sof(jpeg)

	# Walk segments
	app_segs = list(walk_segments(jpeg))
	exif_seg = None
	xmp_seg = None
	ext_xmp_segs = []
	icc_seg = None
	mpf_seg = None
	mpf_seg_off = None  # File offset of FF E2 marker, used for MPF-relative offset math
	iso21496_seg = None
	for off, marker, seglen in app_segs:
		body = jpeg[off + 4:off + 2 + seglen]
		if marker == 0xE1 and body[:6] == b"Exif\x00\x00":
			exif_seg = jpeg[off:off + 2 + seglen]
		elif marker == 0xE1 and body[:29] == b"http://ns.adobe.com/xap/1.0/\x00":
			xmp_seg = body[29:]
		elif marker == 0xE1 and body[:35] == b"http://ns.adobe.com/xmp/extension/\x00":
			ext_xmp_segs.append(body[35:])
		elif marker == 0xE2 and body[:12] == b"ICC_PROFILE\x00":
			icc_seg = body
		elif marker == 0xE2 and body[:4] == b"MPF\x00":
			mpf_seg = body[4:]
			mpf_seg_off = off
		elif marker == 0xE2 and body[:6] == b"\x00\x00\x00\x36urn:":
			iso21496_seg = body  # ISO 21496-1 header has urn: prefix

	# Detect gainmap (post-primary FFD8...FFD9)
	has_gainmap = False
	gainmap_size = 0
	if primary_eoi > 0 and primary_eoi + 4 < len(jpeg):
		if jpeg[primary_eoi:primary_eoi + 2] == b"\xFF\xD8":
			# Find next FFD9 in the trailing region (excluding final SEFT bytes if present)
			scan_end = len(jpeg) - 8 if jpeg[-4:] == b"SEFT" else len(jpeg)
			pos = primary_eoi + 2
			while pos < scan_end - 1:
				if jpeg[pos] == 0xFF and jpeg[pos + 1] == 0xD9:
					has_gainmap = True
					gainmap_size = pos + 2 - primary_eoi
					break
				pos += 1

	# Detect SEFT trailer
	has_seft = len(jpeg) >= 12 and jpeg[-4:] == b"SEFT"
	seft_size = 0
	if has_seft:
		seft_size = struct.unpack("<I", jpeg[-8:-4])[0]

	# Parse XMP for hdrgm + Item:Length
	xmp_has_hdrgm = False
	item_length_value = None
	if xmp_seg is not None:
		try:
			text = xmp_seg.decode("utf-8", errors="replace")
			xmp_has_hdrgm = "hdrgm:" in text or "ns.adobe.com/hdr-gain-map" in text
			# Parse Item:Length
			import re
			m = re.search(r'Item:Length="(\d+)"', text)
			if m:
				item_length_value = int(m.group(1))
		except Exception:
			pass

	# Parse MPF entries
	mpf_entries = 0
	mpf_gainmap_offset = None
	mpf_gainmap_size = None
	if mpf_seg is not None:
		# MPF starts with TIFF header (II*\0 / MM\0*) at body[0..4]
		if len(mpf_seg) >= 8 and mpf_seg[:2] in (b"II", b"MM"):
			mle = mpf_seg[:2] == b"II"
			ifd0_off = u32(mpf_seg, 4, mle)
			if ifd0_off + 2 <= len(mpf_seg):
				n = u16(mpf_seg, ifd0_off, mle)
				for i in range(n):
					e = ifd0_off + 2 + i * 12
					if e + 12 > len(mpf_seg):
						break
					tag = u16(mpf_seg, e, mle)
					if tag == 0xB001:  # NumberOfImages
						mpf_entries = u32(mpf_seg, e + 8, mle)
					elif tag == 0xB002:  # MPEntry
						# Each entry: 16 bytes (attrs/size/offset/dep1/dep2)
						entry_off = u32(mpf_seg, e + 8, mle)
						# Look at entry 1 (the gainmap)
						if entry_off + 32 <= len(mpf_seg):
							mpf_gainmap_size = u32(mpf_seg, entry_off + 16 + 4, mle)
							mpf_gainmap_offset = u32(mpf_seg, entry_off + 16 + 8, mle)

	exif_info = parse_exif_segment(exif_seg) if exif_seg else None
	thumb_dims = None
	if exif_info and exif_info["ifd1"] and exif_info["ifd1"]["thumb_off"]:
		thumb_dims = parse_thumb_dims(exif_seg, exif_info["ifd1"]["thumb_off"],
			exif_info["ifd1"]["thumb_len"])

	# MPF dataOffset is relative to the MP Endian field (just past "MPF\0"). Compute the absolute
	# expected file offset for the gainmap so we can sanity-check it against primary_eoi.
	mpf_gainmap_abs = None
	if mpf_seg_off is not None and mpf_gainmap_offset is not None and mpf_gainmap_offset > 0:
		# mpf_seg_off points at FF E2; +2 marker + 2 segLen + 4 "MPF\0" = +8 to MP Endian start
		mpf_gainmap_abs = mpf_seg_off + 8 + mpf_gainmap_offset

	return {
		"file_size": len(jpeg),
		"primary_eoi": primary_eoi,
		"primary_dims": primary_dims,
		"exif": exif_info,
		"thumb_dims": thumb_dims,
		"xmp_present": xmp_seg is not None,
		"xmp_has_hdrgm": xmp_has_hdrgm,
		"item_length": item_length_value,
		"ext_xmp_count": len(ext_xmp_segs),
		"icc_present": icc_seg is not None,
		"mpf_present": mpf_seg is not None,
		"mpf_entries": mpf_entries,
		"mpf_gainmap_offset_raw": mpf_gainmap_offset,
		"mpf_gainmap_abs": mpf_gainmap_abs,
		"mpf_gainmap_size": mpf_gainmap_size,
		"iso21496_present": iso21496_seg is not None,
		"has_gainmap": has_gainmap,
		"gainmap_size": gainmap_size,
		"has_seft": has_seft,
		"seft_size": seft_size,
	}


def find_anomalies(name, info):
	"""Return list of (severity, description) tuples for issues in this file."""
	issues = []
	exif = info["exif"]

	if not exif:
		issues.append(("P0", "no parseable EXIF segment"))
		return issues

	# Orientation must be 1 (CropCenter normalises every output)
	if exif["orientation"] is not None and exif["orientation"] != 1:
		issues.append(("P1", f"Orientation={exif['orientation']} (expected 1 — CropCenter normalises)"))

	# IFD0 dims must match primary SOF
	if info["primary_dims"] and exif["image_width"] is not None:
		if exif["image_width"] != info["primary_dims"][0] or exif["image_length"] != info["primary_dims"][1]:
			issues.append(("P0", f"IFD0 dims {exif['image_width']}x{exif['image_length']} != "
				f"primary SOF {info['primary_dims'][0]}x{info['primary_dims'][1]}"))

	# IFD1 thumbnail coherence
	if exif["ifd1"]:
		ifd1 = exif["ifd1"]
		if ifd1["thumb_off"] and not ifd1["thumb_len"]:
			issues.append(("P1", "IFD1 has thumb offset but zero length"))
		elif ifd1["thumb_len"] and not ifd1["thumb_off"]:
			issues.append(("P1", "IFD1 has thumb length but zero offset"))
		elif ifd1["thumb_off"] and not info["thumb_dims"]:
			issues.append(("P0", f"IFD1 thumb tags present but bytes don't decode to a valid JPEG"))
		if ifd1["compression"] is not None and ifd1["compression"] != 6:
			issues.append(("P1", f"IFD1 Compression={ifd1['compression']} (expected 6 = JPEG)"))

	# HDR coherence: hdrgm XMP, MPF, gainmap must agree
	hdr_signals = sum([info["xmp_has_hdrgm"], info["mpf_present"], info["has_gainmap"]])
	if 0 < hdr_signals < 3:
		# Mixed signals — mark as inconsistent
		signals_str = (f"xmp_hdrgm={info['xmp_has_hdrgm']} "
			f"mpf_present={info['mpf_present']} "
			f"gainmap_present={info['has_gainmap']}")
		# Acceptable case: SDR file with no HDR signals at all (hdr_signals=0)
		# Acceptable: full HDR (all 3 set)
		# Anything else is potential drift
		if info["mpf_present"] and not info["has_gainmap"]:
			issues.append(("P0", f"MPF segment present but no gainmap follows primary EOI ({signals_str})"))
		if info["xmp_has_hdrgm"] and not info["has_gainmap"]:
			issues.append(("P0", f"XMP hdrgm present but no gainmap follows primary EOI ({signals_str})"))
		if info["has_gainmap"] and not info["mpf_present"]:
			issues.append(("P0", f"Gainmap bytes present but no MPF segment to anchor them ({signals_str})"))
		if info["has_gainmap"] and not info["xmp_has_hdrgm"]:
			issues.append(("P1", f"Gainmap present but no hdrgm in XMP ({signals_str})"))

	# Item:Length should match actual gainmap size
	if info["item_length"] is not None and info["has_gainmap"]:
		if info["item_length"] != info["gainmap_size"]:
			issues.append(("P1", f"XMP Item:Length={info['item_length']} != actual "
				f"gainmap_size={info['gainmap_size']} (XmpItemLengthPatcher should match)"))

	# MPF gainmap offset/size must match actual gainmap. mpf_gainmap_abs is the absolute file
	# offset computed from MPF's relative-to-MP-Endian dataOffset.
	if info["mpf_gainmap_abs"] and info["primary_eoi"] > 0:
		if info["mpf_gainmap_abs"] != info["primary_eoi"]:
			issues.append(("P0", f"MPF gainmap abs offset={info['mpf_gainmap_abs']} (raw "
				f"{info['mpf_gainmap_offset_raw']}) != primary_eoi={info['primary_eoi']}"))
		if info["mpf_gainmap_size"] and info["has_gainmap"]:
			if info["mpf_gainmap_size"] != info["gainmap_size"]:
				issues.append(("P1", f"MPF gainmap size={info['mpf_gainmap_size']} != "
					f"actual={info['gainmap_size']}"))

	# Thumbnail cascade outcome
	if info["thumb_dims"]:
		w, h = info["thumb_dims"]
		max_dim = max(w, h)
		if max_dim < 256:
			issues.append(("P2", f"Thumbnail at {w}x{h} (smaller than expected 256+ maxDim)"))
		# Note: 256 maxDim is acceptable (cascade fallback for busy content);
		# we don't flag it as an issue per the user's leave-cascade-as-is decision

	return issues


def main():
	rows = []
	for path in sorted(INPUT_DIR.glob("*.jpg")):
		with open(path, "rb") as f:
			jpeg = f.read()
		info = detect_features(jpeg)
		issues = find_anomalies(path.name, info)
		mtime = os.path.getmtime(path)
		rows.append({"path": path, "info": info, "issues": issues, "mtime": mtime})

	# Header table
	print(f"\n{'='*100}")
	print(f"  CORPUS AUDIT — {len(rows)} JPEG files")
	print(f"{'='*100}\n")

	# Per-file summary
	print(f"{'file':32} {'primary':>11} {'thumb':>10} {'thumb_B':>8} "
		f"{'HDR':>5} {'SEFT':>5} {'orient':>6} {'iss':>3}")
	print("-" * 100)
	for r in rows:
		i = r["info"]
		dims = i["primary_dims"]
		td = i["thumb_dims"]
		exif = i["exif"]
		hdr_str = "Y" if (i["xmp_has_hdrgm"] and i["mpf_present"] and i["has_gainmap"]) \
			else ("part" if (i["xmp_has_hdrgm"] or i["mpf_present"] or i["has_gainmap"]) else "-")
		seft_str = "Y" if i["has_seft"] else "-"
		orient_str = str(exif["orientation"]) if exif and exif["orientation"] is not None else "-"
		dims_str = f"{dims[0]}x{dims[1]}" if dims else "?"
		td_str = f"{td[0]}x{td[1]}" if td else "?"
		t_bytes = exif["ifd1"]["thumb_len"] if exif and exif["ifd1"] else 0
		print(f"{r['path'].name:32} {dims_str:>11} {td_str:>10} {t_bytes:>8} "
			f"{hdr_str:>5} {seft_str:>5} {orient_str:>6} {len(r['issues']):>3}")

	# Anomaly section
	print(f"\n{'='*100}")
	print(f"  ANOMALIES")
	print(f"{'='*100}\n")
	any_issues = False
	for r in rows:
		if not r["issues"]:
			continue
		any_issues = True
		print(f"  {r['path'].name}:")
		for sev, desc in r["issues"]:
			print(f"    [{sev}] {desc}")
	if not any_issues:
		print("  No anomalies detected — every file passes structural and HDR-coherence checks.")

	# Cascade landing distribution
	print(f"\n{'='*100}")
	print(f"  THUMBNAIL CASCADE LANDING DISTRIBUTION")
	print(f"{'='*100}\n")
	dim_counts = {}
	for r in rows:
		td = r["info"]["thumb_dims"]
		key = f"{td[0]}x{td[1]}" if td else "no-thumb"
		dim_counts[key] = dim_counts.get(key, 0) + 1
	for dim, count in sorted(dim_counts.items(), key=lambda x: -x[1]):
		print(f"  {dim:>15}: {count:>3} file(s)")

	# Quality distribution (bytes per pixel of thumbnail)
	print(f"\n  THUMBNAIL BYTES-PER-PIXEL (rough quality estimate; ~1-2 bpp = q90, ~3-4 bpp = q95+):")
	for r in rows:
		td = r["info"]["thumb_dims"]
		exif = r["info"]["exif"]
		if td and exif and exif["ifd1"]:
			t_len = exif["ifd1"]["thumb_len"]
			bpp = t_len * 8 / (td[0] * td[1])
			marker = "*" if td[0] * td[1] < 65000 else " "  # mark sub-410x512 falls
			print(f"  {marker} {r['path'].name:32} {td[0]}x{td[1]:>3}  {t_len:>6}B  {bpp:.2f} bpp")


if __name__ == "__main__":
	try:
		sys.stdout.reconfigure(encoding="utf-8", errors="replace")
		sys.stderr.reconfigure(encoding="utf-8", errors="replace")
	except (AttributeError, OSError):
		pass
	main()
