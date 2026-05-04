package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 客户端发送给服务端的材料请求数据包。
 */
public record RequestMaterialListC2SPacket(String schematicPath) implements CustomPayload {

    public static final CustomPayload.Id<RequestMaterialListC2SPacket> ID = new CustomPayload.Id<>(ModPackets.REQUEST_MATERIAL_LIST);

    public static final PacketCodec<RegistryByteBuf, RequestMaterialListC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, RequestMaterialListC2SPacket::schematicPath,
            RequestMaterialListC2SPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
