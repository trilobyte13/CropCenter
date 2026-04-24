package com.cropcenter.model;

/**
 * Immutable export settings. Callers mutate via CropState.updateExportConfig, which
 * replaces the current instance with a new one produced by a withXxx transformer. The
 * FORMAT_* / *_MIME / *_EXT constants are the single source of truth for strings that
 * SAF pickers, the save pipeline, and MIME-type routing all need to agree on.
 */
public record ExportConfig(String format)
{
	// Internal format tags stored in ExportConfig.format.
	public static final String FORMAT_JPEG = "jpeg";
	public static final String FORMAT_PNG = "png";

	// File extensions (leading dot included) — passed to SAF filename builders.
	public static final String JPEG_EXT = ".jpg";
	public static final String PNG_EXT = ".png";

	// MIME types — passed to ContentResolver / SAF picker intents.
	public static final String JPEG_MIME = "image/jpeg";
	public static final String PNG_MIME = "image/png";

	/**
	 * Default export config applied when a fresh image loads — JPEG. Users override via
	 * the SaveDialog format toggle before each export.
	 */
	public static ExportConfig defaults()
	{
		return new ExportConfig(FORMAT_JPEG);
	}

	/**
	 * Replace the format tag. Accepts FORMAT_JPEG or FORMAT_PNG; other values flow
	 * through but will fall into the default branch of the exporter's switch.
	 */
	public ExportConfig withFormat(String format)
	{
		return new ExportConfig(format);
	}
}
