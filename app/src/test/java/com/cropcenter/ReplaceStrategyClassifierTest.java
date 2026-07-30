package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Tests for ReplaceStrategy.classifyFilesystemOutcome — the post-Replace disk-state classifier that decides whether the
 * swap succeeded (returns empty) or which dialog wording to surface (returns a typed VerifyFailure). A
 * mis-classification could ship a corrupt file as "saved" or strand orphaned auto-renames the user has to clean up
 * manually.
 *
 * The method is filesystem-authoritative — it queries File.exists / File.length on the placeholder + target paths and
 * routes by what's actually on disk. Pure-Java; we set up temp-file state per test and assert the classification.
 *
 * Side effects under test alongside the classification:
 *   - a sole surviving wrong-length target is KEPT on disk (its length mismatch can't prove it is our failed
 *     write — it may be the pre-Replace original) and the incomplete-file wording names it as preserved
 *   - a length-verified sole-survivor placeholder is RENAMED onto the free target name (clean)
 *   - a redundant placeholder next to a verified target is DELETED (clean)
 *   - a kept hidden crash-safe placeholder (SaveTempFiles naming) is PROMOTED to a visible "(N)" name
 *     before the failure dialog interpolates it — no dialog may name a dotfile Gallery never shows
 *   - the "(N)" promotion move never replaces a file that filled in at the probed candidate name — the
 *     no-REPLACE_EXISTING move refuses and the promotion retries onto the next free candidate
 *   - a knownBadTarget=true classification NEVER returns clean off a length-only target check, never deletes
 *     the placeholder (the only good copy), and keeps the proven-wrong target when no backup survived
 *   - a kept recovery still under its hidden temp name carries that name as VerifyFailure.keptHiddenName (the
 *     journal signal that protects it from the startup sweep); a promoted (visible) kept name carries null
 *   - a NEW-FILE save (wasOverwrite=false) never replaces a file that appeared at the target name after
 *     classification: promoteTempOntoTarget (strategy A's swap) and promoteSoleSurvivor (the classifier's
 *     completion rename) probe target existence immediately before their move / rename and refuse on a hit, and
 *     promoteTempOntoTarget backs the probe with a second gate — its NEW-FILE move drops REPLACE_EXISTING so a
 *     probe-missed target surfaces as FileAlreadyExistsException and gets the same refusal; existing overwrite
 *     scenarios pass wasOverwrite=true and keep replace semantics, INCLUDING a dialog-confirmed Replace on a
 *     0-byte colliding file that the overwrite probe's explicit-0 rule would downgrade (the composed
 *     confirmed-OR-probe pin)
 *
 * Also pins three companion static contracts on ReplaceStrategy: journalThenDeleteColliding (strategy C's destructive
 * fallback journals a temp-shaped placeholder BEFORE deleting the colliding original, and the delete runs ONLY under
 * a durable journal entry — a failed commit skips the delete so the delete-to-retry-rename crash window never holds
 * an unjournaled hidden only-copy), degradeKeptPromiseWhenUnjournaled (a "was kept" dialog whose journal write failed
 * must warn about the sweep's one-hour cleanup window instead of implying protection), and classifyRenameFallback
 * (strategy C's fail-closed ladder — the destructive delete-then-retry runs only on positive evidence the placeholder
 * still exists under its own name; with both probes unavailable the colliding URI may BE a silently-renamed export,
 * so the fallback must never delete it).
 */
public final class ReplaceStrategyClassifierTest
{
	/**
	 * File whose renameTo records its invocations. Pins that promoteSoleSurvivor's NEW-FILE probe refuses the
	 * completion rename WITHOUT attempting it: File.renameTo replaces an existing target on POSIX filesystems
	 * but fails on one under Windows, so a return-value-only pin would stay green under a dropped-probe
	 * regression on a Windows JVM — the invocation count is the platform-independent signal.
	 */
	private static final class RenameRecordingFile extends File
	{
		int renameAttempts;

		RenameRecordingFile(File parent, String name)
		{
			super(parent, name);
		}

		@Override
		public boolean renameTo(File dest)
		{
			renameAttempts++;
			return super.renameTo(dest);
		}
	}

	private static final String REQUESTED_NAME = "crop.jpg";
	private static final int EXPECTED_LENGTH = 1024;

	private Path tempDir;

	@Test
	public void bothMissingProducesSaveMayHaveFailed() throws IOException
	{
		// Strategy-B + delete-of-placeholder + filesystem-out-of-sync edge case: the SAF write reported
		// success, the SAF overwrite path may have run, but a subsequent FS query finds neither file. The
		// dialog tells the user to "check your save directory" because we can't say more without disk evidence.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue("both missing must classify as failure", outcome.isPresent());
		ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
		assertEquals("Save may have failed", failure.title());
		assertTrue("message mentions both names",
			failure.message().contains(REQUESTED_NAME) && failure.message().contains("crop (1).jpg"));
	}

	@Test
	public void cleanReplaceWithPlaceholderAndTargetSeparate() throws IOException
	{
		// Strategy B / C success: target file exists at the correct length, placeholder is gone. Classifier
		// returns empty (no failure). The "placeholder ≠ target" branch — Strategy C may have renamed
		// placeholder ONTO target's path; that case is exercised separately.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(target.toPath(), EXPECTED_LENGTH);
		// placeholder doesn't exist
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue("target present at correct length + placeholder gone = clean", outcome.isEmpty());
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
	public void hiddenPlaceholderDeletedWhenRedundantNextToVerifiedTarget() throws IOException
	{
		// Strategy A wrote + verified the target but couldn't delete the hidden placeholder at the time. The
		// classifier retries the delete: the placeholder is a redundant duplicate of a verified target, so
		// removing it completes the clean end state — no dialog, no hidden orphan left for the startup sweep.
		File placeholder = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), EXPECTED_LENGTH);
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue("verified target + redundant placeholder must classify clean", outcome.isEmpty());
		assertFalse("redundant hidden placeholder must be deleted", placeholder.exists());
		assertTrue("verified target must be untouched", target.exists());
		assertEquals(EXPECTED_LENGTH, target.length());
	}

	@Test
	public void hiddenPlaceholderKeptCarriesJournalSignal()
	{
		// The journal contract behind the startup sweep's promote-instead-of-delete rule: when every rename
		// attempt failed and the dialog is about to promise the user their crop "was kept as a hidden file",
		// the VerifyFailure must carry that hidden name as keptHiddenName — verifyReplace journals it, and
		// without the signal the sweep would delete the promised file an hour later. Pinned via the
		// package-private factory directly because a promotion failure can't be forced portably on a real
		// filesystem (renames of files and non-empty directories both succeed in a writable temp dir).
		String hiddenName = ".cropcenter-tmp-99-crop.jpg";
		ReplaceStrategy.VerifyFailure failure = ReplaceStrategy.hiddenPlaceholderKept(REQUESTED_NAME,
			hiddenName);
		assertEquals("journal signal must carry the exact on-disk hidden name",
			hiddenName, failure.keptHiddenName());
		assertTrue("dialog must name the kept hidden file", failure.message().contains(hiddenName));
	}

	@Test
	public void hiddenPlaceholderPromotedToVisibleNameWhenTargetCorrupt() throws IOException
	{
		// Every strategy failed and the colliding target holds foreign / wrong-length content: the hidden
		// crash-safe placeholder is the user's verified save. The classifier must rename it to a visible "(N)"
		// sibling and the dialog must name THAT file — a message interpolating the dotfile name would claim a
		// save Gallery and the Files app can never show.
		File placeholder = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), 999); // wrong length — not the verified write
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue(outcome.isPresent());
		ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
		File promoted = new File(tempDir.toFile(), "crop (1).jpg");
		assertFalse("hidden placeholder must be renamed away", placeholder.exists());
		assertTrue("placeholder must land at the visible (N) sibling", promoted.exists());
		assertEquals(EXPECTED_LENGTH, promoted.length());
		assertTrue("dialog must name the visible file", failure.message().contains("crop (1).jpg"));
		assertFalse("dialog must not leak the hidden temp name",
			failure.message().contains(SaveTempFiles.TEMP_NAME_MARKER));
	}

	@Test
	public void hiddenPlaceholderRenamedOntoMissingTarget() throws IOException
	{
		// Crash-safe fresh-save keep path: only the hidden placeholder survived and the target name is free.
		// The classifier completes the swap itself — the verified bytes land at the requested visible name and
		// the outcome is clean, not a failure dialog about a hidden file. wasOverwrite=false because this IS
		// the NEW-FILE-classified scenario: the pre-rename probe keys on target existence, so a still-free
		// target name completes the promotion exactly as an overwrite save would.
		File placeholder = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, false);
		assertTrue("verified placeholder + free target name must complete as clean", outcome.isEmpty());
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		assertTrue("placeholder must now live at the requested name", target.exists());
		assertEquals(EXPECTED_LENGTH, target.length());
		assertFalse("hidden temp name must be gone", placeholder.exists());
	}

	@Test
	public void hiddenWrongLengthPlaceholderPromotedButNeverLandsAtRequestedName() throws IOException
	{
		// Sole-survivor HIDDEN placeholder (SaveTempFiles name) at the WRONG length, target name free. Two
		// integrity contracts pin together here: the length gate must reject the completion rename (a
		// wrong-length file must never land at the requested visible name as the "verified" save), and
		// placeholderOnly must still promote the dotfile to a visible "(N)" sibling so the failure dialog
		// names a file Gallery can actually show.
		File placeholder = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		writeBytes(placeholder.toPath(), 512); // half the expected 1024 — not the verified write
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue("wrong-length hidden placeholder must not classify clean", outcome.isPresent());
		ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
		assertFalse("requested name must stay free — no wrong-length file promoted onto it",
			new File(tempDir.toFile(), REQUESTED_NAME).exists());
		File promoted = new File(tempDir.toFile(), "crop (1).jpg");
		assertFalse("hidden placeholder must be renamed away", placeholder.exists());
		assertTrue("placeholder must land at the visible (N) sibling", promoted.exists());
		assertEquals(512, promoted.length());
		assertTrue("dialog must name the visible file", failure.message().contains("crop (1).jpg"));
		assertFalse("dialog must not leak the hidden temp name",
			failure.message().contains(SaveTempFiles.TEMP_NAME_MARKER));
	}

	@Test
	public void journalFailureSkipsCollidingDelete()
	{
		// The fail-closed gate F4's journal exists to serve: a failed journal commit means the
		// delete-to-retry-rename crash window would hold an UNPROTECTED hidden only-copy — the sweep would
		// delete it once stale. The destructive delete must therefore be skipped entirely; the caller's
		// retry rename then fails against the still-existing colliding original and verifyReplace surfaces
		// the recoverable two-files outcome instead.
		List<String> order = new ArrayList<>();
		boolean journaled = ReplaceStrategy.journalThenDeleteColliding(
			SaveTempFiles.tempName(REQUESTED_NAME), () ->
			{
				order.add("journal");
				return false;
			},
			() -> order.add("delete"));
		assertFalse("a failed journal must report not-journaled", journaled);
		assertEquals("the delete must not run without a durable journal entry", List.of("journal"), order);
	}

	@Test
	public void journalRunsBeforeCollidingDeleteForTempShapedPlaceholder()
	{
		// Strategy C's destructive fallback ordering contract: between deleting the colliding original and
		// the retry rename, the hidden placeholder is the user's only copy — the journal MUST land before
		// the delete so a hard kill in that window leaves a protected recovery, not an unjournaled temp the
		// sweep deletes once stale. Recording callbacks pin the order.
		List<String> order = new ArrayList<>();
		boolean journaled = ReplaceStrategy.journalThenDeleteColliding(
			SaveTempFiles.tempName(REQUESTED_NAME), () ->
			{
				order.add("journal");
				return true;
			},
			() -> order.add("delete"));
		assertTrue("temp-shaped placeholder must be journaled", journaled);
		assertEquals(List.of("journal", "delete"), order);
	}

	@Test
	public void journalSkippedForVisiblePlaceholderButDeleteStillRuns()
	{
		// Case B "(N)" auto-rename placeholders are visible files outside the sweep's matcher — journaling
		// them would accrete dead entries the sweep then has to garbage-collect; the delete (the Replace the
		// user confirmed) still runs. An undeterminable (null) name takes the same skip-journal path.
		List<String> order = new ArrayList<>();
		BooleanSupplier journalMustNotRun = () ->
		{
			throw new AssertionError("visible placeholder names must never be journaled");
		};
		assertFalse(ReplaceStrategy.journalThenDeleteColliding("crop (1).jpg",
			journalMustNotRun, () -> order.add("delete")));
		assertFalse(ReplaceStrategy.journalThenDeleteColliding(null,
			journalMustNotRun, () -> order.add("delete")));
		assertEquals(List.of("delete", "delete"), order);
	}

	@Test
	public void knownBadTargetAtExpectedLengthNeverDeletesPlaceholder() throws IOException
	{
		// The exact silent-data-loss threat strategy B's readback exists to catch: the provider left
		// same-length stale content in the target, so a length-only check would call the placeholder a
		// redundant duplicate, DELETE it (the only good copy), and report clean. With knownBadTarget the
		// classifier must fail with the content-mismatch wording and keep both files.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), EXPECTED_LENGTH); // same length — content proven wrong by readback
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, true, true);
		assertTrue("proven-bad target must never classify clean on length alone", outcome.isPresent());
		ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
		assertEquals("Replace wrote wrong content", failure.title());
		assertTrue("message names the bad target", failure.message().contains(REQUESTED_NAME));
		assertTrue("message names the good copy", failure.message().contains("crop (1).jpg"));
		assertTrue("placeholder (the only good copy) must never be deleted", placeholder.exists());
		assertEquals(EXPECTED_LENGTH, placeholder.length());
		assertTrue("proven-wrong target must be kept for the user to inspect", target.exists());
	}

	@Test
	public void knownBadTargetWithHiddenPlaceholderPromotesToVisibleName() throws IOException
	{
		// Same proven-bad-target state but the good copy is still under its hidden crash-safe temp name.
		// The dialog must name a visible file: the placeholder is promoted to the "(N)" sibling first and
		// the hidden marker never leaks into the message.
		File placeholder = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), EXPECTED_LENGTH);
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, true, true);
		assertTrue(outcome.isPresent());
		ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
		File promoted = new File(tempDir.toFile(), "crop (1).jpg");
		assertFalse("hidden placeholder must be renamed away", placeholder.exists());
		assertTrue("good copy must land at the visible (N) sibling", promoted.exists());
		assertEquals(EXPECTED_LENGTH, promoted.length());
		assertTrue("dialog must name the visible good copy", failure.message().contains("crop (1).jpg"));
		assertFalse("dialog must not leak the hidden temp name",
			failure.message().contains(SaveTempFiles.TEMP_NAME_MARKER));
		assertTrue("proven-wrong target must be kept on disk", target.exists());
	}

	@Test
	public void knownBadTargetWithWrongLengthTargetStillReportsContentMismatch() throws IOException
	{
		// knownBadTarget routes on target existence, not length: a wrong-length proven-bad target gets the
		// same content-mismatch outcome (good copy named) rather than the truncation branch — truncation
		// would DELETE the target, which under knownBad may be the user's pre-Replace original left intact
		// by a provider that never applied the overwrite.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), 999);
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, true, true);
		assertTrue(outcome.isPresent());
		assertEquals("Replace wrote wrong content", outcome.orElseThrow().title());
		assertTrue("proven-wrong target must not be deleted", target.exists());
		assertTrue("placeholder must be kept", placeholder.exists());
	}

	@Test
	public void knownBadTargetWithoutPlaceholderKeepsProvenWrongTarget() throws IOException
	{
		// Placeholder gone (external delete / filesystem view lag) and the proven-bad target sits at
		// exactly the expected length. Without the flag this is the clean-replace shape; with it the
		// classifier must report a failure AND leave the target on disk — its stale content (possibly the
		// pre-Replace original) is the only copy the user has left, so deleting it would destroy data.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(target.toPath(), EXPECTED_LENGTH);
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, true, true);
		assertTrue("proven-bad target without backup must classify as failure", outcome.isPresent());
		ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
		assertEquals("Replace wrote wrong content", failure.title());
		assertTrue("message names the target", failure.message().contains(REQUESTED_NAME));
		assertTrue("message says the backup is gone", failure.message().contains("backup"));
		assertTrue("proven-wrong target must NOT be deleted — it's all the user has left", target.exists());
		assertEquals(EXPECTED_LENGTH, target.length());
	}

	@Test
	public void knownBadWithTargetMissingStillCompletesPlaceholderRename() throws IOException
	{
		// knownBadTarget speaks only about the colliding target's content. When the target is gone, the
		// sole-survivor recovery is unchanged: the length-verified placeholder is renamed onto the free
		// target name and the outcome is clean — the file that lands there is the verified good copy.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, true, true);
		assertTrue("good placeholder + free target name must still complete clean", outcome.isEmpty());
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		assertTrue("placeholder must now live at the requested name", target.exists());
		assertEquals(EXPECTED_LENGTH, target.length());
		assertFalse(placeholder.exists());
	}

	@Test
	public void missingAfterRenameWhenPlaceholderEqualsTargetButGone() throws IOException
	{
		// Strategy-C edge case: placeholder == target on path, but the rename then DELETED the file (some
		// providers misbehave). Classifier should report "missing after rename" so the user knows what
		// happened.
		File placeholder = new File(tempDir.toFile(), REQUESTED_NAME);
		// Don't write anything — file doesn't exist
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue(outcome.isPresent());
		ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
		assertEquals("Save may have failed", failure.title());
		assertTrue("message mentions requestedName + 'not on disk after Replace'",
			failure.message().contains(REQUESTED_NAME)
			&& failure.message().contains("not on disk after Replace"));
	}

	@Test
	public void nullParentFromRootLevelPlaceholderClassifiesAsFailure() throws IOException
	{
		// Defensive edge: a placeholder constructed at the filesystem root (no parent component) has
		// File.getParentFile() == null. classifyFilesystemOutcome's `target = (parent != null) ? new
		// File(parent, requestedName) : null` branch must NOT NPE on the downstream `target == null` checks.
		// The result classifies as a "both missing" failure because neither the placeholder nor the
		// (unconstructable) target can be confirmed on disk — which is the honest answer given we have no
		// parent path to probe a sibling against.
		File placeholder = new File("crop (1).jpg");
		assertNull("test fixture: root-level File has no parent", placeholder.getParentFile());
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue("null-parent placeholder must classify as failure, not NPE", outcome.isPresent());
		assertEquals("Save may have failed", outcome.orElseThrow().title());
	}

	@Test
	public void promoteSoleSurvivorNewFileAbortsWithoutAttemptingRename() throws IOException
	{
		// The classifier-side new-file collision guard: the completion rename must probe target existence
		// immediately before renaming and refuse WITHOUT attempting the rename — File.renameTo replaces
		// existing targets on POSIX, so an unprobed rename would silently clobber a file another process
		// created after the save was classified NEW-FILE. The recording placeholder pins "never attempted"
		// platform-independently.
		RenameRecordingFile placeholder = new RenameRecordingFile(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), 999); // the appeared foreign file — its length is irrelevant
		assertFalse("new-file promotion must refuse an existing target",
			ReplaceStrategy.promoteSoleSurvivor(placeholder, target, EXPECTED_LENGTH, false));
		assertEquals("the rename must never be attempted", 0, placeholder.renameAttempts);
		assertTrue("appeared file must be untouched", target.exists());
		assertEquals(999, target.length());
		assertTrue("placeholder must be kept", placeholder.exists());
	}

	@Test
	public void promoteSoleSurvivorNewFileCompletesOntoFreeTarget() throws IOException
	{
		// The guard keys on target existence, not on the save class alone: a NEW-FILE save whose target name
		// is still free completes the sole-survivor promotion exactly as an overwrite save would.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		assertTrue("free target name must still complete the promotion",
			ReplaceStrategy.promoteSoleSurvivor(placeholder, target, EXPECTED_LENGTH, false));
		assertTrue(target.exists());
		assertEquals(EXPECTED_LENGTH, target.length());
		assertFalse(placeholder.exists());
	}

	@Test
	public void promoteSoleSurvivorOverwriteSaveStillAttemptsRename() throws IOException
	{
		// wasOverwrite=true preserves replace semantics: the probe never gates, and the rename is attempted
		// against an existing target. Whether it lands is platform-dependent (POSIX renameTo replaces,
		// Windows refuses), so only the attempt is pinned.
		RenameRecordingFile placeholder = new RenameRecordingFile(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), 999);
		ReplaceStrategy.promoteSoleSurvivor(placeholder, target, EXPECTED_LENGTH, true);
		assertEquals("overwrite semantics must reach the rename", 1, placeholder.renameAttempts);
	}

	@Test
	public void promoteTempOntoTargetConfirmedReplaceOnZeroByteTargetProceeds() throws IOException
	{
		// The composed dialog-confirmed-Replace pin: the in-app collision dialog fires on bare
		// target.isFile(), so its colliding target may be 0 bytes — a shape classifyOverwrite's explicit-0
		// rule reads as NOT-overwrite. A Replace the user confirmed must never be downgraded to a new-file
		// save by that read: routeCrashSafeSave threads the confirmation into the write-time
		// classification (wasOverwrite = confirmed OR probe), so the promotion keeps replace semantics
		// and swaps onto the 0-byte file instead of refusing with ABORTED_TARGET_APPEARED forever.
		File tempFile = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(tempFile.toPath(), EXPECTED_LENGTH);
		assertTrue("fixture: 0-byte pre-existing colliding target", target.createNewFile());
		BooleanSupplier mustNotConsultStream = () ->
		{
			throw new AssertionError("explicit-0 must not consult the stream probe");
		};
		boolean probeSaysOverwrite = SaveController.classifyOverwrite(0, mustNotConsultStream, false);
		assertFalse("the explicit-0 rule downgrades the 0-byte pre-existing file to NOT-overwrite",
			probeSaysOverwrite);
		boolean confirmedReplace = true; // the user tapped Replace in the collision dialog
		assertTrue("confirmed Replace must promote with replace semantics despite the probe downgrade",
			ReplaceStrategy.promoteTempOntoTarget(tempFile, target,
				confirmedReplace || probeSaysOverwrite));
		assertEquals("target must hold the payload", EXPECTED_LENGTH, target.length());
		assertFalse("temp must have moved onto the target", tempFile.exists());
	}

	@Test
	public void promoteTempOntoTargetNewFileAbortsWhenTargetAppeared() throws IOException
	{
		// Strategy A's new-file collision guard — the REPLACE_EXISTING clobber window this probe closes: a
		// file created by another process between the collision probe (save classified NEW-FILE) and the
		// promotion move must never be silently replaced. The probe runs immediately before the atomic
		// swap; a hit refuses the move, leaving the appeared file byte-identical and the temp in place for
		// the caller to discard (replaceViaFileIo then aborts the whole promotion, SAF strategies included).
		File tempFile = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(tempFile.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), 999); // the appeared foreign file
		assertFalse("new-file promotion must refuse an existing target",
			ReplaceStrategy.promoteTempOntoTarget(tempFile, target, false));
		assertTrue("appeared file must be untouched", target.exists());
		assertEquals("appeared file's bytes must survive", 999, target.length());
		assertTrue("temp must be left for the caller's discard path", tempFile.exists());
		assertEquals(EXPECTED_LENGTH, tempFile.length());
	}

	@Test
	public void promoteTempOntoTargetNewFileMovesOntoFreeTarget() throws IOException
	{
		// A NEW-FILE save with the target name still free is the normal promotion: the atomic move runs and
		// the payload lands at the requested name.
		File tempFile = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(tempFile.toPath(), EXPECTED_LENGTH);
		assertTrue(ReplaceStrategy.promoteTempOntoTarget(tempFile, target, false));
		assertTrue(target.exists());
		assertEquals(EXPECTED_LENGTH, target.length());
		assertFalse(tempFile.exists());
	}

	@Test
	public void promoteTempOntoTargetNewFileSecondGateRefusesProbeMissedTarget() throws IOException
	{
		// Gate two of the new-file collision guard: a file that appears AFTER the userspace probe but
		// before the move must still be refused — the no-REPLACE_EXISTING move turns the filesystem's own
		// existence check into a second gate (FileAlreadyExistsException → the same refusal as the
		// probe). Driven deterministically with a target whose exists() reports false while the file IS
		// on disk, so gate one passes and only gate two can refuse.
		File tempFile = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		File probeBlindTarget = new File(tempDir.toFile(), REQUESTED_NAME)
		{
			@Override
			public boolean exists()
			{
				// Probe-miss simulation: the on-disk file is invisible to the userspace probe.
				return false;
			}
		};
		writeBytes(tempFile.toPath(), EXPECTED_LENGTH);
		writeBytes(probeBlindTarget.toPath(), 999); // the appeared foreign file
		assertFalse("gate two must refuse the appeared target",
			ReplaceStrategy.promoteTempOntoTarget(tempFile, probeBlindTarget, false));
		assertEquals("appeared file's bytes must survive", 999, probeBlindTarget.length());
		assertTrue("temp must be left for the caller's discard path", tempFile.exists());
		assertEquals(EXPECTED_LENGTH, tempFile.length());
	}

	@Test
	public void promoteTempOntoTargetOverwriteSaveReplacesExistingTarget() throws IOException
	{
		// wasOverwrite=true preserves replace semantics: REPLACE_EXISTING swaps the temp onto the colliding
		// target — exactly the Replace the user confirmed.
		File tempFile = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(tempFile.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), 999);
		assertTrue(ReplaceStrategy.promoteTempOntoTarget(tempFile, target, true));
		assertEquals("target must hold the payload", EXPECTED_LENGTH, target.length());
		assertFalse(tempFile.exists());
	}

	@Test
	public void promoteToVisibleNameCollisionRetryRefusesAppearedFileAndLandsAtNextCandidate()
		throws IOException
	{
		// The "(N)" promotion's collision guard: nextAvailableNumberedName's exists() probe and the move are
		// not atomic, and a bare rename would silently replace a file another process created at the probed
		// name in that window. Driven through the retry seam with a STALE first candidate whose file is
		// already on disk (the fill-in window is not reachable single-threaded): the no-replace move must
		// refuse the appeared file byte-for-byte untouched and the retry must land the placeholder at the
		// next free "(N)" sibling.
		File placeholder = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		File appeared = new File(tempDir.toFile(), "crop (1).jpg");
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		writeBytes(appeared.toPath(), 999); // filled in after the (simulated) probe picked "crop (1).jpg"
		String landed = ReplaceStrategy.retryPromotionOntoFreeSibling(placeholder, tempDir.toFile(),
			"crop (1).jpg");
		assertEquals("retry must land at the next free candidate", "crop (2).jpg", landed);
		assertTrue("appeared file must be untouched", appeared.exists());
		assertEquals("appeared file's bytes must survive", 999, appeared.length());
		File promoted = new File(tempDir.toFile(), "crop (2).jpg");
		assertTrue("placeholder must live at the retried candidate", promoted.exists());
		assertEquals(EXPECTED_LENGTH, promoted.length());
		assertFalse("hidden temp name must be gone", placeholder.exists());
	}

	@Test
	public void promotedKeptNameCarriesNoJournalSignal() throws IOException
	{
		// The inverse of the journal contract: once promotion lands the kept file at a visible "(N)" name,
		// the failure must carry NO keptHiddenName — the file is out of the sweep's matcher entirely, and a
		// stale journal write here would accrete dead entries the sweep then has to garbage-collect.
		File placeholder = new File(tempDir.toFile(), SaveTempFiles.tempName(REQUESTED_NAME));
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), 999); // wrong length — two-files branch with promotion
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue(outcome.isPresent());
		ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
		assertTrue("fixture: promotion must have landed the visible sibling",
			new File(tempDir.toFile(), "crop (1).jpg").exists());
		assertNull("a visible kept name must not be journaled", failure.keptHiddenName());
	}

	@Test
	public void renameFallbackDeletesOnlyOnPositivePlaceholderEvidence()
	{
		// The two shapes of positive evidence that sanction the destructive delete-then-retry: the filesystem
		// probe sees the placeholder still on disk, or (opaque provider) the display-name probe returns a name
		// that is NOT the requested one — the placeholder verifiably still sits under its own name, so the
		// colliding document cannot be the renamed export.
		assertEquals(ReplaceStrategy.RenameFallbackDecision.DELETE_THEN_RETRY,
			ReplaceStrategy.classifyRenameFallback(true, true, null, REQUESTED_NAME));
		assertEquals(ReplaceStrategy.RenameFallbackDecision.DELETE_THEN_RETRY,
			ReplaceStrategy.classifyRenameFallback(false, false, "crop (1).jpg", REQUESTED_NAME));
	}

	@Test
	public void renameFallbackFailsClosedWhenBothProbesUnavailable()
	{
		// The data-loss guard the ladder exists for: no filesystem path AND no display name means nothing can
		// say whether the placeholder still exists — after a silently-successful null-on-success rename the
		// colliding URI IS the just-renamed export, so the fallback must refuse the delete and leave the
		// outcome to verifyReplace rather than risk destroying the user's only verified copy.
		assertEquals(ReplaceStrategy.RenameFallbackDecision.FAIL_CLOSED,
			ReplaceStrategy.classifyRenameFallback(false, false, null, REQUESTED_NAME));
	}

	@Test
	public void renameFallbackTreatsGonePlaceholderOrRequestedNameAsSilentSuccess()
	{
		// Silent-success evidence, both shapes: the filesystem probe sees the placeholder gone (it moved onto
		// the colliding path), or the display-name probe reports the document already answers to the requested
		// name. Match is case-insensitive, same convention as tryRename's legacy null-on-success check.
		assertEquals(ReplaceStrategy.RenameFallbackDecision.SILENT_SUCCESS,
			ReplaceStrategy.classifyRenameFallback(true, false, null, REQUESTED_NAME));
		assertEquals(ReplaceStrategy.RenameFallbackDecision.SILENT_SUCCESS,
			ReplaceStrategy.classifyRenameFallback(false, false, REQUESTED_NAME, REQUESTED_NAME));
		assertEquals(ReplaceStrategy.RenameFallbackDecision.SILENT_SUCCESS,
			ReplaceStrategy.classifyRenameFallback(false, false, "CROP.JPG", REQUESTED_NAME));
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
		// returns empty.
		File placeholder = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue("Strategy C clean rename must classify as success", outcome.isEmpty());
	}

	@Test
	public void truncatedTargetReportsBytesAndIsPreserved() throws IOException
	{
		// The wrong-length target is the ONLY file on disk — nothing proves it is our failed write rather
		// than the pre-Replace original or a file another process created, so the classifier must KEEP it:
		// deleting would destroy the only copy the user has left. The wording reports actual vs expected
		// byte counts and says the file was left on disk, never that it was removed.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(target.toPath(), 512); // wrong length — half the expected 1024
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue(outcome.isPresent());
		ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
		assertEquals("Replace produced an incomplete file", failure.title());
		assertTrue("message mentions actual byte count (512)", failure.message().contains("512"));
		assertTrue("message mentions expected byte count (1024)", failure.message().contains("1024"));
		assertTrue("message says the file was preserved", failure.message().contains("left on disk"));
		assertFalse("message must not claim removal", failure.message().contains("removed"));
		assertTrue("sole surviving target must NOT be deleted — it may be the user's original",
			target.exists());
		assertEquals(512, target.length());
	}

	@Test
	public void truncatedTargetViaStrategyCRenameAlsoReportsBytesAndPreserves() throws IOException
	{
		// Strategy C variant: placeholder == target on path AND target exists but at wrong length. The
		// SILENT_SUCCESS filesystem inference behind this branch ("placeholder gone => rename landed") can
		// misfire under FUSE-view lag, in which case this file IS the untouched pre-Replace original — the
		// classifier must keep it and report the same preserved-file wording as the different-name branch.
		File placeholder = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), 512);
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue(outcome.isPresent());
		ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
		assertEquals("Replace produced an incomplete file", failure.title());
		assertTrue("message says the file was preserved", failure.message().contains("left on disk"));
		assertTrue("wrong-length sole survivor must NOT be deleted", placeholder.exists());
		assertEquals(512, placeholder.length());
	}

	@Test
	public void twoFilesProducesReplaceLeftTwoFilesMessage() throws IOException
	{
		// Strategy B+C both partial: placeholder kept AND target also exists (perhaps the user's first save
		// landed at a "(1)" suffix and a later run produced the bare name). Classifier surfaces "Replace left
		// two files" so the user can clean up manually.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		writeBytes(target.toPath(), 999); // any non-expected length triggers two-files branch
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue(outcome.isPresent());
		ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
		assertEquals("Replace left two files", failure.title());
		assertTrue("message names both files",
			failure.message().contains(REQUESTED_NAME) && failure.message().contains("crop (1).jpg"));
	}

	@Test
	public void undeletablePlaceholderNextToVerifiedTargetReportsTwoFiles() throws IOException
	{
		// Strategy A wrote + verified the target but the redundant placeholder can't be deleted (FUSE
		// lock, permission hiccup). The classifier must fall through to twoFiles — an unconditional
		// return-null after the delete attempt would report a clean "Replaced" while a duplicate lingers
		// on disk, the exact silent-false-green the classifier exists to prevent. Fixture: the placeholder
		// path is a NON-EMPTY DIRECTORY named "crop (1).jpg" — File.delete() fails on it deterministically
		// on every platform, no mocking needed.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		File inner = new File(placeholder, "pin.bin");
		assertTrue("fixture: placeholder dir must be created", placeholder.mkdir());
		writeBytes(inner.toPath(), 1);
		File target = new File(tempDir.toFile(), REQUESTED_NAME);
		writeBytes(target.toPath(), EXPECTED_LENGTH);
		try
		{
			Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
				placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
			assertTrue("undeletable placeholder must classify as a failure, not clean",
				outcome.isPresent());
			ReplaceStrategy.VerifyFailure failure = outcome.orElseThrow();
			assertEquals("Replace left two files", failure.title());
			assertTrue("message names both files", failure.message().contains(REQUESTED_NAME)
				&& failure.message().contains("crop (1).jpg"));
			assertTrue("verified target must be untouched", target.exists());
			assertEquals(EXPECTED_LENGTH, target.length());
			assertTrue("placeholder must still be on disk", placeholder.exists());
		}
		finally
		{
			// The @After cleanup can't recurse into the directory fixture — empty it here so the temp
			// dir delete succeeds.
			inner.delete();
			placeholder.delete();
		}
	}

	@Test
	public void unjournaledKeptPromiseDegradesHonestly()
	{
		// When journalKeptRecovery can't durably record the kept recovery (unresolvable folder, commit
		// failure), the sweep WILL delete the hidden file once it passes the stale age guard — the dialog's
		// "was kept" promise must degrade to warn about the one-hour cleanup window instead of implying a
		// protection the journal doesn't hold.
		ReplaceStrategy.VerifyFailure kept = ReplaceStrategy.hiddenPlaceholderKept(REQUESTED_NAME,
			".cropcenter-tmp-99-crop.jpg");
		ReplaceStrategy.VerifyFailure degraded = ReplaceStrategy.degradeKeptPromiseWhenUnjournaled(kept, false);
		assertTrue("degraded wording must warn about the cleanup window",
			degraded.message().contains("may be cleaned up automatically after an hour"));
		assertTrue("degraded wording must tell the user to act promptly",
			degraded.message().contains("rescue or re-save it soon"));
		assertEquals("title survives degradation", kept.title(), degraded.title());
		assertEquals("journal signal survives degradation", kept.keptHiddenName(), degraded.keptHiddenName());
		assertEquals("a journaled kept promise must pass through unchanged",
			kept, ReplaceStrategy.degradeKeptPromiseWhenUnjournaled(kept, true));
		ReplaceStrategy.VerifyFailure plain = new ReplaceStrategy.VerifyFailure("Save may have failed",
			"no kept recovery");
		assertEquals("failures without a kept hidden name never degrade",
			plain, ReplaceStrategy.degradeKeptPromiseWhenUnjournaled(plain, false));
	}

	@Test
	public void visiblePlaceholderCompletesSaveByRenameWhenTargetMissing() throws IOException
	{
		// Case B keep path with the colliding original gone: the length-verified "(N)" placeholder is the only
		// survivor and the requested name is free. Completing the swap by rename is exactly the Replace the
		// user confirmed — clean outcome, file at the requested name.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		writeBytes(placeholder.toPath(), EXPECTED_LENGTH);
		// target doesn't exist
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue("verified placeholder + free target name must complete as clean", outcome.isEmpty());
		assertTrue("placeholder must now live at the requested name",
			new File(tempDir.toFile(), REQUESTED_NAME).exists());
		assertFalse(placeholder.exists());
	}

	@Test
	public void wrongLengthPlaceholderIsNotRenamedOntoTarget() throws IOException
	{
		// Sole-survivor placeholder whose length does NOT match the verified write (post-verify corruption /
		// stale FUSE metadata): the completion rename must not run — a wrong-length file must never land at the
		// visible requested name as if it were the verified save. The visible "(N)" placeholder keeps its name
		// and the dialog names it.
		File placeholder = new File(tempDir.toFile(), "crop (1).jpg");
		writeBytes(placeholder.toPath(), 512); // half the expected 1024
		Optional<ReplaceStrategy.VerifyFailure> outcome = ReplaceStrategy.classifyFilesystemOutcome(
			placeholder, REQUESTED_NAME, EXPECTED_LENGTH, false, true);
		assertTrue("wrong-length placeholder must not classify clean", outcome.isPresent());
		assertFalse("requested name must stay free — no wrong-length file promoted onto it",
			new File(tempDir.toFile(), REQUESTED_NAME).exists());
		assertTrue("placeholder keeps its visible name", placeholder.exists());
		assertTrue("dialog names the kept file", outcome.orElseThrow().message().contains("crop (1).jpg"));
	}

	private static void writeBytes(Path path, int length) throws IOException
	{
		byte[] payload = new byte[length];
		Files.write(path, payload, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
			StandardOpenOption.TRUNCATE_EXISTING);
	}
}
