package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record RescanStagingAreaC2SPacket(String schematicId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RescanStagingAreaC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.RESCAN_STAGING_AREA);
    public static final StreamCodec<RegistryFriendlyByteBuf, RescanStagingAreaC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RescanStagingAreaC2SPacket::schematicId,
            RescanStagingAreaC2SPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
