package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record RescanStagingAreaC2SPacket(String schematicId) implements CustomPayload {
    public static final CustomPayload.Id<RescanStagingAreaC2SPacket> ID = new CustomPayload.Id<>(ModPackets.RESCAN_STAGING_AREA);
    public static final PacketCodec<RegistryByteBuf, RescanStagingAreaC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, RescanStagingAreaC2SPacket::schematicId,
            RescanStagingAreaC2SPacket::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
