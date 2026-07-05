package fr.flaton.walkietalkie;

import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import fr.flaton.walkietalkie.audio.AudioProcessor;
import fr.flaton.walkietalkie.audio.MilitaryRadioEffect;
import fr.flaton.walkietalkie.block.entity.SpeakerBlockEntity;
import fr.flaton.walkietalkie.config.ModConfig;
import fr.flaton.walkietalkie.item.WalkieTalkieItem;
import fr.flaton.walkietalkie.music.MusicLifecycle;
import fr.flaton.walkietalkie.network.ModMessages;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import dev.architectury.networking.NetworkManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ForgeVoicechatPlugin
public class WalkieTalkieVoiceChatPlugin implements VoicechatPlugin {

    public final static String SPEAKER_CATEGORY = "speakers";

    @Nullable
    public static VoicechatServerApi api;

    // Audio processors per channel (UUID = channel ID)
    private static final Map<UUID, AudioProcessor> audioProcessors = new ConcurrentHashMap<>();

    // Per sender->receiver stream channels and effects
    private static final Map<String, EntityAudioChannel> radioChannels = new ConcurrentHashMap<>();
    private static final Map<String, MilitaryRadioEffect> radioEffects = new ConcurrentHashMap<>();
    private static final Map<String, OpusEncoder> radioEncoders = new ConcurrentHashMap<>();

    private static final Map<String, Long> packetThrottleMap = new ConcurrentHashMap<>();

    @Override
    public String getPluginId() {
        return Constants.MOD_ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicPacket);
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        api = event.getVoicechat();

        VolumeCategory speakers = api.volumeCategoryBuilder()
                .setId(SPEAKER_CATEGORY)
                .setName("Speakers")
                .setDescription("The volume of all speakers")
                .setIcon(getIcon("assets/walkietalkie/textures/block/speaker.png"))
                .build();
        api.registerVolumeCategory(speakers);
    }

    private void onServerStopped(VoicechatServerStoppedEvent event) {
        MusicLifecycle.stopAllSafely();
        api = null;
    }

    private int[][] getIcon(String path) {
        try {
            Enumeration<URL> resources = WalkieTalkieVoiceChatPlugin.class.getClassLoader().getResources(path);
            while (resources.hasMoreElements()) {
                BufferedImage bufferedImage = ImageIO.read(resources.nextElement().openStream());
                if (bufferedImage.getWidth() != 16) {
                    continue;
                }
                if (bufferedImage.getHeight() != 16) {
                    continue;
                }
                int[][] image = new int[16][16];
                for (int x = 0; x < bufferedImage.getWidth(); x++) {
                    for (int y = 0; y < bufferedImage.getHeight(); y++) {
                        image[x][y] = bufferedImage.getRGB(x, y);
                    }
                }
                return image;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String pairKey(UUID sender, UUID receiver, String canal) {
        return sender + ":" + receiver + ":" + canal;
    }

    private void onMicPacket(MicrophonePacketEvent event) {
        VoicechatConnection senderConnection = event.getSenderConnection();

        if (senderConnection == null) {
            return;
        }

        if (!(senderConnection.getPlayer().getPlayer() instanceof PlayerEntity senderPlayer)) {
            return;
        }

        ItemStack senderItemStack = Util.getWalkieTalkieInHand(senderPlayer);

        if (senderItemStack == null) {
            return;
        }

        if (!isWalkieTalkieActivate(senderItemStack)) {
            return;
        }

        if (isWalkieTalkieMute(senderItemStack)) {
            return;
        }

        String senderChannel = getChannel(senderItemStack, senderPlayer);
        int senderRange = getRange(senderItemStack);

        // Notify sender of transmission
        if (shouldSendNotification(senderPlayer.getUuid(), senderChannel, true, senderPlayer.getUuid())) {
            sendChannelActivity(senderPlayer, senderChannel, true, 100, senderPlayer.getUuid());
        }

        if (api != null) {
            SpeakerBlockEntity.getSpeakersActivatedInRange(senderChannel, senderPlayer.getWorld(), senderPlayer.getPos(), senderRange)
                    .forEach(speakerBlockEntity -> speakerBlockEntity.playSound(api, event));
        }

        if (!ModConfig.lowQualityAudio || api == null) {
            return; // даём штатной пересылке идти
        }

        // Декодер один на пакет — декодирование не требует непрерывного состояния
        OpusDecoder decoder = api.createDecoder();
        if (decoder == null) {
            return;
        }

        byte[] opusData = event.getPacket().getOpusEncodedData();
        short[] basePcm;
        try {
            basePcm = decoder.decode(opusData);
        } catch (Exception e) {
            basePcm = null;
        }
        try { decoder.close(); } catch (Exception ignored) {}

        if (basePcm == null || basePcm.length == 0) {
            return;
        }

        final int FRAME_SIZE = 960; // 20ms @ 48kHz, mono
        final boolean canSplit = (basePcm.length % FRAME_SIZE) == 0;

        for (PlayerEntity receiverPlayer : Objects.requireNonNull(senderPlayer.getServer()).getPlayerManager().getPlayerList()) {
            if (receiverPlayer.getUuid().equals(senderPlayer.getUuid())) {
                continue;
            }
            if (!ModConfig.crossDimensionsEnabled && !receiverPlayer.getWorld().getDimension().equals(senderPlayer.getWorld().getDimension())) {
                continue;
            }
            ItemStack receiverStack = Util.getWalkieTalkieActivated(receiverPlayer);
            if (receiverStack == null) {
                continue;
            }
            String receiverChannel = getChannel(receiverStack, receiverPlayer);
            if (!receiverChannel.equals(senderChannel)) {
                continue;
            }

            if (!canBroadcastToReceiver(senderPlayer, receiverPlayer, senderRange)) {
                continue;
            }

            float p2pDistance = (float) Math.sqrt(senderPlayer.squaredDistanceTo(receiverPlayer));
            float distanceFactor = senderRange > 0 ? (p2pDistance / (float) senderRange) : 1f;
            if (distanceFactor < 0f) distanceFactor = 0f;
            if (distanceFactor > 1f) distanceFactor = 1f;

            // Notify receiver of reception
            if (shouldSendNotification(receiverPlayer.getUuid(), receiverChannel, false, senderPlayer.getUuid())) {
                int quality = (int) ((1.0 - distanceFactor) * 100);
                sendChannelActivity(receiverPlayer, receiverChannel, false, quality, senderPlayer.getUuid());
            }

            // Канал per sender->receiver
            String key = pairKey(senderPlayer.getUuid(), receiverPlayer.getUuid(), senderChannel);
            EntityAudioChannel channel = radioChannels.get(key);
            Entity senderEntity = api.fromEntity(senderPlayer);
            if (senderEntity == null) {
                continue;
            }
            if (channel == null) {
                UUID channelId = UUID.nameUUIDFromBytes(("radio:" + key).getBytes(StandardCharsets.UTF_8));
                channel = api.createEntityAudioChannel(channelId, senderEntity);
                if (channel == null) {
                    continue;
                }
                channel.setCategory(SPEAKER_CATEGORY);
                channel.setDistance(1_000_000F);
                final UUID onlyReceiver = receiverPlayer.getUuid();
                channel.setFilter(serverPlayer -> serverPlayer.getUuid().equals(onlyReceiver));
                radioChannels.put(key, channel);
            } else {
                channel.updateEntity(senderEntity);
            }

            // Эффект per pair для непрерывности между кадрами
            MilitaryRadioEffect effect = radioEffects.computeIfAbsent(key, k -> new MilitaryRadioEffect());

            // Энкодер per pair (сохраняем состояние между кадрами)
            OpusEncoder encoder = radioEncoders.get(key);
            if (encoder == null) {
                encoder = api.createEncoder();
                if (encoder == null) {
                    continue;
                }
                radioEncoders.put(key, encoder);
            }

            if (!canSplit) {
                // Если пакет не кратен 20мс — безопаснее отправить как есть, чтобы не нарушать тайминг
                channel.send(opusData);
            } else {
                for (int off = 0; off < basePcm.length; off += FRAME_SIZE) {
                    short[] frame = Arrays.copyOfRange(basePcm, off, off + FRAME_SIZE);
                    short[] processedPcm = effect.process(frame, distanceFactor);
                    byte[] encoded;
                    try {
                        encoded = encoder.encode(processedPcm);
                    } catch (Exception e) {
                        encoded = opusData; // fallback
                    }
                    channel.send(encoded);
                }
            }

            // Очистка по окончанию передачи для пары
            if (!effect.isTransmitting()) {
                radioEffects.remove(key);
                OpusEncoder enc = radioEncoders.remove(key);
                if (enc != null) {
                    try { enc.close(); } catch (Exception ignored) {}
                }
                // Канал можно оставить в кэше или убрать по таймеру
            }
        }
    }



    private boolean shouldSendNotification(UUID targetPlayer, String channel, boolean transmitting, UUID sourcePlayer) {
        String key = targetPlayer.toString() + ":" + channel + ":" + transmitting + ":" + sourcePlayer;
        long now = System.currentTimeMillis();
        long last = packetThrottleMap.getOrDefault(key, 0L);
        if (now - last > 200) {
            packetThrottleMap.put(key, now);
            return true;
        }
        return false;
    }

    private void sendChannelActivity(PlayerEntity player, String channel, boolean transmitting, int quality, UUID source) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            buf.writeString(channel);
            buf.writeBoolean(transmitting);
            buf.writeInt(quality);
            buf.writeUuid(source);
            NetworkManager.sendToPlayer(serverPlayer, ModMessages.CHANNEL_ACTIVITY_S2C, buf);
        }
    }

    private String getChannel(ItemStack stack, PlayerEntity player) {
        return WalkieTalkieItem.getRadioChannel(stack).getNetworkKey(player);
    }

    private int getRange(ItemStack stack) {
        WalkieTalkieItem item = (WalkieTalkieItem) Objects.requireNonNull(stack.getItem());
        return item.getRange();
    }

    private boolean isWalkieTalkieActivate(ItemStack stack) {
        return Objects.requireNonNull(stack.getNbt()).getBoolean(WalkieTalkieItem.NBT_KEY_ACTIVATE);
    }

    private boolean isWalkieTalkieMute(ItemStack stack) {
        return Objects.requireNonNull(stack.getNbt()).getBoolean(WalkieTalkieItem.NBT_KEY_MUTE);
    }

    private boolean canBroadcastToReceiver(PlayerEntity senderPlayer, PlayerEntity receiverPlayer, int receiverRange) {
        World senderWorld = senderPlayer.getWorld();
        World receiverWorld = receiverPlayer.getWorld();

        return Util.canBroadcastToReceiver(senderWorld, receiverWorld, senderPlayer.getPos(), receiverPlayer.getPos(), receiverRange);
    }
    
    /**
     * Apply low quality radio effect for speaker audio
     * @param channelId The channel ID (for processor management)
     * @param opusData Original opus-encoded audio data
     * @return Processed opus-encoded audio data
     */
    public static byte[] applyLowQualityEffectForSpeaker(UUID channelId, byte[] opusData) {
        if (api == null || !ModConfig.lowQualityAudio) {
            return opusData;
        }
        
        try {
            // Get or create audio processor for this channel
            AudioProcessor processor = audioProcessors.computeIfAbsent(channelId, k -> new AudioProcessor());
            
            // Get opus encoder/decoder from API
            OpusEncoder encoder = api.createEncoder();
            OpusDecoder decoder = api.createDecoder();
            
            if (encoder == null || decoder == null) {
                return opusData;
            }
            
            // Process audio with distance = 0 (no distance effect for speakers)
            byte[] processed = processor.processOpusAudio(opusData, decoder, encoder, 0f);
            
            // Clean up
            encoder.close();
            decoder.close();
            
            return processed;
            
        } catch (Exception e) {
            e.printStackTrace();
            return opusData;
        }
    }
    
    /**
     * Clean up audio processor for a channel
     */
    public static void cleanupProcessor(UUID channelId) {
        AudioProcessor processor = audioProcessors.remove(channelId);
        if (processor != null) {
            processor.reset();
        }
    }
}

