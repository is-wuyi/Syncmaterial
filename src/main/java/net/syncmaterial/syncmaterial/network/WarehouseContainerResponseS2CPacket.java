package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Phase 5: 取货模式容器数据响应（S→C）
 * 服务端返回该玩家引用仓库的全部容器数据
 */
public record WarehouseContainerResponseS2CPacket(
    List<ContainerEntry> containers
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WarehouseContainerResponseS2CPacket> ID =
            new CustomPacketPayload.Type<>(ModPackets.WAREHOUSE_CONTAINER_RESPONSE);

    public static final StreamCodec<RegistryFriendlyByteBuf, ContainerEntry> CONTAINER_ENTRY_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ContainerEntry::posX,
            ByteBufCodecs.VAR_INT, ContainerEntry::posY,
            ByteBufCodecs.VAR_INT, ContainerEntry::posZ,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ContainerEntry::itemIds,
            ContainerEntry::new
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, WarehouseContainerResponseS2CPacket> CODEC = StreamCodec.composite(
            CONTAINER_ENTRY_CODEC.apply(ByteBufCodecs.list()), WarehouseContainerResponseS2CPacket::containers,
            WarehouseContainerResponseS2CPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    /**
     * 单个箱子的容器信息（坐标 + 物品种类列表，不含数量）
     */
    public record ContainerEntry(int posX, int posY, int posZ, List<String> itemIds) {}
}
