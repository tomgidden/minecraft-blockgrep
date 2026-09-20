package cx.gid.minecraft.blockgrep.client.builder;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * A left-to-right cursor for laying out a row of toolbar buttons.
 *
 * Replaces hand-maintained {@code x += 29} arithmetic between every widget,
 * where a single stale number silently overlaps two buttons.  Widths are
 * given once, per button, and the gaps come from here.
 */
final class ToolbarRow
{
  static final int BUTTON_HEIGHT = 20;

  /**
   * Gap between buttons within a group.
   */
  private static final int GAP = 2;

  /**
   * Wider gap marking a break between groups of related tools.
   */
  private static final int GROUP_GAP = 5;

  private int x;
  private int y;

  ToolbarRow(int x, int y)
  {
    this.x = x;
    this.y = y;
  }

  int x()
  {
    return x;
  }

  int y()
  {
    return y;
  }

  /**
   * Restarts the cursor, for wrapping onto a second row.
   */
  void moveTo(int x, int y)
  {
    this.x = x;
    this.y = y;
  }

  void skip(int amount)
  {
    x += amount;
  }

  /**
   * Moves the cursor to an absolute x, for a widget laid out by something
   * else that reports where it ended.
   */
  void resumeAt(int x)
  {
    this.x = x;
  }

  /**
   * Ends the current group, so the next button gets the wider gap.
   */
  void endGroup()
  {
    x += GROUP_GAP - GAP;
  }

  /**
   * Builds a button at the cursor and advances past it.  The caller still
   * registers the result, since only the screen can add widgets.
   */
  Button button(String label, int width, String tooltipKey, Button.OnPress action)
  {
    Button button = Button
                        .builder(Component.literal(label), action)
                        .bounds(x, y, width, BUTTON_HEIGHT)
                        .tooltip(Tooltip.create(Component.translatable(tooltipKey)))
                        .build();

    x += width + GAP;
    return button;
  }
}
