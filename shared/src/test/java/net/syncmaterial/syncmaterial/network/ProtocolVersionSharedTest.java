package net.syncmaterial.syncmaterial.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure version arithmetic moved to the shared module; handshake state stays platform-side. */
class ProtocolVersionSharedTest {
    @Test
    void versionComparisonsRemainStable() {
        assertTrue(ProtocolVersion.atLeast(5, 3));
        assertTrue(ProtocolVersion.atLeast(3, 3));
        assertFalse(ProtocolVersion.atLeast(2, 3));
        assertFalse(ProtocolVersion.atLeast(0, 1));
    }

    @Test
    void compatibilityFloorNeverExceedsCurrent() {
        assertTrue(ProtocolVersion.MIN_COMPATIBLE <= ProtocolVersion.CURRENT);
        assertTrue(ProtocolVersion.isClientAcceptable(ProtocolVersion.MIN_COMPATIBLE));
        assertFalse(ProtocolVersion.isClientAcceptable(ProtocolVersion.MIN_COMPATIBLE - 1));
    }

    @Test
    void newerPeerIsReportedWithoutBlocking() {
        assertTrue(ProtocolVersion.isPeerNewer(ProtocolVersion.CURRENT + 1));
        assertFalse(ProtocolVersion.isPeerNewer(ProtocolVersion.CURRENT));
        assertEquals(2, ProtocolVersion.MATERIAL_CATALOG_UPDATES);
    }
}
