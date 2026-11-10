package net.syncmaterial.syncmaterial.server.ingest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import net.syncmaterial.syncmaterial.adapter.litematica.MaterialDecoder;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;

public final class IngestService implements AutoCloseable {
    private final SqliteProjectStore store;
    private final MaterialDecoder decoder;
    private final ExecutorService executor;
    /** Frozen per service/runtime so a decoder cannot silently change semantics mid-flight. */
    private final String interpretationId;
    private final Map<String, CompletableFuture<Void>> pending = new HashMap<>();
    /**
     * Latest discovery intent per project. Recorded when a task is accepted, never by
     * completion callbacks, so a late older parse cannot overwrite a newer announcement.
     * A missing entry means cold start / direct fixture seed: the stored interpretation
     * is the only available freshness signal.
     */
    private final Map<String, String> announcedHashes = new HashMap<>();
    /** Projects whose latest accepted intent was removal; old snapshots are not current. */
    private final Set<String> removedProjects = new HashSet<>();
    private boolean open = true;

    public IngestService(SqliteProjectStore store, MaterialDecoder decoder, ExecutorService executor) {
        this.store = Objects.requireNonNull(store);
        this.decoder = Objects.requireNonNull(decoder);
        this.executor = Objects.requireNonNull(executor);
        String declared = decoder.interpretationId();
        if (declared == null || declared.isBlank()) {
            throw new IllegalArgumentException("decoder interpretationId must not be blank");
        }
        this.interpretationId = declared;
    }

    /** Material interpretation captured when this service was created. */
    public String interpretationId() {
        return interpretationId;
    }

    /**
     * Whether a committed snapshot still matches the latest known material intent.
     *
     * <p>Checks the frozen interpretation plus the latest announced discovery hash.
     * When nothing has been announced yet (cold start or a fixture seeded directly in
     * the store), a matching interpretation is accepted. Once a latest announcement
     * differs, the old snapshot is rejected even if its rows were preserved.</p>
     */
    public synchronized boolean isMaterialSnapshotCurrent(ProjectSnapshot project) {
        Objects.requireNonNull(project, "project");
        if (!open) return false;
        if (!interpretationId.equals(project.interpretationId())) return false;
        if (removedProjects.contains(project.id())) return false;
        String announced = announcedHashes.get(project.id());
        return announced == null || announced.equals(project.hash());
    }

    public CompletableFuture<Void> ingest(SchematicMetadata metadata) {
        Objects.requireNonNull(metadata);
        CompletableFuture<Void> previous;
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (this) {
            if (!open) return closedFuture();
            // Acceptance is the announcement; completion ordering must not rewrite it.
            announcedHashes.put(metadata.id(), metadata.hash());
            removedProjects.remove(metadata.id());
            previous = pending.put(metadata.id(), result);
        }
        if (previous == null) previous = CompletableFuture.completedFuture(null);
        previous.handle((ignored, error) -> null).thenCompose(ignored -> ingestCurrent(metadata))
                .whenComplete((ignored, error) -> finish(metadata.id(), result, error));
        return result;
    }

    private CompletableFuture<Void> ingestCurrent(SchematicMetadata metadata) {
        synchronized (this) {
            if (!open) return closedFuture();
        }
        return store.findProject(metadata.id()).thenCompose(existing -> {
            if (existing.isPresent() && existing.get().hash().equals(metadata.hash())
                    && existing.get().interpretationId().equals(interpretationId)) {
                synchronized (this) {
                    if (!open) return closedFuture();
                    // Same bytes + same material rules: refresh discovery metadata without
                    // decoding. The unchanged interpretation must be passed so a production
                    // project is never downgraded to the fixture marker.
                    return store.ingest(metadata, List.of(), interpretationId);
                }
            }
            try {
                return CompletableFuture.supplyAsync(() -> decode(metadata), executor)
                        .thenCompose(materials -> {
                            synchronized (this) {
                                if (!open) return closedFuture();
                                // The close gate and writer submission are one operation.
                                return store.ingest(metadata, materials, interpretationId);
                            }
                        });
            } catch (RejectedExecutionException error) {
                return CompletableFuture.failedFuture(error);
            }
        });
    }

    private List<MaterialCount> decode(SchematicMetadata metadata) {
        try {
            synchronized (this) {
                if (!open) throw new IllegalStateException("Ingest service is closed");
            }
            return List.copyOf(decoder.decode(metadata.file()));
        } catch (Exception error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }

    public CompletableFuture<Void> remove(String projectId) {
        CompletableFuture<Void> previous;
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (this) {
            if (!open) return closedFuture();
            // Removal is the latest intent until a newer ingest is accepted.
            announcedHashes.remove(projectId);
            removedProjects.add(projectId);
            previous = pending.put(projectId, result);
        }
        if (previous == null) previous = CompletableFuture.completedFuture(null);
        previous.handle((ignored, error) -> null).thenCompose(ignored -> {
            synchronized (this) {
                if (!open) return closedFuture();
                return store.removeProject(projectId);
            }
        }).whenComplete((ignored, error) -> finish(projectId, result, error));
        return result;
    }

    private void finish(String projectId, CompletableFuture<Void> result, Throwable error) {
        synchronized (this) {
            pending.remove(projectId, result);
        }
        if (error == null) result.complete(null);
        else result.completeExceptionally(error);
    }

    private static <T> CompletableFuture<T> closedFuture() {
        return CompletableFuture.failedFuture(new IllegalStateException("Ingest service is closed"));
    }

    @Override
    public synchronized void close() {
        open = false;
        announcedHashes.clear();
        removedProjects.clear();
    }
}
