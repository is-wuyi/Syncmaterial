package net.syncmaterial.syncmaterial.server.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.syncmaterial.syncmaterial.client.ClientProjectViewStore;
import net.syncmaterial.syncmaterial.client.ProjectViewAssembler;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic ordering proof for a callback generated before a committed leave/kick:
 * the server-side read already carries the old claim/observation, while a client that
 * already installed the newer publication must reject the delayed older snapshot.
 */
class ProgressPublicationOrderTest {
    @TempDir
    Path temporary;

    @Test
    void delayedPreLeaveSnapshotCannotRegressInstalledProgress() throws Exception {
        assertDelayedSnapshotCannotRegress(false);
    }

    @Test
    void delayedPreKickSnapshotCannotRegressInstalledProgress() throws Exception {
        assertDelayedSnapshotCannotRegress(true);
    }

    private void assertDelayedSnapshotCannotRegress(boolean kick) throws Exception {
        Path root = Files.createTempDirectory(temporary, "progress-order-");
        try (ServerRuntime runtime = ServerRuntime.open(root.resolve("world"), root.resolve("game"),
                path -> List.of(new MaterialCount("minecraft:stone", 128)), ignored -> {})) {
            PlayerIdentity owner = player("Owner");
            PlayerIdentity visitor = player("Visitor");
            await(runtime.store().ingest(new SchematicMetadata("p", "p", Path.of("p.litematic"),
                    "hash", owner), List.of(new MaterialCount("minecraft:stone", 128)),
                    runtime.interpretationId()));
            int material = await(runtime.store().findProject("p")).orElseThrow()
                    .materials().getFirst().id();
            runtime.connected(visitor.uuid());
            await(runtime.collaboration().join("p", material, visitor, Map.of(material, 4L)));

            ProjectProgressRead before = await(runtime.readProgress("p")).orElseThrow();
            ProjectProgressSnapshot stale = runtime.progressOnServer(before).orElseThrow();
            ProjectProgressMaterialView staleRow = stale.viewFor(visitor).material(material).orElseThrow();
            assertTrue(staleRow.claimed());
            assertEquals(4, staleRow.myObserved());
            assertEquals(124, staleRow.teamMissing());

            if (kick) {
                assertTrue(await(runtime.teams().execute(new TeamCommand("p", 1, TeamAction.KICK,
                        -1, List.of(material), List.of(visitor)), owner, () -> true)).success());
            } else {
                assertTrue(await(runtime.collaboration().leave("p", material, visitor)).success());
            }
            assertTrue(await(runtime.store().claims("p")).isEmpty());

            ProjectProgressRead after = await(runtime.readProgress("p")).orElseThrow();
            ProjectProgressSnapshot fresh = runtime.progressOnServer(after).orElseThrow();
            ProjectProgressMaterialView freshRow = fresh.viewFor(visitor).material(material).orElseThrow();
            assertFalse(freshRow.claimed());
            assertEquals(0, freshRow.myObserved());
            assertEquals(128, freshRow.teamMissing());
            assertTrue(stale.publicationSequence() < fresh.publicationSequence());

            ProjectViewScope scope = ProjectViewScope.project("p");
            ClientProjectViewStore store = new ClientProjectViewStore();
            assertTrue(store.open(scope, 7, 3));
            assertTrue(store.install(assembled(scope, "fresh", fresh)));
            assertFalse(store.install(assembled(scope, "stale", stale)),
                    "a delayed callback from before the committed transition must not regress the installed view");
            assertEquals(fresh.publicationSequence(), store.progress().orElseThrow().publicationSequence());
            assertEquals(128, store.progress().orElseThrow().viewFor(visitor)
                    .material(material).orElseThrow().teamMissing());
        }
    }

    private static ProjectViewAssembler.AssembledView assembled(ProjectViewScope scope,
                                                                String snapshotId,
                                                                ProjectProgressSnapshot snapshot) {
        return new ProjectViewAssembler.AssembledView(scope, 7, 3,
                snapshot.publicationSequence(), snapshotId, snapshot.catalogRevision(),
                snapshot.inventoryRevision(), snapshot);
    }

    private static PlayerIdentity player(String name) {
        return new PlayerIdentity(UUID.randomUUID(), name);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }
}
