package net.syncmaterial.syncmaterial.client.render;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

//? if >=26.2 {
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?} else {
/*import org.joml.Matrix4f;
import net.minecraft.client.Camera;
*///?}
import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.syncmaterial.syncmaterial.compat.McCompat;

import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;

import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.session.ClientConnectionSession;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;

/**
 * 备货区/仓库线框的绘制引擎与会话渲染状态的兼容入口。
 *
 * <p>状态归属已经迁移：{@link State} 由 {@link ClientConnectionSession} 拥有，
 * 每个连接一份，断线即随会话回收。渲染引擎本身不再持有任何跨连接数据 ——
 * 每帧从 {@link ClientSessionManager#current()} 取当前会话的 state，无会话时
 * 不绘制任何东西。这样上个服务器/上次连接的 schematicId 不会画进新世界。</p>
 *
 * <p>本类原有的公开方法全部保留，作为"当前会话状态"的兼容委托：
 * 有会话时读写该会话的 {@link State}，无会话时是空操作或返回空值。
 * 新代码应优先通过 {@code ClientConnectionSession.rendering()} 直接拿到 State。</p>
 */
public class StagingAreaRenderer implements IRenderer
{
    private static final StagingAreaRenderer INSTANCE = new StagingAreaRenderer();

    /**
     * 一个客户端连接独占的渲染状态。
     *
     * <p>仓库容器与仓库区域都用不可变快照整体替换：读线程只可能看到某一代完整
     * 数据，不会出现"新 entries + 旧 world/loaded"这类字段混代。</p>
     */
    public static final class State
    {
        private final Map<String, AreaSelection> selections = new ConcurrentHashMap<>();
        private final Map<String, Boolean> renderEnabled = new ConcurrentHashMap<>();
        private final Map<String, String> schematicNames = new ConcurrentHashMap<>();
        private volatile Highlight highlight = Highlight.NONE;
        private volatile WarehouseContainersSnapshot warehouseContainers = WarehouseContainersSnapshot.empty();
        private volatile WarehouseAreasSnapshot warehouseAreas = WarehouseAreasSnapshot.empty();

        public State() {}

        // ===== 项目备货区 =====

        public void updateSelection(String schematicId, AreaSelection selection)
        {
            if (schematicId == null || selection == null) return;
            this.selections.put(schematicId, selection);
        }

        public void removeSelection(String schematicId)
        {
            if (schematicId == null) return;
            this.selections.remove(schematicId);
        }

        @Nullable
        public AreaSelection selection(String schematicId)
        {
            return schematicId == null ? null : this.selections.get(schematicId);
        }

        public void setRenderEnabled(String schematicId, boolean enabled)
        {
            if (schematicId == null) return;
            this.renderEnabled.put(schematicId, enabled);
        }

        public boolean isRenderEnabled(String schematicId)
        {
            return schematicId == null || this.renderEnabled.getOrDefault(schematicId, true);
        }

        /** 删除一个项目的全部渲染数据；不影响全局仓库。 */
        public void removeRenderData(String schematicId)
        {
            if (schematicId == null) return;
            this.selections.remove(schematicId);
            this.renderEnabled.remove(schematicId);
            this.schematicNames.remove(schematicId);
            Highlight current = this.highlight;
            if (schematicId.equals(current.schematicId()))
            {
                this.highlight = Highlight.NONE;
            }
        }

        /**
         * 切到某个项目：只清掉其他项目的备货区数据，全局仓库快照原样保留。
         * 这样项目之间切换不会把上个项目的备货区线框带进新项目。
         */
        public void activateProject(String projectId)
        {
            if (projectId == null) return;
            for (String key : List.copyOf(this.selections.keySet()))
            {
                if (!projectId.equals(key)) this.selections.remove(key);
            }
            for (String key : List.copyOf(this.renderEnabled.keySet()))
            {
                if (!projectId.equals(key)) this.renderEnabled.remove(key);
            }
            for (String key : List.copyOf(this.schematicNames.keySet()))
            {
                if (!projectId.equals(key)) this.schematicNames.remove(key);
            }
            Highlight current = this.highlight;
            if (current.schematicId() != null && !projectId.equals(current.schematicId()))
            {
                this.highlight = Highlight.NONE;
            }
        }

        /** 只清一个项目的渲染状态（例如项目切换的旧项目），不触碰全局仓库。 */
        public void clearProject(String projectId)
        {
            this.removeRenderData(projectId);
        }

        /** 清空全部备货区线框（换服/断线用），不影响仓库快照。 */
        public void clearAllSelections()
        {
            this.selections.clear();
            this.renderEnabled.clear();
            this.schematicNames.clear();
            this.highlight = Highlight.NONE;
        }

        public void setHighlightedBox(String schematicId, @Nullable String boxName)
        {
            this.highlight = new Highlight(schematicId, boxName);
        }

        public void clearHighlightedBox()
        {
            this.highlight = Highlight.NONE;
        }

        public Highlight highlight()
        {
            return this.highlight;
        }

        public void setSchematicName(String schematicId, String name)
        {
            if (schematicId == null || name == null) return;
            this.schematicNames.put(schematicId, name);
        }

        @Nullable
        public String schematicName(String schematicId)
        {
            return schematicId == null ? null : this.schematicNames.get(schematicId);
        }

        // ===== 仓库容器（全局，取货高亮用） =====

        public WarehouseContainersSnapshot warehouseContainers()
        {
            return this.warehouseContainers;
        }

        /** 一次替换整个快照：entries/world/loaded 永远来自同一次响应。 */
        public void updateWarehouseContainers(
                List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers, @Nullable String worldId)
        {
            this.warehouseContainers = new WarehouseContainersSnapshot(containers, worldId, true);
        }

        public void clearWarehouseContainers()
        {
            this.warehouseContainers = WarehouseContainersSnapshot.empty();
        }

        public boolean hasWarehouseContainersLoaded()
        {
            return this.warehouseContainers.loaded();
        }

        // ===== 仓库区域（全局，独立于项目） =====

        public WarehouseAreasSnapshot warehouseAreas()
        {
            return this.warehouseAreas;
        }

        /** 一次替换整个快照：areas 与 referencedIds 永远来自同一次广播。 */
        public void updateWarehouseAreas(List<StagingAreaConfigResponseS2CPacket.AreaInfo> warehouses,
                                         List<Integer> referencedIds)
        {
            this.warehouseAreas = new WarehouseAreasSnapshot(warehouses,
                    referencedIds == null ? Set.of() : Set.copyOf(referencedIds));
        }

        public void clearWarehouseAreas()
        {
            this.warehouseAreas = WarehouseAreasSnapshot.empty();
        }

        public boolean isWarehouseReferenced(int warehouseId)
        {
            return this.warehouseAreas.referencedIds().contains(warehouseId);
        }

        // ===== 会话回收 =====

        /** 清空该连接拥有的全部渲染数据（断线/换服/快速重连）。 */
        public void clear()
        {
            this.clearAllSelections();
            this.clearWarehouseContainers();
            this.clearWarehouseAreas();
        }

        /** 不可变的仓库容器快照。 */
        public record WarehouseContainersSnapshot(
                List<WarehouseContainerResponseS2CPacket.ContainerEntry> entries,
                String world,
                boolean loaded)
        {
            public WarehouseContainersSnapshot
            {
                entries = entries == null ? List.of() : List.copyOf(entries);
                world = world == null ? "" : world;
            }

            public static WarehouseContainersSnapshot empty()
            {
                return new WarehouseContainersSnapshot(List.of(), "", false);
            }
        }

        /** 不可变的仓库区域快照。 */
        public record WarehouseAreasSnapshot(
                List<StagingAreaConfigResponseS2CPacket.AreaInfo> areas,
                Set<Integer> referencedIds)
        {
            public WarehouseAreasSnapshot
            {
                areas = areas == null ? List.of() : List.copyOf(areas);
                referencedIds = referencedIds == null ? Set.of() : Set.copyOf(referencedIds);
            }

            public static WarehouseAreasSnapshot empty()
            {
                return new WarehouseAreasSnapshot(List.of(), Set.of());
            }
        }

        /** 不可变高亮目标：某个项目的某个备货区 box。 */
        public record Highlight(@Nullable String schematicId, @Nullable String boxName)
        {
            public static final Highlight NONE = new Highlight(null, null);

            public boolean matches(@Nullable String schematicId, @Nullable String boxName)
            {
                return Objects.equals(this.schematicId, schematicId)
                        && Objects.equals(this.boxName, boxName);
            }
        }
    }

    private StagingAreaRenderer() {}

    public static StagingAreaRenderer getInstance()
    {
        return INSTANCE;
    }

    /** 当前会话的渲染状态；无会话返回 null（引擎不绘制、兼容 API 空操作）。 */
    @Nullable
    private static State currentState()
    {
        return ClientSessionManager.current()
                .map(ClientConnectionSession::rendering)
                .orElse(null);
    }

    // ===== 兼容委托：旧调用方继续读写当前会话状态 =====

    public void updateSelection(String schematicId, AreaSelection selection)
    {
        State state = currentState();
        if (state != null) state.updateSelection(schematicId, selection);
    }

    public void removeSelection(String schematicId)
    {
        State state = currentState();
        if (state != null) state.removeSelection(schematicId);
    }

    @Nullable
    public AreaSelection getSelection(String schematicId)
    {
        State state = currentState();
        return state == null ? null : state.selection(schematicId);
    }

    public void setRenderEnabled(String schematicId, boolean enabled)
    {
        State state = currentState();
        if (state != null) state.setRenderEnabled(schematicId, enabled);
    }

    public boolean isRenderEnabled(String schematicId)
    {
        State state = currentState();
        return state == null || state.isRenderEnabled(schematicId);
    }

    public void removeRenderData(String schematicId)
    {
        State state = currentState();
        if (state != null) state.removeRenderData(schematicId);
    }

    /** 切项目：只清其他项目的渲染数据，全局仓库快照保留。 */
    public void activateProject(String projectId)
    {
        State state = currentState();
        if (state != null) state.activateProject(projectId);
    }

    /** 只清指定项目的渲染数据，不关 global 仓库。 */
    public void clearProject(String projectId)
    {
        State state = currentState();
        if (state != null) state.clearProject(projectId);
    }

    /**
     * 清空当前会话的全部备货区线框状态（换服/断线用）。schematicId 是各服数据库
     * 自增值，跨服会撞号：不清会把上个服的备货区线框画进新服世界。
     */
    public void clearAllSelections()
    {
        State state = currentState();
        if (state != null) state.clearAllSelections();
    }

    public void setHighlightedBox(String schematicId, @Nullable String boxName)
    {
        State state = currentState();
        if (state != null) state.setHighlightedBox(schematicId, boxName);
    }

    public void clearHighlightedBox()
    {
        State state = currentState();
        if (state != null) state.clearHighlightedBox();
    }

    public void setSchematicName(String schematicId, String name)
    {
        State state = currentState();
        if (state != null) state.setSchematicName(schematicId, name);
    }

    @Nullable
    public String getSchematicName(String schematicId)
    {
        State state = currentState();
        return state == null ? null : state.schematicName(schematicId);
    }

    // ===== 兼容委托：仓库快照 =====

    public void updateWarehouseContainers(
            List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers, String worldId)
    {
        State state = currentState();
        if (state != null) state.updateWarehouseContainers(containers, worldId);
    }

    public void clearWarehouseContainers()
    {
        State state = currentState();
        if (state != null) state.clearWarehouseContainers();
    }

    /** 是否已收到过服务端下发的仓库容器数据（测试断言订阅链路真实回流） */
    public boolean hasWarehouseContainersLoaded()
    {
        State state = currentState();
        return state != null && state.hasWarehouseContainersLoaded();
    }

    public List<WarehouseContainerResponseS2CPacket.ContainerEntry> getWarehouseContainers()
    {
        State state = currentState();
        return state == null ? List.of() : state.warehouseContainers().entries();
    }

    public void updateWarehouseAreas(List<StagingAreaConfigResponseS2CPacket.AreaInfo> warehouses,
                                     List<Integer> referencedIds)
    {
        State state = currentState();
        if (state != null) state.updateWarehouseAreas(warehouses, referencedIds);
    }

    public void clearWarehouseAreas()
    {
        State state = currentState();
        if (state != null) state.clearWarehouseAreas();
    }

    public List<StagingAreaConfigResponseS2CPacket.AreaInfo> getWarehouseAreas()
    {
        State state = currentState();
        return state == null ? List.of() : state.warehouseAreas().areas();
    }

    public boolean isWarehouseReferenced(int warehouseId)
    {
        State state = currentState();
        return state != null && state.isWarehouseReferenced(warehouseId);
    }

    /**
     * 当前容器高亮色（渲染循环与配置持久化测试共用同一读取）。
     * 抽成方法是为了锁住「渲染颜色来自配置而非硬编码」——配置改色后
     * 渲染必须跟着变，测试断言这一点防止回退成硬编码。
     */
    public Color4f getContainerHighlightColor()
    {
        return net.syncmaterial.syncmaterial.client.config.Configs.Render.CONTAINER_HIGHLIGHT_COLOR.getColor();
    }

    /** Applies one accepted protocol-5 project configuration to the world projection. */
    public void applyProjectConfiguration(String projectId,
            java.util.List<net.syncmaterial.syncmaterial.core.AreaDefinition> areas) {
        State state = currentState();
        if (state == null || projectId == null || hasUnconfirmedEditorChanges(projectId)) return;
        AreaSelection selection = state.selection(projectId);
        if (selection == null) selection = new AreaSelection();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var area : areas) {
            names.add(area.name());
            var bounds = area.bounds();
            Box box = new Box(new BlockPos(bounds.x1(), bounds.y1(), bounds.z1()),
                    new BlockPos(bounds.x2(), bounds.y2(), bounds.z2()), area.name());
            box.setWorld(area.world());
            selection.addSubRegionBox(box, true);
            selection.setServerId(area.name(), area.id());
        }
        for (String name : java.util.List.copyOf(selection.getAllSubRegionNames())) {
            if (!names.contains(name)) {
                selection.removeSubRegionBox(name);
                selection.removeServerId(name);
            }
        }
        state.updateSelection(projectId, selection);
    }

    /** Authoritative refreshes must not erase coordinates that are still being edited. */
    private static boolean hasUnconfirmedEditorChanges(String projectId) {
        var screen = net.syncmaterial.syncmaterial.client.ClientScreenAccess.currentScreen(Minecraft.getInstance());
        if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal editor
                && projectId.equals(editor.getSchematicId())) {
            return switch (editor.getSaveState()) {
                case IDLE, SAVED -> false;
                default -> true;
            };
        }
        if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorSubRegion editor
                && projectId.equals(editor.getSchematicId())) {
            return switch (editor.getSaveState()) {
                case IDLE, SAVED -> false;
                default -> true;
            };
        }
        return false;
    }

    /** Applies global warehouse definitions without changing project references. */
    public void applyWarehouseDefinitions(java.util.List<net.syncmaterial.syncmaterial.core.AreaDefinition> areas) {
        State state = currentState();
        if (state == null) return;
        updateWarehouseAreas(areas.stream().map(area -> new StagingAreaConfigResponseS2CPacket.AreaInfo(
                area.id(), area.name(), area.bounds().x1(), area.bounds().y1(), area.bounds().z1(),
                area.bounds().x2(), area.bounds().y2(), area.bounds().z2(), area.world())).toList(),
                List.copyOf(state.warehouseAreas().referencedIds()));
    }

    /** Updates only this project's warehouse-reference highlight set. */
    public void applyWarehouseReferences(java.util.List<net.syncmaterial.syncmaterial.core.AreaDefinition> areas) {
        updateWarehouseAreas(getWarehouseAreas(), areas.stream().map(area -> area.id()).toList());
    }

    /**
     * 区域线框的维度过滤：已知维度且与玩家所在维度不同则跳过。
     * world 为空/null 表示旧服务端或尚未同步，不能据此隐藏线框。
     */
    public static boolean matchesWorld(@Nullable String areaWorld, @Nullable String playerWorld)
    {
        if (areaWorld == null || areaWorld.isBlank())
        {
            return true;
        }
        return playerWorld != null && areaWorld.equals(playerWorld);
    }

    /**
     * 取货指示器过滤本体：取货模式下只保留含需取物品的容器。
     *
     * 抽成纯函数的原因：这段逻辑此前埋在世界渲染循环里，端到端测试
     * 只能靠截图或读渲染状态间接验证；抽出后测试可直接对「容器列表 +
     * 需求集合 → 该亮的箱子」做精确断言。渲染与测试共用同一实现，
     * 不存在第二条被测路径。
     *
     * 边界语义（与重构前的内联逻辑逐一对齐，"取满即灭"依赖空集分支）：
     * - null：非取货模式，显示全部容器
     * - 空集：取货模式但所有需求已满足 → 全灭（重构时曾误改为返回全部，
     *   表现为取满后仓库所有箱子常亮，端到端测试抓住了该回归）
     * - 非空集：只保留含需取物品的容器
     *
     * @param neededItemIds 取货需求物品集合（PickupModeState.getNeededItemIds()）；
     *                      null 表示非取货模式
     */
    public static List<WarehouseContainerResponseS2CPacket.ContainerEntry> filterContainersForPickup(
            List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers,
            Set<String> neededItemIds)
    {
        if (containers == null)
        {
            return List.of();
        }
        if (neededItemIds == null)
        {
            return containers;
        }
        if (neededItemIds.isEmpty())
        {
            return List.of();
        }
        List<WarehouseContainerResponseS2CPacket.ContainerEntry> toRender = new java.util.ArrayList<>();
        for (WarehouseContainerResponseS2CPacket.ContainerEntry container : containers)
        {
            for (String itemId : container.itemIds())
            {
                if (neededItemIds.contains(itemId))
                {
                    toRender.add(container);
                    break;
                }
            }
        }
        return toRender;
    }

    /**
     * 当前连接的服务器标识，用于隔离不同服务器的仓库隐藏状态。
     * 仓库 ID 是服务端自增值，跨服会重复，必须带服务器前缀。
     */
    public static String getServerKey()
    {
        Minecraft mc = Minecraft.getInstance();
        var serverInfo = mc.getCurrentServer();
        if (serverInfo != null && serverInfo.ip != null && !serverInfo.ip.isBlank())
        {
            return serverInfo.ip;
        }
        return "singleplayer";
    }

    /**
     * 该仓库当前是否应当渲染：全局开关 + 单仓库开关同时成立。
     */
    public static boolean shouldRenderWarehouse(int warehouseId)
    {
        return net.syncmaterial.syncmaterial.client.config.Configs.Generic.WAREHOUSE_RENDER_ENABLED.getBooleanValue()
                && !net.syncmaterial.syncmaterial.client.config.Configs.isWarehouseHidden(getServerKey(), warehouseId);
    }

    //? if >=26.2 {
    @Override
    public void onRenderWorldLast(RenderTarget renderTarget, Matrix4fc posMatrix, CameraRenderState cameraState,
            Frustum frustum, RenderBuffers buffers, GpuBufferSlice slice, Vector4f vector4f, ProfilerFiller profiler)
    {
        renderWorld(profiler, RenderUtils::renderAreaSides,
                selector -> selector.onRenderWorld(this));
    }
    //?} else {
    /*@Override
    public void onRenderWorldLastAdvanced(RenderTarget renderTarget, Matrix4f posMatrix, Matrix4f projectionMatrix,
            Frustum frustum, Camera camera, RenderBuffers buffers, ProfilerFiller profiler)
    {
        renderWorld(profiler, (first, second, color) -> RenderUtils.renderAreaSides(first, second, color, posMatrix),
                selector -> selector.onRenderWorld(this, posMatrix));
    }
    *///?}

    private void renderWorld(ProfilerFiller profiler, AreaSidesPainter sides, PreviewPainter preview)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc == null || mc.player == null)
        {
            return;
        }

        // 引擎只读当前会话状态：无会话时不绘制任何线框
        ClientConnectionSession session = ClientSessionManager.current().orElse(null);
        if (session == null)
        {
            return;
        }
        State state = session.rendering();
        StagingAreaSelector selector = session.selector();

        profiler.push("syncmaterial_staging_areas");

        String playerWorldId = McCompat.dimensionId(mc.player.level());

        for (Map.Entry<String, AreaSelection> entry : state.selections.entrySet())
        {
            if (!state.isRenderEnabled(entry.getKey()))
            {
                continue;
            }
            AreaSelection selection = entry.getValue();
            for (Box box : selection.getAllSubRegionBoxes())
            {
                BlockPos pos1 = box.getPos1();
                BlockPos pos2 = box.getPos2();

                if (pos1 == null || pos2 == null)
                {
                    continue;
                }

                // 项目备货区按 AreaInfo.world 过滤：known 维度不一致就不画
                if (!matchesWorld(box.getWorld(), playerWorldId))
                {
                    continue;
                }

                // 正在被选区编辑：交由 StagingAreaSelector 按同一配色渲染，
                // 这里跳过以免同一区域出现两个重叠的框
                if (selector.isEditingStagingArea(entry.getKey(), box.getName()))
                {
                    continue;
                }

                boolean isHighlighted = state.highlight().matches(entry.getKey(), box.getName());
                Color4f lineColor = isHighlighted
                    ? net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_HIGHLIGHT_LINE_COLOR.getColor()
                    : net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_LINE_COLOR.getColor();
                Color4f sideColor = isHighlighted
                    ? net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_HIGHLIGHT_LINE_COLOR.getColor()
                    : net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_SIDE_COLOR.getColor();
                RenderUtils.renderAreaOutline(pos1, pos2, 2.0f, lineColor, lineColor, lineColor);
                sides.paint(pos1, pos2, sideColor);

                // 标注名称：原理图名称 - 备货区名称
                if (net.syncmaterial.syncmaterial.client.config.Configs.Render.LABEL_ENABLED.getBooleanValue()) {
                    String schematicName = state.schematicName(entry.getKey());
                    String label = schematicName == null || schematicName.isEmpty()
                        ? box.getName()
                        : schematicName + " - " + box.getName();
                    double cx = (pos1.getX() + pos2.getX()) / 2.0 + 0.5;
                    double cy = Math.max(pos1.getY(), pos2.getY()) + 0.5;
                    double cz = (pos1.getZ() + pos2.getZ()) / 2.0 + 0.5;
                    float labelScale = (float) net.syncmaterial.syncmaterial.client.config.Configs.Render.LABEL_SCALE.getDoubleValue();
                    drawLabel(label, cx, cy, cz, labelScale);
                }
            }
        }

        preview.paint(selector);

        // Phase 5: 仓库区域线框（global，独立于项目）
        renderWarehouseAreas(mc, sides, state, selector);

        // Phase 5: 仓库容器蓝色线框高亮
        renderWarehouseContainers(mc, state, playerWorldId);

        profiler.pop();
    }

    /**
     * 渲染仓库区域线框。
     *
     * 与备货区的区别：
     * - 仓库是全局资源，不按原理图分组，因此不复用 selections/AreaSelection
     * - 每个仓库自带 world 字段，需逐个做跨维度过滤（仓库可能分布在不同维度）
     * - 被当前原理图引用的仓库用高亮色区分
     */
    private void renderWarehouseAreas(Minecraft mc, AreaSidesPainter sides,
                                      State state, StagingAreaSelector selector)
    {
        if (!net.syncmaterial.syncmaterial.client.config.Configs.Generic.WAREHOUSE_RENDER_ENABLED.getBooleanValue())
        {
            return;
        }

        // 整个渲染过程使用同一代快照
        State.WarehouseAreasSnapshot snapshot = state.warehouseAreas();
        if (snapshot.areas().isEmpty() || mc.player == null)
        {
            return;
        }

        String playerWorldId = McCompat.dimensionId(mc.player.level());
        String serverKey = getServerKey();

        Color4f normalLine = net.syncmaterial.syncmaterial.client.config.Configs.Render.WAREHOUSE_LINE_COLOR.getColor();
        Color4f sideColor = net.syncmaterial.syncmaterial.client.config.Configs.Render.WAREHOUSE_SIDE_COLOR.getColor();
        Color4f referencedLine = net.syncmaterial.syncmaterial.client.config.Configs.Render.WAREHOUSE_REFERENCED_LINE_COLOR.getColor();
        boolean labelEnabled = net.syncmaterial.syncmaterial.client.config.Configs.Render.LABEL_ENABLED.getBooleanValue();
        float labelScale = (float) net.syncmaterial.syncmaterial.client.config.Configs.Render.LABEL_SCALE.getDoubleValue();

        for (var warehouse : snapshot.areas())
        {
            // 逐个维度过滤：仓库是全局的，可能位于其他维度
            if (!matchesWorld(warehouse.world(), playerWorldId))
            {
                continue;
            }

            // 单仓库开关：在仓库管理界面被单独隐藏
            if (net.syncmaterial.syncmaterial.client.config.Configs.isWarehouseHidden(serverKey, warehouse.areaId()))
            {
                continue;
            }

            // 正在被选区编辑：交由 StagingAreaSelector 按同一配色渲染，
            // 这里跳过以免同一区域出现两个重叠的框
            if (selector.isEditingWarehouse(warehouse.areaId()))
            {
                continue;
            }

            BlockPos pos1 = new BlockPos(warehouse.x1(), warehouse.y1(), warehouse.z1());
            BlockPos pos2 = new BlockPos(warehouse.x2(), warehouse.y2(), warehouse.z2());

            boolean referenced = snapshot.referencedIds().contains(warehouse.areaId());
            Color4f lineColor = referenced ? referencedLine : normalLine;

            RenderUtils.renderAreaOutline(pos1, pos2, 2.0f, lineColor, lineColor, lineColor);
            sides.paint(pos1, pos2, sideColor);

            if (labelEnabled)
            {
                double cx = (pos1.getX() + pos2.getX()) / 2.0 + 0.5;
                double cy = Math.max(pos1.getY(), pos2.getY()) + 0.5;
                double cz = (pos1.getZ() + pos2.getZ()) / 2.0 + 0.5;
                drawLabel(warehouse.name(), cx, cy, cz, labelScale);
            }
        }
    }

    private void renderWarehouseContainers(Minecraft mc, State state, String playerWorldId)
    {
        // 取局部快照：整个渲染过程用同一份列表，避免中途被网络线程替换引用
        State.WarehouseContainersSnapshot snapshot = state.warehouseContainers();
        net.minecraft.world.level.Level level = mc.level;
        if (snapshot.entries().isEmpty() || mc.player == null || level == null)
        {
            return;
        }

        // 跨世界过滤：只渲染玩家所在世界的仓库容器
        if (!playerWorldId.equals(snapshot.world()))
        {
            return;
        }

        // 取货模式下只显示包含需要材料的箱子（过滤本体在
        // filterContainersForPickup，与测试共用同一实现）
        Set<String> neededItemIds = GuiMaterialList.isPickupModeStatic()
            ? net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().getNeededItemIds()
            : null;

        // 1. 过滤出需要渲染的容器
        List<WarehouseContainerResponseS2CPacket.ContainerEntry> toRender =
            filterContainersForPickup(snapshot.entries(), neededItemIds);

        // 2. 渲染容器线框（大箱子用 CHEST_TYPE 属性检测，渲染覆盖整个双箱区域）
        Color4f containerColor = this.getContainerHighlightColor();

        // 去重：大箱子左右半箱都有 container_inventory 记录，只渲染一次
        Set<BlockPos> renderedPositions = new java.util.HashSet<>();

        for (WarehouseContainerResponseS2CPacket.ContainerEntry container : toRender) {
            int px = container.posX(), py = container.posY(), pz = container.posZ();
            BlockPos pos = new BlockPos(px, py, pz);
            if (renderedPositions.contains(pos)) continue;

            BlockPos pos1 = pos, pos2 = pos;

            // 检测大箱子：读取方块状态的 CHEST_TYPE 属性
            net.minecraft.world.level.block.state.BlockState blockState = level.getBlockState(pos);
            if (blockState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
                net.minecraft.world.level.block.state.properties.EnumProperty<net.minecraft.world.level.block.state.properties.ChestType> chestTypeProp =
                    net.minecraft.world.level.block.ChestBlock.TYPE;
                if (blockState.hasProperty(chestTypeProp)) {
                    net.minecraft.world.level.block.state.properties.ChestType chestType = blockState.getValue(chestTypeProp);
                    if (chestType != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                        // 大箱子：用 getConnectedDirection 获取配对方向，扩展到配对坐标
                        net.minecraft.core.Direction facing = net.minecraft.world.level.block.ChestBlock.getConnectedDirection(blockState);
                        BlockPos pairedPos = pos.relative(facing);
                        pos1 = new BlockPos(
                            Math.min(px, pairedPos.getX()),
                            Math.min(py, pairedPos.getY()),
                            Math.min(pz, pairedPos.getZ()));
                        pos2 = new BlockPos(
                            Math.max(px, pairedPos.getX()),
                            Math.max(py, pairedPos.getY()),
                            Math.max(pz, pairedPos.getZ()));
                    }
                }
            }

            // 标记已渲染的位置（大箱子标记两个位置）
            renderedPositions.add(pos1);
            if (!pos1.equals(pos2)) renderedPositions.add(pos2);
            RenderUtils.renderAreaOutline(pos1, pos2, 3.0f, containerColor, containerColor, containerColor);
        }
    }

    private static void drawLabel(String label, double x, double y, double z, float scale)
    {
        //? if >=26.2 {
        RenderUtils.drawTextPlate(Collections.singletonList(label), x, y, z, scale, 1.0f);
        //?} else {
        /*RenderUtils.drawTextPlate(Collections.singletonList(label), x, y, z, scale);
        *///?}
    }

    @FunctionalInterface
    private interface AreaSidesPainter
    {
        void paint(BlockPos first, BlockPos second, Color4f color);
    }

    @FunctionalInterface
    private interface PreviewPainter
    {
        void paint(StagingAreaSelector selector);
    }
}
