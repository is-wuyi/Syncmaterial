package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
import net.syncmaterial.syncmaterial.core.AreaInventoryWarning;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Read-side area queries and the single-transaction inventory projection. */
final class AreaReads {
    private static final String AREA_COLUMNS =
            "id, project_id, kind, name, world, x1, y1, z1, x2, y2, z2, revision, geometry_revision";
    private static final String AREA_COLUMNS_QUALIFIED =
            "a.id, a.project_id, a.kind, a.name, a.world, a.x1, a.y1, a.z1, a.x2, a.y2, a.z2, "
                    + "a.revision, a.geometry_revision";
    private static final int IN_CHUNK_SIZE = 400;

    private AreaReads() {
    }

    static Optional<AreaDefinition> findArea(Connection connection, int areaId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT " + AREA_COLUMNS + " FROM areas WHERE id = ?")) {
            select.setInt(1, areaId);
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next() ? Optional.of(readArea(resultSet)) : Optional.empty();
            }
        }
    }

    static List<AreaDefinition> allAreas(Connection connection) throws SQLException {
        List<AreaDefinition> areas = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT " + AREA_COLUMNS + " FROM areas ORDER BY id");
             ResultSet resultSet = select.executeQuery()) {
            while (resultSet.next()) {
                areas.add(readArea(resultSet));
            }
        }
        return List.copyOf(areas);
    }

    static List<AreaDefinition> stagingAreas(Connection connection, String projectId) throws SQLException {
        List<AreaDefinition> areas = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT " + AREA_COLUMNS + " FROM areas "
                        + "WHERE project_id = ? AND kind = 'staging_area' ORDER BY id")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    areas.add(readArea(resultSet));
                }
            }
        }
        return List.copyOf(areas);
    }

    static List<AreaDefinition> referencedWarehouses(Connection connection, String projectId) throws SQLException {
        List<AreaDefinition> areas = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT " + AREA_COLUMNS_QUALIFIED
                        + " FROM areas a JOIN warehouse_refs r ON r.area_id = a.id "
                        + "WHERE r.project_id = ? AND a.kind = 'warehouse' ORDER BY a.id")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    areas.add(readArea(resultSet));
                }
            }
        }
        return List.copyOf(areas);
    }

    static AreaCatalog catalog(Connection connection) throws SQLException {
        return new AreaCatalog(
                allAreas(connection),
                warehouseReferences(connection),
                projectNames(connection),
                StoreState.configRevision(connection));
    }

    static Map<String, Set<Integer>> warehouseReferences(Connection connection) throws SQLException {
        Map<String, Set<Integer>> references = new TreeMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT project_id, area_id FROM warehouse_refs ORDER BY project_id, area_id");
             ResultSet resultSet = select.executeQuery()) {
            while (resultSet.next()) {
                references.computeIfAbsent(resultSet.getString(1), ignored -> new LinkedHashSet<>())
                        .add(StoreIds.checkedIntId(resultSet.getLong(2), "area"));
            }
        }
        return Collections.unmodifiableMap(references);
    }

    static Map<String, String> projectNames(Connection connection) throws SQLException {
        Map<String, String> names = new TreeMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, name FROM projects ORDER BY id");
             ResultSet resultSet = select.executeQuery()) {
            while (resultSet.next()) {
                names.put(resultSet.getString(1), resultSet.getString(2));
            }
        }
        return Collections.unmodifiableMap(names);
    }

    static boolean projectExists(Connection connection, String projectId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT 1 FROM projects WHERE id = ?")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    /**
     * Project area projection. Must run inside the same read transaction as the project and
     * claims so the returned inventory revision matches the project view it is attached to.
     */
    static AreaInventoryView projectInventory(Connection connection, String projectId) throws SQLException {
        long revision = StoreState.inventoryRevision(connection);
        List<AreaDefinition> staging = stagingAreas(connection, projectId);
        List<AreaDefinition> warehouses = referencedWarehouses(connection, projectId);
        List<AreaDefinition> contributing = concatenate(staging, warehouses);

        if (contributing.isEmpty()) {
            return new AreaInventoryView(Map.of(), Map.of(), List.of(), true, false, revision);
        }

        Map<Integer, Set<Long>> linksByArea = new LinkedHashMap<>();
        Map<Long, ContainerRow> containers = new LinkedHashMap<>();
        loadLinks(connection, contributing, linksByArea, containers);
        Map<Long, Map<String, Long>> items = loadItems(connection, containers.keySet());

        List<AreaInventoryWarning> warnings = new ArrayList<>();
        for (AreaDefinition area : contributing) {
            Set<Long> containerIds = linksByArea.getOrDefault(area.id(), Set.of());
            Coverage coverage = coverage(connection, area);
            if (coverage.missing()) {
                warnings.add(new AreaInventoryWarning(area.kind(), area.id(), area.name(),
                        AreaInventoryWarning.STATUS_UNKNOWN));
            }
            if (coverage.touched()) {
                warnings.add(new AreaInventoryWarning(area.kind(), area.id(), area.name(),
                        AreaInventoryWarning.STATUS_STALE));
            }
            boolean opaque = containerIds.stream()
                    .map(containers::get)
                    .anyMatch(row -> row != null && !row.warning().isBlank());
            if (opaque) {
                warnings.add(new AreaInventoryWarning(area.kind(), area.id(), area.name(),
                        AreaInventoryWarning.STATUS_OPAQUE));
            }
        }

        Map<String, Long> stagingCounts = new TreeMap<>();
        Set<Long> stagedIds = new HashSet<>();
        sumContributions(staging, linksByArea, containers, items, stagingCounts, stagedIds, Set.of(), List.of());

        Map<String, Long> warehouseCounts = new TreeMap<>();
        sumContributions(warehouses, linksByArea, containers, items, warehouseCounts, new HashSet<>(),
                stagedIds, staging);

        boolean overlap = overlaps(contributing);
        boolean known = warnings.isEmpty();
        return new AreaInventoryView(stagingCounts, warehouseCounts, warnings, known, overlap, revision);
    }

    /** Effective referenced warehouse sources for one dimension, excluding sources staged by this project. */
    static List<ContainerObservation> warehouseContainers(Connection connection, String projectId, String world)
            throws SQLException {
        requireProject(connection, projectId);

        List<AreaDefinition> staging = stagingAreas(connection, projectId).stream()
                .filter(area -> area.world().equals(world))
                .toList();
        List<AreaDefinition> warehouses = referencedWarehouses(connection, projectId).stream()
                .filter(area -> area.world().equals(world))
                .toList();

        Map<Integer, Set<Long>> linksByArea = new LinkedHashMap<>();
        Map<Long, ContainerRow> containers = new LinkedHashMap<>();
        loadLinks(connection, concatenate(staging, warehouses), linksByArea, containers);
        Map<Long, Map<String, Long>> items = loadItems(connection, containers.keySet());

        Set<Long> stagedIds = new HashSet<>();
        for (AreaDefinition area : staging) {
            stagedIds.addAll(linksByArea.getOrDefault(area.id(), Set.of()));
        }

        Map<ContainerKey, ContainerObservation> observations = new TreeMap<>(
                Comparator.comparing(ContainerKey::world)
                        .thenComparingInt(ContainerKey::x)
                        .thenComparingInt(ContainerKey::y)
                        .thenComparingInt(ContainerKey::z));
        for (AreaDefinition area : warehouses) {
            for (Long containerId : linksByArea.getOrDefault(area.id(), Set.of())) {
                if (stagedIds.contains(containerId)) {
                    continue;
                }
                ContainerRow row = containers.get(containerId);
                if (row == null) {
                    continue;
                }
                if (insideAny(staging, row.key())) {
                    // Current staging geometry wins even before staging has scanned this source:
                    // never highlight physically staged stock as warehouse stock.
                    continue;
                }
                observations.put(row.key(), new ContainerObservation(
                        row.key(),
                        items.getOrDefault(containerId, Map.of()),
                        row.warning()));
            }
        }
        return List.copyOf(observations.values());
    }

    static void requireProject(Connection connection, String projectId) throws SQLException {
        if (!projectExists(connection, projectId)) {
            throw new StorageException("Unknown project: " + projectId);
        }
    }

    static AreaDefinition readArea(ResultSet resultSet) throws SQLException {
        try {
            int id = StoreIds.checkedIntId(resultSet.getLong(1), "area");
            String projectId = resultSet.getString(2);
            AreaKind kind = AreaKind.fromWireName(resultSet.getString(3));
            return new AreaDefinition(
                    id,
                    projectId,
                    kind,
                    resultSet.getString(4),
                    resultSet.getString(5),
                    new AreaBounds(
                            resultSet.getInt(6), resultSet.getInt(7), resultSet.getInt(8),
                            resultSet.getInt(9), resultSet.getInt(10), resultSet.getInt(11)),
                    resultSet.getLong(12),
                    resultSet.getLong(13));
        } catch (IllegalArgumentException invalidRow) {
            throw new SQLException("Invalid area row", invalidRow);
        }
    }

    private static List<AreaDefinition> concatenate(List<AreaDefinition> first, List<AreaDefinition> second) {
        Map<Integer, AreaDefinition> byId = new LinkedHashMap<>();
        for (AreaDefinition area : first) {
            byId.put(area.id(), area);
        }
        for (AreaDefinition area : second) {
            byId.putIfAbsent(area.id(), area);
        }
        return List.copyOf(byId.values());
    }

    private static void loadLinks(Connection connection, List<AreaDefinition> areas,
                                  Map<Integer, Set<Long>> linksByArea,
                                  Map<Long, ContainerRow> containers) throws SQLException {
        if (areas.isEmpty()) {
            return;
        }
        List<Integer> areaIds = areas.stream().map(AreaDefinition::id).toList();
        for (int start = 0; start < areaIds.size(); start += IN_CHUNK_SIZE) {
            List<Integer> batch = areaIds.subList(start, Math.min(start + IN_CHUNK_SIZE, areaIds.size()));
            String sql = "SELECT l.area_id, c.id, c.world, c.x, c.y, c.z, c.warning "
                    + "FROM area_chunk_containers l JOIN containers c ON c.id = l.container_id "
                    + "WHERE l.area_id IN (" + placeholders(batch.size()) + ")";
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                bindInts(select, batch, 1);
                try (ResultSet resultSet = select.executeQuery()) {
                    while (resultSet.next()) {
                        int areaId = StoreIds.checkedIntId(resultSet.getLong(1), "area");
                        long containerId = resultSet.getLong(2);
                        ContainerKey key = new ContainerKey(
                                resultSet.getString(3),
                                resultSet.getInt(4),
                                resultSet.getInt(5),
                                resultSet.getInt(6));
                        String warning = resultSet.getString(7);
                        linksByArea.computeIfAbsent(areaId, ignored -> new LinkedHashSet<>())
                                .add(containerId);
                        containers.putIfAbsent(containerId, new ContainerRow(key, warning));
                    }
                }
            }
        }
    }

    private static Map<Long, Map<String, Long>> loadItems(Connection connection, Set<Long> containerIds)
            throws SQLException {
        Map<Long, Map<String, Long>> items = new LinkedHashMap<>();
        if (containerIds.isEmpty()) {
            return items;
        }
        List<Long> ids = new ArrayList<>(containerIds);
        for (int start = 0; start < ids.size(); start += IN_CHUNK_SIZE) {
            List<Long> batch = ids.subList(start, Math.min(start + IN_CHUNK_SIZE, ids.size()));
            String sql = "SELECT container_id, item_id, count FROM container_items "
                    + "WHERE container_id IN (" + placeholders(batch.size()) + ")";
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                for (int index = 0; index < batch.size(); index++) {
                    select.setLong(index + 1, batch.get(index));
                }
                try (ResultSet resultSet = select.executeQuery()) {
                    while (resultSet.next()) {
                        long containerId = resultSet.getLong(1);
                        long count = resultSet.getLong(3);
                        if (count < 0) {
                            throw new SQLException("negative container count: " + count);
                        }
                        items.computeIfAbsent(containerId, ignored -> new TreeMap<>())
                                .merge(resultSet.getString(2), count, Math::addExact);
                    }
                }
            }
        }
        return items;
    }

    private static void sumContributions(List<AreaDefinition> areas,
                                         Map<Integer, Set<Long>> linksByArea,
                                         Map<Long, ContainerRow> containers,
                                         Map<Long, Map<String, Long>> items,
                                         Map<String, Long> target,
                                         Set<Long> counted,
                                         Set<Long> excluded,
                                         List<AreaDefinition> stagingGeometry) {
        for (AreaDefinition area : areas) {
            for (Long containerId : linksByArea.getOrDefault(area.id(), Set.of())) {
                if (excluded.contains(containerId) || !counted.add(containerId)) {
                    continue;
                }
                ContainerRow row = containers.get(containerId);
                if (row == null || !row.warning().isBlank()) {
                    continue;
                }
                if (insideAny(stagingGeometry, row.key())) {
                    continue;
                }
                for (Map.Entry<String, Long> entry : items.getOrDefault(containerId, Map.of()).entrySet()) {
                    target.merge(entry.getKey(), entry.getValue(), Math::addExact);
                }
            }
        }
    }

    private static boolean insideAny(List<AreaDefinition> areas, ContainerKey key) {
        for (AreaDefinition area : areas) {
            if (area.world().equals(key.world()) && area.bounds().contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static Coverage coverage(Connection connection, AreaDefinition area) throws SQLException {
        long expected = area.bounds().chunkCount();
        long total = 0L;
        boolean touched = false;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT state, COUNT(*) FROM area_chunks "
                        + "WHERE area_id = ? AND geometry_revision = ? GROUP BY state")) {
            select.setInt(1, area.id());
            select.setLong(2, area.geometryRevision());
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    String state = resultSet.getString(1);
                    long count = resultSet.getLong(2);
                    total += count;
                    if (!"complete".equals(state)) {
                        if (!"scanning".equals(state) && !"stale".equals(state) && !"dirty".equals(state)) {
                            throw new SQLException("invalid area chunk state: " + state);
                        }
                        touched = true;
                    }
                }
            }
        }
        return new Coverage(total != expected, touched);
    }

    private static boolean overlaps(List<AreaDefinition> areas) {
        for (int first = 0; first < areas.size(); first++) {
            AreaDefinition left = areas.get(first);
            for (int second = first + 1; second < areas.size(); second++) {
                AreaDefinition right = areas.get(second);
                if (left.world().equals(right.world())
                        && left.bounds().intersects(right.bounds())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static void bindInts(PreparedStatement statement, List<Integer> values, int firstIndex)
            throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setInt(firstIndex + index, values.get(index));
        }
    }

    private record ContainerRow(ContainerKey key, String warning) {
    }

    private record Coverage(boolean missing, boolean touched) {
    }
}
