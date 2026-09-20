package cx.gid.minecraft.blockgrep.client.builder;

import cx.gid.minecraft.blockgrep.client.builder.EditablePattern.CellRule;
import cx.gid.minecraft.blockgrep.client.builder.EditablePattern.Term;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Draws and describes cell rules, shared by the viewport and the palette.
 *
 * Holds the tag-to-representative-block cache, which is why this is an
 * instance rather than a pile of statics: resolving a tag walks the registry
 * and must not happen per frame.
 */
final class RuleIcons
{
  /**
   * Half-width of an item icon at scale 1.
   */
  private static final int ICON_HALF = 8;

  private static final int TAG_BADGE_COLOR      = 0xFFFFFF55;
  private static final int NEGATE_BADGE_COLOR   = 0xFFFF4A4A;
  private static final int EXTRA_BADGE_COLOR    = 0xFFFFFFFF;
  private static final int WILDCARD_FILL_COLOR  = 0x704C46A8;
  private static final int WILDCARD_EDGE_COLOR  = 0xFF9C8CFF;
  private static final int WILDCARD_GLYPH_COLOR = 0xFFFFFFFF;

  /**
   * Terms listed in a multi-term tooltip before it elides the rest.
   */
  private static final int MAX_TERMS_IN_TOOLTIP = 3;

  private final Font font;
  private final Map<String, Block> tagRepresentatives = new HashMap<>();

  RuleIcons(Font font)
  {
    this.font = font;
  }

  // -- drawing ------------------------------------------------------------

  void drawEntry(
      GuiGraphicsExtractor graphics,
      PaletteEntry entry,
      int centerX,
      int centerY,
      float scale
  )
  {
    if(entry.any()) {
      drawWildcard(graphics, centerX, centerY);
      return;
    }

    drawItem(graphics, stackFor(entry.representative()), centerX, centerY, scale);

    if(entry.tag()) {
      graphics.centeredText(font, "#", centerX - 6, centerY - 8, TAG_BADGE_COLOR);
    }
  }

  void drawRule(
      GuiGraphicsExtractor graphics,
      CellRule rule,
      int centerX,
      int centerY,
      float scale
  )
  {
    if(rule.isAny()) {
      drawWildcard(graphics, centerX, centerY);
      return;
    }

    Term first = rule.terms().getFirst();
    drawItem(graphics, stackFor(blockFor(first)), centerX, centerY, scale);

    int badgeX = Math.round(6 * scale);
    int badgeY = Math.round(8 * scale);

    if(first.tag()) {
      graphics.centeredText(
          font,
          "#",
          centerX - badgeX,
          centerY - badgeY,
          TAG_BADGE_COLOR
      );
    }

    if(first.negated()) {
      graphics.centeredText(
          font,
          "!",
          centerX + badgeX,
          centerY - badgeY,
          NEGATE_BADGE_COLOR
      );
    }

    int extra = rule.terms().size() - 1;
    if(extra > 0) {
      graphics.centeredText(
          font,
          "+" + extra,
          centerX + Math.round(5 * scale),
          centerY + Math.round(4 * scale),
          EXTRA_BADGE_COLOR
      );
    }
  }

  private void drawWildcard(GuiGraphicsExtractor graphics, int centerX, int centerY)
  {
    graphics.fill(
        centerX - ICON_HALF,
        centerY - ICON_HALF,
        centerX + ICON_HALF,
        centerY + ICON_HALF,
        WILDCARD_FILL_COLOR
    );

    graphics.outline(
        centerX - ICON_HALF,
        centerY - ICON_HALF,
        ICON_HALF * 2,
        ICON_HALF * 2,
        WILDCARD_EDGE_COLOR
    );

    graphics.centeredText(font, "?", centerX, centerY - 4, WILDCARD_GLYPH_COLOR);
  }

  private static void drawItem(
      GuiGraphicsExtractor graphics,
      ItemStack stack,
      int centerX,
      int centerY,
      float scale
  )
  {
    graphics.pose().pushMatrix();
    graphics.pose().translate(centerX, centerY);
    graphics.pose().scale(scale, scale);
    graphics.item(stack, -ICON_HALF, -ICON_HALF);
    graphics.pose().popMatrix();
  }

  // -- block resolution ---------------------------------------------------

  /**
   * Fluids and itemless blocks have no inventory icon, so substitute
   * something recognizable rather than rendering nothing.
   */
  private static ItemStack stackFor(Block block)
  {
    if(block == Blocks.WATER) return stackOf(Items.WATER_BUCKET);
    if(block == Blocks.LAVA) return stackOf(Items.LAVA_BUCKET);

    if(block == null || block == Blocks.AIR || block.asItem() == Items.AIR) {
      return stackOf(Items.BARRIER);
    }

    return stackOf(block.asItem());
  }

  /**
   * Builds a stack only when the item's data components exist.
   *
   * Components are bound when a datapack loads, so before a world is joined
   * every item in the registry still has none.  {@code new ItemStack(item)}
   * reads them through {@code Holder.components()} and throws
   * {@code NullPointerException: Components not bound yet}.
   *
   * {@link PatternEditorScreen#isAvailable} keeps the editor shut until a
   * world is loaded, so this should never fire in practice.  It stays as a
   * backstop because the failure it prevents is a hard client crash on the
   * first rendered frame, and an empty stack merely draws nothing.
   */
  private static ItemStack stackOf(Item item)
  {
    if(item == null || !item.builtInRegistryHolder().areComponentsBound()) {
      return ItemStack.EMPTY;
    }

    return new ItemStack(item);
  }

  Block blockFor(Term term)
  {
    if(term.tag()) {
      return tagRepresentatives.computeIfAbsent(term.id(), this::representativeForTag);
    }

    Identifier id = Identifier.tryParse(term.id());
    if(id == null) return Blocks.BARRIER;

    return BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.BARRIER);
  }

  Block representativeForTag(String idText)
  {
    Identifier id = Identifier.tryParse(idText);
    if(id == null) return PaletteEntry.TAG_FALLBACK_ICON;

    TagKey<Block> key = TagKey.create(BuiltInRegistries.BLOCK.key(), id);

    try {
      for(Holder<Block> holder: BuiltInRegistries.BLOCK.getTagOrEmpty(key)) {
        if(holder.value().asItem() != Items.AIR) {
          return holder.value();
        }
      }
    }
    catch(IllegalStateException ignored) {
      // Tags are unbound on the title screen.  A generic tag block keeps
      // the rule editable until a world supplies a representative.
    }

    return PaletteEntry.TAG_FALLBACK_ICON;
  }

  // -- description --------------------------------------------------------

  Component describeTerm(Term term)
  {
    Block block = blockFor(term);

    Component base;
    if(term.tag()) {
      base = Component.translatable("blockgrep.builder.tag.named", term.id());
    }
    else if(block == Blocks.BARRIER && !term.id().equals("minecraft:barrier")) {
      // A barrier we did not ask for means the id resolved to nothing.
      base = Component.translatable("blockgrep.builder.missing", term.id());
    }
    else {
      base = block.getName();
    }

    return term.negated()
        ? Component.translatable("blockgrep.builder.not", base)
        : base;
  }

  Component describeRule(CellRule rule)
  {
    if(rule.isAny()) {
      return Component.translatable("blockgrep.builder.any");
    }

    if(rule.terms().size() == 1) {
      return describeTerm(rule.terms().getFirst());
    }

    MutableComponent list = Component.empty();
    int shown             = Math.min(rule.terms().size(), MAX_TERMS_IN_TOOLTIP);

    for(int i = 0; i < shown; i++) {
      if(i > 0) list.append(Component.literal(", "));

      list.append(describeTerm(rule.terms().get(i)));
    }

    int hidden = rule.terms().size() - shown;
    if(hidden > 0) {
      list.append(Component.literal(" (+" + hidden + ")"));
    }

    return list;
  }
}
