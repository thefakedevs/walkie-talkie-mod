package fr.flaton.walkietalkie.channel;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

public sealed abstract class RadioChannel permits FrequencyChannel, TeamChannel {

    public static final String TEAM_CHANNEL_ID = "walkietalkie.channel.team";

    public static RadioChannel from(String value) {
        if (value == null) {
            return new FrequencyChannel("100.0");
        }
        if (TEAM_CHANNEL_ID.equals(value)) {
            return new TeamChannel();
        }
        return new FrequencyChannel(value);
    }

    public abstract String getSerializedValue();

    public abstract String getNetworkKey(PlayerEntity player);

    public abstract Text getDisplayText();
}

