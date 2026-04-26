# CropCenter - Application Specification

## Overview

CropCenter is a native Android image cropping tool focused on precise center-based cropping with full metadata preservation, including Samsung Ultra HDR gain map data and Samsung SEFT trailer for Galaxy Gallery Revert support.

**Package**: `com.cropcenter`
**Min SDK**: 35 (Android 15)
**Target/Compile SDK**: 36
**Language**: Java 21
**Build**: AGP 9.1.1, Gradle 9.3.1
**LSLOC**: ~5,500 Java + ~360 XML (UCC-style: statements + control structures + declarations, excluding scaffolding braces)

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
| Mode: [Select] [Move] | Lock-axis: [Both] [H] [V]                 |
+--------------------------------------------------------------------+
| Points: [Undo] [Redo] [Clear]                  [Auto] (if loaded) |
+--------------------------------------------------------------------+
| Rotation ruler (scrollable, Galaxy-style) [zoom -/+]               |
+--------------------------------------------------------------------+
| Info: image size | crop size | rotation | zoom                     |
+--------------------------------------------------------------------+
```

The toolbar's `Pan` checkbox toggles drag-pans-viewport vs drag-moves-crop in
Move mode. The `Lock` checkbox is the freeze-crop ("CenterMode.LOCKED") gate —
when on, drags pan the viewport regardless of mode. `Settings` opens the
combined Settings dialog (grid config, pixel-grid color, About).

### Key Components

| Component | Class | Purpose |
|-----------|-------|---------|
| State | `model/CropState` | Central state: crop params, metadata, rotation anchor (stable intent center for no-selection rotations); volatile selectionPoints/jpegMeta lists for safe load-time swap |
| Crop Math | `crop/CropEngine` | Computes crop from center + AR + lock + rotation; keeps cropX continuous mid-rotation, with parity-snap applied at drag-release in `CropEditorView.onPanRelease` |
| Horizon | `util/HorizonDetector` | Auto-rotation: metadata pass first, fallback to painted-region Hough transform |
| Export | `crop/CropExporter` | Full pipeline: crop, rotate, compress, HDR, EXIF, SEFT |
| Editor | `view/CropEditorView` | Custom View: rendering + gestures + undo/redo |
| Gestures | `view/TouchGestureHandler` | Pinch zoom, tap, drag, long-press; emits onPanRelease for parity-snap on drag end |
| Grid | `view/GridRenderer` | Grid overlay with line positions snapped to integer image pixels (matches `CropExporter.gridLinePixel`) |
| Rotation | `view/RotationRulerView` | Galaxy-style scrollable ruler with snap-to-detent and pinch-to-zoom scale |
| Color Picker | `view/ColorPickerDialog` | Tap-to-select grid + alpha + hex input |
| Settings | `view/SettingsDialog` | Combined dialog: grid config (cols, rows, presets 2x2–8x8, color, width), pixel-grid toggle/color, About (build-time version) |
| Save Dialog | `view/SaveDialog` | Filename, format, export-grid bake-in toggle |
| Save Flow | `SaveController` + `ReplaceStrategy` + `ExportPipeline` | SAF picker routing, collision detection (auto-rename + sibling-create), crash-safe write-then-swap with Samsung Revert backup pre-encode |

### Metadata Pipeline

| Class | Purpose |
|-------|---------|
| `metadata/JpegMetadataExtractor` | Extract all APP/COM segments from JPEG header |
| `metadata/JpegMetadataInjector` | Replace re-encoder's APP markers with originals |
| `metadata/ExifPatcher` | Update orientation, dimensions, thumbnail in EXIF |
| `metadata/GainMapExtractor` | Extract HDR gain map from between primary EOI and SEFT |
| `metadata/GainMapComposer` | Append gain map + trigger MPF patch |
| `metadata/MpfPatcher` | Fix MPF APP2 offsets after primary size changes |
| `metadata/SeftBuilder` | Build Samsung SEFT trailer for Galaxy Gallery Revert |
| `metadata/SeftExtractor` | Extract existing SEFT trailer |
| `metadata/JpegSegment` | Data class for a single JPEG marker segment |

### Utilities

| Class | Purpose |
|-------|---------|
| `util/BitmapUtils` | EXIF orientation reading, `orientationMatrix()` (shared) |
| `util/ByteBufferUtils` | Endian-aware read/write with bounds checking |
| `util/HorizonDetector` | Sobel + Hough horizon detection (used by auto-rotate fallback) |
| `util/RotationMath` | `rotate(x, y, cx, cy, deg)` / `inverse(...)` helpers, single source of truth for rotation math |
| `util/SafFileHelper` | SAF/MediaStore URI helpers: copy, derive sibling, file-from-URI, query size, content-readback verify, create-sibling-placeholder, persistable bytes read |
| `util/StoragePermissionHelper` | MANAGE_EXTERNAL_STORAGE detection + settings deep-link |
| `util/TextFormat` | Locale-safe number formatting for the info bar |
| `util/ThemeColors` | Catppuccin Mocha int constants for code paths without a Context |
| `util/UltraHdrCompat` | Android 14+ Gainmap API: canvas-based HDR export |

---

## Features

### 1. Image Loading

**Supported formats**: JPEG, PNG (detected by magic bytes)

**Loading flow**:
1. Copy URI to cache file (bypasses ContentProvider JPEG processing)
2. Read raw bytes from local file
3. Decode to Bitmap, apply EXIF rotation
4. For JPEG: extract metadata segments, gain map, SEFT trailer, ICC profile
5. Query MediaStore for file path and ID (for Samsung Revert)
6. Display Samsung original data indicator if SEFT trailer present

**Input methods**:
- Open button: `ACTION_OPEN_DOCUMENT` (JPEG, PNG)
- Share intent: `ACTION_SEND` with `image/*`
- View intent: `ACTION_VIEW` with `image/*`

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
- Crop **size is preserved** in Move mode — `recomputeCrop` runs with `cropSizeDirty=false` so only the center is re-clamped against the rotated bounds; cropW/cropH never change
- During the drag the center stays continuous (sub-pixel) for smooth motion; the drag's fractional accumulator lives in a separate "anchor" state so high-zoom slow drags build up across events without losing motion to the rotation clamp
- On finger lift (`onPanRelease`), the center snaps to the parity that makes `cropImgX = centerX − cropW/2` integer (cropW even → centerX rounded; cropW odd → centerX floor + 0.5). This pixel-aligns the crop borders and grid without per-frame snap (which would cause flicker as cropW oscillates during rotation)
- Crop rectangle cannot be dragged outside image bounds (rotation-aware binary search inside `setCenter`)
- Cross-axis drift on a locked axis is bounded to 0.5 px per event and rejected above that threshold
- Tap does nothing (prevents accidental crop placement)

### 3. Lock Modes

The lock-axis row at the bottom of the editor has three buttons (Both / H / V).
Two independent toolbar checkboxes — **Pan** and **Lock** — modulate that row's
behavior:

| Mode (lock-axis button) | Select Behavior | Move Behavior |
|------|----------------|---------------|
| Both | Symmetric on both axes around point midpoint | Drag moves both axes |
| H | Center horizontally on points, maximize vertically | Drag moves X only |
| V | Center vertically on points, maximize horizontally | Drag moves Y only |

**Toolbar `Pan` checkbox**: when on, sets `CenterMode.LOCKED`, which makes
drags pan the viewport regardless of the lock-axis selection (effectively
overriding the row above for the duration the box is checked). Unchecking
restores the previously-selected lock-axis mode.

**Toolbar `Lock` checkbox** (`chkLockCenter`): independent of `Pan`. When on,
selection-point edits do NOT auto-recompute the crop center — the user can add
or remove points without the crop moving. Off (default) gives the documented
auto-center behavior.

**Select mode centering logic**:
- Locked axis: center = midpoint of selection points, crop extent = symmetric from center
- Free axis: center = midpoint of points (best-effort), crop extent = full image dimension; center shifts only if needed to keep the crop in bounds
- With rotation: a second pass of `maxScaleForRotation` shrinks the crop if the rotation-clamped center makes it too large; selection points are rotated through `rotatedSelectionMidpoint` so the rotated AABB midpoint (not the un-rotated one) drives the center under non-zero rotation

Per-mode lock preferences (Both/H/V) are remembered independently for Move and Select. Defaults are **V** in Move and **Both** in Select. "Both" button is only visible in Select mode.

### 4. Aspect Ratio

**Spinner labels in order**: 4:5 (default), Full, 16:9, 3:2, 4:3, 5:4, 1:1, 3:4, 2:3, 9:16, Custom. "Full" is the no-AR-constraint option (`AspectRatio.FREE` with width=height=0).

**Custom AR**: Dialog with width:height inputs when "Custom" is selected; constructs a fresh `AspectRatio(w, h)` and assigns it.

**Auto-crop**: Changing AR auto-creates a crop at image center if none exists.

### 5. Rotation

**Galaxy-style scrollable ruler** (persistent, below point controls):
- Full range: -180.0 to +180.0 degrees at 0.1 degree resolution
- Drag to scroll with momentum fling via OverScroller
- Snap-to-detent at 0, +-45, +-90, +-180 degrees (within 0.8 degree threshold)
- Tick marks: minor every 1 degree, major every 5 degrees with labels, detent ticks at key angles
- Center indicator: mauve triangle + line; zero marker in red
- Degree readout in info bar (visible only when rotation != 0), tappable for exact numeric input
- Ruler disabled (30% opacity, no touch) when no image loaded

**Auto-rotate button** (in the Points row, hidden until an image is loaded):
- First attempts horizon detection from JPEG metadata via `HorizonDetector.detectFromMetadata`
- Falls back to `detectFromPaintedRegion` — a Canny-style edge detection + Hough transform over the user-painted horizon region on the source bitmap

**Rotation + crop interaction**:
- Crop auto-resizes to fit within rotated image bounds
- Center clamping uses 4-corner un-rotation check with binary search
- No-selection rotations use a stable "intent anchor" (`CropState.anchor{X,Y}`) so repeated rotations don't drift the crop center across recomputes. The anchor is updated when the user pans/drags or resets, and left alone through rotation ticks and AR changes
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
- Settings dialog opens via the toolbar gear icon (combined dialog covers grid + pixel-grid + about)
- Grid-count presets: 2x2 through 8x8; arbitrary cols/rows via numeric input
- Configurable color (via `ColorPickerDialog`), line width (1-20px)
- **Line positions snap to integer image pixels** matching `CropExporter.gridLinePixel`'s rounding: first-half lines at `Math.round(cropExtent * i / count)`, second-half lines mirror through `cropExtent`. The middle line at count ∈ {2, 4} keeps `cropCenter` (half-integer for odd cropExtent) so single-point selection markers sit at the grid intersection — the only case where preview diverges from export by 0.5 px
- Line width scales by image-to-screen ratio (preview matches export)
- Pixel grid at 6x+ zoom (separate toggle + configurable color in Settings)
- Selection points and polygon use grid color
- Optional bake-in to exported image (`includeInExport`); grid + HDR supported. Reset on new image load — bake-in is a per-save choice, not a persistent preference

### 8. Undo/Redo

- Full undo/redo for selection points (50-step history)
- Buttons greyed out when not applicable
- Clear button removes all points and resets crop to full image
- History cleared on new image load
- Controls visible only in Select mode (they act on selection points; the row is hidden in Move mode where there's nothing for them to do). The history itself persists across mode switches — switching back to Select restores the buttons with their previous enabled state.

### 9. Export

**Save flow**: Always `ACTION_CREATE_DOCUMENT` with format-aware MIME type (image/jpeg or image/png). Collisions inside the user's chosen directory route through `ReplaceStrategy`'s crash-safe write-then-swap (Strategy A: File-I/O atomic move; B: SAF direct overwrite with byte-for-byte verify; C: SAF rename-with-fallback). Same-name results from `ACTION_CREATE_DOCUMENT` (provider-confirmed overwrites) get a sibling placeholder via `DocumentsContract.createDocument` and route through the same Replace flow; opaque-ID providers fall back to `exportOverwriteWithBackup` (direct write + Samsung Revert backup + preserve-on-failure).

**No-edit bypass**: when the user has applied no transformations (no crop, no rotation, no grid bake-in, JPEG-to-JPEG round-trip) AND the in-memory image is not a graft (`!state.isGraftApplied()`), `ExportPipeline` writes `state.originalFileBytes` verbatim instead of canvas-encoding. Preserves byte-perfect fidelity for re-saves of unmodified Samsung originals. Cropped / rotated / grid-baked saves AND any graft save go through the canvas-encode + ExifPatcher pipeline. Graft saves are explicitly excluded from the bypass because the splice's `originalFileBytes` carries the edit's foreign ICC profile; bypassing skips the canvas-managed Display P3 conversion that the cropped graft path runs and would produce a slightly different output structure than save-after-crop.

**JPEG quality**: 100 (hardcoded, always maximum) when canvas-encoding; verbatim when bypassing.

**HDR Export Pipeline** (all cases use canvas rendering for primary):
```
Original JPEG -> decode with gainmap -> render primary on cropW x cropH canvas
  (same rotation/positioning as preview: rotate around image center)
-> apply identical transform to gainmap bitmap at gainmap resolution
-> attach gainmap to output bitmap -> Bitmap.compress -> Ultra HDR JPEG
-> extract gain map portion -> compose with canvas-rendered primary
-> inject original EXIF (patched) -> append SEFT trailer
```

The gain map undergoes the exact same canvas transform as the primary (same position, pivot, angle, scaled to gainmap resolution), guaranteeing spatial alignment regardless of rotation or crop position.

**Grid + HDR**: The gain map is extracted from the HDR path and composed with the canvas primary (which has the grid baked in). The XMP hdrgm metadata from the original is preserved via `JpegMetadataInjector`.

**Non-HDR JPEG Export**:
```
Canvas-rendered bitmap -> Bitmap.compress(JPEG, 100) -> inject original metadata
-> append gain map bytes (fallback) -> fix MPF -> append SEFT
```

**PNG Export**:
```
Canvas-rendered bitmap -> Bitmap.compress(PNG) -> inject EXIF via eXIf chunk
(PNG 1.6 spec: raw TIFF data in CRC32'd chunk after IHDR)
```

### 10. Metadata Preservation

#### EXIF
- All original APP/COM segments preserved verbatim (camera model, GPS coordinates, MakerNotes, ICC, XMP, Software, DateTimeOriginal, lens info)
- Orientation tag set to 1 (output is always in display orientation)
- Dimensions updated to crop size
- Thumbnail regenerated from cropped bitmap (max 1024px long edge, quality reduced to fit a ~60KB budget within the 65535-byte APP1 segment limit; budget is computed from the source's existing EXIF size with a sanity clamp on out-of-range `oldThumbLen`)
- ExifPatcher creates IFD1 if original has no thumbnail
- **Direct file-path read bypasses Samsung MediaStore mangling** (`SafFileHelper.tryReadDirectlyFromPath`). Samsung's MediaStore ContentProvider rewrites the EXIF segment as it streams JPEG bytes through `openInputStream` — zeros out GPS sub-IFD value blocks and reorders IFD0 entries — likely a privacy-driven sanitisation pass on Samsung firmware. `readUriBytes` resolves the URI to a filesystem path (handles both `com.android.providers.media.documents` and `com.android.externalstorage.documents` SAF authorities, requires `MANAGE_EXTERNAL_STORAGE`) and reads via `FileInputStream` when possible, returning the on-disk bytes that still carry GPS. Falls back to the SAF stream copy for cloud or SAF-only sources where no filesystem path is resolvable.

#### Samsung SEFT Trailer
- Extracted on load, re-appended on save (preserves Galaxy Gallery Revert chain)
- For first-time edits: generates new SEFT with re-edit JSON and backup path
- Original backup saved to `/storage/emulated/0/.cropcenter/{SHA256(path)}_{mediaId}.jpg`
- Requires `MANAGE_EXTERNAL_STORAGE` for the File-I/O strategy in collision-overwrite Replace; the grant prompt is offered from the Replace failure dialog (`ReplaceStrategy.showReplaceFailureDialog`) only when a collision-overwrite hits an SAF-permission limit, never up-front at app start or save-dialog open. Plain Save As flows that don't hit a collision never need this permission, so most users are never prompted.

#### HDR Gain Map
- Extracted via JPEG marker walking (handles SEFT trailer boundary via backward FFD9 scan)
- Re-generated via canvas-based gainmap transform matching the primary
- Composed with primary via `GainMapComposer` + `MpfPatcher` for MPF offset correction
- `containsHdrgm()` verification on output; toast shows [HDR OK] or [HDR FAILED]

#### ICC, XMP, MPF
- ICC profiles preserved as raw APP2 segments
- XMP with hdrgm namespace preserved from original
- MPF offsets recalculated after primary size changes

### 11. Settings / About

Settings dialog (opened via the gear icon in the toolbar) shows:
- **Version** — the build's compile timestamp injected as `BuildConfig.BUILD_TIME` by `app/build.gradle` and displayed by `SettingsDialog`. Used to verify which APK is installed on the device.

### 12. Apply External Edit (In-Memory Pixel Graft)

**Purpose**: Apply a small external pixel edit (typically Photoshop Generative Fill / Generative Remove) to a Samsung Ultra HDR original while preserving Samsung Gallery's Revert button, the original's gain map, and identity metadata (Make, Model, GPS, MakerNote, DateTimeOriginal, lens info, SEFT trailer). The user picks an externally-edited copy of the loaded photo; CropCenter splices the edit's pixel content into the original's metadata container, applies the result as the in-memory image, and saves through the canvas-encoded pipeline so the output is colour-managed (Display P3) and viewer-compatible.

**Recommended editor: Photoshop, opened in pixel space (Camera Raw → File Handling → JPEGs → Disabled).** Photoshop preserves the source pixels everywhere except the AI-edited region, with only ICC-encoding-level differences from the original (mean per-pixel diff vs Samsung original ≈ 1 level after canvas P3 conversion; Lightroom's HDR-tone-mapped output produces ≈ 13 levels and a visible tonal seam at the fill boundary). Other editors work if they meet the same constraint — pixel-space editing, no global tone-curve shift.

**Why Revert works**: Samsung Gallery reads the `originalPath` value from the SEFT trailer's `PhotoEditor_Re_Edit_Data` block and serves whatever JPEG it finds at that path. The graft preserves original's SEFT verbatim, plus original's MPF segment shape (substituting Adobe's MPF reliably breaks Gallery's Revert pre-flight), so any backup chain the user already had stays intact.

**Entry point**: Long-press the toolbar **Open** button. Available only when (a) an image is loaded, (b) the loaded image is JPEG. (Long-press chosen over a new toolbar icon to avoid clutter; Open and Apply-External-Edit are semantically related — both load external files — so the gesture pairing is intuitive.)

**Flow**:
1. User loads the original Samsung HDR JPEG (the metadata source) into CropCenter normally.
2. User long-presses Open.
3. SAF `ACTION_OPEN_DOCUMENT` (image/jpeg) → user picks the external edit (the pixel donor).
4. Validation:
   - Picked file is a JPEG (SOI = `FFD8`).
   - Stored W × H from `BitmapFactory` bounds-only decode equal between loaded and picked. Mismatch → toast "Edit dimensions don't match: original WxH, edit WxH" and abort.
   - EXIF orientation tags match. Mismatch → toast "Edit EXIF orientation differs from original (X vs Y). Re-export with same orientation." and abort.
5. `GraftWriter` splices the bytes in memory per the substitution rule below.
6. `MainActivity.applyGraftedBytes` calls `applyImageBytes(grafted)` which installs the splice as the new in-memory image, then sets `state.setGraftApplied(true)` and `state.setAspectRatio(AspectRatio.FREE)`. The graft-applied flag gates the verbatim-write bypass (see below); the AR reset prevents the user's preserved AR from carving the freshly-applied graft into a sub-region.
7. Toast "External edit applied" confirms success. Default save name = `<original-stem>-graft.jpg`.
8. User saves through the existing Save button. The save runs through `CropExporter.export` (the bypass is disabled for grafts), which canvas-renders the source onto a Display P3 bitmap, re-encodes via `Bitmap.compress(JPEG, 100)`, generates a fresh thumbnail, re-injects original's EXIF / XMP / MPF / SEFT, and appends the gain map.

**Substitution rule** (per-segment provenance — see `GraftWriter.SWAP_*` constants):

| Segment | From | Why |
|---|---|---|
| SOI | universal | always `FF D8` |
| APP0/JFIF | original | identity (density, version) |
| APP1/EXIF | **original** | identity: Make, Model, Software, MakerNote, GPS coordinates, DateTimeOriginal, lens info |
| APP1/XMP | **original** | preserves Samsung's HDR `hdrgm` metadata (matches original's gain map) |
| APP2/ICC | **edit** | matches the edit's pixels' colour space; the canvas-encode pass converts to Display P3 anyway, so this is a fallback when the canvas path is skipped |
| APP2/MPF | original (offset-patched) | Samsung-shape MPF is what Gallery's Revert pre-flight recognises; edit's MPF (Adobe-flavoured `MPType` for the gain-map entry) breaks Revert |
| vendor APPs (APP3-APP15), COM | original | Samsung sensor hints, scene labels |
| DQT, DHT, SOF, SOS+scan, EOI | **edit** | the AI-edited pixels — byte-verbatim from edit's primary |
| gain map JPEG | **original** | preserves Samsung's HDR rendering for the unedited area; mismatch in the AI-fill region is acceptable for low-frequency Generative Remove fills |
| SEFT trailer | original | the sole reason Gallery still surfaces and successfully services Revert |

**Saved output**: the canvas-encoded pipeline runs for **every** graft save (no-crop and cropped alike — `state.isGraftApplied()` disables the bypass). Output structure:
- Primary JPEG: re-encoded from the canvas-rendered bitmap at quality 100, in Display P3 colour space
- EXIF / XMP / MPF / SEFT: re-injected from `state.jpegMeta` (= original's segments, with dimensions / orientation patched and a fresh IFD1 thumbnail)
- Gain map: original's gain map appended (cropped via `UltraHdrCompat.compressWithGainmap` if a crop is applied)

The Display P3 conversion during canvas rendering brings the edit's pixels (which carry the editor's ICC profile) into Samsung's reference colour space, masking the editor's encoding-level colour shift outside the AI-fill region. The fill region's pixels remain as the editor produced them (the colour shift is dwarfed by the AI fill's own pixel changes).

**Why each substitution choice** (validated by visual inspection of saved + cropped outputs and Samsung Gallery Revert testing):

- **EXIF (`SWAP_EXIF=false`)**: preserves Samsung MakerNote (lens info, sensor settings, scene metadata), GPS coordinates, DateTimeOriginal. Substituting edit's EXIF would lose all of these.
- **XMP (`SWAP_XMP=false`)**: preserves Samsung's HDR metadata (`hdrgm` namespace) which describes original's gain map. Edit's XMP describes the editor's gain map — incoherent with the kept original gain map.
- **ICC (`SWAP_ICC=true`)**: matches the edit's pixels' encoding when the bypass-write fallback fires. The canvas-encode pass converts to Display P3 anyway, so this swap is a defensive fallback for paths that skip canvas rendering.
- **MPF (`SWAP_HDR_MPF=false`)**: confirmed via bisection — substituting edit's MPF segment alone reliably hangs Samsung Gallery's Revert. Original's MPF stays Samsung-shape; only its offset/size fields are patched after the gain-map splice.
- **Gain map (`SWAP_HDR_GAINMAP=false`)**: preserves Samsung's HDR rendering across the unedited area (≈ 99.99% of the frame for typical Generative Remove fills). The fill region inherits original's gain map (calibrated for the original content, not the AI fill), but for low-frequency fills (sky, grass, wall) the gain-map mismatch is below visual threshold.
- **Vendor APPs (`STRIP_VENDOR_APPS=false`)**: confirmed no rendering effect; Samsung sensor / scene identity data preserved.

**`ExportPipeline.canBypassEncode`**: returns `true` only when output is JPEG, source is JPEG, **no graft applied**, no rotation, no grid bake-in, source bytes available, AND (no crop OR full-image crop). The graft-applied check is what forces graft saves through `CropExporter.export`. Without it, the verbatim-write bypass would emit the splice's edit-ICC-tagged bytes, which display correctly in ICC-aware viewers but produce a slightly different output structure than save-after-crop — the canvas-encode path always converts to Display P3, giving consistent output regardless of crop state.

**`MainActivity.applyGraftedBytes`**: after `applyImageBytes`, sets `state.setAspectRatio(AspectRatio.FREE)` (so the user's preserved AR doesn't carve the graft into a sub-region) and `state.setGraftApplied(true)` (so `canBypassEncode` returns false for this image). The flag is cleared by `CropState.reset()` on the next image load.

**Validation that the splice is decoder-coherent**: both inputs must share SOF0 dimensions AND EXIF orientation. Caller (`GraftController`) checks via `BitmapFactory.decodeByteArray(inJustDecodeBounds=true)` for stored dims and `BitmapUtils.readExifOrientation` for orientation; mismatch aborts before invoking `GraftWriter`.

**Failure modes**:
- Picked file isn't JPEG → toast "Picked image isn't a JPEG."
- Dimensions don't match → toast with both dimensions, abort.
- EXIF orientation differs → toast with both values, abort.
- Loaded image isn't JPEG → toast "Apply External Edit only works on JPEG sources."
- Decode of grafted bytes fails → toast "Failed to decode" / "Apply failed: <reason>", in-memory image unchanged.

**Verification**: save the grafted file, open in Samsung Gallery, confirm the Revert button appears and successfully restores the original. Inspect the saved file's EXIF in any external tool (exiftool, ImageMagick `identify -verbose`) to confirm GPS coordinates, MakerNote, and other camera tags are preserved.

**Out of scope**: PNG inputs (SEFT is JPEG-specific). HEIC inputs (different metadata system). Differing-dimension edits (would need re-encode; refused with toast). Per-region gain-map regeneration (would need a way to derive HDR data for AI-fill content; the current "use original's gain map verbatim" works for low-frequency fills). Mask-based selective composite (preserve source bytes outside the AI region — useful for editors that produce larger tonal shifts than Photoshop, currently not needed because Photoshop is the recommended editor and produces minimal tonal shift).

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
| Selection point | Grid color (configurable) |

---

## Java 21 Features Used

- **Records**: `model/AspectRatio`, `model/ExportConfig`, `model/GridConfig`, `model/SelectionPoint`, `metadata/JpegSegment`, `crop/CropExporter.ExportResult`, `metadata/GainMapExtractor.ScanResult`, `view/RotationRulerView.TickConfig`, `view/ColorPickerDialog.AlphaRow`, `ExportPipeline.WriteOutcome` — immutable value types replace boilerplate POJOs
- **Switch expressions**: Arrow syntax throughout (`ExifPatcher`, `BitmapUtils`, `GridConfig`, `CropExporter`, `RotationRulerView`)
- **`Math.clamp`**: used wherever a value needs `[lo, hi]` clamping instead of hand-rolled `max(min(...))`
- **`var`**: used sparingly where the right-hand-side type is obvious
- **Pattern matching for `instanceof`**: in metadata-segment dispatch sites
- **Consolidated utilities**: `BitmapUtils.orientationMatrix()` shared between `BitmapUtils` and `UltraHdrCompat`; `RotationMath` is the single source of truth for rotation; `SafFileHelper` consolidates SAF/MediaStore URI helpers

---

## Known Limitations

1. **Canvas re-encoding**: `Bitmap.compress()` re-encodes the JPEG, changing quality and file size vs. original.
2. **PNG metadata**: Only EXIF is injected (via eXIf chunk). Other PNG ancillary chunks are not preserved. HDR is not possible in PNG format.
3. **Single image**: Only one image can be open at a time.
4. **Large files**: Files > 128MB are rejected (`SafFileHelper.MAX_READ_BYTES`). Entire file is read into memory via a per-call `createTempFile` cache file (so concurrent loads don't clobber each other).
5. **MediaStore owner on plain Save As**: `ACTION_CREATE_DOCUMENT` to a different directory creates a new file with a different MediaStore owner. Same-directory same-name saves route through the Replace flow which preserves the original document where the provider supports it.
6. **Samsung Revert**: Backup written to shared storage from the pre-encode hook (so the SEFT trailer only claims a backup that actually wrote); Galaxy Gallery recognition depends on Samsung firmware version.
7. **EXIF thumbnail overflow**: If original EXIF metadata + new thumbnail would exceed the 65535-byte APP1 limit, thumbnail is reduced or dropped. `oldThumbLen` is sanity-clamped against `data.length` to prevent malformed source EXIF from inflating the budget calculation.
8. **No saved instance state**: Configuration changes handled via `configChanges`; process death loses all crop state.
9. **Opaque-ID providers**: Providers without document-ID path encoding (some cloud / SD-card providers) lose the strongest collision-detection paths. The Save flow trusts SAF auto-rename as collision evidence on those providers — false positives surface as a Replace dialog the user can dismiss with Keep, never as silent data loss.
