package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record InventoryUpdateC2SPacket(String schematicId, int materialId, int count) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<InventoryUpdateC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.INVENTORY_UPDATE);

    public static final StreamCodec<RegistryFriendlyByteBuf, InventoryUpdateC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, InventoryUpdateC2SPacket::schematicId,
            ByteBufCodecs.VAR_INT, InventoryUpdateC2SPacket::materialId,
            ByteBufCodecs.VAR_INT, InventoryUpdateC2SPacket::count,
            InventoryUpdateC2SPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
