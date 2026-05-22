package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.List;

public record StagingAreaConfigResponseS2CPacket(
    boolean success,
    String message,
    List<AreaInfo> areas
) implements CustomPayload {

    public static final CustomPayload.Id<StagingAreaConfigResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.STAGING_AREA_CONFIG_RESPONSE);

    public static final PacketCodec<RegistryByteBuf, AreaInfo> AREA_INFO_CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER, AreaInfo::areaId,
            PacketCodecs.STRING, AreaInfo::name,
            PacketCodecs.INTEGER, AreaInfo::x1,
            PacketCodecs.INTEGER, AreaInfo::y1,
            PacketCodecs.INTEGER, AreaInfo::z1,
            PacketCodecs.INTEGER, AreaInfo::x2,
            PacketCodecs.INTEGER, AreaInfo::y2,
            PacketCodecs.INTEGER, AreaInfo::z2,
            AreaInfo::new
    );

    public static final PacketCodec<RegistryByteBuf, StagingAreaConfigResponseS2CPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOLEAN, StagingAreaConfigResponseS2CPacket::success,
            PacketCodecs.STRING, StagingAreaConfigResponseS2CPacket::message,
            AREA_INFO_CODEC.collect(PacketCodecs.toList()), StagingAreaConfigResponseS2CPacket::areas,
            StagingAreaConfigResponseS2CPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record AreaInfo(int areaId, String name, int x1, int y1, int z1, int x2, int y2, int z2) {}
}