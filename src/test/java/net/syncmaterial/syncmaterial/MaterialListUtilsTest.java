package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.client.gui.MaterialListUtils;

/**
 * 玩家可用数统计与条目转换测试（材料列表"你有几个"一列的来源）。
 */
public class MaterialListUtilsTest {

    @BeforeAll
    static void setup() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private PlayerEntity playerWith(ItemStack... stacks) {
        PlayerInventory inv = mock(PlayerInventory.class);
        when(inv.size()).thenReturn(stacks.length);
        for (int i = 0; i < stacks.length; i++) {
            when(inv.getStack(i)).thenReturn(stacks[i]);
        }
        PlayerEntity player = mock(PlayerEntity.class);
        when(player.getInventory()).thenReturn(inv);
        return player;
    }

    @Test
    void updateAvailableCounts_setsAvailableAndClampedMissing() {
        ItemStack shulker = new ItemStack(Items.PURPLE_SHULKER_BOX);
        shulker.set(DataComponentTypes.CONTAINER,
            ContainerComponent.fromStacks(List.of(new ItemStack(Items.DIAMOND, 16))));

        var stone = new MaterialListEntry(1, new ItemStack(Items.STONE), 100, 100, 0, 0);
        var diamond = new MaterialListEntry(2, new ItemStack(Items.DIAMOND), 10, 10, 0, 0);

        MaterialListUtils.updateAvailableCounts(List.of(stone, diamond),
            playerWith(new ItemStack(Items.STONE, 30), shulker));

        assertEquals(30, stone.getCountAvailable());
        assertEquals(70, stone.getCountMissing(), "缺失 = 总数 - 背包，下限 0");

        assertEquals(16, diamond.getCountAvailable(), "潜影盒内钻石计入可用数");
        assertEquals(0, diamond.getCountMissing(), "背包超过总数时缺失钳为 0");
    }

    @Test
    void updateAvailableCounts_nullPlayer_noop() {
        var entry = new MaterialListEntry(1, new ItemStack(Items.STONE), 100, 50, 0, 0);

        MaterialListUtils.updateAvailableCounts(List.of(entry), null);

        assertEquals(0, entry.getCountAvailable(), "无玩家时不应改动");
        assertEquals(50, entry.getCountMissing());
    }

    @Test
    void updateAvailableCounts_unrelatedItemsIgnored() {
        var stone = new MaterialListEntry(1, new ItemStack(Items.STONE), 100, 100, 0, 0);

        MaterialListUtils.updateAvailableCounts(List.of(stone),
            playerWith(new ItemStack(Items.DIRT, 999)));

        assertEquals(0, stone.getCountAvailable(), "无关物品不影响可用数");
    }

    @Test
    void convertFromMaterialEntries_mapsAllFields() {
        var source = new MaterialEntry(7, new ItemStack(Items.STONE), 500, 120, 3, 377);

        var result = MaterialListUtils.convertFromMaterialEntries(List.of(source));

        assertEquals(1, result.size());
        var converted = result.get(0);
        assertEquals(7, converted.getDatabaseId());
        assertEquals(500, converted.getCountTotal());
        assertEquals(120, converted.getCountMissing());
        assertEquals(3, converted.getCountMismatched());
        assertEquals(377, converted.getCountAvailable());
        assertTrue(converted.getStack().isOf(Items.STONE));
    }
}
