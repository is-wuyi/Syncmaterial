package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Write-path area configuration SQL. Callers own the transaction boundary. */
final class AreaWrites {
    private AreaWrites() {
    }

    static AreaDefinition addArea(Connection connection, String projectId, AreaKind kind, AreaSpec spec)
            throws SQLException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(spec, "spec");
        if (projectId == null) {
            if (kind != AreaKind.WAREHOUSE) {
                throw new IllegalArgumentException("only a global warehouse may omit projectId");
            }
        } else {
            if (kind != AreaKind.STAGING_AREA) {
                throw new IllegalArgumentException("warehouses are global; projectId must be null");
            }
            AreaReads.requireProject(connection, projectId);
        }

        long now = System.currentTimeMillis();
        int areaId;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO areas "
                        + "(project_id, kind, name, world, x1, y1, z1, x2, y2, z2, "
                        + "revision, geometry_revision, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?) RETURNING id")) {
            if (projectId == null) {
                insert.setNull(1, java.sql.Types.VARCHAR);
            } else {
                insert.setString(1, projectId);
            }
            insert.setString(2, kind.wireName());
            insert.setString(3, spec.name());
            insert.setString(4, spec.world());
            AreaBounds bounds = spec.bounds();
            insert.setInt(5, bounds.x1());
            insert.setInt(6, bounds.y1());
            insert.setInt(7, bounds.z1());
            insert.setInt(8, bounds.x2());
            insert.setInt(9, bounds.y2());
            insert.setInt(10, bounds.z2());
            insert.setLong(11, now);
            insert.setLong(12, now);
            try (ResultSet resultSet = insert.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("area insert returned no id");
                }
                areaId = StoreIds.checkedIntId(resultSet.getLong(1), "area");
            }
        }

        AreaDefinition definition = requireArea(connection, areaId);
        StoreState.bumpConfigAndInventory(connection);
        return definition;
    }

    static AreaDefinition updateArea(Connection connection, String projectId, AreaKind kind, int areaId,
                                     AreaSpec spec) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(spec, "spec");
        AreaDefinition existing = requireArea(connection, areaId);
        requireScope(existing, projectId, kind);

        boolean geometryChanged = !sameGeometry(existing, spec);
        long now = System.currentTimeMillis();
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE areas SET name = ?, world = ?, x1 = ?, y1 = ?, z1 = ?, x2 = ?, y2 = ?, z2 = ?, "
                        + "revision = revision + 1, geometry_revision = geometry_revision + ?, updated_at = ? "
                        + "WHERE id = ?")) {
            update.setString(1, spec.name());
            update.setString(2, spec.world());
            AreaBounds bounds = spec.bounds();
            update.setInt(3, bounds.x1());
            update.setInt(4, bounds.y1());
            update.setInt(5, bounds.z1());
            update.setInt(6, bounds.x2());
            update.setInt(7, bounds.y2());
            update.setInt(8, bounds.z2());
            update.setInt(9, geometryChanged ? 1 : 0);
            update.setLong(10, now);
            update.setInt(11, areaId);
            if (update.executeUpdate() == 0) {
                throw new SQLException("area update affected no rows: " + areaId);
            }
        }

        if (geometryChanged) {
            deleteCoverage(connection, areaId);
            gcContainers(connection);
        }
        StoreState.bumpConfigAndInventory(connection);
        return requireArea(connection, areaId);
    }

    static AreaDefinition renameArea(Connection connection, String projectId, int areaId, String name)
            throws SQLException {
        AreaDefinition existing = requireArea(connection, areaId);
        requireStagingScope(existing, projectId);

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE areas SET name = ?, revision = revision + 1, updated_at = ? WHERE id = ?")) {
            update.setString(1, name);
            update.setLong(2, System.currentTimeMillis());
            update.setInt(3, areaId);
            if (update.executeUpdate() == 0) {
                throw new SQLException("area rename affected no rows: " + areaId);
            }
        }
        StoreState.bumpConfigAndInventory(connection);
        return requireArea(connection, areaId);
    }

    static boolean removeArea(Connection connection, String projectId, AreaKind kind, int areaId)
            throws SQLException {
        Objects.requireNonNull(kind, "kind");
        AreaDefinition existing = requireArea(connection, areaId);
        requireScope(existing, projectId, kind);

        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM areas WHERE id = ?")) {
            delete.setInt(1, areaId);
            if (delete.executeUpdate() == 0) {
                throw new SQLException("area delete affected no rows: " + areaId);
            }
        }
        gcContainers(connection);
        StoreState.bumpConfigAndInventory(connection);
        return true;
    }

    static int clearStagingAreas(Connection connection, String projectId) throws SQLException {
        AreaReads.requireProject(connection, projectId);
        int removed;
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM areas WHERE project_id = ? AND kind = 'staging_area'")) {
            delete.setString(1, projectId);
            removed = delete.executeUpdate();
        }
        if (removed > 0) {
            gcContainers(connection);
            StoreState.bumpConfigAndInventory(connection);
        }
        return removed;
    }

    static boolean addWarehouseReference(Connection connection, String projectId, int areaId) throws SQLException {
        AreaReads.requireProject(connection, projectId);
        AreaDefinition area = requireArea(connection, areaId);
        requireWarehouse(area);

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO warehouse_refs (project_id, area_id) VALUES (?, ?) "
                        + "ON CONFLICT(project_id, area_id) DO NOTHING")) {
            insert.setString(1, projectId);
            insert.setInt(2, areaId);
            if (insert.executeUpdate() == 0) {
                return false;
            }
        }
        StoreState.bumpConfigAndInventory(connection);
        return true;
    }

    static boolean removeWarehouseReference(Connection connection, String projectId, int areaId) throws SQLException {
        AreaReads.requireProject(connection, projectId);
        AreaDefinition area = requireArea(connection, areaId);
        requireWarehouse(area);

        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM warehouse_refs WHERE project_id = ? AND area_id = ?")) {
            delete.setString(1, projectId);
            delete.setInt(2, areaId);
            if (delete.executeUpdate() == 0) {
                return false;
            }
        }
        StoreState.bumpConfigAndInventory(connection);
        return true;
    }

    static void gcContainers(Connection connection) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM containers WHERE id NOT IN (SELECT container_id FROM area_chunk_containers)")) {
            delete.executeUpdate();
        }
    }

    private static void deleteCoverage(Connection connection, int areaId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM area_chunks WHERE area_id = ?")) {
            delete.setInt(1, areaId);
            delete.executeUpdate();
        }
    }

    private static AreaDefinition requireArea(Connection connection, int areaId) throws SQLException {
        return AreaReads.findArea(connection, areaId)
                .orElseThrow(() -> new StorageException("Unknown area: " + areaId));
    }

    private static void requireWarehouse(AreaDefinition area) {
        if (area.kind() != AreaKind.WAREHOUSE) {
            throw new StorageException("Area " + area.id() + " is not a warehouse");
        }
    }

    private static void requireStagingScope(AreaDefinition area, String projectId) {
        if (projectId == null || !projectId.equals(area.projectId())) {
            throw new StorageException("Area " + area.id() + " does not belong to project " + projectId);
        }
        if (area.kind() != AreaKind.STAGING_AREA) {
            throw new StorageException("Area " + area.id() + " is not a staging area");
        }
    }

    private static void requireScope(AreaDefinition area, String projectId, AreaKind kind) {
        if (area.kind() != kind) {
            throw new StorageException("Area " + area.id() + " is a " + area.kind().wireName()
                    + ", not a " + kind.wireName());
        }
        if (kind == AreaKind.WAREHOUSE) {
            if (projectId != null) {
                throw new StorageException("Warehouse areas are global; projectId must be null");
            }
            return;
        }
        if (projectId == null || !projectId.equals(area.projectId())) {
            throw new StorageException("Area " + area.id() + " does not belong to project " + projectId);
        }
    }

    private static boolean sameGeometry(AreaDefinition existing, AreaSpec spec) {
        if (!existing.world().equals(spec.world())) {
            return false;
        }
        AreaBounds left = existing.bounds();
        AreaBounds right = spec.bounds();
        return left.minX() == right.minX() && left.maxX() == right.maxX()
                && left.minY() == right.minY() && left.maxY() == right.maxY()
                && left.minZ() == right.minZ() && left.maxZ() == right.maxZ();
    }
}
