package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * 批量分配响应包 (Phase 4)
 */
public record BatchAssignResponseS2CPacket(
    boolean success,
    String message
) implements CustomPayload {

    public static final CustomPayload.Id<BatchAssignResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.BATCH_ASSIGN_RESPONSE);
    public static final PacketCodec<RegistryByteBuf, BatchAssignResponseS2CPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOLEAN, BatchAssignResponseS2CPacket::success,
            PacketCodecs.STRING, BatchAssignResponseS2CPacket::message,
            BatchAssignResponseS2CPacket::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
