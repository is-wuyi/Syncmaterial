package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamLimits;
import net.syncmaterial.syncmaterial.core.TeamState;

/** Bounded codecs for the additive protocol 4 management envelopes. */
final class TeamPacketCodec {
    private TeamPacketCodec() {}

    static TeamAction readAction(RegistryFriendlyByteBuf buffer) {
        return TeamAction.valueOf(buffer.readUtf(32));
    }

    static void writeIdentity(RegistryFriendlyByteBuf buffer, PlayerIdentity player) {
        buffer.writeBoolean(player.hasUuid());
        if (player.hasUuid()) buffer.writeUUID(player.uuid());
        buffer.writeUtf(player.name(), TeamLimits.PLAYER_NAME_LENGTH);
    }

    static PlayerIdentity readIdentity(RegistryFriendlyByteBuf buffer) {
        var uuid = buffer.readBoolean() ? buffer.readUUID() : null;
        return new PlayerIdentity(uuid, buffer.readUtf(TeamLimits.PLAYER_NAME_LENGTH));
    }

    static int readSize(RegistryFriendlyByteBuf buffer, int maximum) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("Team packet list exceeds " + maximum);
        }
        return count;
    }

    static void writeTeam(RegistryFriendlyByteBuf buffer, TeamState team) {
        buffer.writeBoolean(team != null);
        if (team == null) return;
        writeIdentity(buffer, team.owner());
        buffer.writeVarInt(team.deputies().size());
        for (PlayerIdentity deputy : team.deputies()) writeIdentity(buffer, deputy);
        buffer.writeBoolean(team.allowSelfClaim());
        buffer.writeBoolean(team.allowNameAuthority());
    }

    static TeamState readTeam(RegistryFriendlyByteBuf buffer, String projectId) {
        if (!buffer.readBoolean()) return null;
        PlayerIdentity owner = readIdentity(buffer);
        int count = readSize(buffer, TeamLimits.DEPUTIES);
        List<PlayerIdentity> deputies = new ArrayList<>(count);
        for (int index = 0; index < count; index++) deputies.add(readIdentity(buffer));
        return new TeamState(projectId, owner, deputies, buffer.readBoolean(), buffer.readBoolean());
    }
}
