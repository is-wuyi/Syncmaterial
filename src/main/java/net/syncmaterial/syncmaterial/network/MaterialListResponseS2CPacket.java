package net.syncmaterial.syncmaterial.network;

import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.syncmaterial.syncmaterial.api.MaterialEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端发送给客户端的材料清单响应数据包。
 */
public record MaterialListResponseS2CPacket(List<MaterialEntry> materials) implements CustomPayload {

    public static final CustomPayload.Id<MaterialListResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.MATERIAL_LIST_RESPONSE);

    public static final PacketCodec<RegistryByteBuf, MaterialEntry> MATERIAL_ENTRY_CODEC = PacketCodec.tuple(
            ItemStack.PACKET_CODEC, MaterialEntry::getStack,
            PacketCodecs.VAR_LONG, MaterialEntry::getCountTotal,
            PacketCodecs.VAR_LONG, MaterialEntry::getCountMissing,
            PacketCodecs.VAR_LONG, MaterialEntry::getCountMismatched,
            PacketCodecs.VAR_LONG, MaterialEntry::getCountAvailable,
            MaterialEntry::new
    );

    public static final PacketCodec<RegistryByteBuf, MaterialListResponseS2CPacket> CODEC = PacketCodec.tuple(
            MATERIAL_ENTRY_CODEC.collect(PacketCodecs.toList()), MaterialListResponseS2CPacket::materials,
            MaterialListResponseS2CPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
