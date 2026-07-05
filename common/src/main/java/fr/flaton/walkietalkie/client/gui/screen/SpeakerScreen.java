package fr.flaton.walkietalkie.client.gui.screen;

import dev.architectury.networking.NetworkManager;
import fr.flaton.walkietalkie.Constants;
import fr.flaton.walkietalkie.client.gui.widget.ToggleImageButton;
import fr.flaton.walkietalkie.network.ModMessages;
import fr.flaton.walkietalkie.screen.SpeakerScreenHandler;
import io.netty.buffer.Unpooled;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Locale;

public class SpeakerScreen extends HandledScreen<SpeakerScreenHandler> {
    private static final Identifier TEXTURE = new Identifier(Constants.MOD_ID, "textures/gui/gui_walkietalkie.png");
    private static final Identifier ACTIVATE_TEXTURE = new Identifier(Constants.MOD_ID, "textures/icons/activate.png");

    private final int xSize = 195;
    private final int ySize = 76;

    private int guiLeft;
    private int guiTop;

    private ToggleImageButton activateButton;
    private TextFieldWidget frequencyField;
    private int pendingFrequency;
    private int originalFrequency;

    public SpeakerScreen(SpeakerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        drawCenteredText(context, this.textRenderer, title.getString(), this.width / 2, guiTop + 7, 4210752);

        updateActivateState();
        updateFrequencyField();
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

    protected void drawCenteredText(DrawContext context, TextRenderer textRenderer, String text, int centerX, int y, int color) {
        context.drawText(textRenderer, text, centerX - textRenderer.getWidth(text) / 2, y, color, false);
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        context.drawTexture(TEXTURE, guiLeft, guiTop, 0, 0, xSize, ySize);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
    }

    private void updateActivateState() {
        activateButton.setState(handler.isActivate());
    }

    @Override
    protected void init() {
        super.init();

        this.guiLeft = (this.width - xSize) / 2;
        this.guiTop = (this.height - ySize) / 2;

        activateButton = new ToggleImageButton(guiLeft + 6, guiTop + ySize - 6 - 20, ACTIVATE_TEXTURE, button -> {
            // Сохраняем частоту перед переключением activate
            if (pendingFrequency != originalFrequency) {
                sendUpdateFrequency(pendingFrequency);
                originalFrequency = pendingFrequency;
            }
            sendUpdateSpeaker(0, false);
        }, handler.isActivate());
        this.addDrawableChild(activateButton);

        // Frequency text field
        originalFrequency = handler.getCanal();
        pendingFrequency = originalFrequency;
        
        frequencyField = new TextFieldWidget(this.textRenderer, this.width / 2 - 30, guiTop + 22, 60, 16, Text.literal(""));
        frequencyField.setMaxLength(6);
        frequencyField.setText(formatFrequency(originalFrequency));
        frequencyField.setChangedListener(this::onFrequencyChanged);
        this.addDrawableChild(frequencyField);

        // Confirm button
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> {
            applyFrequency();
            this.close();
        }).dimensions(this.width / 2 - 50, guiTop + ySize - 6 - 20, 45, 20).build());

        // Cancel button
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> {
            this.close();
        }).dimensions(this.width / 2 + 5, guiTop + ySize - 6 - 20, 45, 20).build());
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

    private void updateFrequencyField() {
        if (frequencyField == null || frequencyField.isFocused()) {
            return;
        }

        int canal = handler.getCanal();
        if (canal != originalFrequency) {
            originalFrequency = canal;
            pendingFrequency = canal;
            frequencyField.setText(formatFrequency(canal));
        }
    }

    private String formatFrequency(int frequency) {
        return String.format(Locale.ROOT, "%.1f", frequency / 10.0);
    }

    @Override
    public void removed() {
        // Auto-save on close
        applyFrequency();
        super.removed();
    }

    private void sendUpdateSpeaker(int index, boolean status) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(index);
        buf.writeBoolean(status);

        NetworkManager.sendToServer(ModMessages.UPDATE_SPEAKER_C2S, buf);
    }

    private void sendUpdateFrequency(int frequency) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(2); // New index for frequency update
        buf.writeInt(frequency);
        NetworkManager.sendToServer(ModMessages.UPDATE_SPEAKER_C2S, buf);
    }


}
