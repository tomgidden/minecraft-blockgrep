package cx.gid.minecraft.blockgrep.client;

import com.mojang.blaze3d.platform.InputConstants;
import cx.gid.minecraft.blockgrep.Constants;
import cx.gid.minecraft.blockgrep.client.config.ConfigScreenFactory;
import cx.gid.minecraft.blockgrep.client.config.BlockGrepConfig;
import cx.gid.minecraft.blockgrep.client.config.PatternManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The mod's key bindings: one to toggle highlighting, one to open the settings.
 *
 * Two independent keys rather than a P-then-C chord. A chord would need its own
 * input state machine and, more importantly, would be invisible to the vanilla
 * controls screen — which is where a player looks to discover a binding, to
 * rebind it, and to be told when it clashes with something else. Two ordinary
 * bindings get all of that for free.
 *
 * Both are registered unbound by default. The mod has no claim on any particular
 * key, and silently taking one risks shadowing a binding the player already has;
 * they appear in the controls screen under the mod's own category, ready to be
 * assigned. The suggested pairing is P for the toggle and a modifier or nearby
 * key for the settings.
 */
public final class BlockGrepKeys {

    private BlockGrepKeys() {}

    /** The controls-screen heading these appear under. */
    private static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "main"));

    private static KeyMapping toggleKey;
    private static KeyMapping configKey;

    public static void register() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.blockgrep.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            CATEGORY));

        configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.blockgrep.config",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(BlockGrepKeys::tick);
    }

    /**
     * Drains any presses that happened this tick.
     *
     * consumeClick() is a queue rather than a level, so this must run every tick
     * whether or not the mod is enabled: leaving presses unconsumed while
     * disabled would mean they all fired at once on re-enabling.
     */
    private static void tick(Minecraft client) {
        while (toggleKey.consumeClick()) {
            BlockGrepConfig config = BlockGrepConfig.get();
            PatternManager.setEnabled(!config.enabled);

            // Said in the action bar rather than chat: it is a transient
            // acknowledgement of a keypress, not something worth a log line.
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.translatable(
                    config.enabled
                        ? "blockgrep.message.toggle.on"
                        : "blockgrep.message.toggle.off"));
            }
        }

        while (configKey.consumeClick()) {
            // Only when the game is willing to have the current screen replaced.
            // Opening over an open container or another menu would dismiss it
            // from under the player; canInterruptScreen answers exactly that.
            if (client.canInterruptScreen()) {
                client.setScreenAndShow(ConfigScreenFactory.create(null));
            }
        }
    }
}
