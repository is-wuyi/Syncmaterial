package net.syncmaterial.syncmaterial.client.session;

import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
    /** 原连接 PacketSender 的窄发送端口；JOIN 时捕获，发送前复核会话仍开放。 */
    private final Consumer<CustomPacketPayload> transport;
    private final ClientHandshakeState handshake;
    private final ProjectViewClient projectView;
    private final ClientProjectCoordinator projectCoordinator;
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

    ClientConnectionSession(Object connection, long token,
            Consumer<CustomPacketPayload> transport) {
        this.connection = Objects.requireNonNull(connection, "connection");
        if (token <= 0) throw new IllegalArgumentException("connection token must be positive");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.token = token;
        this.handshake = new ClientHandshakeState();
        this.teamManagement = new TeamManagementClient.SessionState();
        this.teamManagement.bindTransport(this::sendPacket);
        this.inventory = new InventoryWatcher.Context(this::sendPacket);
        this.pickup = new PickupModeState();
        this.rendering = new StagingAreaRenderer.State();
        this.selector = new StagingAreaSelector();
        this.editorResponses = new EditorResponseRouter();
        // ProjectViewClient 的发送端口已接在本会话的 sendPacket 上；主线程如需调整
        // 构造接线，保持指向 sendPacket 即可（绝不能回到全局 ClientPlayNetworking.send）
        this.projectView = new ProjectViewClient(connection, token, this::sendPacket,
                TeamManagementClient::allocateProjectViewRequestId,
                () -> this.handshake.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS));
        this.projectCoordinator = new ClientProjectCoordinator(this);
    }

    public Object connection() { return connection; }
    public long token() { return token; }
    public ClientHandshakeState handshake() { return handshake; }
    public ProjectViewClient projectView() { return projectView; }
    public ClientProjectCoordinator projectCoordinator() { return projectCoordinator; }
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

    /**
     * 原连接发送端口：仅当会话仍开放时通过 JOIN 捕获的 transport 发出；
     * 绝不回查 Minecraft 当前连接，旧会话的延迟发送不可能落到新连接。
     * 客户端模型/SyncMaterialClient 统一调用本方法发包，不要再走
     * ClientPlayNetworking.send 全局入口。
     */
    public boolean sendPacket(CustomPacketPayload payload) {
        if (payload == null || !isOpen()) return false;
        this.transport.accept(payload);
        return true;
    }

    public synchronized boolean isOpen() { return !closed; }

    public boolean matches(Object candidate, long candidateToken) {
        return this.connection == candidate && this.token == candidateToken && this.isOpen();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        projectCoordinator.close();
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
