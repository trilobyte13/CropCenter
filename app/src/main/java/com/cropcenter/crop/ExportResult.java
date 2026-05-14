package com.cropcenter.crop;

/**
 * Bundle returned by CropExporter.export — the encoded file bytes plus a structurally-derived flag indicating
 * whether the saved output actually carries an attached HDR gain map. Replaces ExportPipeline.reportSuccess's
 * earlier full-file substring scan for "hdrgm", which false-positive'd on preserved trailers, stale metadata
 * the HDR-drop strip didn't catch, and Extended-XMP segments that the XMP-only structural gate handles
 * but a byte-level scan didn't.
 *
 * `hdrAttached` is sourced directly from GainMapComposer.ComposeResult.hdrAttached() — the explicit boolean
 * the composer returns alongside the output bytes. The flag is true only on the full-success path (XMP
 * Item:Length patched, gain map appended, MPF offsets rewritten to point at it) and false on every drop
 * path (no gain map, XmpItemLengthPatcher refused, MpfPatcher failed). Earlier revisions inferred this
 * from `withGainMap != withFullMeta` reference inequality, but that broke once the composer started
 * returning the XMP-patched primary on the MPF-fail path: the freshly-allocated array distinct from the
 * input made the inequality fire `hdrAttached = true` on a JPEG that shipped no gain map. The explicit
 * ComposeResult flag is the canonical signal — never re-derive from byte-array identity. PNG's exportPng
 * path always passes false (PNG can't carry an Ultra HDR gain map; the format limitation is documented
 * as "no HDR" in §10).
 *
 * @param bytes        encoded JPEG / PNG bytes ready for write
 * @param hdrAttached  true when the output file actually carries the Ultra HDR gain map; false for PNG
 *                     output, for SDR sources, and for HDR sources where composition was dropped on the
 *                     HDR-drop path (gain-map render failed, Item:Length unpatchable, MPF patch rejected,
 *                     etc.)
 */
public record ExportResult(byte[] bytes, boolean hdrAttached) {}
