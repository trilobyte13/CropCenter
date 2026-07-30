package com.cropcenter.model;

/**
 * A selection point in un-rotated image coordinates. Used in Select-Feature mode to drive auto-centering of the crop on
 * the selected feature (single point) or to build a polygon around a feature (three or more points). Stored coordinates
 * are half-integer (pixel center) because onTap snaps the placement to `floor(x) + 0.5`.
 *
 * Despite having only two fields, this is deliberately a record rather than a plain (x, y) float pair — sites pass
 * SelectionPoint around and rely on value equality (the record-generated equals/hashCode) for "same point" checks when
 * diffing selection state across undo/redo snapshots. Reference == would fail across snapshot copies; no call site uses
 * it.
 */
public record SelectionPoint(float x, float y)
{
}
