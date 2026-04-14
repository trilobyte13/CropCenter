package com.cropcenter.view;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.cropcenter.model.ExportConfig;

/**
 * Pre-save dialog with Save As and optional Overwrite Original.
 */
public class SaveDialog {

    public interface OnSaveListener {
        void onSave();
    }

    public static void show(Context context, ExportConfig export,
                            OnSaveListener onSaveAs, OnSaveListener onOverwrite) {
        int dp = (int) context.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20 * dp, 12 * dp, 20 * dp, 8 * dp);

        // Filename
        TextView lblName = new TextView(context);
        lblName.setText("Filename"); lblName.setTextSize(13); lblName.setTextColor(0xFFA6ADC8);
        root.addView(lblName);

        EditText editName = new EditText(context);
        editName.setText(export.filename);
        editName.setTextSize(14); editName.setSingleLine(true);
        editName.setBackgroundColor(0xFF313244); editName.setTextColor(0xFFCDD6F4);
        editName.setPadding(8*dp, 6*dp, 8*dp, 6*dp);
        root.addView(editName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Format
        LinearLayout fmtRow = new LinearLayout(context);
        fmtRow.setOrientation(LinearLayout.HORIZONTAL);
        fmtRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fmtLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fmtLP.topMargin = 10 * dp;

        TextView lblFmt = new TextView(context);
        lblFmt.setText("Format  "); lblFmt.setTextSize(13); lblFmt.setTextColor(0xFFA6ADC8);
        fmtRow.addView(lblFmt);

        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, new String[]{"JPEG", "PNG"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection("jpeg".equals(export.format) ? 0 : 1);
        fmtRow.addView(spinner);
        root.addView(fmtRow, fmtLP);

        // Export grid
        CheckBox chkBake = new CheckBox(context);
        chkBake.setText("Export Grid"); chkBake.setTextSize(12);
        chkBake.setChecked(export.includeGrid);
        LinearLayout.LayoutParams bakeLP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bakeLP.topMargin = 6 * dp;
        root.addView(chkBake, bakeLP);

        Runnable applySettings = () -> {
            export.filename = editName.getText().toString().trim();
            export.format = spinner.getSelectedItemPosition() == 0 ? "jpeg" : "png";
            export.includeGrid = chkBake.isChecked();
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("Save Image")
                .setView(root)
                .setPositiveButton("Save As", (d, w) -> {
                    applySettings.run();
                    onSaveAs.onSave();
                })
                .setNegativeButton("Cancel", null);

        // Overwrite option: writes to original URI, preserving MediaStore ownership
        if (onOverwrite != null) {
            builder.setNeutralButton("Overwrite", (d, w) -> {
                applySettings.run();
                onOverwrite.onSave();
            });
        }

        builder.show();
    }
}
