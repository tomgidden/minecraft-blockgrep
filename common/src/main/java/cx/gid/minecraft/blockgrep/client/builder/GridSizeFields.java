package cx.gid.minecraft.blockgrep.client.builder;

import java.util.OptionalInt;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The {@code Grid: [x] × [y] × [z]} numeric fields in the header.
 *
 * Typing is committed on every keystroke where the text parses to a legal
 * size, so the view updates live; illegal or partial text (empty, zero, out
 * of range) is simply not committed, leaving the last good size in place.
 * The field is reconciled with the real size on focus loss, which quietly
 * repairs anything the player left half-typed.
 */
final class GridSizeFields
{
  private static final int FIELD_WIDTH  = 26;
  private static final int FIELD_HEIGHT = 16;
  private static final int SEPARATOR_W  = 10;
  private static final int LABEL_GAP    = 4;

  private static final int LABEL_COLOR = 0xFFA0A0A0;

  private final Font font;
  private final EditBox[] fields = new EditBox[3];

  private int labelX;
  private int labelY;
  private int labelWidth;

  /**
   * Suppresses the responder while we rewrite a field's text ourselves.
   */
  private boolean updating;

  /**
   * Previous focus state per axis, to detect the frame focus is lost.
   */
  private final boolean[] wasFocused = new boolean[3];

  GridSizeFields(Font font)
  {
    this.font = font;
  }

  EditBox[] fields()
  {
    return fields;
  }

  /**
   * Builds the three boxes left to right.
   *
   * @param onChange receives the axis index and the newly typed size.
   * @return the x coordinate just past the last field.
   */
  int layout(int x, int y, EditablePattern pattern, IntAxisConsumer onChange)
  {
    Component label = Component.translatable("blockgrep.builder.grid");

    labelWidth = font.width(label);
    labelX     = x;
    labelY     = y + (FIELD_HEIGHT - font.lineHeight) / 2 + 1;

    int at = x + labelWidth + LABEL_GAP;

    for(int axis = 0; axis < 3; axis++) {
      final int which = axis;

      EditBox box = new EditBox(
          font,
          at,
          y,
          FIELD_WIDTH,
          FIELD_HEIGHT,
          Component.translatable("blockgrep.builder.grid.axis." + axisKey(axis))
      );

      box.setMaxLength(2);
      box.setValue(Integer.toString(sizeOf(pattern, axis)));
      box.setTooltip(Tooltip.create(
          Component.translatable("blockgrep.builder.grid.tooltip")
      ));

      box.setResponder(text -> {
        if(updating) return;

        parseSize(text).ifPresent(size -> onChange.accept(which, size));
      });

      fields[axis] = box;
      at += FIELD_WIDTH + SEPARATOR_W;
    }

    return at - SEPARATOR_W;
  }

  /**
   * Pushes the authoritative sizes back into the boxes.
   *
   * A focused box is left alone so typing is never overwritten mid-keystroke
   * — except on the frame it loses focus, where whatever partial text the
   * player abandoned ("", "0", "99") is replaced by the size actually in
   * effect.  That reconciliation is why {@code wasFocused} is tracked rather
   * than simply skipping focused boxes: without it an abandoned edit would
   * sit there misreporting the pattern indefinitely.
   */
  void sync(EditablePattern pattern)
  {
    updating = true;

    for(int axis = 0; axis < 3; axis++) {
      EditBox box = fields[axis];
      if(box == null) continue;

      boolean focused   = box.isFocused();
      boolean lostFocus = wasFocused[axis] && !focused;
      wasFocused[axis]  = focused;

      if(focused && !lostFocus) continue;

      String expected = Integer.toString(sizeOf(pattern, axis));
      if(!expected.equals(box.getValue())) {
        box.setValue(expected);
      }
    }

    updating = false;
  }

  void render(GuiGraphicsExtractor graphics)
  {
    graphics.text(
        font,
        Component.translatable("blockgrep.builder.grid"),
        labelX,
        labelY,
        LABEL_COLOR
    );

    for(int axis = 0; axis < 2; axis++) {
      EditBox box = fields[axis];
      if(box == null) continue;

      graphics.text(
          font,
          "×",
          box.getX() + FIELD_WIDTH + 3,
          labelY,
          LABEL_COLOR
      );
    }
  }

  private static OptionalInt parseSize(String text)
  {
    try {
      int value = Integer.parseInt(text.trim());

      return value >= 1 && value <= EditablePattern.MAX_SIZE
          ? OptionalInt.of(value)
          : OptionalInt.empty();
    }
    catch(NumberFormatException e) {
      // Partial input while typing; leave the pattern alone.
      return OptionalInt.empty();
    }
  }

  private static int sizeOf(EditablePattern pattern, int axis)
  {
    return switch(axis) {
      case 0 -> pattern.sizeX();
      case 1 -> pattern.sizeY();
      default -> pattern.sizeZ();
    };
  }

  private static String axisKey(int axis)
  {
    return switch(axis) {
      case 0 -> "x";
      case 1 -> "y";
      default -> "z";
    };
  }

  /**
   * Axis index plus the new size for that axis.
   */
  @FunctionalInterface
  interface IntAxisConsumer {
    void accept(int axis, int size);
  }
}
