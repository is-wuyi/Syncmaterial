package net.syncmaterial.syncmaterial.server.legacy;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
import net.syncmaterial.syncmaterial.core.AreaInventoryWarning;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.CoreChecks;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectState;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.runtime.OnlineInventorySnapshot;
import net.syncmaterial.syncmaterial.server.runtime.ProjectProgressService;

/**
 * Reads one authoritative legacy project snapshot inside a single database monitor/
 * transaction, then projects it through the same pure progress model as the Foundation
 * backend.
 *
 * <p>Online claim names are resolved to the actual online player UUID; only a
 * connection-fresh observation from {@code OnlineInventoryTracker} contributes. Unresolved
 * names, offline players and expired observations are listed with zero held. The legacy
 * aggregate tables cannot prove physical-source deduplication: when container facts show
 * overlap or are missing for a non-empty area, the view is explicitly unknown rather than
 * claiming completion. All counts are read as checked {@code long}s; overflow or negative
 * data fails the read instead of being clamped.</p>
 */
public final class LegacyProjectProgressReader {

    private LegacyProjectProgressReader() {
    }

    /**
     * One consistent legacy read. Revisions are assigned by the adapter after the read so
     * the snapshot and its envelope carry the same catalog/inventory generation.
     */
    public record Facts(
            String projectId,
            String projectName,
            PlayerIdentity owner,
            boolean allowSelfClaim,
            List<String> deputies,
            List<MaterialSnapshot> materials,
            List<ClaimSnapshot> claims,
            Map<String, Long> staging,
            Map<String, Long> warehouse,
            List<AreaInventoryWarning> warnings,
            OnlineInventorySnapshot observations,
            boolean areaCurrent,
            boolean overlap,
            List<StagingAreaManager.StagingArea> stagingAreas,
            List<StagingAreaManager.Warehouse> warehouseAreas,
            long catalogFingerprint,
            long inventoryFingerprint
    ) {
        public Facts {
            requireNonBlank(projectId, "projectId");
            projectName = projectName == null ? "" : projectName;
            if (projectName.length() > ProjectViewLimits.PROJECT_NAME_LENGTH) {
                throw new IllegalArgumentException("project name exceeds "
                        + ProjectViewLimits.PROJECT_NAME_LENGTH + " characters");
            }
            java.util.Objects.requireNonNull(owner, "owner");
            materials = List.copyOf(materials);
            claims = List.copyOf(claims);
            staging = Map.copyOf(staging);
            warehouse = Map.copyOf(warehouse);
            warnings = List.copyOf(warnings);
            java.util.Objects.requireNonNull(observations, "observations");
            if (!projectId.equals(observations.projectId())) {
                throw new IllegalArgumentException("online sample belongs to another project");
            }
            stagingAreas = List.copyOf(stagingAreas);
            warehouseAreas = List.copyOf(warehouseAreas);
        }

        public boolean known() {
            return warnings.isEmpty();
        }
    }

    /**
     * Reads project, claims, area aggregates and container facts under one DB monitor.
     *
     * @throws SQLException when the legacy database cannot be read
     * @throws IllegalArgumentException when persisted data cannot be represented truthfully
     */
    public static Optional<Facts> read(LegacyServerContext owner, String projectId)
            throws SQLException {
        return read(owner, projectId, null);
    }

    /**
     * Deterministic test seam: {@code midRead} runs after the project row is read while the
     * DB monitor/snapshot is still held. No production caller supplies it.
     */
    static Optional<Facts> read(LegacyServerContext owner, String projectId, Runnable midRead)
            throws SQLException {
        if (owner == null) {
            throw new IllegalArgumentException("owner must not be null");
        }
        requireNonBlank(projectId, "projectId");
        SchematicDatabase database = requireDatabase(owner);
        return database.inTransaction(() -> readLocked(owner, projectId, midRead));
    }

    private static Optional<Facts> readLocked(LegacyServerContext owner, String projectId,
                                              Runnable midRead) throws SQLException {
        SchematicDatabase database = requireDatabase(owner);
        ProjectMeta meta = readProject(database, projectId);
        if (meta == null) {
            return Optional.empty();
        }
        if (midRead != null) {
            midRead.run();
        }
        List<MaterialSnapshot> materials = readMaterials(database, projectId);
        List<ClaimSnapshot> claims = readClaims(database, projectId, owner);
        Map<String, Long> staging = readItemTotals(database,
                "SELECT sai.item_id AS item_id, SUM(sai.count) AS total "
                        + "FROM staging_area_inventory sai "
                        + "JOIN staging_areas sa ON sai.staging_area_id = sa.id "
                        + "WHERE sa.schematic_id = ? GROUP BY sai.item_id ORDER BY sai.item_id",
                projectId);
        Map<String, Long> warehouse = readItemTotals(database,
                "SELECT wi.item_id AS item_id, SUM(wi.count) AS total "
                        + "FROM warehouse_inventory wi "
                        + "JOIN schematic_warehouses sw ON wi.warehouse_id = sw.warehouse_id "
                        + "WHERE sw.schematic_id = ? GROUP BY wi.item_id ORDER BY wi.item_id",
                projectId);
        List<StagingAreaManager.StagingArea> stagingAreas = readStagingAreas(owner, projectId);
        List<StagingAreaManager.Warehouse> warehouseAreas = readReferencedWarehouses(owner, projectId);
        AreaFacts areaFacts = readAreaFacts(database, owner, stagingAreas, warehouseAreas);
        OnlineInventorySnapshot observations = owner.onlineInventories().snapshot(projectId, claims);

        List<AreaInventoryWarning> warnings = areaFacts.warnings();
        long catalogFingerprint = catalogFingerprint(meta.name(), materials);
        long inventoryFingerprint = inventoryFingerprint(staging, warehouse, warnings,
                areaFacts.current(), areaFacts.overlap(), stagingAreas, warehouseAreas);

        return Optional.of(new Facts(projectId, meta.name(), meta.owner(), meta.allowSelfClaim(),
                meta.deputies(), materials, claims, staging, warehouse, warnings, observations,
                areaFacts.current(), areaFacts.overlap(), stagingAreas, warehouseAreas,
                catalogFingerprint, inventoryFingerprint));
    }

    /** Builds the immutable snapshot with adapter-assigned revisions and one tracker sample. */
    public static ProjectProgressSnapshot toSnapshot(Facts facts, ProjectProgressService progress,
                                                      long catalogRevision, long inventoryRevision,
                                                      long publicationSequence) {
        if (facts == null || progress == null) {
            throw new IllegalArgumentException("facts and progress service are required");
        }
        if (catalogRevision < 0 || inventoryRevision < 0 || publicationSequence < 0) {
            throw new IllegalArgumentException("revisions and sequence must be >= 0");
        }
        ProjectSnapshot project = new ProjectSnapshot(
                facts.projectId(), facts.projectName(), "legacy:" + facts.projectId(), facts.owner(),
                facts.allowSelfClaim(), facts.deputies(), facts.materials(), catalogRevision);
        AreaInventoryView inventory = new AreaInventoryView(
                facts.staging(), facts.warehouse(), facts.warnings(), facts.known(),
                facts.overlap(), inventoryRevision);
        ProjectState state = new ProjectState(project, facts.claims(), inventory);
        return progress.build(state, facts.observations(), publicationSequence,
                facts.areaCurrent());
    }

    public static boolean projectExists(LegacyServerContext owner, String projectId)
            throws SQLException {
        SchematicDatabase database = requireDatabase(owner);
        try (var rows = database.executeQuery("SELECT 1 FROM schematics WHERE id = ?", projectId)) {
            return rows.next();
        }
    }

    public static boolean materialExists(LegacyServerContext owner, String projectId, int materialId)
            throws SQLException {
        if (materialId <= 0) {
            return false;
        }
        SchematicDatabase database = requireDatabase(owner);
        try (var rows = database.executeQuery(
                "SELECT 1 FROM material_entries WHERE id = ? AND schematic_id = ?",
                materialId, projectId)) {
            return rows.next();
        }
    }

    /** Reads staging areas from persisted rows; config reads use the same source. */
    public static List<StagingAreaManager.StagingArea> readStagingAreas(LegacyServerContext owner,
                                                                        String projectId)
            throws SQLException {
        SchematicDatabase database = requireDatabase(owner);
        List<StagingAreaManager.StagingArea> areas = new ArrayList<>();
        try (var rows = database.executeQuery(
                "SELECT id, world, name, x1, y1, z1, x2, y2, z2 FROM staging_areas "
                        + "WHERE schematic_id = ? ORDER BY id", projectId)) {
            while (rows.next()) {
                areas.add(new StagingAreaManager.StagingArea(
                        rows.getInt("id"), rows.getString("world"), rows.getString("name"),
                        rows.getInt("x1"), rows.getInt("y1"), rows.getInt("z1"),
                        rows.getInt("x2"), rows.getInt("y2"), rows.getInt("z2")));
            }
        }
        return List.copyOf(areas);
    }

    public static List<StagingAreaManager.Warehouse> readReferencedWarehouses(
            LegacyServerContext owner, String projectId) throws SQLException {
        SchematicDatabase database = requireDatabase(owner);
        List<StagingAreaManager.Warehouse> warehouses = new ArrayList<>();
        try (var rows = database.executeQuery(
                "SELECT w.id, w.name, w.world, w.x1, w.y1, w.z1, w.x2, w.y2, w.z2 "
                        + "FROM warehouses w INNER JOIN schematic_warehouses sw "
                        + "ON w.id = sw.warehouse_id WHERE sw.schematic_id = ? ORDER BY w.id",
                projectId)) {
            while (rows.next()) {
                warehouses.add(readWarehouse(rows));
            }
        }
        return List.copyOf(warehouses);
    }

    public static List<StagingAreaManager.Warehouse> readAllWarehouses(LegacyServerContext owner)
            throws SQLException {
        SchematicDatabase database = requireDatabase(owner);
        List<StagingAreaManager.Warehouse> warehouses = new ArrayList<>();
        try (var rows = database.executeQuery(
                "SELECT id, name, world, x1, y1, z1, x2, y2, z2 FROM warehouses ORDER BY id")) {
            while (rows.next()) {
                warehouses.add(readWarehouse(rows));
            }
        }
        return List.copyOf(warehouses);
    }

    /**
     * Per-physical-container observations for the referenced warehouses in one dimension.
     * The legacy schema stores container item presence, not per-container counts, so every
     * observation is explicitly opaque and uses zero-valued presence entries instead of
     * pretending an aggregate table is one physical box.
     */
    public record WarehouseContent(List<ContainerObservation> observations, boolean complete,
                                   String error) {
        public WarehouseContent {
            observations = List.copyOf(observations);
            error = error == null ? "" : error;
            if (complete && !error.isBlank()) {
                throw new IllegalArgumentException("complete warehouse content cannot carry an error");
            }
        }

        public static WarehouseContent error(String message) {
            return new WarehouseContent(List.of(), false, message);
        }
    }

    /**
     * Per-physical-container observations for the referenced warehouses in one dimension.
     * COMPLETE is only allowed when every contributing warehouse is current and a non-empty
     * aggregate has container facts; otherwise the caller must not claim an authoritative
     * (possibly empty) container set.
     */
    public static WarehouseContent readWarehouseContainers(LegacyServerContext owner,
                                                           String projectId, String world)
            throws SQLException {
        if (world == null || world.isBlank()) {
            return WarehouseContent.error("当前维度未知");
        }
        CoreChecks.requireRegistryId(world, "warehouse world");
        return requireDatabase(owner).inTransaction(
                () -> readWarehouseContainersLocked(owner, projectId, world));
    }

    private static WarehouseContent readWarehouseContainersLocked(LegacyServerContext owner,
                                                                  String projectId, String world)
            throws SQLException {
        List<StagingAreaManager.Warehouse> warehouses = readReferencedWarehouses(owner, projectId)
                .stream().filter(warehouse -> world.equals(warehouse.world())).toList();
        if (warehouses.isEmpty()) {
            return new WarehouseContent(List.of(), true, "");
        }
        SchematicDatabase database = requireDatabase(owner);
        StagingAreaManager staging = owner.staging();
        Map<ContainerKey, Set<String>> items = new TreeMap<>(Comparator
                .comparing(ContainerKey::world)
                .thenComparingInt(ContainerKey::x)
                .thenComparingInt(ContainerKey::y)
                .thenComparingInt(ContainerKey::z));
        for (StagingAreaManager.Warehouse warehouse : warehouses) {
            if (staging == null || !staging.isWarehouseCurrent(warehouse.id())) {
                return WarehouseContent.error("仓库状态尚未确认");
            }
            long aggregate = areaTotal(database, "warehouse_inventory", "warehouse_id",
                    warehouse.id());
            int containers = 0;
            try (var rows = database.executeQuery(
                    "SELECT pos_x, pos_y, pos_z, item_id FROM container_inventory "
                            + "WHERE area_id = ? AND area_type = 'warehouse'",
                    warehouse.id())) {
                while (rows.next()) {
                    containers++;
                    ContainerKey key = new ContainerKey(world, rows.getInt("pos_x"),
                            rows.getInt("pos_y"), rows.getInt("pos_z"));
                    String item = rows.getString("item_id");
                    if (item != null && !item.isBlank()) {
                        items.computeIfAbsent(key, ignored -> new HashSet<>()).add(item);
                    }
                }
            }
            if (aggregate > 0 && containers == 0) {
                return WarehouseContent.error("仓库容器明细不可用");
            }
        }
        List<ContainerObservation> observations = new ArrayList<>(items.size());
        for (var source : items.entrySet()) {
            Map<String, Long> presence = new TreeMap<>();
            for (String itemId : source.getValue()) presence.put(itemId, 0L);
            observations.add(new ContainerObservation(source.getKey(), presence,
                    AreaInventoryWarning.STATUS_OPAQUE));
        }
        return new WarehouseContent(observations, true, "");
    }

    // ------------------------------------------------------------------

    private record ProjectMeta(String name, PlayerIdentity owner, boolean allowSelfClaim,
                               List<String> deputies) {
    }

    private static ProjectMeta readProject(SchematicDatabase database, String projectId)
            throws SQLException {
        String name;
        String uploadedBy;
        boolean allowSelfClaim;
        try (var rows = database.executeQuery(
                "SELECT name, uploaded_by, allow_self_claim FROM schematics WHERE id = ?",
                projectId)) {
            if (!rows.next()) {
                return null;
            }
            name = rows.getString("name");
            uploadedBy = rows.getString("uploaded_by");
            allowSelfClaim = rows.getInt("allow_self_claim") == 1;
        }
        List<String> deputies = new ArrayList<>();
        try (var rows = database.executeQuery(
                "SELECT player_name FROM deputy_owners WHERE schematic_id = ? ORDER BY player_name",
                projectId)) {
            while (rows.next()) {
                String deputy = rows.getString("player_name");
                if (deputy != null && !deputy.isBlank()) {
                    deputies.add(deputy);
                }
            }
        }
        String ownerName = uploadedBy == null || uploadedBy.isBlank() ? "legacy" : uploadedBy;
        return new ProjectMeta(name == null ? "" : name,
                new PlayerIdentity(null, ownerName), allowSelfClaim, List.copyOf(deputies));
    }

    private static List<MaterialSnapshot> readMaterials(SchematicDatabase database,
                                                        String projectId) throws SQLException {
        List<MaterialSnapshot> materials = new ArrayList<>();
        Set<String> itemIds = new HashSet<>();
        try (var rows = database.executeQuery(
                "SELECT id, item_id, count FROM material_entries "
                        + "WHERE schematic_id = ? ORDER BY id", projectId)) {
            while (rows.next()) {
                int materialId = rows.getInt("id");
                String itemId = rows.getString("item_id");
                long count = rows.getLong("count");
                if (count < 0) {
                    throw new IllegalArgumentException(
                            "material " + materialId + " has a negative requirement " + count);
                }
                if (!itemIds.add(itemId)) {
                    throw new IllegalArgumentException("duplicate material item id " + itemId);
                }
                materials.add(new MaterialSnapshot(materialId, itemId, count));
            }
        }
        return List.copyOf(materials);
    }

    /**
     * Claims are name-only in the legacy schema. A name that matches an online player is
     * resolved to that trusted UUID; everything else stays name-only and can never count an
     * observation.
     */
    private static List<ClaimSnapshot> readClaims(SchematicDatabase database, String projectId,
                                                  LegacyServerContext owner) throws SQLException {
        Map<String, ServerPlayer> online = new HashMap<>();
        if (owner.server() != null && owner.server().getPlayerList() != null) {
            for (ServerPlayer player : owner.server().getPlayerList().getPlayers()) {
                if (player != null && player.connection != null) {
                    online.put(player.getName().getString(), player);
                }
            }
        }
        List<ClaimSnapshot> claims = new ArrayList<>();
        try (var rows = database.executeQuery(
                "SELECT material_id, player_name FROM claims "
                        + "WHERE schematic_id = ? AND status = 'active' ORDER BY id", projectId)) {
            while (rows.next()) {
                String name = rows.getString("player_name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (name.length() > ProjectViewLimits.PLAYER_NAME_LENGTH) {
                    throw new IllegalArgumentException("claim player name exceeds "
                            + ProjectViewLimits.PLAYER_NAME_LENGTH + " characters");
                }
                ServerPlayer player = online.get(name);
                PlayerIdentity identity = player == null
                        ? new PlayerIdentity(null, name)
                        : new PlayerIdentity(player.getUUID(), player.getName().getString());
                claims.add(new ClaimSnapshot(rows.getInt("material_id"), identity));
            }
        }
        return List.copyOf(claims);
    }

    private record AreaFacts(List<AreaInventoryWarning> warnings, boolean current,
                             boolean overlap) {
    }

    private static AreaFacts readAreaFacts(SchematicDatabase database, LegacyServerContext owner,
                                           List<StagingAreaManager.StagingArea> stagingAreas,
                                           List<StagingAreaManager.Warehouse> warehouseAreas)
            throws SQLException {
        StagingAreaManager staging = owner.staging();
        List<AreaInventoryWarning> warnings = new ArrayList<>();
        boolean current = true;
        for (StagingAreaManager.StagingArea area : stagingAreas) {
            if (staging == null || !staging.isStagingAreaCurrent(area.id())) {
                warnings.add(warning(AreaKind.STAGING_AREA, area.id(), area.name()));
                current = false;
            }
        }
        for (StagingAreaManager.Warehouse warehouse : warehouseAreas) {
            if (staging == null || !staging.isWarehouseCurrent(warehouse.id())) {
                warnings.add(warning(AreaKind.WAREHOUSE, warehouse.id(), warehouse.name()));
                current = false;
            }
        }

        // Physical container facts are the only evidence that two aggregate sources are
        // distinct. Without counts per container we can never deduplicate overlapping
        // sources; in that case the view must stay explicitly unknown.
        Map<ContainerKey, Set<String>> containersByKey = new LinkedHashMap<>();
        Map<String, Set<ContainerKey>> containersByArea = new LinkedHashMap<>();
        readContainerKeys(database, stagingAreas, warehouseAreas, containersByKey, containersByArea);
        Set<String> overlapAreas = new java.util.LinkedHashSet<>();
        for (Map.Entry<ContainerKey, Set<String>> entry : containersByKey.entrySet()) {
            if (entry.getValue().size() > 1) {
                overlapAreas.addAll(entry.getValue());
            }
        }
        boolean overlap = !overlapAreas.isEmpty();
        if (overlap) {
            for (StagingAreaManager.StagingArea area : stagingAreas) {
                if (overlapAreas.contains("S:" + area.id())) {
                    warnings.add(new AreaInventoryWarning(AreaKind.STAGING_AREA, area.id(),
                            displayName(area.name()), AreaInventoryWarning.STATUS_OPAQUE));
                }
            }
            for (StagingAreaManager.Warehouse warehouse : warehouseAreas) {
                if (overlapAreas.contains("W:" + warehouse.id())) {
                    warnings.add(new AreaInventoryWarning(AreaKind.WAREHOUSE, warehouse.id(),
                            displayName(warehouse.name()), AreaInventoryWarning.STATUS_OPAQUE));
                }
            }
        }

        // A non-empty aggregate with no physical container fact cannot be attributed or
        // deduplicated. Mark that area opaque rather than confirming completion.
        for (StagingAreaManager.StagingArea area : stagingAreas) {
            long total = areaTotal(database, "staging_area_inventory", "staging_area_id", area.id());
            if (total > 0 && containersByArea.getOrDefault("S:" + area.id(), Set.of()).isEmpty()) {
                warnings.add(new AreaInventoryWarning(AreaKind.STAGING_AREA, area.id(),
                        displayName(area.name()), AreaInventoryWarning.STATUS_OPAQUE));
            }
        }
        for (StagingAreaManager.Warehouse warehouse : warehouseAreas) {
            long total = areaTotal(database, "warehouse_inventory", "warehouse_id", warehouse.id());
            if (total > 0 && containersByArea.getOrDefault("W:" + warehouse.id(), Set.of()).isEmpty()) {
                warnings.add(new AreaInventoryWarning(AreaKind.WAREHOUSE, warehouse.id(),
                        displayName(warehouse.name()), AreaInventoryWarning.STATUS_OPAQUE));
            }
        }

        Map<String, AreaInventoryWarning> unique = new LinkedHashMap<>();
        for (AreaInventoryWarning warning : warnings) {
            unique.putIfAbsent(warning.kind().wireName() + ":" + warning.areaId() + ":"
                    + warning.status(), warning);
        }
        List<AreaInventoryWarning> ordered = new ArrayList<>(unique.values());
        ordered.sort(Comparator.comparing((AreaInventoryWarning warning) -> warning.kind().wireName())
                .thenComparingInt(AreaInventoryWarning::areaId)
                .thenComparing(AreaInventoryWarning::status));
        return new AreaFacts(List.copyOf(ordered), current, overlap);
    }

    private static void readContainerKeys(SchematicDatabase database,
                                          List<StagingAreaManager.StagingArea> stagingAreas,
                                          List<StagingAreaManager.Warehouse> warehouseAreas,
                                          Map<ContainerKey, Set<String>> containersByKey,
                                          Map<String, Set<ContainerKey>> containersByArea)
            throws SQLException {
        for (StagingAreaManager.StagingArea area : stagingAreas) {
            String world = requireWorld(area.world());
            String areaKey = "S:" + area.id();
            try (var rows = database.executeQuery(
                    "SELECT pos_x, pos_y, pos_z FROM container_inventory "
                            + "WHERE area_id = ? AND area_type = 'staging_area'", area.id())) {
                while (rows.next()) {
                    ContainerKey key = new ContainerKey(world, rows.getInt("pos_x"),
                            rows.getInt("pos_y"), rows.getInt("pos_z"));
                    containersByKey.computeIfAbsent(key, ignored -> new java.util.LinkedHashSet<>())
                            .add(areaKey);
                    containersByArea.computeIfAbsent(areaKey, ignored -> new java.util.LinkedHashSet<>())
                            .add(key);
                }
            }
        }
        for (StagingAreaManager.Warehouse area : warehouseAreas) {
            String world = requireWorld(area.world());
            String areaKey = "W:" + area.id();
            try (var rows = database.executeQuery(
                    "SELECT pos_x, pos_y, pos_z FROM container_inventory "
                            + "WHERE area_id = ? AND area_type = 'warehouse'", area.id())) {
                while (rows.next()) {
                    ContainerKey key = new ContainerKey(world, rows.getInt("pos_x"),
                            rows.getInt("pos_y"), rows.getInt("pos_z"));
                    containersByKey.computeIfAbsent(key, ignored -> new java.util.LinkedHashSet<>())
                            .add(areaKey);
                    containersByArea.computeIfAbsent(areaKey, ignored -> new java.util.LinkedHashSet<>())
                            .add(key);
                }
            }
        }
    }

    private static long areaTotal(SchematicDatabase database, String table, String column,
                                  int areaId) throws SQLException {
        try (var rows = database.executeQuery(
                "SELECT COALESCE(SUM(count), 0) FROM " + table + " WHERE " + column + " = ?",
                areaId)) {
            if (!rows.next()) {
                return 0L;
            }
            long total = rows.getLong(1);
            if (total < 0) {
                throw new IllegalArgumentException("area " + areaId + " has a negative total");
            }
            return total;
        }
    }

    private static AreaInventoryWarning warning(AreaKind kind, int id, String name) {
        return new AreaInventoryWarning(kind, id, displayName(name),
                AreaInventoryWarning.STATUS_UNKNOWN);
    }

    private static String displayName(String name) {
        return name == null || name.isBlank() ? "未命名" : name;
    }

    private static String requireWorld(String world) {
        CoreChecks.requireRegistryId(world, "area world");
        return world;
    }

    private static SchematicDatabase requireDatabase(LegacyServerContext owner) throws SQLException {
        if (owner == null || owner.database() == null) {
            throw new SQLException("legacy database is unavailable");
        }
        return owner.database();
    }

    private static Map<String, Long> readItemTotals(SchematicDatabase database, String sql,
                                                    Object parameter) throws SQLException {
        Map<String, Long> totals = new TreeMap<>();
        try (var rows = database.executeQuery(sql, parameter)) {
            while (rows.next()) {
                String itemId = rows.getString("item_id");
                if (itemId == null || itemId.isBlank()) {
                    continue;
                }
                long total = rows.getLong("total");
                if (total < 0) {
                    throw new IllegalArgumentException("item " + itemId + " has a negative total " + total);
                }
                totals.put(itemId, total);
            }
        }
        return Map.copyOf(totals);
    }

    private static StagingAreaManager.Warehouse readWarehouse(SchematicDatabase.QueryResult rows)
            throws SQLException {
        return new StagingAreaManager.Warehouse(
                rows.getInt("id"), rows.getString("name"), rows.getString("world"),
                rows.getInt("x1"), rows.getInt("y1"), rows.getInt("z1"),
                rows.getInt("x2"), rows.getInt("y2"), rows.getInt("z2"));
    }

    private static long catalogFingerprint(String projectName, List<MaterialSnapshot> materials) {
        long hash = FNV_OFFSET;
        hash = mixString(hash, projectName == null ? "" : projectName);
        for (MaterialSnapshot material : materials) {
            hash = mixLong(hash, material.id());
            hash = mixString(hash, material.itemId());
            hash = mixLong(hash, material.required());
        }
        return hash;
    }

    private static long inventoryFingerprint(Map<String, Long> staging, Map<String, Long> warehouse,
                                             List<AreaInventoryWarning> warnings,
                                             boolean current, boolean overlap,
                                             List<StagingAreaManager.StagingArea> stagingAreas,
                                             List<StagingAreaManager.Warehouse> warehouseAreas) {
        long hash = FNV_OFFSET;
        for (Map.Entry<String, Long> entry : new TreeMap<>(staging).entrySet()) {
            hash = mixString(hash, entry.getKey());
            hash = mixLong(hash, entry.getValue());
        }
        for (Map.Entry<String, Long> entry : new TreeMap<>(warehouse).entrySet()) {
            hash = mixString(hash, entry.getKey());
            hash = mixLong(hash, entry.getValue());
        }
        hash = mixLong(hash, current ? 1 : 0);
        hash = mixLong(hash, overlap ? 1 : 0);
        for (StagingAreaManager.StagingArea area : stagingAreas) {
            hash = mixString(hash, "S");
            hash = mixLong(hash, area.id());
            hash = mixString(hash, area.world());
            hash = mixLong(hash, area.x1());
            hash = mixLong(hash, area.y1());
            hash = mixLong(hash, area.z1());
            hash = mixLong(hash, area.x2());
            hash = mixLong(hash, area.y2());
            hash = mixLong(hash, area.z2());
        }
        for (StagingAreaManager.Warehouse area : warehouseAreas) {
            hash = mixString(hash, "W");
            hash = mixLong(hash, area.id());
            hash = mixString(hash, area.world());
            hash = mixLong(hash, area.x1());
            hash = mixLong(hash, area.y1());
            hash = mixLong(hash, area.z1());
            hash = mixLong(hash, area.x2());
            hash = mixLong(hash, area.y2());
            hash = mixLong(hash, area.z2());
        }
        for (AreaInventoryWarning warning : warnings) {
            hash = mixString(hash, warning.kind().wireName());
            hash = mixLong(hash, warning.areaId());
            hash = mixString(hash, warning.status());
        }
        return hash;
    }

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static long mixLong(long hash, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            hash ^= (value >>> shift) & 0xffL;
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static long mixString(long hash, String value) {
        if (value == null) {
            return mixLong(hash, 0);
        }
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xffL;
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
