package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record QueryMaterialStatusC2SPacket(String schematicId) implements CustomPayload {

    public static final CustomPayload.Id<QueryMaterialStatusC2SPacket> ID = new CustomPayload.Id<>(ModPackets.QUERY_MATERIAL_STATUS);

    public static final PacketCodec<RegistryByteBuf, QueryMaterialStatusC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, QueryMaterialStatusC2SPacket::schematicId,
            QueryMaterialStatusC2SPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
