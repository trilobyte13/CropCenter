"""Per-image graft analysis report + 3-panel heatmap.

Usage:
    py scripts/graft_analyze.py 20250820_093032
    py scripts/graft_analyze.py 20250819_172023 20250821_103446 20250820_093032

Each stem requires three fixtures under scripts/graft-fixtures/:
    <stem>.jpg          source primary (the Samsung HDR original)
    <stem>e1.jpg        Photoshop AI edit (Generative Remove output)
    <stem>-graft.jpg    CropCenter graft (the splice we're verifying)

Console report covers:
    - File sizes, EXIF orientations, SEFT trailer presence
    - Identity metadata preservation (Make / Model / DateTimeOriginal / exposure / GPS)
    - UHDR primary APP segments (XMP hdrgm, ISO 21496-1, MPF) on graft and source
    - Gain-map structure (size, dimensions, JFIF/EXIF/XMP/MPF on the gain-map JPEG itself)
    - HDR orientation alignment check (primary aspect vs gain-map aspect)
    - AI region detection (the bbox where Photoshop changed pixels at sampleSize=4)
    - Gainmap diff distribution (display orient) and inpaint effect inside vs outside
      the dilated AI mask

Heatmap output: <stem>-graft-heatmap.png — 3 panels at gainmap scale:
    Panel 1: Source primary thumbnail (spatial reference)
    Panel 2: AI region overlay on source (red dots / red bbox)
    Panel 3: Gainmap diff (graft vs source) heat-mapped, cyan = AI bbox
"""
import io
import os
import sys

import numpy as np
import piexif
from PIL import Image, ImageDraw, ImageFont, ImageOps

import graft_lib

sys.stdout.reconfigure(encoding='utf-8')
BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'graft-fixtures')
DIFF_BINS = [0, 1, 3, 5, 10, 30, 60, 256]


def analyze(stem):
	src_path, edit_path, graft_path = graft_lib.stem_paths(stem, BASE)
	out_heatmap = os.path.join(BASE, f'{stem}-graft-heatmap.png')

	print('\n' + '=' * 90)
	print(f'  ANALYSIS: {stem}')
	print('=' * 90)

	src_data = graft_lib.read_bytes(src_path)
	edit_data = graft_lib.read_bytes(edit_path)
	graft_data = graft_lib.read_bytes(graft_path)
	src_orient = graft_lib.primary_orient(src_path)
	edit_orient = graft_lib.primary_orient(edit_path)
	graft_orient = graft_lib.primary_orient(graft_path)

	print(f'\n  source: {len(src_data):>10,} B  orient={src_orient}')
	print(f'  edit  : {len(edit_data):>10,} B  orient={edit_orient}')
	print(f'  graft : {len(graft_data):>10,} B  orient={graft_orient}')
	print(f'  SEFT trailer: source={src_data[-4:]!r}  graft={graft_data[-4:]!r}')

	_report_identity_metadata(src_path, graft_path)

	src_gm_bytes = graft_lib.extract_gain_map(src_path)
	graft_gm_bytes = graft_lib.extract_gain_map(graft_path)
	if src_gm_bytes is None or graft_gm_bytes is None:
		print(f'\n  ⚠ Missing gain-map on one side; skipping HDR / inpaint analysis.')
		return

	src_gm_im = Image.open(io.BytesIO(src_gm_bytes))
	graft_gm_im = Image.open(io.BytesIO(graft_gm_bytes))
	print(f'\n  UHDR structure:')
	print(f'    source gainmap : {len(src_gm_bytes):>8,} B  '
		f'{src_gm_im.size[0]}x{src_gm_im.size[1]}  mode={src_gm_im.mode}')
	print(f'    graft  gainmap : {len(graft_gm_bytes):>8,} B  '
		f'{graft_gm_im.size[0]}x{graft_gm_im.size[1]}  mode={graft_gm_im.mode}')

	_report_orientation_alignment(src_path, graft_path, src_gm_im, graft_gm_im)
	ai_mask_stored = _ai_mask_stored(src_path, edit_path, src_orient, edit_orient)

	src_gm_disp = graft_lib.reorient(src_gm_im.convert('L'), src_orient)
	graft_gm_disp = graft_lib.reorient(graft_gm_im.convert('L'), graft_orient)
	src_gm_arr = np.asarray(src_gm_disp, dtype=np.int16)
	graft_gm_arr = np.asarray(graft_gm_disp, dtype=np.int16)
	src_gm_arr, alignment_dx, alignment_dy = _align_source_to_graft(src_gm_arr, graft_gm_arr)
	d_gm = np.abs(src_gm_arr - graft_gm_arr).astype(np.uint8)
	# Display dims now match graft's after alignment crop — re-derive the display variant for downstream
	# heatmap rendering / inpaint-effect reporting that expect Image-shaped inputs.
	src_gm_disp = Image.fromarray(src_gm_arr.astype(np.uint8))
	print(f'\n  Best source->graft alignment: dx={alignment_dx} dy={alignment_dy}'
		f' (crops source from {graft_gm_disp.size[0] + abs(alignment_dx)}x'
		f'{graft_gm_disp.size[1] + abs(alignment_dy)})')

	_report_diff_distribution(d_gm)
	ai_mask_in_gm = _report_inpaint_effect(ai_mask_stored, src_orient, graft_gm_disp.size, d_gm)

	_render_heatmap(src_path, graft_gm_disp.size, ai_mask_in_gm, d_gm, stem, out_heatmap)


def _align_source_to_graft(src_arr, graft_arr):
	"""Find the (dx, dy) crop offset where the user's crop placed the graft within source's gain map.

	The export pipeline crops the source's gain map to the user's crop region; the analyzer previously
	assumed centered crop, which silently overestimated the gainmap diff by ~5-8 mean for any non-
	centered crop. This helper slides a graft-sized window over the source's gain map (both axes) and
	picks the offset that minimises L1 mean diff — that's the alignment that reflects where the user
	actually positioned the crop on the source. Returns the source array cropped to graft's dims at
	the discovered offset, plus the offset itself for reporting.

	For typical Samsung HDR JPEGs the quarter-resolution gain map's slide space is small (≤ 250 rows
	for a 3000x4000 → 3000x3750 crop with 0.25 scale → 62-row vertical slide; full-width crops have
	zero horizontal slide). The full grid search is O(slide_w * slide_h * gain_map_pixels), which on
	750x938 with a 62-row slide is ~44M operations — sub-second in numpy.
	"""
	sH, sW = src_arr.shape
	gH, gW = graft_arr.shape
	slide_y = sH - gH
	slide_x = sW - gW
	if slide_y < 0 or slide_x < 0:
		# Source is smaller than graft along some axis — fall back to centered Lanczos resize so the
		# pipeline still produces a diff (degenerate but recoverable on a malformed graft).
		src_pil = Image.fromarray(src_arr.astype(np.uint8))
		resized = np.asarray(src_pil.resize((gW, gH), Image.LANCZOS), dtype=np.int16)
		return (resized, 0, 0)
	best_mean = float('inf')
	best_dx = 0
	best_dy = 0
	for dy in range(slide_y + 1):
		for dx in range(slide_x + 1):
			window = src_arr[dy:dy + gH, dx:dx + gW]
			m = float(np.abs(window - graft_arr).mean())
			if m < best_mean:
				best_mean = m
				best_dx = dx
				best_dy = dy
	return (src_arr[best_dy:best_dy + gH, best_dx:best_dx + gW], best_dx, best_dy)


def _ai_mask_stored(src_path, edit_path, src_orient, edit_orient):
	"""Compute the AI-region mask in source's stored coords at sampleSize=4 — mirrors
	what Java's AiRegionDetector produces. Returns a 2D bool array, or None when the
	source / edit shapes don't match after re-orientation.
	"""
	src_stored = graft_lib.load_stored_downsampled(src_path, 4)
	edit_stored = graft_lib.load_stored_downsampled(edit_path, 4)
	edit_stored = graft_lib.reorient_to_match_source(
		edit_stored, src_orient, edit_orient, src_stored.shape)
	if src_stored.shape != edit_stored.shape:
		print(f'\n  AI mask: shape mismatch {src_stored.shape} vs {edit_stored.shape}')
		return None
	ai_diff = np.abs(src_stored - edit_stored).max(axis=2)
	ai_mask = ai_diff > 30
	n = ai_mask.sum()
	pct = n / ai_mask.size * 100
	print(f'\n  AI mask (stored, sampleSize=4, threshold>30): {n:,} of {ai_mask.size:,} '
		f'({pct:.4f}%)')
	if n > 0:
		ys, xs = np.where(ai_mask)
		print(f'    bbox (stored): x=[{xs.min()}..{xs.max()}] y=[{ys.min()}..{ys.max()}]'
			f' ({xs.max() - xs.min() + 1}x{ys.max() - ys.min() + 1})')
	return ai_mask


def _dilate(mask, radius):
	"""Bool-mask dilation by `radius` 8-connected pixels — matches GainMapInpainter's
	DILATE_RADIUS. Used to project the strict AI mask out into the inpaint footprint
	for the inside-vs-outside diff stats.
	"""
	out = mask.copy()
	for _ in range(radius):
		snap = out.copy()
		h, w = out.shape
		for dy in (-1, 0, 1):
			for dx in (-1, 0, 1):
				if dx == 0 and dy == 0:
					continue
				shifted = np.zeros_like(snap)
				sy0, sy1 = max(0, -dy), min(h, h - dy)
				sx0, sx1 = max(0, -dx), min(w, w - dx)
				dy0, dy1 = max(0, dy), min(h, h + dy)
				dx0, dx1 = max(0, dx), min(w, w + dx)
				shifted[dy0:dy1, dx0:dx1] = snap[sy0:sy1, sx0:sx1]
				out = out | shifted
	return out


def _exif_field(ex, ifd, tag):
	return (ex.get(ifd) or {}).get(tag)


def _render_heatmap(src_path, gm_size, ai_mask_in_gm, d_gm, stem, out_path):
	"""Compose the 3-panel heatmap PNG: primary thumbnail, AI region overlay, gainmap
	diff heat. Cyan box on the diff panel marks the AI bbox so visual comparison with
	the AI panel is direct.
	"""
	pgw, pgh = gm_size
	primary = ImageOps.exif_transpose(Image.open(src_path)).convert('RGB')
	thumb = primary.resize((pgw, pgh), Image.LANCZOS)

	overlay = Image.new('RGBA', (pgw, pgh), (0, 0, 0, 0))
	draw = ImageDraw.Draw(overlay)
	ai_bbox = None
	n_ai_in_gm = 0
	if ai_mask_in_gm is not None and ai_mask_in_gm.any():
		ys, xs = np.where(ai_mask_in_gm)
		ai_bbox = (xs.min(), ys.min(), xs.max(), ys.max())
		draw.rectangle(ai_bbox, outline=(255, 0, 0, 255), width=3)
		for y, x in zip(ys, xs):
			overlay.putpixel((int(x), int(y)), (255, 0, 0, 200))
		n_ai_in_gm = len(xs)
	panel2 = thumb.copy().convert('RGBA')
	panel2.alpha_composite(overlay)
	panel2 = panel2.convert('RGB')

	panel3 = graft_lib.diff_to_heat_image(d_gm)
	if ai_bbox is not None:
		ImageDraw.Draw(panel3).rectangle(ai_bbox, outline=(0, 255, 255), width=2)

	font_t, font_s = _load_label_fonts()
	gap = 8
	label_h = 60
	composite = Image.new('RGB', (pgw * 3 + gap * 2, pgh + label_h), (24, 24, 28))
	composite.paste(thumb, (0, label_h))
	composite.paste(panel2, (pgw + gap, label_h))
	composite.paste(panel3, (pgw * 2 + gap * 2, label_h))

	cdraw = ImageDraw.Draw(composite)
	cdraw.text((6, 6), f'Source primary — {stem}', fill=(220, 220, 240), font=font_t)
	cdraw.text((6, 26), f'{pgw} x {pgh} (gainmap scale)', fill=(180, 180, 200), font=font_s)
	cdraw.text((pgw + gap + 6, 6), 'AI region', fill=(220, 220, 240), font=font_t)
	cdraw.text((pgw + gap + 6, 26),
		f'red dots / box = where Photoshop modified pixels  ({n_ai_in_gm} px in gm coords)',
		fill=(180, 180, 200), font=font_s)
	cdraw.text((pgw * 2 + gap * 2 + 6, 6), 'Gainmap diff (graft vs source)',
		fill=(220, 220, 240), font=font_t)
	cdraw.text((pgw * 2 + gap * 2 + 6, 26),
		f'black=0  red→yellow→white = 1..30+   '
		f'>=1: {(d_gm >= 1).sum():,}  max: {d_gm.max()}   cyan = AI bbox',
		fill=(180, 180, 200), font=font_s)

	composite.save(out_path)
	print(f'\n  → wrote {os.path.basename(out_path)}  ({composite.size[0]}x{composite.size[1]})')


def _load_label_fonts():
	try:
		return ImageFont.truetype('arial.ttf', 16), ImageFont.truetype('arial.ttf', 12)
	except Exception:
		default = ImageFont.load_default()
		return default, default


def _report_diff_distribution(d_gm):
	print(f'\n  Gainmap diff distribution (display orient):')
	counts, _ = np.histogram(d_gm.flatten(), bins=DIFF_BINS)
	total = d_gm.size
	for i in range(len(DIFF_BINS) - 1):
		lo, hi = DIFF_BINS[i], DIFF_BINS[i + 1]
		print(f'    {lo:>3}-{hi - 1:>3}: {counts[i]:>10,} px = {counts[i] / total * 100:7.4f}%')
	print(f'    >= 1: {(d_gm >= 1).sum():,}  '
		f'mean: {d_gm.mean():.4f}  max: {d_gm.max()}')


def _report_identity_metadata(src_path, graft_path):
	print('\n  Identity metadata:')
	src_exif = piexif.load(src_path)
	graft_exif = piexif.load(graft_path)
	checks = [
		('Make', '0th', piexif.ImageIFD.Make),
		('Model', '0th', piexif.ImageIFD.Model),
		('DateTimeOriginal', 'Exif', piexif.ExifIFD.DateTimeOriginal),
		('ExposureTime', 'Exif', piexif.ExifIFD.ExposureTime),
		('FNumber', 'Exif', piexif.ExifIFD.FNumber),
		('ISOSpeedRatings', 'Exif', piexif.ExifIFD.ISOSpeedRatings),
		('FocalLength', 'Exif', piexif.ExifIFD.FocalLength),
	]
	for label, ifd, tag in checks:
		s = _exif_field(src_exif, ifd, tag)
		g = _exif_field(graft_exif, ifd, tag)
		mark = '✓' if s == g else '✗'
		print(f'    {mark} {label:<22} src={repr(s)[:32]:<34} graft={repr(g)[:32]}')

	src_mn = _exif_field(src_exif, 'Exif', piexif.ExifIFD.MakerNote) or b''
	graft_mn = _exif_field(graft_exif, 'Exif', piexif.ExifIFD.MakerNote) or b''
	mark = '✓' if src_mn == graft_mn else '✗'
	print(f'    {mark} {"MakerNote":<22} src={len(src_mn)}B  graft={len(graft_mn)}B')

	src_gps = src_exif.get('GPS', {})
	graft_gps = graft_exif.get('GPS', {})
	mark = '✓' if src_gps == graft_gps else '✗'
	print(f'    {mark} {"GPS IFD":<22} src={len(src_gps)} tags  graft={len(graft_gps)} tags')


def _report_inpaint_effect(ai_mask_stored, src_orient, gm_size, d_gm):
	"""Project the stored-coord AI mask into gain-map display coords, dilate, and report
	mean / max diff inside vs outside. The inside/outside ratio is what tells you the
	inpainter is actually firing on the right region.
	"""
	if ai_mask_stored is None or ai_mask_stored.sum() == 0:
		print(f'\n  Inpaint effect: skipped (no AI mask)')
		return None

	pgw, pgh = gm_size
	mask_pil = Image.fromarray(ai_mask_stored.astype(np.uint8) * 255)
	mask_disp_pil = graft_lib.reorient(mask_pil, src_orient).resize((pgw, pgh), Image.NEAREST)
	ai_mask_in_gm = np.asarray(mask_disp_pil) > 0
	dilated = _dilate(ai_mask_in_gm, 2)

	inside = d_gm[dilated]
	outside = d_gm[~dilated]
	in_pct = (inside >= 1).sum() / max(len(inside), 1) * 100
	out_pct = (outside >= 1).sum() / max(len(outside), 1) * 100
	mean_ratio = inside.mean() / max(outside.mean(), 1e-6)
	verdict = 'inpaint clearly active' if mean_ratio > 5 else 'weak inpaint signal'
	print(f'\n  Inpaint effect:')
	print(f'    INSIDE  AI-dilated mask: {len(inside):>10,} px  '
		f'mean diff {inside.mean():>5.2f}  max {inside.max():>3}  '
		f'>= 1: {(inside >= 1).sum():,} ({in_pct:.1f}%)')
	print(f'    OUTSIDE AI-dilated mask: {len(outside):>10,} px  '
		f'mean diff {outside.mean():>5.3f}  max {outside.max():>3}  '
		f'>= 1: {(outside >= 1).sum():,} ({out_pct:.4f}%)')
	print(f'    INSIDE/OUTSIDE mean ratio: {mean_ratio:.1f}x  ({verdict})')
	return ai_mask_in_gm


def _report_orientation_alignment(src_path, graft_path, src_gm_im, graft_gm_im):
	"""Sanity-check that primary and gain-map have the same aspect orientation. If they
	disagree (one landscape one portrait), the gain-map is sideways relative to its
	primary — which is the regression we hit when the AI detector ran on full-res
	mismatched coords.
	"""
	src_w, src_h = Image.open(src_path).size
	graft_w, graft_h = Image.open(graft_path).size
	gm_src_w, gm_src_h = src_gm_im.size
	gm_graft_w, gm_graft_h = graft_gm_im.size
	src_aligned = (src_w / src_h > 1) == (gm_src_w / gm_src_h > 1)
	graft_aligned = (graft_w / graft_h > 1) == (gm_graft_w / gm_graft_h > 1)
	print(f'\n  HDR orientation alignment:')
	print(f'    source primary {src_w}x{src_h} aspect {src_w / src_h:.3f}  '
		f'gainmap {gm_src_w}x{gm_src_h} aspect {gm_src_w / gm_src_h:.3f}')
	print(f'    graft  primary {graft_w}x{graft_h} aspect {graft_w / graft_h:.3f}  '
		f'gainmap {gm_graft_w}x{gm_graft_h} aspect {gm_graft_w / gm_graft_h:.3f}')
	mark = '✓' if graft_aligned else '⚠ SIDEWAYS'
	print(f'    source aligned: {src_aligned}    graft aligned: {graft_aligned}  {mark}')


if __name__ == '__main__':
	stems = sys.argv[1:] if len(sys.argv) > 1 else [
		'20250819_172023', '20250820_093032', '20250821_103446',
	]
	for s in stems:
		analyze(s)
