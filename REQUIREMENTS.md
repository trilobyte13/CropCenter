# CropCenter - Application Specification

## Overview

CropCenter is a native Android image cropping tool focused on precise center-based cropping with full metadata preservation, including Samsung Ultra HDR gain map data and Samsung SEFT trailer for Galaxy Gallery Revert support.

**Package**: `com.cropcenter`
**Min SDK**: 35 (Android 15)
**Target/Compile SDK**: 36
**Language**: Java 21
**Build**: AGP 9.1.1, Gradle 9.3.1
**LSLOC**: ~3,600 Java + ~240 XML (UCC-style: statements + control structures + declarations, excluding scaffolding braces)

---

## Architecture

### Single Activity Layout

```
+------------------------------------------------------------+
| Toolbar: [AR Spinner] [Grid] [Pixel]    [Open] [Save]     |
+------------------------------------------------------------+
|                                                            |
|              CropEditorView (flexible height)              |
|     Image + crop overlay + grid + selection points         |
|                                                            |
+------------------------------------------------------------+
| Mode: [Select] [Move] | Lock: [Both] [H] [V] [Freeze]    |
+------------------------------------------------------------+
| Points: [Undo] [Redo] [Clear]        [Auto] (if loaded)   |
+------------------------------------------------------------+
| Rotation ruler (scrollable, Galaxy-style)                  |
+------------------------------------------------------------+
| Info: image size | crop size | rotation | zoom             |
+------------------------------------------------------------+
```

### Key Components

| Component | Class | Purpose |
|-----------|-------|---------|
| State | `model/CropState` | Central state: crop params, metadata, rotation anchor (stable intent center for no-selection rotations) |
| Crop Math | `crop/CropEngine` | Computes crop from center + AR + lock + rotation; parity-snaps to pixel grid |
| Horizon | `util/HorizonDetector` | Auto-rotation: metadata pass first, fallback to painted-region Hough transform |
| Export | `crop/CropExporter` | Full pipeline: crop, rotate, compress, HDR, EXIF, SEFT |
| Editor | `view/CropEditorView` | Custom View: rendering + gestures + undo/redo |
| Gestures | `view/TouchGestureHandler` | Pinch zoom, tap, drag, long-press |
| Grid | `view/GridRenderer` | Grid overlay with parity-aware pixel-snapped lines |
| Rotation | `view/RotationRulerView` | Galaxy-style scrollable ruler with snap-to-detent |
| Color Picker | `view/ColorPickerDialog` | Tap-to-select grid + alpha + hex input |
| Grid Settings | `view/GridSettingsDialog` | Cols, rows, presets (2x2-8x8), color, width |
| Settings | `view/SettingsDialog` | About dialog showing build-time Version string |
| Save Dialog | `view/SaveDialog` | Filename, format, export grid, overwrite option |

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
| `util/ByteBufferUtils` | Endian-aware read/write with bounds checking |
| `util/BitmapUtils` | EXIF orientation reading, `orientationMatrix()` (shared) |
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
- Crop size is **never recomputed** in Move mode — only position changes
- Crop borders always land on whole-pixel boundaries: the drag's fractional accumulator lives in a separate "anchor" state so sub-pixel motion builds up across events while the displayed center snaps each frame to the parity that keeps `cropImgX = centerX − cropW/2` integer
- Crop rectangle cannot be dragged outside image bounds (rotation-aware binary search)
- Cross-axis drift on a locked axis is bounded to 0.5 px per event and rejected above that threshold
- Tap does nothing (prevents accidental crop placement)

### 3. Lock Modes

| Mode | Select Behavior | Move Behavior |
|------|----------------|---------------|
| Both | Symmetric on both axes around point midpoint | Drag moves both axes |
| H | Center horizontally on points, maximize vertically | Drag moves X only |
| V | Center vertically on points, maximize horizontally | Drag moves Y only |
| Freeze | N/A | Crop frozen, drag pans viewport |

**Select mode centering logic**:
- Locked axis: center = midpoint of selection points, crop extent = symmetric from center
- Free axis: center = midpoint of points (best-effort), crop extent = full image dimension; center shifts only if needed to keep the crop in bounds
- With rotation: a second pass of `maxScaleForRotation` shrinks the crop if the rotation-clamped center makes it too large

Per-mode lock preferences (Both/H/V) are remembered independently for Move and Select. Defaults are **V** in Move and **Both** in Select. "Both" button is only visible in Select mode.

### 4. Aspect Ratio

**Presets**: 4:5 (default), Free, 16:9, 3:2, 4:3, 5:4, 1:1, 3:4, 2:3, 9:16, Custom

**Custom AR**: Dialog with width:height inputs when "Custom" is selected.

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

- Toggle via toolbar checkbox
- Long-press opens settings dialog
- Presets: 2x2 through 8x8
- Configurable color (via color picker), line width (1-20px)
- Grid-line snap depends on crop-dimension parity: even crop dim → line drawn between pixels (integer coord); odd crop dim → line drawn through a pixel's center (half-integer coord). Single selection forces an odd crop dim so the midline covers the tapped pixel
- Line width scales by image-to-screen ratio (preview matches export)
- Anti-aliased OFF for crisp export lines
- Pixel grid at 6x+ zoom (separate toggle + configurable color via long-press)
- Selection points and polygon use grid color
- Optional bake-in to exported image (grid + HDR supported)

### 8. Undo/Redo

- Full undo/redo for selection points (50-step history)
- Buttons greyed out when not applicable
- Clear button removes all points and resets crop to full image
- History cleared on new image load
- Controls always visible in both Move and Select modes

### 9. Export

**Save options**:
- **Save As**: `ACTION_CREATE_DOCUMENT` with format-aware MIME type (image/jpeg or image/png)
- **Overwrite**: Direct write to source URI via `openOutputStream("wt")` -- preserves MediaStore ownership

**JPEG quality**: 100 (hardcoded, always maximum)

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
- All original APP/COM segments preserved verbatim (camera model, GPS, MakerNotes, ICC, XMP)
- Orientation tag set to 1 (output is always in display orientation)
- Dimensions updated to crop size
- Thumbnail regenerated from cropped bitmap (max 512px, quality reduced to fit 20KB limit)
- ExifPatcher creates IFD1 if original has no thumbnail

#### Samsung SEFT Trailer
- Extracted on load, re-appended on save (preserves Galaxy Gallery Revert chain)
- For first-time edits: generates new SEFT with re-edit JSON and backup path
- Original backup saved to `/storage/emulated/0/.cropcenter/{SHA256(path)}_{mediaId}.jpg`
- Requires `MANAGE_EXTERNAL_STORAGE` permission (requested on first save)

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

- **Records**: `CropExporter.ExportResult`
- **Switch expressions**: Arrow syntax throughout (ExifPatcher, BitmapUtils, GridConfig, CropExporter)
- **`Math.clamp`**: GridSettingsDialog (replaces custom implementation)
- **Consolidated utilities**: `BitmapUtils.orientationMatrix()` shared between BitmapUtils and UltraHdrCompat

---

## Known Limitations

1. **Canvas re-encoding**: `Bitmap.compress()` re-encodes the JPEG, changing quality and file size vs. original.
2. **PNG metadata**: Only EXIF is injected (via eXIf chunk). Other PNG ancillary chunks are not preserved. HDR is not possible in PNG format.
3. **Single image**: Only one image can be open at a time.
4. **Large files**: Files > 100MB are rejected. Entire file is read into memory.
5. **MediaStore owner**: Save As creates a new file (different owner). Use Overwrite to preserve original ownership.
6. **Samsung Revert**: Backup written to shared storage; Galaxy Gallery recognition depends on Samsung firmware version.
7. **EXIF thumbnail overflow**: If original EXIF exceeds ~45KB (large MakerNotes), thumbnail may be reduced or dropped to fit 64KB APP1 limit.
8. **No saved instance state**: Configuration changes handled via `configChanges`; process death loses all crop state.
