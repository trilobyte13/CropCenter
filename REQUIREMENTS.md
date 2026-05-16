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
**LSLOC**: 14,186 total (7,801 main + 6,385 test) — UCC-style logical SLOC via `scripts/audit.py lsloc` (counts
`;`-terminated statements, control-flow openers, type declarations, and method signatures; excludes blanks, comments,
and bare-brace-only lines — the latter is a substantial chunk of Allman-style code and was previously inflating the
count by ~30%). **Numbers must be exact** — every change that adds, removes, or restructures Java code must refresh
the count via `python scripts/audit.py lsloc` and update this line in the same commit. No tolerance band; the spec
matches the codebase or it's a drift bug.

---

## Architecture

### Single Activity Layout

```
+--------------------------------------------------------------------+
| Toolbar: [AR Spinner] [Grid][Pan][Lock]    [Settings][Open][Save] |
+--------------------------------------------------------------------+
|                                                                    |
|              CropEditorView (flexible height)                      |
|     Image + crop overlay + grid + selection points                 |
|                                                                    |
+--------------------------------------------------------------------+
| [Select][Move]  [Both][H][V]  [Undo][Redo][Clear]  [Auto]         |
+--------------------------------------------------------------------+
| Rotation ruler (scrollable, Galaxy-style) [zoom -/+]               |
+--------------------------------------------------------------------+
| Info: image size | crop size | rotation | zoom                     |
+--------------------------------------------------------------------+
```

The mode buttons, lock-axis row, undo/redo/clear, and Auto-rotate live on a single consolidated row
(`pointControlsRow` in the layout). Auto is hidden until an image is loaded; Undo/Redo/Clear are hidden in Move
mode.

The toolbar's `Pan` checkbox is the freeze-crop ("CenterMode.LOCKED") gate — when on, drags pan the viewport regardless
of mode. The `Lock` checkbox suppresses the selection-point auto-recompute in Select mode so points can be added or
removed without the crop relocating. `Settings` opens the combined Settings dialog (see §11 for the full card layout —
Grid, Pixel Grid toggle + color, Selection & Paint color, Build).

**Permissions**: `MANAGE_EXTERNAL_STORAGE` only. Required for the File-I/O strategy in collision-overwrite Replace
(`ReplaceStrategy` strategy A) and for the Samsung MediaStore EXIF workaround (`SafFileHelper.tryReadDirectlyFromPath`).
The grant prompt is offered from the Replace failure dialog when the permission is missing rather than at app start —
ordinary Save As flows don't require it. `ACCESS_MEDIA_LOCATION` is NOT declared (would only matter for
`MediaStore.setRequireOriginal`, which the codebase doesn't use — it reads via SAF directly).

### Key Components

| Component | Class | Purpose |
|-----------|-------|---------|
| State | `model/CropState` | Central state: crop params, metadata, rotation anchor (stable intent center for no-selection rotations). Cross-thread fields are volatile so the bg load/graft/save executor can publish to the UI thread without a lock: `sourceImage`, `selectionPoints`, `jpegMeta`, `aiMask`, `graftApplied` (read by `ExportPipeline.canBypassEncode` on the UI thread, written by `installGraft` on bg). `sourceImage` was added to match the sister fields — UI-thread `EditorRenderer.draw` reads it on every frame while bg-thread `reset()` writes it during load; the per-frame snapshot fix in EditorRenderer is necessary but not sufficient without volatile. `pngExifTiff` (raw TIFF for PNG → PNG export) is also volatile but currently bg-only — set by `extractMetadata` on bg, consumed by `exportPng` on bg via the same single-thread executor; the volatile is preventive rather than load-bearing for now. |
| State Dispatch | `model/StateBus` | Listener-dispatch + batch-suppression protocol extracted from CropState. `bus.beginBatch / endBatch` lets the Activity wrap recomputeCrop + UI updates so inner setter calls coalesce into one listener invocation |
| Output Format | `model/Format` | Enum (`JPEG` / `PNG`) carrying MIME type + file extension. Replaced earlier `String FORMAT_JPEG` constants — gives compile-time exhaustiveness on the export-pipeline switch and removes "any string flows through to default" foot-gun |
| Crop Math | `crop/CropEngine` | Computes crop from center + AR + lock + rotation; keeps cropX continuous mid-rotation, with parity-snap applied at drag-release in `CropEditorView.onPanRelease` |
| Rotated Clamp | `crop/RotatedCropClamp` + `crop/CropFitContext` | Clamp candidate crop centers against a rotated image's bounds via 25-iter binary search. CropFitContext bundles the pre-computed sin/cos/halfWidth/halfHeight that the binary search and corner-check share, replacing an 11-parameter signature |
| Render Geometry | `crop/CropRender` | Final class bundling (centerX, centerY, cropW, cropH, imgW, imgH, rotation) + derived `srcX()` / `srcY()`. Private constructor + public `of(...)` factory in (W, H) order — record-style storage swapped for a factory-only API after the canonical positional constructor's (H, W) alphabetical order was identified as a transposable footgun against the codebase's (W, H) convention. Threaded into `UltraHdrCompat.compressWithGainmap` (was a 12-arg method) |
| Export Result | `crop/ExportResult` | Record bundling encoded bytes + structurally-derived `hdrAttached` flag, returned by `CropExporter.export`. Threaded through `ExportPipeline.encodePhase` to `reportSuccess` so the [HDR OK] / [HDR dropped] toast is driven by `GainMapComposer.ComposeResult.hdrAttached()` — an explicit boolean set true only when the gain map was successfully appended AND MPF offsets were patched to point at it — rather than the old reference-inequality heuristic (which broke once GainMapComposer started returning the XMP-patched primary on the MPF-fail path, distinct from the input array, and falsely fired true) or a full-file substring scan (which false-positives on preserved trailers and Extended-XMP segments) |
| Horizon | `util/HorizonDetector` | Auto-rotation: metadata pass first, fallback to painted-region Hough transform |
| Export | `crop/CropExporter` | Full pipeline: crop, rotate, compress, HDR, EXIF, SEFT |
| Editor | `view/CropEditorView` | Custom View: rendering + gestures + undo/redo |
| Gestures | `view/TouchGestureHandler` | Pinch zoom, tap, drag, long-press; emits onPanRelease for parity-snap on drag end |
| Grid | `view/GridRenderer` | Grid overlay with line positions snapped to integer image pixels (matches `CropExporter.gridLinePixel`) |
| Rotation | `view/RotationRulerView` | Galaxy-style scrollable ruler with snap-to-detent and pinch-to-zoom scale |
| Color Picker | `view/ColorPickerDialog` | Tap-to-select grid + alpha + hex input |
| Settings | `view/SettingsDialog` | Combined dialog: grid config (cols, rows, presets 2x2–8x8, color, width), pixel-grid toggle/color, selection/paint color, Build (build-time version) |
| Save Dialog | `view/SaveDialog` | Format (JPEG / PNG) + export-grid bake-in toggle. Filename / target directory are picked separately by the SAF `ACTION_CREATE_DOCUMENT` picker that follows |
| Save Flow | `SaveController` + `ReplaceStrategy` + `ExportPipeline` | SAF picker routing, collision detection (auto-rename + sibling-create), crash-safe write-then-swap |
| Load Flow | `ImageLoadController` | Bg-thread decode + EXIF orientation + metadata extract for SAF URIs (`load(Uri)`), Share/View intents (`handleIncomingIntent`), and in-memory graft bytes (`applyBytes(byte[], String)`). Owns the busy-release-in-finally + progress-overlay-hide contract |
| Apply-Edit Flow | `GraftController` + `graft/EditAligner` + `metadata/GraftWriter` | Long-press-Open → SAF picker → validation (display-dim match) → optional re-orient → AI-region detect → byte splice → in-memory apply. Owns its own state machine (`graftPending`, `pendingSource` snapshot) |
| Toolbar / AR Spinner | `ToolbarBinder` | AR spinner population, custom-AR dialog, mode/lock-axis row wiring, precise-rotation dialog. Extracted from MainActivity to keep the Activity focused on lifecycle and host-interface implementations |
| Auto-Rotate Button | `AutoRotateBinder` | Wires the Auto button in the Points row to `HorizonDetector` (metadata pass + painted-region fallback) and posts the toast outcome |
| Editor Render Pipeline | `view/EditorRenderer` + `view/ViewportMath` + `view/GridRenderer` + `view/HorizonPaintOverlay` + `view/SelectionHistory` + `view/DialogCards` + `view/DialogStrings` | onDraw delegate (rendering only, no state mutation), screen↔image transform helper, grid render, horizon-paint overlay, undo/redo storage (50-step), shared dialog-card styling, shared dialog button labels (Apply / Cancel / OK) |
| Host interfaces | `EditorHost` / `ImageLoadHost` / `SaveHost` / `UiHost` / `ToolbarHost` + `UiSync` | Capability-typed views the controllers and binders see of MainActivity. `UiSync` collects the per-state-change UI refresh methods (toolbar / progress / dialog reactions) so MainActivity owns the wiring but the response code lives in one cohesive collaborator. Listener registration on `CropState` happens directly in MainActivity.onCreate — UiSync doesn't broker that step. |

### Metadata Pipeline

| Class | Purpose |
|-------|---------|
| `metadata/JpegMetadataExtractor` | Extract all APP/COM segments from JPEG header |
| `metadata/JpegMetadataInjector` | Replace re-encoder's APP markers with originals |
| `metadata/ExifPatcher` | Update orientation, dimensions, and IFD1 thumbnail in EXIF. Four-state thumbnail contract on `patch(...)`: (1) `null` preserves the source's IFD1 (only safe when saved pixels equal source pixels), (2) `byte[0]` / `STRIP_IFD1_THUMBNAIL` strips IFD1 by zeroing IFD0's next-IFD pointer (used as the fail-closed fallback when fresh thumbnail generation fails), (3) `byte[N>0]` replaces with the supplied JPEG bytes, and (4) when the input segment list contains no EXIF segment at all (screenshots, generated images, files re-encoded by minimal tools), `patch` synthesises a fresh APP1 EXIF via `buildMinimalExifSegment` so the freshly-generated thumbnail still lands in the saved file. Replace path also has a fallback chain: when `spliceExistingThumbnail` rejects the rebuild (missing thumb tags, out-of-bounds offsets, oversized cap), `replaceThumbnail` tries `appendFreshIfd1WithThumbnail` against IFD0's existing next-IFD slot before strip-on-fail. **IFD0 sanitisation** inside `scanIfd` (depth=0 only): a `Compression` / `JPEGInterchangeFormat` / `JPEGInterchangeFormatLength` tag found in IFD0 (corruption from earlier Samsung Gallery edits or pre-fix CropCenter outputs that hoist IFD1 tags up into IFD0) gets its entire 12-byte entry zeroed so strict TIFF parsers don't follow stale offsets. Public predicate `hasIfd1Thumbnail(segments)` walks IFD0 → next-IFD → IFD1 → JPEGInterchangeFormat looking for a parse-reachable thumbnail offset; consumed by `ExportPipeline.canBypassEncode` to force re-encode when the source has no pre-computed thumbnail. Two budget-prediction methods serve distinct invariants: `patchedNonThumbBytes(meta)` returns the **byte-exact** post-patch non-thumbnail segment size (used by `CropExporter.buildEmbeddedThumbnail` for JPEG export; mirrors `patch`'s splice / append / synthesise decision tree); `maxThumbnailBytes(meta)` returns the older **estimated** remaining APP1 room with a 20 KB default fallback (still used by `CropExporter.patchPngExifTiff` for the PNG eXIf splice-vs-strip decision where uncapped source TIFF and the u31 chunk boundary make the older semantics the right fit). |
| `metadata/GainMapExtractor` | Extract HDR gain map from between primary EOI and SEFT |
| `metadata/GainMapComposer` | Append gain map + trigger MPF patch |
| `metadata/MpfPatcher` | Fix MPF APP2 offsets after primary size changes |
| `metadata/SeftExtractor` | Extract existing SEFT trailer (re-appended verbatim by CropExporter) |
| `metadata/JpegSegment` | Data class for a single JPEG marker segment. Carries the canonical XMP namespace identifier (`XMP_HEADER`) consumed by `isXmp()`, `HorizonDetector.detectFromMetadata`, and `XmpItemLengthPatcher` |
| `metadata/JpegMarker` | Constants for the JPEG marker bytes (`SOI` / `EOI` / `SOS` / `RST_FIRST..RST_LAST` / `STUFFING` / `TEM`) used directly by `JpegMarkerWalker`, `JpegMetadataExtractor`, `MpfPatcher`, `GraftWriter`, `XmpItemLengthPatcher`, and indirectly (via the walker) by `CropExporter` and `GainMapExtractor` |
| `metadata/JpegMarkerWalker` | Canonical JPEG marker-walking helpers. `findPrimaryEoi(file, endBound)` consolidates the SOS / EOI / RST / segment-length / overflow-guard logic previously duplicated across `CropExporter`, `GraftWriter`, and `GainMapExtractor` — hardened against `segLen < 2`, wrap-overflow, and truncated SOS headers. `skipFillBytes(file, ffOff, endBound)` honors legal `FF FF MARKER` fill-byte sequences (ITU-T T.81 §B.1.1.2) — consumed by `MpfPatcher`, `JpegMetadataExtractor`, `JpegMetadataInjector`, `BitmapUtils.readExifOrientationInternal`, `GraftWriter`, and `XmpItemLengthPatcher`'s segment walker so a `FF FF E1 ...` shape doesn't break any byte-walker on the second 0xFF |
| `metadata/PngMetadataExtractor` | Walk PNG chunks (8-byte signature + length/type/data/CRC chunks) for the eXIf chunk per PNG 1.6 spec. Three entry points: `extract()` returns a synthetic APP1 EXIF segment (capped at the JPEG APP1 u16 limit so JPEG injection stays well-formed), `extractRawTiff()` returns the raw TIFF bytes uncapped (used by PNG → PNG round-trip where the eXIf chunk's u31 length field has no JPEG-side cap), `extractOrientation()` parses the TIFF Orientation tag (0x0112) so PNG sources rotate pixels at load time matching JPEG behavior. Hardened against malformed TIFF (rejects byte order ≠ II/MM, magic ≠ 42, IFD entries with type ≠ SHORT or count ≠ 1, and orientation values outside 1..8) |
| `metadata/TiffTag` | Single-source-of-truth constants for EXIF / TIFF tag IDs (`ORIENTATION`, `IMAGE_WIDTH`, `IMAGE_LENGTH`, `PIXEL_X_DIMENSION`, `PIXEL_Y_DIMENSION`, `EXIF_SUB_IFD`, `JPEG_INTERCHANGE_FORMAT[_LENGTH]`, `MP_ENTRY`) and entry-type codes (`TYPE_SHORT`, `TYPE_LONG`). Consumed by `ExifPatcher`, `BitmapUtils`, `PngMetadataExtractor`, and `MpfPatcher` so a future spec change (new tag, type widening) lands in one file rather than a multi-site bare-literal hunt |
| `metadata/GraftWriter` | In-memory byte splice for "Apply External Edit" — assembles the grafted JPEG per `SWAP_*` constants (original metadata + edit primary scan + original gain map + original SEFT) |
| `metadata/HdrSignature` | XMP "hdrgm" namespace marker scanner. Two entry points: `hasHdrgmInXmp(List<JpegSegment>)` walks ONLY parsed XMP APP1 bodies (standard + extended) for the load + graft HDR-source gate, then falls back to the reassembled Extended XMP buffer to catch markers straddling chunk boundaries (a stray "hdrgm" outside XMP doesn't false-positive); `isHdrSource(byte[])` is a full-file scanner reserved for `UltraHdrCompat`'s post-`Bitmap.compress` diagnostic where the freshly-emitted JPEG is well-formed. Pure Java, no Android deps. |
| `metadata/ExtendedXmpReassembler` | Reassemble Adobe Extended XMP chunks (`http://ns.adobe.com/xmp/extension/`) by 32-byte GUID + 4-byte unsigned offset into a single concatenated byte buffer. Used by `HorizonDetector.detectFromMetadata` (Roll / Tilt scanning across chunk boundaries), `HdrSignature.hasHdrgmInXmp` (hdrgm marker), and `XmpItemLengthPatcher` (Item:Length straddle detection) so the reassembly logic is one chokepoint. Pure Java, no Android deps. |
| `metadata/XmpItemLengthPatcher` | Rewrites the GContainer `Item:Length` attribute in the primary's XMP packet to match the actual gain-map byte size after re-encode. The Ultra HDR pipeline preserves source XMP byte-identically, so the source's pre-edit gain-map length attribute goes stale — strict GContainer decoders (Google's libUltraHdr) slice the gain map by `Item:Length` and would decode a truncated stream, silently dropping HDR boost. Patches in-place when the attribute lives in the standard XMP packet; **fail-closes** (returns null) when (a) the attribute lives in an Extended XMP chunk, (b) the per-chunk Extended XMP scan misses but the reassembled-bytes scan hits a straddling occurrence, OR (c) the attribute lives in standard XMP but the segment is unpatchable — patched segLen would exceed the APP1 u16 cap, the value is non-quoted, the digit run is empty / unterminated, or the closing quote doesn't match. Walks ALL standard XMP APP1 segments (legacy non-Adobe splitters can emit two). All four APP1-walking sites (`patch`, `collectApp1Segments`, `extendedXmpContainsItemLength`, `findAllXmpApp1Segments`) route through one private `walkApp1Ranges` helper that consolidates length validation + `JpegMarkerWalker.skipFillBytes` fill-byte handling — the helper exists because three near-identical inline walkers had drifted apart, and one missed a fill-byte fix that was applied to the others. `GainMapComposer.compose` checks for null and drops HDR rather than ship stale `Item:Length`. Uses a private tagged record `SegmentPatchResult` (factories `failClosed()` / `notPresent()` / `patched(byte[])`) to distinguish "attribute not in this segment" (caller scans the next segment / falls through to Extended XMP) from "attribute here but unpatchable" (caller fails closed). Pure Java, no Android deps. |

### Utilities

| Class | Purpose |
|-------|---------|
| `util/BitmapUtils` | EXIF orientation reading (`readExifOrientation`), `orientationMatrix()` (shared with `UltraHdrCompat.applyExifOrientation`), `applyOrientation()` (consumed by `ImageLoadController` / `EditAligner`; UltraHdrCompat has its own `applyExifOrientation` that ALSO rotates the embedded gainmap pixel buffer to keep primary and gainmap coherent), `drawCropped()` (shared crop+rotate render between `CropExporter` and `UltraHdrCompat.renderPrimary` so primary-byte output is byte-identical), `isCardinalRotation()` (90°/180°/270° fast-path for the gain-map render), and the `ROTATION_EPSILON` constant (0.005°, the canonical sub-epsilon threshold consumed by the render pipeline / ruler / bypass-encode gate / horizon detector) |
| `util/ByteBufferUtils` | Endian-aware read/write with bounds checking |
| `util/DpToPx` | Density-independent-pixel → pixel conversion using `Math.round` (not `(int)` truncation, which collapses 1dp values to zero on density-0.75 screens). Required by every dialog/binder; centralised here so the rounding contract can't drift |
| `util/HorizonDetector` | XMP-roll-tag parse first, then Canny edges + two-pass Hough transform (coarse 80–100° at 0.1° / fine ±2° at 0.01°) over the user-painted horizon region; used by the auto-rotate fallback |
| `util/AiRegionDetector` | Identifies the AI-edited region in a graft by diffing source vs aligned-edit at sampleSize=4. Output `AiMask` record drives the gain-map inpaint at save time |
| `util/GainMapInpainter` | Frontier-tracked grow-from-boundary inpaint that fills the AI-masked region of the source's gain-map Bitmap with the average of unmasked 8-neighbors. Mutates in place from `Gainmap.getGainmapContents()` so the single-channel container survives the save's `Bitmap.compress(JPEG)` call |
| `util/RotationMath` | `rotate(x, y, pivotX, pivotY, deg, out)` / `inverse(x, y, pivotX, pivotY, deg, out)` helpers — single source of truth for rotation math. Both write the rotated point into the caller-allocated length-2 `out` array (no allocation per call) and return `out` for chaining; sub-`ROTATION_EPSILON` rotations short-circuit to identity |
| `util/SafFileHelper` | SAF/MediaStore URI helpers: copy (`transferTo`-based), derive sibling (handles both nested `primary:Pictures/foo.jpg` and root-level `primary:foo.jpg` document IDs), file-from-URI, query size, content-readback verify, create-sibling-placeholder, full bytes read via `readUriBytes` (routes direct-file → SAF-stream with per-call temp cache) |
| `util/SafPaths` | Pure-string helpers extracted from SafFileHelper: `parentDocIdOf`, `lastSegmentSeparatorEnd`, `hasImageSignature`, `hasParentTraversalSegment` (segment-aware `..` check, replacing the substring `String.contains("..")` that rejected legit "IMG..edited.jpg" filenames). Static, no Context — testable directly without an Android runtime |
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
   filesystem-grant providers to bypass Samsung's MediaStore EXIF mangling, with a SAF stream copy fallback for
   cloud / SAF-only sources). Reject non-JPEG / non-PNG sources up front via the magic-byte gate
   (`isJpegSignature` / `isPngSignature`) so HEIC / WebP / GIF can't slip past and silently re-encode through the
   PNG default later.
2. Decode to Bitmap via `BitmapFactory.decodeByteArray`. Recycle on the rare zero-area early-return.
3. Read EXIF orientation from the right parser for the format: `BitmapUtils.readExifOrientation` walks JPEG
   markers; `PngMetadataExtractor.extractOrientation` walks PNG chunks. Both return 1 (upright) on absence /
   malformed input, so the same `BitmapUtils.applyOrientation` rotation pass follows for both formats.
4. Extract metadata via `extractMetadata`:
   - JPEG: `JpegMetadataExtractor.extract` for APP/COM segments → `state.jpegMeta`;
     `HdrSignature.hasHdrgmInXmp(meta)` walks ONLY the parsed XMP APP1 segments looking for the hdrgm
     namespace marker (a stray "hdrgm" 5-byte sequence in MakerNote / COM / vendor blob / SEFT history /
     entropy doesn't false-positive), gated by an `hasMpf` pre-filter (cheap segment
     scan), and the resulting `isHdrSource` boolean drives `GainMapExtractor.extract` (no marker →
     returns null without inspecting post-primary FF D8 bytes, so an SDR Samsung file whose SEFT data
     block begins with an embedded JPEG thumbnail's FF D8 isn't mis-extracted as a gain map);
     `SeftExtractor.extract` then receives `gainMap != null` as a `hasGainMap` hint so its trailer-start
     walk only steps past a gain-map EOI when one was actually present. `GraftWriter.graft` uses the
     same `hasMpf && hasHdrgmInXmp` AND-gate per-side so a graft of an SDR original doesn't synthesise
     a phantom gain map either.
   - PNG: dual storage — `PngMetadataExtractor.extract` builds a synthetic APP1 EXIF segment for `state.jpegMeta`
     (capped at the JPEG APP1 u16 limit so JPEG-injection stays well-formed), and `extractRawTiff` returns the
     uncapped raw TIFF bytes for `state.pngExifTiff` (used by PNG → PNG export so > 64KB EXIF round-trips fully).
     The third PngMetadataExtractor entry point — `extractOrientation` — was already consumed in step 3 by the
     orientation read; this metadata-extract step covers only the segment and raw-TIFF parses.
5. Compose the human-readable format string for the info bar — `EXIF+ICC+XMP+HDR+Samsung` for a JPEG with all
   five flags. EVERY flag is conditional (`hasExif`, `hasIcc`, `hasXmp`, `gainMap != null`,
   `state.getSeftTrailer() != null`); the leading "EXIF" is appended only when present, with subsequent flags
   joined by `+`. An EXIF-less JPEG (rare; older / stripped sources) starts the string with the next-present
   flag, e.g. `XMP+HDR`. `PNG+EXIF` for a PNG with eXIf; plain `PNG` otherwise; empty string for a JPEG with no
   metadata at all.
6. Hand off to `installImageOnUi` on the UI thread so the View hierarchy reads the freshly-populated state
   atomically. Bg-thread mutations to the state become visible to the queued runnable via the Handler.post
   happens-before; concurrent UI-thread reads (e.g., a stray onDraw) see either pre-reset state (still consistent)
   or unwritten state (the renderer's null-source-image guard handles it). The UI runnable also resets
   non-state-backed UI affordances that don't auto-sync from CropState — the Pan / Lock toolbar checkboxes and
   the per-mode lock-axis prefs, and any active horizon paint mode (discards the in-progress stroke and reverts
   the Auto button label / color so the new image's first touch routes to Select / Move instead of paint).

`CropState.setSourceFormat` seeds the export config to match the loaded format so the SaveDialog's format toggle and
default filename arrive on the same format the user opened. Without this, loading a PNG would leave Save defaulting to
JPEG (silent format conversion + alpha loss). The user can still flip the toggle in the SaveDialog before any individual
save.

**Input methods**:
- Open button: `ACTION_OPEN_DOCUMENT` (JPEG, PNG)
- Share intent: `ACTION_SEND` with `image/*`
- View intent: `ACTION_VIEW` with `image/*`

**Touch-blocking progress overlay**: every state-mutating background job (load, graft, save, horizon detect) raises a
full-screen modal overlay (`progressOverlay` in the layout, `clickable=true focusable=true`) for the duration of the bg
work. Without it the editor and toolbar above would still accept taps / drags / AR changes / rotation while CropState
is being reset and re-populated underneath, leaking inputs onto an in-flight-replaced state. `setBusyUi(true)` only
disables Save/Open; the overlay is what gates everything else. The overlay is hidden in `finally` blocks of every
busy-release path so a thrown bg task never strands the user behind a permanently-modal overlay.

**Pre-enqueue cleanup contract**: every busy-acquiring entry point (`ImageLoadController.load`,
`GraftController.onEditPicked`, `ExportPipeline.exportTo`, `AutoRotateBinder.onHorizonPaintComplete`) wraps the busy
claim + `setBusyUi(true)` + `showProgress(...)` + `runInBackground(...)` calls in a `try/catch (RuntimeException)` that
releases busy, clears the UI flag, hides the overlay, and rethrows. Without this guard, a `RejectedExecutionException`
from the executor (post-`onDestroy` racing a tap) or a view-tree throw from the UI mutators would strand `busy=true`
forever — the bg `finally` only fires if the Runnable was actually accepted. The four sites use the same shape so a
future audit can pattern-match.

**Transient-dialog forced dismissal**: `ImageLoadController.load` and `MainActivity.applyGraftedBytes` call
`host.dismissTransientDialogs()` on the UI thread BEFORE the busy claim and bg dispatch. Five dialogs are tracked —
`SettingsDialog` (commits to gridConfig color/width/presets), `SaveDialog` (exportConfig format / gridConfig
includeInExport), `ToolbarBinder.showCustomArDialog` (aspectRatio + the `customArLabel` / `customArActive` UI flags),
`ToolbarBinder.showPreciseRotationDialog` (rotationDegrees), and `SaveController.showReplaceDialog` (writes to the SAF
target on Replace/Keep — without forced dismissal a stale Replace prompt outliving its source image would write image
B's state to image A's SAF target). Without forced dismissal an open dialog at the moment a Share/View intent fires
would race the bg `state.reset()` with the user's still-active in-dialog commits, OR (after the load completes)
silently apply image A's typed values to image B's freshly-reset state. Dialog producers register through
`host.registerTransientDialog(dialog)`, which installs an `OnDismissListener` that clears the tracked reference on
normal dismissal. `dismissTransientDialogs` calls `dialog.cancel()` (not `dialog.dismiss()`) so the dialog's
`OnCancelListener` fires too — Custom AR's spinner-position restore, Replace dialog's placeholder cleanup, and
SaveDialog's `priorSnapshot` clear all live in `OnCancelListener` and would leak / stick on a plain `dismiss()`.
`SettingsDialog`'s `OnCancelListener` AND `OnDismissListener` both cancel any open `ColorPickerDialog` it parented,
since the picker is a separate AlertDialog that mutates gridConfig through its own OK button — without that propagation
a stale picker outliving its parent would keep applying colors to the new image's state. The OnDismissListener is the
load-bearing one: it fires on the "Done" button path AND on Activity-destroyed-mid-dialog config-change dismissal,
which the OnCancelListener doesn't see. Because `setOnDismissListener` replaces rather
than chains, `SettingsDialog.show` takes the host-tracking cleanup as a parameter and composes both cleanups into a
single dismiss listener — `registerTransientDialog(SettingsDialog.show(...))` would silently clobber the picker cleanup
otherwise. SaveDialog's `priorSnapshot` has a second
clear path beyond the OnCancelListener: if `SaveDialog.show` itself throws (BadTokenException from a config-change
race that lands between the `isDestroyed()` pre-check and the actual `.show()` call, or any RuntimeException from the
transient-dialog registration), `SaveController.openSaveOptionsDialog` clears the snapshot in its catch block —
without that, the listener never installs and the source-bitmap reference would stay pinned until the next save
attempt or activity teardown.

### 2. Editor Modes

#### Select Mode (Default)
- Tap to place selection points around a feature
- Tap on existing point to remove it
- Long-press to remove nearest point
- Auto-computes maximum crop at current AR centered on the selection points
- Points can't be placed outside rotated image content
- Clearing all points resets crop to full image
- Single selection snaps the tapped pixel's center: the grid's midline covers the marked pixel

#### Move Mode
- Drag to reposition the crop rectangle
- Respects lock direction: H moves X only, V moves Y only, Both moves freely
- Crop **size is preserved** in Move mode — `recomputeCrop` runs with `cropSizeDirty=false` so only the center is
  re-clamped against the rotated bounds; cropW/cropH never change
- During the drag the center stays continuous (sub-pixel) for smooth motion; the drag's fractional accumulator lives in
  a separate "anchor" state so high-zoom slow drags build up across events without losing motion to the rotation clamp
- On finger lift (`onPanRelease`), the center snaps to the parity that makes `cropImageX = centerX − cropW/2` integer
  (cropW even → centerX rounded; cropW odd → centerX floor + 0.5). This pixel-aligns the crop borders and grid without
  per-frame snap (which would cause flicker as cropW oscillates during rotation)
- Crop rectangle cannot be dragged outside image bounds (rotation-aware binary search inside `setCenter`)
- Cross-axis drift on a locked axis is bounded to 0.5 px per event and rejected above that threshold
- Tap does nothing (prevents accidental crop placement)

### 3. Lock Modes

The lock-axis row at the bottom of the editor has three buttons (Both / H / V). Two independent toolbar checkboxes —
**Pan** and **Lock** — modulate that row's behavior:

| Mode (lock-axis button) | Select Behavior | Move Behavior |
|------|----------------|---------------|
| Both | Symmetric on both axes around point midpoint | Drag moves both axes |
| H | Center horizontally on points, maximize vertically | Drag moves X only |
| V | Center vertically on points, maximize horizontally | Drag moves Y only |

**Toolbar `Pan` checkbox**: when on, sets `CenterMode.LOCKED`, which makes drags pan the viewport regardless of the
lock-axis selection (effectively overriding the row above for the duration the box is checked). Unchecking restores the
previously-selected lock-axis mode.

**Toolbar `Lock` checkbox** (`chkLockCenter`): independent of `Pan`. When on, selection-point edits do NOT
auto-recompute the crop center — the user can add or remove points without the crop moving. Off (default) gives the
documented auto-center behavior.

**Select mode centering logic**:
- Locked axis: center = midpoint of selection points, crop extent = symmetric from center
- Free axis: center = midpoint of points (best-effort), crop extent = full image dimension; center shifts only if needed
  to keep the crop in bounds
- With rotation: a second pass of `maxScaleForRotation` shrinks the crop if the rotation-clamped center makes it too
  large; selection points are rotated through `rotatedSelectionMidpoint` so the rotated AABB midpoint (not the
  un-rotated one) drives the center under non-zero rotation

Per-mode lock preferences (Both/H/V) are remembered independently for Move and Select. Defaults are **V** in Move and
**Both** in Select. "Both" button is only visible in Select mode.

### 4. Aspect Ratio

**Spinner labels in order**: 4:5 (default), Full, 16:9, 3:2, 4:3, 5:4, 1:1, 3:4, 2:3, 9:16, Custom. "Full" is the
no-AR-constraint option (`AspectRatio.FREE` with width=height=0).

**Custom AR**: Dialog with width:height inputs when "Custom" is selected; constructs a fresh `AspectRatio(w, h)` and
assigns it. After Apply, the closed spinner head displays `Custom W:H` (e.g. `Custom 5:7`) instead of reverting to
the previously-selected preset, and the dropdown's Custom row also reads `Custom W:H` so a user reopening the
dropdown can see what's currently committed before re-tapping Custom (the spinner adapter substitutes the dynamic
label via a `customArLabel` / `customArActive` pair driven by `getView` / `getDropDownView` overrides). Picking any
preset row from the spinner clears the dynamic label and the closed head reads the preset name. Cancelling /
dismissing the Custom dialog (X / back-press) leaves the model and label unchanged — the spinner setSelection
restores it to the position it sat at before Custom was tapped.

**Auto-crop**: Changing AR auto-creates a crop at image center if none exists.

**Locked-AR exact-integer realisation** (`AspectRatio.snap`): when an integer-valued AR is locked, `CropEngine`
snaps the rounded crop dimensions to the nearest `(Wᵣ·k, Hᵣ·k)` realisation where `(Wᵣ, Hᵣ)` is the AR reduced to
lowest terms via GCD and `k` is the integer minimising squared distance from the requested crop. This eliminates
the ~½-pixel-per-axis drift that independent `Math.round` on each dimension produces — a 4:5 lock that previously
landed on AR=0.79989 now lands on exact 0.80000. Both `recomputeCrop` and `recheckRotationFit` pass the
pre-snap rounded dims as the snap's max-bound (no-grow) so the snap can never grow past what fits at the user's
locked center, preserving the anchor — `setCenter`'s edge-clamp would otherwise silently drift the locked center
inward. The gain map stays at its natural rounded quarter-resolution dims — **not**
snapped — so the sampled source region matches the primary's full extent (snapping the
gain map would shrink the sampled region by up to (Wᵣ-1, Hᵣ-1) pixels, reintroducing spatial HDR misalignment
on high-contrast crop edges, and the AR drift between primary and gain map is imperceptible after the
decoder scales the gain map over the primary). The drift comes from half-pixel rounding on the gainmap side:
when `primaryH / 4` is a half-integer (e.g., 3750 / 4 = 937.5 or 3735 / 4 = 933.75), `Math.round` snaps to
the nearest integer, producing up to a 0.5-gainmap-pixel error that translates to an AR delta of roughly
`0.5 / gainmapH`. For typical quarter-resolution gainmaps (gainmapH ~ 900-1000), the worst-case AR drift is
~5e-04; observed peak across the May 2025 fixture set is 6e-04 on 2988x3735 primaries. Sub-perceptible at
display resolution after the decoder's scale-to-fit. No-op for `FREE` and fractional ARs (Custom AR with non-integer
inputs).

### 5. Rotation

**Galaxy-style scrollable ruler** (persistent, below point controls):
- Full range: -180.0 to +180.0 degrees, finest snap step 0.01° at maximum zoom
- Drag to scroll with momentum fling via OverScroller; pinch to zoom the ruler scale
- **Interrupted-gesture cleanup**: when Android dispatches `ACTION_CANCEL` (system back, parent-view intercept,
  multi-touch disambiguation), the ruler recycles the velocity tracker and bails without committing a fling, snap,
  or listener notify. Rotation stays at its pre-gesture value rather than applying the partial gesture's velocity.
  Distinct from `ACTION_UP`, which commits the fling / snap / tap as the user-completed release.
- Tick configuration scales with visible-degrees-per-screen; 8 tiers with minor steps in
  {10, 5, 1, 0.5, 0.1, 0.05, 0.01} degrees (the `1°` tier appears twice with different major-tick groupings:
  `{minor=1°, major=10°}` and `{minor=1°, major=5°}` — picked at different zoom levels)
- Snap-to-detent at 0, ±45, ±90, ±180 degrees within `min(currentMinorTick × 0.5, 0.8°)`. The 0.8° cap matters
  most at the coarsest zoom where ±45/±90 aren't part of the tick grid; at deeper zooms the threshold shrinks
  proportionally to the visible minor tick so fine values near a detent (like 0.01°-0.79° near 0°) remain
  selectable rather than getting pulled into a fixed dead zone.
- Center indicator: mauve triangle + line; zero marker in red
- Degree readout in info bar (visible only when |rotation| ≥ ROTATION_EPSILON = 0.005°), tappable for exact numeric
  input
- Ruler disabled (30% opacity, no touch) when no image loaded

**Sub-epsilon rotation snap**: `CropState.setRotationDegrees` is the single chokepoint for every rotation entry point
(ruler, precise-rotation dialog, horizon detector). After clamp it snaps `|deg| < ROTATION_EPSILON` to exactly 0,
keeping the renderer, readout, and `ExportPipeline.canBypassEncode` aligned with the model — sub-epsilon values were the
path that previously let the ruler land on a value the rest of the pipeline rounded to zero (no visible rotation, hidden
readout, but the bypass disabled and forced a needless re-encode). The 0.005° epsilon sits a half-step below the ruler's
0.01° finest tick so every value the ruler / horizon detector can produce is honored end-to-end.

**Auto-rotate button** (in the Points row, hidden until an image is loaded):
- First attempts horizon detection from JPEG metadata via `HorizonDetector.detectFromMetadata` — three
  passes in priority order: (1) standard XMP APP1 segments (canonical Adobe namespace prefix) searched for
  `Roll` / `Tilt` attributes; (2) Adobe Extended XMP chunks (the secondary-segment shape that carries XMP
  overflow past the ~64 KB APP1 cap) reassembled by GUID + offset before scanning, so a Roll attribute past
  the first chunk OR straddling a chunk boundary is still found (per-segment substring scanning would
  otherwise miss split keywords); (3) fallback loop over any APP1 segment
  whose payload contains XML-like `Roll` / `roll` / `Tilt` text (catches vendor shapes that don't use the
  canonical Adobe namespace). Pure EXIF-tag parsing (CameraOrientation, ImageOrientation, MakerNote roll)
  is NOT currently implemented — every Samsung / Pixel / iPhone source we've seen ships horizon angle in
  XMP, so the EXIF path was never built. On a hit the rotation applies immediately with no further user
  interaction.
- On a successful detection (metadata fast-path OR painted-region detection below), the rotation ruler also
  auto-zooms to its finest 0.01° tick spacing so the user can immediately fine-tune. The ruler's auto-zoom is
  unconditional on detection success — the assumption is that if auto-rotate fired at all, the user is dialing
  in a precise correction and wants the high-resolution ruler tier.
- Falls back to a **two-step paint-and-detect flow** when no metadata roll is available:
  - First tap enters paint mode — the Auto button label changes to "Cancel" in red, the editor becomes a paint
    surface (touch overlay routes to `HorizonPaintOverlay`), and the user paints over the visible horizon line
    with a brush. The brush radius is constant in screen pixels (`CropEditorView.TOUCH_THRESHOLD_PX = 30`); its
    image-pixel radius scales inversely with zoom, so a deep-zoom paint covers fewer image pixels per stroke.
  - Second tap (with paint mode active and at least 2 painted points) commits — `AutoRotateBinder.onHorizonPaintComplete`
    snapshots the painted polyline and runs `HorizonDetector.detectFromPaintedRegion` (Canny edges + two-pass
    Hough: coarse 80–100° at 0.1° / fine ±2° at 0.01°) on the bg executor.
  - Tapping Cancel during paint exits paint mode without running detection.
  - A paint with fewer than 2 points surfaces "Paint was too short" and exits paint mode without detection.
  - A new image load (Open / Share / View intent) while paint mode is up exits paint mode through
    `AutoRotateBinder.cancelHorizonPaintMode` — discards the in-progress stroke without invoking the
    detection callback and reverts the Auto button to its resting "Auto" / subtext0 styling. Without this
    exit, the new image's first touch would route to horizon painting instead of Select / Move and the
    Auto button would stay stuck on its "Cancel" / red label from the previous load. Paint mode never
    survives an image swap.
- **Magnitude ceiling**: tilts whose magnitude exceeds `MAX_HORIZON_TILT_DEGREES = 30°` are rejected by both the
  metadata fast-path (`HorizonDetector.normalizeMetadataAngle`) and the painted-region Hough path
  (`HorizonDetector.runHoughAndConvertToRotation`) by returning NaN. Large tilts indicate a held-sideways shot or
  sensor garbage rather than a horizon nudge. The shared constant ensures the same image gets the same auto-rotate
  verdict regardless of which detection path runs (25° on the metadata path and 30° on the painted path were
  unified into a single 30° threshold). User-visible: the metadata fast-path silently does nothing (no rotation, no toast
  — the user simply sees no change); the painted-region path surfaces "No line detected in painted area".
- **Busy-gating contract**: the paint-and-detect flow acquires `host.getBusy()` via `compareAndSet(false, true)`
  the moment the user commits the paint, releases it in `onHorizonDetectionResult` only after the rotation
  has been applied. A Share/View intent or Open tap arriving mid-detection is rejected with the busy toast,
  not raced. Tapping the Auto button while busy (and not in paint mode) also surfaces the busy toast and
  bails before clearing the in-progress paint state. The painted points are snapshotted into a fresh ArrayList
  before bg dispatch so a future code path that bypasses the busy gate can't CME the bg detector via concurrent
  `imagePoints.clear()`.

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
| Two-finger pinch | Zoom with pivot (1x to 256x) |
| Single-finger drag | Move mode: move crop / Select mode: pan viewport |
| Double-tap | Fit image to view (disabled in Select mode) |
| Long-press | Remove nearest selection point (Select mode) |

Viewport clamped to prevent panning image off screen. Bitmap filtering disabled at 4x+ zoom for crisp pixels.

### 7. Grid Overlay

- Toggle via toolbar `Grid` checkbox
- Settings dialog opens via the toolbar settings icon (combined dialog covers grid + pixel-grid + selection/paint + build)
- Grid-count presets: 2x2 through 8x8; arbitrary cols/rows via numeric input
- Configurable color (via `ColorPickerDialog`), line width (1-20px)
- **Line positions snap to integer image pixels** matching `CropExporter.gridLinePixel`'s rounding: first-half lines at
  `Math.round(cropExtent * i / count)`, second-half lines mirror through `cropExtent`. The middle line at count ∈ {2, 4}
  keeps `cropCenter` (half-integer for odd cropExtent) so single-point selection markers sit at the grid intersection —
  the only case where preview diverges from export by 0.5 px
- Line width scales by image-to-screen ratio (preview matches export)
- Pixel grid at 6x+ zoom (separate toggle + configurable color in Settings)
- Selection points, polygon fill, and horizon paint use the shared selection / paint color
  (`GridConfig.selectionColor`, configurable in the Settings card per §11) — kept separate from grid
  color so the paint surface stays visible against the grid overlay
- Optional bake-in to exported image (`includeInExport`); grid + HDR supported. Reset on new image load — bake-in is a
  per-save choice, not a persistent preference

### 8. Undo/Redo

- Full undo/redo for selection points (50-step history)
- Buttons greyed out when not applicable
- Clear button removes all points and resets crop to full image
- History cleared on new image load
- Controls visible only in Select mode (they act on selection points; the row is hidden in Move mode where there's
  nothing for them to do). The history itself persists across mode switches — switching back to Select restores the
  buttons with their previous enabled state.

### 9. Export

**Pre-save dialog**: Tapping Save opens `SaveDialog` first — title `"Save Image"`, positive button `"Continue"`,
negative button `"Cancel"`. The dialog hosts the format toggle (JPEG / PNG) and the `Export Grid` checkbox. The
`ACTION_CREATE_DOCUMENT` picker only opens after the user taps Continue, so the format choice and grid-bake
preference are already committed to `CropState` before the picker locks in the document's MIME.

**Save flow**: Always `ACTION_CREATE_DOCUMENT` with format-aware MIME type (image/jpeg or image/png). Collisions inside
the user's chosen directory route through `ReplaceStrategy`'s crash-safe write-then-swap (Strategy A: File-I/O atomic
move; B: SAF direct overwrite with byte-for-byte verify; C: SAF rename-with-fallback). Same-name results from
`ACTION_CREATE_DOCUMENT` (provider-confirmed overwrites) get a sibling placeholder via
`DocumentsContract.createDocument` and route through the same Replace flow; opaque-ID providers fall back to
`exportToOverwrite` (direct write to the target with preserve-on-failure, "Replaced <name>" toast on success).

**No-edit bypass**: when the user has applied no transformations (no crop, no rotation, no grid bake-in, JPEG-to-JPEG
round-trip) AND the in-memory image is not a graft (`!state.isGraftApplied()`) AND the source carries a pre-computed
IFD1 thumbnail (`ExifPatcher.hasIfd1Thumbnail(state.getJpegMeta())`), `ExportPipeline` writes
`state.originalFileBytes` verbatim instead of canvas-encoding. Preserves byte-perfect fidelity for re-saves of
unmodified Samsung originals. Cropped / rotated / grid-baked saves, any graft save, AND saves of sources lacking an
IFD1 thumbnail go through the canvas-encode + ExifPatcher pipeline. The thumbnail-presence gate forces the
re-encode path on screenshots / minimal-EXIF sources so `CropExporter`'s synthesise-
fresh-EXIF chain can add a thumbnail; without this gate the bypass shipped source bytes verbatim including the
empty-IFD1 state. Graft saves are excluded because the splice ships source's gain map verbatim over the edit's primary
scan; if the user later crops, the gain map's spatial alignment shifts off the features it boosts. Forcing graft saves
through the full encode regenerates the gain map from the spliced primary via `UltraHdrCompat.compressWithGainmap`,
keeping save-without-crop and save-after-crop both correct.

**JPEG quality**: 100 (hardcoded, always maximum) when canvas-encoding; verbatim when bypassing.

**Output canvas color space** (`CropExporter.export`):
- **JPEG with gain map (Ultra HDR)** — explicit `Display P3` canvas. The gain map was calibrated against a
  P3-gamut base; composing onto an sRGB primary produces a subtly wrong HDR boost, so this branch overrides
  whatever the source bitmap's color space reports.
- **JPEG without gain map** — match the source bitmap's color space (`src.getColorSpace()`). The metadata
  injection pass restores the source's APP2 ICC profile verbatim, so the canvas color space has to describe the
  same encoding the ICC tag claims. Without this, a Display P3 source (modern iPhone JPEGs, Photoshop P3
  exports) would render into the default sRGB canvas while the saved ICC tag still claimed P3 — ICC-aware
  viewers would then render wrong colors.
- **PNG** — default (sRGB) so source alpha round-trips and rotation corners stay transparent. Color-managed
  canvases can apply subtle filtering during rasterization that breaks grid-line consistency.

**SAF extension-mismatch guard** (`SaveController.handleSaveAsResult`): SAF locks the document's MIME type from
the requested filename when the picker opens. If the user renames in the picker — `.jpg → .png`, `.jpg →
.webp`, `.jpg → .heic`, etc. — writing the encoder's bytes would land them in a document whose MIME type and
filename extension disagree. The guard rejects when `chosen` has a non-empty extension AND that extension's
Format doesn't match `requested`'s Format. Both known-format mismatches (`.jpg → .png`) and unknown-extension
typos (`.jpg → .webp` where `Format.fromExtension("foo.webp") == null`) are caught; extension-less filenames
are allowed through (SAF MIME stays valid, encoder bytes match).

**HDR Export Pipeline** (all cases use canvas rendering for primary):
```
Original JPEG -> decode with gainmap -> render primary on cropW x cropH canvas
  (same rotation/positioning as preview: rotate around image center)
-> apply analogous transform to gainmap bitmap at gainmap resolution — same EXIF rotation
   matrix, same scaled draw offset; unrotated branch snaps the fractional draw offset to
   the nearest integer + nearest-neighbor (≤ 0.5 gainmap-pixel drift,
   detailed below) — and gainmap dims stay at the natural rounded quarter-resolution —
   NOT AspectRatio.snap'd — so the sampled source-gainmap region matches the primary's
   full extent; the AR drift between primary and gainmap (worst case ~5e-04 from half-pixel
   rounding at gainmap-side dims around 900-1000 px — see "Locked-AR exact-integer realisation"
   for the rationale) is imperceptible after
   the decoder scales the gainmap over the primary, and is the lesser evil vs spatial HDR
   misalignment on edge content that snapping the dims would introduce
-> attach gainmap to output bitmap -> Bitmap.compress -> Ultra HDR JPEG
-> extract gain map portion -> compose with canvas-rendered primary
-> patch GContainer Item:Length to match new gain-map byte size (XmpItemLengthPatcher;
   fail-closed null return -> drop HDR rather than ship stale Item:Length)
-> inject original EXIF (patched) -> re-append existing SEFT trailer verbatim (if any)
```

The gain map undergoes the analogous canvas transform as the primary (same position, pivot, angle, scaled to gainmap
resolution), with one deliberate departure: the unrotated branch snaps the fractional draw offset to the nearest
integer + switches to nearest-neighbor sampling. Bilinear sampling at
fractional gainmap offsets bilinear-blended adjacent rows of the source gainmap on every output row, softening
high-contrast features (horizon lines, cliff edges, wave foam) into visible 5–30-level per-pixel diffs against the
source gainmap. Snap-to-int trades ≤ 0.5 gainmap-pixel (≤ 2 primary-pixel at quarter-res gainmap) of spatial drift on
non-grid-aligned crops for pixel-exact gainmap reproduction at the JPEG round-trip noise floor — sub-perceptible at
display resolution, structurally clean against the noise-proof heatmap. The rotated branch keeps bilinear (rotation
already requires resampling) with a cardinal-rotation + integer-alignment gate that drops to nearest-neighbor when
applicable. Spatial alignment thus matches the primary structurally; the unrotated branch trades sub-pixel positional
accuracy for pixel-exact gainmap content — see the "Gain-map render snap-to-int at fractional offsets" section below
for the full rationale.

**EXIF-orientation rotation of the gainmap**: When the source EXIF orientation is non-identity (2..8),
`UltraHdrCompat.applyExifOrientation` rotates the embedded gainmap's pixel buffer with the SAME matrix as the primary
and substitutes a fresh `Gainmap` instance (tone-mapping metadata copied verbatim via `copyGainmapMetadata`) on the
rotated primary. Android's `Bitmap.createBitmap(src, matrix)` propagates the source's `Gainmap` reference but does NOT
rotate its underlying pixel buffer; without explicit rotation the downstream `renderGainmap` step computes
`scaleX = gmW/primW` against a stored-orientation gainmap whose axes are transposed relative to the rotated primary,
producing catastrophic spatial misalignment on orient=6/8 sources (panel-3 of the graft-analyze heatmap was entirely
lit up — the gainmap diff approached 100% of pixels because every pixel was sampled against the wrong gainmap
neighborhood). The fix keeps the gainmap rotation in lockstep with the primary, so subsequent canvas-renders project
both into display orientation coherently.

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

The non-HDR path is fail-closed: no gain-map bytes are ever appended, and MPF is dropped rather than
"fixed up to point at nothing." When the source carries an MPF segment but no Ultra HDR gain map (e.g.
Samsung "Best Photo" burst groups, focus-stacked panoramas, or any multi-picture JPEG without hdrgm),
`ImageLoadController` leaves `state.getGainMap()` null and the export takes this path. Without the
up-front strip the saved JPEG would carry source's MPF verbatim, anchored at non-existent
secondary-image offsets — strict decoders' multi-picture pre-flight rejects the orphan, lenient
decoders walk past the malformed entries. The strip removes that footgun entirely.

**PNG Export**:
```
Canvas-rendered bitmap -> generate fresh JPEG-compressed IFD1 thumbnail of the cropped pixels
(buildEmbeddedThumbnail; falls back to STRIP_IFD1_THUMBNAIL sentinel on OOM / over-budget)
-> Bitmap.compress(PNG) -> inject EXIF via eXIf chunk with the fresh IFD1 thumbnail
(PNG 1.6 spec: raw TIFF data in CRC32'd chunk after IHDR)
```

PNG export generates a fresh IFD1 thumbnail (JPEG-compressed, per EXIF spec) of the cropped pixels —
without this, passing a `null` thumbnail to `ExifPatcher.patch` would PRESERVE the source's pre-edit
IFD1 thumbnail, leaking pre-crop content via any EXIF-thumbnail-aware viewer.
When fresh thumbnail generation fails (every cascade rung exhausted — both 512 and 256 maxDims at every
quality 95..50 — OR OOM during render / compress, OR the budget itself is ≤ 0 because the source EXIF
already overflows the APP1 cap), `buildEmbeddedThumbnail` returns `ExifPatcher.STRIP_IFD1_THUMBNAIL` (the
byte[0] sentinel), routing ExifPatcher through the strip path that zeros IFD0's next-IFD pointer — the
saved file has no embedded preview rather than the source's stale one.

PNG export pulls EXIF from one of two sources, depending on the source format:
- **PNG sources** use `state.pngExifTiff` (raw TIFF, uncapped) wrapped through
  `CropExporter.patchPngExifTiff` — synthesizes a transient APP1 only to run `ExifPatcher.patch` for
  orientation/dimension normalisation, then unwraps the patched TIFF and writes via
  `injectPngExifFromTiff`. The PNG eXIf chunk has a u31 length field, so a > 64KB EXIF block (camera with
  extensive MakerNote / GPS metadata) round-trips on PNG → PNG. The IFD1 thumbnail itself is still
  bounded by the JPEG APP1 u16 cap that `ExifPatcher.spliceExistingThumbnail` enforces internally;
  `patchPngExifTiff` predicts a too-large rebuild via `ExifPatcher.maxThumbnailBytes` (which subtracts
  the OLD thumbnail's bytes before measuring remaining APP1 room; a naive
  `tiff.length + thumbnail.length` sum would force-strip splices that actually shrink the segment)
  and force-routes to `STRIP_IFD1_THUMBNAIL` so the saved PNG
  carries no IFD1 rather than the source's pre-edit preview. (The PNG eXIf path keeps using the older
  `maxThumbnailBytes` — with its 20 KB conservative default fallback for unparseable TIFF — rather than
  the JPEG path's newer byte-exact `patchedNonThumbBytes`, because PNG eXIf's u31 cap, splice-or-strip
  decision boundary, and uncapped-source-TIFF tolerance call for the older estimation-with-margin
  semantics; the two methods now serve distinct invariants and are intentionally kept separate.) When
  `ExifPatcher` rejects a malformed source TIFF, the export falls through to the synthetic-APP1 path
  below rather than silently dropping metadata.
- **JPEG sources** (and the fallback path above) iterate `state.jpegMeta` through `ExifPatcher.patch` and
  hand the EXIF segment to `injectPngExif`, which strips the JPEG APP1 wrapper before writing the eXIf
  chunk. JPEG-source EXIF is always under the u16 cap by spec, so no special handling is needed.

The export canvas is **not** filled with `CANVAS_BG` for PNG — the bitmap stays on its default transparent background so
source alpha round-trips and rotation corners stay see-through. JPEG keeps the dark-navy fill since the format can't
represent alpha and the user expects rotation corners to read as the editor canvas color they saw in preview. The fill
decision lives at `CropExporter.export` next to the `outBmp` allocation and gates on the same `isJpeg` flag that picks
the Display P3 colorspace.

**Export failure surfacing**: `ExportPipeline.encodePhase` catches both `Exception` and `OutOfMemoryError` and posts an
`"Export failed: <message>"` toast on the UI thread; `writePhase` failures route through `reportFailure` with the same
toast pattern. The OOM catch is required because a multi-megapixel source's primary bitmap or HDR-render allocation
can fail with `OutOfMemoryError` rather than `Exception`; without it the worker would die uncaught and the user would
be left staring at the editor with no feedback after the progress overlay's `finally`-block hide. The same widening
applies on the load path (`ImageLoadController.runLoadBg` posts "Load failed: <message>"), the graft-apply path
(`MainActivity.applyGraftedBytesOnBg` posts "Apply failed: <message>"), the graft-assembly path
(`GraftController.assembleGraftOnBg` posts "Graft failed: <message>"), and the thumbnail subroutine
(`CropExporter.generateThumbnail` widens to `Exception | OutOfMemoryError` so a thumbnail-side allocation failure
degrades to "save without embedded thumbnail" rather than aborting the encode silently). `reportFailure` honours
`preserveOnFailure` for collision-overwrite Replace flows — when the target had prior content, the partial bytes are
kept and the recovery path runs from `ReplaceStrategy` rather than deleting a file the user didn't ask us to destroy.
When the direct-file atomic write fails on a `preserveOnFailure=true` target, `writePhase` synthesizes a failed
`WriteOutcome` and refuses to fall through to the SAF stream's truncate-mode `openOutputStream("w")` — SAF "w"
truncation behavior is provider-dependent (some truncate at open time), so a mid-stream failure after fall-through
would leave the user's original zeroed or partially overwritten, defeating the purpose of the preserveOnFailure
branch. The exportToOverwrite / exportToPreserving callers surface a clean
"Export failed" toast and the target on disk stays intact.

**Encoder-return-value checks**. Every `Bitmap.compress(JPEG|PNG, q, bos)` callsite now checks the boolean
return and treats `false` as a failure. Previously the partial buffer in `bos` (Skia wrote headers + entropy data
before bailing) was shipped onward to `injectExifMetadata` / `composeGainMap` / `appendSeft`, producing structurally
invalid output JPEGs (no primary EOI, no gainmap, no SEFT). The five sites now check explicitly:
- `CropExporter.exportJpeg` / `exportPng` — `throw IOException` on false, routes through `encodePhase`'s catch and the
  user sees "Export failed: Bitmap.compress returned false" instead of a corrupt 8 MB file landing on disk.
- `CropExporter.generateThumbnail` (two-rung × 9-quality cascade — up to 18 `Bitmap.compress` calls) — false on any
  individual quality attempt advances to the next quality / dim combo; if every combo at both 512 and 256 maxDims
  returns false, the method returns null → caller routes through `STRIP_IFD1_THUMBNAIL` (no preview embedded, better
  than partial).
- `UltraHdrCompat.compressWithGainmap` — false → return null → caller drops HDR and re-injects EXIF with
  `hdrgm`/MPF stripped, so the output's metadata stays honest about what's actually attached.
- `EditAligner.reorientEdit` — false → return null → `align()` surfaces "Couldn't decode the edit during reorientation — try exporting again" toast (same message as the BitmapFactory decode-null path because both failures route through a single null-check at the caller site).

**Direct file-I/O write path**. `ExportPipeline.writePhase` tries `FileOutputStream` against the SAF URI's
resolved filesystem path first, falling back to the SAF stream only when `SafFileHelper.fileFromSafUri` returns null
(cloud / opaque-ID providers). On Samsung devices the SAF stream path has been observed to silently corrupt writes:
`openOutputStream("w")` returns success and reports the correct post-write byte count, but the actual disk content
never changes — the provider buffers the write in memory and never flushes, leaving the placeholder document with
stale bytes that downstream Replace strategies then propagate onto the target. The direct file I/O sidesteps the
entire SAF write path: bytes land via the kernel filesystem layer where the provider's caching can't intercept. The
`fsync` after write forces durability before close. Trailing `MediaScannerConnection.scanFile` triggers MediaStore
reindex so Gallery / Photos sees the new content.

**Explicit mtime refresh**. `ExportPipeline.writePhase` (after the direct I/O write) and
`ReplaceStrategy.replaceViaFileIo` (after `Files.move` atomic swap) both call `setLastModified(currentTimeMillis())`
to force the file's last-modified timestamp to update. Samsung's FUSE-backed scoped storage skips mtime refresh on
dedup-detected content-identical writes — the kernel-level write happens but userspace observers (file managers,
`adb stat`, sync tools that compare timestamps) see the old mtime, creating ambiguity about whether the save
actually ran. `setLastModified` after the write/sync forces the timestamp regardless of dedup behaviour, so the user
always has a concrete signal that the save landed.

**Gain-map render snap-to-int at fractional offsets**. `UltraHdrCompat.renderGainmap`'s unrotated branch
now snaps `gainmapDrawX/Y` to the nearest integer and switches to a non-filtering Paint
(`setFilterBitmap(false)` → nearest-neighbor) before `drawBitmap`. Pre-fix, drawing at a fractional offset
(e.g. `gainmapDrawY=-31.25` from `srcY=125 * gainmapScaleY=0.25`) with `FILTER_BITMAP_FLAG` bilinear-blended adjacent
gainmap rows on every output row, softening the boost map and producing 5-30 levels of per-pixel diff against the
source gainmap on high-contrast content (horizon lines, cliff edges, wave foam). The cost of snap-to-int is ≤ 0.5
gainmap pixels of spatial misalignment (≤ 2 primary pixels at quarter-res gainmap — sub-perceptible on a 4000-pixel-
tall image); the benefit is pixel-exact gainmap reproduction at the JPEG-round-trip noise floor. The rotated branch's
`drawGainmapRotated` already had an integer-alignment + cardinal-rotation guard that took the NN path; the same
logic was extended to the unrotated path that was missing it.

### 10. Metadata Preservation

#### EXIF
- All original APP/COM segments preserved verbatim (camera model, GPS coordinates, MakerNotes, ICC, XMP, Software,
  DateTimeOriginal, lens info)
- Orientation tag set to 1 (output is always in display orientation)
- Dimensions updated to crop size
- Thumbnail regenerated from cropped bitmap via a **two-rung dim cascade** with a **9-step quality bracket** at each
  rung. Long-edge max-dim falls 512 → 256; at each rung, encode quality steps through `{ 95, 90, 80, 75, 70, 65, 60,
  55, 50 }` and the first combo whose encoded size fits the APP1 budget wins. The 9-step bracket exhausts dim-
  preserving fallback at 512 before stepping down to 256 — viewers downscale thumbnails for grid / hover display
  (96-256 px), and downscaling masks per-pixel JPEG artifacts substantially, so keeping pixel count high beats keeping
  per-pixel quality high (matches Samsung's native preserve-dim-scale-quality design). The pre-2025 fallback structure
  (single 1024-maxDim rung with quality steps 90→50, then halved 410×512 q70, then quartered 205×256 q50) was
  replaced after empirical re-encode showed the 1024-maxDim rung produced 130-400 KB even at q50 on typical 3-4 MP
  cropped output — well above the 65 535-byte APP1 cap, so it was unreachable in practice and the cascade always
  cliff-dropped to the single-quality halved/quartered fallbacks.
- Budget is computed **byte-exactly** via `ExifPatcher.patchedNonThumbBytes(meta)` which mirrors `patch()`'s decision
  tree (splice / append / synthesise) and returns the corresponding post-patch non-thumbnail size:
  `data.length - oldThumbLen` for splice, `data.length + 42` for append (fresh 42-byte IFD1 header), `102` for
  synthesise. The caller computes `exactBudget = MAX_SEGMENT_BYTES - patchedNonThumbBytes(meta)` — no defensive
  margin, no upper clamp. Replaces an older formulation that estimated via `maxThumbnailBytes` with a 200-byte
  margin and a 60 000-byte upper clamp, which under-reported the available room by ~4 KB on typical sources
  (clamp-induced) and forced the cascade to drop to the smaller dim more often than necessary.
- ExifPatcher creates IFD1 if original has no thumbnail
- **IFD0 sanitisation** in `scanIfd` (depth=0 only): any thumbnail-pointer tag (`Compression` 0x0103,
  `JPEGInterchangeFormat` 0x0201, `JPEGInterchangeFormatLength` 0x0202) found in IFD0 — a known corruption pattern
  from earlier Samsung Gallery edits and pre-fix CropCenter outputs that hoist IFD1 tags up into IFD0 — gets its
  entire 12-byte entry zeroed. The fresh IFD1 still lives at IFD0's `nextIfd` offset and carries the new
  thumbnail; the zeroed IFD0 entries prevent strict TIFF parsers from following the stale offsets into garbage
- **Direct file-path read bypasses Samsung MediaStore mangling** (`SafFileHelper.tryReadDirectlyFromPath`). Samsung's
  MediaStore ContentProvider rewrites the EXIF segment as it streams JPEG bytes through `openInputStream` — zeros out
  GPS sub-IFD value blocks and reorders IFD0 entries — likely a privacy-driven sanitisation pass on Samsung firmware.
  `readUriBytes` resolves the URI to a filesystem path (handles both `com.android.providers.media.documents` and
  `com.android.externalstorage.documents` SAF authorities, requires `MANAGE_EXTERNAL_STORAGE`) and reads via
  `FileInputStream` when possible, returning the on-disk bytes that still carry GPS. Falls back to the SAF stream copy
  for cloud or SAF-only sources where no filesystem path is resolvable.
- **SAF path-traversal guard** (`SafFileHelper.fileFromSafUri` + the `com.android.externalstorage.documents` branch of
  `getFilePathAndId`). Rejects docIds whose tail contains a `..` path segment — checked via the
  segment-aware `SafPaths.hasParentTraversalSegment` (splits on `/`, rejects only segments exactly equal to `..`) —
  or that begin with `/`. A substring `String.contains("..")` check would reject legitimate filenames whose
  characters happened to include `..` (Samsung's "IMG..edited.jpg" pattern), forcing them through the SAF stream path
  and losing the Samsung-MediaStore-bypass benefit above. Applied to the `primary:` volume handler, the `raw:` volume
  handler, AND the ExternalStorageProvider `relPath` branch. Prevents a crafted Share intent with
  `primary:../../data/data/com.othertarget/foo` from materialising a File outside the volume root on rooted devices.
- **TIFF orientation-tag validation** in both readers (`BitmapUtils.readExifOrientationInternal` for JPEG APP1 EXIF
  and `PngMetadataExtractor.extractOrientationInternal` for PNG eXIf). After accepting the byte-order field
  (`II` / `MM`), the reader also validates: TIFF magic = 42 (0x002A), Orientation entry type = SHORT (3),
  Orientation entry count = 1, Orientation value in [1, 8]. A malformed payload with plausible offsets and a
  coincidental tag-0x0112 byte sequence would otherwise rotate pixels even though the documented contract says
  malformed EXIF maps to upright; the four checks bring the contract back into agreement.

#### PNG eXIf (PNG 1.6 spec)
- **Loaded** via `PngMetadataExtractor`. Walks PNG chunks (8-byte signature + length / type / data / CRC chunks)
  for the lowercase-`e` eXIf chunk. Stores results in two parallel forms because the JPEG and PNG export paths
  have different size constraints:
  - `state.jpegMeta` carries a synthetic APP1 EXIF segment (capped at the JPEG APP1 u16 limit of 65535 bytes, so
    JPEG injection downstream doesn't write a malformed segment).
  - `state.pngExifTiff` carries the raw TIFF bytes uncapped, used by PNG → PNG export where the eXIf chunk's u31
    length field has no JPEG-side cap.
- **Orientation** is parsed at load time via `PngMetadataExtractor.extractOrientation` so PNG sources rotate pixels
  to display orientation matching JPEG behavior. Without this, a PNG with eXIf orientation=6 (rotate 90 CW) would
  display in stored pixel orientation while the export side normalises orientation to 1, baking a permanent
  sideways rotation into the saved file.
- **Saved** via `CropExporter.injectPngExifFromTiff` — writes a fresh u31-sized eXIf chunk after IHDR carrying the
  patched TIFF (orientation normalized to 1, dimensions rewritten to crop size by `ExifPatcher.patch`).
  PNG → PNG round-trips therefore preserve EXIF up to the eXIf chunk's u31 limit; PNG → JPEG conversions still
  drop > 64KB EXIF (with a warning log) because the JPEG APP1 segment-length field is u16. A malformed source
  TIFF that `ExifPatcher` rejects falls back to the synthetic-APP1 path so partial metadata isn't silently dropped.
- **Color management chunks** (`iCCP`, `sRGB`, `cICP`, `gAMA`, `cHRM`): NOT preserved across round-trip.
  `PngMetadataExtractor` parses only `eXIf`. The PNG decoder reads any source color profile to interpret pixel
  values during `BitmapFactory.decodeByteArray`, but `CropExporter.exportPng` writes the cropped bitmap with
  `Bitmap.compress(PNG)` against the editor's default sRGB canvas, so any source iCCP profile is lost. A source
  Display P3 PNG saved through CropCenter ships with whatever color metadata Skia's PNG encoder emits for an
  sRGB-tagged bitmap — typically an `sRGB` chunk and / or `gAMA` + `cHRM`, never the source iCCP. This is
  acceptable for the editor's use case (most camera / phone PNGs are already sRGB) but means CropCenter is not a
  color-managed PNG editor.

#### Samsung SEFT Trailer
- Extracted on load and re-appended on save **byte-for-byte** — a Gallery-edited file that's been re-edited in
  CropCenter keeps its working Revert chain because the trailer's backup-path reference still points at Gallery's own
  `/data/sec/photoeditor/` backup, which we never touched.
- **CropCenter does not generate fresh SEFT trailers for new edits.** Samsung Gallery's Revert pre-flight validates the
  trailer's backup path against Samsung-blessed locations like `/data/sec/photoeditor/` that third-party apps cannot
  write to. A SEFT we generate pointing at our own writable shared-storage location is silently rejected by Gallery, so
  fabricating one would produce disk bloat for no Revert benefit. A file edited first in CropCenter (no prior SEFT)
  saves with no SEFT and no Revert option in Gallery — that's expected.
- Requires `MANAGE_EXTERNAL_STORAGE` for the File-I/O strategy in collision-overwrite Replace AND for the Samsung
  MediaStore EXIF workaround (`SafFileHelper.tryReadDirectlyFromPath`); the grant prompt is offered from the Replace
  failure dialog (`ReplaceStrategy.showReplaceFailureDialog`) only when a collision-overwrite hits an SAF-permission
  limit, never up-front at app start or save-dialog open. Plain Save As flows that don't hit a collision never need this
  permission, so most users are never prompted.

#### HDR Gain Map
- Extracted via forward JPEG marker walking — `JpegMarkerWalker.findPrimaryEoi` walks both primary's marker chain to
  find its EOI, then walks the gain map's own marker chain (in a `[primaryEnd, file.length-8)` slice when SEFT is
  present, else `[primaryEnd, file.length)`) to find ITS EOI. This replaced an earlier backward `FFD9` scan that
  could land on a byte-stuffed `FFD9` inside SEFT data blocks (which can hold embedded thumbnails — themselves JPEGs
  ending in `FFD9` — and edit-history blobs).
- **HDR-source gate** (`HdrSignature.hasHdrgmInXmp` + `hasMpf` AND-gate): the gain-map extractor refuses to inspect
  post-primary FF D8 bytes unless the file carries BOTH an APP2 MPF segment (describes the multi-picture layout) AND
  the XMP `hdrgm` namespace marker INSIDE a parsed XMP APP1 segment (standard or extended). The XMP-restricted scan
  is critical — a full-file scan would falsely match a stray `hdrgm` 5-byte sequence in MakerNote / COM / vendor
  blob / SEFT edit history. The walk covers both the canonical 29-byte XMP_HEADER segment AND
  Adobe Extended XMP (`http://ns.adobe.com/xmp/extension/`) so a vendor whose hdrgm declaration spilled past the
  ~64 KB APP1 cap into an extension segment isn't mis-classified as SDR. MPF alone is also
  insufficient because it describes non-HDR multi-picture too (focus-stacked / panorama / ZSL bursts). Without
  the combined gate, an SDR Samsung file whose SEFT data block begins with an embedded JPEG thumbnail's FF D8 was
  mis-walked: the thumbnail extracted as a "gain map" and the saved file's re-appended SEFT trailer truncated
  past the thumbnail's FF D9. `SeftExtractor.extract` takes the symmetric
  `hasGainMap` hint so its trailer-start walk only steps past a gain-map EOI when one was actually present. The
  same combined gate drives `GraftWriter`'s per-side HDR detection so a graft of an SDR original doesn't
  synthesise a phantom gain map either. `HdrSignature.isHdrSource(byte[])` is retained as a SEPARATE post-compress
  diagnostic scanner used only by `UltraHdrCompat` to verify `Bitmap.compress` output emitted the marker — that
  scan operates on freshly-emitted JPEG bytes where the marker can only legitimately live in XMP, so the broader
  scan is safe and avoids re-parsing segments for a log line.
- Re-generated via canvas-based gainmap transform matching the primary
- Composed with primary via `GainMapComposer` + `MpfPatcher` for MPF offset correction
- **Fail-closed on Item:Length-in-Extended-XMP**: when `XmpItemLengthPatcher.patch` returns null because the
  GContainer `Item:Length` attribute lives in an Adobe Extended XMP chunk (or straddles a chunk boundary that
  the per-chunk scan would miss, but the reassembled-bytes scan catches), patching across
  the per-chunk reassembly headers is beyond the patcher's scope. `GainMapComposer.compose` checks for null and
  drops HDR rather than ship a file with stale `Item:Length` that strict decoders would interpret as a
  truncated gain map. Same metadata-strip path as the MPF-failure branch follows (see below).
- **Fail-closed on MPF anchor failure**: when `MpfPatcher.patch` returns false (no MPF segment, malformed/unsupported
  MPF, byte-order mismatch, 3+ image MPF without MPType match, **multi-gain-map MPF where >1 entry has MPType
  `0x010005`** — the spec-legal composite depth + Original Preservation case — negative relative offset, etc.),
  `GainMapComposer.compose` returns the primary verbatim instead of shipping the gain-map bytes appended without an MPF
  entry pointing at them. The multi-gainmap refusal exists because we have one post-edit gain-map size + offset and no
  way to assign it across multiple slots — patching only the first match would leave others pointing at pre-edit
  positions in the source file. Strict decoders' Revert pre-flight would reject an unanchored gain map; lenient
  decoders that scan for the `hdrgm` signature would render with the wrong offset; and the save's "[HDR OK]" /
  "[HDR dropped]" toast (driven by the structural `hdrAttached` flag plumbed through `ExportResult` — see below)
  would falsely announce success. A clean SDR JPEG is strictly safer than orphaned HDR.
- **HDR-drop metadata strip**: when the gain-map composition is skipped (UltraHdrCompat couldn't produce a valid
  output) OR fails inside `GainMapComposer.compose` (MPF patch rejects), the saved JPEG would otherwise still carry
  the source's XMP-`hdrgm` and APP2/MPF metadata describing the now-missing gain map. `CropExporter.exportJpeg`
  detects the drop via the explicit `GainMapComposer.ComposeResult.hdrAttached()` flag and re-injects metadata
  with `stripHdrSegments` (drops APP2/MPF and any XMP segment — standard or extended — flagged by
  `HdrSignature.isHdrgmXmpSegment`), so the file's metadata cannot lie about HDR presence and strict decoders
  see a coherent SDR file (the per-segment predicate covers Extended XMP, which an XMP-only check would miss).
  Prior heuristic (`withGainMap != withFullMeta` reference inequality) broke once GainMapComposer started
  returning the XMP-patched primary on the MPF-fail path — that's a freshly-allocated array distinct from
  `withFullMeta`, so the inequality wrongly fired `hdrAttached=true` and skipped stripping.
- **Structural HDR-OK reporting**: `CropExporter.export` returns an `ExportResult(bytes, hdrAttached)` record
  rather than raw bytes. `hdrAttached` is sourced from `ComposeResult.hdrAttached()` — an explicit boolean
  field set true ONLY when `GainMapComposer.compose` actually appended the gain map AND patched MPF offsets
  to point at it. False on every drop path: UltraHdrCompat failure, MPF-patch rejection, malformed source MPF,
  or the explicit drop-and-re-inject branch after the strip above. `ExportPipeline.reportSuccess` consumes
  this flag directly to drive the "[HDR OK]" / "[HDR dropped]" suffix — replaces an earlier full-file substring
  scan for "hdrgm" that false-positive'd on preserved trailers, stale metadata the strip didn't catch, and
  Extended-XMP segments. The bypass-encode path (no transforms, original bytes verbatim)
  reports `hdrAttached = srcHadHdr` since the bytes carry the source's gain map intact.

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

Settings dialog (opened via the settings icon in the toolbar) is a single scrollable view with four cards. Toggles and
color-picker selections commit to `state.updateGridConfig` immediately as the user interacts; the Cols / Rows
EditText values are deferred to the "Done" button so a partial typed entry doesn't fire a re-render mid-keystroke.

- **Grid card** — Cols / Rows numeric inputs (clamped to [1, 50]), preset chips (2x2 / 3x3 / … / 8x8), line color
  (opens `ColorPickerDialog`), line width seek bar (1–20 px). Cross-references §7.
- **Pixel Grid card** — toggle for the 6x+ zoom pixel-grid overlay, plus its color picker. Cross-references §7.
- **Selection & Paint card** — single shared color used for selection points, polygon fill, and horizon paint
  (kept separate from grid color so the paint surface stays visible against an arbitrary grid hue).
- **Build card** — `BuildConfig.BUILD_TIME` (the build's compile timestamp injected by `app/build.gradle`),
  used to verify which APK is installed on the device. The card heading is the literal string "Build" (not
  "Build / About") — there is no separate About / credits / version-name content.

Tapping Settings while a save / load / detect / graft is in flight surfaces the busy toast instead of opening the
dialog (mirrors `SaveController.showSaveDialog`'s busy gate). The complementary already-open-dialog race is closed by
the §1 "Transient-dialog forced dismissal" contract — `ImageLoadController.load` and `applyGraftedBytes` dismiss any
open Settings dialog before any bg work begins, so an inbound Share/View intent or graft apply visibly closes the
dialog rather than racing its in-dialog `state.gridConfig` commits against the bg `state.reset()`.

### 12. Apply External Edit (In-Memory Pixel Graft)

**Purpose**: Apply a small external pixel edit (typically Photoshop Generative Fill / Generative Remove) to a Samsung
Ultra HDR original while preserving Samsung Gallery's Revert button, the original's gain map, and identity metadata
(Make, Model, GPS, MakerNote, DateTimeOriginal, lens info, SEFT trailer). The user picks an externally-edited copy of
the loaded photo; CropCenter splices the edit's pixel content into the original's metadata container, applies the result
as the in-memory image, and saves through the canvas-encoded pipeline so the output is colour-managed (Display P3) and
viewer-compatible.

**Recommended editor: Photoshop, opened in pixel space (Camera Raw → File Handling → JPEGs → Disabled).** Photoshop
preserves the source pixels everywhere except the AI-edited region, with only ICC-encoding-level differences from the
original (mean per-pixel diff vs Samsung original ≈ 1 level after canvas P3 conversion; Lightroom's HDR-tone-mapped
output produces ≈ 13 levels and a visible tonal seam at the fill boundary). Other editors work if they meet the same
constraint — pixel-space editing, no global tone-curve shift.

**Why Revert works**: Samsung Gallery reads the `originalPath` value from the SEFT trailer's `PhotoEditor_Re_Edit_Data`
block and serves whatever JPEG it finds at that path. The graft preserves original's SEFT verbatim, plus original's MPF
segment shape (substituting Adobe's MPF reliably breaks Gallery's Revert pre-flight), so any backup chain the user
already had stays intact.

**Entry point**: Long-press the toolbar **Open** button. Available only when (a) an image is loaded, (b) the loaded
image is JPEG. (Long-press chosen over a new toolbar icon to avoid clutter; Open and Apply-External-Edit are
semantically related — both load external files — so the gesture pairing is intuitive.)

**Flow**:
1. User loads the original Samsung HDR JPEG (the metadata source) into CropCenter normally.
2. User long-presses Open.
3. SAF `ACTION_OPEN_DOCUMENT` (image/jpeg) → user picks the external edit (the pixel donor).
4. Validation (`EditAligner.align`):
   - Picked file is a JPEG (SOI = `FFD8`).
   - **Display** dimensions match between loaded and picked (stored dims after applying each side's EXIF orientation).
     Photoshop tends to write its export with the orientation flag normalized to 1, so the stored layouts may differ
     even when the visible pixels align — comparing display dims rather than stored dims accommodates that.
   - When stored layouts differ but display layouts match, the edit JPEG is decoded and re-encoded back to the
     original's stored layout via `Bitmap.compress(JPEG, 100)` before splicing. This adds ~1 channel-noise level to the
     edit pixels (same noise floor the save-time canvas pass would add anyway) but produces a graft whose primary scan
     is decoder-coherent against the original's EXIF orientation tag.
   - Mismatched display dimensions → toast `"Edit dimensions don't match the source (source WxH, edit WxH) —
     re-crop in the editor and re-export"` and abort (full toast format pinned in `EditAligner` and listed in
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
   `state.installGraft(graft)` — installGraft atomically sets `graftApplied=true` (which gates the verbatim-write
   bypass) and stashes the AI mask. A decode failure leaves the previously-loaded image intact and surfaces "Failed to
   decode" to the user. The user's saved AR preference applies to the post-graft crop the same way it does on a normal
   image load — if they want the full image, they pick "Full" in the spinner.
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
| APP2/MPF | original (offset-patched) | Samsung-shape MPF is what Gallery's Revert pre-flight recognises; edit's MPF (Adobe-flavoured `MPType` for the gain-map entry) breaks Revert |
| vendor APPs (APP3-APP15), COM | original | Samsung sensor hints, scene labels |
| DQT, DHT, SOF, SOS+scan, EOI | **edit** | the AI-edited pixels — byte-verbatim from edit's primary |
| gain map JPEG | **original, AI-region inpainted at HDR re-encode time** | preserves Samsung's HDR rendering across the unedited area; the AI region's gain values are replaced with their unmasked-neighbor average so the boost in the fill matches its surroundings instead of the original (now-removed) features. Inpaint runs against the gain-map Bitmap inside `UltraHdrCompat.compressWithGainmap` (not against the JPEG bytes at splice time) so the single-channel grayscale container survives the save's `Bitmap.compress` call |
| SEFT trailer | original | the sole reason Gallery still surfaces and successfully services Revert |

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
  splice.
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
  `ARGB_8888` (Adobe's variant). Any other config (e.g. `RGB_565`, `RGBA_F16` from a future Android version) hits
  the inpaint dispatcher's no-op-with-warn-log branch and ships the source gain map untouched — silently
  downsampling unfamiliar pixel formats through 8-bit `getPixels` / `setPixels` would corrupt the boost values, so
  the safe default is to pass the gain map through verbatim. `HARDWARE` config is short-circuited earlier by the
  `!isMutable()` guard since HARDWARE bitmaps are always immutable.
- **Vendor APPs (`STRIP_VENDOR_APPS=false`)**: confirmed no rendering effect; Samsung sensor / scene identity data
  preserved.

**`ExportPipeline.canBypassEncode`**: returns `true` only when output is JPEG, source is JPEG, **no graft applied**, no
rotation, no grid bake-in, source bytes available, (no crop OR full-image crop), AND the source carries an IFD1
thumbnail (`ExifPatcher.hasIfd1Thumbnail(state.getJpegMeta())`). The graft-applied check is what forces graft saves
through `CropExporter.export`; without it, the verbatim-write bypass would ship source's gain map (calibrated for
source's primary) over the spliced primary, breaking spatial alignment after a later crop. The IFD1-thumbnail check is
the user-reported fix for thumbnail leaks: screenshots / generated images / minimal-EXIF JPEGs would otherwise verbatim-ship their
empty-IFD1 state, so the user-facing thumbnail in Files / Gallery / EXIF viewers would be missing. Forcing the
re-encode path lets `CropExporter`'s synthesise-fresh-EXIF chain add a thumbnail.

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
- Picker dimension probe fails — three distinct failure modes with distinct messages so the user gets actionable
  remediation:
  - SOI signature mismatch (HEIC / WebP / PNG bytes leaked past the picker's MIME filter) → toast "Selected file
    is not a JPEG", abort.
  - Source-side BitmapFactory dim probe returns null (memory pressure / bg-write race nulled the previously-loaded
    source bytes) → toast "Source image is corrupt — reload it", abort.
  - Edit-side BitmapFactory dim probe returns null (corrupt edit JPEG past valid SOI) → toast "Couldn't decode the
    edit — try exporting again", abort.
- Display dimensions don't match → toast `"Edit dimensions don't match the source (source WxH, edit WxH) — re-crop
  in the editor and re-export"`, abort.
- Re-orient re-encode fails (BitmapFactory rejects the edit bytes mid-reorient — same failure mode as the edit-side
  dim-probe-null case, so the message routes to the same remediation) → toast "Couldn't decode the edit during
  reorientation — try exporting again", abort.
- Loaded image isn't JPEG → toast "Apply External Edit only works on JPEG sources" (refused at long-press time, picker
  never opens).
- GraftWriter splice fails (edit isn't a JPEG, missing SOI / primary EOI, malformed segments) → toast "Graft failed:
  <reason>", in-memory image unchanged.
- Decode of grafted bytes fails → toast "Graft produced an undecodable result — apply aborted" (more specific than
  the load-flow's "Failed to decode" so users distinguish a graft-pipeline regression from a corrupt source;
  developer-facing breadcrumb at `Log.w(TAG, "graft splice produced undecodable bytes...")`), in-memory image
  unchanged.
- SAF read of the picked edit URI fails (provider permission denial, IOException, fewer than 4 bytes returned) →
  toast "Couldn't read picked edit", abort. Fires before any decode / dimension probe runs.
- Source snapshot bytes lost between long-press and SAF return (rare; can happen on memory pressure that nulls the
  captured `originalBytes`) → toast "Original bytes unavailable — reload the image and try again", abort.
- Source bytes null at long-press (state.originalFileBytes was nulled between load and the long-press tap) → toast
  "Original bytes unavailable — reload the image" (no "and try again" suffix), abort. Distinct from the
  similarly-worded toast above which fires DURING `assembleGraftOnBg` after the snapshot has been claimed; this
  one fires earlier in `GraftController.start()` before any picker opens.
- Generic exception from `applyBytes` / `installGraft` in `MainActivity.applyGraftedBytesOnBg` (post-isDestroyed
  races, OOM during the bitmap install, any other RuntimeException reaching the catch) → toast
  "Apply failed: <message>", abort. Distinct from the SOI / decode / dimension errors above which surface
  earlier in the pipeline; this catches anything that escapes past the graft splice.

**Verification**: save the grafted file, open in Samsung Gallery, confirm the Revert button appears and successfully
restores the original. Inspect the saved file's EXIF in any external tool (exiftool, ImageMagick `identify -verbose`) to
confirm GPS coordinates, MakerNote, and other camera tags are preserved.

**Out of scope**: PNG inputs (SEFT is JPEG-specific). HEIC inputs (different metadata system). Differing-dimension edits
(would need re-encode; refused with toast). Per-region gain-map regeneration (would need a way to derive HDR data for
AI-fill content; the current "use original's gain map verbatim" works for low-frequency fills). Mask-based selective
composite (preserve source bytes outside the AI region — useful for editors that produce larger tonal shifts than
Photoshop, currently not needed because Photoshop is the recommended editor and produces minimal tonal shift).

**Iterative graft compounding**: each save runs the canvas re-encode pass, which adds ~1 level mean per-channel diff vs
the input. Cycling `load → graft → save → load → graft → save` would compound the noise (~N levels after N cycles).
Mitigation: do all AI-fill operations in a single Photoshop session against the original Samsung JPEG, then graft once —
multi-fill in one session adds no compounding regardless of how many objects are removed. Lossless MCU-level transcoding
would eliminate per-cycle noise entirely (~500 LSLOC + libjpeg-turbo NDK), not implemented because the workaround is
trivial and noise is invisible below ~5 cycles.

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
  `metadata/JpegSegment`, `metadata/ExtendedXmpReassembler.ExtendedXmpChunk`,
  `metadata/XmpItemLengthPatcher.SegmentPatchResult`, `crop/CropFitContext`,
  `crop/ExportResult`, `util/AiRegionDetector.AiMask`, `view/RotationRulerView.TickConfig`,
  `graft/EditAligner.Result`, `GraftController.SourceSnapshot`, `ReplaceStrategy.VerifyFailure`,
  `ExportPipeline.WriteOutcome`, `SaveController.PriorSaveSnapshot`, `ImageLoadController.MetadataExtraction` —
  immutable value types replace boilerplate POJOs
- **Enums**: `model/Format` (JPEG / PNG with `extension()` and `mimeType()`), `model/AspectRatio.*` constants,
  `model/CenterMode`, `model/EditorMode`
- **Switch expressions**: Arrow syntax throughout (`ExifPatcher`, `BitmapUtils`, `CropExporter`,
  `RotationRulerView`)
- **`Math.clamp`**: used wherever a value needs `[lo, hi]` clamping instead of hand-rolled `max(min(...))`
- **`var`**: used sparingly where the right-hand-side type is obvious
- **Pattern matching for `instanceof`**: in metadata-segment dispatch sites
- **`InputStream.transferTo` / `readNBytes`**: stdlib I/O in `SafFileHelper.copyUriContents` and `readUriBytes` instead
  of hand-rolled byte-loop / partial-read accounting
- **Consolidated utilities**: `BitmapUtils.drawCropped` shared between `CropExporter` and `UltraHdrCompat.renderPrimary` so primary-byte output is byte-identical; `BitmapUtils.orientationMatrix` + `applyOrientation` shared for stored→display EXIF rotation;
  `RotationMath` is the single source of truth for rotation; `SafFileHelper` + `SafPaths` consolidate SAF/MediaStore URI
  helpers; `JpegMarker` consolidates marker-byte constants across the five byte-walking parsers; `DpToPx.toPx` is the
  single dp→px conversion (Math.round-based, never `(int)` truncation); `JpegSegment.XMP_HEADER` is deduped from prior
  multi-site literals (the `ALL_FILES_ACCESS` literal is centralised within `ReplaceStrategy`, the only file that uses
  it).

---

## Code Organization

The full coding-style contract lives in `CLAUDE.md` (Allman braces, 120-column wrap, alphabetical method ordering,
tabs-only indent, javadoc rules, naming conventions, etc.). The load-bearing structural invariants the rest of the spec
depends on:

- **Field ordering within a class**: tier order is `static final` → `static` (non-final) → `final` (instance) → regular
  instance. Within each tier, sort by type (uppercase types before lowercase primitives), then by name. One variable per
  line.
- **Method ordering within a class**: constructors first; then methods grouped by access (`public` → `protected` →
  package-private → `private`). Within each access tier, **static methods come before instance methods**, and
  alphabetical sort applies separately within each sub-block (the static methods sort among themselves, then the
  instance methods sort among themselves). Don't pair getters with setters — strict alphabetical keeps the file
  Ctrl-F-scannable.
- **Constructors are above the access tiers**: they don't participate in the static-vs-instance ordering rule.
- **Canonical helpers are single chokepoints**: `util/DpToPx`, `util/RotationMath`, `util/BitmapUtils.drawCropped`,
  `util/BitmapUtils.ROTATION_EPSILON`, `metadata/JpegMarker`, `metadata/JpegMarkerWalker`,
  `metadata/JpegSegment.XMP_HEADER`, `metadata/ExtendedXmpReassembler`, `metadata/HdrSignature`,
  `metadata/TiffTag`, `metadata/XmpItemLengthPatcher`, `model/AspectRatio.snap`, `model/Format`,
  `model/StateBus`, `crop/CropFitContext`, `crop/CropRender`, `util/SafPaths`, `view/DialogStrings`.
  Reach for the helper rather than re-rolling.

Self-audit scripts in `CLAUDE.md` cover these mechanically — `scripts/audit.py` is the consolidated runner
(subcommands: `over-cols`, `ignored-catches`, `static-first`, `method-order`, `adjacent-comment-styles`,
`final-classes`, `reflow`, `lsloc`; no-arg form runs all), and the awk one-liners catch double-indents /
over-width lines / inline FQNs / HTML entities / dp-px truncation.

---

## Known Limitations

1. **Canvas re-encoding**: `Bitmap.compress()` re-encodes the JPEG, changing quality and file size vs. original.
2. **PNG metadata**: Only EXIF is injected (via eXIf chunk). Other PNG ancillary chunks are not preserved. HDR is not
   possible in PNG format.
3. **Single image**: Only one image can be open at a time.
4. **Large files**: Files > 128MB are rejected (`SafFileHelper.MAX_READ_BYTES`). Entire file is read into memory via a
   per-call `createTempFile` cache file (so concurrent loads don't clobber each other). Sources whose decoded pixel
   count exceeds the **device-adaptive cap** `BitmapUtils.getMaxDecodePixels()` are subsampled at decode time via a
   two-pass `BitmapFactory` walk — the bounds pre-pass reads the SOF dimensions only,
   `BitmapUtils.computeInSampleSize` picks the smallest power-of-2 sample size that fits the subsampled bitmap
   within the cap, then the real decode runs at that subsampling. The cap is set once at `MainActivity.onCreate`
   via `BitmapUtils.initialize(Context)`, which reads `ActivityManager.getMemoryInfo().totalMem` and budgets 1/16
   of total RAM (4 bytes per ARGB pixel), clamped to `[32 MP, 512 MP]`. On a 12 GB-RAM Samsung flagship the cap
   reaches ~187 MP so 200 MP captures decode at `inSampleSize=1` (no quality loss); on a 4 GB phone the cap is
   ~64 MP and a 200 MP source decodes at `inSampleSize=2` (~50 MP); a hypothetical 2 GB phone floors at 32 MP and
   gets `inSampleSize=4` (~12.6 MP / ~50 MB ARGB) instead of instant-OOM at the un-subsampled 800 MB ARGB
   allocation. **The cap is enforced at
   the consistent-subsampling decode sites**: `ImageLoadController.applyBytes` at load and
   `UltraHdrCompat.decodeHdrBitmap` at HDR-save re-decode time. Both pull from `state.getOriginalFileBytes()` so both
   land on identical `inSampleSize` values via the same `BitmapUtils.computeInSampleSize` math — the HDR re-decode's
   coordinates stay self-consistent with the load-time subsampled `CropRender.cropW/cropH`.
   `EditAligner.reorientEdit` deliberately does NOT subsample:
   `GraftWriter.graft` splices the re-encoded edit's primary scan into the original's full-resolution EXIF / MPF /
   gainmap / SEFT package, and a downsampled primary would disagree with the full-resolution metadata describing
   dimensions and gainmap offsets — silently misaligning the HDR gainmap on a Samsung Revert chain. For a 200 MP
   source + edit pair, `reorientEdit` catches `OutOfMemoryError` and returns null, surfacing the same "Couldn't
   decode the edit during reorientation" toast the BitmapFactory decode-null path produces. Skia's HDR-aware decoder applies inSampleSize to both the primary and
   the embedded gainmap consistently, so the gainmap-to-primary ratio (typically 4:1 quarter-res) stays correct after
   subsampling and the downstream renderGainmap math is unaffected. Trade-off: saved-crop output of a subsampled
   source is at the subsampled resolution — full-res in-memory work on 200 MP sources would exceed any Android
   device's heap so the visible loss is theoretical (no Android display can render 200 MP anyway). The path through
   CropExporter still preserves source-level metadata (EXIF, ICC, gain map) so a subsampled output remains
   structurally complete; only pixel resolution scales down.
5. **MediaStore owner on plain Save As**: `ACTION_CREATE_DOCUMENT` to a different directory creates a new file with a
   different MediaStore owner. Same-directory same-name saves route through the Replace flow which preserves the
   original document where the provider supports it.
6. **Samsung Revert** only works for files that came in with an existing SEFT trailer (i.e., a Gallery-edited file
   re-edited in CropCenter). For files first edited in CropCenter, Gallery does not surface a Revert option — Gallery's
   Revert validates the backup path against Samsung-blessed locations a third-party app cannot write to, so fabricating
   SEFTs pointing at our own shared-storage writes is a no-op that we explicitly skip.
7. **EXIF thumbnail overflow**: If original EXIF metadata + new thumbnail would exceed the 65535-byte APP1 limit,
   thumbnail is reduced or dropped. `oldThumbLen` is sanity-clamped against `data.length` to prevent malformed source
   EXIF from inflating the budget calculation.
8. **No saved instance state**: Configuration changes handled via `configChanges`; process death loses all crop state.
9. **Opaque-ID providers**: Providers without document-ID path encoding (some cloud / SD-card providers) lose the
   strongest collision-detection paths. The Save flow trusts SAF auto-rename as collision evidence on those providers —
   false positives surface as a Replace dialog the user can dismiss with Keep, never as silent data loss.
