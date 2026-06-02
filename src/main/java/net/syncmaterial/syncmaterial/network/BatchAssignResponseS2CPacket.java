package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * 批量分配响应包 (Phase 4)
 */
public record BatchAssignResponseS2CPacket(
    boolean success,
    String message
) implements CustomPayload {

    public static final CustomPayload.Id<BatchAssignResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.BATCH_ASSIGN_RESPONSE);
    public static final PacketCodec<PacketByteBuf, BatchAssignResponseS2CPacket> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeBoolean(value.success());
                buf.writeString(value.message());
            },
            buf -> new BatchAssignResponseS2CPacket(buf.readBoolean(), buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
