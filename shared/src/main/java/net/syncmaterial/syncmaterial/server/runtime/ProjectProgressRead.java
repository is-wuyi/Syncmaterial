package net.syncmaterial.syncmaterial.server.runtime;

import java.util.Objects;
import net.syncmaterial.syncmaterial.core.ProjectState;

/** Immutable writer-read facts plus their single online sample and publication sequence. */
public record ProjectProgressRead(
        ProjectState state,
        OnlineInventorySnapshot observations,
        long publicationSequence
) {
    public ProjectProgressRead {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(observations, "observations");
        if (publicationSequence < 0) {
            throw new IllegalArgumentException("publicationSequence must be >= 0");
        }
        if (!state.project().id().equals(observations.projectId())) {
            throw new IllegalArgumentException("project and online sample scopes differ");
        }
    }
}
