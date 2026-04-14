package com.cropcenter.metadata;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds a Samsung SEFT trailer for Gallery Revert support.
 *
 * SEFT binary format:
 *   [data block 0][data block 1]...[SEFH directory][4-byte SEFH+dir size LE]["SEFT"]
 *
 * Each data block:
 *   [2-byte reserved=0][2-byte type LE][4-byte name_len LE][name ASCII][payload]
 *
 * SEFH directory:
 *   "SEFH" [4-byte version LE][4-byte entry_count LE]
 *   Then entry_count * 12-byte entries:
 *     [2-byte reserved=0][2-byte type LE][4-byte neg_offset_from_SEFH LE][4-byte block_size LE]
 *
 * Footer:
 *   [4-byte SEFH+entries size LE]["SEFT"]
 */
public final class SeftBuilder {

    private static final String TAG = "SeftBuilder";

    private SeftBuilder() {}

    /**
     * Build a complete SEFT trailer for Samsung Gallery Revert.
     *
     * @param originalBackupPath  absolute path where the original was backed up
     *                            (e.g., "/data/sec/photoeditor/0/{hash}_{mediaId}.jpg")
     * @param cropCenterX  normalized crop center X (0-1)
     * @param cropCenterY  normalized crop center Y (0-1)
     * @param cropWidth    normalized crop width (0-1)
     * @param cropHeight   normalized crop height (0-1)
     * @param isCropped    whether a crop was applied (vs full-frame)
     * @param exifRotation original EXIF rotation value
     * @param utcTimestamp  Image_UTC_Data value (milliseconds)
     * @param existingTrailer  existing SEFT trailer to preserve non-edit entries from, or null
     * @return complete SEFT trailer bytes ready to append after JPEG/gain map
     */
    public static byte[] build(String originalBackupPath, float cropCenterX, float cropCenterY,
                                float cropWidth, float cropHeight, boolean isCropped,
                                int exifRotation, long utcTimestamp, byte[] existingTrailer) {
        try {
            ByteArrayOutputStream blocks = new ByteArrayOutputStream();
            java.util.List<int[]> directory = new java.util.ArrayList<>(); // [type, negOffset, blockSize]

            // If there's an existing trailer, preserve non-edit entries
            if (existingTrailer != null) {
                preserveExistingEntries(existingTrailer, blocks, directory);
            } else {
                // Add basic Samsung metadata entries
                if (utcTimestamp > 0) {
                    addBlock(blocks, directory, 0x0a01, "Image_UTC_Data",
                            String.valueOf(utcTimestamp).getBytes(StandardCharsets.UTF_8));
                }
            }

            // Build re-edit JSON with Samsung's exact formatting (escaped slashes)
            String escapedPath = originalBackupPath.replace("/", "\\/");

            int mCropState = isCropped ? 131079 : 131076;
            // Samsung uses escaped JSON strings within JSON
            String clipInfo = "{\\\"mCenterX\\\":" + cropCenterX
                    + ",\\\"mCenterY\\\":" + cropCenterY
                    + ",\\\"mWidth\\\":" + cropWidth
                    + ",\\\"mHeight\\\":" + cropHeight
                    + ",\\\"mRotation\\\":0,\\\"mRotate\\\":0"
                    + ",\\\"mHFlip\\\":0,\\\"mVFlip\\\":0"
                    + ",\\\"mRotationEffect\\\":0,\\\"mRotateEffect\\\":0"
                    + ",\\\"mHFlipEffect\\\":0,\\\"mVFlipEffect\\\":0"
                    + ",\\\"mHozPerspective\\\":0,\\\"mVerPerspective\\\":0}";

            String toneValue = "{\\\"brightness\\\":100,\\\"exposure\\\":100,\\\"contrast\\\":100"
                    + ",\\\"saturation\\\":100,\\\"hue\\\":100,\\\"wbMode\\\":-1"
                    + ",\\\"wbTemperature\\\":100,\\\"tint\\\":100,\\\"shadow\\\":100"
                    + ",\\\"highlight\\\":100,\\\"lightbalance\\\":100,\\\"sharpness\\\":100"
                    + ",\\\"definition\\\":100,\\\"isBrightnessIPE\\\":false"
                    + ",\\\"isExposureIPE\\\":false,\\\"isContrastIPE\\\":false"
                    + ",\\\"isSaturationIPE\\\":false}";

            String effectValue = "{\\\"filterIndication\\\":4097,\\\"alphaValue\\\":100,\\\"filterType\\\":0}";

            String portraitValue = "{\\\"effectId\\\":-1,\\\"effectLevel\\\":-1"
                    + ",\\\"exifRotation\\\":" + exifRotation
                    + ",\\\"lightLevel\\\":0,\\\"touchX\\\":0,\\\"touchY\\\":0"
                    + ",\\\"refocusX\\\":-1,\\\"refocusY\\\":-1"
                    + ",\\\"effectIdOriginal\\\":-1,\\\"effectLevelOriginal\\\":-1"
                    + ",\\\"lightLevelOriginal\\\":-1,\\\"touchXOriginal\\\":0,\\\"touchYOriginal\\\":0"
                    + ",\\\"refocusXOriginal\\\":-1,\\\"refocusYOriginal\\\":-1"
                    + ",\\\"waterMarkRemoved\\\":false,\\\"waterMarkRemovedOriginal\\\":false}";

            String adjustmentValue = "{\\\"mCropState\\\":" + mCropState + "}";

            String reEditJson = "{\"originalPath\":\"" + escapedPath + "\""
                    + ",\"representativeFrameLoc\":-1"
                    + ",\"startMotionVideo\":-1"
                    + ",\"endMotionVideo\":-1"
                    + ",\"isMotionVideoMute\":false"
                    + ",\"isTrimMotionVideo\":false"
                    + ",\"clipInfoValue\":\"" + clipInfo + "\""
                    + ",\"toneValue\":\"" + toneValue + "\""
                    + ",\"effectValue\":\"" + effectValue + "\""
                    + ",\"portraitEffectValue\":\"" + portraitValue + "\""
                    + ",\"isBlending\":true"
                    + ",\"isNotReEdit\":false"
                    + ",\"sepVersion\":\"170000\""
                    + ",\"ndeVersion\":1"
                    + ",\"reSize\":4"
                    + ",\"isScaleAI\":false"
                    + ",\"rotation\":1"
                    + ",\"adjustmentValue\":\"" + adjustmentValue + "\""
                    + ",\"isApplyShapeCorrection\":false"
                    + ",\"isNewReEditOnly\":false"
                    + ",\"isDecoReEditOnly\":false"
                    + ",\"isAIFilterReEditOnly\":false}";

            addBlock(blocks, directory, 0x0ba1, "PhotoEditor_Re_Edit_Data",
                    reEditJson.getBytes(StandardCharsets.UTF_8));

            // Original_Path_Hash_Key = SHA-256(originalBackupPath) + "/" + mediaId
            String pathHash = sha256(originalBackupPath);
            // Extract mediaId from the path (after last _ before .jpg)
            String mediaId = "0";
            int underscorePos = originalBackupPath.lastIndexOf('_');
            int dotPos = originalBackupPath.lastIndexOf('.');
            if (underscorePos > 0 && dotPos > underscorePos) {
                mediaId = originalBackupPath.substring(underscorePos + 1, dotPos);
            }
            String hashKey = pathHash + "/" + mediaId;

            addBlock(blocks, directory, 0x0ba1, "Original_Path_Hash_Key",
                    hashKey.getBytes(StandardCharsets.UTF_8));

            // Build SEFH directory
            byte[] blockData = blocks.toByteArray();
            return buildTrailer(blockData, directory);

        } catch (Exception e) {
            Log.e(TAG, "SEFT build failed", e);
            return null;
        }
    }

    /**
     * Generate the backup path for the original file.
     * Uses shared storage so Samsung Gallery can read it for Revert.
     * Format: /storage/emulated/0/.cropcenter/{SHA256(sourcePath)}_{mediaId}.jpg
     */
    public static String generateBackupPath(String sourceFilePath, long mediaId) {
        String hash = sha256(sourceFilePath);
        return "/storage/emulated/0/.cropcenter/" + hash + "_" + mediaId + ".jpg";
    }

    private static void addBlock(ByteArrayOutputStream out, java.util.List<int[]> dir,
                                  int type, String name, byte[] payload) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        int blockSize = 8 + nameBytes.length + payload.length; // reserved(2) + type(2) + nameLen(4) + name + payload

        int startPos = out.size();

        // Data block: reserved(2) + type(2 LE) + name_len(4 LE) + name + payload
        out.write(0); out.write(0); // reserved
        out.write(type & 0xFF); out.write((type >> 8) & 0xFF); // type LE
        writeU32LE(out, nameBytes.length);
        out.write(nameBytes);
        out.write(payload);

        dir.add(new int[]{type, startPos, blockSize});
    }

    private static byte[] buildTrailer(byte[] blockData, java.util.List<int[]> dir) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Write all data blocks
        out.write(blockData);

        int sefhPos = out.size();

        // SEFH header: "SEFH" + version(4 LE) + entry_count(4 LE)
        out.write('S'); out.write('E'); out.write('F'); out.write('H');
        writeU32LE(out, 0x6B); // version (107, matches Samsung)
        writeU32LE(out, dir.size());

        // Directory entries (12 bytes each)
        // neg_offset = distance from SEFH to the data block
        for (int[] entry : dir) {
            int type = entry[0];
            int blockPos = entry[1];
            int blockSize = entry[2];
            int negOffset = sefhPos - blockPos;

            out.write(0); out.write(0); // reserved
            out.write(type & 0xFF); out.write((type >> 8) & 0xFF); // type LE
            writeU32LE(out, negOffset);
            writeU32LE(out, blockSize);
        }

        // Footer: SEFH+dir size (4 LE) + "SEFT"
        int sefhSize = out.size() - sefhPos;
        writeU32LE(out, sefhSize);
        out.write('S'); out.write('E'); out.write('F'); out.write('T');

        byte[] result = out.toByteArray();
        Log.d(TAG, "Built SEFT trailer: " + result.length + " bytes, " + dir.size() + " entries");
        return result;
    }

    /** Preserve non-edit entries from an existing SEFT trailer. */
    private static void preserveExistingEntries(byte[] trailer, ByteArrayOutputStream out,
                                                 java.util.List<int[]> dir) throws IOException {
        int len = trailer.length;
        int sefhPos = -1;
        for (int i = len - 12; i >= 0; i--) {
            if (trailer[i] == 'S' && trailer[i+1] == 'E' && trailer[i+2] == 'F' && trailer[i+3] == 'H') {
                sefhPos = i; break;
            }
        }
        if (sefhPos < 0) return;

        int entryCount = readU32LE(trailer, sefhPos + 8);
        for (int i = 0; i < entryCount; i++) {
            int eOff = sefhPos + 12 + i * 12;
            if (eOff + 12 > len) break;
            int blockType = (trailer[eOff+2] & 0xFF) | ((trailer[eOff+3] & 0xFF) << 8);
            int negOffset = readU32LE(trailer, eOff + 4);
            int blockSize = readU32LE(trailer, eOff + 8);
            int dataPos = sefhPos - negOffset;

            // Skip re-edit entries (we'll generate new ones)
            if (blockType == 0x0ba1) continue;

            if (dataPos >= 0 && dataPos + blockSize <= len) {
                int startPos = out.size();
                out.write(trailer, dataPos, blockSize);
                dir.add(new int[]{blockType, startPos, blockSize});
            }
        }
    }

    private static void writeU32LE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static int readU32LE(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off+1] & 0xFF) << 8)
                | ((d[off+2] & 0xFF) << 16) | ((d[off+3] & 0xFF) << 24);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
