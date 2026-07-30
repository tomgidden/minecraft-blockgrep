package cx.gid.minecraft.blockgrep.client.config;

import cx.gid.minecraft.blockgrep.pattern.Symmetry;

import cx.gid.minecraft.blockgrep.Constants;
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

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

    public static final ConfigClassHandler<BlockGrepConfig> HANDLER =
        ConfigClassHandler.createBuilder(BlockGrepConfig.class)
            .id(Identifier.tryBuild(Constants.MOD_ID, "config"))
            .serializer(config -> GsonConfigSerializerBuilder.create(config)
                .setPath(FabricLoader.getInstance().getConfigDir()
                    .resolve(Constants.MOD_ID + ".json"))
                .build())
            .build();

    public static BlockGrepConfig get() {
        return HANDLER.instance();
    }

    // ---- Master switch -----------------------------------------------------

    /**
     * Whether the mod highlights at all.
     *
     * Kept separate from the per-pattern switches so a player can silence the
     * whole overlay without losing which individual patterns were on.
     */
    @SerialEntry(comment = "Master switch for pattern highlighting.")
    public boolean enabled = true;

    // ---- Search ------------------------------------------------------------

    @SerialEntry(comment = "How far from the player to search, in blocks.")
    public int radius = 32;

    @SerialEntry(comment = "Maximum number of matches reported by a single scan.")
    public int limit = 1024;

    // Appearance is per-pattern; see SavedPattern. Several patterns are drawn at
    // once, so color, thickness and through-walls belong to each search rather
    // than to the overlay as a whole.

    // ---- Patterns ----------------------------------------------------------

    /**
     * Every saved pattern. Each carries its own enabled flag and color, and any
     * number of them can be searched for at once — there is no "selected" entry.
     */
    @SerialEntry(comment = "Saved patterns. Each has its own color and on/off switch.")
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
