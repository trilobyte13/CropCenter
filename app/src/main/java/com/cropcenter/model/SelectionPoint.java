package com.cropcenter.model;

public class SelectionPoint {
    public float x;
    public float y;
    public boolean active;

    public SelectionPoint(float x, float y) {
        this.x = x;
        this.y = y;
        this.active = true;
    }
}
