package net.syncmaterial.syncmaterial.client.session;

import java.util.Objects;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.client.DimensionChangeTracker;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.PickupModeState;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.TeamManagementClient;
import net.syncmaterial.syncmaterial.client.gui.EditorResponseRouter;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;

/** Composition root for state that belongs to exactly one client connection. */
public final class ClientConnectionSession implements AutoCloseable {
    private final Object connection;
    private final long token;
    private final ClientHandshakeState handshake;
    private final ProjectViewClient projectView;
    private final TeamManagementClient.SessionState teamManagement;
    private final InventoryWatcher.Context inventory;
    private final PickupModeState pickup;
    private final StagingAreaRenderer.State rendering;
    private final StagingAreaSelector selector;
    private final EditorResponseRouter editorResponses;
    private final DimensionChangeTracker dimensions = new DimensionChangeTracker();
    private SyncMaterialList materialList;
    private volatile long catalogRevision = -1;
    private volatile boolean hudVisible = true;
    private int pickupRecomputeTicks;
    private boolean closed;

    ClientConnectionSession(Object connection, long token) {
        this.connection = Objects.requireNonNull(connection, "connection");
        if (token <= 0) throw new IllegalArgumentException("connection token must be positive");
        this.token = token;
        this.handshake = new ClientHandshakeState();
        this.teamManagement = new TeamManagementClient.SessionState();
        this.inventory = new InventoryWatcher.Context();
        this.pickup = new PickupModeState();
        this.rendering = new StagingAreaRenderer.State();
        this.selector = new StagingAreaSelector();
        this.editorResponses = new EditorResponseRouter();
        this.projectView = new ProjectViewClient(connection, token,
                payload -> {
                    if (this.isOpen()) ClientPlayNetworking.send(payload);
                }, TeamManagementClient::allocateProjectViewRequestId,
                () -> this.handshake.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS));
    }

    public Object connection() { return connection; }
    public long token() { return token; }
    public ClientHandshakeState handshake() { return handshake; }
    public ProjectViewClient projectView() { return projectView; }
    public TeamManagementClient.SessionState teamManagement() { return teamManagement; }
    public InventoryWatcher.Context inventory() { return inventory; }
    public PickupModeState pickup() { return pickup; }
    public StagingAreaRenderer.State rendering() { return rendering; }
    public StagingAreaSelector selector() { return selector; }
    public EditorResponseRouter editorResponses() { return editorResponses; }
    public DimensionChangeTracker dimensions() { return dimensions; }
    public SyncMaterialList materialList() { return materialList; }
    public void materialList(SyncMaterialList value) { this.materialList = value; }
    public long catalogRevision() { return catalogRevision; }
    public void catalogRevision(long value) { this.catalogRevision = value; }
    public boolean hudVisible() { return hudVisible; }
    public void hudVisible(boolean value) { this.hudVisible = value; }
    public int nextPickupTick() { return ++this.pickupRecomputeTicks; }

    public synchronized boolean isOpen() { return !closed; }

    public boolean matches(Object candidate, long candidateToken) {
        return this.connection == candidate && this.token == candidateToken && this.isOpen();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        projectView.close();
        editorResponses.clearInstance();
        selector.reset();
        rendering.clear();
        inventory.clear();
        pickup.clear();
        teamManagement.clear();
        if (materialList != null) materialList.clearBindings();
        materialList = null;
        catalogRevision = -1;
        dimensions.reset();
    }
}
