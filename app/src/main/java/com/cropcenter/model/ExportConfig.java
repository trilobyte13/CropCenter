package com.cropcenter.model;

public class ExportConfig {
    public String format = "jpeg"; // "jpeg" or "png"
    public int jpegQuality = 100;  // always max quality
    public String filename = "crop";
    public boolean includeGrid = false;
}
