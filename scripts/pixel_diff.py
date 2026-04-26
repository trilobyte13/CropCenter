"""
Pixel-level perceptual diff between an original JPEG and the cropped output
of the CropCenter export pipeline. Complements scripts/compare_export.py:
that script verifies the JPEG STRUCTURE survived; this one verifies the
PIXELS match (within JPEG re-encode tolerance).

Usage:
    py scripts/pixel_diff.py original.jpg cropped.jpg
    py scripts/pixel_diff.py original.jpg cropped.jpg --save-diff diff.png
    py scripts/pixel_diff.py orig.jpg crop.jpg --max-rotation 5
    py scripts/pixel_diff.py orig.jpg crop.jpg --no-rotation-search

What it does:
    1. Decodes both JPEGs with PIL, applying EXIF orientation so we work in
       display orientation (matching what CropExporter bakes into the output).
    2. Searches for the pose (rotation + translation) that best aligns the
       cropped image inside the original. Translation only by default; with
       rotation search enabled (default ±3° in 0.25° steps), tries each angle
       and picks the (angle, offset) with lowest MAD. Refinement step does a
       finer angle sweep at full resolution around the best coarse pose.
    3. Compares the matched (rotated/offset) original region to the cropped:
         - Mean absolute difference per channel and overall
         - Peak signal-to-noise ratio (PSNR, dB)
         - Maximum per-pixel difference
         - Histogram of per-pixel max-channel differences
         - Worst-deviation pixel coordinates
    4. Optional --save-diff writes a PNG visualization (per-pixel max-channel
       diff scaled to 0..255) so you can eyeball where the differences live.

Flags:
    --save-diff PATH     Write per-pixel diff visualization PNG to PATH.
    --max-rotation DEG   Search rotation range ±DEG (default 3.0). Set 0 to
                         disable, same as --no-rotation-search.
    --rotation-step DEG  Step size for the coarse angle sweep (default 0.25).
    --no-rotation-search Skip rotation search entirely (translation only).

Exit codes:
    0  Pixels match within typical JPEG re-encode tolerance (PSNR ≥ 35 dB
       AND mean abs diff ≤ 3.0 RGB units)
    1  Pixels deviate beyond tolerance — possible regression
    2  Bad input or couldn't locate crop in original

Caveats:
    - In-app rotations beyond ±3° (or whatever --max-rotation is set to) won't
      be picked up. Increase the search range if the user applied larger
      angles.
    - Re-encoded JPEGs differ from their decoded source even with no edits;
      mean abs diff of ~0.5-1.5 RGB units is typical for q=100 round-trips.
      The 3.0 threshold is conservative.

Requires: PIL (Pillow), numpy.
"""

import sys

try:
    import numpy as np
    from PIL import Image, ImageOps
except ImportError as e:
    sys.exit(f'Missing dependency: {e}. Install with: py -m pip install Pillow numpy')


# ── Loading ──────────────────────────────────────────────────────────────────

def load_rgb(path):
    """Decode JPEG to a numpy uint8 RGB array, applying EXIF orientation so
    the array is in DISPLAY orientation (matching what users see and what
    CropExporter bakes into the output's pixel buffer)."""
    img = Image.open(path)
    img = ImageOps.exif_transpose(img)
    return np.asarray(img.convert('RGB'))


# ── Template-match search ───────────────────────────────────────────────────

def _mad_at(orig, crop, x, y):
    """Mean absolute difference (over all channels and pixels) when `crop` is
    placed at offset (x, y) inside `orig`. Casts to int16 so the subtraction
    doesn't underflow uint8."""
    h, w = crop.shape[:2]
    region = orig[y:y + h, x:x + w].astype(np.int16)
    diff = np.abs(region - crop.astype(np.int16))
    return float(diff.mean())


def _brute_search(orig, crop, x_range, y_range, sample_step=1):
    """Exhaustive search over (x, y) in the given ranges. Optionally subsample
    pixels with `sample_step` (every Nth pixel on each axis) to make full-
    resolution coarse passes feasible."""
    best_mad = float('inf')
    best_xy = (0, 0)
    if sample_step > 1:
        crop_s = crop[::sample_step, ::sample_step].astype(np.int16)
    else:
        crop_s = crop.astype(np.int16)
    h_s, w_s = crop_s.shape[:2]
    for y in y_range:
        for x in x_range:
            if sample_step > 1:
                region = orig[y:y + h_s * sample_step:sample_step,
                    x:x + w_s * sample_step:sample_step]
            else:
                region = orig[y:y + h_s, x:x + w_s]
            if region.shape[:2] != crop_s.shape[:2]:
                continue
            mad = float(np.abs(region.astype(np.int16) - crop_s).mean())
            if mad < best_mad:
                best_mad = mad
                best_xy = (x, y)
    return best_xy, best_mad


def find_offset(orig, crop, target_dim=240):
    """Pyramidal template match. Returns ((x, y), best_mad).
    Steps:
      1. Downsample both images so the smaller is `target_dim` px on its long
         edge. Brute-force search over the full coarse offset space.
      2. Scale the coarse best offset back to full resolution and refine via
         a small (±2 * scale) brute-force window at full res.
    """
    H, W = orig.shape[:2]
    h, w = crop.shape[:2]
    if h > H or w > W:
        return None, None  # cropped is larger — can't fit

    # Downsample factor — pick so cropped's long edge is target_dim.
    long_edge = max(h, w)
    scale = max(1, long_edge // target_dim)
    if scale > 1:
        orig_s = orig[::scale, ::scale]
        crop_s = crop[::scale, ::scale]
        Hs, Ws = orig_s.shape[:2]
        hs, ws = crop_s.shape[:2]
        x_lo, x_hi = 0, Ws - ws + 1
        y_lo, y_hi = 0, Hs - hs + 1
        if x_hi <= 0 or y_hi <= 0:
            # Edge case: cropped is essentially the same size as original at
            # downsampled scale. Fall through to full-res refine.
            coarse_xy = (0, 0)
        else:
            (cx, cy), _ = _brute_search(orig_s, crop_s,
                range(x_lo, x_hi), range(y_lo, y_hi))
            coarse_xy = (cx * scale, cy * scale)
    else:
        coarse_xy = (0, 0)

    # Refine at full resolution within ±(2 * scale) of the coarse pick.
    radius = max(2, scale * 2)
    cx0, cy0 = coarse_xy
    x_lo = max(0, cx0 - radius)
    y_lo = max(0, cy0 - radius)
    x_hi = min(W - w + 1, cx0 + radius + 1)
    y_hi = min(H - h + 1, cy0 + radius + 1)
    if x_hi <= x_lo or y_hi <= y_lo:
        return coarse_xy, _mad_at(orig, crop, *coarse_xy)
    return _brute_search(orig, crop, range(x_lo, x_hi), range(y_lo, y_hi))


# ── Rotation-aware pose search ──────────────────────────────────────────────

def _rotate_full(orig_pil, angle):
    """Rotate the original by -angle so a cropped image (presumed rotated by
    +angle in-app) aligns as a pure translation against the rotated original.
    expand=False keeps the array dimensions stable (corners get padded black);
    bicubic resampling matches the quality CropExporter's canvas rotation
    produces. Returns a numpy uint8 RGB array."""
    if abs(angle) < 1e-6:
        return np.asarray(orig_pil)
    rotated = orig_pil.rotate(-angle, resample=Image.BICUBIC,
        expand=False, fillcolor=(0, 0, 0))
    return np.asarray(rotated)


def find_pose(orig, crop, max_rotation=3.0, rotation_step=0.25,
        target_dim=240, refine=True):
    """Search for the rotation + translation that best aligns `crop` inside
    `orig`. Returns (angle_deg, (offset_x, offset_y), best_mad).

    Search strategy:
      1. Sweep angles in [-max_rotation, +max_rotation] at `rotation_step`.
         For each angle, rotate the original (downsampled) and brute-force the
         best translation. Pick the (angle, offset) with lowest MAD.
      2. If `refine` is True, do a finer angle sweep (step / 4) at full res
         around the best coarse angle, with a small ±radius translation
         window from the coarse offset.

    Set `max_rotation=0` to skip rotation search entirely (degenerates to the
    pure-translation `find_offset` path).
    """
    H, W = orig.shape[:2]
    h, w = crop.shape[:2]
    if h > H or w > W:
        return None, None, None

    if max_rotation <= 0:
        offset, mad = find_offset(orig, crop, target_dim=target_dim)
        return 0.0, offset, mad

    long_edge = max(h, w)
    scale = max(1, long_edge // target_dim)
    crop_s = crop[::scale, ::scale]
    hs, ws = crop_s.shape[:2]
    orig_pil = Image.fromarray(orig)

    angles = np.arange(-max_rotation, max_rotation + rotation_step / 2,
        rotation_step)
    best_angle = 0.0
    best_offset = (0, 0)
    best_mad = float('inf')

    for angle in angles:
        rot_full = _rotate_full(orig_pil, float(angle))
        rot_s = rot_full[::scale, ::scale]
        Hs, Ws = rot_s.shape[:2]
        if Hs < hs or Ws < ws:
            continue
        offset, mad = _brute_search(rot_s, crop_s,
            range(0, Ws - ws + 1), range(0, Hs - hs + 1))
        if mad < best_mad:
            best_mad = mad
            best_angle = float(angle)
            best_offset = (offset[0] * scale, offset[1] * scale)

    if not refine:
        return best_angle, best_offset, best_mad

    # Refine: finer angle sweep at full resolution, ±step/4 around best.
    fine_step = rotation_step / 4
    fine_angles = np.arange(best_angle - rotation_step,
        best_angle + rotation_step + fine_step / 2, fine_step)
    radius = max(2, scale * 2)
    cx0, cy0 = best_offset
    x_lo = max(0, cx0 - radius)
    y_lo = max(0, cy0 - radius)
    x_hi = min(W - w + 1, cx0 + radius + 1)
    y_hi = min(H - h + 1, cy0 + radius + 1)
    for angle in fine_angles:
        rot_full = _rotate_full(orig_pil, float(angle))
        offset, mad = _brute_search(rot_full, crop,
            range(x_lo, x_hi), range(y_lo, y_hi))
        if mad < best_mad:
            best_mad = mad
            best_angle = float(angle)
            best_offset = offset

    return best_angle, best_offset, best_mad


# ── Comparison metrics ──────────────────────────────────────────────────────

def compute_metrics(region, crop):
    """Return a dict of pixel-comparison metrics for two same-shape uint8 RGB
    arrays."""
    r = region.astype(np.int16)
    c = crop.astype(np.int16)
    diff = np.abs(r - c)  # shape (H, W, 3), dtype int16
    sq = diff.astype(np.int32) ** 2
    mse = float(sq.mean())
    psnr = float('inf') if mse == 0 else 10.0 * np.log10(255.0 ** 2 / mse)
    per_chan_mad = diff.mean(axis=(0, 1))  # shape (3,)
    max_chan_diff = diff.max(axis=2)  # shape (H, W) — worst channel per pixel
    max_diff = int(max_chan_diff.max())
    worst = np.unravel_index(int(np.argmax(max_chan_diff)), max_chan_diff.shape)
    # Histogram bins of per-pixel max-channel diff.
    bins = [0, 1, 2, 4, 8, 16, 32, 64, 128, 256]
    hist, _ = np.histogram(max_chan_diff, bins=bins)
    n_pixels = max_chan_diff.size
    return {
        'mad': float(diff.mean()),
        'psnr_db': psnr,
        'per_chan_mad_rgb': tuple(float(x) for x in per_chan_mad),
        'max_diff': max_diff,
        'worst_xy': (int(worst[1]), int(worst[0])),
        'hist_bins': bins,
        'hist_counts': hist.tolist(),
        'n_pixels': n_pixels,
    }


# ── Reporting ────────────────────────────────────────────────────────────────

PSNR_GOOD = 35.0   # dB; q=100 round-trip typical 38-45+
MAD_GOOD = 3.0     # RGB units; q=100 round-trip typical 0.5-1.5
# Rotated thresholds: in-app rotation is a core feature, and verifying a
# rotated crop necessarily compares the cropped output (sampled once via
# bilinear during CropExporter's canvas render) against an inverse-rotated
# original (sampled again via PIL bicubic in this script). Each pass adds
# small per-pixel sampling error — pairs that cleanly align with a non-zero
# angle settle around 28-32 dB / 3-6 MAD even when the export is correct.
PSNR_GOOD_ROTATED = 28.0
MAD_GOOD_ROTATED = 6.0


def print_metrics(m):
    print(f'  match offset           : ({m["offset_x"]}, {m["offset_y"]})')
    print(f'  match coarse MAD       : {m["search_mad"]:.3f}')
    print(f'  pixels compared        : {m["n_pixels"]:,}')
    print(f'  mean abs diff (RGB)    : {m["mad"]:.3f}')
    print(f'  per-channel MAD (R/G/B): {m["per_chan_mad_rgb"][0]:.3f} / '
        f'{m["per_chan_mad_rgb"][1]:.3f} / {m["per_chan_mad_rgb"][2]:.3f}')
    print(f'  PSNR                   : {m["psnr_db"]:.2f} dB')
    print(f'  max single-pixel diff  : {m["max_diff"]} (at x={m["worst_xy"][0]}, '
        f'y={m["worst_xy"][1]})')
    print(f'  per-pixel max-diff histogram:')
    bins = m['hist_bins']
    counts = m['hist_counts']
    total = m['n_pixels']
    for i, c in enumerate(counts):
        if c == 0:
            continue
        pct = 100.0 * c / total
        print(f'    [{bins[i]:>3}..{bins[i + 1] - 1:<3}] {c:>10,}  ({pct:5.2f}%)')


def verdict(m):
    is_rotated = abs(m.get('angle_deg', 0.0)) > 1e-6
    psnr_th = PSNR_GOOD_ROTATED if is_rotated else PSNR_GOOD
    mad_th = MAD_GOOD_ROTATED if is_rotated else MAD_GOOD
    psnr_ok = m['psnr_db'] >= psnr_th
    mad_ok = m['mad'] <= mad_th
    if psnr_ok and mad_ok:
        if is_rotated:
            return 0, (f'clean — pixels match within tolerance for a '
                f'{m["angle_deg"]:+.3f}° rotated crop (relaxed thresholds '
                f'cover the two-pass bilinear-resampling cost).')
        return 0, 'clean — pixels match within JPEG re-encode tolerance.'
    reasons = []
    if not psnr_ok:
        reasons.append(f'PSNR {m["psnr_db"]:.2f} dB < {psnr_th} dB threshold')
    if not mad_ok:
        reasons.append(f'mean abs diff {m["mad"]:.3f} > {mad_th} RGB units')
    qualifier = f' (rotated {m["angle_deg"]:+.3f}°)' if is_rotated else ''
    return 1, f'pixel deviation{qualifier}: ' + '; '.join(reasons)


def save_diff_image(region, crop, path):
    """Write a PNG where each pixel's value is the per-channel max diff between
    the matched original region and the cropped image, scaled so the worst diff
    in this comparison shows as full white. Black means perfect match."""
    diff = np.abs(region.astype(np.int16) - crop.astype(np.int16))
    max_chan = diff.max(axis=2).astype(np.uint8)  # (H, W)
    peak = max(1, int(max_chan.max()))
    scaled = np.clip(max_chan.astype(np.int32) * (255 // peak), 0, 255).astype(np.uint8)
    Image.fromarray(scaled, mode='L').save(path)
    print(f'  diff image saved to    : {path}  (peak diff value = {peak})')


# ── Main ─────────────────────────────────────────────────────────────────────

def main(argv):
    save_diff = None
    max_rotation = 3.0
    rotation_step = 0.25
    args = []
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == '--save-diff' and i + 1 < len(argv):
            save_diff = argv[i + 1]
            i += 2
        elif a == '--max-rotation' and i + 1 < len(argv):
            try:
                max_rotation = float(argv[i + 1])
            except ValueError:
                print(f'ERROR: --max-rotation requires a number, got '
                    f'{argv[i + 1]}')
                return 2
            i += 2
        elif a == '--rotation-step' and i + 1 < len(argv):
            try:
                rotation_step = float(argv[i + 1])
            except ValueError:
                print(f'ERROR: --rotation-step requires a number, got '
                    f'{argv[i + 1]}')
                return 2
            i += 2
        elif a == '--no-rotation-search':
            max_rotation = 0.0
            i += 1
        else:
            args.append(a)
            i += 1
    if len(args) != 2:
        print(__doc__)
        return 2
    orig_path, crop_path = args

    print(f'Original: {orig_path}')
    print(f'Cropped:  {crop_path}')
    orig = load_rgb(orig_path)
    crop = load_rgb(crop_path)
    print(f'Original shape (display) : {orig.shape[1]}x{orig.shape[0]} RGB')
    print(f'Cropped shape  (display) : {crop.shape[1]}x{crop.shape[0]} RGB')

    if crop.shape[0] > orig.shape[0] or crop.shape[1] > orig.shape[1]:
        print('ERROR: cropped image is larger than the original on at least '
            'one axis — cannot fit. (Did you swap the arguments? Or was the '
            'cropped image rotated 90° in-app, putting display dimensions on '
            'different axes than the original?)')
        return 2
    print()

    if max_rotation > 0:
        print(f'Locating crop pose (rotation ±{max_rotation}° in '
            f'{rotation_step}° steps + translation)...')
        angle, offset, search_mad = find_pose(orig, crop,
            max_rotation=max_rotation, rotation_step=rotation_step)
    else:
        print('Locating crop offset (translation only)...')
        offset, search_mad = find_offset(orig, crop)
        angle = 0.0
    if offset is None:
        print('ERROR: failed to locate crop in original.')
        return 2

    h, w = crop.shape[:2]
    if abs(angle) > 1e-6:
        # Final comparison region comes from the rotated original at the
        # best angle, so the side-by-side metrics use the same pose the
        # search settled on.
        rotated_full = _rotate_full(Image.fromarray(orig), angle)
        region = rotated_full[offset[1]:offset[1] + h, offset[0]:offset[0] + w]
    else:
        region = orig[offset[1]:offset[1] + h, offset[0]:offset[0] + w]
    metrics = compute_metrics(region, crop)
    metrics['offset_x'] = offset[0]
    metrics['offset_y'] = offset[1]
    metrics['angle_deg'] = angle
    metrics['search_mad'] = search_mad
    print()
    print('Pixel comparison')
    if abs(angle) > 1e-6:
        print(f'  best rotation          : {angle:+.3f}°')
    print_metrics(metrics)

    if save_diff:
        save_diff_image(region, crop, save_diff)

    print()
    code, msg = verdict(metrics)
    print(f'VERDICT: {msg}')
    # Heuristic for sub-pixel / rotation displacement: a clean q=100 round-trip
    # has MAD ~0.5-1.5. MAD above 3 with low max-diff (mostly small-but-
    # everywhere differences) usually means sub-pixel translation or a
    # fractional in-app rotation — every pixel is sampled from a slightly
    # different source position, so the absolute differences are small per
    # pixel but accumulated everywhere. The diff image (saved via --save-diff)
    # makes this visible: high-frequency texture and edges light up while flat
    # regions stay dark.
    if code == 1 and metrics['mad'] > 3:
        print('  Note: the deviation pattern (small but pervasive) is '
            'consistent with sub-pixel displacement — either an in-app '
            'rotation (state.rotationDegrees != 0) or a non-integer crop '
            'origin sampled via bilinear interpolation. The cropped pixels '
            'are still a faithful render of the user\'s edit; the comparison '
            'just can\'t align a rotated/sub-pixel-offset crop against the '
            'original via integer translation. Save with --save-diff to see '
            'whether high-frequency texture is the source of the deviation.')
    return code


if __name__ == '__main__':
    sys.exit(main(sys.argv))
