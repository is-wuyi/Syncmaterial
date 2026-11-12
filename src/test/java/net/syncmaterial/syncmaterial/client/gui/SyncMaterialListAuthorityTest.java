package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.syncmaterial.syncmaterial.TestGameBootstrap;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.network.AreaInventorySnapshotS2CPacket;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class SyncMaterialListAuthorityTest {
    private static final String PROJECT = "project";
    private MockedStatic<Minecraft> clientMock;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        TestGameBootstrap.bindDataComponents();
    }

    @BeforeEach
    void mockClient() {
        clientMock = mockStatic(Minecraft.class);
        clientMock.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
    }

    @AfterEach
    void tearDown() {
        clientMock.close();
    }

    @Test
    void foundationListStaysUnknownUntilTheFirstAuthoritativeSnapshot() {
        SyncMaterialList list = list(4, material(1, Items.STONE, 64));
        list.useFoundationBackend();
        assertFalse(list.isPreparationKnown());

        assertTrue(list.applyAreaInventorySnapshot(snapshot(4, 1, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 20, 10)), List.of())));
        assertTrue(list.isPreparationKnown());
        MaterialListEntry entry = list.getMaterialsAll().get(0);
        assertEquals(20, entry.getStagingCount());
        assertEquals(10, entry.getWarehouseCount());
        assertEquals(34, entry.getCountMissing());
    }

    @Test
    void wrongCatalogueRevisionIsRejected() {
        SyncMaterialList list = list(4, material(1, Items.STONE, 64));
        assertFalse(list.applyAreaInventorySnapshot(snapshot(3, 1, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 1, 1)), List.of())));
        assertFalse(list.hasAuthoritativeInventory());
    }

    @Test
    void olderOrEqualSequenceIsRejected() {
        SyncMaterialList list = list(4, material(1, Items.STONE, 64));
        assertTrue(list.applyAreaInventorySnapshot(snapshot(4, 7, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 1, 1)), List.of())));
        assertFalse(list.applyAreaInventorySnapshot(snapshot(4, 7, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 2, 2)), List.of())));
        assertFalse(list.applyAreaInventorySnapshot(snapshot(4, 6, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 3, 3)), List.of())));
        assertEquals(1, list.getMaterialsAll().get(0).getStagingCount());
        assertEquals(7, list.getLastAreaInventorySequence());
    }

    @Test
    void incompleteUnknownOrEmptyMaterialSetsAreRejectedUnlessCatalogueIsEmpty() {
        SyncMaterialList list = list(4, material(1, Items.STONE, 64), material(2, Items.DIRT, 32));
        assertFalse(list.applyAreaInventorySnapshot(snapshot(4, 1, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 1, 1)), List.of())));
        assertFalse(list.applyAreaInventorySnapshot(snapshot(4, 2, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 1, 1),
                        new AreaInventorySnapshotS2CPacket.MaterialInventory(3, 1, 1)), List.of())));
        assertFalse(list.applyAreaInventorySnapshot(snapshot(4, 3, true, List.of(), List.of())));
        assertFalse(list.hasAuthoritativeInventory());

        SyncMaterialList empty = list(5);
        empty.useFoundationBackend();
        assertTrue(empty.applyAreaInventorySnapshot(snapshot(5, 1, true, List.of(), List.of())));
        assertTrue(empty.isPreparationKnown());
    }

    @Test
    void inconsistentKnownAndFreshnessNeverCountsAsPrepared() {
        var warning = new CollaborationStatusS2CPacket.AreaFreshnessInfo("staging_area", 1, "A", "未初始化");
        assertThrows(IllegalArgumentException.class, () -> snapshot(4, 1, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 1, 1)), List.of(warning)));

        // 即使恶意/畸形的 known=true 携带 freshness 警告到达客户端，有效 known 仍为 false。
        assertFalse(SyncMaterialList.effectiveAuthoritativeKnown(true, List.of(warning)));
        assertFalse(SyncMaterialList.effectiveAuthoritativeKnown(false, List.of()));
        assertTrue(SyncMaterialList.effectiveAuthoritativeKnown(true, List.of()));
    }

    @Test
    void legacyCollaborationStatusCannotOverwriteAuthoritativeCounts() {
        SyncMaterialList list = list(4, material(1, Items.STONE, 64));
        assertTrue(list.applyAreaInventorySnapshot(snapshot(4, 1, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 20, 10)), List.of())));

        list.onCollaborationStatus(new CollaborationStatusS2CPacket(PROJECT, 1, 64, 0, 0,
                List.of(new CollaborationStatusS2CPacket.ParticipantInfo("Alice", 5)), List.of()));

        MaterialListEntry entry = list.getMaterialsAll().get(0);
        assertEquals(20, entry.getStagingCount(), "旧协作状态不能覆盖权威备货区计数");
        assertEquals(10, entry.getWarehouseCount(), "旧协作状态不能覆盖权威仓库计数");
        assertEquals(5, entry.getOtherPlayersCount(), "旧协作状态的参与者仍需展示");
        assertEquals(29, entry.getCountMissing());
        assertTrue(list.isPreparationKnown());
    }

    @Test
    void catalogueChangeInvalidatesAuthorityAndStaleStatusCannotImplyKnown() {
        SyncMaterialList list = list(4, material(1, Items.STONE, 64));
        list.useFoundationBackend();
        assertTrue(list.applyAreaInventorySnapshot(snapshot(4, 1, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 20, 10)), List.of())));
        assertTrue(list.isPreparationKnown());

        list.setMaterialEntries(List.of(material(1, Items.STONE, 128)), 5);
        assertFalse(list.hasAuthoritativeInventory());
        assertFalse(list.isPreparationKnown());

        list.onCollaborationStatus(new CollaborationStatusS2CPacket(PROJECT, 1, 128, 0, 0,
                List.of(new CollaborationStatusS2CPacket.ParticipantInfo("Alice", 5)), List.of()));
        assertFalse(list.isPreparationKnown(), "目录更新后的旧状态不能短暂显示已备齐");
        assertEquals(0, list.getMaterialsAll().get(0).getStagingCount());
    }

    @Test
    void emptyFreshnessClearsPreviousWarningsAndUnknownNeverImpliesPrepared() {
        SyncMaterialList list = list(4, material(1, Items.STONE, 64));
        list.useFoundationBackend();
        assertTrue(list.applyAreaInventorySnapshot(snapshot(4, 1, false,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 0, 0)),
                List.of(new CollaborationStatusS2CPacket.AreaFreshnessInfo("staging_area", 1, "A", "未初始化")))));
        assertFalse(list.isPreparationKnown());
        assertEquals(1, list.getAreaFreshness().size());

        assertTrue(list.applyAreaInventorySnapshot(snapshot(4, 2, true,
                List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 64, 0)), List.of())));
        assertTrue(list.isPreparationKnown());
        assertTrue(list.getAreaFreshness().isEmpty());
    }

    private static SyncMaterialList list(long revision, MaterialEntry... entries) {
        SyncMaterialList list = new SyncMaterialList(PROJECT, "Project");
        list.setMaterialEntries(List.of(entries), revision);
        return list;
    }

    private static MaterialEntry material(int id, net.minecraft.world.item.Item item, long total) {
        return new MaterialEntry(id, new ItemStack(item), total);
    }

    private static AreaInventorySnapshotS2CPacket snapshot(long catalogRevision, long sequence, boolean known,
            List<AreaInventorySnapshotS2CPacket.MaterialInventory> materials,
            List<CollaborationStatusS2CPacket.AreaFreshnessInfo> freshness) {
        return new AreaInventorySnapshotS2CPacket(PROJECT, catalogRevision, sequence, known, materials, freshness);
    }
}
