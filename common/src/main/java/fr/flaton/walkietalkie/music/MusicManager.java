package fr.flaton.walkietalkie.music;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.clients.Android;
import dev.lavalink.youtube.clients.AndroidMusic;
import dev.lavalink.youtube.clients.Ios;
import dev.lavalink.youtube.clients.MWeb;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.TvHtml5Simply;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
import fr.flaton.walkietalkie.Constants;
import fr.flaton.walkietalkie.config.ModConfig;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class MusicManager {
    private static final Logger LOGGER = Constants.LOGGER;
    private static final MusicManager INSTANCE = new MusicManager();

    private final AudioPlayerManager playerManager;
    private final Map<String, MusicSession> sessions = new ConcurrentHashMap<>();
    private boolean youtubeSourceRegistered;
    private final ScheduledExecutorService watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "walkietalkie-music-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private MusicManager() {
        LOGGER.info("Initializing embedded Lavaplayer music manager");
        MusicNativeLibrarySupport.prepareLavaplayerNatives();
        playerManager = new DefaultAudioPlayerManager();
        playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_PCM_S16_BE);
        playerManager.setFrameBufferDuration(Math.max(200, ModConfig.musicFrameBufferDurationMillis));
        playerManager.setTrackStuckThreshold(Math.max(10, ModConfig.musicTrackStuckThresholdSeconds) * 1000L);
        playerManager.setHttpRequestConfigurator(config -> org.apache.http.client.config.RequestConfig.copy(config)
                .setConnectTimeout(10_000)
                .setConnectionRequestTimeout(10_000)
                .setSocketTimeout(30_000)
                .build());
        LOGGER.info("Lavaplayer streaming configuration: frameBufferMs={}, stuckThresholdSeconds={}, loadTimeoutSeconds={}",
                Math.max(200, ModConfig.musicFrameBufferDurationMillis),
                Math.max(10, ModConfig.musicTrackStuckThresholdSeconds),
                Math.max(10, ModConfig.musicLoadTimeoutSeconds));
        registerYoutubeSourceManager(null);
        AudioSourceManagers.registerRemoteSources(playerManager, com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class);
        LOGGER.info("Lavaplayer source manager order: {}", playerManager.getSourceManagers().stream()
                .map(AudioSourceManager::getSourceName)
                .toList());
        if (ModConfig.musicAllowLocalFiles) {
            LOGGER.warn("Local file music sources are enabled by config");
            AudioSourceManagers.registerLocalSource(playerManager);
        }
    }

    public static MusicManager getInstance() {
        return INSTANCE;
    }

    public void start(MinecraftServer server, RegistryKey<World> originWorld, String frequencyKey, String identifier, String sourceType, Consumer<MusicStartResult> callback) {
        if ("youtube".equals(sourceType) && !ensureYoutubeSourceManager(callback)) {
            return;
        }

        if (sessions.size() >= ModConfig.musicMaxConcurrentFrequencies && !sessions.containsKey(frequencyKey)) {
            LOGGER.warn("Rejected music start for frequency {} because {} sessions are already active", frequencyKey, sessions.size());
            callback.accept(MusicStartResult.failure("too many music frequencies are already active"));
            return;
        }

        LOGGER.info("Submitting music source to Lavaplayer: frequency={}, sourceType={}, identifier={}", frequencyKey, sourceType, identifier);
        AtomicBoolean completed = new AtomicBoolean(false);
        Future<Void> loadFuture = playerManager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                if (!completed.compareAndSet(false, true)) {
                    return;
                }
                LOGGER.info("Lavaplayer loaded track for frequency {}: title='{}', author='{}', lengthMs={}, stream={}",
                        frequencyKey, track.getInfo().title, track.getInfo().author, track.getInfo().length, track.getInfo().isStream);
                startLoadedTrack(server, originWorld, frequencyKey, identifier, track, sourceType, callback);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (!completed.compareAndSet(false, true)) {
                    return;
                }
                AudioTrack selectedTrack = playlist.getSelectedTrack();
                if (selectedTrack == null && !playlist.getTracks().isEmpty()) {
                    selectedTrack = playlist.getTracks().get(0);
                }
                if (selectedTrack == null) {
                    LOGGER.warn("Lavaplayer loaded an empty playlist for frequency {}", frequencyKey);
                    callback.accept(MusicStartResult.failure("playlist did not contain playable tracks"));
                    return;
                }
                LOGGER.info("Lavaplayer loaded playlist for frequency {}: name='{}', tracks={}, selected='{}'",
                        frequencyKey, playlist.getName(), playlist.getTracks().size(), selectedTrack.getInfo().title);
                startLoadedTrack(server, originWorld, frequencyKey, identifier, selectedTrack, sourceType, callback);
            }

            @Override
            public void noMatches() {
                if (!completed.compareAndSet(false, true)) {
                    return;
                }
                LOGGER.warn("Lavaplayer found no matches for frequency {} and source {}", frequencyKey, identifier);
                callback.accept(MusicStartResult.failure("no matching track was found"));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                if (!completed.compareAndSet(false, true)) {
                    return;
                }
                LOGGER.warn("Failed to load music source {} for frequency {}", identifier, frequencyKey, exception);
                callback.accept(MusicStartResult.failure(describeLoadFailure(exception)));
            }
        });
        LOGGER.info("Lavaplayer load task submitted: frequency={}, done={}, cancelled={}", frequencyKey, loadFuture.isDone(), loadFuture.isCancelled());
        long loadTimeoutSeconds = Math.max(10, ModConfig.musicLoadTimeoutSeconds);
        watchdogExecutor.schedule(() -> {
            if (completed.compareAndSet(false, true)) {
                boolean cancelled = loadFuture.cancel(true);
                LOGGER.warn("Timed out while loading music source: frequency={}, timeoutSeconds={}, cancelled={}", frequencyKey, loadTimeoutSeconds, cancelled);
                callback.accept(MusicStartResult.failure("timed out while loading music source after " + loadTimeoutSeconds + " seconds"));
            }
        }, loadTimeoutSeconds, TimeUnit.SECONDS);
    }

    private synchronized boolean ensureYoutubeSourceManager(Consumer<MusicStartResult> callback) {
        return registerYoutubeSourceManager(callback);
    }

    private synchronized boolean registerYoutubeSourceManager(Consumer<MusicStartResult> callback) {
        if (!ModConfig.musicAllowYoutube) {
            if (callback != null) {
                callback.accept(MusicStartResult.failure("YouTube music sources are disabled"));
            }
            return false;
        }
        if (youtubeSourceRegistered) {
            return true;
        }
        try {
            LOGGER.info("Registering youtube-source manager for embedded Lavaplayer");
            dev.lavalink.youtube.YoutubeAudioSourceManager sourceManager = new dev.lavalink.youtube.YoutubeAudioSourceManager(
                    new Android(),
                    new Ios(),
                    new TvHtml5Simply(),
                    new Web(),
                    new MWeb(),
                    new WebEmbedded(),
                    new AndroidMusic(),
                    new Music()
            );
            if (!ModConfig.musicYoutubeOauthRefreshToken.isBlank()) {
                LOGGER.info("Configuring youtube-source OAuth2 refresh token; skipInitialization={}", ModConfig.musicYoutubeOauthSkipInitialization);
                sourceManager.useOauth2(ModConfig.musicYoutubeOauthRefreshToken, ModConfig.musicYoutubeOauthSkipInitialization);
            }
            playerManager.registerSourceManager(sourceManager);
            youtubeSourceRegistered = true;
            return true;
        } catch (Throwable t) {
            LOGGER.error("Could not register youtube-source manager", t);
            if (callback != null) {
                callback.accept(MusicStartResult.failure("YouTube source is unavailable: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
            }
            return false;
        }
    }

    private String describeLoadFailure(FriendlyException exception) {
        String message = exception.getMessage();
        Throwable cause = exception.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            message = cause.getMessage();
        }
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }

        String normalized = message.replace("\r", "\n");
        if (normalized.contains("requires login")) {
            return "YouTube requires login for this video/stream. Try another source or configure music-youtube-oauth-refresh-token.";
        }
        if (normalized.contains("All clients failed")) {
            return "YouTube source could not load this video with the available clients. Try another source or configure YouTube OAuth.";
        }
        if (normalized.contains("Video player configuration error")) {
            return "YouTube player configuration failed for this video. Try another source or configure YouTube OAuth.";
        }

        int newline = normalized.indexOf('\n');
        String firstLine = newline >= 0 ? normalized.substring(0, newline) : normalized;
        if (firstLine.length() > 180) {
            return firstLine.substring(0, 177) + "...";
        }
        return firstLine;
    }

    public boolean stop(String frequencyKey) {
        MusicSession session = sessions.remove(frequencyKey);
        if (session == null) {
            LOGGER.info("Stop requested for frequency {}, but no music session is active", frequencyKey);
            return false;
        }
        LOGGER.info("Stopping music session for frequency {}", frequencyKey);
        session.stop();
        return true;
    }

    public void stopAll() {
        LOGGER.info("Stopping all music sessions; activeSessions={}", sessions.size());
        sessions.values().forEach(MusicSession::stop);
        sessions.clear();
    }

    public List<MusicSessionInfo> listSessions() {
        return sessions.values().stream()
                .map(MusicSession::getInfo)
                .sorted(Comparator.comparing(MusicSessionInfo::frequency))
                .toList();
    }

    void removeSession(String frequencyKey, MusicSession session) {
        sessions.remove(frequencyKey, session);
    }

    private void startLoadedTrack(MinecraftServer server, RegistryKey<World> originWorld, String frequencyKey, String source, AudioTrack track, String sourceType, Consumer<MusicStartResult> callback) {
        long maxDurationMillis = ModConfig.musicMaxTrackDurationSeconds <= 0 ? 0L : ModConfig.musicMaxTrackDurationSeconds * 1000L;
        if (track.getInfo().isStream) {
            LOGGER.info("Starting stream music source for frequency {}; duration limit is not applied to livestreams/streams", frequencyKey);
        } else if (maxDurationMillis > 0L && track.getInfo().length > maxDurationMillis) {
            LOGGER.warn("Rejected track '{}' for frequency {} because lengthMs={} exceeds maxDurationMs={}",
                    track.getInfo().title, frequencyKey, track.getInfo().length, maxDurationMillis);
            callback.accept(MusicStartResult.failure("track is longer than the configured maximum duration"));
            return;
        }

        stop(frequencyKey);
        MusicSession session = new MusicSession(server, originWorld, this, playerManager, frequencyKey, source, sourceType, track);
        sessions.put(frequencyKey, session);
        try {
            LOGGER.info("Starting music session for frequency {} with track '{}'", frequencyKey, track.getInfo().title);
            session.start();
            callback.accept(MusicStartResult.success(track.getInfo().title, sourceType));
        } catch (Exception e) {
            sessions.remove(frequencyKey, session);
            session.stop();
            LOGGER.warn("Failed to start music session for frequency {}", frequencyKey, e);
            callback.accept(MusicStartResult.failure(e.getMessage()));
        }
    }
}
