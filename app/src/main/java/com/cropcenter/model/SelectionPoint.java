package com.cropcenter.model;

/**
 * A point in image coordinates, used for Select-Feature polygon building. All fields are set
 * at construction and never mutated — qualifies for record conversion per CLAUDE.md.
 */
public record SelectionPoint(float x, float y)
{
}
