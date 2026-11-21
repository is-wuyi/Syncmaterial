package net.syncmaterial.syncmaterial.client.gui;

import java.util.Objects;

import net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView;

/**
 * Stable, player-facing row states for one project material.
 *
 * <p>The enum is presentation-neutral: each value exposes an i18n key instead of a
 * hardcoded Chinese label. The mapping is intentionally a ladder from the most
 * complete trustworthy observation to the least:</p>
 *
 * <ol>
 *   <li>{@link #NO_DEMAND} - zero required, kept distinct from every progress state.</li>
 *   <li>{@link #UNKNOWN} - preparation is not trusted; last-known counts can never
 *       become "ready" or "collected".</li>
 *   <li>{@link #STAGING_READY} - the staging area alone is sufficient.</li>
 *   <li>{@link #TEAM_COLLECTED} - the trusted team total has no shortfall, but this is
 *       deliberately not merged with staging-ready.</li>
 *   <li>{@link #PARTIAL_STAGING} - something is staged, but completion is not proven.</li>
 *   <li>{@link #IN_BACKPACKS} - trusted team-held counts exist but nothing is staged.</li>
 *   <li>{@link #WAREHOUSE_AVAILABLE} - warehouse stock exists for the remaining need.</li>
 *   <li>{@link #NOT_STARTED} - no trusted progress at all.</li>
 * </ol>
 *
 * <p>Zero demand wins over unknown: an empty catalog is not a stale-completion
 * problem. For positive demand, an unknown/stale preparation view overrides every
 * completion claim and always maps to {@link #UNKNOWN}.</p>
 */
public enum MaterialRowStatus {
    NO_DEMAND("syncmaterial.gui.material_status.no_demand"),
    UNKNOWN("syncmaterial.gui.material_status.unknown"),
    STAGING_READY("syncmaterial.gui.material_status.staging_ready"),
    TEAM_COLLECTED("syncmaterial.gui.material_status.team_collected"),
    PARTIAL_STAGING("syncmaterial.gui.material_status.partial_staging"),
    IN_BACKPACKS("syncmaterial.gui.material_status.in_backpacks"),
    WAREHOUSE_AVAILABLE("syncmaterial.gui.material_status.warehouse_available"),
    NOT_STARTED("syncmaterial.gui.material_status.not_started");

    private final String translationKey;

    MaterialRowStatus(String translationKey) {
        this.translationKey = translationKey;
    }

    /** Stable i18n key; callers translate it with their own localization layer. */
    public String translationKey() {
        return this.translationKey;
    }

    /** Maps a trusted project progress view to one stable row state. */
    public static MaterialRowStatus of(ProjectProgressMaterialView view) {
        Objects.requireNonNull(view, "view");
        if (view.required() == 0L) {
            return NO_DEMAND;
        }
        if (!view.preparationKnown()) {
            return UNKNOWN;
        }
        if (view.stagingReady()) {
            return STAGING_READY;
        }
        if (view.teamCollected()) {
            return TEAM_COLLECTED;
        }
        if (view.staging() > 0L) {
            return PARTIAL_STAGING;
        }
        if (view.teamObserved() > 0L) {
            return IN_BACKPACKS;
        }
        if (view.warehouse() > 0L) {
            return WAREHOUSE_AVAILABLE;
        }
        return NOT_STARTED;
    }
}
