package net.syncmaterial.syncmaterial.client.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket.AreaInfo;
import net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket.ContainerEntry;
import net.syncmaterial.syncmaterial.selection.AreaSelection;

/**
 * 会话渲染状态的纯逻辑测试：快照整体替换、项目级清理、维度过滤。
 * 不启动 Minecraft，也不依赖任何连接。
 */
class StagingAreaRendererStateTest {

    private static AreaInfo area(int id, String name, String world) {
        return new AreaInfo(id, name, 0, 64, 0, 5, 70, 5, world);
    }

    private static ContainerEntry container(int x) {
        return new ContainerEntry(x, 64, x, List.of("minecraft:stone"));
    }

    @Test
    void warehouseContainers_replacedAsOneImmutableSnapshot() {
        var state = new StagingAreaRenderer.State();
        List<ContainerEntry> firstEntries = List.of(container(1));
        state.updateWarehouseContainers(firstEntries, "minecraft:overworld");

        var first = state.warehouseContainers();
        assertEquals(firstEntries, first.entries());
        assertEquals("minecraft:overworld", first.world());
        assertTrue(first.loaded());

        List<ContainerEntry> secondEntries = List.of(container(2), container(3));
        state.updateWarehouseContainers(secondEntries, "minecraft:the_nether");

        // 旧快照保持原样：entries/world/loaded 不会出现跨代混用
        assertEquals(firstEntries, first.entries());
        assertEquals("minecraft:overworld", first.world());
        assertTrue(first.loaded());
        assertEquals(secondEntries, state.warehouseContainers().entries());
        assertEquals("minecraft:the_nether", state.warehouseContainers().world());
    }

    @Test
    void sourceListMutation_doesNotLeakIntoSnapshot() {
        var state = new StagingAreaRenderer.State();
        List<ContainerEntry> source = new ArrayList<>();
        source.add(container(1));

        state.updateWarehouseContainers(source, "w");
        source.add(container(2));

        assertEquals(1, state.warehouseContainers().entries().size());
    }

    @Test
    void clearWarehouseContainers_resetsLoadedAndWorld() {
        var state = new StagingAreaRenderer.State();
        state.updateWarehouseContainers(List.of(container(1)), "minecraft:overworld");
        assertTrue(state.hasWarehouseContainersLoaded());

        state.clearWarehouseContainers();

        assertTrue(state.warehouseContainers().entries().isEmpty());
        assertEquals("", state.warehouseContainers().world());
        assertFalse(state.hasWarehouseContainersLoaded());
    }

    @Test
    void warehouseAreas_replacedAsOneImmutableSnapshot() {
        var state = new StagingAreaRenderer.State();
        List<AreaInfo> firstAreas = List.of(area(1, "仓库A", "minecraft:overworld"));
        state.updateWarehouseAreas(firstAreas, List.of(1));

        var first = state.warehouseAreas();
        assertEquals(firstAreas, first.areas());
        assertEquals(Set.of(1), first.referencedIds());
        assertTrue(state.isWarehouseReferenced(1));
        assertFalse(state.isWarehouseReferenced(2));

        List<AreaInfo> secondAreas = List.of(area(2, "仓库B", "minecraft:the_nether"));
        state.updateWarehouseAreas(secondAreas, List.of());

        assertEquals(firstAreas, first.areas(), "旧快照不能被后续广播改写");
        assertEquals(Set.of(1), first.referencedIds());
        assertEquals(secondAreas, state.warehouseAreas().areas());
        assertTrue(state.warehouseAreas().referencedIds().isEmpty());
        assertFalse(state.isWarehouseReferenced(1));
    }

    @Test
    void clearProject_removesOnlyThatProjectAndKeepsGlobalWarehouseData() {
        var state = new StagingAreaRenderer.State();
        state.updateSelection("project-a", new AreaSelection());
        state.updateSelection("project-b", new AreaSelection());
        state.setRenderEnabled("project-a", false);
        state.setSchematicName("project-a", "项目A");
        state.setSchematicName("project-b", "项目B");
        state.setHighlightedBox("project-a", "区域1");
        state.updateWarehouseContainers(List.of(container(1)), "minecraft:overworld");
        state.updateWarehouseAreas(List.of(area(9, "仓库", "minecraft:overworld")), List.of(9));

        state.clearProject("project-a");

        assertNull(state.selection("project-a"));
        assertNotNull(state.selection("project-b"), "其他项目不受影响");
        assertTrue(state.isRenderEnabled("project-a"), "开关随项目一起清除，回落到默认值");
        assertTrue(state.isRenderEnabled("project-b"));
        assertNull(state.schematicName("project-a"));
        assertEquals("项目B", state.schematicName("project-b"));
        assertEquals(StagingAreaRenderer.State.Highlight.NONE, state.highlight());

        // 切项目不得关闭 global 仓库
        assertEquals(1, state.warehouseContainers().entries().size());
        assertTrue(state.hasWarehouseContainersLoaded());
        assertEquals(1, state.warehouseAreas().areas().size());
        assertTrue(state.isWarehouseReferenced(9));
    }

    @Test
    void activateProject_dropsOtherProjectsAndKeepsGlobalWarehouseData() {
        var state = new StagingAreaRenderer.State();
        state.updateSelection("project-a", new AreaSelection());
        state.updateSelection("project-b", new AreaSelection());
        state.setSchematicName("project-a", "项目A");
        state.setSchematicName("project-b", "项目B");
        state.setHighlightedBox("project-a", "区域1");
        state.updateWarehouseAreas(List.of(area(9, "仓库", "minecraft:overworld")), List.of(9));

        state.activateProject("project-b");

        assertNull(state.selection("project-a"), "切项目只留下目标项目");
        assertNotNull(state.selection("project-b"));
        assertNull(state.schematicName("project-a"));
        assertEquals("项目B", state.schematicName("project-b"));
        assertEquals(StagingAreaRenderer.State.Highlight.NONE, state.highlight());
        assertEquals(1, state.warehouseAreas().areas().size(), "global 仓库独立于项目切换");
    }

    @Test
    void clear_clearsSessionDataIncludingWarehouses() {
        var state = new StagingAreaRenderer.State();
        state.updateSelection("project-a", new AreaSelection());
        state.setSchematicName("project-a", "项目A");
        state.setHighlightedBox("project-a", "区域1");
        state.updateWarehouseContainers(List.of(container(1)), "w");
        state.updateWarehouseAreas(List.of(area(9, "仓库", "w")), List.of(9));

        state.clear();

        assertNull(state.selection("project-a"));
        assertNull(state.schematicName("project-a"));
        assertEquals(StagingAreaRenderer.State.Highlight.NONE, state.highlight());
        assertFalse(state.hasWarehouseContainersLoaded());
        assertTrue(state.warehouseAreas().areas().isEmpty());
        assertTrue(state.warehouseAreas().referencedIds().isEmpty());
    }

    @Test
    void matchesWorld_filtersKnownDimensionsAndKeepsUnknownVisible() {
        assertTrue(StagingAreaRenderer.matchesWorld(null, "minecraft:overworld"),
                "未知维度（旧服务端/未同步）不得隐藏线框");
        assertTrue(StagingAreaRenderer.matchesWorld("  ", "minecraft:overworld"));
        assertTrue(StagingAreaRenderer.matchesWorld("minecraft:overworld", "minecraft:overworld"));
        assertFalse(StagingAreaRenderer.matchesWorld("minecraft:the_nether", "minecraft:overworld"),
                "已知维度不一致必须过滤");
        assertFalse(StagingAreaRenderer.matchesWorld("minecraft:overworld", null),
                "玩家维度未知时不渲染已知其他维度的区域");
    }
}
