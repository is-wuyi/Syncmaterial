package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record ClaimResultS2CPacket(boolean success, String message, int materialId, int newClaimedCount) implements CustomPayload {

    public static final CustomPayload.Id<ClaimResultS2CPacket> ID = new CustomPayload.Id<>(ModPackets.CLAIM_RESULT);

    public static final PacketCodec<RegistryByteBuf, ClaimResultS2CPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOLEAN, ClaimResultS2CPacket::success,
            PacketCodecs.STRING, ClaimResultS2CPacket::message,
            PacketCodecs.INTEGER, ClaimResultS2CPacket::materialId,
            PacketCodecs.INTEGER, ClaimResultS2CPacket::newClaimedCount,
            ClaimResultS2CPacket::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
