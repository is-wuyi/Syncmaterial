package net.syncmaterial.syncmaterial.client;

import java.util.Objects;
import java.util.Set;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.TeamLimits;

/**
 * Client-side merge of consumer leases into the bounded server interest set.
 *
 * <p>The state machine is Minecraft-free and server-free. It is owned by the client
 * session (never a static global); the platform adapter turns a returned
 * {@link Change} into a network command and only sends when {@link Change#changed()}
 * is {@code true}.</p>
 *
 * <p>An active project always retains {@link InterestKind#MATERIALS} and
 * {@link InterestKind#TEAM}, so closing a GUI or hiding the HUD cannot end the
 * project's data subscription. The HUD is a pure render concern and has no lease
 * here. Consumers map to kinds as follows:</p>
 *
 * <ul>
 *   <li>GUI material-list view: {@code AREA_CONFIG} (open/close only adds/removes
 *       this view consumer)</li>
 *   <li>pickup highlight mode: {@code WAREHOUSE_CONTENTS}</li>
 *   <li>outline rendering: {@code OUTLINES}</li>
 *   <li>area editor: {@code AREA_CONFIG}; GUI and editor merge, so closing one keeps
 *       the area interest while the other stays open</li>
 * </ul>
 *
 * <p>Consumer operations carry both the project id and the active view generation.
 * An operation for another project or an older generation is ignored, so a delayed
 * GUI close from a previous project view cannot release the current one even when
 * the two views happen to use the same numeric generation. Project deletion and
 * project close are fenced the same way.</p>
 *
 * <p>Project switch, project deletion, dimension change and disconnect return a
 * {@link Change} with both the previous and current scope/generation/mask. The
 * platform adapter therefore has enough information to send a {@code CLOSE} for the
 * previous scope before opening or updating the current one. A transition from
 * project A to project B with an identical mask is still reported as a change even
 * though the added and removed masks are both zero.</p>
 *
 * <p>{@link #dimensionChanged(String, long)} requires the owner's next generation.
 * The generation is advanced before the method returns, so callbacks still carrying
 * the old dimension/view generation are rejected. Reopening a still-visible view
 * after a dimension change is the platform adapter's responsibility.</p>
 */
public final class ProjectInterestLeases {
    public static final int BASE_MASK = InterestKind.MATERIALS.bit() | InterestKind.TEAM.bit();

    private String projectId;
    private long generation;
    private boolean guiOpen;
    private boolean pickupActive;
    private boolean outlinesActive;
    private boolean editorOpen;

    /** Why a {@link Change} was produced. */
    public enum Reason {
        NONE,
        ACTIVATED,
        SWITCHED,
        GENERATION_CHANGED,
        CLOSED,
        PROJECT_DELETED,
        CONSUMER_CHANGED,
        DIMENSION_CHANGED,
        DISCONNECTED
    }

    /**
     * A before/after snapshot of the lease state.
     *
     * <p>{@code previous*} describes the scope that must be closed or updated away
     * from. {@code projectId}/{@code generation}/{@code desiredMask} describe the new
     * authoritative state. {@code addedMask}/{@code removedMask} are the kind diff,
     * and both can be zero when only the project scope or generation changed. Callers
     * must send the full desired mask whenever {@code changed} is {@code true}, not
     * only the added mask.</p>
     */
    public record Change(boolean changed,
            String previousProjectId, long previousGeneration, int previousMask,
            String projectId, long generation, int desiredMask,
            int addedMask, int removedMask, Reason reason) {
        public Change {
            Objects.requireNonNull(reason, "reason");
            requireScope(previousProjectId, previousGeneration, "previous");
            requireScope(projectId, generation, "current");
            requireMask(previousMask);
            requireMask(desiredMask);
            requireMask(addedMask);
            requireMask(removedMask);
        }

        public boolean isActive() {
            return projectId != null;
        }

        public boolean wasActive() {
            return previousProjectId != null;
        }

        public boolean desires(InterestKind kind) {
            Objects.requireNonNull(kind, "kind");
            return (desiredMask & kind.bit()) != 0;
        }

        public Set<InterestKind> desiredKinds() {
            return InterestKind.fromMask(desiredMask);
        }

        public Set<InterestKind> previousKinds() {
            return InterestKind.fromMask(previousMask);
        }

        private static void requireScope(String projectId, long generation, String label) {
            if (projectId != null) {
                requireProjectId(projectId);
                if (generation <= 0) {
                    throw new IllegalArgumentException(
                            "an active " + label + " project requires a positive generation");
                }
            } else if (generation != 0) {
                throw new IllegalArgumentException(
                        "no active " + label + " project must use generation 0");
            }
        }

        private static void requireMask(int mask) {
            if (mask < 0 || (mask & ~InterestKind.ALL_MASK) != 0) {
                throw new IllegalArgumentException("unknown interest mask bits: 0x"
                        + Integer.toHexString(mask));
            }
        }
    }

    /**
     * Activates a project view generation. Activating the same project and generation
     * twice is idempotent. A different project, or a new generation of the same
     * project, is a reset barrier: every consumer lease of the previous view is
     * closed and only the base interest set remains.
     */
    public synchronized Change activate(String projectId, long generation) {
        requireProjectId(projectId);
        requireGeneration(generation);
        if (isActive() && generation == this.generation && projectId.equals(this.projectId)) {
            return noChange();
        }
        String previousProject = this.projectId;
        long previousGeneration = this.generation;
        int previousMask = currentMask();
        Reason reason;
        if (previousProject == null) {
            reason = Reason.ACTIVATED;
        } else if (projectId.equals(previousProject)) {
            reason = Reason.GENERATION_CHANGED;
        } else {
            reason = Reason.SWITCHED;
        }
        clearConsumers();
        this.projectId = projectId;
        this.generation = generation;
        return change(previousProject, previousGeneration, previousMask, reason);
    }

    /**
     * Closes the active project only when both the project id and generation match it.
     * A delayed close from an older generation or another project is ignored.
     */
    public synchronized Change closeProject(String projectId, long generation) {
        requireProjectId(projectId);
        requireGeneration(generation);
        if (!controls(projectId, generation)) {
            return noChange();
        }
        String previousProject = this.projectId;
        long previousGeneration = this.generation;
        int previousMask = currentMask();
        clearAll();
        return change(previousProject, previousGeneration, previousMask, Reason.CLOSED);
    }

    /**
     * Closes the project only when the deleted id and generation match the active
     * view. A deletion of another project, or a delayed deletion of an older
     * generation of this project, is ignored.
     */
    public synchronized Change projectDeleted(String projectId, long generation) {
        requireProjectId(projectId);
        requireGeneration(generation);
        if (!controls(projectId, generation)) {
            return noChange();
        }
        String previousProject = this.projectId;
        long previousGeneration = this.generation;
        int previousMask = currentMask();
        clearAll();
        return change(previousProject, previousGeneration, previousMask,
                Reason.PROJECT_DELETED);
    }

    /**
     * The world changed. {@code newGeneration} is the owner's explicit next view/content
     * generation and must be greater than the current one; a stale or repeated call is
     * ignored. All world-bound consumer leases are closed, the project keeps its base
     * MATERIALS/TEAM interest, and the active generation is advanced so callbacks that
     * still carry the old generation cannot restore world content. Reopen pickup,
     * outlines, GUI or editor explicitly for the new world with the new generation.
     */
    public synchronized Change dimensionChanged(String projectId, long newGeneration) {
        requireProjectId(projectId);
        requireGeneration(newGeneration);
        if (!isActive() || !projectId.equals(this.projectId)
                || newGeneration <= this.generation) {
            return noChange();
        }
        String previousProject = this.projectId;
        long previousGeneration = this.generation;
        int previousMask = currentMask();
        clearConsumers();
        this.generation = newGeneration;
        return change(previousProject, previousGeneration, previousMask,
                Reason.DIMENSION_CHANGED);
    }

    /**
     * Ends every lease and the project view, e.g. on disconnect or when leaving the
     * world. Idempotent. The returned change retains the previous project scope so the
     * adapter can close it.
     */
    public synchronized Change disconnected() {
        if (!isActive() && !hasConsumers()) {
            return noChange();
        }
        String previousProject = projectId;
        long previousGeneration = generation;
        int previousMask = currentMask();
        clearAll();
        return change(previousProject, previousGeneration, previousMask,
                Reason.DISCONNECTED);
    }

    /** Opens the GUI view for the exact active project and generation. Idempotent. */
    public synchronized Change openGui(String projectId, long generation) {
        return setConsumer(Consumer.GUI, projectId, generation, true);
    }

    /** Closes the GUI view for the exact active project and generation. Idempotent. */
    public synchronized Change closeGui(String projectId, long generation) {
        return setConsumer(Consumer.GUI, projectId, generation, false);
    }

    /** Enables or disables the pickup lease for the exact active project and generation. */
    public synchronized Change setPickup(String projectId, long generation, boolean active) {
        return setConsumer(Consumer.PICKUP, projectId, generation, active);
    }

    /** Enables or disables the outline lease for the exact active project and generation. */
    public synchronized Change setOutlines(String projectId, long generation, boolean active) {
        return setConsumer(Consumer.OUTLINES, projectId, generation, active);
    }

    /** Enables or disables the area editor lease for the exact active project and generation. */
    public synchronized Change setEditor(String projectId, long generation, boolean active) {
        return setConsumer(Consumer.EDITOR, projectId, generation, active);
    }

    public synchronized boolean isActive() {
        return projectId != null;
    }

    /** @return the active project id, or {@code null} */
    public synchronized String projectId() {
        return projectId;
    }

    /** @return the active generation, or 0 when no project is active */
    public synchronized long generation() {
        return projectId == null ? 0 : generation;
    }

    public synchronized int desiredMask() {
        return currentMask();
    }

    public synchronized Set<InterestKind> desiredKinds() {
        return InterestKind.fromMask(currentMask());
    }

    public synchronized boolean isGuiOpen() {
        return guiOpen;
    }

    public synchronized boolean isPickupActive() {
        return pickupActive;
    }

    public synchronized boolean isOutlinesActive() {
        return outlinesActive;
    }

    public synchronized boolean isEditorOpen() {
        return editorOpen;
    }

    private synchronized Change setConsumer(Consumer consumer, String projectId,
            long generation, boolean active) {
        requireProjectId(projectId);
        requireGeneration(generation);
        if (!controls(projectId, generation)) {
            return noChange();
        }
        boolean current = switch (consumer) {
            case GUI -> guiOpen;
            case PICKUP -> pickupActive;
            case OUTLINES -> outlinesActive;
            case EDITOR -> editorOpen;
        };
        if (current == active) {
            return noChange();
        }
        String previousProject = this.projectId;
        long previousGeneration = this.generation;
        int previousMask = currentMask();
        switch (consumer) {
            case GUI -> guiOpen = active;
            case PICKUP -> pickupActive = active;
            case OUTLINES -> outlinesActive = active;
            case EDITOR -> editorOpen = active;
        }
        return change(previousProject, previousGeneration, previousMask,
                Reason.CONSUMER_CHANGED);
    }

    private synchronized boolean controls(String projectId, long generation) {
        return isActive() && generation == this.generation && projectId.equals(this.projectId);
    }

    private synchronized int currentMask() {
        if (!isActive()) {
            return 0;
        }
        int mask = BASE_MASK;
        if (guiOpen || editorOpen) {
            mask |= InterestKind.AREA_CONFIG.bit();
        }
        if (pickupActive) {
            mask |= InterestKind.WAREHOUSE_CONTENTS.bit();
        }
        if (outlinesActive) {
            mask |= InterestKind.OUTLINES.bit();
        }
        return mask;
    }

    private synchronized boolean hasConsumers() {
        return guiOpen || pickupActive || outlinesActive || editorOpen;
    }

    private synchronized Change noChange() {
        String currentProject = projectId;
        long currentGeneration = projectId == null ? 0 : generation;
        int currentMask = currentMask();
        return new Change(false, currentProject, currentGeneration, currentMask,
                currentProject, currentGeneration, currentMask, 0, 0, Reason.NONE);
    }

    private synchronized Change change(String previousProject, long previousGeneration,
            int previousMask, Reason reason) {
        long currentGeneration = projectId == null ? 0 : generation;
        int desiredMask = currentMask();
        boolean changed = !Objects.equals(projectId, previousProject)
                || currentGeneration != previousGeneration
                || desiredMask != previousMask;
        if (!changed) {
            return new Change(false, previousProject, previousGeneration, previousMask,
                    projectId, currentGeneration, desiredMask, 0, 0, Reason.NONE);
        }
        return new Change(true, previousProject, previousGeneration, previousMask,
                projectId, currentGeneration, desiredMask,
                desiredMask & ~previousMask, previousMask & ~desiredMask, reason);
    }

    private synchronized void clearConsumers() {
        guiOpen = false;
        pickupActive = false;
        outlinesActive = false;
        editorOpen = false;
    }

    private synchronized void clearAll() {
        clearConsumers();
        projectId = null;
        generation = 0;
    }

    private static void requireProjectId(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (projectId.length() > TeamLimits.PROJECT_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "projectId exceeds " + TeamLimits.PROJECT_ID_LENGTH + " characters");
        }
    }

    private static void requireGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }

    private enum Consumer {
        GUI,
        PICKUP,
        OUTLINES,
        EDITOR
    }
}
