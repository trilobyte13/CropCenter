"""Empirical control: confirm the gain-map off-mask diff is JPEG round-trip noise.

Usage:
    py scripts/graft_noise_control.py 20250820_093032

Hypothesis under test: the ~4% off-mask "red speckle" you see in the gain-map diff heatmap (graft_analyze.py panel 3
outside the cyan AI bbox) is JPEG round-trip noise from Skia re-encoding the gain-map at save time, not an inpaint bug
or app-side artifact.

Control: take the source's own gain-map, re-encode it ourselves at multiple quality levels (and with the graft's actual
Skia-derived Q table), decode again, and measure the resulting noise. If the self-encode noise floor matches the actual
graft-vs-source diff, the JPEG hypothesis is confirmed and there's no app bug to chase. If self-encoding produces zero
noise but the graft shows 4%, the explanation is wrong and the bug is elsewhere.

Reports six sections of numbers — Q-table comparison, self-encode at q=95, the full quality sweep (q=75..100),
self-encode using Skia's actual Q table, the reference graft-vs-source diff for the same stem, and a verdict line
comparing the two.

Companion script: jpeg_noise_proof.py renders the same comparison visually.
"""
import io
import os
import sys

import numpy as np
import piexif
from PIL import Image

import graft_lib

sys.stdout.reconfigure(encoding='utf-8')
BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'graft-fixtures')
DIFF_BINS = [0, 1, 3, 5, 10, 30, 60, 256]


def run(stem):
	src_path, _edit_path, graft_path = graft_lib.stem_paths(stem, BASE)

	print('\n' + '=' * 80)
	print(f'1. Q tables — source gainmap vs graft gainmap ({stem})')
	print('=' * 80)
	src_gm_bytes = graft_lib.extract_gain_map(src_path)
	graft_gm_bytes = graft_lib.extract_gain_map(graft_path)
	if src_gm_bytes is None or graft_gm_bytes is None:
		print(f'  {stem}: missing gain-map, skipping')
		return

	src_q = graft_lib.extract_dqt_tables(src_gm_bytes)
	graft_q = graft_lib.extract_dqt_tables(graft_gm_bytes)
	for label, qs in (('source', src_q), ('graft ', graft_q)):
		print(f'  {label} gainmap Q tables: {len(qs)}')
		for tid, prec, t in qs:
			print(f'    id={tid} prec={prec} first 8: {list(int(x) for x in t[:8])}')
	if len(src_q) == len(graft_q):
		all_match = all(np.array_equal(s[2], g[2]) for s, g in zip(src_q, graft_q))
		print(f'\n  Q tables identical between source and graft? {all_match}')

	src_gm = Image.open(io.BytesIO(src_gm_bytes)).convert('L')
	src_arr = np.asarray(src_gm, dtype=np.int16)
	print(f'\n  source gainmap: {src_arr.shape[1]}x{src_arr.shape[0]}')

	print('\n' + '=' * 80)
	print('2. Self-encode round-trip noise (PIL q=95)')
	print('=' * 80)
	d, n = _self_encode_noise(src_gm, src_arr, quality=95)
	_print_diff_distribution(d, label='self-diff')
	print(f'  >= 1: {n:,} ({n / d.size * 100:.4f}%)  '
		f'mean: {d.mean():.4f}  max: {d.max()}')

	print('\n' + '=' * 80)
	print('3. Round-trip noise across quality levels')
	print('=' * 80)
	print(f'  {"q":>4} {"size_bytes":>12} {">=1 px":>10} {"%":>7}  {"mean":>6} {"max":>4}')
	for q in (75, 85, 90, 95, 98, 100):
		d, n = _self_encode_noise(src_gm, src_arr, quality=q)
		size = _self_encode_size(src_gm, quality=q)
		print(f'  {q:>4} {size:>12,} {n:>10,} {n / d.size * 100:>7.4f}%  '
			f'{d.mean():>6.3f} {d.max():>4}')

	if graft_q:
		print('\n' + '=' * 80)
		print("4. Re-encode with the graft's actual Skia-derived Q table")
		print('=' * 80)
		qtable_skia = list(int(x) for x in graft_q[0][2])
		buf = io.BytesIO()
		src_gm.save(buf, 'JPEG', qtables=[qtable_skia], optimize=False, progressive=False)
		decoded = np.asarray(Image.open(io.BytesIO(buf.getvalue())).convert('L'),
			dtype=np.int16)
		d = np.abs(src_arr - decoded)
		n = (d >= 1).sum()
		print(f'  size with Skia Q table: {len(buf.getvalue()):,} bytes')
		_print_diff_distribution(d, label='diff')
		print(f'  >= 1: {n:,} ({n / d.size * 100:.4f}%)  '
			f'mean: {d.mean():.4f}  max: {d.max()}')

	print('\n' + '=' * 80)
	print('5. Reference: actual graft vs source gainmap diff (display orient)')
	print('=' * 80)
	real_diff = _real_graft_diff(src_path, graft_path, src_gm_bytes, graft_gm_bytes)
	_print_diff_distribution(real_diff, label='diff')
	n_real = (real_diff >= 1).sum()
	print(f'  >= 1: {n_real:,} ({n_real / real_diff.size * 100:.4f}%)  '
		f'mean: {real_diff.mean():.4f}  max: {real_diff.max()}')

	print('\n' + '=' * 80)
	print('6. Verdict')
	print('=' * 80)
	d, n_self = _self_encode_noise(src_gm, src_arr, quality=95)
	print(f'\n  If JPEG round-trip is the dominant cause, the q=95 self-encode noise should')
	print(f'  match the order of magnitude of the actual graft-vs-source diff.')
	print(f'\n  Self-encode q=95: >= 1: {n_self:,} ({n_self / d.size * 100:.4f}%)  '
		f'mean: {d.mean():.4f}  max: {d.max()}')
	print(f'  Graft vs source : >= 1: {n_real:,} '
		f'({n_real / real_diff.size * 100:.4f}%)  '
		f'mean: {real_diff.mean():.4f}  max: {real_diff.max()}')
	ratio = n_real / max(n_self, 1)
	print(f'  Ratio graft/self = {ratio:.2f}x  '
		f'(near 1.0 confirms JPEG re-encode is dominant)')


def _print_diff_distribution(diff_arr, label):
	counts, _ = np.histogram(diff_arr.flatten(), bins=DIFF_BINS)
	total = diff_arr.size
	print(f'  {label} distribution:')
	for i in range(len(DIFF_BINS) - 1):
		lo, hi = DIFF_BINS[i], DIFF_BINS[i + 1]
		print(f'    {lo:>3}-{hi - 1:>3}: {counts[i]:>10,} px = '
			f'{counts[i] / total * 100:7.4f}%')


def _real_graft_diff(src_path, graft_path, src_gm_bytes, graft_gm_bytes):
	"""Reproduce analyze.py's gain-map diff: reorient both to display, resize source if dimensions don't match,
	return the per-pixel max-channel diff as uint8.
	"""
	src_orient = graft_lib.primary_orient(src_path)
	graft_orient = graft_lib.primary_orient(graft_path)
	src_gm = graft_lib.reorient(Image.open(io.BytesIO(src_gm_bytes)).convert('L'), src_orient)
	graft_gm = graft_lib.reorient(Image.open(io.BytesIO(graft_gm_bytes)).convert('L'), graft_orient)
	if src_gm.size != graft_gm.size:
		src_gm = src_gm.resize(graft_gm.size, Image.LANCZOS)
	src_arr = np.asarray(src_gm, dtype=np.int16)
	graft_arr = np.asarray(graft_gm, dtype=np.int16)
	return np.abs(src_arr - graft_arr).astype(np.uint8)


def _self_encode_noise(src_gm_pil, src_arr, quality):
	"""Encode → decode the source gain-map at the given quality, return (diff_arr, n_diff)."""
	buf = io.BytesIO()
	src_gm_pil.save(buf, 'JPEG', quality=quality, optimize=False, progressive=False)
	decoded = np.asarray(Image.open(io.BytesIO(buf.getvalue())).convert('L'), dtype=np.int16)
	d = np.abs(src_arr - decoded)
	return d, int((d >= 1).sum())


def _self_encode_size(src_gm_pil, quality):
	buf = io.BytesIO()
	src_gm_pil.save(buf, 'JPEG', quality=quality, optimize=False, progressive=False)
	return len(buf.getvalue())


if __name__ == '__main__':
	stems = sys.argv[1:] if len(sys.argv) > 1 else ['20250820_093032']
	for s in stems:
		run(s)
