package cx.gid.minecraft.blockgrep.client.config;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A color editor: HSV, RGB and alpha sliders over a live swatch and hex field.
 *
 * The three representations are views of one value, so moving any slider updates
 * all the others. That is the whole point of offering more than one: hue is the
 * natural way to pick a color, RGB the natural way to match an existing one,
 * and hex the natural way to paste one in.
 *
 * Deliberately plain sliders rather than gradient-filled ones. The gradients
 * would have to be redrawn as the color moves, and with a live swatch directly
 * beneath them they add nothing a player cannot already see.
 *
 * This is a group of ordinary widgets rather than a single composite widget, so
 * the host screen adds them itself via {@link #widgets()} and keeps its own
 * focus and click handling. Callers are notified through {@code onChange} and
 * should not read the sliders directly.
 */
public class ColorPicker {

    /** Height of one slider row, and the gap between rows. */
    private static final int ROW_HEIGHT = 14;

    /**
     * Gap between rows. Wide enough for the gradient strip drawn at the top of
     * each slider to read as belonging to that slider rather than to the one
     * above it.
     */
    private static final int ROW_GAP = 4;

    /** Thickness of the gradient strip along the top edge of each slider. */
    private static final int GRADIENT_HEIGHT = 3;

    /** Width reserved for the swatch at the end of the hex row. */
    private static final int SWATCH_WIDTH = 34;

    /** Width of the editable value box beside each slider. */
    private static final int VALUE_WIDTH = 34;

    /** Gap between the label, the slider and the value box. */
    private static final int CELL_GAP = 4;

    /**
     * Width at which channel labels are spelled out in full.
     *
     * Below this the initials are used instead. The threshold is the width at
     * which "Saturation" plus a usable slider and value box still fit; narrower
     * than that, a full label would either wrap or squeeze the slider down to
     * something that cannot be dragged accurately.
     */
    private static final int FULL_LABEL_WIDTH = 260;

    /**
     * Width past which the picker stops growing.
     *
     * A colour slider gains nothing from being 400px long — past a couple of
     * hundred pixels the extra travel is spurious precision, and two pickers
     * stretched across a wide window look like a mistake rather than a layout.
     * Beyond this the picker keeps its size and the surplus becomes margin.
     */
    private static final int MAX_WIDTH = 300;

    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<ChannelSlider> sliders = new ArrayList<>();

    /** Value boxes, one per slider, in the same order as {@link #sliders}. */
    private final List<EditBox> valueBoxes = new ArrayList<>();

    private final EditBox hexBox;
    private final IntConsumer onChange;

    private final int swatchX;
    private final int swatchY;
    private final int swatchHeight;

    /** Left edge and actual width, after capping to {@link #MAX_WIDTH}. */
    private final int left;
    private final int width;

    /** Width of the label column, sized to the longest label in use. */
    private final int labelWidth;

    /** Whether labels read "Saturation" rather than "S". */
    private final boolean spellOutLabels;

    /** The current value, as 0xAARRGGBB. */
    private int color;

    /**
     * Hue, saturation and value held alongside the RGB.
     *
     * Kept because the conversion is lossy in one direction: a color with zero
     * saturation or zero value has no meaningful hue, so deriving HSV from RGB
     * every time would snap the hue slider to zero whenever the color went
     * black or grey, and the player would be unable to drag it back out. Holding
     * the HSV lets those sliders stay where they were put.
     */
    private final float[] hsv = new float[3];

    /**
     * True while a slider or the hex field is pushing its value into the others.
     *
     * Without it, updating the hex box from a slider would fire the box's own
     * responder, which would parse the text and drive the sliders back — a loop
     * that also fights the player's cursor position as they type.
     */
    private boolean syncing;

    public ColorPicker(Font font, int x, int y, int available, int initial,
                       IntConsumer onChange) {
        this.color = initial;
        this.onChange = onChange;
        readHsvFromColor();

        // Capped rather than filling the space given. The surplus is left as
        // margin by the caller, which reads better than sliders stretched the
        // width of a large window.
        int width = Math.min(available, MAX_WIDTH);
        this.width = width;
        this.left = x;

        // Full words when there is room for them, initials when there is not.
        this.spellOutLabels = width >= FULL_LABEL_WIDTH;

        // Wide enough for the longest label actually in use, so the sliders all
        // start at the same x whichever channel has the longest name in this
        // language.
        int labelWidth = 0;
        for (Channel channel : Channel.values()) {
            labelWidth = Math.max(labelWidth, font.width(label(channel)));
        }
        this.labelWidth = labelWidth;

        int sliderX = x + labelWidth + CELL_GAP;
        int sliderWidth = width - labelWidth - CELL_GAP - VALUE_WIDTH - CELL_GAP;

        int row = y;
        for (Channel channel : Channel.values()) {
            ChannelSlider slider = new ChannelSlider(
                sliderX, row, sliderWidth, ROW_HEIGHT, channel);
            sliders.add(slider);
            widgets.add(slider);

            // The number sits in its own box beside the slider rather than in
            // the slider's label. In the label it was centred under the moving
            // handle, which made it hard to read while dragging and impossible
            // to type an exact value into.
            EditBox valueBox = new EditBox(font,
                sliderX + sliderWidth + CELL_GAP, row, VALUE_WIDTH, ROW_HEIGHT,
                Component.translatable(channel.labelKey));
            valueBox.setMaxLength(3);
            valueBox.setResponder(text -> {
                if (syncing) {
                    return;
                }
                try {
                    slider.set(Integer.parseInt(text.trim()), valueBox);
                } catch (NumberFormatException ignored) {
                    // Mid-edit or nonsense; the last good value stands.
                }
            });
            valueBoxes.add(valueBox);
            widgets.add(valueBox);

            row += ROW_HEIGHT + ROW_GAP;
        }

        this.swatchHeight = ROW_HEIGHT + 4;
        this.swatchX = x + width - SWATCH_WIDTH;
        this.swatchY = row;

        hexBox = new EditBox(font, sliderX, row,
            width - labelWidth - CELL_GAP - SWATCH_WIDTH - CELL_GAP, swatchHeight,
            Component.translatable("blockgrep.color.hex"));
        hexBox.setMaxLength(9);
        hexBox.setResponder(text -> {
            if (syncing) {
                return;
            }
            Integer parsed = parseHex(text);
            if (parsed != null) {
                color = parsed;
                readHsvFromColor();
                syncFrom(hexBox);
                onChange.accept(color);
            }
        });
        widgets.add(hexBox);

        syncFrom(null);
    }

    /** The widgets to add to the host screen, in tab order. */
    public List<AbstractWidget> widgets() {
        return widgets;
    }

    public int color() {
        return color;
    }

    /** Replaces the value, refreshing every control. Does not fire onChange. */
    public void setColor(int value) {
        this.color = value;
        readHsvFromColor();
        syncFrom(null);
    }

    /**
     * This channel's label, spelled out or abbreviated to fit.
     *
     * The short form is the first character of the full word rather than a
     * translation of its own, so a language only ever has to supply the word:
     * "Saturation" gives "S", "Teinte" gives "T". That does assume the initial
     * is a sensible abbreviation, which holds for the languages that use an
     * alphabet and degrades to a reasonable single glyph for those that do not.
     */
    private String label(Channel channel) {
        String full = Component.translatable(channel.labelKey).getString();
        if (spellOutLabels || full.isEmpty()) {
            return full;
        }
        return full.substring(0, full.offsetByCodePoints(0, 1));
    }

    /** Recomputes the held HSV after the color is set from outside. */
    private void readHsvFromColor() {
        int rgb = color & 0xFFFFFF;
        Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsv);
    }

    /** Total height occupied, for laying out whatever comes after it. */
    public static int height() {
        return Channel.values().length * (ROW_HEIGHT + ROW_GAP) + ROW_HEIGHT + 4;
    }

    /** The largest width a picker will use; wider offers become margin. */
    public static int maxWidth() {
        return MAX_WIDTH;
    }

    /**
     * Replaces the RGB while keeping this picker's own alpha.
     *
     * For the link between the outline and fill pickers: the two are meant to
     * share a hue but almost never a transparency — a fill matching the
     * outline's alpha would hide the terrain the boxes are drawn over.
     */
    public void setRgbKeepingAlpha(int other) {
        setColor((color & 0xFF000000) | (other & 0xFFFFFF));
    }

    /**
     * Draws the parts that are not widgets: the channel labels, the gradient
     * strips and the swatch.
     *
     * Called from the host screen's render, after the widgets have drawn.
     */
    public void render(GuiGraphicsExtractor graphics, Font font) {
        for (ChannelSlider slider : sliders) {
            graphics.text(font, label(slider.channel),
                left, slider.getY() + 3, 0xFFA0A0A0);
            drawGradient(graphics, slider);
        }
        graphics.text(font, "#", left, hexBox.getY() + 5, 0xFFA0A0A0);

        // A chequerboard behind the swatch, so a low alpha reads as transparency
        // rather than as a darker color.
        drawChequer(graphics, swatchX, swatchY, SWATCH_WIDTH, swatchHeight);
        graphics.fill(swatchX, swatchY, swatchX + SWATCH_WIDTH, swatchY + swatchHeight, color);
        drawOutline(graphics, swatchX, swatchY, SWATCH_WIDTH, swatchHeight, 0xFF000000);
    }

    /**
     * A strip along the top of a slider showing what that channel produces
     * across its range, with the other channels held at their current values.
     *
     * Drawn as a band above the track rather than as the track itself: the
     * vanilla slider paints its own background and handle, and replacing those
     * would mean reimplementing the widget. A strip keeps the ordinary slider —
     * with its familiar handle, keyboard handling and hover state — and still
     * answers the question the gradient is there for, which is "which way do I
     * drag to get more red?".
     *
     * It updates as other channels move, so the saturation strip really does run
     * from grey to the current hue rather than to a fixed one.
     */
    private void drawGradient(GuiGraphicsExtractor graphics, ChannelSlider slider) {
        int x = slider.getX();
        int width = slider.getWidth();
        int y = slider.getY();

        // One filled rectangle per pixel column. At this width that is a few
        // hundred quads per strip, which is nothing next to a world frame, and
        // it avoids needing a shader or a generated texture for a band that is
        // three pixels tall.
        for (int i = 0; i < width; i++) {
            float t = width <= 1 ? 0f : (float) i / (width - 1);
            graphics.fill(x + i, y, x + i + 1, y + GRADIENT_HEIGHT,
                0xFF000000 | (slider.channel.sample(hsv, color, t) & 0xFFFFFF));
        }
        drawOutline(graphics, x, y, width, GRADIENT_HEIGHT + 1, 0x60000000);
    }

    /** The familiar two-tone grid used to show transparency. */
    private static void drawChequer(GuiGraphicsExtractor graphics,
                                    int x, int y, int width, int height) {
        int cell = 4;
        for (int row = 0; row * cell < height; row++) {
            for (int col = 0; col * cell < width; col++) {
                int cx = x + col * cell;
                int cy = y + row * cell;
                graphics.fill(cx, cy,
                    Math.min(cx + cell, x + width),
                    Math.min(cy + cell, y + height),
                    ((row + col) % 2 == 0) ? 0xFFB0B0B0 : 0xFF707070);
            }
        }
    }

    private static void drawOutline(GuiGraphicsExtractor graphics,
                                    int x, int y, int width, int height, int argb) {
        graphics.fill(x, y, x + width, y + 1, argb);
        graphics.fill(x, y + height - 1, x + width, y + height, argb);
        graphics.fill(x, y, x + 1, y + height, argb);
        graphics.fill(x + width - 1, y, x + width, y + height, argb);
    }

    /**
     * Pushes {@link #color} into every control except the one that changed it.
     *
     * Skipping the source matters for the hex field, whose text would otherwise
     * be rewritten mid-edit, moving the caret out from under the player.
     */
    private void syncFrom(Object source) {
        syncing = true;
        try {
            for (int i = 0; i < sliders.size(); i++) {
                ChannelSlider slider = sliders.get(i);
                slider.pull();
                // The box that is being typed into keeps its text, so the caret
                // is not dragged about and a half-typed "1" does not become "1"
                // rewritten as "1" with the cursor moved to the end.
                EditBox box = valueBoxes.get(i);
                if (box != source) {
                    box.setValue(String.valueOf(slider.amount()));
                }
            }
            if (hexBox != source) {
                hexBox.setValue(String.format("%08X", color));
            }
        } finally {
            syncing = false;
        }
    }

    /** Parses "#AARRGGBB", "AARRGGBB" or "RRGGBB" (opaque), or null. */
    private static Integer parseHex(String text) {
        String hex = text.startsWith("#") ? text.substring(1) : text;
        try {
            if (hex.length() == 6) {
                return 0xFF000000 | Integer.parseInt(hex, 16);
            }
            if (hex.length() == 8) {
                return (int) Long.parseLong(hex, 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** The editable channels, in the order they are shown. */
    private enum Channel {
        HUE("h", 360),
        SATURATION("s", 100),
        VALUE("v", 100),
        RED("r", 255),
        GREEN("g", 255),
        BLUE("b", 255),
        ALPHA("a", 255);

        /**
         * Translation key for this channel's name, spelled out in full.
         *
         * Only the full word is translated; the abbreviation shown when space is
         * tight is its first character. See {@link ColorPicker#label}.
         */
        final String labelKey;

        /** Largest value this channel takes, so the slider can scale to it. */
        final int max;

        Channel(String suffix, int max) {
            this.labelKey = "blockgrep.color.channel." + suffix;
            this.max = max;
        }

        /**
         * The color this channel would produce at position {@code t} along its
         * range, with every other channel left as it currently is.
         *
         * Alpha is shown as a black-to-white ramp rather than by fading the
         * current color, because a strip three pixels tall drawn over the
         * screen behind it reads as noise rather than as transparency; the
         * swatch below already shows alpha properly, over a chequerboard.
         */
        int sample(float[] hsv, int color, float t) {
            return switch (this) {
                case HUE -> Color.HSBtoRGB(t, hsv[1], hsv[2]);
                case SATURATION -> Color.HSBtoRGB(hsv[0], t, hsv[2]);
                case VALUE -> Color.HSBtoRGB(hsv[0], hsv[1], t);
                case RED -> (color & 0x00FFFF) | (Math.round(t * 255) << 16);
                case GREEN -> (color & 0xFF00FF) | (Math.round(t * 255) << 8);
                case BLUE -> (color & 0xFFFF00) | Math.round(t * 255);
                case ALPHA -> {
                    int level = Math.round(t * 255);
                    yield (level << 16) | (level << 8) | level;
                }
            };
        }
    }

    /**
     * One channel's slider.
     *
     * Reads and writes the enclosing picker's color rather than holding its own
     * copy, so the HSV and RGB rows cannot drift apart.
     */
    private class ChannelSlider extends AbstractSliderButton {

        private final Channel channel;

        ChannelSlider(int x, int y, int width, int height, Channel channel) {
            super(x, y, width, height, Component.empty(), 0);
            this.channel = channel;
        }

        /** Refreshes position from the picker's current color. */
        void pull() {
            this.value = (double) read() / channel.max;
            updateMessage();
        }

        /** This channel's value as shown, 0..max. */
        int amount() {
            return (int) Math.round(value * channel.max);
        }

        /**
         * Moves this slider to an absolute value and applies it.
         *
         * For the value box beside it, which sets a number directly rather than
         * by dragging. The box is named as the source of the change so that
         * {@link #syncFrom} leaves its text alone: rewriting it here would move
         * the caret out from under whoever is typing in it.
         */
        void set(int amount, Object source) {
            this.value = (double) Math.clamp(amount, 0, channel.max) / channel.max;
            apply(source);
        }

        /** This channel's value in the current color. */
        private int read() {
            int rgb = color & 0xFFFFFF;
            return switch (channel) {
                case ALPHA -> (color >>> 24) & 0xFF;
                case RED -> (rgb >> 16) & 0xFF;
                case GREEN -> (rgb >> 8) & 0xFF;
                case BLUE -> rgb & 0xFF;
                // From the held HSV rather than recomputed, so a hue chosen on a
                // color that later went black or grey survives.
                case HUE -> Math.round(hsv[0] * 360);
                case SATURATION -> Math.round(hsv[1] * 100);
                case VALUE -> Math.round(hsv[2] * 100);
            };
        }

        @Override
        protected void updateMessage() {
            // Deliberately blank: the value is shown in the box beside the
            // slider, and a number under the moving handle was both hard to read
            // while dragging and impossible to type into.
            setMessage(Component.empty());
        }

        @Override
        protected void applyValue() {
            // A drag: this slider is the source, so every other control follows.
            apply(this);
        }

        /** Writes this channel's value into the color, then refreshes the rest. */
        private void apply(Object source) {
            if (syncing) {
                return;
            }
            int amount = amount();
            int alpha = (color >>> 24) & 0xFF;
            int rgb = color & 0xFFFFFF;

            switch (channel) {
                case ALPHA -> alpha = amount;
                case RED, GREEN, BLUE -> {
                    rgb = switch (channel) {
                        case RED -> (rgb & 0x00FFFF) | (amount << 16);
                        case GREEN -> (rgb & 0xFF00FF) | (amount << 8);
                        default -> (rgb & 0xFFFF00) | amount;
                    };
                    // An RGB edit is authoritative over the held HSV, which must
                    // follow it or the two sets of sliders would disagree.
                    Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsv);
                }
                default -> {
                    switch (channel) {
                        case HUE -> hsv[0] = amount / 360f;
                        case SATURATION -> hsv[1] = amount / 100f;
                        default -> hsv[2] = amount / 100f;
                    }
                    rgb = Color.HSBtoRGB(hsv[0], hsv[1], hsv[2]) & 0xFFFFFF;
                }
            }

            color = (alpha << 24) | rgb;
            syncFrom(source);
            onChange.accept(color);
        }
    }
}
