"""Side-by-side gain-map content visualization (source vs graft).

Usage:
    py scripts/graft_heat_gainmaps.py 20250820_093032
    py scripts/graft_heat_gainmaps.py 20250819_172023 20250821_103446 20250820_093032

Renders a 3-panel composite per stem at gain-map scale in display orient:

    Panel 1: Source primary thumbnail (spatial reference — where in the scene)
    Panel 2: Source gain-map heat-mapped 0..255
    Panel 3: Graft  gain-map heat-mapped 0..255

Color ramp: black → blue → magenta → orange → yellow → white as the gain-map byte sweeps 0 → 255. Bright = high HDR
boost, dark = no boost.

Panels 2 and 3 should look qualitatively identical — the graft pipeline only modifies pixels inside the AI region, so
the global gain-map distribution should preserve. A visible difference between them indicates either an inpaint that's
spilling out of the masked region, or a Skia re-encode that's significantly perturbing source content.

Output: <stem>-gainmaps-heat.png
"""
import io
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps

import graft_lib

sys.stdout.reconfigure(encoding='utf-8')
BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'graft-fixtures')


def render(stem):
	src_path, _edit_path, graft_path = graft_lib.stem_paths(stem, BASE)
	out_name = f'{stem}-gainmaps-heat.png'

	src_gm_bytes = graft_lib.extract_gain_map(src_path)
	graft_gm_bytes = graft_lib.extract_gain_map(graft_path)
	if src_gm_bytes is None or graft_gm_bytes is None:
		print(f'  {stem}: missing gain-map, skipping')
		return

	src_orient = graft_lib.primary_orient(src_path)
	graft_orient = graft_lib.primary_orient(graft_path)
	src_gm = graft_lib.reorient(Image.open(io.BytesIO(src_gm_bytes)).convert('L'), src_orient)
	graft_gm = graft_lib.reorient(Image.open(io.BytesIO(graft_gm_bytes)).convert('L'), graft_orient)
	if src_gm.size != graft_gm.size:
		src_gm = src_gm.resize(graft_gm.size, Image.LANCZOS)

	gw, gh = graft_gm.size
	src_arr = np.asarray(src_gm, dtype=np.uint8)
	graft_arr = np.asarray(graft_gm, dtype=np.uint8)

	print(f'\n  {stem}  ({gw} x {gh}):')
	print(f'    source gainmap: min={src_arr.min()}  max={src_arr.max()}  '
		f'mean={src_arr.mean():.1f}  '
		f'p10/p50/p90={np.percentile(src_arr, [10, 50, 90]).astype(int).tolist()}')
	print(f'    graft  gainmap: min={graft_arr.min()}  max={graft_arr.max()}  '
		f'mean={graft_arr.mean():.1f}  '
		f'p10/p50/p90={np.percentile(graft_arr, [10, 50, 90]).astype(int).tolist()}')

	lut = _build_thermal_lut()
	panel_src = Image.fromarray(lut[src_arr])
	panel_graft = Image.fromarray(lut[graft_arr])

	primary = ImageOps.exif_transpose(Image.open(src_path)).convert('RGB')
	panel_thumb = primary.resize((gw, gh), Image.LANCZOS)

	font_t, font_s = _load_label_fonts()
	gap = 8
	label_h = 60
	composite = Image.new('RGB', (gw * 3 + gap * 2, gh + label_h), (24, 24, 28))
	composite.paste(panel_thumb, (0, label_h))
	composite.paste(panel_src, (gw + gap, label_h))
	composite.paste(panel_graft, (gw * 2 + gap * 2, label_h))

	cdraw = ImageDraw.Draw(composite)
	cdraw.text((6, 6), f'Source primary — {stem}', fill=(220, 220, 240), font=font_t)
	cdraw.text((6, 26), f'(scene reference)  {gw}x{gh}', fill=(180, 180, 200), font=font_s)
	cdraw.text((gw + gap + 6, 6), 'Source gainmap', fill=(220, 220, 240), font=font_t)
	cdraw.text((gw + gap + 6, 26),
		f'min {src_arr.min()} / max {src_arr.max()} / mean {src_arr.mean():.1f}'
		f'   black=no boost  white=max boost',
		fill=(180, 180, 200), font=font_s)
	cdraw.text((gw * 2 + gap * 2 + 6, 6), 'Graft gainmap', fill=(220, 220, 240), font=font_t)
	cdraw.text((gw * 2 + gap * 2 + 6, 26),
		f'min {graft_arr.min()} / max {graft_arr.max()} / mean {graft_arr.mean():.1f}'
		f'   should look like source',
		fill=(180, 180, 200), font=font_s)

	out_path = os.path.join(BASE, out_name)
	composite.save(out_path)
	print(f'    → wrote {out_name}  ({composite.size[0]}x{composite.size[1]})')


def _build_thermal_lut():
	"""256-entry RGB lookup table for thermal-style gain-map visualization. Black at 0, dark blue at 1/4, magenta at
	1/2, orange at 3/4, white at 255. Different ramp than graft_lib.heat() — this one runs the full 0..255 range and
	emphasises mid-range gradients, which is what you want for showing gain-map content (most pixels in the 100-130
	range with peaks at the bright spots) vs gain-map diff (mostly 0 with sparse 1-2s, where the heat() ramp's
	bottom-heavy weighting matters more).
	"""
	lut = np.zeros((256, 3), dtype=np.uint8)
	for i in range(256):
		v = i / 255.0
		if v < 0.25:
			t = v / 0.25
			lut[i] = [0, 0, int(128 * t)]                     # black → dark blue
		elif v < 0.5:
			t = (v - 0.25) / 0.25
			lut[i] = [int(128 * t), 0, int(128 + 127 * t)]    # blue → magenta
		elif v < 0.75:
			t = (v - 0.5) / 0.25
			lut[i] = [int(128 + 127 * t), int(128 * t), int(255 - 255 * t)]  # magenta → orange
		else:
			t = (v - 0.75) / 0.25
			lut[i] = [255, int(128 + 127 * t), int(255 * t)]  # orange → yellow → white
	return lut


def _load_label_fonts():
	try:
		return ImageFont.truetype('arial.ttf', 16), ImageFont.truetype('arial.ttf', 12)
	except Exception:
		default = ImageFont.load_default()
		return default, default


if __name__ == '__main__':
	stems = sys.argv[1:] if len(sys.argv) > 1 else [
		'20250819_172023', '20250820_093032', '20250821_103446',
	]
	for s in stems:
		render(s)
