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

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.block.ShulkerBoxBlock;

import net.syncmaterial.syncmaterial.client.PickupHighlight;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListHudRenderer;

/**
 * 取货指示器：打开箱子时高亮需要取的物品格子。
 *
 * 选格算法与缓存策略都在 PickupHighlight 里（可单测），这里只做两件事：
 * 把 MC 的 Slot 适配成 PickupHighlight.SlotView，以及画边框。
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> {

    @Shadow protected T handler;

    @Inject(method = "drawForeground", at = @At("TAIL"))
    private void onDrawForeground(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        if (!GuiMaterialList.isPickupModeStatic()) {
            // 退出取货模式后缓存必须失效，否则下次开启时会先闪一帧旧高亮
            PickupHighlight.invalidate();
            return;
        }

        Map<String, Integer> needs = MaterialListHudRenderer.getPickupHighlightNeeds();
        List<Slot> slots = this.handler.slots;
        // 一次性快照：内容签名与选格都要读同一份数据，嵌套容器只展开一遍
        List<SlotSnapshot> views = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            views.add(snapshot(slot));
        }

        for (int idx : PickupHighlight.highlightedSlots(needs, this.handler.syncId, views)) {
            if (idx < slots.size()) {
                drawHighlight(context, slots.get(idx));
            }
        }
    }

    @Unique
    private static SlotSnapshot snapshot(Slot slot) {
        if (!slot.hasStack()) {
            return new SlotSnapshot(false, "", 0, List.of());
        }
        ItemStack stack = slot.getStack();
        List<PickupHighlight.StackView> stored = new ArrayList<>();

        if (stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            var container = stack.get(DataComponentTypes.CONTAINER);
            if (container != null) {
                container.streamNonEmpty()
                        .forEach(s -> stored.add(new StackSnapshot(s.getItem().toString(), s.getCount())));
            }
        }
        var bundleContents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundleContents != null) {
            bundleContents.stream()
                    .forEach(s -> stored.add(new StackSnapshot(s.getItem().toString(), s.getCount())));
        }
        return new SlotSnapshot(true, stack.getItem().toString(), stack.getCount(), stored);
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

    /** 槽位内容的不可变快照，脱离 MC 对象以便签名比较稳定 */
    private record SlotSnapshot(boolean present, String itemId, int count,
                                List<PickupHighlight.StackView> contents)
        implements PickupHighlight.SlotView {}

    private record StackSnapshot(String itemId, int count) implements PickupHighlight.StackView {}
}
