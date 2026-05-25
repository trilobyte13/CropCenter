package com.cropcenter.model;

import java.util.Locale;

/**
 * Output image format supported by the export pipeline. Replaces the earlier String-typed format tag — the enum gives
 * compile-time exhaustiveness on switch dispatches in CropExporter and removes the "any string flows through to the
 * default branch" foot-gun called out in the audit.
 *
 * Each constant carries its MIME type (used by SAF picker intents and ContentResolver) and file extension (used by
 * SaveController's filename builders). The fromExtension lookup is the inverse — used by SaveController to detect when
 * a user's extension change in the SAF picker would otherwise produce a MIME/content mismatch.
 */
public enum Format
{
	JPEG(".jpg", "image/jpeg"), PNG(".png", "image/png");

	private final String extension;
	private final String mimeType;

	Format(String extension, String mimeType)
	{
		this.extension = extension;
		this.mimeType = mimeType;
	}

	/**
	 * Map a filename's extension to a Format constant. Accepts the canonical extension (.jpg / .png) plus the
	 * common .jpeg alias. Unknown extensions return null so callers can keep their existing
	 * "leave-format-unchanged" semantics.
	 *
	 * @param name filename to inspect (may be null)
	 * @return the matching Format, or null when the name has no recognised image extension
	 */
	public static Format fromExtension(String name)
	{
		if (name == null)
		{
			return null;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".png"))
		{
			return PNG;
		}
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
		{
			return JPEG;
		}
		return null;
	}

	/**
	 * Strip the trailing extension from `name`, returning everything before the last `.`. Names with no
	 * dot OR with a leading-dot stem (".gitignore") are returned verbatim — the canonical "stem" of a
	 * leading-dot name has no separable extension. Chokepoint for the in-app save / graft / rename
	 * stem-extraction logic; before this lived inline at 3+ sites with subtle "dot > 0" / "dot >= 0"
	 * variations.
	 *
	 * @param name filename to strip; must be non-null (caller responsibility — null filenames upstream
	 *             are a programming error, not a runtime input shape)
	 * @return the substring before the last `.`, or `name` unchanged when no extension separator is
	 *         present (no dot, or only a leading dot)
	 */
	public static String stripExtension(String name)
	{
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	public String extension()
	{
		return extension;
	}

	public String mimeType()
	{
		return mimeType;
	}
}
