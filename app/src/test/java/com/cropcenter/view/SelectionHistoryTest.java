package com.cropcenter.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cropcenter.model.SelectionPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for the SelectionHistory undo/redo stack used by CropEditorView. The class is package-private and pure-Java
 * (no Bitmap / View dependencies), so the tests live in the same package and instantiate it directly.
 *
 * The contract being pinned: push() snapshots the *caller's* current list (not a reference) so subsequent caller
 * mutations don't leak into the saved frame; undo()/redo() trade frames between the two stacks; clear() drops both;
 * the depth cap of 50 trims the oldest frame (FIFO) so the most-recent 50 are preserved.
 */
public final class SelectionHistoryTest
{
	private static SelectionPoint pt(float x, float y)
	{
		return new SelectionPoint(x, y);
	}

	@Test
	public void canRedoAndCanUndoStartFalseOnFreshHistory()
	{
		SelectionHistory history = new SelectionHistory();
		assertFalse(history.canUndo());
		assertFalse(history.canRedo());
	}

	@Test
	public void clearDropsBothStacks()
	{
		SelectionHistory history = new SelectionHistory();
		history.push(Arrays.asList(pt(1, 2)));
		history.push(Arrays.asList(pt(3, 4)));
		history.undo(Arrays.asList(pt(5, 6))); // populates redo
		assertTrue(history.canUndo());
		assertTrue(history.canRedo());
		history.clear();
		assertFalse(history.canUndo());
		assertFalse(history.canRedo());
	}

	@Test
	public void depthCapTrimsOldestNotNewest()
	{
		// MAX_DEPTH = 50. After 51 pushes, the oldest frame (push #1) should be gone; pushes 2..51 remain.
		// Verify by undoing 50 times — the last undo should return frame 2's snapshot, NOT frame 1's.
		SelectionHistory history = new SelectionHistory();
		for (int i = 1; i <= 51; i++)
		{
			List<SelectionPoint> frame = new ArrayList<>();
			frame.add(pt(i, i));
			history.push(frame);
		}
		// 50 undos pop the frames in LIFO order. Last undo should yield frame 2 (the oldest survivor).
		List<SelectionPoint> current = new ArrayList<>(); // arbitrary "current" handed back via redo
		List<SelectionPoint> last = null;
		for (int i = 0; i < 50; i++)
		{
			last = history.undo(current);
		}
		assertFalse("undo stack should be empty after 50 pops", history.canUndo());
		assertEquals(1, last.size());
		// Frame 2's content was [pt(2, 2)] — frame 1 was trimmed.
		assertEquals(2f, last.get(0).x(), 0f);
	}

	@Test
	public void pushClearsRedoStack()
	{
		// After undo, redo is non-empty. A subsequent push must clear redo (you can't redo into a divergent
		// timeline — the new branch invalidates the future).
		SelectionHistory history = new SelectionHistory();
		history.push(Arrays.asList(pt(1, 1)));
		history.undo(Arrays.asList(pt(2, 2)));
		assertTrue(history.canRedo());
		history.push(Arrays.asList(pt(3, 3)));
		assertFalse("push must clear redo", history.canRedo());
	}

	@Test
	public void pushSnapshotsCallerListSubsequentMutationsDoNotLeak()
	{
		// The class doc claims push() takes a snapshot. Verify by mutating the source list AFTER the push: the
		// snapshot must be unaffected.
		List<SelectionPoint> live = new ArrayList<>();
		live.add(pt(1, 1));
		SelectionHistory history = new SelectionHistory();
		history.push(live);
		live.clear();
		live.add(pt(99, 99));
		// Undo should return a list with the original [pt(1,1)], not the mutated [pt(99,99)].
		List<SelectionPoint> restored = history.undo(new ArrayList<>());
		assertEquals(1, restored.size());
		assertEquals(1f, restored.get(0).x(), 0f);
		assertEquals(1f, restored.get(0).y(), 0f);
		// Returned snapshot is also a distinct list from the original — modifying it shouldn't affect future
		// behavior.
		assertNotSame(live, restored);
	}

	@Test
	public void redoReturnsNullWhenStackEmpty()
	{
		SelectionHistory history = new SelectionHistory();
		assertNull(history.redo(Arrays.asList(pt(1, 1))));
	}

	@Test
	public void undoPushesCurrentOntoRedo()
	{
		// Round-trip: push frame A, then undo with current = B. Expect: undo returns A; redo returns B.
		SelectionHistory history = new SelectionHistory();
		history.push(Arrays.asList(pt(1, 1)));
		List<SelectionPoint> currentB = Arrays.asList(pt(2, 2));
		List<SelectionPoint> restoredA = history.undo(currentB);
		assertEquals(1f, restoredA.get(0).x(), 0f);
		// Redo should give us back the [pt(2, 2)] state.
		List<SelectionPoint> currentC = Arrays.asList(pt(3, 3));
		List<SelectionPoint> restoredB = history.redo(currentC);
		assertEquals(2f, restoredB.get(0).x(), 0f);
		// And undo again should give us back currentC's snapshot.
		List<SelectionPoint> restoredC = history.undo(new ArrayList<>());
		assertEquals(3f, restoredC.get(0).x(), 0f);
	}

	@Test
	public void undoReturnsNullWhenStackEmpty()
	{
		SelectionHistory history = new SelectionHistory();
		assertNull(history.undo(Arrays.asList(pt(1, 1))));
	}

	@Test
	public void undoRedoRoundTripPreservesEmptyState()
	{
		// Edge case: pushing an empty list, undoing back to empty, must keep canUndo false and the snapshot
		// non-null + empty.
		SelectionHistory history = new SelectionHistory();
		history.push(new ArrayList<>());
		List<SelectionPoint> restored = history.undo(Arrays.asList(pt(1, 1)));
		assertEquals(0, restored.size());
		assertFalse(history.canUndo());
		assertTrue(history.canRedo());
	}

	@Test
	public void undoTrimsRedoStackToMaxDepth()
	{
		// Regression: redoStack growth was unbounded on the undo path because only push() trimmed. Without
		// the cap, repeated undo() calls would let redoStack grow unboundedly (one frame per undo, no upper
		// limit). Pin the cap by counting drains: a properly capped redoStack drains in EXACTLY MAX_DEPTH
		// (50) redo() calls; a missing-cap stack would drain in 51+ calls.
		SelectionHistory history = new SelectionHistory();
		for (int i = 0; i < 60; i++)
		{
			history.push(Arrays.asList(pt(i, i)));
		}
		// Drive redoStack past MAX_DEPTH by issuing 60 undos (pushes 60 frames onto redoStack, each one
		// must trim if size > 50).
		for (int i = 0; i < 60; i++)
		{
			history.undo(Arrays.asList(pt(99, 99)));
		}
		// Count redo() drains until canRedo turns false. Without the cap on undo()'s push-to-redoStack,
		// this would exceed MAX_DEPTH = 50.
		int redoCount = 0;
		while (history.canRedo() && redoCount < 200)
		{
			history.redo(Arrays.asList(pt(0, 0)));
			redoCount++;
		}
		assertEquals("redoStack must drain in exactly MAX_DEPTH (50) calls; over-cap = leak",
			50, redoCount);
	}

	@Test
	public void redoTrimsUndoStackToMaxDepth()
	{
		// Symmetric regression: undoStack growth on the redo path was unbounded. Pin the cap with the same
		// "exactly MAX_DEPTH drains" assertion shape.
		SelectionHistory history = new SelectionHistory();
		// Build undoStack to 60 (cap applied) then drain into redoStack via 60 undo()s.
		for (int i = 0; i < 60; i++)
		{
			history.push(Arrays.asList(pt(i, i)));
		}
		for (int i = 0; i < 60; i++)
		{
			history.undo(Arrays.asList(pt(99, 99)));
		}
		// Now redoStack holds at most MAX_DEPTH = 50 frames (already verified above). Redo all of them —
		// each redo() pushes onto undoStack via the cap-trimmed path. If the trim is missing, undoStack
		// would grow to 51 (50 from the redo path + the 1 most recent that survived the 60-push trim).
		while (history.canRedo())
		{
			history.redo(Arrays.asList(pt(0, 0)));
		}
		// Drain undoStack and count.
		int undoCount = 0;
		while (history.canUndo() && undoCount < 200)
		{
			history.undo(Arrays.asList(pt(0, 0)));
			undoCount++;
		}
		assertEquals("undoStack must drain in exactly MAX_DEPTH (50) calls",
			50, undoCount);
	}
}
