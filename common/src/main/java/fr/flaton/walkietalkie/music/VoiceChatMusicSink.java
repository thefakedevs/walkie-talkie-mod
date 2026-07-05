package fr.flaton.walkietalkie.music;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import fr.flaton.walkietalkie.Constants;
import fr.flaton.walkietalkie.Util;
import fr.flaton.walkietalkie.WalkieTalkieVoiceChatPlugin;
import fr.flaton.walkietalkie.block.entity.SpeakerBlockEntity;
import fr.flaton.walkietalkie.config.ModConfig;
import fr.flaton.walkietalkie.item.WalkieTalkieItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VoiceChatMusicSink {
    private static final Logger LOGGER = Constants.LOGGER;

    private final MinecraftServer server;
    private final RegistryKey<World> originWorld;
    private final String frequencyKey;
    private final Map<UUID, StaticAudioChannel> channelsByReceiver = new ConcurrentHashMap<>();
    private volatile List<SpeakerBlockEntity> activeSpeakers = List.of();
    private int lastLoggedReceiverCount = -1;
    private int lastLoggedSpeakerCount = -1;

    public VoiceChatMusicSink(MinecraftServer server, RegistryKey<World> originWorld, String frequencyKey) {
        this.server = server;
        this.originWorld = originWorld;
        this.frequencyKey = frequencyKey;
    }

    public void refreshReceivers() {
        VoicechatServerApi api = WalkieTalkieVoiceChatPlugin.api;
        if (api == null) {
            clear();
            return;
        }

        Set<UUID> eligibleReceivers = new HashSet<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!isEligibleReceiver(player)) {
                continue;
            }
            VoicechatConnection connection = api.getConnectionOf(player.getUuid());
            if (connection == null) {
                continue;
            }
            eligibleReceivers.add(player.getUuid());
            channelsByReceiver.computeIfAbsent(player.getUuid(), uuid -> createChannel(api, player, connection));
        }

        World world = server.getWorld(originWorld);
        if (world == null) {
            activeSpeakers = List.of();
        } else {
            List<SpeakerBlockEntity> speakers = SpeakerBlockEntity.getSpeakersActivated(frequencyKey, world)
                    .stream()
                    .filter(speaker -> speaker.prepareMusicChannel(api))
                    .toList();
            activeSpeakers = speakers;
        }

        channelsByReceiver.entrySet().removeIf(entry -> {
            if (eligibleReceivers.contains(entry.getKey())) {
                return false;
            }
            StaticAudioChannel channel = entry.getValue();
            channel.flush();
            return true;
        });

        if (lastLoggedReceiverCount != channelsByReceiver.size()) {
            lastLoggedReceiverCount = channelsByReceiver.size();
            LOGGER.info("Music receivers refreshed: frequency={}, receivers={}", frequencyKey, lastLoggedReceiverCount);
        }
        if (lastLoggedSpeakerCount != activeSpeakers.size()) {
            lastLoggedSpeakerCount = activeSpeakers.size();
            LOGGER.info("Music speakers refreshed: frequency={}, speakers={}", frequencyKey, lastLoggedSpeakerCount);
        }
    }

    public void send(byte[] opusFrame) {
        for (StaticAudioChannel channel : channelsByReceiver.values()) {
            if (!channel.isClosed()) {
                channel.send(opusFrame);
            }
        }
        for (SpeakerBlockEntity speaker : activeSpeakers) {
            speaker.playMusicFrame(opusFrame);
        }
    }

    public void clear() {
        for (StaticAudioChannel channel : channelsByReceiver.values()) {
            channel.flush();
        }
        channelsByReceiver.clear();
        activeSpeakers = List.of();
    }

    private boolean isEligibleReceiver(ServerPlayerEntity player) {
        if (!ModConfig.crossDimensionsEnabled && !player.getWorld().getRegistryKey().equals(originWorld)) {
            return false;
        }
        ItemStack stack = Util.getWalkieTalkieActivated(player);
        if (stack == null) {
            return false;
        }
        return WalkieTalkieItem.getRadioChannel(stack).getNetworkKey(player).equals(frequencyKey);
    }

    private StaticAudioChannel createChannel(VoicechatServerApi api, ServerPlayerEntity player, VoicechatConnection connection) {
        UUID receiverUuid = player.getUuid();
        UUID channelId = UUID.nameUUIDFromBytes(("walkietalkie-music:" + frequencyKey + ":" + receiverUuid).getBytes(StandardCharsets.UTF_8));
        StaticAudioChannel channel = api.createStaticAudioChannel(channelId, api.fromServerLevel(player.getWorld()), connection);
        if (channel == null) {
            throw new IllegalStateException("Could not create static voice chat channel");
        }
        channel.setCategory(WalkieTalkieVoiceChatPlugin.SPEAKER_CATEGORY);
        LOGGER.info("Created static music voice channel: frequency={}, receiver={}, channelId={}", frequencyKey, player.getGameProfile().getName(), channelId);
        return channel;
    }
}
