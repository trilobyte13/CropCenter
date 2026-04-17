package com.cropcenter;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.cropcenter.crop.CropEngine;
import com.cropcenter.crop.CropExporter;
import com.cropcenter.metadata.GainMapExtractor;
import com.cropcenter.metadata.JpegMetadataExtractor;
import com.cropcenter.metadata.JpegSegment;
import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.CropState;
import com.cropcenter.model.EditorMode;
import com.cropcenter.util.BitmapUtils;
import com.cropcenter.view.CropEditorView;
import com.cropcenter.view.SaveDialog;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CropCenter";

    private CropState state = new CropState();
    private CropEditorView editorView;
    private TextView txtSidebarCropSize, txtImageInfo, txtZoomBadge;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private Uri sourceUri; // URI of the opened file, for overwrite-in-place
    private ActivityResultLauncher<String[]> openLauncher;
    private ActivityResultLauncher<String> saveAsLauncher;

    private static final String[] AR_LABELS = {
        "4:5", "Full", "16:9", "3:2", "4:3", "5:4", "1:1", "3:4", "2:3", "9:16", "Custom"
    };
    private static final AspectRatio[] AR_VALUES = {
        AspectRatio.R4_5, AspectRatio.FREE, AspectRatio.R16_9, AspectRatio.R3_2,
        AspectRatio.R4_3, AspectRatio.R5_4, AspectRatio.R1_1, AspectRatio.R3_4,
        AspectRatio.R2_3, AspectRatio.R9_16, null
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Handle edge-to-edge: apply system bar insets as padding to root layout
        View root = findViewById(android.R.id.content);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        editorView = findViewById(R.id.editorView);
        txtZoomBadge = findViewById(R.id.txtZoomBadge);
        txtSidebarCropSize = findViewById(R.id.txtSidebarCropSize);
        txtImageInfo = findViewById(R.id.txtImageInfo);

        editorView.setState(state);
        editorView.setOnZoomChangedListener(this::updateZoomBadge);
        editorView.setOnPointsChangedListener(this::updatePointButtonStates);
        final boolean[] inListener = {false};
        state.setListener(() -> runOnUiThread(() -> {
            if (inListener[0]) return; // prevent recursion
            inListener[0] = true;
            try {
                if (state.isCropSizeDirty()) {
                    // Auto-set center if needed
                    if (!state.hasCenter() && state.getSourceImage() != null) {
                        state.setCenter(state.getImageWidth() / 2f, state.getImageHeight() / 2f);
                    }
                    if (state.hasCenter()) {
                        CropEngine.recomputeCrop(state);
                    }
                }
                updateCropInfo();
                updateZoomBadge();
                updatePointButtonStates();
                updateAutoRotateVisibility();
                syncRotationUI();
                editorView.invalidate();
            } finally {
                inListener[0] = false;
            }
        }));

        openLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        // Take persistable permission for overwrite-in-place
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } catch (Exception ignored) {}
                        sourceUri = uri;
                        loadImage(uri);
                    }
                });

        saveAsLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("image/jpeg") {
                    @Override
                    public Intent createIntent(android.content.Context ctx, String input) {
                        Intent intent = super.createIntent(ctx, input);
                        if (input != null && input.endsWith(".png")) {
                            intent.setType("image/png");
                        }
                        return intent;
                    }
                },
                uri -> {
                    if (uri != null) exportTo(uri);
                });

        // Toolbar
        findViewById(R.id.btnOpen).setOnClickListener(v ->
                openLauncher.launch(new String[]{"image/jpeg", "image/png"}));
        findViewById(R.id.btnSave).setOnClickListener(v -> showSaveDialog());
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                com.cropcenter.view.SettingsDialog.show(this, state.getGridConfig(),
                        () -> editorView.invalidate()));

        // Grid toggle (display-only — full grid settings in the Settings dialog)
        CheckBox chkGrid = findViewById(R.id.chkGridMain);
        chkGrid.setChecked(state.getGridConfig().enabled);
        chkGrid.setOnCheckedChangeListener((b, c) -> {
            state.getGridConfig().enabled = c;
            editorView.invalidate();
        });

        setupARSpinner();

        // Mode bar
        setupModeButtons();
        setupCenterModeButtons();
        setupUndoRedo();
        setupClearPointsButton();
        setupRotation();
        setupAutoRotate();

        updateModeHighlight();
        updateLockHighlight();
        ensureStoragePermission();
        handleIncomingIntent(getIntent());
    }

    private volatile boolean isDestroyed = false;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyed = true;
        Bitmap bmp = state.getSourceImage();
        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
    }

    /** Show the full-screen progress overlay with the given message. */
    private void showProgress(String message) {
        runOnUiThread(() -> {
            if (isDestroyed) return;
            View overlay = findViewById(R.id.progressOverlay);
            ((TextView) findViewById(R.id.progressText)).setText(message);
            overlay.setVisibility(View.VISIBLE);
        });
    }

    private void hideProgress() {
        runOnUiThread(() -> {
            if (isDestroyed) return;
            findViewById(R.id.progressOverlay).setVisibility(View.GONE);
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            if (uri == null) uri = intent.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {}
                sourceUri = uri;
                loadImage(uri);
            }
        }
    }

    // ── Save ──

    private void showSaveDialog() {
        if (state.getSourceImage() == null) return;
        SaveDialog.show(this, state.getExportConfig(),
                () -> {
                    String name = state.getExportConfig().filename;
                    if (name == null || name.isEmpty()) name = "crop";
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) name = name.substring(0, dot);
                    name += ("jpeg".equals(state.getExportConfig().format) ? ".jpg" : ".png");
                    saveAsLauncher.launch(name);
                },
                sourceUri != null ? () -> exportTo(sourceUri) : null);
    }

    // ── AR Spinner ──

    private void setupARSpinner() {
        Spinner spinner = findViewById(R.id.spinnerAR);
        float density = getResources().getDisplayMetrics().density;
        int padH = (int)(6 * density);
        int padV = (int)(4 * density);

        // Custom adapter with compact item views (tight padding, 12sp text)
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, AR_LABELS) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextSize(12);
                tv.setTextColor(0xFFCDD6F4);
                tv.setPadding(padH, padV, padH, padV);
                return tv;
            }
            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                tv.setTextSize(13);
                tv.setTextColor(0xFFCDD6F4);
                tv.setPadding(padH*2, padV*2, padH*2, padV*2);
                return tv;
            }
        };
        spinner.setAdapter(adapter);
        spinner.setSelection(0);

        // Size the spinner to exactly fit the widest label + arrow.
        android.graphics.Paint tp = new android.graphics.Paint();
        tp.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        float maxTextPx = 0;
        for (String label : AR_LABELS) {
            float w = tp.measureText(label);
            if (w > maxTextPx) maxTextPx = w;
        }
        // Width = text + horizontal padding (both sides) + dropdown arrow (~24dp)
        int totalPx = (int) maxTextPx + padH * 2 + (int)(24 * density);
        spinner.setMinimumWidth(totalPx);
        android.view.ViewGroup.LayoutParams lp = spinner.getLayoutParams();
        if (lp != null) {
            lp.width = totalPx;
            spinner.setLayoutParams(lp); // triggers re-layout
        }
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < AR_VALUES.length && AR_VALUES[pos] != null) {
                    state.setAspectRatio(AR_VALUES[pos]); // sets dirty
                    // Recompute: from points if any, otherwise ensure center exists
                    if (!state.getSelectionPoints().isEmpty()) {
                        CropEngine.autoComputeFromPoints(state);
                    } else {
                        ensureCropCenter();
                    }
                } else {
                    showCustomARDialog();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void showCustomARDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Custom Aspect Ratio");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER);
        int dp = (int) getResources().getDisplayMetrics().density;
        layout.setPadding(20*dp, 16*dp, 20*dp, 8*dp);

        EditText editW = new EditText(this);
        editW.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editW.setText("16"); editW.setGravity(android.view.Gravity.CENTER);
        layout.addView(editW, new LinearLayout.LayoutParams(60*dp, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView sep = new TextView(this);
        sep.setText("  :  "); sep.setTextSize(16);
        layout.addView(sep);

        EditText editH = new EditText(this);
        editH.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editH.setText("9"); editH.setGravity(android.view.Gravity.CENTER);
        layout.addView(editH, new LinearLayout.LayoutParams(60*dp, LinearLayout.LayoutParams.WRAP_CONTENT));

        builder.setView(layout);
        builder.setPositiveButton("Apply", (d, which) -> {
            int w = parseIntOr(editW.getText().toString(), 16);
            int h = parseIntOr(editH.getText().toString(), 9);
            if (w <= 0) w = 1; if (h <= 0) h = 1;
            state.setAspectRatio(new AspectRatio(w + ":" + h, w, h));
            if (!state.getSelectionPoints().isEmpty()) {
                CropEngine.autoComputeFromPoints(state);
            } else {
                ensureCropCenter();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // ── Image loading ──
    // Copies URI to a cache file first to guarantee raw byte access.
    // Some ContentProviders (Samsung MediaStore) strip post-EOI data from JPEGs,
    // which would lose the HDR gain map. Copying to local file bypasses this.

    private void loadImage(Uri uri) {
        if (!busy.compareAndSet(false, true)) {
            Toast.makeText(this, "Busy", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                // Copy URI to cache file for guaranteed raw access
                File cacheFile = new File(getCacheDir(), "input_raw");
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    if (is == null) throw new IOException("Cannot open URI");
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
                // Read raw bytes from local file (no ContentProvider interference)
                byte[] fileBytes;
                try (FileInputStream fis = new FileInputStream(cacheFile)) {
                    long fileLen = cacheFile.length();
                    if (fileLen <= 0 || fileLen > 100_000_000) throw new IOException("Invalid file size: " + fileLen);
                    fileBytes = new byte[(int) fileLen];
                    int read = 0;
                    while (read < fileBytes.length) {
                        int n = fis.read(fileBytes, read, fileBytes.length - read);
                        if (n < 0) break;
                        read += n;
                    }
                }
                cacheFile.delete();
                Log.d(TAG, "Loaded " + fileBytes.length + " raw bytes (via cache)");
                if (fileBytes.length > 8) {
                    Log.d(TAG, "File head: " + hex(fileBytes, 0, 4)
                            + " tail: " + hex(fileBytes, fileBytes.length - 4, 4));
                }

                Bitmap raw = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.length);
                if (raw == null || raw.getWidth() <= 0 || raw.getHeight() <= 0) {
                    runOnUiThread(() -> Toast.makeText(this, "Failed to decode", Toast.LENGTH_SHORT).show());
                    return;
                }
                Bitmap bmp = BitmapUtils.applyOrientation(raw,
                        BitmapUtils.readExifOrientation(fileBytes));

                // Get original filename and file path for Samsung Revert
                String origName = getDisplayName(uri);
                String[] pathAndId = getFilePathAndId(uri);

                state.reset();
                state.setOriginalFileBytes(fileBytes);
                state.setOriginalFilename(origName);
                if (pathAndId != null) {
                    state.setOriginalFilePath(pathAndId[0]);
                    try { state.setMediaStoreId(Long.parseLong(pathAndId[1])); }
                    catch (NumberFormatException ignored) {}
                }

                boolean isJpeg = fileBytes.length > 2
                        && (fileBytes[0] & 0xFF) == 0xFF && (fileBytes[1] & 0xFF) == 0xD8;
                String metaInfo = "";

                if (isJpeg) {
                    state.setSourceFormat("jpeg");
                    List<JpegSegment> meta = JpegMetadataExtractor.extract(fileBytes);
                    state.setJpegMeta(meta);

                    boolean hasExif = false, hasIcc = false, hasXmp = false, hasMpf = false;
                    for (JpegSegment seg : meta) {
                        if (seg.isExif()) hasExif = true;
                        if (seg.isIcc()) hasIcc = true;
                        if (seg.isXmp()) hasXmp = true;
                        if (seg.isMpf()) hasMpf = true;
                    }
                    Log.d(TAG, "Segments: " + meta.size()
                            + " EXIF=" + hasExif + " ICC=" + hasIcc
                            + " XMP=" + hasXmp + " MPF=" + hasMpf);

                    byte[] gainMap = GainMapExtractor.extract(fileBytes);
                    state.setGainMap(gainMap);

                    // Extract Samsung SEFT trailer
                    byte[] seft = com.cropcenter.metadata.SeftExtractor.extract(fileBytes);
                    state.setSeftTrailer(seft);

                    boolean hasSeft = fileBytes.length >= 12
                            && fileBytes[fileBytes.length-4] == 'S' && fileBytes[fileBytes.length-3] == 'E'
                            && fileBytes[fileBytes.length-2] == 'F' && fileBytes[fileBytes.length-1] == 'T';

                    Log.d(TAG, "HDR=" + (gainMap != null ? gainMap.length + "b" : "none")
                            + " SEFT=" + hasSeft + " MPF=" + hasMpf + " XMP=" + hasXmp);

                    StringBuilder sb = new StringBuilder();
                    if (hasExif) sb.append("EXIF");
                    if (hasIcc) { if (sb.length() > 0) sb.append("+"); sb.append("ICC"); }
                    if (hasXmp) { if (sb.length() > 0) sb.append("+"); sb.append("XMP"); }
                    if (gainMap != null) { if (sb.length() > 0) sb.append("+"); sb.append("HDR"); }
                    if (hasSeft) { if (sb.length() > 0) sb.append("+"); sb.append("Samsung"); }
                    metaInfo = sb.toString();
                } else {
                    state.setSourceFormat("png");
                    metaInfo = "PNG";
                }

                final int w = bmp.getWidth(), h = bmp.getHeight();
                final String info = w + "\u00D7" + h + "  " + metaInfo;
                final boolean hasHdr = state.getGainMap() != null;

                // Set default export filename: originalname_cropped
                String exportName = "crop";
                if (origName != null && !origName.isEmpty()) {
                    int dot = origName.lastIndexOf('.');
                    exportName = (dot > 0 ? origName.substring(0, dot) : origName) + "_cropped";
                }
                final String defName = exportName;

                runOnUiThread(() -> {
                    state.setSourceImage(bmp);
                    state.getExportConfig().filename = defName;
                    editorView.setState(state);
                    editorView.clearUndoHistory();
                    findViewById(R.id.btnSave).setEnabled(true);
                    txtImageInfo.setText(info);
                    // HDR diagnostic toast is shown on background thread above
                });
            } catch (Exception e) {
                Log.e(TAG, "Load failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Load failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                busy.set(false);
            }
        }).start();
    }

    // ── Export ──

    private void exportTo(Uri uri) {
        if (state.getSourceImage() == null) return;
        if (!busy.compareAndSet(false, true)) {
            Toast.makeText(this, "Busy", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                // Save original to Samsung backup location (for Gallery Revert)
                CropExporter.saveOriginalBackup(state);
                CropExporter.ExportResult result = CropExporter.export(state, getCacheDir());
                byte[] data = result.data();
                boolean hasHdr = state.getGainMap() != null && state.getGainMap().length > 0;
                Log.d(TAG, "Export: " + data.length + " bytes, HDR=" + hasHdr);

                // Write to SAF URI (wt = write + truncate)
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os == null) throw new IOException("Cannot open output stream for " + uri);
                    os.write(data);
                    os.flush();
                    Log.d(TAG, "Written " + data.length + " bytes to SAF");
                }

                // Check if hdrgm XMP is in output (definitive HDR check)
                boolean outputHasHdrgm = false;
                String outputStr = new String(data, 0, Math.min(data.length, 65536));
                if (outputStr.contains("hdrgm")) outputHasHdrgm = true;

                final String msg = "Saved " + data.length / 1024 + "KB"
                        + (outputHasHdrgm ? " [HDR OK]" : (hasHdr ? " [HDR FAILED]" : ""));
                Log.d(TAG, msg + " tail=" + hex(data, data.length - 4, 4));
                runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "Export failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                busy.set(false);
            }
        }).start();
    }

    // ── Mode buttons ──

    private void setupModeButtons() {
        View.OnClickListener click = v -> {
            int id = v.getId();
            if (id == R.id.btnModeMove) {
                state.setEditorMode(EditorMode.MOVE);
            } else if (id == R.id.btnModeSelect) {
                state.setEditorMode(EditorMode.SELECT_FEATURE);
            }
            applyLockMode();
            updateModeHighlight();
            updateLockHighlight();
            // Only recompute crop when entering Select mode (and center not locked)
            if (state.getEditorMode() == EditorMode.SELECT_FEATURE && !isCenterLocked() && !isPanning()) {
                recomputeForLockChange();
            }
            editorView.invalidate();
        };
        findViewById(R.id.btnModeMove).setOnClickListener(click);
        findViewById(R.id.btnModeSelect).setOnClickListener(click);
    }

    private CenterMode moveLockPref = CenterMode.HORIZONTAL;
    private CenterMode selectLockPref = CenterMode.BOTH;

    private void setupCenterModeButtons() {
        View.OnClickListener lockClick = v -> {
            int id = v.getId();
            CenterMode pref;
            if (id == R.id.btnLockBoth) pref = CenterMode.BOTH;
            else if (id == R.id.btnLockH) pref = CenterMode.HORIZONTAL;
            else pref = CenterMode.VERTICAL;

            setCurrentPref(pref);
            applyLockMode();
            updateLockHighlight();

            if (state.getEditorMode() == EditorMode.SELECT_FEATURE && !isCenterLocked() && !isPanning()) {
                // Select mode: recompute crop to reflect new lock axis
                recomputeForLockChange();
            } else if (state.getEditorMode() == EditorMode.MOVE && state.hasCenter()
                    && !state.getSelectionPoints().isEmpty() && !isPanning()) {
                // Move mode: recenter crop on selection for the new axis direction
                recenterOnSelection();
            }
            editorView.invalidate();
        };
        findViewById(R.id.btnLockBoth).setOnClickListener(lockClick);
        findViewById(R.id.btnLockH).setOnClickListener(lockClick);
        findViewById(R.id.btnLockV).setOnClickListener(lockClick);

        // Pan checkbox: crop frozen, drag pans viewport
        ((CheckBox) findViewById(R.id.chkPan)).setOnCheckedChangeListener((btn, isChecked) -> {
            applyLockMode();
            updateLockHighlight();
            // Recompute only when turning Pan off in Select mode
            if (!isChecked
                    && state.getEditorMode() == EditorMode.SELECT_FEATURE
                    && !state.isCenterLocked()) {
                recomputeForLockChange();
            }
            editorView.invalidate();
        });

        // Lock checkbox: locks current auto-computed center from selection points.
        // When unchecked in Select mode, selection recomputes automatically.
        // In Move mode, unchecking does NOT recompute (user's current position is preserved).
        ((CheckBox) findViewById(R.id.chkLockCenter)).setOnCheckedChangeListener((btn, isChecked) -> {
            state.setCenterLocked(isChecked);
            if (!isChecked
                    && state.getEditorMode() == EditorMode.SELECT_FEATURE
                    && !state.getSelectionPoints().isEmpty()) {
                recomputeForLockChange();
            }
            editorView.invalidate();
        });
    }

    private boolean isPanning() {
        return ((CheckBox) findViewById(R.id.chkPan)).isChecked();
    }

    private boolean isCenterLocked() {
        return state.isCenterLocked();
    }

    private CenterMode getCurrentPref() {
        return state.getEditorMode() == EditorMode.SELECT_FEATURE ? selectLockPref : moveLockPref;
    }

    private void setCurrentPref(CenterMode pref) {
        if (state.getEditorMode() == EditorMode.SELECT_FEATURE) selectLockPref = pref;
        else moveLockPref = pref;
    }

    private void applyLockMode() {
        boolean panning = isPanning();
        state.setCenterMode(panning ? CenterMode.LOCKED : getCurrentPref());
    }

    /** Recenter the crop on selection points without resizing (for Move mode axis switch). */
    private void recenterOnSelection() {
        var points = state.getSelectionPoints();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int active = 0;
        for (var p : points) {
            if (!p.active) continue;
            active++;
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
        }
        if (active == 0) return;
        float midX = (active == 1) ? minX : (minX + maxX) / 2f;
        float midY = (active == 1) ? minY : (minY + maxY) / 2f;

        // Move the center to the selection midpoint without changing crop size
        state.setCropSizeDirty(false);
        state.setCenter(midX, midY);
    }

    private void updateLockHighlight() {
        CenterMode pref = getCurrentPref();
        int active = getResources().getColor(R.color.mauve, null);
        int inactive = getResources().getColor(R.color.surface2, null);
        ((MaterialButton) findViewById(R.id.btnLockBoth)).setTextColor(
                pref == CenterMode.BOTH ? active : inactive);
        ((MaterialButton) findViewById(R.id.btnLockH)).setTextColor(
                pref == CenterMode.HORIZONTAL ? active : inactive);
        ((MaterialButton) findViewById(R.id.btnLockV)).setTextColor(
                pref == CenterMode.VERTICAL ? active : inactive);
    }

    private void recomputeForLockChange() {
        if (!state.getSelectionPoints().isEmpty()) {
            CropEngine.autoComputeFromPoints(state);
        } else if (state.hasCenter()) {
            state.markCropSizeDirty();
            CropEngine.recomputeCrop(state);
        }
        editorView.invalidate();
    }

    private void setupUndoRedo() {
        findViewById(R.id.btnUndo).setOnClickListener(v -> editorView.undo());
        findViewById(R.id.btnRedo).setOnClickListener(v -> editorView.redo());
    }

    private void setupClearPointsButton() {
        findViewById(R.id.btnClearPoints).setOnClickListener(v -> {
            state.getSelectionPoints().clear();
            editorView.clearUndoHistory();
            editorView.resetCropToFullImage();
            editorView.invalidate();
            updatePointButtonStates();
        });
    }

    private com.cropcenter.view.RotationRulerView rotationRuler;
    private TextView txtRotDegrees;
    private boolean rulerUpdating;

    private void setupRotation() {
        rotationRuler = findViewById(R.id.rotationRuler);
        txtRotDegrees = findViewById(R.id.txtRotDegrees);

        rotationRuler.setOnRotationChangedListener(deg -> {
            if (rulerUpdating) return;
            state.setRotationDegrees(deg);
        });

        // Tap degree readout → open precise input dialog
        txtRotDegrees.setOnClickListener(v -> showPreciseRotationDialog());

        // Start disabled (no image)
        rotationRuler.setRulerEnabled(false);
    }

    /** Sync ruler + readout to current state rotation. */
    private void syncRotationUI() {
        float deg = state.getRotationDegrees();
        boolean hasImage = state.getSourceImage() != null;

        rulerUpdating = true;
        rotationRuler.setDegrees(deg);
        rulerUpdating = false;
        rotationRuler.setRulerEnabled(hasImage);

        txtRotDegrees.setVisibility(View.VISIBLE);
        txtRotDegrees.setText(formatDeg(deg));
    }

    private static String formatDeg(float deg) {
        if (deg == (int) deg) return (int) deg + "\u00B0";
        // Show full precision: 2 decimals if sub-0.1, 1 decimal otherwise
        if (Math.abs(deg * 10 - Math.round(deg * 10)) > 0.001f)
            return String.format("%.2f\u00B0", deg);
        return String.format("%.1f\u00B0", deg);
    }

    /** Dialog for entering an exact rotation value. */
    private void showPreciseRotationDialog() {
        if (state.getSourceImage() == null) return;
        float density = getResources().getDisplayMetrics().density;
        int dp = (int) density;

        final float originalDeg = state.getRotationDegrees();

        EditText input = new EditText(this);
        input.setText(String.format("%.2f", originalDeg));
        input.setTextSize(18); input.setGravity(android.view.Gravity.CENTER);
        input.setTextColor(0xFFCDD6F4); input.setBackgroundColor(0xFF313244);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setSingleLine(true);
        input.setPadding(12*dp, 10*dp, 12*dp, 10*dp);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Enter Rotation (\u00B0)")
                .setView(input)
                .setPositiveButton("Apply", (d, w) -> {
                    try {
                        float val = Math.max(-180f, Math.min(180f,
                                Float.parseFloat(input.getText().toString().trim())));
                        state.setRotationDegrees(val);
                        syncRotationUI();
                    } catch (NumberFormatException ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setupAutoRotate() {
        TextView btn = findViewById(R.id.btnAutoRotate);
        btn.setOnClickListener(v -> {
            if (state.getSourceImage() == null) return;

            if (editorView.isHorizonMode()) {
                // Cancel horizon mode
                editorView.setHorizonMode(false, null);
                btn.setText("Auto");
                btn.setTextColor(getResources().getColor(R.color.subtext0, null));
                return;
            }

            // Try XMP metadata first (instant)
            float metaAngle = com.cropcenter.util.HorizonDetector.detectFromMetadata(state.getJpegMeta());
            if (!Float.isNaN(metaAngle)) {
                state.setRotationDegrees(metaAngle);
                syncRotationUI();
                Toast.makeText(this, "From metadata: " + formatDeg(metaAngle), Toast.LENGTH_SHORT).show();
                return;
            }

            // Enter horizon paint mode — user paints over the horizon area
            btn.setText("Cancel");
            btn.setTextColor(getResources().getColor(R.color.red, null));
            editorView.setHorizonMode(true, () -> {
                btn.setText("Auto");
                btn.setTextColor(getResources().getColor(R.color.subtext0, null));

                var points = editorView.getHorizonPoints();
                float brushR = editorView.getHorizonBrushRadius();
                Bitmap src = state.getSourceImage();

                if (points.size() < 2 || src == null) {
                    Toast.makeText(this, "Paint was too short", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Run detection on background thread using only the painted region
                showProgress("Detecting horizon\u2026");
                new Thread(() -> {
                    float angle = com.cropcenter.util.HorizonDetector
                            .detectFromPaintedRegion(src, points, brushR);
                    runOnUiThread(() -> {
                        if (isDestroyed) return;
                        hideProgress();
                        if (Float.isNaN(angle)) {
                            Toast.makeText(this, "No line detected in painted area",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            // Replace — detection returns the absolute rotation
                            // needed for the painted line to become horizontal
                            float newRot = Math.round(angle * 100f) / 100f;
                            state.setRotationDegrees(newRot);
                            syncRotationUI();
                            Toast.makeText(this, formatDeg(newRot),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }).start();
            });
        });
    }

    private void updateAutoRotateVisibility() {
        findViewById(R.id.btnAutoRotate).setVisibility(
                state.getSourceImage() != null ? View.VISIBLE : View.GONE);
    }

    private void updatePointButtonStates() {
        boolean canUndo = editorView.canUndo();
        boolean canRedo = editorView.canRedo();
        boolean hasPoints = !state.getSelectionPoints().isEmpty();
        int enabledColor = getResources().getColor(R.color.subtext0, null);
        int disabledColor = getResources().getColor(R.color.surface1, null);

        MaterialButton btnUndo = findViewById(R.id.btnUndo);
        MaterialButton btnRedo = findViewById(R.id.btnRedo);
        MaterialButton btnClear = findViewById(R.id.btnClearPoints);

        btnUndo.setEnabled(canUndo);
        btnUndo.setTextColor(canUndo ? enabledColor : disabledColor);
        btnRedo.setEnabled(canRedo);
        btnRedo.setTextColor(canRedo ? enabledColor : disabledColor);
        btnClear.setEnabled(hasPoints);
        btnClear.setTextColor(hasPoints ? getResources().getColor(R.color.red, null) : disabledColor);
    }

    /** Request MANAGE_EXTERNAL_STORAGE for Samsung Revert backup. */
    private void ensureStoragePermission() {
        if (!android.os.Environment.isExternalStorageManager()) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Log.w(TAG, "Cannot request MANAGE_EXTERNAL_STORAGE", e);
            }
        }
    }

    /** If no crop center exists, auto-set to image center so AR changes take effect. */
    private void ensureCropCenter() {
        if (!state.hasCenter() && state.getSourceImage() != null) {
            state.markCropSizeDirty();
            state.setCenter(state.getImageWidth() / 2f, state.getImageHeight() / 2f);
        }
    }

    // ── UI updates ──

    private void updateCropInfo() {
        if (state.hasCenter()) {
            txtSidebarCropSize.setText(state.getCropW() + "\u00D7" + state.getCropH());
        } else if (state.getSourceImage() != null) {
            txtSidebarCropSize.setText("Full");
        } else {
            txtSidebarCropSize.setText("");
        }
    }

    private void updateModeHighlight() {
        EditorMode mode = state.getEditorMode();
        int active = getResources().getColor(R.color.mauve, null);
        int inactive = getResources().getColor(R.color.surface2, null);
        ((MaterialButton) findViewById(R.id.btnModeMove)).setTextColor(
                mode == EditorMode.MOVE ? active : inactive);
        ((MaterialButton) findViewById(R.id.btnModeSelect)).setTextColor(
                mode == EditorMode.SELECT_FEATURE ? active : inactive);

        boolean isSelect = mode == EditorMode.SELECT_FEATURE;

        // Both button only in Select mode
        findViewById(R.id.btnLockBoth).setVisibility(isSelect ? View.VISIBLE : View.GONE);
        // Auto-switch if Move mode and current pref is Both
        if (!isSelect && moveLockPref == CenterMode.BOTH) {
            moveLockPref = CenterMode.HORIZONTAL;
            applyLockMode();
        }

        // Point controls row is always visible
    }

    private void updateZoomBadge() {
        float zoom = editorView.getZoom();
        if (zoom > 1.01f) {
            txtZoomBadge.setVisibility(View.VISIBLE);
            // Compact format: "2x", "25.6x" — avoids huge "25600%"
            if (zoom < 10f) txtZoomBadge.setText(String.format("%.1fx", zoom));
            else txtZoomBadge.setText(Math.round(zoom) + "x");
        } else {
            txtZoomBadge.setVisibility(View.GONE);
        }
    }

    // ── Utility ──

    private String getDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Query MediaStore for file path and _ID. Returns [path, id] or null. */
    private String[] getFilePathAndId(Uri uri) {
        try {
            // Try to get _ID and DATA from MediaStore
            String[] proj = {"_id", "_data"};
            try (Cursor c = getContentResolver().query(uri, proj, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idIdx = c.getColumnIndex("_id");
                    int dataIdx = c.getColumnIndex("_data");
                    String id = idIdx >= 0 ? c.getString(idIdx) : null;
                    String path = dataIdx >= 0 ? c.getString(dataIdx) : null;
                    if (path != null && id != null) {
                        Log.d(TAG, "MediaStore: path=" + path + " id=" + id);
                        return new String[]{path, id};
                    }
                }
            }
            // For SAF URIs, try to extract document ID
            String docId = null;
            if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
                docId = android.provider.DocumentsContract.getDocumentId(uri);
                // docId format: "image:12345"
                if (docId != null && docId.startsWith("image:")) {
                    String msId = docId.substring(6);
                    // Query MediaStore by ID
                    Uri msUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    try (Cursor c = getContentResolver().query(msUri, new String[]{"_data"},
                            "_id=?", new String[]{msId}, null)) {
                        if (c != null && c.moveToFirst()) {
                            String path = c.getString(0);
                            if (path != null) return new String[]{path, msId};
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getFilePathAndId failed", e);
        }
        return null;
    }

    private static String hex(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < d.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", d[i] & 0xFF));
        }
        return sb.toString();
    }

    private static int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

}
