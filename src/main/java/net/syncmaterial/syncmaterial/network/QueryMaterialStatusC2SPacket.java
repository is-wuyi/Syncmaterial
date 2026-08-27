package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record QueryMaterialStatusC2SPacket(String schematicId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<QueryMaterialStatusC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.QUERY_MATERIAL_STATUS);

    public static final StreamCodec<RegistryFriendlyByteBuf, QueryMaterialStatusC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, QueryMaterialStatusC2SPacket::schematicId,
            QueryMaterialStatusC2SPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
