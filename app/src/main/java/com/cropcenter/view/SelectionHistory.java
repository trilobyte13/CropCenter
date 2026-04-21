package com.cropcenter.view;

import com.cropcenter.model.SelectionPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Undo/redo stack for the editor's selection-point list. Pure bookkeeping — no coordinate
 * math, no view interaction. SelectionPoint is an immutable record so each snapshot is a
 * shallow copy of the current list.
 *
 * Callers pass the current list when pushing / undoing / redoing; the returned snapshot is
 * what the caller should restore (or null when the target stack is empty).
 */
final class SelectionHistory
{
	private static final int MAX_DEPTH = 50;

	private final List<List<SelectionPoint>> redoStack = new ArrayList<>();
	private final List<List<SelectionPoint>> undoStack = new ArrayList<>();

	/**
	 * True when at least one redo frame is available.
	 */
	boolean canRedo()
	{
		return !redoStack.isEmpty();
	}

	/**
	 * True when at least one undo frame is available.
	 */
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
	 * Push the current state as a new undo frame and clear the redo stack. Trims the oldest
	 * frame when the stack exceeds MAX_DEPTH.
	 */
	void push(List<SelectionPoint> current)
	{
		undoStack.add(snapshot(current));
		redoStack.clear();
		if (undoStack.size() > MAX_DEPTH)
		{
			undoStack.remove(0);
		}
	}

	/**
	 * Pop a redo frame and return its snapshot. Pushes the current state onto undo. Returns
	 * null when the redo stack is empty.
	 */
	List<SelectionPoint> redo(List<SelectionPoint> current)
	{
		if (redoStack.isEmpty())
		{
			return null;
		}
		undoStack.add(snapshot(current));
		return redoStack.remove(redoStack.size() - 1);
	}

	/**
	 * Pop an undo frame and return its snapshot. Pushes the current state onto redo. Returns
	 * null when the undo stack is empty.
	 */
	List<SelectionPoint> undo(List<SelectionPoint> current)
	{
		if (undoStack.isEmpty())
		{
			return null;
		}
		redoStack.add(snapshot(current));
		return undoStack.remove(undoStack.size() - 1);
	}

	private static List<SelectionPoint> snapshot(List<SelectionPoint> src)
	{
		return new ArrayList<>(src);
	}
}
