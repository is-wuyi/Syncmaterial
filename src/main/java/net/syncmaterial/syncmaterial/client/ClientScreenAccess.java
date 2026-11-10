package net.syncmaterial.syncmaterial.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 双版本屏幕访问的唯一接线点。
 *
 * <p>26.2 移除了 {@code Minecraft.screen} 公共字段，改为 {@code gui.screen()} 与
 * {@code setScreenAndShow()}；1.21.7/1.21.8 仍是字段 + {@code setScreen()}。
 * 业务代码只调用本类，不再散落版本判断，也不直接触碰 {@code mc.gui}。</p>
 *
 * <p>{@code showScreen(mc, null)} 合法，表示关闭当前界面。</p>
 */
public final class ClientScreenAccess {
    private ClientScreenAccess() {
    }

    /** 打开（或关闭，传 null）界面。mc 为 null 时静默忽略。 */
    public static void showScreen(Minecraft mc, Screen screen) {
        if (mc == null) return;
        //? if >=26.2 {
        mc.setScreenAndShow(screen);
        //?} else {
        /*mc.setScreen(screen);
        *///?}
    }

    /** 当前界面；没有打开界面或 mc 尚未初始化时返回 null。 */
    public static Screen currentScreen(Minecraft mc) {
        if (mc == null) return null;
        //? if >=26.2 {
        return mc.gui == null ? null : mc.gui.screen();
        //?} else {
        /*return mc.screen;
        *///?}
    }
}
