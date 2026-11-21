package net.syncmaterial.syncmaterial.server.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProgressParticipant;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterial;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectState;

/**
 * Builds a {@link ProjectProgressSnapshot} from one {@link ProjectState} and one
 * online inventory sample. It has no storage or Minecraft dependency and never
 * queries rows per material: claims are indexed once by material and identity.
 *
 * <p>The service always takes the sample itself in {@link #build(ProjectState, long,
 * boolean)}. {@link #build(ProjectState, OnlineInventorySnapshot, long, boolean)}
 * accepts an already-taken sample for callers that need to verify connection
 * generations before publication.</p>
 */
public final class ProjectProgressService {

    private final OnlineInventoryTracker inventories;

    public ProjectProgressService(OnlineInventoryTracker inventories) {
        this.inventories = Objects.requireNonNull(inventories, "inventories");
    }

    public ProjectProgressSnapshot build(ProjectState state, long publicationSequence, boolean areaCurrent) {
        Objects.requireNonNull(state, "state");
        if (publicationSequence < 0) {
            throw new IllegalArgumentException("publicationSequence must be >= 0");
        }
        OnlineInventorySnapshot sample = inventories.snapshot(state.project().id(), state.claims());
        return build(state, sample, publicationSequence, areaCurrent);
    }

    public ProjectProgressSnapshot build(ProjectState state, OnlineInventorySnapshot sample,
                                         long publicationSequence, boolean areaCurrent) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sample, "sample");
        if (publicationSequence < 0) {
            throw new IllegalArgumentException("publicationSequence must be >= 0");
        }
        ProjectSnapshot project = state.project();
        if (!sample.projectId().equals(project.id())) {
            throw new IllegalArgumentException(
                    "inventory sample belongs to " + sample.projectId() + ", not " + project.id());
        }

        AreaInventoryView inventory = state.inventory();
        Map<Integer, Map<ParticipantKey, ProgressParticipant>> byMaterial = indexClaims(state.claims(), sample);
        List<ProjectProgressMaterial> materials = new ArrayList<>(project.materials().size());
        for (MaterialSnapshot material : project.materials()) {
            Map<ParticipantKey, ProgressParticipant> participants = byMaterial.get(material.id());
            materials.add(new ProjectProgressMaterial(
                    material.id(),
                    material.itemId(),
                    material.required(),
                    inventory.staging().getOrDefault(material.itemId(), 0L),
                    inventory.warehouse().getOrDefault(material.itemId(), 0L),
                    participants == null ? List.of() : List.copyOf(participants.values())));
        }
        return new ProjectProgressSnapshot(
                project.id(),
                project.name(),
                project.revision(),
                inventory.revision(),
                publicationSequence,
                areaCurrent,
                inventory.known(),
                inventory.overlap(),
                inventory.freshness(),
                materials);
    }

    private static Map<Integer, Map<ParticipantKey, ProgressParticipant>> indexClaims(
            List<ClaimSnapshot> claims, OnlineInventorySnapshot sample) {
        Map<Integer, Map<ParticipantKey, ProgressParticipant>> byMaterial = new TreeMap<>();
        for (ClaimSnapshot claim : claims) {
            PlayerIdentity identity = claim.player();
            ParticipantKey key = ParticipantKey.of(identity);
            long held = identity.hasUuid() ? sample.count(claim.materialId(), identity.uuid()) : 0L;
            ProgressParticipant candidate = new ProgressParticipant(identity, held);
            byMaterial.computeIfAbsent(claim.materialId(), ignored -> new LinkedHashMap<>())
                    .merge(key, candidate, ProjectProgressService::preferredIdentity);
        }
        return byMaterial;
    }

    /**
     * Two claims can share a UUID (for example after a rename) or a legacy name
     * differing only by case. The held count is per UUID/material and must not be
     * added twice, so keep one deterministic display identity.
     */
    private static ProgressParticipant preferredIdentity(ProgressParticipant left, ProgressParticipant right) {
        int ignoreCase = left.player().name().compareToIgnoreCase(right.player().name());
        if (ignoreCase != 0) {
            return ignoreCase < 0 ? left : right;
        }
        return left.player().name().compareTo(right.player().name()) <= 0 ? left : right;
    }

    private record ParticipantKey(UUID uuid, String legacyName) {
        static ParticipantKey of(PlayerIdentity identity) {
            return identity.hasUuid()
                    ? new ParticipantKey(identity.uuid(), "")
                    : new ParticipantKey(null, identity.name().toLowerCase(Locale.ROOT));
        }
    }
}
