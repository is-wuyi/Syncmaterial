//? if >=26 {
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
            PARTICIPANT_CODEC.collect(ByteBufCodecs.collection(ByteBufCodecs.VAR_INT)), CollaborationStatusS2CPacket::participants,
            FRESHNESS_CODEC.collect(ByteBufCodecs.collection(ByteBufCodecs.VAR_INT)), CollaborationStatusS2CPacket::freshnessInfo,
            CollaborationStatusS2CPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record ParticipantInfo(String playerName, int count) {}
    public record AreaFreshnessInfo(String areaType, int areaId, String areaName, String status) {}
}
//?} else {
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
        List<ParticipantInfo> participants,
        List<AreaFreshnessInfo> freshnessInfo
) implements CustomPayload {

    public static final CustomPayload.Id<CollaborationStatusS2CPacket> ID = new CustomPayload.Id<>(ModPackets.COLLABORATION_STATUS);

    public static final PacketCodec<RegistryByteBuf, ParticipantInfo> PARTICIPANT_CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, ParticipantInfo::playerName,
            PacketCodecs.INTEGER, ParticipantInfo::count,
            ParticipantInfo::new
    );

    public static final PacketCodec<RegistryByteBuf, AreaFreshnessInfo> FRESHNESS_CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, AreaFreshnessInfo::areaType,
            PacketCodecs.INTEGER, AreaFreshnessInfo::areaId,
            PacketCodecs.STRING, AreaFreshnessInfo::areaName,
            PacketCodecs.STRING, AreaFreshnessInfo::status,
            AreaFreshnessInfo::new
    );

    public static final PacketCodec<RegistryByteBuf, CollaborationStatusS2CPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, CollaborationStatusS2CPacket::schematicId,
            PacketCodecs.INTEGER, CollaborationStatusS2CPacket::materialId,
            PacketCodecs.INTEGER, CollaborationStatusS2CPacket::totalCount,
            PacketCodecs.INTEGER, CollaborationStatusS2CPacket::stagingCount,
            PacketCodecs.INTEGER, CollaborationStatusS2CPacket::warehouseCount,
            PARTICIPANT_CODEC.collect(PacketCodecs.toList()), CollaborationStatusS2CPacket::participants,
            FRESHNESS_CODEC.collect(PacketCodecs.toList()), CollaborationStatusS2CPacket::freshnessInfo,
            CollaborationStatusS2CPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record ParticipantInfo(String playerName, int count) {}
    public record AreaFreshnessInfo(String areaType, int areaId, String areaName, String status) {}
}
//?}
