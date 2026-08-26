//? if >=26 {
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
    public CustomPacketPayload.Type<? extends CustomPacketPayload> getId() {
        return ID;
    }
}
//?} else {
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
//?}
