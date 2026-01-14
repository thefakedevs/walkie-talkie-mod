package fr.flaton.walkietalkie.item;

import fr.flaton.walkietalkie.client.gui.screen.WalkieTalkieScreen;
import fr.flaton.walkietalkie.channel.RadioChannel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

public class WalkieTalkieItem extends Item {

    public int getRange() {
        return RANGE;
    }

    private final int RANGE;

    public static final String NBT_KEY_CHANNEL = "walkietalkie.channel";
    public static final String NBT_KEY_MUTE = "walkietalkie.mute";
    public static final String NBT_KEY_ACTIVATE = "walkietalkie.activate";


    public WalkieTalkieItem(Settings settings, int range) {
        super(settings);
        RANGE = range;
    }


    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {

        if (world.isClient()) {
            // Only open GUI when in main hand
            if (hand == Hand.MAIN_HAND && player.getStackInHand(hand).hasNbt()) {

                ItemStack stack = player.getStackInHand(hand);

                new WalkieTalkieScreen(stack);
                return TypedActionResult.success(stack);
            }
        }

        return super.use(world, player, hand);
    }


    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        if (world.isClient()) {
            return;
        }

        if (!stack.hasNbt()) {
            NbtCompound nbtCompound = new NbtCompound();
            nbtCompound.putBoolean(WalkieTalkieItem.NBT_KEY_ACTIVATE, false);
            nbtCompound.putBoolean(WalkieTalkieItem.NBT_KEY_MUTE, false);
            nbtCompound.putString(WalkieTalkieItem.NBT_KEY_CHANNEL, RadioChannel.TEAM_CHANNEL_ID);
            stack.setNbt(nbtCompound);
        }

    }

    public static RadioChannel getRadioChannel(ItemStack stack) {
        return RadioChannel.from(getChannel(stack));
    }

    public static String getChannel(ItemStack stack) {
        if (stack.hasNbt()) {
            NbtCompound nbt = stack.getNbt();
            if (nbt.contains(NBT_KEY_CHANNEL)) {
                return nbt.getString(NBT_KEY_CHANNEL);
            } else if (nbt.contains("walkietalkie.canal")) {
                int canal = nbt.getInt("walkietalkie.canal");
                return String.format(java.util.Locale.ROOT, "%.1f", canal / 10.0);
            }
        }
        return RadioChannel.TEAM_CHANNEL_ID;
    }

    public static void setChannel(ItemStack stack, String channel) {
        if (!stack.hasNbt()) {
            stack.setNbt(new NbtCompound());
        }
        NbtCompound nbt = stack.getNbt();
        nbt.putString(NBT_KEY_CHANNEL, channel);
    }
}
