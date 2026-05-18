package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record JoinCollaborationC2SPacket(String schematicId, int materialId) implements CustomPayload {

    public static final CustomPayload.Id<JoinCollaborationC2SPacket> ID = new CustomPayload.Id<>(ModPackets.JOIN_COLLABORATION);

    public static final PacketCodec<RegistryByteBuf, JoinCollaborationC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, JoinCollaborationC2SPacket::schematicId,
            PacketCodecs.INTEGER, JoinCollaborationC2SPacket::materialId,
            JoinCollaborationC2SPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
