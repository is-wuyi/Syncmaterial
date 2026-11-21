package net.syncmaterial.syncmaterial.client.gui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket;
import net.syncmaterial.syncmaterial.network.ProjectViewPackets;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Delayed editor intents are fenced to the exact view generation that accepted them:
 * reconnect, project switch and deletion must discard them instead of writing into the
 * next view. The normal close flush still sends while that view is current.
 */
class ProjectViewEditorFenceTest {
    private static final ProjectViewScope PROJECT = ProjectViewScope.project("project");
    private static final ProjectViewScope OTHER = ProjectViewScope.project("other");

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
        EditorResponseRouter.clear();
        current.close();
        ClientProtocolState.reset();
    }

    @Test
    void currentViewStillFlushesPendingIntentOnClose() {
        session.openProject("project", InterestKind.AREA_CONFIG.bit());
        ProjectViewEditorSupport.EditFence fence = ProjectViewEditorSupport.captureFence(PROJECT);
        AtomicInteger writes = new AtomicInteger();

        UpdateDebouncer debouncer = new UpdateDebouncer(20);
        debouncer.schedule(writes::incrementAndGet, () -> ProjectViewEditorSupport.isFenceCurrent(fence));
        debouncer.flushNow();

        assertEquals(1, writes.get(), "正常关界面补发必须保留");
    }

    @Test
    void projectSwitchDropsDelayedIntent() {
        session.openProject("project", InterestKind.AREA_CONFIG.bit());
        session.openProject("other", InterestKind.AREA_CONFIG.bit());
        ProjectViewCommandC2SPacket switchCommand = lastCommand();
        ProjectViewEditorSupport.EditFence fence = ProjectViewEditorSupport.captureFence(PROJECT);
        assertTrue(ProjectViewEditorSupport.isFenceCurrent(fence));

        session.onPacket(connection, 1, ProjectViewPackets.opened(OTHER, switchCommand.requestId(),
                switchCommand.viewId(), switchCommand.generation(), UUID.randomUUID()));
        assertFalse(ProjectViewEditorSupport.isFenceCurrent(fence));

        AtomicInteger writes = new AtomicInteger();
        UpdateDebouncer debouncer = new UpdateDebouncer(1);
        debouncer.schedule(writes::incrementAndGet, () -> ProjectViewEditorSupport.isFenceCurrent(fence));
        debouncer.tick();
        debouncer.flushNow();
        assertEquals(0, writes.get(), "切项目后的旧意图不得发到新视图");
    }

    @Test
    void deletedViewDropsDelayedIntent() {
        session.openProject("project", InterestKind.AREA_CONFIG.bit());
        ProjectViewCommandC2SPacket open = lastCommand();
        ProjectViewEditorSupport.EditFence fence = ProjectViewEditorSupport.captureFence(PROJECT);

        session.onPacket(connection, 1, ProjectViewPackets.deleted(PROJECT,
                open.viewId(), open.generation()));
        assertFalse(ProjectViewEditorSupport.isFenceCurrent(fence));

        AtomicInteger writes = new AtomicInteger();
        UpdateDebouncer debouncer = new UpdateDebouncer(20);
        debouncer.schedule(writes::incrementAndGet, () -> ProjectViewEditorSupport.isFenceCurrent(fence));
        debouncer.flushNow();
        assertEquals(0, writes.get(), "删除视图后的延迟意图必须丢弃");
    }

    @Test
    void oldSessionCannotReuseItsViewToken() {
        session.openProject("project", InterestKind.AREA_CONFIG.bit());
        ProjectViewEditorSupport.EditFence fence = ProjectViewEditorSupport.captureFence(PROJECT);
        assertTrue(ProjectViewEditorSupport.isFenceCurrent(fence));

        ProjectViewClient replacement = new ProjectViewClient(connection, 2, sent::add,
                requestIds::getAndIncrement, () -> true);
        current.when(ProjectViewClient::current).thenReturn(Optional.of(replacement));

        assertFalse(ProjectViewEditorSupport.isFenceCurrent(fence),
                "view id 会在新连接重置，旧会话的令牌必须失效");
    }

    @Test
    void stagingEditorScheduledWriteUsesTheViewFence() {
        session.openProject("project", InterestKind.AREA_CONFIG.bit());
        GuiStagingAreaEditorNormal editor = mock(GuiStagingAreaEditorNormal.class, CALLS_REAL_METHODS);
        UpdateDebouncer debouncer = new UpdateDebouncer(20);
        setField(editor, "schematicId", "project");
        setField(editor, "coordinateDebouncer", debouncer);
        doNothing().when(editor).sendCoordinateUpdate(any());

        editor.scheduleCoordinateUpdate(fi.dy.masa.litematica.util.PositionUtils.Corner.CORNER_1);
        debouncer.flushNow();
        verify(editor, times(1)).sendCoordinateUpdate(
                fi.dy.masa.litematica.util.PositionUtils.Corner.CORNER_1);
        clearInvocations(editor);

        session.openProject("other", InterestKind.AREA_CONFIG.bit());
        ProjectViewCommandC2SPacket switchCommand = lastCommand();
        session.onPacket(connection, 1, ProjectViewPackets.opened(OTHER, switchCommand.requestId(),
                switchCommand.viewId(), switchCommand.generation(), UUID.randomUUID()));

        editor.scheduleCoordinateUpdate(fi.dy.masa.litematica.util.PositionUtils.Corner.CORNER_1);
        debouncer.tick();
        debouncer.flushNow();
        verify(editor, never()).sendCoordinateUpdate(
                fi.dy.masa.litematica.util.PositionUtils.Corner.CORNER_1);
    }

    @Test
    void deletingAreaDiscardsPendingCoordinateIntent() {
        session.openProject("project", InterestKind.AREA_CONFIG.bit());
        GuiStagingAreaEditorNormal editor = mock(GuiStagingAreaEditorNormal.class, CALLS_REAL_METHODS);
        UpdateDebouncer debouncer = new UpdateDebouncer(20);
        AreaSelection selection = new AreaSelection();
        selection.addSubRegionBox(new Box(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), "area"), true);
        selection.setServerId("area", 5);
        setField(editor, "schematicId", "project");
        setField(editor, "coordinateDebouncer", debouncer);
        setField(editor, "selection", selection);
        setField(editor, "saveTracker", new EditorSaveTracker());
        doNothing().when(editor).initGui();

        AtomicInteger delayed = new AtomicInteger();
        debouncer.schedule(delayed::incrementAndGet);
        editor.deleteArea(0);

        assertFalse(debouncer.hasPending(), "删除区域必须丢弃待发送的坐标意图");
        debouncer.flushNow();
        assertEquals(0, delayed.get());
        assertTrue(sent.stream().map(ProjectViewCommandC2SPacket.class::cast)
                .anyMatch(command -> "DELETE".equals(command.areaAction())),
                "删除本身仍必须发出");
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
            } catch (IllegalAccessException exception) {
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("missing field " + name);
    }
}
