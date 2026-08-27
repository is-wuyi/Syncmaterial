package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

public record CollaborationStatusS2CPacket(
        String schematicId,
        int materialId,
        int totalCount,
        int stagingCount,
        int warehouseCount,
        List<ParticipantInfo> participants,
        List<AreaFreshnessInfo> freshnessInfo
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CollaborationStatusS2CPacket> ID = new CustomPacketPayload.Type<>(ModPackets.COLLABORATION_STATUS);

    public static final StreamCodec<RegistryFriendlyByteBuf, ParticipantInfo> PARTICIPANT_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ParticipantInfo::playerName,
            ByteBufCodecs.VAR_INT, ParticipantInfo::count,
            ParticipantInfo::new
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, AreaFreshnessInfo> FRESHNESS_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, AreaFreshnessInfo::areaType,
            ByteBufCodecs.VAR_INT, AreaFreshnessInfo::areaId,
            ByteBufCodecs.STRING_UTF8, AreaFreshnessInfo::areaName,
            ByteBufCodecs.STRING_UTF8, AreaFreshnessInfo::status,
            AreaFreshnessInfo::new
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, CollaborationStatusS2CPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CollaborationStatusS2CPacket::schematicId,
            ByteBufCodecs.VAR_INT, CollaborationStatusS2CPacket::materialId,
            ByteBufCodecs.VAR_INT, CollaborationStatusS2CPacket::totalCount,
            ByteBufCodecs.VAR_INT, CollaborationStatusS2CPacket::stagingCount,
            ByteBufCodecs.VAR_INT, CollaborationStatusS2CPacket::warehouseCount,
            PARTICIPANT_CODEC.apply(ByteBufCodecs.list()), CollaborationStatusS2CPacket::participants,
            FRESHNESS_CODEC.apply(ByteBufCodecs.list()), CollaborationStatusS2CPacket::freshnessInfo,
            CollaborationStatusS2CPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record ParticipantInfo(String playerName, int count) {}
    public record AreaFreshnessInfo(String areaType, int areaId, String areaName, String status) {}
}
