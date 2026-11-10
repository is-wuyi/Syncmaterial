package net.syncmaterial.syncmaterial.server.storage;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectState;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Same hash with a different material interpretation must be treated as new counts. */
class InterpretationStorageTest {
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";

    @TempDir
    Path directory;

    private final AtomicBoolean failNextTransaction = new AtomicBoolean();
    private SqliteProjectStore store;

    @BeforeEach
    void setUp() {
        store = SqliteProjectStore.openForTesting(directory.resolve("interpretation.sqlite"),
                operation -> {
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
    void twoArgIngestStillDefaultsToFixtureInterpretation() throws Exception {
        await(store.ingest(metadata("p1", "h1"), List.of(new MaterialCount(STONE, 10))));

        ProjectSnapshot project = await(store.findProject("p1")).orElseThrow();
        assertEquals("unversioned", project.interpretationId());
    }

    @Test
    void sameHashAndInterpretationRefreshesMetadataWithoutChangingCounts() throws Exception {
        await(store.ingest(metadata("p1", "h1"), List.of(new MaterialCount(STONE, 10)), "int-a"));
        ProjectSnapshot first = await(store.findProject("p1")).orElseThrow();
        int stoneId = first.materials().get(0).id();

        await(store.ingest(new SchematicMetadata("p1", "Renamed", Path.of("/elsewhere/p1.litematic"),
                "h1", new PlayerIdentity(null, "Other")), List.of(new MaterialCount(STONE, 99)), "int-a"));

        ProjectSnapshot refreshed = await(store.findProject("p1")).orElseThrow();
        assertEquals("Renamed", refreshed.name());
        assertEquals("int-a", refreshed.interpretationId());
        assertEquals(0, refreshed.revision());
        assertEquals(10, refreshed.materials().get(0).required());
        assertEquals(stoneId, refreshed.materials().get(0).id());
        assertEquals("Owner", refreshed.ownerName());
    }

    @Test
    void sameHashChangedInterpretationBumpsRevisionDiffsMaterialsAndKeepsIdsClaimsInventory()
            throws Exception {
        await(store.ingest(metadata("p1", "h1"),
                List.of(new MaterialCount(STONE, 10), new MaterialCount(DIRT, 5)), "int-a"));
        ProjectSnapshot first = await(store.findProject("p1")).orElseThrow();
        int stoneId = materialId(first, STONE);
        int dirtId = materialId(first, DIRT);
        PlayerIdentity alice = online("Alice");
        assertTrue(await(store.join("p1", stoneId, alice)));
        await(store.recordInventory("p1", stoneId, alice, 4));

        // Same bytes, new material rules: survive item keeps id/claim/inventory, revision
        // advances, and counts are replaced by the new interpretation.
        await(store.ingest(metadata("p1", "h1"),
                List.of(new MaterialCount(STONE, 20), new MaterialCount(DIRT, 7)), "int-b"));

        ProjectState state = await(store.snapshot("p1")).orElseThrow();
        assertEquals(1, state.project().revision());
        assertEquals("int-b", state.project().interpretationId());
        assertEquals(stoneId, materialId(state.project(), STONE));
        assertEquals(dirtId, materialId(state.project(), DIRT));
        assertEquals(20, material(state.project(), STONE).required());
        assertEquals(7, material(state.project(), DIRT).required());
        assertEquals(1, state.claims().size());
        ClaimSnapshot claim = state.claims().get(0);
        assertEquals(stoneId, claim.materialId());
        assertEquals(alice, claim.player());
        assertEquals(Map.of(stoneId, 4L), await(store.inventory("p1", alice)));

        // A material that disappears under the new rules loses its claim, not the item id
        // of the material that remains.
        await(store.ingest(metadata("p1", "h1"), List.of(new MaterialCount(STONE, 30)), "int-c"));
        ProjectState afterRemoval = await(store.snapshot("p1")).orElseThrow();
        assertEquals(2, afterRemoval.project().revision());
        assertEquals("int-c", afterRemoval.project().interpretationId());
        assertEquals(1, afterRemoval.project().materials().size());
        assertEquals(stoneId, afterRemoval.project().materials().get(0).id());
        assertEquals(1, afterRemoval.claims().size());
    }

    @Test
    void failedInterpretationUpdateKeepsOldInterpretationRevisionAndCounts() throws Exception {
        await(store.ingest(metadata("p1", "h1"), List.of(new MaterialCount(STONE, 10)), "int-a"));
        ProjectSnapshot first = await(store.findProject("p1")).orElseThrow();
        int stoneId = first.materials().get(0).id();
        PlayerIdentity alice = online("Alice");
        assertTrue(await(store.join("p1", stoneId, alice)));

        failNextTransaction.set(true);
        Throwable failure = failureOf(store.ingest(metadata("p1", "h1"),
                List.of(new MaterialCount(STONE, 99)), "int-b"));
        assertInstanceOf(StorageException.class, failure);

        ProjectState state = await(store.snapshot("p1")).orElseThrow();
        assertEquals(0, state.project().revision());
        assertEquals("int-a", state.project().interpretationId());
        assertEquals(10, state.project().materials().get(0).required());
        assertEquals(stoneId, state.project().materials().get(0).id());
        assertEquals(1, state.claims().size());

        await(store.ingest(metadata("p1", "h1"), List.of(new MaterialCount(STONE, 99)), "int-b"));
        ProjectState retried = await(store.snapshot("p1")).orElseThrow();
        assertEquals(1, retried.project().revision());
        assertEquals("int-b", retried.project().interpretationId());
        assertEquals(99, retried.project().materials().get(0).required());
        assertEquals(stoneId, retried.project().materials().get(0).id());
        assertEquals(1, retried.claims().size());
    }

    @Test
    void blankInterpretationIsRejectedBeforeWriting() throws Exception {
        assertInstanceOf(IllegalArgumentException.class,
                failureOf(store.ingest(metadata("p1", "h1"),
                        List.of(new MaterialCount(STONE, 1)), " ")));
        assertTrue(await(store.findProject("p1")).isEmpty());
        assertInstanceOf(IllegalArgumentException.class,
                failureOf(store.ingest(metadata("p1", "h1"),
                        List.of(new MaterialCount(STONE, 1)), null)));
    }

    private static SchematicMetadata metadata(String id, String hash) {
        return new SchematicMetadata(id, "Project " + id,
                Path.of("/schematics", id + ".litematic"), hash,
                new PlayerIdentity(null, "Owner"));
    }

    private static PlayerIdentity online(String name) {
        return new PlayerIdentity(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                name);
    }

    private static int materialId(ProjectSnapshot project, String itemId) {
        return material(project, itemId).id();
    }

    private static net.syncmaterial.syncmaterial.core.MaterialSnapshot material(
            ProjectSnapshot project, String itemId) {
        return project.materials().stream()
                .filter(material -> material.itemId().equals(itemId))
                .findFirst()
                .orElseThrow();
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
