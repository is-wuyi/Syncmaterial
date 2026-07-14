package net.syncmaterial.syncmaterial.mixin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Inject(method = "drawForeground", at = @At("TAIL"))
    private void onDrawForeground(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        if (!GuiMaterialList.isPickupModeStatic()) return;

        Map<String, Integer> needs = MaterialListHudRenderer.getPickupHighlightNeeds();
        if (needs.isEmpty()) return;

        // 逐格递减的剩余需求量
        Map<String, Integer> remaining = new HashMap<>(needs);

        for (Slot slot : this.handler.slots) {
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            String itemId = stack.getItem().toString();

            if (shouldHighlight(itemId, stack.getCount(), remaining)) {
                drawHighlight(context, slot);
                continue;
            }

            // 潜影盒内容物检测
            if (stack.getItem() instanceof BlockItem blockItem &&
                blockItem.getBlock() instanceof ShulkerBoxBlock) {
                var container = stack.get(DataComponentTypes.CONTAINER);
                if (container != null) {
                    for (var stored : container.streamNonEmpty().toList()) {
                        if (shouldHighlight(stored.getItem().toString(), stored.getCount(), remaining)) {
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
                    if (shouldHighlight(stored.getItem().toString(), stored.getCount(), remaining)) {
                        drawHighlight(context, slot);
                        break;
                    }
                }
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
