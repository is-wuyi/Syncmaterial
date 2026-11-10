package net.syncmaterial.syncmaterial.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validation and defensive-copy tests for the shared core records. */
class CoreRecordsTest {
    @Test
    void materialCount_validatesRegistryIdAndCount() {
        MaterialCount count = new MaterialCount("modid:path/to_item", 3);
        assertEquals(3, count.count());

        assertThrows(IllegalArgumentException.class, () -> new MaterialCount("", 1));
        assertThrows(IllegalArgumentException.class, () -> new MaterialCount("stone", 1));
        assertThrows(IllegalArgumentException.class, () -> new MaterialCount("modid:stone", -1));
    }

    @Test
    void playerIdentity_allowsNullUuidButNotBlankName() {
        UUID uuid = UUID.randomUUID();
        PlayerIdentity resolved = new PlayerIdentity(uuid, "Alice");
        PlayerIdentity unresolved = new PlayerIdentity(null, "Alice");

        assertTrue(resolved.hasUuid());
        assertFalse(unresolved.hasUuid());
        assertNull(unresolved.uuid());
        assertThrows(IllegalArgumentException.class, () -> new PlayerIdentity(uuid, " "));
    }

    @Test
    void schematicMetadata_normalizesNullNameAndRejectsMissingFields() {
        Path file = Path.of("/tmp/test.litematic");
        PlayerIdentity owner = new PlayerIdentity(null, "Owner");

        assertEquals("", new SchematicMetadata("p1", null, file, "hash", owner).name());
        assertThrows(IllegalArgumentException.class,
                () -> new SchematicMetadata(" ", "name", file, "hash", owner));
        assertThrows(IllegalArgumentException.class,
                () -> new SchematicMetadata("p1", "name", file, " ", owner));
        assertThrows(NullPointerException.class,
                () -> new SchematicMetadata("p1", "name", null, "hash", owner));
        assertThrows(NullPointerException.class,
                () -> new SchematicMetadata("p1", "name", file, "hash", null));
    }

    @Test
    void materialAndClaimSnapshots_validateIds() {
        MaterialSnapshot snapshot = new MaterialSnapshot(1, "minecraft:stone", 4);
        assertEquals(1, snapshot.id());
        assertEquals(4, snapshot.required());
        assertThrows(IllegalArgumentException.class,
                () -> new MaterialSnapshot(0, "minecraft:stone", 4));
        assertThrows(IllegalArgumentException.class,
                () -> new MaterialSnapshot(1, "minecraft:stone", -1));

        PlayerIdentity player = new PlayerIdentity(null, "Alice");
        assertEquals(player, new ClaimSnapshot(7, player).player());
        assertThrows(IllegalArgumentException.class, () -> new ClaimSnapshot(0, player));
        assertThrows(NullPointerException.class, () -> new ClaimSnapshot(1, null));
    }

    @Test
    void projectSnapshot_copiesListsDefensively() {
        List<String> deputies = new ArrayList<>(List.of("Alice"));
        List<MaterialSnapshot> materials = new ArrayList<>(
                List.of(new MaterialSnapshot(1, "minecraft:stone", 10)));
        PlayerIdentity owner = new PlayerIdentity(null, "Owner");

        ProjectSnapshot snapshot = new ProjectSnapshot(
                "p1", "Project", "hash", owner, true, deputies, materials, 2);

        deputies.add("Bob");
        materials.clear();

        assertEquals(List.of("Alice"), snapshot.deputies());
        assertEquals(1, snapshot.materials().size());
        assertEquals("Owner", snapshot.ownerName());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.deputies().add("Carol"));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectSnapshot("p1", "Project", "hash", owner, true,
                        List.of(), List.of(), -1));
    }

    @Test
    void projectSnapshot_interpretationDefaultsToFixtureMarkerAndValidatesOverride() {
        PlayerIdentity owner = new PlayerIdentity(null, "Owner");
        ProjectSnapshot legacy = new ProjectSnapshot(
                "p1", "Project", "hash", owner, true, List.of(), List.of(), 0);
        assertEquals("unversioned", legacy.interpretationId());

        ProjectSnapshot versioned = new ProjectSnapshot(
                "p1", "Project", "hash", owner, true, List.of(), List.of(), 0,
                "mc:4325/decoder:1/materials:1");
        assertEquals("mc:4325/decoder:1/materials:1", versioned.interpretationId());

        assertThrows(IllegalArgumentException.class,
                () -> new ProjectSnapshot("p1", "Project", "hash", owner, true,
                        List.of(), List.of(), 0, " "));
    }

    @Test
    void projectState_copiesClaimsAndRejectsNulls() {
        ProjectSnapshot project = new ProjectSnapshot(
                "p1", "Project", "hash", new PlayerIdentity(null, "Owner"),
                true, List.of(), List.of(), 0);
        List<ClaimSnapshot> claims = new ArrayList<>(
                List.of(new ClaimSnapshot(1, new PlayerIdentity(null, "Alice"))));

        ProjectState state = new ProjectState(project, claims);
        claims.clear();

        assertEquals(1, state.claims().size());
        assertThrows(UnsupportedOperationException.class, () -> state.claims().clear());
        assertThrows(NullPointerException.class, () -> new ProjectState(null, List.of()));
        assertThrows(NullPointerException.class, () -> new ProjectState(project, null));
    }

    @Test
    void areaFreshnessInfo_marksUnknownWithWarning() {
        AreaFreshnessInfo info = new AreaFreshnessInfo(
                AreaKind.STAGING_AREA, 0, "备货区", "尚未接入区域扫描，库存未知");

        assertEquals("staging_area", info.kind().wireName());
        assertTrue(info.hasWarning());
        assertFalse(new AreaFreshnessInfo(AreaKind.WAREHOUSE, 5, "仓库", "").hasWarning());
        assertEquals(AreaKind.WAREHOUSE, AreaKind.fromWireName("warehouse"));
        assertThrows(IllegalArgumentException.class, () -> AreaKind.fromWireName("unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaFreshnessInfo(AreaKind.WAREHOUSE, -1, "仓库", ""));
        assertThrows(NullPointerException.class,
                () -> new AreaFreshnessInfo(null, 0, "仓库", ""));
    }
}
