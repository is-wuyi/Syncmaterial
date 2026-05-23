package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.Optional;

public record StagingAreaConfigC2SPacket(
    String schematicId,
    String action,
    int areaId,
    Optional<AreaData> areaData
) implements CustomPayload {

    public static final CustomPayload.Id<StagingAreaConfigC2SPacket> ID = new CustomPayload.Id<>(ModPackets.STAGING_AREA_CONFIG);

    public static final PacketCodec<RegistryByteBuf, AreaData> AREA_DATA_CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, AreaData::name,
            PacketCodecs.INTEGER, AreaData::x1,
            PacketCodecs.INTEGER, AreaData::y1,
            PacketCodecs.INTEGER, AreaData::z1,
            PacketCodecs.INTEGER, AreaData::x2,
            PacketCodecs.INTEGER, AreaData::y2,
            PacketCodecs.INTEGER, AreaData::z2,
            PacketCodecs.optional(PacketCodecs.STRING), AreaData::world,
            AreaData::new
    );

    public static final PacketCodec<RegistryByteBuf, StagingAreaConfigC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, StagingAreaConfigC2SPacket::schematicId,
            PacketCodecs.STRING, StagingAreaConfigC2SPacket::action,
            PacketCodecs.INTEGER, StagingAreaConfigC2SPacket::areaId,
            PacketCodecs.optional(AREA_DATA_CODEC), StagingAreaConfigC2SPacket::areaData,
            StagingAreaConfigC2SPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record AreaData(String name, int x1, int y1, int z1, int x2, int y2, int z2, Optional<String> world) {}
}