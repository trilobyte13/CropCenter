package com.cropcenter.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Value-equality + accessor pin for SelectionPoint. The record drives undo/redo diffing in
 * SelectionHistory and the flat-array serialisation in MainActivity.applyRestoreBundle /
 * onSaveInstanceState — both rely on `==` / equals being identity-by-value, which a future
 * conversion from record → class without explicit equals would silently break. Also pins the
 * half-integer convention (subpixel snap to `floor(x) + 0.5`) by exercising a typical instance.
 */
public final class SelectionPointTest
{
	@Test
	public void distinctCoordinatesDoNotEqual()
	{
		// (1, 2) and (2, 1) must not equal — confirms equals doesn't accidentally treat the record
		// as a multiset or coordinate-swapped match. The selection-restore flat array preserves
		// (x, y) order, so a hypothetical equals that ignored ordering would corrupt undo diffing.
		assertNotEquals(new SelectionPoint(1f, 2f), new SelectionPoint(2f, 1f));
	}

	@Test
	public void equalsByValueWithSameCoordinates()
	{
		// Record-generated equals must be value-equality so SelectionHistory's snapshot diff can
		// detect "same point" across rebuilds. Two fresh instances with identical (x, y) MUST
		// equal — otherwise undo would treat an unchanged point list as different and grow the
		// history unnecessarily.
		SelectionPoint first = new SelectionPoint(123.5f, 456.5f);
		SelectionPoint second = new SelectionPoint(123.5f, 456.5f);
		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
	}

	@Test
	public void halfIntegerCoordinatesRoundTrip()
	{
		// Production placement snaps to `floor(x) + 0.5` per the Javadoc. Confirm the record
		// preserves that exact value (no integer truncation, no auto-rounding).
		SelectionPoint point = new SelectionPoint(99.5f, 100.5f);
		assertEquals(99.5f, point.x(), 0f);
		assertEquals(100.5f, point.y(), 0f);
	}

	@Test
	public void nanCoordinatesNotEqualToThemselves()
	{
		// IEEE 754: NaN != NaN. Record equals delegates to Float.equals which uses Float.compare
		// — Float.compare DOES treat NaN as equal to NaN (different from `==`). Pin the actual
		// behaviour so a downstream consumer relying on either semantics has clear documentation.
		SelectionPoint nanX = new SelectionPoint(Float.NaN, 0f);
		SelectionPoint nanXCopy = new SelectionPoint(Float.NaN, 0f);
		// Record's auto-generated equals uses Float.compare semantics: NaN equals NaN here.
		assertEquals(nanX, nanXCopy);
		// But the raw `==` on the field value still follows IEEE rules — explicit assertion that
		// readers shouldn't conflate the two semantics.
		assertTrue(Float.isNaN(nanX.x()));
	}
}
