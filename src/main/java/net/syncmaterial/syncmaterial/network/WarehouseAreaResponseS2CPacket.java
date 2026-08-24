package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.List;

/**
 * Phase 5: 仓库区域线框数据（S→C）
 *
 * 与 StagingAreaConfigResponseS2CPacket 分开的原因：
 * 仓库是全局资源，不隶属于任何原理图，其 schematicId 为空串。
 * 若复用备货区响应包，客户端的世界侧处理会把仓库当成 key 为 "" 的备货区写进 selections，
 * 导致仓库被按备货区颜色渲染并污染备货区渲染数据。
 *
 * referencedIds 是"当前原理图引用了哪些仓库"，客户端用它决定高亮色；
 * 仓库自带 world 字段，客户端逐个做跨维度过滤。
 */
public record WarehouseAreaResponseS2CPacket(
    List<StagingAreaConfigResponseS2CPacket.AreaInfo> warehouses,
    List<Integer> referencedIds
) implements CustomPayload {

    public static final CustomPayload.Id<WarehouseAreaResponseS2CPacket> ID =
            new CustomPayload.Id<>(ModPackets.WAREHOUSE_AREA_RESPONSE);

    public static final PacketCodec<RegistryByteBuf, WarehouseAreaResponseS2CPacket> CODEC = PacketCodec.tuple(
            StagingAreaConfigResponseS2CPacket.AREA_INFO_CODEC.collect(PacketCodecs.toList()),
            WarehouseAreaResponseS2CPacket::warehouses,
            PacketCodecs.INTEGER.collect(PacketCodecs.toList()),
            WarehouseAreaResponseS2CPacket::referencedIds,
            WarehouseAreaResponseS2CPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
