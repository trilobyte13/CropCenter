# scripts

Diagnostic, audit, and verification tooling for CropCenter, organized by which pipeline a given script targets:

- **Code audit + refactor** — `audit.py` is the consolidated style / size / ordering audit runner. `refactor.py` is the
  consolidated source-reflow CLI.
- **Crop / export verification** — `compare_export.py`, `pixel_diff.py`, `verify.py` validate that a save through the
  regular crop / rotate pipeline produced a structurally and pixel-correct JPEG vs. the source.
- **Graft diagnostics** — `graft_analyze.py`, `graft_heat_gainmaps.py`, `graft_noise_control.py`, `graft_noise_proof.py`
  (built on `graft_lib.py`) validate that an Apply External Edit graft preserved identity metadata, produced a
  correctly-oriented HDR file, and applied the gain-map inpaint to the right region.
- **HDR JPEG inspection** — `hdr_analyze.py` walks a single Ultra HDR JPEG in place and prints a structural report;
  `hdr_dump.py` extracts the primary / gain-map / MPF / XMP / SEFT segments to disk for offline inspection.
- **Corpus audit** — `corpus_audit.py` runs a deep-coherence pass over every JPEG under `scripts/input/` (recursive;
  EXIF / IFD0 / IFD1 / HDR / SEFT / cross-file consistency).
- **Java logical-SLOC** — `python scripts/audit.py lsloc` is the canonical LSLOC count (see Source-size accounting
  below). `ucc_lsloc.awk` implements a different, statement-based count and does not produce the documented numbers.

All Python scripts are pure stdlib or stdlib + PIL/numpy/piexif so they can run without an Android build environment:

```bash
pip install pillow numpy piexif
```

## Code audit + refactor

### `audit.py` — consolidated style + size + ordering runner

```bash
python scripts/audit.py                   # run every audit; exit non-zero on any failure
python scripts/audit.py over-cols         # one audit by name
python scripts/audit.py lsloc             # logical-SLOC count (advisory; never fails)
```

Audit names:

- `over-cols` — lines exceeding 120 rendered columns (tab=8)
- `over-cols-py` — `scripts/*.py` lines exceeding 120 rendered columns (advisory; never fails — fix by running
  `refactor.py py` and manually fixing whatever its MANUAL FIX report prints)
- `ignored-catches` — catches that swallow exceptions without explaining why
- `static-first` — static methods that follow instance methods in the same tier
- `method-order` — access-tier order (public → protected → package → private) + case-sensitive alphabetical within each
  tier
- `adjacent-comment-styles` — `*/` immediately followed by `//` (consolidate)
- `final-classes` — classes that should be `final` per Effective Java item 19
- `reflow` — comment blocks whose last line could fold, plus comma-wrapped constructs that would fit 120 cols joined
  (advisory; the comma detection mirrors `refactor.py code`'s exclusions — initializer rows, enum constants, case
  labels, annotations)
- `lsloc` — logical SLOC count (advisory)

Default roots are `app/src/main/java` and `app/src/test/java` (with `app/src` for `over-cols`); pass alternative roots
positionally.

### `refactor.py` — consolidated source-reflow CLI

```bash
python scripts/refactor.py code                # join multi-line statements that fit under 120 cols
python scripts/refactor.py code --check        # dry-run (print files that would change)
python scripts/refactor.py comments            # reflow Javadoc + // comment paragraphs to 120 cols
python scripts/refactor.py md REQUIREMENTS.md  # reflow markdown paragraphs to width
python scripts/refactor.py py                  # reflow scripts/*.py comments/docstrings + safe code wraps
python scripts/refactor.py py --check          # dry-run; exit 0 only when nothing would change and no manual fixes
python scripts/refactor.py strip-blanks        # remove blanks around braces, collapse double-blanks
```

`code` joins two continuation shapes: pairwise operator joins (trailing `+` `&&` `||` `(`, leading-operator, and
leading-closer forms) and whole-construct comma joins (a line ending `,` whose complete construct — continuation lines
consumed until one ends without `,` — fits 120 cols joined; never a partial join). Comma joins exclude, per CLAUDE.md:
rows inside brace-opened array / collection initializers (`= {`, Allman `=` + `{`, `new type[] {` — fixture data grids
are deliberate layouts), enum constant lists, comment / Javadoc lines, annotation lines, `case` labels, and Java
text-block interiors (a per-line `"""` state scan — interior lines are string content whose layout is the literal's
value); the shared skips (method chains, ternary, string-literal spans, Allman braces) apply to both shapes. The same
comma detection runs advisory in `audit.py reflow` so drift prints without rewriting anything.

`py` applies the same 120-display-col discipline to the Python tooling itself (default file set: `scripts/*.py`;
alternative paths can be passed positionally). Three phases, each provably scoped via `ast` / `tokenize` so string
literals that aren't docstrings, shebang / PEP 263 coding lines, and code are never rewritten by the prose phases:
docstring paragraphs and full-line `#` comment runs reflow in BOTH directions (join premature wraps within a paragraph,
wrap over-120 lines), and over-120 CODE lines wrap only where provably safe — after a comma at the outermost available
bracket level, continuation one indent unit deeper in the file's own style (tab vs 4-space). Structural paragraphs never
reflow: list markers, colon-ended intro lines, section dividers, uneven indentation (code samples / tables), and `Key:
value` enumerations (2+ short-label colon lines — Panel lists, formula tables). Docstrings carrying a backslash are left
alone (escape semantics), as are r/b/f-prefixed literals. Anything over 120 that can't be safely wrapped (no such comma,
trailing comment, multi-line string span, backslash continuation) is printed under `MANUAL FIX REQUIRED` for a human
fix; the command exits non-zero while that report is non-empty, and `py --check` exits 0 only when a re-run would change
nothing — making it usable as a drift gate. The `audit.py over-cols-py` advisory prints the same drift without rewriting
anything. `audit.py selftest` carries embedded py-reflow cases pinning the guards, both reflow directions, the safe code
wrap, the manual-fix report, and idempotency.

Each transform preserves EOL style and tab/indent shape. `code`, `comments`, and `py` accept `--check` for dry-run
before the path arguments.

## Crop / export verification

These scripts test the path that handles a saved cropped / rotated JPEG. Pass an original/cropped pair on the command
line — no fixture conventions, just file paths.

### `verify.py` — structural + pixel verify (convenience wrapper)

```bash
python scripts/verify.py original.jpg cropped.jpg
python scripts/verify.py original.jpg cropped.jpg --quick           # skip pixel diff
python scripts/verify.py original.jpg cropped.jpg -- --max-rotation 5
```

Runs the structural check (`compare_export.py`) and the pixel check (`pixel_diff.py`) in sequence. Exit code is the max
of the two children's exit codes, each normalized first (a negative return — checker killed by a signal — becomes `3`):
`0` means both passed, `1` means at least one flagged a regression, `2` bad input, `3` a checker crashed. Fast
structural pass is suitable for every-build CI; the slower pixel pass (rotation alignment search) is more periodic.

### `compare_export.py` — JPEG structure check (pure stdlib, fast)

```bash
python scripts/compare_export.py original.jpg cropped.jpg
```

Walks the JPEG marker structure of both files and compares: file size sanity, bytes-per-megapixel ratio, image
dimensions, JPEG quality estimate (IJG formula on the luma DQT), chroma subsampling, EXIF orientation /
Make/Model/Lens/DateTime/MakerNote presence, GPS preservation, IFD1 thumbnail SOI/EOI validity, ICC profile (chunk
numbering validity, seq-order reassembly, length + full-byte match), XMP `hdrgm` namespace, MPF secondary-image count,
secondary JPEG (gain map) validation, SEFT trailer, and APP segment marker summary. No PIL — pure `struct` + raw marker
walking, sub-second on typical Samsung HDR files.

### `pixel_diff.py` — pixel correctness check (PIL + numpy, slower)

```bash
python scripts/pixel_diff.py original.jpg cropped.jpg
python scripts/pixel_diff.py original.jpg cropped.jpg --save-diff diff.png
python scripts/pixel_diff.py orig.jpg crop.jpg --max-rotation 5
python scripts/pixel_diff.py orig.jpg crop.jpg --no-rotation-search
```

Decodes both JPEGs (applying EXIF orientation), then searches for the (rotation, translation) pose that best aligns the
cropped image inside the original. Reports mean absolute diff per channel, PSNR, max per-pixel diff, histogram of
per-pixel max-channel diffs, and worst-deviation pixel coordinates. `--save-diff` writes a PNG visualization of the
per-pixel max-channel diff. Tolerance: pass at PSNR ≥ 35 dB AND mean abs diff ≤ 3.0 RGB units.

## Graft diagnostics

These scripts test the Apply External Edit graft pipeline using a fixture convention rather than CLI file pairs. Default
fixture root is `scripts/graft-fixtures/`; each fixture set is identified by a stem:

```
scripts/graft-fixtures/<stem>.jpg          source primary (Samsung HDR original)
scripts/graft-fixtures/<stem>e1.jpg        Photoshop AI edit (Generative Remove output)
scripts/graft-fixtures/<stem>-graft.jpg    CropCenter graft (the splice we're verifying)
```

The fixtures aren't committed (multi-MB JPEGs); produce your own by running the app's Apply External Edit flow against
your Samsung HDR sources and dropping the trio into `graft-fixtures/`.

Standard fixture stems used during the HDR-graft / inpainter investigation:

```
20250819_172023    landscape, sunset, large AI fill
20250820_093032    landscape, beach + horizon, thin tower fill
20250821_103446    portrait, sky-heavy scene, small AI fill
```

### `graft_analyze.py` — per-image graft correctness + 3-panel heatmap

```bash
python scripts/graft_analyze.py 20250820_093032
python scripts/graft_analyze.py 20250819_172023 20250821_103446 20250820_093032
```

Run this first when you suspect a graft regression. The console report covers identity metadata preservation (Make /
Model / DateTimeOriginal / exposure / GPS / MakerNote), UHDR APP segment presence, gain-map structure, HDR orientation
alignment (catches the sideways-gainmap regression), AI region detection at sampleSize=4, gain-map diff distribution in
display orient, and inpaint effect inside vs outside the dilated AI mask.

Also writes `<stem>-graft-heatmap.png` — a 3-panel visualization with source thumbnail, AI region overlay, and gain-map
diff heat (cyan box = AI bbox).

The most-loaded check is the **inside/outside mean ratio** at the bottom of the report. Healthy values are 5x and up —
an inpaint that's clearly active inside the AI region. A ratio below 5x means either the inpaint isn't firing or the AI
region is so small that JPEG noise dominates inside the dilated mask too; check the AI bbox size before debugging
further.

### `graft_heat_gainmaps.py` — source vs graft gain-map content

```bash
python scripts/graft_heat_gainmaps.py 20250820_093032
```

Side-by-side visualization of source's gain map and the graft's gain map, both heat-mapped through a thermal ramp (black
→ blue → magenta → orange → yellow → white). Bright = high HDR boost. Panels 2 and 3 should look qualitatively identical
— the graft pipeline only modifies pixels inside the AI region, so the global gain-map distribution should preserve. A
visible difference means either the inpaint is spilling out of the masked region, or Skia's re-encode is significantly
perturbing source content.

The console report's `min / max / mean / p10 / p50 / p90` summary statistics should match between source and graft on
all three percentiles — single-bit differences on the extremes are JPEG noise outliers and harmless.

Output: `<stem>-gainmaps-heat.png`

### `graft_noise_control.py` — empirical JPEG-noise-floor measurement

```bash
python scripts/graft_noise_control.py 20250820_093032
```

Numerical proof that the off-mask "red speckle" you see in `graft_analyze.py` panel 3 is JPEG round-trip noise, not an
inpaint bug. Compares Q tables (source's Samsung table vs the graft's Skia table — the all-1s Skia tables confirm Skia
is encoding the gain map at effectively quality 100), measures self-encode noise across q=75..100, runs the self-encode
with Skia's actual Q table, and compares the result against the actual graft-vs-source diff. The verdict line at the
bottom prints the ratio of graft noise to self-encode noise — values near 1.0 confirm JPEG re-encode dominates.

Sample output (20250820_093032):

```
Self-encode q=95: >= 1: 32,945 (4.39%)  mean: 0.045  max: 6
Graft vs source : >= 1: 30,404 (4.05%)  mean: 0.049  max: 42
Ratio graft/self = 0.92x  (near 1.0 confirms JPEG re-encode is dominant)
```

### `graft_noise_proof.py` — visual side-by-side proof

```bash
python scripts/graft_noise_proof.py 20250820_093032
```

Companion to `graft_noise_control.py` — that one proves with numbers, this one with pixels. Three panels: source gain
map, self-encode noise (PIL q=95, no app, no inpaint), and the actual graft-vs-source diff with the AI bbox overlaid in
cyan. If panels 2 and 3 look qualitatively the same speckle pattern with matching density and matching spatial
concentration on smooth gradients, the off-mask "red" in the heatmap is JPEG round-trip noise. Panel 3 typically has a
small bright cluster inside the cyan bbox where the inpainter actually wrote new values; panel 2 doesn't.

Output: `<stem>-jpeg-noise-proof.png`

### `graft_lib.py` — shared helpers

JPEG marker walker, SEFT-aware EOI scan (delegating the trailer boundary to `seft_lib.py` and the primary-EOI walk to
`jpeg_lib.py`), gain-map extraction, EXIF orientation lookup and apply, heat color ramp, Q-table parser, and stem-path
resolution. The four `graft_*` scripts
above are thin wrappers over it. If you write a new diagnostic, prefer extending `graft_lib.py` over copy-pasting the
marker-walking code — the SEFT trailer and DC-prediction-aware scan are easy to get wrong on the first try.

## HDR JPEG inspection

### `jpeg_lib.py` — shared bounded JPEG-structure helpers

Pure-stdlib module holding the two walker families every JPEG diagnostic needs: `find_primary_eoi` (the bounded
SOI → segments → SOS → entropy walk to the byte just past the primary JPEG's EOI, honoring FF 00 byte-stuffing, RST
markers, FF FF fill bytes, and progressive multi-scan chains; a head that fails the `has_soi` predicate is refused
with -1 rather than walked misaligned) and the TIFF IFD entry walk (`ifd_entry_count` /
`walk_ifd_entries` / `read_short_value` / `next_ifd_offset` — bounds-checked, endian-correct iteration over 12-byte IFD
entries). `compare_export.py`, `corpus_audit.py`, `graft_lib.py`, `hdr_dump.py`, and `hdr_analyze.py` all route through
this module rather than re-rolling the walks. The inline-SHORT subtlety lives here: TIFF left-justifies short values in
the entry's 4-byte value field, so a SHORT must be read as a u16 at entry+8 in the file's own byte order — masking the
u32 value field with 0xFFFF silently reads 0 on big-endian (MM) files.

### `seft_lib.py` — shared SEFT-trailer boundary helpers

Pure-stdlib module holding the SEFH-directory walk (`find_seft_trailer`) and the trailer-bounded last-EOI scan
(`find_last_eoi` / `content_end`). SEF data blocks can embed whole JPEGs, so every gain-map SOI/EOI scan must be bounded
at the true trailer start — `hdr_dump.py`, `hdr_analyze.py`, `compare_export.py`, `corpus_audit.py`, and
`graft_lib.py` all route through this module rather than re-rolling the boundary logic. A SEFT footer whose SEFH
directory doesn't parse — bad magic / position, a directory length that doesn't match the declared entry table, or a
referenced block span outside `[0, SEFH start)` — is reported explicitly (`find_seft_trailer` returns a `(None, 8)`
malformed signal; `content_end` returns None) and every consumer fails closed: the gain-map scan is skipped and the
malformed trailer is reported, never scanned as gain-map candidates.

### `hdr_analyze.py` — per-file HDR structural report

```bash
python scripts/hdr_analyze.py input.jpg [more.jpg ...]
```

Walks each input JPEG in place (no separate dump step required) and prints a multi-section `=== <stem> ===` report
covering file size, primary / gain-map EOI offsets, primary + gain-map SOF dims, gain-map segments and own XMP packet,
MPF table (byte order, num_images, num_entries, per-entry attrs), ISO-21496-1 36-byte signaling block, and XMP body
(hdrgm:* attributes, Item:Length, GContainer entries, Extended XMP presence). Grep-friendly `key=value` lines designed
for eyeball scanning of structural anomalies. A malformed file prints an `ERROR <file>: ...` line in its own section
and the batch continues with the remaining inputs (exit 1 when any file errored).

### `hdr_dump.py` — Ultra HDR segment extractor

```bash
python scripts/hdr_dump.py input.jpg                    # dumps to ./hdr-dump-<stem>/
python scripts/hdr_dump.py input.jpg my-dump-dir/       # explicit output path
```

Pulls a multi-picture HDR JPEG apart into separate files — primary JPEG, gain-map JPEG, MPF segment, XMP HDR descriptor,
EXIF segment, ICC profile, SEFT trailer — each a byte-exact slice (never re-encoded). Plus a `<stem>-summary.txt` with
segment offsets, sizes, byte-orders, and HDR detection. SDR inputs still dump whatever segments are present and note the
finding in the summary.

## Corpus audit

### `corpus_audit.py` — deep coherence pass over `scripts/input/`

```bash
python scripts/corpus_audit.py
```

Walks every JPEG under `scripts/input/` (recursive; `.jpg` / `.jpeg`, case-insensitive) and checks CropCenter's
structural invariants: JPEG marker chain, EXIF IFD0 (Orientation=1, dim tags match primary SOF, IFD0 sanitisation —
Compression / JPEGInterchangeFormat / JPEGInterchangeFormatLength must be zeroed if leaked into IFD0), IFD1 (thumbnail
offset + length resolve to a valid embedded JPEG), HDR coherence (MPF claim matches gain-map presence, no orphan hdrgm
XMP), SEFT trailer validity, and cross-file pattern outliers. Exits non-zero when the recursive scan finds zero files —
an empty corpus must not produce a success-shaped "no anomalies" verdict. Per-file resilience: a file whose analysis
raises (truncated SOF, out-of-range offsets) is recorded as its own P0 `file unparseable` anomaly row and the scan
continues; a file with no parseable primary SOF is a P0 anomaly rather than a silent `?` dims cell, and so are a
missing SOI head (`jpeg_lib.has_soi`) and a primary stream whose EOI never resolves (`find_primary_eoi` == -1). Pure
stdlib.

## Source-size accounting

`python scripts/audit.py lsloc` walks both `app/src/main/java` and `app/src/test/java` and produces the number
documented in `REQUIREMENTS.md`.

### `ucc_lsloc.awk` — legacy Java logical-SLOC counter

```bash
awk -f scripts/ucc_lsloc.awk app/src/main/java/com/cropcenter/**/*.java
```

UCC-style logical SLOC approximation for Java: counts executable statements, control structures, type declarations, and
method/constructor signatures while skipping comment lines and string literals. Independent of the rest of the tooling.

## Output files and user data

Heatmap PNG outputs (`*-graft-heatmap.png`, `*-gainmaps-heat.png`, `*-jpeg-noise-proof.png`) are not committed — they're
regenerable from fixtures + scripts. Fixture JPEGs in `graft-fixtures/` and arbitrary inputs in `scripts/input/` are
also not committed (multi-MB per-user data). The audit/refactor, crop/export verification, HDR inspection, and
corpus-audit tools work on explicit CLI inputs or `scripts/input/`; only the graft diagnostics require per-user
fixtures.

## scripts/input/

`scripts/input/` is the user-managed directory `corpus_audit.py` walks for its deep-coherence pass and that
`hdr_analyze.py` / `hdr_dump.py` / the verification scripts target by path. Drop arbitrary JPEGs (Samsung Ultra HDR
sources, edited outputs, suspected-broken files) into the directory; the corpus audit will report structural anomalies
across the whole set.
