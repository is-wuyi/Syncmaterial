package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * 按材料踢出响应包 (Phase 4)
 */
public record KickFromMaterialResponseS2CPacket(
    boolean success,
    String message
) implements CustomPayload {

    public static final CustomPayload.Id<KickFromMaterialResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.KICK_FROM_MATERIAL_RESPONSE);
    public static final PacketCodec<PacketByteBuf, KickFromMaterialResponseS2CPacket> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeBoolean(value.success());
                buf.writeString(value.message());
            },
            buf -> new KickFromMaterialResponseS2CPacket(buf.readBoolean(), buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
