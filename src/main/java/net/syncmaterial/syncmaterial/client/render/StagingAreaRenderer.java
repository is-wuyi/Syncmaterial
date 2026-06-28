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
    private final java.util.List<WarehouseContainerResponseS2CPacket.ContainerEntry> warehouseContainers =
            Collections.synchronizedList(new java.util.ArrayList<>());
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
        this.warehouseContainers.clear();
        this.warehouseContainers.addAll(containers);
        this.warehouseContainersWorld = worldId != null ? worldId : "";
    }

    public void clearWarehouseContainers()
    {
        this.warehouseContainers.clear();
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
        if (!this.warehouseContainers.isEmpty() && mc.player != null) {
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
                for (WarehouseContainerResponseS2CPacket.ContainerEntry container : this.warehouseContainers) {
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

                // 2. 合并相邻容器（大箱子检测）：同 Y 层且 X 或 Z 相差 1 的视为同一组
                java.util.Set<String> used = new java.util.HashSet<>();
                Color4f containerColor = new Color4f(0.2f, 0.6f, 1.0f, 1.0f); // 蓝色

                for (int i = 0; i < toRender.size(); i++) {
                    String keyI = toRender.get(i).posX() + "," + toRender.get(i).posY() + "," + toRender.get(i).posZ();
                    if (used.contains(keyI)) continue;

                    int minX = toRender.get(i).posX(), minY = toRender.get(i).posY(), minZ = toRender.get(i).posZ();
                    int maxX = minX, maxY = minY, maxZ = minZ;

                    // 向后查找相邻容器（同 Y 层，X 或 Z 方向差 1）
                    for (int j = i + 1; j < toRender.size(); j++) {
                        String keyJ = toRender.get(j).posX() + "," + toRender.get(j).posY() + "," + toRender.get(j).posZ();
                        if (used.contains(keyJ)) continue;

                        WarehouseContainerResponseS2CPacket.ContainerEntry other = toRender.get(j);
                        if (other.posY() == minY) {
                            boolean adjacentX = Math.abs(other.posX() - minX) == 1 && other.posZ() == minZ;
                            boolean adjacentZ = Math.abs(other.posZ() - minZ) == 1 && other.posX() == minX;
                            if (adjacentX || adjacentZ) {
                                minX = Math.min(minX, other.posX());
                                maxX = Math.max(maxX, other.posX());
                                minZ = Math.min(minZ, other.posZ());
                                maxZ = Math.max(maxZ, other.posZ());
                                used.add(keyJ);
                            }
                        }
                    }

                    used.add(keyI);
                    BlockPos pos1 = new BlockPos(minX, minY, minZ);
                    BlockPos pos2 = new BlockPos(maxX, maxY, maxZ);
                    RenderUtils.renderAreaOutline(pos1, pos2, 2.0f, containerColor, containerColor, containerColor);
                }
            }
        }

        profiler.pop();
    }
}
