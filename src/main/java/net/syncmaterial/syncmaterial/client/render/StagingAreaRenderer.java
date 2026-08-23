package net.syncmaterial.syncmaterial.client.render;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

import org.joml.Matrix4f;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.profiler.Profiler;

import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;

import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
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
    }

    public void clearWarehouseContainers()
    {
        this.warehouseContainers = java.util.List.of();
        this.warehouseContainersWorld = "";
    }

    public java.util.List<WarehouseContainerResponseS2CPacket.ContainerEntry> getWarehouseContainers()
    {
        return this.warehouseContainers;
    }

    @Override
    public void onRenderWorldLastAdvanced(Framebuffer fb, Matrix4f posMatrix, Matrix4f projMatrix,
            Frustum frustum, Camera camera, BufferBuilderStorage buffers, Profiler profiler)
    {
        if (this.selections.isEmpty())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

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

                boolean isHighlighted = java.util.Objects.equals(entry.getKey(), this.highlightedSchematicId)
                    && java.util.Objects.equals(box.getName(), this.highlightedBoxName);
                Color4f lineColor = isHighlighted
                    ? net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_HIGHLIGHT_LINE_COLOR.getColor()
                    : net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_LINE_COLOR.getColor();
                Color4f sideColor = isHighlighted
                    ? net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_HIGHLIGHT_LINE_COLOR.getColor()
                    : net.syncmaterial.syncmaterial.client.config.Configs.Render.AREA_SIDE_COLOR.getColor();
                RenderUtils.renderAreaOutline(pos1, pos2, 2.0f, lineColor, lineColor, lineColor);
                RenderUtils.renderAreaSides(pos1, pos2, sideColor, posMatrix);

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
                    RenderUtils.drawTextPlate(Collections.singletonList(label), cx, cy, cz, labelScale);
                }
            }
        }

        StagingAreaSelector.getInstance().onRenderWorld(this, posMatrix);

        // Phase 5: 仓库容器蓝色线框高亮
        // 取局部快照：整个渲染过程用同一份列表，避免中途被网络线程替换引用
        var containersSnapshot = this.warehouseContainers;
        if (!containersSnapshot.isEmpty() && mc.player != null) {
            String playerWorldId = mc.player.getWorld().getRegistryKey().getValue().toString();
            // 跨世界过滤：只渲染玩家所在世界的仓库容器
            if (playerWorldId.equals(this.warehouseContainersWorld)) {
                // 取货模式下只显示包含需要材料的箱子
                boolean isPickupMode = GuiMaterialList.isPickupModeStatic();

                java.util.Set<String> neededItemIds = null;
                if (isPickupMode) {
                    neededItemIds = GuiMaterialList.getPickupModeNeededItemIds();
                }

                // 1. 过滤出需要渲染的容器
                java.util.List<WarehouseContainerResponseS2CPacket.ContainerEntry> toRender = new java.util.ArrayList<>();
                for (WarehouseContainerResponseS2CPacket.ContainerEntry container : containersSnapshot) {
                    if (isPickupMode && neededItemIds != null) {
                        boolean hasNeeded = false;
                        for (String itemId : container.itemIds()) {
                            if (neededItemIds.contains(itemId)) {
                                hasNeeded = true;
                                break;
                            }
                        }
                        if (!hasNeeded) continue;
                    }
                    toRender.add(container);
                }

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
                    net.minecraft.block.BlockState state = mc.world.getBlockState(pos);
                    if (state.getBlock() instanceof net.minecraft.block.ChestBlock) {
                        net.minecraft.state.property.EnumProperty<net.minecraft.block.enums.ChestType> chestTypeProp =
                            net.minecraft.block.ChestBlock.CHEST_TYPE;
                        if (state.contains(chestTypeProp)) {
                            net.minecraft.block.enums.ChestType chestType = state.get(chestTypeProp);
                            if (chestType != net.minecraft.block.enums.ChestType.SINGLE) {
                                // 大箱子：用 ChestBlock.getFacing 获取配对方向，扩展到配对坐标
                                net.minecraft.util.math.Direction facing = net.minecraft.block.ChestBlock.getFacing(state);
                                BlockPos pairedPos = pos.offset(facing);
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
}
