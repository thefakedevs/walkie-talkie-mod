package fr.flaton.walkietalkie.network.packet.c2s;

import dev.architectury.networking.NetworkManager;
import fr.flaton.walkietalkie.Util;
import fr.flaton.walkietalkie.config.ModConfig;
import fr.flaton.walkietalkie.item.WalkieTalkieItem;
import fr.flaton.walkietalkie.network.ModMessages;
import io.netty.buffer.Unpooled;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

public class UpdateWalkieTalkieC2SPacket {
    public static void receive(PacketByteBuf packetByteBuf, NetworkManager.PacketContext packetContext) {
        ServerPlayerEntity player = (ServerPlayerEntity) packetContext.getPlayer();

        ItemStack stack = Util.getWalkieTalkieInHand(player);
        if (!(stack.getItem() instanceof WalkieTalkieItem) && !stack.hasNbt()) {
            return;
        }

        int index = packetByteBuf.readInt();

        switch (index) {
            case 0 -> {
                boolean activate = stack.getNbt().getBoolean(WalkieTalkieItem.NBT_KEY_ACTIVATE);
                stack.getNbt().putBoolean(WalkieTalkieItem.NBT_KEY_ACTIVATE, !activate);
            }
            case 2 -> {
                boolean mute = stack.getNbt().getBoolean(WalkieTalkieItem.NBT_KEY_MUTE);
                stack.getNbt().putBoolean(WalkieTalkieItem.NBT_KEY_MUTE, !mute);
            }
            case 4 -> {
                String channel = packetByteBuf.readString();
                WalkieTalkieItem.setChannel(stack, channel);
            }
        }

        PacketByteBuf packet = new PacketByteBuf(Unpooled.buffer());
        packet.writeItemStack(stack);
        NetworkManager.sendToPlayer(player, ModMessages.UPDATE_WALKIETALKIE_S2C, packet);
    }
}
