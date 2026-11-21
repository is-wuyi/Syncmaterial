package net.syncmaterial.syncmaterial.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import net.syncmaterial.syncmaterial.TestGameBootstrap;
import net.syncmaterial.syncmaterial.network.AreaInventorySnapshotS2CPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class SyncMaterialListBindingTest {
    private MockedStatic<Minecraft> clientMock;

    @BeforeEach
    void mockClient() {
        clientMock = org.mockito.Mockito.mockStatic(Minecraft.class);
        clientMock.when(Minecraft::getInstance).thenReturn(org.mockito.Mockito.mock(Minecraft.class));
    }

    @AfterEach
    void closeClient() {
        clientMock.close();
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        TestGameBootstrap.bindDataComponents();
    }

    @Test
    void closingBindingStopsUiCallbacksButNotTheProjectModel() {
        SyncMaterialList list = new SyncMaterialList("project", "Project");
        AtomicInteger status = new AtomicInteger();
        AtomicInteger catalog = new AtomicInteger();
        var binding = list.bind(status::incrementAndGet, catalog::incrementAndGet);
        list.setMaterialEntries(List.of(), 1);
        list.applyAreaInventorySnapshot(new AreaInventorySnapshotS2CPacket(
                "project", 1, 1, true, List.of(), List.of()));
        assertEquals(1, status.get());
        assertEquals(1, catalog.get());

        binding.close();
        list.setMaterialEntries(List.of(), 2);
        list.applyAreaInventorySnapshot(new AreaInventorySnapshotS2CPacket(
                "project", 2, 2, true, List.of(), List.of()));
        assertEquals(1, status.get());
        assertEquals(1, catalog.get());
        assertEquals(2, list.getCatalogRevision(), "解绑只影响界面，不回退项目模型");
    }

    @Test
    void oldBindingCannotClearANewerScreenBinding() {
        SyncMaterialList list = new SyncMaterialList("project", "Project");
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        var oldBinding = list.bind(oldCalls::incrementAndGet, oldCalls::incrementAndGet);
        var newBinding = list.bind(newCalls::incrementAndGet, newCalls::incrementAndGet);

        oldBinding.close();
        list.setMaterialEntries(List.of(), 1);
        assertEquals(0, oldCalls.get());
        assertEquals(1, newCalls.get());

        newBinding.close();
        list.setMaterialEntries(List.of(), 2);
        assertEquals(1, newCalls.get());
    }
}
