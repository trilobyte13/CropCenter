package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for ReplaceStrategy.classifyFilesystemOutcome — the post-Replace disk-state classifier that decides
 * whether the swap succeeded (returns null) or which dialog wording to surface (returns a typed VerifyFailure).
 * Six distinct disk states map to six different user messages; a mis-classification could ship a corrupt file
 * as "saved" or strand orphaned auto-renames the user has to clean up manually.
 *
 * The method is filesystem-authoritative — it queries File.exists / File.length on the placeholder + target
 * paths and routes by what's actually on disk. Pure-Java; we set up temp-file state per test and assert the
 * classification.
 *
 * Side effect under test: the truncation branches DELETE the corrupt target file. The placeholderOnly /
 * twoFiles / missingAfterRename / bothMissing branches don't touch disk. We assert both the classification
 * AND the delete-on-truncation side effect.
 */
public final class ReplaceStrategyClassifierTest
{
	private static final String REQUESTED_NAME = "crop.jpg";
	private static final int EXPECTED_LENGTH = 1024;

	private Path tempDir;

	@Test
	public void bothMissingProducesSaveMayHaveFailed() throws IOException
	{
		// Strategy-B + delete-of-placeholder + filesystem-out-of-sync edge case: the SAF write reported
		// success, the SAF overwrite path may have run, but a subsequent FS query finds neither file. The
		// dialog tells the user to "check your save directory" because we can't say more without disk
		// evidence.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		ReplaceStrategy.VerifyFailure failure = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH);
		assertNotNull("both missing must classify as failure", failure);
		assertEquals("Save may have failed", failure.title());
		assertTrue("message mentions both names",
			failure.message().contains(REQUESTED_NAME) && failure.message().contains("crop (1).jpg"));
	}

	@Test
	public void cleanReplaceWithPlaceholderAndTargetSeparate() throws IOException
	{
		// Strategy B / C success: target file exists at the correct length, placeholder is gone. Classifier
		// returns null (no failure). The "placeholder ≠ target" branch — Strategy C may have renamed
		// placeholder ONTO target's path; that case is exercised separately.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(target.toPath(), EXPECTED_LENGTH);
		// placeholder doesn't exist
		ReplaceStrategy.VerifyFailure failure = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH);
		assertNull("target present at correct length + placeholder gone = clean", failure);
	}

	@After
	public void cleanup() throws IOException
	{
		// Recursive delete so per-test side effects don't pile up. Files first, then the directory.
		if (tempDir != null && Files.exists(tempDir))
		{
			File[] residue = tempDir.toFile().listFiles();
			if (residue != null)
			{
				for (File entry : residue)
				{
					entry.delete();
				}
			}
			Files.deleteIfExists(tempDir);
		}
	}

	@Test
	public void missingAfterRenameWhenPlaceholderEqualsTargetButGone() throws IOException
	{
		// Strategy-C edge case: placeholder == target on path, but the rename then DELETED the file (some
		// providers misbehave). Classifier should report "missing after rename" so the user knows what
		// happened.
		File placeholder = new File(tempDir.toFile(), REQUESTED_NAME);
		// Don't write anything — file doesn't exist
		ReplaceStrategy.VerifyFailure failure = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH);
		assertNotNull(failure);
		assertEquals("Save may have failed", failure.title());
		assertTrue("message mentions requestedName + 'not on disk after Replace'",
			failure.message().contains(REQUESTED_NAME)
			&& failure.message().contains("not on disk after Replace"));
	}

	@Test
	public void nullParentFromRootLevelPlaceholderClassifiesAsFailure() throws IOException
	{
		// Defensive edge: a placeholder constructed at the filesystem root (no parent component) has
		// File.getParentFile() == null. classifyFilesystemOutcome's `target = (parent != null) ?
		// new File(parent, requestedName) : null` branch must NOT NPE on the downstream `target ==
		// null` checks. The result classifies as a "both missing" failure because neither the
		// placeholder nor the (unconstructable) target can be confirmed on disk — which is the
		// honest answer given we have no parent path to probe a sibling against.
		File placeholder = new File("crop (1).jpg");
		assertNull("test fixture: root-level File has no parent", placeholder.getParentFile());
		ReplaceStrategy.VerifyFailure failure = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH);
		assertNotNull("null-parent placeholder must classify as failure, not NPE", failure);
		assertEquals("Save may have failed", failure.title());
	}

	@Test
	public void placeholderOnlyProducesCouldntReplaceMessage() throws IOException
	{
		// SAF overwrite + delete-original both failed. The placeholder write succeeded (the user's bytes are
		// safe at the auto-renamed name) but the original couldn't be cleaned up. Dialog tells the user to
		// grant MANAGE_EXTERNAL_STORAGE or manually delete the original.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		// target doesn't exist
		ReplaceStrategy.VerifyFailure failure = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH);
		assertNotNull(failure);
		assertTrue("title mentions couldn't-replace", failure.title().contains("Couldn't replace"));
		assertTrue("message names the auto-suffixed placeholder",
			failure.message().contains("crop (1).jpg"));
	}

	@Before
	public void setup() throws IOException
	{
		tempDir = Files.createTempDirectory("classifier-test");
	}

	@Test
	public void strategyCRenameCleanWhenPlaceholderEqualsTargetAndLengthMatches() throws IOException
	{
		// Strategy C success: placeholder was renamed onto target's path. On disk: target exists at correct
		// length. placeholder.getName() == target.getName() triggers the same-name short-circuit. Classifier
		// returns null.
		File placeholder = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		ReplaceStrategy.VerifyFailure failure = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH);
		assertNull("Strategy C clean rename must classify as success", failure);
	}

	@Test
	public void truncatedTargetReportsBytesAndDeletes() throws IOException
	{
		// Strategy A/B wrote a target file shorter than the expected length. Classifier reports the actual
		// vs expected byte count AND deletes the corrupt target so the next Save doesn't re-offer Replace
		// on the partial file.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(target.toPath(), 512); // truncated — half the expected 1024
		ReplaceStrategy.VerifyFailure failure = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH);
		assertNotNull(failure);
		assertEquals("Replace produced an incomplete file", failure.title());
		assertTrue("message mentions actual byte count (512)", failure.message().contains("512"));
		assertTrue("message mentions expected byte count (1024)", failure.message().contains("1024"));
		assertFalse("corrupt target must be deleted as a side effect", target.exists());
	}

	@Test
	public void truncatedTargetViaStrategyCRenameAlsoReportsBytesAndDeletes() throws IOException
	{
		// Strategy C variant: placeholder == target on path AND target exists but at wrong length. Same
		// truncation classification + same delete side-effect. Pin the same-name branch's truncation path
		// separately from the different-name branch's truncation path.
		File placeholder = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), 512);
		ReplaceStrategy.VerifyFailure failure = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH);
		assertNotNull(failure);
		assertEquals("Replace produced an incomplete file", failure.title());
		assertFalse("truncated file must be deleted as a side effect", placeholder.exists());
	}

	@Test
	public void twoFilesProducesReplaceLeftTwoFilesMessage() throws IOException
	{
		// Strategy B+C both partial: placeholder kept AND target also exists (perhaps the user's first save
		// landed at a "(1)" suffix and a later run produced the bare name). Classifier surfaces "Replace
		// left two files" so the user can clean up manually.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), 999); // any non-expected length triggers two-files branch
		ReplaceStrategy.VerifyFailure failure = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH);
		assertNotNull(failure);
		assertEquals("Replace left two files", failure.title());
		assertTrue("message names both files",
			failure.message().contains(REQUESTED_NAME) && failure.message().contains("crop (1).jpg"));
	}

	private static void writeBytes(Path path, int length) throws IOException
	{
		byte[] payload = new byte[length];
		Files.write(path, payload, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
			StandardOpenOption.TRUNCATE_EXISTING);
	}
}
