package fr.flaton.walkietalkie.client.gui.hud;

import fr.flaton.walkietalkie.item.WalkieTalkieItem;
import fr.flaton.walkietalkie.channel.RadioChannel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.*;

public class WalkieTalkieHud {

    private record SignalState(UUID id, long expiry, int quality) {}

    private static final Map<String, List<SignalState>> receivingStates = new HashMap<>();
    private static final Map<String, Long> transmittingChannels = new HashMap<>();
    private static final long TIMEOUT_MS = 500;

    public static void setChannelActive(String channel, boolean transmitting, int quality, UUID id) {
        long expiry = System.currentTimeMillis() + TIMEOUT_MS;
        if (transmitting) {
            transmittingChannels.put(channel, expiry);
        } else {
            receivingStates.computeIfAbsent(channel, k -> new ArrayList<>()).removeIf(s -> s.id.equals(id));
            receivingStates.get(channel).add(new SignalState(id, expiry, quality));
        }
    }

    public static void render(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerEntity player = client.player;
        if (player == null) return;

        List<ItemStack> walkieTalkies = new ArrayList<>();

        // Scan inventory
        for (ItemStack stack : player.getInventory().main) {
            if (stack.getItem() instanceof WalkieTalkieItem) {
                if (stack.getOrCreateNbt().getBoolean(WalkieTalkieItem.NBT_KEY_ACTIVATE)) {
                    walkieTalkies.add(stack);
                }
            }
        }
        if (player.getOffHandStack().getItem() instanceof WalkieTalkieItem) {
             ItemStack stack = player.getOffHandStack();
             if (stack.getOrCreateNbt().getBoolean(WalkieTalkieItem.NBT_KEY_ACTIVATE) && !walkieTalkies.contains(stack)) {
                 walkieTalkies.add(stack);
             }
        }

        if (walkieTalkies.isEmpty()) return;

        int x = 10;
        int y = 10;

        for (ItemStack stack : walkieTalkies) {
            renderWalkieTalkieInfo(context, client, player, stack, x, y);
            y += 15;
        }
    }

    private static void renderWalkieTalkieInfo(DrawContext context, MinecraftClient client, PlayerEntity player, ItemStack stack, int x, int y) {
        RadioChannel radioChannel = WalkieTalkieItem.getRadioChannel(stack);
        Text displayText = radioChannel.getDisplayText();
        String networkKey = radioChannel.getNetworkKey(player);

        boolean isMuted = stack.getOrCreateNbt().getBoolean(WalkieTalkieItem.NBT_KEY_MUTE);
        boolean isHeld = (player.getMainHandStack() == stack || player.getOffHandStack() == stack);

        long now = System.currentTimeMillis();
        boolean isTransmitting = transmittingChannels.getOrDefault(networkKey, 0L) > now;

        List<SignalState> signals = receivingStates.get(networkKey);
        int maxQuality = 0;
        boolean isReceiving = false;
        List<String> speakers = new ArrayList<>();

        if (signals != null) {
            signals.removeIf(s -> s.expiry < now);
            if (!signals.isEmpty()) {
                isReceiving = true;
                for (SignalState s : signals) {
                    if (s.quality > maxQuality) maxQuality = s.quality;
                    if (client.getNetworkHandler() != null) {
                        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(s.id);
                        if (entry != null) {
                            speakers.add(entry.getProfile().getName());
                        }
                    }
                }
            }
        }

        int range = ((WalkieTalkieItem) stack.getItem()).getRange();

        // Determine Icon Indication
        // Status:
        // - Muted (Red)
        // - Transmitting (Red + Active) if Held
        // - Receiving (Green + Active)
        // - Listening (Gray) if !Muted

        int color = 0xFFCCCCCC; // Gray
        String statusSymbol = "Unknown";

        if (isMuted) {
            color = 0xFFFF5555; // Red
            statusSymbol = "M"; // Fallback
        } else {
             if (isTransmitting) {
                 color = 0xFFFF5555; // Red
                 statusSymbol = "TX " + range + "m"; // Transmission
             } else if (isReceiving) {
                 color = 0xFF55FF55; // Green
                 statusSymbol = "RX " + maxQuality + "%"; // Reception
             } else {
                 color = 0xFFAAAAAA; // Gray/Idle
                 statusSymbol = "L"; // Listening
             }
        }

        // Indication if held (Ready)
        if (isHeld && !isTransmitting) {
             // Show ready icon/text
             statusSymbol = "Rdy";
             if (!isMuted) color = 0xFFFFFF55; // Yellow?
        }

        // Draw
        // [Status] ChannelName

        // Draw status symbol
        context.drawText(client.textRenderer, statusSymbol, x, y + 1, color | 0xFF000000, true);

        // Draw text
        context.drawText(client.textRenderer, displayText, x + 48, y + 1, 0xFFFFFFFF, true);

        if (isReceiving && !speakers.isEmpty()) {
             StringBuilder speakerText = new StringBuilder();
             speakerText.append(" (");
             int limit = 3;
             for (int i = 0; i < Math.min(speakers.size(), limit); i++) {
                 if (i > 0) speakerText.append(", ");
                 speakerText.append(speakers.get(i));
             }
             if (speakers.size() > limit) {
                 speakerText.append(", ...");
             }
             speakerText.append(")");

             int offset = client.textRenderer.getWidth(displayText);
             context.drawText(client.textRenderer, speakerText.toString(), x + 48 + offset + 4, y + 1, 0xFFAAAAAA, true);
        }
    }
}
