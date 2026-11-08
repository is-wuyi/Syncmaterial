package net.syncmaterial.syncmaterial.server.ingest;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestServiceTest {
    @TempDir
    Path directory;

    @Test
    void sameHashForDifferentProjectsKeepsBothProjects() throws Exception {
        AtomicInteger parses = new AtomicInteger();
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("new.sqlite"));
             ExecutorService executor = Executors.newFixedThreadPool(2);
             IngestService ingest = new IngestService(store, path -> {
                 parses.incrementAndGet();
                 return List.of(new MaterialCount("minecraft:stone", 64));
             }, executor)) {
            CompletableFuture.allOf(ingest.ingest(metadata("a", "shared")), ingest.ingest(metadata("b", "shared")))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(2, store.listProjects().get().size());
            assertEquals(2, parses.get());
        }
    }

    @Test
    void sameHashRefreshesMetadataWithoutDecodingAgain() throws Exception {
        AtomicInteger parses = new AtomicInteger();
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("new.sqlite"));
             ExecutorService executor = Executors.newSingleThreadExecutor();
             IngestService ingest = new IngestService(store, path -> {
                 parses.incrementAndGet();
                 return List.of(new MaterialCount("minecraft:stone", 64));
             }, executor)) {
            ingest.ingest(metadata("a", "hash")).get();
            ingest.ingest(new SchematicMetadata("a", "Renamed", directory.resolve("new-path.litematic"),
                    "hash", new PlayerIdentity(null, "Owner"))).get();
            assertEquals(1, parses.get());
            var project = store.findProject("a").get().orElseThrow();
            assertEquals("Renamed", project.name());
            assertEquals(64, project.materials().get(0).required());
        }
    }

    @Test
    void failedDecodeKeepsOldProjectAndCanRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("new.sqlite"));
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            store.ingest(metadata("a", "old"), List.of(new MaterialCount("minecraft:stone", 32))).get();
            try (IngestService ingest = new IngestService(store, path -> {
                if (attempts.incrementAndGet() == 1) throw new IllegalArgumentException("Broken schematic");
                return List.of(new MaterialCount("minecraft:stone", 64));
            }, executor)) {
                assertThrows(java.util.concurrent.ExecutionException.class, () -> ingest.ingest(metadata("a", "new")).get());
                assertEquals("old", store.findProject("a").get().orElseThrow().hash());
                ingest.ingest(metadata("a", "new")).get(5, TimeUnit.SECONDS);
                assertEquals(64, store.findProject("a").get().orElseThrow().materials().get(0).required());
            }
        }
    }

    @Test
    void projectOperationsStayOrderedAcrossContentUpdatesAndDeletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("new.sqlite"));
             ExecutorService executor = Executors.newFixedThreadPool(2);
             IngestService ingest = new IngestService(store, path -> {
                 if (path.endsWith("old.litematic")) {
                     entered.countDown();
                     if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                 }
                 return List.of(new MaterialCount("minecraft:stone", path.endsWith("old.litematic") ? 32 : 64));
             }, executor)) {
            CompletableFuture<Void> old = ingest.ingest(metadata("a", "old"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> updated = ingest.ingest(metadata("a", "new"));
            release.countDown();
            CompletableFuture.allOf(old, updated).get(5, TimeUnit.SECONDS);
            assertEquals("new", store.findProject("a").get().orElseThrow().hash());
            ingest.remove("a").get();
            assertTrue(store.findProject("a").get().isEmpty());
        }
    }

    @Test
    void closePreventsInFlightDecoderFromSubmittingAWrite() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("new.sqlite"));
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            IngestService ingest = new IngestService(store, path -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                return List.of(new MaterialCount("minecraft:stone", 64));
            }, executor);
            CompletableFuture<Void> pending = ingest.ingest(metadata("a", "new"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            ingest.close();
            release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertTrue(store.findProject("a").get().isEmpty());
            assertTrue(ingest.ingest(metadata("b", "new")).isCompletedExceptionally());
        }
    }

    private SchematicMetadata metadata(String id, String hash) {
        return new SchematicMetadata(id, "Test", directory.resolve(hash + ".litematic"), hash,
                new PlayerIdentity(null, "Owner"));
    }
}
