package net.syncmaterial.syncmaterial.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.syncmaterial.syncmaterial.SyncMaterial;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StagingAreaManager {
    private final SchematicDatabase database;
    private final Map<String, List<StagingArea>> stagingAreasBySchematic = new ConcurrentHashMap<>();
    private final Map<BlockPos, ServerWorld> dirtyContainers = new ConcurrentHashMap<>();
    private MinecraftServer server;

    public StagingAreaManager(SchematicDatabase database) {
        this.database = database;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public int addStagingArea(String schematicId, String world, String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        try {
            database.executeUpdate(
                "INSERT INTO staging_areas (schematic_id, world, name, x1, y1, z1, x2, y2, z2) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                schematicId, world, name, x1, y1, z1, x2, y2, z2
            );

            try (var rs = database.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    int areaId = rs.getInt(1);
                    refreshCache(schematicId);
                    SyncMaterial.LOGGER.info("Added staging area {} for schematic {}", areaId, schematicId);
                    return areaId;
                }
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to add staging area", e);
        }
        return -1;
    }

    public void renameStagingArea(int areaId, String schematicId, String newName) {
        try {
            database.executeUpdate("UPDATE staging_areas SET name = ? WHERE id = ?", newName, areaId);
            refreshCache(schematicId);
            SyncMaterial.LOGGER.info("Renamed staging area {} to {}", areaId, newName);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to rename staging area", e);
        }
    }

    public void updateStagingArea(int areaId, String schematicId, String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        try {
            database.executeUpdate(
                "UPDATE staging_areas SET name = ?, x1 = ?, y1 = ?, z1 = ?, x2 = ?, y2 = ?, z2 = ? WHERE id = ?",
                name, x1, y1, z1, x2, y2, z2, areaId
            );
            refreshCache(schematicId);
            SyncMaterial.LOGGER.info("Updated staging area {} coordinates to [{},{},{}]~[{},{},{}]", areaId, x1, y1, z1, x2, y2, z2);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to update staging area", e);
        }
    }

    public void removeStagingArea(int areaId, String schematicId) {
        try {
            database.executeUpdate("DELETE FROM staging_areas WHERE id = ?", areaId);
            refreshCache(schematicId);
            SyncMaterial.LOGGER.info("Removed staging area {}", areaId);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to remove staging area", e);
        }
    }

    public List<StagingArea> getStagingAreas(String schematicId) {
        return stagingAreasBySchematic.computeIfAbsent(schematicId, this::loadStagingAreasFromDb);
    }

    public boolean isInAnyStagingArea(BlockPos pos, ServerWorld world) {
        String worldId = world.getRegistryKey().getValue().toString();
        for (List<StagingArea> areas : stagingAreasBySchematic.values()) {
            for (StagingArea area : areas) {
                if (area.world.equals(worldId) && isPosInArea(pos, area)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void scheduleContainerScan(BlockPos pos, ServerWorld world) {
        dirtyContainers.put(pos, world);
    }

    public void processDirtyContainers() {
        if (dirtyContainers.isEmpty()) {
            return;
        }

        Map<String, Map<String, Integer>> materialChanges = new HashMap<>();

        for (Map.Entry<BlockPos, ServerWorld> entry : dirtyContainers.entrySet()) {
            BlockPos pos = entry.getKey();
            ServerWorld world = entry.getValue();

            scanContainer(pos, world, materialChanges);
        }

        dirtyContainers.clear();

        if (!materialChanges.isEmpty() && server != null) {
            server.execute(() -> {
                broadcastMaterialChanges(materialChanges);
            });
        }
    }

    private void scanContainer(BlockPos pos, ServerWorld world, Map<String, Map<String, Integer>> materialChanges) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof Inventory inventory)) {
            return;
        }

        String worldId = world.getRegistryKey().getValue().toString();

        for (List<StagingArea> areas : stagingAreasBySchematic.values()) {
            for (StagingArea area : areas) {
                if (area.world.equals(worldId) && isPosInArea(pos, area)) {
                    int areaId = area.id;

                    for (int i = 0; i < inventory.size(); i++) {
                        var stack = inventory.getStack(i);
                        if (!stack.isEmpty()) {
                            String itemId = stack.getItem().toString();
                            int count = stack.getCount();

                            materialChanges.computeIfAbsent(String.valueOf(areaId), k -> new HashMap<>())
                                    .merge(itemId, count, Integer::sum);
                        }
                    }

                    updateStagingAreaInventory(areaId, materialChanges.get(String.valueOf(areaId)));
                    return;
                }
            }
        }
    }

    private void updateStagingAreaInventory(int areaId, Map<String, Integer> itemCounts) {
        if (itemCounts == null || itemCounts.isEmpty()) {
            return;
        }

        try {
            database.executeUpdate("DELETE FROM staging_area_inventory WHERE staging_area_id = ?", areaId);

            for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                database.executeUpdate(
                    "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) VALUES (?, ?, ?)",
                    areaId, entry.getKey(), entry.getValue()
                );
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to update staging area inventory", e);
        }
    }

    public int getStagingCountForMaterial(String schematicId, String itemId) {
        try (var rs = database.executeQuery(
            "SELECT COALESCE(SUM(sai.count), 0) FROM staging_area_inventory sai " +
            "JOIN staging_areas sa ON sai.staging_area_id = sa.id " +
            "WHERE sa.schematic_id = ? AND sai.item_id = ?",
            schematicId, itemId
        )) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to get staging count for material", e);
        }
        return 0;
    }

    public void onContainerRemoved(BlockPos pos, ServerWorld world) {
        String worldId = world.getRegistryKey().getValue().toString();

        for (List<StagingArea> areas : stagingAreasBySchematic.values()) {
            for (StagingArea area : areas) {
                if (area.world.equals(worldId) && isPosInArea(pos, area)) {
                    try {
                        database.executeUpdate(
                            "DELETE FROM staging_area_inventory WHERE staging_area_id = ?",
                            area.id
                        );
                        SyncMaterial.LOGGER.info("Cleared staging area {} inventory after container removal", area.id);
                    } catch (SQLException e) {
                        SyncMaterial.LOGGER.error("Failed to clear staging area inventory on container removal", e);
                    }
                    return;
                }
            }
        }
    }

    private boolean isPosInArea(BlockPos pos, StagingArea area) {
        int minX = Math.min(area.x1, area.x2);
        int maxX = Math.max(area.x1, area.x2);
        int minY = Math.min(area.y1, area.y2);
        int maxY = Math.max(area.y1, area.y2);
        int minZ = Math.min(area.z1, area.z2);
        int maxZ = Math.max(area.z1, area.z2);

        return pos.getX() >= minX && pos.getX() <= maxX
            && pos.getY() >= minY && pos.getY() <= maxY
            && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    private List<StagingArea> loadStagingAreasFromDb(String schematicId) {
        List<StagingArea> areas = new ArrayList<>();
        try (var rs = database.executeQuery(
            "SELECT id, world, name, x1, y1, z1, x2, y2, z2 FROM staging_areas WHERE schematic_id = ?",
            schematicId
        )) {
            while (rs.next()) {
                areas.add(new StagingArea(
                    rs.getInt("id"),
                    rs.getString("world"),
                    rs.getString("name"),
                    rs.getInt("x1"), rs.getInt("y1"), rs.getInt("z1"),
                    rs.getInt("x2"), rs.getInt("y2"), rs.getInt("z2")
                ));
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to load staging areas", e);
        }
        return areas;
    }

    private void refreshCache(String schematicId) {
        stagingAreasBySchematic.put(schematicId, loadStagingAreasFromDb(schematicId));
    }

    private void broadcastMaterialChanges(Map<String, Map<String, Integer>> materialChanges) {
        // This will be called from CollaborationManager to broadcast updates
        // The actual broadcasting logic is in CollaborationManager
    }

    public record StagingArea(int id, String world, String name, int x1, int y1, int z1, int x2, int y2, int z2) {}
}