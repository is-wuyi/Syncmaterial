package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

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
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
