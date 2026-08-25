package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * 客户端握手包：进服后第一时间上报自己的协议版本。
 *
 * ！！这个包的字段结构永久冻结，任何版本都不得增删改字段 ！！
 * 它是全部版本协商的地基 —— 若它自己的线格式发生变化，
 * 新旧两端连"对方是谁"都无法得知，任何降级逻辑都失去落脚点。
 * 将来需要传递的新信息一律新开包，不要往这里塞。
 *
 * modVersion 仅用于展示给用户（例如提示"服务端装的是 0.2.9，请升级"），
 * 任何判断逻辑都只许依赖 protocolVersion。
 */
public record HelloC2SPacket(int protocolVersion, String modVersion) implements CustomPayload {
    public static final CustomPayload.Id<HelloC2SPacket> ID = new CustomPayload.Id<>(ModPackets.HELLO_C2S);
    public static final PacketCodec<RegistryByteBuf, HelloC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, HelloC2SPacket::protocolVersion,
            PacketCodecs.STRING, HelloC2SPacket::modVersion,
            HelloC2SPacket::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
