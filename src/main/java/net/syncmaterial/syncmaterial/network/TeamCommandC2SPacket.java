package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamLimits;

/** Project/request-bound team operation. Existing Phase 4 packet layouts remain frozen. */
public record TeamCommandC2SPacket(TeamCommand command) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TeamCommandC2SPacket> ID =
            new CustomPacketPayload.Type<>(ModPackets.TEAM_COMMAND);

    public static final StreamCodec<RegistryFriendlyByteBuf, TeamCommandC2SPacket> CODEC = new StreamCodec<>() {
        @Override
        public TeamCommandC2SPacket decode(RegistryFriendlyByteBuf buffer) {
            String projectId = buffer.readUtf(TeamLimits.PROJECT_ID_LENGTH);
            long requestId = buffer.readVarLong();
            var action = TeamPacketCodec.readAction(buffer);
            long revision = buffer.readVarLong();
            int materialCount = TeamPacketCodec.readSize(buffer, TeamLimits.MATERIALS);
            List<Integer> materials = new ArrayList<>(materialCount);
            for (int index = 0; index < materialCount; index++) materials.add(buffer.readVarInt());
            int targetCount = TeamPacketCodec.readSize(buffer, TeamLimits.TARGETS);
            List<PlayerIdentity> targets = new ArrayList<>(targetCount);
            for (int index = 0; index < targetCount; index++) targets.add(TeamPacketCodec.readIdentity(buffer));
            return new TeamCommandC2SPacket(new TeamCommand(
                    projectId, requestId, action, revision, materials, targets));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, TeamCommandC2SPacket packet) {
            TeamCommand command = packet.command();
            buffer.writeUtf(command.projectId(), TeamLimits.PROJECT_ID_LENGTH);
            buffer.writeVarLong(command.requestId());
            buffer.writeUtf(command.action().name(), 32);
            buffer.writeVarLong(command.catalogRevision());
            buffer.writeVarInt(command.materialIds().size());
            for (int materialId : command.materialIds()) buffer.writeVarInt(materialId);
            buffer.writeVarInt(command.targets().size());
            for (PlayerIdentity target : command.targets()) TeamPacketCodec.writeIdentity(buffer, target);
        }
    };

    public TeamCommandC2SPacket {
        Objects.requireNonNull(command, "command");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
