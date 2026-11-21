package net.syncmaterial.syncmaterial.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import net.syncmaterial.syncmaterial.api.ProgressFormulas;

/**
 * Immutable, Minecraft-free project progress snapshot: catalog facts, area facts,
 * claim participants, and the effective online held counts sampled at one point.
 *
 * <p>Revisions are independent: {@code catalogRevision} is the persisted project
 * revision (the network catalog token is adapted by the caller),
 * {@code inventoryRevision} is the area projection revision, and
 * {@code publicationSequence} is assigned by the runtime publisher. No reservation
 * or personal quota is part of this model.</p>
 *
 * <p>{@link #viewFor(PlayerIdentity)} resolves one viewer without mutating the
 * snapshot. Matching prefers the viewer UUID; a legacy name-only claim can only be
 * adopted when no other resolved participant holds that name. A name-only viewer
 * never inherits a resolved UUID claim.</p>
 */
public record ProjectProgressSnapshot(
        String projectId,
        String projectName,
        long catalogRevision,
        long inventoryRevision,
        long publicationSequence,
        boolean areaCurrent,
        boolean areaKnown,
        boolean areaOverlap,
        List<AreaInventoryWarning> areaWarnings,
        List<ProjectProgressMaterial> materials
) {
    public ProjectProgressSnapshot {
        CoreChecks.requireNonBlank(projectId, "projectId");
        projectName = projectName == null ? "" : projectName;
        if (catalogRevision < 0) {
            throw new IllegalArgumentException("catalogRevision must be >= 0");
        }
        if (inventoryRevision < 0) {
            throw new IllegalArgumentException("inventoryRevision must be >= 0");
        }
        if (publicationSequence < 0) {
            throw new IllegalArgumentException("publicationSequence must be >= 0");
        }
        areaWarnings = List.copyOf(Objects.requireNonNull(areaWarnings, "areaWarnings"));
        if (areaKnown && !areaWarnings.isEmpty()) {
            throw new IllegalArgumentException("known area inventory cannot carry warnings");
        }
        Objects.requireNonNull(materials, "materials");
        Map<Integer, ProjectProgressMaterial> byId = new TreeMap<>();
        for (ProjectProgressMaterial material : materials) {
            Objects.requireNonNull(material, "material");
            ProjectProgressMaterial previous = byId.putIfAbsent(material.materialId(), material);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate materialId " + material.materialId());
            }
        }
        materials = List.copyOf(byId.values());
    }

    /**
     * True only when the area view is current and known (no freshness warning).
     * Overlap is independent: physical containers have already been deduplicated,
     * so current known counts remain authoritative while the overlap notice stays visible.
     * Last-known counts are still carried, but completion is not confirmed when stale or unknown.
     */
    public boolean preparationKnown() {
        return areaCurrent && areaKnown;
    }

    public Optional<ProjectProgressMaterial> material(int materialId) {
        for (ProjectProgressMaterial material : materials) {
            if (material.materialId() == materialId) {
                return Optional.of(material);
            }
        }
        return Optional.empty();
    }

    /** Builds the immutable per-material projection for one viewer. */
    public ProjectProgressView viewFor(PlayerIdentity viewer) {
        Objects.requireNonNull(viewer, "viewer");
        boolean preparationKnown = preparationKnown();
        List<ProjectProgressMaterialView> rows = new ArrayList<>(materials.size());
        for (ProjectProgressMaterial material : materials) {
            boolean sameNameResolvedOther = hasOtherResolvedSameName(material.participants(), viewer);
            long myObserved = 0L;
            long otherObserved = 0L;
            long teamObserved = 0L;
            boolean observedSaturated = false;
            boolean claimed = false;
            for (ProgressParticipant participant : material.participants()) {
                long held = participant.effectiveHeld();
                observedSaturated |= additionSaturates(teamObserved, held);
                teamObserved = ProgressFormulas.saturatingAdd(teamObserved, held);
                if (matchesViewer(viewer, participant.player(), sameNameResolvedOther)) {
                    claimed = true;
                    observedSaturated |= additionSaturates(myObserved, held);
                    myObserved = ProgressFormulas.saturatingAdd(myObserved, held);
                } else {
                    observedSaturated |= additionSaturates(otherObserved, held);
                    otherObserved = ProgressFormulas.saturatingAdd(otherObserved, held);
                }
            }
            long teamMissing = ProgressFormulas.collectedMissing(
                    material.required(), material.staging(), material.warehouse(), teamObserved);
            long pickupMissing = ProgressFormulas.pickupMissing(
                    material.required(), material.staging(), myObserved, material.warehouse());
            boolean stagingReady = preparationKnown
                    && material.required() > 0
                    && material.staging() >= material.required();
            boolean teamCollected = preparationKnown && teamMissing == 0;
            rows.add(new ProjectProgressMaterialView(
                    material.materialId(), material.itemId(), material.required(),
                    material.staging(), material.warehouse(), claimed,
                    myObserved, otherObserved, teamMissing, pickupMissing,
                    stagingReady, teamCollected, preparationKnown, observedSaturated));
        }
        return new ProjectProgressView(
                projectId, projectName, catalogRevision, inventoryRevision, publicationSequence,
                areaCurrent, areaKnown, areaOverlap, areaWarnings, preparationKnown, rows);
    }

    /** Non-negative inputs only; true when the mathematical sum exceeds {@link Long#MAX_VALUE}. */
    private static boolean additionSaturates(long left, long right) {
        return left > Long.MAX_VALUE - right;
    }

    private static boolean hasOtherResolvedSameName(List<ProgressParticipant> participants, PlayerIdentity viewer) {
        for (ProgressParticipant participant : participants) {
            PlayerIdentity identity = participant.player();
            if (identity.hasUuid()
                    && !identity.uuid().equals(viewer.uuid())
                    && identity.name().equalsIgnoreCase(viewer.name())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesViewer(PlayerIdentity viewer, PlayerIdentity participant,
                                         boolean sameNameResolvedOther) {
        if (viewer.hasUuid()) {
            if (participant.hasUuid()) {
                return viewer.uuid().equals(participant.uuid());
            }
            // A legacy name-only row may stand in for the UUID viewer only while no
            // different resolved identity owns that name; otherwise the association
            // is ambiguous and must not be guessed.
            return !sameNameResolvedOther && participant.name().equalsIgnoreCase(viewer.name());
        }
        if (participant.hasUuid()) {
            return false;
        }
        return participant.name().equalsIgnoreCase(viewer.name());
    }
}
