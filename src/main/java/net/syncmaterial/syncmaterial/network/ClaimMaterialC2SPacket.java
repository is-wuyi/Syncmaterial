package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record ClaimMaterialC2SPacket(String schematicId, int materialId, int count) implements CustomPayload {

    public static final CustomPayload.Id<ClaimMaterialC2SPacket> ID = new CustomPayload.Id<>(ModPackets.CLAIM_MATERIAL);

    public static final PacketCodec<RegistryByteBuf, ClaimMaterialC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, ClaimMaterialC2SPacket::schematicId,
            PacketCodecs.INTEGER, ClaimMaterialC2SPacket::materialId,
            PacketCodecs.INTEGER, ClaimMaterialC2SPacket::count,
            ClaimMaterialC2SPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
