package net.syncmaterial.syncmaterial.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One catalog material in an immutable project progress snapshot.
 *
 * <p>{@code staging} and {@code warehouse} are the deduplicated project area counts
 * for {@code itemId}. Every participant is a claim identity; its effective held
 * count is non-negative and is zero when the observation is offline, expired, or
 * belongs to a legacy unresolved identity. Participants are sorted and unique by
 * {@link ProgressParticipant#identityKey()}; duplicate rows are rejected instead of
 * being counted twice.</p>
 */
public record ProjectProgressMaterial(
        int materialId,
        String itemId,
        long required,
        long staging,
        long warehouse,
        List<ProgressParticipant> participants
) {
    public ProjectProgressMaterial {
        if (materialId <= 0) {
            throw new IllegalArgumentException("materialId must be > 0");
        }
        CoreChecks.requireRegistryId(itemId, "itemId");
        if (required < 0) {
            throw new IllegalArgumentException("required must be >= 0");
        }
        if (staging < 0) {
            throw new IllegalArgumentException("staging must be >= 0");
        }
        if (warehouse < 0) {
            throw new IllegalArgumentException("warehouse must be >= 0");
        }
        Objects.requireNonNull(participants, "participants");
        Map<String, ProgressParticipant> byIdentity = new TreeMap<>();
        for (ProgressParticipant participant : participants) {
            Objects.requireNonNull(participant, "participant");
            ProgressParticipant previous = byIdentity.putIfAbsent(participant.identityKey(), participant);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate participant identity " + participant.identityKey());
            }
        }
        List<ProgressParticipant> sorted = new ArrayList<>(byIdentity.values());
        sorted.sort(Comparator
                .comparing((ProgressParticipant participant) -> participant.player().name(),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(participant -> participant.player().name())
                .thenComparing(participant -> participant.player().hasUuid()
                        ? participant.player().uuid().toString() : ""));
        participants = List.copyOf(sorted);
    }
}
