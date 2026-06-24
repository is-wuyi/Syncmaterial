package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.List;

public record CollaborationStatusS2CPacket(
        String schematicId,
        int materialId,
        int totalCount,
        int stagingCount,
        int warehouseCount,
        List<ParticipantInfo> participants
) implements CustomPayload {

    public static final CustomPayload.Id<CollaborationStatusS2CPacket> ID = new CustomPayload.Id<>(ModPackets.COLLABORATION_STATUS);

    public static final PacketCodec<RegistryByteBuf, ParticipantInfo> PARTICIPANT_CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, ParticipantInfo::playerName,
            PacketCodecs.INTEGER, ParticipantInfo::count,
            ParticipantInfo::new
    );

    public static final PacketCodec<RegistryByteBuf, CollaborationStatusS2CPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, CollaborationStatusS2CPacket::schematicId,
            PacketCodecs.INTEGER, CollaborationStatusS2CPacket::materialId,
            PacketCodecs.INTEGER, CollaborationStatusS2CPacket::totalCount,
            PacketCodecs.INTEGER, CollaborationStatusS2CPacket::stagingCount,
            PacketCodecs.INTEGER, CollaborationStatusS2CPacket::warehouseCount,
            PARTICIPANT_CODEC.collect(PacketCodecs.toList()), CollaborationStatusS2CPacket::participants,
            CollaborationStatusS2CPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record ParticipantInfo(String playerName, int count) {}
}
