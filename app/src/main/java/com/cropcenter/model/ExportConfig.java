package com.cropcenter.model;

/**
 * Immutable export settings. Callers mutate via CropState.updateExportConfig, which replaces
 * the current instance with a new one produced by a withXxx transformer.
 */
public record ExportConfig(String format)
{
	public static final String FORMAT_JPEG = "jpeg";
	public static final String FORMAT_PNG = "png";
	public static final String JPEG_EXT = ".jpg";
	public static final String JPEG_MIME = "image/jpeg";
	public static final String PNG_EXT = ".png";
	public static final String PNG_MIME = "image/png";

	public static ExportConfig defaults()
	{
		return new ExportConfig(FORMAT_JPEG);
	}

	public ExportConfig withFormat(String format)
	{
		return new ExportConfig(format);
	}
}
