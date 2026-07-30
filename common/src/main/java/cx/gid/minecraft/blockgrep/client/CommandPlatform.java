package cx.gid.minecraft.blockgrep.client;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.network.chat.Component;

/**
 * The parts of client-command registration the two loaders spell differently.
 *
 * Fabric client commands run on {@code FabricClientCommandSource} and are built
 * with {@code ClientCommands.literal/argument}; NeoForge routes them through the
 * vanilla {@code CommandSourceStack} and {@code Commands.literal/argument}. The
 * command tree itself is identical either way, so it lives once in
 * {@link GrepCommand} and takes one of these to do the four things that differ.
 *
 * Only feedback and error reporting are needed from the source itself — the
 * command never reads a position, a level or a permission — which is what keeps
 * this interface to four methods rather than a wrapper around the whole source.
 *
 * @param <S> the loader's command source type
 */
public interface CommandPlatform<S> {

    /** A literal node, e.g. the {@code list} in {@code /blockgrep list}. */
    LiteralArgumentBuilder<S> literal(String name);

    /** An argument node, e.g. the {@code <n>} in {@code /blockgrep toggle <n>}. */
    <T> RequiredArgumentBuilder<S, T> argument(String name, ArgumentType<T> type);

    /** Ordinary output, shown in chat. */
    void sendFeedback(S source, Component message);

    /** Failure output, shown in red. */
    void sendError(S source, Component message);
}
