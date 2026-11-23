package net.syncmaterial.syncmaterial.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import fi.dy.masa.malilib.gui.GuiBase;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class ClientProjectCoordinatorTest {
    private final List<CustomPacketPayload> sent = new ArrayList<>();
    private MockedStatic<Minecraft> minecraft;
    private MockedStatic<fi.dy.masa.malilib.util.InfoUtils> info;
    private MockedConstruction<GuiMaterialList> screens;
    private ClientConnectionSession session;
    private ClientProjectCoordinator coordinator;

    @BeforeEach
    void setUp() {
        this.minecraft = mockStatic(Minecraft.class);
        this.minecraft.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
        this.info = mockStatic(fi.dy.masa.malilib.util.InfoUtils.class);
        this.screens = mockConstruction(GuiMaterialList.class, (mock, context) -> {
            when(mock.getSchematicId()).thenAnswer(ignored ->
                    this.coordinator.materialList().getSchematicId());
            when(mock.getMaterialList()).thenReturn(this.coordinator.materialList());
        });
        this.session = ClientSessionManager.join(new Object(), this.sent::add);
        this.coordinator = this.session.projectCoordinator();
    }

    @AfterEach
    void tearDown() {
        if (this.coordinator != null) this.coordinator.close();
        this.screens.close();
        this.info.close();
        this.minecraft.close();
        ClientSessionManager.resetForTesting();
    }

    @Test
    void reopenKeepsOneProjectionAndClosingDoesNotDiscardHudData() {
        this.coordinator.acceptLegacyMaterialList("project", "Project", List.of(),
                false, false, "", List.of(), true);
        SyncMaterialList model = this.coordinator.materialList();
        GuiMaterialList first = this.screens.constructed().getFirst();
        assertSame(model, this.session.materialList());

        this.coordinator.closeMaterialScreen(first);
        verify(first).suspendBinding();
        assertNull(this.coordinator.materialScreen());
        assertSame(model, this.coordinator.materialList());

        this.coordinator.acceptLegacyMaterialList("project", "Project", List.of(),
                false, false, "", List.of(), true);
        assertSame(model, this.coordinator.materialList());
        assertEquals(2, this.screens.constructed().size(), "新界面应重新绑定同一模型");
        assertSame(model, this.screens.constructed().get(1).getMaterialList());
    }

    @Test
    void childSuspendsButDirectReplacementClosesTheScreenBinding() {
        this.coordinator.acceptLegacyMaterialList("project", "Project", List.of(),
                false, false, "", List.of(), true);
        GuiMaterialList first = this.screens.constructed().getFirst();
        GuiBase child = mock(GuiBase.class);
        when(child.getParent()).thenReturn(first);

        this.coordinator.materialScreenRemoved(first, child);
        assertSame(first, this.coordinator.materialScreen());
        this.coordinator.materialScreenShown(first);
        verify(first).resumeBinding();

        this.coordinator.materialScreenRemoved(first, mock(Screen.class));
        assertNull(this.coordinator.materialScreen());
        assertNotNull(this.coordinator.materialList());
    }

    @Test
    void directEditorSwitchRetiresTheOldHudWithoutTransferringGuiInterest() {
        this.coordinator.acceptLegacyMaterialList("project", "Project", List.of(),
                false, false, "", List.of(), true);
        this.session.handshake().response(5, "server", true);
        this.session.projectView().openEditor("other");
        var open = this.sent.stream()
                .map(net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket.class::cast)
                .filter(packet -> packet.scope().projectId().equals("other")).findFirst().orElseThrow();
        this.session.projectView().onPacket(this.session.connection(), this.session.token(),
                net.syncmaterial.syncmaterial.network.ProjectViewPackets.opened(open.scope(), open.requestId(),
                        open.viewId(), open.generation(), java.util.UUID.randomUUID()));

        assertNull(this.coordinator.materialList());
        assertNull(this.coordinator.materialScreen());
        assertEquals(1, this.screens.constructed().size());
        org.junit.jupiter.api.Assertions.assertFalse(
                net.syncmaterial.syncmaterial.client.InventoryWatcher.isWatching());
    }

    @Test
    void failedCandidateKeepsTheAcceptedProjectAndItsModel() {
        this.session.handshake().response(5, "server", true);
        this.coordinator.requestMaterialList("project");
        acceptOnlyOpen("project");
        var model = this.coordinator.materialList();
        var screen = this.coordinator.materialScreen();

        this.coordinator.requestMaterialList("other");
        var pending = this.sent.stream()
                .map(net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket.class::cast)
                .filter(packet -> packet.scope().projectId().equals("other")).findFirst().orElseThrow();
        this.session.projectView().onPacket(this.session.connection(), this.session.token(),
                net.syncmaterial.syncmaterial.network.ProjectViewPackets.error(pending.scope(), pending.requestId(),
                        pending.viewId(), pending.generation(), net.syncmaterial.syncmaterial.core.ProjectViewAction.OPEN,
                        "denied"));
        assertSame(model, this.coordinator.materialList());
        assertSame(screen, this.coordinator.materialScreen());
    }

    private void acceptOnlyOpen(String projectId) {
        var open = this.sent.stream()
                .map(net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket.class::cast)
                .filter(packet -> packet.scope().projectId().equals(projectId)).findFirst().orElseThrow();
        this.session.projectView().onPacket(this.session.connection(), this.session.token(),
                net.syncmaterial.syncmaterial.network.ProjectViewPackets.opened(open.scope(), open.requestId(),
                        open.viewId(), open.generation(), java.util.UUID.randomUUID()));
    }

    @Test
    void hiddenEditorReceivesConfigurationUntilItsIntentIsClosed() {
        this.session.handshake().response(5, "server", true);
        var lease = this.session.projectView().acquireGlobalWarehouse(
                net.syncmaterial.syncmaterial.core.InterestKind.AREA_CONFIG.bit());
        var open = this.sent.stream()
                .map(net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket.class::cast)
                .findFirst().orElseThrow();
        this.session.projectView().onPacket(this.session.connection(), this.session.token(),
                net.syncmaterial.syncmaterial.network.ProjectViewPackets.opened(open.scope(), open.requestId(),
                        open.viewId(), open.generation(), java.util.UUID.randomUUID()));
        GuiBase owner = mock(GuiBase.class);
        var received = new java.util.concurrent.atomic.AtomicInteger();
        this.coordinator.subscribeConfigurations(owner, open.scope(),
                (action, areas) -> received.incrementAndGet());
        GuiBase child = mock(GuiBase.class);
        when(child.getParent()).thenReturn(owner);
        this.coordinator.reconcileScreen(child);

        pushGlobalConfiguration(open, 1);
        assertEquals(1, received.get());
        pushGlobalConfiguration(open, 2);
        assertEquals(2, received.get());

        this.coordinator.reconcileScreen(mock(Screen.class));
        pushGlobalConfiguration(open, 3);
        assertEquals(2, received.get(), "真正退出后不得再保留编辑器回调");
        lease.close();
    }

    private void pushGlobalConfiguration(
            net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket open, long sequence) {
        var area = new net.syncmaterial.syncmaterial.core.AreaDefinition(1, null,
                net.syncmaterial.syncmaterial.core.AreaKind.WAREHOUSE, "warehouse",
                "minecraft:overworld", new net.syncmaterial.syncmaterial.core.AreaBounds(
                        0, 64, 0, 2, 66, 2), sequence, 0);
        this.session.projectView().onPacket(this.session.connection(), this.session.token(),
                net.syncmaterial.syncmaterial.network.ProjectViewPackets.configuration(open.scope(), 0,
                        open.viewId(), open.generation(), sequence,
                        net.syncmaterial.syncmaterial.core.ProjectViewAction.AREA_CONFIG, true, "",
                        new net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket.AreaConfigData(
                                "LIST_WAREHOUSES", List.of(area))));
    }

    @Test
    void repeatedScreenInitializationKeepsOneConfigurationSubscription() {
        this.session.handshake().response(5, "server", true);
        var lease = this.session.projectView().acquireGlobalWarehouse(
                net.syncmaterial.syncmaterial.core.InterestKind.AREA_CONFIG.bit());
        var open = this.sent.stream()
                .map(net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket.class::cast)
                .findFirst().orElseThrow();
        this.session.projectView().onPacket(this.session.connection(), this.session.token(),
                net.syncmaterial.syncmaterial.network.ProjectViewPackets.opened(open.scope(), open.requestId(),
                        open.viewId(), open.generation(), java.util.UUID.randomUUID()));
        GuiBase owner = mock(GuiBase.class);
        var received = new java.util.concurrent.atomic.AtomicInteger();
        var first = this.coordinator.subscribeConfigurations(owner, open.scope(),
                (action, areas) -> received.incrementAndGet(), false);
        var second = this.coordinator.subscribeConfigurations(owner, open.scope(),
                (action, areas) -> received.incrementAndGet(), false);
        assertSame(first, second);

        pushGlobalConfiguration(open, 1);
        pushGlobalConfiguration(open, 2);
        assertEquals(2, received.get(), "同一 owner/scope 不得随 initGui 累积重复回调");
        lease.close();
    }

    @Test
    void onlyAcceptedViewCanReplaceWorldProjection() {
        this.session.handshake().response(5, "server", true);
        this.session.projectView().openEditor("project");
        acceptOnlyOpen("project");
        pushAreaConfiguration("project", 1);
        assertNotNull(this.session.rendering().projectAreas("project"));

        this.session.projectView().openEditor("candidate");
        pushAreaConfiguration("candidate", 1);
        assertNull(this.session.rendering().projectAreas("candidate"));
        assertNotNull(this.session.rendering().projectAreas("project"));
    }

    private void pushAreaConfiguration(String projectId, long sequence) {
        var scope = net.syncmaterial.syncmaterial.core.ProjectViewScope.project(projectId);
        var token = this.session.projectView().viewToken(scope);
        var area = new net.syncmaterial.syncmaterial.core.AreaDefinition(1, projectId,
                net.syncmaterial.syncmaterial.core.AreaKind.STAGING_AREA, "area",
                "minecraft:overworld", new net.syncmaterial.syncmaterial.core.AreaBounds(
                        0, 64, 0, 2, 66, 2), sequence, 0);
        this.session.projectView().onPacket(this.session.connection(), this.session.token(),
                net.syncmaterial.syncmaterial.network.ProjectViewPackets.configuration(scope, 0,
                        token.viewId(), token.generation(), sequence,
                        net.syncmaterial.syncmaterial.core.ProjectViewAction.AREA_CONFIG, true, "",
                        new net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket.AreaConfigData(
                                "LIST", List.of(area))));
    }

    @Test
    void dimensionChangeWithoutMaterialScreenAdvancesViewAndDropsOldProjection() {
        this.session.handshake().response(5, "server", true);
        this.session.projectView().openEditor("project");
        acceptOnlyOpen("project");
        var area = new net.syncmaterial.syncmaterial.core.AreaDefinition(1, "project",
                net.syncmaterial.syncmaterial.core.AreaKind.STAGING_AREA, "area",
                "minecraft:overworld", new net.syncmaterial.syncmaterial.core.AreaBounds(
                        0, 64, 0, 2, 66, 2), 1, 0);
        var renderer = net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance();
        renderer.applyProjectConfiguration("project", List.of(area), 7,
                this.session.projectView().viewToken(net.syncmaterial.syncmaterial.core.ProjectViewScope.project("project")));
        renderer.updateWarehouseContainers("project", List.of(), "minecraft:overworld");
        renderer.setRenderEnabled("project", false);

        this.coordinator.dimensionChanged();

        assertNull(this.session.rendering().projectAreas("project"));
        assertNull(this.session.rendering().projectWarehouseContainers("project"));
        org.junit.jupiter.api.Assertions.assertFalse(renderer.isRenderEnabled("project"));
        assertNull(this.coordinator.materialScreen());
        org.junit.jupiter.api.Assertions.assertTrue(this.sent.stream()
                .map(net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket.class::cast)
                .anyMatch(packet -> packet.generation() > 1));
    }

    @Test
    void closedProjectionUsesItsOwnSenderAndCannotWriteToAReplacement() {
        this.coordinator.acceptLegacyMaterialList("project", "Project", List.of(),
                false, false, "", List.of(), true);
        SyncMaterialList model = this.coordinator.materialList();
        ClientConnectionSession old = this.session;
        ClientConnectionSession replacement = ClientSessionManager.join(new Object(), this.sent::add);
        assertSame(replacement, ClientSessionManager.current().orElseThrow());

        int beforeOldSend = this.sent.size();
        model.requestCollaborationStatus();
        org.junit.jupiter.api.Assertions.assertEquals(beforeOldSend, this.sent.size(),
                "a closed projection cannot send into the replacement connection");
    }
}
