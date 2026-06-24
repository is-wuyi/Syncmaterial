package net.syncmaterial.syncmaterial.mixin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.block.ShulkerBoxBlock;

import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;

/**
 * Phase 5: 打开箱子时精确高亮需要的材料格子
 * 参考 Litematica MaterialListHudRenderer.highlightSlotsWithItem
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> {

    @Shadow protected T handler;
    @Shadow protected int x;
    @Shadow protected int y;

    @Inject(method = "drawForeground", at = @At("TAIL"))
    private void onDrawForeground(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        // 仅在取货模式下生效
        if (!GuiMaterialList.isPickupModeStatic()) return;

        Set<String> neededItemIds = GuiMaterialList.getPickupModeNeededItemIds();
        if (neededItemIds == null || neededItemIds.isEmpty()) return;

        // 获取缺失数量（用于精确限制高亮数量）
        Map<String, Integer> missingCounts = GuiMaterialList.getPickupModeMissingCounts();
        if (missingCounts == null || missingCounts.isEmpty()) return;

        // 已高亮的物品计数（用于精确限制）
        Map<String, Integer> highlightedCounts = new HashMap<>();

        List<Slot> slots = this.handler.slots;

        for (Slot slot : slots) {
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            String itemId = stack.getItem().toString();

            // 检查是否是需要的物品
            boolean isNeeded = false;
            int missing = missingCounts.getOrDefault(itemId, 0);
            int alreadyHighlighted = highlightedCounts.getOrDefault(itemId, 0);

            if (neededItemIds.contains(itemId) && alreadyHighlighted < missing) {
                isNeeded = true;
                highlightedCounts.merge(itemId, stack.getCount(), Integer::sum);
            }

            // 潜影盒内容物检测（递归深度 1）
            if (!isNeeded && stack.getItem() instanceof BlockItem blockItem &&
                blockItem.getBlock() instanceof ShulkerBoxBlock) {
                var container = stack.get(DataComponentTypes.CONTAINER);
                if (container != null) {
                    for (var stored : container.streamNonEmpty().toList()) {
                        String storedId = stored.getItem().toString();
                        int storedMissing = missingCounts.getOrDefault(storedId, 0);
                        int storedHighlighted = highlightedCounts.getOrDefault(storedId, 0);
                        if (neededItemIds.contains(storedId) && storedHighlighted < storedMissing) {
                            isNeeded = true;
                            highlightedCounts.merge(storedId, stored.getCount(), Integer::sum);
                            break;
                        }
                    }
                }
            }

            if (isNeeded) {
                // 画高亮边框（黄色，参考 Litematica 风格）
                int slotX = slot.x;
                int slotY = slot.y;
                int color = 0xFFFFAA00; // 黄色
                // 上边框
                context.fill(slotX, slotY - 1, slotX + 16, slotY, color);
                // 下边框
                context.fill(slotX, slotY + 16, slotX + 16, slotY + 17, color);
                // 左边框
                context.fill(slotX - 1, slotY, slotX, slotY + 16, color);
                // 右边框
                context.fill(slotX + 16, slotY, slotX + 17, slotY + 16, color);
            }
        }
    }
}
