package net.syncmaterial.syncmaterial.network;

import io.netty.buffer.Unpooled;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaInventorySnapshotCodecTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roundtripKeepsAuthorityFieldsInOrder() {
        var original = new AreaInventorySnapshotS2CPacket("project", 9L, 4L, false,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(3, 12, 7),
                        new AreaInventorySnapshotS2CPacket.MaterialInventory(5, 0, 0)),
                List.of(new CollaborationStatusS2CPacket.AreaFreshnessInfo("warehouse", 2, "WH", "过期")));

        var decoded = roundtrip(original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(9L, decoded.catalogRevision());
        assertEquals(4L, decoded.sequence());
        assertFalse(decoded.known());
        assertEquals(2, decoded.materials().size());
        assertEquals(3, decoded.materials().get(0).materialId());
        assertEquals(12, decoded.materials().get(0).stagingCount());
        assertEquals(7, decoded.materials().get(0).warehouseCount());
        assertEquals(1, decoded.freshnessInfo().size());
        assertEquals("warehouse", decoded.freshnessInfo().get(0).areaType());
    }

    @Test
    void emptyCatalogueRoundtripExpressesKnown() {
        var decoded = roundtrip(new AreaInventorySnapshotS2CPacket("project", 1L, 1L, true,
                List.of(), List.of()));
        assertTrue(decoded.known());
        assertTrue(decoded.materials().isEmpty());
        assertTrue(decoded.freshnessInfo().isEmpty());
    }

    @Test
    void wireOrderIsFrozen() {
        var packet = new AreaInventorySnapshotS2CPacket("p", 2L, 3L, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(5, 7, 9)), List.of());
        assertBytes(packet, "01700203010105070900");
    }

    @Test
    void invalidEntriesAndInconsistentKnownAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AreaInventorySnapshotS2CPacket("", 1L, 1L,
                true, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AreaInventorySnapshotS2CPacket("p", -1L, 1L,
                true, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AreaInventorySnapshotS2CPacket("p", 1L, -1L,
                true, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AreaInventorySnapshotS2CPacket.MaterialInventory(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AreaInventorySnapshotS2CPacket.MaterialInventory(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AreaInventorySnapshotS2CPacket("p", 1L, 1L, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 1, 1),
                        new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 2, 2)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AreaInventorySnapshotS2CPacket("p", 1L, 1L, true,
                List.of(), List.of(new CollaborationStatusS2CPacket.AreaFreshnessInfo(
                        "staging_area", 1, "A", "未初始化"))));
    }

    private static AreaInventorySnapshotS2CPacket roundtrip(AreaInventorySnapshotS2CPacket original) {
        var buffer = buffer();
        try {
            AreaInventorySnapshotS2CPacket.CODEC.encode(buffer, original);
            AreaInventorySnapshotS2CPacket decoded = AreaInventorySnapshotS2CPacket.CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static void assertBytes(AreaInventorySnapshotS2CPacket value, String expectedHex) {
        var buffer = buffer();
        try {
            AreaInventorySnapshotS2CPacket.CODEC.encode(buffer, value);
            byte[] actual = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), actual);
            assertArrayEquals(HexFormat.of().parseHex(expectedHex), actual);
        } finally {
            buffer.release();
        }
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
