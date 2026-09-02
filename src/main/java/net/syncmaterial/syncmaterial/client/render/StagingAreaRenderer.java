package net.syncmaterial.syncmaterial.client.render;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;

import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;

import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;

public class StagingAreaRenderer implements IRenderer
{
    private static final StagingAreaRenderer INSTANCE = new StagingAreaRenderer();

    private final Map<String, AreaSelection> selections = new ConcurrentHashMap<>();
    private final Map<String, Boolean> renderEnabled = new ConcurrentHashMap<>();
    private final Map<String, String> schematicNames = new ConcurrentHashMap<>();
    // Phase 5: 仓库容器缓存（从服务端推送的 container_inventory 数据）
    // volatile + 整体替换：渲染线程遍历期间网络线程不会改动同一个 List
    // （与本类 selections 等并发容器一致的原子替换模式，避免 clear+addAll 的窗口期与迭代器竞态）
    private volatile java.util.List<WarehouseContainerResponseS2CPacket.ContainerEntry> warehouseContainers = java.util.List.of();
    private volatile String warehouseContainersWorld = "";
    /** 收到过容器数据响应的标志（空仓库时容器列表为空，需独立标志区分"从未收到"） */
    private volatile boolean warehouseContainersLoaded = false;
    // Phase 5: 仓库区域线框数据（服务端全局广播，不隶属任何原理图）
    private volatile java.util.List<StagingAreaConfigResponseS2CPacket.AreaInfo> warehouseAreas = java.util.List.of();
    private volatile java.util.Set<Integer> referencedWarehouseIds = java.util.Set.of();
    // 编辑器打开时，标记当前选中的 box 名称（仅用于视觉高亮）
    @Nullable private String highlightedSchematicId;
    @Nullable private String highlightedBoxName;

    private StagingAreaRenderer() {}

    public static StagingAreaRenderer getInstance()
    {
        return INSTANCE;
    }

    public void updateSelection(String schematicId, AreaSelection selection)
    {
        this.selections.put(schematicId, selection);
    }

    public void removeSelection(String schematicId)
    {
        this.selections.remove(schematicId);
    }

    @Nullable
    public AreaSelection getSelection(String schematicId)
    {
        return this.selections.get(schematicId);
    }

    public void setRenderEnabled(String schematicId, boolean enabled)
    {
        this.renderEnabled.put(schematicId, enabled);
    }

    public boolean isRenderEnabled(String schematicId)
    {
        return this.renderEnabled.getOrDefault(schematicId, true);
    }

    public void removeRenderData(String schematicId)
    {
        this.selections.remove(schematicId);
        this.renderEnabled.remove(schematicId);
        this.schematicNames.remove(schematicId);
        if (schematicId.equals(this.highlightedSchematicId))
        {
            this.highlightedSchematicId = null;
            this.highlightedBoxName = null;
        }
    }

    public void setHighlightedBox(String schematicId, @Nullable String boxName)
    {
        this.highlightedSchematicId = schematicId;
        this.highlightedBoxName = boxName;
    }

    public void clearHighlightedBox()
    {
        this.highlightedSchematicId = null;
        this.highlightedBoxName = null;
    }

    public void setSchematicName(String schematicId, String name)
    {
        this.schematicNames.put(schematicId, name);
    }

    @Nullable
    public String getSchematicName(String schematicId)
    {
        return this.schematicNames.get(schematicId);
    }

    // Phase 5: 仓库容器缓存管理

    /**
     * 更新仓库容器数据（从 WarehouseContainerResponseS2CPacket 接收）
     */
    public void updateWarehouseContainers(java.util.List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers, String worldId)
    {
        // 整体替换而非原地改写：读线程要么看到旧列表要么看到新列表，不会看到中间状态
        this.warehouseContainers = containers != null ? java.util.List.copyOf(containers) : java.util.List.of();
        this.warehouseContainersWorld = worldId != null ? worldId : "";
        this.warehouseContainersLoaded = true;
    }

    public void clearWarehouseContainers()
    {
        this.warehouseContainers = java.util.List.of();
        this.warehouseContainersWorld = "";
        this.warehouseContainersLoaded = false;
    }

    /** 是否已收到过服务端下发的仓库容器数据（测试断言订阅链路真实回流） */
    public boolean hasWarehouseContainersLoaded()
    {
        return this.warehouseContainersLoaded;
    }

    public java.util.List<WarehouseContainerResponseS2CPacket.ContainerEntry> getWarehouseContainers()
    {
        return this.warehouseContainers;
    }

    /**
     * 取货指示器过滤本体：取货模式下只保留含需取物品的容器。
     *
     * 抽成纯函数的原因：这段逻辑此前埋在世界渲染循环里，端到端测试
     * 只能靠截图或读渲染状态间接验证；抽出后测试可直接对「容器列表 +
     * 需求集合 → 该亮的箱子」做精确断言。渲染与测试共用同一实现，
     * 不存在第二条被测路径。
     *
     * @param neededItemIds 取货需求物品集合（PickupModeState.getNeededItemIds()）；
     *                      null 或空集表示非取货模式，返回全部容器
     */
    public static java.util.List<WarehouseContainerResponseS2CPacket.ContainerEntry> filterContainersForPickup(
            java.util.List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers,
            java.util.Set<String> neededItemIds)
    {
        if (neededItemIds == null || neededItemIds.isEmpty() || containers == null)
        {
            return containers == null ? java.util.List.of() : containers;
        }
        java.util.List<WarehouseContainerResponseS2CPacket.ContainerEntry> toRender = new java.util.ArrayList<>();
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

    // Phase 5: 仓库区域线框数据管理

    /**
     * 更新仓库区域数据（从 WarehouseAreaResponseS2CPacket 接收）
     */
    public void updateWarehouseAreas(java.util.List<StagingAreaConfigResponseS2CPacket.AreaInfo> warehouses,
                                     java.util.List<Integer> referencedIds)
    {
        this.warehouseAreas = warehouses != null ? java.util.List.copyOf(warehouses) : java.util.List.of();
        this.referencedWarehouseIds = referencedIds != null
                ? java.util.Set.copyOf(referencedIds) : java.util.Set.of();
    }

    public void clearWarehouseAreas()
    {
        this.warehouseAreas = java.util.List.of();
        this.referencedWarehouseIds = java.util.Set.of();
    }

    public java.util.List<StagingAreaConfigResponseS2CPacket.AreaInfo> getWarehouseAreas()
    {
        return this.warehouseAreas;
    }

    public boolean isWarehouseReferenced(int warehouseId)
    {
        return this.referencedWarehouseIds.contains(warehouseId);
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

    /**
     * 26.2 渲染入口：IRenderer 接口改为 state 化签名（矩阵/相机状态/渲染缓冲均由调用方提供），
     * renderAreaSides 也不再需要显式传入位置矩阵。
     */
    @Override
    public void onRenderWorldLast(RenderTarget renderTarget, Matrix4fc posMatrix, CameraRenderState cameraState,
            Frustum frustum, RenderBuffers buffers, GpuBufferSlice slice, Vector4f vector4f, ProfilerFiller profiler)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null)
        {
            return;
        }

        profiler.push("syncmaterial_staging_areas");

        for (Map.Entry<String, AreaSelection> entry : this.selections.entrySet())
        {
            if (!isRenderEnabled(entry.getKey()))
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

                // 正在被选区编辑：交由 StagingAreaSelector 按同一配色渲染，
                // 这里跳过以免同一区域出现两个重叠的框
                if (StagingAreaSelector.getInstance().isEditingStagingArea(entry.getKey(), box.getName()))
                {
                    continue;
                }

                boolean isHighlighted = java.util.Objects.equals(entry.getKey(), this.highlightedSchematicId)
                    && java.util.Objects.equals(box.getName(), this.highlightedBoxName);
                Color4f lineColor = isHighlighted
                    ? net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_HIGHLIGHT_LINE_COLOR.getColor()
                    : net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_LINE_COLOR.getColor();
                Color4f sideColor = isHighlighted
                    ? net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_HIGHLIGHT_LINE_COLOR.getColor()
                    : net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_SIDE_COLOR.getColor();
                RenderUtils.renderAreaOutline(pos1, pos2, 2.0f, lineColor, lineColor, lineColor);
                RenderUtils.renderAreaSides(pos1, pos2, sideColor);

                // 标注名称：原理图名称 - 备货区名称
                if (net.syncmaterial.syncmaterial.client.config.Configs.Render.LABEL_ENABLED.getBooleanValue()) {
                    String schematicName = this.schematicNames.getOrDefault(entry.getKey(), "");
                    String label = schematicName.isEmpty()
                        ? box.getName()
                        : schematicName + " - " + box.getName();
                    double cx = (pos1.getX() + pos2.getX()) / 2.0 + 0.5;
                    double cy = Math.max(pos1.getY(), pos2.getY()) + 0.5;
                    double cz = (pos1.getZ() + pos2.getZ()) / 2.0 + 0.5;
                    float labelScale = (float) net.syncmaterial.syncmaterial.client.config.Configs.Render.LABEL_SCALE.getDoubleValue();
                    // 26.2 的 drawTextPlate 需要显式 partialTicks（用于相机实体朝向插值），此处取 1.0 即最终朝向
                    RenderUtils.drawTextPlate(Collections.singletonList(label), cx, cy, cz, labelScale, 1.0f);
                }
            }
        }

        StagingAreaSelector.getInstance().onRenderWorld(this);

        // Phase 5: 仓库区域线框
        this.renderWarehouseAreas(mc);

        // Phase 5: 仓库容器蓝色线框高亮
        // 取局部快照：整个渲染过程用同一份列表，避免中途被网络线程替换引用
        var containersSnapshot = this.warehouseContainers;
        if (!containersSnapshot.isEmpty() && mc.player != null) {
            String playerWorldId = mc.player.level().dimension().identifier().toString();
            // 跨世界过滤：只渲染玩家所在世界的仓库容器
            if (playerWorldId.equals(this.warehouseContainersWorld)) {
                // 取货模式下只显示包含需要材料的箱子（过滤本体在
                // filterContainersForPickup，与测试共用同一实现）
                java.util.Set<String> neededItemIds = GuiMaterialList.isPickupModeStatic()
                    ? net.syncmaterial.syncmaterial.client.PickupModeState.getNeededItemIds()
                    : null;

                // 1. 过滤出需要渲染的容器
                java.util.List<WarehouseContainerResponseS2CPacket.ContainerEntry> toRender =
                    filterContainersForPickup(containersSnapshot, neededItemIds);

                // 2. 渲染容器线框（大箱子用 CHEST_TYPE 属性检测，渲染覆盖整个双箱区域）
                Color4f containerColor = new Color4f(0.2f, 0.6f, 1.0f, 1.0f); // 蓝色

                // 去重：大箱子左右半箱都有 container_inventory 记录，只渲染一次
                java.util.Set<BlockPos> renderedPositions = new java.util.HashSet<>();

                for (WarehouseContainerResponseS2CPacket.ContainerEntry container : toRender) {
                    int px = container.posX(), py = container.posY(), pz = container.posZ();
                    BlockPos pos = new BlockPos(px, py, pz);
                    if (renderedPositions.contains(pos)) continue;

                    BlockPos pos1 = pos, pos2 = pos;

                    // 检测大箱子：读取方块状态的 CHEST_TYPE 属性
                    net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
                    if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
                        net.minecraft.world.level.block.state.properties.EnumProperty<net.minecraft.world.level.block.state.properties.ChestType> chestTypeProp =
                            net.minecraft.world.level.block.ChestBlock.TYPE;
                        if (state.hasProperty(chestTypeProp)) {
                            net.minecraft.world.level.block.state.properties.ChestType chestType = state.getValue(chestTypeProp);
                            if (chestType != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                                // 大箱子：用 getConnectedDirection 获取配对方向，扩展到配对坐标
                                net.minecraft.core.Direction facing = net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state);
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
                    RenderUtils.renderAreaOutline(pos1, pos2, 2.0f, containerColor, containerColor, containerColor);
                }
            }
        }

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
    private void renderWarehouseAreas(Minecraft mc)
    {
        if (!net.syncmaterial.syncmaterial.client.config.Configs.Generic.WAREHOUSE_RENDER_ENABLED.getBooleanValue())
        {
            return;
        }

        // 取局部快照：遍历期间网络线程可能替换引用
        var areasSnapshot = this.warehouseAreas;
        if (areasSnapshot.isEmpty() || mc.player == null)
        {
            return;
        }

        String playerWorldId = mc.player.level().dimension().identifier().toString();
        String serverKey = getServerKey();

        Color4f normalLine = net.syncmaterial.syncmaterial.client.config.Configs.Render.WAREHOUSE_LINE_COLOR.getColor();
        Color4f sideColor = net.syncmaterial.syncmaterial.client.config.Configs.Render.WAREHOUSE_SIDE_COLOR.getColor();
        Color4f referencedLine = net.syncmaterial.syncmaterial.client.config.Configs.Render.WAREHOUSE_REFERENCED_LINE_COLOR.getColor();
        boolean labelEnabled = net.syncmaterial.syncmaterial.client.config.Configs.Render.LABEL_ENABLED.getBooleanValue();
        float labelScale = (float) net.syncmaterial.syncmaterial.client.config.Configs.Render.LABEL_SCALE.getDoubleValue();

        for (var warehouse : areasSnapshot)
        {
            // 逐个维度过滤：仓库是全局的，可能位于其他维度
            if (!playerWorldId.equals(warehouse.world()))
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
            if (StagingAreaSelector.getInstance().isEditingWarehouse(warehouse.areaId()))
            {
                continue;
            }

            BlockPos pos1 = new BlockPos(warehouse.x1(), warehouse.y1(), warehouse.z1());
            BlockPos pos2 = new BlockPos(warehouse.x2(), warehouse.y2(), warehouse.z2());

            boolean referenced = this.referencedWarehouseIds.contains(warehouse.areaId());
            Color4f lineColor = referenced ? referencedLine : normalLine;

            RenderUtils.renderAreaOutline(pos1, pos2, 2.0f, lineColor, lineColor, lineColor);
            RenderUtils.renderAreaSides(pos1, pos2, sideColor);

            if (labelEnabled)
            {
                double cx = (pos1.getX() + pos2.getX()) / 2.0 + 0.5;
                double cy = Math.max(pos1.getY(), pos2.getY()) + 0.5;
                double cz = (pos1.getZ() + pos2.getZ()) / 2.0 + 0.5;
                RenderUtils.drawTextPlate(
                    Collections.singletonList(warehouse.name()), cx, cy, cz, labelScale, 1.0f);
            }
        }
    }
}
