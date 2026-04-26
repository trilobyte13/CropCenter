"""
Run both export-verification scripts against an original/cropped JPEG pair.
Convenience wrapper for the common case "I want to know if this export is
clean end-to-end" — runs the structural check (compare_export.py, pure-Python,
fast) AND the pixel check (pixel_diff.py, needs PIL + numpy, slower with
rotation search).

Usage:
    py scripts/verify.py original.jpg cropped.jpg
    py scripts/verify.py original.jpg cropped.jpg --quick      # skip pixel diff
    py scripts/verify.py original.jpg cropped.jpg -- --max-rotation 5
                                                  ^^ args after `--` go to
                                                     pixel_diff.py verbatim

Exit code: max of the two child processes' exit codes (so 1 if either flags).

Why a thin wrapper rather than a merged script: the two underlying tools
have different dependency profiles (compare_export is pure-stdlib; pixel_diff
needs Pillow + numpy) and different time scales (sub-second vs minutes).
Keeping them separate means a contributor without PIL installed can still
sanity-check structure, and CI can run the fast structural pass on every
build while reserving the deeper pixel pass for periodic verification.
"""

import os
import subprocess
import sys


def main(argv):
    if '--help' in argv or '-h' in argv or len(argv) < 3:
        print(__doc__)
        return 2

    quick = False
    extra_pixel_args = []
    args = []
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == '--quick':
            quick = True
            i += 1
        elif a == '--':
            extra_pixel_args = argv[i + 1:]
            break
        else:
            args.append(a)
            i += 1
    if len(args) != 2:
        print(__doc__)
        return 2
    orig, crop = args

    here = os.path.dirname(os.path.abspath(__file__))
    structural = os.path.join(here, 'compare_export.py')
    pixel = os.path.join(here, 'pixel_diff.py')

    print('=' * 70)
    print('STRUCTURAL CHECK — compare_export.py')
    print('=' * 70)
    rc_structural = subprocess.call(
        [sys.executable, structural, orig, crop])

    if quick:
        print()
        print(f'Skipping pixel diff (--quick). Structural exit: {rc_structural}')
        return rc_structural

    print()
    print('=' * 70)
    print('PIXEL CHECK — pixel_diff.py')
    print('=' * 70)
    rc_pixel = subprocess.call(
        [sys.executable, pixel, orig, crop] + extra_pixel_args)

    print()
    print('=' * 70)
    print(f'COMBINED VERDICT: structural={rc_structural}  pixel={rc_pixel}  '
        f'overall={max(rc_structural, rc_pixel)}')
    print('=' * 70)
    return max(rc_structural, rc_pixel)


if __name__ == '__main__':
    sys.exit(main(sys.argv))
