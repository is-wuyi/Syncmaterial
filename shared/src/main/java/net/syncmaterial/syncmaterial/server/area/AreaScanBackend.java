package net.syncmaterial.syncmaterial.server.area;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;

/**
 * Internal storage port used by {@link AreaScanService}. Production uses the SQLite scan
 * store; scheduler tests use an in-memory fake. All returned futures complete on a storage
 * thread, never on the server tick thread.
 */
interface AreaScanBackend {
    CompletableFuture<Long> beginEpoch();

    CompletableFuture<AreaCatalog> areaCatalog();

    CompletableFuture<Optional<ProjectSnapshot>> findProject(String projectId);

    CompletableFuture<Optional<ScanToken>> beginChunkScan(int areaId, long geometryRevision, ChunkKey chunk);

    CompletableFuture<Boolean> applyChunkScan(ScanToken token, List<ContainerObservation> observations);

    CompletableFuture<Boolean> abandonChunkScan(ScanToken token);

    CompletableFuture<Optional<ScanToken>> beginContainerUpdates(int areaId, long geometryRevision, ChunkKey chunk);

    CompletableFuture<Boolean> applyContainerUpdates(ScanToken token, List<ContainerObservation> upserts,
                                                     List<ContainerKey> removals);

    CompletableFuture<Integer> invalidateChunk(ChunkKey chunk);

    CompletableFuture<Integer> markChunkStale(ChunkKey chunk);

    /**
     * Current inventory-state watermark. Issued on the same single-writer queue, so a read
     * queued after a commit proves the minimum revision a delivery may use.
     */
    CompletableFuture<Long> inventoryRevision();

    /** Scanner-local mirror of the storage CAS envelope. */
    record ScanToken(long epoch, int areaId, long geometryRevision, ChunkKey chunk,
                     long generation, long dirtyGeneration, long fenceGeneration,
                     long observationSequence) {
    }
}
