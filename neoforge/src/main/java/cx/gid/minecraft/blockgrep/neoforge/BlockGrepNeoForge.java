package cx.gid.minecraft.blockgrep.neoforge;

import cx.gid.minecraft.blockgrep.Constants;
import cx.gid.minecraft.blockgrep.client.BlockGrepKeys;
import cx.gid.minecraft.blockgrep.client.GrepCommand;
import cx.gid.minecraft.blockgrep.client.GrepState;
import cx.gid.minecraft.blockgrep.client.MatchRenderer;
import cx.gid.minecraft.blockgrep.client.config.BlockGrepConfig;
import cx.gid.minecraft.blockgrep.client.config.PatternManager;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * NeoForge entrypoint.
 *
 * The mod is client-only — it reads blocks the client already has and draws
 * boxes locally — so this is declared {@code Dist.CLIENT} and there is no
 * server half at all.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class BlockGrepNeoForge {

    public BlockGrepNeoForge(IEventBus modEventBus) {
        // Config is read here rather than in client setup so that a setting is
        // never observed before it has been loaded.
        BlockGrepConfig.setPath(
            FMLPaths.CONFIGDIR.get().resolve(Constants.MOD_ID + ".json"));
        BlockGrepConfig.load();
        BlockGrepConfig.get().apply();

        // Not compiled yet: a pattern naming a block tag needs the registry,
        // and tags are not bound until a world loads. See BlockGrepClient.
        PatternManager.invalidate();

        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        Constants.LOGGER.info("{} (NeoForge) initialized", Constants.MOD_NAME);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        for (net.minecraft.client.KeyMapping mapping : BlockGrepKeys.create()) {
            event.register(mapping);
        }
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        GrepCommand.register(event.getDispatcher(), new NeoForgeCommandPlatform());
    }

    /**
     * The per-tick scan and gizmo emission.
     *
     * NeoForge has no direct equivalent of Fabric's END_CLIENT_TICK, so this
     * hangs off the client player's post-tick. That fires once per client tick
     * while in a world, which is exactly when there is anything to scan — and
     * outside a world the mod has nothing to do anyway.
     */
    private void onClientTick(PlayerTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (event.getEntity() != client.player) {
            return;
        }

        BlockGrepKeys.tick(client);

        if (!BlockGrepConfig.get().enabled) {
            return;
        }
        if (client.level != null) {
            PatternManager.compileIfNeeded();
        }
        GrepState.tick(client);
        if (GrepState.isActive()) {
            MatchRenderer.emit(GrepState.currentHits());
        }
    }
}
