package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.List;

/**
 * Phase 5: 取货模式容器数据响应（S→C）
 * 服务端返回该玩家引用仓库的全部容器数据
 */
public record WarehouseContainerResponseS2CPacket(
    List<ContainerEntry> containers
) implements CustomPayload {

    public static final CustomPayload.Id<WarehouseContainerResponseS2CPacket> ID =
            new CustomPayload.Id<>(ModPackets.WAREHOUSE_CONTAINER_RESPONSE);

    public static final PacketCodec<RegistryByteBuf, ContainerEntry> CONTAINER_ENTRY_CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER, ContainerEntry::posX,
            PacketCodecs.INTEGER, ContainerEntry::posY,
            PacketCodecs.INTEGER, ContainerEntry::posZ,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), ContainerEntry::itemIds,
            ContainerEntry::new
    );

    public static final PacketCodec<RegistryByteBuf, WarehouseContainerResponseS2CPacket> CODEC = PacketCodec.tuple(
            CONTAINER_ENTRY_CODEC.collect(PacketCodecs.toList()), WarehouseContainerResponseS2CPacket::containers,
            WarehouseContainerResponseS2CPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * 单个箱子的容器信息（坐标 + 物品种类列表，不含数量）
     */
    public record ContainerEntry(int posX, int posY, int posZ, List<String> itemIds) {}
}
