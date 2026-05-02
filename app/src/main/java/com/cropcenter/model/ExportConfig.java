package com.cropcenter.model;

/**
 * Immutable export settings. Callers mutate via CropState.updateExportConfig, which replaces the current instance with
 * a new one produced by a withXxx transformer.
 */
public record ExportConfig(Format format)
{
	/**
	 * Replace the format. Returns a new ExportConfig with the supplied format; the receiver is unchanged.
	 */
	public ExportConfig withFormat(Format format)
	{
		return new ExportConfig(format);
	}

	/**
	 * Default export config applied when a fresh image loads — JPEG. Users override via the SaveDialog format
	 * toggle before each export.
	 */
	public static ExportConfig defaults()
	{
		return new ExportConfig(Format.JPEG);
	}
}
