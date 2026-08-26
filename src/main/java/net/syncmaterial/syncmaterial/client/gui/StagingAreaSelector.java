//? if >=26 {
package net.syncmaterial.syncmaterial.client.gui;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

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
    /**
     * 是否仍需关闭界面。
     *
     * start() 里的 setScreen(null) 未必最终生效：从 MaLiLib 文本弹窗启动时，
     * 弹窗基类在 applyValue 返回 true 之后还会执行 openGui(parent)，把界面又打开。
     * 该标记让 onTick 每帧兜底关闭，直到界面真正消失为止。
     */
    private boolean pendingScreenClose = false;
    private boolean leftClicked = false;
    private boolean rightClicked = false;

    private StagingAreaSelector() {}

    public static StagingAreaSelector getInstance() {
        return INSTANCE;
    }

    public boolean isActive() {
        return this.active;
    }

    /**
     * 是否仍在等待界面关闭。
     * 该标记为真期间 onTick 会持续调用 setScreen(null)，
     * 因此退出选区时必须清掉，否则会把正常打开的界面也关掉。
     */
    public boolean isPendingScreenClose() {
        return this.pendingScreenClose;
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

        Minecraft mc = Minecraft.getInstance();
        // 这里先关一次，随后由 onTick 每帧兜底，直到界面真正消失。
        //
        // 单关一次不够：从 MaLiLib 文本弹窗启动时，弹窗基类会在 applyValue 返回 true
        // 之后执行 openGui(parent)，把界面重新打开，导致选区模式已激活但屏幕仍是 GUI。
        // 曾试图用 Minecraft.execute() 推迟一帧，但那是错的 ——
        // ThreadExecutor.execute 仅在 shouldExecuteAsync() 为真时入队，
        // 而 GUI 回调本就在主线程且 runningTasks 为 0，因此是当场同步执行，起不到延迟作用。
        //
        // mc 为 null 只出现在无客户端实例的环境（单元测试）；
        // 选区状态此时已设置完毕，不应因为关不掉界面而整体抛出
        this.pendingScreenClose = true;
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
        // 不清会导致断连后持续关闭界面（连主菜单都会被关掉）
        this.pendingScreenClose = false;
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
        // 必须先清标记再 setScreen：否则 onTick 会把刚恢复的界面又关掉
        this.pendingScreenClose = false;

        Minecraft mc = Minecraft.getInstance();
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

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) {
            this.cancel();
            return;
        }

        // 兜底关闭界面：start() 之后 MaLiLib 弹窗基类可能又把父界面打开，
        // 这里持续关到界面真正消失，避免"选区已激活但屏幕仍是 GUI"
        if (this.pendingScreenClose) {
            if (mc.currentScreen != null) {
                mc.setScreen(null);
            } else {
                this.pendingScreenClose = false;
            }
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

    public void onRenderHUD(GuiGraphicsExtractor drawContext) {
        if (!this.active) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
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
//?} else {
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
    /**
     * 是否仍需关闭界面。
     *
     * start() 里的 setScreen(null) 未必最终生效：从 MaLiLib 文本弹窗启动时，
     * 弹窗基类在 applyValue 返回 true 之后还会执行 openGui(parent)，把界面又打开。
     * 该标记让 onTick 每帧兜底关闭，直到界面真正消失为止。
     */
    private boolean pendingScreenClose = false;
    private boolean leftClicked = false;
    private boolean rightClicked = false;

    private StagingAreaSelector() {}

    public static StagingAreaSelector getInstance() {
        return INSTANCE;
    }

    public boolean isActive() {
        return this.active;
    }

    /**
     * 是否仍在等待界面关闭。
     * 该标记为真期间 onTick 会持续调用 setScreen(null)，
     * 因此退出选区时必须清掉，否则会把正常打开的界面也关掉。
     */
    public boolean isPendingScreenClose() {
        return this.pendingScreenClose;
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
        // 这里先关一次，随后由 onTick 每帧兜底，直到界面真正消失。
        //
        // 单关一次不够：从 MaLiLib 文本弹窗启动时，弹窗基类会在 applyValue 返回 true
        // 之后执行 openGui(parent)，把界面重新打开，导致选区模式已激活但屏幕仍是 GUI。
        // 曾试图用 MinecraftClient.execute() 推迟一帧，但那是错的 ——
        // ThreadExecutor.execute 仅在 shouldExecuteAsync() 为真时入队，
        // 而 GUI 回调本就在主线程且 runningTasks 为 0，因此是当场同步执行，起不到延迟作用。
        //
        // mc 为 null 只出现在无客户端实例的环境（单元测试）；
        // 选区状态此时已设置完毕，不应因为关不掉界面而整体抛出
        this.pendingScreenClose = true;
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
        // 不清会导致断连后持续关闭界面（连主菜单都会被关掉）
        this.pendingScreenClose = false;
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
        // 必须先清标记再 setScreen：否则 onTick 会把刚恢复的界面又关掉
        this.pendingScreenClose = false;

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

        // 兜底关闭界面：start() 之后 MaLiLib 弹窗基类可能又把父界面打开，
        // 这里持续关到界面真正消失，避免"选区已激活但屏幕仍是 GUI"
        if (this.pendingScreenClose) {
            if (mc.currentScreen != null) {
                mc.setScreen(null);
            } else {
                this.pendingScreenClose = false;
            }
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
//?}
