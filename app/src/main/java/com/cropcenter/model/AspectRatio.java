package com.cropcenter.model;

public class AspectRatio {
    public static final AspectRatio FREE = new AspectRatio("Full", 0, 0);
    public static final AspectRatio R16_9 = new AspectRatio("16:9", 16, 9);
    public static final AspectRatio R3_2 = new AspectRatio("3:2", 3, 2);
    public static final AspectRatio R4_3 = new AspectRatio("4:3", 4, 3);
    public static final AspectRatio R5_4 = new AspectRatio("5:4", 5, 4);
    public static final AspectRatio R1_1 = new AspectRatio("1:1", 1, 1);
    public static final AspectRatio R4_5 = new AspectRatio("4:5", 4, 5);
    public static final AspectRatio R3_4 = new AspectRatio("3:4", 3, 4);
    public static final AspectRatio R2_3 = new AspectRatio("2:3", 2, 3);
    public static final AspectRatio R9_16 = new AspectRatio("9:16", 9, 16);

    public static final AspectRatio[] PRESETS = {
        FREE, R16_9, R3_2, R4_3, R5_4, R1_1, R4_5, R3_4, R2_3, R9_16
    };

    public final String label;
    public final float w;
    public final float h;

    public AspectRatio(String label, float w, float h) {
        this.label = label;
        this.w = w;
        this.h = h;
    }

    public boolean isFree() {
        return w == 0 && h == 0;
    }

    public float ratio() {
        if (isFree() || h == 0) return 0;
        return w / h;
    }
}
