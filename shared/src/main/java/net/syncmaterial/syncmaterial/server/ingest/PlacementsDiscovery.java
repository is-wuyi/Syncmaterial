package net.syncmaterial.syncmaterial.server.ingest;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;

public final class PlacementsDiscovery {
    private static final long MAX_PLACEMENTS_BYTES = 8L * 1024 * 1024;
    private final Path placementsFile;
    private final Path schematicsRoot;
    private final IngestService ingest;
    private final Consumer<String> onChanged;
    private final Consumer<Throwable> onFailure;
    private final Map<String, SchematicMetadata> committed = new HashMap<>();
    private final Map<String, SchematicMetadata> scheduled = new HashMap<>();
    private final Set<String> deleting = new HashSet<>();
    private final Set<String> retry = new HashSet<>();

    public PlacementsDiscovery(Path placementsFile, Path schematicsRoot, IngestService ingest,
                               Consumer<String> onChanged, Consumer<Throwable> onFailure) {
        this.placementsFile = placementsFile;
        this.schematicsRoot = schematicsRoot;
        this.ingest = ingest;
        this.onChanged = onChanged;
        this.onFailure = onFailure;
    }

    public synchronized void scan() throws IOException {
        if (!Files.isRegularFile(placementsFile)) return;
        if (Files.size(placementsFile) > MAX_PLACEMENTS_BYTES) {
            throw new IOException("Placements file exceeds its size limit");
        }
        Map<String, SchematicMetadata> desired = readPlacements();
        for (var metadata : desired.values()) {
            boolean scheduledAlready = metadata.equals(scheduled.get(metadata.id()));
            boolean committedAlready = metadata.equals(committed.get(metadata.id()));
            if (!deleting.contains(metadata.id())
                    && (scheduledAlready || (committedAlready && !retry.contains(metadata.id())))) continue;
            deleting.remove(metadata.id());
            scheduled.put(metadata.id(), metadata);
            CompletableFuture<Void> operation = ingest.ingest(metadata);
            operation.whenComplete((ignored, error) -> completed(metadata, error));
        }
        Set<String> removed = new HashSet<>(committed.keySet());
        removed.addAll(scheduled.keySet());
        removed.removeAll(desired.keySet());
        for (String projectId : removed) {
            retry.remove(projectId);
            if (!deleting.add(projectId)) continue;
            scheduled.remove(projectId);
            ingest.remove(projectId).whenComplete((ignored, error) -> {
                synchronized (this) {
                    if (!deleting.remove(projectId)) return;
                    if (error != null) {
                        // The removal intent was accepted by the ingest service even
                        // though the row survived. Subscribers must re-read the
                        // snapshot so the freshness guard can report it as expired.
                        onChanged.accept(projectId);
                        onFailure.accept(error);
                    } else {
                        committed.remove(projectId);
                        onChanged.accept(projectId);
                    }
                }
            });
        }
    }

    private synchronized void completed(SchematicMetadata metadata, Throwable error) {
        if (!metadata.equals(scheduled.get(metadata.id()))) return;
        scheduled.remove(metadata.id());
        if (error != null) {
            // A same-hash re-ingest can fail after a pending delete already removed the
            // project. Keep a retry marker so the next scan does not mistake the stale
            // committed metadata for a live database row.
            retry.add(metadata.id());
            // The ingest service already accepted this latest announcement, so the
            // preserved rows are stale even though parsing failed. Notify subscribers
            // so the freshness guard rejects them instead of waiting for an unrelated
            // inventory tick; this is a re-read request, not a success announcement.
            onChanged.accept(metadata.id());
            onFailure.accept(error);
            return;
        }
        retry.remove(metadata.id());
        committed.put(metadata.id(), metadata);
        onChanged.accept(metadata.id());
    }

    private Map<String, SchematicMetadata> readPlacements() throws IOException {
        try {
            var root = JsonParser.parseString(Files.readString(placementsFile)).getAsJsonObject();
            var entries = root.getAsJsonArray("placements");
            if (entries == null) throw new IOException("Placements array is missing");
            Map<String, SchematicMetadata> result = new HashMap<>();
            for (var element : entries) {
                var entry = element.getAsJsonObject();
                String id = entry.get("id").getAsString();
                String hash = entry.get("hash").getAsString();
                if (id.isBlank() || id.length() > 100 || hash.isBlank() || hash.length() > 100
                        || hash.contains("/") || hash.contains("\\")) {
                    throw new IOException("Invalid placement identity");
                }
                String name = entry.has("display_name") ? entry.get("display_name").getAsString() : id;
                String ownerName = "unknown";
                if (entry.has("owner") && entry.get("owner").isJsonObject()) {
                    var owner = entry.getAsJsonObject("owner");
                    if (owner.has("name")) ownerName = owner.get("name").getAsString();
                }
                Path file = findFile(hash);
                if (file == null) {
                    // A placement can be announced before the uploaded file is available.
                    file = schematicsRoot.resolve(hash + ".litematic");
                }
                var metadata = new SchematicMetadata(id, name, file, hash, new PlayerIdentity(null, ownerName));
                if (result.put(id, metadata) != null) throw new IOException("Duplicate placement id: " + id);
            }
            return result;
        } catch (RuntimeException error) {
            throw new IOException("Invalid placements document", error);
        }
    }

    private Path findFile(String hash) throws IOException {
        if (!Files.isDirectory(schematicsRoot)) return null;
        Path realRoot = schematicsRoot.toRealPath();
        try (var files = Files.list(schematicsRoot)) {
            List<Path> matches = files.filter(Files::isRegularFile)
                    .filter(path -> {
                        Path name = path.getFileName();
                        return name != null && name.toString().startsWith(hash)
                                && name.toString().endsWith(".litematic");
                    })
                    .sorted().toList();
            if (matches.isEmpty()) return null;
            Path exact = schematicsRoot.resolve(hash + ".litematic");
            Path chosen = matches.contains(exact) ? exact : matches.size() == 1 ? matches.get(0) : null;
            if (chosen == null) throw new IOException("Ambiguous schematic files for hash: " + hash);
            Path realFile = chosen.toRealPath();
            if (!realFile.startsWith(realRoot)) throw new IOException("Schematic file is outside its root");
            return realFile;
        }
    }
}
