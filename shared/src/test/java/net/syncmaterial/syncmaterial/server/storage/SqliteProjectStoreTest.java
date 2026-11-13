package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectState;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real-store tests. Schema is always created by {@link SqliteProjectStore} itself;
 * tests only use the repository API, package-private fixture hooks, and read-only
 * inspection of an already-created database.
 */
class SqliteProjectStoreTest {
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final String IRON = "minecraft:iron_ingot";

    @TempDir
    Path tempDir;

    private final AtomicBoolean failNextTransaction = new AtomicBoolean();

    private Path dbPath;
    private SqliteProjectStore store;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("runtime-v2.sqlite");
        store = SqliteProjectStore.openForTesting(dbPath, operation -> {
            if (failNextTransaction.getAndSet(false)) {
                throw new SQLException("injected failure for " + operation);
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    // ========== Ingest ==========

    @Test
    void ingest_createsProjectMaterialsAndSnapshot() throws Exception {
        PlayerIdentity owner = named("Alice");

        await(store.ingest(metadata("p1", "h1", owner), List.of(
                new MaterialCount(STONE, 10),
                new MaterialCount(DIRT, 5),
                new MaterialCount(STONE, 2))));

        ProjectSnapshot project = await(store.findProject("p1")).orElseThrow();
        assertEquals("Project p1", project.name());
        assertEquals("h1", project.hash());
        assertEquals(owner, project.owner());
        assertTrue(project.allowSelfClaim());
        assertEquals(0, project.revision());
        assertEquals(2, project.materials().size());
        assertEquals(12, material(project, STONE).required());
        assertEquals(5, material(project, DIRT).required());

        ProjectState state = await(store.snapshot("p1")).orElseThrow();
        assertEquals(project, state.project());
        assertTrue(state.claims().isEmpty());

        assertEquals(1, await(store.listProjects()).size());
    }

    @Test
    void ingest_blankNameFallsBackToFileName() throws Exception {
        await(store.ingest(
                new SchematicMetadata("p1", "", Path.of("/schematics/blank.litematic"), "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 1))));

        assertEquals("blank.litematic", await(store.findProject("p1")).orElseThrow().name());
    }

    @Test
    void ingest_checkedSumOverflowLeavesNoProject() throws Exception {
        CompletableFuture<Void> failed = store.ingest(metadata("p1", "h1", named("Alice")), List.of(
                new MaterialCount(STONE, Long.MAX_VALUE),
                new MaterialCount(STONE, 1)));

        assertInstanceOf(ArithmeticException.class, failureOf(failed));
        assertTrue(await(store.findProject("p1")).isEmpty());
        assertTrue(await(store.listProjects()).isEmpty());
    }

    @Test
    void ingest_failedTransactionRollsBackAndDoesNotRollBackAnotherProject() throws Exception {
        await(store.ingest(metadata("p2", "h2", named("Bob")),
                List.of(new MaterialCount(DIRT, 7))));

        failNextTransaction.set(true);
        Throwable failure = failureOf(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));

        assertInstanceOf(StorageException.class, failure);
        assertTrue(await(store.findProject("p1")).isEmpty());
        ProjectSnapshot untouched = await(store.findProject("p2")).orElseThrow();
        assertEquals("h2", untouched.hash());
        assertEquals(7, material(untouched, DIRT).required());
        assertEquals(1, await(store.listProjects()).size());

        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));

        ProjectSnapshot project = await(store.findProject("p1")).orElseThrow();
        assertEquals(0, project.revision());
        assertEquals(10, material(project, STONE).required());
        assertEquals(7, material(await(store.findProject("p2")).orElseThrow(), DIRT).required());
    }

    @Test
    void ingest_failedContentUpdateKeepsPreviousRevisionAndClaims() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));
        ProjectSnapshot first = await(store.findProject("p1")).orElseThrow();
        int stoneId = materialId(first, STONE);
        PlayerIdentity alice = online("Alice");
        assertTrue(await(store.join("p1", stoneId, alice)));

        failNextTransaction.set(true);
        Throwable failure = failureOf(store.ingest(metadata("p1", "h2", named("Alice")),
                List.of(new MaterialCount(STONE, 99), new MaterialCount(DIRT, 5))));
        assertInstanceOf(StorageException.class, failure);

        ProjectSnapshot afterFailure = await(store.findProject("p1")).orElseThrow();
        assertEquals("h1", afterFailure.hash());
        assertEquals(0, afterFailure.revision());
        assertEquals(10, material(afterFailure, STONE).required());
        assertEquals(stoneId, materialId(afterFailure, STONE));
        assertEquals(1, await(store.claims("p1")).size());

        await(store.ingest(metadata("p1", "h2", named("Alice")),
                List.of(new MaterialCount(STONE, 99), new MaterialCount(DIRT, 5))));

        ProjectSnapshot retried = await(store.findProject("p1")).orElseThrow();
        assertEquals("h2", retried.hash());
        assertEquals(1, retried.revision());
        assertEquals(99, material(retried, STONE).required());
        assertEquals(stoneId, materialId(retried, STONE));
        assertEquals(1, await(store.claims("p1")).size());
    }

    @Test
    void ingest_sameHashIsIdempotentAndPreservesUserMetadata() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);

        setAllowSelfClaim(store, "p1", false);
        transferOwner(store, "p1", online("Bob"));
        addDeputy(store, "p1", named("Dana"));

        await(store.ingest(
                new SchematicMetadata("p1", "Renamed", Path.of("/schematics/moved.litematic"), "h1", online("Carol")),
                List.of(new MaterialCount(STONE, 999), new MaterialCount(DIRT, 1))));

        ProjectSnapshot sameHash = await(store.findProject("p1")).orElseThrow();
        assertEquals("Renamed", sameHash.name());
        assertEquals("Bob", sameHash.ownerName());
        assertFalse(sameHash.allowSelfClaim());
        assertEquals(List.of("Dana"), sameHash.deputies());
        assertEquals(0, sameHash.revision());
        assertEquals(10, material(sameHash, STONE).required());
        assertEquals(1, sameHash.materials().size());
        assertEquals(stoneId, materialId(sameHash, STONE));

        await(store.ingest(
                new SchematicMetadata("p1", "Renamed Again", Path.of("/schematics/moved.litematic"), "h2", online("Carol")),
                List.of(new MaterialCount(STONE, 11), new MaterialCount(DIRT, 2))));

        ProjectSnapshot changedHash = await(store.findProject("p1")).orElseThrow();
        assertEquals("Renamed Again", changedHash.name());
        assertEquals("Bob", changedHash.ownerName());
        assertFalse(changedHash.allowSelfClaim());
        assertEquals(List.of("Dana"), changedHash.deputies());
        assertEquals(1, changedHash.revision());
        assertEquals(11, material(changedHash, STONE).required());
        assertEquals(stoneId, materialId(changedHash, STONE));
        assertEquals(2, material(changedHash, DIRT).required());
    }

    @Test
    void ingest_contentUpdateDeletesOnlyDisappearedItemsAndKeepsKeptClaims() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10), new MaterialCount(DIRT, 5))));
        ProjectSnapshot first = await(store.findProject("p1")).orElseThrow();
        int stoneId = materialId(first, STONE);
        int dirtId = materialId(first, DIRT);

        PlayerIdentity alice = online("Alice");
        PlayerIdentity bob = named("Bob");
        assertTrue(await(store.join("p1", stoneId, alice)));
        assertTrue(await(store.join("p1", dirtId, bob)));
        await(store.recordInventory("p1", stoneId, alice, 4));

        await(store.ingest(metadata("p1", "h2", named("Alice")),
                List.of(new MaterialCount(STONE, 20), new MaterialCount(IRON, 3))));

        ProjectState state = await(store.snapshot("p1")).orElseThrow();
        assertEquals(1, state.project().revision());
        assertEquals(2, state.project().materials().size());
        assertEquals(stoneId, materialId(state.project(), STONE));
        assertEquals(20, material(state.project(), STONE).required());
        assertEquals(3, material(state.project(), IRON).required());

        assertEquals(1, state.claims().size());
        ClaimSnapshot remaining = state.claims().get(0);
        assertEquals(stoneId, remaining.materialId());
        assertEquals("Alice", remaining.player().name());

        assertEquals(Map.of(stoneId, 4L), await(store.inventory("p1", alice)));
    }

    @Test
    void sameHashTwoProjectsAreIndependent() throws Exception {
        await(store.ingest(metadata("p1", "shared", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));
        await(store.ingest(metadata("p2", "shared", named("Bob")),
                List.of(new MaterialCount(STONE, 10))));

        ProjectSnapshot p1 = await(store.findProject("p1")).orElseThrow();
        ProjectSnapshot p2 = await(store.findProject("p2")).orElseThrow();
        int p1Stone = materialId(p1, STONE);
        int p2Stone = materialId(p2, STONE);
        assertNotEquals(p1Stone, p2Stone);

        await(store.ingest(metadata("p2", "changed", named("Bob")),
                List.of(new MaterialCount(STONE, 20))));

        ProjectSnapshot p1After = await(store.findProject("p1")).orElseThrow();
        ProjectSnapshot p2After = await(store.findProject("p2")).orElseThrow();
        assertEquals(0, p1After.revision());
        assertEquals(10, material(p1After, STONE).required());
        assertEquals(p1Stone, materialId(p1After, STONE));
        assertEquals(1, p2After.revision());
        assertEquals(20, material(p2After, STONE).required());
        assertEquals(p2Stone, materialId(p2After, STONE));
    }

    // ========== Claims and inventory ==========

    @Test
    void joinIsIdempotentAndOwnerNameOnlyIsResolvedByOnlineUuid() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);

        PlayerIdentity onlineAlice = online("Alice");
        assertTrue(await(store.join("p1", stoneId, onlineAlice)));
        assertFalse(await(store.join("p1", stoneId, onlineAlice)));
        assertTrue(await(store.join("p1", stoneId, named("Bob"))));

        ProjectState state = await(store.snapshot("p1")).orElseThrow();
        assertEquals(2, state.claims().size());
        ClaimSnapshot aliceClaim = claim(state, stoneId, "Alice");
        ClaimSnapshot bobClaim = claim(state, stoneId, "Bob");
        assertEquals(onlineAlice.uuid(), aliceClaim.player().uuid());
        assertEquals(null, bobClaim.player().uuid());
        assertEquals(onlineAlice.uuid(), state.project().owner().uuid(),
                "name-only metadata owner should be upgraded, not duplicated with a fake uuid");

        assertEquals(Map.of(), await(store.inventory("p1", named("Nobody"))));
    }

    @Test
    void joinRejectsMaterialFromAnotherProjectAndUnknownIds() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));
        await(store.ingest(metadata("p2", "h2", named("Bob")),
                List.of(new MaterialCount(DIRT, 5))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);

        Throwable crossProject = failureOf(store.join("p2", stoneId, online("Alice")));
        assertInstanceOf(StorageException.class, crossProject);
        assertInstanceOf(SQLException.class, crossProject.getCause());
        assertTrue(await(store.claims("p2")).isEmpty());

        assertInstanceOf(StorageException.class,
                failureOf(store.join("missing", stoneId, online("Alice"))));
        assertInstanceOf(StorageException.class,
                failureOf(store.join("p1", Integer.MAX_VALUE, online("Alice"))));

        assertInstanceOf(IllegalArgumentException.class,
                failureOf(store.join("p1", 0, online("Alice"))));
    }

    @Test
    void recordInventoryRequiresClaimAndKeepsLongCounts() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));
        await(store.ingest(metadata("p2", "h2", named("Alice")),
                List.of(new MaterialCount(DIRT, 5))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        PlayerIdentity alice = online("Alice");

        assertInstanceOf(IllegalStateException.class,
                failureOf(store.recordInventory("p1", stoneId, alice, 10)));
        assertEquals(Map.of(), await(store.inventory("p1", alice)));

        assertTrue(await(store.join("p1", stoneId, alice)));
        await(store.recordInventory("p1", stoneId, alice, 10));
        assertEquals(Map.of(stoneId, 10L), await(store.inventory("p1", alice)));

        await(store.recordInventory("p1", stoneId, alice, 5_000_000_000L));
        assertEquals(Map.of(stoneId, 5_000_000_000L), await(store.inventory("p1", alice)));

        assertInstanceOf(IllegalArgumentException.class,
                failureOf(store.recordInventory("p1", stoneId, alice, -1)));
        assertEquals(5_000_000_000L, await(store.inventory("p1", alice)).get(stoneId));

        await(store.recordInventory("p1", stoneId, alice, 0));
        assertEquals(0L, await(store.inventory("p1", alice)).get(stoneId));

        assertInstanceOf(IllegalStateException.class,
                failureOf(store.recordInventory("p2", stoneId, alice, 3)));
        assertEquals(Map.of(), await(store.inventory("p2", alice)));
    }

    @Test
    void leaveRemovesClaimAndInventory() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        PlayerIdentity alice = online("Alice");

        assertTrue(await(store.join("p1", stoneId, alice)));
        await(store.recordInventory("p1", stoneId, alice, 7));

        assertTrue(await(store.leave("p1", stoneId, alice)));
        assertTrue(await(store.claims("p1")).isEmpty());
        assertEquals(Map.of(), await(store.inventory("p1", alice)));
        assertFalse(await(store.leave("p1", stoneId, alice)));
        assertTrue(await(store.findProject("p1")).isPresent());
    }

    @Test
    void resolvedUuidDoesNotFallBackToAnotherResolvedSameNamePlayer() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Owner")),
                List.of(new MaterialCount(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);

        PlayerIdentity aliceOne = onlineUuid("alice-1", "Alice");
        PlayerIdentity aliceTwo = onlineUuid("alice-2", "Alice");
        PlayerIdentity aliceThree = onlineUuid("alice-3", "Alice");

        assertTrue(await(store.join("p1", stoneId, aliceOne)));
        assertTrue(await(store.join("p1", stoneId, aliceTwo)));
        await(store.recordInventory("p1", stoneId, aliceOne, 3));
        await(store.recordInventory("p1", stoneId, aliceTwo, 7));
        assertEquals(2, await(store.claims("p1")).size());

        assertEquals(Map.of(), await(store.inventory("p1", aliceThree)),
                "unknown UUID must not read another resolved player's inventory");
        assertFalse(await(store.leave("p1", stoneId, aliceThree)),
                "unknown UUID must not remove another resolved player's claim");
        assertInstanceOf(IllegalStateException.class,
                failureOf(store.recordInventory("p1", stoneId, aliceThree, 99)),
                "unknown UUID must not report inventory onto another resolved player");

        assertEquals(2, await(store.claims("p1")).size());
        assertEquals(Map.of(stoneId, 3L), await(store.inventory("p1", aliceOne)));
        assertEquals(Map.of(stoneId, 7L), await(store.inventory("p1", aliceTwo)));
        assertEquals(Map.of(), await(store.inventory("p1", aliceThree)));

        assertTrue(await(store.join("p1", stoneId, aliceThree)));
        await(store.recordInventory("p1", stoneId, aliceThree, 11));
        assertEquals(3, await(store.claims("p1")).size());
        assertEquals(Map.of(stoneId, 11L), await(store.inventory("p1", aliceThree)));
        assertEquals(Map.of(stoneId, 3L), await(store.inventory("p1", aliceOne)));
        assertEquals(Map.of(stoneId, 7L), await(store.inventory("p1", aliceTwo)));
    }

    @Test
    void removeProjectCascadesClaimsInventoryDeputyAndProjectArea() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));
        await(store.ingest(metadata("p2", "h2", named("Bob")),
                List.of(new MaterialCount(DIRT, 5))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        PlayerIdentity alice = online("Alice");

        assertTrue(await(store.join("p1", stoneId, alice)));
        await(store.recordInventory("p1", stoneId, alice, 5));
        addDeputy(store, "p1", named("Dana"));
        long stagingArea = addArea(store,
                "p1", AreaKind.STAGING_AREA, "Staging", "minecraft:overworld", 0, 0, 0, 8, 4, 8);
        assertTrue(areaExists(store, stagingArea));

        await(store.removeProject("p1"));

        assertTrue(await(store.findProject("p1")).isEmpty());
        assertTrue(await(store.claims("p1")).isEmpty());
        assertEquals(Map.of(), await(store.inventory("p1", alice)));
        assertFalse(areaExists(store, stagingArea));
        assertTrue(await(store.findProject("p2")).isPresent());
        await(store.removeProject("missing"));
    }

    @Test
    void warehouseAreasAreGlobalAndSurviveProjectDelete() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 10))));
        await(store.ingest(metadata("p2", "h2", named("Bob")),
                List.of(new MaterialCount(DIRT, 5))));

        long globalWarehouse = addArea(store,
                null, AreaKind.WAREHOUSE, "Global", "minecraft:overworld", 0, 0, 0, 16, 8, 16);
        long stagingArea = addArea(store,
                "p1", AreaKind.STAGING_AREA, "Staging", "minecraft:overworld", 0, 0, 0, 8, 4, 8);

        addWarehouseRef(store, "p1", globalWarehouse);
        addWarehouseRef(store, "p2", globalWarehouse);
        assertTrue(areaExists(store, globalWarehouse));
        assertTrue(warehouseRefExists(store, "p1", globalWarehouse));
        assertTrue(warehouseRefExists(store, "p2", globalWarehouse));

        assertInstanceOf(StorageException.class, failureOf(addAreaFuture(
                store, null, AreaKind.STAGING_AREA, "Bad", "minecraft:overworld", 0, 0, 0, 1, 1, 1)));

        await(store.ingest(metadata("p1", "h3", named("Alice")),
                List.of(new MaterialCount(STONE, 12))));
        assertTrue(areaExists(store, stagingArea), "re-ingest must keep area ids");
        assertTrue(warehouseRefExists(store, "p1", globalWarehouse));
        assertTrue(warehouseRefExists(store, "p2", globalWarehouse));

        await(store.removeProject("p1"));

        assertFalse(areaExists(store, stagingArea),
                "project-owned staging area should cascade");
        assertTrue(areaExists(store, globalWarehouse),
                "global warehouse must not be deleted with one project");
        assertFalse(warehouseRefExists(store, "p1", globalWarehouse));
        assertTrue(warehouseRefExists(store, "p2", globalWarehouse));
    }

    // ========== Transactions, concurrency and close ==========

    @Test
    void databaseConstraintsRejectNullAndDuplicateRows() throws Exception {
        Throwable nullName = failureOf(store.runTestSupport(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO players (uuid, name) VALUES (NULL, NULL)")) {
                statement.executeUpdate();
            }
            return null;
        }));
        assertInstanceOf(StorageException.class, nullName);

        long firstWarehouse = addArea(store,
                null, AreaKind.WAREHOUSE, "Duplicate", "minecraft:overworld", 0, 0, 0, 1, 1, 1);
        Throwable duplicate = failureOf(addAreaFuture(
                store, null, AreaKind.WAREHOUSE, "Duplicate", "minecraft:overworld", 0, 0, 0, 1, 1, 1));
        assertInstanceOf(StorageException.class, duplicate);
        assertTrue(areaExists(store, firstWarehouse));
    }

    @Test
    void parallelWritesAreIsolated() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Owner")),
                List.of(new MaterialCount(STONE, 10))));
        await(store.ingest(metadata("p2", "h2", named("Owner")),
                List.of(new MaterialCount(DIRT, 5))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);

        List<PlayerIdentity> players = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            players.add(online("Player" + i));
        }

        List<CompletableFuture<?>> operations = new ArrayList<>();
        for (PlayerIdentity player : players) {
            operations.add(store.join("p1", stoneId, player)
                    .thenCompose(joined -> store.recordInventory("p1", stoneId, player, 1)));
        }
        operations.add(store.ingest(metadata("p3", "h3", named("Owner")),
                List.of(new MaterialCount(STONE, 2))));
        operations.add(store.ingest(metadata("p4", "h4", named("Owner")),
                List.of(new MaterialCount(DIRT, 3))));

        CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

        assertEquals(players.size(), (await(store.claims("p1"))).size());
        for (PlayerIdentity player : players) {
            assertEquals(Map.of(stoneId, 1L), await(store.inventory("p1", player)));
        }
        assertEquals(4, await(store.listProjects()).size());
        assertEquals(2, material(await(store.findProject("p3")).orElseThrow(), STONE).required());
        assertEquals(3, material(await(store.findProject("p4")).orElseThrow(), DIRT).required());
    }

    @Test
    void closeRejectsSubsequentOperations() throws Exception {
        SqliteProjectStore closedStore = store;
        closedStore.close();
        store = null;

        IllegalStateException failure = assertInstanceOf(
                IllegalStateException.class, failureOf(closedStore.findProject("p1")));
        assertEquals("SqliteProjectStore is closed", failure.getMessage());
        assertTrue(closedStore.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 1))).isCompletedExceptionally());

        closedStore.close();
    }

    @Test
    void closeFromWriterCallbackDoesNotDeadlock() throws Exception {
        CompletableFuture<Void> callback = store.findProject("missing")
                .thenAccept(project -> store.close());

        callback.get(5, TimeUnit.SECONDS);

        assertTrue(store.findProject("missing").isCompletedExceptionally());
    }

    // ========== Path lease, close timeout and interruption ==========

    @Test
    void sameCanonicalPathCannotBeOpenedTwice() {
        StorageException direct = assertThrows(StorageException.class,
                () -> SqliteProjectStore.open(dbPath));
        assertTrue(direct.getMessage().contains("already open"));

        Path alias = dbPath.getParent().resolve(".").resolve(dbPath.getFileName());
        StorageException normalizedAlias = assertThrows(StorageException.class,
                () -> SqliteProjectStore.open(alias));
        assertTrue(normalizedAlias.getMessage().contains("already open"));
    }

    @Test
    void closeTimeoutKeepsPathLeaseUntilFinishClose() throws Exception {
        Path path = tempDir.resolve("lease-timeout.sqlite");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SqliteProjectStore old = SqliteProjectStore.openForTesting(path, operation -> {
            if ("ingest".equals(operation)) {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
        }, 100L);

        try {
            CompletableFuture<Void> ingest = old.ingest(metadata("p1", "h1", named("Alice")),
                    List.of(new MaterialCount(STONE, 1)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            StorageException timeout = assertThrows(StorageException.class, old::close);
            assertTrue(timeout.getMessage().contains("Timed out"));

            StorageException lease = assertThrows(StorageException.class,
                    () -> SqliteProjectStore.open(path));
            assertTrue(lease.getMessage().contains("already open"));

            release.countDown();
            await(ingest);
            assertTrue(old.awaitClosedForTesting(5, TimeUnit.SECONDS),
                    "finishClose must complete after the admitted writer task finishes");

            old.close();
            try (SqliteProjectStore reopened = SqliteProjectStore.open(path)) {
                assertTrue(await(reopened.findProject("p1")).isPresent());
            }
        } finally {
            release.countDown();
            old.close();
        }
    }

    @Test
    void interruptedCloseThrowsAndKeepsLeaseUntilWriterExits() throws Exception {
        Path path = tempDir.resolve("lease-interrupt.sqlite");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SqliteProjectStore old = SqliteProjectStore.openForTesting(path, operation -> {
            if ("ingest".equals(operation)) {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
        }, 10_000L);

        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closeThread = new Thread(() -> {
            try {
                old.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        }, "store-close-interrupt-test");

        try {
            CompletableFuture<Void> ingest = old.ingest(metadata("p1", "h1", named("Alice")),
                    List.of(new MaterialCount(STONE, 1)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            closeThread.start();
            awaitThreadWaiting(closeThread, 5_000L);
            closeThread.interrupt();
            closeThread.join(5_000L);
            assertFalse(closeThread.isAlive());

            StorageException interrupted = assertInstanceOf(StorageException.class, closeFailure.get());
            assertTrue(interrupted.getMessage().contains("Interrupted"));
            assertThrows(StorageException.class, () -> SqliteProjectStore.open(path));

            release.countDown();
            await(ingest);
            assertTrue(old.awaitClosedForTesting(5, TimeUnit.SECONDS),
                    "finishClose must complete after the admitted writer task finishes");

            old.close();
            try (SqliteProjectStore reopened = SqliteProjectStore.open(path)) {
                assertTrue(await(reopened.findProject("p1")).isPresent());
            }
        } finally {
            release.countDown();
            old.close();
        }
    }

    @Test
    void selfCloseFailsAlreadyAcceptedQueuedFutureClearly() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        CompletableFuture<Void> closer = store.findProject("missing").thenRun(() -> {
            callbackEntered.countDown();
            try {
                allowClose.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            store.close();
        });

        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> queued = store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(new MaterialCount(STONE, 1)));
        allowClose.countDown();

        closer.get(5, TimeUnit.SECONDS);
        store = null;

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> queued.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("SqliteProjectStore is closed", failure.getCause().getMessage());
    }

    // ========== Schema, version and reopen ==========

    @Test
    void openRejectsUnknownHigherVersionWithoutByteChanges() throws Exception {
        Path futureDb = tempDir.resolve("future-version.sqlite");
        int futureVersion = StoreSchema.SCHEMA_VERSION + 1;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + futureDb);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=" + futureVersion);
        }

        byte[] before = Files.readAllBytes(futureDb);
        StorageException failure = assertThrows(StorageException.class,
                () -> SqliteProjectStore.open(futureDb));
        assertTrue(failure.getMessage().contains("Unsupported schema version " + futureVersion));
        assertArrayEquals(before, Files.readAllBytes(futureDb));
        assertFalse(Files.exists(futureDb.resolveSibling("future-version.sqlite-wal")));
        assertFalse(Files.exists(futureDb.resolveSibling("future-version.sqlite-shm")));
        assertFalse(Files.exists(futureDb.resolveSibling("future-version.sqlite-journal")));

        StorageException retry = assertThrows(StorageException.class,
                () -> SqliteProjectStore.open(futureDb));
        assertTrue(retry.getMessage().contains("Unsupported schema version " + futureVersion),
                "failed open must release its path lease");
    }

    @Test
    void schemaVersionIsCurrentAndConfigTablesExist() throws Exception {
        SqliteProjectStore liveStore = store;
        liveStore.close();
        store = null;

        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection connection = config.createConnection("jdbc:sqlite:" + dbPath);
             Statement statement = connection.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(resultSet.next());
                assertEquals(StoreSchema.SCHEMA_VERSION, resultSet.getInt(1));
            }

            Set<String> tables = new HashSet<>();
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table'")) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString(1));
                }
            }
            assertTrue(tables.containsAll(Set.of(
                    "players", "projects", "materials", "material_claims", "player_inventories",
                    "project_deputies", "areas", "warehouse_refs", "store_state", "containers",
                    "container_items", "container_tombstones", "chunk_generations", "area_chunks",
                    "area_chunk_containers")));

            Set<String> projectColumns = new HashSet<>();
            try (ResultSet resultSet = statement.executeQuery("PRAGMA table_info(projects)")) {
                while (resultSet.next()) {
                    projectColumns.add(resultSet.getString(2));
                }
            }
            assertTrue(projectColumns.contains("interpretation_id"));

            Set<String> areaColumns = new HashSet<>();
            try (ResultSet resultSet = statement.executeQuery("PRAGMA table_info(areas)")) {
                while (resultSet.next()) {
                    areaColumns.add(resultSet.getString(2));
                }
            }
            assertTrue(areaColumns.containsAll(Set.of("revision", "geometry_revision")));
        }
    }

    @Test
    void reopenPreservesProjectsClaimsAndInventory() throws Exception {
        Path reopenPath = tempDir.resolve("reopen.sqlite");
        PlayerIdentity alice = online("Alice");

        try (SqliteProjectStore first = SqliteProjectStore.open(reopenPath)) {
            await(first.ingest(metadata("p1", "h1", named("Alice")),
                    List.of(new MaterialCount(STONE, 10))));
            int stoneId = materialId(await(first.findProject("p1")).orElseThrow(), STONE);
            assertTrue(await(first.join("p1", stoneId, alice)));
            await(first.recordInventory("p1", stoneId, alice, 6));
        }

        try (SqliteProjectStore second = SqliteProjectStore.open(reopenPath)) {
            ProjectState state = await(second.snapshot("p1")).orElseThrow();
            int stoneId = materialId(state.project(), STONE);
            assertEquals(1, state.claims().size());
            assertEquals(alice, state.claims().get(0).player());
            assertEquals(Map.of(stoneId, 6L), await(second.inventory("p1", alice)));
        }
    }

    @Test
    void openDoesNotTouchSiblingLegacyDatabase() throws Exception {
        Path legacy = tempDir.resolve("syncmaterial.db");
        byte[] legacyBytes = "not a sqlite database, must stay untouched".getBytes(StandardCharsets.UTF_8);
        Files.write(legacy, legacyBytes);

        Path runtimePath = tempDir.resolve("runtime-sibling-test.sqlite");
        try (SqliteProjectStore local = SqliteProjectStore.open(runtimePath)) {
            await(local.ingest(metadata("p1", "h1", named("Alice")),
                    List.of(new MaterialCount(STONE, 10))));
        }

        assertArrayEquals(legacyBytes, Files.readAllBytes(legacy));
        assertFalse(Files.exists(legacy.resolveSibling("syncmaterial.db-wal")));
        assertFalse(Files.exists(legacy.resolveSibling("syncmaterial.db-shm")));
    }

    // ========== Helpers ==========

    private static void setAllowSelfClaim(SqliteProjectStore store, String projectId, boolean allow) throws Exception {
        await(store.runTestSupport(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE projects SET allow_self_claim = ?, updated_at = ? WHERE id = ?")) {
                statement.setInt(1, allow ? 1 : 0);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, projectId);
                if (statement.executeUpdate() == 0) {
                    throw new SQLException("unknown project: " + projectId);
                }
            }
            return null;
        }));
    }

    private static void transferOwner(SqliteProjectStore store, String projectId, PlayerIdentity owner)
            throws Exception {
        await(store.runTestSupport(connection -> {
            long ownerId = PlayersRepository.resolvePlayer(connection, owner);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE projects SET owner_player_id = ?, updated_at = ? WHERE id = ?")) {
                statement.setLong(1, ownerId);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, projectId);
                if (statement.executeUpdate() == 0) {
                    throw new SQLException("unknown project: " + projectId);
                }
            }
            return null;
        }));
    }

    private static void addDeputy(SqliteProjectStore store, String projectId, PlayerIdentity deputy) throws Exception {
        await(store.runTestSupport(connection -> {
            long playerId = PlayersRepository.resolvePlayer(connection, deputy);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO project_deputies (project_id, player_id, added_at) VALUES (?, ?, ?) "
                            + "ON CONFLICT(project_id, player_id) DO NOTHING")) {
                statement.setString(1, projectId);
                statement.setLong(2, playerId);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        }));
    }

    private static CompletableFuture<Long> addAreaFuture(SqliteProjectStore store, String projectId, AreaKind kind,
                                                         String name, String world,
                                                         int x1, int y1, int z1, int x2, int y2, int z2) {
        return store.runTestSupport(connection -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO areas (project_id, kind, name, world, x1, y1, z1, x2, y2, z2, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
                if (projectId == null) {
                    statement.setNull(1, Types.VARCHAR);
                } else {
                    statement.setString(1, projectId);
                }
                statement.setString(2, kind.wireName());
                statement.setString(3, name);
                statement.setString(4, world);
                statement.setInt(5, x1);
                statement.setInt(6, y1);
                statement.setInt(7, z1);
                statement.setInt(8, x2);
                statement.setInt(9, y2);
                statement.setInt(10, z2);
                statement.setLong(11, now);
                statement.setLong(12, now);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException("area insert returned no id");
                    }
                    return resultSet.getLong(1);
                }
            }
        });
    }

    private static long addArea(SqliteProjectStore store, String projectId, AreaKind kind, String name, String world,
                                int x1, int y1, int z1, int x2, int y2, int z2) throws Exception {
        return await(addAreaFuture(store, projectId, kind, name, world, x1, y1, z1, x2, y2, z2));
    }

    private static void addWarehouseRef(SqliteProjectStore store, String projectId, long areaId) throws Exception {
        await(store.runTestSupport(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO warehouse_refs (project_id, area_id) VALUES (?, ?)")) {
                statement.setString(1, projectId);
                statement.setLong(2, areaId);
                statement.executeUpdate();
            }
            return null;
        }));
    }

    private static boolean areaExists(SqliteProjectStore store, long areaId) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM areas WHERE id = ?")) {
                statement.setLong(1, areaId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        }));
    }

    private static boolean warehouseRefExists(SqliteProjectStore store, String projectId, long areaId)
            throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM warehouse_refs WHERE project_id = ? AND area_id = ?")) {
                statement.setString(1, projectId);
                statement.setLong(2, areaId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        }));
    }

    private static SchematicMetadata metadata(String id, String hash, PlayerIdentity owner) {
        return new SchematicMetadata(id, "Project " + id,
                Path.of("/schematics", id + ".litematic"), hash, owner);
    }

    private static PlayerIdentity named(String name) {
        return new PlayerIdentity(null, name);
    }

    private static PlayerIdentity online(String name) {
        return onlineUuid(name, name);
    }

    private static PlayerIdentity onlineUuid(String uuidSeed, String name) {
        return new PlayerIdentity(
                UUID.nameUUIDFromBytes(uuidSeed.getBytes(StandardCharsets.UTF_8)), name);
    }

    private static MaterialSnapshot material(ProjectSnapshot project, String itemId) {
        return project.materials().stream()
                .filter(material -> material.itemId().equals(itemId))
                .findFirst()
                .orElseThrow();
    }

    private static int materialId(ProjectSnapshot project, String itemId) {
        return material(project, itemId).id();
    }

    private static ClaimSnapshot claim(ProjectState state, int materialId, String playerName) {
        return state.claims().stream()
                .filter(claim -> claim.materialId() == materialId
                        && claim.player().name().equals(playerName))
                .findFirst()
                .orElseThrow();
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

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(10, TimeUnit.SECONDS));
        return failure.getCause();
    }
}
