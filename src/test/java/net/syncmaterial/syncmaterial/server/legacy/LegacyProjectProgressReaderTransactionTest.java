package net.syncmaterial.syncmaterial.server.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.runtime.OnlineInventoryTracker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic proof that one progress read holds the DB monitor for every fact. */
class LegacyProjectProgressReaderTransactionTest {
    @TempDir
    Path temporary;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @Test
    void writerCannotInterleaveBeforeTheReaderTransactionCommits() throws Exception {
        SchematicDatabase database = new SchematicDatabase();
        database.initialize(temporary.resolve("reader-transaction.db").toString());
        database.executeUpdate("INSERT INTO schematics (id, name, file_path, uploaded_by, allow_self_claim) "
                + "VALUES ('p1', 'Project', 'x', 'Owner', 1)");
        database.executeUpdate("INSERT INTO material_entries (id, schematic_id, item_id, count) "
                + "VALUES (1, 'p1', 'minecraft:stone', 100)");

        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());
        LegacyServerContext owner = mock(LegacyServerContext.class);
        when(owner.database()).thenReturn(database);
        when(owner.server()).thenReturn(server);
        when(owner.staging()).thenReturn(null);
        when(owner.onlineInventories()).thenReturn(new OnlineInventoryTracker());

        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        AtomicReference<Optional<LegacyProjectProgressReader.Facts>> readResult =
                new AtomicReference<>();
        AtomicReference<Throwable> readFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                readResult.set(LegacyProjectProgressReader.read(owner, "p1", () -> {
                    hookEntered.countDown();
                    try {
                        releaseHook.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }));
            } catch (Throwable failure) {
                readFailure.set(failure);
            }
        }, "legacy-progress-reader");
        reader.start();
        assertTrue(hookEntered.await(5, TimeUnit.SECONDS), "reader must pause inside its transaction");

        ExecutorService writerPool = Executors.newSingleThreadExecutor();
        CountDownLatch writerStarted = new CountDownLatch(1);
        Future<?> writer = writerPool.submit(() -> {
            writerStarted.countDown();
            database.executeUpdate("UPDATE material_entries SET count = 999 WHERE id = 1");
            return null;
        });
        assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
        assertFalse(writer.isDone(),
                "the writer must wait for the single enclosing progress-read transaction");

        releaseHook.countDown();
        reader.join(5_000);
        writer.get(5, TimeUnit.SECONDS);
        writerPool.shutdownNow();

        if (readFailure.get() != null) throw new AssertionError(readFailure.get());
        var facts = readResult.get().orElseThrow();
        assertEquals(100L, facts.materials().getFirst().required(),
                "the whole snapshot belongs to the pre-writer revision");
        try (var rows = database.executeQuery(
                "SELECT count FROM material_entries WHERE id = 1")) {
            assertTrue(rows.next());
            assertEquals(999L, rows.getLong(1));
        }
        database.close();
    }
}
