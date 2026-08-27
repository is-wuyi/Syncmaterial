package net.syncmaterial.syncmaterial.mixin;

import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.syncmaterial.syncmaterial.client.gui.GuiSettings;
import net.syncmaterial.syncmaterial.client.gui.GuiWarehouseManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GuiMainMenu.class, remap = false)
public abstract class MixinLitematicaMainMenu extends GuiBase {

    @Inject(method = "initGui", at = @At("RETURN"), remap = false)
    private void addSyncMaterialButtons(CallbackInfo ci) {
        int buttonWidth = sm_getButtonWidth();
        // 放在与 Litematica Configuration 按钮同列下方
        int x = 12 + buttonWidth + 20;
        int y = 184;

        // 设置按钮
        String settingsLabel = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.button.settings");
        ButtonGeneric settingsBtn = new ButtonGeneric(x, y, buttonWidth, 20, settingsLabel);
        addButton(settingsBtn, (ButtonBase b, int mb) -> {
            mc.setScreenAndShow(new GuiSettings((GuiMainMenu) (Object) this));
        });

        // Phase 5: 仓库管理按钮
        y += 24;
        String warehouseLabel = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.title.warehouse_manager");
        ButtonGeneric warehouseBtn = new ButtonGeneric(x, y, buttonWidth, 20, warehouseLabel);
        addButton(warehouseBtn, (ButtonBase b, int mb) -> {
            mc.setScreenAndShow(new GuiWarehouseManager());
        });
    }

    @Unique
    private int sm_getButtonWidth() {
        String label = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.button.settings");
        return getStringWidth(label) + 20;
    }
}
