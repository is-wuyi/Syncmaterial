package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record LeaveCollaborationC2SPacket(String schematicId, int materialId) implements CustomPayload {

    public static final CustomPayload.Id<LeaveCollaborationC2SPacket> ID = new CustomPayload.Id<>(ModPackets.LEAVE_COLLABORATION);

    public static final PacketCodec<RegistryByteBuf, LeaveCollaborationC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, LeaveCollaborationC2SPacket::schematicId,
            PacketCodecs.INTEGER, LeaveCollaborationC2SPacket::materialId,
            LeaveCollaborationC2SPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
