package cx.gid.minecraft.blockgrep.client.config;

import cx.gid.minecraft.blockgrep.client.config.EditablePattern.CellRule;
import cx.gid.minecraft.blockgrep.client.config.EditablePattern.Term;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A no-syntax voxel editor for Block Grep patterns.
 *
 * The view is an orbitable orthographic projection.  Exact block rules use the
 * game's own item model, while wildcard, tag, alternation and negation rules add
 * distinct overlays.  It deliberately remains in the ordinary GUI extraction
 * pipeline instead of reaching into the world renderer; that makes the same
 * implementation work on Fabric and NeoForge and keeps it compatible with the
 * render-state split introduced in Minecraft 26.
 */
public final class PatternEditorScreen extends Screen {

    private static final int HEADER_BOTTOM = 44;
    private static final int FOOTER_HEIGHT = 30;
    private static final int PANEL_GAP = 6;
    private static final int TILE = 22;
    private static final int TOOL_ROW_HEIGHT = 20;
    private static final int BRUSH_ROW_HEIGHT = 25;
    private static final int SEARCH_HEIGHT = 16;
    private static final int MAX_HISTORY = 64;

    private final Screen parent;
    private final SavedPattern target;
    private final boolean discardOnCancel;
    private boolean committed;
    private EditablePattern pattern;
    private final boolean recoveredInvalidSource;
    private boolean patternValid;

    private CellRule brush = CellRule.of(new Term("minecraft:stone", false, false));
    private boolean combine;
    private boolean negateNew;
    private boolean inspectMode;
    private PaletteMode paletteMode = PaletteMode.BLOCKS;
    private int palettePage;
    private int activePage;
    private String searchQuery = "";

    private final List<PaletteEntry> blocks;
    private final List<PaletteEntry> tags;
    private List<PaletteEntry> filteredBlocks;
    private List<PaletteEntry> filteredTags;
    private final Map<String, Block> tagRepresentatives = new HashMap<>();

    private final Deque<EditablePattern> undo = new ArrayDeque<>();
    private final Deque<EditablePattern> redo = new ArrayDeque<>();

    private float yaw = -0.72f;
    private float pitch = 0.52f;
    private float zoom = 1.0f;
    private boolean sliceOnly;
    private int sliceY;
    private CellPos selected = new CellPos(0, 0, 0);

    private boolean orbiting;
    private boolean painting;
    private CellPos lastPainted;

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
    private EditBox searchBox;
    private Button previousPage;
    private Button nextPage;

    public PatternEditorScreen(Screen parent, SavedPattern target) {
        this(parent, target, false);
    }

    public PatternEditorScreen(Screen parent, SavedPattern target, boolean discardOnCancel) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font,
            Component.translatable("blockgrep.builder.title"));
        this.parent = parent;
        this.target = target;
        this.discardOnCancel = discardOnCancel;

        EditablePattern parsed;
        boolean recovered = false;
        try {
            parsed = EditablePattern.parse(target.spec);
        } catch (RuntimeException e) {
            // Keep the original SavedPattern untouched until Save.  The player
            // can safely inspect this recovery draft and Cancel without losing a
            // hand-edited or now-invalid config entry.
            parsed = new EditablePattern(1, 1, 1);
            recovered = true;
        }
        this.pattern = parsed;
        this.recoveredInvalidSource = recovered;
        this.blocks = collectBlocks();
        this.tags = collectTags();
        this.filteredBlocks = this.blocks;
        this.filteredTags = this.tags;
        this.sliceY = 0;
        this.inspectMode = false;
        this.activePage = 0;
    }

    @Override
    protected void init() {
        int x = PANEL_GAP;
        int y = 21;
        for (int axis = 0; axis < 3; axis++) {
            final int changedAxis = axis;
            addRenderableWidget(iconButton(axisName(axis) + "−", x, y, 27,
                "blockgrep.builder.dimension.remove", b -> changeDimension(changedAxis, -1)));
            x += 28;
            addRenderableWidget(iconButton(axisName(axis) + "+", x, y, 27,
                "blockgrep.builder.dimension.add", b -> changeDimension(changedAxis, 1)));
            x += 31;
        }

        // At large GUI scales the logical screen can be only ~320-640 px wide.
        // Keep every tool reachable by wrapping the non-dimension tools instead
        // of letting them disappear under the palette panel.
        if (width < 720) {
            x = PANEL_GAP;
            y = 42;
        }

        undoButton = addRenderableWidget(iconButton("↶", x, y, 27,
            "blockgrep.builder.undo", b -> undo()));
        x += 29;
        redoButton = addRenderableWidget(iconButton("↷", x, y, 27,
            "blockgrep.builder.redo", b -> redo()));
        x += 32;

        viewButton = addRenderableWidget(iconButton("3D", x, y, 39,
            "blockgrep.builder.view", b -> {
                sliceOnly = !sliceOnly;
                updateButtons();
            }));
        x += 41;
        layerDownButton = addRenderableWidget(iconButton("Y−", x, y, 27,
            "blockgrep.builder.layer.down", b -> {
                sliceOnly = true;
                sliceY = Math.max(0, sliceY - 1);
                updateButtons();
            }));
        x += 28;
        layerUpButton = addRenderableWidget(iconButton("Y+", x, y, 27,
            "blockgrep.builder.layer.up", b -> {
                sliceOnly = true;
                sliceY = Math.min(pattern.sizeY() - 1, sliceY + 1);
                updateButtons();
            }));
        x += 31;
        addRenderableWidget(iconButton("◎", x, y, 27,
            "blockgrep.builder.center", b -> resetCamera()));
        x += 29;
        addRenderableWidget(iconButton("⌫", x, y, 27,
            "blockgrep.builder.clear", b -> clearPattern()));
        x += 29;
        captureButton = addRenderableWidget(iconButton("▣", x, y, 27,
            "blockgrep.builder.capture", b -> captureFromWorld()));
        x += 31;

        modeButton = addRenderableWidget(iconButton(inspectMode ? "🔍" : "🖌", x, y, 32,
            inspectMode ? "blockgrep.builder.mode.inspect.tooltip" : "blockgrep.builder.mode.paint.tooltip",
            b -> toggleInspectMode()));

        int panelX = paletteLeft();
        int innerWidth = paletteWidth() - 8;
        int quarter = Math.max(25, innerWidth / 4);
        int toolY = headerBottom() + 4;
        blockTab = addRenderableWidget(Button.builder(
                Component.translatable("blockgrep.builder.blocks"), b -> setPalette(PaletteMode.BLOCKS))
            .bounds(panelX + 4, toolY, quarter, TOOL_ROW_HEIGHT).build());
        tagTab = addRenderableWidget(Button.builder(
                Component.translatable("blockgrep.builder.tags"), b -> setPalette(PaletteMode.TAGS))
            .bounds(panelX + 4 + quarter, toolY, quarter, TOOL_ROW_HEIGHT).build());
        combineButton = addRenderableWidget(iconButton("+", panelX + 4 + quarter * 2,
            toolY, quarter, "blockgrep.builder.combine", b -> {
                combine = !combine;
                updateButtons();
            }));
        negateButton = addRenderableWidget(iconButton("!", panelX + 4 + quarter * 3,
            toolY, Math.max(20, innerWidth - quarter * 3), "blockgrep.builder.negate", b -> {
                negateNew = !negateNew;
                updateButtons();
            }));

        int searchY = toolY + TOOL_ROW_HEIGHT + 3;
        searchBox = new EditBox(font, panelX + 4, searchY, innerWidth, SEARCH_HEIGHT,
            Component.translatable("blockgrep.builder.search"));
        searchBox.setHint(Component.translatable(paletteMode == PaletteMode.BLOCKS
            ? "blockgrep.builder.search.blocks"
            : "blockgrep.builder.search.tags"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(text -> {
            searchQuery = text;
            recomputeFilteredEntries();
        });
        addRenderableWidget(searchBox);

        int navY = height - FOOTER_HEIGHT - 24;
        previousPage = addRenderableWidget(iconButton("◀", panelX + 5, navY, 28,
            "blockgrep.builder.page.previous", b -> changePage(-1)));
        nextPage = addRenderableWidget(iconButton("▶", panelX + paletteWidth() - 33,
            navY, 28, "blockgrep.builder.page.next", b -> changePage(1)));

        addRenderableWidget(Button.builder(Component.translatable("blockgrep.builder.cancel"),
                b -> onClose())
            .bounds(width - 216, height - 26, 102, 20).build());
        saveButton = addRenderableWidget(Button.builder(
                Component.translatable("blockgrep.builder.save"), b -> save())
            .bounds(width - 108, height - 26, 102, 20).build());

        recomputeFilteredEntries();
        updateButtons();
    }

    private Button iconButton(String label, int x, int y, int width,
                              String tooltip, Button.OnPress action) {
        return Button.builder(Component.literal(label), action)
            .bounds(x, y, width, 20)
            .tooltip(Tooltip.create(Component.translatable(tooltip)))
            .build();
    }

    private static String axisName(int axis) {
        return switch (axis) {
            case 0 -> "X";
            case 1 -> "Y";
            default -> "Z";
        };
    }

    private void toggleInspectMode() {
        inspectMode = !inspectMode;
        activePage = 0;
        updateButtons();
    }

    private CellRule activeRule() {
        return inspectMode ? pattern.cell(selected.x, selected.y, selected.z) : brush;
    }

    private void setActiveRule(CellRule rule) {
        if (inspectMode) {
            pushUndo();
            pattern.set(selected.x, selected.y, selected.z, rule);
        } else {
            brush = rule;
        }
        updateButtons();
    }

    private void setPalette(PaletteMode mode) {
        paletteMode = mode;
        palettePage = 0;
        if (searchBox != null) {
            searchBox.setHint(Component.translatable(mode == PaletteMode.BLOCKS
                ? "blockgrep.builder.search.blocks"
                : "blockgrep.builder.search.tags"));
        }
        recomputeFilteredEntries();
        updateButtons();
    }

    private void recomputeFilteredEntries() {
        if (searchQuery == null || searchQuery.isBlank()) {
            filteredBlocks = blocks;
            filteredTags = tags;
        } else {
            String q = searchQuery.trim().toLowerCase(Locale.ROOT);
            filteredBlocks = blocks.stream()
                .filter(entry -> entry.matches(q))
                .toList();
            filteredTags = tags.stream()
                .filter(entry -> entry.matches(q))
                .toList();
        }
        palettePage = 0;
        updateButtons();
    }

    private void changeDimension(int axis, int amount) {
        int[] sizes = {pattern.sizeX(), pattern.sizeY(), pattern.sizeZ()};
        int changed = Math.clamp(sizes[axis] + amount, 1, EditablePattern.MAX_SIZE);
        if (changed == sizes[axis]) {
            return;
        }
        pushUndo();
        sizes[axis] = changed;
        pattern.resize(sizes[0], sizes[1], sizes[2]);
        selected = new CellPos(
            Math.min(selected.x, pattern.sizeX() - 1),
            Math.min(selected.y, pattern.sizeY() - 1),
            Math.min(selected.z, pattern.sizeZ() - 1));
        sliceY = Math.min(sliceY, pattern.sizeY() - 1);
        updateButtons();
    }

    private void clearPattern() {
        if (pattern.significantCells() == 0) {
            return;
        }
        pushUndo();
        pattern.clear();
        updateButtons();
    }

    private void resetCamera() {
        yaw = -0.72f;
        pitch = 0.52f;
        zoom = 1.0f;
    }

    private void pushUndo() {
        undo.push(pattern.copy());
        while (undo.size() > MAX_HISTORY) {
            undo.removeLast();
        }
        redo.clear();
        updateButtons();
    }

    private void undo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(pattern.copy());
        pattern = undo.pop();
        clampViewToPattern();
        updateButtons();
    }

    private void redo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(pattern.copy());
        pattern = redo.pop();
        clampViewToPattern();
        updateButtons();
    }

    private void clampViewToPattern() {
        sliceY = Math.clamp(sliceY, 0, pattern.sizeY() - 1);
        selected = new CellPos(
            Math.clamp(selected.x, 0, pattern.sizeX() - 1),
            Math.clamp(selected.y, 0, pattern.sizeY() - 1),
            Math.clamp(selected.z, 0, pattern.sizeZ() - 1));
    }

    private void updateButtons() {
        if (undoButton == null) {
            return;
        }
        patternValid = pattern.isValid();
        undoButton.active = !undo.isEmpty();
        redoButton.active = !redo.isEmpty();
        saveButton.active = patternValid;
        viewButton.setMessage(Component.literal(sliceOnly ? "Y " + (sliceY + 1) : "3D"));
        layerDownButton.active = sliceY > 0;
        layerUpButton.active = sliceY < pattern.sizeY() - 1;
        blockTab.active = paletteMode != PaletteMode.BLOCKS;
        tagTab.active = paletteMode != PaletteMode.TAGS;
        combineButton.setMessage(Component.literal(combine ? "§a+" : "§7+"));
        negateButton.setMessage(Component.literal(negateNew ? "§c!" : "§7!"));
        captureButton.active = captureTarget() != null;

        if (modeButton != null) {
            modeButton.setMessage(Component.literal(inspectMode ? "🔍" : "🖌"));
            modeButton.setTooltip(Tooltip.create(Component.translatable(inspectMode
                ? "blockgrep.builder.mode.inspect.tooltip"
                : "blockgrep.builder.mode.paint.tooltip")));
        }

        int pages = pageCount();
        palettePage = Math.clamp(palettePage, 0, Math.max(0, pages - 1));
        previousPage.active = palettePage > 0;
        nextPage.active = palettePage + 1 < pages;
    }

    private void save() {
        if (!patternValid) {
            return;
        }
        target.spec = pattern.toSpec();
        committed = true;
        PatternManager.save();
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void onClose() {
        if (discardOnCancel && !committed) {
            BlockGrepConfig.get().patterns.remove(target);
            PatternManager.save();
        }
        minecraft.setScreenAndShow(parent);
    }

    private BlockPos captureTarget() {
        if (minecraft.level == null || !(minecraft.hitResult instanceof BlockHitResult blockHit)
                || blockHit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return blockHit.getBlockPos();
    }

    /**
     * Captures a volume around the block the player was looking at before the
     * screen opened.  The looked-at block maps to the selected voxel, which makes
     * the anchor controllable without coordinates or a text field.
     */
    private void captureFromWorld() {
        BlockPos anchor = captureTarget();
        if (anchor == null || minecraft.level == null) {
            return;
        }
        pushUndo();
        for (int y = 0; y < pattern.sizeY(); y++) {
            for (int z = 0; z < pattern.sizeZ(); z++) {
                for (int x = 0; x < pattern.sizeX(); x++) {
                    BlockPos world = anchor.offset(
                        x - selected.x, y - selected.y, z - selected.z);
                    Block block = minecraft.level.getBlockState(world).getBlock();
                    Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                    pattern.set(x, y, z, CellRule.of(new Term(id.toString(), false, false)));
                }
            }
        }
        updateButtons();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (searchBox != null && searchBox.isFocused()) {
            if (event.key() == 256) { // Escape
                searchBox.setFocused(false);
                return true;
            }
            return super.keyPressed(event);
        }
        if (event.key() == 258 || event.key() == 77) { // Tab or M
            toggleInspectMode();
            return true;
        }
        if (event.key() == 66) { // B -> Paint mode
            if (inspectMode) {
                toggleInspectMode();
            }
            return true;
        }
        if (event.key() == 83 && !event.hasControlDown()) { // S -> Inspect mode
            if (!inspectMode) {
                toggleInspectMode();
            }
            return true;
        }
        if (event.hasControlDown() && event.key() == 90) { // Z
            if (event.hasShiftDown()) {
                redo();
            } else {
                undo();
            }
            return true;
        }
        if (event.hasControlDown() && event.key() == 89) { // Y
            redo();
            return true;
        }
        if (event.key() == 261 || event.key() == 259) { // Delete / Backspace
            pushUndo();
            pattern.set(selected.x, selected.y, selected.z, CellRule.any());
            updateButtons();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }

        if (insideActive(event.x(), event.y())) {
            return clickActiveRule(event);
        }
        if (insidePalette(event.x(), event.y())) {
            if (event.button() == 0) {
                PaletteEntry entry = paletteAt(event.x(), event.y());
                if (entry != null) {
                    choose(entry);
                }
            }
            return true;
        }
        if (!insideViewport(event.x(), event.y())) {
            return false;
        }

        CellPos hit = pickCell(event.x(), event.y());
        if (event.button() == 2 && hit != null) {
            selected = hit;
            brush = pattern.cell(hit.x, hit.y, hit.z);
            activePage = 0;
            updateButtons();
            return true;
        }
        if (event.button() == 1) {
            if (hit != null) {
                pushUndo();
                selected = hit;
                pattern.set(hit.x, hit.y, hit.z, CellRule.any());
                updateButtons();
            } else {
                orbiting = true;
            }
            return true;
        }
        if (event.button() == 0 && hit != null) {
            selected = hit;
            if (inspectMode) {
                activePage = 0;
                updateButtons();
                return true;
            } else {
                pushUndo();
                pattern.set(hit.x, hit.y, hit.z,
                    event.hasShiftDown() ? CellRule.any() : brush);
                painting = true;
                lastPainted = hit;
                updateButtons();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (orbiting) {
            yaw += (float) dragX * 0.018f;
            pitch = Math.clamp(pitch - (float) dragY * 0.015f, -1.25f, 1.25f);
            return true;
        }
        if (painting && !inspectMode) {
            CellPos hit = pickCell(event.x(), event.y());
            if (hit != null && !hit.equals(lastPainted)) {
                selected = hit;
                pattern.set(hit.x, hit.y, hit.z,
                    event.hasShiftDown() ? CellRule.any() : brush);
                lastPainted = hit;
                updateButtons();
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        orbiting = false;
        painting = false;
        lastPainted = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (insidePalette(mouseX, mouseY)) {
            changePage(deltaY < 0 ? 1 : -1);
            return true;
        }
        if (insideViewport(mouseX, mouseY)) {
            zoom = Math.clamp((float) (zoom * Math.pow(1.12, deltaY)), 0.45f, 2.4f);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    private void changePage(int delta) {
        int pages = pageCount();
        palettePage = Math.clamp(palettePage + delta, 0, Math.max(0, pages - 1));
        updateButtons();
    }

    private void choose(PaletteEntry entry) {
        CellRule current = activeRule();
        CellRule next;
        if (entry.any) {
            next = CellRule.any();
        } else {
            Term selectedTerm = new Term(entry.id, entry.tag, negateNew);
            next = combine ? current.add(selectedTerm) : CellRule.of(selectedTerm);
        }
        setActiveRule(next);
        activePage = Math.max(0, (next.terms().size() - 1) / activeSlots());
    }

    private boolean clickActiveRule(MouseButtonEvent event) {
        int pageArrow = activeArrowAt(event.x(), event.y());
        if (pageArrow != 0) {
            activePage = Math.clamp(activePage + pageArrow, 0, Math.max(0, activePageCount() - 1));
            return true;
        }
        int termIndex = activeTermAt(event.x(), event.y());
        CellRule current = activeRule();
        if (termIndex < 0 || current.isAny()) {
            return true;
        }
        if (event.button() == 0) {
            setActiveRule(current.toggle(termIndex));
        } else if (event.button() == 1) {
            setActiveRule(current.remove(termIndex));
            activePage = Math.min(activePage, Math.max(0, activePageCount() - 1));
        }
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partial) {
        renderViewport(graphics, mouseX, mouseY);
        renderPalette(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        graphics.centeredText(font, title, width / 2, 7, 0xFFFFFFFF);
        Component dimensions = width < 520
            ? Component.literal(pattern.sizeX() + " × " + pattern.sizeY() + " × " + pattern.sizeZ())
            : Component.translatable("blockgrep.builder.dimensions",
                pattern.sizeX(), pattern.sizeY(), pattern.sizeZ(), pattern.significantCells());
        graphics.text(font, dimensions, PANEL_GAP, height - 21,
            patternValid ? 0xFFA0A0A0 : 0xFFFF7070);
        if (recoveredInvalidSource && undo.isEmpty()) {
            graphics.centeredText(font, Component.translatable("blockgrep.builder.recovered"),
                viewportCenterX(), headerBottom() + 6, 0xFFFFB050);
        } else if (!patternValid) {
            graphics.centeredText(font, Component.translatable("blockgrep.builder.need.cell"),
                viewportCenterX(), headerBottom() + 6, 0xFFFF7070);
        }
    }

    private void renderViewport(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = PANEL_GAP;
        int top = headerBottom();
        int right = paletteLeft() - PANEL_GAP;
        int bottom = height - FOOTER_HEIGHT;
        graphics.fill(left, top, right, bottom, 0xB010141B);
        graphics.outline(left, top, right - left, bottom - top, 0x805A708A);
        graphics.enableScissor(left + 1, top + 1, right - 1, bottom - 1);

        drawBounds(graphics);
        List<ProjectedCell> cells = projectedCells();
        CellPos hovered = insideViewport(mouseX, mouseY) ? pickCell(mouseX, mouseY, cells) : null;
        float iconScale = Math.clamp(cellSpacing() / 17.0f, 0.65f, 2.25f);
        int iconPixels = Math.max(11, Math.round(16 * iconScale));

        for (ProjectedCell projected : cells) {
            CellRule rule = projected.rule;
            int px = Math.round(projected.screenX);
            int py = Math.round(projected.screenY);
            if (rule.isAny()) {
                int radius = Math.max(3, iconPixels / 3);
                graphics.fill(px - radius, py - radius, px + radius, py + radius,
                    0x183A6BFF);
                graphics.outline(px - radius, py - radius, radius * 2, radius * 2,
                    0x504F75FF);
                if (cellSpacing() > 19) {
                    graphics.centeredText(font, "?", px, py - 4, 0xA0B9C8FF);
                }
            } else {
                renderRuleIcon(graphics, rule, px, py, iconScale);
            }

            CellPos position = projected.position();
            if (position.equals(selected) || position.equals(hovered)) {
                int radius = Math.max(7, iconPixels / 2 + 2);
                int color = position.equals(hovered) ? 0xFFFFFFFF : (inspectMode ? 0xFFFFD700 : 0xFF65D7FF);
                graphics.outline(px - radius, py - radius, radius * 2, radius * 2, color);
            }
        }

        drawAxisGizmo(graphics);
        graphics.disableScissor();

        if (hovered != null) {
            CellRule rule = pattern.cell(hovered.x, hovered.y, hovered.z);
            graphics.setTooltipForNextFrame(font, font.split(describeCell(hovered, rule), Math.max(240, width / 2)), mouseX, mouseY);
        }
    }

    private void drawBounds(GuiGraphicsExtractor graphics) {
        float minY = sliceOnly ? sliceY : 0;
        float maxY = sliceOnly ? sliceY + 1 : pattern.sizeY();
        float[][] points = new float[8][];
        int at = 0;
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    points[at++] = projectCorner(
                        x == 0 ? 0 : pattern.sizeX(),
                        y == 0 ? minY : maxY,
                        z == 0 ? 0 : pattern.sizeZ());
                }
            }
        }
        int[][] edges = {
            {0,1},{0,2},{1,3},{2,3},
            {4,5},{4,6},{5,7},{6,7},
            {0,4},{1,5},{2,6},{3,7}
        };
        for (int[] edge : edges) {
            line(graphics, points[edge[0]][0], points[edge[0]][1],
                points[edge[1]][0], points[edge[1]][1], 0x704FA9C8);
        }
    }

    private void drawAxisGizmo(GuiGraphicsExtractor graphics) {
        int ox = PANEL_GAP + 22;
        int oy = headerBottom() + 25;
        float length = 18;
        float[] origin = projectVector(0, 0, 0, length);
        float[] x = projectVector(1, 0, 0, length);
        float[] y = projectVector(0, 1, 0, length);
        float[] z = projectVector(0, 0, 1, length);
        line(graphics, ox + origin[0], oy + origin[1], ox + x[0], oy + x[1], 0xFFFF5555);
        line(graphics, ox + origin[0], oy + origin[1], ox + y[0], oy + y[1], 0xFF55FF55);
        line(graphics, ox + origin[0], oy + origin[1], ox + z[0], oy + z[1], 0xFF5599FF);
        graphics.text(font, "X", Math.round(ox + x[0]), Math.round(oy + x[1] - 4), 0xFFFF7777);
        graphics.text(font, "Y", Math.round(ox + y[0]), Math.round(oy + y[1] - 4), 0xFF77FF77);
        graphics.text(font, "Z", Math.round(ox + z[0]), Math.round(oy + z[1] - 4), 0xFF77AAFF);
    }

    /** Cheap anti-gap line made from GUI rectangles; no world render pass needed. */
    private static void line(GuiGraphicsExtractor graphics, float x0, float y0,
                             float x1, float y1, int color) {
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0))));
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            int x = Math.round(x0 + (x1 - x0) * t);
            int y = Math.round(y0 + (y1 - y0) * t);
            graphics.fill(x, y, x + 2, y + 2, color);
        }
    }

    private void renderPalette(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = paletteLeft();
        int right = left + paletteWidth();
        int bottom = height - FOOTER_HEIGHT;
        graphics.fill(left, headerBottom(), right, bottom, 0xD0121820);
        graphics.outline(left, headerBottom(), paletteWidth(), bottom - headerBottom(), 0x805A708A);

        renderActiveRule(graphics, mouseX, mouseY);

        PaletteBounds grid = paletteBounds();
        List<PaletteEntry> entries = paletteEntries();
        if (entries.isEmpty()) {
            graphics.centeredText(font, Component.translatable("blockgrep.builder.search.empty"),
                left + paletteWidth() / 2, grid.top + 20, 0xFF808080);
        } else {
            int from = palettePage * grid.capacity();
            int to = Math.min(entries.size(), from + grid.capacity());
            for (int i = from; i < to; i++) {
                int local = i - from;
                int col = local % grid.columns;
                int row = local / grid.columns;
                int x = grid.left + col * TILE;
                int y = grid.top + row * TILE;
                boolean hovered = mouseX >= x && mouseX < x + TILE
                    && mouseY >= y && mouseY < y + TILE;
                graphics.fill(x, y, x + TILE - 2, y + TILE - 2,
                    hovered ? 0x605E86A8 : 0x302C3A49);
                PaletteEntry entry = entries.get(i);
                renderPaletteEntry(graphics, entry, x + 10, y + 10, 1.0f);
                if (hovered) {
                    graphics.outline(x, y, TILE - 2, TILE - 2, 0xFFFFFFFF);
                    graphics.setTooltipForNextFrame(font, font.split(entry.tooltip(), Math.max(240, width / 2)), mouseX, mouseY);
                }
            }
        }

        int pages = Math.max(1, pageCount());
        graphics.centeredText(font, Component.translatable("blockgrep.builder.page",
                palettePage + 1, pages),
            left + paletteWidth() / 2, height - FOOTER_HEIGHT - 18, 0xFFA0A0A0);
    }

    private void renderActiveRule(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ActiveBounds bounds = activeBounds();
        Component header = inspectMode
            ? Component.translatable("blockgrep.builder.selected.voxel",
                selected.x + 1, selected.y + 1, selected.z + 1)
            : Component.translatable("blockgrep.builder.brush");
        graphics.text(font, header, bounds.left, bounds.top - 10, 0xFFA0A0A0);
        graphics.fill(bounds.left, bounds.top, bounds.right, bounds.bottom, 0x402C3A49);

        CellRule current = activeRule();
        if (current.isAny()) {
            renderPaletteEntry(graphics, PaletteEntry.wildcard(), bounds.left + 11,
                bounds.top + 12, 1.0f);
        } else {
            int slots = activeSlots();
            int pad = activePad();
            int start = activePage * slots;
            int end = Math.min(current.terms().size(), start + slots);
            for (int i = start; i < end; i++) {
                int x = bounds.left + pad + (i - start) * TILE;
                Term term = current.terms().get(i);
                renderRuleIcon(graphics, CellRule.of(term), x + 10, bounds.top + 12, 1.0f);
                if (mouseX >= x && mouseX < x + TILE && mouseY >= bounds.top
                        && mouseY < bounds.bottom) {
                    graphics.outline(x, bounds.top + 1, TILE - 2, BRUSH_ROW_HEIGHT - 4, 0xFFFFFFFF);
                    graphics.setTooltipForNextFrame(font,
                        font.split(Component.translatable("blockgrep.builder.brush.term", describeTerm(term)),
                            Math.max(240, width / 2)),
                        mouseX, mouseY);
                }
            }
            if (activePageCount() > 1) {
                graphics.centeredText(font, "‹", bounds.left + 6, bounds.top + 7,
                    activePage > 0 ? 0xFFFFFFFF : 0xFF555555);
                graphics.centeredText(font, "›", bounds.right - 6, bounds.top + 7,
                    activePage + 1 < activePageCount() ? 0xFFFFFFFF : 0xFF555555);
            }
        }
    }

    private void renderPaletteEntry(GuiGraphicsExtractor graphics, PaletteEntry entry,
                                    int centerX, int centerY, float scale) {
        if (entry.any) {
            graphics.fill(centerX - 8, centerY - 8, centerX + 8, centerY + 8, 0x704C46A8);
            graphics.outline(centerX - 8, centerY - 8, 16, 16, 0xFF9C8CFF);
            graphics.centeredText(font, "?", centerX, centerY - 4, 0xFFFFFFFF);
            return;
        }
        Block block = entry.representative;
        renderItem(graphics, stackFor(block), centerX, centerY, scale);
        if (entry.tag) {
            graphics.centeredText(font, "#", centerX - 6, centerY - 8, 0xFFFFFF55);
        }
    }

    private void renderRuleIcon(GuiGraphicsExtractor graphics, CellRule rule,
                                int centerX, int centerY, float scale) {
        if (rule.isAny()) {
            renderPaletteEntry(graphics, PaletteEntry.wildcard(), centerX, centerY, scale);
            return;
        }
        Term first = rule.terms().getFirst();
        Block representative = blockFor(first);
        renderItem(graphics, stackFor(representative), centerX, centerY, scale);
        if (first.tag()) {
            graphics.centeredText(font, "#", centerX - Math.round(6 * scale),
                centerY - Math.round(8 * scale), 0xFFFFFF55);
        }
        if (first.negated()) {
            graphics.centeredText(font, "!", centerX + Math.round(6 * scale),
                centerY - Math.round(8 * scale), 0xFFFF4A4A);
        }
        if (rule.terms().size() > 1) {
            graphics.centeredText(font, "+" + (rule.terms().size() - 1),
                centerX + Math.round(5 * scale), centerY + Math.round(4 * scale), 0xFFFFFFFF);
        }
    }

    private static void renderItem(GuiGraphicsExtractor graphics, ItemStack stack,
                                   int centerX, int centerY, float scale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, centerY);
        graphics.pose().scale(scale, scale);
        graphics.item(stack, -8, -8);
        graphics.pose().popMatrix();
    }

    private ItemStack stackFor(Block block) {
        if (block == Blocks.WATER) {
            return new ItemStack(Items.WATER_BUCKET);
        }
        if (block == Blocks.LAVA) {
            return new ItemStack(Items.LAVA_BUCKET);
        }
        if (block == null || block == Blocks.AIR || block.asItem() == Items.AIR) {
            return new ItemStack(Items.BARRIER);
        }
        return new ItemStack(block);
    }

    private Block blockFor(Term term) {
        if (term.tag()) {
            return tagRepresentatives.computeIfAbsent(term.id(), this::representativeForTag);
        }
        Identifier id = Identifier.tryParse(term.id());
        return id == null ? Blocks.BARRIER
            : BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.BARRIER);
    }

    private Block representativeForTag(String idText) {
        Identifier id = Identifier.tryParse(idText);
        if (id == null) {
            return Blocks.OAK_LOG;
        }
        TagKey<Block> key = TagKey.create(BuiltInRegistries.BLOCK.key(), id);
        try {
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(key)) {
                if (holder.value().asItem() != Items.AIR) {
                    return holder.value();
                }
            }
        } catch (IllegalStateException ignored) {
            // Tags are unbound on the title screen.  A generic tag block keeps
            // the rule editable until a world supplies a representative.
        }
        return Blocks.OAK_LOG;
    }

    private Component describeCell(CellPos position, CellRule rule) {
        Component ruleName;
        if (rule.isAny()) {
            ruleName = Component.translatable("blockgrep.builder.any");
        } else if (rule.terms().size() == 1) {
            ruleName = describeTerm(rule.terms().getFirst());
        } else {
            MutableComponent list = Component.empty();
            int maxShown = Math.min(rule.terms().size(), 3);
            for (int i = 0; i < maxShown; i++) {
                if (i > 0) {
                    list.append(Component.literal(", "));
                }
                list.append(describeTerm(rule.terms().get(i)));
            }
            if (rule.terms().size() > maxShown) {
                list.append(Component.literal(" (+" + (rule.terms().size() - maxShown) + ")"));
            }
            ruleName = list;
        }
        String key = inspectMode
            ? "blockgrep.builder.cell.tooltip.inspect"
            : "blockgrep.builder.cell.tooltip.paint";
        return Component.translatable(key,
            position.x + 1, position.y + 1, position.z + 1, ruleName);
    }

    private Component describeTerm(Term term) {
        Block block = blockFor(term);
        Component base = term.tag()
            ? Component.translatable("blockgrep.builder.tag.named", term.id())
            : block == Blocks.BARRIER && !term.id().equals("minecraft:barrier")
                ? Component.translatable("blockgrep.builder.missing", term.id())
                : block.getName();
        return term.negated()
            ? Component.translatable("blockgrep.builder.not", base)
            : base;
    }

    private List<ProjectedCell> projectedCells() {
        List<ProjectedCell> out = new ArrayList<>();
        int minY = sliceOnly ? sliceY : 0;
        int maxY = sliceOnly ? sliceY + 1 : pattern.sizeY();
        for (int y = minY; y < maxY; y++) {
            for (int z = 0; z < pattern.sizeZ(); z++) {
                for (int x = 0; x < pattern.sizeX(); x++) {
                    float[] p = projectCell(x, y, z);
                    out.add(new ProjectedCell(x, y, z, p[0], p[1], p[2], pattern.cell(x, y, z)));
                }
            }
        }
        // Painter's order: larger depth is closer to the camera.
        out.sort(Comparator.comparingDouble(ProjectedCell::depth));
        return out;
    }

    private CellPos pickCell(double mouseX, double mouseY) {
        return pickCell(mouseX, mouseY, projectedCells());
    }

    private CellPos pickCell(double mouseX, double mouseY, List<ProjectedCell> cells) {
        float radius = Math.max(7, cellSpacing() * 0.48f);
        double maxDistSq = radius * radius;
        ProjectedCell best = null;
        double bestDistSq = maxDistSq;
        for (ProjectedCell cell : cells) {
            double dx = mouseX - cell.screenX;
            double dy = mouseY - cell.screenY;
            double distSq = dx * dx + dy * dy;
            if (distSq <= maxDistSq) {
                if (best == null || cell.depth > best.depth || (cell.depth == best.depth && distSq < bestDistSq)) {
                    best = cell;
                    bestDistSq = distSq;
                }
            }
        }
        return best == null ? null : best.position();
    }

    private float[] projectCell(int x, int y, int z) {
        return project(x + 0.5f, y + 0.5f, z + 0.5f, cellSpacing());
    }

    private float[] projectCorner(float x, float y, float z) {
        return project(x, y, z, cellSpacing());
    }

    private float[] project(float x, float y, float z, float scale) {
        float px = x - pattern.sizeX() / 2.0f;
        float centerY = sliceOnly ? (sliceY + 0.5f) : (pattern.sizeY() / 2.0f);
        float py = y - centerY;
        float pz = z - pattern.sizeZ() / 2.0f;
        float cy = (float) Math.cos(yaw);
        float sy = (float) Math.sin(yaw);
        float cp = (float) Math.cos(pitch);
        float sp = (float) Math.sin(pitch);
        float rx = cy * px - sy * pz;
        float rz = sy * px + cy * pz;
        float ry = cp * py - sp * rz;
        float depth = sp * py + cp * rz;
        return new float[] {viewportCenterX() + rx * scale, viewportCenterY() - ry * scale, depth};
    }

    private float[] projectVector(float x, float y, float z, float scale) {
        float cy = (float) Math.cos(yaw);
        float sy = (float) Math.sin(yaw);
        float cp = (float) Math.cos(pitch);
        float sp = (float) Math.sin(pitch);
        float rx = cy * x - sy * z;
        float rz = sy * x + cy * z;
        float ry = cp * y - sp * rz;
        return new float[] {rx * scale, -ry * scale};
    }

    private float cellSpacing() {
        float availableW = Math.max(80, paletteLeft() - PANEL_GAP * 3);
        float availableH = Math.max(70, height - headerBottom() - FOOTER_HEIGHT - 20);
        float horizontalExtent = Math.max(1.5f, pattern.sizeX() + pattern.sizeZ());
        float verticalExtent = Math.max(1.5f,
            (sliceOnly ? 1 : pattern.sizeY()) + (pattern.sizeX() + pattern.sizeZ()) * 0.38f);
        float fit = Math.min(availableW / horizontalExtent, availableH / verticalExtent) * 0.92f;
        return Math.clamp(fit, 8.0f, 34.0f) * zoom;
    }

    private int headerBottom() {
        return width < 720 ? 65 : HEADER_BOTTOM;
    }

    private int viewportCenterX() {
        return (PANEL_GAP + paletteLeft() - PANEL_GAP) / 2;
    }

    private int viewportCenterY() {
        return (headerBottom() + height - FOOTER_HEIGHT) / 2 + 5;
    }

    private int paletteWidth() {
        return Math.clamp(width / 3, 126, 246);
    }

    private int paletteLeft() {
        return width - paletteWidth() - PANEL_GAP;
    }

    private boolean insideViewport(double x, double y) {
        return x >= PANEL_GAP && x < paletteLeft() - PANEL_GAP
            && y >= headerBottom() && y < height - FOOTER_HEIGHT;
    }

    private PaletteBounds paletteBounds() {
        int left = paletteLeft() + 6;
        int top = activeBounds().bottom + 8;
        int right = paletteLeft() + paletteWidth() - 5;
        int bottom = height - FOOTER_HEIGHT - 27;
        int columns = Math.max(1, (right - left) / TILE);
        int rows = Math.max(1, (bottom - top) / TILE);
        return new PaletteBounds(left, top, right, bottom, columns, rows);
    }

    private boolean insidePalette(double x, double y) {
        PaletteBounds bounds = paletteBounds();
        return x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom;
    }

    private PaletteEntry paletteAt(double mouseX, double mouseY) {
        PaletteBounds bounds = paletteBounds();
        int col = (int) (mouseX - bounds.left) / TILE;
        int row = (int) (mouseY - bounds.top) / TILE;
        if (col < 0 || col >= bounds.columns || row < 0 || row >= bounds.rows) {
            return null;
        }
        int index = palettePage * bounds.capacity() + row * bounds.columns + col;
        List<PaletteEntry> entries = paletteEntries();
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    private int pageCount() {
        int capacity = paletteBounds().capacity();
        return Math.max(1, (paletteEntries().size() + capacity - 1) / capacity);
    }

    private List<PaletteEntry> paletteEntries() {
        return paletteMode == PaletteMode.BLOCKS ? filteredBlocks : filteredTags;
    }

    private ActiveBounds activeBounds() {
        int left = paletteLeft() + 6;
        int top = headerBottom() + 4 + TOOL_ROW_HEIGHT + SEARCH_HEIGHT + 14;
        return new ActiveBounds(left, top, paletteLeft() + paletteWidth() - 6,
            top + BRUSH_ROW_HEIGHT - 3);
    }

    private boolean insideActive(double x, double y) {
        ActiveBounds bounds = activeBounds();
        return x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom;
    }

    private int activeSlots() {
        ActiveBounds bounds = activeBounds();
        int width = bounds.right - bounds.left - 4;
        int maxSlots = Math.max(1, width / TILE);
        CellRule current = activeRule();
        if (current.isAny() || current.terms().size() <= maxSlots) {
            return maxSlots;
        }
        return Math.max(1, (bounds.right - bounds.left - 28) / TILE);
    }

    private int activePad() {
        return activePageCount() > 1 ? 14 : 2;
    }

    private int activePageCount() {
        CellRule current = activeRule();
        if (current.isAny()) {
            return 1;
        }
        return Math.max(1, (current.terms().size() + activeSlots() - 1) / activeSlots());
    }

    private int activeArrowAt(double x, double y) {
        ActiveBounds bounds = activeBounds();
        if (activePageCount() <= 1) {
            return 0;
        }
        if (x < bounds.left + 14) {
            return -1;
        }
        if (x >= bounds.right - 14) {
            return 1;
        }
        return 0;
    }

    private int activeTermAt(double x, double y) {
        ActiveBounds bounds = activeBounds();
        int pad = activePad();
        int local = (int) (x - bounds.left - pad) / TILE;
        if (local < 0 || local >= activeSlots()) {
            return -1;
        }
        int index = activePage * activeSlots() + local;
        CellRule current = activeRule();
        return index < current.terms().size() ? index : -1;
    }

    private static List<PaletteEntry> collectBlocks() {
        List<PaletteEntry> out = new ArrayList<>();
        out.add(PaletteEntry.wildcard());
        out.add(PaletteEntry.block("minecraft:air", Blocks.AIR));
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block != Blocks.AIR) {
                out.add(PaletteEntry.block(BuiltInRegistries.BLOCK.getKey(block).toString(), block));
            }
        }
        out.sort(Comparator.comparing(entry -> entry.id));
        return List.copyOf(out);
    }

    private List<PaletteEntry> collectTags() {
        Map<String, PaletteEntry> found = new LinkedHashMap<>();
        try {
            BuiltInRegistries.BLOCK.getTags()
                .sorted(Comparator.comparing(named -> named.key().location().toString()))
                .forEach(named -> {
                    Block representative = firstDisplayable(named);
                    String id = named.key().location().toString();
                    found.put(id, PaletteEntry.tag(id,
                        representative == null ? Blocks.OAK_LOG : representative));
                });
        } catch (IllegalStateException ignored) {
            // The built-in registry has no bound tag contents at the title screen.
        }

        // Keep the tag tool useful from the title screen too.  These are only
        // visual choices; the runtime registry still decides what each tag means.
        String[] common = {
            "minecraft:logs", "minecraft:planks", "minecraft:leaves",
            "minecraft:wool", "minecraft:doors", "minecraft:trapdoors",
            "minecraft:stairs", "minecraft:slabs", "minecraft:fences",
            "minecraft:walls", "minecraft:mineable/pickaxe", "minecraft:ores",
            "minecraft:coal_ores", "minecraft:iron_ores", "minecraft:copper_ores",
            "minecraft:gold_ores", "minecraft:diamond_ores",
            "minecraft:redstone_ores", "minecraft:base_stone_overworld",
            "minecraft:dirt", "minecraft:sand"
        };
        for (String id : common) {
            found.computeIfAbsent(id,
                key -> PaletteEntry.tag(key, representativeForTag(key)));
        }
        List<PaletteEntry> sorted = new ArrayList<>(found.values());
        sorted.sort(Comparator.comparing(entry -> entry.id));
        return List.copyOf(sorted);
    }

    private static Block firstDisplayable(HolderSet.Named<Block> named) {
        for (Holder<Block> holder : named) {
            if (holder.value().asItem() != Items.AIR) {
                return holder.value();
            }
        }
        return null;
    }

    private enum PaletteMode { BLOCKS, TAGS }

    private record CellPos(int x, int y, int z) {}

    private record ProjectedCell(int x, int y, int z, float screenX, float screenY,
                                 float depth, CellRule rule) {
        CellPos position() { return new CellPos(x, y, z); }
    }

    private record PaletteBounds(int left, int top, int right, int bottom,
                                 int columns, int rows) {
        int capacity() { return columns * rows; }
    }

    private record ActiveBounds(int left, int top, int right, int bottom) {}

    private record PaletteEntry(String id, boolean tag, boolean any, Block representative) {
        static PaletteEntry wildcard() {
            return new PaletteEntry("?", false, true, Blocks.AMETHYST_BLOCK);
        }

        static PaletteEntry block(String id, Block block) {
            return new PaletteEntry(id, false, false, block);
        }

        static PaletteEntry tag(String id, Block block) {
            return new PaletteEntry(id, true, false, block);
        }

        boolean matches(String query) {
            if (any) {
                return query.equals("?") || query.startsWith("any") || query.startsWith("wild");
            }
            if (id.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
            if (representative != null) {
                String name = representative.getName().getString().toLowerCase(Locale.ROOT);
                return name.contains(query);
            }
            return false;
        }

        Component tooltip() {
            if (any) {
                return Component.translatable("blockgrep.builder.any.tooltip");
            }
            if (tag) {
                return Component.translatable("blockgrep.builder.tag.tooltip", id);
            }
            if (representative == Blocks.AIR) {
                return Component.translatable("blockgrep.builder.air.tooltip");
            }
            return Component.translatable("blockgrep.builder.block.tooltip",
                representative.getName(), id);
        }
    }
}
