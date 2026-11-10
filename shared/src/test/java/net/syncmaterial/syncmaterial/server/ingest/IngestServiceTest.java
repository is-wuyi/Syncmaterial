package net.syncmaterial.syncmaterial.server.ingest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.syncmaterial.syncmaterial.adapter.litematica.MaterialDecoder;
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

    @Test
    void sameHashChangedInterpretationDecodesAgainAndMetadataRefreshKeepsIt() throws Exception {
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("new.sqlite"))) {
            MutableDecoder firstDecoder = new MutableDecoder("int-a", 32);
            try (ExecutorService executor = Executors.newSingleThreadExecutor();
                 IngestService ingest = new IngestService(store, firstDecoder, executor)) {
                ingest.ingest(metadata("a", "h")).get(5, TimeUnit.SECONDS);
                var first = store.findProject("a").get().orElseThrow();
                assertEquals("int-a", first.interpretationId());
                assertEquals(32, first.materials().get(0).required());
                assertEquals(0, first.revision());
                assertEquals(1, firstDecoder.calls);
            }

            // A new service/runtime advertises new material rules. Same hash must be
            // decoded again instead of being treated as an unchanged project.
            MutableDecoder secondDecoder = new MutableDecoder("int-b", 64);
            try (ExecutorService executor = Executors.newSingleThreadExecutor();
                 IngestService ingest = new IngestService(store, secondDecoder, executor)) {
                ingest.ingest(metadata("a", "h")).get(5, TimeUnit.SECONDS);
                var reinterpreted = store.findProject("a").get().orElseThrow();
                assertEquals("int-b", reinterpreted.interpretationId());
                assertEquals(64, reinterpreted.materials().get(0).required());
                assertEquals(1, reinterpreted.revision());
                assertEquals(1, secondDecoder.calls);

                // Same hash + same interpretation may refresh discovery metadata without
                // a decode, and must not downgrade the production interpretation id.
                ingest.ingest(new SchematicMetadata("a", "Renamed", directory.resolve("moved.litematic"),
                        "h", new PlayerIdentity(null, "Owner"))).get(5, TimeUnit.SECONDS);
                var refreshed = store.findProject("a").get().orElseThrow();
                assertEquals("Renamed", refreshed.name());
                assertEquals("int-b", refreshed.interpretationId());
                assertEquals(64, refreshed.materials().get(0).required());
                assertEquals(1, refreshed.revision());
                assertEquals(1, secondDecoder.calls);
            }
        }
    }

    @Test
    void failedDecodeWithNewInterpretationKeepsOldCountsAndInterpretation() throws Exception {
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("new.sqlite"))) {
            MutableDecoder oldDecoder = new MutableDecoder("int-a", 32);
            try (ExecutorService executor = Executors.newSingleThreadExecutor();
                 IngestService ingest = new IngestService(store, oldDecoder, executor)) {
                ingest.ingest(metadata("a", "h")).get(5, TimeUnit.SECONDS);
            }

            MutableDecoder newDecoder = new MutableDecoder("int-b", 64);
            newDecoder.fail = true;
            try (ExecutorService executor = Executors.newSingleThreadExecutor();
                 IngestService ingest = new IngestService(store, newDecoder, executor)) {
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> ingest.ingest(metadata("a", "h")).get(5, TimeUnit.SECONDS));

                var afterFailure = store.findProject("a").get().orElseThrow();
                assertEquals("int-a", afterFailure.interpretationId());
                assertEquals(32, afterFailure.materials().get(0).required());
                assertEquals(0, afterFailure.revision());

                newDecoder.fail = false;
                ingest.ingest(metadata("a", "h")).get(5, TimeUnit.SECONDS);
                var retried = store.findProject("a").get().orElseThrow();
                assertEquals("int-b", retried.interpretationId());
                assertEquals(64, retried.materials().get(0).required());
                assertEquals(1, retried.revision());
            }
        }
    }

    @Test
    void hashChangeFailureKeepsRowsButRejectsOldSnapshotUntilRetrySucceeds() throws Exception {
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("new.sqlite"))) {
            MutableDecoder decoder = new MutableDecoder("int-a", 32);
            try (ExecutorService executor = Executors.newSingleThreadExecutor();
                 IngestService ingest = new IngestService(store, decoder, executor)) {
                ingest.ingest(metadata("a", "old")).get(5, TimeUnit.SECONDS);
                var oldSnapshot = store.findProject("a").get().orElseThrow();
                assertTrue(ingest.isMaterialSnapshotCurrent(oldSnapshot));
                int materialId = oldSnapshot.materials().get(0).id();
                PlayerIdentity alice = online("Alice");
                assertTrue(store.join("a", materialId, alice).get(5, TimeUnit.SECONDS));
                store.recordInventory("a", materialId, alice, 4).get(5, TimeUnit.SECONDS);

                decoder.fail = true;
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> ingest.ingest(metadata("a", "new")).get(5, TimeUnit.SECONDS));

                var preserved = store.findProject("a").get().orElseThrow();
                assertEquals("old", preserved.hash());
                assertEquals(32, preserved.materials().get(0).required());
                assertEquals(materialId, preserved.materials().get(0).id());
                assertEquals(1, store.snapshot("a").get().orElseThrow().claims().size());
                assertEquals(4L, store.inventory("a", alice).get(5, TimeUnit.SECONDS).get(materialId));
                assertFalse(ingest.isMaterialSnapshotCurrent(preserved),
                        "preserved rows for the old hash must not be presented as current");

                decoder.fail = false;
                decoder.count = 64;
                ingest.ingest(metadata("a", "new")).get(5, TimeUnit.SECONDS);
                var retried = store.findProject("a").get().orElseThrow();
                assertEquals("new", retried.hash());
                assertEquals(64, retried.materials().get(0).required());
                assertEquals(materialId, retried.materials().get(0).id());
                assertEquals(1, store.snapshot("a").get().orElseThrow().claims().size());
                assertTrue(ingest.isMaterialSnapshotCurrent(retried));
            }
        }
    }

    @Test
    void queuedNewerAnnouncementSurvivesOlderCompletion() throws Exception {
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch newEntered = new CountDownLatch(1);
        CountDownLatch releaseNew = new CountDownLatch(1);
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("queued.sqlite"));
             ExecutorService executor = Executors.newSingleThreadExecutor();
             IngestService ingest = new IngestService(store, path -> {
                 String fileName = path.getFileName().toString();
                 if (fileName.equals("old.litematic")) {
                     oldEntered.countDown();
                     if (!releaseOld.await(5, TimeUnit.SECONDS)) {
                         throw new IllegalStateException("old decode timed out");
                     }
                     return List.of(new MaterialCount("minecraft:stone", 32));
                 }
                 newEntered.countDown();
                 if (!releaseNew.await(5, TimeUnit.SECONDS)) {
                     throw new IllegalStateException("new decode timed out");
                 }
                 return List.of(new MaterialCount("minecraft:stone", 64));
             }, executor)) {
            CompletableFuture<Void> older = ingest.ingest(metadata("a", "old"));
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> newer = ingest.ingest(metadata("a", "new"));
            releaseOld.countDown();
            older.get(5, TimeUnit.SECONDS);
            assertTrue(newEntered.await(5, TimeUnit.SECONDS));

            var oldSnapshot = store.findProject("a").get().orElseThrow();
            assertEquals("old", oldSnapshot.hash());
            assertFalse(ingest.isMaterialSnapshotCurrent(oldSnapshot),
                    "older completion must not replace the newer announced hash");

            releaseNew.countDown();
            newer.get(5, TimeUnit.SECONDS);
            var newSnapshot = store.findProject("a").get().orElseThrow();
            assertEquals("new", newSnapshot.hash());
            assertTrue(ingest.isMaterialSnapshotCurrent(newSnapshot));
        }
    }

    @Test
    void removeWhileIngestInFlightRejectsPreservedSnapshotAndLateCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("remove.sqlite"));
             ExecutorService executor = Executors.newSingleThreadExecutor();
             IngestService ingest = new IngestService(store, path -> {
                 if (!path.getFileName().toString().equals("new.litematic")) {
                     return List.of(new MaterialCount("minecraft:stone", 32));
                 }
                 entered.countDown();
                 if (!release.await(5, TimeUnit.SECONDS)) {
                     throw new IllegalStateException("new decode timed out");
                 }
                 return List.of(new MaterialCount("minecraft:stone", 64));
             }, executor)) {
            ingest.ingest(metadata("a", "old")).get(5, TimeUnit.SECONDS);
            var oldSnapshot = store.findProject("a").get().orElseThrow();
            assertTrue(ingest.isMaterialSnapshotCurrent(oldSnapshot));

            CompletableFuture<Void> inFlight = ingest.ingest(metadata("a", "new"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> removal = ingest.remove("a");
            assertFalse(ingest.isMaterialSnapshotCurrent(oldSnapshot),
                    "accepted removal rejects the preserved snapshot");

            release.countDown();
            inFlight.get(5, TimeUnit.SECONDS);
            assertFalse(ingest.isMaterialSnapshotCurrent(oldSnapshot),
                    "late decode completion must not clear the removal intent");
            removal.get(5, TimeUnit.SECONDS);
            assertTrue(store.findProject("a").get().isEmpty());
            assertFalse(ingest.isMaterialSnapshotCurrent(oldSnapshot));
        }
    }

    @Test
    void coldStartAcceptsMatchingStoredInterpretationUntilConflictingAnnouncement() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("cold.sqlite"))) {
            store.ingest(metadata("a", "old"), List.of(new MaterialCount("minecraft:stone", 32)), "int-a")
                    .get(5, TimeUnit.SECONDS);
            MaterialDecoder blockingDecoder = new MaterialDecoder() {
                @Override
                public List<MaterialCount> decode(Path path) throws Exception {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("decode timed out");
                    }
                    return List.of(new MaterialCount("minecraft:stone", 64));
                }

                @Override
                public String interpretationId() {
                    return "int-a";
                }
            };
            try (ExecutorService executor = Executors.newSingleThreadExecutor();
                 IngestService ingest = new IngestService(store, blockingDecoder, executor)) {
                var coldSnapshot = store.findProject("a").get().orElseThrow();
                assertTrue(ingest.isMaterialSnapshotCurrent(coldSnapshot),
                        "no announcement yet: matching stored interpretation stays usable");

                CompletableFuture<Void> pending = ingest.ingest(metadata("a", "new"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(ingest.isMaterialSnapshotCurrent(coldSnapshot),
                        "a conflicting announcement rejects the old snapshot while decoding");
                release.countDown();
                pending.get(5, TimeUnit.SECONDS);
                assertTrue(ingest.isMaterialSnapshotCurrent(store.findProject("a").get().orElseThrow()));
            }
        }
    }

    @Test
    void closeClearsAnnouncementsAndColdStartAcceptsStoredInterpretationAgain() throws Exception {
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("close.sqlite"));
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            IngestService first = new IngestService(store, new MutableDecoder("int-a", 32), executor);
            first.ingest(metadata("a", "old")).get(5, TimeUnit.SECONDS);
            var snapshot = store.findProject("a").get().orElseThrow();
            assertTrue(first.isMaterialSnapshotCurrent(snapshot));

            first.close();
            assertFalse(first.isMaterialSnapshotCurrent(snapshot));

            IngestService reopened = new IngestService(store, new MutableDecoder("int-a", 32), executor);
            try {
                assertTrue(reopened.isMaterialSnapshotCurrent(snapshot),
                        "cold start accepts a matching stored interpretation again");
            } finally {
                reopened.close();
            }
        }
    }

    private static PlayerIdentity online(String name) {
        return new PlayerIdentity(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
    }

    private static final class MutableDecoder implements MaterialDecoder {
        private String interpretation;
        private int count;
        private boolean fail;
        private int calls;

        private MutableDecoder(String interpretation, int count) {
            this.interpretation = interpretation;
            this.count = count;
        }

        @Override
        public List<MaterialCount> decode(Path schematicPath) throws Exception {
            calls++;
            if (fail) {
                throw new IllegalArgumentException("Broken schematic");
            }
            return List.of(new MaterialCount("minecraft:stone", count));
        }

        @Override
        public String interpretationId() {
            return interpretation;
        }
    }

    private SchematicMetadata metadata(String id, String hash) {
        return new SchematicMetadata(id, "Test", directory.resolve(hash + ".litematic"), hash,
                new PlayerIdentity(null, "Owner"));
    }
}
