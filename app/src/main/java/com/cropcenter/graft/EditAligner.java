package com.cropcenter.graft;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.cropcenter.util.BitmapUtils;

import java.io.ByteArrayOutputStream;

/**
 * Pure-function image alignment for the graft pipeline. Decodes both inputs at the dimension level, compares display
 * dimensions, and re-encodes the edit so its stored pixel layout matches the original's — required for GraftWriter's
 * splice (= edit's primary scan + original's metadata, including the EXIF orientation tag) to decode coherently.
 *
 * Extracted from GraftController so the alignment math is testable without mocking the SaveHost / picker state machine.
 * The class is stateless and reports outcomes via the Result record — the caller (GraftController) owns the toast
 * surface and decides what to do with each error string. No invariants migrate; this is a pure-function lift.
 */
public final class EditAligner
{
	private static final String TAG = "EditAligner";

	private EditAligner() {}

	/**
	 * Outcome of an alignment attempt. Either alignedBytes is non-null (success — caller uses these bytes for the
	 * splice) or errorMessage is non-null (failure — caller surfaces this exact string as a toast and aborts the
	 * graft).
	 *
	 * Designed so the caller only checks one field: success := alignedBytes != null.
	 *
	 * @param alignedBytes  edit bytes ready for splicing, or null on failure
	 * @param errorMessage  user-facing error text, or null on success
	 */
	public record Result(byte[] alignedBytes, String errorMessage)
	{
		/**
		 * Build a failure Result. Caller surfaces errorMessage and aborts.
		 *
		 * @param message user-facing error text
		 * @return a failure Result with no aligned bytes
		 */
		public static Result error(String message)
		{
			return new Result(null, message);
		}

		/**
		 * Build a success Result. Caller's invariant: alignedBytes is non-null and decodes cleanly.
		 *
		 * @param bytes edit bytes whose stored layout matches the original's
		 * @return a success Result wrapping the bytes
		 */
		public static Result ok(byte[] bytes)
		{
			return new Result(bytes, null);
		}
	}

	/**
	 * Align edit bytes to the original's stored layout so GraftWriter's splice produces a decoder-coherent JPEG.
	 *
	 * Pipeline:
	 *   1. Read both stored dimensions cheaply (BitmapFactory inJustDecodeBounds).
	 *   2. Read both EXIF orientation tags.
	 *   3. Compare display dimensions (stored dims after applying each side's
	 *      orientation). Mismatch → error result; the splice can't recover.
	 *   4. If both stored dims and orientation already match, return edit bytes
	 *      verbatim (no re-encode).
	 *   5. Otherwise re-encode the edit (decode → apply edit's orient → apply inverse
	 *      of original's orient → JPEG-compress at quality 100). Costs ~1 channel-noise
	 *      level — same noise floor the save-time canvas pass would add anyway.
	 *
	 * @param originalBytes source JPEG (the metadata donor)
	 * @param editBytes     externally-edited JPEG (the pixel donor)
	 * @return Result.ok with the aligned bytes when the splice can proceed, or
	 *         Result.error when dimensions don't match / either decode failed
	 */
	public static Result align(byte[] originalBytes, byte[] editBytes)
	{
		// Explicit SOI check up front. BitmapFactory accepts HEIC / WebP / PNG (depending on Android version),
		// so a picker selection that bypasses the image/jpeg MIME filter would otherwise sail through the
		// dimension probe and re-orient pass and only fail at GraftWriter with a generic "Edit is not a JPEG"
		// IOException. Catching it here surfaces an actionable "Selected file is not a JPEG" message — telling
		// the user what kind of file they need rather than the generic "Couldn't read JPEG dimensions" which
		// they could mis-read as "the file is corrupt, retry".
		if (editBytes == null || editBytes.length < 4
			|| (editBytes[0] & 0xFF) != 0xFF || (editBytes[1] & 0xFF) != 0xD8)
		{
			return Result.error("Selected file is not a JPEG");
		}
		int[] origStored = decodeStoredDims(originalBytes);
		if (origStored == null)
		{
			// Source bytes are something we previously loaded successfully — if dim probe fails now, the
			// source was corrupted between load and graft (memory pressure, bg-write race). The user-facing
			// "Source image" matches the spec's terminology for "the photo already loaded" vs "edit" for
			// the just-picked file; saying "original" would be ambiguous (which one?).
			return Result.error("Source image is corrupt — reload it");
		}
		int[] editStored = decodeStoredDims(editBytes);
		if (editStored == null)
		{
			// Edit has a valid SOI (passed the check above) but BitmapFactory rejected it. The edit JPEG is
			// structurally malformed past the marker — corrupt export from the editor.
			return Result.error("Couldn't decode the edit — try exporting again");
		}
		int origOrient = BitmapUtils.readExifOrientation(originalBytes);
		int editOrient = BitmapUtils.readExifOrientation(editBytes);

		int[] origDisplay = displayDims(origStored, origOrient);
		int[] editDisplay = displayDims(editStored, editOrient);
		if (origDisplay[0] != editDisplay[0] || origDisplay[1] != editDisplay[1])
		{
			return Result.error("Edit dimensions don't match the source ("
				+ "source " + origDisplay[0] + "x" + origDisplay[1]
				+ ", edit " + editDisplay[0] + "x" + editDisplay[1]
				+ ") — re-crop in the editor and re-export");
		}

		boolean perfectMatch = origOrient == editOrient && origStored[0] == editStored[0]
			&& origStored[1] == editStored[1];
		if (perfectMatch)
		{
			return Result.ok(editBytes);
		}
		byte[] reoriented = reorientEdit(editBytes, editOrient, origOrient);
		if (reoriented == null)
		{
			// reorientEdit returns null only when BitmapFactory.decodeByteArray rejects the bytes — same
			// failure mode as the edit-decode-null branch above, so route the user to the same remediation.
			return Result.error("Couldn't decode the edit during reorientation — try exporting again");
		}
		Log.d(TAG, "Reoriented edit (origOrient=" + origOrient + " editOrient=" + editOrient
			+ ") from " + editStored[0] + "x" + editStored[1]
			+ " to original's stored layout (" + origStored[0] + "x" + origStored[1] + ")");
		return Result.ok(reoriented);
	}

	/**
	 * Apply EXIF orientation to stored dims to get display dims. EXIF tags 5/6/7/8 swap the axes (90° rotations +
	 * transpose / transverse); 1/2/3/4 leave them alone. Returns a fresh int[2] so callers can mutate without
	 * aliasing.
	 *
	 * @param stored [width, height] in stored coordinates
	 * @param orient EXIF orientation tag (1..8); values outside that range pass through as identity
	 * @return [width, height] in display coordinates
	 */
	static int[] displayDims(int[] stored, int orient)
	{
		boolean swap = orient == 5 || orient == 6 || orient == 7 || orient == 8;
		return swap ? new int[] { stored[1], stored[0] } : new int[] { stored[0], stored[1] };
	}

	/**
	 * Inverse of an EXIF orientation transform — applying orient then inverseOrientation gives the identity. Most
	 * orientations (1, 2, 3, 4, 5, 7) are involutions and map to themselves; only the 90° rotations (6 ↔ 8) form an
	 * inverse pair.
	 *
	 * @param orient EXIF orientation tag (1..8)
	 * @return the orientation tag whose composition with `orient` is identity
	 */
	static int inverseOrientation(int orient)
	{
		if (orient == 6)
		{
			return 8;
		}
		if (orient == 8)
		{
			return 6;
		}
		return orient;
	}

	/**
	 * Decode-cheap dimension probe: returns the JPEG's stored width and height without allocating pixel data.
	 * Returns null when BitmapFactory rejects the byte array.
	 *
	 * @param bytes raw JPEG bytes
	 * @return [width, height] in stored coordinates, or null on decode failure
	 */
	private static int[] decodeStoredDims(byte[] bytes)
	{
		BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
		if (opts.outWidth <= 0 || opts.outHeight <= 0)
		{
			return null;
		}
		return new int[] { opts.outWidth, opts.outHeight };
	}

	/**
	 * Re-encode the edit so its stored pixel layout matches the original's. Pipeline:
	 * decode raw (BitmapFactory does not apply orientation) → apply edit's orientation
	 * to land in display orientation → apply the inverse of original's orientation to
	 * land in original's stored orientation → JPEG-compress at quality 100. The output
	 * has no EXIF (Bitmap.compress doesn't write APP1/EXIF segments); GraftWriter only
	 * uses the primary scan from this file, so the missing EXIF is fine.
	 *
	 * @param editBytes  raw JPEG bytes of the externally-edited file
	 * @param editOrient edit's EXIF orientation tag
	 * @param origOrient original's EXIF orientation tag (the target stored orientation)
	 * @return re-encoded JPEG bytes, or null when the decode fails (corrupt edit)
	 */
	private static byte[] reorientEdit(byte[] editBytes, int editOrient, int origOrient)
	{
		// Full-resolution decode — NOT subsampled. GraftWriter.graft splices the edit's primary scan into
		// the original's full-resolution EXIF / MPF / gainmap / SEFT package — if reorientEdit
		// downsampled the primary while the original's metadata still described the full-resolution
		// gainmap, the assembled file would have SOF dimensions disagreeing with EXIF, and (for HDR
		// sources) the MPF entries pointing at a gainmap whose pixel coordinates no longer match the
		// spliced primary. The result is silent HDR misalignment with no failure toast.
		//
		// The trade-off: on a 200 MP source + edit pair, this decode CAN OOM. The OutOfMemoryError catch
		// below converts it into a clean null return; align() then surfaces the same
		// "Couldn't decode the edit during reorientation — try exporting again" toast that the
		// decode-null path produces, which is the right user-facing message — graft is the one workflow
		// where downsampling would corrupt the output rather than just trade quality.
		Bitmap raw;
		try
		{
			raw = BitmapFactory.decodeByteArray(editBytes, 0, editBytes.length);
		}
		catch (RuntimeException | OutOfMemoryError e)
		{
			// Widened to RuntimeException as well as OutOfMemoryError so a truly malformed JPEG (Skia
			// can throw IllegalArgumentException / RuntimeException from decodeByteArray on bad
			// Huffman tables, truncated DQT, etc.) surfaces the same "Couldn't decode the edit during
			// reorientation — try exporting again" toast as the OOM and decode-null paths. Without
			// this widening, the throw escapes to GraftController.assembleGraftOnBg's catch and
			// reports the generic "Graft failed: …" instead of the more accurate decode-failure
			// message.
			Log.w(TAG, "reorientEdit failed on full-resolution decode of "
				+ editBytes.length + "-byte edit; graft will surface "
				+ "'Couldn't decode the edit during reorientation' toast", e);
			return null;
		}
		if (raw == null)
		{
			return null;
		}
		// Bitmap.compress(JPEG, 100, ...) emits a baseline JPEG with NO APP markers — every edit-side EXIF /
		// XMP / ICC segment that was present in editBytes is silently discarded. GraftWriter is designed to
		// keep original's metadata wholesale (the graft's identity is the original photo, not the edit), so
		// this is correct by design — but for users who made an edit that wrote new metadata (tone-curve →
		// fresh ICC profile, descriptive XMP keywords from a Lightroom round-trip), the loss is invisible.
		// Log so the loss is at least diagnosable in bug reports rather than silent.
		Log.d(TAG, "reorientEdit re-encoding edit JPEG to match original's stored layout"
			+ " (editOrient=" + editOrient + ", origOrient=" + origOrient + ");"
			+ " any edit-side EXIF / XMP / ICC segments are not preserved");
		Bitmap inDisplay = null;
		Bitmap inOrigStored = null;
		try
		{
			inDisplay = BitmapUtils.applyOrientation(raw, editOrient);
			// applyOrientation either recycled raw and returned a new rotated bitmap, OR (when editOrient
			// is 1 / out of range) returned raw unchanged — in which case inDisplay aliases raw. Either
			// way, the only live reference to those pixels is now inDisplay; null out the local so the
			// finally block doesn't try to recycle twice on the alias case.
			raw = null;
			inOrigStored = BitmapUtils.applyOrientation(inDisplay, inverseOrientation(origOrient));
			// Same alias logic for inDisplay → inOrigStored.
			inDisplay = null;
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			// Bitmap.compress returns false on Skia encoder rejection (extreme dims, corrupt native state,
			// unsupported config). The previous code ignored the return and shipped whatever partial bytes
			// landed in the stream — downstream GraftWriter then bailed with a generic "Edit is not a JPEG"
			// because the result had no SOI marker, leaving the user with a confusing "graft failed" error
			// when the actual cause was the re-encode. Detect here so the caller (align) can surface a
			// specific "couldn't re-encode the edit during reorientation" remediation matching the existing
			// decode-failure phrasing.
			if (!inOrigStored.compress(Bitmap.CompressFormat.JPEG, 100, bos))
			{
				Log.w(TAG, "reorientEdit Bitmap.compress(JPEG, 100) returned false");
				return null;
			}
			return bos.toByteArray();
		}
		finally
		{
			if (raw != null && !raw.isRecycled())
			{
				raw.recycle();
			}
			if (inDisplay != null && !inDisplay.isRecycled())
			{
				inDisplay.recycle();
			}
			if (inOrigStored != null && !inOrigStored.isRecycled())
			{
				inOrigStored.recycle();
			}
		}
	}
}
