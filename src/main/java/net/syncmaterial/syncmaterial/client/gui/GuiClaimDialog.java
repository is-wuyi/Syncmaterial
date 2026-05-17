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
    private final int databaseId;
    private final String itemName;
    private final int totalCount;
    private final int claimedCount;
    private final int remaining;
    private String claimInput;

    private int dialogX, dialogY, dialogWidth, dialogHeight;
    private int inputX, inputY, inputWidth, inputHeight;
    private int confirmX, confirmY, cancelX, cancelY;
    private boolean inputFocused = false;

    public GuiClaimDialog(Screen parent, int databaseId, String itemName, int totalCount, int claimedCount) {
        super(Text.literal("认领材料"));
        this.parent = parent;
        this.databaseId = databaseId;
        this.itemName = itemName;
        this.totalCount = totalCount;
        this.claimedCount = claimedCount;
        this.remaining = totalCount - claimedCount;
        this.claimInput = String.valueOf(this.remaining);
    }

    @Override
    protected void init() {
        super.init();

        dialogWidth = 220;
        dialogHeight = 110;
        dialogX = (this.width - dialogWidth) / 2;
        dialogY = (this.height - dialogHeight) / 2;

        inputX = dialogX + 70;
        inputY = dialogY + 45;
        inputWidth = 90;
        inputHeight = 18;

        int btnY = dialogY + 75;
        confirmX = dialogX + 40;
        confirmY = btnY;
        cancelX = dialogX + dialogWidth - 90;
        cancelY = btnY;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0x80000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        if (this.textRenderer == null) {
            return;
        }

        context.fill(dialogX, dialogY, dialogX + dialogWidth, dialogY + dialogHeight, 0xFF202020);
        context.drawBorder(dialogX, dialogY, dialogWidth, dialogHeight, 0xFFFFFFFF);

        int textColor = 0xFFFFFFFF;
        int labelColor = 0xFFAAAAAA;

        context.drawTextWithShadow(this.textRenderer, "认领材料", dialogX + 10, dialogY + 10, textColor);
        context.drawTextWithShadow(this.textRenderer, "材料: " + itemName, dialogX + 10, dialogY + 26, labelColor);
        context.drawTextWithShadow(this.textRenderer, "剩余: " + remaining, dialogX + 10, dialogY + 38, labelColor);
        context.drawTextWithShadow(this.textRenderer, "数量:", dialogX + 10, inputY + 3, textColor);

        int borderColor = inputFocused ? 0xFFFFFFFF : 0xFF888888;
        context.fill(inputX, inputY, inputX + inputWidth, inputY + inputHeight, 0xFF000000);
        context.drawBorder(inputX, inputY, inputWidth, inputHeight, borderColor);
        context.drawTextWithShadow(this.textRenderer, claimInput, inputX + 5, inputY + 3, textColor);

        drawButton(context, confirmX, confirmY, 50, 18, "确认", mouseX, mouseY);
        drawButton(context, cancelX, cancelY, 50, 18, "取消", mouseX, mouseY);
    }

    private void drawButton(DrawContext context, int x, int y, int w, int h, String text, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        int color = hovered ? 0xFF606060 : 0xFF404040;
        context.fill(x, y, x + w, y + h, color);
        context.drawBorder(x, y, w, h, 0xFFFFFFFF);
        int textX = x + (w - this.textRenderer.getWidth(text)) / 2;
        int textY = y + (h - 8) / 2;
        context.drawTextWithShadow(this.textRenderer, text, textX, textY, 0xFFFFFFFF);
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
            if ((keyCode >= 48 && keyCode <= 57) || (keyCode >= 320 && keyCode <= 329)) {
                int digit = (keyCode >= 320) ? (keyCode - 320) : (keyCode - 48);
                claimInput += digit;
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
            if (amount > 0 && amount <= remaining) {
                ClientPlayNetworking.send(new ClaimMaterialC2SPacket(
                    SyncMaterialClient.getActiveMaterialList().getSchematicId(),
                    databaseId, amount));
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
