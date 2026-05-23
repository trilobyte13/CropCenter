package com.cropcenter.crop;

import java.io.File;

/**
 * Bundle returned by CropExporter.export — either an in-memory byte[] payload (small / bypass-encode
 * paths) OR a tempfile reference (streaming paths that must never materialise the encoded bytes in
 * Java heap), plus a structurally-derived flag indicating whether the saved output actually carries
 * an attached HDR gain map.
 *
 * Dual-mode contract: EXACTLY one of `bytes` and `tempfile` is non-null. The byte[] form is used by
 * the bypass-encode path (where `state.getOriginalFileBytes()` already lives in heap as a byte[])
 * and by the JPEG streaming path's final readback (~100-150 MB fits within largeHeap). The tempfile
 * form is used by the PNG streaming path — a 200 MP ARGB bitmap compresses lossless to 400-600 MB,
 * and `Files.readAllBytes` on that file would OOM even with largeHeap (heap is typically at ~390 MB
 * used by then; the readback's contiguous allocation can't fit). Tempfile mode lets `ExportPipeline`
 * stream the encoded bytes straight from disk to the SAF / file output without ever materialising
 * them as a single contiguous byte[].
 *
 * Ownership: when `tempfile` is non-null, the caller of `CropExporter.export` (i.e. `ExportPipeline`)
 * is responsible for deleting it once write / verify / callback consumption finishes. The encode
 * pipeline does NOT delete its final tempfile when handing it off via this record.
 *
 * `hdrAttached` is sourced directly from GainMapComposer.ComposeResult.hdrAttached() — the explicit
 * boolean the composer returns alongside the output bytes. True only on the full-success path (XMP
 * Item:Length patched, gain map appended, MPF offsets rewritten to point at it) and false on every
 * drop path (no gain map, XmpItemLengthPatcher refused, MpfPatcher failed). PNG's exportPng path
 * always passes false (PNG can't carry an Ultra HDR gain map; the format limitation is documented as
 * "no HDR" in §10).
 *
 * @param bytes        encoded JPEG / PNG bytes ready for write; null when tempfile is non-null
 * @param tempfile     encoded JPEG / PNG bytes staged on disk; null when bytes is non-null. Caller
 *                     owns deletion.
 * @param hdrAttached  true when the output file actually carries the Ultra HDR gain map; false for
 *                     PNG output, for SDR sources, and for HDR sources where composition was dropped
 *                     on the HDR-drop path (gain-map render failed, Item:Length unpatchable, MPF
 *                     patch rejected, etc.)
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
