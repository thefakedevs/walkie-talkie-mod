package fr.flaton.walkietalkie;

import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.registry.item.ItemPropertiesRegistry;
import fr.flaton.walkietalkie.client.KeyBindings;
import fr.flaton.walkietalkie.client.gui.hud.WalkieTalkieHud;
import fr.flaton.walkietalkie.item.WalkieTalkieItem;
import fr.flaton.walkietalkie.network.ModMessages;
import net.minecraft.util.Identifier;

public class WalkieTalkieClient {

    public static void init() {
        ModMessages.registerS2CPackets();
        KeyBindings.register();

        ClientGuiEvent.RENDER_HUD.register(WalkieTalkieHud::render);

        ItemPropertiesRegistry.registerGeneric(new Identifier(Constants.MOD_ID, "activate"), ((stack, world, entity, seed) -> {
            if (!stack.hasNbt()) {
                return 0;
            }
            return stack.getNbt().getBoolean(WalkieTalkieItem.NBT_KEY_ACTIVATE) ? 1.0f : 0.0f;
        }));
    }
}
