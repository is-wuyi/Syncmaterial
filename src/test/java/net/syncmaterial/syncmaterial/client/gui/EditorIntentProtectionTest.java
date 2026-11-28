package net.syncmaterial.syncmaterial.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket;
import net.syncmaterial.syncmaterial.network.ProjectViewPackets;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * 编辑意图与权威刷新的确定性回归（beta.61 合同 2/3）：
 *
 * <ul>
 *   <li>合法输入登记即产生版本；去抖载荷携带该版本与登记时刻的不可变目标/坐标，
 *       发送时不再重读选中 Box，也不重新制造版本；</li>
 *   <li>权威配置合并按目标保护：去抖静默期、SAVING、FAILED、UNCONFIRMED 期间
 *       收到 LIST 都不得覆盖正在编辑的目标；未修改目标及时更新；</li>
 *   <li>上下文失效先 discard 不 flush；被丢弃的载荷标记 UNCONFIRMED 而不是伪装已保存；</li>
 *   <li>writePrepared 不制造版本；旧 ACK 不保存新版本。</li>
 * </ul>
 */
class EditorIntentProtectionTest {
    private static final ProjectViewScope PROJECT = ProjectViewScope.project("project");
    private static final ProjectViewScope OTHER = ProjectViewScope.project("other");

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
        EditorResponseRouter.clear();
        current.close();
        ClientProtocolState.reset();
        net.syncmaterial.syncmaterial.client.session.ClientSessionManager.resetForTesting();
    }

    // ========== 合并保护：去抖静默期/SAVING/FAILED/UNCONFIRMED 不得覆盖未提交输入 ==========

    @Test
    void mergedConfigurationKeepsPendingTargetAndUpdatesOthers() {
        GuiStagingAreaEditorNormal editor = editorWithBoxes();
        EditorSaveTracker tracker = trackerOf(editor);

        // 模拟合法输入：登记版本（PENDING）
        tracker.edit();
        setPendingTarget(editor, 1);

        List<AreaDefinition> definitions = List.of(
                area(1, "区域A", 100, 100, 100, 110, 110, 110),   // 正在编辑的目标被权威刷新
                area(2, "区域B", 200, 200, 200, 210, 210, 210));  // 未修改目标

        doNothing().when(editor).initGui();
        editor.applyProjectViewConfiguration("LIST", definitions);

        AreaSelection selection = selectionOf(editor);
        Box boxA = selection.getSubRegionBox("区域A");
        assertEquals(0, boxA.getPos1().getX(), "去抖静默期内正在编辑的目标不得被权威值覆盖");
        Box boxB = selection.getSubRegionBox("区域B");
        assertEquals(200, boxB.getPos1().getX(), "未修改目标必须及时更新权威坐标");
        assertEquals(EditorSaveTracker.State.PENDING, tracker.state(), "合并不得抹掉保存状态");
        assertTrue(booleanField(editor, "needsListRefresh"), "有未落定意图时列表重建必须推迟");
        verify(editor, never()).initGui();
    }

    @Test
    void mergedConfigurationInSavingOrUnconfirmedStateStillProtectsTarget() {
        GuiStagingAreaEditorNormal editor = editorWithBoxes();
        EditorSaveTracker tracker = trackerOf(editor);

        tracker.edit();
        setPendingTarget(editor, 1);
        // 直接把 tracker 推到 SAVING（模拟已发送未确认）
        tracker.sent(tracker.version(), 99);

        List<AreaDefinition> definitions = List.of(area(1, "区域A", 500, 0, 0, 510, 10, 10));
        doNothing().when(editor).initGui();
        editor.applyProjectViewConfiguration("LIST", definitions);

        assertEquals(0, selectionOf(editor).getSubRegionBox("区域A").getPos1().getX(),
                "SAVING 状态的目标保留本地值，等回执协调");
        assertEquals(EditorSaveTracker.State.SAVING, tracker.state());

        // 回执失败/超时 → UNCONFIRMED：本地值仍受保护
        tracker.unconfirmed(tracker.version());
        editor.applyProjectViewConfiguration("LIST", List.of(area(1, "区域A", 600, 0, 0, 610, 10, 10)));
        assertEquals(0, selectionOf(editor).getSubRegionBox("区域A").getPos1().getX(),
                "UNCONFIRMED 的本地输入不得被权威值覆盖");
    }

    @Test
    void idleMergeUpdatesAllTargetsAndRemovesDeletedOnes() {
        GuiStagingAreaEditorNormal editor = editorWithBoxes();
        EditorSaveTracker tracker = trackerOf(editor);
        assertEquals(EditorSaveTracker.State.IDLE, tracker.state());

        doNothing().when(editor).initGui();
        editor.applyProjectViewConfiguration("LIST", List.of(area(1, "区域A", 100, 0, 0, 110, 10, 10)));

        AreaSelection selection = selectionOf(editor);
        assertEquals(100, selection.getSubRegionBox("区域A").getPos1().getX(),
                "无未落定意图时权威值即时更新（含坐标）");
        assertNull(selection.getSubRegionBox("区域B"), "服务端已删除的目标必须从工作副本移除");
        verify(editor).initGui();
    }

    // ========== writePrepared：不制造版本；丢弃标记 UNCONFIRMED ==========

    @Test
    void writePreparedUsesTheRegisteredVersionAndNeverCreatesOne() {
        session.openProject("project", InterestKind.AREA_CONFIG.bit());
        EditorSaveTracker tracker = new EditorSaveTracker();
        long version = tracker.edit();

        long request = ProjectViewEditorSupport.writePrepared(PROJECT, 5, "UPDATE",
                Optional.of(data(10, 20)), legacyPacket(), tracker, version);

        assertTrue(request > 0);
        assertEquals(version, tracker.version(), "发送时不得重新制造版本");
        assertEquals(EditorSaveTracker.State.SAVING, tracker.state());
        assertEquals(request, tracker.requestId());
    }

    @Test
    void staleVersionCannotBeSentOrConfirmedAsLatest() {
        session.openProject("project", InterestKind.AREA_CONFIG.bit());
        EditorSaveTracker tracker = new EditorSaveTracker();
        tracker.edit();
        int writesBefore = areaConfigWrites();

        // 新输入取代旧意图
        long newVersion = tracker.edit();

        assertFalse(ProjectViewEditorSupport.intentValidity(
                        ProjectViewEditorSupport.captureFence(PROJECT), newVersion - 1, tracker).getAsBoolean(),
                "被取代的旧版本载荷必须失效");
        assertEquals(EditorSaveTracker.State.PENDING, tracker.state(),
                "旧载荷失效不得污染新意图的状态");

        // writePrepared 拒绝被取代的版本：不发旧数据，也不污染新意图状态
        long request = ProjectViewEditorSupport.writePrepared(PROJECT, 5, "UPDATE",
                Optional.of(data(10, 20)), legacyPacket(), tracker, newVersion - 1);
        assertEquals(-1, request, "被取代的载荷必须在发送原语处被拒绝");
        assertEquals(writesBefore, areaConfigWrites(), "旧载荷不得上线路覆盖新数据");
        assertEquals(newVersion, tracker.version());
        assertEquals(EditorSaveTracker.State.PENDING, tracker.state(),
                "旧 ACK/旧载荷不得把新版本标成已发送或已保存");
    }

    @Test
    void flushDropsInvalidIntentInsteadOfSending() {
        session.openProject("project", InterestKind.AREA_CONFIG.bit());
        GuiStagingAreaEditorNormal editor = editorWithBoxes();
        EditorSaveTracker tracker = trackerOf(editor);
        UpdateDebouncer debouncer = debouncerOf(editor);

        editor.scheduleCoordinateUpdate(fi.dy.masa.litematica.util.PositionUtils.Corner.CORNER_1);
        int writesBefore = areaConfigWrites();

        // 上下文失效（切项目）
        session.openProject("other", InterestKind.AREA_CONFIG.bit());
        ProjectViewCommandC2SPacket switchCommand = lastCommand();
        session.onPacket(connection, 1, ProjectViewPackets.opened(OTHER, switchCommand.requestId(),
                switchCommand.viewId(), switchCommand.generation(), UUID.randomUUID()));

        debouncer.flushNow();

        assertEquals(areaConfigWrites(), writesBefore, "失效载荷必须先 discard，不得 flush 发出");
        assertEquals(EditorSaveTracker.State.UNCONFIRMED, tracker.state(),
                "被丢弃的载荷标记未确认，不伪装已保存");
        assertNull(field(editor, "pendingCoordinateServerId"), "失效丢弃后不得继续保护旧坐标");
    }

    @Test
    void debounceFireKeepsTargetThroughSavingWindowAndClearsOnSave() {
        session.openProject("project", InterestKind.AREA_CONFIG.bit());
        GuiStagingAreaEditorNormal editor = editorWithBoxes();
        EditorSaveTracker tracker = trackerOf(editor);
        editor.scheduleCoordinateUpdate(fi.dy.masa.litematica.util.PositionUtils.Corner.CORNER_1);
        debouncerOf(editor).flushNow();
        assertEquals(EditorSaveTracker.State.SAVING, tracker.state());

        doNothing().when(editor).initGui();
        editor.applyProjectViewConfiguration("LIST", List.of(area(1, "区域A", 500, 0, 0, 510, 10, 10)));
        assertEquals(0, selectionOf(editor).getSubRegionBox("区域A").getPos1().getX(),
                "SAVING 窗口内不得被权威旧值回滚");

        tracker.complete(tracker.requestId(), true);
        editor.applyProjectViewConfiguration("LIST", List.of(area(1, "区域A", 500, 0, 0, 510, 10, 10)));
        assertEquals(500, selectionOf(editor).getSubRegionBox("区域A").getPos1().getX(),
                "SAVED 后权威值必须生效");
        assertNull(field(editor, "pendingCoordinateServerId"), "正常完成后必须清空目标字段");
    }

    @Test
    void flushSendsCapturedPayloadNotCurrentBoxState() {
        session.openProject("project", InterestKind.AREA_CONFIG.bit());
        GuiStagingAreaEditorNormal editor = editorWithBoxes();
        EditorSaveTracker tracker = trackerOf(editor);
        UpdateDebouncer debouncer = debouncerOf(editor);

        // 登记时坐标是 (0,0,0)-(1,1,1)；登记后有人改了工作副本（例如按钮动作）
        editor.scheduleCoordinateUpdate(fi.dy.masa.litematica.util.PositionUtils.Corner.CORNER_1);
        selectionOf(editor).getSubRegionBox("区域A").setPos1(new BlockPos(999, 0, 0));

        int writesBefore = areaConfigWrites();
        debouncer.flushNow();

        assertEquals(areaConfigWrites(), writesBefore + 1);
        ProjectViewCommandC2SPacket command = lastCommand();
        var areaData = command.areaData().orElseThrow();
        assertEquals(0, areaData.x1(), "载荷必须携带登记时刻的坐标快照，不得发送时重读 Box");
        assertEquals("区域A", areaData.name());
        assertEquals(EditorSaveTracker.State.SAVING, tracker.state());
    }

    // ========== 辅助 ==========

    private GuiStagingAreaEditorNormal editorWithBoxes() {
        GuiStagingAreaEditorNormal editor = mock(GuiStagingAreaEditorNormal.class, CALLS_REAL_METHODS);
        AreaSelection selection = new AreaSelection();
        selection.addSubRegionBox(new Box(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), "区域A"), true);
        selection.setServerId("区域A", 1);
        selection.addSubRegionBox(new Box(new BlockPos(20, 0, 0), new BlockPos(21, 1, 1), "区域B"), true);
        selection.setServerId("区域B", 2);
        selection.setSelectedSubRegionBox("区域A");
        setField(editor, "selection", selection);
        setField(editor, "schematicId", "project");
        setField(editor, "saveTracker", new EditorSaveTracker());
        setField(editor, "coordinateDebouncer", new UpdateDebouncer(10));
        setField(editor, "areaWorlds", new java.util.HashMap<String, String>());
        return editor;
    }

    private static AreaDefinition area(int id, String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new AreaDefinition(id, "project", AreaKind.STAGING_AREA, name,
                "minecraft:overworld", new AreaBounds(x1, y1, z1, x2, y2, z2), id + 10, 0);
    }

    private static StagingAreaConfigC2SPacket.AreaData data(int x1, int y1) {
        return new StagingAreaConfigC2SPacket.AreaData("区域A", x1, y1, 0, x1 + 5, y1 + 5, 5,
                Optional.empty());
    }

    private static StagingAreaConfigC2SPacket legacyPacket() {
        return new StagingAreaConfigC2SPacket("project", "UPDATE", 5, Optional.of(data(10, 20)));
    }

    private int areaConfigWrites() {
        return (int) sent.stream()
                .filter(ProjectViewCommandC2SPacket.class::isInstance)
                .map(ProjectViewCommandC2SPacket.class::cast)
                .filter(command -> command.areaAction() != null)
                .count();
    }

    private ProjectViewCommandC2SPacket lastCommand() {
        return (ProjectViewCommandC2SPacket) sent.getLast();
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

    @SuppressWarnings("unchecked")
    private AreaSelection selectionOf(GuiStagingAreaEditorNormal editor) {
        return (AreaSelection) field(editor, "selection");
    }

    private EditorSaveTracker trackerOf(GuiStagingAreaEditorNormal editor) {
        return (EditorSaveTracker) field(editor, "saveTracker");
    }

    private UpdateDebouncer debouncerOf(GuiStagingAreaEditorNormal editor) {
        return (UpdateDebouncer) field(editor, "coordinateDebouncer");
    }

    private void setPendingTarget(GuiStagingAreaEditorNormal editor, int serverId) {
        setField(editor, "pendingCoordinateServerId", serverId);
    }

    private boolean booleanField(Object target, String name) {
        return (boolean) field(target, name);
    }

    private Object field(Object target, String name) {
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
