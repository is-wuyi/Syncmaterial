package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record RescanStagingAreaResponseS2CPacket(boolean success, String message) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RescanStagingAreaResponseS2CPacket> ID = new CustomPacketPayload.Type<>(ModPackets.RESCAN_STAGING_AREA_RESPONSE);
    public static final StreamCodec<RegistryFriendlyByteBuf, RescanStagingAreaResponseS2CPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, RescanStagingAreaResponseS2CPacket::success,
            ByteBufCodecs.STRING_UTF8, RescanStagingAreaResponseS2CPacket::message,
            RescanStagingAreaResponseS2CPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
