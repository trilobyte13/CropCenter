package com.cropcenter.util;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.cropcenter.metadata.JpegSegment;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the horizon tilt angle for auto-rotation correction.
 *
 * Strategy (in priority order):
 *   1. XMP metadata: look for device roll angle (most accurate, ~0.01° precision)
 *   2. Computer vision: Canny edges + Radon variance maximization (fallback)
 */
public final class HorizonDetector {

    private static final String TAG = "HorizonDetector";

    private HorizonDetector() {}

    /**
     * Detect horizon tilt from EXIF/XMP metadata if available.
     *
     * @param meta JPEG metadata segments (from JpegMetadataExtractor)
     * @return correction angle in degrees, or NaN if no roll data found
     */
    public static float detectFromMetadata(List<JpegSegment> meta) {
        if (meta == null) return Float.NaN;

        for (JpegSegment seg : meta) {
            if (!seg.isXmp()) continue;

            // XMP data starts after "http://ns.adobe.com/xap/1.0/\0" (29 bytes) + APP1 header (4 bytes)
            // seg.data = FF E1 LL LL [XMP identifier] [XML...]
            String xmpId = "http://ns.adobe.com/xap/1.0/\0";
            int xmlStart = 4 + xmpId.length();
            if (seg.data.length <= xmlStart) continue;

            String xml = new String(seg.data, xmlStart, seg.data.length - xmlStart);

            // Search for roll angle in common XMP properties.
            // Different cameras use different namespaces:
            //   GCamera:Roll, Device:Roll, samsung:LensRoll, exif:Roll,
            //   or generic Roll/Tilt attributes
            float roll = findXmpFloat(xml, "Roll");
            if (!Float.isNaN(roll)) {
                Log.d(TAG, "Found XMP Roll: " + roll + "°");
                // Roll > 0 typically means CW tilt → need CCW correction
                if (Math.abs(roll) < 0.005f) return 0f;
                if (Math.abs(roll) > 25f) return Float.NaN;
                return -Math.round(roll * 100f) / 100f;
            }

            // Some cameras store pitch/tilt instead of roll
            float tilt = findXmpFloat(xml, "Tilt");
            if (!Float.isNaN(tilt)) {
                Log.d(TAG, "Found XMP Tilt: " + tilt + "°");
                if (Math.abs(tilt) < 0.005f) return 0f;
                if (Math.abs(tilt) > 25f) return Float.NaN;
                return -Math.round(tilt * 100f) / 100f;
            }
        }

        // Also check extended XMP (APP1 segments with different identifier)
        for (JpegSegment seg : meta) {
            if (seg.marker != 0xE1 || seg.data.length < 50) continue;
            // Try to find roll in any APP1 segment that contains XML-like content
            String raw = new String(seg.data, 4, Math.min(seg.data.length - 4, 65000));
            if (!raw.contains("Roll") && !raw.contains("roll") && !raw.contains("Tilt")) continue;

            float roll = findXmpFloat(raw, "Roll");
            if (!Float.isNaN(roll)) {
                Log.d(TAG, "Found Roll in APP1: " + roll + "°");
                if (Math.abs(roll) < 0.005f) return 0f;
                if (Math.abs(roll) > 25f) return Float.NaN;
                return -Math.round(roll * 100f) / 100f;
            }
        }

        return Float.NaN;
    }

    /**
     * Search XMP XML for a float attribute whose name ends with the given suffix.
     * Handles patterns like: namespace:Roll="1.23" or Roll="1.23"
     */
    private static float findXmpFloat(String xml, String attrSuffix) {
        // Match: anyPrefix:Suffix="number" or Suffix="number"
        Pattern p = Pattern.compile("\\w*:?" + attrSuffix + "\\s*=\\s*\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(xml);
        while (m.find()) {
            try {
                return Float.parseFloat(m.group(1).trim());
            } catch (NumberFormatException ignored) {}
        }
        return Float.NaN;
    }

    /**
     * Detect horizon angle using only edges within a user-painted region.
     * The painted points define a brush stroke; only edge pixels near this
     * stroke are used for the Hough line detection.
     *
     * @param src         source bitmap
     * @param paintPoints list of (x,y) image-coordinate points from the paint stroke
     * @param brushRadius radius in image pixels around each paint point
     * @return correction angle in degrees, or NaN if not detected
     */
    public static float detectFromPaintedRegion(Bitmap src, java.util.List<float[]> paintPoints,
                                                 float brushRadius) {
        if (src == null || src.getWidth() < 10 || src.getHeight() < 10
                || paintPoints == null || paintPoints.size() < 2) return Float.NaN;

        try {
            return detectPaintedInternal(src, paintPoints, brushRadius);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "OOM in painted detection");
            return Float.NaN;
        }
    }

    private static float detectPaintedInternal(Bitmap src, java.util.List<float[]> paintPoints,
                                                float brushRadius) {
        int w = src.getWidth(), h = src.getHeight();

        // ── Build mask from paint stroke ──
        // For efficiency, rasterize the stroke into a boolean grid at 1/4 resolution
        int mw = w / 4, mh = h / 4;
        float mScale = 4f;
        boolean[] mask = new boolean[mw * mh];
        float mr = brushRadius / mScale;
        float mr2 = mr * mr;

        for (float[] pt : paintPoints) {
            int cx = (int)(pt[0] / mScale), cy = (int)(pt[1] / mScale);
            int r = (int) Math.ceil(mr);
            for (int dy = -r; dy <= r; dy++) {
                int my = cy + dy;
                if (my < 0 || my >= mh) continue;
                for (int dx = -r; dx <= r; dx++) {
                    int mx = cx + dx;
                    if (mx < 0 || mx >= mw) continue;
                    if (dx * dx + dy * dy <= mr2) mask[my * mw + mx] = true;
                }
            }
        }

        // ── Edge detection ──
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] buf1 = new float[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            buf1[i] = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p);
        }
        pixels = null;

        float[] buf2 = gaussianBlur5x5(buf1, w, h);
        buf1 = null;

        buf1 = new float[w * h];
        float[] gradDir = new float[w * h];
        sobelGradient(buf2, w, h, buf1, gradDir);
        buf2 = null;

        buf2 = nonMaxSuppression(buf1, gradDir, w, h);

        // Direction filter: keep only near-horizontal edges
        for (int i = 0; i < w * h; i++) {
            if (buf2[i] > 0) {
                float absDir = Math.abs(gradDir[i]);
                if (absDir < (float)(Math.PI / 2 - Math.PI * 35 / 180)
                        || absDir > (float)(Math.PI / 2 + Math.PI * 35 / 180)) {
                    buf2[i] = 0;
                }
            }
        }
        gradDir = null;
        buf1 = null;

        // ── Collect edge pixels WITHIN the painted mask ──
        float threshold = computeThreshold(buf2, 0.15f);
        int edgeCount = 0;
        for (int y = 0; y < h; y++) {
            int my = y / 4;
            if (my >= mh) my = mh - 1;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int mx = x / 4;
                if (mx >= mw) mx = mw - 1;
                if (buf2[row + x] >= threshold && mask[my * mw + mx]) edgeCount++;
            }
        }

        if (edgeCount < 30) {
            Log.d(TAG, "Too few masked edge pixels: " + edgeCount);
            return Float.NaN;
        }

        int[] edgeX = new int[edgeCount];
        int[] edgeY = new int[edgeCount];
        int ei = 0;
        for (int y = 0; y < h; y++) {
            int my = y / 4;
            if (my >= mh) my = mh - 1;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int mx = x / 4;
                if (mx >= mw) mx = mw - 1;
                if (buf2[row + x] >= threshold && mask[my * mw + mx]) {
                    edgeX[ei] = x;
                    edgeY[ei] = y;
                    ei++;
                }
            }
        }
        buf2 = null;
        mask = null;
        Log.d(TAG, "Masked edge pixels: " + edgeCount);

        // ── Hough: coarse then fine ──
        float coarseAngle = houghPass(edgeX, edgeY, edgeCount, w, h, 80f, 100f, 0.1f);
        if (Float.isNaN(coarseAngle)) return Float.NaN;

        float fineAngle = houghPass(edgeX, edgeY, edgeCount, w, h,
                Math.max(80f, coarseAngle - 2f),
                Math.min(100f, coarseAngle + 2f), 0.01f);
        if (Float.isNaN(fineAngle)) fineAngle = coarseAngle;

        float tilt = fineAngle - 90f;
        Log.d(TAG, "Painted region tilt: " + String.format("%.3f", tilt) + "°");

        if (Math.abs(tilt) < 0.005f) return 0f;
        if (Math.abs(tilt) > 30f) return Float.NaN;
        return -Math.round(tilt * 100f) / 100f;
    }

    /**
     * Hough transform: find the angle of the single strongest near-horizontal line.
     * Uses max-single-bin (longest line wins) rather than sum-of-squares (all edges).
     */
    private static float houghPass(int[] edgeX, int[] edgeY, int edgeCount,
                                    int w, int h, float minDeg, float maxDeg, float stepDeg) {
        int numAngles = (int) ((maxDeg - minDeg) / stepDeg) + 1;
        float diagonal = (float) Math.hypot(w, h);
        int numBins = (int) (2 * diagonal) + 1;
        int distOff = (int) diagonal;

        double[] cosA = new double[numAngles];
        double[] sinA = new double[numAngles];
        for (int i = 0; i < numAngles; i++) {
            double rad = Math.toRadians(minDeg + i * stepDeg);
            cosA[i] = Math.cos(rad);
            sinA[i] = Math.sin(rad);
        }

        int[] hist = new int[numBins];
        int[] peakPerAngle = new int[numAngles]; // strongest single bin per angle

        for (int ai = 0; ai < numAngles; ai++) {
            java.util.Arrays.fill(hist, 0);
            double ca = cosA[ai], sa = sinA[ai];
            for (int i = 0; i < edgeCount; i++) {
                int bin = (int) (edgeX[i] * ca + edgeY[i] * sa) + distOff;
                if (bin >= 0 && bin < numBins) hist[bin]++;
            }
            int maxBin = 0;
            for (int b = 0; b < numBins; b++) {
                if (hist[b] > maxBin) maxBin = hist[b];
            }
            peakPerAngle[ai] = maxBin;
        }

        // Find the angle whose strongest single line has the most votes
        int bestAi = 0, bestPeak = 0;
        for (int ai = 0; ai < numAngles; ai++) {
            if (peakPerAngle[ai] > bestPeak) {
                bestPeak = peakPerAngle[ai];
                bestAi = ai;
            }
        }

        // Line must span at least 3% of image width
        if (bestPeak < Math.max(15, w * 3 / 100)) return Float.NaN;

        float bestAngle = minDeg + bestAi * stepDeg;

        // Parabolic interpolation for sub-bin accuracy
        if (bestAi > 0 && bestAi < numAngles - 1) {
            float sL = peakPerAngle[bestAi - 1];
            float sC = peakPerAngle[bestAi];
            float sR = peakPerAngle[bestAi + 1];
            float denom = sL - 2 * sC + sR;
            if (denom != 0) {
                float delta = (sL - sR) / (2f * denom);
                delta = Math.max(-0.5f, Math.min(0.5f, delta));
                bestAngle += delta * stepDeg;
            }
        }

        return bestAngle;
    }

    // ── Image processing primitives ──

    private static float[] gaussianBlur5x5(float[] src, int w, int h) {
        float[] k = {
            1/273f, 4/273f,  7/273f,  4/273f, 1/273f,
            4/273f, 16/273f, 26/273f, 16/273f, 4/273f,
            7/273f, 26/273f, 41/273f, 26/273f, 7/273f,
            4/273f, 16/273f, 26/273f, 16/273f, 4/273f,
            1/273f, 4/273f,  7/273f,  4/273f, 1/273f,
        };
        float[] dst = new float[w * h];
        for (int y = 2; y < h - 2; y++) {
            for (int x = 2; x < w - 2; x++) {
                float sum = 0;
                for (int ky = -2; ky <= 2; ky++) {
                    int row = (y + ky) * w;
                    for (int kx = -2; kx <= 2; kx++) {
                        sum += src[row + (x + kx)] * k[(ky + 2) * 5 + (kx + 2)];
                    }
                }
                dst[y * w + x] = sum;
            }
        }
        return dst;
    }

    private static void sobelGradient(float[] src, int w, int h, float[] mag, float[] dir) {
        for (int y = 1; y < h - 1; y++) {
            int prevRow = (y - 1) * w, curRow = y * w, nextRow = (y + 1) * w;
            for (int x = 1; x < w - 1; x++) {
                float tl = src[prevRow + x - 1], tc = src[prevRow + x], tr = src[prevRow + x + 1];
                float ml = src[curRow + x - 1],                         mr = src[curRow + x + 1];
                float bl = src[nextRow + x - 1], bc = src[nextRow + x], br = src[nextRow + x + 1];
                float gx = -tl + tr - 2*ml + 2*mr - bl + br;
                float gy = -tl - 2*tc - tr + bl + 2*bc + br;
                int i = curRow + x;
                mag[i] = (float) Math.hypot(gx, gy);
                dir[i] = (float) Math.atan2(gy, gx);
            }
        }
    }

    private static float[] nonMaxSuppression(float[] mag, float[] dir, int w, int h) {
        float[] out = new float[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float m = mag[i];
                if (m == 0) continue;
                float angle = dir[i];
                if (angle < 0) angle += (float) Math.PI;
                float n1, n2;
                if (angle < Math.PI / 8 || angle >= 7 * Math.PI / 8) {
                    n1 = mag[y * w + x - 1]; n2 = mag[y * w + x + 1];
                } else if (angle < 3 * Math.PI / 8) {
                    n1 = mag[(y-1)*w + x+1]; n2 = mag[(y+1)*w + x-1];
                } else if (angle < 5 * Math.PI / 8) {
                    n1 = mag[(y-1)*w + x]; n2 = mag[(y+1)*w + x];
                } else {
                    n1 = mag[(y-1)*w + x-1]; n2 = mag[(y+1)*w + x+1];
                }
                out[i] = (m >= n1 && m >= n2) ? m : 0;
            }
        }
        return out;
    }

    private static float computeThreshold(float[] edges, float topFraction) {
        float maxVal = 0;
        int nonZero = 0;
        for (float v : edges) {
            if (v > 0) { nonZero++; if (v > maxVal) maxVal = v; }
        }
        if (nonZero == 0 || maxVal == 0) return Float.MAX_VALUE;
        int bins = 256;
        int[] hist = new int[bins];
        for (float v : edges) {
            if (v > 0) hist[Math.min(bins - 1, (int) (v / maxVal * (bins - 1)))]++;
        }
        int target = (int) (nonZero * (1f - topFraction));
        int cumulative = 0;
        for (int i = 0; i < bins; i++) {
            cumulative += hist[i];
            if (cumulative >= target) return (i / (float) (bins - 1)) * maxVal;
        }
        return maxVal * 0.5f;
    }
}
