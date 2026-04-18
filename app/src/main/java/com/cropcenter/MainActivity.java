package com.cropcenter;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
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
import com.cropcenter.util.TextFormat;
import com.cropcenter.util.UltraHdrCompat;
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
    private TextView txtSidebarCropSize, txtImageInfo, txtImageFormats, txtZoomBadge, txtTransformArrow;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private Uri sourceUri; // URI of the opened file, for overwrite-in-place
    // Filename we asked SAF to create. When SAF silently auto-renames to avoid
    // a collision (e.g. "vacation.jpg" → "vacation (1).jpg"), the returned URI's
    // display name won't match this — that's how we detect the rename.
    private String pendingSaveName;
    // Set when we launch the SAF picker and cleared when its result arrives
    // (URI or cancel) or the Replace confirmation finishes. Gates rapid taps
    // between launch and result, and also between the result and the Replace
    // dialog response — busy.get() doesn't flip until exportTo actually runs,
    // so it isn't sufficient on its own.
    private boolean savePending;
    // One-shot prefix consumed by the next successful save's toast. Used when
    // SAF auto-renamed a collision and we want the user to see both the rename
    // notice and the save result as a single toast instead of two.
    private String pendingSaveToastPrefix;
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
        txtImageFormats = findViewById(R.id.txtImageFormats);
        txtTransformArrow = findViewById(R.id.txtTransformArrow);

        editorView.setState(state);
        editorView.setOnZoomChangedListener(this::updateZoomBadge);
        editorView.setOnPointsChangedListener(this::updatePointButtonStates);
        final boolean[] inListener = {false};
        state.setListener(() -> runOnUiThread(() -> {
            if (inListener[0]) return;
            inListener[0] = true;
            try {
                if (state.isCropSizeDirty()) {
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
                        // Take persistable permission for overwrite-in-place.
                        // Non-fatal if it fails — we just lose the ability to
                        // re-open this URI across app restarts.
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } catch (Exception e) {
                            Log.w(TAG, "takePersistableUriPermission failed for " + uri, e);
                        }
                        // NB: don't assign sourceUri here — loadImage assigns it only
                        // after a successful decode, so a failed/blocked load can't
                        // leave sourceUri pointing at a URI whose image isn't in state.
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
                    // SAF result: either a URI (user chose a file) or null (user
                    // cancelled). Clear the pending-save flag in both cases so
                    // the Save button re-enables.
                    if (uri != null) {
                        handleSaveAsResult(uri);
                    } else {
                        savePending = false;
                        pendingSaveName = null;
                    }
                });

        findViewById(R.id.btnOpen).setOnClickListener(v ->
                openLauncher.launch(new String[]{"image/jpeg", "image/png"}));
        findViewById(R.id.btnSave).setOnClickListener(v -> showSaveDialog());
        // Save stays disabled until an image is loaded.
        setBusyUi(false);
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                com.cropcenter.view.SettingsDialog.show(this, state.getGridConfig(),
                        () -> editorView.invalidate()));

        // Display-only toggle; full grid settings live in the Settings dialog.
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Bitmap bmp = state.getSourceImage();
        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
    }

    /** Show the full-screen progress overlay with the given message. */
    private void showProgress(String message) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            View overlay = findViewById(R.id.progressOverlay);
            ((TextView) findViewById(R.id.progressText)).setText(message);
            overlay.setVisibility(View.VISIBLE);
        });
    }

    private void hideProgress() {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
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
                // Share/View intents often don't carry persistable permission —
                // failure here is expected, not an error.
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception e) {
                    Log.d(TAG, "No persistable permission for shared URI: " + e.getMessage());
                }
                // sourceUri is assigned by loadImage on successful decode.
                loadImage(uri);
            }
        }
    }

    // ── Save ──

    /**
     * Save button handler. Runs {@link SaveDialog} first (format + grid-bake
     * options), and on its "Continue" the SAF picker opens with the correct
     * extension pre-filled. Replace/Keep confirmation is handled downstream
     * in {@link #handleSaveAsResult}.
     */
    private void showSaveDialog() {
        if (state.getSourceImage() == null) return;
        if (busy.get() || savePending) {
            Toast.makeText(this, "Busy — try again", Toast.LENGTH_SHORT).show();
            return;
        }
        SaveDialog.show(this, state.getExportConfig(), state.getGridConfig(), () -> {
            if (busy.get() || savePending) {
                Toast.makeText(this, "Busy — try again", Toast.LENGTH_SHORT).show();
                return;
            }
            // Extension follows the format the user just picked in SaveDialog;
            // if they change the extension in the SAF picker, applyFormatFromFilename
            // updates ExportConfig.format again before encode.
            String stem = state.getOriginalFilename();
            if (stem == null || stem.isEmpty()) stem = "crop";
            int dot = stem.lastIndexOf('.');
            if (dot > 0) stem = stem.substring(0, dot);
            String ext = "png".equals(state.getExportConfig().format) ? ".png" : ".jpg";
            String name = stem + ext;
            pendingSaveName = name;
            savePending = true;
            saveAsLauncher.launch(name);
        });
    }

    /**
     * Route the SAF-returned URI to the correct save path.
     *
     * SAF's {@code ACTION_CREATE_DOCUMENT} behaviour on filename collision is
     * inconsistent across providers. The returned URI's display name tells us
     * what actually happened:
     *
     *   (A) {@code chosen == requested}  — SAF kept the name. Either the
     *       file didn't exist (new file) or SAF prompted "Replace?" and the
     *       user accepted. Either way, write to {@code newUri} directly; SAF
     *       already handled any confirmation, so no extra dialog from us.
     *       This fixes the "two dialogs" bug.
     *
     *   (B) {@code chosen} matches the {@code requested (N).ext} auto-rename
     *       pattern — SAF silently renamed to dodge a collision. The user's
     *       typed-or-accepted name was in use; they got no confirmation.
     *       If that conflicting name was our opened file, we offer an
     *       in-place overwrite of {@code sourceUri} (delete the "(N)"
     *       placeholder, write to sourceUri via the persistable grant).
     *       Otherwise we can't target the conflicting file directly, so we
     *       save to the renamed URI and toast the actual final name — the
     *       rename no longer goes unnoticed. This fixes bug #1.
     *
     *   (C) {@code chosen} differs from {@code requested} but NOT in the
     *       auto-rename pattern — the user deliberately changed the filename
     *       in the picker. Save to {@code newUri} with no extra dialog.
     *
     * Bug #3 fix: {@code tryDeleteSafDocument(newUri)} is only called in
     * case (B)-overwrite-original, where {@code newUri} is guaranteed to be
     * a freshly-created "(N)" placeholder — never the opened file itself,
     * which keeps its own URI in {@code sourceUri}.
     */
    private void handleSaveAsResult(Uri newUri) {
        String requested = pendingSaveName;
        pendingSaveName = null;

        String chosen = getDisplayName(newUri);
        applyFormatFromFilename(chosen);

        // Case (A): SAF accepted the requested name exactly. Either no
        // collision or SAF itself prompted and the user confirmed; save as-is.
        if (requested != null && chosen != null && requested.equalsIgnoreCase(chosen)) {
            savePending = false;
            exportTo(newUri);
            return;
        }

        // Case (B): SAF auto-renamed ("stem (N).ext"). A collision exists in
        // the user's CHOSEN directory — NOT necessarily the opened file's
        // directory. Offer Replace / Keep / Cancel on the chosen location.
        if (looksLikeAutoRename(requested, chosen)) {
            Runnable cleanupPlaceholder = () -> tryDeleteSafDocument(newUri);
            final String safName = chosen;
            final String targetName = requested;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Replace " + targetName + "?")
                    .setMessage("A file with this name already exists in the "
                            + "selected location.\n\n"
                            + "Replace \u2014 overwrite it.\n"
                            + "Keep \u2014 save as \"" + safName + "\" instead.\n"
                            + "Cancel \u2014 don't save.")
                    .setPositiveButton("Replace", (d, w) -> {
                        savePending = false;
                        replaceCollidingInChosenDir(newUri, targetName);
                    })
                    .setNeutralButton("Keep", (d, w) -> {
                        // Keep the SAF-assigned name — newUri IS the target.
                        savePending = false;
                        exportTo(newUri);
                    })
                    .setNegativeButton("Cancel", (d, w) -> {
                        cleanupPlaceholder.run();
                        savePending = false;
                    })
                    // BACK or touch-outside behaves like Cancel.
                    .setOnCancelListener(d -> {
                        cleanupPlaceholder.run();
                        savePending = false;
                    })
                    .show();
            return;
        }

        // Case (C): user changed the name intentionally. Save as-is.
        savePending = false;
        exportTo(newUri);
    }

    /**
     * Replace the colliding file in the user's chosen directory (NOT the
     * opened file's directory).
     *
     * Sequence:
     *   1. Derive the colliding file's URI by substituting the last path
     *      segment of {@code newUri}'s document ID with {@code requestedName}.
     *   2. Delete it via {@link DocumentsContract#deleteDocument}.
     *   3. Rename {@code newUri} from "stem (N).ext" to {@code requestedName}
     *      via {@link DocumentsContract#renameDocument}.
     *   4. Write bytes to the renamed URI.
     *
     * Any step may fail (opaque-ID providers, missing permissions, etc.). On
     * partial failure we fall through to saving at the SAF-assigned name so
     * the user never loses their crop — a toast prefix explains what ended up
     * on disk.
     */
    private void replaceCollidingInChosenDir(Uri newUri, String requestedName) {
        Uri saveTarget = newUri;
        boolean renamedOk = false;

        Uri colliding = deriveSiblingUri(newUri, requestedName);
        if (colliding != null) {
            try {
                android.provider.DocumentsContract.deleteDocument(
                        getContentResolver(), colliding);
            } catch (Exception e) {
                Log.w(TAG, "Couldn't delete colliding " + requestedName
                        + " (" + colliding + "): " + e.getMessage());
            }
        }

        try {
            Uri renamed = android.provider.DocumentsContract.renameDocument(
                    getContentResolver(), newUri, requestedName);
            if (renamed != null) {
                saveTarget = renamed;
                renamedOk = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't rename to " + requestedName + ": " + e.getMessage());
        }

        if (!renamedOk) {
            String actual = getDisplayName(saveTarget);
            if (actual == null) actual = "auto-renamed file";
            pendingSaveToastPrefix = "Saved as " + actual
                    + " (couldn't replace existing) \u2014 ";
        }
        exportTo(saveTarget);
    }

    /**
     * Build a sibling document URI by swapping the last path segment of
     * {@code src}'s document ID for {@code siblingName}. Works on providers
     * that encode paths in their document IDs (notably the built-in external-
     * storage provider). Returns null for opaque-ID providers.
     *
     * The returned URI isn't guaranteed to be actionable with our permission
     * set — callers treat delete/rename failures on it as soft errors.
     */
    private static Uri deriveSiblingUri(Uri src, String siblingName) {
        try {
            String docId = android.provider.DocumentsContract.getDocumentId(src);
            int slash = docId.lastIndexOf('/');
            if (slash < 0) return null;
            return android.provider.DocumentsContract.buildDocumentUri(
                    src.getAuthority(),
                    docId.substring(0, slash + 1) + siblingName);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Detect SAF's auto-rename naming pattern: "{@code stem (N).ext}" where
     * stem/ext match the requested filename. Used to separate an SAF silent
     * rename from the user genuinely typing a different name in the picker.
     */
    private static boolean looksLikeAutoRename(String requested, String chosen) {
        if (requested == null || chosen == null) return false;
        int reqDot = requested.lastIndexOf('.');
        int choDot = chosen.lastIndexOf('.');
        if (reqDot < 0 || choDot < 0) return false;
        String reqStem = requested.substring(0, reqDot);
        String reqExt = requested.substring(reqDot);
        String choStem = chosen.substring(0, choDot);
        String choExt = chosen.substring(choDot);
        if (!reqExt.equalsIgnoreCase(choExt)) return false;
        // "stem (1)", "stem (2)", … — accept 1+ digits, optional whitespace
        // between stem and paren for providers that use no space.
        return choStem.matches("\\Q" + reqStem + "\\E\\s*\\(\\d+\\)");
    }

    /** Pick encoder based on the extension the user typed in the SAF picker. */
    private void applyFormatFromFilename(String name) {
        if (name == null) return;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) {
            state.getExportConfig().format = "png";
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            state.getExportConfig().format = "jpeg";
        }
        // Anything else leaves the format unchanged (source default).
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
        setBusyUi(true);

        new Thread(() -> {
            try {
                File cacheFile = new File(getCacheDir(), "input_raw");
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    if (is == null) throw new IOException("Cannot open URI");
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
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
                final String sizeInfo = w + "\u00D7" + h;
                final String formatsInfo = metaInfo;
                final boolean hasHdr = state.getGainMap() != null;

                runOnUiThread(() -> {
                    state.setSourceImage(bmp);
                    // Commit sourceUri only after the image is installed in state,
                    // so Save (which overwrites sourceUri) is always in sync with
                    // what's actually loaded. See also load-failure path below.
                    sourceUri = uri;
                    editorView.setState(state);
                    editorView.clearUndoHistory();
                    txtImageInfo.setText(sizeInfo);
                    txtImageFormats.setText(formatsInfo);
                });
            } catch (Exception e) {
                Log.e(TAG, "Load failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Load failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                busy.set(false);
                runOnUiThread(() -> setBusyUi(false));
            }
        }).start();
    }

    // ── Export ──

    private void exportTo(Uri uri) {
        if (state.getSourceImage() == null) return;
        if (!busy.compareAndSet(false, true)) {
            Toast.makeText(this, "Busy — try again", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusyUi(true);
        showProgress("Saving\u2026");
        new Thread(() -> {
            try { doExport(uri); }
            finally {
                busy.set(false);
                runOnUiThread(() -> setBusyUi(false));
                hideProgress();
            }
        }).start();
    }

    /**
     * Export pipeline: encode → write → verify → report.
     *
     * Success signal hierarchy:
     *   1. Write path completes without exception → definitively saved.
     *   2. Write path threw → read the file back to count persisted bytes.
     *      Many SAF providers throw harmless EPIPE/IOException on close
     *      yet persist the full payload, so readback is the ground truth.
     *   3. Neither → genuine failure; delete the partial file.
     */
    private void doExport(Uri uri) {
        final boolean isPng = "png".equals(state.getExportConfig().format);

        // ── Phase 1: encode ──
        byte[] data;
        boolean srcHadHdr;
        boolean backupFailed = false;
        try {
            CropExporter.BackupStatus backup = CropExporter.saveOriginalBackup(state);
            backupFailed = (backup == CropExporter.BackupStatus.FAILED);
            data = CropExporter.export(state, getCacheDir()).data();
            srcHadHdr = state.getGainMap() != null && state.getGainMap().length > 0;
            Log.d(TAG, "Encoded " + data.length + " bytes (srcHdr=" + srcHadHdr
                    + " isPng=" + isPng + " backup=" + backup + ")");
        } catch (Exception e) {
            Log.e(TAG, "Encode failed", e);
            final String emsg = "Export failed: " + e.getMessage();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                Toast.makeText(this, emsg, Toast.LENGTH_SHORT).show();
            });
            return;
        }

        // saveOriginalBackup returns FAILED only when the source is a MediaStore
        // file (so Gallery Revert is relevant) AND the backup couldn't be written.
        // NOT_APPLICABLE sources and already-existing backups skip the warning.
        // SAF's picker — not this app — decides whether the user is overwriting,
        // so we warn any time the backup didn't survive regardless of URI identity.
        if (backupFailed) {
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                Toast.makeText(this,
                        "Warning: couldn't write revert backup — Gallery Revert won't work if you overwrite",
                        Toast.LENGTH_LONG).show();
            });
        }

        // ── Phase 2: write ──
        // try-with-resources: close() runs after writeReturned=true, so close
        // failures can't invalidate a successful write (the catch block sets
        // writeException but writeReturned is already locked true).
        boolean writeReturned = false;
        Exception writeException = null;
        try (java.io.OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) throw new IOException("openOutputStream returned null");
            os.write(data);
            writeReturned = true;
        } catch (Exception e) {
            writeException = e;
            Log.w(TAG, "Write path threw (may still have persisted)", e);
        }

        // ── Phase 3: verify ──
        boolean savedOk = writeReturned;
        long verifiedBytes = -1;
        if (!savedOk) {
            verifiedBytes = readbackByteCount(uri, data.length);
            savedOk = verifiedBytes >= data.length;
            if (savedOk) Log.d(TAG, "Recovered via readback: " + verifiedBytes + " bytes");
        }
        Log.d(TAG, "Save result: writeReturned=" + writeReturned
                + " verifiedBytes=" + verifiedBytes + " expected=" + data.length
                + " → savedOk=" + savedOk);

        // ── Phase 4: report ──
        if (savedOk) {
            // HDR suffix is informational. PNG can't carry gain maps — that's a
            // format limitation, NOT a failure, so suppress the suffix in that case.
            // "[HDR dropped]" only fires when JPEG export dropped an HDR source.
            final String hdrSuffix;
            if (!srcHadHdr)                                  hdrSuffix = "";
            else if (UltraHdrCompat.containsHdrgm(data))     hdrSuffix = " [HDR OK]";
            else if (isPng)                                  hdrSuffix = "";
            else                                             hdrSuffix = " [HDR dropped]";

            // Consume any pending rename notice so the user sees one toast,
            // not two ("Saved as X — name was in use" combined with size).
            String prefix = pendingSaveToastPrefix;
            pendingSaveToastPrefix = null;
            final String msg = (prefix != null ? prefix : "Saved ")
                    + data.length / 1024 + "KB" + hdrSuffix;
            final int length = prefix != null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                Toast.makeText(this, msg, length).show();
            });
        } else {
            final String emsg = writeException != null
                    ? "Export failed: " + writeException.getMessage()
                    : "Export failed";
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                Toast.makeText(this, emsg, Toast.LENGTH_SHORT).show();
            });
            tryDeleteSafDocument(uri);
        }
    }

    /**
     * Read the file at {@code uri} back and return the number of bytes readable,
     * short-circuiting as soon as {@code minBytes} is reached. Returns -1 if the
     * provider can't serve the file at all. This is the authoritative verification
     * when the write path throws — neither OpenableColumns.SIZE nor PFD.getStatSize
     * are reliable across DocumentsProvider implementations.
     */
    private long readbackByteCount(Uri uri, int minBytes) {
        long total = 0;
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return -1;
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total >= minBytes) return total;
            }
            return total;
        } catch (Exception e) {
            Log.w(TAG, "readbackByteCount: " + e.getMessage());
            return total > 0 ? total : -1;
        }
    }

    /** Best-effort delete of a SAF document URI. Silently ignores failures. */
    private void tryDeleteSafDocument(Uri uri) {
        try {
            // Try DocumentsContract first (works for CREATE_DOCUMENT URIs)
            if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri);
                return;
            }
        } catch (Exception ignored) {}
        try {
            getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {}
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

        // Unlocking in Select mode re-derives the center from selection points;
        // Move mode preserves the user's current position.
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

        // Clear the readout when there's nothing to rotate so the info bar
        // doesn't display a stale "0°" against no image.
        txtRotDegrees.setText(hasImage ? TextFormat.degrees(deg) : "");
    }

    /** Disable Save/Open while busy so rapid taps can't stack up. UI thread only. */
    private void setBusyUi(boolean isBusy) {
        View btnSave = findViewById(R.id.btnSave);
        View btnOpen = findViewById(R.id.btnOpen);
        boolean hasImage = state.getSourceImage() != null;
        if (btnSave != null) btnSave.setEnabled(!isBusy && hasImage);
        if (btnOpen != null) btnOpen.setEnabled(!isBusy);
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
                        // syncRotationUI runs via state listener — no manual call needed
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
                // syncRotationUI runs via state listener
                Toast.makeText(this, "From metadata: " + TextFormat.degrees(metaAngle), Toast.LENGTH_SHORT).show();
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
                        if (isDestroyed()) return;
                        hideProgress();
                        if (Float.isNaN(angle)) {
                            Toast.makeText(this, "No line detected in painted area",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            // Replace — detection returns the absolute rotation
                            // needed for the painted line to become horizontal
                            float newRot = Math.round(angle * 100f) / 100f;
                            state.setRotationDegrees(newRot);
                            // syncRotationUI runs via state listener
                            Toast.makeText(this, TextFormat.degrees(newRot),
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
        boolean hasImage = state.getSourceImage() != null;
        if (state.hasCenter()) {
            txtSidebarCropSize.setText(state.getCropW() + "\u00D7" + state.getCropH());
        } else if (hasImage) {
            txtSidebarCropSize.setText("Full");
        } else {
            txtSidebarCropSize.setText("");
        }
        if (txtTransformArrow != null) {
            txtTransformArrow.setVisibility(hasImage ? View.VISIBLE : View.GONE);
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

        findViewById(R.id.btnLockBoth).setVisibility(isSelect ? View.VISIBLE : View.GONE);
        // BOTH is a Select-only option; fall back to Horizontal when leaving Select mode.
        if (!isSelect && moveLockPref == CenterMode.BOTH) {
            moveLockPref = CenterMode.HORIZONTAL;
            applyLockMode();
        }

        // Undo/Redo/Clear only visible in Select mode (they act on selection points)
        int pointCtrlVis = isSelect ? View.VISIBLE : View.GONE;
        findViewById(R.id.btnUndo).setVisibility(pointCtrlVis);
        findViewById(R.id.btnRedo).setVisibility(pointCtrlVis);
        findViewById(R.id.btnClearPoints).setVisibility(pointCtrlVis);
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
        } catch (Exception e) {
            Log.w(TAG, "getDisplayName query failed for " + uri, e);
        }
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
