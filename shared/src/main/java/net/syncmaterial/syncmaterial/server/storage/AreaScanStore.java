package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Internal scanner-facing persistence for area chunk coverage and canonical container counts.
 * All methods run on the store's writer thread and complete their futures there.
 *
 * <p>A token carries session epoch, area geometry revision, per-area chunk/dirty generation,
 * canonical chunk fence and global observation sequence. Apply operations reject stale
 * tokens without partial writes.</p>
 */
public final class AreaScanStore {
    private final SqliteProjectStore store;

    AreaScanStore(SqliteProjectStore store) {
        this.store = store;
    }

    public enum CoverageState {
        SCANNING,
        COMPLETE,
        STALE,
        DIRTY;

        private String storageName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static CoverageState fromStorage(String state) throws SQLException {
            for (CoverageState candidate : values()) {
                if (candidate.storageName().equals(state)) {
                    return candidate;
                }
            }
            throw new SQLException("invalid area chunk state: " + state);
        }
    }

    public record ChunkToken(
            long epoch,
            int areaId,
            long geometryRevision,
            ChunkKey chunk,
            long generation,
            long dirtyGeneration,
            long fenceGeneration,
            long observationSequence
    ) {
        public ChunkToken {
            Objects.requireNonNull(chunk, "chunk");
            if (epoch < 0) {
                throw new IllegalArgumentException("epoch must be >= 0");
            }
            if (areaId <= 0) {
                throw new IllegalArgumentException("areaId must be > 0");
            }
            if (geometryRevision < 0 || generation < 0 || dirtyGeneration < 0
                    || fenceGeneration < 0 || observationSequence < 0) {
                throw new IllegalArgumentException("token counters must be >= 0");
            }
        }
    }

    public record ChunkCoverage(
            ChunkKey chunk,
            long geometryRevision,
            CoverageState state,
            long generation,
            long dirtyGeneration,
            long updatedAt
    ) {
        public ChunkCoverage {
            Objects.requireNonNull(chunk, "chunk");
            Objects.requireNonNull(state, "state");
            if (geometryRevision < 0 || generation < 0 || dirtyGeneration < 0) {
                throw new IllegalArgumentException("coverage counters must be >= 0");
            }
        }
    }

    /** Starts a new scan session: stale every old coverage row and invalidate every old token. */
    public CompletableFuture<Long> beginEpoch() {
        return store.transactionOnWriter("areaScan.beginEpoch", connection -> {
            long epoch = StoreState.bumpScanEpoch(connection);
            staleAllAreas(connection, null, System.currentTimeMillis());
            StoreState.bumpInventoryRevision(connection);
            return epoch;
        });
    }

    public CompletableFuture<Long> currentEpoch() {
        return store.readOnWriter("areaScan.currentEpoch", StoreState::scanEpoch);
    }

    /** Current inventory-state revision; advance on every freshness/count commit, not config. */
    public CompletableFuture<Long> inventoryRevision() {
        return store.readOnWriter("areaScan.inventoryRevision", StoreState::inventoryRevision);
    }

    public CompletableFuture<List<ChunkCoverage>> coverage(int areaId) {
        if (areaId <= 0) {
            return failed(new IllegalArgumentException("areaId must be > 0"));
        }
        return store.readOnWriter("areaScan.coverage", connection -> {
            Optional<AreaDefinition> area = AreaReads.findArea(connection, areaId);
            if (area.isEmpty()) {
                return List.of();
            }
            List<ChunkCoverage> rows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT world, chunk_x, chunk_z, state, generation, dirty_generation, updated_at "
                            + "FROM area_chunks WHERE area_id = ? AND geometry_revision = ? "
                            + "ORDER BY world, chunk_x, chunk_z")) {
                select.setInt(1, areaId);
                select.setLong(2, area.get().geometryRevision());
                try (ResultSet resultSet = select.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new ChunkCoverage(
                                new ChunkKey(resultSet.getString(1), resultSet.getInt(2), resultSet.getInt(3)),
                                area.get().geometryRevision(),
                                CoverageState.fromStorage(resultSet.getString(4)),
                                resultSet.getLong(5),
                                resultSet.getLong(6),
                                resultSet.getLong(7)));
                    }
                }
            }
            return List.copyOf(rows);
        });
    }

    /**
     * Begins a full chunk scan. Empty when the area is gone, its geometry changed, the world
     * differs, or the chunk is outside current bounds.
     */
    public CompletableFuture<Optional<ChunkToken>> beginChunkScan(int areaId, long geometryRevision,
                                                                  ChunkKey chunk) {
        if (areaId <= 0 || geometryRevision < 0) {
            return failed(new IllegalArgumentException("areaId must be > 0 and geometryRevision >= 0"));
        }
        Objects.requireNonNull(chunk, "chunk");

        return store.transactionOnWriter("areaScan.beginChunkScan", connection -> {
            Optional<AreaDefinition> area = AreaReads.findArea(connection, areaId);
            if (area.isEmpty() || !tokenAreaMatches(area.get(), geometryRevision, chunk)) {
                return Optional.empty();
            }

            long epoch = StoreState.scanEpoch(connection);
            long fence = StoreState.chunkFence(connection, chunk);
            long sequence = StoreState.nextObservationSequence(connection);
            long now = System.currentTimeMillis();
            long generation;
            long dirtyGeneration;
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO area_chunks "
                            + "(area_id, geometry_revision, world, chunk_x, chunk_z, state, "
                            + "generation, dirty_generation, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'scanning', 1, 0, ?) "
                            + "ON CONFLICT(area_id, geometry_revision, world, chunk_x, chunk_z) DO UPDATE SET "
                            + "state = 'scanning', generation = area_chunks.generation + 1, "
                            + "updated_at = excluded.updated_at "
                            + "RETURNING generation, dirty_generation")) {
                upsert.setInt(1, areaId);
                upsert.setLong(2, geometryRevision);
                upsert.setString(3, chunk.world());
                upsert.setInt(4, chunk.x());
                upsert.setInt(5, chunk.z());
                upsert.setLong(6, now);
                try (ResultSet resultSet = upsert.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException("chunk scan begin returned no row");
                    }
                    generation = resultSet.getLong(1);
                    dirtyGeneration = resultSet.getLong(2);
                }
            }

            StoreState.bumpInventoryRevision(connection);
            return Optional.of(new ChunkToken(epoch, areaId, geometryRevision, chunk,
                    generation, dirtyGeneration, fence, sequence));
        });
    }

    /**
     * Atomically replaces all sources of the token's area/chunk. Empty observations remove
     * old sources, but cannot discard links backed by newer canonical observations.
     */
    public CompletableFuture<Boolean> applyChunkScan(ChunkToken token, List<ContainerObservation> observations) {
        Objects.requireNonNull(token, "token");
        final List<ContainerObservation> checked;
        try {
            checked = checkedObservations(token.chunk(), observations);
        } catch (Throwable failure) {
            return failed(failure);
        }

        return store.transactionOnWriter("areaScan.applyChunkScan", connection -> {
            AreaDefinition area = matchedArea(connection, token, CoverageState.SCANNING);
            if (area == null) {
                return false;
            }
            requireInsideArea(area, checked);

            long now = System.currentTimeMillis();
            Map<ContainerKey, LinkedContainer> previous = linkedContainers(connection, token);
            deleteChunkLinks(connection, token);

            Set<ContainerKey> observed = new HashSet<>();
            for (ContainerObservation observation : checked) {
                observed.add(observation.key());
                Long containerId = upsertObservation(connection, observation, token.observationSequence(), now);
                if (containerId != null) {
                    insertLink(connection, token, containerId);
                }
            }
            for (Map.Entry<ContainerKey, LinkedContainer> old : previous.entrySet()) {
                if (observed.contains(old.getKey())) {
                    continue;
                }
                LinkedContainer linked = old.getValue();
                if (linked.observationSequence() > token.observationSequence()) {
                    // The canonical update won the sequence race; its area link must survive too.
                    insertLink(connection, token, linked.containerId());
                } else {
                    applyRemoval(connection, old.getKey(), token.observationSequence(), now);
                }
            }

            if (updateChunkState(connection, token, CoverageState.COMPLETE, now) != 1) {
                throw new SQLException("chunk state update lost its CAS row");
            }
            AreaWrites.gcContainers(connection);
            StoreState.bumpInventoryRevision(connection);
            return true;
        });
    }

    /** Marks a started full scan as failed/stale when no result will be applied. */
    public CompletableFuture<Boolean> abandonChunkScan(ChunkToken token) {
        Objects.requireNonNull(token, "token");
        return store.transactionOnWriter("areaScan.abandonChunkScan", connection -> {
            if (matchedArea(connection, token, CoverageState.SCANNING) == null) {
                return false;
            }
            if (updateChunkState(connection, token, CoverageState.STALE, System.currentTimeMillis()) == 0) {
                return false;
            }
            StoreState.bumpInventoryRevision(connection);
            return true;
        });
    }

    /**
     * Source create/change/destroy fence. Bumps the canonical chunk fence once and marks the
     * coverage not-ready while preserving what can still be selectively updated:
     * COMPLETE -> DIRTY, DIRTY stays DIRTY, SCANNING -> STALE, STALE stays STALE. All row
     * generation/dirtyGeneration counters advance, so tokens issued before the event are
     * rejected. Last-known links are kept for projections.
     */
    public CompletableFuture<Integer> invalidateChunk(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return store.transactionOnWriter("areaScan.invalidateChunk", connection -> {
            long now = System.currentTimeMillis();
            StoreState.bumpChunkFence(connection, chunk);
            int rows;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE area_chunks SET "
                            + "state = CASE state WHEN 'complete' THEN 'dirty' "
                            + "WHEN 'scanning' THEN 'stale' ELSE state END, "
                            + "generation = generation + 1, dirty_generation = dirty_generation + 1, "
                            + "updated_at = ? "
                            + "WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
                update.setLong(1, now);
                update.setString(2, chunk.world());
                update.setInt(3, chunk.x());
                update.setInt(4, chunk.z());
                rows = update.executeUpdate();
            }
            StoreState.bumpInventoryRevision(connection);
            return rows;
        });
    }

    /**
     * Begins selected-source updates for one area. It only advances this area's local
     * generations and snapshots the canonical fence; scanner must call {@link
     * #invalidateChunk(ChunkKey)} once per source event so all intersecting areas share one
     * fence and each can still issue its own dirty token. Empty when the row is STALE or
     * absent (full scan required). If the row is still SCANNING (no invalidate call was made),
     * the fence is bumped and the row is staled so the in-flight scan can never commit.
     */
    public CompletableFuture<Optional<ChunkToken>> beginContainerUpdates(int areaId, long geometryRevision,
                                                                        ChunkKey chunk) {
        if (areaId <= 0 || geometryRevision < 0) {
            return failed(new IllegalArgumentException("areaId must be > 0 and geometryRevision >= 0"));
        }
        Objects.requireNonNull(chunk, "chunk");

        return store.transactionOnWriter("areaScan.beginContainerUpdates", connection -> {
            Optional<AreaDefinition> area = AreaReads.findArea(connection, areaId);
            if (area.isEmpty() || !tokenAreaMatches(area.get(), geometryRevision, chunk)) {
                return Optional.empty();
            }

            StoredCoverage row = loadCoverageRow(connection, areaId, geometryRevision, chunk);
            if (row == null) {
                return Optional.empty();
            }
            CoverageState state = CoverageState.fromStorage(row.state());
            if (state == CoverageState.STALE) {
                return Optional.empty();
            }
            if (state == CoverageState.SCANNING) {
                StoreState.bumpChunkFence(connection, chunk);
                staleTarget(connection, areaId, geometryRevision, chunk, System.currentTimeMillis());
                StoreState.bumpInventoryRevision(connection);
                return Optional.empty();
            }

            long now = System.currentTimeMillis();
            long fence = StoreState.chunkFence(connection, chunk);
            long generation;
            long dirtyGeneration;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE area_chunks SET state = 'dirty', generation = generation + 1, "
                            + "dirty_generation = dirty_generation + 1, updated_at = ? "
                            + "WHERE area_id = ? AND geometry_revision = ? AND world = ? "
                            + "AND chunk_x = ? AND chunk_z = ? AND state IN ('complete', 'dirty') "
                            + "RETURNING generation, dirty_generation")) {
                update.setLong(1, now);
                update.setInt(2, areaId);
                update.setLong(3, geometryRevision);
                update.setString(4, chunk.world());
                update.setInt(5, chunk.x());
                update.setInt(6, chunk.z());
                try (ResultSet resultSet = update.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException("dirty begin returned no row");
                    }
                    generation = resultSet.getLong(1);
                    dirtyGeneration = resultSet.getLong(2);
                }
            }
            long epoch = StoreState.scanEpoch(connection);
            long sequence = StoreState.nextObservationSequence(connection);
            StoreState.bumpInventoryRevision(connection);
            return Optional.of(new ChunkToken(epoch, areaId, geometryRevision, chunk,
                    generation, dirtyGeneration, fence, sequence));
        });
    }

    /** Applies selected-source upserts/removals without replacing unlisted links. */
    public CompletableFuture<Boolean> applyContainerUpdates(ChunkToken token,
                                                           List<ContainerObservation> upserts,
                                                           List<ContainerKey> removals) {
        Objects.requireNonNull(token, "token");
        final List<ContainerObservation> checkedUpserts;
        final List<ContainerKey> checkedRemovals;
        try {
            checkedUpserts = checkedObservations(token.chunk(), upserts);
            checkedRemovals = checkedRemovals(token.chunk(), removals);
            Set<ContainerKey> upsertKeys = new HashSet<>();
            for (ContainerObservation observation : checkedUpserts) {
                upsertKeys.add(observation.key());
            }
            for (ContainerKey removal : checkedRemovals) {
                if (upsertKeys.contains(removal)) {
                    throw new IllegalArgumentException("container is both upserted and removed: " + removal);
                }
            }
        } catch (Throwable failure) {
            return failed(failure);
        }

        return store.transactionOnWriter("areaScan.applyContainerUpdates", connection -> {
            AreaDefinition area = matchedArea(connection, token, CoverageState.DIRTY);
            if (area == null) {
                return false;
            }
            requireInsideArea(area, checkedUpserts);
            requireInsideArea(area, checkedRemovals);
            long now = System.currentTimeMillis();
            for (ContainerObservation observation : checkedUpserts) {
                Long containerId = upsertObservation(connection, observation, token.observationSequence(), now);
                if (containerId != null) {
                    insertLink(connection, token, containerId);
                }
            }
            for (ContainerKey removal : checkedRemovals) {
                applyRemoval(connection, removal, token.observationSequence(), now);
            }

            if (updateChunkState(connection, token, CoverageState.COMPLETE, now) != 1) {
                throw new SQLException("chunk state update lost its CAS row");
            }
            AreaWrites.gcContainers(connection);
            StoreState.bumpInventoryRevision(connection);
            return true;
        });
    }

    /** Chunk unload: keep last-known links, stale every area covering the chunk, invalidate tokens. */
    public CompletableFuture<Integer> markChunkStale(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return store.transactionOnWriter("areaScan.markChunkStale", connection -> {
            long now = System.currentTimeMillis();
            StoreState.bumpChunkFence(connection, chunk);
            int rows = staleAllAreas(connection, chunk, now);
            StoreState.bumpInventoryRevision(connection);
            return rows;
        });
    }

    private static boolean tokenAreaMatches(AreaDefinition area, long geometryRevision, ChunkKey chunk) {
        return area.geometryRevision() == geometryRevision
                && area.world().equals(chunk.world())
                && area.bounds().containsChunk(chunk.x(), chunk.z());
    }

    private static AreaDefinition matchedArea(Connection connection, ChunkToken token, CoverageState requiredState)
            throws SQLException {
        if (StoreState.scanEpoch(connection) != token.epoch()) {
            return null;
        }
        Optional<AreaDefinition> area = AreaReads.findArea(connection, token.areaId());
        if (area.isEmpty() || !tokenAreaMatches(area.get(), token.geometryRevision(), token.chunk())) {
            return null;
        }
        if (StoreState.chunkFence(connection, token.chunk()) != token.fenceGeneration()) {
            return null;
        }
        StoredCoverage row = loadCoverageRow(connection, token.areaId(), token.geometryRevision(), token.chunk());
        if (row == null
                || row.generation() != token.generation()
                || row.dirtyGeneration() != token.dirtyGeneration()
                || CoverageState.fromStorage(row.state()) != requiredState) {
            return null;
        }
        return area.get();
    }

    private static void requireInsideArea(AreaDefinition area, List<ContainerObservation> observations) {
        for (ContainerObservation observation : observations) {
            requireInsideArea(area, observation.key());
        }
    }

    private static void requireInsideArea(AreaDefinition area, ContainerKey key) {
        if (!area.world().equals(key.world()) || !area.bounds().contains(key)) {
            throw new IllegalArgumentException("container is outside area " + area.id() + ": " + key);
        }
    }

    private static void requireInsideArea(AreaDefinition area, Iterable<ContainerKey> keys) {
        for (ContainerKey key : keys) {
            requireInsideArea(area, key);
        }
    }

    private record StoredCoverage(long generation, long dirtyGeneration, String state) {
    }

    private static StoredCoverage loadCoverageRow(Connection connection, int areaId, long geometryRevision,
                                                  ChunkKey chunk) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT generation, dirty_generation, state FROM area_chunks "
                        + "WHERE area_id = ? AND geometry_revision = ? AND world = ? "
                        + "AND chunk_x = ? AND chunk_z = ?")) {
            select.setInt(1, areaId);
            select.setLong(2, geometryRevision);
            select.setString(3, chunk.world());
            select.setInt(4, chunk.x());
            select.setInt(5, chunk.z());
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new StoredCoverage(resultSet.getLong(1), resultSet.getLong(2), resultSet.getString(3));
            }
        }
    }

    private record LinkedContainer(long containerId, long observationSequence) {
    }

    private static Map<ContainerKey, LinkedContainer> linkedContainers(Connection connection, ChunkToken token)
            throws SQLException {
        Map<ContainerKey, LinkedContainer> previous = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT l.container_id, c.world, c.x, c.y, c.z, c.observation_sequence "
                        + "FROM area_chunk_containers l JOIN containers c ON c.id = l.container_id "
                        + "WHERE l.area_id = ? AND l.geometry_revision = ? AND l.world = ? "
                        + "AND l.chunk_x = ? AND l.chunk_z = ?")) {
            select.setInt(1, token.areaId());
            select.setLong(2, token.geometryRevision());
            select.setString(3, token.chunk().world());
            select.setInt(4, token.chunk().x());
            select.setInt(5, token.chunk().z());
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    previous.put(new ContainerKey(
                            resultSet.getString(2), resultSet.getInt(3), resultSet.getInt(4), resultSet.getInt(5)),
                            new LinkedContainer(resultSet.getLong(1), resultSet.getLong(6)));
                }
            }
        }
        return previous;
    }

    private static void deleteChunkLinks(Connection connection, ChunkToken token) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM area_chunk_containers WHERE area_id = ? AND geometry_revision = ? "
                        + "AND world = ? AND chunk_x = ? AND chunk_z = ?")) {
            delete.setInt(1, token.areaId());
            delete.setLong(2, token.geometryRevision());
            delete.setString(3, token.chunk().world());
            delete.setInt(4, token.chunk().x());
            delete.setInt(5, token.chunk().z());
            delete.executeUpdate();
        }
    }

    private static void insertLink(Connection connection, ChunkToken token, long containerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO area_chunk_containers "
                        + "(area_id, geometry_revision, world, chunk_x, chunk_z, container_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            insert.setInt(1, token.areaId());
            insert.setLong(2, token.geometryRevision());
            insert.setString(3, token.chunk().world());
            insert.setInt(4, token.chunk().x());
            insert.setInt(5, token.chunk().z());
            insert.setLong(6, containerId);
            insert.executeUpdate();
        }
    }

    private static int updateChunkState(Connection connection, ChunkToken token, CoverageState state, long now)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE area_chunks SET state = ?, updated_at = ? "
                        + "WHERE area_id = ? AND geometry_revision = ? AND world = ? "
                        + "AND chunk_x = ? AND chunk_z = ? AND generation = ? AND dirty_generation = ?")) {
            update.setString(1, state.storageName());
            update.setLong(2, now);
            update.setInt(3, token.areaId());
            update.setLong(4, token.geometryRevision());
            update.setString(5, token.chunk().world());
            update.setInt(6, token.chunk().x());
            update.setInt(7, token.chunk().z());
            update.setLong(8, token.generation());
            update.setLong(9, token.dirtyGeneration());
            return update.executeUpdate();
        }
    }

    private static int staleAllAreas(Connection connection, ChunkKey chunk, long now) throws SQLException {
        String sql = "UPDATE area_chunks SET state = 'stale', generation = generation + 1, "
                + "dirty_generation = dirty_generation + 1, updated_at = ?";
        if (chunk != null) {
            sql += " WHERE world = ? AND chunk_x = ? AND chunk_z = ?";
        }
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setLong(1, now);
            if (chunk != null) {
                update.setString(2, chunk.world());
                update.setInt(3, chunk.x());
                update.setInt(4, chunk.z());
            }
            return update.executeUpdate();
        }
    }

    private static void staleTarget(Connection connection, int areaId, long geometryRevision, ChunkKey chunk,
                                    long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE area_chunks SET state = 'stale', generation = generation + 1, "
                        + "dirty_generation = dirty_generation + 1, updated_at = ? "
                        + "WHERE area_id = ? AND geometry_revision = ? AND world = ? "
                        + "AND chunk_x = ? AND chunk_z = ?")) {
            update.setLong(1, now);
            update.setInt(2, areaId);
            update.setLong(3, geometryRevision);
            update.setString(4, chunk.world());
            update.setInt(5, chunk.x());
            update.setInt(6, chunk.z());
            update.executeUpdate();
        }
    }

    /**
     * Canonical write. A newer observation sequence always wins even if an older overlapping
     * area applies later; a newer tombstone prevents an older scan from resurrecting a source.
     */
    private static Long upsertObservation(Connection connection, ContainerObservation observation,
                                          long sequence, long now) throws SQLException {
        ContainerKey key = observation.key();
        Long existingId = null;
        long existingSequence = Long.MIN_VALUE;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, observation_sequence FROM containers "
                        + "WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            select.setString(1, key.world());
            select.setInt(2, key.x());
            select.setInt(3, key.y());
            select.setInt(4, key.z());
            try (ResultSet resultSet = select.executeQuery()) {
                if (resultSet.next()) {
                    existingId = resultSet.getLong(1);
                    existingSequence = resultSet.getLong(2);
                }
            }
        }
        Long tombstone = readTombstone(connection, key);

        if (existingId != null) {
            if (existingSequence > sequence) {
                return existingId;
            }
            if (existingSequence < sequence) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE containers SET warning = ?, observation_sequence = ?, updated_at = ? "
                                + "WHERE id = ?")) {
                    update.setString(1, observation.warning());
                    update.setLong(2, sequence);
                    update.setLong(3, now);
                    update.setLong(4, existingId);
                    update.executeUpdate();
                }
                replaceItems(connection, existingId, observation.counts());
            }
            if (tombstone != null && tombstone <= sequence) {
                deleteTombstone(connection, key);
            }
            return existingId;
        }

        if (tombstone != null && tombstone >= sequence) {
            return null;
        }

        long containerId;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO containers (world, x, y, z, warning, observation_sequence, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
            insert.setString(1, key.world());
            insert.setInt(2, key.x());
            insert.setInt(3, key.y());
            insert.setInt(4, key.z());
            insert.setString(5, observation.warning());
            insert.setLong(6, sequence);
            insert.setLong(7, now);
            try (ResultSet resultSet = insert.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("container insert returned no id");
                }
                containerId = resultSet.getLong(1);
            }
        }
        replaceItems(connection, containerId, observation.counts());
        deleteTombstone(connection, key);
        return containerId;
    }

    private static void replaceItems(Connection connection, long containerId, Map<String, Long> counts)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM container_items WHERE container_id = ?")) {
            delete.setLong(1, containerId);
            delete.executeUpdate();
        }
        if (counts.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO container_items (container_id, item_id, count) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                insert.setLong(1, containerId);
                insert.setString(2, entry.getKey());
                insert.setLong(3, entry.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Removes a source observed as destroyed. If a newer live observation already won, the
     * removal is ignored; otherwise the canonical row (and its links in every area) is gone.
     */
    private static void applyRemoval(Connection connection, ContainerKey key, long sequence, long now)
            throws SQLException {
        Long existingId = null;
        long existingSequence = Long.MIN_VALUE;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, observation_sequence FROM containers "
                        + "WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            select.setString(1, key.world());
            select.setInt(2, key.x());
            select.setInt(3, key.y());
            select.setInt(4, key.z());
            try (ResultSet resultSet = select.executeQuery()) {
                if (resultSet.next()) {
                    existingId = resultSet.getLong(1);
                    existingSequence = resultSet.getLong(2);
                }
            }
        }

        if (existingId != null) {
            if (existingSequence > sequence) {
                return;
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM containers WHERE id = ?")) {
                delete.setLong(1, existingId);
                delete.executeUpdate();
            }
        }

        Long tombstone = readTombstone(connection, key);
        if (tombstone == null || tombstone < sequence) {
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO container_tombstones "
                            + "(world, x, y, z, observation_sequence, updated_at) VALUES (?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(world, x, y, z) DO UPDATE SET "
                            + "observation_sequence = excluded.observation_sequence, "
                            + "updated_at = excluded.updated_at")) {
                upsert.setString(1, key.world());
                upsert.setInt(2, key.x());
                upsert.setInt(3, key.y());
                upsert.setInt(4, key.z());
                upsert.setLong(5, sequence);
                upsert.setLong(6, now);
                upsert.executeUpdate();
            }
        }
    }

    private static Long readTombstone(Connection connection, ContainerKey key) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT observation_sequence FROM container_tombstones "
                        + "WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            select.setString(1, key.world());
            select.setInt(2, key.x());
            select.setInt(3, key.y());
            select.setInt(4, key.z());
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private static void deleteTombstone(Connection connection, ContainerKey key) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM container_tombstones WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            delete.setString(1, key.world());
            delete.setInt(2, key.x());
            delete.setInt(3, key.y());
            delete.setInt(4, key.z());
            delete.executeUpdate();
        }
    }

    private static List<ContainerObservation> checkedObservations(ChunkKey chunk,
                                                                 List<ContainerObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        Map<ContainerKey, ContainerObservation> checked = new LinkedHashMap<>();
        for (ContainerObservation observation : observations) {
            Objects.requireNonNull(observation, "observation");
            if (!observation.key().chunk().equals(chunk)) {
                throw new IllegalArgumentException("observation is outside the token chunk: " + observation.key());
            }
            if (checked.putIfAbsent(observation.key(), observation) != null) {
                throw new IllegalArgumentException("duplicate container observation: " + observation.key());
            }
        }
        return List.copyOf(checked.values());
    }

    private static List<ContainerKey> checkedRemovals(ChunkKey chunk, List<ContainerKey> removals) {
        Objects.requireNonNull(removals, "removals");
        Set<ContainerKey> checked = new HashSet<>();
        for (ContainerKey key : removals) {
            Objects.requireNonNull(key, "removal");
            if (!key.chunk().equals(chunk)) {
                throw new IllegalArgumentException("removal is outside the token chunk: " + key);
            }
            if (!checked.add(key)) {
                throw new IllegalArgumentException("duplicate container removal: " + key);
            }
        }
        return List.copyOf(checked);
    }

    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }
}
