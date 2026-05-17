package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.network.ClaimMaterialC2SPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class GuiClaimDialog extends Screen {
    private final Screen parent;
    private final String itemName;
    private final int totalNeeded;
    private String claimInput;

    private int dialogX, dialogY, dialogWidth, dialogHeight;
    private int inputX, inputY, inputWidth, inputHeight;
    private int confirmX, confirmY, cancelX, cancelY;
    private boolean inputFocused = false;

    public GuiClaimDialog(Screen parent, String itemName, int totalNeeded) {
        super(Text.literal("认领材料"));
        this.parent = parent;
        this.itemName = itemName;
        this.totalNeeded = totalNeeded;
        this.claimInput = String.valueOf(totalNeeded);
    }

    @Override
    protected void init() {
        super.init();

        dialogWidth = 200;
        dialogHeight = 100;
        dialogX = (this.width - dialogWidth) / 2;
        dialogY = (this.height - dialogHeight) / 2;

        inputX = dialogX + 60;
        inputY = dialogY + 40;
        inputWidth = 80;
        inputHeight = 20;

        int btnY = dialogY + 70;
        confirmX = dialogX + 30;
        confirmY = btnY;
        cancelX = dialogX + dialogWidth - 80;
        cancelY = btnY;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0x80000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        context.fill(dialogX, dialogY, dialogX + dialogWidth, dialogY + dialogHeight, 0xFF303030);
        context.drawBorder(dialogX, dialogY, dialogWidth, dialogHeight, 0xFFFFFFFF);

        context.drawTextWithShadow(this.textRenderer, "认领材料", dialogX + 10, dialogY + 8, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "材料: " + itemName, dialogX + 10, dialogY + 22, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, "总量: " + totalNeeded, dialogX + 10, dialogY + 32, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, "数量:", dialogX + 10, inputY + 6, 0xFFFFFF);

        int borderColor = inputFocused ? 0xFFFFFF : 0x888888;
        context.fill(inputX, inputY, inputX + inputWidth, inputY + inputHeight, 0xFF000000);
        context.drawBorder(inputX, inputY, inputWidth, inputHeight, borderColor);
        context.drawTextWithShadow(this.textRenderer, claimInput, inputX + 5, inputY + 6, 0xFFFFFF);

        drawButton(context, confirmX, confirmY, 50, 18, "确认", mouseX, mouseY);
        drawButton(context, cancelX, cancelY, 50, 18, "取消", mouseX, mouseY);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawButton(DrawContext context, int x, int y, int w, int h, String text, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        int color = hovered ? 0xFF606060 : 0xFF404040;
        context.fill(x, y, x + w, y + h, color);
        context.drawBorder(x, y, w, h, 0xFFFFFFFF);
        int textX = x + (w - this.textRenderer.getWidth(text)) / 2;
        int textY = y + (h - 8) / 2;
        context.drawTextWithShadow(this.textRenderer, text, textX, textY, 0xFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= inputX && mouseX <= inputX + inputWidth &&
            mouseY >= inputY && mouseY <= inputY + inputHeight) {
            inputFocused = true;
            return true;
        }
        inputFocused = false;

        if (mouseX >= confirmX && mouseX <= confirmX + 50 &&
            mouseY >= confirmY && mouseY <= confirmY + 18) {
            tryClaim();
            return true;
        }

        if (mouseX >= cancelX && mouseX <= cancelX + 50 &&
            mouseY >= cancelY && mouseY <= cancelY + 18) {
            this.close();
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (inputFocused) {
            if (keyCode == 259 && claimInput.length() > 0) {
                claimInput = claimInput.substring(0, claimInput.length() - 1);
                return true;
            }
            if (keyCode >= 256 && keyCode <= 265) {
                claimInput += (char) ('0' + (keyCode - 256));
                return true;
            }
            if (keyCode == 257) {
                tryClaim();
                return true;
            }
        }
        if (keyCode == 256) {
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void tryClaim() {
        try {
            int amount = Integer.parseInt(claimInput);
            if (amount > 0 && amount <= totalNeeded) {
                ClientPlayNetworking.send(new ClaimMaterialC2SPacket(
                    SyncMaterialClient.getActiveMaterialList().getTitle(),
                    0, amount));
            }
        } catch (NumberFormatException e) {
        }
        this.close();
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
