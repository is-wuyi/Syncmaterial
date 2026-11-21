package net.syncmaterial.syncmaterial.core;

import java.util.Locale;
import java.util.Objects;

/**
 * One claim participant of one project material together with the server's current
 * effective held count. A claim row is membership; {@code effectiveHeld} is the
 * non-negative count that may be counted toward team progress right now.
 *
 * <p>Offline, expired, and legacy unresolved participants are still listed with
 * {@code effectiveHeld == 0}; they are never dropped from the claim list and no
 * UUID is invented for a name-only identity.</p>
 */
public record ProgressParticipant(PlayerIdentity player, long effectiveHeld) {

    public ProgressParticipant {
        Objects.requireNonNull(player, "player");
        if (effectiveHeld < 0) {
            throw new IllegalArgumentException("effectiveHeld must be >= 0");
        }
        if (!player.hasUuid() && effectiveHeld != 0) {
            throw new IllegalArgumentException("a legacy name-only identity cannot carry an effective held count");
        }
    }

    public boolean hasUuid() {
        return player.hasUuid();
    }

    /**
     * Stable de-duplication key for the projection index: the UUID when resolved,
     * otherwise the case-insensitive legacy name. Names are only a fallback for
     * unresolved identities and are never converted into a UUID.
     */
    public String identityKey() {
        return player.hasUuid()
                ? "uuid:" + player.uuid()
                : "name:" + player.name().toLowerCase(Locale.ROOT);
    }
}
