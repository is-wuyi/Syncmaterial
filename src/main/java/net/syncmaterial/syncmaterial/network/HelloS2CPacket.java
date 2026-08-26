//? if >=26 {
package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端握手回应：告知客户端自己的协议版本，以及该客户端是否被接受。
 *
 * ！！这个包的字段结构永久冻结，任何版本都不得增删改字段 ！！
 * 理由同 HelloC2SPacket。
 *
 * accepted 为 false 表示客户端版本低于服务端的 MIN_COMPATIBLE，
 * 此时服务端不会再处理该客户端的任何业务包；客户端应据此禁用全部功能并提示升级。
 */
public record HelloS2CPacket(int protocolVersion, String modVersion, boolean accepted) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<HelloS2CPacket> ID = new CustomPacketPayload.Type<>(ModPackets.HELLO_S2C);
    public static final StreamCodec<RegistryFriendlyByteBuf, HelloS2CPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HelloS2CPacket::protocolVersion,
            ByteBufCodecs.STRING_UTF8, HelloS2CPacket::modVersion,
            ByteBufCodecs.BOOL, HelloS2CPacket::accepted,
            HelloS2CPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
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
 * 服务端握手回应：告知客户端自己的协议版本，以及该客户端是否被接受。
 *
 * ！！这个包的字段结构永久冻结，任何版本都不得增删改字段 ！！
 * 理由同 HelloC2SPacket。
 *
 * accepted 为 false 表示客户端版本低于服务端的 MIN_COMPATIBLE，
 * 此时服务端不会再处理该客户端的任何业务包；客户端应据此禁用全部功能并提示升级。
 */
public record HelloS2CPacket(int protocolVersion, String modVersion, boolean accepted) implements CustomPayload {
    public static final CustomPayload.Id<HelloS2CPacket> ID = new CustomPayload.Id<>(ModPackets.HELLO_S2C);
    public static final PacketCodec<RegistryByteBuf, HelloS2CPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, HelloS2CPacket::protocolVersion,
            PacketCodecs.STRING, HelloS2CPacket::modVersion,
            PacketCodecs.BOOLEAN, HelloS2CPacket::accepted,
            HelloS2CPacket::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
//?}
