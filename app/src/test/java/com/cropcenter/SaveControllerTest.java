package com.cropcenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;

import com.cropcenter.model.CropState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Tests for SaveController's auto-rename pattern detection. The user-facing flow this gates: SAF returned a different
 * name than the user typed (because the framework auto-renamed on collision); the controller infers the base name and
 * then offers Replace / Keep / Cancel against the colliding original. A false-positive would put a Replace dialog on a
 * file the user really meant to type as "foo (1).jpg" — and a false-negative skips the dialog and silently saves at the
 * suffixed name. Pin both directions of the pattern match.
 *
 * Also pins abortIfSourceChanged, the identity gate binding every bg-hop save continuation (onOverwriteProbed,
 * dispatchCrashSafeWrite, the overwrite-confirm Replace recheck) to the source image the save was initiated for. A
 * regression that stops aborting exports the image a Share/View load installed mid-probe to the PREVIOUS image's
 * destination with no confirmation — the wrong-image-to-old-destination data-loss bug; a regression that aborts on an
 * unchanged reference kills every save and orphans the crash-safe placeholder its abort action would have deleted.
 *
 * Also pins classifyOverwrite, the pure decision core of probeWasOverwrite. That truth table is data-loss-adjacent in
 * both directions: a false negative silently overwrites user content without the Replace dialog, a false positive
 * throws a Replace dialog at every fresh save.
 *
 * Also pins classifySiblingCollision, the pure decision core of Case B's sibling probe: the filesystem answer runs
 * first and the SAF probes are never consulted for a path-resolved base. The regression direction that matters:
 * getDisplayName's path-first shortcut string-parses path-addressable URIs with no existence check, so a
 * presence-first probe order offers Replace — and can end in a "Replaced X" toast — for a base file that never
 * existed when the user intentionally typed an "X (N).ext" name in the picker.
 *
 * Also pins extensionMismatch, the legacy-SAF extension-change reject predicate: a regression writes one format's
 * bytes into a document whose name and MIME promise another, or rejects every legitimate extensionless save.
 *
 * Also pins defaultSaveStem, the save-field pre-fill derivation with its reserved-namespace de-reservation: a
 * regression pre-fills a rescued recovery's temp-shaped filename verbatim, which the validator's reserved-namespace
 * check can only reject — leaving the user typing a fresh name from scratch every time.
 *
 * Also pins rejectsProviderDisplayName, the legacy-SAF display-name guard: a regression lets a provider-returned
 * name with path separators, traversal segments, or a reserved temp-namespace shape flow verbatim into the
 * crash-safe write path's filesystem sinks (temp naming, sibling placeholder, new File(parent, name)).
 *
 * Also pins the in-flight temp journal seams journalInflightTemp / unjournalInflightTemp via their
 * SharedPreferences overloads (InMemorySharedPreferences): the at-creation absolute-path entry, its removal on
 * in-process disposal, the last-save-folder fallback, and the never-save-fatal degradation when no folder resolves
 * or the commit fails — the journal is what lets the startup sweep reclaim a hard-kill-stranded temp from a folder
 * the last-save delete pass would never visit. The journal's cross-instance serialization is pinned by a two-thread
 * lockstep harness: an Activity recreation runs the new instance's startup sweep concurrently with the old
 * instance's in-flight save, and an unsynchronized read-modify-write would silently erase a freshly journaled
 * entry. commitSweptJournal — the sweep's remove-only merge seam — is pinned separately: the sweep's filesystem
 * passes run outside the journal lock on snapshot copies, and the merge back must drop only the entries the sweep
 * resolved, never an entry a save journaled concurrently.
 *
 * Also pins runPostedSaveContinuation, the guard every bg-hop continuation posted to the main looper runs through:
 * a regression that drops the destroyed-host abort or the rethrow absorption turns a back-press / dark-mode /
 * multi-window change landing mid-save into an uncaught RejectedExecutionException on the main looper — a full app
 * crash.
 */
public final class SaveControllerTest
{
	/**
	 * Minimal EditorHost fake for the runPostedSaveContinuation seam. Only isDestroyed is consulted; every
	 * other surface throws so an accidental code-path drift surfaces instead of silently passing — the same
	 * convention as EditorHostFinishBusyTest's fake and RecordingImageLoadHost.
	 */
	private static final class DestroyableHost implements EditorHost
	{
		boolean destroyed;

		// Stub override returning null — the tests never read the result. SuppressWarnings keeps the
		// generic-method override quiet even though `return null` doesn't strictly require it.
		@Override
		@SuppressWarnings("unchecked")
		public <T extends View> T findViewById(int id) { return null; }

		@Override
		public Activity getActivity() { throw new UnsupportedOperationException(); }

		@Override
		public AtomicBoolean getBusy() { throw new UnsupportedOperationException(); }

		@Override
		public CropState getState() { throw new UnsupportedOperationException(); }

		@Override
		public void hideProgress() { throw new UnsupportedOperationException(); }

		@Override
		public boolean isDestroyed() { return destroyed; }

		@Override
		public AlertDialog registerTransientDialog(AlertDialog newDialog)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public void runInBackground(Runnable task) { throw new UnsupportedOperationException(); }

		@Override
		public void runOnUiThread(Runnable task) { throw new UnsupportedOperationException(); }

		@Override
		public void setBusyUi(boolean busy) { throw new UnsupportedOperationException(); }

		@Override
		public void showBusyToast() { throw new UnsupportedOperationException(); }

		@Override
		public void showProgress(String msg) { throw new UnsupportedOperationException(); }

		@Override
		public void toastIfAlive(String msg, int length) { throw new UnsupportedOperationException(); }
	}

	// Display-name stub for classifySiblingCollision rows where the contract REQUIRES the probe not to run — a
	// path-resolved base already has the authoritative filesystem answer, and getDisplayName's path-first
	// shortcut string-parses path-addressable URIs with no existence check, so consulting it would confirm a
	// phantom base.
	private static final BooleanSupplier MUST_NOT_CONSULT_SIBLING_NAME = () ->
	{
		throw new AssertionError("display-name probe must not be consulted for this input");
	};

	// Stream-probe stub for classifyOverwrite rows where the contract REQUIRES the probe not to run — a provider
	// that reported an explicit size has already answered, and opening a stream on it would reintroduce the
	// Replace-dialog-on-every-new-save false positive.
	private static final BooleanSupplier MUST_NOT_CONSULT_STREAM = () ->
	{
		throw new AssertionError("content-stream probe must not be consulted for this input");
	};

	// Size stub for classifySiblingCollision rows where the contract REQUIRES the probe not to run — either the
	// filesystem already answered (path-resolved base) or the display-name probe found nothing to size.
	private static final LongSupplier MUST_NOT_CONSULT_SIBLING_SIZE = () ->
	{
		throw new AssertionError("provider-size probe must not be consulted for this input");
	};

	// Abort-action stub for abortIfSourceChanged rows where the contract REQUIRES the abort not to run — an
	// unchanged source reference must dispatch normally; running the abort would clear savePending and delete the
	// crash-safe placeholder out from under the live dispatch.
	private static final Runnable MUST_NOT_ABORT = () ->
	{
		throw new AssertionError("abort action must not run for an unchanged source reference");
	};

	// Destroyed-cleanup stub for runPostedSaveContinuation rows on a LIVE host — running the destroyed-host
	// cleanup there would cancel a save that must dispatch.
	private static final Runnable MUST_NOT_CLEAN_UP = () ->
	{
		throw new AssertionError("destroyed-host cleanup must not run on a live host");
	};

	// Continuation-body stub for runPostedSaveContinuation rows on a DESTROYED host — dispatching there would
	// rethrow RejectedExecutionException from the shut-down executor into the main looper.
	private static final Runnable MUST_NOT_DISPATCH = () ->
	{
		throw new AssertionError("posted continuation body must not run on a destroyed host");
	};

	// SaveController's persisted in-flight temp journal key, pinned as a literal deliberately: entries under it
	// survive app updates, so renaming the key in production silently orphans every already-journaled temp path.
	private static final String INFLIGHT_TEMP_KEY = "inflight_temp_paths";
	// Per-thread write count for the journal lockstep harness — high enough that a dropped journal lock loses
	// entries with near-certainty, small enough to keep the barrier handshakes cheap.
	private static final int STRESS_JOURNAL_WRITES = 200;

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void abortIfSourceChangedAbortsOnNullTransitions()
	{
		// One-sided null is a changed reference in both directions: initiation with an image that a bg
		// reset() later drops (live null), and the degenerate inverse. Either way the continuation must not
		// dispatch — the destination belongs to an image that is no longer loaded.
		int[] abortRuns = { 0 };
		assertTrue("live source dropped mid-hop must abort",
			SaveController.abortIfSourceChanged(new Object(), null, () -> abortRuns[0]++));
		assertTrue("source appearing mid-hop must abort",
			SaveController.abortIfSourceChanged(null, new Object(), () -> abortRuns[0]++));
		assertEquals("each abort must run the abort action exactly once", 2, abortRuns[0]);
	}

	@Test
	public void abortIfSourceChangedAbortsWhenReferenceDiffers()
	{
		// A Share/View load completing during a bg probe installs a NEW source bitmap; a continuation still
		// holding the initiation-time reference would export the new image to the OLD image's destination.
		// Distinct references → the abort action runs exactly once and the continuation must stop.
		int[] abortRuns = { 0 };
		assertTrue("distinct references must abort",
			SaveController.abortIfSourceChanged(new Object(), new Object(), () -> abortRuns[0]++));
		assertEquals("abort action must run exactly once", 1, abortRuns[0]);
	}

	@Test
	public void abortIfSourceChangedProceedsWhenReferenceUnchanged()
	{
		// Same reference → the save proceeds. MUST_NOT_ABORT pins that the abort action is never invoked on
		// the proceed path — running it would clear savePending and delete the placeholder out from under
		// the dispatch that is about to use it.
		Object sourceImage = new Object();
		assertFalse("identical reference must proceed",
			SaveController.abortIfSourceChanged(sourceImage, sourceImage, MUST_NOT_ABORT));
	}

	@Test
	public void abortIfSourceChangedUsesReferenceIdentityNotEquals()
	{
		// equals-equal but distinct instances must still abort — the binding contract is reference identity
		// (the SAME Bitmap instance survives from initiation to continuation), mirroring
		// restorePriorSaveSettings' snapshot check. An equals-based check would treat any two equal-content
		// values as "unchanged" and let the continuation dispatch against a replaced image.
		int[] abortRuns = { 0 };
		assertTrue("equal-but-distinct references must abort",
			SaveController.abortIfSourceChanged(List.of("img"), List.of("img"), () -> abortRuns[0]++));
		assertEquals("abort action must run exactly once", 1, abortRuns[0]);
	}

	@Test
	public void autoRenameBaseNameDetectsClassicalCollision()
	{
		// Most common case: SAF auto-renamed by appending " (1)" before the extension.
		assertEquals("crop.jpg", SaveController.autoRenameBaseName("crop (1).jpg").orElseThrow());
	}

	@Test
	public void autoRenameBaseNameDetectsMultiDigitSuffix()
	{
		// SAF can suffix arbitrary digit counts when prior collisions exist.
		assertEquals("photo.png", SaveController.autoRenameBaseName("photo (123).png").orElseThrow());
	}

	@Test
	public void autoRenameBaseNameDetectsSuffixWithoutSpace()
	{
		// Some legacy providers omit the space before the open paren.
		assertEquals("crop.jpg", SaveController.autoRenameBaseName("crop(1).jpg").orElseThrow());
	}

	@Test
	public void autoRenameBaseNameHandlesStemWithDotsAndSpaces()
	{
		// Multi-dot stem like "image.v2.final.jpg" → "image.v2.final (1).jpg" suffixed → infer the same with
		// "(1)" stripped. lastIndexOf('.') is the canonical extension splitter — pin behaviour against a stem
		// that itself contains dots.
		assertEquals("image.v2.final.jpg",
			SaveController.autoRenameBaseName("image.v2.final (1).jpg").orElseThrow());
	}

	@Test
	public void autoRenameBaseNamePreservesBaseExtension()
	{
		// Base extension is whatever the suffixed name carries, regardless of case.
		assertEquals("snapshot.PNG", SaveController.autoRenameBaseName("snapshot (5).PNG").orElseThrow());
	}

	@Test
	public void autoRenameBaseNameReturnsEmptyForCloseWithoutOpen()
	{
		// Reversed bracket — must not crash, must not match.
		assertTrue(SaveController.autoRenameBaseName("crop 1).jpg").isEmpty());
	}

	@Test
	public void autoRenameBaseNameReturnsEmptyForEmptyParens()
	{
		// "()" has no digits — bare parens, not an auto-rename suffix.
		assertTrue(SaveController.autoRenameBaseName("crop ().jpg").isEmpty());
	}

	@Test
	public void autoRenameBaseNameReturnsEmptyForExtensionOnly()
	{
		// Leading-dot file (no stem) — SAF wouldn't generate this from a typed filename.
		assertTrue(SaveController.autoRenameBaseName(".jpg").isEmpty());
	}

	@Test
	public void autoRenameBaseNameReturnsEmptyForNoExtension()
	{
		// No dot at all — can't be the SAF auto-rename pattern.
		assertTrue(SaveController.autoRenameBaseName("crop (1)").isEmpty());
	}

	@Test
	public void autoRenameBaseNameReturnsEmptyForNoSuffixPattern()
	{
		// Plain filename, no parenthesised suffix.
		assertTrue(SaveController.autoRenameBaseName("crop.jpg").isEmpty());
	}

	@Test
	public void autoRenameBaseNameReturnsEmptyForNonNumericSuffix()
	{
		// "(copy)" is not the SAF auto-rename pattern; user typed it as part of the filename.
		assertTrue(SaveController.autoRenameBaseName("crop (copy).jpg").isEmpty());
	}

	@Test
	public void autoRenameBaseNameReturnsEmptyForNullInput()
	{
		assertTrue(SaveController.autoRenameBaseName(null).isEmpty());
	}

	@Test
	public void autoRenameBaseNameReturnsEmptyForOpenParenWithoutClose()
	{
		// Malformed bracket — must not crash, must not match.
		assertTrue(SaveController.autoRenameBaseName("crop (1.jpg").isEmpty());
	}

	@Test
	public void autoRenameBaseNameReturnsEmptyForSuffixAlone()
	{
		// "(1)" with no leading stem — empty base after stripping the suffix.
		assertTrue(SaveController.autoRenameBaseName("(1).jpg").isEmpty());
	}

	@Test
	public void autoRenameBaseNameReturnsEmptyForWhitespaceOnlyStem()
	{
		// Whitespace-only stem before "(N)": baseStem.stripTrailing() collapses to empty, so no real base name
		// (empty result). Unlike the "(1).jpg" suffix-alone case the leading chars exist but are all spaces —
		// exercises the stripTrailing-then-isEmpty guard rather than the openParen<=0 guard.
		assertTrue(SaveController.autoRenameBaseName("   (1).jpg").isEmpty());
	}

	@Test
	public void autoRenameBaseNameStripsTrailingWhitespaceFromBase()
	{
		// Multiple spaces between stem and "(N)" — exercises stripTrailing distinctly from the canonical
		// single-space test (autoRenameBaseNameDetectsClassicalCollision). A regression that switched
		// stripTrailing for a single-char trim would still pass the canonical test but fail this one.
		assertEquals("crop.jpg", SaveController.autoRenameBaseName("crop   (1).jpg").orElseThrow());
	}

	@Test
	public void classifyOverwriteAllSignalsNegativeIsNotOverwrite()
	{
		// No SIZE, stream refuses, no path-resolved content — nothing confirms prior content, so the save
		// proceeds without a Replace dialog (the preserving write downstream is the safety net).
		assertFalse(SaveController.classifyOverwrite(-1, () -> false, false));
	}

	@Test
	public void classifyOverwriteExplicitZeroSkipsStreamProbe()
	{
		// Provider reported an explicit 0 and the filesystem doesn't contradict it: a fresh SAF picker
		// placeholder, NOT an overwrite. The throwing supplier pins that the stream probe is never consulted —
		// probing a 0-byte doc would re-answer a question the provider already answered.
		assertFalse(SaveController.classifyOverwrite(0, MUST_NOT_CONSULT_STREAM, false));
	}

	@Test
	public void classifyOverwriteFileContentOverridesExplicitZeroSize()
	{
		// Provider metadata says 0 bytes but the path-resolved file holds real content — the filesystem is
		// authoritative (Samsung MediaStore-backed pickers under-report SIZE). Treating the explicit 0 as final
		// would silently overwrite the real bytes without the Replace dialog.
		assertTrue(SaveController.classifyOverwrite(0, MUST_NOT_CONSULT_STREAM, true));
	}

	@Test
	public void classifyOverwriteNoSizeFallsBackToStreamProbe()
	{
		// Provider omits SIZE entirely — one byte served over openInputStream confirms a real file.
		assertTrue(SaveController.classifyOverwrite(-1, () -> true, false));
	}

	@Test
	public void classifyOverwriteNoSizeStreamRefusedFileContentStillConfirms()
	{
		// Provider exposes neither SIZE nor a readable stream but the path-resolved file has bytes — the third
		// probe alone must confirm, or the Samsung no-metadata shape silently overwrites.
		assertTrue(SaveController.classifyOverwrite(-1, () -> false, true));
	}

	@Test
	public void classifyOverwritePositiveSizeConfirmsAlone()
	{
		// Provider-reported size is the strongest signal; neither fallback probe is needed (the throwing
		// supplier pins that the stream is not consulted when SIZE already confirmed).
		assertTrue(SaveController.classifyOverwrite(1, MUST_NOT_CONSULT_STREAM, false));
	}

	@Test
	public void classifySiblingCollisionAbsentPathResolvedBaseIsNotCollision()
	{
		// The phantom-base regression pin. fileFromSafUri string-parses a path-addressable sibling URI
		// whether or not the file exists, so a user who intentionally typed "vacation (2).jpg" with no
		// colliding "vacation.jpg" hands this seam a File that exists() denies. The throwing stubs pin the
		// load-bearing order: consulting the display-name probe here (its path-first shortcut parses the
		// same nonexistent path) would confirm the phantom and offer Replace on a file that never existed.
		File phantom = new File(tmp.getRoot(), "vacation.jpg");
		assertFalse("nonexistent path-resolved base must not be a collision",
			SaveController.classifySiblingCollision(phantom,
				MUST_NOT_CONSULT_SIBLING_NAME, MUST_NOT_CONSULT_SIBLING_SIZE));
	}

	@Test
	public void classifySiblingCollisionEmptyPathResolvedBaseIsNotCollision() throws IOException
	{
		// A 0-byte base is an interrupted prior save's placeholder — no content the user needs to preserve,
		// so no Replace dialog; the SAF probes stay out of it.
		assertFalse("0-byte path-resolved base must not be a collision",
			SaveController.classifySiblingCollision(tmp.newFile("vacation.jpg"),
				MUST_NOT_CONSULT_SIBLING_NAME, MUST_NOT_CONSULT_SIBLING_SIZE));
	}

	@Test
	public void classifySiblingCollisionNoPathNamePresentRealSizeIsCollision()
	{
		// Cloud / opaque provider: no path resolves, the ContentResolver query names the document (a real
		// existence proof), and SIZE reports content — the Replace dialog is warranted.
		assertTrue(SaveController.classifySiblingCollision(null, () -> true, () -> 1));
	}

	@Test
	public void classifySiblingCollisionNoPathNamePresentUnknownSizeIsCollision()
	{
		// -1: the provider doesn't expose OpenableColumns.SIZE. Existence is confirmed and there is no
		// negative signal, so unknown size still counts as collision.
		assertTrue(SaveController.classifySiblingCollision(null, () -> true, () -> -1));
	}

	@Test
	public void classifySiblingCollisionNoPathNamePresentZeroSizeIsPlaceholder()
	{
		// Explicit 0 from the provider mirrors the 0-byte filesystem case: placeholder, not content.
		assertFalse(SaveController.classifySiblingCollision(null, () -> true, () -> 0));
	}

	@Test
	public void classifySiblingCollisionNoProbeSignalIsNotCollision()
	{
		// Every probe inconclusive: prefer the false-negative outcome (save under the SAF-assigned "(N)"
		// name, no data loss) over a false-positive Replace dialog; sizing an unnamed document is
		// meaningless, so the size probe must not run.
		assertFalse(SaveController.classifySiblingCollision(null, () -> false, MUST_NOT_CONSULT_SIBLING_SIZE));
	}

	@Test
	public void classifySiblingCollisionRealContentPathResolvedBaseConfirmsAlone() throws IOException
	{
		// Filesystem-authoritative positive: a path-resolved base with real bytes is a collision no matter
		// what the SAF probes would say.
		File base = tmp.newFile("vacation.jpg");
		Files.write(base.toPath(), new byte[] { 1 });
		assertTrue(SaveController.classifySiblingCollision(base,
			MUST_NOT_CONSULT_SIBLING_NAME, MUST_NOT_CONSULT_SIBLING_SIZE));
	}

	@Test
	public void commitSweptJournalFailedCommitDegradesWithoutThrowing()
	{
		// A failing prefs store must never turn the sweep into a crash: the merge result is dropped with a
		// warn, the entries stay, and the next launch's sweep re-resolves them.
		InMemorySharedPreferences prefs = new InMemorySharedPreferences();
		assertTrue(prefs.edit().putStringSet(INFLIGHT_TEMP_KEY, Set.of("/save/a", "/save/b")).commit());
		prefs.setFailCommits(true);
		SaveController.commitSweptJournal(prefs, INFLIGHT_TEMP_KEY,
			Set.of("/save/a", "/save/b"), Set.of("/save/b"), "in-flight temp");
		prefs.setFailCommits(false);
		assertEquals("failed commit must leave the stored journal untouched",
			Set.of("/save/a", "/save/b"), prefs.getStringSet(INFLIGHT_TEMP_KEY, Set.of()));
	}

	@Test
	public void commitSweptJournalPreservesConcurrentAdditions()
	{
		// The sweep's filesystem passes run OUTSIDE the journal lock on snapshot copies; an in-flight save
		// (the old Activity instance's executor, during recreation) journals a fresh temp meanwhile. The
		// merge must be remove-only: final = live minus entries-the-sweep-resolved. Committing the swept
		// copy wholesale would silently erase the concurrent entry — un-journaling a live temp past the
		// hard-kill backstop the journal exists to provide.
		InMemorySharedPreferences prefs = new InMemorySharedPreferences();
		Set<String> preSweep = Set.of("/save/resolved.tmp", "/save/retained.tmp");
		// Live set = the snapshot plus a concurrently journaled entry the sweep never saw.
		assertTrue(prefs.edit().putStringSet(INFLIGHT_TEMP_KEY,
			Set.of("/save/resolved.tmp", "/save/retained.tmp", "/save/concurrent.tmp")).commit());
		SaveController.commitSweptJournal(prefs, INFLIGHT_TEMP_KEY, preSweep,
			Set.of("/save/retained.tmp"), "in-flight temp");
		assertEquals("resolved entry dropped; retained + concurrent entries survive",
			Set.of("/save/retained.tmp", "/save/concurrent.tmp"),
			prefs.getStringSet(INFLIGHT_TEMP_KEY, Set.of()));
	}

	@Test
	public void commitSweptJournalWithNothingResolvedKeepsLiveSetIntact()
	{
		// Sweep resolved nothing (every entry fresh or retained): the merge is a no-op, and the live set —
		// including an entry journaled after the snapshot — must come through unchanged.
		InMemorySharedPreferences prefs = new InMemorySharedPreferences();
		assertTrue(prefs.edit().putStringSet(INFLIGHT_TEMP_KEY,
			Set.of("/save/retained.tmp", "/save/concurrent.tmp")).commit());
		SaveController.commitSweptJournal(prefs, INFLIGHT_TEMP_KEY, Set.of("/save/retained.tmp"),
			Set.of("/save/retained.tmp"), "in-flight temp");
		assertEquals(Set.of("/save/retained.tmp", "/save/concurrent.tmp"),
			prefs.getStringSet(INFLIGHT_TEMP_KEY, Set.of()));
	}

	@Test
	public void defaultSaveStemDereservesTempShapedOriginalFilename()
	{
		// The save-field pre-fill de-reservation: a rescued hidden recovery re-shared into the app carries
		// its reserved temp-shaped filename, and pre-filling it verbatim would only ever hit the
		// reserved-namespace "Invalid filename" rejection. The current marker-prefix shape strips to its
		// embedded visible name; every unparseable reserved shape falls back to the default stem.
		assertEquals("crop (3)", SaveController.defaultSaveStem(".cropcenter-tmp-123-crop (3).jpg"));
		assertEquals("legacy suffix temps carry no parseable name — default stem",
			"crop", SaveController.defaultSaveStem(".photo.jpg.cropcenter-tmp-123"));
		assertEquals("one-nonce-short marker names carry no parseable name — default stem",
			"crop", SaveController.defaultSaveStem(".cropcenter-tmp-photo.jpg"));
		assertEquals("marker substring mid-name is reserved but unparseable — default stem",
			"crop", SaveController.defaultSaveStem("notes.cropcenter-tmp-1.jpg"));
	}

	@Test
	public void defaultSaveStemStripsExtensionAndFallsBack()
	{
		// The pre-existing stem contract around the de-reservation: ordinary names lose their recognised
		// extension, absent / empty names fall back to "crop".
		assertEquals("vacation", SaveController.defaultSaveStem("vacation.jpg"));
		assertEquals("noext", SaveController.defaultSaveStem("noext"));
		assertEquals("crop", SaveController.defaultSaveStem(null));
		assertEquals("crop", SaveController.defaultSaveStem(""));
	}

	@Test
	public void extensionMismatchRejectsCrossFormatRenameAndPassesExtensionless()
	{
		// Fail-closed MIME/content guard on the legacy SAF flow. Reject side: a cross-format rename writes
		// JPEG bytes into a PNG-promising document (misclassified in Gallery / file managers), and an
		// unknown extension (.webp — Format lookup returns null but the extension is present) promises an
		// encoding the encoder can't produce.
		assertTrue(".jpg -> .png must reject", SaveController.extensionMismatch("crop.jpg", "crop.png"));
		assertTrue(".png -> .jpg must reject", SaveController.extensionMismatch("crop.png", "crop.jpg"));
		assertTrue(".jpg -> .webp (unknown Format, extension present) must reject",
			SaveController.extensionMismatch("crop.jpg", "crop.webp"));
		// Pass side: rejecting these would block every legitimate extensionless / ambiguous save.
		assertFalse("same format must pass", SaveController.extensionMismatch("crop.jpg", "crop.jpg"));
		assertFalse(".jpeg alias maps to the same Format",
			SaveController.extensionMismatch("crop.jpg", "crop.jpeg"));
		assertFalse("extensionless chosen is ambiguous — let through, SAF MIME still says image/jpeg",
			SaveController.extensionMismatch("crop.jpg", "crop"));
		assertFalse("chosen \".png\" has lastIndexOf('.') == 0 — treated extensionless, let through",
			SaveController.extensionMismatch("crop.jpg", ".png"));
		assertFalse("extensionless requested has no promised Format — nothing to mismatch",
			SaveController.extensionMismatch("crop", "crop.png"));
		assertFalse("null chosen must not reject (or NPE)", SaveController.extensionMismatch("crop.jpg", null));
		assertFalse("null requested must not reject (or NPE)",
			SaveController.extensionMismatch(null, "crop.png"));
	}

	@Test
	public void journalInflightTempFailedCommitReportsFalseWithoutThrowing() throws IOException
	{
		// A failing prefs store (disk-full commit) must degrade to an unjournaled-but-running save: the
		// writer logs and continues — the temp is still cleaned in-process on every non-kill path, so the
		// journal is purely the hard-kill backstop and its failure must never become a save failure.
		InMemorySharedPreferences prefs = new InMemorySharedPreferences();
		prefs.setFailCommits(true);
		File folder = tmp.newFolder("save");
		assertFalse("failed commit must report no durable entry",
			SaveController.journalInflightTemp(prefs, folder, SaveTempFiles.tempName("crop.jpg")));
		prefs.setFailCommits(false);
		assertTrue("the dropped edit must not have landed",
			prefs.getStringSet(INFLIGHT_TEMP_KEY, Set.of()).isEmpty());
	}

	@Test
	public void journalInflightTempFallsBackToLastSaveFolder() throws IOException
	{
		// Opaque-provider path: the writer can't resolve the temp's folder, so the persisted last-save
		// folder anchors the entry — mirroring journalKeptRecovery, and self-healing at the next sweep when
		// the guess was wrong (file gone from its journaled path — entry dropped).
		InMemorySharedPreferences prefs = new InMemorySharedPreferences();
		File lastSave = tmp.newFolder("last-save");
		assertTrue(prefs.edit().putString("last_save_folder", lastSave.getAbsolutePath()).commit());
		String tempName = SaveTempFiles.tempName("crop.jpg");
		assertTrue("last-save fallback must journal",
			SaveController.journalInflightTemp(prefs, null, tempName));
		assertTrue("entry must anchor to the persisted last-save folder",
			prefs.getStringSet(INFLIGHT_TEMP_KEY, Set.of())
				.contains(new File(lastSave, tempName).getAbsolutePath()));
	}

	@Test
	public void journalInflightTempRecordsAbsolutePathDurably() throws IOException
	{
		// The at-creation journal contract: the temp's ABSOLUTE path lands under the in-flight temp key
		// before the writer creates the file, so a hard kill at any later point leaves the startup sweep an
		// entry that reaches the temp's own folder — not just the last-save folder. Re-journaling the same
		// path stays durable (idempotent true).
		InMemorySharedPreferences prefs = new InMemorySharedPreferences();
		File folder = tmp.newFolder("save");
		String tempName = SaveTempFiles.tempName("crop.jpg");
		assertTrue("journal must report a durable entry",
			SaveController.journalInflightTemp(prefs, folder, tempName));
		assertTrue("re-journaling the same path is a durable no-op",
			SaveController.journalInflightTemp(prefs, folder, tempName));
		assertEquals(Set.of(new File(folder, tempName).getAbsolutePath()),
			prefs.getStringSet(INFLIGHT_TEMP_KEY, Set.of()));
	}

	@Test
	public void journalInflightTempWithNoResolvableFolderReportsFalse()
	{
		// No caller-resolved folder AND no persisted last-save folder: nothing anchors an absolute path, so
		// the temp goes unjournaled — reported false so the writer's log line is honest, and never thrown.
		InMemorySharedPreferences prefs = new InMemorySharedPreferences();
		assertFalse(SaveController.journalInflightTemp(prefs, null, SaveTempFiles.tempName("crop.jpg")));
		assertTrue(prefs.getStringSet(INFLIGHT_TEMP_KEY, Set.of()).isEmpty());
	}

	@Test
	public void journalMutationsFromTwoThreadsNeverLoseEntries() throws Exception
	{
		// The cross-instance lost-update topology (Activity recreation): an in-flight save on the OLD
		// instance's executor and the NEW instance's startup sweep read-modify-write the same journal
		// StringSet from two threads. Unsynchronized, one side commits a stale copy and silently erases
		// the other's fresh entry — un-protecting a kept recovery a dialog promised was safe, or
		// un-journaling an in-flight temp past the hard-kill backstop. Every journal
		// read-modify-write-commit serializes on SaveController's static journal lock; this harness
		// drives the production seam from two threads with a per-iteration barrier forcing the RMW
		// windows to overlap, and pins that every entry lands.
		InMemorySharedPreferences prefs = new InMemorySharedPreferences();
		File folder = tmp.newFolder("journal-race");
		CyclicBarrier barrier = new CyclicBarrier(2);
		Thread second = new Thread(() -> journalTempsLockstep(prefs, folder, barrier, "second-"));
		second.start();
		journalTempsLockstep(prefs, folder, barrier, "first-");
		second.join();
		assertEquals("no journaled entry may be lost to a concurrent read-modify-write",
			2 * STRESS_JOURNAL_WRITES, prefs.getStringSet(INFLIGHT_TEMP_KEY, Set.of()).size());
	}

	@Test
	public void nextAvailableNumberedNameHandlesEmptyStemLeadingDot() throws IOException
	{
		// Degenerate ".jpg" (leading dot, no stem): autoRenameBaseName returns empty, so the loop runs with
		// ".jpg" as the stem and an EMPTY ext — lastIndexOf('.')==0 fails the dot>0 guard, so it's NOT split as
		// stem=""/ext=".jpg". Suggestion is ".jpg (1)" — a leading-dot name is an extensionless stem.
		File dir = tmp.newFolder();
		assertEquals(".jpg (1)", SaveController.nextAvailableNumberedName(dir, ".jpg").orElseThrow());
	}

	@Test
	public void nextAvailableNumberedNameHandlesExtensionlessOriginal() throws IOException
	{
		// No extension in the original — suggestion appends "(N)" with no extension.
		File dir = tmp.newFolder();
		new File(dir, "README").createNewFile();
		assertEquals("README (1)", SaveController.nextAvailableNumberedName(dir, "README").orElseThrow());
	}

	@Test
	public void nextAvailableNumberedNameReturnsCandidateWhenNothingExists() throws IOException
	{
		// Empty folder — suggestion is (1) even though the "original" doesn't actually collide. The caller
		// (showInAppRenameDialog) only invokes this AFTER detecting a collision, so the "no collision actually
		// exists" case shouldn't fire in production — but the method's contract is "first available (N)", which
		// is (1) in an empty dir.
		File dir = tmp.newFolder();
		assertEquals("foo (1).jpg", SaveController.nextAvailableNumberedName(dir, "foo.jpg").orElseThrow());
	}

	@Test
	public void nextAvailableNumberedNameReturnsEmptyWhenAllSuffixesTaken() throws IOException
	{
		// Exhaustion contract: when every probed "(N)" collides, the method returns empty — NOT the colliding
		// original — so showInAppRenameDialog's fallback branch (pre-fill with `original`) is reachable and the
		// Rename → OK → collision cycle can't loop on the auto-suggestion. Probed via the bounded-maxSuffix
		// seam (production passes MAX_RENAME_SUFFIX = 9999; creating 9999 files here would only test the
		// filesystem's patience).
		File dir = tmp.newFolder();
		new File(dir, "foo.jpg").createNewFile();
		new File(dir, "foo (1).jpg").createNewFile();
		new File(dir, "foo (2).jpg").createNewFile();
		assertTrue("all suffixes 1..2 taken must return empty",
			SaveController.nextAvailableNumberedName(dir, "foo.jpg", 2).isEmpty());
		// Same fixture with headroom (maxSuffix=3) confirms the bound — not the fixture — drove the
		// exhaustion result.
		assertEquals("foo (3).jpg", SaveController.nextAvailableNumberedName(dir, "foo.jpg", 3).orElseThrow());
	}

	@Test
	public void nextAvailableNumberedNameSkipsExistingSuffixedFiles() throws IOException
	{
		// foo.jpg AND foo (1).jpg both taken — first available is (2).
		File dir = tmp.newFolder();
		new File(dir, "foo.jpg").createNewFile();
		new File(dir, "foo (1).jpg").createNewFile();
		assertEquals("foo (2).jpg", SaveController.nextAvailableNumberedName(dir, "foo.jpg").orElseThrow());
	}

	@Test
	public void nextAvailableNumberedNameStripsExistingSuffixBeforeProbing() throws IOException
	{
		// Renaming "foo (1).jpg" must suggest "foo (2).jpg" NOT "foo (1) (1).jpg" — the existing "(1)" is
		// stripped by autoRenameBaseName so the loop probes against "foo.jpg" as the stem.
		File dir = tmp.newFolder();
		new File(dir, "foo.jpg").createNewFile();
		new File(dir, "foo (1).jpg").createNewFile();
		assertEquals("foo (2).jpg", SaveController.nextAvailableNumberedName(dir, "foo (1).jpg").orElseThrow());
	}

	@Test
	public void nextAvailableNumberedNameSuggestsFirstSuffixWhenStemIsFree() throws IOException
	{
		// Base case: "foo.jpg" exists, "(1)..(N)" don't — suggestion is "foo (1).jpg".
		File dir = tmp.newFolder();
		new File(dir, "foo.jpg").createNewFile();
		assertEquals("foo (1).jpg", SaveController.nextAvailableNumberedName(dir, "foo.jpg").orElseThrow());
	}

	@Test
	public void rejectsProviderDisplayNameBlocksNamesTheAppWouldNeverAccept()
	{
		// Case C hands the provider-returned name verbatim to the crash-safe write path (temp naming, sibling
		// placeholder, new File(parent, name)), so any name the app's own typed-name validation refuses must
		// abort the save before routing. Path separators and traversal segments are path-injection shapes;
		// reserved temp-namespace names would land the save inside the startup sweep's deletable namespace.
		assertTrue("path separator must reject", SaveController.rejectsProviderDisplayName("a/b.jpg"));
		assertTrue("backslash separator must reject", SaveController.rejectsProviderDisplayName("a\\b.jpg"));
		assertTrue("traversal segment must reject", SaveController.rejectsProviderDisplayName(".."));
		assertTrue("empty name must reject", SaveController.rejectsProviderDisplayName(""));
		assertTrue("reserved temp namespace must reject",
			SaveController.rejectsProviderDisplayName(".cropcenter-tmp-123-crop.jpg"));
		assertTrue("over-length name must reject",
			SaveController.rejectsProviderDisplayName("x".repeat(211) + ".jpg"));
	}

	@Test
	public void rejectsProviderDisplayNamePassesOrdinaryAndAbsentNames()
	{
		// Pass side: ordinary provider names (including SAF "(N)" auto-renames) route on, and a null display
		// name (query failed) is not a rejection — the opaque-provider fallback saves under the
		// already-validated requested name, so rejecting on null would break every opaque-name provider.
		assertFalse("ordinary name must pass", SaveController.rejectsProviderDisplayName("crop.jpg"));
		assertFalse("SAF auto-rename must pass", SaveController.rejectsProviderDisplayName("crop (1).jpg"));
		assertFalse("null (no display name) must pass", SaveController.rejectsProviderDisplayName(null));
	}

	@Test
	public void runPostedSaveContinuationAbortsOnDestroyedHostWithoutRunningBody()
	{
		// A continuation posted from the bg probe hop lands on the main looper AFTER onDestroy when a
		// back-press (or a uiMode / locale / multi-window recreate) arrives mid-hop — runOnUiThread posts
		// unconditionally. The seam must run the caller's cleanup and never the body: the executor is
		// already shut down, so dispatching would rethrow RejectedExecutionException into the main looper
		// and kill the process.
		DestroyableHost host = new DestroyableHost();
		host.destroyed = true;
		int[] cleanupRuns = { 0 };
		SaveController.runPostedSaveContinuation(host, "test", () -> cleanupRuns[0]++, MUST_NOT_DISPATCH);
		assertEquals("destroyed-host cleanup must run exactly once", 1, cleanupRuns[0]);
	}

	@Test
	public void runPostedSaveContinuationAbsorbsDispatchRejectionAfterCleanup()
	{
		// The posted-context conversion of the dispatch-guard rethrow: routeCrashSafeSave and
		// ExportPipeline.exportTo clean up (savePending, placeholder, busy) and rethrow for their
		// synchronous callers — a posted continuation has no caller, so the seam must absorb the rethrow
		// or it escapes the posted runnable and crashes the app. The body models the guard shape: cleanup,
		// then throw. Returning normally from this call IS the no-escape pin.
		DestroyableHost host = new DestroyableHost();
		int[] cleanupRuns = { 0 };
		SaveController.runPostedSaveContinuation(host, "test", MUST_NOT_CLEAN_UP, () ->
		{
			cleanupRuns[0]++;
			throw new RejectedExecutionException("executor shut down after onDestroy");
		});
		assertEquals("the body's own cleanup must have run before the absorbed rethrow", 1, cleanupRuns[0]);
	}

	@Test
	public void runPostedSaveContinuationRunsBodyOnLiveHost()
	{
		// The plain path: live host, body dispatches normally, and the destroyed-host cleanup never runs —
		// running it would cancel a save that must proceed.
		DestroyableHost host = new DestroyableHost();
		int[] bodyRuns = { 0 };
		SaveController.runPostedSaveContinuation(host, "test", MUST_NOT_CLEAN_UP, () -> bodyRuns[0]++);
		assertEquals("body must run exactly once on a live host", 1, bodyRuns[0]);
	}

	@Test
	public void unjournalInflightTempRemovesEntryOnDisposal() throws IOException
	{
		// The disposal half of the round trip: every in-process cleanup / rename that disposes a temp drops
		// its entry, resolved through the same path derivation as the journaling side, so the journal holds
		// only temps a hard kill could actually strand. A repeat removal (or one for a never-journaled name)
		// is a harmless no-op.
		InMemorySharedPreferences prefs = new InMemorySharedPreferences();
		File folder = tmp.newFolder("save");
		String tempName = SaveTempFiles.tempName("crop.jpg");
		assertTrue(SaveController.journalInflightTemp(prefs, folder, tempName));
		SaveController.unjournalInflightTemp(prefs, folder, tempName);
		assertTrue("disposal must remove the journal entry",
			prefs.getStringSet(INFLIGHT_TEMP_KEY, Set.of()).isEmpty());
		SaveController.unjournalInflightTemp(prefs, folder, tempName);
		assertTrue("repeat removal stays a no-op", prefs.getStringSet(INFLIGHT_TEMP_KEY, Set.of()).isEmpty());
	}

	/**
	 * Journal STRESS_JOURNAL_WRITES distinct temp paths through the production seam, meeting the sibling
	 * thread at the barrier before every write so the two threads' read-modify-write windows overlap as
	 * tightly as the scheduler allows.
	 *
	 * @param prefs   shared in-memory journal store both threads mutate
	 * @param folder  resolvable journal folder (keeps resolveJournalPath off the prefs)
	 * @param barrier two-party barrier synchronising each iteration pair
	 * @param prefix  per-thread name prefix keeping the journaled paths distinct
	 */
	private static void journalTempsLockstep(InMemorySharedPreferences prefs, File folder,
		CyclicBarrier barrier, String prefix)
	{
		for (int i = 0; i < STRESS_JOURNAL_WRITES; i++)
		{
			try
			{
				barrier.await();
			}
			catch (Exception e)
			{
				throw new AssertionError("barrier interrupted", e);
			}
			SaveController.journalInflightTemp(prefs, folder, prefix + i + ".jpg");
		}
	}
}
