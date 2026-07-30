package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Tests for SaveTempFiles — the single chokepoint for crash-safe save temp naming and the startup sweep. The
 * load-bearing contract: every name a writer builds via tempName is matched by isTempArtifact (or the sweep can never
 * reclaim a hard-kill orphan), the legacy suffix shape from earlier builds still matches, user files never match, the
 * age guard protects an in-flight save's fresh temp from the sweep, and a journaled kept recovery (a hidden file a
 * Replace failure dialog promised the user was KEPT) is never deleted — the sweep promotes it to a visible
 * "stem (N).ext" sibling instead and drops the journal entry once the file is resolved (promoted or vanished).
 * Journal entries are folder-scoped absolute paths, so every check runs against the entry's OWN folder: a recovery
 * kept in one folder survives, and still promotes, when a later save moves the last-save folder elsewhere.
 *
 * The in-flight temp journal (SaveController.journalInflightTemp — every temp's absolute path, recorded before
 * creation) is the DELETE-side twin: the sweep reclaims a stale journaled temp in whatever folder it sits — the
 * unjournaled delete pass reaches only the last-save folder — dropping entries as they resolve (deleted, vanished
 * from a present parent, or never-deletable by shape) and retaining fresh temps and unverifiable missing-parent
 * entries. A path journaled in BOTH sets is owned by the kept lifecycle: never deletable through the temp pass.
 */
public final class SaveTempFilesTest
{
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void builtNameRoundTripsThroughMatcher() throws IOException
	{
		// The writer-to-sweeper contract: a name built by tempName MUST be matched by isTempArtifact. A drift
		// between the builder and the matcher makes every hard-kill orphan unsweepable.
		String name = SaveTempFiles.tempName("crop.jpg");
		assertTrue("built name must match the sweep predicate", SaveTempFiles.isTempArtifact(name));
		assertTrue("built name must be hidden (leading dot)", name.startsWith(SaveTempFiles.TEMP_NAME_MARKER));
		assertTrue("built name must carry the visible name for post-crash diagnosis",
			name.endsWith("-crop.jpg"));
	}

	@Test
	public void isReservedNameCoversWholeMarkerNamespace()
	{
		// The user-filename reservation (FolderPickerDialog.isValidFilename routes here) is deliberately
		// broader than isTempArtifact: every deletable sweep shape plus everything one nonce short of one —
		// a user file must not be able to sit anywhere in the marker namespace.
		assertTrue(SaveTempFiles.isReservedName(SaveTempFiles.tempName("crop.jpg")));
		assertTrue(SaveTempFiles.isReservedName(".crop.jpg.cropcenter-tmp-123456789"));
		assertTrue("one-nonce-short marker prefix is still reserved",
			SaveTempFiles.isReservedName(".cropcenter-tmp-crop.jpg"));
		assertTrue("marker substring with a malformed tail is still reserved",
			SaveTempFiles.isReservedName(".notes.cropcenter-tmp-reference.jpg"));
		assertFalse(SaveTempFiles.isReservedName("crop.jpg"));
		assertFalse("an ordinary hidden file is outside the namespace",
			SaveTempFiles.isReservedName(".hiddenphoto.jpg"));
		assertFalse(SaveTempFiles.isReservedName(null));
	}

	@Test
	public void legacySuffixShapeStillMatches()
	{
		// Earlier builds wrote ".<name>.cropcenter-tmp-<nanos>" write-temps; stale artifacts with that shape
		// still sit on user devices, so the matcher must reclaim them too.
		assertTrue(SaveTempFiles.isTempArtifact(".crop.jpg.cropcenter-tmp-123456789"));
		assertTrue(SaveTempFiles.isTempArtifact(".photo (1).png.cropcenter-tmp-42"));
	}

	@Test
	public void matcherRejectsUserFilesAndNull()
	{
		// A false positive here deletes a user's file at startup — the matcher validates the full generated
		// shape (marker position + numeric nonce), so a filename merely containing the marker never matches.
		assertFalse(SaveTempFiles.isTempArtifact("crop.jpg"));
		assertFalse(SaveTempFiles.isTempArtifact("crop (1).jpg"));
		assertFalse(SaveTempFiles.isTempArtifact(".hiddenphoto.jpg"));
		assertFalse("marker without leading dot must not match",
			SaveTempFiles.isTempArtifact("cropcenter-tmp-1-crop.jpg"));
		assertFalse("hidden user file containing the marker with a non-numeric tail must not match",
			SaveTempFiles.isTempArtifact(".notes.cropcenter-tmp-reference.jpg"));
		assertFalse("marker prefix without a numeric nonce must not match",
			SaveTempFiles.isTempArtifact(".cropcenter-tmp-nodigits-crop.jpg"));
		assertFalse("marker prefix with digits but no separator must not match",
			SaveTempFiles.isTempArtifact(".cropcenter-tmp-123456"));
		assertFalse("separator with an empty visible name matches nothing tempName builds — consistent"
			+ " with visibleNameOf's empty return for the same shape",
			SaveTempFiles.isTempArtifact(".cropcenter-tmp-123456-"));
		assertFalse(SaveTempFiles.isTempArtifact(null));
	}

	@Test
	public void sweepAgeGuardsFreshJournaledTemp() throws IOException
	{
		// A journaled kept recovery younger than STALE_TEMP_MIN_AGE_MS is untouched on every axis: not deleted
		// (journal), not promoted (age guard — the Replace flow that journaled it may still be running against
		// the hidden name), journal entry retained for the launch that finds it stale.
		File freshKept = new File(tmp.getRoot(), SaveTempFiles.tempName("crop.jpg"));
		Files.write(freshKept.toPath(), new byte[]{ 1 });
		Set<String> journal = new HashSet<>(Set.of(freshKept.getAbsolutePath()));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(tmp.getRoot(), System.currentTimeMillis(),
			journal, file ->
			{
				throw new AssertionError("fresh temp must not reach the promoter");
			}, new HashSet<>());

		assertEquals(0, deleted);
		assertTrue("fresh journaled temp must survive untouched", freshKept.exists());
		assertTrue("journal entry must be retained for the next launch",
			journal.contains(freshKept.getAbsolutePath()));
	}

	@Test
	public void sweepDeletesOnlyStaleArtifacts() throws IOException
	{
		long now = System.currentTimeMillis();
		File staleTemp = new File(tmp.getRoot(), SaveTempFiles.tempName("crop.jpg"));
		File freshTemp = new File(tmp.getRoot(), SaveTempFiles.tempName("fresh.jpg"));
		File staleLegacyTemp = new File(tmp.getRoot(), ".crop.jpg.cropcenter-tmp-99");
		File staleUserFile = new File(tmp.getRoot(), "vacation.jpg");
		Files.write(staleTemp.toPath(), new byte[]{ 1 });
		Files.write(freshTemp.toPath(), new byte[]{ 2 });
		Files.write(staleLegacyTemp.toPath(), new byte[]{ 3 });
		Files.write(staleUserFile.toPath(), new byte[]{ 4 });
		backdate(staleTemp, now);
		backdate(staleLegacyTemp, now);
		backdate(staleUserFile, now);

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(tmp.getRoot(), now, new HashSet<>(), file ->
		{
			throw new AssertionError("non-journaled temps must never reach the promoter");
		}, new HashSet<>());

		assertEquals("both stale temp shapes reclaimed, nothing else", 2, deleted);
		assertFalse("stale current-shape temp must be swept", staleTemp.exists());
		assertFalse("stale legacy-shape temp must be swept", staleLegacyTemp.exists());
		assertTrue("fresh temp (in-flight save) must survive the age guard", freshTemp.exists());
		assertTrue("user files must never be swept regardless of age", staleUserFile.exists());
	}

	@Test
	public void sweepDeletesStrandedJournaledTempInOldFolder() throws IOException
	{
		// THE leak F6 closes: a hard kill strands a temp in folder A, the user then saves into folder B, and
		// the startup sweep runs with B as the last-save folder. The unjournaled delete pass never visits A —
		// without the at-creation temp journal the stranded temp (400-600 MB for a 200 MP PNG) leaks
		// permanently. The journaled entry must reach into A: stale temp deleted, entry dropped.
		long now = System.currentTimeMillis();
		File folderA = tmp.newFolder("a");
		File folderB = tmp.newFolder("b");
		File strandedA = new File(folderA, SaveTempFiles.tempName("crop.jpg"));
		Files.write(strandedA.toPath(), new byte[]{ 1 });
		backdate(strandedA, now);
		Set<String> temps = new HashSet<>(Set.of(strandedA.getAbsolutePath()));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(folderB, now, new HashSet<>(), file ->
		{
			throw new AssertionError("an in-flight temp entry must not reach the kept promoter");
		}, temps);

		assertEquals("the stranded temp is the one deletion", 1, deleted);
		assertFalse("stale journaled temp must be deleted in its OWN folder", strandedA.exists());
		assertTrue("the resolved entry must be dropped", temps.isEmpty());
	}

	@Test
	public void sweepDropsInflightEntryVanishedFromOwnFolder() throws IOException
	{
		// An in-process disposal whose apply()-mode un-journal was lost (documented tolerance): the entry's
		// file is gone from its own present parent, so the entry is resolved and dropped without a deletion.
		File folder = tmp.newFolder("a");
		String gonePath = new File(folder, ".cropcenter-tmp-11-gone.jpg").getAbsolutePath();
		Set<String> temps = new HashSet<>(Set.of(gonePath));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(tmp.getRoot(), System.currentTimeMillis(),
			new HashSet<>(), file -> false, temps);

		assertEquals(0, deleted);
		assertTrue("entry vanished from its own folder must be dropped", temps.isEmpty());
	}

	@Test
	public void sweepDropsJournalEntryVanishedFromItsOwnFolder() throws IOException
	{
		// The journaled recovery is no longer in ITS OWN folder (user cleaned it up in a file manager, or an
		// earlier promotion landed and the journal write raced a kill). The entry is resolved — drop it so the
		// journal can't grow without bound. A same-named file in the CURRENT last-save folder must not mask
		// the vanish: the check runs against the entry's own folder, never the swept one.
		File folderA = tmp.newFolder("a");
		File folderB = tmp.newFolder("b");
		File goneA = new File(folderA, ".cropcenter-tmp-42-gone.jpg");
		File decoyB = new File(folderB, goneA.getName());
		Files.write(decoyB.toPath(), new byte[]{ 1 });
		Set<String> journal = new HashSet<>(Set.of(goneA.getAbsolutePath()));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(folderB, System.currentTimeMillis(), journal,
			file ->
			{
				throw new AssertionError("a vanished file must not reach the promoter");
			}, new HashSet<>());

		assertEquals(0, deleted);
		assertTrue("entry vanished from its own folder must be dropped", journal.isEmpty());
		assertTrue("the same-named decoy in the swept folder is fresh and must survive", decoyB.exists());
	}

	@Test
	public void sweepDropsNonTempShapedInflightEntryWithoutDeleting() throws IOException
	{
		// Defensive deletion gate: the journal only ever holds tempName-generated names, but the temp pass
		// gates deletion in the user's folders — a stale entry somehow pointing at a NON-temp-shaped file
		// must never delete it, and the never-deletable entry is dropped rather than lingering forever.
		long now = System.currentTimeMillis();
		File userFile = new File(tmp.newFolder("a"), "vacation.jpg");
		Files.write(userFile.toPath(), new byte[]{ 1 });
		backdate(userFile, now);
		Set<String> temps = new HashSet<>(Set.of(userFile.getAbsolutePath()));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(null, now, new HashSet<>(), file -> false, temps);

		assertEquals("a non-temp-shaped file must never be deleted", 0, deleted);
		assertTrue("the user file must survive", userFile.exists());
		assertTrue("the never-deletable entry must be dropped", temps.isEmpty());
	}

	@Test
	public void sweepInflightEntryWithMissingParentRetained()
	{
		// Unmounted-volume symmetry with the kept journal: no parent directory means nothing can be
		// verified, so the entry is retained — dropping it would leak the temp permanently if the volume
		// comes back.
		String unmountedPath = new File(new File(tmp.getRoot(), "unmounted"),
			".cropcenter-tmp-12-kept.jpg").getAbsolutePath();
		Set<String> temps = new HashSet<>(Set.of(unmountedPath));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(null, System.currentTimeMillis(),
			new HashSet<>(), file -> false, temps);

		assertEquals(0, deleted);
		assertTrue("entry with a missing parent must be retained", temps.contains(unmountedPath));
	}

	@Test
	public void sweepKeepsJournalEntryWhenPromotionFails() throws IOException
	{
		// Promotion can fail transiently (FUSE lock, name probe hiccup). The file must still survive — the
		// delete branch is unreachable for journaled names — and the entry stays so the next launch retries.
		long now = System.currentTimeMillis();
		File kept = new File(tmp.getRoot(), SaveTempFiles.tempName("crop.jpg"));
		Files.write(kept.toPath(), new byte[]{ 1 });
		backdate(kept, now);
		Set<String> journal = new HashSet<>(Set.of(kept.getAbsolutePath()));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(tmp.getRoot(), now, journal, file -> false,
			new HashSet<>());

		assertEquals("a failed promotion must not fall through to delete", 0, deleted);
		assertTrue("journaled temp must survive a failed promotion", kept.exists());
		assertTrue("journal entry must be retained for a retry", journal.contains(kept.getAbsolutePath()));
	}

	@Test
	public void sweepNeverDeletesInflightTempAlsoJournaledAsKeptRecovery() throws IOException
	{
		// The kept-recovery precedence guard: a crash-safe placeholder is journaled as an in-flight temp at
		// creation, and a later Replace failure dialog promises it was KEPT (kept-recovery journal, same
		// path). The temp pass must NEVER delete it — the kept lifecycle (promote, never delete) owns the
		// file. Promotion is refused (file -> false) so the guard is pinned while both entries survive.
		long now = System.currentTimeMillis();
		File kept = new File(tmp.newFolder("a"), SaveTempFiles.tempName("crop.jpg"));
		Files.write(kept.toPath(), new byte[]{ 1 });
		backdate(kept, now);
		Set<String> keptJournal = new HashSet<>(Set.of(kept.getAbsolutePath()));
		Set<String> temps = new HashSet<>(Set.of(kept.getAbsolutePath()));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(null, now, keptJournal, file -> false, temps);

		assertEquals("a kept-journaled path must never be deleted through the temp pass", 0, deleted);
		assertTrue("the promised recovery must survive", kept.exists());
		assertTrue("the kept entry must be retained for a promotion retry",
			keptJournal.contains(kept.getAbsolutePath()));
		assertTrue("the guarded temp entry must be retained until the kept lifecycle resolves it",
			temps.contains(kept.getAbsolutePath()));
	}

	@Test
	public void sweepNullAndNonDirectorySkipDeletePassOnly() throws IOException
	{
		// A null / non-directory last-save folder skips only the delete pass. Journal entries still resolve
		// against their own folders: a present parent with the file gone drops the entry, while a missing
		// parent (unmounted volume) retains it — nothing can be verified against a folder that isn't there.
		String unmountedPath = new File(new File(tmp.getRoot(), "unmounted"),
			".cropcenter-tmp-7-kept.jpg").getAbsolutePath();
		String vanishedPath = new File(tmp.getRoot(), ".cropcenter-tmp-8-gone.jpg").getAbsolutePath();
		Set<String> journal = new HashSet<>(Set.of(unmountedPath, vanishedPath));

		assertEquals(0, SaveTempFiles.sweepStaleTempArtifacts(null, System.currentTimeMillis(), journal,
			file -> false, new HashSet<>()));

		assertTrue("entry with a missing parent must be retained", journal.contains(unmountedPath));
		assertFalse("entry vanished from its own present folder resolves even with no last-save dir",
			journal.contains(vanishedPath));
		File regularFile = new File(tmp.getRoot(), "not-a-dir.jpg");
		Files.write(regularFile.toPath(), new byte[]{ 1 });
		assertEquals("a regular file is not a sweepable directory",
			0, SaveTempFiles.sweepStaleTempArtifacts(regularFile, System.currentTimeMillis(), journal,
				file -> false, new HashSet<>()));
		assertTrue(regularFile.exists());
	}

	@Test
	public void sweepPromotesJournaledRecoveryInItsOwnFolder() throws IOException
	{
		// A stale journaled recovery is promoted against ITS OWN parent even when the last-save folder is
		// elsewhere: the "(N)" sibling lands next to the recovery, in the folder the failure dialog's promise
		// was made about, and the entry drops once the file is out from under its hidden name.
		long now = System.currentTimeMillis();
		File folderA = tmp.newFolder("a");
		File folderB = tmp.newFolder("b");
		File keptA = new File(folderA, SaveTempFiles.tempName("crop.jpg"));
		Files.write(keptA.toPath(), new byte[]{ 7 });
		backdate(keptA, now);
		Set<String> journal = new HashSet<>(Set.of(keptA.getAbsolutePath()));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(folderB, now, journal,
			SaveController::promoteJournaledRecovery, new HashSet<>());

		File promoted = new File(folderA, "crop (1).jpg");
		assertEquals("promotion is not a deletion", 0, deleted);
		assertFalse("hidden temp name must be gone after promotion", keptA.exists());
		assertTrue("recovery must land at the visible (N) sibling in its own folder", promoted.exists());
		assertEquals(1, promoted.length());
		assertTrue("journal entry must be dropped after successful promotion", journal.isEmpty());
	}

	@Test
	public void sweepPromotesJournaledStaleTempInsteadOfDeleting() throws IOException
	{
		// The kept-recovery promise end-to-end through the production promotion callback: a stale journaled
		// temp is NEVER deleted — SaveController.promoteJournaledRecovery parses the visible name back out of
		// the temp name and ReplaceStrategy.promoteToVisibleName renames it to the first free "stem (N).ext"
		// sibling — and the journal entry is dropped once the file is out from under its hidden name.
		long now = System.currentTimeMillis();
		File kept = new File(tmp.getRoot(), SaveTempFiles.tempName("crop.jpg"));
		Files.write(kept.toPath(), new byte[]{ 7 });
		backdate(kept, now);
		Set<String> journal = new HashSet<>(Set.of(kept.getAbsolutePath()));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(tmp.getRoot(), now, journal,
			SaveController::promoteJournaledRecovery, new HashSet<>());

		File promoted = new File(tmp.getRoot(), "crop (1).jpg");
		assertEquals("promotion is not a deletion", 0, deleted);
		assertFalse("hidden temp name must be gone after promotion", kept.exists());
		assertTrue("recovery must land at the visible (N) sibling", promoted.exists());
		assertEquals(1, promoted.length());
		assertTrue("journal entry must be dropped after successful promotion", journal.isEmpty());
	}

	@Test
	public void sweepPromotionAlsoResolvesInflightEntry() throws IOException
	{
		// Interplay of the two lifecycles on one file: the kept promotion renames the placeholder to its
		// visible "(N)" sibling, after which the same sweep's temp pass sees the temp path vanished from its
		// present parent and drops the now-resolved temp entry — no second launch needed.
		long now = System.currentTimeMillis();
		File kept = new File(tmp.getRoot(), SaveTempFiles.tempName("crop.jpg"));
		Files.write(kept.toPath(), new byte[]{ 7 });
		backdate(kept, now);
		Set<String> keptJournal = new HashSet<>(Set.of(kept.getAbsolutePath()));
		Set<String> temps = new HashSet<>(Set.of(kept.getAbsolutePath()));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(tmp.getRoot(), now, keptJournal,
			SaveController::promoteJournaledRecovery, temps);

		assertEquals("promotion is not a deletion", 0, deleted);
		assertTrue("recovery must land at the visible (N) sibling",
			new File(tmp.getRoot(), "crop (1).jpg").exists());
		assertTrue("kept entry must be dropped after successful promotion", keptJournal.isEmpty());
		assertTrue("the temp entry must resolve as vanished in the same sweep", temps.isEmpty());
	}

	@Test
	public void sweepRetainsFreshJournaledInflightTemp() throws IOException
	{
		// A live save's temp is journaled the moment it is created, well inside the one-hour age guard. The
		// sweep must leave both the file and the entry alone — deleting either would yank an in-flight
		// save's write target out from under it.
		File fresh = new File(tmp.newFolder("a"), SaveTempFiles.tempName("crop.jpg"));
		Files.write(fresh.toPath(), new byte[]{ 1 });
		Set<String> temps = new HashSet<>(Set.of(fresh.getAbsolutePath()));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(tmp.getRoot(), System.currentTimeMillis(),
			new HashSet<>(), file -> false, temps);

		assertEquals(0, deleted);
		assertTrue("fresh journaled temp must survive", fresh.exists());
		assertTrue("its entry must be retained for the launch that finds it stale",
			temps.contains(fresh.getAbsolutePath()));
	}

	@Test
	public void sweepRetainsJournaledRecoveryWhenLastSaveFolderDiffers() throws IOException
	{
		// THE folder-change regression: recovery kept + journaled in folder A, user later saves in folder B,
		// startup sweeps with B as the last-save folder. The A entry must survive with its file untouched — a
		// journal resolved against the swept folder would drop A's entry as "vanished", and the next sweep of
		// A would then delete the recovery its dialog promised was kept. Promotion is refused (file -> false)
		// so retention is pinned independently of the promote pass.
		long now = System.currentTimeMillis();
		File folderA = tmp.newFolder("a");
		File folderB = tmp.newFolder("b");
		File keptA = new File(folderA, SaveTempFiles.tempName("crop.jpg"));
		File staleTempB = new File(folderB, SaveTempFiles.tempName("other.jpg"));
		Files.write(keptA.toPath(), new byte[]{ 1 });
		Files.write(staleTempB.toPath(), new byte[]{ 2 });
		backdate(keptA, now);
		backdate(staleTempB, now);
		Set<String> journal = new HashSet<>(Set.of(keptA.getAbsolutePath()));

		int deleted = SaveTempFiles.sweepStaleTempArtifacts(folderB, now, journal, file -> false,
			new HashSet<>());

		assertEquals("B's unjournaled stale temp is the only deletion", 1, deleted);
		assertFalse("the last-save folder's delete pass must still run", staleTempB.exists());
		assertTrue("the journaled A recovery must survive a B-folder sweep", keptA.exists());
		assertTrue("the A entry must be retained, not vanish-dropped against folder B",
			journal.contains(keptA.getAbsolutePath()));
	}

	@Test
	public void visibleNameOfExtractsTrailingVisibleName()
	{
		// The round-trip contract with tempName: the sweep's promotion path derives the "(N)" candidate from
		// this parse, so a drift between builder and parser strands journaled recoveries un-promotable.
		Optional<String> visible = SaveTempFiles.visibleNameOf(SaveTempFiles.tempName("crop (3).jpg"));
		assertTrue("built temp name must parse", visible.isPresent());
		assertEquals("crop (3).jpg", visible.orElseThrow());
		// Dashes inside the visible name survive — only the first separator after the nanos run splits.
		assertEquals("my-photo.jpg",
			SaveTempFiles.visibleNameOf(".cropcenter-tmp-123-my-photo.jpg").orElseThrow());
	}

	@Test
	public void visibleNameOfRejectsUnparseableShapes()
	{
		// Legacy suffix temps carry the name in an ambiguous position; user files and malformed names carry
		// none. All must return empty so the promotion path fails safe (file kept, journal entry retained)
		// instead of renaming to a garbage-derived name.
		assertTrue("legacy suffix shape carries no parseable name",
			SaveTempFiles.visibleNameOf(".crop.jpg.cropcenter-tmp-123").isEmpty());
		assertTrue("null must be tolerated", SaveTempFiles.visibleNameOf(null).isEmpty());
		assertTrue("user files never parse", SaveTempFiles.visibleNameOf("crop.jpg").isEmpty());
		assertTrue("non-digit run between marker and separator must reject",
			SaveTempFiles.visibleNameOf(".cropcenter-tmp-abc-crop.jpg").isEmpty());
		assertTrue("marker with empty trailing name must reject",
			SaveTempFiles.visibleNameOf(".cropcenter-tmp-123-").isEmpty());
	}

	private static void backdate(File file, long nowMs)
	{
		assertTrue("fixture needs a backdated mtime",
			file.setLastModified(nowMs - SaveTempFiles.STALE_TEMP_MIN_AGE_MS - 60_000L));
	}
}
