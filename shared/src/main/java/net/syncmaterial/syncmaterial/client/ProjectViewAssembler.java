package net.syncmaterial.syncmaterial.client;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ProjectProgressCodec;
import net.syncmaterial.syncmaterial.network.ProjectViewPart;

/**
 * Client-side atomic assembler for protocol-5 progress fragments.
 *
 * <p>Views must be explicitly opened and closed by the client owner. The connection token
 * is compared with reference identity ({@code ==}); its positive connection generation is
 * supplied by that owner and is never generated here. An equal-but-distinct reconnect token
 * therefore cannot inherit the old connection's state, and an older generation can never
 * close or release a newer one.</p>
 *
 * <p>Assemblies are keyed by connection identity, connection generation, view id, view
 * generation and snapshot id. Every part must agree on scope, view generation, publication
 * sequence, catalog revision, inventory revision, part count and total bytes. Only a fully
 * decoded immutable {@link ProjectProgressSnapshot} is emitted. The assembled snapshot's
 * project id, catalog revision, inventory revision and publication sequence are checked
 * against the envelope again. Completion is rejected when its sequence is not newer than
 * the sequence already installed for that view. Pending input is bounded by active
 * connections/views and four assemblies; timeouts are explicit through {@link #tick()} or
 * {@link #advance(long)} and retained closures are removed rather than accumulated.</p>
 */
public final class ProjectViewAssembler {

    public enum FailureReason {
        NOT_A_PROGRESS_PART,
        CONNECTION_NOT_OPEN,
        VIEW_NOT_OPEN,
        OUT_OF_ORDER,
        DUPLICATE_CONFLICT,
        INCONSISTENT,
        TOO_MANY_PENDING,
        OVER_LIMIT,
        TIMEOUT,
        STALE,
        DECODE_FAILED
    }

    public sealed interface Outcome permits Pending, Complete, Failed {}

    /** Assembly is incomplete; {@code receivedParts} parts of {@code partCount} are present. */
    public record Pending(String snapshotId, int receivedParts, int partCount) implements Outcome {}

    /** Assembly completed and decoded atomically; no mutable byte array is exposed. */
    public record Complete(AssembledView view) implements Outcome {}

    public record Failed(FailureReason reason, long viewId, String snapshotId, String detail)
            implements Outcome {}

    /** Immutable result of a completed assembly. */
    public record AssembledView(
            ProjectViewScope scope,
            long viewId,
            long generation,
            long sequence,
            String snapshotId,
            long catalogRevision,
            long inventoryRevision,
            ProjectProgressSnapshot snapshot
    ) {
        public AssembledView {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(snapshot, "snapshot");
            ProjectViewLimits.requirePositiveId(viewId, "viewId");
            ProjectViewLimits.requirePositiveId(generation, "generation");
            ProjectViewLimits.requireNonNegative(sequence, "sequence");
        }
    }

    private static final class ConnectionState {
        private final long generation;
        private final Map<Long, ViewState> views = new HashMap<>();

        private ConnectionState(long generation) {
            this.generation = generation;
        }
    }

    private static final class ViewState {
        private final ProjectViewScope scope;
        private final long viewId;
        private final long viewGeneration;
        private final Map<String, Assembly> assemblies = new HashMap<>();
        private long installedSequence = -1;

        private ViewState(ProjectViewScope scope, long viewId, long viewGeneration) {
            this.scope = scope;
            this.viewId = viewId;
            this.viewGeneration = viewGeneration;
        }
    }

    private static final class Assembly {
        private final ProjectViewScope scope;
        private final long viewId;
        private final long generation;
        private final long sequence;
        private final String snapshotId;
        private final long catalogRevision;
        private final long inventoryRevision;
        private final int partCount;
        private final int totalBytes;
        private final byte[][] parts;
        private int receivedParts;
        private int receivedBytes;
        private long lastActivityMillis;

        private Assembly(ProjectViewPart packet, long now) {
            this.scope = packet.scope();
            this.viewId = packet.viewId();
            this.generation = packet.generation();
            this.sequence = packet.sequence();
            this.snapshotId = packet.snapshotId();
            this.catalogRevision = packet.catalogRevision();
            this.inventoryRevision = packet.inventoryRevision();
            this.partCount = packet.partCount();
            this.totalBytes = packet.totalBytes();
            this.parts = new byte[partCount][];
            this.lastActivityMillis = now;
        }
    }

    private final long timeoutMillis;
    private final IdentityHashMap<Object, ConnectionState> connections = new IdentityHashMap<>();
    private int assemblyCount;
    private int activeViewCount;
    private long nowMillis;

    public ProjectViewAssembler() {
        this(ProjectViewLimits.ASSEMBLY_TIMEOUT_MILLIS);
    }

    public ProjectViewAssembler(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be > 0");
        }
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Opens/refreshes one view for the exact connection token and positive connection
     * generation. The same scope/generation is idempotent; a newer view generation resets
     * that view's assemblies and installed sequence. A stale connection/view generation
     * returns false and changes nothing.
     */
    public synchronized boolean openView(Object connection, long connectionGeneration,
                                         ProjectViewScope scope, long viewId,
                                         long viewGeneration) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(scope, "scope");
        ProjectViewLimits.requirePositiveId(connectionGeneration, "connectionGeneration");
        ProjectViewLimits.requirePositiveId(viewId, "viewId");
        ProjectViewLimits.requirePositiveId(viewGeneration, "viewGeneration");

        ConnectionState state = connections.get(connection);
        if (state != null) {
            if (connectionGeneration < state.generation) {
                return false;
            }
            if (connectionGeneration > state.generation) {
                removeConnectionState(connection, state);
                state = null;
            }
        }
        if (state == null) {
            if (connections.size() >= ProjectViewLimits.MAX_ACTIVE_CONNECTIONS) {
                throw new IllegalStateException("too many active project-view connections");
            }
            state = new ConnectionState(connectionGeneration);
            connections.put(connection, state);
        }

        ViewState view = state.views.get(viewId);
        if (view != null) {
            if (viewGeneration < view.viewGeneration) {
                return false;
            }
            if (viewGeneration == view.viewGeneration) {
                // Reopening under another project requires the owner to advance the view
                // generation; otherwise late packets from the two scopes become indistinguishable.
                return view.scope.equals(scope);
            }
            removeViewState(state, view);
        }
        if (activeViewCount >= ProjectViewLimits.MAX_ACTIVE_VIEWS) {
            if (state.views.isEmpty()) {
                connections.remove(connection);
            }
            throw new IllegalStateException("too many active project views");
        }
        state.views.put(viewId, new ViewState(scope, viewId, viewGeneration));
        activeViewCount++;
        return true;
    }

    /** Closes exactly one open view for the exact connection generation. */
    public synchronized int closeView(Object connection, long connectionGeneration, long viewId) {
        Objects.requireNonNull(connection, "connection");
        ProjectViewLimits.requirePositiveId(connectionGeneration, "connectionGeneration");
        ProjectViewLimits.requirePositiveId(viewId, "viewId");
        ConnectionState state = connections.get(connection);
        if (state == null || state.generation != connectionGeneration) {
            return 0;
        }
        ViewState view = state.views.get(viewId);
        if (view == null) {
            return 0;
        }
        int removed = view.assemblies.size();
        removeViewState(state, view);
        return removed;
    }

    /** Closes every view for the exact connection token and generation. */
    public synchronized int closeConnection(Object connection, long connectionGeneration) {
        Objects.requireNonNull(connection, "connection");
        ProjectViewLimits.requirePositiveId(connectionGeneration, "connectionGeneration");
        ConnectionState state = connections.get(connection);
        if (state == null || state.generation != connectionGeneration) {
            return 0;
        }
        return removeConnectionState(connection, state);
    }

    /** Accepts one S2C envelope. Non-progress results are rejected, never installed. */
    public synchronized Outcome accept(Object connection, long connectionGeneration,
                                       ProjectViewPart packet) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(packet, "packet");
        ProjectViewLimits.requirePositiveId(connectionGeneration, "connectionGeneration");

        if (!packet.isProgressPart()) {
            return failed(FailureReason.NOT_A_PROGRESS_PART, packet,
                    "result is not a progress fragment");
        }
        ConnectionState state = connections.get(connection);
        if (state == null || state.generation != connectionGeneration) {
            return failed(FailureReason.CONNECTION_NOT_OPEN, packet,
                    "connection generation is not open");
        }
        ViewState view = state.views.get(packet.viewId());
        if (view == null) {
            return failed(FailureReason.VIEW_NOT_OPEN, packet,
                    "view " + packet.viewId() + " is not open");
        }
        if (!view.scope.equals(packet.scope())) {
            return failed(FailureReason.INCONSISTENT, packet,
                    "fragment scope differs from the open view");
        }
        if (packet.generation() != view.viewGeneration) {
            return failed(FailureReason.STALE, packet,
                    "fragment view generation " + packet.generation()
                            + " is not the open generation " + view.viewGeneration);
        }

        byte[] receivedFragment = packet.fragment();
        String structural = validatePart(packet, receivedFragment);
        if (structural != null) {
            return failed(FailureReason.OVER_LIMIT, packet, structural);
        }

        Assembly assembly = view.assemblies.get(packet.snapshotId());
        if (assembly == null) {
            if (assemblyCount >= ProjectViewLimits.MAX_CONCURRENT_ASSEMBLIES) {
                return failed(FailureReason.TOO_MANY_PENDING, packet,
                        "at most " + ProjectViewLimits.MAX_CONCURRENT_ASSEMBLIES
                                + " incomplete assemblies are allowed");
            }
            if (packet.partIndex() != 0) {
                return failed(FailureReason.OUT_OF_ORDER, packet,
                        "first received part index is " + packet.partIndex());
            }
            assembly = new Assembly(packet, nowMillis);
            view.assemblies.put(packet.snapshotId(), assembly);
            assemblyCount++;
        } else if (metadataMismatch(assembly, packet)) {
            removeAssembly(view, assembly);
            return failed(FailureReason.INCONSISTENT, packet,
                    "fragment metadata differs from the first fragment");
        }

        int expected = assembly.receivedParts;
        if (packet.partIndex() > expected) {
            removeAssembly(view, assembly);
            return failed(FailureReason.OUT_OF_ORDER, packet,
                    "expected part " + expected + " but received " + packet.partIndex());
        }
        if (packet.partIndex() < expected) {
            byte[] existing = assembly.parts[packet.partIndex()];
            if (existing != null && Arrays.equals(existing, receivedFragment)) {
                return new Pending(packet.snapshotId(), assembly.receivedParts, assembly.partCount);
            }
            removeAssembly(view, assembly);
            return failed(FailureReason.DUPLICATE_CONFLICT, packet,
                    "repeated part index has different bytes");
        }

        if ((long) assembly.receivedBytes + receivedFragment.length > assembly.totalBytes) {
            removeAssembly(view, assembly);
            return failed(FailureReason.OVER_LIMIT, packet,
                    "fragments exceed declared totalBytes");
        }
        assembly.parts[packet.partIndex()] = receivedFragment;
        assembly.receivedParts++;
        assembly.receivedBytes += receivedFragment.length;
        assembly.lastActivityMillis = nowMillis;

        if (assembly.receivedParts < assembly.partCount) {
            return new Pending(packet.snapshotId(), assembly.receivedParts, assembly.partCount);
        }

        removeAssembly(view, assembly);
        if (assembly.receivedBytes != assembly.totalBytes) {
            return failed(FailureReason.OVER_LIMIT, packet,
                    "assembled " + assembly.receivedBytes + " bytes but declared "
                            + assembly.totalBytes);
        }
        return decode(view, assembly, packet);
    }

    /** Advances the deterministic clock by one default tick and returns timed-out assemblies. */
    public synchronized List<Failed> tick() {
        return advance(ProjectViewLimits.DEFAULT_TICK_MILLIS);
    }

    /** Advances the deterministic clock and returns every assembly that timed out. */
    public synchronized List<Failed> advance(long deltaMillis) {
        if (deltaMillis < 0) {
            throw new IllegalArgumentException("deltaMillis must be >= 0");
        }
        nowMillis += deltaMillis;
        List<Failed> expired = new ArrayList<>();
        for (ConnectionState state : connections.values()) {
            for (ViewState view : state.views.values()) {
                Iterator<Map.Entry<String, Assembly>> iterator = view.assemblies.entrySet().iterator();
                while (iterator.hasNext()) {
                    Assembly assembly = iterator.next().getValue();
                    if (nowMillis - assembly.lastActivityMillis >= timeoutMillis) {
                        iterator.remove();
                        assemblyCount--;
                        expired.add(new Failed(FailureReason.TIMEOUT, assembly.viewId,
                                assembly.snapshotId,
                                "missing " + (assembly.partCount - assembly.receivedParts)
                                        + " of " + assembly.partCount + " parts"));
                    }
                }
            }
        }
        return List.copyOf(expired);
    }

    public synchronized int pendingCount() {
        return assemblyCount;
    }

    public synchronized int activeConnectionCount() {
        return connections.size();
    }

    public synchronized int activeViewCount() {
        return activeViewCount;
    }

    public synchronized boolean hasPending(Object connection, long connectionGeneration,
                                           long viewId) {
        Objects.requireNonNull(connection, "connection");
        ConnectionState state = connections.get(connection);
        if (state == null || state.generation != connectionGeneration) {
            return false;
        }
        ViewState view = state.views.get(viewId);
        return view != null && !view.assemblies.isEmpty();
    }

    private int removeConnectionState(Object connection, ConnectionState state) {
        int removed = 0;
        for (ViewState view : state.views.values()) {
            removed += view.assemblies.size();
            activeViewCount--;
        }
        assemblyCount -= removed;
        connections.remove(connection);
        return removed;
    }

    private void removeViewState(ConnectionState state, ViewState view) {
        assemblyCount -= view.assemblies.size();
        activeViewCount--;
        state.views.remove(view.viewId);
    }

    private void removeAssembly(ViewState view, Assembly assembly) {
        if (view.assemblies.remove(assembly.snapshotId) != null) {
            assemblyCount--;
        }
    }

    private static String validatePart(ProjectViewPart packet, byte[] fragment) {
        if (packet.scope() == null) {
            return "scope is null";
        }
        if (packet.viewId() <= 0 || packet.generation() <= 0) {
            return "invalid view id or generation";
        }
        if (packet.sequence() < 0) {
            return "invalid publication sequence";
        }
        if (packet.catalogRevision() < 0 || packet.inventoryRevision() < 0) {
            return "invalid catalog or inventory revision";
        }
        String snapshotId = packet.snapshotId();
        if (snapshotId == null || snapshotId.isBlank()) {
            return "invalid snapshotId";
        }
        try {
            ProjectViewLimits.requireUuidString(snapshotId, "snapshotId");
        } catch (IllegalArgumentException exception) {
            return exception.getMessage();
        }
        if (fragment == null) {
            return "fragment is null";
        }
        if (packet.partCount() <= 0 || packet.partCount() > ProjectViewLimits.MAX_PART_COUNT) {
            return "partCount " + packet.partCount() + " exceeds the maximum";
        }
        if (packet.totalBytes() <= 0 || packet.totalBytes() > ProjectViewLimits.TOTAL_BYTES) {
            return "totalBytes " + packet.totalBytes() + " exceeds the maximum";
        }
        if (packet.partCount() != ProjectViewLimits.maxPartCount(packet.totalBytes())) {
            return "partCount does not match ceil(totalBytes / PART_BYTES)";
        }
        if (packet.partIndex() < 0 || packet.partIndex() >= packet.partCount()) {
            return "invalid partIndex " + packet.partIndex();
        }
        if (fragment.length <= 0 || fragment.length > ProjectViewLimits.PART_BYTES) {
            return "fragment length " + fragment.length + " is invalid";
        }
        if (fragment.length > packet.totalBytes() - (packet.partCount() - 1)) {
            return "fragment leaves no room for the remaining parts";
        }
        return null;
    }

    private static boolean metadataMismatch(Assembly assembly, ProjectViewPart packet) {
        return !assembly.scope.equals(packet.scope())
                || assembly.generation != packet.generation()
                || assembly.sequence != packet.sequence()
                || assembly.catalogRevision != packet.catalogRevision()
                || assembly.inventoryRevision != packet.inventoryRevision()
                || assembly.partCount != packet.partCount()
                || assembly.totalBytes != packet.totalBytes();
    }

    private Outcome decode(ViewState view, Assembly assembly, ProjectViewPart packet) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(assembly.totalBytes);
        for (byte[] part : assembly.parts) {
            if (part == null) {
                return failed(FailureReason.INCONSISTENT, packet, "missing assembled part");
            }
            payload.writeBytes(part);
        }
        ProjectProgressSnapshot snapshot;
        try {
            snapshot = ProjectProgressCodec.decode(payload.toByteArray());
        } catch (RuntimeException exception) {
            return failed(FailureReason.DECODE_FAILED, packet,
                    "progress payload failed validation: " + exception.getMessage());
        }
        if (!view.scope.isProject()) {
            return failed(FailureReason.INCONSISTENT, packet,
                    "progress snapshots are project-scoped");
        }
        if (!view.scope.projectId().equals(snapshot.projectId())) {
            return failed(FailureReason.INCONSISTENT, packet,
                    "decoded project id " + snapshot.projectId()
                            + " does not match scope " + view.scope.projectId());
        }
        if (snapshot.catalogRevision() != assembly.catalogRevision) {
            return failed(FailureReason.INCONSISTENT, packet,
                    "decoded catalog revision does not match the envelope");
        }
        if (snapshot.inventoryRevision() != assembly.inventoryRevision) {
            return failed(FailureReason.INCONSISTENT, packet,
                    "decoded inventory revision does not match the envelope");
        }
        if (snapshot.publicationSequence() != assembly.sequence) {
            return failed(FailureReason.INCONSISTENT, packet,
                    "decoded publication sequence does not match the envelope");
        }
        if (view.installedSequence >= 0 && assembly.sequence <= view.installedSequence) {
            return failed(FailureReason.STALE, packet,
                    "sequence " + assembly.sequence + " is not newer than installed "
                            + view.installedSequence);
        }
        view.installedSequence = assembly.sequence;
        return new Complete(new AssembledView(view.scope, view.viewId, view.viewGeneration,
                assembly.sequence, assembly.snapshotId, assembly.catalogRevision,
                assembly.inventoryRevision, snapshot));
    }

    private static Failed failed(FailureReason reason, ProjectViewPart packet, String detail) {
        return new Failed(reason, packet.viewId(), packet.snapshotId(), detail);
    }
}
