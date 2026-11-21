package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** GUI-level ownership of the global warehouse view, without requiring a Minecraft screen. */
class ProjectViewGuiLifecycleTest {
    private final Object connection = new Object();
    private final List<CustomPacketPayload> sent = new ArrayList<>();
    private final AtomicLong requestIds = new AtomicLong(1);
    private ProjectViewClient session;
    private MockedStatic<ProjectViewClient> current;

    @BeforeEach
    void setUp() {
        session = new ProjectViewClient(connection, 1, sent::add, requestIds::getAndIncrement, () -> true);
        current = mockStatic(ProjectViewClient.class);
        current.when(ProjectViewClient::current).thenReturn(Optional.of(session));
        ClientProtocolState.onHandshakeResponse(ProtocolVersion.CURRENT, "test", true);
    }

    @AfterEach
    void tearDown() {
        current.close();
        ClientProtocolState.reset();
    }

    @Test
    void managerAndWarehouseEditorReleasesDoNotCloseEachOther() {
        GuiWarehouseManager manager = mock(GuiWarehouseManager.class, CALLS_REAL_METHODS);
        manager.ensureGlobalLease();
        assertEquals(ProjectViewAction.OPEN, lastCommand().action());
        assertEquals(ProjectViewScope.globalWarehouse(), lastCommand().scope());

        GuiWarehouseEditor editor = mock(GuiWarehouseEditor.class, CALLS_REAL_METHODS);
        editor.ensureGlobalLease();
        int beforeEditorClose = sent.size();

        editor.releaseGlobalLease();
        assertEquals(beforeEditorClose, sent.size(),
                "closing the child editor must not CLOSE the manager's global view");
        assertTrue(session.globalStore().isPresent());

        manager.releaseGlobalLease();
        assertTrue(session.globalStore().isEmpty());
        assertEquals(ProjectViewAction.CLOSE, lastCommand().action());
        assertEquals(ProjectViewScope.globalWarehouse(), lastCommand().scope());
    }

    @Test
    void managerListReadIsIdempotentAcrossResizeButExplicitRefreshReadsAgain() {
        GuiWarehouseManager manager = mock(GuiWarehouseManager.class, CALLS_REAL_METHODS);

        manager.ensureWarehouseListRead(false);
        assertEquals(1, listRequests(), "首次打开应读取一次列表");
        assertEquals(ProjectViewAction.OPEN, firstCommand().action());
        assertEquals(ProjectViewAction.AREA_CONFIG, lastCommand().action());

        // initGui 再次执行（resize/角色重排）不得重复发 LIST
        manager.ensureWarehouseListRead(false);
        assertEquals(1, listRequests());

        manager.requestRefresh();
        assertEquals(2, listRequests(), "明确刷新必须重新读取");

        manager.releaseGlobalLease();
        manager.ensureWarehouseListRead(false);
        assertEquals(3, listRequests(), "全局视图真正重开后应读取新视图");

        manager.releaseGlobalLease();
        assertTrue(session.globalStore().isEmpty());
    }

    @Test
    void popupListReadIsNotRepeatedWhileLoadingButNewViewReadsAgain() {
        GuiWarehouseRefPopup popup = mock(GuiWarehouseRefPopup.class, CALLS_REAL_METHODS);

        popup.requestWarehouseList();
        popup.requestWarehouseList();
        assertEquals(1, listRequests(), "loading 期间重入 initGui 不得重复发 LIST");

        popup.releaseGlobalLease();
        popup.requestWarehouseList();
        assertEquals(2, listRequests(), "新全局视图应重新读取");

        popup.releaseGlobalLease();
        assertTrue(session.globalStore().isEmpty());
    }

    @Test
    void freshPopupOpensGlobalBeforeItsListRequest() {
        GuiWarehouseRefPopup popup = mock(GuiWarehouseRefPopup.class, CALLS_REAL_METHODS);
        popup.requestWarehouseList();

        List<ProjectViewCommandC2SPacket> commands = sent.stream()
                .map(ProjectViewCommandC2SPacket.class::cast).toList();
        assertEquals(2, commands.size());
        assertEquals(ProjectViewAction.OPEN, commands.get(0).action());
        assertEquals(ProjectViewScope.globalWarehouse(), commands.get(0).scope());
        assertEquals(ProjectViewAction.AREA_CONFIG, commands.get(1).action());
        assertEquals(ProjectViewScope.globalWarehouse(), commands.get(1).scope());

        popup.releaseGlobalLease();
        assertTrue(session.globalStore().isEmpty());
        assertEquals(ProjectViewAction.CLOSE, lastCommand().action());
    }

    private ProjectViewCommandC2SPacket lastCommand() {
        return (ProjectViewCommandC2SPacket) sent.getLast();
    }

    private ProjectViewCommandC2SPacket firstCommand() {
        return (ProjectViewCommandC2SPacket) sent.getFirst();
    }

    private long listRequests() {
        return sent.stream()
                .map(ProjectViewCommandC2SPacket.class::cast)
                .filter(command -> command.action() == ProjectViewAction.AREA_CONFIG
                        && "LIST_WAREHOUSES".equals(command.areaAction()))
                .count();
    }
}
