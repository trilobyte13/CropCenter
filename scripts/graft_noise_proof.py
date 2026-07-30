"""Visual proof: side-by-side render of self-encode noise vs actual graft diff.

Usage:
    py scripts/graft_noise_proof.py 20250820_093032

Companion to graft_noise_control.py — that script proves the JPEG-round-trip
hypothesis with numbers; this one proves it with pixels. Three panels at gain-map
scale in display orient:

    Panel 1: Source gain-map (the input to both round trips below)
    Panel 2: Self-encode noise — |source - decode(encode_q95(source))|.
             No app code, no inpainting, no Skia. Pure PIL JPEG round trip.
    Panel 3: Graft vs source gain-map diff — what the app actually produces,
             with the cyan AI bbox overlaid for spatial reference.

If panels 2 and 3 look qualitatively the same speckle pattern (matching density, matching spatial concentration on
smooth gradients vs sharp edges), the off-mask "red" in the actual graft heatmap is JPEG round-trip noise — not an
inpaint bug or an app artifact. Panel 3 typically has a small bright cluster inside the cyan bbox where the inpainter
wrote new values; panel 2 doesn't (nothing was inpainted).

Output: <stem>-jpeg-noise-proof.png
"""
import hashlib
import io
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import graft_lib

sys.stdout.reconfigure(encoding='utf-8')
BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'graft-fixtures')


def render(stem):
	src_path, edit_path, graft_path = graft_lib.stem_paths(stem, BASE)
	out_name = f'{stem}-jpeg-noise-proof.png'

	src_gm_bytes = graft_lib.extract_gain_map(src_path)
	graft_gm_bytes = graft_lib.extract_gain_map(graft_path)
	if src_gm_bytes is None or graft_gm_bytes is None:
		print(f'  {stem}: missing gain-map, skipping')
		return

	# Optional sanity: if a graft (2) sibling exists alongside graft, confirm it's byte-identical. The export
	# pipeline is deterministic, so two saves of the same state produce the same MD5 — useful one-time confirmation,
	# harmless to leave in.
	graft2_path = os.path.join(BASE, f'{stem}-graft (2).jpg')
	if os.path.exists(graft2_path):
		md5_a = hashlib.md5(graft_lib.read_bytes(graft_path)).hexdigest()
		md5_b = hashlib.md5(graft_lib.read_bytes(graft2_path)).hexdigest()
		print(f'  graft and graft (2) byte-identical: {md5_a == md5_b} ({md5_a[:16]}…)')

	src_orient = graft_lib.primary_orient(src_path)
	graft_orient = graft_lib.primary_orient(graft_path)

	src_gm_pil = Image.open(io.BytesIO(src_gm_bytes)).convert('L')
	src_disp_pil = graft_lib.reorient(src_gm_pil, src_orient)
	gw, gh = src_disp_pil.size
	src_arr = np.asarray(src_disp_pil, dtype=np.int16)
	print(f'  display gainmap dim: {gw} x {gh}')

	# Panel 2: self-encode noise (no app, no inpaint).
	buf = io.BytesIO()
	src_gm_pil.save(buf, 'JPEG', quality=95, optimize=False, progressive=False)
	self_decoded_pil = graft_lib.reorient(
		Image.open(io.BytesIO(buf.getvalue())).convert('L'), src_orient)
	self_arr = np.asarray(self_decoded_pil, dtype=np.int16)
	self_diff = np.abs(src_arr - self_arr).astype(np.uint8)
	n_self = int((self_diff >= 1).sum())
	print(f'  self-encode (q=95) diff: >=1: {n_self:,}  max: {self_diff.max()}')

	# Panel 3: actual graft vs source diff, with AI bbox overlay.
	graft_gm_pil = Image.open(io.BytesIO(graft_gm_bytes)).convert('L')
	graft_disp_pil = graft_lib.reorient(graft_gm_pil, graft_orient)
	pgw, pgh = graft_disp_pil.size
	if (gw, gh) != (pgw, pgh):
		src_for_graft_pil = src_disp_pil.resize((pgw, pgh), Image.LANCZOS)
		src_for_graft = np.asarray(src_for_graft_pil, dtype=np.int16)
	else:
		src_for_graft = src_arr
	graft_arr = np.asarray(graft_disp_pil, dtype=np.int16)
	graft_diff = np.abs(src_for_graft - graft_arr).astype(np.uint8)
	n_graft = int((graft_diff >= 1).sum())
	print(f'  graft vs source diff   : >=1: {n_graft:,}  max: {graft_diff.max()}')

	ai_bbox = _compute_ai_bbox(src_path, edit_path, src_orient,
		graft_lib.primary_orient(edit_path), (pgw, pgh))

	panel1 = src_disp_pil.convert('RGB')
	if (gw, gh) != (pgw, pgh):
		panel1 = panel1.resize((pgw, pgh), Image.LANCZOS)

	panel2 = graft_lib.diff_to_heat_image(self_diff)
	if panel2.size != (pgw, pgh):
		panel2 = panel2.resize((pgw, pgh), Image.NEAREST)

	panel3 = graft_lib.diff_to_heat_image(graft_diff)
	if ai_bbox is not None:
		ImageDraw.Draw(panel3).rectangle(ai_bbox, outline=(0, 255, 255), width=2)

	font_t, font_s = _load_label_fonts()
	gap = 8
	label_h = 60
	composite = Image.new('RGB', (pgw * 3 + gap * 2, pgh + label_h), (24, 24, 28))
	composite.paste(panel1, (0, label_h))
	composite.paste(panel2, (pgw + gap, label_h))
	composite.paste(panel3, (pgw * 2 + gap * 2, label_h))

	cdraw = ImageDraw.Draw(composite)
	cdraw.text((6, 6), 'Source gainmap (display orient)', fill=(220, 220, 240), font=font_t)
	cdraw.text((6, 26), '(reference — input to both round trips)',
		fill=(180, 180, 200), font=font_s)
	cdraw.text((6, 42), f'{pgw} x {pgh}', fill=(180, 180, 200), font=font_s)

	cdraw.text((pgw + gap + 6, 6), 'Self-encode noise — JPEG round-trip ONLY',
		fill=(220, 220, 240), font=font_t)
	cdraw.text((pgw + gap + 6, 26), 'PIL q=95, no app, no inpaint',
		fill=(180, 180, 200), font=font_s)
	cdraw.text((pgw + gap + 6, 42),
		f'{n_self:,} px diff >= 1   max diff: {self_diff.max()}',
		fill=(180, 180, 200), font=font_s)

	cdraw.text((pgw * 2 + gap * 2 + 6, 6), 'Graft vs source — what the app produces',
		fill=(220, 220, 240), font=font_t)
	cdraw.text((pgw * 2 + gap * 2 + 6, 26), 'inpaint + Skia encode + decode round-trip',
		fill=(180, 180, 200), font=font_s)
	cdraw.text((pgw * 2 + gap * 2 + 6, 42),
		f'{n_graft:,} px diff >= 1   max diff: {graft_diff.max()}   cyan box = AI region',
		fill=(180, 180, 200), font=font_s)

	out_path = os.path.join(BASE, out_name)
	composite.save(out_path)
	print(f'\n  → wrote {out_name}  ({composite.size[0]}x{composite.size[1]})')
	print(f'  Compare panels 2 (no app) and 3 (graft):')
	print(f'    self-encode pure JPEG noise:  {n_self:,} px '
		f'({n_self / self_diff.size * 100:.2f}%)')
	print(f'    graft vs source actual diff:  {n_graft:,} px '
		f'({n_graft / graft_diff.size * 100:.2f}%)')
	print(f'    ratio graft/self: {n_graft / max(n_self, 1):.2f}x  '
		f'(near 1.0 confirms JPEG dominates)')


def _compute_ai_bbox(src_path, edit_path, src_orient, edit_orient, gm_size):
	"""Locate the AI region in gain-map display coords for the cyan overlay. Mirrors analyze.py's mask
	reconstruction at sampleSize=4 and threshold>30, then projects the stored-coord mask through the source's EXIF
	orientation into gain-map space.
	"""
	src_stored = graft_lib.load_stored_downsampled(src_path, 4)
	edit_stored = graft_lib.load_stored_downsampled(edit_path, 4)
	edit_stored = graft_lib.reorient_to_match_source(
		edit_stored, src_orient, edit_orient, src_stored.shape)
	if src_stored.shape != edit_stored.shape:
		return None
	ai_diff = np.abs(src_stored - edit_stored).max(axis=2)
	ai_mask_stored = ai_diff > 30
	if not ai_mask_stored.any():
		return None
	mask_pil = graft_lib.reorient(
		Image.fromarray(ai_mask_stored.astype(np.uint8) * 255), src_orient)
	mask_pil = mask_pil.resize(gm_size, Image.NEAREST)
	ai_mask_arr = np.asarray(mask_pil) > 0
	ys, xs = np.where(ai_mask_arr)
	return (xs.min(), ys.min(), xs.max(), ys.max())


def _load_label_fonts():
	try:
		return ImageFont.truetype('arial.ttf', 16), ImageFont.truetype('arial.ttf', 12)
	except Exception:
		default = ImageFont.load_default()
		return default, default


if __name__ == '__main__':
	stems = sys.argv[1:] if len(sys.argv) > 1 else ['20250820_093032']
	for s in stems:
		render(s)
