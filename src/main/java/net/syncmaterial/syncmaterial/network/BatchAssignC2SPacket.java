package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.List;

/**
 * 批量分配材料请求包 (Phase 4)
 * 将多个材料分配给多个玩家，玩家自动加入协作组
 */
public record BatchAssignC2SPacket(
    String schematicId,
    List<Integer> materialIds,
    List<String> targetPlayers
) implements CustomPayload {

    public static final CustomPayload.Id<BatchAssignC2SPacket> ID = new CustomPayload.Id<>(ModPackets.BATCH_ASSIGN);
    public static final PacketCodec<RegistryByteBuf, BatchAssignC2SPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, BatchAssignC2SPacket::schematicId,
            PacketCodecs.INTEGER.collect(PacketCodecs.toList()), BatchAssignC2SPacket::materialIds,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), BatchAssignC2SPacket::targetPlayers,
            BatchAssignC2SPacket::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
