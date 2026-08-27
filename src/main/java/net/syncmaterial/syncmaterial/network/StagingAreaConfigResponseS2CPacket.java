package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

public record StagingAreaConfigResponseS2CPacket(
    String action,
    String schematicId,
    String schematicName,
    boolean success,
    String message,
    List<AreaInfo> areas
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StagingAreaConfigResponseS2CPacket> ID = new CustomPacketPayload.Type<>(ModPackets.STAGING_AREA_CONFIG_RESPONSE);

    public static final StreamCodec<RegistryFriendlyByteBuf, AreaInfo> AREA_INFO_CODEC = new StreamCodec<>() {
        @Override
        public AreaInfo decode(RegistryFriendlyByteBuf buf) {
            int areaId = ByteBufCodecs.VAR_INT.decode(buf);
            String name = ByteBufCodecs.STRING_UTF8.decode(buf);
            int x1 = ByteBufCodecs.VAR_INT.decode(buf);
            int y1 = ByteBufCodecs.VAR_INT.decode(buf);
            int z1 = ByteBufCodecs.VAR_INT.decode(buf);
            int x2 = ByteBufCodecs.VAR_INT.decode(buf);
            int y2 = ByteBufCodecs.VAR_INT.decode(buf);
            int z2 = ByteBufCodecs.VAR_INT.decode(buf);
            String world = ByteBufCodecs.STRING_UTF8.decode(buf);
            return new AreaInfo(areaId, name, x1, y1, z1, x2, y2, z2, world);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AreaInfo info) {
            ByteBufCodecs.VAR_INT.encode(buf, info.areaId());
            ByteBufCodecs.STRING_UTF8.encode(buf, info.name());
            ByteBufCodecs.VAR_INT.encode(buf, info.x1());
            ByteBufCodecs.VAR_INT.encode(buf, info.y1());
            ByteBufCodecs.VAR_INT.encode(buf, info.z1());
            ByteBufCodecs.VAR_INT.encode(buf, info.x2());
            ByteBufCodecs.VAR_INT.encode(buf, info.y2());
            ByteBufCodecs.VAR_INT.encode(buf, info.z2());
            ByteBufCodecs.STRING_UTF8.encode(buf, info.world());
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, StagingAreaConfigResponseS2CPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StagingAreaConfigResponseS2CPacket::action,
            ByteBufCodecs.STRING_UTF8, StagingAreaConfigResponseS2CPacket::schematicId,
            ByteBufCodecs.STRING_UTF8, StagingAreaConfigResponseS2CPacket::schematicName,
            ByteBufCodecs.BOOL, StagingAreaConfigResponseS2CPacket::success,
            ByteBufCodecs.STRING_UTF8, StagingAreaConfigResponseS2CPacket::message,
            AREA_INFO_CODEC.apply(ByteBufCodecs.list()), StagingAreaConfigResponseS2CPacket::areas,
            StagingAreaConfigResponseS2CPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record AreaInfo(int areaId, String name, int x1, int y1, int z1, int x2, int y2, int z2, String world) {}
}
