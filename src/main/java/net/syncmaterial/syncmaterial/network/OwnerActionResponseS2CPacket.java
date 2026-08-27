package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * 负责人操作响应包 (Phase 4)
 * 成功时携带最新的负责人状态
 */
public record OwnerActionResponseS2CPacket(
    boolean success,
    String message,
    String ownerName,
    List<String> deputyOwners,
    boolean allowSelfClaim
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OwnerActionResponseS2CPacket> ID = new CustomPacketPayload.Type<>(ModPackets.OWNER_ACTION_RESPONSE);
    public static final StreamCodec<RegistryFriendlyByteBuf, OwnerActionResponseS2CPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, OwnerActionResponseS2CPacket::success,
            ByteBufCodecs.STRING_UTF8, OwnerActionResponseS2CPacket::message,
            ByteBufCodecs.STRING_UTF8, OwnerActionResponseS2CPacket::ownerName,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OwnerActionResponseS2CPacket::deputyOwners,
            ByteBufCodecs.BOOL, OwnerActionResponseS2CPacket::allowSelfClaim,
            OwnerActionResponseS2CPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
