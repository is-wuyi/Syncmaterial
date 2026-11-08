package net.syncmaterial.syncmaterial.server.ingest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import net.syncmaterial.syncmaterial.adapter.litematica.MaterialDecoder;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;

public final class IngestService implements AutoCloseable {
    private final SqliteProjectStore store;
    private final MaterialDecoder decoder;
    private final ExecutorService executor;
    private final Map<String, CompletableFuture<Void>> pending = new HashMap<>();
    private boolean open = true;

    public IngestService(SqliteProjectStore store, MaterialDecoder decoder, ExecutorService executor) {
        this.store = Objects.requireNonNull(store);
        this.decoder = Objects.requireNonNull(decoder);
        this.executor = Objects.requireNonNull(executor);
    }

    public CompletableFuture<Void> ingest(SchematicMetadata metadata) {
        Objects.requireNonNull(metadata);
        CompletableFuture<Void> previous;
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (this) {
            if (!open) return closedFuture();
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
            if (existing.isPresent() && existing.get().hash().equals(metadata.hash())) {
                synchronized (this) {
                    if (!open) return closedFuture();
                    // Refresh discovery metadata without decoding unchanged content.
                    return store.ingest(metadata, List.of());
                }
            }
            try {
                return CompletableFuture.supplyAsync(() -> decode(metadata), executor)
                        .thenCompose(materials -> {
                            synchronized (this) {
                                if (!open) return closedFuture();
                                // The close gate and writer submission are one operation.
                                return store.ingest(metadata, materials);
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
    }
}
