//? if >=26 {
package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;
import java.util.Objects;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.screens.Screen;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.Configs.ConfigTab;

/**
 * SyncMaterial 全局设置界面（基于 MaLiLib GuiConfigsBase 自动生成控件）。
 * 入口：Litematica 主菜单 → "共享材料表设置" 按钮。
 */
public class GuiSettings extends GuiConfigsBase {
    private static ConfigTab currentTab = ConfigTab.GENERIC;

    public GuiSettings(Screen parent) {
        super(10, 50, SyncMaterial.MOD_ID, parent,
                "syncmaterial.gui.title.settings", SyncMaterial.MOD_ID);
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();

        int x = 10;
        int y = 26;

        x += this.createTabButton(x, y, ConfigTab.GENERIC);
        x += this.createTabButton(x, y, ConfigTab.HUD);
        x += this.createTabButton(x, y, ConfigTab.RENDER);
    }

    private int createTabButton(int x, int y, ConfigTab tab) {
        String label = StringUtils.translate("syncmaterial.gui.tab." + tab.name().toLowerCase());
        ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, label);
        button.setEnabled(currentTab != tab);
        this.addButton(button, new TabButtonListener(tab, this));
        return button.getWidth() + 2;
    }

    @Override
    protected int getConfigWidth() {
        return switch (currentTab) {
            case GENERIC -> 140;
            case HUD -> 140;
            case RENDER -> 120;
        };
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs = Configs.getTabOptions(currentTab);
        return ConfigOptionWrapper.createFor(configs);
    }

    @Override
    protected void onSettingsChanged() {
        super.onSettingsChanged();
        // 配置变更时立即保存
        Configs.saveToFile();
    }

    private record TabButtonListener(ConfigTab tab, GuiSettings parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            currentTab = this.tab;
            this.parent.reCreateListWidget();
            Objects.requireNonNull(this.parent.getListWidget()).resetScrollbarPosition();
            this.parent.initGui();
        }
    }
}
//?} else {
package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;
import java.util.Objects;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.screen.Screen;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.Configs.ConfigTab;

/**
 * SyncMaterial 全局设置界面（基于 MaLiLib GuiConfigsBase 自动生成控件）。
 * 入口：Litematica 主菜单 → "共享材料表设置" 按钮。
 */
public class GuiSettings extends GuiConfigsBase {
    private static ConfigTab currentTab = ConfigTab.GENERIC;

    public GuiSettings(Screen parent) {
        super(10, 50, SyncMaterial.MOD_ID, parent,
                "syncmaterial.gui.title.settings", SyncMaterial.MOD_ID);
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();

        int x = 10;
        int y = 26;

        x += this.createTabButton(x, y, ConfigTab.GENERIC);
        x += this.createTabButton(x, y, ConfigTab.HUD);
        x += this.createTabButton(x, y, ConfigTab.RENDER);
    }

    private int createTabButton(int x, int y, ConfigTab tab) {
        String label = StringUtils.translate("syncmaterial.gui.tab." + tab.name().toLowerCase());
        ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, label);
        button.setEnabled(currentTab != tab);
        this.addButton(button, new TabButtonListener(tab, this));
        return button.getWidth() + 2;
    }

    @Override
    protected int getConfigWidth() {
        return switch (currentTab) {
            case GENERIC -> 140;
            case HUD -> 140;
            case RENDER -> 120;
        };
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs = Configs.getTabOptions(currentTab);
        return ConfigOptionWrapper.createFor(configs);
    }

    @Override
    protected void onSettingsChanged() {
        super.onSettingsChanged();
        // 配置变更时立即保存
        Configs.saveToFile();
    }

    private record TabButtonListener(ConfigTab tab, GuiSettings parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            currentTab = this.tab;
            this.parent.reCreateListWidget();
            Objects.requireNonNull(this.parent.getListWidget()).resetScrollbarPosition();
            this.parent.initGui();
        }
    }
}
//?}
