package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record MaterialListCloseC2SPacket(String schematicId) implements CustomPayload {

    public static final CustomPayload.Id<MaterialListCloseC2SPacket> ID = new CustomPayload.Id<>(ModPackets.MATERIAL_LIST_CLOSE_C2S);

    public static final PacketCodec<RegistryByteBuf, MaterialListCloseC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, MaterialListCloseC2SPacket::schematicId,
            MaterialListCloseC2SPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
