//? if >=26 {
package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * 玩家列表响应包 (Phase 4)
 */
public record PlayerListResponseS2CPacket(
    List<PlayerInfo> players
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlayerListResponseS2CPacket> ID = new CustomPacketPayload.Type<>(ModPackets.PLAYER_LIST_RESPONSE);

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerInfo> PLAYER_INFO_CODEC = new StreamCodec<>() {
        @Override
        public PlayerInfo decode(RegistryFriendlyByteBuf buf) {
            String name = ByteBufCodecs.STRING_UTF8.decode(buf);
            boolean online = ByteBufCodecs.BOOL.decode(buf);
            return new PlayerInfo(name, online);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PlayerInfo info) {
            ByteBufCodecs.STRING_UTF8.encode(buf, info.name());
            ByteBufCodecs.BOOL.encode(buf, info.online());
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerListResponseS2CPacket> CODEC = StreamCodec.composite(
            PLAYER_INFO_CODEC.collect(ByteBufCodecs.collection(ByteBufCodecs.VAR_INT)), PlayerListResponseS2CPacket::players,
            PlayerListResponseS2CPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record PlayerInfo(String name, boolean online) {}
}
//?} else {
package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.List;

/**
 * 玩家列表响应包 (Phase 4)
 */
public record PlayerListResponseS2CPacket(
    List<PlayerInfo> players
) implements CustomPayload {

    public static final CustomPayload.Id<PlayerListResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.PLAYER_LIST_RESPONSE);

    public static final PacketCodec<RegistryByteBuf, PlayerInfo> PLAYER_INFO_CODEC = new PacketCodec<>() {
        @Override
        public PlayerInfo decode(RegistryByteBuf buf) {
            String name = PacketCodecs.STRING.decode(buf);
            boolean online = PacketCodecs.BOOLEAN.decode(buf);
            return new PlayerInfo(name, online);
        }

        @Override
        public void encode(RegistryByteBuf buf, PlayerInfo info) {
            PacketCodecs.STRING.encode(buf, info.name());
            PacketCodecs.BOOLEAN.encode(buf, info.online());
        }
    };

    public static final PacketCodec<RegistryByteBuf, PlayerListResponseS2CPacket> CODEC = PacketCodec.tuple(
            PLAYER_INFO_CODEC.collect(PacketCodecs.toList()), PlayerListResponseS2CPacket::players,
            PlayerListResponseS2CPacket::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record PlayerInfo(String name, boolean online) {}
}
//?}
