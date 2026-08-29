package net.syncmaterial.syncmaterial.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import net.syncmaterial.syncmaterial.client.PickupHighlight;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListHudRenderer;

/**
 * 取货指示器：打开箱子时高亮需要取的物品格子。
 *
 * 选格算法与缓存策略都在 PickupHighlight 里（可单测），这里只做两件事：
 * 把 MC 的 Slot 适配成 PickupHighlight.SlotView，以及画边框。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin<T extends AbstractContainerMenu> {

    @Shadow protected T menu;

    @Inject(method = "extractLabels", at = @At("TAIL"))
    private void onDrawForeground(GuiGraphicsExtractor context, int mouseX, int mouseY, CallbackInfo ci) {
        if (!GuiMaterialList.isPickupModeStatic()) {
            // 退出取货模式后缓存必须失效，否则下次开启时会先闪一帧旧高亮
            PickupHighlight.invalidate();
            return;
        }

        Map<String, Integer> needs = MaterialListHudRenderer.getPickupHighlightNeeds();
        List<Slot> slots = this.menu.slots;
        // 一次性快照：内容签名与选格都要读同一份数据，嵌套容器只展开一遍
        List<SlotSnapshot> views = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            views.add(snapshot(slot));
        }

        for (int idx : PickupHighlight.highlightedSlots(needs, this.menu.containerId, views)) {
            if (idx < slots.size()) {
                drawHighlight(context, slots.get(idx));
            }
        }
    }

    @Unique
    private static SlotSnapshot snapshot(Slot slot) {
        if (!slot.hasItem()) {
            return new SlotSnapshot(false, "", 0, List.of());
        }
        ItemStack stack = slot.getItem();
        List<PickupHighlight.StackView> stored = new ArrayList<>();

        if (stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            var container = stack.get(DataComponents.CONTAINER);
            if (container != null) {
                container.nonEmptyItemCopyStream()
                        .forEach(s -> stored.add(new StackSnapshot(s.getItem().toString(), s.getCount())));
            }
        }
        var bundleContents = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundleContents != null) {
            bundleContents.itemCopyStream()
                    .forEach(s -> stored.add(new StackSnapshot(s.getItem().toString(), s.getCount())));
        }
        return new SlotSnapshot(true, stack.getItem().toString(), stack.getCount(), stored);
    }

    @Unique
    private void drawHighlight(GuiGraphicsExtractor context, Slot slot) {
        int slotX = slot.x;
        int slotY = slot.y;
        int color = 0xFFFFAA00;
        context.fill(slotX, slotY - 1, slotX + 16, slotY, color);
        context.fill(slotX, slotY + 16, slotX + 16, slotY + 17, color);
        context.fill(slotX - 1, slotY, slotX, slotY + 16, color);
        context.fill(slotX + 16, slotY, slotX + 17, slotY + 16, color);
    }

    /** 槽位内容的不可变快照，脱离 MC 对象以便签名比较稳定 */
    private record SlotSnapshot(boolean present, String itemId, int count,
                                List<PickupHighlight.StackView> contents)
        implements PickupHighlight.SlotView {}

    private record StackSnapshot(String itemId, int count) implements PickupHighlight.StackView {}
}
