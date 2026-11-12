package net.syncmaterial.syncmaterial.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable configuration snapshot for the scanner and the client area manager.
 * {@code warehouseReferences} contains only projects that reference at least one warehouse.
 * All collections are defensive copies and every helper returns immutable values.
 */
public record AreaCatalog(
        List<AreaDefinition> areas,
        Map<String, Set<Integer>> warehouseReferences,
        Map<String, String> projectNames,
        long revision
) {
    public AreaCatalog {
        List<AreaDefinition> areaCopy = List.copyOf(Objects.requireNonNull(areas, "areas"));
        Set<Integer> ids = new LinkedHashSet<>();
        for (AreaDefinition area : areaCopy) {
            Objects.requireNonNull(area, "area");
            if (!ids.add(area.id())) {
                throw new IllegalArgumentException("duplicate area id: " + area.id());
            }
        }
        areas = areaCopy;

        Map<String, Set<Integer>> referenceCopy = new TreeMap<>();
        for (Map.Entry<String, Set<Integer>> entry : Objects.requireNonNull(
                warehouseReferences, "warehouseReferences").entrySet()) {
            String projectId = CoreChecks.requireNonBlank(entry.getKey(), "projectId");
            Set<Integer> referenced = new TreeSet<>();
            for (Integer areaId : Objects.requireNonNull(entry.getValue(), "warehouse references")) {
                if (areaId == null || areaId <= 0) {
                    throw new IllegalArgumentException("warehouse reference id must be > 0");
                }
                referenced.add(areaId);
            }
            if (!referenced.isEmpty()) {
                referenceCopy.put(projectId, Collections.unmodifiableSet(referenced));
            }
        }
        warehouseReferences = Collections.unmodifiableMap(referenceCopy);

        Map<String, String> nameCopy = new TreeMap<>();
        for (Map.Entry<String, String> entry : Objects.requireNonNull(projectNames, "projectNames").entrySet()) {
            String projectId = CoreChecks.requireNonBlank(entry.getKey(), "projectId");
            nameCopy.put(projectId, Objects.requireNonNull(entry.getValue(), "project name for " + projectId));
        }
        projectNames = Collections.unmodifiableMap(nameCopy);

        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
    }

    public Optional<AreaDefinition> findArea(int areaId) {
        for (AreaDefinition area : areas) {
            if (area.id() == areaId) {
                return Optional.of(area);
            }
        }
        return Optional.empty();
    }

    public List<AreaDefinition> stagingAreas(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return areas.stream()
                .filter(area -> area.kind() == AreaKind.STAGING_AREA && projectId.equals(area.projectId()))
                .toList();
    }

    public Set<Integer> referencedWarehouseIds(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return warehouseReferences.getOrDefault(projectId, Set.of());
    }

    /** Referenced warehouse definitions of one project, in catalog/id order. */
    public List<AreaDefinition> referencedWarehouses(String projectId) {
        Set<Integer> referenced = referencedWarehouseIds(projectId);
        if (referenced.isEmpty()) {
            return List.of();
        }
        List<AreaDefinition> result = new ArrayList<>(referenced.size());
        for (AreaDefinition area : areas) {
            if (area.kind() == AreaKind.WAREHOUSE && referenced.contains(area.id())) {
                result.add(area);
            }
        }
        return List.copyOf(result);
    }

    public List<AreaDefinition> areasCovering(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return areas.stream()
                .filter(area -> area.world().equals(chunk.world())
                        && area.bounds().containsChunk(chunk.x(), chunk.z()))
                .toList();
    }

    public List<AreaDefinition> areasCovering(ContainerKey container) {
        Objects.requireNonNull(container, "container");
        return areas.stream()
                .filter(area -> area.world().equals(container.world())
                        && area.bounds().contains(container.x(), container.y(), container.z()))
                .toList();
    }
}
