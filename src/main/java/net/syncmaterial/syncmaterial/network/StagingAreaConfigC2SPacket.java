package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Optional;

public record StagingAreaConfigC2SPacket(
    String schematicId,
    String action,
    int areaId,
    Optional<AreaData> areaData
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StagingAreaConfigC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.STAGING_AREA_CONFIG);

    public static final StreamCodec<RegistryFriendlyByteBuf, AreaData> AREA_DATA_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, AreaData::name,
            ByteBufCodecs.VAR_INT, AreaData::x1,
            ByteBufCodecs.VAR_INT, AreaData::y1,
            ByteBufCodecs.VAR_INT, AreaData::z1,
            ByteBufCodecs.VAR_INT, AreaData::x2,
            ByteBufCodecs.VAR_INT, AreaData::y2,
            ByteBufCodecs.VAR_INT, AreaData::z2,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), AreaData::world,
            AreaData::new
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, StagingAreaConfigC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StagingAreaConfigC2SPacket::schematicId,
            ByteBufCodecs.STRING_UTF8, StagingAreaConfigC2SPacket::action,
            ByteBufCodecs.VAR_INT, StagingAreaConfigC2SPacket::areaId,
            ByteBufCodecs.optional(AREA_DATA_CODEC), StagingAreaConfigC2SPacket::areaData,
            StagingAreaConfigC2SPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record AreaData(String name, int x1, int y1, int z1, int x2, int y2, int z2, Optional<String> world) {}
}
