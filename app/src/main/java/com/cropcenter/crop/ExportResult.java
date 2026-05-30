package com.cropcenter.crop;

import java.io.File;

/**
 * Bundle returned by CropExporter.export — either an in-memory byte[] payload OR a tempfile reference (for
 * streaming paths that must never materialise the encoded bytes in heap), plus a structurally-derived flag
 * for whether the saved output carries an attached HDR gain map.
 *
 * Dual-mode contract: EXACTLY one of `bytes` and `tempfile` is non-null. byte[] is used by the bypass-encode
 * path (source bytes already in heap) and the JPEG streaming readback (~100-150 MB fits largeHeap). tempfile
 * is used by the PNG streaming path — a 200 MP ARGB compresses to 400-600 MB and Files.readAllBytes would OOM
 * even on largeHeap — so ExportPipeline streams it disk→output without a contiguous byte[].
 *
 * Ownership: when `tempfile` is non-null the caller (ExportPipeline) must delete it after write / verify /
 * callback finishes; the encode pipeline does NOT delete the final tempfile it hands off here.
 *
 * `hdrAttached` comes straight from GainMapComposer.ComposeResult — true only on full success (XMP Item:Length
 * patched, gain map appended, MPF offsets rewritten), false on every drop path and always for PNG (can't carry
 * an Ultra HDR gain map).
 *
 * @param bytes        encoded bytes ready for write; null when tempfile is non-null
 * @param tempfile     encoded bytes staged on disk; null when bytes is non-null. Caller owns deletion.
 * @param hdrAttached  true when the output actually carries the gain map; false for PNG, SDR sources, and
 *                     dropped HDR composition
 */
public record ExportResult(byte[] bytes, File tempfile, boolean hdrAttached)
{
	/**
	 * Compact constructor enforces two invariants:
	 *   1. Exactly one of `bytes` and `tempfile` is non-null (XOR contract).
	 *   2. When `tempfile` is non-null, its current `length()` does NOT exceed Integer.MAX_VALUE — a
	 *      defensive guard for downstream byte[] sites (`ReplaceStrategy.writeReplacementPayload`
	 *      casts size() to int for `verifyReplace`; SAF write APIs all use int byte counts).
	 *
	 * Both encode pipelines (`CropExporter.exportJpeg`, `CropExporter.exportPng`) already validate
	 * `finalSize > Integer.MAX_VALUE` before constructing the record, so the int-cap check here is
	 * load-bearing only for hypothetical future callers; the XOR check is load-bearing today (any
	 * caller that returns both, or neither, would silently break tempfile-vs-bytes dispatch in
	 * `ExportPipeline.writePayloadToStream`).
	 *
	 * @throws IllegalArgumentException when either invariant fails
	 */
	public ExportResult
	{
		if ((bytes == null) == (tempfile == null))
		{
			throw new IllegalArgumentException(
				"ExportResult requires exactly one of bytes/tempfile non-null");
		}
		// Size invariant: callers downstream (ReplaceStrategy.writeReplacementPayload's OOM-fallback
		// branch casts size() to int for verifyReplace; SAF write APIs all use int byte counts) assume
		// the payload fits in a JVM byte[] / int. byte[] mode is inherently bounded by
		// Integer.MAX_VALUE; tempfile mode is NOT bounded by anything intrinsic, so guard here. Both
		// encode pipelines (`exportJpeg`, `exportPng`) already validate their final tempfile size
		// against Integer.MAX_VALUE before constructing the record, so this is defensive — a future
		// caller that skips that check still can't ship an oversize record without an explicit error.
		if (tempfile != null && tempfile.length() > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException(
				"ExportResult tempfile exceeds Integer.MAX_VALUE bytes: " + tempfile.length());
		}
	}

	/**
	 * Two-arg constructor for the bytes-mode case (bypass encode + JPEG streaming pipeline final
	 * readback). Equivalent to `new ExportResult(bytes, null, hdrAttached)`. Delegates to the compact
	 * constructor, so the XOR + int-cap invariants apply.
	 *
	 * @param bytes        non-null encoded bytes
	 * @param hdrAttached  HDR-attached flag
	 * @throws IllegalArgumentException when bytes is null (would violate the XOR invariant against
	 *                                  the null tempfile this constructor passes)
	 */
	public ExportResult(byte[] bytes, boolean hdrAttached)
	{
		this(bytes, null, hdrAttached);
	}

	/**
	 * Total size of the encoded payload in bytes. For bytes-mode this is `bytes.length`; for
	 * tempfile-mode this is `tempfile.length()` — querying the file length is a cheap stat call that
	 * doesn't load the file into heap.
	 *
	 * @return encoded payload size in bytes
	 */
	public long size()
	{
		return bytes != null ? bytes.length : tempfile.length();
	}
}
