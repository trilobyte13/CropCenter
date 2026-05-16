package com.cropcenter.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

/**
 * Tests for ExportConfig.defaults() — the format applied to every freshly-loaded image — and the
 * withFormat transformer. Sister records (GridConfig, Graft, AspectRatio, Format) have dedicated
 * test files; ExportConfig was the one model record without one, leaving the JPEG-default
 * contract uncovered by a direct assertion.
 */
public final class ExportConfigTest
{
	@Test
	public void defaultsAreJpeg()
	{
		// A regression that flipped the default to PNG would silently change every save's default
		// extension. The user-visible failure (PNG file with .jpg name, or vice versa via SAF
		// picker confusion) would be hard to trace back to this one-line change.
		assertEquals(Format.JPEG, ExportConfig.defaults().format());
	}

	@Test
	public void withFormatReturnsNewInstanceWithUpdatedFormat()
	{
		// Pin the immutable-record transformer contract: receiver unchanged, returned instance
		// carries the new value. A regression that mutated the receiver (impossible on a record
		// today, but a future refactor to a mutable class would silently break the
		// CropState.updateExportConfig dispatch contract).
		ExportConfig original = ExportConfig.defaults();
		ExportConfig updated = original.withFormat(Format.PNG);
		assertEquals("receiver unchanged", Format.JPEG, original.format());
		assertEquals("updated carries new format", Format.PNG, updated.format());
		assertNotSame("transformer returns a new instance", original, updated);
	}
}
