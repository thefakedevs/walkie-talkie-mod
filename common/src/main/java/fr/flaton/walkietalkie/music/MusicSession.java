package fr.flaton.walkietalkie.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import fr.flaton.walkietalkie.Constants;
import fr.flaton.walkietalkie.WalkieTalkieVoiceChatPlugin;
import fr.flaton.walkietalkie.config.ModConfig;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MusicSession {
    private static final Logger LOGGER = Constants.LOGGER;
    private static final int SAMPLES_PER_FRAME = 960;
    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(20L);

    private final MinecraftServer server;
    private final MusicManager manager;
    private final String frequencyKey;
    private final String source;
    private final String sourceType;
    private final AudioPlayer audioPlayer;
    private final AudioTrack track;
    private final VoiceChatMusicSink sink;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread workerThread;
    private OpusEncoder encoder;

    public MusicSession(MinecraftServer server, RegistryKey<World> originWorld, MusicManager manager, AudioPlayerManager playerManager, String frequencyKey, String source, String sourceType, AudioTrack track) {
        this.server = server;
        this.manager = manager;
        this.frequencyKey = frequencyKey;
        this.source = source;
        this.sourceType = sourceType;
        this.track = track;
        this.audioPlayer = playerManager.createPlayer();
        this.audioPlayer.setVolume(Math.max(0, Math.min(200, ModConfig.musicDefaultVolume)));
        this.sink = new VoiceChatMusicSink(server, originWorld, frequencyKey);
    }

    public void start() {
        VoicechatServerApi api = WalkieTalkieVoiceChatPlugin.api;
        if (api == null) {
            throw new IllegalStateException("Simple Voice Chat API is not available");
        }
        encoder = api.createEncoder();
        if (encoder == null) {
            throw new IllegalStateException("Could not create Simple Voice Chat Opus encoder");
        }
        if (!audioPlayer.startTrack(track, false)) {
            throw new IllegalStateException("Could not start Lavaplayer track");
        }
        running.set(true);
        workerThread = new Thread(this::runPlayback, "walkietalkie-music-" + frequencyKey);
        workerThread.setDaemon(true);
        workerThread.start();
        LOGGER.info("Music playback worker started: frequency={}, track='{}', thread={}", frequencyKey, track.getInfo().title, workerThread.getName());
    }

    public MusicSessionInfo getInfo() {
        AudioTrack playingTrack = audioPlayer.getPlayingTrack();
        long positionMillis = playingTrack == null ? track.getPosition() : playingTrack.getPosition();
        return new MusicSessionInfo(
                frequencyKey,
                source,
                sourceType,
                track.getInfo().title,
                track.getInfo().isStream,
                positionMillis,
                track.getInfo().length
        );
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            cleanup();
            return;
        }
        audioPlayer.stopTrack();
        if (workerThread != null) {
            workerThread.interrupt();
        }
        cleanup();
    }

    private void runPlayback() {
        long nextFrameTime = System.nanoTime();
        long lastReceiverRefresh = 0L;
        long refreshIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, ModConfig.musicRefreshReceiversIntervalTicks) * 50L);
        boolean loggedFirstFrame = false;
        long framesSent = 0L;

        try {
            while (running.get()) {
                VoicechatServerApi api = WalkieTalkieVoiceChatPlugin.api;
                if (api == null || encoder == null || encoder.isClosed()) {
                    LOGGER.warn("Stopping music playback for frequency {} because Simple Voice Chat API or Opus encoder is unavailable", frequencyKey);
                    break;
                }

                long now = System.nanoTime();
                if (now - lastReceiverRefresh >= refreshIntervalNanos) {
                    lastReceiverRefresh = now;
                    server.execute(sink::refreshReceivers);
                }

                AudioFrame frame;
                try {
                    frame = audioPlayer.provide(100L, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    continue;
                }

                if (frame == null) {
                    if (audioPlayer.getPlayingTrack() == null) {
                        break;
                    }
                    continue;
                }

                short[] monoPcm = decodeLavaplayerPcm(frame.getData());
                byte[] opus = encoder.encode(monoPcm);
                if (!loggedFirstFrame) {
                    loggedFirstFrame = true;
                    LOGGER.info("Music playback received first Lavaplayer audio frame: frequency={}, frameBytes={}, opusBytes={}", frequencyKey, frame.getData().length, opus.length);
                }
                sink.send(opus);
                framesSent++;

                nextFrameTime += FRAME_NANOS;
                long sleepNanos = nextFrameTime - System.nanoTime();
                if (sleepNanos > 0L) {
                    TimeUnit.NANOSECONDS.sleep(sleepNanos);
                } else if (sleepNanos < -TimeUnit.MILLISECONDS.toNanos(200L)) {
                    nextFrameTime = System.nanoTime();
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Music playback failed for frequency {}", frequencyKey, e);
        } finally {
            running.set(false);
            cleanup();
            manager.removeSession(frequencyKey, this);
            LOGGER.info("Music playback worker stopped: frequency={}, track='{}', framesSent={}", frequencyKey, track.getInfo().title, framesSent);
        }
    }

    private short[] decodeLavaplayerPcm(byte[] data) {
        short[] mono = new short[SAMPLES_PER_FRAME];
        int stereoSamples = Math.min(SAMPLES_PER_FRAME, data.length / 4);
        float volume = Math.max(0, Math.min(200, ModConfig.musicDefaultVolume)) / 100F;
        for (int sample = 0; sample < stereoSamples; sample++) {
            int offset = sample * 4;
            short left = readBigEndianShort(data, offset);
            short right = readBigEndianShort(data, offset + 2);
            int mixed = Math.round(((left + right) / 2F) * volume);
            if (mixed > Short.MAX_VALUE) {
                mixed = Short.MAX_VALUE;
            } else if (mixed < Short.MIN_VALUE) {
                mixed = Short.MIN_VALUE;
            }
            mono[sample] = (short) mixed;
        }
        return mono;
    }

    private short readBigEndianShort(byte[] data, int offset) {
        return (short) ((data[offset] << 8) | (data[offset + 1] & 0xFF));
    }

    private void cleanup() {
        sink.clear();
        audioPlayer.destroy();
        if (encoder != null && !encoder.isClosed()) {
            try {
                encoder.close();
            } catch (Exception ignored) {
            }
        }
    }
}
