package cx.gid.minecraft.blockgrep.client.config;

import net.minecraft.client.gui.screens.Screen;

/**
 * Opens the settings screen.
 *
 * This used to guard against YetAnotherConfigLib being absent, because the
 * settings were a YACL-generated screen and touching one of its classes without
 * the library present would have been fatal. The settings are now a plain
 * vanilla {@link Screen}, so there is nothing left to guard: the screen always
 * builds, and YACL is needed only for the config file's json5 serialisation,
 * which is a hard dependency rather than a soft one.
 *
 * Kept as a named entry point rather than inlined at its call sites so that
 * "where does the settings screen come from" has one answer — the mod menu
 * integration, the command and the key binding all route through here.
 */
public final class ConfigScreenFactory {

    private ConfigScreenFactory() {}

    public static Screen create(Screen parent) {
        return new PatternListScreen(parent);
    }
}
