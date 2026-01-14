package fr.flaton.walkietalkie.network.packet.s2c;

import dev.architectury.networking.NetworkManager;
import fr.flaton.walkietalkie.client.gui.hud.WalkieTalkieHud;
import net.minecraft.network.PacketByteBuf;

import java.util.UUID;

public class ChannelActivityS2CPacket {
    public static void receive(PacketByteBuf buf, NetworkManager.PacketContext context) {
        String channel = buf.readString();
        boolean transmitting = buf.readBoolean();
        int quality = buf.readInt();
        UUID id = buf.readUuid();
        context.queue(() -> {
            WalkieTalkieHud.setChannelActive(channel, transmitting, quality, id);
        });
    }
}
