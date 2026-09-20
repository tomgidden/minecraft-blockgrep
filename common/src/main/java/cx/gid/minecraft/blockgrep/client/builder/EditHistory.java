package cx.gid.minecraft.blockgrep.client.builder;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded undo/redo stacks of whole-pattern snapshots.
 *
 * Patterns top out at 16³ cells, so snapshotting the entire grid per edit is
 * cheaper and far simpler than a command log, and it survives resizes without
 * needing to replay dimension changes.
 */
final class EditHistory
{
  private static final int MAX_HISTORY = 64;

  private final Deque<EditablePattern> undo = new ArrayDeque<>();
  private final Deque<EditablePattern> redo = new ArrayDeque<>();

  boolean canUndo()
  {
    return !undo.isEmpty();
  }

  boolean canRedo()
  {
    return !redo.isEmpty();
  }

  boolean isEmpty()
  {
    return undo.isEmpty();
  }

  /**
   * Records the state before a mutation.  Callers push first, then mutate.
   */
  void push(EditablePattern current)
  {
    undo.push(current.copy());

    while(undo.size() > MAX_HISTORY) {
      undo.removeLast();
    }

    redo.clear();
  }

  /**
   * @return the pattern to restore, or {@code null} if there is nothing to
   *         undo.
   */
  EditablePattern undo(EditablePattern current)
  {
    if(undo.isEmpty()) return null;

    redo.push(current.copy());
    return undo.pop();
  }

  /**
   * @return the pattern to restore, or {@code null} if there is nothing to
   *         redo.
   */
  EditablePattern redo(EditablePattern current)
  {
    if(redo.isEmpty()) return null;

    undo.push(current.copy());
    return redo.pop();
  }
}
