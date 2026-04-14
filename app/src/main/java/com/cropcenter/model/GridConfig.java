package com.cropcenter.model;

public class GridConfig {
    public boolean enabled = true;
    public boolean showPixelGrid = false;
    public int columns = 4;
    public int rows = 4;
    public int color = 0xFFFFFFFF; // white (matching HTML default)
    public int pixelGridColor = 0x60FFFFFF; // semi-transparent white
    public float lineWidth = 1f;

    public void applyPreset(String preset) {
        switch (preset) {
            case "2x2": columns = 2; rows = 2; break;
            case "3x3": columns = 3; rows = 3; break;
            case "4x4": columns = 4; rows = 4; break;
            case "5x5": columns = 5; rows = 5; break;
            default: break;
        }
    }
}
