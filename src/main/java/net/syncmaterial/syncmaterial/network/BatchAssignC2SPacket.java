package net.syncmaterial.syncmaterial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * 批量分配材料请求包 (Phase 4)
 * 将多个材料分配给多个玩家，玩家自动加入协作组
 */
public record BatchAssignC2SPacket(
    String schematicId,
    List<Integer> materialIds,
    List<String> targetPlayers
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BatchAssignC2SPacket> ID = new CustomPacketPayload.Type<>(ModPackets.BATCH_ASSIGN);
    public static final StreamCodec<RegistryFriendlyByteBuf, BatchAssignC2SPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, BatchAssignC2SPacket::schematicId,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), BatchAssignC2SPacket::materialIds,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), BatchAssignC2SPacket::targetPlayers,
            BatchAssignC2SPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
