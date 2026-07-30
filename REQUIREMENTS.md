# CropCenter - Application Specification

## Overview

CropCenter is a native Android image cropping tool focused on precise center-based cropping with full metadata
preservation, including Samsung Ultra HDR gain map data and verbatim re-append of any existing Samsung SEFT trailer (so
a Gallery-edited file keeps its Revert chain across CropCenter re-edits).

**Package**: `com.cropcenter`
**Min SDK**: 35 (Android 15)
**Target/Compile SDK**: 36
**Language**: Java 21
**Build**: AGP 9.1.1, Gradle 9.3.1
**LSLOC**: 21,797 total (11,142 main + 10,655 test) — UCC-style logical SLOC via `scripts/audit.py lsloc` (strips
`//` and `/* */` comments, then counts every remaining non-blank line except lines made purely of structural delimiters
— braces, parens, commas, semicolons; `scripts/ucc_lsloc.awk` implements a different, statement-based count and does NOT
produce these numbers). **Numbers must be exact** — every change that adds, removes, or restructures Java code must
refresh this line via `python scripts/audit.py lsloc` in the same commit. No tolerance band; the spec matches the
codebase or it's a drift bug.

### Test coverage

**Tests**: 1,158 tests in 82 test classes — plain JUnit 4, headless (`returnDefaultValues=true`, no mocking libraries,
no Robolectric, no device), all green, none skipped. The test tree carries 89 `.java` files: the 82 test classes plus 7
fixture/fake helpers with no tests of their own (`metadata/JpegFixtures`, `metadata/PngFixtures`,
`metadata/EndianStreamFixtures`, `metadata/TiffFixtures`, `metadata/MpfFixtures`, and the root
`RecordingImageLoadHost` and `InMemorySharedPreferences`). **Numbers must be exact**, with the same discipline as the
LSLOC pin: derive them from the JUnit XMLs of a fresh full run (`gradlew.bat testDebugUnitTest --rerun-tasks`, then
count classes and `tests=` totals over `app/build/test-results/testDebugUnitTest/TEST-*.xml`) AND cross-check against
the `@Test` annotation count over `app/src/test/java` — both counts must agree before the numbers land here. Refresh
this section in the same commit as any test change, via the same XML/annotation double count. No tolerance band.

| Package                             | Test classes | Tests     |
| ----------------------------------- | ------------ | --------- |
| `com.cropcenter` (root controllers) | 15           | 202       |
| `com.cropcenter.crop`               | 8            | 75        |
| `com.cropcenter.graft`              | 1            | 12        |
| `com.cropcenter.metadata`           | 16           | 287       |
| `com.cropcenter.model`              | 11           | 130       |
| `com.cropcenter.util`               | 15           | 282       |
| `com.cropcenter.view`               | 16           | 170       |
| **Total**                           | **82**       | **1,158** |

What the suite pins: the byte-integrity chokepoints (JpegMarkerWalker's primary-EOI walk, JpegMetadataInjector's
streaming byte-identity and `skipExactly` forced-progress skip, GainMapComposer / GraftWriter HDR assembly with MPF
offset patching, ExifPatcher IFD rewrites and thumbnail budget clamps, XmpItemLengthPatcher's fail-closed anchor rules);
the boundary matrices on the save-verification mirror paths (SafFileHelper.readbackByteCountFromStream and
ExportPipeline.streamCompare, the latter a one-line delegating seam over the shared SafFileHelper.streamCompare body —
first-mismatch offset across chunk boundaries, short EOF, trailing-past-expected, exact match) and on the geometry
layer (RotatedCropClamp axis rescues, ViewportMath NaN fail-closed zoom guards, CropEngine Pin-mode no-ops and
rotate-first selection midpoints); the mutation-verified gates (every HIGH gap of the 2026-07-10
campaign had its regression injected, the new test observed red, the edit reverted exactly, and the suite re-observed
green); and the epoch/race pins (CropState epoch-guard and reset-race interleaves, StateBus batch suppression,
listener-fire counts on every selection mutator).

Headless-untestable inventory (documented, deliberate — these surfaces return defaults or need Android classes under
this toolchain):

- CropExporter's PNG eXIf injection >2 GB guards — the u31-overflow throws need multi-GB inputs; the int-wrap regression
  classes ARE pinned via long-arithmetic fixtures (`CropExporterPngExifInjectionTest`), the literal >2 GB throws are not
  executed.
- ExportPipeline's real-Bitmap bypass path — `canBypassEncode` against actually-decoded Bitmaps and the
  bitmap-dimension-match reject branch; Bitmap methods return defaults headlessly.
- MotionEvent / Looper-bound surfaces — TouchGestureHandler and CropEditorView touch streams, `onDraw` paths, UiSync
  handler dispatch, dialog / detector wiring.
- Skia-bound encode / decode — `CropExporter.export` / `exportJpeg` / `exportPng`, `recomputeCrop`'s full path,
  EditAligner decode branches, `buildCroppedGainMap`, and the Bitmap halves of UltraHdrCompat / GainMapInpainter /
  AiRegionDetector / SafFileHelper.
- SAF / ContentResolver-bound — StoragePermissionHelper, `verifyPhase` / `writePhase` / `replaceViaFileIo`,
  `classifySafFallbackOutcome` / `tryRename`.

Device-only verification (campaign list — behaviors only observable on hardware, not reproducible headlessly):

- Samsung Gallery Revert accepting grafted HDR files (graft structure is cross-checked offline via `scripts/graft_*.py`
  against device-produced fixtures; Gallery's acceptance itself is a device check).
- `FileInputStream.skip()` transiently returning 0 on Samsung's FUSE-backed scoped storage — the observed trigger for
  the `skipExactly` chokepoint.
- Silent SAF stream-write corruption on Samsung devices — the reason the readback verifiers exist.
- Skia's exact nearest-neighbor tie-break direction (the BitmapUtils parity math is device-independent; the tie-break
  itself is not verified off-device).
- The 200 MP PNG decode OOM — heap arithmetic from spec figures, not a runtime repro.
- HDR display rendering behind the `[HDR OK]` toast — gain-map application at view time is visual-only.
- The read-only-grant persistable downgrade — `ImageLoadController.tryTakePersistable` takes read+write first and, for
  the read-only call sites (Share / restore), retries with `FLAG_GRANT_READ_URI_PERMISSION` alone when a read-only
  persistable grant rejects the write bit (the Save As site stays read+write-or-nothing — Replace's SAF fallbacks need
  write). `takePersistableUriPermission` is ContentResolver-bound, so the retry ladder is only observable on hardware
  against a provider that offers read-only persistable grants.
- An `assembleRelease` install with R8 shrinking + obfuscation fully enabled (the ProGuard keeps were removed):
  exercise load / crop / save / HDR save / graft, explicitly verifying the gain map survives a release-build save —
  the metadata pipeline fails closed, so silent HDR-to-SDR degrade is the one failure R8 breakage would NOT crash on.
- Thumbnail-cache evictions staying terminal against an in-flight decode straggler, end-to-end — the eviction epoch
  protocol itself (generation snapshot / check+insert vs. increment+evict, atomic under `FolderBrowser`'s cache lock)
  is pinned headlessly, but the full straddle — a picker worker inside a native BitmapFactory decode as the
  picker-dismiss / export-dispatch eviction lands — exercises framework-native BitmapFactory and LruCache, so only
  hardware demonstrates it.

---

## Architecture

### Single Activity Layout

```
+--------------------------------------------------------------------+
| Toolbar: [AR][Grid][Pin]            [Settings][Open][Graft][Save]  |
+--------------------------------------------------------------------+
|                                                                    |
|              CropEditorView (flexible height)                      |
|     Image + crop overlay + grid + selection points                 |
|                                                                    |
+--------------------------------------------------------------------+
| [Select][Move]  [Both][H][V]  [Undo][Redo][Clear]                  |
+--------------------------------------------------------------------+
| [Auto]                          0°                          [Reset]|
+--------------------------------------------------------------------+
| [−]   Rotation ruler (scrollable, Galaxy-style)                 [+]|
+--------------------------------------------------------------------+
| Info: formats | image size | crop size | zoom                      |
+--------------------------------------------------------------------+
```

The top toolbar's three left chips (AR / Grid / Pin) are MaterialButton.TextButton chips matching the Mode / Lock / Undo
cluster's visual tier. AR is a current-ratio chip ("4:5", "Full", "Custom 7:5"…) that opens a PopupMenu of presets +
Custom on tap. Grid and Pin are toggle chips (mauve when on, surface2 when off).

The mode buttons, lock-axis row, and undo/redo/clear live on a single consolidated point-controls row.
Undo/Redo/Clear stay visible in Move mode but render disabled (greyed-out) so the user sees the affordance
without the row reflowing on mode switch. The Auto-rotate button lives at the left edge of the rotation-actions row
(with the 0° label at center and Reset at the right edge, above the ruler row whose −/+ buttons flank the ruler); it's
always visible but disabled until an image is loaded.

**Disabled-controls-stay-visible principle**: every toolbar / bottom-row control that depends on a
loaded image (or a specific source format, or an editor mode) renders disabled when its precondition isn't met rather
than vanishing from the layout. Concretely: AR / Grid / Pin chips / Mode buttons / H / V lock-axis / Auto-rotate all
disable when no image is loaded; Both disables in Move mode (Move-mode lock-axis pref is V or H only); Graft disables
when no image OR the loaded source isn't JPEG OR a bg op is busy; Undo / Redo / Clear disable in Move mode AND when
their history-state condition isn't met; ruler-zoom +/− disable at min/max pixels-per-degree AND when no image is
loaded; Save disables when no image is loaded OR a bg op is busy. Disabled controls render at the same alpha / surface1
text tint as Undo/Redo/Clear's disabled state for a uniform look. Settings is always enabled; Open works pre-image but
disables while a bg op is busy (`setBusyUi` gates Save / Open / Graft together). The Auto↔Cancel text swap during
horizon-paint mode doesn't shift adjacent controls (the layout anchors each rotation-row control independently — the
mechanism is documented at the row in activity_main.xml).

The toolbar's `Pin` chip is the freeze-crop ("CenterMode.LOCKED") gate — tap to toggle; when on (mauve), drags pan the
viewport regardless of mode. `Settings` opens the combined Settings dialog (see §11 for the full card layout — Grid,
Pixel Grid toggle + color, Selection & Paint color, Build).

Beyond the image + crop overlay + grid, the editor canvas (`EditorRenderer`) draws four pieces of furniture: a crosshair
at the crop center (grid color at 0xCC alpha), a live `W x H` crop-size readout at the crop's top-left corner, a
numbered index label on every selection point (placement order), and — when no image is loaded — the hint text "Tap the
gallery icon to open an image".

**Permissions**: `MANAGE_EXTERNAL_STORAGE` only. Gates four capabilities:
- **In-app Open picker** — `MainActivity.showOpenPickerDialog` checks the grant first; if held, routes to
  `OpenPickerDialog` (browses primary external storage via `File.listFiles`). Without MES, `listFiles` returns null on
  most directories under primary external on Android 11+ scoped storage, so the picker would open showing partial /
  empty contents with no signal — instead the tap surfaces a directed toast pointing the user at the Settings →
  Permissions affordance. There is no fallback to the system SAF picker for Open (replaced by design — see §1 Input
  methods).
- **Merged in-app save dialog (primary path)** — `SaveController.showSaveDialog` checks the grant first; if held, routes
  to `FolderPickerDialog` (browses the filesystem via `java.io.File`, writes through externalstorage SAF document URIs
  that `SafFileHelper.fileFromSafUri` resolves to the same `File`). Without MES, falls back to the legacy `SaveDialog` →
  `ACTION_CREATE_DOCUMENT` SAF picker which on Samsung One UI hides every subfolder inside internal storage.
- **File-I/O Replace** — `ReplaceStrategy` strategy A's direct `FileOutputStream` write + atomic rename bypasses the SAF
  stream path that some Samsung providers silently corrupt.
- **Samsung MediaStore EXIF workaround** — `SafFileHelper.tryReadDirectlyFromPath`. Also the read path the in-app Open
  picker exercises end-to-end: the picked `File` rounds to an externalstorage SAF URI via
  `SafFileHelper.buildExternalStorageDocumentUri`, then `readUriBytes` → `tryReadDirectlyFromPath` → `getFilePathAndId`
  resolves the URI back to the same `File` (pure string parsing of the `primary:<rel>` docId) and reads via
  `FileInputStream`. **`getFilePathAndId`'s initial MediaStore probe is wrapped in its own SecurityException catch** so
  a locally-constructed URI without `takePersistableUriPermission` (the in-app picker never calls it — there's no SAF
  grant to persist) falls through to the externalstorage-authority branch rather than short-circuiting the whole
  resolver. Without that inner catch, the `ContentResolver.query` would throw on the un-granted URI, the outer catch
  would return null, and `readUriBytes` would fall through to the SAF stream path which also lacks a grant — surfacing a
  "permission denied" toast on every Open pick. Without MES the file read itself fails; the Open flow's MES gate
  prevents that case from being reached.

The grant prompt is offered at first launch via `MainActivity.showAllFilesAccessPrompt` (a one-time in-app dialog with a
direct deep-link to the system "All files access" page), and from the Settings dialog's Permissions card whenever the
permission is missing. `ACCESS_MEDIA_LOCATION` is NOT declared (would only matter for `MediaStore.setRequireOriginal`,
which the codebase doesn't use — it reads via SAF directly).

**Java heap**: `android:largeHeap="true"` is declared in the manifest. `CropExporter.exportJpeg` runs a fully streaming
disk-backed pipeline for 200 MP-class q=100 saves — `encodeToTempfile` (Bitmap.compress straight to a
FileOutputStream; the same parameterised encoder serves the PNG pipeline), then `JpegMetadataInjector.injectFileToFile`
(head-scan + chunk-copy tail), then
`GainMapComposer.composeFileToFile` (16 MB head for XMP/MPF patch + chunk-copy tail + gain-map append), then
`appendSeftFileToFile` (chunk-copy + SEFT append), and finally a single sized `Files.readAllBytes` on the last tempfile
once all upstream byte[]s have been freed. Peak Java heap during the entire encode → inject → compose → SEFT chain is
~64 KB (the streaming chunk buffer) plus the metadata segments (a few MB at most); the only large byte[] alive at any
point is the final readback (~100-150 MB on a 200 MP save). `UltraHdrCompat.sweepStaleCacheFiles` (posted to bg from
`MainActivity.onCreate`) reclaims pipeline tempfiles from hard process kills. The complete set of pipeline tempfile
prefixes the sweep matches: `hdr_src_jpeg_encode_` (JPEG encode stage), `hdr_src_inject_` (JPEG metadata inject),
`hdr_src_compose_` (JPEG gain-map compose), `hdr_src_reinject_` (JPEG HDR-drop re-inject), `hdr_src_seft_` (JPEG SEFT
append), `hdr_src_png_encode_` (PNG encode stage), `hdr_src_png_inject_` (PNG eXIf inject), and the bare
`hdr_src_<pid>_<nanos>.jpg` scratch copy written by `UltraHdrCompat.decodeHdrBitmap` for the HDR re-decode (a full
source-image copy, up to ~200 MB — the most privacy-sensitive member of the set) — all matched by the `hdr_src_*` prefix
filter — plus `input_raw_*` (written by `SafFileHelper.readUriBytes` as the per-call cache-file fallback when a SAF URI
doesn't resolve to a direct filesystem path; up to the 128 MB MAX_READ_BYTES cap), the second prefix
`UltraHdrCompat.sweepStaleCacheFiles` filters on. Without `largeHeap`, the per-app Java heap cap (~256 MB on stock
Android, varies by OEM) can't satisfy the final readback even on a clean heap; `largeHeap` typically lifts the cap to
512 MB+ (768 MB or higher on Samsung flagships), enough headroom for the readback plus the GC slack the runtime needs to
actually grow the heap into that budget. Native bitmaps (sourceImage, displayImage, outputBitmap) live in native heap
and don't compete for this cap — only the byte[] arrays do.

**Native heap (HDR re-encode)**: `exportJpeg`'s stage ordering recycles the cropped primary bitmap (`bmp`, up to ~800 MB
of native pixel buffer on a lightly-cropped 200 MP capture) BEFORE calling `buildCroppedGainMap`. The HDR re-decode path
(`UltraHdrCompat.compressWithGainmap`) allocates its own 200 MP HDR-decoded bitmap + a cropped HDR primary + a cropped
gain-map surface — up to ~2 GB of native heap at peak. With `bmp` still alive AND `state.getSourceImage()` (another ~800
MB) still alive across the save, native heap can't satisfy the HDR re-decode allocations and `compressWithGainmap`
silently catches the failure, returns an empty Optional, and HDR drops from the saved file. The stage order is: build
thumbnail (needs `bmp`) → encode `bmp` to tempfile (needs `bmp`) → recycle `bmp` → build cropped gain map (no `bmp`
reference, ~800 MB of native heap freed for the HDR intermediates).

**PNG streaming pipeline**: `CropExporter.exportPng` runs a disk-backed pattern that NEVER materialises the encoded
PNG as a single byte[] — `encodeToTempfile` (Bitmap.compress(PNG, 100) straight to a FileOutputStream) →
`injectPngExifFromTiffFileToFile` (stream-copy with eXIf chunk spliced after IHDR) → return a tempfile-mode
`ExportResult`. `ExportPipeline.writePhase` then `writePayloadToStream`s the tempfile in 64 KB chunks to the SAF output
stream or direct-file FileOutputStream; no `Files.readAllBytes` ever runs. PNG-lossless on a 200 MP ARGB bitmap (800 MB
raw) compresses to roughly 400-600 MB; the previous in-memory path's `BAOS.toByteArray()` peak was ~1 GB live (BAOS
internal buffer at the next power of 2 above the final size plus the toByteArray copy), and even after that refactor a
single `Files.readAllBytes(tempfile)` at the end of `exportPng` allocated a 400-600 MB byte[] that OOM'd on top of the
~390 MB of resident Java state (originalFileBytes, jpegMeta) sitting in heap at save time. Peak Java heap during the
full streaming pipeline (encode + inject + write) is the chunk buffer + the resident state — no large byte[] ever exists
for the PNG payload.

**`ExportResult` dual-mode**: the record carries EITHER `bytes` (JPEG streaming pipeline's final readback ~100-150 MB,
plus the bypass-encode path where `state.originalFileBytes` is already in heap) OR `tempfile` (PNG streaming pipeline,
where the payload is too large to safely materialise). Exactly one is non-null; the size() accessor returns the
appropriate metric without loading the file. `ExportPipeline.writePhase` and `tryDirectAtomicWrite` route through
`writePayloadToStream(ExportResult, OutputStream)` which picks `os.write(bytes)` for bytes-mode and chunked
FileInputStream copy for tempfile-mode. `verifyPhase` uses `encoded.size()` instead of `data.length`; the fallback
content-verify (fires when the clean-write checks fail) never materialises the payload — bytes-mode streams the saved
URI back against the in-heap array via `SafFileHelper.readbackByteCount`, tempfile-mode streams URI and tempfile
against each other via `ExportPipeline.streamCompareUriToFile`. The Replace flow
(`ReplaceStrategy.writeReplacementPayload`) — which every crash-safe in-app save routes through, fresh non-colliding
saves included — consumes the payload in whatever mode `ExportResult` carries it: bytes-mode uses the in-heap array,
tempfile-mode streams from the retained encode tempfile (kept alive by `runExportBg` until the callback returns) through
`ExportPipeline.writePayloadToStream` for strategy A's temp-sibling write and `ExportPipeline.streamCompareUriToFile`
for strategy B's byte-for-byte readback, both in `JpegMetadataInjector.STREAM_CHUNK_SIZE` chunks. No
`Files.readAllBytes` runs anywhere on the Replace path, so the 400-600 MB 200 MP PNG payload is never materialised as a
single byte[].

### Key Components

| Component | Class | Purpose |
|-----------|-------|---------|
| State | `model/CropState` | Central state: crop params, metadata, rotation anchor (stable intent center for no-selection rotations). Cross-thread fields are volatile so the bg load/graft/save executor can publish to the UI thread without a lock — the blanket rule: every field written on the bg load/graft path is volatile. Notable contracts: `displayImage` is the display proxy installed in lockstep with sourceImage via `setSourceImage(source, display)`; `graftApplied` is read by `ExportPipeline.canBypassEncode` on the UI thread and written by `installGraft` on bg; `exportConfig` + `gridConfig` are read by `ExportPipeline.canBypassEncode` and `SaveController.openSaveOptionsDialog` on UI and mutated via `installLoadedImage` and `updateGridConfig` from either thread; `gainMap` + `seftTrailer` are committed on bg by `applyBytes` and read by save paths on bg via the same single-thread executor. **Load-epoch guard**: `reset()` (bg load executor) increments a monotonic load epoch inside a `commitLock` critical section that also clears the crop-geometry / selection fields; the UI-thread mutators whose inputs derive from the pre-reset image (`setCenter`, `setCenterUnclamped`, `setRotationDegrees`, `setAnchor`, `setCropSizeSilent`, `addSelectionPoint`, `removeSelectionPoint`, `clearSelectionPoints`, `replaceSelectionPoints`) capture the epoch at entry and abandon their commit — no field writes, no listener fire — when the epoch changed underneath them, so a racing gesture callback, ruler fling, recompute pass, or undo/redo restore can never install the previous image's crop center, rotation, anchor, crop dims, or selection points onto a freshly reset state (`removeSelectionPoint` reports the abandoned commit as `false`). `getImageWidth` / `getImageHeight` keep the returns-0-when-unloaded contract even when a bg `reset()` nulls `sourceImage` mid-call. |
| State Dispatch | `model/StateBus` | Listener-dispatch + batch-suppression protocol; CropState delegates here. `bus.beginBatch / endBatch` lets the Activity wrap recomputeCrop + UI updates so inner setter calls coalesce into one listener invocation |
| Output Format | `model/Format` | Enum (`JPEG` / `PNG`) carrying MIME type + file extension. Gives compile-time exhaustiveness on the export-pipeline switch |
| Crop Math | `crop/CropEngine` | Computes crop from center + AR + lock + rotation; keeps cropX continuous mid-rotation, with parity-snap applied at drag-release in `CropEditorView.onPanRelease` |
| Rotated Clamp | `util/RotatedCropClamp` + `util/CropFitContext` | Clamp candidate crop centers against a rotated image's bounds. CropFitContext bundles the pre-computed geometry the clamp's search and corner-check share |
| Render Geometry | `model/CropRender` | Final class bundling (centerX, centerY, cropW, cropH, imgW, imgH, rotation) + derived `srcX()` / `srcY()`. The public `of(...)` factory in (W, H) order is the only construction path |
| Export Result | `crop/ExportResult` | Record bundling encoded bytes + structurally-derived `hdrAttached` flag, returned by `CropExporter.export`. Threaded through `ExportPipeline.encodePhase` to `reportSuccess` so the [HDR OK] / [HDR dropped] toast is driven by the composer's `hdrAttached` boolean (`GainMapComposer.composeFileToFile`'s return in the production path) — set true only when the gain map was successfully appended AND MPF offsets were patched to point at it. The structural flag is required because reference-inequality on the byte[] is unreliable (GainMapComposer returns an XMP-patched primary on the MPF-fail path, distinct from the input array) and a full-file substring scan for `hdrgm` false-positives on preserved trailers and Extended-XMP segments |
| Horizon | `util/HorizonDetector` | Auto-rotation: metadata pass first, fallback to painted-region Hough transform |
| Export | `crop/CropExporter` | Full pipeline: crop, rotate, compress, HDR, EXIF, SEFT |
| Editor | `view/CropEditorView` | Custom View: rendering + gestures + undo/redo |
| Gestures | `view/TouchGestureHandler` | Pinch zoom, tap, drag, long-press; emits onPanRelease for parity-snap on drag end |
| Grid | `view/GridRenderer` | Grid overlay with line positions matching `CropExporter.gridLinePixel`'s rounded relative-offsets (preview lines land at the same intra-crop positions the export bakes; absolute image-pixel coords inherit the crop origin's fractional state) |
| Rotation | `view/RotationRulerView` | Galaxy-style scrollable ruler with snap-to-detent and pinch-to-zoom scale |
| Color Picker | `view/ColorPickerDialog` | Tap-to-select grid + alpha + hex input. A typed hex value updates the alpha slider / preview / grid highlight, but the programmatic slider echo never rewrites the hex field mid-typing |
| Settings | `view/SettingsDialog` | Combined dialog: grid config (cols, rows, presets 2x2–8x8, color, width), pixel-grid toggle/color, selection/paint color, Build (build-time version) |
| Save Dialog (legacy / no-MES) | `view/SaveDialog` | Format (JPEG / PNG) + export-grid bake-in toggle. Filename / target directory are picked separately by the SAF `ACTION_CREATE_DOCUMENT` picker that follows. Used as the fallback when MES isn't granted. |
| Shared Folder Browser | `view/FolderBrowser` + `view/BrowserRecyclerView` + `view/BrowserFastScroller` | Common filesystem-browser helper used by BOTH `FolderPickerDialog` (save flow) and `OpenPickerDialog` (load flow). Provides the title block + clickable breadcrumb + sort toggle + grid/list view-mode toggle (3-column grid vs single-column list; folder rows span the full width in both modes) + the scrollable content list with a draggable fast-scroll thumb. A file tap selects-and-dismisses in the Open flow and populates the filename input in the Save flow — the only behavioral difference between the two flows. **Navigation contract**: confined to primary external storage — every navigation entry point (folder-row tap, breadcrumb tap, constructor start folder) passes `isInsideRoot`'s canonicalized containment check, so symlinked paths resolve and out-of-tree targets are refused. The breadcrumb walks root ("Internal storage") to the current folder; every ancestor segment is tappable and jumps to that level, the current segment is not tappable. Failure behavior degrades, never crashes: a breadcrumb whose current path can't be canonicalized collapses to the root segment, and an unreadable / deleted folder enumerates as empty. **Enumeration contract**: folder enumeration and thumbnail decode never run on the UI thread; the visible list clears immediately on navigation, and a user who taps folder A then folder B before A's enumeration finishes only ever sees B's content. Scales to 50k-photo Camera folders without OOM (only the visible window of cells is materialised) and stays responsive at that size for scrolling, sort toggling, and grid/list toggling. **Hidden-file rule**: dot-prefixed entries — files and folders alike (`isHiddenName`) — never appear in any picker, so MediaStore trash (`.trashed-*`), pending captures (`.pending-*`), and the save pipeline's crash-safe `.cropcenter-tmp-*` temps can't be opened, grafted, or overwritten from the browser (the MANAGE_EXTERNAL_STORAGE `listFiles` walk sees them all). **Tappability**: files visible to the picker (`isImageFile`) but outside the load pipeline's supported set (`isSupportedSourceFormat` — HEIC / WebP / HEIF) render at 0.4 alpha with no click handler, so the user sees the file exists but can't tap-select something the loader would reject; the graft picker tightens the tappable set to JPEG (`isJpegSourceFormat`), greying PNGs the same way; Open and Save keep the broader set. **Sort contract**: the title-row sort toggle flips between `SortMode.NEWEST_FIRST` (lowercase-filename descending — chronological for date-prefixed camera filenames) and `OLDEST_FIRST` (ascending); sort applies to file rows only — folders stay alphabetical in every mode, matching file-manager convention; default is NEWEST_FIRST (camera / gallery convention). Persistence: sort direction under `KEY_SORT_MODE` (stored as the enum name; an unparseable stored value falls back to the default), grid/list mode under `KEY_GRID_MODE`, both in the `cropcenter_picker_view` SharedPreferences file. **Spinner contract**: the enumeration spinner appears only after a 200ms delay and is cancelled / hidden the moment items land — cached and instant loads never flash it; only genuinely slow first enumerations surface it. **Fast-scroller guarantees**: whenever content overflows the viewport, an always-visible draggable thumb is shown with a 32dp minimum height so it stays grabbable on 50k+ folders; while a thumb is visible, every touch in the scroller strip is consumed — a tap without a drag stays a tap (never scrolls), an off-thumb touch becomes a drag once it crosses the system touch slop — and the strip is excluded from the system back-swipe gesture zone so edge touches reach the thumb; with no thumb (folder fits the viewport), cells underneath stay tappable. Dragging to the track bottom lands the true last row bottom-flush; releasing a drag never scrolls past the position the user released at; drag response stays instant regardless of folder size. **Thumbnail-cache lifecycle contract**: thumbnails are cached process-wide only while a picker is open; a replaced file (Gallery edit, sync overwrite) re-decodes instead of serving the stale thumbnail, and a stale decode never paints into a recycled or navigated-away cell. The cache is released on every picker dismiss AND at export dispatch (`FolderBrowser.evictThumbnailCache`, which `ExportPipeline` calls before enqueueing an export), so cached bitmaps are never pinned through the export pipeline's GB-class HDR allocations — re-open decode latency is the accepted cost. Evictions are terminal against in-flight decodes: a decode that straddles the eviction still delivers its bitmap to the cell but never re-grows the evicted cache. **Sizing**: the shared `CARD_RESERVED_DP` (220dp) screen-height reserve sizes the browser card for ALL pickers (Load / Graft / Save) so the three dialogs open at the same maximum size. |
| Merged Save Dialog (MES path) | `view/FolderPickerDialog` | Format + Export Grid + folder navigator + thumbnail grid/list (toggle persists) + editable `Save as` filename field. Browser UI (title block / breadcrumb / content list / thumbnail decode) is shared through `FolderBrowser`; filename validation lives in the static helpers `isValidFilename` / `normaliseExtension`. `isValidFilename` rejects empty / traversal / path-separator names, names in the reserved crash-safe temp namespace (`SaveTempFiles.isReservedName` — any name containing the `.cropcenter-tmp-` marker substring), and names over `MAX_FILENAME_UTF8_BYTES` (210 UTF-8 bytes: the 255-byte filesystem component cap minus the up-to-36-byte hidden temp prefix `SaveTempFiles.tempName` prepends to every validated name, minus extension-swap headroom — a longer validated name would hard-fail placeholder / write-temp creation with ENAMETOOLONG after validation passed). Browses the filesystem via `java.io.File`, returns a `SaveChoices` record (folder, filename, format, bakeGrid), writes through externalstorage SAF document URIs that the existing Replace flow resolves back to the same `File`. **Tap-to-populate-filename**: file rows / grid cells in the browser are tap-to-set-filename: a tap on an existing photo fills the Save As field with that file's WHOLE name and parks the IME caret at the stem-end (`tapCaretIndex`), so the user can fast-overwrite an existing file (or fast-edit the stem before confirming). The field's `InputFilter` shares the validator's `MAX_FILENAME_UTF8_BYTES` cap (`editExceedsByteCap` / `filterByteCapEdit`): an edit that would push the field over 210 UTF-8 bytes is rejected whole as a true no-op — a rejected replace-selection edit can neither truncate into a silently-different valid name nor delete the selection — so tapping an over-cap name leaves the field unchanged and surfaces the "Name is too long to reuse" toast (`DialogStrings.NAME_TOO_LONG_TO_REUSE`) instead of populating a name that targets a different file than the one tapped. The dialog stays open; commit still routes through the Save here button. Distinct from `OpenPickerDialog`'s tap-to-dismiss semantics — save commits a typed name, not a tapped file. |
| Open Dialog (in-app) | `view/OpenPickerDialog` | Filesystem picker for the Open flow — replaces the system SAF `ACTION_OPEN_DOCUMENT` picker that doesn't persist sort preferences on Samsung One UI. Tap an image cell / list row to select; the picked `File` rounds through `SafFileHelper.buildExternalStorageDocumentUri` and feeds `ImageLoadController.load` so the existing load pipeline applies unchanged. Browser UI is shared through `FolderBrowser`; the dialog contributes Cancel + tap-to-dismiss selection + a `jpegOnly` constructor flag passed through to `FolderBrowser` — the Apply-Edit (graft) flow reuses this same dialog with jpegOnly=true so only JPEGs are tappable (PNGs render greyed-out, matching the HEIC / WebP / HEIF treatment), while the Open flow passes false. Shares the grid/list toggle preference (`cropcenter_picker_view` / `grid_mode`) with FolderPickerDialog through the common helper. |
| Save Flow | `SaveController` + `ReplaceStrategy` + `ExportPipeline` | Dual-path routing (merged-in-app vs SAF) gated on MES, collision detection (auto-rename + sibling-create, in-app Replace/Rename/Cancel), crash-safe write-then-swap |
| Load Flow | `ImageLoadController` | Bg-thread decode + EXIF orientation + metadata extract for SAF URIs (`load(Uri)`), Share/View intents (`handleIncomingIntent`), and in-memory graft bytes (`applyBytes(byte[], String)`). Owns the busy-release-in-finally + progress-overlay-hide contract. Exposes `getLastLoadedUri()` so `MainActivity.onSaveInstanceState` can persist the source URI for process-death restore (promoted on the bg thread only after `applyBytes` returns true and the UI install runnable is posted — a busy-rejected or decode-failed load leaves the prior session's URI in place so the restore-bundle URI and in-memory CropState stay paired) |
| Apply-Edit Flow | `GraftController` + `graft/EditAligner` + `metadata/GraftWriter` | btnGraft tap → in-app `OpenPickerDialog` (same dialog as Open) → validation (display-dim match) → optional re-orient → AI-region detect → byte splice → in-memory apply. Owns its own state machine (`graftPending`, `pendingSource` snapshot) |
| Toolbar / Crop config chips | `ToolbarBinder` | AR chip + PopupMenu wiring, Grid / Pin toggle chips, custom-AR dialog, mode/lock-axis row wiring, rotation-ruler zoom buttons; keeps MainActivity focused on lifecycle and host-interface implementations |
| Auto-Rotate Button | `AutoRotateBinder` | Wires the Auto button (left edge of the rotation-actions row, above the ruler row) to `HorizonDetector` (metadata pass + painted-region fallback) and posts the toast outcome |
| Editor Render Pipeline | `view/EditorRenderer` + `view/ViewportMath` + `view/GridRenderer` + `view/HorizonPaintOverlay` + `view/SelectionHistory` + `view/DialogCards` + `view/DialogStrings` | onDraw delegate (rendering only, no state mutation), screen↔image transform helper, grid render, horizon-paint overlay, undo/redo storage (50-step), shared dialog-card styling, shared dialog button labels (Apply / Cancel / OK) |
| Host interfaces | `EditorHost` / `ImageLoadHost` / `SaveHost` / `UiHost` / `ToolbarHost` + `UiSync` | Capability-typed views the controllers and binders see of MainActivity. `UiSync` collects the per-state-change UI refresh methods (toolbar / progress / dialog reactions) so MainActivity owns the wiring but the response code lives in one cohesive collaborator. |

### Metadata Pipeline

| Class | Purpose |
|-------|---------|
| `metadata/JpegMetadataExtractor` | Extract all APP/COM segments from JPEG header |
| `metadata/JpegMetadataInjector` | Replace re-encoder's APP markers with originals. Two entry points: `inject(byte[], segments)` (in-memory; allocates one sized output byte[]) and `injectFileToFile(inFile, segments, outFile)` (fully streaming — reads bounded head from disk to locate scanStart, then chunk-copies the image-data tail). The file-to-file variant keeps peak Java heap at ~2 MB independent of input size and is the path `CropExporter.exportJpeg` takes for 200 MP saves so the re-encoded primary never materialises as a single byte[]. Also hosts the shared streaming primitives `STREAM_CHUNK_SIZE` (64 KB chunk buffer), `copyRemaining(InputStream, OutputStream)` (the canonical chunked copy loop, returning bytes copied), and `skipExactly(FileInputStream, long)` (skip-then-read-fallback for the FUSE skip()-returns-0 corner case) — consumed by `GainMapComposer.composeFileToFile`, `CropExporter.appendSeftFileToFile`, `CropExporter.injectPngExifFromTiffFileToFile`, and `ExportPipeline.writePayloadToStream` |
| `metadata/ExifPatcher` | Update orientation, dimensions, and IFD1 thumbnail in EXIF. See §10 EXIF for the four-state thumbnail contract on `patch(...)`, the splice / append / strip fallback chain, IFD0 sanitisation, the `hasIfd1Thumbnail` predicate, and the two budget-prediction methods (`patchedNonThumbBytes` for byte-exact JPEG export; `maxThumbnailBytes` for the PNG eXIf splice-vs-strip decision). |
| `metadata/GainMapExtractor` | Extract HDR gain map from between primary EOI and SEFT |
| `metadata/GainMapComposer` | Append gain map + trigger MPF patch. Two entry points: `compose(byte[], gainMap)` (in-memory) and `composeFileToFile(inFile, gainMap, outFile)` (streaming — loads up to 16 MB head for XMP+MPF patching since APP segments cluster before SOS, then chunk-copies the tail and appends the gain map). The file-to-file variant is what `CropExporter.exportJpeg` calls so the metadata-injected primary stays on disk through HDR composition |
| `metadata/MpfPatcher` | Fix MPF APP2 offsets after primary size changes. Two entry points: `patch(jpeg, primarySize)` derives `gainMapSize = jpeg.length - primarySize` for the in-memory `GainMapComposer.compose` path (where `jpeg` is the full `[primary][gainMap]` concatenation); `patch(jpeg, primarySize, gainMapSize)` takes the gain-map size explicitly for the streaming `composeFileToFile` path where `jpeg` is only the primary's APP-marker head (a buffer-length-derived size would go negative when head.length < primarySize and write a garbage u32 into the MPF size slot) |
| `metadata/SeftExtractor` | Extract existing SEFT trailer (re-appended verbatim by CropExporter) |
| `metadata/JpegSegment` | Data class for a single JPEG marker segment. Carries the canonical XMP namespace identifier (`XMP_HEADER`) consumed by `isXmp()`, `HorizonDetector.detectFromMetadata`, and `XmpItemLengthPatcher` |
| `metadata/JpegMarker` | Constants for the JPEG marker bytes (`SOI` / `EOI` / `SOS` / `RST_FIRST..RST_LAST` / `STUFFING` / `TEM`) used directly by `JpegMarkerWalker`, `JpegMetadataExtractor`, `MpfPatcher`, `GraftWriter`, `XmpItemLengthPatcher`, and indirectly (via the walker) by `CropExporter` and `GainMapExtractor` |
| `metadata/JpegMarkerWalker` | Canonical JPEG marker-walking helpers. `findPrimaryEoi(file, endBound)` is the single chokepoint for the SOS / EOI / RST / segment-length / overflow-guard walk consumed by `CropExporter`, `GraftWriter`, and `GainMapExtractor` — hardened against `segLen < 2`, wrap-overflow, and truncated SOS headers. `nextHeadSegment(file, off, endBound)` is the per-segment head-walk cursor — fill-byte skipping via `skipFillBytes` (legal `FF FF MARKER` sequences per ITU-T T.81 §B.1.1.2), standalone-marker detection, segment-length validation, and overrun classification — consumed by `MpfPatcher`, `JpegMetadataExtractor`, `JpegMetadataInjector`, `BitmapUtils.readExifOrientationInternal`, `GraftWriter`, and `XmpItemLengthPatcher`'s segment walker; each caller keeps only its own segment filter, stop condition, and failure convention, and a `FF FF E1 ...` fill-byte shape can't break any head walker on the second 0xFF |
| `metadata/PngMetadataExtractor` | Walk PNG chunks (8-byte signature + length/type/data/CRC chunks) for the eXIf chunk per PNG 1.6 spec. The shared chunk walk stops at IEND (post-IEND trailer bytes are never parsed as chunks) and CRC32-validates the selected eXIf chunk over type + data — a failed CRC drops metadata only, pixels unaffected. Three entry points: `extract()` returns a synthetic APP1 EXIF segment (capped at the JPEG APP1 u16 limit so JPEG injection stays well-formed), `extractRawTiff()` returns the raw TIFF bytes uncapped (used by PNG → PNG round-trip where the eXIf chunk's u31 length field has no JPEG-side cap), `extractOrientation()` parses the TIFF Orientation tag (0x0112) so PNG sources rotate pixels at load time matching JPEG behavior. Hardened against malformed TIFF (rejects byte order ≠ II/MM, magic ≠ 42, IFD entries with type ≠ SHORT or count ≠ 1, and orientation values outside 1..8) |
| `metadata/TiffTag` | Single-source-of-truth constants for EXIF / TIFF tag IDs (`ORIENTATION`, `IMAGE_WIDTH`, `IMAGE_LENGTH`, `PIXEL_X_DIMENSION`, `PIXEL_Y_DIMENSION`, `EXIF_SUB_IFD`, `JPEG_INTERCHANGE_FORMAT[_LENGTH]`, `MP_ENTRY`) and entry-type codes (`TYPE_SHORT`, `TYPE_LONG`). Consumed by `ExifPatcher`, `TiffIfd0`, and `MpfPatcher` (with `BitmapUtils`, `PngMetadataExtractor`, and `CropExporter` reaching the Orientation constants through `TiffIfd0`) so a future spec change (new tag, type widening) lands in one file rather than a multi-site bare-literal hunt |
| `metadata/TiffIfd0` | Canonical TIFF IFD0 Orientation-entry walker: `findOrientationEntry(data, tiffStart, tiffEnd, minIfdRel)` validates the header (byte order = II/MM, magic = 42), bounds-checks the IFD0 offset in long arithmetic, and returns the first Orientation entry only when shaped SHORT / count-1; `readOrientation` maps values outside 1..8 to upright. Shared by `BitmapUtils.readExifOrientationInternal` (JPEG APP1), `PngMetadataExtractor.extractOrientationInternal` (PNG eXIf, bounded to the chunk end), and `CropExporter.forceTiffOrientationToUpright` (raw-TIFF rewrite, `minIfdRel=8`) so the orientation-walk hardening lives in one place |
| `metadata/GraftWriter` | In-memory byte splice for "Apply External Edit" — assembles the grafted JPEG per `SWAP_*` constants (original metadata + edit primary scan + original gain map + original SEFT) |
| `metadata/HdrSignature` | XMP "hdrgm" namespace marker scanner. Two entry points: `hasHdrgmInXmp(List<JpegSegment>)` walks ONLY parsed XMP APP1 bodies (standard + extended) for the load + graft HDR-source gate, then falls back to the per-GUID reassembled Extended XMP buffers to catch markers straddling chunk boundaries within one GUID group — never a marker synthesized across two groups' boundary bytes (a stray "hdrgm" outside XMP doesn't false-positive); `isHdrSource(byte[])` is a full-file scanner reserved for `UltraHdrCompat`'s post-`Bitmap.compress` diagnostic where the freshly-emitted JPEG is well-formed. Pure Java, no Android deps. |
| `metadata/ExtendedXmpReassembler` | Reassemble Adobe Extended XMP chunks (`http://ns.adobe.com/xmp/extension/`) by 32-byte GUID + 4-byte unsigned offset into one concatenated byte buffer PER GUID group (buffers in GUID string order; within a group, gaps / overlaps between chunk offsets are tolerated — no structural rejection). Consumers scan each group's buffer independently: a GUID group is one XMP document, and a cross-group concatenation would let a detection string (hdrgm, Roll / Tilt, Item:Length) synthesize across the boundary between two unrelated documents. Used by `HorizonDetector.detectFromMetadata` (Roll / Tilt scanning across chunk boundaries), `HdrSignature.hasHdrgmInXmp` (hdrgm marker), and `XmpItemLengthPatcher` (Item:Length straddle detection) so the reassembly logic is one chokepoint. Pure Java, no Android deps. |
| `metadata/XmpItemLengthPatcher` | Rewrites the GContainer `Item:Length` attribute in the primary's XMP packet to match the actual gain-map byte size after re-encode. The Ultra HDR pipeline preserves source XMP byte-identically, so the source's pre-edit gain-map length attribute goes stale — strict GContainer decoders (Google's libUltraHdr) slice the gain map by `Item:Length` and would decode a truncated stream, silently dropping HDR boost. Every `Item:Length` scan (patch, per-chunk Extended XMP, reassembled-bytes) tolerates spec-legal XML whitespace around the attribute's `=`; a packet carrying several `Item:Length` attributes (Google MotionPhoto XMP declares one per Container:Item, the Primary item's `"0"` ahead of the gain map's) is patched only at the occurrence whose enclosing element carries `Item:Semantic="GainMap"` (bounded backward same-element scan). Patches in-place when the attribute lives in the standard XMP packet; **fail-closes** (returns empty) when (a) the attribute lives in an Extended XMP chunk, (b) the per-chunk Extended XMP scan misses but the reassembled-bytes scan hits a straddling occurrence, (c) the attribute lives in standard XMP but the segment is unpatchable — patched segLen would exceed the APP1 u16 cap, the value is non-quoted, the digit run is empty / unterminated, or the closing quote doesn't match — OR (d) a multi-occurrence packet has zero or more than one GainMap-anchored `Item:Length` (one post-edit gain-map size cannot be assigned across many slots, and a first-match guess would rewrite the wrong item's length). Walks ALL standard XMP APP1 segments (legacy non-Adobe splitters can emit two). All four APP1-walking sites (`patch`, `collectApp1Segments`, `extendedXmpContainsItemLength`, `findAllXmpApp1Segments`) route through one private `walkApp1Ranges` helper built on the `JpegMarkerWalker.nextHeadSegment` cursor (fill-byte handling + length validation) — the single-chokepoint shape keeps every APP1 walk on one fill-byte and length discipline, the drift class the audit's canonical-helpers rule exists to prevent. `GainMapComposer.compose` checks for the empty return and drops HDR rather than ship stale `Item:Length`. Uses a private tagged record `SegmentPatchResult` (factories `failClosed()` / `notPresent()` / `patched(byte[])`) to distinguish "attribute not in this segment" (caller scans the next segment / falls through to Extended XMP) from "attribute here but unpatchable" (caller fails closed). Pure Java, no Android deps. |

### Utilities

| Class | Purpose |
|-------|---------|
| `util/BitmapUtils` | EXIF orientation reading (`readExifOrientation`), `orientationMatrix()` (shared with `UltraHdrCompat.applyExifOrientation`), `applyOrientation()` (consumed by `ImageLoadController` / `EditAligner`; UltraHdrCompat has its own `applyExifOrientation` that ALSO rotates the embedded gainmap pixel buffer to keep primary and gainmap coherent), `drawCropped()` (shared crop+rotate render between `CropExporter` and `UltraHdrCompat.renderPrimary` — both sites pair it with identical paint flags and the `ThemeColors.BACKGROUND` fill so primary-pixel output is identical), `isLosslessCardinalRotation()` (the parity-aware nearest-neighbor gate shared by `drawCropped` and the gain-map render: 0°/180° are lossless integer remaps at any dims, 90°/270° only when the drawn bitmap's width + height is even — an odd sum puts the center-rotated grid on half-pixel offsets, so those inputs fall back to bilinear), and the `ROTATION_EPSILON` constant (0.005°, the canonical sub-epsilon threshold consumed by the render pipeline / ruler / bypass-encode gate / horizon detector) |
| `util/ByteBufferUtils` | Endian-aware read/write with bounds checking |
| `util/DpToPx` | Density-independent-pixel → pixel conversion using `Math.round` (not `(int)` truncation, which collapses 1dp values to zero on density-0.75 screens). Required by every dialog/binder; centralised here so the rounding contract can't drift |
| `util/HorizonDetector` | XMP-roll-tag parse first, then Canny edges + two-pass Hough transform (coarse 80–100° at 0.1° / fine ±2° at 0.01°) over the user-painted horizon region; used by the auto-rotate fallback |
| `util/AiRegionDetector` | Identifies the AI-edited region in a graft by diffing source vs aligned-edit at sampleSize=4. Output `AiMask` record drives the gain-map inpaint at save time |
| `util/GainMapInpainter` | Frontier-tracked grow-from-boundary inpaint that fills the AI-masked region of the source's gain-map Bitmap with the average of unmasked 8-neighbors. Mutates in place from `Gainmap.getGainmapContents()` so the single-channel container survives the save's `Bitmap.compress(JPEG)` call |
| `util/GridGeometry` | Mirror-symmetric grid-line pixel positions (`mirroredLinePos`) shared by `view/GridRenderer` (preview) and `crop/CropExporter` (export) so the saved rule-of-thirds grid aligns byte-for-byte with the in-editor preview |
| `util/RotationMath` | `rotate(x, y, pivotX, pivotY, deg, out)` / `inverse(x, y, pivotX, pivotY, deg, out)` helpers — single source of truth for rotation math. Both write the rotated point into the caller-allocated length-2 `out` array (no allocation per call) and return `out` for chaining; sub-`ROTATION_EPSILON` rotations short-circuit to identity |
| `util/SafFileHelper` | SAF/MediaStore URI helpers: copy (`transferTo`-based), derive sibling (handles both nested `primary:Pictures/foo.jpg` and root-level `primary:foo.jpg` document IDs), file-from-URI, query size, content-readback verify, create-sibling-placeholder, full bytes read via `readUriBytes` (routes direct-file → SAF-stream with per-call temp cache) |
| `util/SafPaths` | Pure-string helpers extracted from SafFileHelper: `parentDocIdOf`, `lastSegmentSeparatorEnd` (both classify the opaque docId schemes `msf:` / `image:` / `document:` / `qb:` / bare-numeric as no-parent — empty / -1 — so sibling-URI derivation never fabricates document IDs on opaque providers and collision handling routes to the opaque-provider fallback), `hasImageSignature`, `hasParentTraversalSegment` (segment-aware `..` check, replacing the substring `String.contains("..")` that rejected legit "IMG..edited.jpg" filenames). Static, no Context — testable directly without an Android runtime |
| `util/StoragePermissionHelper` | MANAGE_EXTERNAL_STORAGE detection + settings deep-link |
| `util/TextFormat` | Locale-safe number formatting for the info bar |
| `util/ThemeColors` | Catppuccin Mocha int constants for code paths without a Context |
| `util/UltraHdrCompat` | Android 14+ Gainmap API: canvas-based HDR export |

---

## Features

### 1. Image Loading

**Supported formats**: JPEG, PNG (detected by magic bytes)

**Loading flow** (`ImageLoadController.load` and `applyBytes`):
1. Read URI bytes via `SafFileHelper.readUriBytes` (which routes to `tryReadDirectlyFromPath` for path-resolvable
   filesystem-grant providers to bypass Samsung's MediaStore EXIF mangling, with a SAF stream copy fallback for cloud /
   SAF-only sources). Reject non-JPEG / non-PNG sources up front via the magic-byte gate (`isJpegSignature` /
   `isPngSignature`) so HEIC / WebP / GIF can't slip past and silently re-encode through the PNG default later.
   Rejection surfaces the toast "Unsupported image format — only JPEG and PNG are supported".
2. Decode to Bitmap via `BitmapFactory.decodeByteArray`. Recycle on the rare zero-area early-return.
3. Read EXIF orientation from the right parser for the format: `BitmapUtils.readExifOrientation` walks JPEG markers;
   `PngMetadataExtractor.extractOrientation` walks PNG chunks. Both return 1 (upright) on absence / malformed input, so
   the same `BitmapUtils.applyOrientation` rotation pass follows for both formats.
4. Extract metadata via `extractMetadata`:
   - JPEG: `JpegMetadataExtractor.extract` for APP/COM segments → `state.jpegMeta`; `HdrSignature.hasHdrgmInXmp(meta)`
     walks ONLY the parsed XMP APP1 segments looking for the hdrgm namespace marker (a stray "hdrgm" 5-byte sequence in
     MakerNote / COM / vendor blob / SEFT history / entropy doesn't false-positive), gated by an `hasMpf` pre-filter
     (cheap segment scan), and the resulting `isHdrSource` boolean drives `GainMapExtractor.extract` (no marker →
     returns empty without inspecting post-primary FF D8 bytes, so an SDR Samsung file whose SEFT data block begins with
     an embedded JPEG thumbnail's FF D8 isn't mis-extracted as a gain map); `SeftExtractor.extract` then receives the
     gain map's presence as a `hasGainMap` hint so its trailer-start walk only steps past a gain-map EOI when one was
     actually present. `GraftWriter.graft` uses the same `hasMpf && hasHdrgmInXmp` AND-gate per-side so a graft of an
     SDR original doesn't synthesise a phantom gain map either.
   - PNG: dual storage — `PngMetadataExtractor.extract` builds a synthetic APP1 EXIF segment for `state.jpegMeta`
     (capped at the JPEG APP1 u16 limit so JPEG-injection stays well-formed), and `extractRawTiff` returns the uncapped
     raw TIFF bytes for `state.pngExifTiff` (used by PNG → PNG export so > 64KB EXIF round-trips fully). The third
     PngMetadataExtractor entry point — `extractOrientation` — was already consumed in step 3 by the orientation read;
     this metadata-extract step covers only the segment and raw-TIFF parses.
5. Compose the human-readable format string for the info bar — `EXIF+ICC+XMP+HDR+Samsung` for a JPEG with all five
   flags. Every flag is conditional (`hasExif`, `hasIcc`, `hasXmp`, `gainMap != null`, `state.getSeftTrailer() !=
   null`); only present flags are appended, joined by `+`, in the listed order. A JPEG missing any leading flag drops
   straight to the next-present one (e.g. an EXIF-less JPEG reads `ICC+XMP+HDR` or `XMP+HDR` depending on what's
   actually present). `PNG+EXIF` for a PNG with eXIf; plain `PNG` otherwise; empty string for a JPEG with no metadata at
   all.
6. Hand off to `installImageOnUi` on the UI thread so the View hierarchy reads the freshly-populated state atomically.
   Bg-thread mutations to the state become visible to the queued runnable via the Handler.post happens-before;
   concurrent UI-thread reads (e.g., a stray onDraw) see either pre-reset state (still consistent) or unwritten state
   (the renderer's null-source-image guard handles it). The UI runnable also resets non-state-backed UI affordances that
   don't auto-sync from CropState — the Pin toolbar chip and the per-mode lock-axis prefs, and any active horizon paint
   mode (discards the in-progress stroke and reverts the Auto button label / color so the new image's first touch routes
   to Select / Move instead of paint).

**UI-commit failure resyncs to the no-image state (UI honesty)**: `installImageOnUi` wraps its UI-side commit in
try/catch. On a throw it resets CropState, recycles the orphaned bitmaps, clears the info-bar text (`txtImageInfo` /
`txtImageFormats`), and re-runs the shared state→UI fan-out (`MainActivity.syncAllUiFromState` — the same fan-out the
state listener uses) so EVERY image-gated control — AR / Grid / Pin / mode / lock chips, point buttons, Auto / Reset
rotation chips, rotation ruler + degree readout, zoom badge, and the editor canvas — returns to the no-image state
instead of continuing to claim a loaded image, then surfaces a "Load failed: couldn't display the image" toast. The
bg-side finally still posts `setBusyUi(false)` afterward, which re-enables Open and leaves Save / Graft disabled (no
image). A failed install therefore fully resyncs the UI: no control may keep reporting success for a load that never
committed.

`CropState.installLoadedImage` seeds the export config to match the loaded format so the SaveDialog's format toggle and
default filename arrive on the same format the user opened. Without this, loading a PNG would leave Save defaulting to
JPEG (silent format conversion + alpha loss). The user can still flip the toggle in the SaveDialog before any individual
save.

**Input methods**:
- Open button (tap): in-app `view/OpenPickerDialog` — browses primary external storage with a folder navigator +
  thumbnail grid/list (toggle persists, shares the `cropcenter_picker_view` / `grid_mode` SharedPreference with
  FolderPickerDialog). Tapping an image cell selects it; the picked File is converted to a SAF externalstorage URI via
  `SafFileHelper.buildExternalStorageDocumentUri` and routed through `ImageLoadController.load` (same path as Share /
  View intents). When the conversion returns empty (picked file outside primary external storage), the pick aborts with
  the toast "Couldn't resolve picked file to a load URI". The system `ACTION_OPEN_DOCUMENT` picker is deliberately
  not used: Samsung One UI's DocumentsUI doesn't persist user sort preferences across launches, forcing a "Sort by
  name" re-pick every session. Initial folder resolves via `SaveController.loadInitialPickerFolder` (last-save vs
  last-load folder, most recent wins; falls back to primary external storage). Trade-off: loses cross-app access to
  Drive / Dropbox / Gallery — files must be in a filesystem-visible folder. **Requires MANAGE_EXTERNAL_STORAGE**:
  without MES the picker can't enumerate primary external storage, so `showOpenPickerDialog` checks the grant first and
  surfaces a "grant All files access" toast instead of opening an empty / broken picker. There is no SAF fallback for
  Open — the system picker is excluded by user design.
- Graft button (tap): graft flow — opens the **same `view/OpenPickerDialog`** as the load flow. Picked `File` rounds
  through `SafFileHelper.buildExternalStorageDocumentUri` to a SAF externalstorage URI, which
  `GraftController.onEditPicked` then validates and splices. Graft shares the load flow's picker rather than a system
  `ACTION_OPEN_DOCUMENT` picker — cross-app sources (Drive / Gallery / Lightroom HDR exports) have to be copied into a
  filesystem-visible folder first, same trade-off as the Open flow. Only entry point for the graft flow — deliberately
  no btnOpen long-press shortcut, so users don't discover the same action two ways with different gestures.
- Share intent: `ACTION_SEND` with `image/*`
- View intent: `ACTION_VIEW` with `image/*`

**Touch-blocking progress overlay**: every state-mutating background job (load, graft, save, horizon detect) raises a
full-screen modal overlay (`progressOverlay` in the layout, `clickable=true focusable=true`) for the duration of the bg
work. Without it the editor and toolbar above would still accept taps / drags / AR changes / rotation while CropState is
being reset and re-populated underneath, leaking inputs onto an in-flight-replaced state. `setBusyUi(true)` only
disables Save / Open / Graft; the overlay is what gates everything else. The overlay is hidden in `finally` blocks of
every busy-release path so a thrown bg task never strands the user behind a permanently-modal overlay. The release
ordering invariant: busy clears LAST, after the UI teardown (re-enable controls, hide overlay). Background-thread tails
MUST route through `EditorHost.finishBusy`, which posts the teardown then clears busy inside one UI-thread runnable, so
a Share/View `onNewIntent` can't acquire busy for a new op in a bg-clear-then-post gap and then be unmasked by the prior
op's pending teardown. Paths already on the UI thread release inline, since no cross-thread gap exists:
`AutoRotateBinder`'s detection-result tail inlines the same teardown-then-clear sequence, while the pre-enqueue rollback
branches (`ImageLoadController.load`, `AutoRotateBinder`, `GraftController`, `ExportPipeline`) clear busy first and then
tear down the UI — every busy acquirer runs on the UI thread, so nothing can interleave inside one UI-thread call stack
and the clear-LAST ordering is load-bearing only across a bg→UI hop.

**Pre-enqueue cleanup contract**: every busy-acquiring entry point (`ImageLoadController.load`,
`GraftController.onEditPicked`, `ExportPipeline.exportTo`, `AutoRotateBinder.onHorizonPaintComplete`) wraps the busy
claim + `setBusyUi(true)` + `showProgress(...)` + `runInBackground(...)` calls in a `try/catch (RuntimeException)` that
releases busy, clears the UI flag, hides the overlay, and rethrows. Without this guard, a `RejectedExecutionException`
from the executor (post-`onDestroy` racing a tap) or a view-tree throw from the UI mutators would strand `busy=true`
forever — the bg `finally` only fires if the Runnable was actually accepted. The four sites use the same shape so a
future audit can pattern-match.

**Thread-contract annotations**: the UI-thread / bg-thread contracts described in this section are not prose-only —
the seams (the `EditorHost` / `SaveHost` / `ImageLoadHost` host methods, the `*Bg` / `*OnBg` bodies and the
bg pipeline roots they call, `UiSync`, and FolderBrowser's UI/bg split) carry androidx `@UiThread` / `@WorkerThread` /
`@AnyThread` annotations, and Android lint's `WrongThread` check (`./gradlew.bat :app:lintDebug`) machine-checks the
`@UiThread`-vs-`@WorkerThread` call paths (`@AnyThread` documents the marshal-internally helpers).

**Transient-dialog forced dismissal**: `ImageLoadController.load` and `MainActivity.applyGraftedBytes` call
`host.dismissTransientDialogs()` on the UI thread BEFORE the busy claim and bg dispatch. Without it, an open dialog at
the moment a Share/View intent fires would race the bg `state.reset()` with the user's still-active in-dialog commits,
or (after the load completes) silently apply image A's typed values to image B's freshly-reset state. Dismissal narrows
but cannot close that race — a pan / drag / ruler-fling callback already dispatched to the UI thread can still land
after the bg `state.reset()`. `CropState`'s load-epoch guard (see Key Components) is the backstop: such late commits are
abandoned instead of written over the fresh state.

Tracked dialogs (every state-mutating dialog or any dialog whose abandonment must release a lifecycle flag like
`savePending`):
- `SettingsDialog` — gridConfig color / width / presets
- `SaveDialog` — exportConfig format + gridConfig includeInExport (legacy fallback when MES is not granted)
- `FolderPickerDialog` — the merged in-app save dialog when MES is granted; commits format + grid + folder on Save here.
  Uses `setActiveTransientDialog` (not `registerTransientDialog`) because it installs its own composite
  `OnDismissListener` that owns thumbnail-executor shutdown + savePending release; the host's `clearTransientDialog`
  callback is composed into that listener.
- `OpenPickerDialog` — the in-app Open picker (replaces system SAF `ACTION_OPEN_DOCUMENT`). Same
  `setActiveTransientDialog` + composite OnDismissListener pattern as FolderPickerDialog because of the
  thumbnail-executor shutdown. Selection on file tap dispatches through `ImageLoadController.load`, which dismisses any
  tracked transient dialog (including this one if it were somehow still showing) via `host.dismissTransientDialogs()`
  before its bg dispatch.
- `SaveController.showInAppCollisionDialog` — Replace / Rename / Cancel for the in-app save flow
- `SaveController.showInAppRenameDialog` — filename input with auto-numbered "(N)" suggestion
- `SaveController.showOverwriteConfirmDialog` — Cases A/C overwrite confirmation for the SAF flow
- `SaveController.showReplaceDialog` — Case B Replace / Keep / Cancel for SAF auto-rename collisions
- `ToolbarBinder.showCustomArDialog` — aspectRatio + customArLabel / customArActive UI flags
- `GraftController` sanity-check dialog — large-edit warning before applying a graft
- `MainActivity.showAllFilesAccessPrompt` — first-launch MES grant prompt

Dialog producers register through `host.registerTransientDialog(dialog)`, which installs an `OnDismissListener` that
clears the tracked reference on normal dismissal; producers that build their own AlertDialog route the whole show
through the shared `EditorHost.showTransientGuarded` ceremony (destroyed-Activity skip, build → register → show,
BadTokenException-tolerant failure mapping to per-site abort runnables). `dismissTransientDialogs` calls
`dialog.cancel()` (not `dialog.dismiss()`) so the dialog's `OnCancelListener` fires too — the Replace dialog's
placeholder cleanup and SaveDialog's `priorSnapshot` clear both live in `OnCancelListener` and would leak / stick on a
plain `dismiss()`.
`SettingsDialog`'s `OnCancelListener` AND `OnDismissListener` both cancel any open `ColorPickerDialog` it parented,
since the picker is a separate AlertDialog that mutates gridConfig through its own OK button. The `OnDismissListener` is
load-bearing: it fires on the "Done" button path AND on Activity-destroyed-mid-dialog config-change dismissal, which the
`OnCancelListener` doesn't see. Because `setOnDismissListener` replaces rather than chains, `SettingsDialog.show` takes
the host-tracking cleanup as a parameter and composes both cleanups into a single dismiss listener —
`registerTransientDialog(SettingsDialog.show(...))` would silently clobber the picker cleanup otherwise.

**`SaveDialog` `priorSnapshot` second clear path**: if `SaveDialog.show` itself throws (BadTokenException from a
config-change race between the `isDestroyed()` pre-check and the actual `.show()`, or any RuntimeException from the
transient-dialog registration), `SaveController.openSaveOptionsDialog` clears the snapshot in its catch block. Without
that, the listener never installs and the source-bitmap reference would stay pinned until the next save attempt or
activity teardown.

### 2. Editor Modes

#### Select Mode (Default)
- Tap to place selection points around a feature
- Tap on existing point to remove it
- Long-press to remove nearest point
- Auto-computes maximum crop at current AR centered on the selection points
- Points can't be placed outside rotated image content
- Clearing all points recenters the crop on the image midpoint at max size for the current AR — the full image only when
  the AR is Full / free
- Single selection snaps the tapped pixel's center: the grid's midline covers the marked pixel

#### Move Mode
- Drag to reposition the crop rectangle
- Respects lock direction: H moves X only, V moves Y only, Both moves freely
- Crop **size is preserved** in Move mode — the drag path calls `clearCropSizeDirty()` so `recomputeCrop` takes its
  size-locked early return and only the center is re-clamped against the rotated bounds; cropW/cropH never change
- During the drag the center stays continuous (sub-pixel) for smooth motion; the drag's fractional accumulator lives in
  a separate "anchor" state so high-zoom slow drags build up across events without losing motion to the rotation clamp
- On finger lift (`onPanRelease`), the center snaps to the parity that makes `cropImageX = centerX − cropW/2` integer
  (cropW even → centerX rounded; cropW odd → centerX floor + 0.5). This pixel-aligns the crop borders and grid without
  per-frame snap (which would cause flicker as cropW oscillates during rotation). An exact half-distance tie (the center
  equidistant from both candidate targets, e.g. the state a Select-mode tap places on the locked axis) is preserved
  unsnapped (`snapAxisPreservingTies`), so `cropImageX` may stay fractional after such a release
- Crop rectangle cannot be dragged outside image bounds (rotation-aware binary search inside `setCenter`)
- Cross-axis drift on a locked axis is bounded to 0.5 px per event and rejected above that threshold
- Tap does nothing (prevents accidental crop placement)

### 3. Lock Modes

The lock-axis row at the bottom of the editor has three buttons (Both / H / V). The toolbar **Pin** chip modulates that
row's behavior:

| Mode (lock-axis button) | Select Behavior | Move Behavior |
|------|----------------|---------------|
| Both | Symmetric on both axes around point midpoint | Drag moves both axes |
| H | Center horizontally on points, maximize vertically | Drag moves X only |
| V | Center vertically on points, maximize horizontally | Drag moves Y only |

**Toolbar `Pin` chip** (`btnPin`): tap to toggle. When selected (mauve), sets `CenterMode.LOCKED`, which makes drags
pan the viewport regardless of the lock-axis selection (effectively overriding the row above for the duration Pin is
on). Toggling Pin off restores the previously-selected lock-axis mode. The chip's selected-vs-not state IS the source of
truth for "is Pin on" — `MainActivity.isPanning()` reads `btnPin.isSelected()` directly.

**Select mode centering logic**:
- Locked axis: center = midpoint of selection points, crop extent = symmetric from center
- Free axis: center = midpoint of points (best-effort), crop extent = full image dimension; center shifts only if needed
  to keep the crop in bounds
- With rotation: a second pass of `maxScaleForRotation` shrinks the crop if the rotation-clamped center makes it too
  large; selection points are rotated through `rotatedSelectionMidpoint` so the rotated AABB midpoint (not the
  un-rotated one) drives the center under non-zero rotation

Per-mode lock preferences (Both/H/V) are remembered independently for Move and Select. Defaults are **V** in Move and
**Both** in Select. The "Both" button is only enabled in Select mode (visible-but-disabled in Move mode).

### 4. Aspect Ratio

**Popup rows in order** (widest landscape → square → tallest portrait): Full, 16:9, 3:2, 4:3, 5:4, 1:1,
4:5, 3:4, 2:3, 9:16, Custom. "Full" is the no-AR-constraint option (`AspectRatio.FREE` with width=height=0). The AR
control is a `MaterialButton.TextButton` chip (`btnAspectRatio`) whose text shows the current ratio — preset name for
matched presets ("4:5", "Full"), numeric "W:H" for custom ratios. `ToolbarBinder.setupArButton` wires the chip's tap
handler to open a `PopupMenu` built from `AR_LABELS`; the chip's text is sourced from
`ToolbarBinder.arLabel(state.getAspectRatio())` and updated via `UiSync.updateAspectRatioButton` on every state change.

**Orientation-aware default**: `CropState` constructs at `R4_5` (4:5) — chosen so a pre-image session and the
test-fake path land on a sensible value — but `MainActivity.installImageOnUi` re-seeds the AR from the loaded image's
display dimensions on every fresh load: landscape (`width > height`) defaults to **5:4** (`R5_4`), portrait (`height >
width`) defaults to **4:5** (`R4_5`), and a true square (`width == height`) defaults to **1:1** (`R1_1`) so the initial
crop doesn't trim a square source for no benefit. The orientation-aware seed only fires when no restore bundle was
consumed (`RestoreController.Outcome.consumed() == false`); a restored session keeps the user's pre-kill AR choice
(stashed in `STATE_AR_WIDTH` / `STATE_AR_HEIGHT`) so process-death restore doesn't silently overwrite a non-default
custom AR with the orientation default. After either branch, `ToolbarBinder.syncFromState` always fires so the AR chip
text reflects the committed model value (orientation default OR restored AR).

**Custom AR**: Dialog with width:height inputs when "Custom" is selected from the popup; constructs a fresh
`AspectRatio(w, h)` and assigns it. The width / height fields pre-fill in priority order: last-typed Custom values
persisted in SharedPreferences (so a Custom 2.39:1 survives a brief detour to a 1:1 preset), then the currently-applied
AR's values when no stored Custom exists, then 16:9 as final fallback. FREE falls back to a 16:9 starting point since
FREE has no meaningful (width, height) to seed from. After Apply, the AR chip text re-derives via
`ToolbarBinder.arLabel` — preset name when the typed ratio happens to match a preset (defensive; the user almost always
picks Custom for non-presets), numeric `W:H` (e.g. `5:7`) otherwise. Cancel is a pure no-op: with the popup layout
there's no spinner position to restore (the popup builds fresh on each tap).

**Auto-crop**: Changing AR auto-creates a crop at image center if none exists.

**Recompute-on-mode-switch**: entering Select mode — and lock-axis changes or a Pin-off toggle made while in
Select mode — runs `recomputeForLockChange`, which calls `autoComputeFromPoints` when selection points exist (re-frames
the crop on the points) or `recomputeCrop` against the current anchor when they don't (re-fits the crop size at the
current AR). Entering Move mode never recomputes — the crop's size and framing carry over unchanged. A Move-mode lock
change recenters without resizing (`recenterOnSelection`: crop moves to the rotated selection midpoint, size preserved)
when selection points exist, and does nothing when they don't. All recomputes are suppressed mid-pan. Combined with the
AR popup's onMenuItemClick handler — which auto-recomputes on every AR change — every meaningful "reset" the user might
want is reachable by mode switch, AR change, or the "Clear Points" button. No separate long-press / gesture-driven reset
affordance is exposed.

**Locked-AR exact-integer realisation** (`AspectRatio.snap`): when an integer-valued AR is locked, `CropEngine` snaps
the rounded crop dimensions to the nearest `(Wᵣ·k, Hᵣ·k)` realisation, where `(Wᵣ, Hᵣ)` is the AR reduced to lowest
terms via GCD and `k` is the integer minimising squared distance from the requested crop. This eliminates the
~½-pixel-per-axis drift that independent `Math.round` on each dimension would otherwise introduce — a 4:5 lock realises
as exact 0.80000 instead of a rounded 0.79989. Both `recomputeCrop` and `recheckRotationFit` pass the pre-snap rounded
dims as the snap's max-bound (no-grow), so the snap never grows past what fits at the user's locked center —
`setCenter`'s edge-clamp would otherwise silently drift the locked center inward. No-op for `FREE` and fractional ARs.

The gain map is **not** snapped — it stays at its natural rounded quarter-resolution dims. Two reasons:

- Snapping the gain map would shrink the sampled source region by up to `(Wᵣ-1, Hᵣ-1)` pixels, reintroducing the spatial
  HDR misalignment on high-contrast crop edges that the primary-side snap is designed to avoid.
- The AR drift between primary and gain map (worst case ~5e-04 at gainmap-side dims ~900-1000; observed peak 6e-04 on
  2988×3735 primaries) is imperceptible after the decoder's scale-to-fit. The drift comes from half-pixel rounding on
  the gainmap-side dim: when `primaryH / 4` is a half-integer (e.g., 3750/4 = 937.5), `Math.round` snaps to the nearest
  integer, producing up to a 0.5-gainmap-pixel error that translates to an AR delta of `0.5 / gainmapH`.

### 5. Rotation

**Galaxy-style scrollable ruler** (persistent, below point controls):
- Full range: -180.0 to +180.0 degrees, finest snap step 0.01° at maximum zoom
- Drag to scroll with momentum fling via OverScroller; pinch to zoom the ruler scale
- Tap to jump: a release with ≤ 8 px of total drag (`TAP_SLOP`) commits the angle under the tapped x-offset (converted
  via pixels-per-degree), snapped to the nearest detent / tick
- **Multi-touch pointer identity**: drag deltas track a single active pointer (the first finger down), read via
  `findPointerIndex` — never pointer index 0. When the active finger lifts mid-gesture (`ACTION_POINTER_UP`), the
  remaining finger is promoted to active and the drag baseline rebases to that finger's own x, so the promotion itself
  contributes a zero rotation delta — lifting one finger of a pinch never applies the inter-finger distance as an
  uncommanded rotation jump.
- **Interrupted-gesture cleanup**: when Android dispatches `ACTION_CANCEL` (system back, parent-view intercept,
  multi-touch disambiguation), the ruler recycles the velocity tracker and restores the pre-gesture angle — it never
  commits a fling or snap. Because drag deltas are published live during the gesture, restoring fires one corrective
  listener notify back to the pre-gesture value, so an interrupted gesture leaves rotation where it started. Distinct
  from `ACTION_UP`, which commits the fling / snap / tap as the user-completed release.
- Tick configuration scales with visible-degrees-per-screen; 8 tiers with minor steps in {10, 5, 1, 0.5, 0.1, 0.05,
  0.01} degrees (the `1°` tier appears twice with different major-tick groupings: `{minor=1°, major=10°}` and
  `{minor=1°, major=5°}` — picked at different zoom levels)
- Snap-to-detent at 0, ±45, ±90, ±180 degrees within `min(currentMinorTick × 0.5, 0.8°)`. The 0.8° cap matters most at
  the coarsest zoom (minor=10°) where ±45° isn't part of the tick grid (±90° and ±180° divide 10° so they stay on-grid);
  at deeper zooms the threshold shrinks proportionally to the visible minor tick so fine values near a detent (like
  0.01°-0.79° near 0°) remain selectable rather than getting pulled into a fixed dead zone. On a slow drag-release the
  detent the gesture STARTED at is excluded from snapping, so a deliberate small drag off a detent (0° → 0.4°) lands on
  the nearest tick instead of re-snapping back to its origin.
- Center indicator: mauve triangle + line; zero marker in red
- Reset chip: sets rotation back to exactly 0° (`setRotationDegrees(0f)`), leaving crop size, center, AR, and selection
  points untouched
- Ruler disabled (30% opacity, no touch) when no image loaded
- Ruler-zoom −/+ buttons (in the rotation row alongside the ruler) disable when (a) no image is loaded OR (b) the ruler
  is at its min / max pixels-per-degree limit — surfaced via `RotationRulerView.canZoomIn` / `canZoomOut`, with
  `setOnZoomChangedListener` firing the toolbar's `UiSync.updateRotationZoomButtons` on every zoom mutation (pinch OR
  button press OR zoomToMax from auto-rotate)

**Sub-epsilon rotation snap**: `CropState.setRotationDegrees` is the single chokepoint for every rotation entry point
(ruler, Reset chip, horizon detector). After clamp it snaps `|deg| < ROTATION_EPSILON` to exactly 0, keeping the
renderer, readout, and `ExportPipeline.canBypassEncode` aligned with the model — without the snap, the ruler can land
on a sub-epsilon value the rest of the pipeline rounds to zero (no visible rotation, hidden readout, but the bypass
disabled and a needless re-encode forced). The 0.005° epsilon sits a half-step below the ruler's 0.01°
finest tick so every value the ruler / horizon detector can produce is honored end-to-end.

**Auto-rotate button** (in the rotation actions row on the left, opposite the Reset button; the ruler-density
−/+ buttons flank the ruler in the row below. Always visible, disabled until an image is loaded — see
"Disabled-controls-stay-visible principle" above):
- First attempts horizon detection from JPEG metadata via `HorizonDetector.detectFromMetadata` — three passes in
  priority order: (1) standard XMP APP1 segments (canonical Adobe namespace prefix) searched for `Roll` / `Tilt`
  attributes; (2) Adobe Extended XMP chunks (the secondary-segment shape that carries XMP overflow past the ~64 KB APP1
  cap) reassembled by GUID + offset before scanning, so a Roll attribute past the first chunk OR straddling a chunk
  boundary is still found (per-segment substring scanning would otherwise miss split keywords); (3) fallback loop over
  any APP1 segment whose payload contains XML-like `Roll` / `roll` / `Tilt` text (catches vendor shapes that don't use
  the canonical Adobe namespace). Pure EXIF-tag parsing (CameraOrientation, ImageOrientation, MakerNote roll) is NOT
  currently implemented — every Samsung / Pixel / iPhone source we've seen ships horizon angle in XMP, so the EXIF path
  was never built. On a hit the rotation applies immediately with no further user interaction.
- On a successful detection (metadata fast-path OR painted-region detection below), the rotation ruler also auto-zooms
  to its finest 0.01° tick spacing so the user can immediately fine-tune. The ruler's auto-zoom is unconditional on
  detection success — the assumption is that if auto-rotate fired at all, the user is dialing in a precise correction
  and wants the high-resolution ruler tier.
- Falls back to a **paint-and-detect flow** (one Auto tap arms, one stroke commits) when no metadata roll is available:
  - Tapping Auto arms paint mode — the Auto button label changes to "Cancel" in red, the editor becomes a paint surface
    (`CropEditorView.onTouchEvent` routes the whole gesture stream to `HorizonPaintOverlay`), and the user paints over
    the visible horizon line with a single continuous stroke. The brush radius is constant in screen pixels
    (`CropEditorView.TOUCH_THRESHOLD_PX = 30`); its image-pixel radius scales inversely with zoom, so a deep-zoom paint
    covers fewer image pixels per stroke.
  - The stroke commits on finger lift (the stroke's `ACTION_UP`) — no second tap is involved. Paint mode exits, the Auto
    button reverts to its resting "Auto" label, and `AutoRotateBinder.onHorizonPaintComplete` snapshots the painted
    polyline and runs `HorizonDetector.detectFromPaintedRegion` (Canny edges + two-pass Hough: coarse 80–100° at 0.1° /
    fine ±2° at 0.01°) on the bg executor.
  - Tapping the Auto button again while paint mode is armed (its label reads "Cancel") cancels — exits paint mode
    without running detection and restores the button. The cancel tap is honored even while a bg op holds busy.
  - A degenerate stroke — bounding-box span smaller than the image-space brush radius, i.e. a bare tap (whose begin /
    end points coincide) or finger jitter with no direction for the line fit — surfaces "Paint was too short" and
    dispatches no detection. The gate is `CropEditorView.isHorizonStrokeTooShort` (distance-based, not point-count: even
    a tap contributes two coincident points) and runs before busy is claimed.
  - An OS / parent-view gesture interruption (`ACTION_CANCEL` — system back gesture, multi-touch disambiguation,
    scroll-container intercept) discards the in-progress stroke and exits paint mode without detection, restoring the
    Auto button via the overlay's one-shot onCancel callback.
  - A new image load (Open / Share / View intent) while paint mode is up exits paint mode through
    `AutoRotateBinder.cancelHorizonPaintMode` — discards the in-progress stroke without invoking the detection callback
    and reverts the Auto button to its resting "Auto" / subtext0 styling. Without this exit, the new image's first touch
    would route to horizon painting instead of Select / Move and the Auto button would stay stuck on its "Cancel" / red
    label from the previous load. Paint mode never survives an image swap.
- **Magnitude ceiling**: metadata roll values whose magnitude exceeds `MAX_HORIZON_TILT_DEGREES = 30°` are rejected by
  the metadata fast-path (`HorizonDetector.normalizeMetadataAngle`) by returning NaN. Large tilts indicate a
  held-sideways shot or sensor garbage rather than a horizon nudge. The painted-region Hough path
  (`HorizonDetector.runHoughAndConvertToRotation`) needs no such gate: its sweep is bounded to [80°, 100°] (and the LSQ
  refinement clamps to the same), so painted tilt is structurally capped at ±10°. User-visible: a rejected metadata roll
  is indistinguishable from absent metadata — `detectFromMetadata` returns NaN either way, so the same Auto tap falls
  through and arms the paint-and-detect flow; a painted-region detection failure (no Hough peak) surfaces "No line
  detected in painted area".
- **Busy-gating contract**: the paint-and-detect flow acquires `host.getBusy()` via `compareAndSet(false, true)` the
  moment the user commits the stroke (after the too-short gate passes), releases it in `onHorizonDetectionResult` only
  after the rotation has been applied. A Share/View intent or Open tap arriving mid-detection is rejected with the busy
  toast, not raced. Tapping the Auto button while busy (and not in paint mode) surfaces the busy toast and bails; only
  the cancel tap on an armed paint mode is exempt from the busy gate (see above). The painted points are snapshotted
  into a fresh ArrayList before bg dispatch so a future code path that bypasses the busy gate can't CME the bg detector
  via concurrent `imagePoints.clear()`.

**Rotation + crop interaction**:
- Crop auto-resizes to fit within rotated image bounds
- Center clamping uses 4-corner un-rotation check with binary search
- No-selection rotations use a stable "intent anchor" (`CropState.anchor{X,Y}`) so repeated rotations don't drift the
  crop center across recomputes. The anchor is updated when the user pans/drags or resets, and left alone through
  rotation ticks and AR changes
- Export canvas rotates around image center (matches preview exactly)

### 6. Zoom and Pan

| Gesture | Action |
|---------|--------|
| Two-finger pinch | Zoom with pivot; max zoom is per-image (caps each source pixel at 64 screen pixels) |
| Single-finger drag | Move mode: move crop / Select mode: pan viewport |
| Double-tap | Fit image to view (disabled in Select mode) |
| Long-press | Remove nearest selection point (Select mode) |

Zoom ceiling is `ViewportMath.maxZoom() = MAX_PIXEL_RATIO / baseScale`, so a large source image fit at a tiny baseScale
gets a high zoom cap (e.g., a 200 MP source fit on a phone caps near 1000×) while a small image fit near 1:1 caps at
64×. `ScaleGestureDetector`'s onScale is gated by a 1.5% scaleFactor deadzone so sub-percent finger-distance jitter
(hand tremor, sensor noise) doesn't drift-zoom when the user is just holding two fingers down. Suppressed events are
reported unconsumed (onScale returns false), so the detector keeps its span baseline and sub-threshold motion
accumulates across events — a slow steady pinch whose per-event delta never reaches 1.5% still crosses the gate and
zooms, while zero-mean tremor oscillates inside the band without accumulating a directional crossing. Every zoom
mutation fires the editor's zoom-changed listener — pinch steps via `onZoom` and every fit-to-view reset (image load,
view resize, double-tap) via `fitToView` — so the info-bar zoom badge always shows the actual zoom; a double-tap fit may
never leave the stale pre-fit value on the badge (UI honesty). Viewport clamped to prevent panning image off screen. At
4×+ zoom the renderer switches from the filtered display proxy to unfiltered 1:1 source pixels for a crisp peek, on any
source size. When the whole source fits the render budget (`MAX_SOURCE_RENDER_PIXELS` / `MAX_SOURCE_RENDER_AXIS`) it
uploads the entire source as one texture; for an over-budget source (e.g. a 200 MP capture) it instead draws only the
visible source region as a viewport-bounded tile cut from the software-ARGB source (`EditorRenderer`'s region-tile
path), so the GPU never receives an over-budget texture yet the pixels stay 1:1 crisp with the pixel grid active.

### 7. Grid Overlay

- Toggle via toolbar `Grid` chip
- Settings dialog opens via the toolbar settings icon (cards alphabetised: build, grid, permissions, pixel-grid,
  selection/paint)
- Grid-count presets: 2x2 through 8x8; arbitrary cols/rows via numeric input
- Configurable color (via `ColorPickerDialog`), line width (1-20px)
- **Line positions match `CropExporter.gridLinePixel`'s rounded relative-offsets**: first-half lines at `cropOrigin +
  Math.round(cropExtent * i / count)`, second-half lines mirror through `cropExtent`. The intra-crop positions agree
  with the export byte-for-byte; the absolute image-pixel coordinate is integer when `cropOrigin` is integer and
  fractional when it isn't (float-origin export keeps the two in lockstep). The middle line of any even grid count (2,
  4, 6, 8…) keeps `cropCenter` (half-integer for odd cropExtent) so single-point selection markers sit at the grid
  intersection — the only case where preview diverges from export, by ≤ 0.5 px
- Line width scales by image-to-screen ratio (preview matches export)
- Pixel grid activates only when BOTH conditions hold: each source pixel renders at ≥ `EditorRenderer.MIN_PIXEL_PEEP_DP`
  (3dp) on screen, AND the renderer is showing 1:1 source pixels — either the whole-source upload (`shouldRenderSource`)
  or the region-tile path (`shouldRenderSourceTile`), both requiring scale ≥ 4f (the tile path covers over-budget
  sources the whole-source upload can't). Gating on the source-switch keeps the grid's source-coordinate lines aligned
  with the pixels actually shown — on a low-density (mdpi) screen the dp threshold alone would fire in the 3 ≤ scale < 4
  band while the display proxy is still drawn, painting a grid that doesn't match the on-screen pixels. The dp threshold
  is density-normalised so visibility is consistent across screens. Separate toggle + configurable color in Settings;
  the same dp threshold drives the per-pixel selection-marker style in `EditorRenderer.drawSelectionMarkers` (which keys
  off the dp threshold alone, so it can lead the grid by the 3 ≤ scale < 4 band on mdpi)
- Selection points, polygon fill, and horizon paint use the shared selection / paint color (`GridConfig.selectionColor`,
  configurable in the Settings card per §11) — kept separate from grid color so the paint surface stays visible against
  the grid overlay
- Optional bake-in to exported image (`includeInExport`); grid + HDR supported. Reset on new image load — bake-in is a
  per-save choice, not a persistent preference

### 8. Undo/Redo

- Full undo/redo for selection points (50-step history)
- Buttons greyed out when not applicable
- Clear button removes all points and recenters the crop on the image midpoint at max size for the current AR
- History cleared on new image load
- Controls stay visible in Move mode but render disabled / greyed-out — the "Disabled-controls-stay-visible principle"
  above keeps the point-controls row from reflowing on every mode switch. The history itself persists across mode
  switches — switching back to Select restores the buttons with their previous enabled state.

### 9. Export

**Save dialog**: Two code paths gated by the MANAGE_EXTERNAL_STORAGE grant probed at Save-tap time.

- **MES granted (primary path)** — `SaveController.showMergedInAppDialog` opens `view/FolderPickerDialog`, a merged
  in-app dialog that hosts the format toggle (JPEG / PNG), the `Export Grid` checkbox, the folder navigator, an editable
  `Save as` filename field, AND a thumbnail browser of the current folder's existing images all in one place. The
  thumbnail browser toggles between a 3-column grid and a list view; the choice persists across launches via
  SharedPreferences (`cropcenter_picker_view` / `grid_mode`). The breadcrumb underneath the bold folder-name title row
  is clickable per-segment (each ancestor segment jumps to that level; the current segment is not tappable) and
  ellipsizes at START so the current segment stays visible on deep paths. On "Save here", the picked folder is mapped
  to an externalstorage SAF document URI via
  `SafFileHelper.buildExternalStorageDocumentUri` (primary-external-storage only; secondary-volume picks toast "Picked
  folder isn't on primary storage" and cancel — there is no automatic fallback, reaching the legacy SAF picker requires
  revoking MES per invariant #10 below), and the write dispatches through `routeCrashSafeSave` with the same Replace /
  Rename / Cancel collision handling the SAF flow uses. The Rename input pre-fills with `nextAvailableNumberedName`'s
  "(N)" suffix per Samsung / Android Files-app convention. The merged dialog commits format / grid-include selections to
  `CropState` once at "Save here"; `SaveController.onMergedSaveConfirmed` captures a `priorSnapshot` BEFORE the commit,
  so an in-app collision-dialog Cancel rolls the format / grid back (matching the SAF path's rollback contract). Initial
  folder resolves in three tiers via `SaveController.loadInitialPickerFolder` (same helper the OpenPickerDialog uses):
  the last-picked save folder (`cropcenter_save` / `last_save_folder`), then the last-loaded file's parent
  (`last_load_folder`, recorded by `ImageLoadController` on successful load), then
  primary external storage. Most-recent timestamp wins between the two folder candidates. Each persisted path is checked
  for existence + directory-ness before use, so a deleted folder falls through to the next tier. Every visible element —
  title region (bold folder name + clickable breadcrumb + grid/list toggle icon), content list, format/options row
  (`Export Grid` checkbox + JPEG/PNG format chips), filename input — sits inside a single `DialogCards` panel styled
  like the Settings dialog's section cards. The dialog opens at a stable full-size extent regardless of when the async
  enumeration populates the list — panel height derives from the shared `CARD_RESERVED_DP` (220dp) screen-height
  reserve, so all three pickers open at the same maximum size — and the content list shrinks and scrolls internally
  while the title, options, and filename rows keep their full height. Grid cells render square thumbnails; grid, list,
  and folder rows span the full card-content width.

- **MES not granted (legacy path)** — `SaveController.openSaveOptionsDialog` opens `view/SaveDialog` (title
  `"Save Image"`, positive button `"Continue"`, negative button `"Cancel"`) with the format toggle + Export Grid
  checkbox; on Continue, `ACTION_CREATE_DOCUMENT` opens with the format-aware MIME type. SaveDialog mutates `CropState`
  directly on Continue; `priorSnapshot` captures the pre-mutation state so a SAF cancel rolls back format / grid-include
  before the next Save attempt.

**Save flow**: Collisions inside the user's chosen directory route through `ReplaceStrategy`'s crash-safe
write-then-swap (Strategy A: File-I/O atomic move; B: SAF direct overwrite with byte-for-byte verify; C: SAF
rename-with-fallback) on the SAF path. Strategy B's byte-for-byte verify is authoritative over the later disk-state
check: a readback mismatch marks the colliding target proven-bad (`ReplaceStrategy.writeReplacementPayload`'s
`knownBadTarget`) and can never be reclassified as success by a length-only target check — the verified placeholder
survives and a dialog names it as the good copy. Strategy C's destructive delete-then-retry runs only on positive
evidence that the placeholder still exists under its own name (`ReplaceStrategy.classifyRenameFallback` — filesystem
probe first, display-name probe when no path resolves): when both probes are unavailable the fallback fails closed
without deleting the colliding document — after a silent null-on-success rename the colliding URI is the just-renamed
export — and `verifyReplace` surfaces the unresolved state instead.
The in-app path uses the same routing once the folder is mapped to its
externalstorage URI — collision detection fires the in-app Replace / Rename / Cancel dialog before the write dispatches,
gated on `target.isFile()`; a directory at the target name is instead rejected up front with a "That name is a folder —
pick another" toast + cancel (Replace is never offered against a folder — the delete-then-rename fallback could delete
the user's folder), a guard mirrored in the Rename dialog's OK handler. Same-name SAF results from
`ACTION_CREATE_DOCUMENT` (provider-confirmed overwrites) get a sibling placeholder via
`DocumentsContract.createDocument` and route through the same Replace flow; opaque-ID providers fall back to
`exportToOverwrite` (direct write to the target with preserve-on-failure, "Replaced <name>" toast on success).

**Crash-safe temp naming, placeholder honesty, and the startup sweep**: all three writers that create temp
artifacts in the user's save directory — `SaveController.routeCrashSafeSave` (the crash-safe SAF placeholder),
`ReplaceStrategy.replaceViaFileIo` (strategy A's write-then-swap temp sibling), and
`ExportPipeline.tryDirectAtomicWrite` (the preserve-on-failure overwrite temp) — build their names through the single
chokepoint `SaveTempFiles.tempName`, whose `SaveTempFiles.TEMP_NAME_MARKER` (`.cropcenter-tmp-`) prefix hides in-flight
temps from Gallery / MediaStore. The hidden name is strictly transient: every `ReplaceStrategy` failure branch that
keeps the placeholder as the user's final save gives it a visible name before any dialog names it.
`classifyFilesystemOutcome` renames a length-verified sole-survivor placeholder onto the free target name (clean
outcome, no dialog), and promotes a kept hidden placeholder to the first free "stem (N).ext" sibling
(`promoteToVisibleName`) before the two-files / placeholder-only dialogs interpolate it. The promotion rename itself
never replaces: the move runs without `REPLACE_EXISTING`, so a file another process creates at the probed "(N)" name
between probe and rename surfaces as `FileAlreadyExistsException` and the promotion retries onto the next free "(N)"
candidate (`ReplaceStrategy.retryPromotionOntoFreeSibling`, bounded by the same 9999-suffix cap as the probe) instead
of destroying the appeared file. A sole surviving wrong-length target is never deleted off its length alone — a
length mismatch proves the save didn't verify, not that the file is CropCenter's failed write (it may be the
untouched pre-Replace original under a lagging filesystem view, or a file another process created) — so both
wrong-length branches keep the file on disk and the "Replace produced an incomplete file" dialog reports it as left
in place; the only file the classifier ever deletes is a redundant placeholder beside a target verified at the
expected length. A save classified NEW-FILE
never replaces a file that appeared at the target name after classification: both promotion sites
(`ReplaceStrategy.promoteTempOntoTarget`, strategy A's swap, and `promoteSoleSurvivor`, the classifier's
completion rename) probe target existence immediately before the move under `wasOverwrite=false` and refuse on a
hit — the promotion aborts (SAF strategies skipped too) and `verifyReplace`'s two-files wording names the appeared
file and the kept placeholder. `promoteTempOntoTarget` backs the probe with a second gate: its NEW-FILE move drops
`REPLACE_EXISTING` so a target that appears between probe and move surfaces as `FileAlreadyExistsException` and
gets the same refusal; the kernel-level window between that filesystem check and the underlying rename is an
accepted residual (Known Limitations 11). A Replace the user explicitly confirmed is never classified NEW-FILE:
the in-app collision dialog's Replace button and the overwrite-confirm dialog's Replace button thread the
confirmation into `routeCrashSafeSave`, whose write-time classification is confirmed-OR-probe (`wasOverwrite =
confirmedReplace || probeWasOverwrite`) — so a pre-existing 0-byte colliding file, which `classifyOverwrite`'s
explicit-0 rule reads as NOT-overwrite, still gets the replace semantics the user confirmed instead of a
deterministically refused promotion. When strategy B's readback
proved the colliding target's content wrong (`knownBadTarget`), no `classifyFilesystemOutcome` branch may return clean
off a length-only target check: the placeholder is never deleted as a redundant duplicate — it is promoted and named
as the good copy by the `contentMismatch` dialog — and when no placeholder backup survived, the proven-wrong target is
kept on disk (`contentMismatchNoBackup`) because its stale content is all the user has left; the SAF-only fallback
(`classifySafFallbackOutcome`) attempts one SAF rename onto the requested name. Only when every rename attempt fails
does the dialog (`hiddenPlaceholderKept`) fire — and it then states the actual hidden filename kept and that hidden
files don't appear in Gallery. A kept recovery a dialog promised is protected from the startup sweep, in whichever
folder the promise was made: every failure wording that names a still-hidden kept file (`hiddenPlaceholderKept`, and
`contentMismatch`'s hidden-name variant) carries the name as `VerifyFailure.keptHiddenName`, which `verifyReplace`
journals durably (synchronous commit, so a process kill after the dialog's promise can't lose the entry) via
`SaveController.journalKeptRecovery` before the dialog shows. When the journal write fails (no resolvable folder, or
the synchronous commit itself fails — `journalKeptRecovery` returns false), the kept-name wording degrades honestly
(`ReplaceStrategy.degradeKeptPromiseWhenUnjournaled`): the dialog then warns that the kept file may be cleaned up
automatically after an hour and should be rescued or re-saved soon, instead of implying a sweep protection the
journal doesn't hold. Journal entries are folder-scoped — the kept file's
absolute path, anchored to the placeholder's resolved parent (`SafFileHelper.fileFromSafUri`), with the persisted
last-save folder standing in when no path resolves (opaque providers) — so changing the save folder can neither
strand a journal entry nor expose the promised file; the sweep then promotes the journaled file instead of deleting
it. Strategy C's destructive fallback journals before it destroys: when the provider rejects rename-to-existing and
the fallback must delete the colliding original before the retry rename, a temp-shaped placeholder is journaled as a
kept recovery first (`ReplaceStrategy.journalThenDeleteColliding` → `journalKeptRecovery`, synchronous commit on the
bg executor) — a hard kill between the delete and the retry rename leaves the hidden placeholder as the user's only
copy, and unjournaled the sweep would delete it once stale; a verified clean outcome drops that entry again
(`SaveController.unjournalKeptRecovery`) so the journal doesn't accumulate resolved entries. The delete proceeds
only under a durable journal entry: a failed journal commit makes `journalThenDeleteColliding` skip the delete
(fail closed), and the retry rename then fails against the still-existing colliding original into `verifyReplace`'s
two-files surface. When the
MANAGE_EXTERNAL_STORAGE grant isn't held at dialog time, replace-failure dialogs
add a neutral "Grant access" button that deep-links to the system MES settings page
(`ReplaceStrategy.postReplaceFailureDialog`). No path may leave the save under a hidden name while a dialog or toast
claims a visible filename. In-process aborts never orphan the crash-safe placeholder: `routeCrashSafeSave`
busy-pre-checks before creating it, and every pre-write abort downstream — a stale-source continuation abort (the
source image changed during the bg hop — `SaveController.abortIfSourceChanged`), a destroyed-host continuation abort
(the Activity was destroyed during the hop — `SaveController.runPostedSaveContinuation`), busy rejection at
`ExportPipeline`'s CAS, missing source image, encode failure, pre-enqueue dispatch failure — deletes the fresh
placeholder document
(write/verify failures already delete it via `reportFailure`'s delete-on-failure). Hard process kills (OOM killer
mid-save, force-stop, battery pull) bypass all in-process cleanup, so all three temp writers journal their temp's
absolute path BEFORE creating the file (`SaveController.journalInflightTemp`, `KEY_INFLIGHT_TEMP_PATHS` — a StringSet
distinct from the kept-recovery journal: temps are deletable, kept recoveries are promotable; synchronous commit on
the bg executor, and a failed commit never aborts the save — the journal is purely the hard-kill backstop), and every
in-process cleanup or rename that disposes a temp drops its entry again (`SaveController.unjournalInflightTemp`).
Every read-modify-write-commit on either journal StringSet — both journal writers, both unjournal removals, and the
startup sweep's journal snapshot and merge-commit — serializes on one process-wide lock in `SaveController`: an
Activity recreation (uiMode / locale / multi-window) leaves an in-flight save running on the old instance's executor
while the new instance's sweep runs on a fresh one, and an unsynchronized commit would silently erase an entry the
other side journaled meanwhile. The sweep holds the lock for NO filesystem work: it snapshots both journal sets under
the lock, runs its passes unlocked on the copies — safe because the 1-hour age guard keeps a concurrently-created
temp undeletable — then merges under the lock with remove-only semantics (`SaveController.commitSweptJournal`: the
committed set is the current live set minus the entries the sweep resolved, so entries journaled concurrently with
the sweep survive). A UI-thread temp disposal (`unjournalInflightTemp` is any-thread) therefore never blocks behind
the sweep's unbounded folder listing.
`SaveController.sweepStaleSaveTemps` (posted to the bg executor from `MainActivity.onCreate` alongside
`UltraHdrCompat.sweepStaleCacheFiles` — startup sweeps must not block first frame, and the save-folder listing is
unbounded) deletes stale temp artifacts in every folder the temp journal names — a journaled temp that is stale and
matches the temp shape is deleted and its entry dropped, one vanished from its present parent drops its entry, and a
fresh one is retained — plus the persisted last-save folder (`KEY_LAST_SAVE_FOLDER`), whose unjournaled delete pass
reclaims pre-journal legacy orphans. The matcher (`SaveTempFiles.isTempArtifact`) validates the full generated
shape rather than substring-matching — the marker prefix followed by a numeric nanoTime nonce, a `-` separator, and a
non-empty visible-name tail, or the legacy `.name.cropcenter-tmp-<nanos>` digits-only suffix shape earlier builds
wrote (so pre-unification orphans are reclaimed too). A file that merely contains the marker substring without a
valid nonce field never matches, since this predicate gates deletion in the user's save folder. The namespace is also
reserved in the opposite direction: `FolderPickerDialog.isValidFilename` rejects any typed name containing the marker
substring (`SaveTempFiles.isReservedName` — the exact `isTempArtifact` shapes plus everything one nonce short of
them), so neither the merged dialog's Save here nor the in-app Rename OK (both route through that one predicate) can
create a user file anywhere in the temp namespace; a temp-shaped original filename (a rescued hidden recovery
re-shared into the app) is de-reserved for the save-field pre-fill by stripping to its embedded visible name via
`SaveTempFiles.visibleNameOf`, falling back to the default "crop" stem (`SaveController.defaultSaveStem`). The sweep
is age-guarded by
`SaveTempFiles.STALE_TEMP_MIN_AGE_MS` (1 hour): a concurrent in-flight save's fresh temp is never swept. A journaled
kept recovery (see the kept-recovery journal above) is NEVER deleted by the sweep — not even when its path also sits
in the temp journal, the kept set takes explicit precedence — and the sweep visits every journaled parent folder,
resolving each entry of either journal against its own folder, while the unjournaled-delete pass runs only in the
last-save folder. Once stale, a journaled kept file is promoted, wherever it sits, to the
first free "stem (N).ext" sibling through the same `promoteToVisibleName` rename the failure dialogs use
(`SaveController.promoteJournaledRecovery`), and its journal entry is dropped on successful promotion or when the
file vanished from its own folder — never because a name is absent from whichever folder is currently swept. A
failed promotion keeps both the file and the entry for a retry at the next launch, and an entry whose parent folder
is missing (unmounted volume) is retained unverified.

**No-edit bypass**: when the user has applied no transformations (no crop, no rotation, no grid bake-in, JPEG-to-JPEG
round-trip) AND the in-memory image is not a graft (`!state.isGraftApplied()`) AND the source carries a pre-computed
IFD1 thumbnail (`ExifPatcher.hasIfd1Thumbnail(state.getJpegMeta())`), `ExportPipeline` writes `state.originalFileBytes`
verbatim instead of canvas-encoding. Preserves byte-perfect fidelity for re-saves of unmodified Samsung originals.
Cropped / rotated / grid-baked saves, any graft save, AND saves of sources lacking an IFD1 thumbnail go through the
canvas-encode + ExifPatcher pipeline. The thumbnail-presence gate forces the re-encode path on screenshots /
minimal-EXIF sources so `CropExporter`'s synthesise-fresh-EXIF chain can add a thumbnail; without this gate the bypass
shipped source bytes verbatim including the empty-IFD1 state. Graft saves are excluded because the splice ships source's
gain map verbatim over the edit's primary scan; if the user later crops, the gain map's spatial alignment shifts off the
features it boosts. Forcing graft saves through the full encode regenerates the gain map from the spliced primary via
`UltraHdrCompat.compressWithGainmap`, keeping save-without-crop and save-after-crop both correct.

**JPEG quality**: 100 (hardcoded, always maximum) when canvas-encoding; verbatim when bypassing.

**Output canvas color space** (`CropExporter.export`):
- **JPEG with gain map (Ultra HDR)** — explicit `Display P3` canvas. The gain map was calibrated against a P3-gamut
  base; composing onto an sRGB primary produces a subtly wrong HDR boost, so this branch overrides whatever the source
  bitmap's color space reports.
- **JPEG without gain map** — match the source bitmap's color space (`src.getColorSpace()`). The metadata injection pass
  restores the source's APP2 ICC profile verbatim, so the canvas color space has to describe the same encoding the ICC
  tag claims. Without this, a Display P3 source (modern iPhone JPEGs, Photoshop P3 exports) would render into the
  default sRGB canvas while the saved ICC tag still claimed P3 — ICC-aware viewers would then render wrong colors.
- **PNG** — default (sRGB) so source alpha round-trips and rotation corners stay transparent. Color-managed canvases can
  apply subtle filtering during rasterization that breaks grid-line consistency.

**Extension-vs-format coherence**: the merged in-app dialog and the legacy SAF picker handle this differently because
they have different control surfaces.
- **Merged dialog** (`FolderPickerDialog.normaliseExtension`): the format chip drives the on-disk extension. At "Save
  here" and at in-app Rename OK, the typed filename's trailing extension is swapped to match the selected format
  ("photo.heic" with PNG selected → "photo.png"). Same rule applies to the live extension swap when the user toggles the
  format chip mid-edit.
- **Legacy SAF picker** (`SaveController.handleSaveAsResult` guard): SAF locks the document's MIME type from the
  requested filename when the picker opens. If the user renames in the picker (`.jpg → .png`, `.jpg → .webp`, `.jpg →
  .heic`, etc.), the bytes would land in a document whose MIME and extension disagree. The guard rejects when `chosen`
  has a non-empty extension AND that extension's Format doesn't match `requested`'s Format. Known-format mismatches
  (`.jpg → .png`) and unknown-extension typos (`.jpg → .webp`) are both caught; extension-less filenames are allowed
  through (SAF MIME stays valid, encoder bytes match). Before either check, the provider-returned display name itself
  is validated (`SaveController.rejectsProviderDisplayName` → `FolderPickerDialog.isValidFilename`): a non-null
  `chosen` with path separators, traversal segments, a reserved crash-safe temp-namespace shape, or an over-length
  encoding aborts the save with the `"Invalid filename"` toast and the SaveDialog-settings rollback — the returned
  document is left undeleted because a provider's own Replace prompt can return a real pre-existing user file.

**Overwrite-confirm dialog** (`SaveController.showOverwriteConfirmDialog`): SAF's `ACTION_CREATE_DOCUMENT` picker can
return a URI to pre-existing content without surfacing a Replace prompt — observed on recent Samsung Files / Google
Files where the picker silently hands back the colliding document. Without an in-app confirmation, the save would
silently overwrite a file the user might not have meant to destroy. The save flow's Case A (chosen == requested) and
Case C (user-typed name, no auto-rename pattern) both probe the SAF URI via `probeWasOverwrite(newUri)` — three
independent signals, any positive confirms the overwrite: `priorSize > 0`, OR `priorSize == -1 &&
hasExistingContent(newUri)` (content-stream fallback, consulted only when the provider omits SIZE), OR the path-resolved
`fileFromSafUri(newUri)` file exists with `length() > 0` (filesystem-authoritative — catches Samsung MediaStore-backed
providers that expose neither SIZE nor a readable stream, and overrides an explicit provider-reported 0;
`SaveController.classifyOverwrite` is the pure decision core). `routeCrashSafeSave` re-runs the same predicate at write
time so dialog wording and write strategy can't disagree — and a dialog-confirmed Replace is threaded alongside as a
fact the re-probe can widen but never downgrade (write-time `wasOverwrite = confirmedReplace || probeWasOverwrite`; see
the crash-safe promotion section for the 0-byte-collision case this protects). The probe is synchronous provider IO, so
it runs on the shared background executor with a UI-thread continuation; `savePending` stays set across the hop
(parallel Save taps stay gated) and the continuation preserves the dialog-before-dispatch ordering. Every bg-probe
continuation is bound to the source image the save was initiated for: before showing a dialog or dispatching a write,
the continuation re-checks reference identity against the live `CropState` source (`SaveController.abortIfSourceChanged`
— the same identity contract as `restorePriorSaveSettings`' snapshot check) and aborts the save when a Share/View load
replaced the image during the hop, instead of exporting the new image to the old image's destination. On abort:
`savePending` clears, the abort routes through the settings-rollback chokepoint (`restorePriorSaveSettings` — which
discards the snapshot but skips the rollback itself: the snapshot belongs to the replaced image, and restoring it would
overwrite the new image's freshly-set format), the user sees a "Save cancelled — image changed" toast, and the
write-dispatch continuation deletes the sibling placeholder its bg hop just created (the no-orphan-on-abort invariant).
The overwrite-confirm dialog's Replace button re-checks the same binding before dispatching — defense in depth beside
the transient-dialog dismissal, which covers the dialog only once it is registered. Every continuation posted to the
main looper additionally runs through `SaveController.runPostedSaveContinuation`: on a host destroyed during the hop
(back-press, uiMode / locale / multi-window recreate — the manifest handles rotation) it aborts with the standard
cleanup (placeholder delete, `savePending` clear, settings rollback, no toast) instead of dispatching against the
shut-down executor, and a dispatch-guard rethrow reaching a posted continuation is absorbed after that guard's own
cleanup — the rethrow convention exists for synchronous callers with a live call stack; from a posted runnable it would
be an uncaught main-looper exception that kills the process. When the probe returns true, the user sees a Replace /
Cancel dialog. Replace dispatches through the same crash-safe sibling-placeholder path; Cancel preserves the file.
Distinct from Case B's three-option Replace / Keep / Cancel dialog (`showReplaceDialog`), which fires only when SAF
auto-renamed to a "X (N).ext" pattern — that case has a meaningful Keep option (commit the auto-renamed URI as-is);
Cases A and C don't. Case B verifies the inferred base document before offering Replace
(`SaveController.classifySiblingCollision` is the pure decision core): filesystem probe first — a path-resolved base
must exist with `length() > 0` — and the display-name + SIZE probes only when no path resolves, because
`getDisplayName`'s path-first shortcut string-parses path-addressable URIs without an existence check and would
otherwise confirm a phantom base the user merely implied by typing "(N)" in the picker. A 0-byte base (filesystem or
provider-reported) is an interrupted save's placeholder, not content to preserve — no dialog; unknown SIZE on a
name-confirmed base still counts as collision; when no sibling URI can be derived (opaque-ID providers) the
auto-rename pattern itself is trusted.

**HDR Export Pipeline** (all cases use canvas rendering for primary):
```
Render primary on cropW x cropH canvas -> Bitmap.compress(JPEG) to encode tempfile
  (same rotation/positioning as preview: rotate around image center)
-> build cropped gain map: decode original with gainmap; apply the analogous transform to the
   gainmap bitmap at gainmap resolution — same EXIF rotation matrix, same scaled draw offset;
   unrotated branch snaps the fractional draw offset to the nearest integer + nearest-neighbor
   (≤ 0.5 gainmap-pixel drift, detailed below); gainmap dims stay at natural rounded
   quarter-resolution and are NOT AspectRatio.snap'd (see §4 "Locked-AR exact-integer
   realisation" for the rationale); attach gainmap -> Bitmap.compress -> Ultra HDR JPEG
   -> extract the gain-map portion
-> inject original EXIF (patched) into the encoded primary — hdrgm XMP + MPF kept when a
   gain map was built, stripped up front otherwise
-> compose the gain map onto the injected file (GainMapComposer: patches the GContainer
   Item:Length to the new gain-map byte size via XmpItemLengthPatcher and the MPF offsets via
   MpfPatcher inside the compose; fail-closed refusal -> drop HDR and re-inject from the
   encoded primary with HDR-stripped metadata rather than ship stale Item:Length)
-> re-append existing SEFT trailer verbatim (if any)
```

The gain map undergoes the analogous canvas transform as the primary (same position, pivot, angle, scaled to gainmap
resolution). The unrotated and rotated branches differ in resampling: unrotated snaps to integer + nearest-neighbor;
rotated keeps bilinear unless the integer-alignment + `BitmapUtils.isLosslessCardinalRotation` gate passes (180° at any
dims; 90°/270° only when the gain-map bitmap's width + height is even — evaluated on the gain map's OWN dims, which can
differ in parity from the primary's). See "Gain-map render snap-to-int at fractional offsets" below for the full
rationale.

**EXIF-orientation rotation of the gainmap**: When the source EXIF orientation is non-identity (2..8),
`UltraHdrCompat.applyExifOrientation` rotates the embedded gainmap's pixel buffer with the SAME matrix as the primary
and substitutes a fresh `Gainmap` instance (tone-mapping metadata copied verbatim via `copyGainmapMetadata`) on the
rotated primary. Android's `Bitmap.createBitmap(src, matrix)` propagates the source's `Gainmap` reference but does NOT
rotate its underlying pixel buffer; without explicit rotation the downstream `renderGainmap` step computes `scaleX =
gmW/primW` against a stored-orientation gainmap whose axes are transposed relative to the rotated primary, producing
catastrophic spatial misalignment on orient=6/8 sources (panel-3 of the graft-analyze heatmap was entirely lit up — the
gainmap diff approached 100% of pixels because every pixel was sampled against the wrong gainmap neighborhood). The fix
keeps the gainmap rotation in lockstep with the primary, so subsequent canvas-renders project both into display
orientation coherently.

**Grid + HDR**: The gain map is extracted from the HDR path and composed with the canvas primary (which has the grid
baked in). The XMP hdrgm metadata from the original is preserved via `JpegMetadataInjector`.

**Non-HDR JPEG Export** (no source gain map, or HDR explicitly dropped):
```
Canvas-rendered bitmap -> Bitmap.compress(JPEG, 100)
  -> stripHdrSegments on source meta (drop APP2/MPF + hdrgm XMP up-front so the output
     never ships orphan HDR metadata describing a non-existent gain map)
  -> inject the pre-stripped metadata
  -> re-append existing SEFT trailer verbatim (if any)
```

The non-HDR path is fail-closed: no gain-map bytes are ever appended, and MPF is dropped rather than "fixed up to point
at nothing." When the source carries an MPF segment but no Ultra HDR gain map (e.g. Samsung "Best Photo" burst groups,
focus-stacked panoramas, or any multi-picture JPEG without hdrgm), `ImageLoadController` leaves `state.getGainMap()`
null and the export takes this path. Without the up-front strip the saved JPEG would carry source's MPF verbatim,
anchored at non-existent secondary-image offsets — strict decoders' multi-picture pre-flight rejects the orphan, lenient
decoders walk past the malformed entries. The strip removes that footgun entirely.

**PNG Export**:
```
Canvas-rendered bitmap -> generate fresh JPEG-compressed IFD1 thumbnail of the cropped pixels
(buildEmbeddedThumbnail; falls back to STRIP_IFD1_THUMBNAIL sentinel on OOM / over-budget)
-> Bitmap.compress(PNG) -> inject EXIF via eXIf chunk with the fresh IFD1 thumbnail
(PNG 1.6 spec: raw TIFF data in CRC32'd chunk after IHDR)
```

PNG export generates a fresh IFD1 thumbnail (JPEG-compressed, per EXIF spec) of the cropped pixels — without this,
passing a `null` thumbnail to `ExifPatcher.patch` would PRESERVE the source's pre-edit IFD1 thumbnail, leaking pre-crop
content via any EXIF-thumbnail-aware viewer. When fresh thumbnail generation fails (every cascade rung exhausted — both
512 and 256 maxDims at every quality 90..50 — OR OOM during render / compress, OR the budget itself is ≤ 0 because the
source EXIF already overflows the APP1 cap), `buildEmbeddedThumbnail` returns `ExifPatcher.STRIP_IFD1_THUMBNAIL` (the
byte[0] sentinel), routing ExifPatcher through the strip path that zeros IFD0's next-IFD pointer — the saved file has no
embedded preview rather than the source's stale one.

PNG export pulls EXIF from one of two sources, depending on the source format:
- **PNG sources** use `state.pngExifTiff` (raw TIFF, uncapped) wrapped through `CropExporter.patchPngExifTiff` —
  synthesizes a transient APP1 only to run `ExifPatcher.patch` for orientation/dimension normalisation, then unwraps the
  patched TIFF and writes via `injectPngExifFromTiffFileToFile`. The PNG eXIf chunk has a u31 length field, so a > 64KB
  EXIF block (camera with extensive MakerNote / GPS metadata) round-trips on PNG → PNG. The IFD1 thumbnail itself is
  still bounded by the JPEG APP1 u16 cap that `ExifPatcher.spliceExistingThumbnail` enforces internally;
  `patchPngExifTiff` predicts a too-large rebuild via `ExifPatcher.maxThumbnailBytes` (which subtracts the OLD
  thumbnail's bytes before measuring remaining APP1 room — a naive `tiff.length + thumbnail.length` sum would
  force-strip splices that actually shrink the segment — and clamps the budget at oldThumbLen when trailing bytes follow
  the IFD1 thumbnail, because the padded splice can't widen the slot) and force-routes to `STRIP_IFD1_THUMBNAIL` so the
  saved PNG carries no IFD1 rather than the source's pre-edit preview. PNG eXIf keeps using `maxThumbnailBytes` (20 KB
  conservative fallback) rather than the JPEG path's byte-exact `patchedNonThumbBytes` — its u31 cap and uncapped-TIFF
  tolerance need the older estimation-with-margin semantics. When `ExifPatcher` rejects a malformed source TIFF, the
  export branches by TIFF size: > 64 KB TIFFs ship VERBATIM through `CropExporter.forceTiffOrientationToUpright` +
  `injectPngExifFromTiffFileToFile` (preserves metadata that would otherwise be truncated by the u16-capped
  synthetic-APP1 fallback; orientation is rewritten to 1 because the saved pixels are already upright after
  BitmapUtils.applyOrientation ran at load — without the rewrite, EXIF-aware viewers would double-rotate); ≤ 64 KB TIFFs
  fall through to the synthetic-APP1 path below.
- **JPEG sources** (and the fallback path above) iterate `state.jpegMeta` through `ExifPatcher.patch`; the EXIF
  segment's JPEG APP1 wrapper (`FF E1 LL LL "Exif\0\0"` — 10 bytes) is stripped inline in `exportPng` and the raw TIFF
  body is streamed into the eXIf chunk via `injectPngExifFromTiffFileToFile`. JPEG-source EXIF is always under the u16
  cap by spec, so no special handling is needed.

The export canvas is **not** background-filled for PNG — the bitmap stays on its default transparent background so
source alpha round-trips and rotation corners stay see-through. JPEG fills with `ThemeColors.BACKGROUND` — the exact
color the editor preview draws behind the image (`EditorRenderer` uses the same constant) — since the format can't
represent alpha; rotation corners in the saved JPEG therefore match the editor background the user saw.
`UltraHdrCompat.renderPrimary` applies the identical fill and paint flags so the HDR-path primary render agrees with the
SDR render. The fill decision lives at `CropExporter.export` next to the `outputBitmap` allocation and gates on the same
`isJpeg` flag that picks the Display P3 colorspace.

**Export failure surfacing**: `ExportPipeline.encodePhase` catches both `Exception` and `OutOfMemoryError` and posts an
`"Export failed: <message>"` toast on the UI thread; `writePhase` failures route through `reportFailure` with the same
toast pattern. The OOM catch is required because a multi-megapixel source's primary bitmap or HDR-render allocation can
fail with `OutOfMemoryError` rather than `Exception`; without it the worker would die uncaught and the user would be
left staring at the editor with no feedback after the progress overlay's `finally`-block hide. The same widening applies
on the load path (`ImageLoadController.runLoadBg` posts "Load failed: <message>"), the graft-apply path
(`MainActivity.applyGraftedBytesOnBg` posts "Apply failed: <message>"), the graft-assembly path
(`GraftController.assembleGraftOnBg` posts "Graft failed: <message>"), and the thumbnail subroutine
(`CropExporter.generateThumbnail` widens to `Exception | OutOfMemoryError` so a thumbnail-side allocation failure
degrades to "save without embedded thumbnail" rather than aborting the encode silently).
`UltraHdrCompat.compressWithGainmap`'s outer catch carries the same widening so a native-heap allocation failure during
the HDR re-decode / render returns empty — HDR drops and the SDR save proceeds (the degrade path documented under
"Native heap (HDR re-encode)" above) — instead of escaping through `buildCroppedGainMap` and aborting the export; and
`ExportPipeline.runExportBg`'s post-save Replace-callback catch is widened too, so a callback OOM (the Replace payload
materialisation) surfaces the "Save step failed" toast and releases busy rather than killing the process with an
uncaught Error. `reportFailure` honours `preserveOnFailure` for collision-overwrite Replace flows — when the target had
prior content, the partial bytes are kept and the recovery path runs from `ReplaceStrategy` rather than deleting a file
the user didn't ask us to destroy. When the direct-file atomic write fails on a `preserveOnFailure=true` target,
`writePhase` synthesizes a failed `WriteOutcome` and refuses to fall through to the SAF stream's truncate-mode
`openOutputStream("w")` — SAF "w" truncation behavior is provider-dependent (some truncate at open time), so a
mid-stream failure after fall-through would leave the user's original zeroed or partially overwritten, defeating the
purpose of the preserveOnFailure branch. The exportToOverwrite / exportToPreserving callers surface a clean "Export
failed" toast and the target on disk stays intact.

**Encoder-return-value checks**. Every `Bitmap.compress(JPEG|PNG, q, bos)` callsite treats `false` as a failure. Without
the check, the partial buffer in `bos` (Skia writes headers + entropy data before bailing) would ship onward to
`JpegMetadataInjector.injectFileToFile` / `GainMapComposer.composeFileToFile` / `appendSeftFileToFile` and produce
structurally invalid output (no primary EOI, no gainmap, no SEFT). Non-cascade sites (`CropExporter.exportJpeg` /
`exportPng`, `UltraHdrCompat.compressWithGainmap`, `EditAligner.reorientEdit`) short-circuit to an empty-Optional /
exception that surfaces the existing failure toast. `CropExporter.generateThumbnail`'s 2-rung × 8-quality cascade (up to
16 attempts) is the one exception: false on any individual attempt advances to the next combo; only an all-combo-failure
returns empty and routes the caller through `STRIP_IFD1_THUMBNAIL` (no preview embedded, better than partial).

**Direct file-I/O write path**. `ExportPipeline.writePhase` tries `FileOutputStream` against the SAF URI's resolved
filesystem path first, falling back to the SAF stream only when `SafFileHelper.fileFromSafUri` returns empty (cloud /
opaque-ID providers). On Samsung devices the SAF stream path has been observed to silently corrupt writes:
`openOutputStream("w")` returns success and reports the correct post-write byte count, but the actual disk content never
changes — the provider buffers the write in memory and never flushes, leaving the placeholder document with stale bytes
that downstream Replace strategies then propagate onto the target. The direct file I/O sidesteps the entire SAF write
path: bytes land via the kernel filesystem layer where the provider's caching can't intercept. The `fsync` after write
forces durability before close. Trailing `MediaScannerConnection.scanFile` triggers MediaStore reindex so Gallery /
Photos sees the new content.

**Explicit mtime refresh**. `ExportPipeline.writePhase` (after the direct I/O write) and
`ReplaceStrategy.replaceViaFileIo` (after `Files.move` atomic swap) both call `setLastModified(currentTimeMillis())` to
force the file's last-modified timestamp to update. Samsung's FUSE-backed scoped storage skips mtime refresh on
dedup-detected content-identical writes — the kernel-level write happens but userspace observers (file managers, `adb
stat`, sync tools that compare timestamps) see the old mtime, creating ambiguity about whether the save actually ran.
`setLastModified` after the write/sync forces the timestamp regardless of dedup behaviour, so the user always has a
concrete signal that the save landed.

**Gain-map render snap-to-int at fractional offsets**. `UltraHdrCompat.renderGainmap`'s unrotated branch snaps
`gainmapDrawX/Y` to the nearest integer and switches to a non-filtering Paint (`setFilterBitmap(false)` →
nearest-neighbor) before `drawBitmap`. Drawing at a fractional offset (e.g. `gainmapDrawY = -31.25` from `srcY = 125 *
gainmapScaleY = 0.25`) with `FILTER_BITMAP_FLAG` would bilinear-blend adjacent gainmap rows on every output row,
softening the boost map and producing 5-30 levels of per-pixel diff against the source gainmap on high-contrast content
(horizon lines, cliff edges, wave foam). Snap-to-int costs ≤ 0.5 gainmap pixels of spatial misalignment (≤ 2 primary
pixels at quarter-res gainmap — sub-perceptible on a 4000-pixel-tall image) and buys pixel-exact gainmap reproduction at
the JPEG-round-trip noise floor. The rotated branch (`UltraHdrCompat.drawGainmapRotated`) takes the NN path only behind
an integer-alignment + `BitmapUtils.isLosslessCardinalRotation` guard — the same parity-aware predicate
`BitmapUtils.drawCropped` uses for the primary: 180° is a lossless integer remap at any dims, 90°/270° only when the
drawn bitmap's width + height is even. A mixed-parity 90°/270° keeps bilinear, because a half-pixel-offset NN gain map
would spatially shift the HDR boost off the pixels it belongs to. The unrotated branch snaps + NN unconditionally.

### 10. Metadata Preservation

#### EXIF
- All original APP/COM segments preserved verbatim (camera model, GPS coordinates, MakerNotes, ICC, XMP, Software,
  DateTimeOriginal, lens info)
- Orientation tag set to 1 (output is always in display orientation)
- Dimensions updated to crop size
- **Four-state thumbnail contract on `ExifPatcher.patch(segments, newW, newH, thumbnail)`**:
  1. `thumbnail == null` — preserve source's IFD1 verbatim. Only safe when saved pixels equal source pixels (verbatim
     bypass-encode path).
  2. `thumbnail == STRIP_IFD1_THUMBNAIL` (the canonical zero-length sentinel) — strip IFD1 by zeroing IFD0's next-IFD
     pointer. Used as the fail-closed fallback when fresh thumbnail generation hits OOM or over-budget.
  3. `thumbnail.length > 0` — replace IFD1's thumbnail with the supplied JPEG bytes. Fallback chain inside
     `replaceThumbnail`: try `spliceExistingThumbnail` first; on reject (missing thumb tags, out-of-bounds offsets,
     oversized cap, or new thumbnail BIGGER than the old one with non-empty trailing data) try
     `appendFreshIfd1WithThumbnail` against IFD0's existing next-IFD slot; strip-on-fail. **Padded splice for
     shrink-with-trailing-data**: Samsung HDR captures place ~7 KB of MakerNote / SubIFD value blocks AFTER the IFD1
     thumbnail (afterLen > 0), and the cascade's fresh thumbnail is typically SMALLER than the source thumbnail. The
     splice zero-fills the gap between the new thumbnail's EOI and the trailing data so the trailing bytes stay at their
     original absolute offset (any TIFF offset stored elsewhere — MakerNote value blocks, SubIFD value data, GPS
     offset-referenced data — remains valid); the IFD1 `JPEGInterchangeFormatLength` tag is rewritten to the actual new
     thumbnail length so decoders read only the real thumbnail bytes and the padding is unreached. Without this, every
     Samsung HDR save stripped the thumbnail because the splice bailed on length mismatch with trailing data and
     `appendFresh`'s APP1-cap check also rejected on the already-near-full segment.
  4. No EXIF segment in the input (the list may be non-empty — e.g. XMP-only) — `patch` synthesises a fresh APP1 EXIF
     via `buildMinimalExifSegment` so a freshly-generated thumbnail still lands in the saved file when the source
     carries no EXIF (screenshots, generated images, files re-encoded by minimal tools). Synthesised IFD entries are
     written in ascending tag order (IFD0: ImageWidth, ImageLength, Orientation) per TIFF 6.0's sorted-entries
     requirement — strict / binary-searching parsers may otherwise miss the tags.
- Thumbnail regenerated from cropped bitmap via a **two-rung dim cascade** with an **8-step quality bracket** at each
  rung. Long-edge max-dim falls 512 → 256; at each rung, encode quality steps through `{ 90, 80, 75, 70, 65, 60, 55, 50
  }` and the first combo whose encoded size fits the APP1 budget wins. The 8-step bracket exhausts dim-preserving
  fallback at 512 before stepping down to 256 — viewers downscale thumbnails for grid / hover display (96-256 px), and
  downscaling masks per-pixel JPEG artifacts substantially, so keeping pixel count high beats keeping per-pixel quality
  high (matches Samsung's native preserve-dim-scale-quality design). No 1024-maxDim rung because typical 3-4 MP cropped
  output produces 130-400 KB at any quality — above the 65 535-byte APP1 cap, so it would be unreachable in practice.
- Budget is computed **byte-exactly** via `ExifPatcher.patchedNonThumbBytes(meta)` which mirrors `patch()`'s decision
  tree (splice / append / synthesise) and returns the corresponding post-patch non-thumbnail size: `data.length
  - oldThumbLen` for a splice with no data after the thumbnail, `MAX_SEGMENT_BYTES - oldThumbLen` for a splice with
    trailing bytes after the IFD1 thumbnail (the Samsung MakerNote shape — the padded splice only accepts new thumbnails
    ≤ oldThumbLen, so the derived budget clamps at exactly oldThumbLen), `data.length + 42` for append (fresh 42-byte
    IFD1 header), `102` for synthesise. The caller computes `exactBudget = MAX_SEGMENT_BYTES
    -patchedNonThumbBytes(meta)` — no defensive margin, no upper clamp. `maxThumbnailBytes` applies the same
    trailing-bytes clamp under the same splice gate (both IFD1 thumbnail tags present AND a non-zero offset) so the
    two budget predictors agree on every source shape — an incomplete IFD1 (e.g. `JPEGInterchangeFormat` without
    `JPEGInterchangeFormatLength`) routes through append and is never clamped to a zero budget.
- ExifPatcher creates IFD1 if original has no thumbnail
- **IFD0 sanitisation** in `scanIfd` (depth=0 only): any thumbnail-pointer tag (`Compression` 0x0103,
  `JPEGInterchangeFormat` 0x0201, `JPEGInterchangeFormatLength` 0x0202) found in IFD0 — a corruption pattern that
  exists in the wild (Samsung Gallery edits and older CropCenter outputs hoist IFD1 tags up into IFD0) — gets its
  entire 12-byte entry zeroed. The fresh IFD1 still lives at IFD0's `nextIfd` offset and carries the new thumbnail;
  the zeroed IFD0 entries prevent strict TIFF parsers from following the stale offsets into garbage
- **Direct file-path read bypasses Samsung MediaStore mangling** (`SafFileHelper.tryReadDirectlyFromPath`). Samsung's
  MediaStore ContentProvider rewrites the EXIF segment as it streams JPEG bytes through `openInputStream` — zeros out
  GPS sub-IFD value blocks and reorders IFD0 entries — likely a privacy-driven sanitisation pass on Samsung firmware.
  `readUriBytes` resolves the URI to a filesystem path (handles both `com.android.providers.media.documents` and
  `com.android.externalstorage.documents` SAF authorities, requires `MANAGE_EXTERNAL_STORAGE`) and reads via
  `FileInputStream` when possible, returning the on-disk bytes that still carry GPS. Falls back to the SAF stream copy
  for cloud or SAF-only sources where no filesystem path is resolvable. A direct read that observes interference
  refuses its result and takes the same fallback: the exact-length read is followed by a one-byte EOF probe
  (`SafFileHelper.readExactSnapshot`), so a file that grew past its `File.length()` snapshot mid-read (a concurrent
  writer still appending the gain-map / SEFT appendix) is re-read through the SAF stream at its own read time instead
  of silently installing a valid-prefix JPEG minus its appendix.
- **SAF path-traversal guard** (`SafFileHelper.fileFromSafUri` + the `com.android.externalstorage.documents` branch of
  `getFilePathAndId`). Rejects docIds whose tail contains a `..` path segment — checked via the segment-aware
  `SafPaths.hasParentTraversalSegment` (splits on `/`, rejects only segments exactly equal to `..`) — or that begin with
  `/`. A substring `String.contains("..")` check would reject legitimate filenames whose characters happened to include
  `..` (Samsung's "IMG..edited.jpg" pattern), forcing them through the SAF stream path and losing the
  Samsung-MediaStore-bypass benefit above. Applied to the `primary:` volume handler, the `raw:` volume handler, the
  ExternalStorageProvider `relPath` branch, and — via `canonicalUnderStorage` — the docId-based MediaStore `_data`
  lookups, proc-fd readlink targets, and `fileFromSafUri`'s `_data` fallback. Prevents a crafted Share intent with
  `primary:../../data/data/com.othertarget/foo` from materialising a File outside the volume root on rooted devices.
- **Provider-path anchoring invariant** (`SafFileHelper.canonicalUnderStorage` / `isUnderStorageRoot`). Every filesystem
  path a URI-resolution branch produces — the `primary:` / `raw:` docId parses, the `msf:` / bare-numeric MediaStore
  Files lookups, `getFilePathAndId`'s direct `_data` query AND its media.documents `image:NNN` lookup, and the
  `/proc/self/fd` readlink fallback — is anchored under `/storage/` before it feeds direct MES-backed File I/O
  (`tryReadDirectlyFromPath`'s `FileInputStream`, the Replace flow's `FileOutputStream`). All of those branches except
  the direct `_data` query also screen `..` segments through `canonicalUnderStorage`; the direct query instead trusts
  prefix-anchored rows from platform authorities only (`isTrustedFileAuthority` + `isUnderStorageRoot`), and
  `fileFromSafUri` re-screens its result through `canonicalUnderStorage`. A rejected path resolves to an absent result
  (empty Optional) and the caller falls back to the access-checked ContentResolver stream, which enforces the URI's own
  grant. Without the anchor, a crafted `_data` row on an untrusted Share / VIEW URI could steer the
  MANAGE_EXTERNAL_STORAGE grant at app-private paths (`/data/data/...`) outside the granted-URI boundary.
- **TIFF orientation-tag validation** in both readers (`BitmapUtils.readExifOrientationInternal` for JPEG APP1 EXIF and
  `PngMetadataExtractor.extractOrientationInternal` for PNG eXIf), via the shared `TiffIfd0` walker. After accepting
  the byte-order field (`II` / `MM`), the walk also validates: TIFF magic = 42 (0x002A), Orientation entry type =
  SHORT (3), Orientation entry count = 1, and Orientation value in [1, 8] (`TiffIfd0.readOrientation`). A malformed
  payload with plausible offsets and a coincidental tag-0x0112 byte sequence would otherwise rotate pixels even though
  the documented contract says malformed EXIF maps to upright; the four checks bring the contract back into agreement.

#### PNG eXIf (PNG 1.6 spec)
- **Loaded** via `PngMetadataExtractor`. Walks PNG chunks (8-byte signature + length / type / data / CRC chunks) for the
  lowercase-`e` eXIf chunk. The walk stops at IEND (spec-correct: IEND terminates the datastream, so post-IEND trailer
  bytes are never parsed as chunks), and the selected eXIf chunk must pass CRC32 validation over its type + data fields
  — a CRC mismatch drops metadata only (pixel decode is unaffected). Stores results in two parallel forms because the
  JPEG and PNG export paths have different size constraints:
  - `state.jpegMeta` carries a synthetic APP1 EXIF segment (capped at the JPEG APP1 u16 limit of 65535 bytes, so JPEG
    injection downstream doesn't write a malformed segment).
  - `state.pngExifTiff` carries the raw TIFF bytes uncapped, used by PNG → PNG export where the eXIf chunk's u31 length
    field has no JPEG-side cap.
- **Orientation** is parsed at load time via `PngMetadataExtractor.extractOrientation` so PNG sources rotate pixels to
  display orientation matching JPEG behavior. Without this, a PNG with eXIf orientation=6 (rotate 90 CW) would display
  in stored pixel orientation while the export side normalises orientation to 1, baking a permanent sideways rotation
  into the saved file.
- **Saved** via `CropExporter.injectPngExifFromTiffFileToFile` — writes a fresh u31-sized eXIf chunk after IHDR carrying
  the patched TIFF (orientation normalized to 1, dimensions rewritten to crop size by `ExifPatcher.patch`). PNG → PNG
  round-trips therefore preserve EXIF up to the eXIf chunk's u31 limit; PNG → JPEG conversions still drop > 64KB EXIF
  (with a warning log) because the JPEG APP1 segment-length field is u16. A malformed source TIFF ≤ 64 KB that
  `ExifPatcher` rejects falls back to the synthetic-APP1 path so partial metadata isn't silently dropped; rejected TIFFs
  > 64 KB ship verbatim through `forceTiffOrientationToUpright` (the §9 size branch).
- **Color management chunks** (`iCCP`, `sRGB`, `cICP`, `gAMA`, `cHRM`): NOT preserved across round-trip.
  `PngMetadataExtractor` parses only `eXIf`. The PNG decoder reads any source color profile to interpret pixel values
  during `BitmapFactory.decodeByteArray`, but `CropExporter.exportPng` writes the cropped bitmap with
  `Bitmap.compress(PNG)` against the editor's default sRGB canvas, so any source iCCP profile is lost. A source Display
  P3 PNG saved through CropCenter ships with whatever color metadata Skia's PNG encoder emits for an sRGB-tagged bitmap
  — typically an `sRGB` chunk and / or `gAMA` + `cHRM`, never the source iCCP. This is acceptable for the editor's use
  case (most camera / phone PNGs are already sRGB) but means CropCenter is not a color-managed PNG editor.

#### Samsung SEFT Trailer
- Extracted on load and re-appended on save **byte-for-byte** — a Gallery-edited file that's been re-edited in
  CropCenter keeps its working Revert chain because the trailer's backup-path reference still points at Gallery's own
  `/data/sec/photoeditor/` backup, which we never touched.
- **Fail-closed on an unwalkable trailer start**: `SeftExtractor.extract` locates the trailer start only by forward
  marker-walking (primary EOI, then the gain map's EOI when the `hasGainMap` hint is set). When that walk fails
  (malformed gain-map chain), the trailer is treated as absent — extract returns empty and the save carries no SEFT. The
  SEF footer's u32 is the SEFH directory length only (data blocks precede the directory, addressed by backward offsets
  from the SEFH start), so no footer-based shortcut can locate the trailer start; recovering it would require the
  backward-offset directory parse. Dropping the trailer costs Revert on that save; shipping a truncated trailer whose
  directory offsets dangle into gain-map or primary bytes would corrupt the SEF chain, so the trailer is never
  re-appended partially.
- **CropCenter does not generate fresh SEFT trailers for new edits.** Samsung Gallery's Revert pre-flight validates the
  trailer's backup path against Samsung-blessed locations like `/data/sec/photoeditor/` that third-party apps cannot
  write to. A SEFT we generate pointing at our own writable shared-storage location is silently rejected by Gallery, so
  fabricating one would produce disk bloat for no Revert benefit. A file edited first in CropCenter (no prior SEFT)
  saves with no SEFT and no Revert option in Gallery — that's expected.

#### HDR Gain Map
- Extracted via forward JPEG marker walking — `JpegMarkerWalker.findPrimaryEoi` walks both primary's marker chain to
  find its EOI, then walks the gain map's own marker chain (in a `[primaryEnd, file.length-8)` slice when SEFT is
  present, else `[primaryEnd, file.length)`) to find ITS EOI. Forward marker-walking is required because a backward
  `FFD9` scan can land on a byte-stuffed `FFD9` inside SEFT data blocks (which can hold embedded thumbnails — themselves
  JPEGs ending in `FFD9` — and edit-history blobs).
- **HDR-source gate** (`HdrSignature.hasHdrgmInXmp` + `hasMpf` AND-gate): the gain-map extractor refuses to inspect
  post-primary FF D8 bytes unless the file carries BOTH an APP2 MPF segment (describes the multi-picture layout) AND the
  XMP `hdrgm` namespace marker INSIDE a parsed XMP APP1 segment (standard or extended). The XMP-restricted scan is
  critical — a full-file scan would falsely match a stray `hdrgm` 5-byte sequence in MakerNote / COM / vendor blob /
  SEFT edit history. The walk covers both the canonical 29-byte XMP_HEADER segment AND Adobe Extended XMP
  (`http://ns.adobe.com/xmp/extension/`) so a vendor whose hdrgm declaration spilled past the ~64 KB APP1 cap into an
  extension segment isn't mis-classified as SDR. MPF alone is insufficient because it also describes non-HDR
  multi-picture content (focus-stacked / panorama / ZSL bursts); without the combined gate, an SDR Samsung file whose
  SEFT data block begins with an embedded JPEG thumbnail's FF D8 would be mis-walked, with the thumbnail extracted as a
  "gain map" and the saved file's re-appended SEFT trailer truncated past the thumbnail's FF D9. The same combined gate
  drives `GraftWriter`'s per-side HDR detection so a graft of an SDR original doesn't synthesise a phantom gain map
  either. `HdrSignature.isHdrSource(byte[])` is retained as a SEPARATE post-compress diagnostic scanner used only by
  `UltraHdrCompat` to verify `Bitmap.compress` output emitted the marker — that scan operates on freshly-emitted JPEG
  bytes where the marker can only legitimately live in XMP, so the broader scan is safe and avoids re-parsing segments
  for a log line.
- Re-generated via canvas-based gainmap transform matching the primary
- Composed with primary via `GainMapComposer` + `MpfPatcher` for MPF offset correction
- **Fail-closed on Item:Length-in-Extended-XMP**: when `XmpItemLengthPatcher.patch` returns empty because the GContainer
  `Item:Length` attribute lives in an Adobe Extended XMP chunk (or straddles a chunk boundary that the per-chunk scan
  would miss, but the reassembled-bytes scan catches), patching across the per-chunk reassembly headers is beyond the
  patcher's scope. `GainMapComposer.compose` checks for the empty return and drops HDR rather than ship a file with
  stale `Item:Length` that strict decoders would interpret as a truncated gain map. Same metadata-strip path as the
  MPF-failure branch follows (see below).
- **Fail-closed on ambiguous standard-XMP Item:Length**: every `Item:Length` scan tolerates spec-legal XML whitespace
  around the attribute's `=` (a byte-literal `Item:Length=` match would report the spaced form as absent and append the
  gain map with a stale length — a silent fail-open). A standard-XMP packet carrying several `Item:Length` attributes
  (Google MotionPhoto XMP declares one per Container:Item) is patched only at the occurrence whose enclosing element
  carries `Item:Semantic="GainMap"`; when zero or more than one occurrence anchors, `XmpItemLengthPatcher.patch` returns
  empty and `GainMapComposer.compose` drops HDR rather than guess — a first-match rewrite could patch the Primary item's
  length and ship the gain map's stale one. Same metadata-strip path as the MPF-failure branch follows (see below).
- **Fail-closed on MPF anchor failure**: when `MpfPatcher.patch` returns false (no MPF segment, malformed/unsupported
  MPF, byte-order mismatch, 3+ image MPF without MPType match, **multi-gain-map MPF where >1 entry has MPType
  `0x010005`** — the spec-legal composite depth + Original Preservation case — negative relative offset, etc.),
  `GainMapComposer.compose` returns the XMP-Item:Length-patched primary (freshly allocated — see below) instead of
  shipping the gain-map bytes appended without an MPF entry pointing at them. The multi-gainmap refusal exists because
  we have one post-edit gain-map size + offset and no way to assign it across multiple slots — patching only the first
  match would leave others pointing at pre-edit positions in the source file. Strict decoders' Revert pre-flight would
  reject an unanchored gain map; lenient decoders that scan for the `hdrgm` signature would render with the wrong
  offset; and the save's "[HDR OK]" / "[HDR dropped]" toast (driven by the structural `hdrAttached` flag plumbed through
  `ExportResult` — see below) would falsely announce success. A clean SDR JPEG is strictly safer than orphaned HDR.
- **Structural HDR-OK signal**: `CropExporter.export` returns an `ExportResult(bytes, tempfile, hdrAttached)` record
  (dual-mode — exactly one of bytes/tempfile is non-null; see §Architecture "ExportResult dual-mode"); `hdrAttached` is
  sourced from `GainMapComposer.composeFileToFile`'s boolean return — an explicit flag set true ONLY when the composer
  actually appended the gain map AND patched MPF offsets to point at it. False on every drop path: UltraHdrCompat
  failure, MPF-patch rejection, malformed source MPF, or the strip-and-re-inject branch (below).
  `ExportPipeline.reportSuccess` consumes this flag directly to drive the "[HDR OK]" / "[HDR dropped]" suffix. The
  explicit flag is required because reference-inequality on the byte[] is unreliable (GainMapComposer returns the
  XMP-patched primary on the MPF-fail path, freshly allocated and distinct from the input primary) and a full-file
  `hdrgm` substring scan false-positives on preserved trailers, stale metadata, and Extended-XMP segments. The
  bypass-encode path reports `hdrAttached = srcHadHdr` since the source bytes carry the gain map intact.
- **HDR-drop metadata strip**: when `hdrAttached` is false but the source carried HDR metadata (UltraHdrCompat failure
  or MPF-patch reject), the saved JPEG would otherwise still carry source's XMP-`hdrgm` and APP2/MPF describing a
  now-missing gain map. `CropExporter.exportJpeg` re-injects metadata through `stripHdrSegments`, dropping APP2/MPF and
  any XMP segment — standard or extended — flagged by `HdrSignature.isHdrgmXmpSegment`. The per-segment predicate covers
  Extended XMP, which a standard-XMP-only check would miss.

#### ICC, XMP, MPF
- ICC profiles preserved as raw APP2 segments
- XMP with hdrgm namespace preserved from original
- MPF offsets recalculated after primary size changes
- `MpfPatcher` validates the MP Endian field as a full 2-byte pair (`II` / `MM`) rather than a single-byte check,
  locates the gain-map entry by walking entries for MPType `0x010005` ("Original Preservation") rather than hard-coding
  entry[1], refuses to patch 3+ image MPFs that have no MPType match (the entry[1] fallback is restricted to the 2-image
  Samsung Ultra HDR layout where the gain map is reliably at index 1 even when the MPType field is malformed), and
  rejects negative `relativeOffset` (`primarySize < mpfStart`) before it would otherwise be reinterpreted as a huge u32
  offset

### 11. Settings

Settings dialog (opened via the settings icon in the toolbar) is a single scrollable view holding up to five
alphabetically-ordered cards. Toggles and color-picker selections commit to `state.updateGridConfig` immediately as the
user interacts; the Cols / Rows EditText values are deferred to the "Done" button so a partial typed entry doesn't fire
a re-render mid-keystroke.

- **Build card** — single line `"Version: " + BuildConfig.BUILD_TIME` (the build's compile timestamp injected by
  `app/build.gradle`), used to verify which APK is installed on the device. The card heading is the literal string
  "Build" (not "Build / About") — no separate About / credits surface, and the displayed value is the compile timestamp
  rather than a manifest `versionName`.
- **Grid card** — Cols / Rows numeric inputs (IME-capped at 2 digits via `InputFilter.LengthFilter`, clamped to [1, 99]
  on Done so a stray 0 / negative resolves to 1), preset chips (2x2 / 3x3 / … / 8x8), line color (opens
  `ColorPickerDialog`), line width seek bar (1–20 px). Cross-references §7.
- **Permissions card** — tap target deep-linking to the system MANAGE_EXTERNAL_STORAGE settings page. The entire card
  panel is the tap target (LinearLayout-level `setClickable(true)` + `setOnClickListener`, with a
  `selectableItemBackground` foreground ripple) so taps land regardless of where inside the panel they fall. Only added
  when the host exposes a `StoragePermissionHelper` (omitted in test fixtures). `openStoragePermissionSettings` tries
  three intents in fallback order (per-app All-files-access toggle → system-wide All-files-access list → app details
  page) since heavily-skinned OEMs sometimes reject the per-app intent.
- **Pixel Grid card** — toggle for the dp-thresholded pixel-grid overlay (see §7), plus its color picker.
- **Selection & Paint card** — single shared color used for selection points, polygon fill, and horizon paint (kept
  separate from grid color so the paint surface stays visible against an arbitrary grid hue).

Tapping Settings while a save / load / detect / graft is in flight surfaces the busy toast instead of opening the dialog
(mirrors `SaveController.showSaveDialog`'s busy gate). The complementary already-open-dialog race is closed by the §1
"Transient-dialog forced dismissal" contract — `ImageLoadController.load` and `applyGraftedBytes` dismiss any open
Settings dialog before any bg work begins, so an inbound Share/View intent or graft apply visibly closes the dialog
rather than racing its in-dialog `state.gridConfig` commits against the bg `state.reset()`.

### 12. Apply External Edit (In-Memory Pixel Graft)

**Purpose**: Apply a small external pixel edit (typically Photoshop Generative Fill / Generative Remove) to a Samsung
Ultra HDR original while preserving the original's gain map and identity metadata (Make, Model, GPS, MakerNote,
DateTimeOriginal, lens info, SEFT trailer). When the source already carries a Samsung SEFT trailer (a previously
Gallery-edited file), Samsung Gallery's Revert button on the saved graft continues to work for that pre-existing Revert
chain; sources without a SEFT trailer (e.g., a fresh Samsung Ultra HDR capture, or any non-Gallery-edited JPEG) save
without Revert support — see the SEFT Trailer section for why CropCenter cannot fabricate fresh Samsung-blessed Revert
chains. The user picks an externally-edited copy of the loaded photo; CropCenter splices the edit's pixel content into
the original's metadata container, applies the result as the in-memory image, and saves through the canvas-encoded
pipeline so the output is colour-managed (Display P3) and viewer-compatible.

**Recommended editor: Photoshop, opened in pixel space (Camera Raw → File Handling → JPEGs → Disabled).** Photoshop
preserves the source pixels everywhere except the AI-edited region, with only ICC-encoding-level differences from the
original (mean per-pixel diff vs Samsung original ≈ 1 level after canvas P3 conversion; Lightroom's HDR-tone-mapped
output produces ≈ 13 levels and a visible tonal seam at the fill boundary). Other editors work if they meet the same
constraint — pixel-space editing, no global tone-curve shift.

**Why Revert works**: Samsung Gallery reads the `originalPath` value from the SEFT trailer's `PhotoEditor_Re_Edit_Data`
block and serves whatever JPEG it finds at that path. The graft preserves original's SEFT verbatim, plus original's MPF
segment shape (substituting Adobe's MPF reliably breaks Gallery's Revert pre-flight), so any backup chain the user
already had stays intact.

**Entry point**: Tap the toolbar **Graft** button (the merge-glyph icon between Open and Save).
Available only when (a) an image is loaded, (b) the loaded image is JPEG, (c) MANAGE_EXTERNAL_STORAGE is held (the
in-app picker needs it to enumerate the filesystem).

**Flow**:
1. User loads the original Samsung HDR JPEG (the metadata source) into CropCenter normally.
2. User taps the Graft toolbar icon.
3. In-app `OpenPickerDialog` (same dialog as the load flow) → user picks the external edit (the pixel donor). The picked
   File is converted to an externalstorage SAF URI via `SafFileHelper.buildExternalStorageDocumentUri` so
   `GraftController.onEditPicked` can read it through the existing SAF byte-reader path.
4. Validation (`EditAligner.align`):
   - Picked file is a JPEG (SOI = `FFD8`).
   - **Display** dimensions match between loaded and picked (stored dims after applying each side's EXIF orientation).
     Photoshop tends to write its export with the orientation flag normalized to 1, so the stored layouts may differ
     even when the visible pixels align — comparing display dims rather than stored dims accommodates that.
   - When stored layouts differ but display layouts match, the edit JPEG is decoded and re-encoded back to the
     original's stored layout via `Bitmap.compress(JPEG, 100)` before splicing. This adds ~1 channel-noise level to the
     edit pixels (same noise floor the save-time canvas pass would add anyway) but produces a graft whose primary scan
     is decoder-coherent against the original's EXIF orientation tag.
   - Mismatched display dimensions → toast `"Edit dimensions don't match the source (source WxH, edit WxH) — re-crop in
     the editor and re-export"` and abort (full toast format pinned in `EditAligner` and listed in
     **Failure modes** below).
5. **AI region detection** — `AiRegionDetector.detect` runs on the source/aligned-edit pair (only for HDR sources, since
   the mask is consumed only by `UltraHdrCompat.compressWithGainmap`). The detected mask travels with the graft into
   `state.installGraft` and drives the gain-map inpaint at save time.
6. **Sanity check** — when the AI mask flags more than 10% of pixels (`GraftController.LARGE_EDIT_FRACTION`), the user
   is shown a confirm dialog ("This edit changed about N% of pixels — much larger than typical for AI spot removal.
   Apply anyway?"). The feature targets small Generative Remove / Fill touch-ups (typical real cases at 0.001%-0.5%); a
   wholesale global edit (Lightroom tone curve, Photoshop colour grade) or a wrong-file pick would spike the fraction
   far past 10%, and the dialog turns the silent "graft an unrelated image into my metadata" footgun into a visible
   decision point.
7. `GraftWriter` splices the bytes in memory per the substitution rule below.
8. `MainActivity.applyGraftedBytes` calls `imageLoader.applyBytes(grafted)` which installs the splice as the new
   in-memory image. **Only on a successful decode** (applyBytes returns true) does it then call
   `state.installGraft(graft)` — installGraft writes the AI mask FIRST then sets `graftApplied=true` (which gates the
   verbatim-write bypass). The setAiMask-before-setGraftApplied order mirrors `reset()`'s clear order (aiMask=null
   FIRST, then graftApplied=false) so — **when an AI mask is present** — no observer catches the inconsistent
   `(graftApplied=true, aiMask=null)` transient pair on either side of the lifecycle.
   `UltraHdrCompat.compressWithGainmap` reading that pair mid-install would otherwise skip the inpaint while the
   verbatim-bypass gate was already firing, shipping a stale boost. The qualification matters because
   `(graftApplied=true, aiMask=null)` IS the steady state for SDR / no-mask grafts (see `CropState.installGraft`'s
   Javadoc) — `UltraHdrCompat.inpaintGainmapIfMasked` treats null aiMask as a no-op, so a no-mask graft observing that
   pair is correct, not a violation. A decode failure leaves the previously-loaded image intact and surfaces "Failed to
   decode" to the user. The user's saved AR preference applies to the post-graft crop the same way it does on a normal
   image load — if they want the full image, they pick "Full" from the AR chip's popup menu.
9. Toast "External edit applied" confirms a successful apply (fired only after `applyBytes` returns true, not when the
   splice is queued). Default save name = `<original-stem>-graft.jpg`, falling back to bare `"graft.jpg"` when the
   source has no display name available (typical of content URIs that don't expose `OpenableColumns.DISPLAY_NAME`).
10. User saves through the existing Save button. The save runs through `CropExporter.export` (the bypass is disabled for
    grafts), which canvas-renders the source onto a Display P3 bitmap, re-encodes via `Bitmap.compress(JPEG, 100)`,
    generates a fresh thumbnail, re-injects original's EXIF / XMP / MPF / SEFT (verbatim re-append, no fresh trailer),
    and appends the gain map (regenerated by `UltraHdrCompat.compressWithGainmap` with the AI-mask-driven inpaint
    applied).

**Substitution rule** (per-segment provenance — see `GraftWriter.SWAP_*` constants):

| Segment | From | Why |
|---|---|---|
| SOI | universal | always `FF D8` |
| APP0/JFIF | original | identity (density, version) |
| APP1/EXIF | **original** | identity: Make, Model, Software, MakerNote, GPS coordinates, DateTimeOriginal, lens info |
| APP1/XMP | **original** | preserves Samsung's HDR `hdrgm` metadata (matches original's gain map) |
| APP2/ICC | **original** | source's "DCI-P3 D65 Gamut with sRGB Transfer" profile matches the gain map's calibration; trusting the edit's ICC would mis-tag the spliced output (the reorient pass injects Skia's synthetic sRGB profile that describes Skia's container, not the actual P3-numerical pixels) |
| APP2/MPF | original (offset-patched) when the graft writes a gain map; **dropped** otherwise | Samsung-shape MPF is what Gallery's Revert pre-flight recognises; edit's MPF (Adobe-flavoured `MPType` for the gain-map entry) breaks Revert. When no gain map is written (a non-HDR original whose MPF describes a multi-picture layout — Samsung "Best Photo" burst, focus-stacked panorama — whose post-EOI secondary images are never copied into the grafted file), copying the MPF verbatim would leave it anchored at secondaries that no longer exist; strict decoders' MPF pre-flight rejects the orphan, so GraftWriter drops it |
| vendor APPs (APP3-APP15), COM | original | Samsung sensor hints, scene labels |
| DQT, DHT, SOF, SOS+scan, EOI | **edit** | the AI-edited pixels — byte-verbatim from edit's primary |
| gain map JPEG | **original, AI-region inpainted at HDR re-encode time** | preserves Samsung's HDR rendering across the unedited area; the AI region's gain values are replaced with their unmasked-neighbor average so the boost in the fill matches its surroundings instead of the original (now-removed) features. Inpaint runs against the gain-map Bitmap inside `UltraHdrCompat.compressWithGainmap` (not against the JPEG bytes at splice time) so the single-channel grayscale container survives the save's `Bitmap.compress` call |
| SEFT trailer | original | only present when source carried one; the verbatim re-append is the sole reason Gallery still surfaces and services Revert on that source — see SEFT Trailer subsection for the no-fresh-trailer policy |

**Saved output**: the canvas-encoded pipeline runs for **every** graft save (no-crop and cropped alike —
`state.isGraftApplied()` disables the bypass). Output structure:
- Primary JPEG: re-encoded from the canvas-rendered bitmap at quality 100, in Display P3 colour space
- EXIF / XMP / ICC / MPF / SEFT: re-injected from `state.jpegMeta` (= original's segments, with dimensions / orientation
  patched and a fresh IFD1 thumbnail)
- Gain map: regenerated by `UltraHdrCompat.compressWithGainmap` so it stays spatially aligned with the canvas-rendered
  primary

The canvas conversion is near-identity in colour terms: `graftedBytes` already carries source's "DCI-P3 D65 Gamut with
sRGB Transfer" ICC, the Bitmap canvas is `DISPLAY_P3` (same chromaticities, same D65 white point, same sRGB transfer),
and the metadata-injection step writes source's ICC bytes back over Skia's Bitmap.compress profile. The pass exists for
spatial correctness (regenerating the gain map for the spliced primary, baking any user crop), not for colour
management.

**Why each substitution choice** (validated by visual inspection of saved + cropped outputs and Samsung Gallery Revert
testing):

- **EXIF (`SWAP_EXIF=false`)**: preserves Samsung MakerNote (lens info, sensor settings, scene metadata), GPS
  coordinates, DateTimeOriginal. Substituting edit's EXIF would lose all of these.
- **XMP (`SWAP_XMP=false`)**: preserves Samsung's HDR metadata (`hdrgm` namespace) which describes original's gain map.
  Edit's XMP describes the editor's gain map — incoherent with the kept original gain map.
- **ICC (`SWAP_ICC=false`)**: keeps source's "DCI-P3 D65 Gamut with sRGB Transfer" profile. The recommended editor
  (Photoshop with Camera Raw disabled) preserves source's pixel values verbatim outside the AI fill, so the edit's
  pixels are P3-numerical even when no ICC tag is written. When `EditAligner.reorientEdit` re-encodes the edit through
  `Bitmap.compress` to fix a stored-layout mismatch, Skia injects its own synthetic 456-byte sRGB profile — that ICC
  describes Skia's container, not the actual pixel encoding. Trusting it would tag the spliced output as sRGB while the
  pixels remain P3-numerical and the gain map is calibrated for P3, producing washed-out HDR composition.
- **MPF (`SWAP_HDR_MPF=false`)**: confirmed via bisection — substituting edit's MPF segment alone reliably hangs Samsung
  Gallery's Revert. Original's MPF stays Samsung-shape; only its offset/size fields are patched after the gain-map
  splice. The preserve applies only when the graft writes a gain map — for an MPF-without-gainmap original the segment
  is dropped (orphan-MPF rule in the substitution table above), since the multi-picture secondaries it anchors are never
  copied into the grafted output.
- **Gain map (`SWAP_HDR_GAINMAP=false`, AI region inpainted at save time)**: preserves Samsung's HDR rendering across
  the unedited area (≈ 99.99% of the frame for typical Generative Remove fills). Inside the AI region the gain map's HDR
  boost was calibrated for the original (now-removed) content and would mis-target the new fill — `AiRegionDetector`
  locates that region (diff source vs aligned-edit at sampleSize=4) when the graft is applied and stashes the mask on
  `state.aiMask`. `GainMapInpainter` then runs at HDR re-encode time inside `UltraHdrCompat.compressWithGainmap`,
  mutating the source's gain-map Bitmap in place via frontier-tracked grow-from-boundary (each masked pixel becomes the
  average of its unmasked 8-neighbors). The fill ends up with the same HDR boost as its surroundings, which is exactly
  what Generative Remove visually intends ("this region should look like its neighbors"). Inpainting at save time
  (rather than at graft time on the gain-map JPEG bytes) is critical — Samsung's gain map is single-channel grayscale,
  and `Bitmap.compress` always emits 3-channel YCbCr 4:2:0 regardless of input config, so a graft-time JPEG round-trip
  would force a structural format change that downstream UHDR decoders silently reject (HDR drops). Operating on the
  Bitmap in place from `Gainmap.getGainmapContents()` keeps source's single-channel format intact through the save's
  `Bitmap.compress(JPEG)` call, which preserves the gainmap's container format when the bitmap has an attached
  `Gainmap`. **Supported gain-map Bitmap.Config values** are `ALPHA_8` (Samsung's typical single-channel format) and
  `ARGB_8888` (Adobe's variant). Any other config (e.g. `RGB_565`, `RGBA_F16` from a future Android version) hits the
  inpaint dispatcher's no-op-with-warn-log branch and ships the source gain map untouched — silently downsampling
  unfamiliar pixel formats through 8-bit `getPixels` / `setPixels` would corrupt the boost values, so the safe default
  is to pass the gain map through verbatim. `HARDWARE` config is short-circuited earlier by the `!isMutable()` guard
  since HARDWARE bitmaps are always immutable.
- **Vendor APPs (`STRIP_VENDOR_APPS=false`)**: confirmed no rendering effect; Samsung sensor / scene identity data
  preserved.

**`ExportPipeline.canBypassEncode`**: implements the no-edit bypass gate — see §9 No-edit bypass for the full predicate
list and the graft / IFD1-thumbnail exclusion rationale.

**`MainActivity.applyGraftedBytes`**: after `imageLoader.applyBytes` returns true, calls `state.installGraft(graft)`
which atomically sets `graftApplied=true` and stashes the AI mask. `canBypassEncode` then returns false for this image
so the save runs through the full encode + gain-map regeneration. Both fields are cleared by `CropState.reset()` on the
next image load. The user's AR preference is left alone — graft behaves like a normal image load with respect to the
crop overlay.

**Validation that the splice is decoder-coherent**: both inputs must share **display** dimensions (stored dims after
applying each side's EXIF orientation). `EditAligner.align` checks via `BitmapFactory.decodeByteArray(inJustDecodeBounds
=true)` for stored dims and `BitmapUtils.readExifOrientation` for orientation. When stored layouts differ but display
layouts match, the edit JPEG is auto-reoriented via `EditAligner.reorientEdit` (decode + `Bitmap.compress(JPEG, 100)`)
into the original's stored layout before splicing — there is no separate "orientation differs" abort path.
Display-dimension mismatch is the only refusal trigger at this stage.

**Failure modes**:
- Picked-File→SAF-URI conversion fails (`buildExternalStorageDocumentUri` returns empty — picked file outside primary
  external storage) → toast "Couldn't resolve picked file to a graft URI" and the graft session cancels
  (`onEditPickerCancelled`).
- Picker dimension probe fails — three distinct failure modes with distinct messages so the user gets actionable
  remediation:
  - SOI signature mismatch (HEIC / WebP / PNG bytes leaked past the picker's MIME filter) → toast "Selected file is not
    a JPEG", abort.
  - Source-side BitmapFactory dim probe comes back empty (memory pressure / bg-write race nulled the previously-loaded
    source bytes) → toast "Source image is corrupt — reload it", abort.
  - Edit-side BitmapFactory dim probe comes back empty (corrupt edit JPEG past valid SOI) → toast "Couldn't decode the
    edit — try exporting again", abort.
- Display dimensions don't match → toast `"Edit dimensions don't match the source (source WxH, edit WxH) — re-crop in
  the editor and re-export"`, abort.
- Re-orient re-encode fails (BitmapFactory rejects the edit bytes mid-reorient — same failure mode as the edit-side
  dim-probe-failure case, so the message routes to the same remediation) → toast "Couldn't decode the edit during
  reorientation — try exporting again", abort.
- Loaded image isn't JPEG → toast "Apply External Edit only works on JPEG sources" (refused at btnGraft tap time, picker
  never opens).
- GraftWriter splice fails (edit isn't a JPEG, missing SOI / primary EOI, malformed segments) → toast "Graft failed:
  <reason>", in-memory image unchanged.
- Decode of grafted bytes fails → toast "Graft produced an undecodable result — apply aborted" (more specific than the
  load-flow's "Failed to decode" so users distinguish a graft-pipeline regression from a corrupt source;
  developer-facing breadcrumb at `Log.w(TAG, "graft splice produced undecodable bytes...")`), in-memory image unchanged.
- SAF read of the picked edit URI fails (provider permission denial, IOException, fewer than 4 bytes returned) → toast
  "Couldn't read picked edit", abort. Fires before any decode / dimension probe runs.
- Source snapshot bytes lost between btnGraft tap and picker return (rare; can happen on memory pressure that nulls the
  captured `originalBytes`) → toast "Original bytes unavailable — reload the image and try again", abort.
- Source bytes null at btnGraft tap time (state.originalFileBytes was nulled between load and the tap) → toast "Original
  bytes unavailable — reload the image" (no "and try again" suffix), abort. Distinct from the similarly-worded toast
  above which fires DURING `assembleGraftOnBg` after the snapshot has been claimed; this one fires earlier in
  `GraftController.start()` before any picker opens.
- Generic exception from `applyBytes` / `installGraft` in `MainActivity.applyGraftedBytesOnBg` (post-isDestroyed races,
  OOM during the bitmap install, any other RuntimeException reaching the catch) → toast "Apply failed: <message>",
  abort. Distinct from the SOI / decode / dimension errors above which surface earlier in the pipeline; this catches
  anything that escapes past the graft splice.

**Verification**: save the grafted file, open in Samsung Gallery, and — when the source carried an existing SEFT Revert
chain — confirm the Revert button still appears and restores to the pre-graft state. For sources without a prior SEFT
trailer, Revert will not appear; this is expected (see the SEFT Trailer section). Inspect the saved file's EXIF in any
external tool (exiftool, ImageMagick `identify -verbose`) to confirm GPS coordinates, MakerNote, and other camera tags
are preserved.

**Out of scope**: PNG inputs (SEFT is JPEG-specific). HEIC inputs (different metadata system). Differing-dimension edits
(would need re-encode; refused with toast). Per-region gain-map *regeneration* — i.e. synthesizing fresh HDR boost
values from the AI-fill pixel content. Per-region gain-map *inpaint* IS implemented: `AiRegionDetector` locates the
AI-edited region (source vs aligned-edit diff at sampleSize=4) and `GainMapInpainter` fills that region of the source's
gain map with the average of unmasked 8-neighbors at HDR re-encode time, so the AI region no longer reads through
original's pre-fill HDR boost (which boosted features that the AI fill no longer contains). Regeneration would require a
model that derives HDR ratios for arbitrary fill content — well outside the splice scope. Mask-based selective composite
(preserve source bytes outside the AI region — useful for editors that produce larger tonal shifts than Photoshop,
currently not needed because Photoshop is the recommended editor and produces minimal tonal shift).

**Iterative graft compounding**: each save runs the canvas re-encode pass (~1 level mean per-channel diff per cycle); N
load-graft-save cycles compound to ~N levels. Workaround: do all AI fills in one Photoshop session against the original
Samsung JPEG, then graft once — multi-fill in one session adds no compounding. Noise is invisible below ~5 cycles, so
lossless MCU-level transcoding (~500 LSLOC + libjpeg-turbo NDK) isn't implemented.

---

## UI Theme

**Color palette**: Catppuccin Mocha (dark theme)

| Element | Color |
|---------|-------|
| Background | #111318 |
| Sidebar | #1E2030 |
| Primary accent | #CBA6F7 (mauve) |
| Text | #CDD6F4 |
| Subtext | #A6ADC8 |
| Surface | #313244 |
| Active point / crop border | #CBA6F7 (mauve) |
| Zero rotation marker | #F38BA8 (red) |
| Selection point | Selection/paint color (`GridConfig.selectionColor`, configurable) |

---

## Java 21 Features Used

- **Records**: `model/AspectRatio`, `model/ExportConfig`, `model/GridConfig`, `model/SelectionPoint`, `model/Graft`,
  `metadata/JpegSegment`, `metadata/ExtendedXmpReassembler.ExtendedXmpChunk`, `metadata/GainMapComposer.ComposeResult`,
  `metadata/XmpItemLengthPatcher.SegmentPatchResult`, `util/CropFitContext`, `crop/ExportResult`,
  `util/AiRegionDetector.AiMask`, `view/RotationRulerView.TickConfig`, `graft/EditAligner.Result`,
  `GraftController.SourceSnapshot`, `ReplaceStrategy.VerifyFailure`, `ExportPipeline.WriteOutcome`,
  `SaveController.PriorSaveSnapshot`, `ImageLoadController.MetadataExtraction`, `view/FolderPickerDialog.SaveChoices` —
  immutable value types replace boilerplate POJOs. (`AspectRatio` is a record carrying named-constant instances for the
  preset ratios — not an enum, since custom AR values are first-class instances too.)
- **Enums**: `model/Format` (JPEG / PNG with `extension()` and `mimeType()`), `model/CenterMode`, `model/EditorMode`
- **Switch expressions**: Arrow syntax throughout (`ExifPatcher`, `BitmapUtils`, `CropExporter`, `RotationRulerView`)
- **`Math.clamp`**: used wherever a value needs `[lo, hi]` clamping instead of hand-rolled `max(min(...))`
- **`var`**: used sparingly where the right-hand-side type is obvious
- **Pattern matching for `instanceof`**: `FolderBrowser` (`holder instanceof FileListViewHolder listVh && item
  instanceof FileRow row`) — used where the cast would otherwise be redundant boilerplate
- **`InputStream.transferTo` / `readNBytes`**: stdlib I/O in `SafFileHelper.copyUriContents` and `readUriBytes` instead
  of hand-rolled byte-loop / partial-read accounting

Canonical helpers (single chokepoints for recurring patterns) are listed in CLAUDE.md > Constants > Canonical helpers.

---

## Code Organization

The full coding-style contract — Allman braces, tabs-only indent, 120-column wrap, field / method ordering, naming
conventions, Javadoc completeness rules, and the canonical-helper list — lives in `CLAUDE.md`. Mechanical enforcement
runs through `scripts/audit.py` (subcommands `over-cols`, `over-cols-py`, `ignored-catches`, `static-first`,
`method-order`, `adjacent-comment-styles`, `final-classes`, `reflow`, `lsloc`; no-arg form runs all); the additional
grep / awk checks in CLAUDE.md's Self-audit section cover the bits the script doesn't (double-indents, inline FQNs,
dp-px truncation, HTML entities, etc.).

---

## Known Limitations

1. **Canvas re-encoding**: `Bitmap.compress()` re-encodes the JPEG, changing quality and file size vs. original.
2. **PNG metadata**: Only EXIF is injected (via eXIf chunk). Other PNG ancillary chunks are not preserved. HDR is not
   possible in PNG format.
3. **Single image**: Only one image can be open at a time.
4. **Large files**: Files > 128MB are rejected (`SafFileHelper.MAX_READ_BYTES`). Entire file is read into memory.
   Local-filesystem paths (the common MediaStore case) read directly via `tryReadDirectlyFromPath`'s `FileInputStream` —
   no temp file, immediate oversize-fail when `File.length()` already exceeds the cap. SAF / cloud URIs without a
   readable filesystem path fall back to a per-call `createTempFile` cache copy (so concurrent loads don't clobber each
   other) that streams up to the cap before rejecting. Sources whose decoded pixel count exceeds the **static cap**
   `BitmapUtils.MAX_DECODE_PIXELS` (256 MP, ~1 GB ARGB) are subsampled at decode time — see the
   `BitmapUtils.computeInSampleSize` Javadoc for the power-of-2 sampleSize contract. The cap handles Samsung's "200 MP"
   capture mode (16384×12288 = 192 mebipixels) at `inSampleSize=1` with comfortable headroom for future 256 MP sensors.
   The bg-thread load ALSO derives a **display proxy** via `BitmapUtils.createDisplayProxy` that caps the editor-render
   bitmap at `BitmapUtils.MAX_DISPLAY_PIXELS` (16 MP, HARDWARE config); the proxy stays in GPU memory for zero-upload
   per-frame draws while the source feeds the save path. A per-axis cap `BitmapUtils.MAX_PROXY_AXIS` (16384 px) sits
   alongside the pixel-count cap to defend against pathological aspect ratios (e.g. 1×100M attacker input) that would
   otherwise produce a 1×16M proxy past the GPU's `GL_MAX_TEXTURE_SIZE`. At zoom ≥ 4 the renderer shows 1:1 source
   pixels for pixel-grid accuracy on any source. When the whole source fits a third cap
   `BitmapUtils.MAX_SOURCE_RENDER_PIXELS` (64 MP) AND each axis fits `BitmapUtils.MAX_SOURCE_RENDER_AXIS` (16384) it
   uploads the entire source as one texture; past either cap (e.g. a 200 MP capture) it draws only the visible source
   region as a viewport-bounded tile cut from the software-ARGB source — 1:1 crisp without ever handing the GPU an
   over-budget texture (a stay-on-the-proxy fallback would leave 200 MP captures un-peekable at zoom ≥ 4). The
   tile is re-extracted only when the visible region (under the current rotation) leaves the cached tile's margin, so
   steady-state panning allocates nothing. Auto-rotate's painted-region detection reads pixels via
   `AutoRotateBinder.tryReadbackArgb`, a HARDWARE→ARGB_8888 readback (HARDWARE bitmaps return null from `getPixels()`)
   wrapped in `RuntimeException | OutOfMemoryError` so Skia / GPU readback faults surface as clean "Horizon detection
   failed" toasts rather than escaping the bg job and stranding busy/progress. Enforced at the consistent-subsampling
   decode sites (`ImageLoadController.applyBytes` at load, `UltraHdrCompat.decodeHdrBitmap` at HDR-save re-decode);
   `EditAligner.reorientEdit` deliberately bypasses the cap because `GraftWriter.graft` splices the re-encoded edit's
   primary scan into the original's full-resolution EXIF / MPF / gainmap / SEFT package — a downsampled edit primary
   would disagree with the full-resolution metadata describing dimensions and gainmap offsets, silently misaligning the
   HDR gainmap on a Samsung Revert chain. For a 200 MP source + edit pair, `reorientEdit` catches `OutOfMemoryError` and
   surfaces the "Couldn't decode the edit during reorientation" toast. Trade-off on the load path: saved-crop output of
   a subsampled source is at the subsampled resolution — full-res in-memory work on 200 MP sources exceeds any current
   Android device's heap so the visible loss is theoretical (no Android display can render 200 MP anyway). CropExporter
   still preserves source-level metadata so the subsampled output remains structurally complete.
5. **MediaStore owner on plain Save As**: `ACTION_CREATE_DOCUMENT` to a different directory creates a new file with a
   different MediaStore owner. Same-directory same-name saves route through the Replace flow which preserves the
   original document where the provider supports it.
6. **Samsung Revert** only works for files that came in with an existing SEFT trailer (a Gallery-edited file re-edited
   in CropCenter). Files first edited in CropCenter save without Revert support — see §10 SEFT Trailer for the
   no-fresh-trailer rationale.
7. **EXIF thumbnail overflow**: If original EXIF metadata + new thumbnail would exceed the 65535-byte APP1 limit,
   thumbnail is reduced or dropped. `oldThumbLen` is sanity-clamped against `data.length` to prevent malformed source
   EXIF from inflating the budget calculation.
8. **Bitmap not persisted across process death**: Activity declares `configChanges="orientation|screenSize"`, which
   covers device rotation without an Activity recreate. Other configuration changes (`density`, `uiMode` dark/light,
   `locale`, `fontScale`, `layoutDirection`) trigger a recreate, AND a low-memory kill (force-stop, OOM eviction) ends
   the process entirely. `MainActivity.onSaveInstanceState` persists the source URI plus the user's editing geometry
   (AR, center, cropW/H, anchor, rotation, editor mode, center mode, per-mode lock-axis prefs, selection points) so a
   kill mid-edit doesn't lose alignment work — restore on the next `onCreate` re-fetches bytes via
   `imageLoader.load(savedUri)` and `installImageOnUi` replays the geometry via `applyRestoreBundle`. Restored crop
   dimensions are re-validated against the freshly loaded image by the same corner geometry the interactive pipeline
   enforces (`RestoreController.restoredCropFits`: a rotated-AABB pre-filter, then `RotatedCropClamp.cornersInside` at
   the clamped restored center within the pipeline's ±0.5 px slack): if the source file changed between kill and
   restore so that the crop's corners no longer fit inside the rotated image, the persisted size is discarded and the
   crop size is left dirty — the post-restore recompute then derives the maximum fitting crop at the restored
   (clamped) center, the same clamp interactive gestures get, so a crop whose corners fall outside the rotated source
   never persists (an AABB check alone would accept one whenever the restored rotation is non-zero). The center itself
   always restores through the clamping `setCenter`. The bitmap, originalFileBytes, gain map, SEFT
   trailer, and PNG/EXIF metadata are NOT in the Bundle — they reload from the source URI. A source whose
   persistable-read permission was revoked between save and restore (rare; SAF typically only revokes on explicit user
   action) is not recoverable. **Graft-session exception**: when `state.isGraftApplied()` is true (the user has applied
   an external edit via Apply External Edit), `RestoreController.writeTo` writes NOTHING to the bundle and the next
   launch cold-starts. The graft bytes live only in memory (the original source URI still points at the pre-graft file
   on disk); restoring from that URI would silently reload the pre-graft image and replay the geometry against it,
   presenting it as if the external edit were still applied. The user would then save a crop of the original instead of
   the edit. Cold-starting is the lesser surprise — the user loses the in-progress session but doesn't accidentally save
   the wrong image.
9. **Opaque-ID providers**: Providers without document-ID path encoding (some cloud / SD-card providers) lose the
   strongest collision-detection paths. The Save flow trusts SAF auto-rename as collision evidence on those providers —
   false positives surface as a Replace dialog the user can dismiss with Keep, never as silent data loss.
10. **Merged in-app save dialog is primary-external-storage only**: `SafFileHelper.buildExternalStorageDocumentUri`
    returns empty for paths outside `Environment.getExternalStorageDirectory()` (secondary SD card, USB OTG, cloud
    targets). The save dispatches `onSaveCancelled` with a "Picked folder isn't on primary storage" toast in that case;
    users wanting to save to a secondary volume should revoke MES to fall back to the legacy SAF picker, which honors
    SD-card / USB targets through `ACTION_CREATE_DOCUMENT`.
11. **New-file collision refusal is best-effort at the kernel level**: a save classified NEW-FILE refuses a target
    that appeared after classification through two gates in `ReplaceStrategy.promoteTempOntoTarget` — a userspace
    existence probe immediately before the move, then the move itself run without `REPLACE_EXISTING` so the
    filesystem's own existence check refuses via `FileAlreadyExistsException`. The "(N)" promotion rename
    (`promoteToVisibleName` → `retryPromotionOntoFreeSibling`) carries the same no-`REPLACE_EXISTING` refusal, with
    a retry onto the next free candidate. That check and the underlying rename
    are still not one atomic operation, so a file created by another process in the final microseconds-wide window
    can be replaced. Closing it entirely needs `renameat2(RENAME_NOREPLACE)`, which `Files.move` does not expose;
    accepted as a residual — it requires a concurrent writer racing the exact same filename in the same folder
    inside the syscall window, and the refusal path already covers every userspace-observable appearance.
