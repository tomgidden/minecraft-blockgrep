package cx.gid.minecraft.blockgrep.client.config;

import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/**
 * Puts a "Config" button next to this mod in ModMenu's list.
 *
 * ModMenu is compiled against but never required: Fabric only instantiates an
 * entrypoint when the mod providing its interface is loaded, so without ModMenu
 * this class is never touched.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public com.terraformersmc.modmenu.api.ConfigScreenFactory<?> getModConfigScreenFactory() {
        return (Screen parent) ->
            cx.gid.minecraft.blockgrep.client.config.ConfigScreenFactory.create(parent);
    }
}
