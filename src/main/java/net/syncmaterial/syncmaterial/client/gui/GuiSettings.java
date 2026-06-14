package net.syncmaterial.syncmaterial.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.Configs.ConfigTab;

/**
 * SyncMaterial 全局设置界面。
 * 入口：Litematica 主菜单 → "共享材料表设置" 按钮。
 */
public class GuiSettings extends GuiBase {
    private final Screen parent;
    private ConfigTab currentTab = ConfigTab.GENERIC;

    public GuiSettings(Screen parent) {
        this.parent = parent;
        this.title = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.title.settings");
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearWidgets();
        this.createTabButtons();
        this.createConfigEntries();
    }

    private void createTabButtons() {
        int x = 10;
        int y = 4;
        for (ConfigTab tab : ConfigTab.values()) {
            String label = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.tab." + tab.name().toLowerCase());
            int width = this.mc.textRenderer.getWidth(label) + 16;
            boolean selected = tab == currentTab;
            ButtonGeneric button = new ButtonGeneric(x, y, width, 14, label);
            button.setEnabled(!selected);
            addButton(button, (b, mb) -> {
                this.currentTab = tab;
                this.initGui();
            });
            x += width + 2;
        }
    }

    private void createConfigEntries() {
        // TODO: 根据 currentTab 创建配置项控件
    }

    @Override
    public void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        // 背景已在父类绘制
    }

    @Override
    public void close() {
        Configs.saveToFile();
        this.mc.setScreen(this.parent);
    }
}
