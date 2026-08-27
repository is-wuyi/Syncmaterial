package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record LeaveCollaborationC2SPacket(String schematicId, int materialId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<LeaveCollaborationC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.LEAVE_COLLABORATION);

    public static final StreamCodec<RegistryFriendlyByteBuf, LeaveCollaborationC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LeaveCollaborationC2SPacket::schematicId,
            ByteBufCodecs.VAR_INT, LeaveCollaborationC2SPacket::materialId,
            LeaveCollaborationC2SPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
