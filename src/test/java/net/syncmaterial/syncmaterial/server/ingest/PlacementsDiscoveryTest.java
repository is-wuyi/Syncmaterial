package net.syncmaterial.syncmaterial.server.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementsDiscoveryTest {
    @TempDir
    Path root;

    @Test
    void contentHashIsNotTheProjectIdentity() throws Exception {
        Path placements = root.resolve("placements.json");
        Path files = Files.createDirectory(root.resolve("syncmatics"));
        Files.writeString(files.resolve("shared.litematic"), "test fixture");
        Files.writeString(placements, document(entry("a", "shared"), entry("b", "shared")));
        var changed = new CopyOnWriteArrayList<String>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        try (var store = SqliteProjectStore.open(root.resolve("new.sqlite"));
             var executor = Executors.newFixedThreadPool(2);
             var ingest = new IngestService(store, path -> List.of(new MaterialCount("minecraft:stone", 64)), executor)) {
            var discovery = new PlacementsDiscovery(placements, files, ingest, changed::add, errors::add);
            discovery.scan();
            await(() -> changed.size() == 2);
            assertEquals(2, store.listProjects().get().size());
            assertTrue(errors.isEmpty());
            Files.writeString(placements, document(entry("b", "shared")));
            discovery.scan();
            await(() -> changed.size() == 3);
            assertTrue(store.findProject("a").get().isEmpty());
            assertTrue(store.findProject("b").get().isPresent());
        }
    }

    @Test
    void invalidDocumentDoesNotDeletePreviouslyCommittedProjects() throws Exception {
        Path placements = root.resolve("placements.json");
        Path files = Files.createDirectory(root.resolve("syncmatics"));
        Files.writeString(files.resolve("hash.litematic"), "test fixture");
        Files.writeString(placements, document(entry("a", "hash")));
        var changed = new CopyOnWriteArrayList<String>();
        try (var store = SqliteProjectStore.open(root.resolve("new.sqlite"));
             var executor = Executors.newSingleThreadExecutor();
             var ingest = new IngestService(store, path -> List.of(new MaterialCount("minecraft:stone", 64)), executor)) {
            var discovery = new PlacementsDiscovery(placements, files, ingest, changed::add, error -> {});
            discovery.scan();
            await(() -> changed.size() == 1);
            Files.writeString(placements, "{\"placements\":[{\"id\":\"broken\"}]}");
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, discovery::scan);
            assertTrue(store.findProject("a").get().isPresent());
        }
    }

    @Test
    void sameHashReappearanceRetriesAfterFailedReingestAndRestoresProject() throws Exception {
        Path placements = root.resolve("placements.json");
        Path files = Files.createDirectory(root.resolve("syncmatics"));
        Path fileA = files.resolve("hashA.litematic");
        Files.writeString(fileA, "fixture A");
        Files.writeString(files.resolve("hashB.litematic"), "fixture B");
        Files.writeString(placements, document(entry("a", "hashA")));

        var changed = new CopyOnWriteArrayList<String>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        CountDownLatch blockedBEntered = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        try (var store = SqliteProjectStore.open(root.resolve("new.sqlite"));
             var executor = Executors.newSingleThreadExecutor();
             var ingest = new IngestService(store, path -> {
                 String name = path.getFileName().toString();
                 if ("hashB.litematic".equals(name)) {
                     blockedBEntered.countDown();
                     if (!releaseB.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("B release timed out");
                     return List.of(new MaterialCount("minecraft:dirt", 1));
                 }
                 if (!Files.isRegularFile(path)) throw new java.io.IOException("fixture missing: " + path);
                 return List.of(new MaterialCount("minecraft:stone", 64));
             }, executor)) {
            var discovery = new PlacementsDiscovery(placements, files, ingest, changed::add, errors::add);
            discovery.scan();
            await(() -> changed.size() == 1 && store.findProject("a").join().isPresent());

            // Hold a content update (B) in the decoder so the subsequent delete stays queued.
            Files.writeString(placements, document(entry("a", "hashB")));
            discovery.scan();
            assertTrue(blockedBEntered.await(5, TimeUnit.SECONDS));

            Files.writeString(placements, document());
            discovery.scan();

            // Reappear with the original metadata while the file is unavailable: the queued
            // re-ingest runs after the queued delete and fails with an empty database.
            Files.delete(fileA);
            Files.writeString(placements, document(entry("a", "hashA")));
            discovery.scan();
            releaseB.countDown();

            await(() -> errors.size() >= 1 && store.findProject("a").join().isEmpty());

            // Restoring the file must retry despite committed still holding the same metadata.
            Files.writeString(fileA, "fixture A restored");
            discovery.scan();
            await(() -> store.findProject("a").join()
                    .map(project -> "hashA".equals(project.hash())).orElse(false));

            // A successful retry clears the marker: another scan must not notify again.
            int changedAfterRecovery = changed.size();
            discovery.scan();
            Thread.sleep(200);
            assertEquals(changedAfterRecovery, changed.size());
        } finally {
            releaseB.countDown();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Discovery did not settle");
            Thread.sleep(5);
        }
    }

    private static String entry(String id, String hash) {
        return "{\"id\":\"" + id + "\",\"hash\":\"" + hash + "\",\"display_name\":\"Test\",\"owner\":{\"name\":\"Owner\"}}";
    }

    private static String document(String... entries) {
        return "{\"placements\":[" + String.join(",", entries) + "]}";
    }
}
