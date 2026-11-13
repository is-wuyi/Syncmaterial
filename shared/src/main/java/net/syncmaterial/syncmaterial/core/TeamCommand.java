package net.syncmaterial.syncmaterial.core;

import java.util.List;
import java.util.Objects;

/** An immutable request. Catalog revision is a delivery token, not a database revision. */
public record TeamCommand(String projectId, long requestId, TeamAction action, long catalogRevision,
                          List<Integer> materialIds, List<PlayerIdentity> targets) {
    public TeamCommand {
        CoreChecks.requireNonBlank(projectId, "projectId");
        if (projectId.length() > TeamLimits.PROJECT_ID_LENGTH || requestId <= 0 || catalogRevision < -1) {
            throw new IllegalArgumentException("Invalid team request identity or revision");
        }
        Objects.requireNonNull(action, "action");
        materialIds = List.copyOf(Objects.requireNonNull(materialIds, "materialIds"));
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        if (materialIds.size() > TeamLimits.MATERIALS || targets.size() > TeamLimits.TARGETS
                || (long) materialIds.size() * targets.size() > TeamLimits.ASSIGNMENTS) {
            throw new IllegalArgumentException("Team request exceeds operation bounds");
        }
        for (PlayerIdentity target : targets) {
            if (target.name().length() > TeamLimits.PLAYER_NAME_LENGTH) {
                throw new IllegalArgumentException("Team target name is too long");
            }
        }
        switch (action) {
            case TRANSFER, ADD_DEPUTY, REMOVE_DEPUTY -> {
                if (targets.size() != 1 || !materialIds.isEmpty()) {
                    throw new IllegalArgumentException("Owner action requires exactly one target and no materials");
                }
            }
            case TOGGLE_SELF_CLAIM, PLAYERS, TEAM_STATE -> {
                if (!targets.isEmpty() || !materialIds.isEmpty()) {
                    throw new IllegalArgumentException("Team query or toggle cannot carry targets or materials");
                }
            }
            case ASSIGN -> {
                if (targets.isEmpty() || materialIds.isEmpty()) {
                    throw new IllegalArgumentException("Assignment requires targets and materials");
                }
            }
            case KICK -> {
                if (targets.size() != 1 || materialIds.isEmpty()) {
                    throw new IllegalArgumentException("Kick requires one target and materials");
                }
            }
        }
    }
}
