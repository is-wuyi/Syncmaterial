package net.syncmaterial.syncmaterial.server.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;

/** Tracks only role rows affected by trusted identity reconciliation; never loads material catalogs. */
final class TeamIdentityWrites {
    private TeamIdentityWrites() {}

    static Set<String> reconcile(Connection connection, List<PlayerIdentity> identities) throws SQLException {
        Set<String> candidates = new LinkedHashSet<>();
        try (PreparedStatement projects = connection.prepareStatement(
                "SELECT p.id FROM projects p JOIN players pl ON pl.id = p.owner_player_id "
                        + "WHERE pl.uuid = ? OR pl.name = ? COLLATE NOCASE "
                        + "UNION SELECT d.project_id FROM project_deputies d JOIN players pl ON pl.id = d.player_id "
                        + "WHERE pl.uuid = ? OR pl.name = ? COLLATE NOCASE")) {
            for (PlayerIdentity identity : identities) {
                String uuid = identity.hasUuid() ? identity.uuid().toString() : null;
                projects.setString(1, uuid);
                projects.setString(2, identity.name());
                projects.setString(3, uuid);
                projects.setString(4, identity.name());
                try (ResultSet rows = projects.executeQuery()) {
                    while (rows.next()) candidates.add(rows.getString(1));
                }
            }
        }
        java.util.Map<String, net.syncmaterial.syncmaterial.core.TeamState> before = new java.util.HashMap<>();
        for (String project : candidates) ProjectTeamReads.teamState(connection, project).ifPresent(state -> before.put(project, state));
        PlayersRepository.reconcilePlayers(connection, identities);
        Set<String> changed = new LinkedHashSet<>();
        for (String project : candidates) {
            var after = ProjectTeamReads.teamState(connection, project);
            if (after.isPresent() && !after.get().equals(before.get(project))) changed.add(project);
        }
        return Set.copyOf(changed);
    }
}
