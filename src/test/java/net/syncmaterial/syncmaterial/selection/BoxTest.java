package net.syncmaterial.syncmaterial.selection;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * Box 选区尺寸计算测试。
 * 只测坐标逻辑，不触碰 Corner/CoordinateType（避免依赖可选 mod 的运行时类）。
 */
public class BoxTest {

    @Test
    void defaultConstructor_originsWithZeroSize() {
        Box box = new Box();
        assertEquals(BlockPos.ZERO, box.getPos1());
        assertEquals(BlockPos.ZERO, box.getPos2());
        assertEquals(new BlockPos(1, 1, 1), box.getSize(), "同一点 → 尺寸 1x1x1");
    }

    @Test
    void twoCorners_sizeIsInclusiveDifference() {
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(9, 4, 19);
        Box box = new Box(a, b, "test");

        assertEquals(new BlockPos(10, 5, 20), box.getSize(), "尺寸 = 差 + 1（含两端）");
    }

    @Test
    void reversedCorners_sizeCarriesDirectionSign() {
        // pos1 在 pos2 的正方向：diff 为负，尺寸量级仍为 11 但带负号（现状语义）
        Box box = new Box(new BlockPos(10, 10, 10), new BlockPos(0, 0, 0), "rev");
        assertEquals(new BlockPos(-11, -11, -11), box.getSize());
    }

    @Test
    void singleBlock_sizeOne() {
        BlockPos p = new BlockPos(100, 64, -200);
        Box box = new Box(p, p, "point");
        assertEquals(new BlockPos(1, 1, 1), box.getSize());
    }

    @Test
    void bothCornersNull_sizeIsOrigin() {
        Box box = new Box(null, null, "empty");
        assertNull(box.getPos1());
        assertNull(box.getPos2());
        assertEquals(BlockPos.ZERO, box.getSize());
    }

    @Test
    void oneCornerNull_sizeIsOne() {
        Box box = new Box(new BlockPos(5, 5, 5), null, "half");
        assertEquals(new BlockPos(1, 1, 1), box.getSize());

        Box box2 = new Box(null, new BlockPos(5, 5, 5), "half2");
        assertEquals(new BlockPos(1, 1, 1), box2.getSize());
    }

    @Test
    void setPos_updatesSize() {
        Box box = new Box(new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), "grow");
        assertEquals(new BlockPos(3, 3, 3), box.getSize());

        box.setPos2(new BlockPos(4, 2, 2));
        assertEquals(new BlockPos(5, 3, 3), box.getSize(), "setPos2 后尺寸应联动更新");

        box.setPos1(new BlockPos(1, 0, 0));
        assertEquals(new BlockPos(4, 3, 3), box.getSize(), "setPos1 后尺寸应联动更新");
    }

    @Test
    void name_getAndSet() {
        Box box = new Box();
        assertEquals("Unnamed", box.getName());
        box.setName("主城堡");
        assertEquals("主城堡", box.getName());
    }
}
