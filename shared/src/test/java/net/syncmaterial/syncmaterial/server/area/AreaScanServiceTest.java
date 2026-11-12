package net.syncmaterial.syncmaterial.server.area;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaScanServiceTest {
    private static final String WORLD = "minecraft:overworld";
    private static final String PROJECT = "project-a";
    private static final UUID RUNTIME = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void overlappingAreasShareOneChunkReadAndApplyPerArea() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        fixture.backend.areas.add(area(2, PROJECT, 8, 31, 0, 15));
        fixture.source.full.put(chunk(0, 0), List.of(observation(10, 1, 1, "minecraft:stone", 5)));

        fixture.loaded(0, 0);
        fixture.runTicks(12);

        assertEquals(1, fixture.source.scanCalls, "overlapping areas must share one chunk read");
        assertEquals(2, fixture.backend.chunkApplies.size(), "each area gets its own coverage write");
        assertEquals(1, fixture.backend.chunkApplies.get(0).observations().size());
        assertEquals(1, fixture.backend.chunkApplies.get(1).observations().size());
    }

    @Test
    void incompleteChunkReadAbandonsTokensWithoutApplyingOrSpinning() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        ChunkKey chunk = chunk(0, 0);
        fixture.source.scripted.put(chunk, new ArrayList<>(List.of(ChunkScanSlice.incomplete())));
        fixture.loaded(0, 0);

        fixture.runTicks(20);

        assertEquals(1, fixture.source.scanCalls, "an incomplete read must not be retried every tick");
        assertEquals(0, fixture.backend.chunkApplies.size(), "no partial or empty snapshot may commit");
        assertTrue(fixture.backend.abandoned > 0, "scan tokens must be abandoned so storage stays stale");
    }

    @Test
    void rescanReportsSourceFailureInsteadOfClaimingAllLoadedScanned() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        ChunkKey chunk = chunk(0, 0);
        fixture.source.scripted.put(chunk, new ArrayList<>(List.of(
                ChunkScanSlice.incomplete(),
                ChunkScanSlice.incomplete())));
        fixture.loaded(0, 0);

        CompletableFuture<AreaScanService.RescanResult> future = fixture.service.rescan(PROJECT);
        fixture.runTicks(30);

        assertTrue(future.isDone());
        AreaScanService.RescanResult result = future.join();
        assertFalse(result.success(), "a failed source read must not report rescan success");
        assertTrue(result.message().contains("源读取失败"), result.message());
    }

    @Test
    void watermarkCompletionBroadcastsSettledProjectAfterPendingReasonClears() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        fixture.source.full.put(chunk(0, 0), List.of(observation(1, 1, 1, "minecraft:stone", 5)));
        fixture.backend.deferRevision = true;
        fixture.loaded(0, 0);
        fixture.runTicks(15);

        assertTrue(fixture.broadcasts.contains(PROJECT), "the apply still broadcasts before the watermark settles");
        assertFalse(fixture.service.isInventoryCurrent(PROJECT, view(true, fixture.backend.revision)),
                "a pending watermark must keep the project not-current");

        fixture.broadcasts.clear();
        fixture.backend.completeRevisions();
        fixture.runTicks(5);

        assertTrue(fixture.broadcasts.contains(PROJECT),
                "the settled known=true state must be published after pending reasons clear");
        assertTrue(fixture.service.isInventoryCurrent(PROJECT, view(true, fixture.backend.revision)));
    }

    @Test
    void dirtyRemovalIsOnlySubmittedToAreasThatContainIt() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 7, 0, 15));
        fixture.backend.areas.add(area(2, PROJECT, 8, 15, 0, 15));
        ContainerKey right = key(10, 1, 1);
        fixture.source.full.put(chunk(0, 0), List.of(
                observation(1, 1, 1, "minecraft:stone", 5),
                observation(10, 1, 1, "minecraft:dirt", 2)));
        fixture.loaded(0, 0);
        fixture.runTicks(15);
        assertEquals(2, fixture.backend.chunkApplies.size());
        fixture.resetCounters();

        fixture.service.containerRemoved(right);
        fixture.runTicks(15);

        assertEquals(2, fixture.backend.dirtyApplies.size(), "each overlapping area gets its own dirty token");
        AppliedDirty first = fixture.backend.dirtyApplies.stream()
                .filter(applied -> applied.token().areaId() == 1).findFirst().orElseThrow();
        AppliedDirty second = fixture.backend.dirtyApplies.stream()
                .filter(applied -> applied.token().areaId() == 2).findFirst().orElseThrow();
        assertTrue(first.removals().isEmpty(), "an out-of-bounds removal must not be sent to area 1");
        assertEquals(List.of(right), second.removals());
        assertEquals(0, fixture.source.scanCalls, "a filtered dirty removal must not force a full rescan");
    }

    @Test
    void emptyObservedChunkCommitsEmptyListNotUnknown() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        fixture.loaded(0, 0);
        fixture.runTicks(12);

        assertEquals(1, fixture.backend.chunkApplies.size());
        assertTrue(fixture.backend.chunkApplies.get(0).observations().isEmpty());
        assertTrue(fixture.backend.complete.contains(chunk(0, 0)));
    }

    @Test
    void selectedDirtyUpdateRunsAfterCoalescedInvalidationAndBroadcasts() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        fixture.source.full.put(chunk(0, 0), List.of(observation(1, 1, 1, "minecraft:stone", 5)));
        fixture.loaded(0, 0);
        fixture.runTicks(12);
        fixture.resetCounters();

        ContainerKey key = key(1, 1, 1);
        fixture.source.selected.put(key, observation(1, 1, 1, "minecraft:stone", 9));
        fixture.service.containerChanged(key);
        fixture.service.containerChanged(key);

        assertEquals(1, fixture.backend.invalidated.size(), "one event burst, one fence bump");
        fixture.runTicks(12);
        assertEquals(1, fixture.backend.dirtyApplies.size());
        assertEquals(List.of(observation(1, 1, 1, "minecraft:stone", 9)),
                fixture.backend.dirtyApplies.get(0).upserts());
        assertTrue(fixture.broadcasts.contains(PROJECT));
        assertEquals(0, fixture.source.scanCalls, "selected dirty update must not rescan the chunk");
    }

    @Test
    void dirtyDuringCursorInvalidatesAndNeverCommitsOldSnapshot() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        ChunkKey chunk = chunk(0, 0);
        fixture.source.full.put(chunk, List.of(observation(1, 1, 1, "minecraft:stone", 5)));
        fixture.loaded(0, 0);
        fixture.runTicks(12);
        assertEquals(1, fixture.backend.chunkApplies.size(), "baseline must be COMPLETE before rescan");
        fixture.resetCounters();

        fixture.source.scripted.put(chunk, new ArrayList<>(List.of(
                new ChunkScanSlice(List.of(observation(1, 1, 1, "minecraft:stone", 5)),
                        ChunkScanCursor.of(List.of(key(1, 1, 1)), List.of()),
                        false, true, 1, 1))));
        fixture.source.full.put(chunk, List.of(observation(1, 1, 1, "minecraft:stone", 7)));
        fixture.service.rescan(PROJECT);
        for (int i = 0; i < 12 && fixture.source.scanCalls == 0; i++) {
            fixture.service.tick();
        }
        // The rescan has read a partial old snapshot (5) but has not applied it yet.
        assertEquals(1, fixture.source.scanCalls);

        fixture.source.selected.put(key(1, 1, 1), observation(1, 1, 1, "minecraft:stone", 8));
        fixture.service.containerChanged(key(1, 1, 1));
        fixture.runTicks(20);

        assertTrue(fixture.backend.abandoned > 0, "in-flight full-scan tokens must be abandoned");
        assertTrue(fixture.backend.chunkApplies.stream().noneMatch(a -> containsCount(a, "minecraft:stone", 5)),
                "the stale partial snapshot must never commit");
        boolean freshFull = fixture.backend.chunkApplies.stream()
                .anyMatch(a -> containsCount(a, "minecraft:stone", 7));
        boolean freshDirty = fixture.backend.dirtyApplies.stream()
                .anyMatch(a -> a.upserts().stream().anyMatch(o -> Long.valueOf(8).equals(o.counts().get("minecraft:stone"))));
        assertTrue(freshFull || freshDirty, "a newer full read or selected dirty update must win");
    }

    @Test
    void destroyedSourceIsRemovedOnlyWhileLoadedAndUnloadKeepsLastKnownRows() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        ChunkKey chunk = chunk(0, 0);
        fixture.source.full.put(chunk, List.of(
                observation(1, 1, 1, "minecraft:stone", 5),
                observation(2, 1, 2, "minecraft:dirt", 2)));
        fixture.loaded(0, 0);
        fixture.runTicks(12);
        fixture.resetCounters();

        ContainerKey removed = key(2, 1, 2);
        fixture.service.containerRemoved(removed);
        fixture.runTicks(12);
        assertEquals(1, fixture.backend.dirtyApplies.size());
        assertEquals(List.of(removed), fixture.backend.dirtyApplies.get(0).removals());

        fixture.resetCounters();
        fixture.service.chunkUnloaded(chunk);
        fixture.runTicks(12);
        assertEquals(1, fixture.backend.stales.size());
        assertTrue(fixture.backend.chunkApplies.isEmpty(), "unload must not commit an empty chunk");
        assertTrue(fixture.backend.dirtyApplies.isEmpty(), "unload must preserve canonical last-known rows");
    }

    @Test
    void unloadedChunkNeverAppliesEmptyAndReloadRescans() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        ChunkKey chunk = chunk(0, 0);
        fixture.source.full.put(chunk, List.of(observation(1, 1, 1, "minecraft:stone", 5)));
        fixture.loaded(0, 0);
        fixture.runTicks(12);
        fixture.service.chunkUnloaded(chunk);
        fixture.runTicks(4);
        fixture.resetCounters();

        fixture.source.loaded.remove(chunk);
        fixture.loaded(0, 0);
        fixture.runTicks(12);
        assertEquals(1, fixture.backend.stales.size() + fixture.backend.chunkApplies.size());
        assertTrue(fixture.backend.chunkApplies.isEmpty() || fixture.backend.chunkApplies.get(0).observations().size() == 1);
    }

    @Test
    void rescanWaitsForLoadedWorkAndReportsUnloadedChunks() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 31, 0, 15)); // two chunks
        fixture.backend.projects.add(PROJECT);
        ChunkKey loaded = chunk(0, 0);
        fixture.source.full.put(loaded, List.of(observation(1, 1, 1, "minecraft:stone", 5)));
        fixture.loaded(0, 0);

        CompletableFuture<AreaScanService.RescanResult> future = fixture.service.rescan(PROJECT);
        fixture.runTicks(2);
        assertFalse(future.isDone(), "rescan must not complete before the loaded chunk scan");

        fixture.runTicks(30);
        assertTrue(future.isDone());
        AreaScanService.RescanResult result = future.join();
        assertTrue(result.success());
        assertTrue(result.message().contains("1 个区块未加载"), result.message());
        assertTrue(fixture.backend.chunkApplies.size() >= 1);
    }

    @Test
    void rescanRejectsMissingProject() {
        Fixture fixture = new Fixture();
        fixture.runTicks(3);
        CompletableFuture<AreaScanService.RescanResult> future = fixture.service.rescan("missing");
        fixture.runTicks(6);
        assertTrue(future.isDone());
        AreaScanService.RescanResult result = future.join();
        assertFalse(result.success());
        assertTrue(result.message().contains("项目不存在"), result.message());
    }

    @Test
    void fenceRejectsOldViewAfterDirtyCommitAndPendingWork() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        ContainerKey key = key(1, 1, 1);
        fixture.source.full.put(chunk(0, 0), List.of(observation(1, 1, 1, "minecraft:stone", 5)));
        fixture.loaded(0, 0);
        fixture.runTicks(15);

        long committed = fixture.backend.revision;
        assertTrue(fixture.service.isInventoryCurrent(PROJECT, view(true, committed)));

        fixture.source.selected.put(key, observation(1, 1, 1, "minecraft:stone", 6));
        fixture.service.containerChanged(key);
        assertFalse(fixture.service.isInventoryCurrent(PROJECT, view(true, committed)),
                "pending invalidation must block readiness immediately");
        fixture.runTicks(15);
        assertFalse(fixture.service.isInventoryCurrent(PROJECT, view(true, committed)),
                "an old committed revision is not current after a newer watermark");
        assertTrue(fixture.service.isInventoryCurrent(PROJECT, view(true, fixture.backend.revision)));
        assertFalse(fixture.service.isInventoryCurrent(PROJECT, view(false, fixture.backend.revision)),
                "storage unknown must stay not-ready");
    }

    @Test
    void budgetedCursorSpansTicksAndAccountsSlots() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        ChunkKey chunk = chunk(0, 0);
        List<ContainerKey> keys = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            keys.add(key(i, 1, i));
        }
        fixture.source.scripted.put(chunk, new ArrayList<>(List.of(
                new ChunkScanSlice(List.of(), ChunkScanCursor.of(keys, List.of()), false, true, 0, 0),
                new ChunkScanSlice(List.of(), ChunkScanCursor.of(keys, List.of()), false, true, 0, 0),
                ChunkScanSlice.complete(List.of(), 8, 8))));
        fixture.loaded(0, 0);

        fixture.service.tick();
        fixture.service.tick();
        fixture.service.tick();
        assertEquals(1, fixture.source.scanCalls);
        fixture.runTicks(10);
        assertEquals(3, fixture.source.scanCalls);
        assertTrue(fixture.source.maxSlotsPerCall <= AreaScanService.MAX_SLOTS_PER_TICK);
        assertEquals(1, fixture.backend.chunkApplies.size());
    }

    @Test
    void pendingBlockEntityPositionsBecomeOpaqueObservations() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        ChunkKey chunk = chunk(0, 0);
        ContainerObservation opaque = new ContainerObservation(key(3, 2, 3), Map.of(), "pending block entity");
        fixture.source.full.put(chunk, List.of(opaque));
        fixture.loaded(0, 0);
        fixture.runTicks(12);

        assertEquals(1, fixture.backend.chunkApplies.size());
        assertEquals("pending block entity",
                fixture.backend.chunkApplies.get(0).observations().get(0).warning());
    }

    @Test
    void delayedBeginTokensAreNotCancelledBeforeTheyArriveAndDirtyStillWins() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        fixture.backend.deferChunkBegins = true;
        fixture.source.full.put(chunk(0, 0), List.of(observation(1, 1, 1, "minecraft:stone", 7)));
        fixture.loaded(0, 0);

        fixture.runTicks(6);
        assertEquals(1, fixture.backend.pendingBegins.size(), "begin callback is deliberately delayed");
        assertEquals(0, fixture.source.scanCalls);

        fixture.source.selected.put(key(1, 1, 1), observation(1, 1, 1, "minecraft:stone", 8));
        fixture.service.containerChanged(key(1, 1, 1));
        fixture.runTicks(2);
        assertTrue(fixture.backend.abandoned == 0,
                "an event during BEGINS must not cancel before the token future arrives");

        fixture.backend.completeChunkBegins();
        fixture.runTicks(30);
        assertTrue(fixture.backend.abandoned > 0, "the stale begin result is abandoned after the dirty event");
        boolean freshFull = fixture.backend.chunkApplies.stream()
                .anyMatch(a -> containsCount(a, "minecraft:stone", 7));
        boolean freshDirty = fixture.backend.dirtyApplies.stream()
                .anyMatch(a -> a.upserts().stream().anyMatch(o -> Long.valueOf(8).equals(o.counts().get("minecraft:stone"))));
        assertTrue(freshFull || freshDirty);
    }

    @Test
    void dirtyApplyWaitsForCoalescedInvalidationFence() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        fixture.source.full.put(chunk(0, 0), List.of(observation(1, 1, 1, "minecraft:stone", 5)));
        fixture.loaded(0, 0);
        fixture.runTicks(12);
        fixture.resetCounters();

        fixture.backend.deferInvalidation = true;
        ContainerKey key = key(1, 1, 1);
        fixture.source.selected.put(key, observation(1, 1, 1, "minecraft:stone", 9));
        fixture.service.containerChanged(key);
        fixture.runTicks(8);
        assertEquals(1, fixture.backend.invalidationCalls, "one event burst opens one fence transaction");
        assertEquals(0, fixture.source.dirtyReads, "the dirty read must wait for the fence commit");
        assertEquals(0, fixture.backend.dirtyApplies.size());

        fixture.backend.completeInvalidations();
        fixture.runTicks(12);
        assertEquals(1, fixture.backend.dirtyApplies.size(), "the deferred event applies after the fence settles");
        assertTrue(fixture.broadcasts.contains(PROJECT));
    }

    @Test
    void closeDrainsQueuedDirtyBeginTokensAndAbandonsThem() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        fixture.source.full.put(chunk(0, 0), List.of(observation(1, 1, 1, "minecraft:stone", 5)));
        fixture.loaded(0, 0);
        fixture.runTicks(12);
        fixture.resetCounters();

        fixture.backend.deferContainerBegins = true;
        ContainerKey key = key(1, 1, 1);
        fixture.source.selected.put(key, observation(1, 1, 1, "minecraft:stone", 9));
        fixture.service.containerChanged(key);
        fixture.runTicks(8);
        assertEquals(1, fixture.backend.pendingContainerBegins.size());

        fixture.backend.completeContainerBegins();
        fixture.service.close();
        assertEquals(1, fixture.backend.abandoned,
                "already queued begin callbacks must still abandon their tokens during close");
        assertFalse(fixture.service.isOpen());
        fixture.service.close();
        assertEquals(1, fixture.backend.abandoned, "close is idempotent");
    }

    @Test
    void closeAbandonsDirtyBeginTokensDeliveredAfterClose() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        fixture.source.full.put(chunk(0, 0), List.of(observation(1, 1, 1, "minecraft:stone", 5)));
        fixture.loaded(0, 0);
        fixture.runTicks(12);
        fixture.resetCounters();

        fixture.backend.deferContainerBegins = true;
        ContainerKey key = key(1, 1, 1);
        fixture.source.selected.put(key, observation(1, 1, 1, "minecraft:stone", 9));
        fixture.service.containerChanged(key);
        fixture.runTicks(8);
        assertEquals(1, fixture.backend.pendingContainerBegins.size());

        fixture.service.close();
        fixture.backend.completeContainerBegins();
        assertEquals(1, fixture.backend.abandoned,
                "a begin token arriving after close must be abandoned, never applied");
        assertEquals(0, fixture.backend.dirtyApplies.size());
    }

    @Test
    void closeAbandonsScanBeginTokensDeliveredAfterClose() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        fixture.source.full.put(chunk(0, 0), List.of(observation(1, 1, 1, "minecraft:stone", 5)));
        fixture.backend.deferChunkBegins = true;
        fixture.loaded(0, 0);
        fixture.runTicks(8);
        assertEquals(1, fixture.backend.pendingBegins.size());

        fixture.service.close();
        fixture.backend.completeChunkBegins();
        assertEquals(1, fixture.backend.abandoned,
                "a scan begin token arriving after close must be abandoned, never read");
        assertEquals(0, fixture.source.scanCalls);
    }

    @Test
    void rescanWithoutWorldSourceReportsFailure() {
        FakeBackend backend = new FakeBackend();
        backend.projects.add(PROJECT);
        backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        AreaScanService service = new AreaScanService(RUNTIME, ignored -> {
        }, backend);
        try {
            service.tick();
            service.tick();
            CompletableFuture<AreaScanService.RescanResult> future = service.rescan(PROJECT);
            for (int i = 0; i < 4; i++) {
                service.tick();
            }
            assertTrue(future.isDone());
            AreaScanService.RescanResult result = future.join();
            assertFalse(result.success());
            assertTrue(result.message().contains("不可用"), result.message());
        } finally {
            service.close();
        }
    }

    @Test
    void configRefreshCompletesOnlyAfterTickAppliesCatalog() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        CompletableFuture<Void> refresh = fixture.service.refreshConfiguration();
        assertFalse(refresh.isDone());
        fixture.service.tick();
        fixture.service.tick();
        assertTrue(refresh.isDone());
        assertTrue(fixture.service.configuration().isPresent());
        assertEquals(1, fixture.service.configuration().orElseThrow().areas().size());
    }

    @Test
    void closeCompletesRefreshAndRescanFutures() {
        Fixture fixture = new Fixture();
        fixture.backend.deferCatalog = true;
        CompletableFuture<Void> refresh = fixture.service.refreshConfiguration();
        CompletableFuture<AreaScanService.RescanResult> rescan = fixture.service.rescan(PROJECT);
        fixture.service.close();
        assertTrue(refresh.isCompletedExceptionally());
        assertTrue(rescan.isDone());
        assertFalse(rescan.join().success());
    }

    @Test
    void containerOutsideAreasDoesNotQueueWork() {
        Fixture fixture = new Fixture();
        fixture.backend.areas.add(area(1, PROJECT, 0, 15, 0, 15));
        fixture.loaded(0, 0);
        fixture.runTicks(12);
        fixture.resetCounters();
        fixture.service.containerChanged(key(100, 5, 100));
        fixture.runTicks(6);
        assertEquals(0, fixture.backend.invalidated.size());
        assertEquals(0, fixture.backend.dirtyApplies.size());
    }

    // ==================================================================

    private static final class Fixture {
        private final FakeBackend backend = new FakeBackend();
        private final FakeSource source = new FakeSource();
        private final List<String> broadcasts = new ArrayList<>();
        private final AreaScanService service;

        private Fixture() {
            backend.projects.add(PROJECT);
            service = new AreaScanService(RUNTIME, broadcasts::add, backend);
            service.attachSource(source);
        }

        private void loaded(int chunkX, int chunkZ) {
            ChunkKey chunk = chunk(chunkX, chunkZ);
            source.loaded.add(chunk);
            service.chunkLoaded(chunk);
        }

        private void runTicks(int ticks) {
            for (int i = 0; i < ticks; i++) {
                service.tick();
            }
        }

        private void resetCounters() {
            backend.invalidated.clear();
            backend.dirtyApplies.clear();
            backend.stales.clear();
            backend.chunkApplies.clear();
            backend.abandoned = 0;
            source.scanCalls = 0;
            source.dirtyReads = 0;
            broadcasts.clear();
        }
    }

    private static final class FakeBackend implements AreaScanBackend {
        private final List<AreaDefinition> areas = new ArrayList<>();
        private final Set<String> projects = new HashSet<>();
        private final List<AppliedChunk> chunkApplies = new ArrayList<>();
        private final List<AppliedDirty> dirtyApplies = new ArrayList<>();
        private final List<ChunkKey> invalidated = new ArrayList<>();
        private final List<ChunkKey> stales = new ArrayList<>();
        private final Set<ChunkKey> complete = new HashSet<>();
        private final Set<ChunkKey> dirtyState = new HashSet<>();
        private final Map<Long, ScanToken> tokens = new HashMap<>();
        private long epoch;
        private long revision;
        private long nextToken;
        private int abandoned;
        private boolean deferCatalog;
        private boolean deferChunkBegins;
        private boolean deferContainerBegins;
        private boolean deferInvalidation;
        private boolean deferRevision;
        private int invalidationCalls;
        private final List<CompletableFuture<Optional<ScanToken>>> pendingBegins = new ArrayList<>();
        private final List<ScanToken> pendingBeginTokens = new ArrayList<>();
        private final List<CompletableFuture<Optional<ScanToken>>> pendingContainerBegins = new ArrayList<>();
        private final List<ScanToken> pendingContainerTokens = new ArrayList<>();
        private final List<Runnable> pendingInvalidations = new ArrayList<>();
        private final List<CompletableFuture<Long>> pendingRevisions = new ArrayList<>();
        private CompletableFuture<AreaCatalog> deferredCatalog = new CompletableFuture<>();

        @Override
        public CompletableFuture<Long> beginEpoch() {
            return CompletableFuture.completedFuture(++epoch);
        }

        @Override
        public CompletableFuture<AreaCatalog> areaCatalog() {
            AreaCatalog catalog = new AreaCatalog(List.copyOf(areas), Map.of(), Map.of(PROJECT, "Project"), revision);
            if (deferCatalog) {
                return deferredCatalog;
            }
            return CompletableFuture.completedFuture(catalog);
        }

        @Override
        public CompletableFuture<Optional<ProjectSnapshot>> findProject(String projectId) {
            if (!projects.contains(projectId)) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return CompletableFuture.completedFuture(Optional.of(new ProjectSnapshot(
                    projectId, "Project", "hash", new PlayerIdentity(null, "Owner"),
                    true, List.of(), List.of(), 0)));
        }

        @Override
        public CompletableFuture<Optional<ScanToken>> beginChunkScan(int areaId, long geometryRevision, ChunkKey chunk) {
            AreaDefinition area = find(areaId);
            if (area == null || area.geometryRevision() != geometryRevision
                    || !area.world().equals(chunk.world())
                    || !area.bounds().containsChunk(chunk.x(), chunk.z())) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            ScanToken token = token(areaId, chunk, ++nextToken);
            tokens.put(token.generation(), token);
            revision++;
            if (!deferChunkBegins) {
                return CompletableFuture.completedFuture(Optional.of(token));
            }
            CompletableFuture<Optional<ScanToken>> pending = new CompletableFuture<>();
            pendingBegins.add(pending);
            pendingBeginTokens.add(token);
            return pending;
        }

        private void completeChunkBegins() {
            deferChunkBegins = false;
            List<CompletableFuture<Optional<ScanToken>>> futures = new ArrayList<>(pendingBegins);
            List<ScanToken> completed = new ArrayList<>(pendingBeginTokens);
            pendingBegins.clear();
            pendingBeginTokens.clear();
            for (int i = 0; i < futures.size(); i++) {
                futures.get(i).complete(Optional.of(completed.get(i)));
            }
        }

        @Override
        public CompletableFuture<Boolean> applyChunkScan(ScanToken token, List<ContainerObservation> observations) {
            if (!tokens.containsKey(token.generation())) {
                return CompletableFuture.completedFuture(false);
            }
            chunkApplies.add(new AppliedChunk(token, List.copyOf(observations)));
            complete.add(token.chunk());
            dirtyState.remove(token.chunk());
            revision++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> abandonChunkScan(ScanToken token) {
            abandoned++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Optional<ScanToken>> beginContainerUpdates(int areaId, long geometryRevision, ChunkKey chunk) {
            AreaDefinition area = find(areaId);
            if (area == null || area.geometryRevision() != geometryRevision
                    || !complete.contains(chunk) && !dirtyState.contains(chunk)) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            ScanToken token = token(areaId, chunk, ++nextToken);
            tokens.put(token.generation(), token);
            dirtyState.add(chunk);
            revision++;
            if (!deferContainerBegins) {
                return CompletableFuture.completedFuture(Optional.of(token));
            }
            CompletableFuture<Optional<ScanToken>> pending = new CompletableFuture<>();
            pendingContainerBegins.add(pending);
            pendingContainerTokens.add(token);
            return pending;
        }

        private void completeContainerBegins() {
            deferContainerBegins = false;
            List<CompletableFuture<Optional<ScanToken>>> futures = new ArrayList<>(pendingContainerBegins);
            List<ScanToken> completed = new ArrayList<>(pendingContainerTokens);
            pendingContainerBegins.clear();
            pendingContainerTokens.clear();
            for (int i = 0; i < futures.size(); i++) {
                futures.get(i).complete(Optional.of(completed.get(i)));
            }
        }

        @Override
        public CompletableFuture<Boolean> applyContainerUpdates(ScanToken token, List<ContainerObservation> upserts,
                                                                List<ContainerKey> removals) {
            if (!tokens.containsKey(token.generation())) {
                return CompletableFuture.completedFuture(false);
            }
            dirtyApplies.add(new AppliedDirty(token, List.copyOf(upserts), List.copyOf(removals)));
            dirtyState.remove(token.chunk());
            complete.add(token.chunk());
            revision++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Integer> invalidateChunk(ChunkKey chunk) {
            invalidationCalls++;
            if (!deferInvalidation) {
                commitInvalidation(chunk);
                return CompletableFuture.completedFuture(1);
            }
            CompletableFuture<Integer> pending = new CompletableFuture<>();
            pendingInvalidations.add(() -> {
                commitInvalidation(chunk);
                pending.complete(1);
            });
            return pending;
        }

        private void commitInvalidation(ChunkKey chunk) {
            invalidated.add(chunk);
            if (complete.remove(chunk)) {
                dirtyState.add(chunk);
            }
            revision++;
        }

        private void completeInvalidations() {
            deferInvalidation = false;
            List<Runnable> actions = new ArrayList<>(pendingInvalidations);
            pendingInvalidations.clear();
            for (Runnable action : actions) {
                action.run();
            }
        }

        @Override
        public CompletableFuture<Integer> markChunkStale(ChunkKey chunk) {
            stales.add(chunk);
            complete.remove(chunk);
            dirtyState.remove(chunk);
            revision++;
            return CompletableFuture.completedFuture(1);
        }

        @Override
        public CompletableFuture<Long> inventoryRevision() {
            if (deferRevision) {
                CompletableFuture<Long> pending = new CompletableFuture<>();
                pendingRevisions.add(pending);
                return pending;
            }
            return CompletableFuture.completedFuture(revision);
        }

        private void completeRevisions() {
            deferRevision = false;
            List<CompletableFuture<Long>> futures = new ArrayList<>(pendingRevisions);
            pendingRevisions.clear();
            for (CompletableFuture<Long> future : futures) {
                future.complete(revision);
            }
        }

        private ScanToken token(int areaId, ChunkKey chunk, long generation) {
            AreaDefinition area = find(areaId);
            return new ScanToken(epoch, areaId, area.geometryRevision(), chunk,
                    generation, 0, 0, generation);
        }

        private AreaDefinition find(int areaId) {
            for (AreaDefinition area : areas) {
                if (area.id() == areaId) {
                    return area;
                }
            }
            return null;
        }
    }

    private record AppliedChunk(AreaScanBackend.ScanToken token, List<ContainerObservation> observations) {
    }

    private record AppliedDirty(AreaScanBackend.ScanToken token,
                                List<ContainerObservation> upserts,
                                List<ContainerKey> removals) {
    }

    private static final class FakeSource implements AreaSnapshotSource {
        private final Set<ChunkKey> loaded = new LinkedHashSet<>();
        private final Map<ChunkKey, List<ContainerObservation>> full = new HashMap<>();
        private final Map<ChunkKey, List<ChunkScanSlice>> scripted = new HashMap<>();
        private final Map<ContainerKey, ContainerObservation> selected = new HashMap<>();
        private int scanCalls;
        private int dirtyReads;
        private int maxSlotsPerCall;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Set<ChunkKey> loadedChunks() {
            return Set.copyOf(loaded);
        }

        @Override
        public boolean isChunkLoaded(ChunkKey chunk) {
            return loaded.contains(chunk);
        }

        @Override
        public ChunkScanSlice scanChunk(ChunkKey chunk, ChunkScanFilter filter, ChunkScanCursor cursor,
                                        ScanBudget budget) {
            scanCalls++;
            maxSlotsPerCall = Math.max(maxSlotsPerCall, budget.maxSlots());
            List<ChunkScanSlice> slices = scripted.get(chunk);
            if (slices != null && !slices.isEmpty()) {
                return slices.remove(0);
            }
            List<ContainerObservation> observations = full.getOrDefault(chunk, List.of());
            return ChunkScanSlice.complete(observations, observations.size(), observations.size());
        }

        @Override
        public ContainerReadResult readContainers(ChunkKey chunk, ChunkScanFilter filter, Set<ContainerKey> keys,
                                                  ScanBudget budget) {
            dirtyReads++;
            maxSlotsPerCall = Math.max(maxSlotsPerCall, budget.maxSlots());
            List<ContainerObservation> observations = new ArrayList<>();
            for (ContainerKey key : keys) {
                ContainerObservation observation = selected.get(key);
                if (observation != null) {
                    observations.add(observation);
                }
            }
            return new ContainerReadResult(observations, true, false, observations.size(), observations.size());
        }
    }

    private static AreaDefinition area(int id, String project, int x1, int x2, int z1, int z2) {
        return new AreaDefinition(id, project, AreaKind.STAGING_AREA, "Area " + id, WORLD,
                new AreaBounds(x1, 0, z1, x2, 10, z2), id, id);
    }

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD, x, z);
    }

    private static ContainerKey key(int x, int y, int z) {
        return new ContainerKey(WORLD, x, y, z);
    }

    private static ContainerObservation observation(int x, int y, int z, String item, long count) {
        return new ContainerObservation(key(x, y, z), Map.of(item, count), "");
    }

    private static AreaInventoryView view(boolean known, long revision) {
        return new AreaInventoryView(Map.of(), Map.of(), List.of(), known, false, revision);
    }

    private static boolean containsCount(AppliedChunk applied, String item, long count) {
        for (ContainerObservation observation : applied.observations()) {
            if (Long.valueOf(count).equals(observation.counts().get(item))) {
                return true;
            }
        }
        return false;
    }
}
