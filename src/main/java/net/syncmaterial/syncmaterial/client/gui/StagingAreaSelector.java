package net.syncmaterial.syncmaterial.client.gui;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;

import net.syncmaterial.syncmaterial.SyncMaterial;

public class StagingAreaSelector {
    private static final StagingAreaSelector INSTANCE = new StagingAreaSelector();

    private static final Color4f COLOR_POS1 = new Color4f(1.0f, 0.0f, 0.0f, 1.0f);
    private static final Color4f COLOR_POS2 = new Color4f(0.0f, 0.0f, 1.0f, 1.0f);
    private static final Color4f COLOR_LOOKING = new Color4f(1.0f, 1.0f, 0.0f, 0.5f);
    private static final Color4f COLOR_AREA = new Color4f(0.0f, 1.0f, 0.0f, 0.3f);

    private boolean active = false;
    @Nullable private BlockPos pos1 = null;
    @Nullable private BlockPos pos2 = null;
    @Nullable private BlockPos posLookingAt = null;
    @Nullable private GuiStagingAreaEditorNormal returnScreen = null;
    @Nullable private String targetBoxName = null;
    private boolean leftClicked = false;
    private boolean rightClicked = false;

    private StagingAreaSelector() {}

    public static StagingAreaSelector getInstance() {
        return INSTANCE;
    }

    public boolean isActive() {
        return this.active;
    }

    @Nullable
    public BlockPos getPos1() {
        return this.pos1;
    }

    @Nullable
    public BlockPos getPos2() {
        return this.pos2;
    }

    public void start(GuiStagingAreaEditorNormal returnScreen, @Nullable String boxName,
                      @Nullable BlockPos initialPos1, @Nullable BlockPos initialPos2) {
        this.active = true;
        this.returnScreen = returnScreen;
        this.targetBoxName = boxName;
        this.pos1 = initialPos1;
        this.pos2 = initialPos2;
        this.posLookingAt = null;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null) {
            mc.setScreen(null);
        }

        SyncMaterial.LOGGER.info("[StagingAreaSelector] 启动选区模式: boxName={}, pos1={}, pos2={}",
                boxName, initialPos1, initialPos2);
    }

    public void cancel() {
        SyncMaterial.LOGGER.info("[StagingAreaSelector] 取消选区");
        this.active = false;
        this.returnToGui();
    }

    public void confirm() {
        if (this.pos1 == null || this.pos2 == null) {
            SyncMaterial.LOGGER.warn("[StagingAreaSelector] 确认失败: pos1 或 pos2 为空");
            return;
        }

        SyncMaterial.LOGGER.info("[StagingAreaSelector] 确认选区: pos1={}, pos2={}", this.pos1, this.pos2);
        this.active = false;

        if (this.returnScreen != null) {
            this.returnScreen.onSelectionConfirmed(this.targetBoxName, this.pos1, this.pos2);
        }

        this.returnToGui();
    }

    private void returnToGui() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (this.returnScreen != null) {
            mc.setScreen(this.returnScreen);
        }
        this.returnScreen = null;
        this.targetBoxName = null;
    }

    public void onTick() {
        if (!this.active) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            this.cancel();
            return;
        }

        HitResult hitResult = mc.crosshairTarget;
        if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hitResult;
            this.posLookingAt = blockHit.getBlockPos();

            if (mc.options.sneakKey.isPressed()) {
                Direction direction = blockHit.getSide();
                this.posLookingAt = this.posLookingAt.offset(direction);
            }
        } else {
            this.posLookingAt = null;
        }

        if (GLFW.glfwGetKey(mc.getWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
            this.cancel();
            return;
        }

        if (GLFW.glfwGetKey(mc.getWindow().getHandle(), GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS) {
            this.confirm();
            return;
        }

        if (this.posLookingAt != null) {
            boolean attackDown = mc.options.attackKey.isPressed();
            boolean useDown = mc.options.useKey.isPressed();

            if (attackDown && !this.leftClicked) {
                this.pos1 = this.posLookingAt;
                SyncMaterial.LOGGER.info("[StagingAreaSelector] 设置 pos1: {}", this.pos1);
            }

            if (useDown && !this.rightClicked) {
                this.pos2 = this.posLookingAt;
                SyncMaterial.LOGGER.info("[StagingAreaSelector] 设置 pos2: {}", this.pos2);
            }

            this.leftClicked = attackDown;
            this.rightClicked = useDown;
        } else {
            this.leftClicked = false;
            this.rightClicked = false;
        }
    }

    public void onRenderWorld(fi.dy.masa.malilib.interfaces.IRenderer renderer, org.joml.Matrix4f posMatrix) {
        if (!this.active) {
            return;
        }

        if (this.pos1 != null) {
            RenderUtils.renderAreaOutline(this.pos1, this.pos1, 3.0f, COLOR_POS1, COLOR_POS1, COLOR_POS1);
            RenderUtils.renderAreaSides(this.pos1, this.pos1, new Color4f(1.0f, 0.0f, 0.0f, 0.25f), posMatrix);
        }

        if (this.pos2 != null) {
            RenderUtils.renderAreaOutline(this.pos2, this.pos2, 3.0f, COLOR_POS2, COLOR_POS2, COLOR_POS2);
            RenderUtils.renderAreaSides(this.pos2, this.pos2, new Color4f(0.0f, 0.0f, 1.0f, 0.25f), posMatrix);
        }

        if (this.pos1 != null && this.pos2 != null) {
            RenderUtils.renderAreaOutline(this.pos1, this.pos2, 2.0f, COLOR_AREA, COLOR_AREA, COLOR_AREA);
            RenderUtils.renderAreaSides(this.pos1, this.pos2, COLOR_AREA, posMatrix);
        }

        if (this.posLookingAt != null) {
            RenderUtils.renderAreaOutline(this.posLookingAt, this.posLookingAt, 2.0f, COLOR_LOOKING, COLOR_LOOKING, COLOR_LOOKING);
            RenderUtils.renderAreaSides(this.posLookingAt, this.posLookingAt, COLOR_LOOKING, posMatrix);
        }
    }

    public void onRenderHUD(DrawContext drawContext) {
        if (!this.active) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        int centerX = drawContext.getScaledWindowWidth() / 2;
        int centerY = drawContext.getScaledWindowHeight() / 2;

        String line1 = "左键: 设置 pos1" + (this.pos1 != null ? " ✓" : "");
        String line2 = "右键: 设置 pos2" + (this.pos2 != null ? " ✓" : "");
        String line3 = "Enter: 确认 | Esc: 取消";

        if (this.posLookingAt != null) {
            line1 += "  准星: " + this.posLookingAt.getX() + ", " + this.posLookingAt.getY() + ", " + this.posLookingAt.getZ();
        }

        int textY = centerY - 30;
        drawContext.fill(centerX - 150, textY - 5, centerX + 150, textY + 45, 0xCC000000);
        drawContext.drawTextWithShadow(mc.textRenderer, line1, centerX - mc.textRenderer.getWidth(line1) / 2, textY, 0xFFFFFFFF);
        drawContext.drawTextWithShadow(mc.textRenderer, line2, centerX - mc.textRenderer.getWidth(line2) / 2, textY + 14, 0xFFFFFFFF);
        drawContext.drawTextWithShadow(mc.textRenderer, line3, centerX - mc.textRenderer.getWidth(line3) / 2, textY + 28, 0xFFAAAAAA);
    }
}
