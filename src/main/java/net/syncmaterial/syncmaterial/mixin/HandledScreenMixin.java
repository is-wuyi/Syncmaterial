package net.syncmaterial.syncmaterial.mixin;

import java.util.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.block.ShulkerBoxBlock;

import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListHudRenderer;

/**
 * 取货指示器：打开箱子时高亮需要取的物品格子
 * 使用 MaterialListHudRenderer 的缓存数据（与HUD同步更新），逐格递减计算高亮。
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> {

    @Shadow protected T handler;

    // 缓存：HUD刷新时计算一次，之后直接渲染缓存，避免光标拿取物品导致闪烁
    @Unique private static final Set<Integer> cachedHighlightSlots = new HashSet<>();
    @Unique private static int lastNeedsHash = 0;

    @Inject(method = "drawForeground", at = @At("TAIL"))
    private void onDrawForeground(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        if (!GuiMaterialList.isPickupModeStatic()) return;

        Map<String, Integer> needs = MaterialListHudRenderer.getPickupHighlightNeeds();
        int currentHash = needs.hashCode();

        // 仅在HUD数据更新时重新计算高亮格子，之后直接渲染缓存
        if (currentHash != lastNeedsHash) {
            lastNeedsHash = currentHash;
            cachedHighlightSlots.clear();
            if (needs.isEmpty()) return;
            Map<String, Integer> remaining = new HashMap<>(needs);
            List<Slot> slots = this.handler.slots;
            for (int i = 0; i < slots.size(); i++) {
                Slot slot = slots.get(i);
                if (!slot.hasStack()) continue;
                ItemStack stack = slot.getStack();
                String itemId = stack.getItem().toString();
                if (shouldHighlight(itemId, stack.getCount(), remaining)) {
                    cachedHighlightSlots.add(i);
                    continue;
                }
                // 潜影盒内容物
                if (stack.getItem() instanceof BlockItem blockItem &&
                    blockItem.getBlock() instanceof ShulkerBoxBlock) {
                    var container = stack.get(DataComponentTypes.CONTAINER);
                    if (container != null) {
                        for (var stored : container.streamNonEmpty().toList()) {
                            if (shouldHighlight(stored.getItem().toString(), stored.getCount(), remaining)) {
                                cachedHighlightSlots.add(i);
                                break;
                            }
                        }
                    }
                }
                // Bundle 内容物
                var bundleContents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
                if (bundleContents != null) {
                    for (var stored : bundleContents.stream().toList()) {
                        if (shouldHighlight(stored.getItem().toString(), stored.getCount(), remaining)) {
                            cachedHighlightSlots.add(i);
                            break;
                        }
                    }
                }
            }
        }

        // 渲染缓存的高亮格子
        List<Slot> allSlots = this.handler.slots;
        for (int idx : cachedHighlightSlots) {
            if (idx < allSlots.size()) {
                drawHighlight(context, allSlots.get(idx));
            }
        }
    }

    @Unique
    private boolean shouldHighlight(String itemId, int stackCount, Map<String, Integer> remaining) {
        int rem = remaining.getOrDefault(itemId, 0);
        if (rem <= 0) return false;
        remaining.put(itemId, rem - stackCount);
        return true;
    }

    @Unique
    private void drawHighlight(DrawContext context, Slot slot) {
        int slotX = slot.x;
        int slotY = slot.y;
        int color = 0xFFFFAA00;
        context.fill(slotX, slotY - 1, slotX + 16, slotY, color);
        context.fill(slotX, slotY + 16, slotX + 16, slotY + 17, color);
        context.fill(slotX - 1, slotY, slotX, slotY + 16, color);
        context.fill(slotX + 16, slotY, slotX + 17, slotY + 16, color);
    }
}
