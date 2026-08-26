//? if >=26 {
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
//?} else {
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
//?}
