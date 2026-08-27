package net.syncmaterial.syncmaterial.network;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 服务端响应材料统计请求的数据包
 */
public record MaterialStatsResponseS2CPacket(
    String schematicId,
    String schematicName,
    List<MaterialEntry> materials,
    boolean isOwner,
    boolean isMainOwner,
    String ownerName,
    List<String> deputyOwners,
    boolean allowSelfClaim
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MaterialStatsResponseS2CPacket> ID = new CustomPacketPayload.Type<>(ModPackets.MATERIAL_LIST_RESPONSE);

    public static final StreamCodec<RegistryFriendlyByteBuf, MaterialStatsResponseS2CPacket> CODEC = new StreamCodec<>() {
        @Override
        public MaterialStatsResponseS2CPacket decode(RegistryFriendlyByteBuf buf) {
            String schematicId = ByteBufCodecs.STRING_UTF8.decode(buf);
            String schematicName = ByteBufCodecs.STRING_UTF8.decode(buf);
            List<MaterialEntry> materials = MaterialEntry.PACKET_CODEC.apply(ByteBufCodecs.list()).decode(buf);
            boolean isOwner = ByteBufCodecs.BOOL.decode(buf);
            boolean isMainOwner = ByteBufCodecs.BOOL.decode(buf);
            String ownerName = ByteBufCodecs.STRING_UTF8.decode(buf);
            List<String> deputyOwners = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf);
            boolean allowSelfClaim = ByteBufCodecs.BOOL.decode(buf);
            return new MaterialStatsResponseS2CPacket(schematicId, schematicName, materials, isOwner, isMainOwner, ownerName, deputyOwners, allowSelfClaim);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, MaterialStatsResponseS2CPacket value) {
            ByteBufCodecs.STRING_UTF8.encode(buf, value.schematicId());
            ByteBufCodecs.STRING_UTF8.encode(buf, value.schematicName());
            MaterialEntry.PACKET_CODEC.apply(ByteBufCodecs.list()).encode(buf, value.materials());
            ByteBufCodecs.BOOL.encode(buf, value.isOwner());
            ByteBufCodecs.BOOL.encode(buf, value.isMainOwner());
            ByteBufCodecs.STRING_UTF8.encode(buf, value.ownerName());
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, value.deputyOwners());
            ByteBufCodecs.BOOL.encode(buf, value.allowSelfClaim());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
