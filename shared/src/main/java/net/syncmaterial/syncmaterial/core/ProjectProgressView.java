package net.syncmaterial.syncmaterial.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable per-viewer projection of one {@link ProjectProgressSnapshot}. It contains
 * no raw participant list and no mutable GUI state; the viewer-specific fields are
 * already resolved by {@link ProjectProgressSnapshot#viewFor(PlayerIdentity)}.
 *
 * <p>{@code preparationKnown} is project-wide: it requires a current area snapshot
 * with no freshness warning. Deduplicated overlap remains a separate notice and
 * does not invalidate the counts. The flag is copied to every material row for
 * self-contained rendering. When false, last-known counts may still appear in
 * {@code teamMissing}/{@code staging} but no row can claim completion.</p>
 */
public record ProjectProgressView(
        String projectId,
        String projectName,
        long catalogRevision,
        long inventoryRevision,
        long publicationSequence,
        boolean areaCurrent,
        boolean areaKnown,
        boolean areaOverlap,
        List<AreaInventoryWarning> areaWarnings,
        boolean preparationKnown,
        List<ProjectProgressMaterialView> materials
) {
    public ProjectProgressView {
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
        if (preparationKnown && (!areaCurrent || !areaKnown)) {
            throw new IllegalArgumentException(
                    "preparationKnown requires a current, known area view");
        }
        Objects.requireNonNull(materials, "materials");
        Map<Integer, ProjectProgressMaterialView> byId = new TreeMap<>();
        for (ProjectProgressMaterialView material : materials) {
            Objects.requireNonNull(material, "material");
            ProjectProgressMaterialView previous = byId.putIfAbsent(material.materialId(), material);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate materialId " + material.materialId());
            }
        }
        materials = List.copyOf(byId.values());
    }

    public Optional<ProjectProgressMaterialView> material(int materialId) {
        for (ProjectProgressMaterialView material : materials) {
            if (material.materialId() == materialId) {
                return Optional.of(material);
            }
        }
        return Optional.empty();
    }
}
