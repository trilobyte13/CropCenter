# CropCenter - Application Specification

## Overview

CropCenter is a native Android image cropping tool focused on precise center-based cropping with full metadata preservation, including Samsung Ultra HDR gain map data and Samsung SEFT trailer for Gallery Revert support. The app targets Samsung Galaxy devices running Android 8.0+ (API 26+).

**Package**: `com.cropcenter`
**Min SDK**: 26 (Android 8.0)
**Target SDK**: 34 (Android 14)
**Language**: Java 17

---

## Architecture

### Single Activity (no drawer)
- `MainActivity` hosts the entire UI in a flat layout
- Toolbar at top: AR spinner, grid/pixel toggles, open, save
- Editor canvas (CropEditorView) in the center
- Mode bar: Move/Select toggle, lock mode buttons (Both/H/V), Freeze checkbox, Rotate
- Point controls bar: Undo, Redo, Clear (always visible)
- Info bar at bottom: image info, crop size, zoom level
- Save dialog: pre-save settings (filename, format, export grid)
- Grid settings overlay: triggered by long-pressing Grid checkbox

### Key Components

| Component | Class | Purpose |
|-----------|-------|---------|
| State | `model/CropState` | Central state with listener, crop params, metadata |
| Crop Math | `crop/CropEngine` | Computes crop from center + AR + lock + rotation |
| Export | `crop/CropExporter` | Full pipeline: crop, rotate, compress, HDR, EXIF, SEFT |
| Editor | `view/CropEditorView` | Custom View: rendering + gestures + undo/redo |
| Gestures | `view/TouchGestureHandler` | Pinch zoom, tap, drag, long-press |
| Grid | `view/GridRenderer` | Grid overlay with pixel-snapped lines |
| Color Picker | `view/ColorPickerDialog` | Tap-to-select grid + alpha + hex input |
| Grid Settings | `view/GridSettingsDialog` | Cols, rows, presets, color, width |
| Save Dialog | `view/SaveDialog` | Filename, format, export grid, overwrite option |

### Metadata Pipeline

| Class | Purpose |
|-------|---------|
| `metadata/JpegMetadataExtractor` | Extract all APP/COM segments from JPEG header |
| `metadata/JpegMetadataInjector` | Replace re-encoder's APP markers with originals |
| `metadata/ExifPatcher` | Update orientation, dimensions, thumbnail in EXIF |
| `metadata/GainMapExtractor` | Extract HDR gain map from between primary EOI and SEFT |
| `metadata/GainMapComposer` | Append gain map + trigger MPF patch (fallback path) |
| `metadata/MpfPatcher` | Fix MPF APP2 offsets after primary size changes |
| `metadata/SeftBuilder` | Build Samsung SEFT trailer for Gallery Revert |
| `metadata/SeftExtractor` | Extract existing SEFT trailer |
| `metadata/JpegSegment` | Data class for a single JPEG marker segment |
| `util/ByteBufferUtils` | Endian-aware read/write with bounds checking |
| `util/BitmapUtils` | EXIF orientation reading and bitmap rotation |
| `util/UltraHdrCompat` | Android 14+ Gainmap API for HDR export |

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

**Input methods**:
- Open button: `ACTION_OPEN_DOCUMENT`
- Share intent: `ACTION_SEND` with `image/*`
- View intent: `ACTION_VIEW` with `image/*`

### 2. Editor Modes

#### Move (Default)
- Drag to reposition the crop rectangle
- Respects lock mode direction (H-Lock: X only, V-Lock: Y only)
- Crop rectangle cannot be dragged outside image bounds (rotation-aware)
- Tap does nothing (prevents accidental crop placement)

#### Select
- Tap to place selection points around a feature
- Tap on existing point to remove it
- Long-press to remove nearest point
- Auto-computes center from points respecting lock mode
- Maximum crop at current AR centered on feature
- Points can't be placed outside rotated image content

### 3. Lock Modes

| Mode | Behavior | Available In |
|------|----------|--------------|
| Both | Symmetric on both axes | Select only |
| H-Lock | Symmetric X, free Y | Both modes |
| V-Lock | Symmetric Y, free X | Both modes |
| Locked | Crop frozen, drag pans viewport | Move only (via Freeze checkbox) |

Lock mode is a single toggle button that cycles:
- Select mode: Both → H-Lock → V-Lock → Both
- Move mode: H-Lock ↔ V-Lock

Per-mode preferences are remembered independently.

### 4. Aspect Ratio

**Presets** (via toolbar spinner): 4:5 (default), Free, 16:9, 3:2, 4:3, 5:4, 1:1, 4:5, 3:4, 2:3, 9:16, Custom

**Custom AR**: Dialog with width:height inputs when "Custom" is selected.

**Auto-crop**: Changing AR auto-creates a crop at image center if none exists.

### 5. Rotation

Precise rotation with dialog:
- Direct numeric input (2 decimal places)
- Nudge buttons: ±0.01°, ±0.05°, ±0.1°, ±0.5°, ±1°, ±45°, ±90°
- Crop auto-resizes to fit within rotated image bounds
- Center clamping uses 4-corner un-rotation check

### 6. Zoom & Pan

| Gesture | Action |
|---------|--------|
| Two-finger pinch | Zoom with pivot (1x to 256x) |
| Single-finger drag | Move mode: move crop / Select mode: pan viewport |
| Double-tap | Fit image to view (disabled in Select mode) |
| Long-press | Remove nearest selection point (Select mode) |

Viewport is clamped to prevent panning image off screen.

### 7. Grid Overlay

- Toggle via toolbar checkbox
- Long-press opens settings dialog (cols, rows, presets, color, width)
- Grid lines snap to pixel boundaries
- Pixel grid at 6x+ zoom (toggle + configurable color via long-press)
- Selection points and polygon use grid color
- Optional bake-in to exported image

### 8. Undo/Redo

- Full undo/redo for selection points (50-step history)
- Buttons greyed out when not applicable
- Clear button removes all points and resets crop to full image
- History cleared on new image load

### 9. Export

**HDR Export (API 34+)**:
```
Source bitmap → render crop+rotation+grid → attach gainmap → Bitmap.compress → Ultra HDR JPEG
→ inject original EXIF (patched) → fix MPF offsets → append SEFT trailer
```

The gainmap is cropped and rotated using `Bitmap.createBitmap` operations that preserve the native pixel format. EXIF rotation is detected and applied to the gainmap if BitmapFactory auto-rotated the primary.

**Non-HDR Export**:
```
Source bitmap → render crop+rotation+grid → Bitmap.compress → inject metadata
→ append gain map bytes → fix MPF → append SEFT
```

**Save options**:
- **Save As**: `ACTION_CREATE_DOCUMENT` — creates new file
- **Overwrite**: writes to original URI — preserves MediaStore ownership

**JPEG quality**: 100 (hardcoded)

### 10. Metadata Preservation

#### EXIF
- All tags preserved except: Orientation (→1), dimensions (→crop size)
- Thumbnail regenerated from cropped bitmap (max 512px, auto-shrinks to fit 64KB EXIF limit)
- In HDR path: original EXIF replaces platform EXIF, MPF offsets fixed afterward

#### Samsung SEFT Trailer
- Extracted on load, re-appended on save (preserves Gallery Revert)
- For first-time edits: generates new SEFT with backup path at `/storage/emulated/0/.cropcenter/`
- Existing SEFT preserved verbatim (keeps Gallery's backup chain)

#### HDR Gain Map
- Extracted via marker walking (handles SEFT trailer boundary)
- Re-attached via platform Gainmap API (API 34+) with crop/rotation applied
- Fallback: raw byte append with MPF offset patching

#### ICC, XMP, MPF
- ICC preserved as raw segments
- XMP with hdrgm preserved (platform generates new XMP in HDR path)
- MPF offsets updated after EXIF injection

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
| Dividers | #313244 |

---

## Known Limitations

1. **Canvas re-encoding**: `Bitmap.compress()` re-encodes the JPEG, changing quality and file size.
2. **PNG metadata**: PNG ancillary chunks are not preserved.
3. **Single image**: Only one image can be open at a time.
4. **Large files**: Files > 100MB are rejected. Entire file is read into memory.
5. **Grid color/width**: Configurable via long-press overlay, not inline controls.
6. **MediaStore owner**: Save As creates new file with `com.samsung.android.providers.trash` as owner. Use Overwrite to preserve original ownership.
7. **Samsung Revert for first-time edits**: Backup is written to shared storage; Galaxy Gallery may not recognize the path.
