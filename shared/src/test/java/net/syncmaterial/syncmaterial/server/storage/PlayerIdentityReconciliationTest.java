package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.KnownPlayers;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamLimits;
import net.syncmaterial.syncmaterial.core.TeamOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.deputyPlayerIds;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.failureOf;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.insertClaim;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.insertInventory;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.insertResolvedPlayer;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.insertUnresolvedPlayer;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.insertUnresolvedPlayersBatch;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.inventoryRow;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.inventoryRowCount;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.materialId;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.metadata;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.named;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.online;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.onlineUuid;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.ownerPlayerId;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.playerIdByUuid;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.playerIdsByName;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.playerRowCount;
import static net.syncmaterial.syncmaterial.server.storage.TeamTestFixtures.unresolvedPlayerId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity reconciliation tests: UUID-first roles, unambiguous name-only upgrade,
 * double-row merge, same-name distinct identities, and bounded directory reads.
 */
class PlayerIdentityReconciliationTest {
    @TempDir
    Path tempDir;

    private final AtomicBoolean failNextTransaction = new AtomicBoolean();
    private SqliteProjectStore store;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("team-identity.sqlite");
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

    @Test
    void singleRealUuidUpgradesUnresolvedOwnerInPlaceAndKeepsClaimInventory() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        Long unresolvedId = unresolvedPlayerId(store, "Alice");
        assertNotNull(unresolvedId);
        insertClaim(store, "p1", stoneId, unresolvedId, 10L);
        insertInventory(store, "p1", stoneId, unresolvedId, 5L, 100L);

        PlayerIdentity onlineAlice = online("Alice");
        await(store.reconcilePlayers(List.of(onlineAlice)));

        List<Long> ids = playerIdsByName(store, "Alice");
        assertEquals(List.of(unresolvedId), ids);
        assertEquals(onlineAlice.uuid(), TeamTestFixtures.playerUuid(store, unresolvedId));
        assertEquals(unresolvedId, ownerPlayerId(store, "p1"));
        assertEquals(onlineAlice.uuid(), await(store.teamState("p1")).orElseThrow().owner().uuid());
        assertEquals(1, claimRowCount(store, "p1"));
        assertEquals(new TeamTestFixtures.InventoryRow(5L, 100L),
                inventoryRow(store, "p1", stoneId, unresolvedId).orElseThrow());
    }

    @Test
    void multipleRealUuidsWithSameNameStayDistinctAndPreserveUnresolved() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        Long unresolvedId = unresolvedPlayerId(store, "Alice");
        assertNotNull(unresolvedId);
        PlayerIdentity aliceA = onlineUuid("alice-a", "Alice");
        PlayerIdentity aliceB = onlineUuid("alice-b", "Alice");

        await(store.reconcilePlayers(List.of(aliceA, aliceB)));

        assertEquals(3, playerIdsByName(store, "Alice").size());
        assertNotNull(unresolvedPlayerId(store, "Alice"));
        assertEquals(unresolvedId, ownerPlayerId(store, "p1"));
        assertNull(await(store.teamState("p1")).orElseThrow().owner().uuid());

        // A later single-UUID batch must not guess which real UUID owns the stale name.
        await(store.reconcilePlayers(List.of(aliceA)));
        assertEquals(3, playerIdsByName(store, "Alice").size());
        assertNotNull(unresolvedPlayerId(store, "Alice"));
        assertEquals(unresolvedId, ownerPlayerId(store, "p1"));
    }

    @Test
    void singleUuidBatchDoesNotMergeUnresolvedIntoWrongUuidWhenAnotherResolvedExists()
            throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        Long unresolvedId = unresolvedPlayerId(store, "Alice");
        PlayerIdentity aliceB = onlineUuid("alice-b", "Alice");
        insertResolvedPlayer(store, aliceB);

        PlayerIdentity aliceA = onlineUuid("alice-a", "Alice");
        await(store.reconcilePlayers(List.of(aliceA)));
        assertEquals(3, playerIdsByName(store, "Alice").size());
        assertNotNull(unresolvedPlayerId(store, "Alice"));
        assertEquals(unresolvedId, ownerPlayerId(store, "p1"));

        await(store.reconcilePlayers(List.of(aliceB)));
        assertEquals(3, playerIdsByName(store, "Alice").size());
        assertNotNull(unresolvedPlayerId(store, "Alice"));
        assertEquals(unresolvedId, ownerPlayerId(store, "p1"));
    }

    @Test
    void resolvedActorMergesUnresolvedOwnerAndDeputyBeforeAuthorization() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        await(store.ingest(metadata("p2", "h1", named("Bob")), List.of(count(IRON, 3))));
        Long unresolvedId = unresolvedPlayerId(store, "Alice");
        PlayerIdentity aliceA = onlineUuid("alice-a", "Alice");
        long targetId = insertResolvedPlayer(store, aliceA);
        addDeputyRow(store, "p2", unresolvedId);

        TeamOutcome toggle = await(apply(
                command("p1", 1, TeamAction.TOGGLE_SELF_CLAIM, List.of(), List.of()), aliceA));
        assertEquals(TeamOutcome.Status.SUCCESS, toggle.status(),
                "unambiguous UUID+unresolved must merge before role authorization");
        assertEquals(targetId, ownerPlayerId(store, "p1"));
        assertNull(unresolvedPlayerId(store, "Alice"));
        assertEquals(1, playerIdsByName(store, "Alice").size());

        TeamOutcome deputyToggle = await(apply(
                command("p2", 2, TeamAction.TOGGLE_SELF_CLAIM, List.of(), List.of()), aliceA));
        assertEquals(TeamOutcome.Status.SUCCESS, deputyToggle.status(),
                "merged deputy role must be visible after reloading role ids");
        assertEquals(List.of(targetId), deputyPlayerIds(store, "p2"));
    }

    @Test
    void resolvedActorDoesNotMergeOrAuthorizeWhenAnotherResolvedUuidOwnsSameName()
            throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        Long unresolvedId = unresolvedPlayerId(store, "Alice");
        PlayerIdentity aliceA = onlineUuid("alice-a", "Alice");
        insertResolvedPlayer(store, aliceA);
        insertResolvedPlayer(store, onlineUuid("alice-b", "Alice"));

        TeamOutcome denied = await(apply(
                command("p1", 1, TeamAction.TOGGLE_SELF_CLAIM, List.of(), List.of()), aliceA));

        assertEquals(TeamOutcome.Status.DENIED, denied.status());
        assertEquals(3, playerIdsByName(store, "Alice").size());
        assertEquals(unresolvedId, ownerPlayerId(store, "p1"));
        assertNull(await(store.teamState("p1")).orElseThrow().owner().uuid());
    }

    @Test
    void doubleRowMergeRedirectsOwnersUnionsDeputiesClaimsAndPicksLatestInventory()
            throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")),
                List.of(count(STONE, 10), count(DIRT, 5))));
        await(store.ingest(metadata("p2", "h1", named("Alice")), List.of(count(IRON, 3))));
        await(store.ingest(metadata("p3", "h1", named("Bob")), List.of(count(STONE, 1))));
        await(store.ingest(metadata("p4", "h1", named("Carol")), List.of(count(STONE, 1))));
        ProjectSnapshot p1 = await(store.findProject("p1")).orElseThrow();
        ProjectSnapshot p2 = await(store.findProject("p2")).orElseThrow();
        int stoneId = materialId(p1, STONE);
        int dirtId = materialId(p1, DIRT);
        int ironId = materialId(p2, IRON);

        Long unresolvedId = unresolvedPlayerId(store, "Alice");
        assertNotNull(unresolvedId);
        assertEquals(unresolvedId, ownerPlayerId(store, "p1"));
        assertEquals(unresolvedId, ownerPlayerId(store, "p2"));
        PlayerIdentity aliceA = onlineUuid("alice-a", "Alice");
        long targetId = insertResolvedPlayer(store, aliceA);

        insertClaim(store, "p1", stoneId, unresolvedId, 10L);
        insertClaim(store, "p2", ironId, targetId, 20L);
        addDeputyRow(store, "p3", unresolvedId);
        addDeputyRow(store, "p4", targetId);

        insertInventory(store, "p1", stoneId, unresolvedId, 5L, 100L);
        insertInventory(store, "p1", stoneId, targetId, 9L, 200L);
        insertInventory(store, "p1", dirtId, unresolvedId, 7L, 300L);
        insertInventory(store, "p1", dirtId, targetId, 2L, 100L);
        insertInventory(store, "p2", ironId, unresolvedId, 99L, 50L);
        insertInventory(store, "p2", ironId, targetId, 4L, 50L);

        await(store.reconcilePlayers(List.of(aliceA)));

        assertEquals(1, playerIdsByName(store, "Alice").size());
        assertNull(unresolvedPlayerId(store, "Alice"));
        assertEquals(targetId, ownerPlayerId(store, "p1"));
        assertEquals(targetId, ownerPlayerId(store, "p2"));
        assertEquals(aliceA.uuid(), await(store.teamState("p1")).orElseThrow().owner().uuid());
        assertEquals(List.of(targetId), deputyPlayerIds(store, "p3"));
        assertEquals(List.of(targetId), deputyPlayerIds(store, "p4"));

        assertEquals(1, claimRowCount(store, "p1", stoneId, targetId));
        assertEquals(0, claimRowCount(store, "p1", stoneId, unresolvedId));
        assertEquals(1, claimRowCount(store, "p2", ironId, targetId));
        assertEquals(1, claimRowCount(store, "p1"));

        assertEquals(new TeamTestFixtures.InventoryRow(9L, 200L),
                inventoryRow(store, "p1", stoneId, targetId).orElseThrow(),
                "older source inventory must not replace the resolved target");
        assertEquals(new TeamTestFixtures.InventoryRow(7L, 300L),
                inventoryRow(store, "p1", dirtId, targetId).orElseThrow(),
                "newer source inventory must replace the resolved target");
        assertEquals(new TeamTestFixtures.InventoryRow(4L, 50L),
                inventoryRow(store, "p2", ironId, targetId).orElseThrow(),
                "updated_at tie must keep the resolved target");
        assertEquals(3, inventoryRowCount(store));
    }

    @Test
    void nameOnlyTargetWithTwoResolvedSameNamesRejectsWholeCommand() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Owner")), List.of(count(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        PlayerIdentity aliceA = onlineUuid("alice-a", "Alice");
        PlayerIdentity aliceB = onlineUuid("alice-b", "Alice");
        await(store.reconcilePlayers(List.of(aliceA, aliceB)));

        TeamOutcome ambiguous = await(apply(command("p1", 1, TeamAction.ASSIGN,
                List.of(stoneId), List.of(named("Alice"))), online("Owner")));
        assertEquals(TeamOutcome.Status.INVALID_TARGET, ambiguous.status());
        assertEquals(0, claimRowCount(store, "p1"));

        TeamOutcome wholeRequestInvalid = await(apply(command("p1", 2, TeamAction.ASSIGN,
                List.of(stoneId), List.of(named("Alice"), named("Carol"))), online("Owner")));
        assertEquals(TeamOutcome.Status.INVALID_TARGET, wholeRequestInvalid.status());
        assertTrue(playerIdsByName(store, "Carol").isEmpty());
        assertEquals(0, claimRowCount(store, "p1"));

        assertEquals(TeamOutcome.Status.INVALID_TARGET, await(apply(command("p1", 3, TeamAction.KICK,
                List.of(stoneId), List.of(named("Alice"))), online("Owner"))).status());
        assertEquals(0, claimRowCount(store, "p1"));

        TeamOutcome resolved = await(apply(command("p1", 4, TeamAction.ASSIGN,
                List.of(stoneId), List.of(aliceA)), online("Owner")));
        assertEquals(TeamOutcome.Status.SUCCESS, resolved.status());
        assertEquals(1, claimRowCount(store, "p1"));
        assertEquals(2, playerIdsByName(store, "Alice").size(), "resolved same names stay distinct");
    }

    @Test
    void rawJoinAndReportInventoryPreserveSameNameUnresolvedRows() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Owner")), List.of(count(STONE, 10))));
        await(store.ingest(metadata("p2", "h2", named("Owner2")), List.of(count(IRON, 3))));
        ProjectSnapshot p1 = await(store.findProject("p1")).orElseThrow();
        ProjectSnapshot p2 = await(store.findProject("p2")).orElseThrow();
        int stoneId = materialId(p1, STONE);
        int ironId = materialId(p2, IRON);
        Long unresolvedId = insertUnresolvedPlayer(store, "Bob");
        insertClaim(store, "p1", stoneId, unresolvedId, 10L);
        insertInventory(store, "p1", stoneId, unresolvedId, 5L, 100L);
        insertClaim(store, "p2", ironId, unresolvedId, 10L);
        insertInventory(store, "p2", ironId, unresolvedId, 6L, 100L);

        PlayerIdentity onlineBob = online("Bob");
        assertFalse(await(store.join("p1", stoneId, onlineBob)),
                "existing unresolved claim is kept, so join reports no new row");
        assertEquals(onlineBob.uuid(), TeamTestFixtures.playerUuid(store, unresolvedId));
        assertEquals(1, playerIdsByName(store, "Bob").size());
        assertEquals(1, claimRowCount(store, "p1"));
        assertEquals(1, claimRowCount(store, "p2"));

        await(store.recordInventory("p1", stoneId, onlineBob, 11));
        assertEquals(Map.of(stoneId, 11L), await(store.inventory("p1", onlineBob)));
        assertEquals(Map.of(ironId, 6L), await(store.inventory("p2", onlineBob)));
        assertEquals(2, inventoryRowCount(store));
        assertEquals(1, playerIdsByName(store, "Bob").size());
    }

    @Test
    void rawJoinDoesNotUpgradeUnresolvedRowAcrossConflictingResolvedUuid() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Alice")), List.of(count(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        Long unresolvedOwnerId = unresolvedPlayerId(store, "Alice");
        PlayerIdentity aliceB = onlineUuid("alice-b", "Alice");
        insertResolvedPlayer(store, aliceB);

        PlayerIdentity aliceA = onlineUuid("alice-a", "Alice");
        assertTrue(await(store.join("p1", stoneId, aliceA)));
        assertEquals(3, playerIdsByName(store, "Alice").size());
        assertEquals(unresolvedOwnerId, ownerPlayerId(store, "p1"));
        assertNull(TeamTestFixtures.playerUuid(store, unresolvedOwnerId),
                "raw join must not upgrade the unresolved owner across a conflicting UUID");

        Throwable ambiguousNameOnly = failureOf(store.join("p1", stoneId, named("Alice")));
        assertInstanceOf(StorageException.class, ambiguousNameOnly);
        assertInstanceOf(PlayersRepository.AmbiguousPlayerNameException.class,
                ambiguousNameOnly.getCause());
    }

    @Test
    void knownPlayersPrioritizesRolesAndClaimsBeforeOrphanCacheNames() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Zoe")), List.of(count(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        long yanId = insertUnresolvedPlayer(store, "Yan");
        addDeputyRow(store, "p1", yanId);
        long xenaId = insertUnresolvedPlayer(store, "Xena");
        insertClaim(store, "p1", stoneId, xenaId, 10L);
        insertUnresolvedPlayer(store, "Aaron");

        List<String> names = await(store.knownPlayers()).stream().map(PlayerIdentity::name).toList();
        assertEquals(List.of("Zoe", "Yan", "Xena", "Aaron"), names,
                "owner > deputy > claimant > orphan cache order must beat name order");
    }

    @Test
    void resolveTeamTargetsPrefersCurrentOnlineNameForKnownUuid() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Owner")), List.of(count(STONE, 10))));
        PlayerIdentity oldName = onlineUuid("rename-player", "OldName");
        insertResolvedPlayer(store, oldName);
        PlayerIdentity current = new PlayerIdentity(oldName.uuid(), "NewName");

        List<PlayerIdentity> resolved = await(store.resolveTeamTargets(
                List.of(oldName), List.of(current), List.of()));

        assertEquals(List.of(current), resolved);
        assertEquals(1, playerIdsByName(store, "NewName").size());
        assertTrue(playerIdsByName(store, "OldName").isEmpty(),
                "online rename must update the stored display identity");
        assertNull(unresolvedPlayerId(store, "NewName"));
    }

    @Test
    void resolveTeamTargetsRejectsAmbiguousNameBeyondDirectoryCap() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Owner")), List.of(count(STONE, 10))));
        List<String> cacheNames = new ArrayList<>();
        for (int i = 0; i < TeamLimits.DIRECTORY + 1; i++) {
            cacheNames.add(String.format(java.util.Locale.ROOT, "Aaa%05d", i));
        }
        insertUnresolvedPlayersBatch(store, cacheNames);
        insertResolvedPlayer(store, onlineUuid("zulu-a", "Zulu"));
        insertResolvedPlayer(store, onlineUuid("zulu-b", "Zulu"));

        KnownPlayers visible = await(store.knownPlayerDirectory("p1"));
        assertTrue(visible.limited());
        assertFalse(visible.players().stream().anyMatch(player -> player.name().equals("Zulu")),
                "ambiguous rows must be outside the delivered directory page");

        Throwable failure = failureOf(store.resolveTeamTargets(
                List.of(named("Zulu")), List.of(), List.of()));
        assertTargetFailure(failure, PlayersRepository.AmbiguousPlayerNameException.class,
                "target lookup must query all matches, not the truncated display page");
    }

    @Test
    void resolveTeamTargetsUsesTrustedSourcesAndReconcilesMatchedIdentities() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Owner")), List.of(count(STONE, 10))));
        PlayerIdentity alice = onlineUuid("alice-a", "Alice");
        insertResolvedPlayer(store, alice);
        insertUnresolvedPlayer(store, "Bob");
        PlayerIdentity carol = online("Carol");
        PlayerIdentity dave = online("Dave");

        List<PlayerIdentity> resolved = await(store.resolveTeamTargets(
                List.of(named("Alice"), named("bob"), named("Carol"), named("Dave"), named("Eve"), alice),
                List.of(carol), List.of(dave)));

        assertEquals(6, resolved.size());
        assertEquals(alice.uuid(), resolved.get(0).uuid());
        assertNull(resolved.get(1).uuid());
        assertEquals("Bob", resolved.get(1).name());
        assertEquals(carol, resolved.get(2));
        assertEquals(dave, resolved.get(3));
        assertNull(resolved.get(4).uuid());
        assertEquals("Eve", resolved.get(4).name());
        assertEquals(alice.uuid(), resolved.get(5).uuid());
        assertNotNull(playerIdByUuid(store, carol.uuid()),
                "trusted online UUID must be persisted in the same writer transaction");
        assertNotNull(playerIdByUuid(store, dave.uuid()),
                "trusted cache UUID must be persisted in the same writer transaction");
        assertTrue(playerIdsByName(store, "Eve").isEmpty(),
                "unknown name-only target must remain creation-compatible, not be inserted early");
    }

    @Test
    void resolveTeamTargetsRejectsCacheStoreConflictingUuidForName() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Owner")), List.of(count(STONE, 10))));
        insertResolvedPlayer(store, onlineUuid("alice-store", "Alice"));
        PlayerIdentity cacheAlice = onlineUuid("alice-cache", "Alice");

        Throwable failure = failureOf(store.resolveTeamTargets(
                List.of(named("Alice")), List.of(), List.of(cacheAlice)));
        assertTargetFailure(failure, PlayersRepository.AmbiguousPlayerNameException.class,
                "cache and store name matches must be rejected together");
    }

    @Test
    void resolveTeamTargetsRejectsForgedUnknownUuidWithoutInsertingIt() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Owner")), List.of(count(STONE, 10))));
        PlayerIdentity forged = online("Mallory");

        Throwable failure = failureOf(store.resolveTeamTargets(
                List.of(forged), List.of(), List.of()));
        assertTargetFailure(failure, PlayersRepository.UnknownPlayerUuidException.class,
                "an unknown requested UUID must never be inserted");
        assertNull(playerIdByUuid(store, forged.uuid()));
    }

    @Test
    void resolveTeamTargetsReconcilesTrustedCacheTargetOutsideDirectoryPage() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Owner")), List.of(count(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        List<String> cacheNames = new ArrayList<>();
        for (int i = 0; i < TeamLimits.DIRECTORY + 1; i++) {
            cacheNames.add(String.format(java.util.Locale.ROOT, "Aaa%05d", i));
        }
        insertUnresolvedPlayersBatch(store, cacheNames);
        PlayerIdentity zulu = online("Zulu");

        List<PlayerIdentity> resolved = await(store.resolveTeamTargets(
                List.of(named("Zulu")), List.of(), List.of(zulu)));
        assertEquals(List.of(zulu), resolved);
        assertNotNull(playerIdByUuid(store, zulu.uuid()));

        TeamOutcome assigned = await(apply(command("p1", 1, TeamAction.ASSIGN,
                List.of(stoneId), resolved), online("Owner")));
        assertEquals(TeamOutcome.Status.SUCCESS, assigned.status());
        assertEquals(1, assigned.changedCount());
        assertEquals(1, claimRowCount(store, "p1"));
    }

    @Test
    void knownPlayerDirectoryPrioritizesCurrentProjectBeforeUnrelatedOwnersAndOrphans()
            throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Zoe")), List.of(count(STONE, 10))));
        int stoneId = materialId(await(store.findProject("p1")).orElseThrow(), STONE);
        long yanId = insertUnresolvedPlayer(store, "Yan");
        addDeputyRow(store, "p1", yanId);
        long xenaId = insertUnresolvedPlayer(store, "Xena");
        insertClaim(store, "p1", stoneId, xenaId, 10L);
        await(store.ingest(metadata("p2", "h2", named("Aaron")), List.of(count(STONE, 1))));
        insertUnresolvedPlayer(store, "Abby");

        KnownPlayers directory = await(store.knownPlayerDirectory("p1"));

        assertFalse(directory.limited());
        assertEquals(List.of("Zoe", "Yan", "Xena", "Aaron", "Abby"),
                directory.players().stream().map(PlayerIdentity::name).toList(),
                "current project roles/claims must precede unrelated project owners and orphans");
    }

    @Test
    void knownPlayerDirectoryFlagsTruncationAndOversizedNames() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Zoe")), List.of(count(STONE, 10))));
        List<String> cacheNames = new ArrayList<>();
        for (int i = 0; i < TeamLimits.DIRECTORY + 5; i++) {
            cacheNames.add(String.format(java.util.Locale.ROOT, "Cache%05d", i));
        }
        insertUnresolvedPlayersBatch(store, cacheNames);

        KnownPlayers bounded = await(store.knownPlayerDirectory("p1"));
        assertEquals(TeamLimits.DIRECTORY, bounded.players().size());
        assertTrue(bounded.limited());
        assertEquals("Zoe", bounded.players().get(0).name());

        insertUnresolvedPlayer(store, "A".repeat(TeamLimits.PLAYER_NAME_LENGTH + 1));
        KnownPlayers withOversized = await(store.knownPlayerDirectory("p3"));
        assertTrue(withOversized.limited(), "an oversized stored name must be reported explicitly");
        assertTrue(withOversized.players().stream()
                .allMatch(player -> player.name().length() <= TeamLimits.PLAYER_NAME_LENGTH));
    }

    @Test
    void knownPlayersIsBoundedByDirectoryLimit() throws Exception {
        await(store.ingest(metadata("p1", "h1", named("Zoe")), List.of(count(STONE, 10))));
        List<String> cacheNames = new ArrayList<>();
        for (int i = 0; i < TeamLimits.DIRECTORY + 5; i++) {
            cacheNames.add(String.format(java.util.Locale.ROOT, "Cache%05d", i));
        }
        insertUnresolvedPlayersBatch(store, cacheNames);

        List<PlayerIdentity> players = await(store.knownPlayers());
        assertEquals(TeamLimits.DIRECTORY, players.size());
        assertEquals("Zoe", players.get(0).name(),
                "owner must survive the bounded page ahead of orphan cache names");
        assertEquals(players.size(), new HashSet<>(players).size());
    }

    private CompletableFuture<TeamOutcome> apply(TeamCommand command, PlayerIdentity actor) {
        return TeamTestFixtures.apply(store, command, actor);
    }

    private static void assertTargetFailure(Throwable failure, Class<? extends Throwable> causeType,
                                            String message) {
        assertInstanceOf(net.syncmaterial.syncmaterial.core.PlayerTargetException.class, failure);
        assertInstanceOf(causeType, failure.getCause(), message);
    }
}
