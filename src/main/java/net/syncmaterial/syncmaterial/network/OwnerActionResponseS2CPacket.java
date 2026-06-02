package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

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
) implements CustomPayload {

    public static final CustomPayload.Id<OwnerActionResponseS2CPacket> ID = new CustomPayload.Id<>(ModPackets.OWNER_ACTION_RESPONSE);
    public static final PacketCodec<PacketByteBuf, OwnerActionResponseS2CPacket> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeBoolean(value.success());
                buf.writeString(value.message());
                buf.writeString(value.ownerName());
                buf.writeCollection(value.deputyOwners(), PacketByteBuf::writeString);
                buf.writeBoolean(value.allowSelfClaim());
            },
            buf -> new OwnerActionResponseS2CPacket(
                    buf.readBoolean(),
                    buf.readString(),
                    buf.readString(),
                    buf.readList(PacketByteBuf::readString),
                    buf.readBoolean()
            )
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
