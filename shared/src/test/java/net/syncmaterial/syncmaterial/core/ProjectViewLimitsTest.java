package net.syncmaterial.syncmaterial.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectViewLimitsTest {

    @Test
    void fixedLimitsMatchTheProtocolContract() {
        assertEquals(100, ProjectViewLimits.PROJECT_ID_LENGTH);
        assertEquals(256, ProjectViewLimits.PROJECT_NAME_LENGTH);
        assertEquals(64, ProjectViewLimits.INVENTORY_OBSERVATIONS);
        assertEquals(900_000, ProjectViewLimits.PART_BYTES);
        assertEquals(16 * 1024 * 1024, ProjectViewLimits.TOTAL_BYTES);
        assertEquals(19, ProjectViewLimits.MAX_PART_COUNT);
        assertEquals(4, ProjectViewLimits.MAX_CONCURRENT_ASSEMBLIES);
        assertEquals(8, ProjectViewLimits.MAX_ACTIVE_CONNECTIONS);
        assertEquals(64, ProjectViewLimits.MAX_ACTIVE_VIEWS);
        assertEquals(36, ProjectViewLimits.SNAPSHOT_ID_LENGTH);
        assertTrue(ProjectViewLimits.S2C_MAX_BYTES <= 1_048_576);
        assertTrue(ProjectViewLimits.C2S_MAX_BYTES < ProjectViewLimits.S2C_MAX_BYTES);
    }

    @Test
    void partCountIsBoundedByTheCeilingFormula() {
        assertEquals(0, ProjectViewLimits.maxPartCount(0));
        assertEquals(0, ProjectViewLimits.maxPartCount(-1));
        assertEquals(0, ProjectViewLimits.maxPartCount(ProjectViewLimits.TOTAL_BYTES + 1));
        assertEquals(1, ProjectViewLimits.maxPartCount(1));
        assertEquals(1, ProjectViewLimits.maxPartCount(4));
        assertEquals(1, ProjectViewLimits.maxPartCount(ProjectViewLimits.PART_BYTES));
        assertEquals(2, ProjectViewLimits.maxPartCount(ProjectViewLimits.PART_BYTES + 1));
        assertEquals(19, ProjectViewLimits.maxPartCount(ProjectViewLimits.TOTAL_BYTES));
    }

    @Test
    void interestMaskRejectsUnknownBits() {
        assertTrue(ProjectViewLimits.isKnownInterestMask(0));
        assertEquals(31, ProjectViewLimits.INTEREST_ALL);
        assertTrue(ProjectViewLimits.isKnownInterestMask(ProjectViewLimits.INTEREST_ALL));
        assertTrue(ProjectViewLimits.isKnownInterestMask(ProjectViewLimits.INTEREST_MATERIALS
                | ProjectViewLimits.INTEREST_WAREHOUSE_CONTENTS));
        assertFalse(ProjectViewLimits.isKnownInterestMask(ProjectViewLimits.INTEREST_ALL + 1));
        assertFalse(ProjectViewLimits.isKnownInterestMask(-1));
    }

    @Test
    void positiveIdsAndUtfBoundAreExplicit() {
        assertTrue(ProjectViewLimits.isPositiveId(1));
        assertFalse(ProjectViewLimits.isPositiveId(0));
        assertFalse(ProjectViewLimits.isPositiveId(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectViewLimits.requirePositiveId(0, "requestId"));
        assertEquals(0, ProjectViewLimits.utfBytesUpperBound(null));
        assertEquals(9, ProjectViewLimits.utfBytesUpperBound("abc"));
    }

    @Test
    void snapshotIdMustBeCanonicalUuid() {
        ProjectViewLimits.requireUuidString("12345678-1234-1234-1234-123456789abc", "snapshotId");
        assertThrows(IllegalArgumentException.class,
                () -> ProjectViewLimits.requireUuidString("snapshot-1", "snapshotId"));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectViewLimits.requireUuidString(
                        "12345678-1234-1234-1234-123456789ABC", "snapshotId"));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectViewLimits.requireUuidString(null, "snapshotId"));
    }

    @Test
    void typedScopeSeparatesGlobalFromProject() {
        ProjectViewScope project = ProjectViewScope.project("p");
        ProjectViewScope global = ProjectViewScope.globalWarehouse();
        assertTrue(project.isProject());
        assertFalse(global.isProject());
        assertEquals("p", project.projectId());
        assertThrows(IllegalStateException.class, global::projectId);
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectViewScope(" ", true));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectViewScope("x".repeat(ProjectViewLimits.SCOPE_LENGTH + 1), false));
    }
}
