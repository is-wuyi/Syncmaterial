//? if >=26 {
package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 玩家列表请求包 (Phase 4)
 */
public record PlayerListRequestC2SPacket(
    String schematicId
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlayerListRequestC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.PLAYER_LIST_REQUEST);
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerListRequestC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PlayerListRequestC2SPacket::schematicId,
            PlayerListRequestC2SPacket::new
    );

    @Override
    public Id<? extends CustomPacketPayload> getId() {
        return ID;
    }
}
//?} else {
package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * 玩家列表请求包 (Phase 4)
 */
public record PlayerListRequestC2SPacket(
    String schematicId
) implements CustomPayload {

    public static final CustomPayload.Id<PlayerListRequestC2SPacket> ID = new CustomPayload.Id<>(ModPackets.PLAYER_LIST_REQUEST);
    public static final PacketCodec<RegistryByteBuf, PlayerListRequestC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, PlayerListRequestC2SPacket::schematicId,
            PlayerListRequestC2SPacket::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
//?}
