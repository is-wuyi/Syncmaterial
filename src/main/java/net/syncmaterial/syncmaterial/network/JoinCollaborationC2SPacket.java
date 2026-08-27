package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.HashMap;
import java.util.Map;

public record JoinCollaborationC2SPacket(String schematicId, int materialId, Map<Integer, Integer> inventoryCounts) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<JoinCollaborationC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.JOIN_COLLABORATION);

    public static final StreamCodec<RegistryFriendlyByteBuf, JoinCollaborationC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, JoinCollaborationC2SPacket::schematicId,
            ByteBufCodecs.VAR_INT, JoinCollaborationC2SPacket::materialId,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.VAR_INT, ByteBufCodecs.VAR_INT, 50), JoinCollaborationC2SPacket::inventoryCounts,
            JoinCollaborationC2SPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
