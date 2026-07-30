package cx.gid.minecraft.blockgrep.neoforge;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import cx.gid.minecraft.blockgrep.client.CommandPlatform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * NeoForge's client commands, behind the shared interface.
 *
 * Client commands here run on the ordinary {@code CommandSourceStack} rather
 * than a client-specific source, so the vanilla builders apply directly.
 */
public final class NeoForgeCommandPlatform implements CommandPlatform<CommandSourceStack> {

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return Commands.literal(name);
    }

    @Override
    public <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(
            String name, ArgumentType<T> type) {
        return Commands.argument(name, type);
    }

    @Override
    public void sendFeedback(CommandSourceStack source, Component message) {
        // Not a "success" in the command-result sense — this is a client-side
        // informational reply, so it never wants to go to the operator log.
        source.sendSuccess(() -> message, false);
    }

    @Override
    public void sendError(CommandSourceStack source, Component message) {
        source.sendFailure(message);
    }
}
