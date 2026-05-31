package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record RescanStagingAreaResponseS2CPacket(boolean success, String message) implements CustomPayload {
    public static final CustomPayload.Id<RescanStagingAreaResponseS2CPacket> ID = new CustomPayload.Id<>(Identifier.of("syncmaterial", "rescan_staging_area_response"));
    public static final PacketCodec<PacketByteBuf, RescanStagingAreaResponseS2CPacket> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeBoolean(value.success());
                buf.writeString(value.message());
            },
            buf -> new RescanStagingAreaResponseS2CPacket(buf.readBoolean(), buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
