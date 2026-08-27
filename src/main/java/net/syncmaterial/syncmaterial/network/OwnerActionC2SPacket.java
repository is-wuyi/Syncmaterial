package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 负责人操作请求包 (Phase 4)
 * actions: TRANSFER, ADD_DEPUTY, REMOVE_DEPUTY, TOGGLE_SELF_CLAIM
 */
public record OwnerActionC2SPacket(
    String schematicId,
    String action,
    String targetPlayerName
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OwnerActionC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.OWNER_ACTION);
    public static final StreamCodec<RegistryFriendlyByteBuf, OwnerActionC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, OwnerActionC2SPacket::schematicId,
            ByteBufCodecs.STRING_UTF8, OwnerActionC2SPacket::action,
            ByteBufCodecs.STRING_UTF8, OwnerActionC2SPacket::targetPlayerName,
            OwnerActionC2SPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
