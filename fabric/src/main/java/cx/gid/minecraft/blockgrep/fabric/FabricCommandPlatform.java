package cx.gid.minecraft.blockgrep.fabric;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import cx.gid.minecraft.blockgrep.client.CommandPlatform;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** Fabric's client command source, behind the shared interface. */
public final class FabricCommandPlatform
        implements CommandPlatform<FabricClientCommandSource> {

    @Override
    public LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return ClientCommands.literal(name);
    }

    @Override
    public <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(
            String name, ArgumentType<T> type) {
        return ClientCommands.argument(name, type);
    }

    @Override
    public void sendFeedback(FabricClientCommandSource source, Component message) {
        source.sendFeedback(message);
    }

    @Override
    public void sendError(FabricClientCommandSource source, Component message) {
        source.sendError(message);
    }
}
