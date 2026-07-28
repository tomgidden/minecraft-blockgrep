package cx.gid.minecraft.blockgrep.client.config;

import cx.gid.minecraft.blockgrep.Constants;
import cx.gid.minecraft.blockgrep.client.ActivePattern;
import cx.gid.minecraft.blockgrep.client.GrepState;
import cx.gid.minecraft.blockgrep.pattern.BlockPredicates;
import cx.gid.minecraft.blockgrep.pattern.Symmetry;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the configured patterns into the live search set.
 *
 * The bridge between "what the config says" and "what the scanner is doing".
 * Everything that changes the patterns — startup, the editor screens, a command
 * — routes through {@link #save()}, which marks the live set stale;
 * {@link #activateConfigured()} then rebuilds it, so there is exactly one place
 * where a saved pattern becomes live and exactly one place a parse failure is
 * reported.
 *
 * The rebuild is deferred to the client tick rather than done at the point of
 * the edit. Compiling a pattern resolves any block tags it names, and tags are
 * bound only once a world has loaded; compiling during client init or from the
 * main menu throws, and at startup that is fatal to the whole game.
 */
public final class PatternManager {

    private PatternManager() {}

    /** Most recent activation failures, surfaced by {@code /blockgrep status}. */
    private static List<Component> errors = List.of();

    /**
     * Whether the live set needs rebuilding from the config.
     *
     * Compiling is deferred rather than done at the moment of the edit because a
     * pattern naming a block tag can only be compiled once the registry has its
     * tags bound, which does not happen until a world is loaded. Setting a flag
     * lets the config be edited from the main menu — or during client init —
     * without the compile being attempted too early.
     */
    private static boolean dirty = true;

    /** Marks the live set as needing a rebuild on the next opportunity. */
    public static void invalidate() {
        dirty = true;
    }

    /**
     * Marks the live set stale without writing the config to disk.
     *
     * For the editor, where every keystroke and every drag of a color slider
     * changes a pattern. Those must reach the scanner immediately — the point of
     * editing beside the world is seeing the effect — but rewriting the config
     * file at that rate would be pointless disk traffic. The screen persists once
     * when it closes; this keeps the running search current in between.
     */
    public static void refreshLive() {
        invalidate();
    }

    /**
     * Rebuilds the live set if it is stale. Called from the client tick once a
     * level exists, which is the earliest point tags are guaranteed bound.
     */
    public static void compileIfNeeded() {
        if (dirty) {
            activateConfigured();
        }
    }

    /**
     * Compiles every enabled pattern and hands the set to the scanner.
     *
     * A pattern that no longer parses — a block from a mod since removed, say —
     * is skipped and its reason recorded rather than thrown, so one broken entry
     * does not stop the others being searched for.
     *
     * Must not be called before a world is loaded; see {@link #compileIfNeeded}.
     */
    public static void activateConfigured() {
        BlockGrepConfig config = BlockGrepConfig.get();
        List<Component> failures = new ArrayList<>();
        List<ActivePattern> live = new ArrayList<>();

        // Note we do not clear on !enabled. The tick loop already skips all
        // scanning and drawing while disabled, and keeping the patterns loaded
        // means toggling the switch off and on again restores exactly what was
        // being shown rather than silently forgetting it.

        for (SavedPattern saved : config.patterns) {
            if (!saved.enabled) {
                continue;
            }
            try {
                live.add(new ActivePattern(
                    saved.label(), saved.compile(), saved.symmetry(),
                    saved.strokeColor, saved.fillColor,
                    saved.strokeWidth, saved.xray));
            } catch (BlockPredicates.ParseException e) {
                failures.add(Component.translatable(
                    "blockgrep.command.error.invalid", saved.label(), e.getMessage()));
                // The log stays in English regardless of the client's language:
                // it is read by whoever is diagnosing a broken config, often
                // from a pasted log, and a translated line is harder to search.
                Constants.LOGGER.warn("Pattern '{}' is invalid: {}",
                    saved.label(), e.getMessage());
            }
        }

        errors = List.copyOf(failures);
        dirty = false;
        GrepState.setPatterns(live);
    }

    /** Adds a pattern and makes it live. Returns the entry that was added. */
    public static SavedPattern addPattern(String name, String spec, Symmetry symmetry) {
        BlockGrepConfig config = BlockGrepConfig.get();
        SavedPattern added = new SavedPattern(name, spec, symmetry,
            nextColor(config.patterns.size()), true);
        config.patterns.add(added);
        save();
        return added;
    }

    /**
     * Removes a pattern by position, returning false when out of range.
     *
     * The caller reports that; nothing is changed in the failing case.
     */
    public static boolean removePattern(int index) {
        BlockGrepConfig config = BlockGrepConfig.get();
        if (index < 0 || index >= config.patterns.size()) {
            return false;
        }
        config.patterns.remove(index);
        save();
        return true;
    }

    /** Flips one pattern's switch, returning its new state, or null if absent. */
    public static Boolean togglePattern(int index) {
        BlockGrepConfig config = BlockGrepConfig.get();
        if (index < 0 || index >= config.patterns.size()) {
            return null;
        }
        SavedPattern pattern = config.patterns.get(index);
        pattern.enabled = !pattern.enabled;
        save();
        return pattern.enabled;
    }

    /**
     * Persists the config and schedules a rebuild of the live set.
     *
     * Marks rather than rebuilds, because this is reached from the settings
     * screens, which can be open at the main menu where no world — and so no
     * bound tag registry — exists yet.
     */
    public static void save() {
        BlockGrepConfig.HANDLER.save();
        invalidate();
    }

    /** Turns highlighting on or off and persists the switch. */
    public static void setEnabled(boolean enabled) {
        BlockGrepConfig.get().enabled = enabled;
        save();
    }

    /**
     * A distinct starting color for a newly added pattern.
     *
     * Cycles a small hand-picked palette rather than generating a hue, so the
     * defaults stay legible against terrain and distinguishable from each other;
     * the player can override it in the editor regardless.
     */
    public static int nextColor(int index) {
        int[] palette = {
            0x00E5FF, // cyan
            0x4CFF4C, // green
            0xFFB74C, // amber
            0xFF4C8C, // pink
            0xB44CFF, // violet
            0xFFFF4C, // yellow
        };
        return palette[Math.floorMod(index, palette.length)];
    }

    /** Activation failures from the last rebuild; empty when all is well. */
    public static List<Component> errors() {
        return errors;
    }
}
