package cx.gid.minecraft.blockgrep.client.config;

import cx.gid.minecraft.blockgrep.client.GrepState;
import cx.gid.minecraft.blockgrep.pattern.BlockPredicates;
import cx.gid.minecraft.blockgrep.pattern.Pattern;
import cx.gid.minecraft.blockgrep.pattern.PatternSpec;
import cx.gid.minecraft.blockgrep.pattern.Symmetry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The mod's entire settings screen: global options along the top, the pattern
 * list on the left, and the selected pattern's settings on the right.
 *
 * This is the only settings screen. It previously sat behind a generated config
 * screen whose sole content was a button leading here, which meant every visit
 * cost a step that existed only to be clicked through. The three global settings
 * that justified that screen are small enough to sit on this one.
 *
 * Written as a plain {@link Screen} rather than as config-library options
 * because a list row carries several independent controls — select, enable,
 * remove — and an options library gives a row exactly one.
 *
 * Every edit takes effect immediately: the change is written to the pattern and
 * the live search is rebuilt on the next tick, so the world behind the screen
 * updates as the player types. The config file is written once, on close, since
 * a keystroke does not warrant disk traffic.
 */
public class PatternListScreen extends Screen {

    /** Where to return when this screen closes. May be null. */
    private final Screen parent;

    private PatternList list;

    /** The pattern the right-hand pane is editing, or null when none is chosen. */
    private SavedPattern selected;

    /** Widgets belonging to the editor pane, rebuilt whenever the selection changes. */
    private final List<AbstractWidget> editorWidgets = new ArrayList<>();

    private ColorPicker strokePicker;
    private ColorPicker fillPicker;

    /**
     * The editor pane's viewport.
     *
     * The editor is the tall part of the screen, and at a large GUI scale it can
     * be taller than the window. Rather than shrink the form to fit, it scrolls:
     * the alternative would mean the color pickers losing their hex field and
     * swatch on exactly the screens where the settings are hardest to reach.
     */
    private ScrollPane editorPane;

    /** Result of validating the current spec text; drawn under the spec field. */
    private Validation validation = new Validation(Component.empty(), 0xFFA0A0A0);

    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SWATCH_WIDTH = 4;

    /** Vertical space for the title and the row of global settings beneath it. */
    private static final int HEADER_HEIGHT = 58;

    private static final int BOTTOM_MARGIN = 34;

    /** Width of the list pane; the editor takes what is left. */
    private static final int LIST_WIDTH = 180;

    private static final int GUTTER = 10;

    /**
     * Space kept clear on the right of the editor for its scrollbar.
     *
     * Reserved unconditionally, so the form does not shift sideways when the
     * content grows past the point of needing to scroll.
     */
    private static final int SCROLLBAR_GUTTER = 10;

    /**
     * Narrowest a column may be before the form drops to one column.
     *
     * Sized for the longest checkbox label — "Free to mirror horizontally" — plus
     * its box. Below this the label would be clipped, which is worse than a
     * taller form given that the pane scrolls.
     */
    private static final int MIN_CHECKBOX_WIDTH = 170;

    /** Size of the link button that joins the two colors. */
    private static final int LINK_WIDTH = 20;
    private static final int LINK_HEIGHT = 16;

    /** Small gap used where a widget sits against another. */
    private static final int CELL_GAP = 4;

    /**
     * Space between one labelled control and the next.
     *
     * A label sits 11px above its widget, so a row occupies label + widget and
     * this is the gap after it. Every vertical step in the editor is expressed in
     * terms of these constants rather than as literal numbers, because the last
     * layout drifted precisely by having labels and widgets positioned from
     * separate hardcoded values.
     */
    private static final int LABEL_HEIGHT = 11;
    private static final int FIELD_GAP = 10;
    private static final int CHECK_HEIGHT = 20;

    public PatternListScreen(Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font,
            Component.translatable("blockgrep.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int listTop = HEADER_HEIGHT + LABEL_HEIGHT + BUTTON_HEIGHT + 6;

        // Global settings, across the top above both panes: they apply to the
        // whole search rather than to any one pattern, and putting them here
        // rather than on a screen of their own removes a navigation step whose
        // only purpose was to reach this one.
        BlockGrepConfig config = BlockGrepConfig.get();

        addRenderableWidget(Checkbox.builder(
                Component.translatable("blockgrep.screen.enable"), font)
            .pos(GUTTER, HEADER_HEIGHT - 24)
            .selected(config.enabled)
            .onValueChange((box, value) -> {
                config.enabled = value;
                PatternManager.refreshLive();
            })
            .build());

        int globalWidth = 150;
        int limitX = width - GUTTER - globalWidth;
        int radiusX = limitX - GUTTER - globalWidth;

        addRenderableWidget(new RadiusSlider(radiusX, HEADER_HEIGHT - 26,
            globalWidth, BUTTON_HEIGHT, config));

        EditBox limitBox = new EditBox(font, limitX, HEADER_HEIGHT - 26,
            globalWidth, BUTTON_HEIGHT, Component.translatable("blockgrep.screen.limit"));
        limitBox.setMaxLength(5);
        limitBox.setValue(String.valueOf(config.limit));
        limitBox.setResponder(text -> {
            // Anything that is not a number is ignored rather than rejected.
            // An empty field is the normal state mid-edit — the player has
            // cleared it to retype — and treating that as zero would briefly
            // stop the search; leaving the previous limit in force until a
            // number appears is what makes the field usable.
            try {
                config.limit = Math.clamp(Integer.parseInt(text.trim()), 1, 20000);
                GrepState.setLimit(config.limit);
            } catch (NumberFormatException ignored) {
                // Keep the last good value.
            }
        });
        addRenderableWidget(limitBox);

        list = new PatternList(minecraft, LIST_WIDTH,
            height - listTop - BOTTOM_MARGIN, listTop, ROW_HEIGHT);
        list.setX(GUTTER);
        addRenderableWidget(list);

        // The editor's viewport spans the same vertical extent as the list, and
        // the width the editor previously drew into directly.
        editorPane = new ScrollPane(editorLeft(), HEADER_HEIGHT,
            editorWidth(), height - HEADER_HEIGHT - BOTTOM_MARGIN);
        editorPane.setOverlay(this::renderEditorLabels);
        addRenderableWidget(editorPane);

        // Above the list rather than at the foot of the screen: it acts on the
        // list, so it belongs against it, and at the bottom it read as a
        // screen-level action alongside Done.
        addRenderableWidget(Button.builder(Component.translatable("blockgrep.screen.add"), b -> {
            // A new pattern starts as a single don't-care cell: valid, matching
            // nothing useful, and obviously a stub to be filled in. Starting from
            // something that fails to parse would show an error before the player
            // has typed anything.
            SavedPattern added = PatternManager.addPattern("", "?", Symmetry.YAW);
            rebuildRows();
            select(added);
        }).bounds(GUTTER, listTop - BUTTON_HEIGHT - 4, LIST_WIDTH, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.translatable("blockgrep.screen.done"), b -> onClose())
            .bounds(width - GUTTER - 100, height - 28, 100, BUTTON_HEIGHT).build());

        rebuildRows();

        // Keep the previous selection across a resize, falling back to the first
        // pattern so the editor pane is not needlessly blank on opening.
        List<SavedPattern> patterns = config.patterns;
        if (selected != null && patterns.contains(selected)) {
            select(selected);
        } else if (!patterns.isEmpty()) {
            select(patterns.getFirst());
        } else {
            select(null);
        }
    }

    /**
     * Rebuilds every row from the config.
     *
     * Rebuilding wholesale rather than patching rows keeps this screen stateless
     * with respect to the config, and is necessary after a removal in any case:
     * each row holds its own index, which shifts for everything after the gap.
     */
    private void rebuildRows() {
        // clearEntries(), not children().clear(): the list exposes its entries
        // through a view that does not support removal.
        list.clearPatterns();
        List<SavedPattern> patterns = BlockGrepConfig.get().patterns;
        for (int i = 0; i < patterns.size(); i++) {
            list.addPattern(new PatternRow(patterns.get(i), i));
        }
    }

    /**
     * Notes that a pattern changed, so the running search picks it up.
     *
     * Called from every editor control. The rebuild happens on the next client
     * tick rather than here, because compiling resolves block tags and this
     * screen can be open at the main menu where they are not bound.
     */
    private static void edited() {
        PatternManager.refreshLive();
    }

    /**
     * Points the editor pane at a pattern, or clears it when given null.
     *
     * The editor's widgets are discarded and rebuilt rather than repointed,
     * because their initial values are captured when they are constructed.
     */
    private void select(SavedPattern pattern) {
        editorWidgets.clear();
        editorPane.clearContents();
        strokePicker = null;
        fillPicker = null;

        this.selected = pattern;
        if (pattern == null) {
            return;
        }

        int left = editorLeft();
        int paneWidth = formWidth();
        int half = editorHalf();
        int y = editorPane.contentTop();

        // Each labelled field advances y by the same amount the render pass
        // does, so the two cannot drift apart.
        y += LABEL_HEIGHT;
        EditBox nameBox = new EditBox(font, left, y, paneWidth, BUTTON_HEIGHT,
            Component.translatable("blockgrep.editor.name"));
        nameBox.setMaxLength(64);
        nameBox.setValue(pattern.name == null ? "" : pattern.name);
        // Left empty deliberately when unnamed. A placeholder copied from the
        // spec would be indistinguishable from a real name and would go stale.
        nameBox.setHint(Component.translatable("blockgrep.editor.name.hint"));
        nameBox.setResponder(v -> {
            pattern.name = v;
            // The list shows the name, so it has to follow the field as it is typed.
            rebuildRows();
            edited();
        });
        addEditorWidget(nameBox);
        y += BUTTON_HEIGHT + FIELD_GAP;

        y += LABEL_HEIGHT;
        EditBox specBox = new EditBox(font, left, y, paneWidth, BUTTON_HEIGHT,
            Component.translatable("blockgrep.editor.spec"));
        // Long enough for a multi-layer spec of full block ids, which runs to a
        // few hundred characters once namespaces and alternations are spelled out.
        specBox.setMaxLength(1024);
        specBox.setValue(pattern.spec == null ? "" : pattern.spec);
        specBox.setResponder(v -> {
            pattern.spec = v;
            validation = validate(v);
            rebuildRows();
            edited();
        });
        addEditorWidget(specBox);
        validation = validate(specBox.getValue());
        // The validation line sits below the box, not over it: it is its own
        // row, with the same gap after it as any other field.
        y += BUTTON_HEIGHT + LABEL_HEIGHT + FIELD_GAP;

        // The orientation summary is a line of its own before the checkboxes.
        y += LABEL_HEIGHT + 4;

        // Orientation. The labels state what the pattern is free to do rather
        // than instructing the player to do it: these are properties of the
        // search, not actions to perform.
        //
        // Two columns when they fit, one when they do not. A checkbox whose label
        // is clipped is worse than a taller form, and the pane scrolls anyway.
        boolean twoColumns = half >= MIN_CHECKBOX_WIDTH;
        int rightColumn = twoColumns ? left + half + 8 : left;

        addEditorWidget(axisBox("blockgrep.editor.rotate.x", left, y, 0));
        y += twoColumns ? 0 : CHECK_HEIGHT;
        addEditorWidget(axisBox("blockgrep.editor.rotate.y", rightColumn, y, 1));
        y += CHECK_HEIGHT;
        addEditorWidget(axisBox("blockgrep.editor.rotate.z", left, y, 2));
        y += CHECK_HEIGHT + 4;

        addEditorWidget(mirrorBox("blockgrep.editor.mirror.horizontal", left, y, false));
        y += twoColumns ? 0 : CHECK_HEIGHT;
        addEditorWidget(mirrorBox("blockgrep.editor.mirror.vertical",
            rightColumn, y, true));
        y += CHECK_HEIGHT + FIELD_GAP;

        addEditorWidget(Checkbox.builder(Component.translatable("blockgrep.editor.enabled"), font)
            .pos(left, y)
            .selected(pattern.enabled)
            .onValueChange((box, value) -> {
                pattern.enabled = value;
                rebuildRows();
                edited();
            })
            .build());
        y += twoColumns ? 0 : CHECK_HEIGHT;

        addEditorWidget(Checkbox.builder(Component.translatable("blockgrep.editor.xray"), font)
            .pos(rightColumn, y)
            .selected(pattern.xray)
            .onValueChange((box, value) -> {
                pattern.xray = value;
                edited();
            })
            .build());
        y += CHECK_HEIGHT + FIELD_GAP;

        addEditorWidget(new WidthSlider(left, y,
            twoColumns ? half : paneWidth, BUTTON_HEIGHT, pattern));
        y += BUTTON_HEIGHT + FIELD_GAP;

        // The two color editors sit side by side when there is room, since the
        // fill is usually chosen relative to the outline and comparing them
        // matters. When there is not, the fill goes below the outline rather
        // than both being squeezed to an unusable width.
        int pickerWidth = Math.min(
            twoColumns ? half : paneWidth, ColorPicker.maxWidth());
        colorsSideBySide = twoColumns;
        colorTop = y;
        y += LABEL_HEIGHT + 2;

        strokePicker = new ColorPicker(font, left, y, pickerWidth,
            pattern.strokeColor, value -> {
                pattern.strokeColor = value;
                // The list swatch shows the outline color, so it tracks this.
                rebuildRows();
                if (pattern.linkColors) {
                    // A programmatic set does not fire the other picker's
                    // callback, so the saved color has to be copied across here
                    // too — otherwise the widget shows the new color while the
                    // renderer keeps drawing the old one.
                    fillPicker.setRgbKeepingAlpha(value);
                    pattern.fillColor = fillPicker.color();
                }
                edited();
            });

        int fillX = colorsSideBySide ? left + half + 8 : left;
        fillTop = colorsSideBySide ? colorTop : y + ColorPicker.height() + FIELD_GAP;
        int fillY = colorsSideBySide ? y : fillTop + LABEL_HEIGHT + 2;

        fillPicker = new ColorPicker(font, fillX, fillY, pickerWidth,
            pattern.fillColor, value -> {
                pattern.fillColor = value;
                if (pattern.linkColors) {
                    strokePicker.setRgbKeepingAlpha(value);
                    pattern.strokeColor = strokePicker.color();
                    // The list swatch shows the outline color, so it tracks this.
                    rebuildRows();
                }
                edited();
            });

        // Linking the two colors. Sits between the headings when they are side by
        // side, since it is a relationship between them rather than a property of
        // either; stacked, it goes at the right of the Outline heading's row.
        //
        // A button showing '=' or '!=' rather than a tickbox: the glyph states
        // the relationship that currently holds, which is more direct than a box
        // whose meaning depends on reading a label beside it.
        addEditorWidget(linkButton(
            colorsSideBySide
                ? left + half + 8 - LINK_WIDTH - CELL_GAP
                : left + paneWidth - LINK_WIDTH,
            colorTop - 5, pattern));

        strokePicker.widgets().forEach(this::addEditorWidget);
        fillPicker.widgets().forEach(this::addEditorWidget);

        if (!colorsSideBySide) {
            y = fillY;
        }

        // The pickers extend past their last slider by the hex row and swatch,
        // so the scroll extent is measured from the picker rather than from the
        // widgets alone.
        editorPane.setContentBottom(y + ColorPicker.height() + FIELD_GAP);
    }

    /**
     * Y of the "Outline" heading, recorded by {@link #select} for the render
     * pass rather than recomputed from constants there.
     */
    private int colorTop;

    /** Y of the "Fill" heading; equal to {@link #colorTop} when side by side. */
    private int fillTop;

    /** Whether the two pickers are beside each other or stacked. */
    private boolean colorsSideBySide;

    private int editorLeft() {
        return GUTTER + LIST_WIDTH + GUTTER;
    }

    private int editorWidth() {
        return width - editorLeft() - GUTTER;
    }

    /**
     * Width available to the form, inside the viewport.
     *
     * Narrower than the pane by the scrollbar, which is reserved whether or not
     * it is currently needed: letting the form widen when the content happens to
     * fit would make every widget jump sideways as a pattern is edited past the
     * point of scrolling.
     */
    private int formWidth() {
        return editorWidth() - SCROLLBAR_GUTTER;
    }

    private int editorHalf() {
        return (formWidth() - 8) / 2;
    }

    /**
     * Adds a widget to the editor pane.
     *
     * Deliberately not addRenderableWidget: the pane draws and dispatches to its
     * own contents with the scroll offset applied, and a widget the screen also
     * knew about would be drawn twice — once in the wrong place.
     */
    private void addEditorWidget(AbstractWidget widget) {
        editorWidgets.add(widget);
        editorPane.add(widget);
    }

    /** A rotation-axis checkbox for the given axis index. */
    private Checkbox axisBox(String labelKey, int x, int y, int axis) {
        Symmetry current = selected.symmetry();
        boolean on = switch (axis) {
            case 0 -> current.freeX();
            case 1 -> current.freeY();
            default -> current.freeZ();
        };
        return Checkbox.builder(Component.translatable(labelKey), font)
            .pos(x, y)
            .selected(on)
            .onValueChange((box, value) -> {
                Symmetry now = selected.symmetry();
                boolean[] rot = {now.freeX(), now.freeY(), now.freeZ()};
                rot[axis] = value;
                selected.symmetry = new Symmetry(rot[0], rot[1], rot[2],
                    now.mirrorX(), now.mirrorY(), now.mirrorZ()).spec();
                edited();
            })
            .build();
    }

    /**
     * A mirror checkbox, for either the horizontal pair or the vertical plane.
     *
     * Horizontal sets both the x and z planes together: with any rotation about
     * y they denote the same group anyway, and without one, offering them
     * separately would be a distinction almost nobody wants to draw by hand.
     * Naming individual planes is still possible from the command line.
     */
    private Checkbox mirrorBox(String labelKey, int x, int y, boolean vertical) {
        Symmetry current = selected.symmetry();
        boolean on = vertical ? current.mirrorY() : (current.mirrorX() || current.mirrorZ());
        return Checkbox.builder(Component.translatable(labelKey), font)
            .pos(x, y)
            .selected(on)
            .onValueChange((box, value) -> {
                Symmetry now = selected.symmetry();
                selected.symmetry = new Symmetry(
                    now.freeX(), now.freeY(), now.freeZ(),
                    vertical ? now.mirrorX() : value,
                    vertical ? value : now.mirrorY(),
                    vertical ? now.mirrorZ() : value).spec();
                edited();
            })
            .build();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partial) {
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        graphics.centeredText(font, title.getString(), width / 2, 10, 0xFFFFFFFF);

        int globalWidth = 150;
        int limitX = width - GUTTER - globalWidth;
        int radiusX = limitX - GUTTER - globalWidth;
        graphics.text(font, Component.translatable("blockgrep.screen.limit"),
            limitX, HEADER_HEIGHT - 38, 0xFFA0A0A0);
        graphics.text(font, Component.translatable("blockgrep.screen.radius"),
            radiusX, HEADER_HEIGHT - 38, 0xFFA0A0A0);

        if (BlockGrepConfig.get().patterns.isEmpty()) {
            graphics.text(font, Component.translatable("blockgrep.screen.empty"),
                GUTTER + 4, HEADER_HEIGHT + LABEL_HEIGHT + BUTTON_HEIGHT + 14, 0xFFA0A0A0);
        }

    }

    /**
     * Draws the editor's labels, inside the scroll pane's transform.
     *
     * Called back from the pane rather than from the render pass directly, so
     * these are clipped to the viewport and shifted by the scroll amount exactly
     * as the widgets they label are.
     */
    private void renderEditorLabels(GuiGraphicsExtractor graphics) {
        if (selected == null) {
            return;
        }

        int left = editorLeft();
        int half = editorHalf();
        int y = editorPane.contentTop();

        // Mirrors the sequence in select() step for step. Both walk the same
        // constants in the same order, which is what keeps a label over its
        // field rather than across it.
        graphics.text(font, Component.translatable("blockgrep.editor.name"),
            left, y, 0xFFA0A0A0);
        y += LABEL_HEIGHT + BUTTON_HEIGHT + FIELD_GAP;

        graphics.text(font, Component.translatable("blockgrep.editor.spec"),
            left, y, 0xFFA0A0A0);
        y += LABEL_HEIGHT + BUTTON_HEIGHT;
        graphics.text(font, validation.message(), left, y + 2, validation.color());
        y += LABEL_HEIGHT + FIELD_GAP;

        // Reports the group the checkboxes below add up to — the number is the
        // thing a player actually wants to know, and it is not obvious from the
        // boxes alone.
        graphics.text(font, Component.translatable("blockgrep.editor.orientations",
                describeSymmetry(selected.symmetry())),
            left, y, 0xFFA0A0A0);

        graphics.text(font, Component.translatable("blockgrep.editor.outline"),
            left, colorTop, 0xFFFFFFFF);
        graphics.text(font, Component.translatable("blockgrep.editor.fill"),
            colorsSideBySide ? left + half + 8 : left, fillTop, 0xFFFFFFFF);

        strokePicker.render(graphics, font);
        fillPicker.render(graphics, font);
    }

    @Override
    public void onClose() {
        // The only write to disk. Every edit has already reached the running
        // search; this is what makes it survive the session.
        PatternManager.save();
        BlockGrepConfig.get().apply();
        minecraft.setScreenAndShow(parent);
    }

    /**
     * Describes a symmetry group in the player's language.
     *
     * {@link Symmetry} lives in the loader-agnostic module and has no access to
     * Minecraft's translation machinery, so it reports the raw facts — how many
     * orientations, and the canonical spec — and the phrasing is chosen here.
     */
    private static Component describeSymmetry(Symmetry symmetry) {
        if (symmetry.isIdentityOnly()) {
            return Component.translatable("blockgrep.symmetry.fixed");
        }
        return Component.translatable("blockgrep.symmetry.orientations",
            symmetry.transforms().size(), symmetry.spec());
    }

    /** Validates spec text, producing the line shown beneath the field. */
    private static Validation validate(String spec) {
        if (spec == null || spec.isBlank()) {
            return new Validation(
                Component.translatable("blockgrep.editor.spec.empty"), 0xFFA0A0A0);
        }
        try {
            Pattern parsed = PatternSpec.parse(spec);
            return new Validation(
                Component.translatable("blockgrep.editor.spec.valid",
                    parsed.sizeX(), parsed.sizeY(), parsed.sizeZ(),
                    parsed.significantCells()),
                0xFF60D060);
        } catch (BlockPredicates.ParseException e) {
            // The parse error itself is not translated: it comes from the shared
            // module, which has no access to the language files, and it names
            // block ids and spec fragments that would not be translated anyway.
            return new Validation(Component.literal(e.getMessage()), 0xFFE05050);
        }
    }

    /** The feedback line under the spec field: what to say, and in what color. */
    private record Validation(Component message, int color) {}

    /** The global search radius, in the range the scanner accepts. */
    private static class RadiusSlider extends AbstractSliderButton {

        private final BlockGrepConfig config;

        RadiusSlider(int x, int y, int width, int height, BlockGrepConfig config) {
            super(x, y, width, height, Component.empty(),
                (double) (config.radius - GrepState.MIN_RADIUS)
                    / (GrepState.MAX_RADIUS - GrepState.MIN_RADIUS));
            this.config = config;
            updateMessage();
        }

        private int blocks() {
            int span = GrepState.MAX_RADIUS - GrepState.MIN_RADIUS;
            // Stepped to 4 blocks, matching the granularity the old slider used:
            // finer than that is below the point where the difference is felt.
            int raw = (int) Math.round(GrepState.MIN_RADIUS + value * span);
            return Math.clamp(raw / 4 * 4, GrepState.MIN_RADIUS, GrepState.MAX_RADIUS);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(
                "blockgrep.screen.radius.value", blocks()));
        }

        @Override
        protected void applyValue() {
            config.radius = blocks();
            // Straight into the scanner rather than waiting for the screen to
            // close, so the effect is visible behind the screen as it is dragged.
            GrepState.setRadius(config.radius);
        }
    }

    /**
     * Toggles whether the outline and fill colors are kept equal.
     *
     * Shows '=' when they are linked and '≠' when they are not, so the button
     * reads as the state of the relationship rather than as an instruction. Both
     * glyphs are in the default font, so neither shows as a missing-glyph box.
     *
     * Turning it on copies the outline's hue to the fill immediately: waiting for
     * the next edit would leave the button claiming an equality that did not yet
     * hold. Alphas are never copied — see {@link ColorPicker#setRgbKeepingAlpha}.
     */
    private Button linkButton(int x, int y, SavedPattern pattern) {
        Button button = Button.builder(linkLabel(pattern), b -> {
                pattern.linkColors = !pattern.linkColors;
                b.setMessage(linkLabel(pattern));
                if (pattern.linkColors) {
                    fillPicker.setRgbKeepingAlpha(pattern.strokeColor);
                    pattern.fillColor = fillPicker.color();
                }
                edited();
            })
            .bounds(x, y, LINK_WIDTH, LINK_HEIGHT)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("blockgrep.editor.link.tooltip")))
            .build();
        return button;
    }

    private static Component linkLabel(SavedPattern pattern) {
        return Component.translatable(pattern.linkColors
            ? "blockgrep.editor.link.on"
            : "blockgrep.editor.link.off");
    }

    /** Outline thickness, in the same 0.5–8 range the renderer accepts. */
    private static class WidthSlider extends AbstractSliderButton {

        private static final float MIN = 0.5f;
        private static final float MAX = 8.0f;

        private final SavedPattern pattern;

        WidthSlider(int x, int y, int width, int height, SavedPattern pattern) {
            super(x, y, width, height, Component.empty(),
                (pattern.strokeWidth - MIN) / (MAX - MIN));
            this.pattern = pattern;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("blockgrep.editor.width",
                String.format("%.1f", MIN + value * (MAX - MIN))));
        }

        @Override
        protected void applyValue() {
            // Rounded to the nearest half, which is as fine as the difference is
            // visible and keeps the displayed number honest.
            float raw = (float) (MIN + value * (MAX - MIN));
            pattern.strokeWidth = Math.round(raw * 2) / 2f;
            edited();
        }
    }

    /** The scrolling container. Exists to expose add/clear and set the width. */
    private class PatternList extends ObjectSelectionList<PatternRow> {

        PatternList(Minecraft client, int width, int height, int top, int rowHeight) {
            super(client, width, height, top, rowHeight);
        }

        void addPattern(PatternRow row) {
            addEntry(row);
        }

        void clearPatterns() {
            clearEntries();
        }

        @Override
        public int getRowWidth() {
            return LIST_WIDTH - 12;
        }
    }

    /**
     * One pattern in the list: color swatch, name, enable toggle and remove.
     *
     * Clicking anywhere else on the row selects it for editing, which is why the
     * two buttons are checked first in {@link #mouseClicked}.
     */
    private class PatternRow extends ObjectSelectionList.Entry<PatternRow> {

        private final SavedPattern pattern;
        private final int index;

        private final Button toggle;
        private final Button remove;

        PatternRow(SavedPattern pattern, int index) {
            this.pattern = pattern;
            this.index = index;

            this.toggle = Button.builder(enabledLabel(), b -> {
                pattern.enabled = !pattern.enabled;
                b.setMessage(enabledLabel());
                edited();
                // The editor's own Enabled box mirrors this, so it must be rebuilt.
                if (pattern == selected) {
                    select(pattern);
                }
            }).bounds(0, 0, 22, ROW_HEIGHT - 4).build();

            this.remove = Button.builder(Component.translatable("blockgrep.editor.remove"), b -> {
                PatternManager.removePattern(index);
                List<SavedPattern> remaining = BlockGrepConfig.get().patterns;
                rebuildRows();
                if (pattern == selected) {
                    select(remaining.isEmpty() ? null : remaining.getFirst());
                }
            }).bounds(0, 0, 16, ROW_HEIGHT - 4).build();
        }

        /** A tick or a cross rather than words: the row is narrow. */
        private Component enabledLabel() {
            return Component.literal(pattern.enabled ? "§a✔" : "§7✘");
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   boolean hovered, float partial) {
            int rowY = getY();
            int rowLeft = getX();
            int rowRight = rowLeft + getWidth();
            int textY = rowY + (ROW_HEIGHT - 8) / 2;
            int buttonY = rowY + 2;

            // The selected row is marked with a lighter band, since the editor on
            // the right belongs to it.
            if (pattern == selected) {
                graphics.fill(rowLeft, rowY, rowRight, rowY + ROW_HEIGHT - 1, 0x40FFFFFF);
            }

            // A slab of the pattern's own outline color, so the list reads as a
            // legend for what is drawn in the world.
            graphics.fill(rowLeft, rowY + 2, rowLeft + SWATCH_WIDTH, rowY + ROW_HEIGHT - 3,
                0xFF000000 | (pattern.strokeColor & 0xFFFFFF));

            remove.setX(rowRight - remove.getWidth());
            remove.setY(buttonY);
            toggle.setX(remove.getX() - toggle.getWidth() - 2);
            toggle.setY(buttonY);

            int nameLeft = rowLeft + SWATCH_WIDTH + 4;
            int nameWidth = Math.max(0, toggle.getX() - 4 - nameLeft);
            String name = font.plainSubstrByWidth(pattern.label(), nameWidth);
            graphics.text(font, name, nameLeft, textY,
                pattern.enabled ? 0xFFFFFFFF : 0xFF808080);

            toggle.extractRenderState(graphics, mouseX, mouseY, partial);
            remove.extractRenderState(graphics, mouseX, mouseY, partial);
        }

        /**
         * A row's buttons are not children of the list, so clicks must be offered
         * to them here; the list would otherwise treat the whole row as one band
         * and swallow them.
         */
        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,
                                    boolean doubled) {
            for (Button button : List.of(toggle, remove)) {
                if (button.mouseClicked(event, doubled)) {
                    return true;
                }
            }
            select(pattern);
            return true;
        }

        /** Lets the list include these in narration and focus traversal. */
        @Override
        public void visitWidgets(java.util.function.Consumer<AbstractWidget> consumer) {
            consumer.accept(toggle);
            consumer.accept(remove);
        }

        @Override
        public Component getNarration() {
            return Component.literal(pattern.label());
        }
    }
}
