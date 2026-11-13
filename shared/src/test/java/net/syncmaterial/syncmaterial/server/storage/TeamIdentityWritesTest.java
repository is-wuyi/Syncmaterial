package net.syncmaterial.syncmaterial.server.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class TeamIdentityWritesTest {
    @TempDir Path temporary;

    @Test
    void uuidRenameReportsExactlyAffectedOwnerProject() throws Exception {
        try (var store = SqliteProjectStore.open(temporary.resolve("roles.sqlite"))) {
            var owner = new PlayerIdentity(UUID.randomUUID(), "Before");
            var other = new PlayerIdentity(UUID.randomUUID(), "Other");
            store.ingest(metadata("p", owner), List.of(new MaterialCount("minecraft:stone", 64))).get();
            store.ingest(metadata("other", other), List.of(new MaterialCount("minecraft:stone", 64))).get();
            var renamed = new PlayerIdentity(owner.uuid(), "After");
            assertEquals(Set.of("p"), store.reconcileTeamPlayers(List.of(renamed)).get());
            assertEquals(renamed, store.teamState("p").get().orElseThrow().owner());
            assertTrue(store.reconcileTeamPlayers(List.of(renamed)).get().isEmpty());
            assertEquals(other, store.teamState("other").get().orElseThrow().owner());
        }
    }

    @Test
    void identityUpgradeReportsOwnerAndDeputyWithoutMaterialRevisionChange() throws Exception {
        try (var store = SqliteProjectStore.open(temporary.resolve("upgrade.sqlite"))) {
            var unresolved = new PlayerIdentity(null, "Name");
            store.ingest(metadata("p", unresolved), List.of(new MaterialCount("minecraft:stone", 64))).get();
            var before = store.findProject("p").get().orElseThrow();
            var actor = new PlayerIdentity(UUID.randomUUID(), "Name");
            assertEquals(Set.of("p"), store.reconcileTeamPlayers(List.of(actor)).get());
            var after = store.findProject("p").get().orElseThrow();
            assertEquals(before.revision(), after.revision());
            assertEquals(before.materials(), after.materials());
            assertEquals(actor, after.owner());
        }
    }

    @Test
    void claimantOnlyReconciliationDoesNotPretendToChangeRoles() throws Exception {
        try (var store = SqliteProjectStore.open(temporary.resolve("claims.sqlite"))) {
            var owner = new PlayerIdentity(UUID.randomUUID(), "Owner");
            var claimant = new PlayerIdentity(null, "Claimant");
            store.ingest(metadata("p", owner), List.of(new MaterialCount("minecraft:stone", 64))).get();
            int material = store.findProject("p").get().orElseThrow().materials().getFirst().id();
            store.join("p", material, claimant).get();
            var resolved = new PlayerIdentity(UUID.randomUUID(), "Claimant");
            assertTrue(store.reconcileTeamPlayers(List.of(resolved)).get().isEmpty());
            assertEquals(resolved, store.claims("p").get().getFirst().player());
            assertEquals(owner, store.teamState("p").get().orElseThrow().owner());
        }
    }

    private static SchematicMetadata metadata(String id, PlayerIdentity owner) {
        return new SchematicMetadata(id, id, Path.of(id + ".litematic"), "hash", owner);
    }
}
