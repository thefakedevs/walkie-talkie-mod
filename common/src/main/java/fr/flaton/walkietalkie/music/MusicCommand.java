package fr.flaton.walkietalkie.music;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import fr.flaton.walkietalkie.Constants;
import fr.flaton.walkietalkie.channel.RadioChannel;
import fr.flaton.walkietalkie.config.ModConfig;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

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
}
