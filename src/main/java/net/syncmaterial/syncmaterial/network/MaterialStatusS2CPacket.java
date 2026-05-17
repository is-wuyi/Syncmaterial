package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.List;

public record MaterialStatusS2CPacket(List<MaterialStatusEntry> statuses) implements CustomPayload {

    public static final CustomPayload.Id<MaterialStatusS2CPacket> ID = new CustomPayload.Id<>(ModPackets.MATERIAL_STATUS_RESPONSE);

    public static final PacketCodec<RegistryByteBuf, MaterialStatusS2CPacket> CODEC = PacketCodec.tuple(
            MaterialStatusEntry.PACKET_CODEC.collect(PacketCodecs.toList()), MaterialStatusS2CPacket::statuses,
            MaterialStatusS2CPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record MaterialStatusEntry(int materialId, String itemId, int totalCount, int claimedCount, String claimer) {
        public static final PacketCodec<RegistryByteBuf, MaterialStatusEntry> PACKET_CODEC = PacketCodec.tuple(
                PacketCodecs.INTEGER, MaterialStatusEntry::materialId,
                PacketCodecs.STRING, MaterialStatusEntry::itemId,
                PacketCodecs.INTEGER, MaterialStatusEntry::totalCount,
                PacketCodecs.INTEGER, MaterialStatusEntry::claimedCount,
                PacketCodecs.STRING, MaterialStatusEntry::claimer,
                MaterialStatusEntry::new
        );
    }
}
