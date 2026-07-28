package cx.gid.minecraft.blockgrep.client;

import cx.gid.minecraft.blockgrep.Constants;
import cx.gid.minecraft.blockgrep.client.config.BlockGrepConfig;
import cx.gid.minecraft.blockgrep.client.config.PatternManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client entrypoint.
 *
 * The mod is entirely client-side: it reads blocks the client has already been
 * sent and draws boxes locally. Nothing is transmitted, so it is safe on servers
 * that do not have it installed.
 */
public class BlockGrepClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Load before anything can read a setting. apply() also clamps values, so
        // a hand-edited file cannot push a nonsense radius into the scanner.
        BlockGrepConfig.HANDLER.load();
        BlockGrepConfig.get().apply();

        // Deliberately not compiled here. A pattern naming a block tag has to
        // resolve it against the registry, and tags are not bound until a world
        // loads — doing it now throws and takes the whole client down with it.
        // The patterns are marked dirty instead and built on the first tick that
        // has a level, by which point the registry is ready.
        PatternManager.invalidate();

        ClientCommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess) -> GrepCommand.register(dispatcher));

        BlockGrepKeys.register();

        // Both the rescan check and gizmo emission happen here. The game wraps
        // the client tick in a gizmo collection scope and drains it afterwards,
        // so boxes emitted now are drawn for the coming frames.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            BlockGrepConfig config = BlockGrepConfig.get();
            if (!config.enabled) {
                return;
            }
            if (client.level != null) {
                PatternManager.compileIfNeeded();
            }
            GrepState.tick(client);
            if (GrepState.isActive()) {
                MatchRenderer.emit(GrepState.currentHits());
            }
        });

        Constants.LOGGER.info("{} initialized", Constants.MOD_NAME);
    }
}
