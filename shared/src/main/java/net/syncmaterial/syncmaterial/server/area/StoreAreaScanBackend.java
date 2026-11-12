package net.syncmaterial.syncmaterial.server.area;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.server.storage.AreaScanStore;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;

/** Production adapter from the scanner port to the single-writer SQLite store. */
final class StoreAreaScanBackend implements AreaScanBackend {
    private final SqliteProjectStore store;

    StoreAreaScanBackend(SqliteProjectStore store) {
        this.store = store;
    }

    private AreaScanStore scanStore() {
        return store.areaScanStore();
    }

    @Override
    public CompletableFuture<Long> beginEpoch() {
        return scanStore().beginEpoch();
    }

    @Override
    public CompletableFuture<AreaCatalog> areaCatalog() {
        return store.areaCatalog();
    }

    @Override
    public CompletableFuture<Optional<ProjectSnapshot>> findProject(String projectId) {
        return store.findProject(projectId);
    }

    @Override
    public CompletableFuture<Optional<ScanToken>> beginChunkScan(int areaId, long geometryRevision,
                                                                 ChunkKey chunk) {
        return scanStore().beginChunkScan(areaId, geometryRevision, chunk)
                .thenApply(token -> token.map(StoreAreaScanBackend::token));
    }

    @Override
    public CompletableFuture<Boolean> applyChunkScan(ScanToken token,
                                                    List<ContainerObservation> observations) {
        return scanStore().applyChunkScan(storageToken(token), observations);
    }

    @Override
    public CompletableFuture<Boolean> abandonChunkScan(ScanToken token) {
        return scanStore().abandonChunkScan(storageToken(token));
    }

    @Override
    public CompletableFuture<Optional<ScanToken>> beginContainerUpdates(int areaId, long geometryRevision,
                                                                        ChunkKey chunk) {
        return scanStore().beginContainerUpdates(areaId, geometryRevision, chunk)
                .thenApply(token -> token.map(StoreAreaScanBackend::token));
    }

    @Override
    public CompletableFuture<Boolean> applyContainerUpdates(ScanToken token,
                                                           List<ContainerObservation> upserts,
                                                           List<ContainerKey> removals) {
        return scanStore().applyContainerUpdates(storageToken(token), upserts, removals);
    }

    @Override
    public CompletableFuture<Integer> invalidateChunk(ChunkKey chunk) {
        return scanStore().invalidateChunk(chunk);
    }

    @Override
    public CompletableFuture<Integer> markChunkStale(ChunkKey chunk) {
        return scanStore().markChunkStale(chunk);
    }

    @Override
    public CompletableFuture<Long> inventoryRevision() {
        return scanStore().inventoryRevision();
    }

    private static ScanToken token(AreaScanStore.ChunkToken token) {
        return new ScanToken(token.epoch(), token.areaId(), token.geometryRevision(), token.chunk(),
                token.generation(), token.dirtyGeneration(), token.fenceGeneration(),
                token.observationSequence());
    }

    private static AreaScanStore.ChunkToken storageToken(ScanToken token) {
        return new AreaScanStore.ChunkToken(token.epoch(), token.areaId(), token.geometryRevision(),
                token.chunk(), token.generation(), token.dirtyGeneration(), token.fenceGeneration(),
                token.observationSequence());
    }
}
