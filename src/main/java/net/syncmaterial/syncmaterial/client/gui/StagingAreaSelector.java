package net.syncmaterial.syncmaterial.client.gui;

import java.util.Objects;
import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
//? if >=26.2 {
import fi.dy.masa.malilib.render.GuiContext;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Color4f;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.session.ClientConnectionSession;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;

/**
 * 准星选区状态机。
 *
 * <p>每个连接会话拥有一个实例（{@code ClientConnectionSession.selector()}），
 * {@link #getInstance()} 只是"当前会话的 selector"的便捷入口；没有活动会话时
 * 返回一个永不激活的断开空实例，避免旧连接残留的选区模式落到主菜单/新服务器。</p>
 *
 * <p>{@link #start} 会一次性捕获四样东西：目标 scope（项目或 global 仓库）、
 * 编辑意图（目标备货区/仓库或新建）、启动时的维度、以及原回调/返回界面/视图
 * 围栏。{@link #confirm()} / {@link #cancel()} 最多消费一次，消费时立刻释放
 * callback、returnScreen 与 pendingScreenClose；换维度、目标视图失效或断线
 * 一律只丢弃，不提交旧角点，也不恢复已失效的旧界面。</p>
 */
public class StagingAreaSelector {
    private static final FenceCheck ALWAYS_CURRENT = () -> true;
    private static final StagingAreaSelector DETACHED = new StagingAreaSelector(true);

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

    /**
     * 本次选区归属的 scope。global 表示全局仓库（不隶属任何项目）；
     * projectId 为 null 且非 global 表示上下文未知（旧调用方没有传项目）。
     */
    public record SelectionScope(@Nullable String projectId, boolean global) {
        public SelectionScope {
            if (!global && projectId != null && projectId.isBlank()) {
                projectId = null;
            }
        }

        public static SelectionScope project(String projectId) {
            return new SelectionScope(Objects.requireNonNull(projectId, "projectId"), false);
        }

        /** 不能命名为 global()：会与记录组件 global 的访问器冲突。 */
        public static SelectionScope globalScope() {
            return new SelectionScope(null, true);
        }

        /** 供 ProjectView 围栏使用的类型化 scope；未知上下文返回 null。 */
        @Nullable
        public ProjectViewScope viewScope() {
            if (this.global) return ProjectViewScope.globalWarehouse();
            return this.projectId == null ? null : ProjectViewScope.project(this.projectId);
        }
    }

    /** 视图围栏：start 时捕获，confirm/cancel 时确认目标仍然有效。 */
    @FunctionalInterface
    interface FenceCheck {
        boolean isCurrent();
    }

    /** 维度来源；生产读取玩家当前维度，测试可注入。 */
    @FunctionalInterface
    interface DimensionSource {
        @Nullable
        String currentDimension();
    }

    private static final Color4f COLOR_POS1 = new Color4f(1.0f, 0.0f, 0.0f, 1.0f);
    private static final Color4f COLOR_POS2 = new Color4f(0.0f, 0.0f, 1.0f, 1.0f);
    private static final Color4f COLOR_LOOKING = new Color4f(1.0f, 1.0f, 0.0f, 0.5f);

    /** 断开空实例：不拥有任何会话，任何输入都不得让它激活。 */
    private final boolean detached;

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
    /** 启动时捕获的 scope（项目或 global），消费后清空。 */
    @Nullable private SelectionScope scope = null;
    /** 启动时捕获的视图围栏；目标被删除/换视图后确认会被丢弃。 */
    private FenceCheck viewFence = ALWAYS_CURRENT;
    /** 协议 5 下启动时没有有效视图令牌：本次选区拒绝进入可提交状态。 */
    private boolean viewRequiredMissing = false;
    /** 启动时的维度；换维度后旧角点一律不提交。 */
    @Nullable private String startDimension = null;
    private DimensionSource dimensionSource = StagingAreaSelector::minecraftDimension;
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

    /** 供 ClientConnectionSession 为每个连接创建一个独立实例。 */
    public StagingAreaSelector() {
        this(false);
    }

    private StagingAreaSelector(boolean detached) {
        this.detached = detached;
    }

    /**
     * 当前会话的选区实例；无会话时返回断开空实例（永不激活）。
     */
    public static StagingAreaSelector getInstance() {
        return ClientSessionManager.current()
                .map(ClientConnectionSession::selector)
                .orElse(DETACHED);
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

    @Nullable
    public SelectionScope getScope() {
        return this.scope;
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
     * @param editingSchematicId 编辑/新建备货区时的所属原理图；仓库目标可传 null
     * @param editingWarehouseId 编辑仓库时传仓库 ID；新建或编辑备货区传 -1
     */
    public void start(SelectionCallback callback, Screen returnScreen,
                      @Nullable String boxName, @Nullable BlockPos initialPos1, @Nullable BlockPos initialPos2,
                      TargetType targetType, @Nullable String editingSchematicId, int editingWarehouseId) {
        if (this.detached) {
            SyncMaterial.LOGGER.debug("[StagingAreaSelector] 当前无活动连接会话，忽略选区启动");
            return;
        }

        // 上一次未消费的意图直接丢弃：不调用旧 callback，也不恢复旧界面
        if (this.active) {
            SyncMaterial.LOGGER.info("[StagingAreaSelector] 重新启动选区，丢弃上一次未消费的意图");
        }

        this.targetType = targetType != null ? targetType : TargetType.STAGING_AREA;
        this.editingSchematicId = editingSchematicId;
        this.editingWarehouseId = editingWarehouseId;
        this.scope = resolveScope(this.targetType, editingSchematicId);
        this.viewFence = captureViewFence(this.scope);
        // 协议 5 下没有有效视图令牌（视图未建立/旧兼容调用没传目标）时，
        // 本次选区不得进入可提交状态：confirm 会被拒绝并提示，不用 ALWAYS_CURRENT 回退
        this.viewRequiredMissing = ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)
                && (this.scope == null || this.viewFence == ALWAYS_CURRENT);
        this.startDimension = this.dimensionSource.currentDimension();

        this.active = true;
        this.callback = callback;
        this.returnScreen = returnScreen;
        this.targetBoxName = boxName;
        this.pos1 = initialPos1;
        this.pos2 = initialPos2;
        this.posLookingAt = null;

        if (this.viewRequiredMissing && Minecraft.getInstance() != null) {
            fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                    fi.dy.masa.malilib.gui.Message.MessageType.ERROR,
                    fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.result.project_missing"));
        }

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
        if (mc != null && net.syncmaterial.syncmaterial.client.ClientScreenAccess.currentScreen(mc) != null) {
            net.syncmaterial.syncmaterial.client.ClientScreenAccess.showScreen(mc, null);
        }

        SyncMaterial.LOGGER.info("[StagingAreaSelector] 启动选区模式: boxName={}, pos1={}, pos2={}, type={}, scope={}",
                boxName, initialPos1, initialPos2, this.targetType, this.scope);
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

    /**
     * 取消选区。目标视图已失效（项目/仓库被删除或换视图）时不恢复旧界面。
     */
    public void cancel() {
        if (this.detached || !this.active) {
            return;
        }
        SyncMaterial.LOGGER.info("[StagingAreaSelector] 取消选区");
        boolean fenceCurrent = this.viewFence.isCurrent();
        Screen screen = this.returnScreen;
        this.active = false;
        this.consumeIntent();
        if (fenceCurrent) {
            restoreScreen(screen);
        }
    }

    /**
     * 丢弃选区：不调用回调、不恢复旧界面、清掉旧角点。
     * 用于换维度、断线以及目标已失效的场景。
     */
    /** Called by the session dimension tracker; old-world points are never submitted. */
    public void onDimensionChanged() {
        this.discardIfDimensionChanged();
    }

    public void discard() {
        if (this.detached) {
            return;
        }
        this.active = false;
        this.pos1 = null;
        this.pos2 = null;
        this.posLookingAt = null;
        this.consumeIntent();
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
        this.startDimension = null;
        this.consumeIntent();
    }

    /**
     * 确认选区。无论成功、角点不足、维度已变还是视图失效，本次意图最多消费一次：
     * callback、returnScreen、pendingScreenClose 在消费时立刻释放 —— 因此回调里
     * 快速重选（start）建立的是全新意图，不会被旧意图的收尾清掉。
     */
    public void confirm() {
        if (this.detached || !this.active) {
            return;
        }

        // 换维度后旧角点属于别的世界，不能提交
        if (this.discardIfDimensionChanged()) {
            return;
        }

        BlockPos pos1 = this.pos1;
        BlockPos pos2 = this.pos2;
        if (pos1 == null || pos2 == null) {
            SyncMaterial.LOGGER.warn("[StagingAreaSelector] 确认失败: pos1 或 pos2 为空");
            return;
        }

        String boxName = this.targetBoxName;
        SelectionCallback callback = this.callback;
        Screen screen = this.returnScreen;
        boolean fenceCurrent = this.viewFence.isCurrent();
        boolean viewMissing = this.viewRequiredMissing;

        this.active = false;
        this.consumeIntent();

        SyncMaterial.LOGGER.info("[StagingAreaSelector] 确认选区: pos1={}, pos2={}", pos1, pos2);

        if (!fenceCurrent || viewMissing) {
            // 协议 5 视图未建立/已换代，或目标项目/仓库已被删除，或旧兼容调用缺显式目标：
            // 不写回旧界面，也不回调旧 GUI；缺视图/缺目标的情况给出明确提示
            if (viewMissing && Minecraft.getInstance() != null) {
                fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                        fi.dy.masa.malilib.gui.Message.MessageType.ERROR,
                        fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.result.project_missing"));
            }
            SyncMaterial.LOGGER.info("[StagingAreaSelector] 目标视图已失效，丢弃选区结果且不恢复旧界面");
            return;
        }

        try {
            if (callback != null) {
                callback.onSelectionConfirmed(boxName, pos1, pos2);
            }
        } catch (Exception e) {
            // 回调异常：不恢复旧界面（回调可能已打开新界面/新选区），意图已消费不复活
            SyncMaterial.LOGGER.error("[StagingAreaSelector] 选区确认回调异常", e);
            return;
        }

        // 回调之后再恢复界面：回调可能已经把新界面打开；
        // 回调中快速重选建立了新意图（active==true）时不得恢复旧 screen
        if (!this.active) {
            restoreScreen(screen);
        }
    }

    /** MaLiLib keyboard entry; only an active selection consumes Enter/Escape. */
    public boolean onKeyInput(int keyCode, int scanCode, int modifiers, boolean pressed) {
        if (this.detached || !this.active || !pressed) return false;
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            this.confirm();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.cancel();
            return true;
        }
        return false;
    }

    public void onTick() {
        if (this.detached || !this.active) {
            return;
        }

        // 换维度：取消且不提交旧角点，也不恢复旧界面
        if (this.discardIfDimensionChanged()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            // 断线/离开世界：不能恢复到已断开的服务器界面
            this.discard();
            return;
        }

        // 兜底关闭界面：start() 之后 MaLiLib 弹窗基类可能又把父界面打开，
        // 这里持续关到界面真正消失，避免"选区已激活但屏幕仍是 GUI"
        if (this.pendingScreenClose) {
            if (net.syncmaterial.syncmaterial.client.ClientScreenAccess.currentScreen(mc) != null) {
                net.syncmaterial.syncmaterial.client.ClientScreenAccess.showScreen(mc, null);
            } else {
                this.pendingScreenClose = false;
            }
        }

        HitResult hitResult = mc.hitResult;
        if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hitResult;
            this.posLookingAt = blockHit.getBlockPos();

            if (mc.options.keyShift.isDown()) {
                Direction direction = blockHit.getDirection();
                this.posLookingAt = this.posLookingAt.relative(direction);
            }
        } else {
            this.posLookingAt = null;
        }

        // Enter/Esc 只走 MaLiLib 键盘事件入口（onKeyInput），不再轮询 GLFW：
        // 两套独立确认逻辑会在同一按键上触发两次状态机（事件+轮询），事件路径是唯一事实。

        if (this.posLookingAt != null) {
            boolean attackDown = mc.options.keyAttack.isDown();
            boolean useDown = mc.options.keyUse.isDown();

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

    //? if >=26.2 {
    public void onRenderWorld(fi.dy.masa.malilib.interfaces.IRenderer renderer) {
    //?} else {
    /*public void onRenderWorld(fi.dy.masa.malilib.interfaces.IRenderer renderer, org.joml.Matrix4f posMatrix) {
    *///?}
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
            //? if >=26.2 {
            RenderUtils.renderAreaSides(this.pos1, this.pos2, areaSide);
            //?} else {
            /*RenderUtils.renderAreaSides(this.pos1, this.pos2, areaSide, posMatrix);
            *///?}
        }

        // 角点标记在区域框之后绘制：红/蓝是"哪个角是 pos1、是否已点过"的必要反馈，
        // 需要盖在区域框上才看得清
        if (this.pos1 != null) {
            RenderUtils.renderAreaOutline(this.pos1, this.pos1, 3.0f, COLOR_POS1, COLOR_POS1, COLOR_POS1);
            //? if >=26.2 {
            RenderUtils.renderAreaSides(this.pos1, this.pos1, new Color4f(1.0f, 0.0f, 0.0f, 0.25f));
            //?} else {
            /*RenderUtils.renderAreaSides(this.pos1, this.pos1, new Color4f(1.0f, 0.0f, 0.0f, 0.25f), posMatrix);
            *///?}
        }

        if (this.pos2 != null) {
            RenderUtils.renderAreaOutline(this.pos2, this.pos2, 3.0f, COLOR_POS2, COLOR_POS2, COLOR_POS2);
            //? if >=26.2 {
            RenderUtils.renderAreaSides(this.pos2, this.pos2, new Color4f(0.0f, 0.0f, 1.0f, 0.25f));
            //?} else {
            /*RenderUtils.renderAreaSides(this.pos2, this.pos2, new Color4f(0.0f, 0.0f, 1.0f, 0.25f), posMatrix);
            *///?}
        }

        if (this.posLookingAt != null) {
            RenderUtils.renderAreaOutline(this.posLookingAt, this.posLookingAt, 2.0f, COLOR_LOOKING, COLOR_LOOKING, COLOR_LOOKING);
            //? if >=26.2 {
            RenderUtils.renderAreaSides(this.posLookingAt, this.posLookingAt, COLOR_LOOKING);
            //?} else {
            /*RenderUtils.renderAreaSides(this.posLookingAt, this.posLookingAt, COLOR_LOOKING, posMatrix);
            *///?}
        }
    }

    //? if >=26.2 {
    public void onRenderHUD(GuiContext drawContext) {
    //?} else {
    /*public void onRenderHUD(GuiGraphics drawContext) {
    *///?}
        if (!this.active) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int centerX = drawContext.guiWidth() / 2;
        int centerY = drawContext.guiHeight() / 2;

        String line1 = StringUtils.translate("syncmaterial.gui.hud.left_click_set_pos1") + (this.pos1 != null ? " ✓" : "");
        String line2 = StringUtils.translate("syncmaterial.gui.hud.right_click_set_pos2") + (this.pos2 != null ? " ✓" : "");
        String line3 = StringUtils.translate("syncmaterial.gui.hud.confirm_or_cancel");
        String lineCrosshair = this.posLookingAt != null
                ? StringUtils.translate("syncmaterial.gui.hud.crosshair",
                        this.posLookingAt.getX(), this.posLookingAt.getY(), this.posLookingAt.getZ())
                : "";

        int textY = centerY - 30;
        drawContext.fill(centerX - 150, textY - 5, centerX + 150, textY + 55, 0xCC000000);
        drawContext.drawString(mc.font, line1, centerX - mc.font.width(line1) / 2, textY, 0xFFFFFFFF, true);
        drawContext.drawString(mc.font, line2, centerX - mc.font.width(line2) / 2, textY + 14, 0xFFFFFFFF, true);
        drawContext.drawString(mc.font, line3, centerX - mc.font.width(line3) / 2, textY + 28, 0xFFAAAAAA, true);
        if (!lineCrosshair.isEmpty()) {
            drawContext.drawString(mc.font, lineCrosshair, centerX - mc.font.width(lineCrosshair) / 2, textY + 42, 0xFF55FFFF, true);
        }
    }

    // ========== 内部：scope / 围栏 / 维度 ==========

    @Nullable
    private static SelectionScope resolveScope(TargetType targetType, @Nullable String editingSchematicId) {
        if (targetType == TargetType.WAREHOUSE) {
            return SelectionScope.globalScope();
        }
        if (editingSchematicId != null && !editingSchematicId.isBlank()) {
            return SelectionScope.project(editingSchematicId);
        }
        // 旧兼容重载没有显式目标：不得默默取当前项目视图的 scope。
        // scope 保持未知（协议 1—4 无视图围栏，行为不变；协议 5 下该选区拒绝提交并提示），
        // 生产调用必须传真实项目 ID / global scope（见 8 参 start 重载）。
        return null;
    }

    private static FenceCheck captureViewFence(@Nullable SelectionScope scope) {
        ProjectViewScope viewScope = scope == null ? null : scope.viewScope();
        if (viewScope == null) {
            return ALWAYS_CURRENT;
        }
        // 协议 5 视图缺失时不再用 ALWAYS_CURRENT 回退：围栏保持严格，
        // isFenceCurrent 返回 false，confirm 拒绝提交并提示（见 start/confirm）
        ProjectViewEditorSupport.EditFence fence = ProjectViewEditorSupport.captureFence(viewScope);
        return () -> ProjectViewEditorSupport.isFenceCurrent(fence);
    }

    private boolean dimensionChangedSinceStart() {
        String start = this.startDimension;
        if (start == null) {
            return false;
        }
        String current = this.dimensionSource.currentDimension();
        return current != null && !start.equals(current);
    }

    /**
     * 维度围栏：换维度时丢弃选区且不提交旧角点。
     *
     * @return true 表示已丢弃
     */
    boolean discardIfDimensionChanged() {
        if (!this.dimensionChangedSinceStart()) {
            return false;
        }
        SyncMaterial.LOGGER.info("[StagingAreaSelector] 维度已变化，取消选区且不提交旧角点: {} -> {}",
                this.startDimension, this.dimensionSource.currentDimension());
        this.discard();
        return true;
    }

    /** 释放本次意图的一次性资源；调用方负责设置 active=false。 */
    private void consumeIntent() {
        this.callback = null;
        this.returnScreen = null;
        this.pendingScreenClose = false;
        this.targetBoxName = null;
        this.editingSchematicId = null;
        this.editingWarehouseId = -1;
        this.targetType = TargetType.STAGING_AREA;
        this.scope = null;
        this.viewFence = ALWAYS_CURRENT;
        this.viewRequiredMissing = false;
        this.leftClicked = false;
        this.rightClicked = false;
    }

    private static void restoreScreen(@Nullable Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && screen != null) {
            net.syncmaterial.syncmaterial.client.ClientScreenAccess.showScreen(mc, screen);
        }
    }

    @Nullable
    private static String minecraftDimension() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.player.level() == null) {
            return null;
        }
        return McCompat.dimensionId(mc.player.level());
    }

    // ========== 测试钩子（同包可见） ==========

    void setViewFenceForTesting(FenceCheck fence) {
        this.viewFence = fence == null ? ALWAYS_CURRENT : fence;
    }

    void setDimensionSourceForTesting(DimensionSource source) {
        this.dimensionSource = source == null ? StagingAreaSelector::minecraftDimension : source;
    }

    @Nullable
    SelectionCallback callbackForTesting() {
        return this.callback;
    }

    @Nullable
    Screen returnScreenForTesting() {
        return this.returnScreen;
    }

    @Nullable
    String startDimensionForTesting() {
        return this.startDimension;
    }
}
