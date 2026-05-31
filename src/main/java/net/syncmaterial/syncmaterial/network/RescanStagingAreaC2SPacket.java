package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record RescanStagingAreaC2SPacket(String schematicId) implements CustomPayload {
    public static final CustomPayload.Id<RescanStagingAreaC2SPacket> ID = new CustomPayload.Id<>(Identifier.of("syncmaterial", "rescan_staging_area"));
    public static final PacketCodec<PacketByteBuf, RescanStagingAreaC2SPacket> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.schematicId());
            },
            buf -> new RescanStagingAreaC2SPacket(buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
