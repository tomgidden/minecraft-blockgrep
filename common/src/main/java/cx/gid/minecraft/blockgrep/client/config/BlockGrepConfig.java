package cx.gid.minecraft.blockgrep.client.config;

import cx.gid.minecraft.blockgrep.pattern.Symmetry;

import cx.gid.minecraft.blockgrep.Constants;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted settings, and the single source of truth for them.
 *
 * The mod previously kept radius and limit in {@link
 * cx.gid.minecraft.blockgrep.client.GrepState} as plain statics. Those still
 * exist and remain the values the scanner reads each tick, because the scan path
 * must not touch config machinery; this class owns the durable copy and pushes
 * it into GrepState via {@link #apply()}. Anything that changes a value here
 * calls apply(), so the two never drift.
 */
public class BlockGrepConfig {

    /**
     * Field names go to snake_case, matching the file this has always written.
     * Nulls are serialised so a cleared display name stays an explicit "" in the
     * file rather than vanishing and reverting to a default on the next load.
     */
    private static final Gson GSON = new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .setPrettyPrinting()
        .create();

    /**
     * Where the config file lives.
     *
     * Supplied by the loader entrypoint rather than looked up here, because the
     * two loaders find their config directory by different means and this class
     * is shared between them.
     */
    private static Path path;

    /** The loaded config, or defaults until {@link #load} has run. */
    private static BlockGrepConfig instance = new BlockGrepConfig();

    public static BlockGrepConfig get() {
        return instance;
    }

    /** Names the config file. Called once, before {@link #load}. */
    public static void setPath(Path configPath) {
        path = configPath;
    }

    /**
     * Reads the config, falling back to defaults.
     *
     * A file that does not parse is left on disk untouched rather than being
     * overwritten with defaults: it is likely hand-edited, and silently
     * discarding it would lose whatever the player was trying to write.
     */
    public static void load() {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            BlockGrepConfig loaded = GSON.fromJson(reader, BlockGrepConfig.class);
            if (loaded != null) {
                instance = loaded;
            }
        } catch (IOException | JsonParseException e) {
            Constants.LOGGER.warn("Could not read {}: {}", path, e.toString());
        }
    }

    /** Writes the config, creating the directory if this is a first run. */
    public static void save() {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            Constants.LOGGER.error("Could not write {}: {}", path, e.toString());
        }
    }

    // ---- Master switch -----------------------------------------------------

    /**
     * Whether the mod highlights at all.
     *
     * Kept separate from the per-pattern switches so a player can silence the
     * whole overlay without losing which individual patterns were on.
     */
    public boolean enabled = true;

    // ---- Search ------------------------------------------------------------

    public int radius = 32;

    public int limit = 1024;

    // Appearance is per-pattern; see SavedPattern. Several patterns are drawn at
    // once, so color, thickness and through-walls belong to each search rather
    // than to the overlay as a whole.

    // ---- Patterns ----------------------------------------------------------

    /**
     * Every saved pattern. Each carries its own enabled flag and color, and any
     * number of them can be searched for at once — there is no "selected" entry.
     */
    public List<SavedPattern> patterns = defaultPatterns();

    /**
     * The patterns a fresh install starts with — a couple of shapes that
     * demonstrate the syntax beyond a uniform box.
     */
    private static List<SavedPattern> defaultPatterns() {
        List<SavedPattern> out = new ArrayList<>();
        out.add(new SavedPattern("Diagonal mud", "mud ? | ? mud",
            Symmetry.YAW, 0xFFB74C, false));
        out.add(new SavedPattern("Water above grass", "grass_block || water",
            Symmetry.YAW, 0x4cB7FF, false));
        out.add(new SavedPattern("Diamond ore pair", "diamond_ore diamond_ore",
            Symmetry.YAW, 0x4CFF4C, false));
        return out;
    }

    /**
     * Clamps values into range and pushes the search settings into GrepState.
     *
     * Called after a load and after any GUI save. Clamping happens here rather
     * than at each call site because a hand-edited config file is just as likely
     * a source of nonsense as the GUI is.
     */
    public void apply() {
        radius = Math.clamp(radius,
            cx.gid.minecraft.blockgrep.client.GrepState.MIN_RADIUS,
            cx.gid.minecraft.blockgrep.client.GrepState.MAX_RADIUS);
        limit = Math.max(1, limit);

        if (patterns == null) {
            patterns = new ArrayList<>();
        }
        for (SavedPattern pattern : patterns) {
            pattern.strokeWidth = Math.clamp(pattern.strokeWidth, 0.5f, 8.0f);
        }

        cx.gid.minecraft.blockgrep.client.GrepState.setRadius(radius);
        cx.gid.minecraft.blockgrep.client.GrepState.setLimit(limit);
    }

}
