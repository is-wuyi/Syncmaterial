package net.syncmaterial.syncmaterial.client.gui;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Color4f;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.config.Configs;

public class StagingAreaSelector {
    private static final StagingAreaSelector INSTANCE = new StagingAreaSelector();

    /**
     * 准星选区完成回调接口（解耦 StagingAreaSelector 和具体 GUI 类）
     */
    public interface SelectionCallback {
        void onSelectionConfirmed(@Nullable String boxName, @Nullable BlockPos pos1, @Nullable BlockPos pos2);
    }

    /**
     * 选区目标类型：决定区域框用哪套配置颜色，
     * 让"编辑"看起来就是在就地改这个框，而不是旁边冒出一个陌生的预览框。
     */
    public enum TargetType {
        STAGING_AREA,
        WAREHOUSE
    }

    private static final Color4f COLOR_POS1 = new Color4f(1.0f, 0.0f, 0.0f, 1.0f);
    private static final Color4f COLOR_POS2 = new Color4f(0.0f, 0.0f, 1.0f, 1.0f);
    private static final Color4f COLOR_LOOKING = new Color4f(1.0f, 1.0f, 0.0f, 0.5f);

    private boolean active = false;
    @Nullable private BlockPos pos1 = null;
    @Nullable private BlockPos pos2 = null;
    @Nullable private BlockPos posLookingAt = null;
    @Nullable private SelectionCallback callback = null;
    @Nullable private Screen returnScreen = null;
    @Nullable private String targetBoxName = null;
    private TargetType targetType = TargetType.STAGING_AREA;
    /**
     * 正在编辑的仓库 ID（-1 表示不是在编辑仓库）。
     * 正式渲染据此跳过该仓库，避免同一个区域被画两遍（原框 + 预览框重叠）。
     */
    private int editingWarehouseId = -1;
    /** 正在编辑的备货区所属原理图，配合 targetBoxName 供正式渲染跳过 */
    @Nullable private String editingSchematicId = null;
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

    public void start(SelectionCallback callback, Screen returnScreen,
                      @Nullable String boxName, @Nullable BlockPos initialPos1, @Nullable BlockPos initialPos2) {
        this.start(callback, returnScreen, boxName, initialPos1, initialPos2,
                TargetType.STAGING_AREA, null, -1);
    }

    /**
     * 启动准星选区。
     *
     * @param targetType         决定区域框配色，使编辑时与该对象平时的线框颜色一致
     * @param editingSchematicId 编辑备货区时传所属原理图；新建传 null
     * @param editingWarehouseId 编辑仓库时传仓库 ID；新建或编辑备货区传 -1
     */
    public void start(SelectionCallback callback, Screen returnScreen,
                      @Nullable String boxName, @Nullable BlockPos initialPos1, @Nullable BlockPos initialPos2,
                      TargetType targetType, @Nullable String editingSchematicId, int editingWarehouseId) {
        this.active = true;
        this.callback = callback;
        this.returnScreen = returnScreen;
        this.targetBoxName = boxName;
        this.pos1 = initialPos1;
        this.pos2 = initialPos2;
        this.posLookingAt = null;
        this.targetType = targetType != null ? targetType : TargetType.STAGING_AREA;
        this.editingSchematicId = editingSchematicId;
        this.editingWarehouseId = editingWarehouseId;

        MinecraftClient mc = MinecraftClient.getInstance();
        // mc 为 null 只出现在无客户端实例的环境（单元测试）；
        // 选区状态此时已设置完毕，不应因为关不掉界面而整体抛出
        if (mc != null && mc.currentScreen != null) {
            mc.setScreen(null);
        }

        SyncMaterial.LOGGER.info("[StagingAreaSelector] 启动选区模式: boxName={}, pos1={}, pos2={}, type={}",
                boxName, initialPos1, initialPos2, this.targetType);
    }

    /**
     * 正式渲染是否应跳过该仓库：正在选区编辑它，交由选区渲染，避免同一区域画两遍。
     */
    public boolean isEditingWarehouse(int warehouseId) {
        return this.active && this.targetType == TargetType.WAREHOUSE
                && this.editingWarehouseId >= 0 && this.editingWarehouseId == warehouseId;
    }

    /**
     * 正式渲染是否应跳过该备货区，理由同 isEditingWarehouse。
     */
    public boolean isEditingStagingArea(@Nullable String schematicId, @Nullable String boxName) {
        return this.active && this.targetType == TargetType.STAGING_AREA
                && this.editingSchematicId != null && this.targetBoxName != null
                && this.editingSchematicId.equals(schematicId)
                && this.targetBoxName.equals(boxName);
    }

    public void cancel() {
        SyncMaterial.LOGGER.info("[StagingAreaSelector] 取消选区");
        this.active = false;
        this.returnToGui();
    }

    /**
     * 断连时的强制重置：不回到上一个界面（那属于已断开的服务器），
     * 只把状态清干净。否则 active 残留会让选区模式在下次进服时仍然生效。
     */
    public void reset() {
        this.active = false;
        this.pos1 = null;
        this.pos2 = null;
        this.posLookingAt = null;
        this.callback = null;
        this.returnScreen = null;
        this.targetBoxName = null;
        this.editingSchematicId = null;
        this.editingWarehouseId = -1;
        this.targetType = TargetType.STAGING_AREA;
        this.leftClicked = false;
        this.rightClicked = false;
    }

    public void confirm() {
        if (this.pos1 == null || this.pos2 == null) {
            SyncMaterial.LOGGER.warn("[StagingAreaSelector] 确认失败: pos1 或 pos2 为空");
            return;
        }

        SyncMaterial.LOGGER.info("[StagingAreaSelector] 确认选区: pos1={}, pos2={}", this.pos1, this.pos2);
        this.active = false;

        if (this.callback != null) {
            this.callback.onSelectionConfirmed(this.targetBoxName, this.pos1, this.pos2);
        }

        this.returnToGui();
    }

    private void returnToGui() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && this.returnScreen != null) {
            mc.setScreen(this.returnScreen);
        }
        this.returnScreen = null;
        this.targetBoxName = null;
        // 编辑上下文必须一并清掉：否则正式渲染会继续跳过该对象，
        // 而选区已退出不再绘制，那个框就永久消失了
        this.editingSchematicId = null;
        this.editingWarehouseId = -1;
        this.targetType = TargetType.STAGING_AREA;
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

        // 区域框用该对象平时的配色，让编辑看起来就是在就地改这个框。
        // 正式渲染会跳过被编辑的对象（见 isEditingWarehouse/isEditingStagingArea），
        // 所以这里画的就是"那一个框"本身，不会与原框重叠。
        Color4f areaLine;
        Color4f areaSide;
        if (this.targetType == TargetType.WAREHOUSE) {
            areaLine = Configs.Render.WAREHOUSE_LINE_COLOR.getColor();
            areaSide = Configs.Render.WAREHOUSE_SIDE_COLOR.getColor();
        } else {
            areaLine = Configs.Render.AREA_LINE_COLOR.getColor();
            areaSide = Configs.Render.AREA_SIDE_COLOR.getColor();
        }

        if (this.pos1 != null && this.pos2 != null) {
            RenderUtils.renderAreaOutline(this.pos1, this.pos2, 2.0f, areaLine, areaLine, areaLine);
            RenderUtils.renderAreaSides(this.pos1, this.pos2, areaSide, posMatrix);
        }

        // 角点标记在区域框之后绘制：红/蓝是"哪个角是 pos1、是否已点过"的必要反馈，
        // 需要盖在区域框上才看得清
        if (this.pos1 != null) {
            RenderUtils.renderAreaOutline(this.pos1, this.pos1, 3.0f, COLOR_POS1, COLOR_POS1, COLOR_POS1);
            RenderUtils.renderAreaSides(this.pos1, this.pos1, new Color4f(1.0f, 0.0f, 0.0f, 0.25f), posMatrix);
        }

        if (this.pos2 != null) {
            RenderUtils.renderAreaOutline(this.pos2, this.pos2, 3.0f, COLOR_POS2, COLOR_POS2, COLOR_POS2);
            RenderUtils.renderAreaSides(this.pos2, this.pos2, new Color4f(0.0f, 0.0f, 1.0f, 0.25f), posMatrix);
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

        String line1 = StringUtils.translate("syncmaterial.gui.hud.left_click_set_pos1") + (this.pos1 != null ? " ✓" : "");
        String line2 = StringUtils.translate("syncmaterial.gui.hud.right_click_set_pos2") + (this.pos2 != null ? " ✓" : "");
        String line3 = StringUtils.translate("syncmaterial.gui.hud.confirm_or_cancel");
        String lineCrosshair = this.posLookingAt != null
                ? StringUtils.translate("syncmaterial.gui.hud.crosshair",
                        this.posLookingAt.getX(), this.posLookingAt.getY(), this.posLookingAt.getZ())
                : "";

        int textY = centerY - 30;
        drawContext.fill(centerX - 150, textY - 5, centerX + 150, textY + 55, 0xCC000000);
        drawContext.drawTextWithShadow(mc.textRenderer, line1, centerX - mc.textRenderer.getWidth(line1) / 2, textY, 0xFFFFFFFF);
        drawContext.drawTextWithShadow(mc.textRenderer, line2, centerX - mc.textRenderer.getWidth(line2) / 2, textY + 14, 0xFFFFFFFF);
        drawContext.drawTextWithShadow(mc.textRenderer, line3, centerX - mc.textRenderer.getWidth(line3) / 2, textY + 28, 0xFFAAAAAA);
        if (!lineCrosshair.isEmpty()) {
            drawContext.drawTextWithShadow(mc.textRenderer, lineCrosshair, centerX - mc.textRenderer.getWidth(lineCrosshair) / 2, textY + 42, 0xFF55FFFF);
        }
    }
}
