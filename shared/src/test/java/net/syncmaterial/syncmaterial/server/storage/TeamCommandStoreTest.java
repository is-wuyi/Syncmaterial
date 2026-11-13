package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamOutcome;
import net.syncmaterial.syncmaterial.core.TeamState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.ADMITTED;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.CURRENT_MATERIAL;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.DIRT;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.IRON;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.STONE;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.addDeputyRow;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.await;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.claimRowCount;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.command;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.count;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.deputyExists;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.failureOf;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.insertUnresolvedPlayer;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.inventoryRowCount;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.materialId;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.metadata;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.named;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.online;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.playerIdByUuid;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.playerIdsByName;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.playerRowCount;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.setAllowSelfClaim;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.unresolvedPlayerId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-JDBC team command tests against the public {@link SqliteProjectStore} API.
 * Schema is always created by the store itself; fixture SQL only seeds rows that a
 * legacy database or an earlier server session would already contain.
 */
class TeamCommandStoreTest {
    @TempDir
    Path tempDir;

    private final AtomicBoolean failNextTransaction = new AtomicBoolean();
    private SqliteProjectStore store;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("team-command.sqlite");
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

    // ========== Permission matrix ==========

    @Test
    void nameOnlyOwnerUpgradesOnFirstAuthorizedCommand() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        PlayerIdentity onlineAlice = online("Alice");

        TeamOutcome outcome = await(apply(
                command("p1", 1, TeamAction.TOGGLE_SELF_CLAIM, List.of(), List.of()), onlineAlice));

        assertEquals(TeamOutcome.Status.SUCCESS, outcome.status());
        assertEquals(1, outcome.processedCount());
        assertEquals(1, outcome.changedCount());
        assertFalse(outcome.state().allowSelfClaim());
        assertEquals(onlineAlice.uuid(), outcome.state().owner().uuid());

        TeamState stored = await(store.teamState("p1")).orElseThrow();
        assertEquals(onlineAlice.uuid(), stored.owner().uuid());
        assertEquals(1, playerIdsByName(store, "Alice").size());
        assertTrue(await(store.knownPlayers()).contains(onlineAlice));
        assertFalse(await(store.knownPlayers()).stream()
                .anyMatch(player -> player.uuid() == null && player.name().equals("Alice")));
    }

    @Test
    void deputyCanAssignKickAndToggleButNotManageDeputiesOrTransfer() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("David")),
                List.of(count(STONE, 10), count(DIRT, 5))));
        ProjectSnapshot project = await(store.findProject("p1")).orElseThrow();
        int stoneId = materialId(project, STONE);
        long danaId = insertUnresolvedPlayer(store, "Dana");
        addDeputyRow(store, "p1", danaId);
        PlayerIdentity onlineDana = online("Dana");

        TeamOutcome assigned = await(apply(
                command("p1", 1, TeamAction.ASSIGN, List.of(stoneId), List.of(named("Bob"))),
                onlineDana));
        assertEquals(TeamOutcome.Status.SUCCESS, assigned.status());
        assertEquals(1, assigned.processedCount());
        assertEquals(1, assigned.changedCount());
        assertTrue(assigned.state().deputies().stream()
                .anyMatch(deputy -> onlineDana.uuid().equals(deputy.uuid())));
        assertEquals(1, claimRowCount(store, "p1"));

        TeamOutcome kicked = await(apply(
                command("p1", 2, TeamAction.KICK, List.of(stoneId), List.of(named("Bob"))),
                onlineDana));
        assertEquals(TeamOutcome.Status.SUCCESS, kicked.status());
        assertEquals(1, kicked.changedCount());
        assertEquals(1, kicked.removedObservations().size());

        TeamOutcome transferred = await(apply(
                command("p1", 3, TeamAction.TRANSFER, List.of(), List.of(named("Bob"))),
                onlineDana));
        assertEquals(TeamOutcome.Status.DENIED, transferred.status());
        assertEquals("David", transferred.state().owner().name());
        assertNull(transferred.state().owner().uuid());

        assertEquals(TeamOutcome.Status.DENIED, await(apply(
                command("p1", 4, TeamAction.ADD_DEPUTY, List.of(), List.of(named("Bob"))),
                onlineDana)).status());
        assertEquals(TeamOutcome.Status.DENIED, await(apply(
                command("p1", 5, TeamAction.REMOVE_DEPUTY, List.of(), List.of(named("Bob"))),
                onlineDana)).status());

        TeamOutcome toggled = await(apply(
                command("p1", 6, TeamAction.TOGGLE_SELF_CLAIM, List.of(), List.of()), onlineDana));
        assertEquals(TeamOutcome.Status.SUCCESS, toggled.status());
        assertFalse(toggled.state().allowSelfClaim());
    }

    @Test
    void nonOwnerCannotAssignToggleOrKickAndIdentityIsNotUpgradedOnDenial() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        ProjectSnapshot project = await(store.findProject("p1")).orElseThrow();
        int stoneId = materialId(project, STONE);
        assertTrue(await(store.join("p1", stoneId, named("Member"))));
        PlayerIdentity onlineMember = online("Member");
        Long unresolvedMember = TeamTestFixtures.unresolvedPlayerId(store, "Member");
        assertTrue(unresolvedMember != null);

        assertEquals(TeamOutcome.Status.DENIED, await(apply(
                command("p1", 1, TeamAction.ASSIGN, List.of(stoneId), List.of(named("Bob"))),
                onlineMember)).status());
        assertEquals(TeamOutcome.Status.DENIED, await(apply(
                command("p1", 2, TeamAction.TOGGLE_SELF_CLAIM, List.of(), List.of()),
                onlineMember)).status());
        assertEquals(TeamOutcome.Status.DENIED, await(apply(
                command("p1", 3, TeamAction.KICK, List.of(stoneId), List.of(named("Member"))),
                onlineMember)).status());

        assertEquals(1, claimRowCount(store, "p1"));
        assertNull(playerIdByUuid(store, onlineMember.uuid()));
        assertEquals(1, playerIdsByName(store, "Member").size());
    }

    @Test
    void directoryAndStateHaveNoOwnerRestriction() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        PlayerIdentity stranger = online("Eve");

        TeamOutcome state = await(apply(
                command("p1", 1, TeamAction.TEAM_STATE, List.of(), List.of()), stranger));
        assertEquals(TeamOutcome.Status.SUCCESS, state.status());
        assertEquals("Alice", state.state().owner().name());
        assertEquals(0, state.processedCount());
        assertEquals(0, state.changedCount());

        TeamOutcome players = await(apply(
                command("p1", 2, TeamAction.PLAYERS, List.of(), List.of()), stranger));
        assertEquals(TeamOutcome.Status.SUCCESS, players.status());
        assertEquals(1, playerRowCount(store), "read-only team actions must not create actor rows");

        TeamOutcome missing = await(apply(
                command("missing", 3, TeamAction.TEAM_STATE, List.of(), List.of()), stranger));
        assertEquals(TeamOutcome.Status.PROJECT_MISSING, missing.status());
        assertNull(missing.state());
        assertTrue(await(store.teamState("missing")).isEmpty());
    }

    @Test
    void transferKeepsDeputyOverlapAndDoesNotCleanDeputies() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        PlayerIdentity onlineAlice = online("Alice");

        TeamOutcome selfDeputy = await(apply(
                command("p1", 1, TeamAction.ADD_DEPUTY, List.of(), List.of(named("Alice"))),
                onlineAlice));
        assertEquals(TeamOutcome.Status.SUCCESS, selfDeputy.status());
        assertEquals(1, selfDeputy.changedCount());
        assertTrue(selfDeputy.state().deputies().stream()
                .anyMatch(deputy -> onlineAlice.uuid().equals(deputy.uuid())));

        TeamOutcome bobDeputy = await(apply(
                command("p1", 2, TeamAction.ADD_DEPUTY, List.of(), List.of(named("Bob"))),
                onlineAlice));
        assertEquals(TeamOutcome.Status.SUCCESS, bobDeputy.status());

        TeamOutcome transferred = await(apply(
                command("p1", 3, TeamAction.TRANSFER, List.of(), List.of(named("Bob"))),
                onlineAlice));
        assertEquals(TeamOutcome.Status.SUCCESS, transferred.status());
        assertEquals(1, transferred.changedCount());
        assertEquals("Bob", transferred.state().owner().name());
        assertEquals(2, transferred.state().deputies().size(), "transfer must not clean deputy rows");

        PlayerIdentity onlineBob = online("Bob");
        TeamOutcome removedSelf = await(apply(
                command("p1", 4, TeamAction.REMOVE_DEPUTY, List.of(), List.of(named("Bob"))),
                onlineBob));
        assertEquals(TeamOutcome.Status.SUCCESS, removedSelf.status());
        assertEquals(1, removedSelf.changedCount());
        assertEquals(1, removedSelf.state().deputies().size());
        assertEquals(onlineBob.uuid(), removedSelf.state().owner().uuid());
        List<Long> aliceIds = playerIdsByName(store, "Alice");
        assertEquals(1, aliceIds.size());
        assertTrue(deputyExists(store, "p1", aliceIds.get(0)));
    }

    // ========== Batch assignment ==========

    @Test
    void assignmentSkipsMixedInvalidMaterialsAndCountsAttemptsSeparately() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(count(STONE, 10), count(DIRT, 5))));
        await(store.ingest(metadata("p2", "h1", named("Other")), List.of(count(IRON, 3))));
        ProjectSnapshot p1 = await(store.findProject("p1")).orElseThrow();
        ProjectSnapshot p2 = await(store.findProject("p2")).orElseThrow();
        int stoneId = materialId(p1, STONE);
        int ironId = materialId(p2, IRON);
        PlayerIdentity owner = online("Alice");

        TeamOutcome first = await(apply(command("p1", 1, TeamAction.ASSIGN,
                List.of(stoneId, stoneId, ironId, -1),
                List.of(named("Bob"), named("Carol"))), owner));
        assertEquals(TeamOutcome.Status.SUCCESS, first.status());
        assertEquals(4, first.processedCount());
        assertEquals(2, first.changedCount());
        assertEquals(Set.of(stoneId), first.changedMaterials());
        assertEquals(2, claimRowCount(store, "p1"));
        assertEquals(0, claimRowCount(store, "p2"));
        assertEquals(0, inventoryRowCount(store), "assignment must not fabricate inventories");

        TeamOutcome repeated = await(apply(command("p1", 2, TeamAction.ASSIGN,
                List.of(stoneId, stoneId, ironId, -1),
                List.of(named("Bob"), named("Carol"))), owner));
        assertEquals(TeamOutcome.Status.SUCCESS, repeated.status());
        assertEquals(4, repeated.processedCount());
        assertEquals(0, repeated.changedCount());
        assertTrue(repeated.changedMaterials().isEmpty());

        TeamOutcome allInvalid = await(apply(command("p1", 3, TeamAction.ASSIGN,
                List.of(ironId, -1), List.of(named("Bob"))), owner));
        assertEquals(TeamOutcome.Status.SUCCESS, allInvalid.status());
        assertEquals(0, allInvalid.processedCount());
        assertEquals(0, allInvalid.changedCount());
        assertEquals(2, claimRowCount(store, "p1"));
        assertEquals(0, inventoryRowCount(store));
    }

    @Test
    void currentMaterialAndAdmissionFencesRejectWithoutWriting() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        PlayerIdentity owner = online("Alice");

        TeamOutcome stale = await(apply(
                command("p1", 1, TeamAction.ASSIGN, List.of(stoneId), List.of(named("Bob"))),
                owner, snapshot -> false, ADMITTED));
        assertEquals(TeamOutcome.Status.STALE_MATERIALS, stale.status());
        assertEquals(0, claimRowCount(store, "p1"));

        TeamOutcome denied = await(apply(
                command("p1", 2, TeamAction.ASSIGN, List.of(stoneId), List.of(named("Bob"))),
                owner, CURRENT_MATERIAL, () -> false));
        assertEquals(TeamOutcome.Status.DENIED, denied.status());
        assertEquals(0, claimRowCount(store, "p1"));

        TeamOutcome allInvalidButCurrent = await(apply(
                command("p1", 3, TeamAction.ASSIGN, List.of(-1), List.of(named("Bob"))),
                owner, CURRENT_MATERIAL, ADMITTED));
        assertEquals(TeamOutcome.Status.SUCCESS, allInvalidButCurrent.status());
        assertEquals(0, allInvalidButCurrent.processedCount());
        assertEquals(0, claimRowCount(store, "p1"));
    }

    @Test
    void assignmentStorageFaultRollsBackTargetsClaimsAndKeepsStoreUsable() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(count(STONE, 10), count(DIRT, 5))));
        ProjectSnapshot project = await(store.findProject("p1")).orElseThrow();
        int stoneId = materialId(project, STONE);
        int dirtId = materialId(project, DIRT);
        PlayerIdentity owner = online("Alice");

        failNextTransaction.set(true);
        Throwable failure = failureOf(apply(command("p1", 1, TeamAction.ASSIGN,
                List.of(stoneId, dirtId), List.of(named("Bob"), named("Carol"))), owner));
        assertInstanceOf(StorageException.class, failure);
        assertEquals(0, claimRowCount(store, "p1"));
        assertEquals(0, inventoryRowCount(store));
        assertTrue(playerIdsByName(store, "Bob").isEmpty());
        assertTrue(playerIdsByName(store, "Carol").isEmpty());

        TeamOutcome retried = await(apply(command("p1", 2, TeamAction.ASSIGN,
                List.of(stoneId, dirtId), List.of(named("Bob"), named("Carol"))), owner));
        assertEquals(TeamOutcome.Status.SUCCESS, retried.status());
        assertEquals(4, retried.changedCount());
        assertEquals(4, claimRowCount(store, "p1"));
    }

    // ========== Kick ==========

    @Test
    void kickCleansExactClaimInventoryCrossProjectAndSurvivesReimport() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(count(STONE, 10), count(DIRT, 5))));
        await(store.ingest(metadata("p2", "h1", named("Other")), List.of(count(IRON, 3))));
        ProjectSnapshot p1 = await(store.findProject("p1")).orElseThrow();
        ProjectSnapshot p2 = await(store.findProject("p2")).orElseThrow();
        int stoneId = materialId(p1, STONE);
        int dirtId = materialId(p1, DIRT);
        int ironId = materialId(p2, IRON);
        PlayerIdentity onlineAlice = online("Alice");

        assertTrue(await(store.join("p1", stoneId, onlineAlice)));
        assertTrue(await(store.join("p1", dirtId, onlineAlice)));
        assertTrue(await(store.join("p1", stoneId, named("Bob"))));
        assertTrue(await(store.join("p2", ironId, onlineAlice)));
        await(store.recordInventory("p1", stoneId, onlineAlice, 5));
        await(store.recordInventory("p1", dirtId, onlineAlice, 2));
        await(store.recordInventory("p2", ironId, onlineAlice, 7));

        TeamOutcome kicked = await(apply(command("p1", 1, TeamAction.KICK,
                List.of(stoneId, ironId, dirtId, stoneId, -1), List.of(named("Alice"))),
                onlineAlice));
        assertEquals(TeamOutcome.Status.SUCCESS, kicked.status());
        assertEquals(5, kicked.processedCount());
        assertEquals(2, kicked.changedCount());
        assertEquals(Set.of(stoneId, dirtId), kicked.changedMaterials());
        assertEquals(2, kicked.removedObservations().size());
        assertEquals(Set.of(stoneId, dirtId), kicked.removedObservations().stream()
                .map(ClaimSnapshot::materialId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(kicked.removedObservations().stream()
                .allMatch(snapshot -> onlineAlice.uuid().equals(snapshot.player().uuid())));

        assertEquals(1, claimRowCount(store, "p1"), "Bob's claim must stay");
        assertEquals(1, claimRowCount(store, "p2"), "cross-project claim must not be touched");
        assertEquals(Map.of(), await(store.inventory("p1", onlineAlice)));
        assertEquals(Map.of(ironId, 7L), await(store.inventory("p2", onlineAlice)));
        assertEquals(1, inventoryRowCount(store));

        TeamOutcome noOp = await(apply(command("p1", 2, TeamAction.KICK,
                List.of(dirtId), List.of(named("Alice"))), onlineAlice));
        assertEquals(TeamOutcome.Status.SUCCESS, noOp.status());
        assertEquals(1, noOp.processedCount());
        assertEquals(0, noOp.changedCount());
        assertEquals(1, noOp.removedObservations().size(),
                "in-memory-only observations must still be cleared for valid materials");
        assertEquals(dirtId, noOp.removedObservations().get(0).materialId());

        await(store.ingest(metadata("p1", "h2", named("Alice")),
                List.of(count(STONE, 20), count(DIRT, 10))));
        ProjectSnapshot reimported = await(store.findProject("p1")).orElseThrow();
        assertEquals(stoneId, materialId(reimported, STONE));
        assertEquals(dirtId, materialId(reimported, DIRT));
        assertEquals(1, claimRowCount(store, "p1"), "only Bob's claim survives reimport");
        assertEquals(Map.of(), await(store.inventory("p1", onlineAlice)));
        assertEquals(Map.of(ironId, 7L), await(store.inventory("p2", onlineAlice)));
        assertEquals(onlineAlice.uuid(), await(store.teamState("p1")).orElseThrow().owner().uuid());

        await(store.ingest(metadata("p1", "h2", named("Alice")),
                List.of(count(STONE, 20), count(DIRT, 10))));
        assertEquals(1, claimRowCount(store, "p1"));
    }

    @Test
    void kickStorageFaultRestoresClaimAndInventory() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        PlayerIdentity onlineAlice = online("Alice");
        assertTrue(await(store.join("p1", stoneId, onlineAlice)));
        await(store.recordInventory("p1", stoneId, onlineAlice, 4));

        failNextTransaction.set(true);
        Throwable failure = failureOf(apply(command("p1", 1, TeamAction.KICK,
                List.of(stoneId), List.of(named("Alice"))), onlineAlice));
        assertInstanceOf(StorageException.class, failure);
        assertEquals(1, claimRowCount(store, "p1"));
        assertEquals(Map.of(stoneId, 4L), await(store.inventory("p1", onlineAlice)));
        assertEquals(1, inventoryRowCount(store));
    }

    // ========== Linearization / transaction ordering ==========

    @Test
    void newerMaterialIntentVisibleBeforeAdmissionRejectsWithoutWrite() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        AtomicBoolean newerIntentVisible = new AtomicBoolean(true);

        TeamOutcome stale = await(apply(
                command("p1", 1, TeamAction.ASSIGN, List.of(stoneId), List.of(named("Bob"))),
                online("Alice"), snapshot -> !newerIntentVisible.get(), ADMITTED));

        assertEquals(TeamOutcome.Status.STALE_MATERIALS, stale.status());
        assertEquals(0, stale.processedCount());
        assertEquals(0, stale.changedCount());
        assertEquals(0, claimRowCount(store, "p1"));
        assertEquals(0, inventoryRowCount(store));
    }

    @Test
    void admittedCommandCommitsBeforeLaterRemovalWins() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);

        CompletableFuture<TeamOutcome> command = apply(
                command("p1", 1, TeamAction.ASSIGN, List.of(stoneId), List.of(named("Bob"))),
                online("Alice"), CURRENT_MATERIAL, ADMITTED);
        CompletableFuture<Void> removal = store.removeProject("p1");

        TeamOutcome outcome = await(command);
        assertEquals(TeamOutcome.Status.SUCCESS, outcome.status());
        assertEquals(1, outcome.changedCount());
        await(removal);
        assertTrue(await(store.findProject("p1")).isEmpty());
        assertEquals(0, claimRowCount(store, "p1"));
    }

    // ========== Authorized join ==========

    @Test
    void joinAuthorizedValidatesReportedMaterialsAndSelfClaimPermissions() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(count(STONE, 10), count(DIRT, 5))));
        await(store.ingest(metadata("p2", "h1", named("Other")), List.of(count(IRON, 3))));
        ProjectSnapshot p1 = await(store.findProject("p1")).orElseThrow();
        ProjectSnapshot p2 = await(store.findProject("p2")).orElseThrow();
        int stoneId = materialId(p1, STONE);
        int dirtId = materialId(p1, DIRT);
        int ironId = materialId(p2, IRON);
        PlayerIdentity bob = online("Bob");

        assertTrue(await(store.joinAuthorized("p1", stoneId, bob,
                Set.of(stoneId, dirtId), CURRENT_MATERIAL, ADMITTED)));
        assertEquals(1, claimRowCount(store, "p1"));
        assertTrue(await(store.joinAuthorized("p1", stoneId, bob,
                Set.of(stoneId, dirtId), CURRENT_MATERIAL, ADMITTED)));
        assertEquals(1, claimRowCount(store, "p1"));

        PlayerIdentity carol = online("Carol");
        assertFalse(await(store.joinAuthorized("p1", stoneId, carol,
                Set.of(ironId), CURRENT_MATERIAL, ADMITTED)));
        assertFalse(await(store.joinAuthorized("p1", stoneId, carol,
                Set.of(-1), CURRENT_MATERIAL, ADMITTED)));
        assertNull(playerIdByUuid(store, carol.uuid()));
        assertEquals(1, claimRowCount(store, "p1"));

        assertFalse(await(store.joinAuthorized("p1", ironId, bob,
                Set.of(), CURRENT_MATERIAL, ADMITTED)));
        assertFalse(await(store.joinAuthorized("p1", stoneId, bob,
                Set.of(), snapshot -> false, ADMITTED)));
        assertFalse(await(store.joinAuthorized("p1", stoneId, bob,
                Set.of(), CURRENT_MATERIAL, () -> false)));
        assertFalse(await(store.joinAuthorized("missing", stoneId, bob,
                Set.of(), CURRENT_MATERIAL, ADMITTED)));
        assertEquals(0, inventoryRowCount(store));

        setAllowSelfClaim(store, "p1", false);
        PlayerIdentity dave = online("Dave");
        assertFalse(await(store.joinAuthorized("p1", dirtId, dave,
                Set.of(), CURRENT_MATERIAL, ADMITTED)));

        PlayerIdentity onlineAlice = online("Alice");
        assertTrue(await(store.joinAuthorized("p1", dirtId, onlineAlice,
                Set.of(dirtId), CURRENT_MATERIAL, ADMITTED)));
        assertEquals(onlineAlice.uuid(), await(store.teamState("p1")).orElseThrow().owner().uuid());

        long danaId = insertUnresolvedPlayer(store, "Dana");
        addDeputyRow(store, "p1", danaId);
        PlayerIdentity onlineDana = online("Dana");
        assertTrue(await(store.joinAuthorized("p1", stoneId, onlineDana,
                Set.of(stoneId), CURRENT_MATERIAL, ADMITTED)));
        assertEquals(onlineDana.uuid(), TeamTestFixtures.playerUuid(store, danaId));
        assertEquals(3, claimRowCount(store, "p1"));
        assertFalse(await(store.joinAuthorized("p1", stoneId, bob,
                Set.of(stoneId), CURRENT_MATERIAL, ADMITTED)));
        assertEquals(0, await(store.claims("p2")).size());
        assertEquals(0, inventoryRowCount(store));
    }

    @Test
    void unresolvedNameRolesDoNotGrantClientAuthorityUntilTrustedUpgrade() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        PlayerIdentity onlineAlice = online("Alice");

        TeamState unresolved = await(store.teamState("p1")).orElseThrow();
        assertFalse(unresolved.allowNameAuthority());
        assertFalse(unresolved.isOwner(onlineAlice),
                "Foundation state must not grant manager UI rights by unresolved name");
        assertFalse(unresolved.isMainOwner(onlineAlice));

        await(store.reconcilePlayers(List.of(onlineAlice)));
        TeamState upgraded = await(store.teamState("p1")).orElseThrow();
        assertEquals(onlineAlice.uuid(), upgraded.owner().uuid());
        assertTrue(upgraded.isOwner(onlineAlice));
        assertTrue(upgraded.isMainOwner(onlineAlice));
    }

    @Test
    void joinAuthorizedDoesNotUpgradeUnresolvedOwnerAcrossConflictingResolvedUuid() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        setAllowSelfClaim(store, "p1", false);
        TeamTestFixtures.insertResolvedPlayer(store, onlineUuid("other-alice", "Alice"));

        PlayerIdentity onlineAlice = online("Alice");
        assertFalse(await(store.joinAuthorized("p1", stoneId, onlineAlice,
                Set.of(), CURRENT_MATERIAL, ADMITTED)));
        assertEquals(0, claimRowCount(store, "p1"));
        assertNull(await(store.teamState("p1")).orElseThrow().owner().uuid());
        Long unresolvedId = unresolvedPlayerId(store, "Alice");
        assertNotNull(unresolvedId);
        assertNull(playerIdByUuid(store, onlineAlice.uuid()));
        assertEquals(2, playerIdsByName(store, "Alice").size(),
                "conflicting resolved row and unresolved owner stay distinct");
        assertTrue(playerIdsByName(store, "Alice").contains(unresolvedId));
    }

    private static PlayerIdentity onlineUuid(String seed, String name) {
        return TeamTestFixtures.onlineUuid(seed, name);
    }

    private CompletableFuture<TeamOutcome> apply(TeamCommand command, PlayerIdentity actor) {
        return TeamTestFixtures.apply(store, command, actor);
    }

    private CompletableFuture<TeamOutcome> apply(TeamCommand command, PlayerIdentity actor,
                                                 Predicate<ProjectSnapshot> currentMaterial,
                                                 BooleanSupplier admission) {
        return TeamTestFixtures.apply(store, command, actor, currentMaterial, admission);
    }
}
