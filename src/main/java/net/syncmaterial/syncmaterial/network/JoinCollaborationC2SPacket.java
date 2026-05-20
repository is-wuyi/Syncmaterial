package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.HashMap;
import java.util.Map;

public record JoinCollaborationC2SPacket(String schematicId, int materialId, Map<Integer, Integer> inventoryCounts) implements CustomPayload {

    public static final CustomPayload.Id<JoinCollaborationC2SPacket> ID = new CustomPayload.Id<>(ModPackets.JOIN_COLLABORATION);

    public static final PacketCodec<RegistryByteBuf, JoinCollaborationC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, JoinCollaborationC2SPacket::schematicId,
            PacketCodecs.INTEGER, JoinCollaborationC2SPacket::materialId,
            PacketCodecs.map(HashMap::new, PacketCodecs.INTEGER, PacketCodecs.INTEGER, 50), JoinCollaborationC2SPacket::inventoryCounts,
            JoinCollaborationC2SPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
