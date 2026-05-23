package com.cropcenter.model;

/**
 * Immutable export settings. Callers mutate via CropState.updateExportConfig, which replaces the current instance with
 * a new one produced by a withXxx transformer.
 */
public record ExportConfig(Format format)
{
	/**
	 * Default export config applied when a fresh image loads — JPEG. Users override via the save dialog
	 * format toggle before each export (FolderPickerDialog when MANAGE_EXTERNAL_STORAGE is granted,
	 * SaveDialog on the legacy SAF path).
	 *
	 * @return a new ExportConfig with the default JPEG format
	 */
	public static ExportConfig defaults()
	{
		return new ExportConfig(Format.JPEG);
	}

	public ExportConfig withFormat(Format format)
	{
		return new ExportConfig(format);
	}
}
