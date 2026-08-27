package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record MaterialListCloseC2SPacket(String schematicId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MaterialListCloseC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.MATERIAL_LIST_CLOSE_C2S);

    public static final StreamCodec<RegistryFriendlyByteBuf, MaterialListCloseC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MaterialListCloseC2SPacket::schematicId,
            MaterialListCloseC2SPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
