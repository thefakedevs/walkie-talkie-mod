package fr.flaton.walkietalkie.client.gui.screen;

import dev.architectury.networking.NetworkManager;
import fr.flaton.walkietalkie.Constants;
import fr.flaton.walkietalkie.client.gui.widget.ToggleImageButton;
import fr.flaton.walkietalkie.item.WalkieTalkieItem;
import fr.flaton.walkietalkie.network.ModMessages;
import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
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
    private int pendingFrequency;
    private int originalFrequency;

    private static final Identifier BG_TEXTURE = new Identifier(Constants.MOD_ID, "textures/gui/gui_walkietalkie.png");
    private static final Identifier MUTE_TEXTURE = new Identifier("voicechat", "textures/icons/microphone_button.png");
    private static final Identifier ACTIVATE_TEXTURE = new Identifier(Constants.MOD_ID, "textures/icons/activate.png");

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

        activate = new ToggleImageButton(guiLeft + 6, guiTop + ySize - 26, ACTIVATE_TEXTURE, button -> {
            // Сохраняем частоту перед переключением activate
            if (pendingFrequency != originalFrequency) {
                sendUpdateFrequency(pendingFrequency);
                originalFrequency = pendingFrequency;
            }
            sendUpdateWalkieTalkie(0, false);
        }, stack.getNbt().getBoolean(WalkieTalkieItem.NBT_KEY_ACTIVATE));
        this.addDrawableChild(activate);

        mute = new ToggleImageButton(guiRight - 26, guiTop + ySize - 6 - 20, MUTE_TEXTURE, button -> {
            // Сохраняем частоту перед переключением mute
            if (pendingFrequency != originalFrequency) {
                sendUpdateFrequency(pendingFrequency);
                originalFrequency = pendingFrequency;
            }
            sendUpdateWalkieTalkie(2, false);
        }, stack.getNbt().getBoolean(WalkieTalkieItem.NBT_KEY_MUTE));
        this.addDrawableChild(mute);

        // Frequency text field
        originalFrequency = stack.getNbt().getInt(WalkieTalkieItem.NBT_KEY_CANAL);
        pendingFrequency = originalFrequency;
        
        frequencyField = new TextFieldWidget(this.textRenderer, this.width / 2 - 30, guiTop + 22, 60, 16, Text.literal(""));
        frequencyField.setMaxLength(6);
        frequencyField.setText(String.format("%.1f", originalFrequency / 10.0));
        frequencyField.setChangedListener(this::onFrequencyChanged);
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
        try {
            double frequency = Double.parseDouble(text.replace(',', '.'));
            if (frequency >= 10.0 && frequency <= 1000.0) {
                // Store pending frequency, don't send yet
                pendingFrequency = (int) Math.round(frequency * 10.0);
            }
        } catch (NumberFormatException e) {
            // Invalid input, ignore
        }
    }

    private void applyFrequency() {
        if (pendingFrequency != originalFrequency) {
            sendUpdateFrequency(pendingFrequency);
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

    private void sendUpdateFrequency(int frequency) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(3); // New index for frequency update
        buf.writeInt(frequency);
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
        int titleX = fieldX + fieldWidth + 6;
        int titleY = guiTop + 25;
        Text freqLabel = Text.translatable("gui.walkietalkie.frequency.label");
        drawCenteredText(context, this.textRenderer, freqLabel, titleX, titleY, 4210752);

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
            int freqInt = stack.getNbt().getInt(WalkieTalkieItem.NBT_KEY_CANAL);
            String newFreqText = String.format("%.1f", freqInt / 10.0);
            
            // Обновляем только если значение действительно изменилось
            if (!frequencyField.getText().equals(newFreqText)) {
                frequencyField.setText(newFreqText);
                originalFrequency = freqInt;
                pendingFrequency = freqInt;
            }
        }
    }

    public static WalkieTalkieScreen getInstance() {
        return instance;
    }

}
