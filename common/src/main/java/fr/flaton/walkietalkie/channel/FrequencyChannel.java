package fr.flaton.walkietalkie.channel;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

import java.util.Locale;

public final class FrequencyChannel extends RadioChannel {
    private final String frequency;
    private final int frequencyInt;

    public FrequencyChannel(String frequency) {
        this.frequency = frequency;
        int val = 1000;
        try {
            double freq = Double.parseDouble(frequency);
            val = (int) Math.round(freq * 10.0);
        } catch (NumberFormatException ignored) {
        }
        this.frequencyInt = val;
    }

    @Override
    public String getSerializedValue() {
        return frequency;
    }

    @Override
    public String getNetworkKey(PlayerEntity player) {
        // Normalize frequency to avoid "100.0" vs "100.00" issues if any
        return String.format(Locale.ROOT, "%.1f", frequencyInt / 10.0);
    }

    @Override
    public Text getDisplayText() {
        return Text.literal(String.format(Locale.ROOT, "%.1f", frequencyInt / 10.0) + " MHz");
    }
}

