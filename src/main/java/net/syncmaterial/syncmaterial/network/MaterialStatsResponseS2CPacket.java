package net.syncmaterial.syncmaterial.network;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 服务端响应材料统计请求的数据包
 */
public record MaterialStatsResponseS2CPacket(String schematicName, List<MaterialEntry> materials) implements CustomPayload {

    public static final CustomPayload.Id<MaterialStatsResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.MATERIAL_LIST_RESPONSE);

    public static final PacketCodec<RegistryByteBuf, MaterialStatsResponseS2CPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, MaterialStatsResponseS2CPacket::schematicName,
            MaterialEntry.PACKET_CODEC.collect(PacketCodecs.toList()), MaterialStatsResponseS2CPacket::materials,
            MaterialStatsResponseS2CPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}