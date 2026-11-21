package net.syncmaterial.syncmaterial.client.gui;

import java.math.BigInteger;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.syncmaterial.syncmaterial.TestGameBootstrap;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialListProjectionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        TestGameBootstrap.bindDataComponents();
    }

    @Test
    void invalidatedListKeepsCountsButCannotReuseReadyRowFlags() {
        MaterialListEntry entry = new MaterialListEntry(1, new ItemStack(Items.STONE), 64, 0, 0, 0);
        entry.setProgressView(new ProjectProgressMaterialView(1, "minecraft:stone", 64, 64, 0,
                true, 0, 0, 0, 0, true, true, true));
        boolean[] known = {true};
        MaterialListBase list = new MaterialListBase() {
            @Override
            public String getTitle() { return "test"; }

            @Override
            public boolean isPreparationKnown() { return known[0]; }
        };
        assertTrue(entry.progressView(list).stagingReady());
        known[0] = false;
        ProjectProgressMaterialView stale = entry.progressView(list);
        assertFalse(stale.preparationKnown());
        assertFalse(stale.stagingReady());
        assertFalse(stale.teamCollected());
        assertEquals(64, stale.staging());
        assertEquals(0, stale.teamMissing());
        assertEquals(MaterialRowStatus.UNKNOWN, MaterialRowStatus.of(stale));
        known[0] = true;
        assertTrue(entry.progressView(list).stagingReady());
    }

    @Test
    void managementTokenDoesNotReplacePersistedProgressRevisionOrAcceptMismatchedCatalog() {
        var client = org.mockito.Mockito.mock(net.minecraft.client.Minecraft.class);
        client.player = org.mockito.Mockito.mock(net.minecraft.client.player.LocalPlayer.class);
        java.util.UUID viewer = java.util.UUID.randomUUID();
        org.mockito.Mockito.when(client.player.getUUID()).thenReturn(viewer);
        org.mockito.Mockito.when(client.player.getName()).thenReturn(net.minecraft.network.chat.Component.literal("Viewer"));
        try (var minecraft = org.mockito.Mockito.mockStatic(net.minecraft.client.Minecraft.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(client);
            SyncMaterialList list = new SyncMaterialList("project", "Project");
            var snapshot = new net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot("project", "Project",
                    3, 4, 5, true, true, false, List.of(), List.of(
                            new net.syncmaterial.syncmaterial.core.ProjectProgressMaterial(1, "minecraft:stone", 64, 16, 8, List.of())));
            assertTrue(list.applyProjectProgress(snapshot));
            assertEquals(-1, list.getManagementCatalogRevision());
            list.applyManagementCatalog(new net.syncmaterial.syncmaterial.network.MaterialCatalogUpdateS2CPacket(
                    "project", "Project", 42, List.of(new net.syncmaterial.syncmaterial.api.MaterialEntry(
                            1, new ItemStack(Items.STONE), 64))));
            assertEquals(42, list.getManagementCatalogRevision());
            assertEquals(3, list.getCatalogRevision());
            assertEquals(16, list.getMaterialsAll().getFirst().getStagingCount());
            assertTrue(list.hasProjectProgress());
            list.applyManagementCatalog(new net.syncmaterial.syncmaterial.network.MaterialCatalogUpdateS2CPacket(
                    "project", "Project", 43, List.of(new net.syncmaterial.syncmaterial.api.MaterialEntry(
                            1, new ItemStack(Items.STONE), 128))));
            assertEquals(-1, list.getManagementCatalogRevision(), "token for another content cannot authorize this view");
            assertEquals(64, list.getMaterialsAll().getFirst().getCountTotal());
        }
    }

    @Test
    void rejectedProgressCannotPartiallyMutateTheCurrentRows() {
        var client = org.mockito.Mockito.mock(net.minecraft.client.Minecraft.class);
        client.player = org.mockito.Mockito.mock(net.minecraft.client.player.LocalPlayer.class);
        java.util.UUID viewer = java.util.UUID.randomUUID();
        org.mockito.Mockito.when(client.player.getUUID()).thenReturn(viewer);
        org.mockito.Mockito.when(client.player.getName()).thenReturn(net.minecraft.network.chat.Component.literal("Viewer"));
        try (var minecraft = org.mockito.Mockito.mockStatic(net.minecraft.client.Minecraft.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(client);
            SyncMaterialList list = new SyncMaterialList("project", "Project");
            var initial = new net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot("project", "Project",
                    3, 4, 5, true, true, false, List.of(), List.of(
                            new net.syncmaterial.syncmaterial.core.ProjectProgressMaterial(1, "minecraft:stone", 64, 16, 8, List.of())));
            assertTrue(list.applyProjectProgress(initial));
            MaterialListEntry existing = list.getMaterialsAll().getFirst();
            var invalid = new net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot("project", "Changed",
                    4, 6, 7, true, true, false, List.of(), List.of(
                            new net.syncmaterial.syncmaterial.core.ProjectProgressMaterial(1, "minecraft:stone", 64, 64, 0, List.of()),
                            new net.syncmaterial.syncmaterial.core.ProjectProgressMaterial(2, "missing:item", 32, 0, 0, List.of())));

            assertFalse(list.applyProjectProgress(invalid));
            assertEquals(1, list.getMaterialsAll().size());
            assertEquals(16, existing.getStagingCount(), "a rejected trailing item must not mutate earlier rows");
            assertEquals(8, existing.getWarehouseCount());
            assertEquals(40, existing.getCountMissing());
            assertEquals(16, existing.progressView(list).staging());
            assertEquals("Project", list.getTitle());
            assertEquals(3, list.getCatalogRevision());
            assertEquals(5, list.getLastAreaInventorySequence());
        }
    }

    @Test
    void saturatedObservedDisplayIsExplicitlyALowerBound() {
        MaterialListEntry entry = new MaterialListEntry(1, new ItemStack(Items.STONE), 64, 0, 0, 0);
        entry.setProgressView(new ProjectProgressMaterialView(1, "minecraft:stone", 64, 0, 0,
                false, 0, Long.MAX_VALUE, 0, 64, false, true, true, true));
        assertEquals("≥" + Long.MAX_VALUE, entry.observedCountText(Long.MAX_VALUE));
        assertEquals("63", entry.observedCountText(63));

        entry.setProgressView(new ProjectProgressMaterialView(1, "minecraft:stone", 64, 0, 0,
                false, 0, Long.MAX_VALUE, 0, 64, false, true, true, false));
        assertEquals(Long.toString(Long.MAX_VALUE), entry.observedCountText(Long.MAX_VALUE),
                "one participant holding exactly MAX is not an overflow");
    }

    @Test
    void multipliedDisplayRetainsExactQuantityWithoutChangingTeamFacts() {
        MaterialListBase list = new MaterialListBase() {
            @Override
            public String getTitle() { return "test"; }
        };
        MaterialListEntry entry = new MaterialListEntry(1, new ItemStack(Items.STONE), Long.MAX_VALUE,
                3, 0, 0);
        list.setMaterialListEntries(List.of(entry));
        BigInteger maximum = BigInteger.valueOf(Long.MAX_VALUE);
        assertEquals(maximum, list.displayedTotal(entry));
        assertEquals(BigInteger.valueOf(3), list.displayedMissing(entry));

        list.setMultiplier(2);
        assertEquals(maximum.multiply(BigInteger.TWO), list.displayedTotal(entry));
        assertEquals(maximum.multiply(BigInteger.TWO), list.displayedMissing(entry));
        assertEquals(Long.MAX_VALUE, entry.getCountTotal());
        assertEquals(3, entry.getCountMissing());
        assertEquals(maximum, list.getExactCountTotal());
    }

    @Test
    void aggregateLongOverflowRetainsExactTotalsAndExplicitSaturation() {
        MaterialListBase list = new MaterialListBase() {
            @Override
            public String getTitle() { return "test"; }
        };
        list.setMaterialListEntries(List.of(
                new MaterialListEntry(1, new ItemStack(Items.STONE), Long.MAX_VALUE,
                        Long.MAX_VALUE, Long.MAX_VALUE, 0),
                new MaterialListEntry(2, new ItemStack(Items.DIRT), 3, 2, 1, 0)));
        BigInteger maximum = BigInteger.valueOf(Long.MAX_VALUE);
        assertEquals(maximum.add(BigInteger.valueOf(3)), list.getExactCountTotal());
        assertEquals(maximum.add(BigInteger.valueOf(2)), list.getExactCountMissing());
        assertEquals(maximum.add(BigInteger.ONE), list.getExactCountMismatched());
        assertEquals(Long.MAX_VALUE, list.getCountTotal());
        assertEquals(Long.MAX_VALUE, list.getCountMissing());
        assertTrue(list.isCountTotalSaturated());
        assertTrue(list.isCountMissingSaturated());
        assertTrue(list.isCountMismatchedSaturated());

        list.setMaterialListEntries(List.of(new MaterialListEntry(3, new ItemStack(Items.GLASS),
                Long.MAX_VALUE, Long.MAX_VALUE, 0, 0)));
        assertEquals(maximum, list.getExactCountTotal());
        assertFalse(list.isCountTotalSaturated());
        assertFalse(list.isCountMissingSaturated());
        assertFalse(list.isCountMismatchedSaturated());
    }
}
