//? if >=26 {
package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Identifier;

/**
 * 客户端请求服务端进行材料统计的数据包
 */
public record MaterialStatsRequestC2SPacket(String schematicId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MaterialStatsRequestC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.REQUEST_MATERIAL_LIST);

    public static final StreamCodec<RegistryFriendlyByteBuf, MaterialStatsRequestC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MaterialStatsRequestC2SPacket::schematicId,
            MaterialStatsRequestC2SPacket::new
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
import net.minecraft.util.Identifier;

/**
 * 客户端请求服务端进行材料统计的数据包
 */
public record MaterialStatsRequestC2SPacket(String schematicId) implements CustomPayload {

    public static final CustomPayload.Id<MaterialStatsRequestC2SPacket> ID = new CustomPayload.Id<>(ModPackets.REQUEST_MATERIAL_LIST);

    public static final PacketCodec<RegistryByteBuf, MaterialStatsRequestC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, MaterialStatsRequestC2SPacket::schematicId,
            MaterialStatsRequestC2SPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
//?}
