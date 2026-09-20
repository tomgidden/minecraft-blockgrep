package cx.gid.minecraft.blockgrep.client.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The searchable block/tag swatch grid down the right-hand side.
 *
 * Owns its own registry snapshots, filtering and paging; the screen supplies
 * only the rectangle to draw into and receives the chosen entry back.
 */
final class BlockPalette
{
  /**
   * Swatch pitch, including the 2px gutter drawn inside each tile.
   */
  static final int TILE = 22;

  private static final int TILE_GUTTER = 2;

  private static final int PANEL_FILL_COLOR  = 0xD0121820;
  private static final int PANEL_EDGE_COLOR  = 0x805A708A;
  private static final int TILE_FILL_COLOR   = 0x302C3A49;
  private static final int TILE_HOVER_COLOR  = 0x605E86A8;
  private static final int TILE_HOVER_EDGE   = 0xFFFFFFFF;
  private static final int MUTED_TEXT_COLOR  = 0xFFA0A0A0;
  private static final int EMPTY_TEXT_COLOR  = 0xFF808080;
  private static final int MIN_TOOLTIP_WIDTH = 240;

  /**
   * Tags offered even when the registry has no bound contents, so the tag
   * tool stays usable from the title screen.  Purely a display convenience;
   * the runtime registry still decides what each tag means.
   */
  private static final String[] COMMON_TAGS = {
      "minecraft:logs",
      "minecraft:planks",
      "minecraft:leaves",
      "minecraft:wool",
      "minecraft:doors",
      "minecraft:trapdoors",
      "minecraft:stairs",
      "minecraft:slabs",
      "minecraft:fences",
      "minecraft:walls",
      "minecraft:mineable/pickaxe",
      "minecraft:ores",
      "minecraft:coal_ores",
      "minecraft:iron_ores",
      "minecraft:copper_ores",
      "minecraft:gold_ores",
      "minecraft:diamond_ores",
      "minecraft:redstone_ores",
      "minecraft:base_stone_overworld",
      "minecraft:dirt",
      "minecraft:sand"
  };

  enum Mode { BLOCKS,
              TAGS }

  /**
   * A laid-out swatch grid, derived fresh from the panel rectangle.
   */
  record Grid(int left, int top, int right, int bottom, int columns, int rows) {
    int capacity()
    {
      return columns * rows;
    }
  }

  private final Font font;
  private final RuleIcons icons;

  private final List<PaletteEntry> allBlocks;
  private final List<PaletteEntry> allTags;
  private List<PaletteEntry> visibleBlocks;
  private List<PaletteEntry> visibleTags;

  private Mode mode    = Mode.BLOCKS;
  private String query = "";
  private int page;

  /**
   * Most recent layout, so hit-testing matches what was last drawn.
   */
  private Grid grid = new Grid(0, 0, 0, 0, 1, 1);

  BlockPalette(Font font, RuleIcons icons)
  {
    this.font          = font;
    this.icons         = icons;
    this.allBlocks     = collectBlocks();
    this.allTags       = collectTags(icons);
    this.visibleBlocks = allBlocks;
    this.visibleTags   = allTags;
  }

  // -- state --------------------------------------------------------------

  Mode mode()
  {
    return mode;
  }

  void setMode(Mode mode)
  {
    this.mode = mode;
    this.page = 0;
  }

  void setQuery(String query)
  {
    this.query = query;
    refilter();
  }

  /**
   * Translation key for the search box hint, which names the active tab.
   */
  String searchHintKey()
  {
    return mode == Mode.BLOCKS
        ? "blockgrep.builder.search.blocks"
        : "blockgrep.builder.search.tags";
  }

  private void refilter()
  {
    if(query == null || query.isBlank()) {
      visibleBlocks = allBlocks;
      visibleTags   = allTags;
    }
    else {
      String needle = query.trim().toLowerCase(Locale.ROOT);

      visibleBlocks = allBlocks.stream().filter(e -> e.matches(needle)).toList();
      visibleTags   = allTags.stream().filter(e -> e.matches(needle)).toList();
    }

    page = 0;
  }

  private List<PaletteEntry> entries()
  {
    return mode == Mode.BLOCKS ? visibleBlocks : visibleTags;
  }

  // -- paging -------------------------------------------------------------

  int page()
  {
    return page;
  }

  int pageCount()
  {
    int capacity = Math.max(1, grid.capacity());
    return Math.max(1, (entries().size() + capacity - 1) / capacity);
  }

  void changePage(int delta)
  {
    page = Math.clamp(page + delta, 0, Math.max(0, pageCount() - 1));
  }

  /**
   * Re-clamps after a resize or a filter change shrinks the page count.
   */
  void clampPage()
  {
    page = Math.clamp(page, 0, Math.max(0, pageCount() - 1));
  }

  // -- layout and hit-testing ---------------------------------------------

  /**
   * Lays the swatch grid out within the given panel rectangle and remembers
   * it for hit-testing.  Called from render, before any picking.
   */
  private void layout(int left, int top, int right, int bottom)
  {
    int columns = Math.max(1, (right - left) / TILE);
    int rows    = Math.max(1, (bottom - top) / TILE);
    grid        = new Grid(left, top, right, bottom, columns, rows);
  }

  boolean contains(double x, double y)
  {
    return x >= grid.left && x < grid.right
        && y >= grid.top && y < grid.bottom;
  }

  PaletteEntry entryAt(double mouseX, double mouseY)
  {
    int col = (int) (mouseX - grid.left) / TILE;
    int row = (int) (mouseY - grid.top) / TILE;

    if(col < 0 || col >= grid.columns || row < 0 || row >= grid.rows) {
      return null;
    }

    int index = page * grid.capacity() + row * grid.columns + col;

    List<PaletteEntry> entries = entries();
    return index >= 0 && index < entries.size() ? entries.get(index) : null;
  }

  // -- rendering ----------------------------------------------------------

  /**
   * @param gridTop top of the swatch area; the panel above it is owned by
   *                the caller (tabs, search box, active-rule strip).
   */
  void render(
      GuiGraphicsExtractor graphics,
      int panelLeft,
      int panelTop,
      int panelRight,
      int panelBottom,
      int gridTop,
      int mouseX,
      int mouseY,
      int screenWidth
  )
  {
    graphics.fill(panelLeft, panelTop, panelRight, panelBottom, PANEL_FILL_COLOR);
    graphics.outline(
        panelLeft,
        panelTop,
        panelRight - panelLeft,
        panelBottom - panelTop,
        PANEL_EDGE_COLOR
    );

    layout(panelLeft + 6, gridTop, panelRight - 5, panelBottom - 27);
    clampPage();

    List<PaletteEntry> entries = entries();
    int centerX                = (panelLeft + panelRight) / 2;

    if(entries.isEmpty()) {
      graphics.centeredText(
          font,
          Component.translatable("blockgrep.builder.search.empty"),
          centerX,
          grid.top + 20,
          EMPTY_TEXT_COLOR
      );
    }
    else {
      renderSwatches(graphics, entries, mouseX, mouseY, screenWidth);
    }

    graphics.centeredText(
        font,
        Component.translatable("blockgrep.builder.page", page + 1, pageCount()),
        centerX,
        panelBottom - 18,
        MUTED_TEXT_COLOR
    );
  }

  private void renderSwatches(
      GuiGraphicsExtractor graphics,
      List<PaletteEntry> entries,
      int mouseX,
      int mouseY,
      int screenWidth
  )
  {
    int from = page * grid.capacity();
    int to   = Math.min(entries.size(), from + grid.capacity());
    int size = TILE - TILE_GUTTER;

    for(int i = from; i < to; i++) {
      int local = i - from;
      int x     = grid.left + (local % grid.columns) * TILE;
      int y     = grid.top + (local / grid.columns) * TILE;

      boolean hovered = mouseX >= x && mouseX < x + TILE
          && mouseY >= y && mouseY < y + TILE;

      graphics.fill(
          x,
          y,
          x + size,
          y + size,
          hovered ? TILE_HOVER_COLOR : TILE_FILL_COLOR
      );

      PaletteEntry entry = entries.get(i);
      icons.drawEntry(graphics, entry, x + 10, y + 10, 1.0f);

      if(hovered) {
        graphics.outline(x, y, size, size, TILE_HOVER_EDGE);
        graphics.setTooltipForNextFrame(
            font,
            font.split(entry.tooltip(), Math.max(MIN_TOOLTIP_WIDTH, screenWidth / 2)),
            mouseX,
            mouseY
        );
      }
    }
  }

  // -- registry snapshots -------------------------------------------------

  private static List<PaletteEntry> collectBlocks()
  {
    List<PaletteEntry> out = new ArrayList<>();

    out.add(PaletteEntry.wildcard());
    out.add(PaletteEntry.block("minecraft:air", Blocks.AIR));

    for(Block block: BuiltInRegistries.BLOCK) {
      if(block != Blocks.AIR) {
        out.add(PaletteEntry.block(
            BuiltInRegistries.BLOCK.getKey(block).toString(),
            block
        ));
      }
    }

    out.sort(Comparator.comparing(PaletteEntry::id));
    return List.copyOf(out);
  }

  private static List<PaletteEntry> collectTags(RuleIcons icons)
  {
    Map<String, PaletteEntry> found = new LinkedHashMap<>();

    try {
      BuiltInRegistries.BLOCK
          .getTags()
          .sorted(Comparator.comparing(named -> named.key().location().toString()))
          .forEach(named -> {
            Block representative = firstDisplayable(named);
            String id            = named.key().location().toString();

            found.put(id, PaletteEntry.tag(id, representative == null ? PaletteEntry.TAG_FALLBACK_ICON : representative));
          });
    }
    catch(IllegalStateException ignored) {
      // The built-in registry has no bound tag contents at the title
      // screen.
    }

    for(String id: COMMON_TAGS) {
      found.computeIfAbsent(
          id,
          key -> PaletteEntry.tag(key, icons.representativeForTag(key))
      );
    }

    List<PaletteEntry> sorted = new ArrayList<>(found.values());
    sorted.sort(Comparator.comparing(PaletteEntry::id));
    return List.copyOf(sorted);
  }

  private static Block firstDisplayable(HolderSet.Named<Block> named)
  {
    for(Holder<Block> holder: named) {
      if(holder.value().asItem() != Items.AIR) {
        return holder.value();
      }
    }
    return null;
  }
}
