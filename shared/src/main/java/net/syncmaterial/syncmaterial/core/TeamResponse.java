package net.syncmaterial.syncmaterial.core;

import java.util.List;
import java.util.Objects;

/** Project/request-bound reply. Request zero is reserved for unsolicited team state only. */
public record TeamResponse(String projectId, long requestId, TeamAction action, long sequence,
                           boolean success, String message, TeamState team,
                           List<PlayerDirectoryEntry> players, int processedCount, int changedCount) {
    public TeamResponse {
        CoreChecks.requireNonBlank(projectId, "projectId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(message, "message");
        players = List.copyOf(Objects.requireNonNull(players, "players"));
        if (projectId.length() > TeamLimits.PROJECT_ID_LENGTH || requestId < 0 || sequence < 0
                || message.length() > TeamLimits.MESSAGE_LENGTH || players.size() > TeamLimits.DIRECTORY
                || processedCount < 0 || changedCount < 0) {
            throw new IllegalArgumentException("Invalid team response");
        }
        if (team != null && !projectId.equals(team.projectId())) {
            throw new IllegalArgumentException("Team response state belongs to another project");
        }
        if (requestId == 0 && (action != TeamAction.TEAM_STATE || !success || team == null || !players.isEmpty())) {
            throw new IllegalArgumentException("Unsolicited reply must be a successful team state");
        }
        if (action != TeamAction.PLAYERS && !players.isEmpty()) {
            throw new IllegalArgumentException("Only a directory reply may contain players");
        }
    }
}
