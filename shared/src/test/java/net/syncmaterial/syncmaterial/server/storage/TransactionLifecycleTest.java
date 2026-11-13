package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.core.TeamOutcome;
import net.syncmaterial.syncmaterial.core.TeamState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Transaction lifecycle tests for {@link SqliteProjectStore}.
 *
 * <p>Uses a real SQLite connection wrapped in a deterministic {@link Connection} fault
 * proxy. The proxy is installed only on the writer thread through the existing
 * package-private fixture hook; it throws on selected JDBC calls without changing the
 * store's public API. These tests cover commit/rollback/autoCommit failures, connection
 * discard, queued-job failure, path-lease release and inline writer submission ordering.</p>
 */
class TransactionLifecycleTest {
    private static final String STONE = "minecraft:stone";

    @TempDir
    Path tempDir;

    private Path dbPath;
    private SqliteProjectStore store;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("transaction-lifecycle.sqlite");
        store = SqliteProjectStore.open(dbPath);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void commitFailureRollbackSuccessRestoresAutoCommitAndKeepsConnectionUsable() throws Exception {
        seedProject("p1");
        FaultHandler faults = installFaultConnection();
        faults.failNextCommit.set(true);

        Throwable failure = failureOf(store.ingest(
                metadata("p2", "h2", named("Bob")), List.of(new MaterialCount(STONE, 4))));

        StorageException storage = assertInstanceOf(StorageException.class, failure);
        assertEquals("ingest failed", storage.getMessage());
        SQLException commitFailure = assertInstanceOf(SQLException.class, storage.getCause());
        assertTrue(commitFailure.getMessage().contains("injected commit failure"));
        assertEquals(0, commitFailure.getSuppressed().length,
                "a successful rollback has no rollback failure to suppress");

        assertEquals(1, faults.commitCalls.get());
        assertEquals(1, faults.rollbackCalls.get());
        assertEquals(1, faults.autoCommitTrueCalls.get(),
                "autoCommit must be restored after a successful rollback");
        assertFalse(faults.delegate.isClosed());
        assertSame(faults.proxy, connectionField(store),
                "the connection must stay usable after a successful rollback");

        assertTrue(await(store.findProject("p2")).isEmpty(),
                "a failed commit must never leave the transaction's rows behind");
        assertTrue(await(store.findProject("p1")).isPresent());

        await(store.ingest(metadata("p2", "h2", named("Bob")),
                List.of(new MaterialCount(STONE, 4))));
        assertTrue(await(store.findProject("p2")).isPresent(),
                "the same connection must serve later transactions");
    }

    @Test
    void commitFailureRollbackFailureDiscardsConnectionWithoutResidualCommit() throws Exception {
        seedProject("p1");
        FaultHandler faults = installFaultConnection();
        faults.failNextCommit.set(true);
        faults.failRollback.set(true);

        Throwable failure = failureOf(store.ingest(
                metadata("p2", "h2", named("Bob")), List.of(new MaterialCount(STONE, 4))));

        StorageException storage = assertInstanceOf(StorageException.class, failure);
        SQLException commitFailure = assertInstanceOf(SQLException.class, storage.getCause());
        assertTrue(commitFailure.getMessage().contains("injected commit failure"));
        assertEquals(1, commitFailure.getSuppressed().length,
                "the rollback failure must stay attached to the primary failure");
        SQLException rollbackFailure = assertInstanceOf(
                SQLException.class, commitFailure.getSuppressed()[0]);
        assertTrue(rollbackFailure.getMessage().contains("injected rollback failure"));

        assertEquals(1, faults.commitCalls.get());
        assertEquals(1, faults.rollbackCalls.get());
        assertEquals(0, faults.autoCommitTrueCalls.get(),
                "setAutoCommit(true) after a failed rollback would commit residual work");
        assertTrue(faults.delegate.isClosed(), "the discarded connection must be physically closed");
        assertEquals(1, faults.closeCalls.get());
        assertNull(connectionField(store), "no later job may reuse the failed connection");

        int callsAfterDiscard = faults.totalCalls.get();
        Throwable laterRead = failureOf(store.findProject("p1"));
        assertInstanceOf(StorageException.class, laterRead);
        assertTrue(laterRead.getMessage().contains("discarded"), laterRead.getMessage());
        Throwable laterWrite = failureOf(store.ingest(
                metadata("p3", "h3", named("Carol")), List.of(new MaterialCount(STONE, 1))));
        assertInstanceOf(StorageException.class, laterWrite);
        assertTrue(laterWrite.getMessage().contains("discarded"), laterWrite.getMessage());
        assertEquals(callsAfterDiscard, faults.totalCalls.get(),
                "a discarded connection must receive no later SQL or commit attempt");

        SqliteProjectStore sealedStore = store;
        store = null;
        sealedStore.close();

        try (SqliteProjectStore reopened = SqliteProjectStore.open(dbPath)) {
            assertTrue(await(reopened.findProject("p1")).isPresent());
            assertTrue(await(reopened.findProject("p2")).isEmpty(),
                    "closing the native connection must roll back the un-rolled-back transaction");
            assertTrue(await(reopened.findProject("p3")).isEmpty());
        }
    }

    @Test
    void readTransactionRollbackFailureDiscardsConnection() throws Exception {
        FaultHandler faults = installFaultConnection();
        faults.failRollback.set(true);

        Throwable failure = failureOf(store.readOnWriter("faultyRead", connection -> {
            throw new SQLException("injected read work failure");
        }));

        StorageException storage = assertInstanceOf(StorageException.class, failure);
        assertEquals("faultyRead failed", storage.getMessage());
        SQLException primary = assertInstanceOf(SQLException.class, storage.getCause());
        assertTrue(primary.getMessage().contains("injected read work failure"));
        assertEquals(1, primary.getSuppressed().length,
                "the read-path rollback failure must be preserved as suppressed");
        SQLException rollbackFailure = assertInstanceOf(SQLException.class, primary.getSuppressed()[0]);
        assertTrue(rollbackFailure.getMessage().contains("injected rollback failure"));

        assertEquals(0, faults.autoCommitTrueCalls.get(),
                "the read path must not re-enable autoCommit after a failed rollback");
        assertTrue(faults.delegate.isClosed());
        assertNull(connectionField(store));

        Throwable later = failureOf(store.claims("p1"));
        assertInstanceOf(StorageException.class, later);
        assertTrue(later.getMessage().contains("discarded"), later.getMessage());

        SqliteProjectStore sealedStore = store;
        store = null;
        sealedStore.close();

        try (SqliteProjectStore reopened = SqliteProjectStore.open(dbPath)) {
            assertTrue(await(reopened.findProject("p1")).isEmpty());
        }
    }

    @Test
    void getAutoCommitFailureDiscardsConnectionBeforeAnyWork() throws Exception {
        seedProject("p1");
        FaultHandler faults = installFaultConnection();
        faults.failGetAutoCommit.set(true);

        Throwable failure = failureOf(store.findProject("p1"));

        StorageException storage = assertInstanceOf(StorageException.class, failure);
        assertEquals("findProject failed", storage.getMessage());
        SQLException primary = assertInstanceOf(SQLException.class, storage.getCause());
        assertTrue(primary.getMessage().contains("injected getAutoCommit failure"));

        assertEquals(1, faults.getAutoCommitCalls.get());
        assertEquals(0, faults.autoCommitFalseCalls.get());
        assertEquals(0, faults.commitCalls.get());
        assertEquals(0, faults.rollbackCalls.get());
        assertEquals(0, faults.autoCommitTrueCalls.get());
        assertTrue(faults.delegate.isClosed());
        assertNull(connectionField(store));

        int callsAfterDiscard = faults.totalCalls.get();
        Throwable later = failureOf(store.claims("p1"));
        assertInstanceOf(StorageException.class, later);
        assertTrue(later.getMessage().contains("discarded"), later.getMessage());
        assertEquals(callsAfterDiscard, faults.totalCalls.get(),
                "a connection with unknown autoCommit state must receive no later SQL");

        SqliteProjectStore sealedStore = store;
        store = null;
        sealedStore.close();

        try (SqliteProjectStore reopened = SqliteProjectStore.open(dbPath)) {
            assertTrue(await(reopened.findProject("p1")).isPresent());
        }
    }

    @Test
    void setAutoCommitFalseFailureDiscardsConnectionBeforeAnyWork() throws Exception {
        seedProject("p1");
        FaultHandler faults = installFaultConnection();
        faults.failNextAutoCommitFalse.set(true);

        Throwable failure = failureOf(store.ingest(
                metadata("p2", "h2", named("Bob")), List.of(new MaterialCount(STONE, 4))));

        StorageException storage = assertInstanceOf(StorageException.class, failure);
        assertEquals("ingest failed", storage.getMessage());
        SQLException primary = assertInstanceOf(SQLException.class, storage.getCause());
        assertTrue(primary.getMessage().contains("injected setAutoCommit(false) failure"));

        assertEquals(1, faults.autoCommitFalseCalls.get());
        assertEquals(0, faults.commitCalls.get());
        assertEquals(0, faults.rollbackCalls.get());
        assertEquals(0, faults.autoCommitTrueCalls.get());
        assertTrue(faults.delegate.isClosed());
        assertNull(connectionField(store));

        SqliteProjectStore sealedStore = store;
        store = null;
        sealedStore.close();

        try (SqliteProjectStore reopened = SqliteProjectStore.open(dbPath)) {
            assertTrue(await(reopened.findProject("p1")).isPresent());
            assertTrue(await(reopened.findProject("p2")).isEmpty(),
                    "no work may run when the transaction never started");
        }
    }

    @Test
    void autoCommitRestoreFailureFailsCommittedOperationAndSealsConnection() throws Exception {
        seedProject("p1");
        FaultHandler faults = installFaultConnection();
        faults.failAutoCommitRestore.set(true);

        Throwable failure = failureOf(store.ingest(
                metadata("p2", "h2", named("Bob")), List.of(new MaterialCount(STONE, 4))));

        CommittedStorageException committed = assertInstanceOf(CommittedStorageException.class, failure);
        assertEquals("ingest", committed.operation());
        assertTrue(committed.committed());
        assertNull(committed.committedResult(Void.class));
        assertTrue(committed.committedResult().isEmpty());
        assertTrue(committed.getMessage().contains("restore autoCommit"),
                "a restore failure must surface as the operation failure: " + committed.getMessage());
        SQLException restoreFailure = assertInstanceOf(SQLException.class, committed.getCause());
        assertTrue(restoreFailure.getMessage().contains("injected autoCommit restore failure"));

        assertEquals(0, faults.rollbackCalls.get(),
                "a committed transaction must not be rolled back after the commit succeeded");
        assertEquals(1, faults.commitCalls.get(), "the transaction committed before restoration failed");
        assertEquals(1, faults.autoCommitTrueCalls.get());
        assertTrue(faults.delegate.isClosed());
        assertNull(connectionField(store));

        int callsAfterDiscard = faults.totalCalls.get();
        Throwable later = failureOf(store.findProject("p2"));
        assertInstanceOf(StorageException.class, later);
        assertTrue(later.getMessage().contains("discarded"), later.getMessage());
        assertEquals(callsAfterDiscard, faults.totalCalls.get(),
                "an unknown autoCommit mode must not serve later SQL");

        SqliteProjectStore sealedStore = store;
        store = null;
        sealedStore.close();

        try (SqliteProjectStore reopened = SqliteProjectStore.open(dbPath)) {
            assertTrue(await(reopened.findProject("p1")).isPresent());
            assertTrue(await(reopened.findProject("p2")).isPresent(),
                    "the already successful commit stays durable; only the restore failed");
        }
    }

    @Test
    void committedStorageExceptionCarriesExactTeamOutcomeWithoutReplay() throws Exception {
        seedProject("p1");
        FaultHandler faults = installFaultConnection();
        faults.failAutoCommitRestore.set(true);
        AtomicInteger workRuns = new AtomicInteger();
        TeamState state = new TeamState("p1", named("Alice"), List.of(), true);
        TeamOutcome outcome = new TeamOutcome(
                TeamOutcome.Status.SUCCESS, state, 1, 1, Set.of(1), List.of());

        Throwable failure = failureOf(store.runTestSupport(connection -> {
            workRuns.incrementAndGet();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO players (uuid, name) VALUES (NULL, 'wp5-committed-team')")) {
                statement.executeUpdate();
            }
            return outcome;
        }));

        CommittedStorageException committed = assertInstanceOf(CommittedStorageException.class, failure);
        assertEquals("testSupport", committed.operation());
        assertTrue(committed.committed());
        assertSame(outcome, committed.committedResult(TeamOutcome.class));
        assertSame(outcome, committed.committedResult().orElseThrow());
        assertEquals(1, workRuns.get(), "the committed transaction must be applied exactly once");
        assertEquals(1, faults.commitCalls.get());
        assertEquals(0, faults.rollbackCalls.get(),
                "the durable commit must not be undone when connection recovery fails");
        assertTrue(faults.delegate.isClosed());
        assertNull(connectionField(store));

        SqliteProjectStore sealedStore = store;
        store = null;
        sealedStore.close();

        try (SqliteProjectStore reopened = SqliteProjectStore.open(dbPath)) {
            assertTrue(await(reopened.findProject("p1")).isPresent());
            Integer persisted = await(reopened.runTestSupport(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM players WHERE name = 'wp5-committed-team'")) {
                    try (ResultSet resultSet = statement.executeQuery()) {
                        resultSet.next();
                        return resultSet.getInt(1);
                    }
                }
            }));
            assertEquals(1, persisted, "the committed write must be durable exactly once");
        }
    }

    @Test
    void rollbackSuccessThenAutoCommitRestoreFailureKeepsPrimaryAndSuppressedFailures() throws Exception {
        seedProject("p1");
        FaultHandler faults = installFaultConnection();
        faults.failNextCommit.set(true);
        faults.failAutoCommitRestore.set(true);

        Throwable failure = failureOf(store.ingest(
                metadata("p2", "h2", named("Bob")), List.of(new MaterialCount(STONE, 4))));

        StorageException storage = assertInstanceOf(StorageException.class, failure);
        assertFalse(failure instanceof CommittedStorageException,
                "an uncertain commit failure must never be marked committed");
        SQLException commitFailure = assertInstanceOf(SQLException.class, storage.getCause());
        assertTrue(commitFailure.getMessage().contains("injected commit failure"));
        assertEquals(1, commitFailure.getSuppressed().length);
        assertTrue(assertInstanceOf(SQLException.class, commitFailure.getSuppressed()[0])
                .getMessage().contains("injected autoCommit restore failure"));

        assertEquals(1, faults.rollbackCalls.get());
        assertEquals(1, faults.autoCommitTrueCalls.get());
        assertTrue(faults.delegate.isClosed());
        assertNull(connectionField(store));

        SqliteProjectStore sealedStore = store;
        store = null;
        sealedStore.close();

        try (SqliteProjectStore reopened = SqliteProjectStore.open(dbPath)) {
            assertTrue(await(reopened.findProject("p1")).isPresent());
            assertTrue(await(reopened.findProject("p2")).isEmpty(),
                    "the successful rollback result must still be discarded");
        }
    }

    @Test
    void queuedOperationsAfterDiscardFailAndPathReopensAfterPhysicalClose() throws Exception {
        seedProject("p1");
        FaultHandler faults = installFaultConnection();
        faults.failNextCommit.set(true);
        faults.failRollback.set(true);
        faults.blockInjectedCommit = true;

        CompletableFuture<Void> failing = store.ingest(
                metadata("p2", "h2", named("Bob")), List.of(new MaterialCount(STONE, 4)));
        assertTrue(faults.commitEntered.await(5, TimeUnit.SECONDS),
                "the failing transaction must be inside commit before queuing the other jobs");

        CompletableFuture<Optional<ProjectSnapshot>> queuedRead = store.findProject("p1");
        CompletableFuture<List<ProjectSnapshot>> queuedList = store.listProjects();
        CompletableFuture<Void> queuedWrite = store.ingest(
                metadata("p3", "h3", named("Carol")), List.of(new MaterialCount(STONE, 1)));
        faults.releaseInjectedCommit.countDown();

        assertInstanceOf(StorageException.class, failureOf(failing));
        for (CompletableFuture<?> queued : List.of(queuedRead, queuedList, queuedWrite)) {
            Throwable queuedFailure = failureOf(queued);
            assertInstanceOf(StorageException.class, queuedFailure);
            assertTrue(queuedFailure.getMessage().contains("discarded"), queuedFailure.getMessage());
        }

        assertTrue(faults.delegate.isClosed(), "the native connection must be closed before close()");
        assertEquals(1, faults.closeCalls.get());

        SqliteProjectStore sealedStore = store;
        store = null;
        sealedStore.close();
        sealedStore.close();

        try (SqliteProjectStore reopened = SqliteProjectStore.open(dbPath)) {
            assertTrue(await(reopened.findProject("p1")).isPresent());
            assertTrue(await(reopened.findProject("p2")).isEmpty());
            assertTrue(await(reopened.findProject("p3")).isEmpty());
        }
    }

    @Test
    void inlineWriterSubmissionReleasesGateBeforeRunningWork() throws Exception {
        Object ingestMonitor = new Object();
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch writerAttemptingMonitor = new CountDownLatch(1);
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        AtomicReference<Throwable> monitorFailure = new AtomicReference<>();
        AtomicReference<Optional<ProjectSnapshot>> monitorRead = new AtomicReference<>();

        Thread monitorHolder = new Thread(() -> {
            try {
                CompletableFuture<Optional<ProjectSnapshot>> read;
                synchronized (ingestMonitor) {
                    monitorHeld.countDown();
                    if (!writerAttemptingMonitor.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("writer never attempted the monitor");
                    }
                    // Monitor -> store submission, mirroring IngestService.ingest.
                    read = store.findProject("held-read");
                }
                monitorRead.set(read.get(5, TimeUnit.SECONDS));
            } catch (Throwable failure) {
                monitorFailure.set(failure);
            }
        }, "transaction-lifecycle-monitor-holder");
        monitorHolder.setDaemon(true);
        monitorHolder.start();

        CompletableFuture<Void> writerCallback = store.readOnWriter("deadlockTrigger", connection -> {
            readStarted.countDown();
            try {
                if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                    throw new SQLException("trigger read was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("trigger read interrupted", e);
            }
            return null;
        }).thenRun(() -> {
            try {
                if (!monitorHeld.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("monitor holder never started");
                }
                // Inline submission from the writer thread; its work takes the external
                // monitor, reproducing the ingest-monitor <-> store-gate lock ordering.
                store.runTestSupport(connection -> {
                    writerAttemptingMonitor.countDown();
                    synchronized (ingestMonitor) {
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });

        assertTrue(readStarted.await(5, TimeUnit.SECONDS));
        releaseRead.countDown();

        try {
            writerCallback.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            throw new AssertionError("inline writer submission failed: " + cause, cause);
        } catch (TimeoutException timeout) {
            fail("inline writer submission deadlocked while holding the store gate");
        } finally {
            writerAttemptingMonitor.countDown();
            monitorHolder.join(5_000L);
        }

        assertFalse(monitorHolder.isAlive(), "submission while holding an external monitor deadlocked");
        assertNull(monitorFailure.get(), () -> "monitor holder failed: " + monitorFailure.get());
        assertTrue(monitorRead.get().isEmpty(), "the queued read must still run after the callback");
    }

    @Test
    void nativeCloseFailureDuringDiscardIsRetriedByCloseAndLeaseIsReleased() throws Exception {
        seedProject("p1");
        FaultHandler faults = installFaultConnection();
        faults.failNextCommit.set(true);
        faults.failRollback.set(true);
        faults.failNextClose.set(true);

        Throwable failure = failureOf(store.ingest(
                metadata("p2", "h2", named("Bob")), List.of(new MaterialCount(STONE, 4))));

        StorageException storage = assertInstanceOf(StorageException.class, failure);
        SQLException primary = assertInstanceOf(SQLException.class, storage.getCause());
        assertEquals(2, primary.getSuppressed().length,
                "both the rollback failure and the failed native close must be preserved");
        assertTrue(primary.getSuppressed()[0].getMessage().contains("injected rollback failure"));
        assertTrue(primary.getSuppressed()[1].getMessage().contains("injected close failure"));

        assertFalse(faults.delegate.isClosed(),
                "the injected close failure must leave the native handle open for retry");
        assertEquals(1, faults.closeCalls.get());
        assertNull(connectionField(store));
        StorageException lease = assertThrows(StorageException.class,
                () -> SqliteProjectStore.open(dbPath));
        assertTrue(lease.getMessage().contains("already open"));

        store.close();
        store = null;

        assertTrue(faults.delegate.isClosed(), "close() must retry the retained native handle");
        assertEquals(2, faults.closeCalls.get());
        try (SqliteProjectStore reopened = SqliteProjectStore.open(dbPath)) {
            assertTrue(await(reopened.findProject("p1")).isPresent());
            assertTrue(await(reopened.findProject("p2")).isEmpty());
        }
    }

    @Test
    void persistentNativeCloseFailureRetainsLeaseUntilExplicitCloseRetry() throws Exception {
        seedProject("p1");
        FaultHandler faults = installFaultConnection();
        faults.failNextCommit.set(true);
        faults.failRollback.set(true);
        faults.failClose.set(true);

        assertInstanceOf(StorageException.class, failureOf(store.ingest(
                metadata("p2", "h2", named("Bob")), List.of(new MaterialCount(STONE, 4)))));

        assertFalse(faults.delegate.isClosed());
        assertNull(connectionField(store));

        StorageException closeFailure = assertThrows(StorageException.class, store::close);
        assertTrue(closeFailure.getMessage().contains("path lease retained"),
                closeFailure.getMessage());
        SQLException nativeCloseFailure = assertInstanceOf(SQLException.class, closeFailure.getCause());
        assertTrue(nativeCloseFailure.getMessage().contains("injected close failure"));

        assertFalse(faults.delegate.isClosed(),
                "the native connection is still open after the failed close");
        assertEquals(2, faults.closeCalls.get(), "discard close plus the finishClose retry");

        StorageException lease = assertThrows(StorageException.class,
                () -> SqliteProjectStore.open(dbPath));
        assertTrue(lease.getMessage().contains("already open"),
                "the lease must be retained while the native connection is live");

        // The writer stays alive after a physical close failure. Once the fault clears an
        // explicit close() retries the retained handle and only then releases the lease.
        faults.failClose.set(false);
        store.close();
        store = null;

        assertTrue(faults.delegate.isClosed(), "the explicit retry must physically close the handle");
        assertEquals(3, faults.closeCalls.get(),
                "discard close, failed finishClose, successful explicit retry");
        try (SqliteProjectStore reopened = SqliteProjectStore.open(dbPath)) {
            assertTrue(await(reopened.findProject("p1")).isPresent());
            assertTrue(await(reopened.findProject("p2")).isEmpty());
        }
    }

    @Test
    void concurrentCloseCallersShareSingleRetryAttempt() throws Exception {
        seedProject("p1");
        FaultHandler faults = installFaultConnection();
        faults.failClose.set(true);
        assertThrows(StorageException.class, store::close);
        assertFalse(faults.delegate.isClosed());
        assertEquals(1, faults.closeCalls.get());

        faults.failClose.set(false);
        faults.blockNextClose.set(true);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread first = new Thread(() -> {
            try {
                store.close();
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        }, "transaction-lifecycle-close-1");
        first.setDaemon(true);
        first.start();
        assertTrue(faults.closeEntered.await(5, TimeUnit.SECONDS),
                "the single retry must reach the native close");
        assertEquals(2, faults.closeCalls.get(), "exactly one retry may be started");
        Object inFlightAttempt = closeLatchField(store);

        Thread second = new Thread(() -> {
            secondStarted.countDown();
            try {
                store.close();
            } catch (Throwable failure) {
                secondFailure.set(failure);
            }
        }, "transaction-lifecycle-close-2");
        second.setDaemon(true);
        second.start();
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
        awaitThreadWaiting(second, 5_000L);
        assertSame(inFlightAttempt, closeLatchField(store),
                "a concurrent caller must join the in-flight retry, not replace its latch");
        assertTrue(first.isAlive(), "the first caller must wait for the blocked retry");
        assertTrue(second.isAlive(), "the second caller must wait for the blocked retry");

        faults.releaseClose.countDown();
        first.join(5_000L);
        second.join(5_000L);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertNull(firstFailure.get(), () -> "first close failed: " + firstFailure.get());
        assertNull(secondFailure.get(), () -> "second close failed: " + secondFailure.get());

        assertEquals(2, faults.closeCalls.get(), "only one physical retry may run");
        assertTrue(faults.delegate.isClosed());
        store = null;
        try (SqliteProjectStore reopened = SqliteProjectStore.open(dbPath)) {
            assertTrue(await(reopened.findProject("p1")).isPresent());
        }
    }

    @Test
    void committedKickStillClearsRuntimeObservationWhenRestoreFails() throws Exception {
        Path game = tempDir.resolve("game");
        var runtime = net.syncmaterial.syncmaterial.server.runtime.ServerRuntime.open(tempDir.resolve("world"), game,
                path -> List.of(new MaterialCount(STONE, 64)), ignored -> {});
        SqliteProjectStore previous = store;
        store = runtime.store();
        var owner = new PlayerIdentity(java.util.UUID.randomUUID(), "Owner");
        var target = new PlayerIdentity(java.util.UUID.randomUUID(), "Target");
        try {
            await(store.ingest(metadata("team", "hash", owner), List.of(new MaterialCount(STONE, 64)), runtime.interpretationId()));
            int material = await(store.findProject("team")).orElseThrow().materials().getFirst().id();
            await(store.join("team", material, target));
            runtime.connected(target.uuid());
            await(runtime.reportInventory("team", material, target, 12));
            FaultHandler faults = installFaultConnection();
            faults.failAutoCommitRestore.set(true);
            Throwable failure = failureOf(runtime.teams().execute(new net.syncmaterial.syncmaterial.core.TeamCommand(
                    "team", 1, net.syncmaterial.syncmaterial.core.TeamAction.KICK, -1, List.of(material), List.of(target)),
                    owner, () -> true));
            CommittedStorageException committed = assertInstanceOf(CommittedStorageException.class, failure);
            assertTrue(committed.committedResult(net.syncmaterial.syncmaterial.core.TeamOutcome.class).success());
            assertEquals(0, runtime.inventoryCount(target.uuid(), "team", material));
            assertTrue(faults.delegate.isClosed());
        } finally {
            runtime.close();
            store = previous;
        }
        try (SqliteProjectStore reopened = SqliteProjectStore.open(tempDir.resolve("world/syncmaterial/runtime-v2.sqlite"))) {
            assertTrue(await(reopened.claims("team")).isEmpty());
            assertTrue(await(reopened.inventory("team", target)).isEmpty());
        }
    }

    // ========== Helpers ==========

    private void seedProject(String projectId) throws Exception {
        await(store.ingest(metadata(projectId, "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));
    }

    /**
     * Wraps the live native connection in a fault proxy and installs it on the writer
     * thread. The outer fixture transaction finishes on the native connection, so no
     * proxy behavior is triggered by the installation itself.
     */
    private FaultHandler installFaultConnection() throws Exception {
        AtomicReference<FaultHandler> installed = new AtomicReference<>();
        await(store.runTestSupport(connection -> {
            FaultHandler faults = new FaultHandler(connection);
            setConnectionField(store, faults.proxy);
            installed.set(faults);
            return null;
        }));
        return installed.get();
    }

    private static void setConnectionField(SqliteProjectStore target, Connection connection) {
        try {
            connectionField().set(target, connection);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to install the test connection", e);
        }
    }

    private static Connection connectionField(SqliteProjectStore target) throws Exception {
        return (Connection) connectionField().get(target);
    }

    private static Field connectionField() throws NoSuchFieldException {
        Field field = SqliteProjectStore.class.getDeclaredField("connection");
        field.setAccessible(true);
        return field;
    }

    private static Object closeLatchField(SqliteProjectStore target) throws Exception {
        Field field = SqliteProjectStore.class.getDeclaredField("closeLatch");
        field.setAccessible(true);
        return field.get(target);
    }

    private static void awaitThreadWaiting(Thread thread, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(5);
        }
        fail("thread did not enter a waiting state: " + thread.getName());
    }

    private static final class FaultHandler implements InvocationHandler {
        private final Connection delegate;
        private final Connection proxy;

        private final AtomicBoolean failNextCommit = new AtomicBoolean();
        private final AtomicBoolean failRollback = new AtomicBoolean();
        private final AtomicBoolean failAutoCommitRestore = new AtomicBoolean();
        private final AtomicBoolean failGetAutoCommit = new AtomicBoolean();
        private final AtomicBoolean failNextAutoCommitFalse = new AtomicBoolean();
        private final AtomicBoolean failClose = new AtomicBoolean();
        private final AtomicBoolean failNextClose = new AtomicBoolean();

        private final AtomicInteger getAutoCommitCalls = new AtomicInteger();
        private final AtomicInteger autoCommitFalseCalls = new AtomicInteger();
        private final AtomicInteger autoCommitTrueCalls = new AtomicInteger();
        private final AtomicInteger commitCalls = new AtomicInteger();
        private final AtomicInteger rollbackCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger totalCalls = new AtomicInteger();

        private final CountDownLatch commitEntered = new CountDownLatch(1);
        private final CountDownLatch releaseInjectedCommit = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private volatile boolean blockInjectedCommit;
        private final AtomicBoolean blockNextClose = new AtomicBoolean();

        FaultHandler(Connection delegate) {
            this.delegate = delegate;
            this.proxy = (Connection) Proxy.newProxyInstance(
                    TransactionLifecycleTest.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            totalCalls.incrementAndGet();
            switch (method.getName()) {
                case "commit" -> {
                    commitCalls.incrementAndGet();
                    if (failNextCommit.getAndSet(false)) {
                        commitEntered.countDown();
                        if (blockInjectedCommit) {
                            try {
                                if (!releaseInjectedCommit.await(5, TimeUnit.SECONDS)) {
                                    throw new SQLException("injected commit was never released");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new SQLException("injected commit interrupted", e);
                            }
                        }
                        throw new SQLException("injected commit failure");
                    }
                }
                case "rollback" -> {
                    rollbackCalls.incrementAndGet();
                    if (failRollback.get()) {
                        throw new SQLException("injected rollback failure");
                    }
                }
                case "getAutoCommit" -> {
                    getAutoCommitCalls.incrementAndGet();
                    if (failGetAutoCommit.get()) {
                        throw new SQLException("injected getAutoCommit failure");
                    }
                }
                case "setAutoCommit" -> {
                    if (Boolean.TRUE.equals(args[0])) {
                        autoCommitTrueCalls.incrementAndGet();
                        if (failAutoCommitRestore.get()) {
                            throw new SQLException("injected autoCommit restore failure");
                        }
                    } else {
                        autoCommitFalseCalls.incrementAndGet();
                        if (failNextAutoCommitFalse.getAndSet(false)) {
                            throw new SQLException("injected setAutoCommit(false) failure");
                        }
                    }
                }
                case "close" -> {
                    closeCalls.incrementAndGet();
                    if (failClose.get() || failNextClose.getAndSet(false)) {
                        throw new SQLException("injected close failure");
                    }
                    if (blockNextClose.getAndSet(false)) {
                        closeEntered.countDown();
                        try {
                            if (!releaseClose.await(5, TimeUnit.SECONDS)) {
                                throw new SQLException("blocked close was never released");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new SQLException("blocked close interrupted", e);
                        }
                    }
                }
                default -> {
                }
            }

            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static SchematicMetadata metadata(String id, String hash, PlayerIdentity owner) {
        return new SchematicMetadata(id, "Project " + id,
                Path.of("/schematics", id + ".litematic"), hash, owner);
    }

    private static PlayerIdentity named(String name) {
        return new PlayerIdentity(null, name);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(10, TimeUnit.SECONDS));
        return failure.getCause();
    }
}
