package cx.gid.minecraft.blockgrep.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import cx.gid.minecraft.blockgrep.client.config.ConfigScreenFactory;
import cx.gid.minecraft.blockgrep.client.config.BlockGrepConfig;
import cx.gid.minecraft.blockgrep.client.config.PatternManager;
import cx.gid.minecraft.blockgrep.client.config.SavedPattern;
import cx.gid.minecraft.blockgrep.client.ActivePattern;
import cx.gid.minecraft.blockgrep.pattern.BlockPredicates;
import cx.gid.minecraft.blockgrep.pattern.Pattern;
import cx.gid.minecraft.blockgrep.pattern.PatternSpec;
import cx.gid.minecraft.blockgrep.pattern.Symmetry;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The /blockgrep client command.
 *
 * Registered on the client's own dispatcher, so it works on any server without
 * the server knowing this mod exists.
 *
 * Several patterns can be active at once, so the commands that add one are
 * additive: {@code pattern} and {@code box} append to the list rather than
 * replacing what is there.
 *
 *   /blockgrep pattern [sym] <spec>       add a shape of any dimensions
 *   /blockgrep box <x> <y> <z> <blocks>   add a uniform box
 *   /blockgrep list                       show the saved patterns
 *   /blockgrep toggle <n>                 turn one on or off
 *   /blockgrep remove <n>                 delete one
 *   /blockgrep name <n> <text>            rename one
 *   /blockgrep only <n>                   enable one, disable the rest
 *   /blockgrep all on|off                 every pattern at once
 *   /blockgrep radius <n>                 search radius
 *   /blockgrep limit <n>                  cap on reported matches
 *   /blockgrep off                        stop highlighting entirely
 */
public final class GrepCommand {

    private GrepCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            ClientCommands.literal("blockgrep")
                .then(boxCommand())
                .then(patternCommand())
                .then(listCommand())
                .then(toggleCommand())
                .then(removeCommand())
                .then(nameCommand())
                .then(onlyCommand())
                .then(allCommand())
                .then(radiusCommand())
                .then(limitCommand())
                .then(offCommand())
                .then(statusCommand())
                .then(onCommand())
                .then(configCommand())
        );
    }

    /** {@code on} — re-enable highlighting without changing the pattern. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> onCommand() {
        return ClientCommands.literal("on")
            .executes(ctx -> {
                PatternManager.setEnabled(true);
                feedback(ctx.getSource(),
                    Component.translatable("blockgrep.command.on"));
                return 1;
            });
    }

    /**
     * {@code config} — open the settings screen.
     *
     * Opening is deferred to the next client tick: a screen cannot be shown from
     * inside command dispatch, which happens while the current screen (the chat
     * box) is still processing input.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> configCommand() {
        return ClientCommands.literal("config")
            .executes(ctx -> {
                Minecraft client = Minecraft.getInstance();
                client.schedule(() -> client.setScreenAndShow(ConfigScreenFactory.create(null)));
                return 1;
            });
    }

    /** {@code list} — show every saved pattern with its number and state. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> listCommand() {
        return ClientCommands.literal("list")
            .executes(ctx -> {
                List<SavedPattern> patterns = BlockGrepConfig.get().patterns;
                if (patterns.isEmpty()) {
                    feedback(ctx.getSource(),
                        Component.translatable("blockgrep.command.list.empty"));
                    return 1;
                }
                feedback(ctx.getSource(),
                    Component.translatable("blockgrep.command.list.header"));
                for (int i = 0; i < patterns.size(); i++) {
                    SavedPattern pattern = patterns.get(i);
                    feedback(ctx.getSource(), Component.translatable(
                        "blockgrep.command.list.entry",
                        i + 1,
                        Component.translatable(pattern.enabled
                            ? "blockgrep.command.list.on"
                            : "blockgrep.command.list.off"),
                        pattern.label(),
                        pattern.symmetry));
                }
                return 1;
            });
    }

    /** {@code toggle <n>} — turn one pattern on or off. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> toggleCommand() {
        return ClientCommands.literal("toggle")
            .then(ClientCommands.argument("number", IntegerArgumentType.integer(1))
                .executes(ctx -> {
                    int oneBased = IntegerArgumentType.getInteger(ctx, "number");
                    Boolean now = PatternManager.togglePattern(oneBased - 1);
                    if (now == null) {
                        return unknownPattern(ctx.getSource(), oneBased);
                    }
                    feedback(ctx.getSource(), Component.translatable(
                        now ? "blockgrep.command.toggled.on"
                            : "blockgrep.command.toggled.off",
                        nameOf(oneBased - 1)));
                    return 1;
                }));
    }

    /** {@code remove <n>} — delete a pattern. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> removeCommand() {
        return ClientCommands.literal("remove")
            .then(ClientCommands.argument("number", IntegerArgumentType.integer(1))
                .executes(ctx -> {
                    int oneBased = IntegerArgumentType.getInteger(ctx, "number");
                    // Read the name before removing it, or the message would have
                    // to describe a pattern that no longer exists.
                    Component name = nameOf(oneBased - 1);
                    if (!PatternManager.removePattern(oneBased - 1)) {
                        return unknownPattern(ctx.getSource(), oneBased);
                    }
                    feedback(ctx.getSource(),
                        Component.translatable("blockgrep.command.removed", name));
                    return 1;
                }));
    }

    /** {@code name <n> <text>} — rename a pattern. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> nameCommand() {
        return ClientCommands.literal("name")
            .then(ClientCommands.argument("number", IntegerArgumentType.integer(1))
                .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        int oneBased = IntegerArgumentType.getInteger(ctx, "number");
                        List<SavedPattern> patterns = BlockGrepConfig.get().patterns;
                        if (oneBased > patterns.size()) {
                            return unknownPattern(ctx.getSource(), oneBased);
                        }
                        patterns.get(oneBased - 1).name =
                            StringArgumentType.getString(ctx, "text").trim();
                        PatternManager.save();
                        feedback(ctx.getSource(), Component.translatable(
                            "blockgrep.command.renamed", nameOf(oneBased - 1)));
                        return 1;
                    })));
    }

    /**
     * {@code only <n>} — enable one pattern and disable the rest.
     *
     * The quickest way back to looking at a single thing after several have been
     * switched on, without deleting any of them.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> onlyCommand() {
        return ClientCommands.literal("only")
            .then(ClientCommands.argument("number", IntegerArgumentType.integer(1))
                .executes(ctx -> {
                    int oneBased = IntegerArgumentType.getInteger(ctx, "number");
                    List<SavedPattern> patterns = BlockGrepConfig.get().patterns;
                    if (oneBased > patterns.size()) {
                        return unknownPattern(ctx.getSource(), oneBased);
                    }
                    for (int i = 0; i < patterns.size(); i++) {
                        patterns.get(i).enabled = (i == oneBased - 1);
                    }
                    PatternManager.save();
                    feedback(ctx.getSource(), Component.translatable(
                        "blockgrep.command.only", nameOf(oneBased - 1)));
                    return 1;
                }));
    }

    /** {@code all on|off} — switch every pattern at once. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> allCommand() {
        return ClientCommands.literal("all")
            .then(ClientCommands.literal("on").executes(ctx -> setAll(ctx.getSource(), true)))
            .then(ClientCommands.literal("off").executes(ctx -> setAll(ctx.getSource(), false)));
    }

    private static int setAll(FabricClientCommandSource source, boolean enabled) {
        List<SavedPattern> patterns = BlockGrepConfig.get().patterns;
        for (SavedPattern pattern : patterns) {
            pattern.enabled = enabled;
        }
        PatternManager.save();
        feedback(source, Component.translatable(
            enabled ? "blockgrep.command.all.on" : "blockgrep.command.all.off",
            patterns.size()));
        return 1;
    }

    /**
     * The display name of a pattern by index, for feedback messages.
     *
     * A pattern's own name is player-supplied text and so is never translated;
     * only the fallback for an index that no longer exists is, since that phrase
     * is the mod's own words rather than the player's.
     */
    private static Component nameOf(int index) {
        List<SavedPattern> patterns = BlockGrepConfig.get().patterns;
        if (index < 0 || index >= patterns.size()) {
            return Component.translatable("blockgrep.command.that_pattern");
        }
        return Component.literal("'" + patterns.get(index).label() + "'");
    }

    private static int unknownPattern(FabricClientCommandSource source, int oneBased) {
        error(source, Component.translatable(
            "blockgrep.command.error.no_pattern", oneBased));
        return 0;
    }

    /** {@code box <x> <y> <z> <blocks>} — a uniform box of the given size. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> boxCommand() {
        return ClientCommands.literal("box")
            .then(ClientCommands.argument("sizeX", IntegerArgumentType.integer(1, 16))
                .then(ClientCommands.argument("sizeY", IntegerArgumentType.integer(1, 16))
                    .then(ClientCommands.argument("sizeZ", IntegerArgumentType.integer(1, 16))
                        .then(ClientCommands.argument("blocks", StringArgumentType.greedyString())
                            .executes(ctx -> applyBox(
                                ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "sizeX"),
                                IntegerArgumentType.getInteger(ctx, "sizeY"),
                                IntegerArgumentType.getInteger(ctx, "sizeZ"),
                                StringArgumentType.getString(ctx, "blocks")))
                            )
                        )
                    )
                );
    }

    /**
     * {@code pattern [symmetry] <spec>} — a general shape of any dimensions.
     *
     * Replaces the old single-layer {@code rows}, which this accepts unchanged:
     * a spec with no layer separator is simply a one-layer pattern.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> patternCommand() {
        return ClientCommands.literal("pattern")
            .then(ClientCommands.argument("spec", StringArgumentType.greedyString())
                .executes(ctx -> applyPattern(
                    ctx.getSource(), StringArgumentType.getString(ctx, "spec"))));
    }

    /** {@code radius <n>} — how far from the player to search. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> radiusCommand() {
        return ClientCommands.literal("radius")
            .then(ClientCommands.argument("blocks",
                    IntegerArgumentType.integer(GrepState.MIN_RADIUS, GrepState.MAX_RADIUS))
                .executes(ctx -> {
                    BlockGrepConfig.get().radius =
                        IntegerArgumentType.getInteger(ctx, "blocks");
                    BlockGrepConfig.get().apply();
                    BlockGrepConfig.HANDLER.save();
                    feedback(ctx.getSource(), Component.translatable(
                        "blockgrep.command.radius", GrepState.radius()));
                    return 1;
                }));
    }

    /** {@code limit <n>} — cap on reported matches. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> limitCommand() {
        return ClientCommands.literal("limit")
            .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 20000))
                .executes(ctx -> {
                    BlockGrepConfig.get().limit =
                        IntegerArgumentType.getInteger(ctx, "count");
                    BlockGrepConfig.get().apply();
                    BlockGrepConfig.HANDLER.save();
                    feedback(ctx.getSource(), Component.translatable(
                        "blockgrep.command.limit", GrepState.limit()));
                    return 1;
                }));
    }

    /** {@code off} — stop highlighting. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> offCommand() {
        return ClientCommands.literal("off")
            .executes(ctx -> {
                // Only the master switch: the patterns themselves are kept, so
                // 'on' restores exactly what was being shown.
                PatternManager.setEnabled(false);
                feedback(ctx.getSource(),
                    Component.translatable("blockgrep.command.off"));
                return 1;
            });
    }

    /** {@code status} — summarise what is being searched for and found. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> statusCommand() {
        return ClientCommands.literal("status")
            .executes(ctx -> {
                if (!BlockGrepConfig.get().enabled) {
                    feedback(ctx.getSource(),
                        Component.translatable("blockgrep.command.status.disabled"));
                    return 1;
                }

                // Compile failures are reported first and individually: with
                // several patterns live, "something is broken" is not actionable
                // without saying which.
                for (Component failure : PatternManager.errors()) {
                    error(ctx.getSource(), failure);
                }

                List<ActivePattern> live = GrepState.activePatterns();
                if (live.isEmpty()) {
                    feedback(ctx.getSource(),
                        Component.translatable("blockgrep.command.status.none"));
                    return PatternManager.errors().isEmpty() ? 1 : 0;
                }

                feedback(ctx.getSource(), Component.translatable(
                    "blockgrep.command.status.header",
                    live.size(), GrepState.radius(), GrepState.currentHits().size()));
                for (ActivePattern entry : live) {
                    long found = GrepState.currentHits().stream()
                        .filter(hit -> hit.source() == entry)
                        .count();
                    feedback(ctx.getSource(), Component.translatable(
                        "blockgrep.command.status.entry",
                        entry.name(), entry.pattern().description(), found));
                }
                return 1;
            });
    }

    private static int applyBox(FabricClientCommandSource source,
                                int sx, int sy, int sz, String blocks) {
        try {
            // Parsed here only so an invalid block name is reported now rather
            // than becoming a saved pattern that fails to compile later.
            Pattern pattern = Pattern.uniformBox(sx, sy, sz, blocks);
            String spec = PatternSpec.uniformBoxSpec(sx, sy, sz, blocks);
            SavedPattern added = PatternManager.addPattern(
                sx + "x" + sy + "x" + sz + " " + blocks.trim(), spec, Symmetry.YAW);
            feedback(source, Component.translatable("blockgrep.command.added",
                describeAdded(added, pattern)));
            return 1;
        } catch (BlockPredicates.ParseException e) {
            error(source, e.getMessage());
            return 0;
        }
    }

    /**
     * Applies a {@code pattern} argument: optional symmetry, then the shape.
     *
     * The heavy lifting is in {@link PatternSpec} so that a spec typed here and
     * the same spec stored in the config cannot be read differently.
     */
    private static int applyPattern(FabricClientCommandSource source, String spec) {
        try {
            PatternSpec.Parsed parsed = PatternSpec.parseWithSymmetry(spec);
            SavedPattern added = PatternManager.addPattern(
                parsed.patternText(), parsed.patternText(), parsed.symmetry());
            feedback(source, Component.translatable("blockgrep.command.added",
                describeAdded(added, parsed.pattern())));
            return 1;
        } catch (BlockPredicates.ParseException e) {
            error(source, e.getMessage());
            return 0;
        }
    }

    /**
     * How a newly added pattern is announced: its number, shape and symmetry.
     *
     * The number matters because every other command addresses patterns by
     * position, so it is the first thing the player needs.
     */
    private static Component describeAdded(SavedPattern added, Pattern compiled) {
        int number = BlockGrepConfig.get().patterns.indexOf(added) + 1;
        return Component.translatable("blockgrep.command.added.detail",
            number, compiled.description(), describeSymmetry(added.symmetry()));
    }

    /**
     * Describes a symmetry group in the player's language.
     *
     * {@link Symmetry} lives in the loader-agnostic module and cannot reach the
     * language files, so it reports the raw facts — how many orientations, and
     * the canonical spec — and the phrasing is chosen here.
     */
    private static Component describeSymmetry(Symmetry symmetry) {
        if (symmetry.isIdentityOnly()) {
            return Component.translatable("blockgrep.symmetry.fixed");
        }
        return Component.translatable("blockgrep.symmetry.orientations",
            symmetry.transforms().size(), symmetry.spec());
    }

    /**
     * Sends one line of command output, tagged with the mod's name.
     *
     * The tag is part of the translated string rather than prepended here, so a
     * translation can move or drop it — some languages would put a bracketed
     * prefix differently, and it is the kind of thing a translator should own.
     */
    private static void feedback(FabricClientCommandSource source, Component message) {
        source.sendFeedback(Component.translatable("blockgrep.command.prefix", message));
    }

    private static void error(FabricClientCommandSource source, Component message) {
        source.sendError(Component.translatable("blockgrep.command.prefix", message));
    }

    /** Overload for text that is not itself translatable, such as a parse error. */
    private static void error(FabricClientCommandSource source, String message) {
        error(source, Component.literal(message));
    }
}
