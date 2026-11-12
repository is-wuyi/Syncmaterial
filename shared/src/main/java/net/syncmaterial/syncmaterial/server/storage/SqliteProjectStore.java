package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectState;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQLite-backed project store for the Foundation backend.
 *
 * <p>One writer thread owns the only connection. All public futures complete on that
 * thread (callbacks run inline there, so callers must hop to their own executor before
 * touching the game world). Only the given path is opened; no legacy database is read.
 */
public final class SqliteProjectStore implements AutoCloseable {
    private static final String WRITER_THREAD_NAME = "syncmaterial-storage-writer";
    private static final String CLOSED_MESSAGE = "SqliteProjectStore is closed";
    private static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 30_000L;

    /** One live store per canonical database path; released only by finishClose/abortOpen. */
    private static final ConcurrentHashMap<Path, SqliteProjectStore> PATH_LEASES = new ConcurrentHashMap<>();

    private final Path dbPath;
    private final ExecutorService writer;
    private final FailureInjector failureInjector;
    private final long closeTimeoutMillis;
    private final AreaScanStore scanStore;
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final ReentrantLock gate = new ReentrantLock();

    /** Guarded by {@link #gate}; set before the close barrier is queued. */
    private boolean closing;

    private volatile Connection connection;
    private volatile Thread writerThread;
    private volatile boolean closed;

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private SqliteProjectStore(Path dbPath, FailureInjector failureInjector, long closeTimeoutMillis) {
        this.dbPath = dbPath;
        this.failureInjector = failureInjector;
        this.closeTimeoutMillis = closeTimeoutMillis;
        this.scanStore = new AreaScanStore(this);
        this.writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, WRITER_THREAD_NAME);
            thread.setDaemon(true);
            writerThread = thread;
            return thread;
        });
    }

    /** Opens (creating or migrating if needed) a store at exactly the given path. */
    public static SqliteProjectStore open(Path dbPath) {
        return open(dbPath, FailureInjector.NONE, DEFAULT_CLOSE_TIMEOUT_MILLIS);
    }

    /** Test seam: same as {@link #open(Path)} but with a hook that may fail a transaction. */
    static SqliteProjectStore openForTesting(Path dbPath, FailureInjector failureInjector) {
        return open(dbPath, failureInjector, DEFAULT_CLOSE_TIMEOUT_MILLIS);
    }

    /** Test seam with a short close deadline so tests never wait the real 30 seconds. */
    static SqliteProjectStore openForTesting(Path dbPath, FailureInjector failureInjector, long closeTimeoutMillis) {
        return open(dbPath, failureInjector, closeTimeoutMillis);
    }

    private static SqliteProjectStore open(Path dbPath, FailureInjector failureInjector, long closeTimeoutMillis) {
        Objects.requireNonNull(dbPath, "dbPath");
        Objects.requireNonNull(failureInjector, "failureInjector");
        if (closeTimeoutMillis <= 0) {
            throw new IllegalArgumentException("closeTimeoutMillis must be > 0");
        }

        Path canonicalPath = canonicalPath(dbPath);
        SqliteProjectStore store = new SqliteProjectStore(canonicalPath, failureInjector, closeTimeoutMillis);
        if (PATH_LEASES.putIfAbsent(canonicalPath, store) != null) {
            store.abortOpen();
            throw new StorageException(
                    "Database path is already open by another SqliteProjectStore: " + canonicalPath);
        }

        try {
            store.writer.submit(store::initialize).get();
            return store;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            store.abortOpen();
            throw new StorageException("Interrupted while opening " + canonicalPath, e);
        } catch (ExecutionException e) {
            store.abortOpen();
            Throwable cause = e.getCause();

            if (cause instanceof StorageException storageException) {
                throw storageException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new StorageException("Failed to open " + canonicalPath, cause);
        }
    }

    /**
     * Claims a stable absolute path. The nearest existing ancestor is resolved to its real
     * path so symlinked directories cannot alias one database; a direct symlink database
     * file is rejected.
     */
    private static Path canonicalPath(Path dbPath) {
        Path absolute = dbPath.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) {
            throw new StorageException("Symbolic-link database paths are not supported: " + absolute);
        }

        Path existing = absolute;
        Deque<Path> missing = new ArrayDeque<>();
        while (existing != null && !Files.exists(existing)) {
            Path fileName = existing.getFileName();
            if (fileName == null) {
                break;
            }
            missing.addFirst(fileName);
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }

        Path realExisting;
        try {
            realExisting = existing.toRealPath();
        } catch (IOException e) {
            throw new StorageException("Failed to resolve database path: " + absolute, e);
        }

        Path resolved = realExisting;
        for (Path segment : missing) {
            if (segment != null) {
                resolved = resolved.resolve(segment);
            }
        }
        resolved = resolved.normalize();

        if (Files.isSymbolicLink(resolved)) {
            throw new StorageException("Symbolic-link database paths are not supported: " + resolved);
        }
        return resolved;
    }

    // ========== Public async API ==========

    /**
     * Fixture-compatible ingest. Stored rows get the {@code unversioned} interpretation;
     * production callers must use {@link #ingest(SchematicMetadata, List, String)}.
     */
    public CompletableFuture<Void> ingest(SchematicMetadata metadata, List<MaterialCount> counts) {
        return ingest(metadata, counts, net.syncmaterial.syncmaterial.core.MaterialInterpretations.UNVERSIONED);
    }

    /**
     * Atomically replaces the project metadata and its material list for one material
     * interpretation. When the hash is unchanged but the interpretation changed, the
     * revision is bumped and material rows are diffed while surviving ids/claims stay.
     */
    public CompletableFuture<Void> ingest(SchematicMetadata metadata, List<MaterialCount> counts,
                                          String interpretationId) {
        try {
            Objects.requireNonNull(metadata, "metadata");
            requireNonBlank(interpretationId, "interpretationId");
            Map<String, Long> aggregated = ProjectWrites.aggregateCounts(counts);

            return submit("ingest", () -> inTransaction("ingest", connection -> {
                ProjectWrites.ingest(connection, metadata, aggregated, interpretationId);
                return null;
            }));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Finds one project, including materials, deputies and revision. */
    public CompletableFuture<Optional<ProjectSnapshot>> findProject(String projectId) {
        try {
            requireProjectId(projectId);
            return submit("findProject", () -> findProjectOnWriter(projectId));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /**
     * Reads project and claims in one writer task and one read transaction. Use this
     * instead of sequencing findProject then claims when both must share a revision.
     */
    public CompletableFuture<Optional<ProjectState>> snapshot(String projectId) {
        try {
            requireProjectId(projectId);
            return submit("snapshot", () -> snapshotOnWriter(projectId));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Lists all projects with full snapshots, ordered by project id. */
    public CompletableFuture<List<ProjectSnapshot>> listProjects() {
        return submit("listProjects", this::listProjectsOnWriter);
    }

    /** Adds a claim. Returns true when the player was newly added, false if already a member. */
    public CompletableFuture<Boolean> join(String projectId, int materialId, PlayerIdentity player) {
        try {
            requireProjectId(projectId);
            requireMaterialId(materialId);
            Objects.requireNonNull(player, "player");

            return submit("join", () -> inTransaction("join", connection ->
                    ProjectWrites.join(connection, projectId, materialId, player)));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Removes the claim and the matching inventory row. Returns true when a claim existed. */
    public CompletableFuture<Boolean> leave(String projectId, int materialId, PlayerIdentity player) {
        try {
            requireProjectId(projectId);
            requireMaterialId(materialId);
            Objects.requireNonNull(player, "player");

            return submit("leave", () -> inTransaction("leave", connection ->
                    ProjectWrites.leave(connection, projectId, materialId, player)));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /**
     * Records a player's inventory for a material. The player must already be a claimant
     * of exactly this project/material pair; otherwise the operation fails.
     */
    public CompletableFuture<Void> recordInventory(String projectId, int materialId, PlayerIdentity player, long count) {
        try {
            requireProjectId(projectId);
            requireMaterialId(materialId);
            Objects.requireNonNull(player, "player");
            if (count < 0) {
                throw new IllegalArgumentException("count must be >= 0");
            }

            return submit("recordInventory", () -> inTransaction("recordInventory", connection -> {
                ProjectWrites.recordInventory(connection, projectId, materialId, player, count);
                return null;
            }));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Returns the persisted material id to count map for one player in one project. */
    public CompletableFuture<Map<Integer, Long>> inventory(String projectId, PlayerIdentity player) {
        try {
            requireProjectId(projectId);
            Objects.requireNonNull(player, "player");

            return submit("inventory", () -> inventoryOnWriter(projectId, player));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Lists all claim rows of a project. Row existence alone means membership. */
    public CompletableFuture<List<ClaimSnapshot>> claims(String projectId) {
        try {
            requireProjectId(projectId);
            return submit("claims", () -> claimsOnWriter(projectId));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Deletes a project; materials, claims, inventories, deputies and project areas cascade. */
    public CompletableFuture<Void> removeProject(String projectId) {
        try {
            requireProjectId(projectId);
            return submit("removeProject", () -> inTransaction("removeProject", connection -> {
                ProjectWrites.removeProject(connection, projectId);
                return null;
            }));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    // ========== Areas (stable Foundation API) ==========

    /** All area configuration plus references and project names, read in one transaction. */
    public CompletableFuture<AreaCatalog> areaCatalog() {
        return readOnWriter("areaCatalog", AreaReads::catalog);
    }

    /** Adds an area. {@code projectId} must be null only for a global warehouse. */
    public CompletableFuture<AreaDefinition> addArea(String projectId, AreaKind kind, AreaSpec spec) {
        try {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(spec, "spec");
            String scope = projectId == null ? null : requireNonBlank(projectId, "projectId");
            return transactionOnWriter("addArea", connection ->
                    AreaWrites.addArea(connection, scope, kind, spec));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /**
     * Updates an existing area. Unknown ids, wrong kinds and project mismatches fail; a
     * geometry change invalidates that area's scan coverage, a rename does not.
     */
    public CompletableFuture<AreaDefinition> updateArea(String projectId, AreaKind kind, int areaId,
                                                        AreaSpec spec) {
        try {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(spec, "spec");
            requireAreaId(areaId);
            String scope = projectId == null ? null : requireNonBlank(projectId, "projectId");
            return transactionOnWriter("updateArea", connection ->
                    AreaWrites.updateArea(connection, scope, kind, areaId, spec));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Renames one staging area of a project; coverage survives because geometry is unchanged. */
    public CompletableFuture<AreaDefinition> renameArea(String projectId, int areaId, String name) {
        try {
            requireProjectId(projectId);
            requireAreaId(areaId);
            requireNonBlank(name, "name");
            return transactionOnWriter("renameArea", connection ->
                    AreaWrites.renameArea(connection, projectId, areaId, name));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Removes an area. Unknown ids, wrong kinds and project mismatches fail. */
    public CompletableFuture<Boolean> removeArea(String projectId, AreaKind kind, int areaId) {
        try {
            Objects.requireNonNull(kind, "kind");
            requireAreaId(areaId);
            String scope = projectId == null ? null : requireNonBlank(projectId, "projectId");
            return transactionOnWriter("removeArea", connection ->
                    AreaWrites.removeArea(connection, scope, kind, areaId));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Removes every staging area of a project and returns how many were deleted. */
    public CompletableFuture<Integer> clearStagingAreas(String projectId) {
        try {
            requireProjectId(projectId);
            return transactionOnWriter("clearStagingAreas", connection ->
                    AreaWrites.clearStagingAreas(connection, projectId));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Adds a project reference to a warehouse; returns false when the valid reference already exists. */
    public CompletableFuture<Boolean> addWarehouseReference(String projectId, int areaId) {
        try {
            requireProjectId(projectId);
            requireAreaId(areaId);
            return transactionOnWriter("addWarehouseReference", connection ->
                    AreaWrites.addWarehouseReference(connection, projectId, areaId));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Removes a project reference to a warehouse; returns false when it did not exist. */
    public CompletableFuture<Boolean> removeWarehouseReference(String projectId, int areaId) {
        try {
            requireProjectId(projectId);
            requireAreaId(areaId);
            return transactionOnWriter("removeWarehouseReference", connection ->
                    AreaWrites.removeWarehouseReference(connection, projectId, areaId));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /**
     * Effective referenced warehouse sources in one dimension: one observation per physical
     * source, excluding sources observed by this project's staging areas.
     */
    public CompletableFuture<List<ContainerObservation>> warehouseContainers(String projectId, String world) {
        try {
            requireProjectId(projectId);
            requireNonBlank(world, "world");
            return readOnWriter("warehouseContainers", connection ->
                    AreaReads.warehouseContainers(connection, projectId, world));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Internal scanner-facing store; see {@link AreaScanStore} for the token and CAS contract. */
    public AreaScanStore areaScanStore() {
        return scanStore;
    }

    // ========== Package-private test support (not a public SQL surface) ==========

    /**
     * Runs a fixture action on the writer thread inside a transaction. Tests use this to
     * seed owner/deputy/area rows; no SQL or connection is exposed publicly.
     */
    <T> CompletableFuture<T> runTestSupport(TestSupportWork<T> work) {
        try {
            Objects.requireNonNull(work, "work");
            return submit("testSupport", () -> inTransaction("testSupport", work::run));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Package-private seam for the scan store: one read transaction on the writer thread. */
    <T> CompletableFuture<T> readOnWriter(String operation, WriterWork<T> work) {
        try {
            Objects.requireNonNull(work, "work");
            return submit(operation, () -> inReadTransaction(requireOpenConnection(), work::run));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    /** Package-private seam for the scan store: one write transaction on the writer thread. */
    <T> CompletableFuture<T> transactionOnWriter(String operation, WriterWork<T> work) {
        try {
            Objects.requireNonNull(work, "work");
            return submit(operation, () -> inTransaction(operation, work::run));
        } catch (Throwable failure) {
            return failedFuture(failure);
        }
    }

    @FunctionalInterface
    interface WriterWork<T> {
        T run(Connection connection) throws SQLException;
    }

    // ========== Lifecycle ==========

    @Override
    public void close() {
        boolean self = Thread.currentThread() == writerThread;

        // One gate serializes submission with the close barrier: accepted jobs are queued
        // before finishClose, and every later submission sees closing. finishClose is the
        // only external-call-path that touches the connection, and it always runs on writer.
        gate.lock();
        try {
            if (closed) {
                return;
            }
            if (!closing) {
                closing = true;
                if (self) {
                    finishClose();
                    writer.shutdown();
                    return;
                }
                try {
                    writer.execute(this::finishClose);
                } catch (RejectedExecutionException e) {
                    throw new StorageException(
                            "Cannot enqueue close because the writer is already shut down; "
                                    + "path lease retained: " + dbPath, e);
                }
            }
        } finally {
            gate.unlock();
        }

        if (self) {
            // A close from the writer thread must never wait for its own queue.
            return;
        }

        try {
            awaitClosed();
        } finally {
            // Queued work is allowed to drain; finishClose still releases the path lease.
            writer.shutdown();
        }
    }

    private void initialize() {
        assertWriterThread();
        Connection opened = null;
        try {
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            int existingVersion = StoreSchema.readUserVersionReadOnly(dbPath);
            if (existingVersion > StoreSchema.SCHEMA_VERSION) {
                throw StoreSchema.unsupportedVersion(dbPath, existingVersion);
            }

            // Upgrade an existing store only after a consistent snapshot exists. Version 0
            // means a fresh/empty file, so there is nothing to preserve yet.
            boolean migrationBackedUp = false;
            if (existingVersion > 0 && existingVersion < StoreSchema.SCHEMA_VERSION) {
                StoreSchema.backupBeforeMigration(dbPath, existingVersion);
                migrationBackedUp = true;
            }

            opened = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            connection = opened;

            // Re-check before any PRAGMA so a raced future-version database is never modified.
            int actualVersion = StoreSchema.readUserVersion(opened);
            if (actualVersion > StoreSchema.SCHEMA_VERSION) {
                throw StoreSchema.unsupportedVersion(dbPath, actualVersion);
            }
            if (!migrationBackedUp && actualVersion > 0 && actualVersion < StoreSchema.SCHEMA_VERSION) {
                StoreSchema.backupBeforeMigration(dbPath, actualVersion);
            }

            StoreSchema.configureConnection(opened);
            if (actualVersion < StoreSchema.SCHEMA_VERSION) {
                StoreSchema.migrateSchema(opened);
            }
            StoreSchema.createTemporarySchema(opened);
        } catch (IOException | SQLException e) {
            closeQuietly(opened);
            connection = null;
            throw new StorageException("Failed to initialize storage at " + dbPath, e);
        } catch (RuntimeException e) {
            closeQuietly(opened);
            connection = null;
            throw e;
        }
    }

    private void abortOpen() {
        closeQuietly(connection);
        connection = null;
        PATH_LEASES.remove(dbPath, this);
        closedLatch.countDown();
        closed = true;
        writer.shutdownNow();
    }

    private void finishClose() {
        // Publish closed only after the native connection is gone and the path lease is
        // released, otherwise an early closed check could let a caller reopen too soon.
        closeQuietly(connection);
        connection = null;
        PATH_LEASES.remove(dbPath, this);
        closedLatch.countDown();
        closed = true;
    }

    private void awaitClosed() {
        if (closed) {
            return;
        }
        try {
            if (!closedLatch.await(closeTimeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new StorageException(
                        "Timed out after " + closeTimeoutMillis
                                + " ms waiting for SqliteProjectStore close; path lease retained: " + dbPath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException(
                    "Interrupted while waiting for SqliteProjectStore close; path lease retained: " + dbPath, e);
        }
    }

    // ========== Writer-thread writes ==========

    private Optional<ProjectSnapshot> findProjectOnWriter(String projectId) throws SQLException {
        Connection current = requireOpenConnection();
        return inReadTransaction(current, connection -> ProjectReads.findProject(connection, projectId));
    }

    private Optional<ProjectState> snapshotOnWriter(String projectId) throws SQLException {
        Connection current = requireOpenConnection();
        return inReadTransaction(current, connection -> {
            Optional<ProjectSnapshot> project = ProjectReads.findProject(connection, projectId);
            if (project.isEmpty()) {
                return Optional.empty();
            }
            List<ClaimSnapshot> claims = ProjectReads.claims(connection, projectId);
            AreaInventoryView inventory = AreaReads.projectInventory(connection, projectId);
            return Optional.of(new ProjectState(project.get(), claims, inventory));
        });
    }

    private List<ProjectSnapshot> listProjectsOnWriter() throws SQLException {
        Connection current = requireOpenConnection();
        return inReadTransaction(current, ProjectReads::listProjects);
    }

    private Map<Integer, Long> inventoryOnWriter(String projectId, PlayerIdentity player) throws SQLException {
        Connection current = requireOpenConnection();
        return inReadTransaction(
                current, connection -> ProjectReads.inventory(connection, projectId, player));
    }

    private List<ClaimSnapshot> claimsOnWriter(String projectId) throws SQLException {
        Connection current = requireOpenConnection();
        return inReadTransaction(current, connection -> ProjectReads.claims(connection, projectId));
    }

    // ========== SQL helpers ==========

    private <T> T inTransaction(String operation, SqlWork<T> work) throws SQLException {
        Connection current = requireOpenConnection();
        boolean restoreAutoCommit = current.getAutoCommit();
        if (restoreAutoCommit) {
            current.setAutoCommit(false);
        }

        try {
            T result = work.run(current);
            failureInjector.beforeCommit(operation);
            current.commit();
            return result;
        } catch (Throwable failure) {
            try {
                current.rollback();
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }

            if (failure instanceof SQLException sqlException) {
                throw sqlException;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new StorageException("Fault injection failed during " + operation, failure);
        } finally {
            if (restoreAutoCommit) {
                try {
                    current.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // The connection is no longer usable; later operations fail on their own.
                }
            }
        }
    }

    private static <T> T inReadTransaction(Connection current, SqlWork<T> work) throws SQLException {
        boolean restoreAutoCommit = current.getAutoCommit();
        if (restoreAutoCommit) {
            current.setAutoCommit(false);
        }

        try {
            T result = work.run(current);
            current.commit();
            return result;
        } catch (Throwable failure) {
            try {
                current.rollback();
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }

            if (failure instanceof SQLException sqlException) {
                throw sqlException;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new StorageException("Read transaction failed", failure);
        } finally {
            if (restoreAutoCommit) {
                try {
                    current.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // The connection is no longer usable; later operations fail on their own.
                }
            }
        }
    }

    private <T> CompletableFuture<T> submit(String operation, Callable<T> action) {
        gate.lock();
        try {
            if (closed || closing) {
                return failedFuture(closedException());
            }

            // Inline execution avoids self-deadlock when a completion callback running on
            // the writer thread calls back into the store. The previous transaction has
            // already committed by the time a future completes.
            if (Thread.currentThread() == writerThread) {
                try {
                    return CompletableFuture.completedFuture(action.call());
                } catch (SQLException sqlException) {
                    return failedFuture(new StorageException(operation + " failed", sqlException));
                } catch (Throwable failure) {
                    return failedFuture(failure);
                }
            }

            CompletableFuture<T> future = new CompletableFuture<>();
            try {
                writer.execute(() -> {
                    if (closed) {
                        future.completeExceptionally(closedException());
                        return;
                    }
                    try {
                        future.complete(action.call());
                    } catch (SQLException sqlException) {
                        future.completeExceptionally(new StorageException(operation + " failed", sqlException));
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                future.completeExceptionally(closedException());
            }
            return future;
        } finally {
            gate.unlock();
        }
    }

    private Connection requireOpenConnection() {
        assertWriterThread();
        Connection current = connection;
        if (closed || current == null) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
        return current;
    }

    private void assertWriterThread() {
        if (Thread.currentThread() != writerThread) {
            throw new IllegalStateException(
                    "SQLite store connection may only be used by the " + WRITER_THREAD_NAME + " thread");
        }
    }

    private static IllegalStateException closedException() {
        return new IllegalStateException(CLOSED_MESSAGE);
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    private static String requireProjectId(String projectId) {
        return requireNonBlank(projectId, "projectId");
    }

    private static int requireMaterialId(int materialId) {
        if (materialId <= 0) {
            throw new IllegalArgumentException("materialId must be > 0");
        }
        return materialId;
    }

    private static int requireAreaId(int areaId) {
        if (areaId <= 0) {
            throw new IllegalArgumentException("areaId must be > 0");
        }
        return areaId;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing is best-effort; the caller is already handling a failure or shutdown.
        }
    }

    /** Test-only seam: may throw to force rollback at the end of a transaction. */
    @FunctionalInterface
    interface FailureInjector {
        void beforeCommit(String operation) throws Exception;

        FailureInjector NONE = operation -> {
        };
    }

    /** Package-private test fixture callback; no public SQL surface. */
    @FunctionalInterface
    interface TestSupportWork<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

}
