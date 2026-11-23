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
        private final Map<String, ProjectAreasSnapshot> projectAreas = new ConcurrentHashMap<>();
        private final Map<String, Boolean> renderEnabled = new ConcurrentHashMap<>();
        private final Map<String, String> schematicNames = new ConcurrentHashMap<>();
        private volatile Highlight highlight = Highlight.NONE;
        private volatile EditorPreview editorPreview = EditorPreview.NONE;
        private volatile WarehouseContainersSnapshot warehouseContainers = WarehouseContainersSnapshot.empty();
        private final Map<String, WarehouseContainersSnapshot> projectContainers = new ConcurrentHashMap<>();
        private final Map<String, Set<Integer>> projectWarehouseReferences = new ConcurrentHashMap<>();
        private volatile WarehouseAreasSnapshot warehouseAreas = WarehouseAreasSnapshot.empty();

        public State() {}

        // ===== 项目备货区（权威投影） =====

        /**
         * 一次接受一份协议 5 权威项目配置：不可变快照整体替换，sequence 与
         * view 身份同代。它绝不触碰 {@link #selections} 里的编辑工作副本 ——
         * 权威刷新与本地编辑从此是两份数据。
         */
        public void applyProjectAreas(String projectId, List<ProjectArea> areas,
                long configSequence, @Nullable Object viewIdentity)
        {
            if (projectId == null) return;
            this.projectAreas.put(projectId,
                    new ProjectAreasSnapshot(projectId, configSequence, viewIdentity, areas));
        }

        @Nullable
        public ProjectAreasSnapshot projectAreas(String projectId)
        {
            return projectId == null ? null : this.projectAreas.get(projectId);
        }

        /** 当前全部权威项目投影；渲染从这些只读值绘制。 */
        public List<ProjectAreasSnapshot> allProjectAreas()
        {
            return List.copyOf(this.projectAreas.values());
        }

        /**
         * 登记编辑器的本地工作副本作为叠加预览。工作副本不是权威事实：
         * 协议 5 权威线框永远来自 {@link #projectAreas}；编辑器打开期间该项目的
         * 线框由工作副本取代绘制（工作副本按目标合并了最新权威值）。
         */
        public void setEditorPreview(@Nullable String projectId, @Nullable AreaSelection workingCopy)
        {
            if (projectId == null || workingCopy == null)
            {
                this.editorPreview = EditorPreview.NONE;
            }
            else
            {
                this.editorPreview = new EditorPreview(projectId, workingCopy);
            }
        }

        public EditorPreview editorPreview()
        {
            return this.editorPreview;
        }

        // ===== 项目备货区（legacy 兼容工作副本，仅供编辑器/旧协议读写） =====

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

        /** 删除一个项目的全部渲染数据；不影响其他项目，也不影响 global 仓库。 */
        public void removeRenderData(String schematicId)
        {
            if (schematicId == null) return;
            this.selections.remove(schematicId);
            this.projectAreas.remove(schematicId);
            this.projectContainers.remove(schematicId);
            this.projectWarehouseReferences.remove(schematicId);
            this.renderEnabled.remove(schematicId);
            this.schematicNames.remove(schematicId);
            Highlight current = this.highlight;
            if (schematicId.equals(current.schematicId()))
            {
                this.highlight = Highlight.NONE;
            }
            EditorPreview preview = this.editorPreview;
            if (schematicId.equals(preview.projectId()))
            {
                this.editorPreview = EditorPreview.NONE;
            }
        }

        /** Drops old-view authority without resetting user display choices or editor drafts. */
        public void invalidateProjectProjection(String projectId) {
            if (projectId == null) return;
            this.projectAreas.remove(projectId);
            this.projectContainers.remove(projectId);
            this.projectWarehouseReferences.remove(projectId);
        }

        /**
         * 切到某个项目：只清掉其他项目的备货区数据（含按项目归属的容器/引用），
         * global 仓库定义与 legacy 全局容器快照原样保留。
         * 这样项目之间切换不会把上个项目的备货区线框带进新项目。
         */
        public void activateProject(String projectId)
        {
            if (projectId == null) return;
            for (String key : List.copyOf(this.selections.keySet()))
            {
                if (!projectId.equals(key)) this.selections.remove(key);
            }
            for (String key : List.copyOf(this.projectAreas.keySet()))
            {
                if (!projectId.equals(key)) this.projectAreas.remove(key);
            }
            for (String key : List.copyOf(this.projectContainers.keySet()))
            {
                if (!projectId.equals(key)) this.projectContainers.remove(key);
            }
            for (String key : List.copyOf(this.projectWarehouseReferences.keySet()))
            {
                if (!projectId.equals(key)) this.projectWarehouseReferences.remove(key);
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
            EditorPreview preview = this.editorPreview;
            if (preview.projectId() != null && !projectId.equals(preview.projectId()))
            {
                this.editorPreview = EditorPreview.NONE;
            }
        }

        /** 只清一个项目的渲染状态（例如项目切换的旧项目），不触碰 global 仓库。 */
        public void clearProject(String projectId)
        {
            this.removeRenderData(projectId);
        }

        /** 清空全部备货区线框（换服/断线用），不影响 global 仓库定义快照。 */
        public void clearAllSelections()
        {
            this.selections.clear();
            this.projectAreas.clear();
            this.projectContainers.clear();
            this.projectWarehouseReferences.clear();
            this.renderEnabled.clear();
            this.schematicNames.clear();
            this.highlight = Highlight.NONE;
            this.editorPreview = EditorPreview.NONE;
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

        // ===== 仓库容器/引用（协议 5 按项目归属） =====

        /**
         * 协议 5：写入某个项目视图的仓库容器快照（整体替换，world 与 entries 同代）。
         * 与 legacy 全局快照相互独立；有项目快照时渲染不再读 legacy 全局快照，
         * 旧包不能覆盖已接受的协议 5 投影。
         */
        public void updateProjectWarehouseContainers(String projectId,
                List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers, @Nullable String worldId)
        {
            if (projectId == null) return;
            this.projectContainers.put(projectId,
                    new WarehouseContainersSnapshot(containers, worldId, true));
        }

        @Nullable
        public WarehouseContainersSnapshot projectWarehouseContainers(String projectId)
        {
            return projectId == null ? null : this.projectContainers.get(projectId);
        }

        /** 只清一个项目的容器快照；不影响其他项目，也不影响 legacy 全局快照。 */
        public void clearProjectWarehouseContainers(String projectId)
        {
            if (projectId == null) return;
            this.projectContainers.remove(projectId);
        }

        /** 是否存在任何按项目归属的容器快照（存在则渲染走协议 5 数据）。 */
        public boolean hasProjectWarehouseContainers()
        {
            return !this.projectContainers.isEmpty();
        }

        /** 当前全部按项目归属的容器快照；渲染从这些只读值绘制。 */
        public List<WarehouseContainersSnapshot> allProjectContainers()
        {
            return List.copyOf(this.projectContainers.values());
        }

        /** 协议 5：记录某项目的仓库引用集合（线框"被引用"高亮），整体替换。 */
        public void applyProjectWarehouseReferences(String projectId, List<Integer> referencedIds)
        {
            if (projectId == null) return;
            this.projectWarehouseReferences.put(projectId,
                    referencedIds == null ? Set.of() : Set.copyOf(referencedIds));
        }

        /** 只清一个项目的仓库引用；不影响其他项目，也不影响 global 仓库定义。 */
        public void clearProjectWarehouseReferences(String projectId)
        {
            if (projectId == null) return;
            this.projectWarehouseReferences.remove(projectId);
        }

        /**
         * 渲染用引用并集：legacy 全局集合 + 各项目集合。activateProject 会清掉
         * 非当前项目的引用，因此并集在实践中等价于"当前项目的引用"。
         */
        public Set<Integer> warehouseReferences()
        {
            Set<Integer> union = new java.util.HashSet<>(this.warehouseAreas.referencedIds());
            for (Set<Integer> refs : this.projectWarehouseReferences.values())
            {
                union.addAll(refs);
            }
            return union;
        }

        public boolean hasWarehouseContainersLoaded()
        {
            return this.warehouseContainers.loaded() || this.projectContainers.values().stream()
                    .anyMatch(WarehouseContainersSnapshot::loaded);
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
            return this.warehouseReferences().contains(warehouseId);
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

        /**
         * 一条权威项目区域的只读投影值。只含渲染所需最小字段：
         * area ID（协议 5 真实主键）、名称、维度与边界；坐标来自服务端 AreaDefinition。
         */
        public record ProjectArea(int areaId, String name, @Nullable String world,
                net.syncmaterial.syncmaterial.core.AreaBounds bounds)
        {
            public ProjectArea
            {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(bounds, "bounds");
            }
        }

        /**
         * 一个项目的权威区域投影，整体替换：sequence/view 身份与 areas 永远同代。
         * {@code configSequence} 是接受的视图配置序号；兼容入口可从区域 revision 推导；
         * {@code viewIdentity} 是对应的协议 5 ViewToken，旧协议可为 null。
         */
        public record ProjectAreasSnapshot(
                String projectId,
                long configSequence,
                @Nullable Object viewIdentity,
                List<ProjectArea> areas)
        {
            public ProjectAreasSnapshot
            {
                Objects.requireNonNull(projectId, "projectId");
                areas = areas == null ? List.of() : List.copyOf(areas);
            }

            public static ProjectAreasSnapshot empty(String projectId)
            {
                return new ProjectAreasSnapshot(projectId, 0L, null, List.of());
            }
        }

        /**
         * 编辑器的本地工作副本叠加预览。它是编辑/选区的即时反馈来源，
         * 绝不作为权威渲染事实 —— 协议 5 权威线框只来自 {@link ProjectAreasSnapshot}。
         */
        public record EditorPreview(@Nullable String projectId, @Nullable AreaSelection workingCopy)
        {
            public static final EditorPreview NONE = new EditorPreview(null, null);

            public boolean matches(@Nullable String projectId)
            {
                return projectId != null && projectId.equals(this.projectId()) && this.workingCopy != null;
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

    /**
     * legacy 兼容入口（协议 1—4 初始投影与编辑器工作副本登记）。
     * 协议 5 的权威投影走 {@link #applyProjectConfiguration}；渲染不再从这里取权威值。
     */
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

    /** legacy 全局容器快照（协议 1—4）；协议 5 用 {@link #updateWarehouseContainers(String, List, String)}。 */
    public void updateWarehouseContainers(
            List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers, String worldId)
    {
        State state = currentState();
        if (state != null) state.updateWarehouseContainers(containers, worldId);
    }

    /**
     * 协议 5：按项目写入仓库容器快照。项目删除只清该项目（{@link #clearWarehouseContainers(String)}），
     * 不得当全局删除。
     */
    public void updateWarehouseContainers(String projectId,
            List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers, String worldId)
    {
        State state = currentState();
        if (state != null) state.updateProjectWarehouseContainers(projectId, containers, worldId);
    }

    /** legacy 全局清理（换维度/断线用）；项目级清理走 {@link #clearWarehouseContainers(String)}。 */
    public void clearWarehouseContainers()
    {
        State state = currentState();
        if (state != null) state.clearWarehouseContainers();
    }

    /** 只清一个项目的容器快照：不影响其他项目，也不影响 legacy 全局快照。 */
    public void clearWarehouseContainers(String projectId)
    {
        State state = currentState();
        if (state != null) state.clearProjectWarehouseContainers(projectId);
    }

    @Nullable
    public State.WarehouseContainersSnapshot getProjectWarehouseContainers(String projectId)
    {
        State state = currentState();
        return state == null ? null : state.projectWarehouseContainers(projectId);
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
        if (state == null) return List.of();
        if (state.hasProjectWarehouseContainers()) {
            return state.allProjectContainers().stream()
                    .flatMap(snapshot -> snapshot.entries().stream()).toList();
        }
        return state.warehouseContainers().entries();
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

    /** Drops one old view generation while retaining editor preview and display preferences. */
    public void invalidateProjectProjection(String projectId) {
        State state = currentState();
        if (state != null) state.invalidateProjectProjection(projectId);
    }

    /** Applies one accepted protocol-5 project configuration to the world projection. */
    public void applyProjectConfiguration(String projectId,
            java.util.List<net.syncmaterial.syncmaterial.core.AreaDefinition> areas) {
        applyProjectConfiguration(projectId, areas, null);
    }

    /**
     * 同上，主线程可附带协议 5 视图令牌（投影与视图身份同代保存）。
     *
     * <p>权威配置只写入不可变 {@link State.ProjectAreasSnapshot}，绝不覆写编辑器
     * 正在使用的可变 AreaSelection/Box 工作副本；正在编辑的输入由编辑器按目标
     * 合并保护，这里不再检查当前 screen 的保存状态。</p>
     */
    public void applyProjectConfiguration(String projectId,
            java.util.List<net.syncmaterial.syncmaterial.core.AreaDefinition> areas,
            @Nullable Object viewIdentity) {
        long sequence = 0;
        for (var area : areas) sequence = Math.max(sequence, area.revision());
        applyProjectConfiguration(projectId, areas, sequence, viewIdentity);
    }

    public void applyProjectConfiguration(String projectId,
            java.util.List<net.syncmaterial.syncmaterial.core.AreaDefinition> areas,
            long configurationSequence, @Nullable Object viewIdentity) {
        State state = currentState();
        if (state == null || projectId == null) return;
        java.util.List<State.ProjectArea> projected = new java.util.ArrayList<>(areas.size());
        for (var area : areas) {
            projected.add(new State.ProjectArea(area.id(), area.name(), area.world(), area.bounds()));
        }
        state.applyProjectAreas(projectId, projected, configurationSequence, viewIdentity);
    }

    /** 编辑器本地工作副本叠加预览；见 {@link State#setEditorPreview}。 */
    public void setEditorPreview(@Nullable String projectId, @Nullable AreaSelection workingCopy) {
        State state = currentState();
        if (state != null) state.setEditorPreview(projectId, workingCopy);
    }

    @Nullable
    public State.EditorPreview getEditorPreview() {
        State state = currentState();
        return state == null ? State.EditorPreview.NONE : state.editorPreview();
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
        applyWarehouseReferences(null, areas);
    }

    /**
     * 协议 5：按项目记录仓库引用集合（被引用高亮）；projectId 为 null 时退回 legacy 全局集合。
     * 删除项目用 {@link #clearWarehouseReferences(String)}，不得清 global 仓库定义。
     */
    public void applyWarehouseReferences(@Nullable String projectId,
            java.util.List<net.syncmaterial.syncmaterial.core.AreaDefinition> areas) {
        State state = currentState();
        if (state == null) return;
        List<Integer> ids = areas.stream().map(area -> area.id()).toList();
        if (projectId == null) {
            updateWarehouseAreas(getWarehouseAreas(), ids);
        } else {
            state.applyProjectWarehouseReferences(projectId, ids);
        }
    }

    /** 只清一个项目的仓库引用；不影响其他项目与 global 仓库定义。 */
    public void clearWarehouseReferences(String projectId) {
        State state = currentState();
        if (state != null) state.clearProjectWarehouseReferences(projectId);
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

        // ===== 权威投影（协议 5）：从只读快照绘制 =====
        State.EditorPreview previewOverlay = state.editorPreview();
        for (State.ProjectAreasSnapshot snapshot : state.allProjectAreas())
        {
            if (!state.isRenderEnabled(snapshot.projectId()))
            {
                continue;
            }
            for (State.ProjectArea area : snapshot.areas())
            {
                // 编辑器工作副本预览按名称接管该项目里它正持有的框；
                // 其余框继续按权威值绘制，死预览不会遮住权威更新
                if (previewHasBox(previewOverlay, snapshot.projectId(), area.name()))
                {
                    continue;
                }
                if (!matchesWorld(area.world(), playerWorldId))
                {
                    continue;
                }
                if (selector.isEditingStagingArea(snapshot.projectId(), area.name()))
                {
                    continue;
                }
                BlockPos pos1 = new BlockPos(area.bounds().x1(), area.bounds().y1(), area.bounds().z1());
                BlockPos pos2 = new BlockPos(area.bounds().x2(), area.bounds().y2(), area.bounds().z2());
                boolean isHighlighted = state.highlight().matches(snapshot.projectId(), area.name());
                drawProjectBox(sides, pos1, pos2, isHighlighted, snapshot.projectId(), area.name(), state);
            }
        }

        // ===== 编辑器工作副本叠加预览（本地即时反馈，非权威事实） =====
        if (previewOverlay.projectId() != null && previewOverlay.workingCopy() != null
                && state.isRenderEnabled(previewOverlay.projectId()))
        {
            String previewProjectId = previewOverlay.projectId();
            for (Box box : previewOverlay.workingCopy().getAllSubRegionBoxes())
            {
                BlockPos pos1 = box.getPos1();
                BlockPos pos2 = box.getPos2();
                if (pos1 == null || pos2 == null) continue;
                if (!matchesWorld(box.getWorld(), playerWorldId)) continue;
                if (selector.isEditingStagingArea(previewProjectId, box.getName())) continue;
                boolean isHighlighted = state.highlight().matches(previewProjectId, box.getName());
                drawProjectBox(sides, pos1, pos2, isHighlighted, previewProjectId, box.getName(), state);
            }
        }

        // ===== legacy 兼容初始入口（协议 1—4）：只画没有权威投影的项目 =====
        for (Map.Entry<String, AreaSelection> entry : state.selections.entrySet())
        {
            if (state.projectAreas(entry.getKey()) != null)
            {
                continue;
            }
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
                drawProjectBox(sides, pos1, pos2, isHighlighted, entry.getKey(), box.getName(), state);
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
    /** 编辑器预览是否接管了该项目的这个名称：接管则权威/legacy 版本跳过。 */
    private static boolean previewHasBox(State.EditorPreview preview, @Nullable String projectId, String boxName)
    {
        return preview.matches(projectId) && preview.workingCopy().getSubRegionBox(boxName) != null;
    }

    /** 项目备货区线框 + 名称标注：权威投影、编辑预览与 legacy 三条路径共用同一绘制。 */
    private void drawProjectBox(AreaSidesPainter sides, BlockPos pos1, BlockPos pos2,
                                boolean isHighlighted, String projectId, String boxName, State state)
    {
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
            String schematicName = state.schematicName(projectId);
            String label = schematicName == null || schematicName.isEmpty()
                ? boxName
                : schematicName + " - " + boxName;
            double cx = (pos1.getX() + pos2.getX()) / 2.0 + 0.5;
            double cy = Math.max(pos1.getY(), pos2.getY()) + 0.5;
            double cz = (pos1.getZ() + pos2.getZ()) / 2.0 + 0.5;
            float labelScale = (float) net.syncmaterial.syncmaterial.client.config.Configs.Render.LABEL_SCALE.getDoubleValue();
            drawLabel(label, cx, cy, cz, labelScale);
        }
    }

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

            boolean referenced = state.warehouseReferences().contains(warehouse.areaId());
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
        net.minecraft.world.level.Level level = mc.level;
        if (mc.player == null || level == null)
        {
            return;
        }

        // 有协议 5 按项目归属的容器快照时只读它们；否则退回 legacy 全局快照，
        // 旧包不能覆盖已接受的协议 5 投影
        List<State.WarehouseContainersSnapshot> snapshots = state.hasProjectWarehouseContainers()
            ? state.allProjectContainers()
            : List.of(state.warehouseContainers());
        if (snapshots.stream().allMatch(snapshot -> snapshot.entries().isEmpty()))
        {
            return;
        }

        // 取货模式下只显示包含需要材料的箱子（过滤本体在
        // filterContainersForPickup，与测试共用同一实现）
        Set<String> neededItemIds = GuiMaterialList.isPickupModeStatic()
            ? net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().getNeededItemIds()
            : null;

        // 2. 渲染容器线框（大箱子用 CHEST_TYPE 属性检测，渲染覆盖整个双箱区域）
        Color4f containerColor = this.getContainerHighlightColor();

        // 去重：大箱子左右半箱都有 container_inventory 记录，只渲染一次
        Set<BlockPos> renderedPositions = new java.util.HashSet<>();

        for (State.WarehouseContainersSnapshot snapshot : snapshots)
        {
            // 跨世界过滤：只渲染玩家所在世界的仓库容器
            if (snapshot.entries().isEmpty() || !playerWorldId.equals(snapshot.world()))
            {
                continue;
            }

            // 1. 过滤出需要渲染的容器
            List<WarehouseContainerResponseS2CPacket.ContainerEntry> toRender =
                filterContainersForPickup(snapshot.entries(), neededItemIds);

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
