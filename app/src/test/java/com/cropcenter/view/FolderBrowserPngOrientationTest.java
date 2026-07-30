package com.cropcenter.view;

import static org.junit.Assert.assertEquals;

import com.cropcenter.metadata.PngFixtures;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Tests for FolderBrowser.readPngOrientationCapped — the sole orientation source for PNG thumbnails in the picker
 * grid. The capped-prefix read is the load-bearing part: it keeps the bg thumbnail thread from slurping a 100 MB PNG
 * into RAM just for an orientation lookup, so the tests pin both sides of the 64 KB cap — an eXIf inside the prefix
 * is read (rotated PNGs render correctly in the grid) and an eXIf past it falls back to upright (a cosmetic-only miss
 * for that rare shape; the main load path reads the full file). Fully headless: PngFixtures builds the chunk stream,
 * TemporaryFolder supplies real files, and ExifInterface.ORIENTATION_NORMAL is a compile-time constant so no Android
 * class loads.
 */
public final class FolderBrowserPngOrientationTest
{
	// Little-endian TIFF with a single IFD0 entry: tag 0x0112 (Orientation), type SHORT, count 1, value 6
	// (rotate 90 CW) — the same fixture shape PngMetadataExtractorTest pins for the chunk parser.
	private static final byte[] TIFF_ORIENTATION_6 = {
		'I', 'I',                               // little-endian
		0x2A, 0x00,                             // TIFF magic 42
		0x08, 0x00, 0x00, 0x00,                 // IFD0 offset = 8
		0x01, 0x00,                             // entry count = 1
		0x12, 0x01,                             // tag 0x0112 (Orientation)
		0x03, 0x00,                             // type SHORT
		0x01, 0x00, 0x00, 0x00,                 // count = 1
		0x06, 0x00, 0x00, 0x00,                 // value = 6 (low 2 bytes)
	};

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void readPngOrientationCappedReadsExifWithinCapAndDefaultsPastCap() throws IOException
	{
		// Within the cap: signature + IHDR + eXIf sits in the first few hundred bytes — the capped read must
		// find orientation 6 so a rotated PNG's thumbnail renders upright in the grid.
		File withinCap = writeFile("within_cap.png", PngFixtures.PNG_SIGNATURE,
			PngFixtures.buildIhdrChunk(), PngFixtures.buildChunk("eXIf", TIFF_ORIENTATION_6));
		assertEquals("eXIf within the 64 KB prefix must be read", 6,
			FolderBrowser.readPngOrientationCapped(withinCap));
		// Past the cap: a 70000-byte filler chunk pushes the same eXIf beyond the 64 KB prefix. The truncated
		// head ends mid-filler, PngMetadataExtractor's bounds check rejects the partial chunk, and the wrapper
		// must fall back to upright (1) rather than read the whole file. This pins the cap arithmetic — a
		// regression that drops the Math.min slurps the full file and finds the 6.
		File pastCap = writeFile("past_cap.png", PngFixtures.PNG_SIGNATURE,
			PngFixtures.buildIhdrChunk(), PngFixtures.buildChunk("tEXt", new byte[70000]),
			PngFixtures.buildChunk("eXIf", TIFF_ORIENTATION_6));
		assertEquals("eXIf past the 64 KB cap must default to upright", 1,
			FolderBrowser.readPngOrientationCapped(pastCap));
	}

	@Test
	public void readPngOrientationCappedReturnsNormalForMalformedBytes() throws IOException
	{
		// Non-PNG content longer than the 8-byte floor: the read proceeds, the extractor rejects the missing
		// signature, and the wrapper reports upright instead of throwing on the bg thumbnail thread.
		byte[] garbage = new byte[64];
		for (int i = 0; i < garbage.length; i++)
		{
			garbage[i] = (byte) (i * 7);
		}
		File malformed = writeFile("malformed.png", garbage);
		assertEquals(1, FolderBrowser.readPngOrientationCapped(malformed));
	}

	@Test
	public void readPngOrientationCappedReturnsNormalForTinyFile() throws IOException
	{
		// At or below 8 bytes there isn't even a full PNG signature — the early return must report upright
		// without opening the stream. A signature-only file (exactly 8 bytes) sits right on the `cap <= 8`
		// boundary; an empty file exercises the 0-length degenerate (also the shape of a file deleted between
		// enumeration and decode, whose length() reads 0).
		File signatureOnly = writeFile("signature_only.png", PngFixtures.PNG_SIGNATURE);
		assertEquals("8-byte file sits on the early-return boundary", 1,
			FolderBrowser.readPngOrientationCapped(signatureOnly));
		File empty = writeFile("empty.png");
		assertEquals("empty file must default to upright", 1, FolderBrowser.readPngOrientationCapped(empty));
	}

	/**
	 * Concatenate the given byte arrays into a fresh file under the temp folder.
	 *
	 * @param name  file name to create under the TemporaryFolder root
	 * @param parts byte sequences written back-to-back in argument order
	 * @return the written File, ready to hand to readPngOrientationCapped
	 * @throws IOException when the temp file cannot be created or written
	 */
	private File writeFile(String name, byte[]... parts) throws IOException
	{
		File file = tmp.newFile(name);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts)
		{
			out.write(part);
		}
		Files.write(file.toPath(), out.toByteArray());
		return file;
	}
}
