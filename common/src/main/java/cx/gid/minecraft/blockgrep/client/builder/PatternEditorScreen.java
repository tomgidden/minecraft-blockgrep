package cx.gid.minecraft.blockgrep.client.builder;

import cx.gid.minecraft.blockgrep.client.builder.EditablePattern.CellRule;
import cx.gid.minecraft.blockgrep.client.builder.EditablePattern.Term;
import cx.gid.minecraft.blockgrep.client.config.BlockGrepConfig;
import cx.gid.minecraft.blockgrep.client.config.PatternManager;
import cx.gid.minecraft.blockgrep.client.config.SavedPattern;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.NonNull;

/**
 * A no-syntax voxel editor for Block Grep patterns.
 *
 * The view is an orbitable, mildly perspective projection.  Exact block rules
 * use the game's own item model, while wildcard, tag, alternation and
 * negation rules add distinct overlays.  It deliberately remains in the
 * ordinary GUI extraction pipeline instead of reaching into the world
 * renderer; that makes the same implementation work on Fabric and NeoForge
 * and keeps it compatible with the render-state split introduced in
 * Minecraft 26.
 *
 * This class owns only layout, widgets and input routing.  The heavy lifting
 * lives in {@link PatternViewport} (projection and picking),
 * {@link BlockPalette} (swatches), {@link ActiveRuleStrip} (the rule being
 * edited) and {@link EditHistory}.
 */
public final class PatternEditorScreen extends Screen
{
  private static final int HEADER_BOTTOM   = 44;
  private static final int HEADER_WRAPPED  = 65;
  private static final int FOOTER_HEIGHT   = 30;
  private static final int PANEL_GAP       = 6;
  private static final int TOOL_ROW_HEIGHT = 20;
  private static final int SEARCH_HEIGHT   = 16;

  /**
   * Below this logical width the toolbar wraps to a second row.  At large GUI
   * scales the screen can be only ~320-640px wide, and every tool must stay
   * reachable rather than disappearing under the palette.
   */
  private static final int NARROW_WIDTH = 720;

  /**
   * Toolbar baseline, and where it drops to when the header wraps.
   */
  private static final int TOOLBAR_Y         = 21;
  private static final int TOOLBAR_WRAPPED_Y = 42;

  private static final int DIMENSION_TEXT_WIDTH = 520;

  /**
   * Size of a floating axis button.
   */
  private static final int AXIS_BUTTON_SIZE = 14;

  /**
   * Gap kept between a floating axis button and the viewport edge.
   */
  private static final int AXIS_BUTTON_MARGIN = 3;

  /**
   * Minimum center-to-center separation between two floating axis buttons
   * before they are pushed apart.  Two over the button size, so a settled
   * pair shows a visible gap rather than sitting edge to edge.
   */
  private static final int AXIS_BUTTON_SPACING = AXIS_BUTTON_SIZE + 2;

  /**
   * Passes of the separation relaxation.
   *
   * Integer rounding costs a fraction of a pixel per pass, so this converges
   * to about one pixel short of {@link #AXIS_BUTTON_SPACING} rather than
   * reaching it exactly — still a clear gap at the button size.  Four passes
   * leaves visible overlap in the degenerate cases; eight settles them, and
   * more buys nothing.
   */
  private static final int AXIS_SEPARATION_PASSES = 8;

  private static final int VALID_TEXT_COLOR   = 0xFFA0A0A0;
  private static final int INVALID_TEXT_COLOR = 0xFFFF7070;
  private static final int WARNING_TEXT_COLOR = 0xFFFFB050;

  private static final int MIN_TOOLTIP_WIDTH = 240;

  // -- model --------------------------------------------------------------

  private final Screen parent;
  private final SavedPattern target;
  private final boolean discardOnCancel;
  private final boolean recoveredInvalidSource;

  private EditablePattern pattern;
  private boolean committed;
  private boolean patternValid;

  private final EditHistory history = new EditHistory();

  private CellRule brush = CellRule.of(new Term("minecraft:stone", false, false));
  private boolean combine;
  private boolean negateNew;
  private boolean inspectMode;

  private boolean sliceOnly;
  private int sliceY;
  private CellPos selected = new CellPos(0, 0, 0);

  // -- components ---------------------------------------------------------

  private RuleIcons icons;
  private PatternViewport viewport;
  private BlockPalette palette;
  private ActiveRuleStrip activeStrip;
  private GridSizeFields gridFields;

  private String searchQuery = "";

  // -- transient input state ----------------------------------------------

  private boolean orbiting;
  private boolean painting;
  private CellPos lastPainted;

  // -- widgets ------------------------------------------------------------

  private final List<Button> axisButtons = new ArrayList<>();

  private Button undoButton;
  private Button redoButton;
  private Button viewButton;
  private Button layerDownButton;
  private Button layerUpButton;
  private Button modeButton;
  private Button saveButton;
  private Button captureButton;
  private Button blockTab;
  private Button tagTab;
  private Button combineButton;
  private Button negateButton;
  private Button previousPage;
  private Button nextPage;
  private EditBox searchBox;

  /**
   * Whether the editor can be opened.
   *
   * The palette and the voxel view are built from item models, and an item's
   * data components are only bound once a datapack has loaded — that is, once
   * a world is joined.  Before then {@code new ItemStack(item)} throws
   * {@code NullPointerException: Components not bound yet} and takes the
   * client down on the first rendered frame.
   *
   * Callers disable their entry point rather than let this screen open into
   * a crash.  A block registry alone is not enough: the ids all resolve on
   * the title screen, it is only the art that is missing.
   */
  public static boolean isAvailable()
  {
    Minecraft minecraft = Minecraft.getInstance();

    if(minecraft == null || minecraft.level == null) {
      return false;
    }

    // Asking the registry directly, rather than trusting the world to imply
    // it.  Components are bound by the datapack reload that accompanies
    // joining a world, and this is the state the editor actually depends on.
    return Items.STONE.builtInRegistryHolder().areComponentsBound();
  }

  public PatternEditorScreen(Screen parent, SavedPattern target)
  {
    this(parent, target, false);
  }

  public PatternEditorScreen(Screen parent, SavedPattern target, boolean discardOnCancel)
  {
    super(
        Minecraft.getInstance(),
        Minecraft.getInstance().font,
        Component.translatable("blockgrep.builder.title")
    );

    this.parent          = parent;
    this.target          = target;
    this.discardOnCancel = discardOnCancel;

    EditablePattern parsed;
    boolean recovered = false;

    try {
      parsed = EditablePattern.parse(target.spec);
    }
    catch(RuntimeException e) {
      // Keep the original SavedPattern untouched until Save.  The player
      // can safely inspect this recovery draft and Cancel without losing
      // a hand-edited or now-invalid config entry.
      parsed    = new EditablePattern(1, 1, 1);
      recovered = true;
    }

    this.pattern                = parsed;
    this.recoveredInvalidSource = recovered;
  }

  // -- layout -------------------------------------------------------------

  @Override
  protected void init()
  {
    if(icons == null) {
      icons       = new RuleIcons(font);
      viewport    = new PatternViewport(font, icons);
      palette     = new BlockPalette(font, icons);
      activeStrip = new ActiveRuleStrip(font, icons);
      gridFields  = new GridSizeFields(font);
    }

    axisButtons.clear();

    layoutToolbar();
    layoutPalettePanel();
    layoutFooter();
    layoutAxisButtons();

    updateBounds();
    updateButtons();
  }

  private void layoutToolbar()
  {
    ToolbarRow row = new ToolbarRow(PANEL_GAP, TOOLBAR_Y);

    int afterFields =
        gridFields.layout(row.x(), row.y(), pattern, this::setDimension);

    row.resumeAt(afterFields + PANEL_GAP);

    for(EditBox field: gridFields.fields()) {
      addRenderableWidget(field);
    }

    if(width < NARROW_WIDTH) {
      row.moveTo(PANEL_GAP, TOOLBAR_WRAPPED_Y);
    }

    undoButton = addRenderableWidget(
        row.button("↶", 27, "blockgrep.builder.undo", b -> undo())
    );

    redoButton = addRenderableWidget(
        row.button("↷", 27, "blockgrep.builder.redo", b -> redo())
    );
    row.endGroup();

    viewButton = addRenderableWidget(
        row.button("3D", 39, "blockgrep.builder.view", b -> toggleSlice())
    );
    row.endGroup();

    layerDownButton = addRenderableWidget(
        row.button("Y−", 27, "blockgrep.builder.layer.down", b -> changeLayer(-1))
    );

    layerUpButton = addRenderableWidget(
        row.button("Y+", 27, "blockgrep.builder.layer.up", b -> changeLayer(1))
    );
    row.endGroup();

    addRenderableWidget(
        row.button("◎", 27, "blockgrep.builder.center", b -> viewport.camera().reset())
    );

    addRenderableWidget(
        row.button("⌫", 27, "blockgrep.builder.clear", b -> clearPattern())
    );
    row.endGroup();

    captureButton = addRenderableWidget(
        row.button("▣", 27, "blockgrep.builder.capture", b -> captureFromWorld())
    );
    row.endGroup();

    modeButton = addRenderableWidget(
        row.button(modeGlyph(), 32, modeTooltipKey(), b -> toggleInspectMode())
    );
  }

  private void layoutPalettePanel()
  {
    int panelX     = paletteLeft();
    int innerWidth = paletteWidth() - 8;
    int quarter    = Math.max(25, innerWidth / 4);
    int toolY      = headerBottom() + 4;

    blockTab = addRenderableWidget(
        Button
            .builder(
                Component.translatable("blockgrep.builder.blocks"),
                b -> setPalette(BlockPalette.Mode.BLOCKS)
            )
            .bounds(panelX + 4, toolY, quarter, TOOL_ROW_HEIGHT)
            .build()
    );

    tagTab = addRenderableWidget(
        Button
            .builder(
                Component.translatable("blockgrep.builder.tags"),
                b -> setPalette(BlockPalette.Mode.TAGS)
            )
            .bounds(panelX + 4 + quarter, toolY, quarter, TOOL_ROW_HEIGHT)
            .build()
    );

    combineButton = addRenderableWidget(
        iconButton(
            "+",
            panelX + 4 + quarter * 2,
            toolY,
            quarter,
            "blockgrep.builder.combine",
            b -> {
              combine = !combine;
              updateButtons();
            }
        )
    );

    negateButton = addRenderableWidget(
        iconButton(
            "!",
            panelX + 4 + quarter * 3,
            toolY,
            Math.max(20, innerWidth - quarter * 3),
            "blockgrep.builder.negate",
            b -> {
              negateNew = !negateNew;
              updateButtons();
            }
        )
    );

    searchBox = new EditBox(
        font,
        panelX + 4,
        toolY + TOOL_ROW_HEIGHT + 3,
        innerWidth,
        SEARCH_HEIGHT,
        Component.translatable("blockgrep.builder.search")
    );

    searchBox.setHint(Component.translatable(palette.searchHintKey()));
    searchBox.setValue(searchQuery);

    searchBox.setResponder(text -> {
      searchQuery = text;
      palette.setQuery(text);
      updateButtons();
    });

    addRenderableWidget(searchBox);

    int navY = height - FOOTER_HEIGHT - 24;

    previousPage = addRenderableWidget(
        iconButton(
            "◀",
            panelX + 5,
            navY,
            28,
            "blockgrep.builder.page.previous",
            b -> changePage(-1)
        )
    );

    nextPage = addRenderableWidget(
        iconButton(
            "▶",
            panelX + paletteWidth() - 33,
            navY,
            28,
            "blockgrep.builder.page.next",
            b -> changePage(1)
        )
    );
  }

  private void layoutFooter()
  {
    addRenderableWidget(
        Button
            .builder(Component.translatable("blockgrep.builder.cancel"), b -> onClose())
            .bounds(width - 216, height - 26, 102, 20)
            .build()
    );

    saveButton = addRenderableWidget(
        Button
            .builder(Component.translatable("blockgrep.builder.save"), b -> save())
            .bounds(width - 108, height - 26, 102, 20)
            .build()
    );
  }

  /**
   * Creates the six floating grid-resize buttons.  Their positions are
   * recomputed every frame in {@link #positionAxisButtons()}, since they
   * track the volume as it orbits.
   */
  private void layoutAxisButtons()
  {
    String[] tooltips = {
        "blockgrep.builder.dimension.x.remove",
        "blockgrep.builder.dimension.x.add",
        "blockgrep.builder.dimension.y.remove",
        "blockgrep.builder.dimension.y.add",
        "blockgrep.builder.dimension.z.remove",
        "blockgrep.builder.dimension.z.add"
    };

    for(int i = 0; i < 6; i++) {
      final int axis   = i / 2;
      final int amount = (i % 2 == 0) ? -1 : 1;

      Button button = Button
                          .builder(
                              Component.literal(amount < 0 ? "−" : "+"),
                              b -> changeDimension(axis, amount)
                          )
                          .bounds(0, 0, AXIS_BUTTON_SIZE, AXIS_BUTTON_SIZE)
                          .tooltip(Tooltip.create(Component.translatable(tooltips[i])))
                          .build();

      axisButtons.add(addRenderableWidget(button));
    }
  }

  private Button iconButton(
      String label,
      int x,
      int y,
      int width,
      String tooltipKey,
      Button.OnPress action
  )
  {
    return Button
        .builder(Component.literal(label), action)
        .bounds(x, y, width, 20)
        .tooltip(Tooltip.create(Component.translatable(tooltipKey)))
        .build();
  }

  /**
   * Recomputes the rectangles the sub-components draw into.
   */
  private void updateBounds()
  {
    viewport.setBounds(
        PANEL_GAP,
        headerBottom(),
        paletteLeft() - PANEL_GAP,
        height - FOOTER_HEIGHT
    );

    activeStrip.setBounds(
        paletteLeft() + 6,
        headerBottom() + 4 + TOOL_ROW_HEIGHT + SEARCH_HEIGHT + 14,
        paletteLeft() + paletteWidth() - 6
    );
  }

  /**
   * Moves each floating axis button onto the edge it resizes, then keeps it
   * inside the viewport and clear of its neighbors.
   *
   * The anchors already place each pair outside the volume, so the
   * separation pass only has to resolve the cases where two edges project
   * close together — an edge-on view, or an axis only one cell deep.
   * Buttons are ordinary widgets, so they cannot be scissored or
   * depth-sorted against the voxels; clamping is what stops them escaping
   * the panel when the volume fills it.
   */
  private void positionAxisButtons()
  {
    if(axisButtons.size() < 6) return;

    List<PatternViewport.Anchor> anchors = viewport
                                               .axisHandles(pattern, sliceOnly, sliceY, AXIS_BUTTON_SIZE)
                                               .inOrder();

    int[] xs = new int[6];
    int[] ys = new int[6];

    for(int i = 0; i < 6; i++) {
      xs[i] = anchors.get(i).x();
      ys[i] = anchors.get(i).y();
    }

    separate(xs, ys);

    int half = AXIS_BUTTON_SIZE / 2;
    int minX = PANEL_GAP + AXIS_BUTTON_MARGIN + half;
    int maxX = paletteLeft() - PANEL_GAP - AXIS_BUTTON_MARGIN - half;
    int minY = headerBottom() + AXIS_BUTTON_MARGIN + half;
    int maxY = height - FOOTER_HEIGHT - AXIS_BUTTON_MARGIN - half;

    for(int pair = 0; pair < 3; pair++) {
      clampPair(xs, ys, pair, minX, maxX, minY, maxY);
    }

    for(int i = 0; i < 6; i++) {
      Button button = axisButtons.get(i);

      button.setX(xs[i] - half);
      button.setY(ys[i] - half);
    }
  }

  /**
   * Keeps one axis pair inside the viewport without letting its two buttons
   * merge.
   *
   * Clamping each button on its own collapses the pair whenever both run off
   * the same edge: they land on the identical boundary and overlap.  Instead
   * the outer button ({@code +}) takes the clamp, and the inner one
   * ({@code -}) is placed back along the pair's own direction from there, so
   * the pair stays legible and keeps its outward order.
   */
  private static void clampPair(
      int[] xs,
      int[] ys,
      int pair,
      int minX,
      int maxX,
      int minY,
      int maxY
  )
  {
    int inner = pair * 2;
    int outer = inner + 1;

    // The pair's own direction, measured before any clamping: inner to
    // outer is the outward direction, so its negation steps back inward.
    double dx = xs[outer] - xs[inner];
    double dy = ys[outer] - ys[inner];

    int clampedX = Math.clamp(xs[outer], minX, maxX);
    int clampedY = Math.clamp(ys[outer], minY, maxY);

    boolean moved = clampedX != xs[outer] || clampedY != ys[outer];

    xs[outer] = clampedX;
    ys[outer] = clampedY;

    if(!moved) {
      // The pair is fully on screen; the inner button only needs its own
      // clamp as a backstop.
      xs[inner] = Math.clamp(xs[inner], minX, maxX);
      ys[inner] = Math.clamp(ys[inner], minY, maxY);
      return;
    }

    double length = Math.hypot(dx, dy);

    if(length < 0.01) {
      // Degenerate: the two anchors coincided, so the pair has no direction
      // of its own.  Step inward from whichever edge caught it.
      dx     = (clampedX <= minX) ? 1 : (clampedX >= maxX ? -1 : 0);
      dy     = (clampedY <= minY) ? 1 : (clampedY >= maxY ? -1 : 0);
      length = Math.max(1, Math.hypot(dx, dy));
    }
    else {
      // Step against the outward direction.
      dx = -dx;
      dy = -dy;
    }

    xs[inner] = (int) Math.round(xs[outer] + dx / length * AXIS_BUTTON_SPACING);
    ys[inner] = (int) Math.round(ys[outer] + dy / length * AXIS_BUTTON_SPACING);

    // The inner button may now itself sit outside; nudge it back without
    // disturbing the outer one.
    xs[inner] = Math.clamp(xs[inner], minX, maxX);
    ys[inner] = Math.clamp(ys[inner], minY, maxY);
  }

  /**
   * Nudges overlapping buttons apart along the line between them.  A few
   * relaxation passes are plenty for six buttons, and it degrades gracefully
   * when the projection genuinely collapses them onto one point.
   */
  private static void separate(int[] xs, int[] ys)
  {
    nudgeCoincident(xs, ys);

    for(int pass = 0; pass < AXIS_SEPARATION_PASSES; pass++) {
      boolean moved = false;

      for(int a = 0; a < xs.length; a++) {
        for(int b = a + 1; b < xs.length; b++) {
          // The two buttons of one axis are spaced along that axis on
          // purpose; only push apart buttons belonging to different axes.
          if(a / 2 == b / 2) continue;

          int dx = xs[b] - xs[a];
          int dy = ys[b] - ys[a];

          double distance = Math.sqrt(dx * dx + dy * dy);
          if(distance >= AXIS_BUTTON_SPACING) continue;

          double nx = dx / distance;
          double ny = dy / distance;

          // Half the shortfall each, so the pair meets in the middle.
          double push = (AXIS_BUTTON_SPACING - distance) / 2.0;

          xs[a] -= (int) Math.round(nx * push);
          ys[a] -= (int) Math.round(ny * push);
          xs[b] += (int) Math.round(nx * push);
          ys[b] += (int) Math.round(ny * push);

          moved = true;
        }
      }

      if(!moved) return;
    }
  }

  /**
   * Fans apart any buttons sharing a position, so the relaxation above has a
   * direction to work with.
   *
   * Without this the projection's degenerate cases — a 1x1x1 pattern, or a
   * pitch so shallow that the Y faces land on the X and Z ones — leave every
   * pair with a zero-length separating vector, and they never come apart.
   * Seeding them on a small ring gives each pair a distinct bearing.
   */
  private static void nudgeCoincident(int[] xs, int[] ys)
  {
    for(int a = 0; a < xs.length; a++) {
      for(int b = a + 1; b < xs.length; b++) {
        if(a / 2 == b / 2) continue;

        if(xs[a] != xs[b] || ys[a] != ys[b]) continue;

        double angle = b * (2 * Math.PI / xs.length);

        xs[b] += (int) Math.round(Math.cos(angle) * AXIS_BUTTON_SPACING);
        ys[b] += (int) Math.round(Math.sin(angle) * AXIS_BUTTON_SPACING);
      }
    }
  }

  // -- layout metrics -----------------------------------------------------

  private int headerBottom()
  {
    return width < NARROW_WIDTH ? HEADER_WRAPPED : HEADER_BOTTOM;
  }

  private int paletteWidth()
  {
    return Math.clamp(width / 3, 126, 246);
  }

  private int paletteLeft()
  {
    return width - paletteWidth() - PANEL_GAP;
  }

  // -- editing ------------------------------------------------------------

  private CellRule activeRule()
  {
    return inspectMode
        ? pattern.cell(selected.x(), selected.y(), selected.z())
        : brush;
  }

  private void setActiveRule(CellRule rule)
  {
    if(inspectMode) {
      history.push(pattern);
      pattern.set(selected.x(), selected.y(), selected.z(), rule);
    }
    else {
      brush = rule;
    }

    updateButtons();
  }

  private void toggleInspectMode()
  {
    inspectMode = !inspectMode;
    activeStrip.resetPage();
    updateButtons();
  }

  private void toggleSlice()
  {
    sliceOnly = !sliceOnly;
    updateButtons();
  }

  private void changeLayer(int delta)
  {
    sliceOnly = true;
    sliceY    = Math.clamp(sliceY + delta, 0, pattern.sizeY() - 1);
    updateButtons();
  }

  private void setPalette(BlockPalette.Mode mode)
  {
    palette.setMode(mode);

    if(searchBox != null) {
      searchBox.setHint(Component.translatable(palette.searchHintKey()));
    }

    updateButtons();
  }

  private void changePage(int delta)
  {
    palette.changePage(delta);
    updateButtons();
  }

  /**
   * Sets one axis to an absolute size, from the grid fields.
   */
  private void setDimension(int axis, int size)
  {
    resizeAxis(axis, size);
  }

  /**
   * Grows or shrinks one axis, from the floating +/− buttons.
   */
  private void changeDimension(int axis, int amount)
  {
    int[] sizes = {pattern.sizeX(), pattern.sizeY(), pattern.sizeZ()};

    resizeAxis(axis, Math.clamp(sizes[axis] + amount, 1, EditablePattern.MAX_SIZE));
  }

  private void resizeAxis(int axis, int size)
  {
    int[] sizes = {pattern.sizeX(), pattern.sizeY(), pattern.sizeZ()};

    if(sizes[axis] == size) return;

    history.push(pattern);
    sizes[axis] = size;
    pattern.resize(sizes[0], sizes[1], sizes[2]);

    clampViewToPattern();
    updateButtons();
  }

  private void clearPattern()
  {
    if(pattern.significantCells() == 0) return;

    history.push(pattern);
    pattern.clear();
    updateButtons();
  }

  private void undo()
  {
    EditablePattern restored = history.undo(pattern);
    if(restored == null) return;

    pattern = restored;
    clampViewToPattern();
    updateButtons();
  }

  private void redo()
  {
    EditablePattern restored = history.redo(pattern);
    if(restored == null) return;

    pattern = restored;
    clampViewToPattern();
    updateButtons();
  }

  private void clampViewToPattern()
  {
    sliceY   = Math.clamp(sliceY, 0, pattern.sizeY() - 1);
    selected = selected.clampTo(pattern);
  }

  private void choose(PaletteEntry entry)
  {
    CellRule current = activeRule();
    CellRule next;

    if(entry.any()) {
      next = CellRule.any();
    }
    else {
      Term term = new Term(entry.id(), entry.tag(), negateNew);
      next      = combine ? current.add(term) : CellRule.of(term);
    }

    setActiveRule(next);
    activeStrip.showLastTerm(next);
  }

  // -- widget state -------------------------------------------------------

  private String modeGlyph()
  {
    return inspectMode ? "🔍" : "🖌";
  }

  private String modeTooltipKey()
  {
    return inspectMode
        ? "blockgrep.builder.mode.inspect.tooltip"
        : "blockgrep.builder.mode.paint.tooltip";
  }

  private void updateButtons()
  {
    if(undoButton == null) return;

    patternValid = pattern.isValid();

    undoButton.active = history.canUndo();
    redoButton.active = history.canRedo();
    saveButton.active = patternValid;

    viewButton.setMessage(Component.literal(sliceOnly ? "Y " + (sliceY + 1) : "3D"));
    layerDownButton.active = sliceY > 0;
    layerUpButton.active   = sliceY < pattern.sizeY() - 1;

    blockTab.active = palette.mode() != BlockPalette.Mode.BLOCKS;
    tagTab.active   = palette.mode() != BlockPalette.Mode.TAGS;

    combineButton.setMessage(Component.literal(combine ? "§a+" : "§7+"));
    negateButton.setMessage(Component.literal(negateNew ? "§c!" : "§7!"));

    captureButton.active = captureTarget() != null;

    modeButton.setMessage(Component.literal(modeGlyph()));
    modeButton.setTooltip(Tooltip.create(Component.translatable(modeTooltipKey())));

    palette.clampPage();
    previousPage.active = palette.page() > 0;
    nextPage.active     = palette.page() + 1 < palette.pageCount();

    gridFields.sync(pattern);
  }

  // -- persistence --------------------------------------------------------

  private void save()
  {
    if(!patternValid) return;

    target.spec = pattern.toSpec();
    committed   = true;

    PatternManager.save();
    minecraft.setScreenAndShow(parent);
  }

  @Override
  public void onClose()
  {
    if(discardOnCancel && !committed) {
      BlockGrepConfig.get().patterns.remove(target);
      PatternManager.save();
    }

    minecraft.setScreenAndShow(parent);
  }

  private BlockPos captureTarget()
  {
    if(minecraft.level == null
       || !(minecraft.hitResult instanceof BlockHitResult blockHit)
       || blockHit.getType() != HitResult.Type.BLOCK) {
      return null;
    }

    return blockHit.getBlockPos();
  }

  /**
   * Captures a volume around the block the player was looking at before the
   * screen opened.  The looked-at block maps to the selected voxel, which
   * makes the anchor controllable without coordinates or a text field.
   */
  private void captureFromWorld()
  {
    BlockPos anchor = captureTarget();
    if(anchor == null || minecraft.level == null) return;

    history.push(pattern);

    for(int y = 0; y < pattern.sizeY(); y++) {
      for(int z = 0; z < pattern.sizeZ(); z++) {
        for(int x = 0; x < pattern.sizeX(); x++) {
          BlockPos world = anchor.offset(
              x - selected.x(),
              y - selected.y(),
              z - selected.z()
          );

          Block block   = minecraft.level.getBlockState(world).getBlock();
          Identifier id = BuiltInRegistries.BLOCK.getKey(block);

          pattern.set(x, y, z, CellRule.of(new Term(id.toString(), false, false)));
        }
      }
    }

    updateButtons();
  }

  // -- keyboard -----------------------------------------------------------

  @Override
  public boolean keyPressed(KeyEvent event)
  {
    if(searchBox != null && searchBox.isFocused()) {
      if(event.isEscape()) {
        searchBox.setFocused(false);
        return true;
      }

      return super.keyPressed(event);
    }

    // The grid fields take digits and editing keys; none of the shortcuts
    // below should fire while one has focus.
    if(anyGridFieldFocused()) {
      return super.keyPressed(event);
    }

    if(event.hasControlDownWithQuirk() && event.key() == KEY_Z) {
      if(event.hasShiftDown())
        redo();
      else
        undo();

      return true;
    }

    if(event.hasControlDownWithQuirk() && event.key() == KEY_Y) {
      redo();
      return true;
    }

    if(event.isCycleFocus() || event.key() == KEY_M) {
      toggleInspectMode();
      return true;
    }

    if(event.key() == KEY_B) {
      if(inspectMode) toggleInspectMode();

      return true;
    }

    if(event.key() == KEY_S && !event.hasControlDownWithQuirk()) {
      if(!inspectMode) toggleInspectMode();

      return true;
    }

    if(event.key() == KEY_DELETE || event.key() == KEY_BACKSPACE) {
      history.push(pattern);
      pattern.set(selected.x(), selected.y(), selected.z(), CellRule.any());
      updateButtons();
      return true;
    }

    return super.keyPressed(event);
  }

  private boolean anyGridFieldFocused()
  {
    for(EditBox field: gridFields.fields()) {
      if(field != null && field.isFocused()) return true;
    }

    return false;
  }

  // -- mouse --------------------------------------------------------------

  @Override
  public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubled)
  {
    if(super.mouseClicked(event, doubled)) return true;

    if(activeStrip.contains(event.x(), event.y())) {
      return clickActiveRule(event);
    }

    if(palette.contains(event.x(), event.y())) {
      if(event.button() == MOUSE_LEFT) {
        PaletteEntry entry = palette.entryAt(event.x(), event.y());
        if(entry != null) choose(entry);
      }

      return true;
    }

    if(!viewport.contains(event.x(), event.y())) return false;

    CellPos hit = viewport.pick(event.x(), event.y(), pattern, sliceOnly, sliceY);

    if(hit == null) {
      orbiting = true;
      return true;
    }

    switch(effectiveButton(event)) {
      case MOUSE_LEFT -> paintOrSelect(event, hit);
      case MOUSE_RIGHT -> pickBrush(hit);
      case MOUSE_MIDDLE -> eraseCell(hit);
      default -> {
      }
    }

    updateButtons();
    return true;
  }

  private void paintOrSelect(MouseButtonEvent event, CellPos hit)
  {
    selected = hit;

    if(inspectMode) {
      activeStrip.resetPage();
      return;
    }

    history.push(pattern);
    pattern.set(
        hit.x(),
        hit.y(),
        hit.z(),
        event.hasShiftDown() ? CellRule.any() : brush
    );

    painting    = true;
    lastPainted = hit;
  }

  private void pickBrush(CellPos hit)
  {
    selected = hit;
    brush    = pattern.cell(hit.x(), hit.y(), hit.z());
    activeStrip.resetPage();
  }

  private void eraseCell(CellPos hit)
  {
    history.push(pattern);
    selected = hit;
    pattern.set(hit.x(), hit.y(), hit.z(), CellRule.any());
  }

  private boolean clickActiveRule(MouseButtonEvent event)
  {
    CellRule current = activeRule();

    int arrow = activeStrip.arrowAt(event.x(), current);
    if(arrow != 0) {
      activeStrip.turnPage(arrow, current);
      return true;
    }

    int termIndex = activeStrip.termAt(event.x(), current);
    if(termIndex < 0 || current.isAny()) return true;

    switch(effectiveButton(event)) {
      case MOUSE_LEFT -> setActiveRule(current.toggle(termIndex));

      case MOUSE_RIGHT -> {
        setActiveRule(current.remove(termIndex));
        activeStrip.clampPage(activeRule());
      }

      default -> {
      }
    }

    return true;
  }

  @Override
  public boolean mouseDragged(@NonNull MouseButtonEvent event, double dragX, double dragY)
  {
    if(orbiting) {
      viewport.camera().orbit(dragX, dragY);
      return true;
    }

    if(painting && !inspectMode) {
      CellPos hit = viewport.pick(event.x(), event.y(), pattern, sliceOnly, sliceY);

      if(hit != null && !hit.equals(lastPainted)) {
        selected = hit;
        pattern.set(
            hit.x(),
            hit.y(),
            hit.z(),
            event.hasShiftDown() ? CellRule.any() : brush
        );

        lastPainted = hit;
        updateButtons();
      }

      return true;
    }

    return super.mouseDragged(event, dragX, dragY);
  }

  @Override
  public boolean mouseReleased(@NonNull MouseButtonEvent event)
  {
    orbiting    = false;
    painting    = false;
    lastPainted = null;

    return super.mouseReleased(event);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY)
  {
    if(palette.contains(mouseX, mouseY)) {
      changePage(deltaY < 0 ? 1 : -1);
      return true;
    }

    if(viewport.contains(mouseX, mouseY)) {
      viewport.camera().zoomBy(deltaY);
      return true;
    }

    return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
  }

  // -- rendering ----------------------------------------------------------

  @Override
  public void extractRenderState(
      @NonNull GuiGraphicsExtractor graphics,
      int mouseX,
      int mouseY,
      float partial
  )
  {
    updateBounds();

    CellPos hovered = viewport.render(
        graphics,
        pattern,
        sliceOnly,
        sliceY,
        selected,
        inspectMode,
        mouseX,
        mouseY
    );

    renderPalettePanel(graphics, mouseX, mouseY);

    // Must run before the widgets draw, so they land where the volume is
    // this frame rather than one frame behind.
    positionAxisButtons();

    super.extractRenderState(graphics, mouseX, mouseY, partial);

    graphics.centeredText(font, title, width / 2, 7, 0xFFFFFFFF);

    // Per-frame rather than only on edits: this is where an abandoned
    // half-typed size gets reconciled, and focus can be lost on any frame.
    gridFields.sync(pattern);
    gridFields.render(graphics);

    renderStatus(graphics);

    if(hovered != null) {
      renderCellTooltip(graphics, hovered, mouseX, mouseY);
    }
  }

  private void renderPalettePanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY)
  {
    palette.render(
        graphics,
        paletteLeft(),
        headerBottom(),
        paletteLeft() + paletteWidth(),
        height - FOOTER_HEIGHT,
        activeStrip.bottom() + 8,
        mouseX,
        mouseY,
        width
    );

    Component header = inspectMode
        ? Component.translatable(
              "blockgrep.builder.selected.voxel",
              selected.x() + 1,
              selected.y() + 1,
              selected.z() + 1
          )
        : Component.translatable("blockgrep.builder.brush");

    activeStrip.render(graphics, activeRule(), header, mouseX, mouseY, width);
  }

  private void renderStatus(GuiGraphicsExtractor graphics)
  {
    Component dimensions = width < DIMENSION_TEXT_WIDTH
        ? Component.literal(
              pattern.sizeX() + " × " + pattern.sizeY() + " × " + pattern.sizeZ()
          )
        : Component.translatable(
              "blockgrep.builder.dimensions",
              pattern.sizeX(),
              pattern.sizeY(),
              pattern.sizeZ(),
              pattern.significantCells()
          );

    graphics.text(
        font,
        dimensions,
        PANEL_GAP,
        height - 21,
        patternValid ? VALID_TEXT_COLOR : INVALID_TEXT_COLOR
    );

    if(recoveredInvalidSource && history.isEmpty()) {
      graphics.centeredText(
          font,
          Component.translatable("blockgrep.builder.recovered"),
          viewport.centerX(),
          headerBottom() + 6,
          WARNING_TEXT_COLOR
      );
    }
    else if(!patternValid) {
      graphics.centeredText(
          font,
          Component.translatable("blockgrep.builder.need.cell"),
          viewport.centerX(),
          headerBottom() + 6,
          INVALID_TEXT_COLOR
      );
    }
  }

  private void renderCellTooltip(
      GuiGraphicsExtractor graphics,
      CellPos position,
      int mouseX,
      int mouseY
  )
  {
    CellRule rule = pattern.cell(position.x(), position.y(), position.z());

    String key = inspectMode
        ? "blockgrep.builder.cell.tooltip.inspect"
        : "blockgrep.builder.cell.tooltip.paint";

    Component text = Component.translatable(
        key,
        position.x() + 1,
        position.y() + 1,
        position.z() + 1,
        icons.describeRule(rule)
    );

    graphics.setTooltipForNextFrame(
        font,
        font.split(text, Math.max(MIN_TOOLTIP_WIDTH, width / 2)),
        mouseX,
        mouseY
    );
  }

  // -- input constants ----------------------------------------------------

  /**
   * Mouse buttons became 1-based when Minecraft 26.3 replaced GLFW with SDL,
   * and SDL orders middle before right where GLFW ordered right before
   * middle.  See {@code InputConstants.MOUSE_BUTTON_*}.
   */
  private static final int MOUSE_LEFT   = 1;
  private static final int MOUSE_MIDDLE = 2;
  private static final int MOUSE_RIGHT  = 3;

  private static final int KEY_B         = 66;
  private static final int KEY_M         = 77;
  private static final int KEY_S         = 83;
  private static final int KEY_Y         = 89;
  private static final int KEY_Z         = 90;
  private static final int KEY_BACKSPACE = 259;
  private static final int KEY_DELETE    = 261;

  /**
   * Maps modified left-clicks onto the middle and right buttons.
   *
   * Trackpads and single-button mice cannot produce a middle click at all,
   * and right-click is awkward on a laptop, so shift and the platform's edit
   * modifier (Cmd on macOS, Ctrl elsewhere) stand in for them.
   */
  private static int effectiveButton(MouseButtonEvent event)
  {
    if(event.button() != MOUSE_LEFT) return event.button();

    if(event.hasShiftDown()) return MOUSE_RIGHT;

    if(event.hasControlDownWithQuirk()) return MOUSE_MIDDLE;

    return MOUSE_LEFT;
  }
}
