package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;

/**
 * 背包白名单统计测试：
 * 只统计 setContext 注册过的材料，潜影盒内容物递归展开后同样按白名单过滤。
 */
public class InventoryWhitelistTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @AfterEach
    void cleanup() {
        InventoryWatcher.clearContext();
    }

    private Inventory inventoryOf(ItemStack... stacks) {
        Inventory inv = mock(Inventory.class);
        when(inv.getContainerSize()).thenReturn(stacks.length);
        for (int i = 0; i < stacks.length; i++) {
            when(inv.getItem(i)).thenReturn(stacks[i]);
        }
        return inv;
    }

    private ItemStack shulkerWith(int diamondCount) {
        ItemStack shulker = new ItemStack(net.minecraft.world.level.block.Blocks.DYED_SHULKER_BOX.purple());
        shulker.set(DataComponents.CONTAINER,
            ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, diamondCount))));
        return shulker;
    }

    @Test
    void whitelistedItemsCounted_othersIgnored() {
        InventoryWatcher.setContext("s1", Map.of(
            "minecraft:stone", 1,
            "minecraft:diamond", 2));

        var counts = InventoryWatcher.getCounts(inventoryOf(
            new ItemStack(Items.STONE, 32),
            new ItemStack(Items.DIRT, 64),        // 不在白名单：忽略
            new ItemStack(Items.DIAMOND, 3)));

        assertEquals(Map.of(1, 32, 2, 3), counts);
    }

    @Test
    void sameItemMultipleSlots_summed() {
        InventoryWatcher.setContext("s1", Map.of("minecraft:stone", 1));

        var counts = InventoryWatcher.getCounts(inventoryOf(
            new ItemStack(Items.STONE, 10),
            new ItemStack(Items.STONE, 20)));

        assertEquals(30, counts.get(1));
    }

    @Test
    void shulkerContents_countedIfWhitelisted() {
        InventoryWatcher.setContext("s1", Map.of(
            "minecraft:diamond", 2,
            "minecraft:purple_shulker_box", 3));

        var counts = InventoryWatcher.getCounts(inventoryOf(shulkerWith(64)));

        assertEquals(64, counts.get(2), "潜影盒内钻石应递归计入");
        assertEquals(1, counts.get(3), "潜影盒本身按白名单计入一件");
    }

    @Test
    void shulkerContents_ignoredIfNotWhitelisted() {
        // 钻石不在白名单：潜影盒内容物不应计入
        InventoryWatcher.setContext("s1", Map.of("minecraft:purple_shulker_box", 3));

        var counts = InventoryWatcher.getCounts(inventoryOf(shulkerWith(64)));

        assertEquals(Map.of(3, 1), counts);
    }

    @Test
    void emptyWhitelist_nothingCounted() {
        InventoryWatcher.setContext("s1", Map.of());

        var counts = InventoryWatcher.getCounts(inventoryOf(
            new ItemStack(Items.STONE, 32), shulkerWith(64)));

        assertTrue(counts.isEmpty());
    }

    @Test
    void clearContext_resetsWhitelist() {
        InventoryWatcher.setContext("s1", Map.of("minecraft:stone", 1));
        InventoryWatcher.clearContext();

        var counts = InventoryWatcher.getCounts(inventoryOf(new ItemStack(Items.STONE, 32)));

        assertTrue(counts.isEmpty(), "清空上下文后白名单应失效");
    }

    @Test
    void emptyStacks_skipped() {
        InventoryWatcher.setContext("s1", Map.of("minecraft:stone", 1));

        var counts = InventoryWatcher.getCounts(inventoryOf(
            ItemStack.EMPTY, new ItemStack(Items.STONE, 5), ItemStack.EMPTY));

        assertEquals(Map.of(1, 5), counts);
    }
}
