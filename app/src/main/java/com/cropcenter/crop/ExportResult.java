package com.cropcenter.crop;

/**
 * Bundle returned by CropExporter.export — the encoded file bytes plus a structurally-derived flag indicating
 * whether the saved output actually carries an attached HDR gain map. Replaces ExportPipeline.reportSuccess's
 * earlier full-file substring scan for "hdrgm", which false-positive'd on preserved trailers, stale metadata
 * the HDR-drop strip didn't catch, and Extended-XMP segments that the round-19 XMP-only gate handles
 * structurally but the byte-level scan didn't (Codex round-20 F2).
 *
 * `hdrAttached` is computed inside exportJpeg from `withGainMap != withFullMeta` — i.e. the gain-map
 * composition actually returned a different byte array than the metadata-only inject, meaning the gain map
 * was successfully appended. PNG's exportPng path always returns false (PNG can't carry an Ultra HDR gain
 * map; the format limitation is documented as "no HDR" in §10).
 *
 * @param bytes        encoded JPEG / PNG bytes ready for write
 * @param hdrAttached  true when the output file actually carries the Ultra HDR gain map; false for PNG
 *                     output, for SDR sources, and for HDR sources where composition was dropped on the
 *                     HDR-drop path (gain-map render failed, MPF patch rejected, etc.)
 */
public record ExportResult(byte[] bytes, boolean hdrAttached) {}
