package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * 按材料踢出玩家请求包 (Phase 4)
 */
public record KickFromMaterialC2SPacket(
    String schematicId,
    List<Integer> materialIds,
    String targetPlayer
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<KickFromMaterialC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.KICK_FROM_MATERIAL);
    public static final StreamCodec<RegistryFriendlyByteBuf, KickFromMaterialC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, KickFromMaterialC2SPacket::schematicId,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), KickFromMaterialC2SPacket::materialIds,
            ByteBufCodecs.STRING_UTF8, KickFromMaterialC2SPacket::targetPlayer,
            KickFromMaterialC2SPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
