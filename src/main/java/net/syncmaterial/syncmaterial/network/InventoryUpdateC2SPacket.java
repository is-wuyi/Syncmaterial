package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record InventoryUpdateC2SPacket(String schematicId, int materialId, int count) implements CustomPayload {

    public static final CustomPayload.Id<InventoryUpdateC2SPacket> ID = new CustomPayload.Id<>(ModPackets.INVENTORY_UPDATE);

    public static final PacketCodec<RegistryByteBuf, InventoryUpdateC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, InventoryUpdateC2SPacket::schematicId,
            PacketCodecs.INTEGER, InventoryUpdateC2SPacket::materialId,
            PacketCodecs.INTEGER, InventoryUpdateC2SPacket::count,
            InventoryUpdateC2SPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
