package fr.flaton.walkietalkie.channel;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.text.Text;

public final class TeamChannel extends RadioChannel {

    @Override
    public String getSerializedValue() {
        return TEAM_CHANNEL_ID;
    }

    @Override
    public String getNetworkKey(PlayerEntity player) {
        if (player.getScoreboardTeam() != null) {
            return "team:" + player.getScoreboardTeam().getName();
        }
        return "team:noteam";
    }

    @Override
    public Text getDisplayText() {
        return Text.translatable("gui.walkietalkie.mode.team");
    }
}

