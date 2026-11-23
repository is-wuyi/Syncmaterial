package net.syncmaterial.syncmaterial.client.gui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListWarehouses;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket;
import net.syncmaterial.syncmaterial.network.ProjectViewPackets;
import net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
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
        net.syncmaterial.syncmaterial.client.session.ClientSessionManager.resetForTesting();
        net.syncmaterial.syncmaterial.client.session.ClientSessionManager.join(new Object());
        session = new ProjectViewClient(connection, 1, sent::add, requestIds::getAndIncrement, () -> true);
        current = mockStatic(ProjectViewClient.class);
        current.when(ProjectViewClient::current).thenReturn(Optional.of(session));
        ClientProtocolState.onHandshakeResponse(ProtocolVersion.CURRENT, "test", true);
    }

    @AfterEach
    void tearDown() {
        current.close();
        ClientProtocolState.reset();
        net.syncmaterial.syncmaterial.client.session.ClientSessionManager.resetForTesting();
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

    // ========== beta.61 合同 5：被遮挡的 manager 不错过后台配置更新 ==========

    /** 建立 global 视图并推送一份 LIST_WAREHOUSES 权威配置（模拟后台到达）。 */
    private void openGlobalViewAndPushConfig(int warehouseId, String name) {
        GuiWarehouseManager opener = mock(GuiWarehouseManager.class, CALLS_REAL_METHODS);
        opener.ensureWarehouseListRead(false);
        ProjectViewCommandC2SPacket open = lastCommand();
        session.onPacket(connection, 1, ProjectViewPackets.opened(
                ProjectViewScope.globalWarehouse(), open.requestId(),
                open.viewId(), open.generation(), UUID.randomUUID()));
        var config = new ProjectViewS2CPacket.AreaConfigData("LIST_WAREHOUSES", List.of(
                new AreaDefinition(warehouseId, null, AreaKind.WAREHOUSE, name,
                        "minecraft:overworld", new AreaBounds(0, 64, 0, 5, 70, 5), 1, 0)));
        session.onPacket(connection, 1, ProjectViewPackets.configuration(
                ProjectViewScope.globalWarehouse(), 0, open.viewId(), open.generation(),
                1, ProjectViewAction.AREA_CONFIG, true, "", config));
    }

    @Test
    void managerOccludedThenRestored_replaysLatestStoreWithoutCurrentScreenGating() {
        openGlobalViewAndPushConfig(11, "后台新建的仓库");

        // manager 被子界面遮挡期间收到的配置不依赖 currentScreen；
        // 恢复（initGui）时从最新 store 补齐
        GuiWarehouseManager manager = mock(GuiWarehouseManager.class, CALLS_REAL_METHODS);
        setField(manager, "warehouses", new ArrayList<>());
        // 预置列表 widget（GuiListBase.getListWidget 会在无界面环境懒加载并触碰 Minecraft）
        WidgetListWarehouses widget = mock(WidgetListWarehouses.class, CALLS_REAL_METHODS);
        doNothing().when(widget).refreshEntries();
        setField(manager, "widget", widget);
        manager.applyLatestGlobalStoreConfiguration();

        assertEquals(1, manager.getWarehouses().size());
        assertEquals("后台新建的仓库", manager.getWarehouses().get(0).name());
        assertEquals(11, manager.getWarehouses().get(0).warehouseId());
    }

    @Test
    void warehouseEditorRefusesToWriteDeletedTarget() {
        openGlobalViewAndPushConfig(11, "还在的仓库");
        long writesBefore = updateWarehouseWrites();

        // 目标 99 不在权威全局目录里：编辑器不得把它写回（精确失效，不降级）
        GuiWarehouseEditor editor = editorWithTarget(99);
        editor.renameForTest("改名尝试");

        assertEquals(writesBefore, updateWarehouseWrites(),
                "已删除目标的 UPDATE_WAREHOUSE 必须被拦截");
        assertEquals("改名尝试", readField(editor, "name"), "本地值保留，等待目标重新出现或关闭");
    }

    @Test
    void warehouseEditorWritesExistingTarget() {
        openGlobalViewAndPushConfig(11, "还在的仓库");
        long writesBefore = updateWarehouseWrites();

        GuiWarehouseEditor editor = editorWithTarget(11);
        editor.renameForTest("改名后的仓库");

        assertEquals(writesBefore + 1, updateWarehouseWrites(), "目标仍在目录里时照常发送");
    }

    private GuiWarehouseEditor editorWithTarget(int warehouseId) {
        GuiWarehouseEditor editor = mock(GuiWarehouseEditor.class, CALLS_REAL_METHODS);
        setField(editor, "warehouseId", warehouseId);
        setField(editor, "name", "原名");
        setField(editor, "world", "minecraft:overworld");
        setField(editor, "pos1", new net.minecraft.core.BlockPos(0, 64, 0));
        setField(editor, "pos2", new net.minecraft.core.BlockPos(5, 70, 5));
        setField(editor, "manager", mock(GuiWarehouseManager.class, CALLS_REAL_METHODS));
        setField(editor, "saveTracker", new EditorSaveTracker());
        setField(editor, "coordinateDebouncer", new UpdateDebouncer(10));
        return editor;
    }

    private long updateWarehouseWrites() {
        return sent.stream()
                .map(ProjectViewCommandC2SPacket.class::cast)
                .filter(command -> command.action() == ProjectViewAction.AREA_CONFIG
                        && "UPDATE_WAREHOUSE".equals(command.areaAction()))
                .count();
    }

    private static void setField(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError("missing field " + name);
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError("missing field " + name);
    }
}
