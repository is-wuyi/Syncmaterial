package net.syncmaterial.syncmaterial.client.gui;

/**
 * 仓库列表条目数据（类似 StagingAreaEntry）
 */
public record WarehouseEntry(
    int warehouseId,
    String name,
    int x1, int y1, int z1,
    int x2, int y2, int z2,
    String world
) {}
