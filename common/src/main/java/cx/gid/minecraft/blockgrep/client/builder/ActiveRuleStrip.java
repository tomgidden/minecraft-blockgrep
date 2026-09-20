package cx.gid.minecraft.blockgrep.client.builder;

import cx.gid.minecraft.blockgrep.client.builder.EditablePattern.CellRule;
import cx.gid.minecraft.blockgrep.client.builder.EditablePattern.Term;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The single row above the palette showing the rule being edited — either
 * the paint brush or the selected voxel, depending on mode.
 *
 * A rule can hold more terms than the row has slots, so it pages
 * independently of the palette, with inline ‹ › arrows rather than widgets.
 */
final class ActiveRuleStrip
{
  static final int HEIGHT = 25;

  /**
   * Width reserved at each end for a paging arrow.
   */
  private static final int ARROW_ZONE = 14;

  private static final int PAD_WITH_ARROWS = 14;
  private static final int PAD_PLAIN       = 2;

  private static final int LABEL_COLOR      = 0xFFA0A0A0;
  private static final int FILL_COLOR       = 0x402C3A49;
  private static final int HOVER_EDGE_COLOR = 0xFFFFFFFF;
  private static final int ARROW_ON_COLOR   = 0xFFFFFFFF;
  private static final int ARROW_OFF_COLOR  = 0xFF555555;

  private static final int MIN_TOOLTIP_WIDTH = 240;

  private final Font font;
  private final RuleIcons icons;

  private int left;
  private int top;
  private int right;
  private int bottom;

  private int page;

  ActiveRuleStrip(Font font, RuleIcons icons)
  {
    this.font  = font;
    this.icons = icons;
  }

  void setBounds(int left, int top, int right)
  {
    this.left   = left;
    this.top    = top;
    this.right  = right;
    this.bottom = top + HEIGHT - 3;
  }

  int bottom()
  {
    return bottom;
  }

  void resetPage()
  {
    page = 0;
  }

  boolean contains(double x, double y)
  {
    return x >= left && x < right && y >= top && y < bottom;
  }

  // -- paging -------------------------------------------------------------

  /**
   * Slots available for terms.  When the rule overflows, the arrows claim
   * space, so this shrinks — hence the two-step calculation.
   */
  int slots(CellRule rule)
  {
    int maxSlots = Math.max(1, (right - left - 4) / BlockPalette.TILE);

    if(rule.isAny() || rule.terms().size() <= maxSlots) {
      return maxSlots;
    }

    return Math.max(1, (right - left - 28) / BlockPalette.TILE);
  }

  int pageCount(CellRule rule)
  {
    if(rule.isAny()) return 1;

    int slots = slots(rule);
    return Math.max(1, (rule.terms().size() + slots - 1) / slots);
  }

  /**
   * Scrolls to whichever page holds the last term, after an add.
   */
  void showLastTerm(CellRule rule)
  {
    page = Math.max(0, (rule.terms().size() - 1) / slots(rule));
  }

  void clampPage(CellRule rule)
  {
    page = Math.clamp(page, 0, Math.max(0, pageCount(rule) - 1));
  }

  private int pad(CellRule rule)
  {
    return pageCount(rule) > 1 ? PAD_WITH_ARROWS : PAD_PLAIN;
  }

  // -- hit-testing --------------------------------------------------------

  /**
   * @return -1 or 1 for a paging arrow, 0 for neither.
   */
  int arrowAt(double x, CellRule rule)
  {
    if(pageCount(rule) <= 1) return 0;

    if(x < left + ARROW_ZONE) return -1;
    if(x >= right - ARROW_ZONE) return 1;

    return 0;
  }

  void turnPage(int delta, CellRule rule)
  {
    page = Math.clamp(page + delta, 0, Math.max(0, pageCount(rule) - 1));
  }

  /**
   * @return index into the rule's term list, or -1 if the point is not on a
   *         populated slot.
   */
  int termAt(double x, CellRule rule)
  {
    int local = (int) (x - left - pad(rule)) / BlockPalette.TILE;

    if(local < 0 || local >= slots(rule)) return -1;

    int index = page * slots(rule) + local;
    return index < rule.terms().size() ? index : -1;
  }

  // -- rendering ----------------------------------------------------------

  void render(
      GuiGraphicsExtractor graphics,
      CellRule rule,
      Component header,
      int mouseX,
      int mouseY,
      int screenWidth
  )
  {
    graphics.text(font, header, left, top - 10, LABEL_COLOR);
    graphics.fill(left, top, right, bottom, FILL_COLOR);

    if(rule.isAny()) {
      icons.drawEntry(graphics, PaletteEntry.wildcard(), left + 11, top + 12, 1.0f);
      return;
    }

    renderTerms(graphics, rule, mouseX, mouseY, screenWidth);

    if(pageCount(rule) > 1) {
      renderArrows(graphics, rule);
    }
  }

  private void renderTerms(
      GuiGraphicsExtractor graphics,
      CellRule rule,
      int mouseX,
      int mouseY,
      int screenWidth
  )
  {
    int slots = slots(rule);
    int pad   = pad(rule);
    int start = page * slots;
    int end   = Math.min(rule.terms().size(), start + slots);

    for(int i = start; i < end; i++) {
      int x     = left + pad + (i - start) * BlockPalette.TILE;
      Term term = rule.terms().get(i);

      icons.drawRule(graphics, CellRule.of(term), x + 10, top + 12, 1.0f);

      boolean hovered = mouseX >= x && mouseX < x + BlockPalette.TILE
          && mouseY >= top && mouseY < bottom;

      if(hovered) {
        graphics.outline(
            x,
            top + 1,
            BlockPalette.TILE - 2,
            HEIGHT - 4,
            HOVER_EDGE_COLOR
        );

        graphics.setTooltipForNextFrame(
            font,
            font.split(
                Component.translatable(
                    "blockgrep.builder.brush.term",
                    icons.describeTerm(term)
                ),
                Math.max(MIN_TOOLTIP_WIDTH, screenWidth / 2)
            ),
            mouseX,
            mouseY
        );
      }
    }
  }

  private void renderArrows(GuiGraphicsExtractor graphics, CellRule rule)
  {
    graphics.centeredText(
        font,
        "‹",
        left + 6,
        top + 7,
        page > 0 ? ARROW_ON_COLOR : ARROW_OFF_COLOR
    );

    graphics.centeredText(
        font,
        "›",
        right - 6,
        top + 7,
        page + 1 < pageCount(rule) ? ARROW_ON_COLOR : ARROW_OFF_COLOR
    );
  }
}
