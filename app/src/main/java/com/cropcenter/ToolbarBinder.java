package com.cropcenter;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.cropcenter.crop.CropEngine;
import com.cropcenter.model.AspectRatio;
import com.cropcenter.model.CenterMode;
import com.cropcenter.model.EditorMode;
import com.cropcenter.util.HorizonDetector;
import com.cropcenter.util.TextFormat;
import com.cropcenter.util.ThemeColors;

import java.util.List;
import java.util.Locale;

/**
 * Wires the toolbar controls (mode / lock / AR / undo-redo / clear / rotation / auto-rotate)
 * and two secondary dialogs (custom AR, precise rotation). All onClick handlers route back into
 * the activity for crop-state manipulation and into UiSync for visual updates, keeping the
 * binder free of any direct rendering or state mutation beyond what the corresponding control
 * conceptually owns.
 */
final class ToolbarBinder
{
	private static final AspectRatio[] AR_VALUES = {
		AspectRatio.R4_5, AspectRatio.FREE, AspectRatio.R16_9, AspectRatio.R3_2,
		AspectRatio.R4_3, AspectRatio.R5_4, AspectRatio.R1_1, AspectRatio.R3_4,
		AspectRatio.R2_3, AspectRatio.R9_16, null
	};
	private static final String TAG = "ToolbarBinder";
	private static final String[] AR_LABELS = {
		"4:5", "Full", "16:9", "3:2", "4:3", "5:4", "1:1", "3:4", "2:3", "9:16", "Custom"
	};

	private final ToolbarHost host;
	private final UiSync ui;

	ToolbarBinder(ToolbarHost host, UiSync ui)
	{
		this.host = host;
		this.ui = ui;
	}

	/**
	 * Entry point called once from MainActivity.onCreate, after setContentView and view lookups.
	 */
	void bindAll()
	{
		setupArSpinner();
		setupModeButtons();
		setupCenterModeButtons();
		setupUndoRedo();
		setupClearPointsButton();
		setupRotation();
		setupAutoRotate();
	}

	private void setupArSpinner()
	{
		Spinner spinner = host.findViewById(R.id.spinnerAr);
		float density = host.getActivity().getResources().getDisplayMetrics().density;
		int padH = (int) (6 * density);
		int padV = (int) (4 * density);

		// Custom adapter with compact item views (tight padding, 12sp text)
		ArrayAdapter<String> adapter = new ArrayAdapter<>(host.getActivity(),
			android.R.layout.simple_spinner_item, AR_LABELS)
		{
			@Override
			public View getView(int position, View convertView, ViewGroup parent)
			{
				return styleArLabel((TextView) super.getView(position, convertView, parent),
					12, padH, padV);
			}

			@Override
			public View getDropDownView(int position, View convertView, ViewGroup parent)
			{
				return styleArLabel((TextView) super.getDropDownView(position, convertView, parent),
					13, padH * 2, padV * 2);
			}
		};
		spinner.setAdapter(adapter);
		spinner.setSelection(0);

		// Size the spinner to exactly fit the widest label + arrow.
		Paint textPaint = new Paint();
		textPaint.setTextSize(12 * host.getActivity().getResources().getDisplayMetrics().scaledDensity);
		float maxTextPx = 0;
		for (String label : AR_LABELS)
		{
			maxTextPx = Math.max(maxTextPx, textPaint.measureText(label));
		}
		int totalPx = (int) maxTextPx + padH * 2 + (int) (24 * density);
		spinner.setMinimumWidth(totalPx);
		ViewGroup.LayoutParams lp = spinner.getLayoutParams();
		if (lp != null)
		{
			lp.width = totalPx;
			spinner.setLayoutParams(lp); // triggers re-layout
		}
		spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
		{
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int pos, long id)
			{
				if (pos < AR_VALUES.length && AR_VALUES[pos] != null)
				{
					host.getState().setAspectRatio(AR_VALUES[pos]);
					if (!host.getState().getSelectionPoints().isEmpty())
					{
						CropEngine.autoComputeFromPoints(host.getState());
					}
					else
					{
						host.ensureCropCenter();
					}
				}
				else
				{
					showCustomArDialog();
				}
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {}
		});
	}

	private void setupAutoRotate()
	{
		TextView btn = host.findViewById(R.id.btnAutoRotate);
		btn.setOnClickListener(view -> handleAutoRotateTap(btn));
	}

	/**
	 * Auto-rotate click handler. Three paths, in order: cancel paint mode if active;
	 * apply XMP-embedded horizon angle if present; else enter paint mode to let the
	 * user outline the horizon for background detection.
	 */
	private void handleAutoRotateTap(TextView btn)
	{
		if (host.getState().getSourceImage() == null)
		{
			return;
		}
		if (host.getEditorView().isHorizonMode())
		{
			host.getEditorView().setHorizonMode(false, null);
			resetAutoRotateButton(btn);
			return;
		}

		float metaAngle = HorizonDetector.detectFromMetadata(host.getState().getJpegMeta());
		if (!Float.isNaN(metaAngle))
		{
			applyDetectedRotation(metaAngle, "From metadata: " + TextFormat.degrees(metaAngle));
			return;
		}

		btn.setText("Cancel");
		btn.setTextColor(host.getActivity().getResources().getColor(R.color.red, null));
		host.getEditorView().setHorizonMode(true, () -> onHorizonPaintComplete(btn));
	}

	/**
	 * Horizon-paint callback: reset the button, grab the painted points, and dispatch
	 * the detection pipeline on the background executor. Empty / too-short paints
	 * surface a toast and return immediately.
	 */
	private void onHorizonPaintComplete(TextView btn)
	{
		resetAutoRotateButton(btn);
		var points = host.getEditorView().getHorizonPoints();
		float brushRadius = host.getEditorView().getHorizonBrushRadius();
		Bitmap src = host.getState().getSourceImage();

		if (points.size() < 2 || src == null)
		{
			Toast.makeText(host.getActivity(), "Paint was too short",
				Toast.LENGTH_SHORT).show();
			return;
		}

		host.showProgress("Detecting horizon\u2026");
		host.runInBackground(() -> runHorizonDetectionInBackground(src, points, brushRadius));
	}

	/**
	 * Background detection job. Runs on the single-thread executor; posts all UI
	 * mutation (toast, hideProgress, rotation update) back through runOnUiThread.
	 * Wrapped so a throw inside HorizonDetector can't leave the progress overlay stuck.
	 */
	private void runHorizonDetectionInBackground(Bitmap src, List<float[]> points,
		float brushRadius)
	{
		float angle;
		try
		{
			angle = HorizonDetector.detectFromPaintedRegion(src, points, brushRadius);
		}
		catch (Exception | StackOverflowError t)
		{
			// Narrow catch: Exception + StackOverflowError specifically. A degenerate
			// Hough search can blow the stack; that's the one Error subclass worth
			// recovering from here. Catching Throwable (OutOfMemoryError, LinkageError,
			// ThreadDeath) would let the recovery handler itself fail or worsen the
			// situation. HorizonDetector already catches OutOfMemoryError internally.
			Log.w(TAG, "horizon detection failed", t);
			host.hideProgress();
			host.runOnUiThread(() -> host.toastIfAlive(
				"Horizon detection failed", Toast.LENGTH_SHORT));
			return;
		}
		final float detected = angle;
		host.runOnUiThread(() -> onHorizonDetectionResult(detected));
	}

	/**
	 * UI-thread handler for the detection result. Rounds to 0.01° precision, applies,
	 * and toasts the result — or toasts a "no line detected" message when the
	 * detector returns NaN.
	 */
	private void onHorizonDetectionResult(float detected)
	{
		if (host.isDestroyed())
		{
			return;
		}
		host.hideProgress();
		if (Float.isNaN(detected))
		{
			Toast.makeText(host.getActivity(), "No line detected in painted area",
				Toast.LENGTH_SHORT).show();
			return;
		}
		float newRot = Math.round(detected * 100f) / 100f;
		applyDetectedRotation(newRot, TextFormat.degrees(newRot));
	}

	/**
	 * Apply a detected rotation and zoom the ruler so the user can fine-tune within
	 * ~0.01° immediately. Shared between the metadata-fast path and the painted-horizon
	 * background path.
	 */
	private void applyDetectedRotation(float degrees, String toastText)
	{
		host.getState().setRotationDegrees(degrees);
		host.getRotationRuler().zoomToMax();
		Toast.makeText(host.getActivity(), toastText, Toast.LENGTH_SHORT).show();
	}

	/**
	 * Restore the Auto-rotate button to its resting "Auto" label + subtext0 color.
	 */
	private void resetAutoRotateButton(TextView btn)
	{
		btn.setText("Auto");
		btn.setTextColor(host.getActivity().getResources().getColor(R.color.subtext0, null));
	}

	private void setupCenterModeButtons()
	{
		View.OnClickListener lockClick = view ->
		{
			int id = view.getId();
			CenterMode pref;
			if (id == R.id.btnLockBoth)
			{
				pref = CenterMode.BOTH;
			}
			else if (id == R.id.btnLockH)
			{
				pref = CenterMode.HORIZONTAL;
			}
			else
			{
				pref = CenterMode.VERTICAL;
			}

			host.setCurrentPref(pref);
			host.applyLockMode();
			ui.updateLockHighlight();

			if (host.getState().getEditorMode() == EditorMode.SELECT_FEATURE
				&& !host.isCenterLocked() && !host.isPanning())
			{
				host.recomputeForLockChange();
			}
			else if (host.getState().getEditorMode() == EditorMode.MOVE && host.getState().hasCenter()
				&& !host.getState().getSelectionPoints().isEmpty() && !host.isPanning())
			{
				host.recenterOnSelection();
			}
			host.getEditorView().invalidate();
		};
		host.findViewById(R.id.btnLockBoth).setOnClickListener(lockClick);
		host.findViewById(R.id.btnLockH).setOnClickListener(lockClick);
		host.findViewById(R.id.btnLockV).setOnClickListener(lockClick);

		((CheckBox) host.findViewById(R.id.chkPan))
			.setOnCheckedChangeListener((button, isChecked) ->
		{
			host.applyLockMode();
			ui.updateLockHighlight();
			// Recompute only when turning Pan off in Select mode
			if (!isChecked && host.getState().getEditorMode() == EditorMode.SELECT_FEATURE
				&& !host.getState().isCenterLocked())
			{
				host.recomputeForLockChange();
			}
			host.getEditorView().invalidate();
		});

		// Unlocking in Select mode re-derives the center from selection points; Move mode
		// preserves the user's current position.
		((CheckBox) host.findViewById(R.id.chkLockCenter))
			.setOnCheckedChangeListener((button, isChecked) ->
		{
			host.getState().setCenterLocked(isChecked);
			if (!isChecked && host.getState().getEditorMode() == EditorMode.SELECT_FEATURE
				&& !host.getState().getSelectionPoints().isEmpty())
			{
				host.recomputeForLockChange();
			}
			host.getEditorView().invalidate();
		});
	}

	private void setupClearPointsButton()
	{
		host.findViewById(R.id.btnClearPoints).setOnClickListener(view ->
		{
			host.getState().clearSelectionPoints();
			host.getEditorView().clearUndoHistory();
			host.getEditorView().resetCropToFullImage();
			host.getEditorView().invalidate();
			ui.updatePointButtonStates();
		});
	}

	private void setupModeButtons()
	{
		View.OnClickListener click = view ->
		{
			int id = view.getId();
			if (id == R.id.btnModeMove)
			{
				host.getState().setEditorMode(EditorMode.MOVE);
			}
			else if (id == R.id.btnModeSelect)
			{
				host.getState().setEditorMode(EditorMode.SELECT_FEATURE);
			}
			host.applyLockMode();
			ui.updateModeHighlight();
			ui.updateLockHighlight();
			if (host.getState().getEditorMode() == EditorMode.SELECT_FEATURE
				&& !host.isCenterLocked() && !host.isPanning())
			{
				host.recomputeForLockChange();
			}
			host.getEditorView().invalidate();
		};
		host.findViewById(R.id.btnModeMove).setOnClickListener(click);
		host.findViewById(R.id.btnModeSelect).setOnClickListener(click);
	}

	private void setupRotation()
	{
		host.getRotationRuler().setOnRotationChangedListener(deg ->
		{
			if (host.isRulerUpdating())
			{
				return;
			}
			host.getState().setRotationDegrees(deg);
		});

		host.getRotDegreesTextView().setOnClickListener(view -> showPreciseRotationDialog());
		host.getRotationRuler().setRulerEnabled(false); // disabled until an image loads

		// Ruler-zoom buttons. 2× per tap matches the pinch-zoom progression and lets the user
		// step from coarse (10°/major tick) to finest (0.01°/minor tick) in ~7 taps.
		host.findViewById(R.id.btnRotZoomOut).setOnClickListener(view ->
			host.getRotationRuler().zoomBy(0.5f));
		host.findViewById(R.id.btnRotZoomIn).setOnClickListener(view ->
			host.getRotationRuler().zoomBy(2f));
	}

	private void setupUndoRedo()
	{
		host.findViewById(R.id.btnUndo).setOnClickListener(view -> host.getEditorView().undo());
		host.findViewById(R.id.btnRedo).setOnClickListener(view -> host.getEditorView().redo());
	}

	private void showCustomArDialog()
	{
		int dp = (int) host.getActivity().getResources().getDisplayMetrics().density;

		LinearLayout layout = new LinearLayout(host.getActivity());
		layout.setOrientation(LinearLayout.HORIZONTAL);
		layout.setGravity(Gravity.CENTER);
		layout.setPadding(20 * dp, 16 * dp, 20 * dp, 8 * dp);

		EditText editW = numberInput("16");
		layout.addView(editW,
			new LinearLayout.LayoutParams(60 * dp, LinearLayout.LayoutParams.WRAP_CONTENT));

		TextView separator = new TextView(host.getActivity());
		separator.setText("  :  ");
		separator.setTextSize(16);
		layout.addView(separator);

		EditText editH = numberInput("9");
		layout.addView(editH,
			new LinearLayout.LayoutParams(60 * dp, LinearLayout.LayoutParams.WRAP_CONTENT));

		new AlertDialog.Builder(host.getActivity())
			.setTitle("Custom Aspect Ratio")
			.setView(layout)
			.setPositiveButton("Apply", (dialog, which) ->
			{
				int ratioW = Math.max(1, parseIntOr(editW.getText().toString(), 16));
				int ratioH = Math.max(1, parseIntOr(editH.getText().toString(), 9));
				host.getState().setAspectRatio(new AspectRatio(ratioW, ratioH));
				if (!host.getState().getSelectionPoints().isEmpty())
				{
					CropEngine.autoComputeFromPoints(host.getState());
				}
				else
				{
					host.ensureCropCenter();
				}
			})
			.setNegativeButton("Cancel", null)
			.show();
	}

	/**
	 * Dialog for entering an exact rotation value.
	 */
	private void showPreciseRotationDialog()
	{
		if (host.getState().getSourceImage() == null)
		{
			return;
		}
		int dp = (int) host.getActivity().getResources().getDisplayMetrics().density;

		EditText input = new EditText(host.getActivity());
		input.setText(String.format(Locale.ROOT, "%.2f", host.getState().getRotationDegrees()));
		input.setTextSize(18);
		input.setGravity(Gravity.CENTER);
		input.setTextColor(ThemeColors.TEXT);
		input.setBackgroundColor(ThemeColors.SURFACE0);
		input.setInputType(InputType.TYPE_CLASS_NUMBER
			| InputType.TYPE_NUMBER_FLAG_DECIMAL
			| InputType.TYPE_NUMBER_FLAG_SIGNED);
		input.setSingleLine(true);
		input.setPadding(12 * dp, 10 * dp, 12 * dp, 10 * dp);

		new AlertDialog.Builder(host.getActivity())
			.setTitle("Enter Rotation (\u00B0)")
			.setView(input)
			.setPositiveButton("Apply", (dialog, which) ->
			{
				try
				{
					float val = Math.clamp(Float.parseFloat(input.getText().toString().trim()),
						-180f, 180f);
					host.getState().setRotationDegrees(val);
				}
				catch (NumberFormatException ignored)
				{
				}
			})
			.setNegativeButton("Cancel", null)
			.show();
	}

	private EditText numberInput(String initial)
	{
		EditText edit = new EditText(host.getActivity());
		edit.setInputType(InputType.TYPE_CLASS_NUMBER);
		edit.setText(initial);
		edit.setGravity(Gravity.CENTER);
		return edit;
	}

	private static int parseIntOr(String text, int def)
	{
		try
		{
			return Integer.parseInt(text.trim());
		}
		catch (NumberFormatException ignored)
		{
			return def;
		}
	}

	private static TextView styleArLabel(TextView tv, int textSize, int padH, int padV)
	{
		tv.setTextSize(textSize);
		tv.setTextColor(ThemeColors.TEXT);
		tv.setPadding(padH, padV, padH, padV);
		return tv;
	}
}
