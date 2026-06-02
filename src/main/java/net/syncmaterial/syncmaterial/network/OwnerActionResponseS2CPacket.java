package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * 负责人操作响应包 (Phase 4)
 */
public record OwnerActionResponseS2CPacket(
    boolean success,
    String message
) implements CustomPayload {

    public static final CustomPayload.Id<OwnerActionResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.OWNER_ACTION_RESPONSE);
    public static final PacketCodec<PacketByteBuf, OwnerActionResponseS2CPacket> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeBoolean(value.success());
                buf.writeString(value.message());
            },
            buf -> new OwnerActionResponseS2CPacket(buf.readBoolean(), buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
