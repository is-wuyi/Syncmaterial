package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record RescanStagingAreaResponseS2CPacket(boolean success, String message) implements CustomPayload {
    public static final CustomPayload.Id<RescanStagingAreaResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.RESCAN_STAGING_AREA_RESPONSE);
    public static final PacketCodec<RegistryByteBuf, RescanStagingAreaResponseS2CPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOLEAN, RescanStagingAreaResponseS2CPacket::success,
            PacketCodecs.STRING, RescanStagingAreaResponseS2CPacket::message,
            RescanStagingAreaResponseS2CPacket::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
