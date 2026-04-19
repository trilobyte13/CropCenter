package com.cropcenter.model;

public enum CenterMode
{
	BOTH,       // Select mode: symmetric on both axes
	HORIZONTAL, // Lock X axis symmetric, Y free
	VERTICAL,   // Lock Y axis symmetric, X free
	LOCKED      // Move mode: crop fully locked, drag pans viewport
}
