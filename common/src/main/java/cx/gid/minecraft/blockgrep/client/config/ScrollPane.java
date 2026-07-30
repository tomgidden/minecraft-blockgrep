package cx.gid.minecraft.blockgrep.client.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A viewport that scrolls a set of absolutely-positioned widgets.
 *
 * The editor pane is a form of plain widgets at computed coordinates, together
 * with labels drawn straight onto the screen. Minecraft's own
 * {@code ScrollableLayout} scrolls a {@code Layout}, which would mean rebuilding
 * the form as layout elements — and would still not carry the free-drawn labels,
 * since those are not layout elements at all.
 *
 * So this scrolls the coordinate space instead. Contents are positioned once, in
 * content space, starting at {@link #contentTop()}; this shifts everything up by
 * the scroll amount when drawing and shifts the mouse down by the same amount
 * when testing hits. Nothing inside needs to know it is being scrolled.
 *
 * Extends {@link AbstractScrollArea} for the scrollbar, wheel handling and
 * drag-the-scroller behaviour, all of which are fiddly and none of which is
 * specific to this screen.
 */
public class ScrollPane extends AbstractScrollArea {

    /** Widgets living in the pane, in content coordinates. */
    private final List<AbstractWidget> contents = new ArrayList<>();

    /**
     * Extra drawing the host does inside the viewport — the labels.
     *
     * Called with the scroll transform already applied, so the host draws at the
     * same content coordinates it used to position the widgets.
     */
    private Consumer<GuiGraphicsExtractor> overlay = graphics -> {};


    /** Height of the content, measured from {@link #contentTop()}. */
    private int contentHeight;

    public ScrollPane(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty(),
            AbstractScrollArea.defaultSettings(6));
    }

    /**
     * Where content coordinates begin.
     *
     * Content is laid out in screen coordinates at zero scroll, rather than in a
     * space of its own beginning at zero, so that the existing layout code needs
     * no change: it already computes absolute positions from the header height,
     * and those are exactly the right values when the pane is scrolled to the top.
     */
    public int contentTop() {
        return getY();
    }

    /** Discards the current contents, ready for the pane to be refilled. */
    public void clearContents() {
        contents.clear();
        contentHeight = 0;
        setScrollAmount(0);
        // Both refer to widgets that no longer exist; keeping either would send
        // input to a discarded widget after a rebuild.
        focused = null;
        dragging = null;
    }

    /**
     * Adds a widget, in content coordinates.
     *
     * The widget is not registered with the screen: this pane draws and dispatches
     * to it, because a widget the screen knows about would be drawn a second time
     * without the scroll offset and would respond to clicks at the wrong place.
     */
    public void add(AbstractWidget widget) {
        contents.add(widget);
    }

    public void setOverlay(Consumer<GuiGraphicsExtractor> overlay) {
        this.overlay = overlay;
    }


    /**
     * Declares how tall the content is, as an absolute y in content space.
     *
     * Taken from the host's layout pass rather than measured from the widgets,
     * because the labels and the color pickers' swatches extend past the last
     * widget and would otherwise be cut off at the bottom of the scroll range.
     */
    public void setContentBottom(int bottomY) {
        this.contentHeight = Math.max(0, bottomY - contentTop());
    }

    @Override
    protected int contentHeight() {
        return contentHeight;
    }

    /**
     * Scrolls only when the pointer is actually over the pane.
     *
     * The inherited implementation checks only that the widget is visible, so
     * without this the editor would scroll while the player was spinning the
     * wheel over the pattern list beside it.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double deltaX, double deltaY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    protected double scrollRate() {
        // Roughly one form row per wheel notch. The inherited default is tuned
        // for lists of uniform entries, which this is not.
        return 12.0;
    }

    /** Where the content has been shifted to, as a positive number of pixels. */
    private int offset() {
        return (int) scrollAmount();
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
                                            int mouseX, int mouseY, float partial) {
        // Clipped to the viewport, so a widget scrolled past the top edge does
        // not draw over the header above it.
        graphics.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
        graphics.pose().pushMatrix();
        graphics.pose().translate(0, -offset());

        // The mouse is moved into content space for the same reason: a widget
        // asked to draw its hover state needs the pointer in its own coordinates.
        int contentMouseX = mouseX;
        int contentMouseY = mouseY + offset();

        for (AbstractWidget widget : contents) {
            widget.extractRenderState(graphics, contentMouseX, contentMouseY, partial);
        }
        overlay.accept(graphics);

        graphics.pose().popMatrix();
        graphics.disableScissor();

        extractScrollbar(graphics, mouseX, mouseY);
    }

    /**
     * Offers a click to the contents, in content coordinates.
     *
     * The scrollbar is checked first via updateScrolling: a click on it must
     * start a drag rather than fall through to whatever widget lies beneath.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (!isMouseOver(event.x(), event.y())) {
            return false;
        }
        if (updateScrolling(event)) {
            // The press was on the scrollbar, so the drag belongs to the
            // scroller and must not be offered to any child.
            draggingScrollbar = true;
            dragging = null;
            return true;
        }
        draggingScrollbar = false;
        MouseButtonEvent shifted = translate(event);
        for (AbstractWidget widget : contents) {
            if (widget.mouseClicked(shifted, doubled)) {
                // Focus follows the click, so typing goes to the box just
                // clicked rather than to whatever held focus before.
                setFocusedChild(widget);
                // Remembered separately from focus: a drag belongs to the widget
                // the press landed on, and focus can move independently.
                dragging = widget;
                return true;
            }
        }
        // Clicking empty space in the pane clears focus, matching what vanilla
        // screens do — otherwise a text box keeps the caret after the player has
        // clearly moved on from it.
        setFocusedChild(null);
        dragging = null;
        return true;
    }

    /**
     * Forwards a drag to the widget the press started on, and only that one.
     *
     * Two traps here, both stemming from AbstractWidget.mouseDragged reporting
     * every drag handled without any hit test. Offering the drag to each child
     * in turn would let the first one swallow it, so the target is remembered
     * from the press instead. And super.mouseDragged cannot be used to test
     * "was this the scrollbar", because when it is not scrolling it falls
     * through to that same always-true AbstractWidget method — which would
     * consume the drag before any child saw it. Whether the scrollbar owns the
     * drag is therefore recorded at press time.
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar) {
            return super.mouseDragged(event, dragX, dragY);
        }
        if (dragging == null) {
            return false;
        }
        return dragging.mouseDragged(translate(event), dragX, dragY);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        draggingScrollbar = false;
        if (dragging != null) {
            dragging.onRelease(translate(event));
            dragging = null;
        }
    }

    /** The same event with its position moved into content space. */
    private MouseButtonEvent translate(MouseButtonEvent event) {
        return new MouseButtonEvent(event.x(), event.y() + offset(), event.buttonInfo());
    }

    /** The widget currently receiving typed input, or null. */
    private AbstractWidget focused;

    /** The widget a press landed on, which owns the drag until release. */
    private AbstractWidget dragging;

    /** Whether the press landed on the scrollbar, which then owns the drag. */
    private boolean draggingScrollbar;

    private void setFocusedChild(AbstractWidget widget) {
        if (focused == widget) {
            return;
        }
        if (focused != null) {
            focused.setFocused(false);
        }
        focused = widget;
        if (focused != null) {
            focused.setFocused(true);
        }
    }

    /** Whichever child has focus, so the host can route key events to it. */
    public AbstractWidget focusedChild() {
        return focused;
    }

    /**
     * Forwards typing to the focused child.
     *
     * The screen routes key events to its own focused element, which is this
     * pane; without these the pane would swallow every keystroke and the text
     * boxes inside it would be uneditable.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (focused != null && focused.keyPressed(event)) {
            // Typing can move the caret past the edge of the viewport, so follow
            // the field being edited.
            scrollTo(focused);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (focused != null && focused.charTyped(event)) {
            scrollTo(focused);
            return true;
        }
        return false;
    }

    /**
     * Scrolls a focused widget into view.
     *
     * Called after tabbing between fields: a field that has focus but is off
     * screen leaves the player typing somewhere they cannot see.
     */
    public void scrollTo(AbstractWidget widget) {
        int top = widget.getY() - contentTop();
        int bottom = top + widget.getHeight();
        if (top < scrollAmount()) {
            setScrollAmount(top);
        } else if (bottom > scrollAmount() + getHeight()) {
            setScrollAmount(bottom - getHeight());
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // Nothing useful to say about the viewport itself; the contents narrate
        // themselves when focused.
    }
}
