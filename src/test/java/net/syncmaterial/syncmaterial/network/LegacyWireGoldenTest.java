package net.syncmaterial.syncmaterial.network;

import io.netty.buffer.Unpooled;
import java.util.HexFormat;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyWireGoldenTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void helloPacketsKeepThePublishedByteOrder() {
        assertBytes(HelloC2SPacket.CODEC, new HelloC2SPacket(1, "test"), "010474657374");
        assertBytes(HelloS2CPacket.CODEC, new HelloS2CPacket(1, "test", true), "01047465737401");
        assertBytes(HelloS2CPacket.CODEC, new HelloS2CPacket(1, "test", false), "01047465737400");
    }

    @Test
    void materialAndInventoryRequestsKeepThePublishedByteOrder() {
        assertBytes(MaterialStatsRequestC2SPacket.CODEC, new MaterialStatsRequestC2SPacket("p"), "0170");
        assertBytes(QueryMaterialStatusC2SPacket.CODEC, new QueryMaterialStatusC2SPacket("p"), "0170");
        assertBytes(MaterialListCloseC2SPacket.CODEC, new MaterialListCloseC2SPacket("p"), "0170");
        assertBytes(LeaveCollaborationC2SPacket.CODEC, new LeaveCollaborationC2SPacket("p", 3), "017003");
        assertBytes(InventoryUpdateC2SPacket.CODEC, new InventoryUpdateC2SPacket("p", 3, 64), "01700340");
    }

    @Test
    void phaseFourPacketsKeepThePublishedByteOrder() {
        assertBytes(OwnerActionC2SPacket.CODEC, new OwnerActionC2SPacket("p", "TRANSFER", "A"),
                "0170085452414e534645520141");
        assertBytes(OwnerActionResponseS2CPacket.CODEC,
                new OwnerActionResponseS2CPacket(true, "m", "A", java.util.List.of("B"), false),
                "01016d014101014200");
        assertBytes(BatchAssignC2SPacket.CODEC, new BatchAssignC2SPacket("p", java.util.List.of(3), java.util.List.of("A")),
                "01700103010141");
        assertBytes(BatchAssignResponseS2CPacket.CODEC, new BatchAssignResponseS2CPacket(true, "m"), "01016d");
        assertBytes(KickFromMaterialC2SPacket.CODEC, new KickFromMaterialC2SPacket("p", java.util.List.of(3), "A"),
                "017001030141");
        assertBytes(KickFromMaterialResponseS2CPacket.CODEC, new KickFromMaterialResponseS2CPacket(false, "m"), "00016d");
        assertBytes(PlayerListRequestC2SPacket.CODEC, new PlayerListRequestC2SPacket("p"), "0170");
        assertBytes(PlayerListResponseS2CPacket.CODEC, new PlayerListResponseS2CPacket(java.util.List.of(
                new PlayerListResponseS2CPacket.PlayerInfo("A", true))), "01014101");
    }

    private static <T> void assertBytes(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value, String expectedHex) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            codec.encode(buffer, value);
            byte[] actual = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), actual);
            assertArrayEquals(HexFormat.of().parseHex(expectedHex), actual);
            assertEquals(value, codec.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}
