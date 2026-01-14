package fr.flaton.walkietalkie.client.gui.screen;

import dev.architectury.networking.NetworkManager;
import fr.flaton.walkietalkie.Constants;
import fr.flaton.walkietalkie.client.gui.widget.ToggleImageButton;
import fr.flaton.walkietalkie.item.WalkieTalkieItem;
import fr.flaton.walkietalkie.network.ModMessages;
import fr.flaton.walkietalkie.channel.RadioChannel;
import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class WalkieTalkieScreen extends Screen {

    private static WalkieTalkieScreen instance;

    private final int xSize = 195;
    private final int ySize = 76;

    private int guiLeft;
    private int guiRight;
    private int guiTop;

    private final ItemStack stack;

    private ToggleImageButton mute;
    private ToggleImageButton activate;
    private TextFieldWidget frequencyField;
    private String pendingChannel;
    private String originalChannel;

    // Last manual channel entered by user. Default to 100.0 if not set.
    private String lastManualChannel = "100.0";

    private static final Identifier BG_TEXTURE = new Identifier(Constants.MOD_ID, "textures/gui/gui_walkietalkie.png");
    private static final Identifier MUTE_TEXTURE = new Identifier("voicechat", "textures/icons/microphone_button.png");
    private static final Identifier ACTIVATE_TEXTURE = new Identifier(Constants.MOD_ID, "textures/icons/activate.png");

    private ButtonWidget modeButton;

    public WalkieTalkieScreen(ItemStack stack) {
        super(Text.translatable("gui.walkietalkie.title"));
        instance = this;
        this.stack = stack;

        MinecraftClient.getInstance().setScreen(this);
    }

    @Override
    protected void init() {
        super.init();
        this.guiLeft = (this.width - xSize) / 2;
        this.guiRight = (this.width + xSize) / 2;
        this.guiTop = (this.height - ySize) / 2;

        originalChannel = WalkieTalkieItem.getChannel(stack);
        pendingChannel = originalChannel;

        // Initialize lastManualChannel if not in team mode
        if (!RadioChannel.TEAM_CHANNEL_ID.equals(originalChannel)) {
            lastManualChannel = originalChannel;
        }

        activate = new ToggleImageButton(guiLeft + 6, guiTop + ySize - 26, ACTIVATE_TEXTURE, button -> {
            // Сохраняем частоту перед переключением activate
            if (!pendingChannel.equals(originalChannel)) {
                sendUpdateChannel(pendingChannel);
                originalChannel = pendingChannel;
            }
            sendUpdateWalkieTalkie(0, false);
        }, stack.getNbt().getBoolean(WalkieTalkieItem.NBT_KEY_ACTIVATE));
        this.addDrawableChild(activate);

        mute = new ToggleImageButton(guiRight - 26, guiTop + ySize - 6 - 20, MUTE_TEXTURE, button -> {
            // Сохраняем частоту перед переключением mute
            if (!pendingChannel.equals(originalChannel)) {
                sendUpdateChannel(pendingChannel);
                originalChannel = pendingChannel;
            }
            sendUpdateWalkieTalkie(2, false);
        }, stack.getNbt().getBoolean(WalkieTalkieItem.NBT_KEY_MUTE));
        this.addDrawableChild(mute);

        // Mode button
        boolean isTeamMode = RadioChannel.TEAM_CHANNEL_ID.equals(pendingChannel);
        modeButton = ButtonWidget.builder(getModeText(isTeamMode), button -> {
            toggleMode();
        }).dimensions(this.guiLeft + 19, guiTop + 22, 40, 16).build();
        this.addDrawableChild(modeButton);

        // Frequency text field
        frequencyField = new TextFieldWidget(this.textRenderer, this.width / 2 - 20, guiTop + 22, 60, 16, Text.literal(""));
        frequencyField.setMaxLength(16);
        frequencyField.setText(isTeamMode ? "" : pendingChannel);
        frequencyField.setChangedListener(this::onFrequencyChanged);
        frequencyField.setVisible(!isTeamMode);

        this.addDrawableChild(frequencyField);

        // Confirm button
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> {
            applyFrequency();
            this.close();
        }).dimensions(this.width / 2 - 48, guiTop + ySize - 6 - 20, 45, 20).build());

        // Cancel button
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> {
            this.close();
        }).dimensions(this.width / 2 + 3, guiTop + ySize - 6 - 20, 45, 20).build());

    }

    private void onFrequencyChanged(String text) {
        pendingChannel = text;
    }

    private void applyFrequency() {
        if (!pendingChannel.equals(originalChannel)) {
            sendUpdateChannel(pendingChannel);
        }
    }

    @Override
    public void removed() {
        // Auto-save on close
        applyFrequency();
        super.removed();
    }

    private void sendUpdateWalkieTalkie(int index, boolean status) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(index);
        buf.writeBoolean(status);
        NetworkManager.sendToServer(ModMessages.UPDATE_WALKIETALKIE_C2S, buf);
    }

    private void sendUpdateChannel(String channel) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(4); // New index for channel update
        buf.writeString(channel);
        NetworkManager.sendToServer(ModMessages.UPDATE_WALKIETALKIE_C2S, buf);
    }

    @Override
    public void renderBackground(DrawContext context) {
        super.renderBackground(context);
        context.drawTexture(BG_TEXTURE, guiLeft, guiTop, 0, 0, xSize, ySize);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        drawCenteredText(context, this.textRenderer, this.title, this.width / 2, guiTop + 7, 4210752);

        int fieldX = this.width / 2 - 20;
        int fieldWidth = 60;
        int titleX = fieldX + fieldWidth + 12;
        int titleY = guiTop + 25;
        Text freqLabel = Text.translatable("gui.walkietalkie.frequency.label");
        // Only draw frequency label if field is visible
        if (frequencyField.isVisible()) {
             drawCenteredText(context, this.textRenderer, freqLabel, titleX, titleY, 4210752);
        } else {
             Text teamName = getTeamName();
             int color = 4210752;
             if (MinecraftClient.getInstance().player != null) {
                 AbstractTeam team = MinecraftClient.getInstance().player.getScoreboardTeam();
                 if (team != null && team.getColor().getColorValue() != null) {
                     color = team.getColor().getColorValue();
                 }
             }
             drawCenteredText(context, this.textRenderer, teamName, this.width / 2 + 10, titleY, color);
        }
    }

    private Text getTeamName() {
        if (MinecraftClient.getInstance().player != null) {
            AbstractTeam team = MinecraftClient.getInstance().player.getScoreboardTeam();
            if (team instanceof Team) {
                return ((Team) team).getDisplayName();
            }
        }
        return Text.translatable("gui.walkietalkie.team.none");
    }

    private void toggleMode() {
        boolean isTeamMode = RadioChannel.TEAM_CHANNEL_ID.equals(pendingChannel);
        if (isTeamMode) {
            // Switch to manual
            pendingChannel = lastManualChannel;
            frequencyField.setText(lastManualChannel);
            frequencyField.setVisible(true);
            modeButton.setMessage(getModeText(false));

            // If we are reverting to manual, we should probably update valid input immediately?
            // frequencyField.setText already triggers listener? No, TextFieldWidget usually doesn't trigger listener on setText unless explicitly called or modifying internal.
            // Let's ensure pendingChannel matches
        } else {
            // Switch to team
            // Save current manual input
            if (!pendingChannel.equals(RadioChannel.TEAM_CHANNEL_ID)) {
                lastManualChannel = pendingChannel;
            }
            pendingChannel = RadioChannel.TEAM_CHANNEL_ID;
            frequencyField.setVisible(false);
            modeButton.setMessage(getModeText(true));
        }
    }

    private Text getModeText(boolean isTeamMode) {
        return isTeamMode ? Text.translatable("gui.walkietalkie.mode.team") : Text.translatable("gui.walkietalkie.mode.manual");
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (frequencyField.isFocused()) {
            return frequencyField.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (frequencyField.isFocused()) {
            return frequencyField.charTyped(chr, modifiers);
        }
        return super.charTyped(chr, modifiers);
    }

    protected void drawCenteredText(DrawContext context, TextRenderer textRenderer, Text text, int centerX, int y, int color) {
        context.drawText(textRenderer, text, centerX - textRenderer.getWidth(text) / 2, y, color, false);
    }

    public void updateButtons(ItemStack stack) {
        mute.setState(stack.getNbt().getBoolean(WalkieTalkieItem.NBT_KEY_MUTE));
        activate.setState(stack.getNbt().getBoolean(WalkieTalkieItem.NBT_KEY_ACTIVATE));
        
        // НЕ обновляем частоту если поле в фокусе (пользователь редактирует)
        if (frequencyField != null && !frequencyField.isFocused()) {
            String channel = WalkieTalkieItem.getChannel(stack);

            // Обновляем только если значение действительно изменилось
            if (!frequencyField.getText().equals(channel)) {
                frequencyField.setText(channel);
                originalChannel = channel;
                pendingChannel = channel;
            }
        }
    }

    public static WalkieTalkieScreen getInstance() {
        return instance;
    }

}
