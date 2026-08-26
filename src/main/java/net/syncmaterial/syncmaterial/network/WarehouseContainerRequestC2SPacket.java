//? if >=26 {
package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Phase 5: 取货模式容器数据请求（C→S）
 * 进入取货模式时发送（订阅），退出时发送（取消订阅）
 */
public record WarehouseContainerRequestC2SPacket(
    String schematicId,
    boolean subscribe  // true=订阅，false=取消订阅
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WarehouseContainerRequestC2SPacket> ID =
            new CustomPacketPayload.Type<>(ModPackets.WAREHOUSE_CONTAINER_REQUEST);

    public static final StreamCodec<RegistryFriendlyByteBuf, WarehouseContainerRequestC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, WarehouseContainerRequestC2SPacket::schematicId,
            ByteBufCodecs.BOOL, WarehouseContainerRequestC2SPacket::subscribe,
            WarehouseContainerRequestC2SPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
//?} else {
package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * Phase 5: 取货模式容器数据请求（C→S）
 * 进入取货模式时发送（订阅），退出时发送（取消订阅）
 */
public record WarehouseContainerRequestC2SPacket(
    String schematicId,
    boolean subscribe  // true=订阅，false=取消订阅
) implements CustomPayload {

    public static final CustomPayload.Id<WarehouseContainerRequestC2SPacket> ID =
            new CustomPayload.Id<>(ModPackets.WAREHOUSE_CONTAINER_REQUEST);

    public static final PacketCodec<RegistryByteBuf, WarehouseContainerRequestC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, WarehouseContainerRequestC2SPacket::schematicId,
            PacketCodecs.BOOLEAN, WarehouseContainerRequestC2SPacket::subscribe,
            WarehouseContainerRequestC2SPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
//?}
