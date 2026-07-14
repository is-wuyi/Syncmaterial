package net.syncmaterial.syncmaterial.mixin;

import java.util.List;

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

import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;

/**
 * 取货指示器：打开箱子时高亮需要取的物品格子
 * 遍历容器格子，用实时取货公式逐格递减，直到满足需求为止。
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> {

    @Shadow protected T handler;

    @Inject(method = "drawForeground", at = @At("TAIL"))
    private void onDrawForeground(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        if (!GuiMaterialList.isPickupModeStatic()) return;

        var activeList = SyncMaterialClient.getActiveMaterialList();
        if (activeList == null) return;
        var entries = activeList.getMaterialsAll();
        java.util.Map<String, Integer> remaining = new java.util.HashMap<>();

        List<Slot> slots = this.handler.slots;

        for (Slot slot : slots) {
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            String itemId = stack.getItem().toString();

            if (shouldHighlight(itemId, stack.getCount(), entries, remaining)) {
                drawHighlight(context, slot);
                continue;
            }

            // 潜影盒内容物检测
            if (stack.getItem() instanceof BlockItem blockItem &&
                blockItem.getBlock() instanceof ShulkerBoxBlock) {
                var container = stack.get(DataComponentTypes.CONTAINER);
                if (container != null) {
                    for (var stored : container.streamNonEmpty().toList()) {
                        if (shouldHighlight(stored.getItem().toString(), stored.getCount(), entries, remaining)) {
                            drawHighlight(context, slot);
                            break;
                        }
                    }
                }
            }

            // Bundle 内容物检测
            var bundleContents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundleContents != null) {
                for (var stored : bundleContents.stream().toList()) {
                    if (shouldHighlight(stored.getItem().toString(), stored.getCount(), entries, remaining)) {
                        drawHighlight(context, slot);
                        break;
                    }
                }
            }
        }
    }

    /**
     * 判断某个物品是否还需要高亮。
     * 对每个 materialList 条目，用 HUD 相同公式算出还需取货量，
     * 再按格子顺序递减，若还有剩余则高亮。
     */
    @Unique
    private boolean shouldHighlight(String itemId, int stackCount, java.util.List<MaterialListEntry> entries, java.util.Map<String, Integer> remaining) {
        for (MaterialListEntry entry : entries) {
            if (!entry.getStack().getItem().toString().equals(itemId)) continue;

            int pickupMissing = Math.max(0,
                Math.min(entry.getCountTotal() - entry.getStagingCount() - entry.getCountAvailable(),
                         entry.getWarehouseCount()));
            if (pickupMissing <= 0) continue;

            // 用已渲染的剩余需求量（逐格递减）
            int rem = remaining.getOrDefault(itemId, pickupMissing);
            if (rem > 0) {
                remaining.put(itemId, rem - stackCount);
                return true;
            }
        }
        return false;
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
