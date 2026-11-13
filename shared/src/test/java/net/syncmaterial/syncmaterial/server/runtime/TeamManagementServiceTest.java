package net.syncmaterial.syncmaterial.server.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class TeamManagementServiceTest {
    @TempDir Path temporary;

    @Test
    void kickClearsPersistentAndRuntimeInventoryBeforePublication() throws Exception {
        try (var runtime = runtime()) {
            PlayerIdentity owner = player("Owner");
            PlayerIdentity target = player("Target");
            int material = project(runtime, "p", owner);
            runtime.store().reconcilePlayers(List.of(target)).get(5, TimeUnit.SECONDS);
            runtime.store().join("p", material, target).get(5, TimeUnit.SECONDS);
            runtime.connected(target.uuid());
            runtime.reportInventory("p", material, target, 12).get(5, TimeUnit.SECONDS);
            var result = runtime.teams().execute(command("p", TeamAction.KICK, List.of(material), target),
                    owner, () -> true).get(5, TimeUnit.SECONDS);
            assertTrue(result.success());
            assertEquals(0, runtime.inventoryCount(target.uuid(), "p", material));
            assertTrue(runtime.store().inventory("p", target).get().isEmpty());
            assertTrue(runtime.store().claims("p").get().isEmpty());
        }
    }

    @Test
    void assignmentIsMembershipNotAnInventoryObservation() throws Exception {
        try (var runtime = runtime()) {
            var owner = player("Owner");
            var target = player("Target");
            int material = project(runtime, "p", owner);
            runtime.store().reconcilePlayers(List.of(target)).get();
            var result = runtime.teams().execute(command("p", TeamAction.ASSIGN, List.of(material, material), target),
                    owner, () -> true).get(5, TimeUnit.SECONDS);
            assertTrue(result.success());
            assertEquals(2, result.processedCount());
            assertEquals(1, runtime.store().claims("p").get().size());
            assertTrue(runtime.store().inventory("p", target).get().isEmpty());
            assertEquals(0, runtime.inventoryCount(target.uuid(), "p", material));
        }
    }

    @Test
    void lostConnectionBeforeWriterAdmissionMakesNoChanges() throws Exception {
        try (var runtime = runtime()) {
            var owner = player("Owner");
            var target = player("Target");
            int material = project(runtime, "p", owner);
            runtime.store().reconcilePlayers(List.of(target)).get();
            var result = runtime.teams().execute(command("p", TeamAction.ASSIGN, List.of(material), target),
                    owner, () -> false).get(5, TimeUnit.SECONDS);
            assertFalse(result.success());
            assertTrue(runtime.store().claims("p").get().isEmpty());
        }
    }

    @Test
    void roleChangeDoesNotResetMaterialCatalogAndQueuesTeamOnly() throws Exception {
        try (var runtime = runtime()) {
            var owner = player("Owner");
            int material = project(runtime, "p", owner);
            var snapshot = runtime.store().findProject("p").get().orElseThrow();
            runtime.connected(owner.uuid());
            long revision = runtime.catalogs().sent(owner.uuid(), runtime.connection(owner.uuid()), snapshot);
            var request = new TeamCommand("p", 1, TeamAction.TOGGLE_SELF_CLAIM, -1, List.of(), List.of());
            assertTrue(runtime.teams().execute(request, owner, () -> true).get().success());
            assertEquals(Set.of("p"), runtime.takeTeamChanges());
            assertEquals(revision, runtime.catalogs().currentRevision(owner.uuid(), "p", runtime.connection(owner.uuid())).orElseThrow());
            assertEquals(material, runtime.store().findProject("p").get().orElseThrow().materials().getFirst().id());
            assertTrue(runtime.takeTeamChanges().isEmpty());
        }
    }

    @Test
    void directoryIncludesOfflineStoreAndCachedUuid() throws Exception {
        try (var runtime = runtime()) {
            var owner = player("Owner");
            var online = player("Online");
            var cached = player("Cached");
            project(runtime, "p", owner);
            var directory = runtime.teams().directory("p", List.of(online), () -> List.of(cached)).get(5, TimeUnit.SECONDS).players();
            assertTrue(directory.stream().anyMatch(entry -> entry.player().uuid().equals(owner.uuid()) && !entry.online()));
            assertTrue(directory.stream().anyMatch(entry -> entry.player().uuid().equals(cached.uuid()) && !entry.online()));
            assertTrue(directory.getFirst().online());
            assertEquals(online.uuid(), directory.getFirst().player().uuid());
        }
    }

    @Test
    void authorizedJoinChecksEveryReportedMaterialAndCurrentPolicy() throws Exception {
        try (var runtime = runtime()) {
            var owner = player("Owner");
            var visitor = player("Visitor");
            int material = project(runtime, "p", owner);
            assertFalse(runtime.teams().join("p", material, visitor, Set.of(material, Integer.MAX_VALUE),
                    () -> true).get());
            assertTrue(runtime.store().claims("p").get().isEmpty());
            var toggle = new TeamCommand("p", 1, TeamAction.TOGGLE_SELF_CLAIM, -1, List.of(), List.of());
            runtime.teams().execute(toggle, owner, () -> true).get();
            assertFalse(runtime.teams().join("p", material, visitor, Set.of(material), () -> true).get());
            assertTrue(runtime.teams().join("p", material, owner, Set.of(material), () -> true).get());
        }
    }

    private ServerRuntime runtime() throws Exception {
        Path root = Files.createTempDirectory(temporary, "runtime-");
        return ServerRuntime.open(root.resolve("world"), root.resolve("game"),
                path -> List.of(new MaterialCount("minecraft:stone", 64)), ignored -> {});
    }

    private static int project(ServerRuntime runtime, String id, PlayerIdentity owner) throws Exception {
        runtime.store().ingest(new SchematicMetadata(id, id, Path.of(id + ".litematic"), "hash", owner),
                List.of(new MaterialCount("minecraft:stone", 64)), runtime.interpretationId()).get(5, TimeUnit.SECONDS);
        return runtime.store().findProject(id).get().orElseThrow().materials().getFirst().id();
    }

    private static TeamCommand command(String id, TeamAction action, List<Integer> materialIds, PlayerIdentity target) {
        return new TeamCommand(id, 1, action, -1, materialIds, List.of(target));
    }

    private static PlayerIdentity player(String name) {
        return new PlayerIdentity(UUID.randomUUID(), name);
    }
}
