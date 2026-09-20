package cx.gid.minecraft.blockgrep.client.builder;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * One selectable swatch: a concrete block, a block tag, or the wildcard.
 *
 * {@code representative} is purely cosmetic — for a tag it is some member
 * block chosen only so the swatch has an icon.  The runtime registry, not
 * this record, decides what a tag actually matches.
 */
record PaletteEntry(String id, boolean tag, boolean any, Block representative) {
  /**
   * Icon stand-in for the wildcard swatch.
   */
  private static final Block WILDCARD_ICON = Blocks.AMETHYST_BLOCK;

  /**
   * Icon stand-in for a tag with no resolvable members.
   */
  static final Block TAG_FALLBACK_ICON = Blocks.OAK_LOG;

  static PaletteEntry wildcard()
  {
    return new PaletteEntry("?", false, true, WILDCARD_ICON);
  }

  static PaletteEntry block(String id, Block block)
  {
    return new PaletteEntry(id, false, false, block);
  }

  static PaletteEntry tag(String id, Block block)
  {
    return new PaletteEntry(id, true, false, block);
  }

  /**
   * @param query already lower-cased and trimmed by the caller.
   */
  boolean matches(String query)
  {
    if(any) {
      return query.equals("?")
          || query.startsWith("any")
          || query.startsWith("wild");
    }

    if(id.toLowerCase(Locale.ROOT).contains(query)) {
      return true;
    }

    if(representative == null) {
      return false;
    }

    String name = representative.getName().getString().toLowerCase(Locale.ROOT);
    return name.contains(query);
  }

  Component tooltip()
  {
    if(any) {
      return Component.translatable("blockgrep.builder.any.tooltip");
    }

    if(tag) {
      return Component.translatable("blockgrep.builder.tag.tooltip", id);
    }

    if(representative == Blocks.AIR) {
      return Component.translatable("blockgrep.builder.air.tooltip");
    }

    return Component.translatable(
        "blockgrep.builder.block.tooltip",
        representative.getName(),
        id
    );
  }
}
