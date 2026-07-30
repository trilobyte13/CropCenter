package com.cropcenter.view;

import com.cropcenter.model.SelectionPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Undo/redo stack for the editor's selection-point list. Pure bookkeeping — no coordinate math, no view interaction.
 * SelectionPoint is an immutable record so each snapshot is a shallow copy of the current list.
 *
 * Callers pass the current list when pushing / undoing / redoing; the returned snapshot is what the caller should
 * restore (or null when the target stack is empty).
 */
final class SelectionHistory
{
	private static final int MAX_DEPTH = 50;

	private final List<List<SelectionPoint>> redoStack = new ArrayList<>();
	private final List<List<SelectionPoint>> undoStack = new ArrayList<>();

	boolean canRedo()
	{
		return !redoStack.isEmpty();
	}

	boolean canUndo()
	{
		return !undoStack.isEmpty();
	}

	/**
	 * Drop both stacks — called when the image is reset or the session starts fresh.
	 */
	void clear()
	{
		undoStack.clear();
		redoStack.clear();
	}

	/**
	 * Push the current state as a new undo frame and clear the redo stack. Trims the oldest frame when the stack
	 * exceeds MAX_DEPTH.
	 *
	 * @param current selection-point list to snapshot onto the undo stack
	 */
	void push(List<SelectionPoint> current)
	{
		undoStack.add(snapshot(current));
		redoStack.clear();
		if (undoStack.size() > MAX_DEPTH)
		{
			undoStack.removeFirst();
		}
	}

	/**
	 * Pop a redo frame and return its snapshot. Pushes the current state onto undoStack. No trim needed on the
	 * undo-side push: redo() only fires when redoStack is non-empty, which means the most recent op was an undo()
	 * that decreased undoStack — so undoStack is at most MAX_DEPTH−1 going in and at most MAX_DEPTH after the push,
	 * never tripping a trim.
	 *
	 * @param current selection-point list to snapshot onto the undo stack before popping redo
	 * @return the popped redo snapshot, or null when the redo stack is empty
	 */
	List<SelectionPoint> redo(List<SelectionPoint> current)
	{
		if (redoStack.isEmpty())
		{
			return null;
		}
		undoStack.add(snapshot(current));
		return redoStack.removeLast();
	}

	/**
	 * Pop an undo frame and return its snapshot. Pushes the current state onto redoStack. No trim needed on the
	 * redo-side push: push() clears redoStack and caps undoStack at MAX_DEPTH, so the maximum consecutive undos
	 * before undoStack drains is MAX_DEPTH — redoStack tops out at MAX_DEPTH and never exceeds it.
	 *
	 * @param current selection-point list to snapshot onto the redo stack before popping undo
	 * @return the popped undo snapshot, or null when the undo stack is empty
	 */
	List<SelectionPoint> undo(List<SelectionPoint> current)
	{
		if (undoStack.isEmpty())
		{
			return null;
		}
		redoStack.add(snapshot(current));
		return undoStack.removeLast();
	}

	private static List<SelectionPoint> snapshot(List<SelectionPoint> src)
	{
		return new ArrayList<>(src);
	}
}
