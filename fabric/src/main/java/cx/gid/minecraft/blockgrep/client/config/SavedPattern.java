package cx.gid.minecraft.blockgrep.client.config;

import cx.gid.minecraft.blockgrep.client.ActivePattern;
import cx.gid.minecraft.blockgrep.pattern.BlockPredicates;
import cx.gid.minecraft.blockgrep.pattern.Pattern;
import cx.gid.minecraft.blockgrep.pattern.PatternSpec;
import cx.gid.minecraft.blockgrep.pattern.Symmetry;

/**
 * A named, saved pattern, with how it should be drawn.
 *
 * Only the source text is stored, never a built {@link Pattern}. A compiled
 * pattern holds opaque predicate lambdas and references to registry objects,
 * neither of which survives a round-trip through JSON; and block ids that were
 * valid when saved may belong to a mod that is no longer present. Keeping the
 * spec means a preset is re-parsed against whatever registry is live now, and a
 * preset that has become invalid degrades to a readable error rather than a
 * crash at load.
 *
 * The spec syntax is exactly the one {@code /blockgrep pattern} accepts, so
 * anything a player can type at the command line can be saved verbatim, and
 * anything saved here can be pasted back into chat.
 */
public class SavedPattern {

    /**
     * Display name, or empty when the player has not given one.
     *
     * Deliberately empty by default rather than seeded from the spec. A name
     * copied from the spec at creation would go stale the moment the spec was
     * edited, leaving the list describing something the pattern no longer is;
     * {@link #label()} falls back to the live spec instead, so an unnamed
     * pattern always describes itself accurately.
     */
    public String name = "";

    /**
     * The pattern source, in {@code pattern} syntax: cells separated by spaces,
     * rows by '|', layers by '||'. For example {@code "mud ? | ? mud"}.
     */
    public String spec = "?";

    /**
     * Which orientations count as a match, as a symmetry spec such as
     * {@code "XyZ"} or {@code "XyZ+xz"}.
     */
    public String symmetry = Symmetry.YAW.spec();

    /**
     * Outline color, as 0xAARRGGBB.
     *
     * Per-pattern rather than global because several patterns are searched at
     * once, and a shared appearance would leave the results indistinguishable.
     * The alpha is part of the color rather than a separate opacity setting, so
     * that one pattern can be emphasised and another dimmed without touching a
     * global that would move both.
     */
    public int strokeColor = 0xFF00E5FF;

    /**
     * Fill color, as 0xAARRGGBB.
     *
     * Kept separate from the outline rather than derived from it, since the two
     * usually want very different alphas: a solid fill on a dense set of matches
     * hides the terrain the player is trying to read.
     */
    public int fillColor = 0x2600E5FF;

    /** Thickness of the outline. */
    public float strokeWidth = 2.0f;

    /**
     * Whether the outline and fill share a hue.
     *
     * When set, editing either color copies its RGB to the other and leaves both
     * alphas alone — which is almost always what is wanted, since the two differ
     * by transparency rather than by color. Saved per pattern so the relationship
     * survives reopening the editor.
     */
    public boolean linkColors = false;

    /**
     * Whether this pattern's boxes draw through whatever is in front of them.
     *
     * Per-pattern because it is a question about the individual search: a
     * pattern being used to find buried ore wants it, while one checking
     * something in front of the player usually does not.
     */
    public boolean xray = false;

    /**
     * Whether this pattern is currently being searched for.
     *
     * Lets a pattern be kept but silenced, which is the point of having a list:
     * switching one off must not mean deleting and retyping it.
     */
    public boolean enabled = true;

    /** GSON requires a no-args constructor to deserialise entries. */
    public SavedPattern() {}

    public SavedPattern(String name, String spec, Symmetry symmetry) {
        this(name, spec, symmetry, 0x00E5FF, true);
    }

    /**
     * Builds a pattern from a plain RGB hue, applying the default opacities.
     *
     * The two alphas differ by design — a visible outline and a faint fill —
     * so callers naming only a hue still get a readable box.
     */
    public SavedPattern(String name, String spec, Symmetry symmetry,
                        int rgb, boolean enabled) {
        this.name = name;
        this.spec = spec;
        this.symmetry = symmetry.spec();
        this.strokeColor = 0xFF000000 | (rgb & 0xFFFFFF);
        this.fillColor = 0x26000000 | (rgb & 0xFFFFFF);
        this.enabled = enabled;
    }

    /** A deep-enough copy for the editor to mutate without touching the original. */
    public SavedPattern copy() {
        SavedPattern out = new SavedPattern();
        out.name = name;
        out.spec = spec;
        out.symmetry = symmetry;
        out.strokeColor = strokeColor;
        out.fillColor = fillColor;
        out.strokeWidth = strokeWidth;
        out.linkColors = linkColors;
        out.xray = xray;
        out.enabled = enabled;
        return out;
    }

    /**
     * Compiles this into the runtime form, or null when it does not parse.
     *
     * The null return is for callers assembling the live set, where one bad
     * entry must not stop the others being searched for.
     */
    public ActivePattern toActive() {
        try {
            return new ActivePattern(label(), compile(), symmetry(),
                strokeColor, fillColor, strokeWidth, xray);
        } catch (BlockPredicates.ParseException e) {
            return null;
        }
    }

    /**
     * The symmetry to match this preset at.
     *
     * Falls back to the default when the stored spec is absent or no longer
     * parses, so a hand-edited config cannot stop the mod from starting.
     */
    public Symmetry symmetry() {
        if (symmetry == null) {
            return Symmetry.YAW;
        }
        try {
            return Symmetry.parse(symmetry);
        } catch (BlockPredicates.ParseException e) {
            return Symmetry.YAW;
        }
    }

    /**
     * Compiles this preset into a usable pattern.
     *
     * Throws rather than returning null so the caller is forced to surface the
     * parse failure; a silently-skipped preset would look like the mod had
     * simply stopped working.
     */
    public Pattern compile() throws BlockPredicates.ParseException {
        return PatternSpec.parse(spec);
    }

    /** A short label for lists, falling back to the spec when unnamed. */
    public String label() {
        return name == null || name.isBlank() ? spec : name;
    }
}
