package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * 按材料踢出响应包 (Phase 4)
 */
public record KickFromMaterialResponseS2CPacket(
    boolean success,
    String message
) implements CustomPayload {

    public static final CustomPayload.Id<KickFromMaterialResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.KICK_FROM_MATERIAL_RESPONSE);
    public static final PacketCodec<RegistryByteBuf, KickFromMaterialResponseS2CPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOLEAN, KickFromMaterialResponseS2CPacket::success,
            PacketCodecs.STRING, KickFromMaterialResponseS2CPacket::message,
            KickFromMaterialResponseS2CPacket::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
