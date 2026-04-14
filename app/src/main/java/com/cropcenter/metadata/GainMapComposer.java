package com.cropcenter.metadata;

import android.util.Log;

/**
 * Appends the HDR gain map JPEG after the primary image and updates MPF offsets.
 */
public final class GainMapComposer {

    private static final String TAG = "GainMapComposer";

    private GainMapComposer() {}

    /**
     * Append the gain map to the primary JPEG and fix MPF offsets.
     * @param primary  the primary JPEG bytes (with metadata already injected)
     * @param gainMap  the gain map JPEG bytes (preserved from original file)
     * @return combined JPEG bytes with corrected MPF offsets
     */
    public static byte[] compose(byte[] primary, byte[] gainMap) {
        int primarySize = primary.length;

        // Concatenate primary + gain map
        byte[] combined = new byte[primarySize + gainMap.length];
        System.arraycopy(primary, 0, combined, 0, primarySize);
        System.arraycopy(gainMap, 0, combined, primarySize, gainMap.length);

        // Patch MPF offsets in-place
        boolean patched = MpfPatcher.patch(combined, primarySize);
        if (patched) {
            Log.d(TAG, "Composed: primary=" + primarySize + " + gainMap=" + gainMap.length
                    + " = " + combined.length + " (MPF patched)");
        } else {
            Log.w(TAG, "Composed without MPF patching (no MPF segment found)");
        }

        return combined;
    }
}
