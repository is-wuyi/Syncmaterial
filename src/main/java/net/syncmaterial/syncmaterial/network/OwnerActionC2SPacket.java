package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * 负责人操作请求包 (Phase 4)
 * actions: TRANSFER, ADD_DEPUTY, REMOVE_DEPUTY, TOGGLE_SELF_CLAIM
 */
public record OwnerActionC2SPacket(
    String schematicId,
    String action,
    String targetPlayerName
) implements CustomPayload {

    public static final CustomPayload.Id<OwnerActionC2SPacket> ID = new CustomPayload.Id<>(ModPackets.OWNER_ACTION);
    public static final PacketCodec<PacketByteBuf, OwnerActionC2SPacket> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.schematicId());
                buf.writeString(value.action());
                buf.writeString(value.targetPlayerName());
            },
            buf -> new OwnerActionC2SPacket(buf.readString(), buf.readString(), buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
