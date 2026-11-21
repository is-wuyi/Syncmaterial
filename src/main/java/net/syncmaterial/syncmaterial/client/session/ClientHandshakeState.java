package net.syncmaterial.syncmaterial.client.session;

import net.syncmaterial.syncmaterial.network.ProtocolVersion;

/** Session-owned handshake; each response replaces one immutable value. */
public final class ClientHandshakeState {
    public enum Status { PENDING, ACCEPTED, REJECTED }

    public record Snapshot(Status status, int serverProtocolVersion, String serverModVersion) {
        public static final Snapshot PENDING = new Snapshot(Status.PENDING, 0, "");

        public Snapshot {
            if (status == null) throw new IllegalArgumentException("status is required");
            if (serverProtocolVersion < 0) throw new IllegalArgumentException("negative protocol version");
            serverModVersion = serverModVersion == null ? "" : serverModVersion;
        }
    }

    private volatile Snapshot snapshot = Snapshot.PENDING;

    public Snapshot snapshot() { return snapshot; }
    public Status status() { return snapshot.status(); }
    public int serverProtocolVersion() { return snapshot.serverProtocolVersion(); }
    public String serverModVersion() { return snapshot.serverModVersion(); }

    public void response(int protocolVersion, String modVersion, boolean accepted) {
        this.snapshot = new Snapshot(accepted ? Status.ACCEPTED : Status.REJECTED,
                protocolVersion, modVersion);
    }

    public void reset() { this.snapshot = Snapshot.PENDING; }

    public boolean isUsable() { return snapshot.status() == Status.ACCEPTED; }

    public boolean serverSupports(int requiredVersion) {
        Snapshot current = this.snapshot;
        return current.status() == Status.ACCEPTED
                && ProtocolVersion.atLeast(current.serverProtocolVersion(), requiredVersion);
    }

    public boolean isServerNewer() {
        Snapshot current = this.snapshot;
        return current.status() == Status.ACCEPTED
                && ProtocolVersion.isPeerNewer(current.serverProtocolVersion());
    }
}
