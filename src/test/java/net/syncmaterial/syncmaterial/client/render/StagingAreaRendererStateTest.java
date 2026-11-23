package net.syncmaterial.syncmaterial.client.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket.AreaInfo;
import net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket.ContainerEntry;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;

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

    // ========== beta.61：权威投影与编辑工作副本分离 ==========

    private static final net.syncmaterial.syncmaterial.core.AreaBounds BOUNDS =
            new net.syncmaterial.syncmaterial.core.AreaBounds(0, 64, 0, 5, 70, 5);

    @Test
    void applyProjectAreas_replacesAuthoritativeSnapshotAndLeavesWorkingCopyAlone() {
        var state = new StagingAreaRenderer.State();
        AreaSelection workingCopy = new AreaSelection();
        state.updateSelection("project-a", workingCopy);

        state.applyProjectAreas("project-a", List.of(
                new StagingAreaRenderer.State.ProjectArea(7, "区域A", "minecraft:overworld", BOUNDS)),
                42L, "view-token");

        var snapshot = state.projectAreas("project-a");
        assertEquals(42L, snapshot.configSequence());
        assertEquals("view-token", snapshot.viewIdentity());
        assertEquals(1, snapshot.areas().size());
        assertEquals(7, snapshot.areas().get(0).areaId());

        // 权威配置不得覆写编辑器正在使用的可变 AreaSelection
        assertSame(workingCopy, state.selection("project-a"),
                "权威快照替换时编辑工作副本必须原样保留");
        assertTrue(workingCopy.getAllSubRegionBoxes().isEmpty(),
                "权威配置不得把区域写进编辑器工作副本");

        // 旧快照不被后续应用改写（不可变整体替换）
        var first = snapshot;
        state.applyProjectAreas("project-a", List.of(
                new StagingAreaRenderer.State.ProjectArea(8, "区域B", "minecraft:overworld", BOUNDS)),
                43L, null);
        assertEquals(1, first.areas().size(), "旧权威快照必须保持只读");
        assertEquals(1, state.projectAreas("project-a").areas().size());
        assertEquals("区域B", state.projectAreas("project-a").areas().get(0).name());
    }

    @Test
    void projectDeletionAndSwitchClearAuthoritativeProjectionPrecisely() {
        var state = new StagingAreaRenderer.State();
        state.applyProjectAreas("project-a", List.of(
                new StagingAreaRenderer.State.ProjectArea(1, "区域A", "w", BOUNDS)), 1L, null);
        state.applyProjectAreas("project-b", List.of(
                new StagingAreaRenderer.State.ProjectArea(2, "区域B", "w", BOUNDS)), 2L, null);
        state.setEditorPreview("project-a", new AreaSelection());

        // 删除 A：只清 A 的投影/预览，B 原样保留
        state.removeRenderData("project-a");
        assertNull(state.projectAreas("project-a"));
        assertNotNull(state.projectAreas("project-b"), "删除 A 不得清 B 的投影");
        assertEquals(StagingAreaRenderer.State.EditorPreview.NONE, state.editorPreview());

        // 切到 B：A 的数据清掉，global 仓库独立
        state.applyProjectAreas("project-a", List.of(
                new StagingAreaRenderer.State.ProjectArea(1, "区域A", "w", BOUNDS)), 1L, null);
        state.activateProject("project-b");
        assertNull(state.projectAreas("project-a"));
        assertNotNull(state.projectAreas("project-b"));
    }

    @Test
    void clearResetsAuthoritativeProjectionAndPreview() {
        var state = new StagingAreaRenderer.State();
        state.applyProjectAreas("project-a", List.of(
                new StagingAreaRenderer.State.ProjectArea(1, "区域A", "w", BOUNDS)), 1L, null);
        state.setEditorPreview("project-a", new AreaSelection());

        state.clear();

        assertNull(state.projectAreas("project-a"));
        assertEquals(StagingAreaRenderer.State.EditorPreview.NONE, state.editorPreview());
    }

    @Test
    void editorPreviewIsOverlayOnlyAndNeverAuthoritative() {
        var state = new StagingAreaRenderer.State();
        AreaSelection workingCopy = new AreaSelection();
        workingCopy.addSubRegionBox(new Box(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), "本地预览"), true);

        state.setEditorPreview("project-a", workingCopy);
        assertTrue(state.editorPreview().matches("project-a"));
        assertFalse(state.editorPreview().matches("project-b"));
        assertNull(state.projectAreas("project-a"), "预览登记不得生成权威投影");

        state.setEditorPreview("project-a", null);
        assertEquals(StagingAreaRenderer.State.EditorPreview.NONE, state.editorPreview());
    }

    // ========== beta.61：协议 5 按项目归属的容器与引用 ==========

    @Test
    void projectWarehouseContainersAreScopedPerProject() {
        var state = new StagingAreaRenderer.State();
        state.updateProjectWarehouseContainers("project-a", List.of(container(1)), "minecraft:overworld");
        state.updateProjectWarehouseContainers("project-b", List.of(container(2)), "minecraft:the_nether");

        assertEquals(1, state.projectWarehouseContainers("project-a").entries().size());
        assertEquals("minecraft:the_nether", state.projectWarehouseContainers("project-b").world());
        assertTrue(state.hasProjectWarehouseContainers());

        // 只清 A：B 与 legacy 全局快照不受影响（项目删除不得当全局删除）
        state.updateWarehouseContainers(List.of(container(9)), "minecraft:overworld");
        state.clearProjectWarehouseContainers("project-a");
        assertNull(state.projectWarehouseContainers("project-a"));
        assertNotNull(state.projectWarehouseContainers("project-b"));
        assertEquals(1, state.warehouseContainers().entries().size(), "legacy 全局快照独立");
    }

    @Test
    void projectWarehouseReferencesUnionAndPreciseClear() {
        var state = new StagingAreaRenderer.State();
        state.applyProjectWarehouseReferences("project-a", List.of(1, 2));
        state.applyProjectWarehouseReferences("project-b", List.of(3));
        state.updateWarehouseAreas(List.of(area(9, "仓库", "w")), List.of(7));

        assertTrue(state.isWarehouseReferenced(1));
        assertTrue(state.isWarehouseReferenced(3));
        assertTrue(state.isWarehouseReferenced(7), "legacy 全局引用仍在并集里");

        state.clearProjectWarehouseReferences("project-a");
        assertFalse(state.isWarehouseReferenced(1), "清 A 的引用不得影响 B");
        assertTrue(state.isWarehouseReferenced(3));
        assertTrue(state.isWarehouseReferenced(7));

        state.removeRenderData("project-b");
        assertFalse(state.isWarehouseReferenced(3));
        assertEquals(1, state.warehouseAreas().areas().size(), "项目删除不得清 global 仓库定义");
    }

    @Test
    void activateProjectDropsOtherProjectsContainersAndReferences() {
        var state = new StagingAreaRenderer.State();
        state.updateProjectWarehouseContainers("project-a", List.of(container(1)), "w");
        state.updateProjectWarehouseContainers("project-b", List.of(container(2)), "w");
        state.applyProjectWarehouseReferences("project-a", List.of(1));
        state.applyProjectWarehouseReferences("project-b", List.of(2));

        state.activateProject("project-b");

        assertNull(state.projectWarehouseContainers("project-a"));
        assertNotNull(state.projectWarehouseContainers("project-b"));
        assertFalse(state.isWarehouseReferenced(1));
        assertTrue(state.isWarehouseReferenced(2));
    }
}
