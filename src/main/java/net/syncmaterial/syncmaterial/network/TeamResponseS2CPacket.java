package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.core.PlayerDirectoryEntry;
import net.syncmaterial.syncmaterial.core.TeamLimits;
import net.syncmaterial.syncmaterial.core.TeamResponse;

/** Correlated management result, directory or non-intrusive project team-state push. */
public record TeamResponseS2CPacket(TeamResponse response) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TeamResponseS2CPacket> ID =
            new CustomPacketPayload.Type<>(ModPackets.TEAM_RESPONSE);

    public static final StreamCodec<RegistryFriendlyByteBuf, TeamResponseS2CPacket> CODEC = new StreamCodec<>() {
        @Override
        public TeamResponseS2CPacket decode(RegistryFriendlyByteBuf buffer) {
            String projectId = buffer.readUtf(TeamLimits.PROJECT_ID_LENGTH);
            long requestId = buffer.readVarLong();
            var action = TeamPacketCodec.readAction(buffer);
            long sequence = buffer.readVarLong();
            boolean success = buffer.readBoolean();
            String message = buffer.readUtf(TeamLimits.MESSAGE_LENGTH);
            var team = TeamPacketCodec.readTeam(buffer, projectId);
            int count = TeamPacketCodec.readSize(buffer, TeamLimits.DIRECTORY);
            List<PlayerDirectoryEntry> players = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                players.add(new PlayerDirectoryEntry(TeamPacketCodec.readIdentity(buffer), buffer.readBoolean()));
            }
            return new TeamResponseS2CPacket(new TeamResponse(projectId, requestId, action, sequence,
                    success, message, team, players, buffer.readVarInt(), buffer.readVarInt()));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, TeamResponseS2CPacket packet) {
            TeamResponse response = packet.response();
            buffer.writeUtf(response.projectId(), TeamLimits.PROJECT_ID_LENGTH);
            buffer.writeVarLong(response.requestId());
            buffer.writeUtf(response.action().name(), 32);
            buffer.writeVarLong(response.sequence());
            buffer.writeBoolean(response.success());
            buffer.writeUtf(response.message(), TeamLimits.MESSAGE_LENGTH);
            TeamPacketCodec.writeTeam(buffer, response.team());
            buffer.writeVarInt(response.players().size());
            for (PlayerDirectoryEntry player : response.players()) {
                TeamPacketCodec.writeIdentity(buffer, player.player());
                buffer.writeBoolean(player.online());
            }
            buffer.writeVarInt(response.processedCount());
            buffer.writeVarInt(response.changedCount());
        }
    };

    public TeamResponseS2CPacket {
        Objects.requireNonNull(response, "response");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
