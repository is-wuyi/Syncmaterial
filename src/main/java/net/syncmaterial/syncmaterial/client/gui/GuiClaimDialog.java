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
    private int claimAmount;
    private String claimInput = "";
    private boolean inputFocused = false;

    private int inputFieldX, inputFieldY, inputFieldWidth, inputFieldHeight;
    private int confirmBtnX, confirmBtnY, cancelBtnX, cancelBtnY;

    public GuiClaimDialog(Screen parent, String itemName, int totalNeeded) {
        super(Text.literal("认领材料"));
        this.parent = parent;
        this.itemName = itemName;
        this.totalNeeded = totalNeeded;
        this.claimAmount = totalNeeded;
        this.claimInput = String.valueOf(totalNeeded);
    }

    @Override
    protected void init() {
        super.init();

        int dialogWidth = 200;
        int dialogHeight = 120;
        int x = (this.width - dialogWidth) / 2;
        int y = (this.height - dialogHeight) / 2;

        inputFieldX = x + 20;
        inputFieldY = y + 50;
        inputFieldWidth = dialogWidth - 40;
        inputFieldHeight = 20;

        int btnY = y + 80;
        confirmBtnX = x + 30;
        confirmBtnY = btnY;
        cancelBtnX = x + dialogWidth - 80;
        cancelBtnY = btnY;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0x80000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int dialogWidth = 200;
        int dialogHeight = 120;
        int x = (this.width - dialogWidth) / 2;
        int y = (this.height - dialogHeight) / 2;

        context.fill(x, y, x + dialogWidth, y + dialogHeight, 0xFF202020);
        context.drawBorder(x, y, dialogWidth, dialogHeight, 0xFFFFFFFF);

        context.drawTextWithShadow(this.textRenderer, "认领材料", x + 10, y + 10, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "材料: " + itemName, x + 10, y + 25, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, "需要总量: " + totalNeeded, x + 10, y + 35, 0xAAAAAA);

        context.drawTextWithShadow(this.textRenderer, "认领数量:", x + 10, y + 52, 0xFFFFFF);

        int borderColor = inputFocused ? 0xFFFFFF : 0x888888;
        context.fill(inputFieldX, inputFieldY, inputFieldX + inputFieldWidth, inputFieldY + inputFieldHeight, 0xFF000000);
        context.drawBorder(inputFieldX, inputFieldY, inputFieldWidth, inputFieldHeight, borderColor);
        context.drawTextWithShadow(this.textRenderer, claimInput, inputFieldX + 5, inputFieldY + 6, 0xFFFFFF);

        drawButton(context, confirmBtnX, confirmBtnY, 50, 20, "确认", mouseX, mouseY);
        drawButton(context, cancelBtnX, cancelBtnY, 50, 20, "取消", mouseX, mouseY);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawButton(DrawContext context, int x, int y, int width, int height, String text, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        int color = hovered ? 0xFF606060 : 0xFF404040;
        context.fill(x, y, x + width, y + height, color);
        context.drawBorder(x, y, width, height, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, text, x + (width - this.textRenderer.getWidth(text)) / 2, y + 6, 0xFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= inputFieldX && mouseX <= inputFieldX + inputFieldWidth &&
            mouseY >= inputFieldY && mouseY <= inputFieldY + inputFieldHeight) {
            inputFocused = true;
            return true;
        }

        inputFocused = false;

        if (mouseX >= confirmBtnX && mouseX <= confirmBtnX + 50 &&
            mouseY >= confirmBtnY && mouseY <= confirmBtnY + 20) {
            try {
                claimAmount = Integer.parseInt(claimInput);
                if (claimAmount > 0 && claimAmount <= totalNeeded) {
                    ClientPlayNetworking.send(new ClaimMaterialC2SPacket(
                        SyncMaterialClient.getActiveMaterialList().getTitle(),
                        0, claimAmount));
                }
            } catch (NumberFormatException e) {
            }
            this.close();
            return true;
        }

        if (mouseX >= cancelBtnX && mouseX <= cancelBtnX + 50 &&
            mouseY >= cancelBtnY && mouseY <= cancelBtnY + 20) {
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
                try {
                    claimAmount = Integer.parseInt(claimInput);
                    if (claimAmount > 0 && claimAmount <= totalNeeded) {
                        ClientPlayNetworking.send(new ClaimMaterialC2SPacket(
                            SyncMaterialClient.getActiveMaterialList().getTitle(),
                            0, claimAmount));
                    }
                } catch (NumberFormatException e) {
                }
                this.close();
                return true;
            }
        }
        if (keyCode == 256) {
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
