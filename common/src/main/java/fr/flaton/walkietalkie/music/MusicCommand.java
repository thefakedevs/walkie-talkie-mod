package fr.flaton.walkietalkie.music;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import fr.flaton.walkietalkie.Constants;
import fr.flaton.walkietalkie.channel.RadioChannel;
import fr.flaton.walkietalkie.config.ModConfig;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;

public final class MusicCommand {

    private MusicCommand() {
    }

    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("playmusic")
                    .requires(source -> source.hasPermissionLevel(ModConfig.musicPermissionLevel))
                    .then(CommandManager.argument("frequency", StringArgumentType.string())
                            .then(CommandManager.argument("source", StringArgumentType.greedyString())
                                    .executes(context -> play(
                                            context.getSource(),
                                            StringArgumentType.getString(context, "frequency"),
                                            StringArgumentType.getString(context, "source")
                                    )))));

            dispatcher.register(CommandManager.literal("stopmusic")
                    .requires(source -> source.hasPermissionLevel(ModConfig.musicPermissionLevel))
                    .then(CommandManager.argument("frequency", StringArgumentType.string())
                            .executes(context -> stop(
                                    context.getSource(),
                                    StringArgumentType.getString(context, "frequency")
                            ))));

            dispatcher.register(CommandManager.literal("listmusic")
                    .requires(source -> source.hasPermissionLevel(ModConfig.musicPermissionLevel))
                    .executes(context -> list(context.getSource())));
        });
    }

    private static int play(ServerCommandSource source, String frequency, String identifier) {
        if (!ModConfig.musicEnabled) {
            source.sendError(Text.literal("Walkie-talkie music playback is disabled."));
            return 0;
        }

        String networkKey = resolveFrequencyKey(source, frequency);
        MusicSourceValidator.ValidationResult validation = MusicSourceValidator.validate(identifier);
        if (!validation.allowed()) {
            Constants.LOGGER.warn("Rejected music source for frequency {}: {}", networkKey, validation.message());
            source.sendError(Text.literal(validation.message()));
            return 0;
        }

        Constants.LOGGER.info("Music command accepted: frequency={}, sourceType={}, source={}", networkKey, validation.sourceType(), validation.identifier());
        source.sendFeedback(() -> Text.literal("Loading music for " + networkKey + "..."), false);

        try {
            Constants.LOGGER.info("Dispatching music load to manager: frequency={}", networkKey);
            MusicManager.getInstance().start(source.getServer(), source.getWorld().getRegistryKey(), networkKey, validation.identifier(), validation.sourceType(), result -> {
                source.getServer().execute(() -> {
                    if (result.success()) {
                        source.sendFeedback(() -> Text.literal("Started music on " + networkKey + ": " + result.trackTitle() + " (" + result.sourceType() + ")"), true);
                    } else {
                        source.sendError(Text.literal("Could not start music on " + networkKey + ": " + result.message()));
                    }
                });
            });
        } catch (Throwable t) {
            Constants.LOGGER.error("Failed to dispatch music load for frequency {}", networkKey, t);
            source.sendError(Text.literal("Could not start music on " + networkKey + ": " + t.getClass().getSimpleName() + ": " + t.getMessage()));
            return 0;
        }
        return 1;
    }

    private static int list(ServerCommandSource source) {
        List<MusicSessionInfo> sessions = MusicManager.getInstance().listSessions();
        if (sessions.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No music streams are currently playing."), false);
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Playing music streams: " + sessions.size()), false);
        for (MusicSessionInfo session : sessions) {
            source.sendFeedback(() -> Text.literal("- " + session.frequency()
                    + " | " + session.sourceType()
                    + " | " + session.trackTitle()
                    + " | " + formatPosition(session)
                    + " | " + session.source()), false);
        }
        return sessions.size();
    }

    private static int stop(ServerCommandSource source, String frequency) {
        String networkKey = resolveFrequencyKey(source, frequency);
        boolean stopped;
        try {
            stopped = MusicManager.getInstance().stop(networkKey);
        } catch (Throwable t) {
            Constants.LOGGER.error("Failed to stop music for frequency {}", networkKey, t);
            source.sendError(Text.literal("Could not stop music on " + networkKey + ": " + t.getClass().getSimpleName() + ": " + t.getMessage()));
            return 0;
        }
        if (stopped) {
            source.sendFeedback(() -> Text.literal("Stopped music on " + networkKey + "."), true);
            return 1;
        }
        source.sendFeedback(() -> Text.literal("No music is playing on " + networkKey + "."), false);
        return 0;
    }

    private static String resolveFrequencyKey(ServerCommandSource source, String frequency) {
        if (source.getEntity() instanceof net.minecraft.entity.player.PlayerEntity player) {
            return RadioChannel.from(frequency).getNetworkKey(player);
        }
        if (RadioChannel.TEAM_CHANNEL_ID.equals(frequency)) {
            return "team:noteam";
        }
        return RadioChannel.from(frequency).getNetworkKey(null);
    }

    private static String formatPosition(MusicSessionInfo session) {
        if (session.stream() || session.lengthMillis() <= 0L) {
            return formatDuration(session.positionMillis()) + " / live";
        }
        return formatDuration(session.positionMillis()) + " / " + formatDuration(session.lengthMillis());
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long seconds = totalSeconds % 60L;
        long minutes = (totalSeconds / 60L) % 60L;
        long hours = totalSeconds / 3600L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }
}
