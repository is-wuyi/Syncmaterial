package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 按材料踢出响应包 (Phase 4)
 */
public record KickFromMaterialResponseS2CPacket(
    boolean success,
    String message
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<KickFromMaterialResponseS2CPacket> ID = new CustomPacketPayload.Type<>(ModPackets.KICK_FROM_MATERIAL_RESPONSE);
    public static final StreamCodec<RegistryFriendlyByteBuf, KickFromMaterialResponseS2CPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, KickFromMaterialResponseS2CPacket::success,
            ByteBufCodecs.STRING_UTF8, KickFromMaterialResponseS2CPacket::message,
            KickFromMaterialResponseS2CPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
