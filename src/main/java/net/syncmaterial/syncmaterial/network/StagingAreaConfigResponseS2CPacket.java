package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.List;

public record StagingAreaConfigResponseS2CPacket(
    String schematicId,
    boolean success,
    String message,
    List<AreaInfo> areas
) implements CustomPayload {

    public static final CustomPayload.Id<StagingAreaConfigResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.STAGING_AREA_CONFIG_RESPONSE);

    public static final PacketCodec<RegistryByteBuf, AreaInfo> AREA_INFO_CODEC = new PacketCodec<>() {
        @Override
        public AreaInfo decode(RegistryByteBuf buf) {
            int areaId = PacketCodecs.INTEGER.decode(buf);
            String name = PacketCodecs.STRING.decode(buf);
            int x1 = PacketCodecs.INTEGER.decode(buf);
            int y1 = PacketCodecs.INTEGER.decode(buf);
            int z1 = PacketCodecs.INTEGER.decode(buf);
            int x2 = PacketCodecs.INTEGER.decode(buf);
            int y2 = PacketCodecs.INTEGER.decode(buf);
            int z2 = PacketCodecs.INTEGER.decode(buf);
            String world = PacketCodecs.STRING.decode(buf);
            return new AreaInfo(areaId, name, x1, y1, z1, x2, y2, z2, world);
        }

        @Override
        public void encode(RegistryByteBuf buf, AreaInfo info) {
            PacketCodecs.INTEGER.encode(buf, info.areaId());
            PacketCodecs.STRING.encode(buf, info.name());
            PacketCodecs.INTEGER.encode(buf, info.x1());
            PacketCodecs.INTEGER.encode(buf, info.y1());
            PacketCodecs.INTEGER.encode(buf, info.z1());
            PacketCodecs.INTEGER.encode(buf, info.x2());
            PacketCodecs.INTEGER.encode(buf, info.y2());
            PacketCodecs.INTEGER.encode(buf, info.z2());
            PacketCodecs.STRING.encode(buf, info.world());
        }
    };

    public static final PacketCodec<RegistryByteBuf, StagingAreaConfigResponseS2CPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, StagingAreaConfigResponseS2CPacket::schematicId,
            PacketCodecs.BOOLEAN, StagingAreaConfigResponseS2CPacket::success,
            PacketCodecs.STRING, StagingAreaConfigResponseS2CPacket::message,
            AREA_INFO_CODEC.collect(PacketCodecs.toList()), StagingAreaConfigResponseS2CPacket::areas,
            StagingAreaConfigResponseS2CPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record AreaInfo(int areaId, String name, int x1, int y1, int z1, int x2, int y2, int z2, String world) {}
}