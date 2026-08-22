package net.syncmaterial.syncmaterial.selection;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.util.math.BlockPos;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

/**
 * AreaSelection 子区域管理逻辑测试。
 */
public class AreaSelectionTest {

    @Test
    void createNewSubRegionBox_setsBothCornersToPos() {
        AreaSelection sel = new AreaSelection();
        BlockPos pos = new BlockPos(10, 64, -20);

        String name = sel.createNewSubRegionBox(pos, "塔楼");

        assertEquals("塔楼", name);
        assertEquals("塔楼", sel.getCurrentSubRegionBoxName());
        Box box = sel.getSubRegionBox("塔楼");
        assertNotNull(box);
        assertEquals(pos, box.getPos1(), "新建子区域两角都应初始化为起点");
        assertEquals(pos, box.getPos2());
        assertEquals(Corner.CORNER_1, box.getSelectedCorner(), "新建后应选中角点 1");
    }

    @Test
    void createNewSubRegionBox_duplicateName_appendsCounterSuffix() {
        AreaSelection sel = new AreaSelection();
        BlockPos pos = BlockPos.ORIGIN;

        assertEquals("区域", sel.createNewSubRegionBox(pos, "区域"));
        assertEquals("区域 1", sel.createNewSubRegionBox(pos, "区域"));
        assertEquals("区域 2", sel.createNewSubRegionBox(pos, "区域"));
        assertEquals(3, sel.getAllSubRegionNames().size());
    }

    @Test
    void addSubRegionBox_respectsReplace() {
        AreaSelection sel = new AreaSelection();
        Box box1 = new Box(BlockPos.ORIGIN, BlockPos.ORIGIN, "A");
        Box box2 = new Box(BlockPos.ORIGIN, BlockPos.ORIGIN, "A");

        assertTrue(sel.addSubRegionBox(box1, false));
        assertFalse(sel.addSubRegionBox(box2, false), "不替换时同名添加应失败");
        assertSame(box1, sel.getSubRegionBox("A"), "原 box 应保留");

        assertTrue(sel.addSubRegionBox(box2, true), "replace=true 时应覆盖");
        assertSame(box2, sel.getSubRegionBox("A"));
    }

    @Test
    void removeSubRegionBox_clearsCurrentSelection() {
        AreaSelection sel = new AreaSelection();
        sel.createNewSubRegionBox(BlockPos.ORIGIN, "A");

        assertTrue(sel.removeSubRegionBox("A"));
        assertNull(sel.getSubRegionBox("A"));
        assertNull(sel.getCurrentSubRegionBoxName(), "删除当前选中区域后应清空选中");
        assertFalse(sel.removeSubRegionBox("A"), "重复删除应返回 false");
    }

    @Test
    void renameSubRegionBox_targetNameExists_returnsFalse() {
        AreaSelection sel = new AreaSelection();
        sel.createNewSubRegionBox(BlockPos.ORIGIN, "A");
        sel.createNewSubRegionBox(BlockPos.ORIGIN, "B");

        assertFalse(sel.renameSubRegionBox("A", "B"), "目标名已占用应失败");
        assertNotNull(sel.getSubRegionBox("A"), "失败时原区域应保留");
    }

    @Test
    void renameSubRegionBox_success_updatesCurrentBoxName() {
        AreaSelection sel = new AreaSelection();
        sel.createNewSubRegionBox(BlockPos.ORIGIN, "旧名");

        assertTrue(sel.renameSubRegionBox("旧名", "新名"));
        assertNull(sel.getSubRegionBox("旧名"));
        assertNotNull(sel.getSubRegionBox("新名"));
        assertEquals("新名", sel.getCurrentSubRegionBoxName(), "重命名后选中应跟随");
    }

    @Test
    void serverIdMap_roundtrip() {
        AreaSelection sel = new AreaSelection();

        sel.setServerId("A", 42);
        assertEquals(42, sel.getServerId("A"));
        assertNull(sel.getServerId("B"));

        sel.removeServerId("A");
        assertNull(sel.getServerId("A"));
    }

    @Test
    void setSelectedSubRegionBox_unknownName_rejected() {
        AreaSelection sel = new AreaSelection();
        sel.createNewSubRegionBox(BlockPos.ORIGIN, "A");

        assertFalse(sel.setSelectedSubRegionBox("不存在"));
        assertTrue(sel.setSelectedSubRegionBox("A"));
        assertTrue(sel.setSelectedSubRegionBox(null), "null 应允许（取消选中）");
    }
}
