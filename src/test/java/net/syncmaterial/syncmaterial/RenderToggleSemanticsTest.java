package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.gui.MaterialListBase;
import net.syncmaterial.syncmaterial.client.gui.MaterialListHudRenderer;

/**
 * HUD 与仓库线框的"总闸 / 分闸"语义测试。
 *
 * 约定：总闸（配置项 + 热键）只参与渲染判断，绝不写入分闸状态；
 * 关掉总闸后分闸保持原值，重新打开总闸时分闸原样恢复。
 */
class RenderToggleSemanticsTest {

    private static final String SERVER = "semantics-test-server";

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @AfterEach
    void tearDown() {
        Configs.Generic.HUD_ENABLED.setBooleanValue(true);
        Configs.Generic.WAREHOUSE_RENDER_ENABLED.setBooleanValue(true);
        Configs.clearHiddenWarehouses();
    }

    private static MaterialListHudRenderer hudRenderer() {
        return new MaterialListHudRenderer(mock(MaterialListBase.class));
    }

    // ========== HUD 分闸 ==========

    @Test
    void hud_subToggleDefaultsToOn() {
        assertTrue(hudRenderer().getShouldRender(),
            "HUD 分闸应有独立默认值 true，不派生于总闸");
    }

    @Test
    void hud_masterToggleOff_doesNotMutateSubToggle() {
        var renderer = hudRenderer();
        assertTrue(renderer.getShouldRender());

        Configs.Generic.HUD_ENABLED.setBooleanValue(false);

        assertTrue(renderer.getShouldRender(),
            "关闭总闸不得篡改分闸状态（分闸仍应为开）");
    }

    @Test
    void hud_subToggleOff_survivesMasterToggleCycle() {
        var renderer = hudRenderer();
        renderer.setShouldRender(false);

        // 总闸关 → 开，走完整一轮
        Configs.Generic.HUD_ENABLED.setBooleanValue(false);
        Configs.Generic.HUD_ENABLED.setBooleanValue(true);

        assertFalse(renderer.getShouldRender(),
            "手动关闭的分闸不应被总闸的开关循环重置为开");
    }

    @Test
    void hud_bothMustBeOnToRender() {
        var renderer = hudRenderer();

        // 分闸开 + 总闸开 → 渲染
        Configs.Generic.HUD_ENABLED.setBooleanValue(true);
        renderer.setShouldRender(true);
        assertTrue(Configs.Generic.HUD_ENABLED.getBooleanValue() && renderer.getShouldRender());

        // 总闸关 → 不渲染（分闸仍开）
        Configs.Generic.HUD_ENABLED.setBooleanValue(false);
        assertFalse(Configs.Generic.HUD_ENABLED.getBooleanValue() && renderer.getShouldRender());
        assertTrue(renderer.getShouldRender(), "分闸未被改动");

        // 总闸开 + 分闸关 → 不渲染
        Configs.Generic.HUD_ENABLED.setBooleanValue(true);
        renderer.setShouldRender(false);
        assertFalse(Configs.Generic.HUD_ENABLED.getBooleanValue() && renderer.getShouldRender());
    }

    @Test
    void hud_toggleFlipsOnlySubToggle() {
        var renderer = hudRenderer();
        boolean masterBefore = Configs.Generic.HUD_ENABLED.getBooleanValue();

        renderer.toggleShouldRender();

        assertFalse(renderer.getShouldRender());
        assertEquals(masterBefore, Configs.Generic.HUD_ENABLED.getBooleanValue(),
            "切换分闸不得反向影响总闸");
    }

    // ========== 仓库线框分闸（同一语义） ==========

    @Test
    void warehouse_masterToggleOff_doesNotMutateHiddenSet() {
        Configs.setWarehouseHidden(SERVER, 7, true);

        Configs.Generic.WAREHOUSE_RENDER_ENABLED.setBooleanValue(false);
        Configs.Generic.WAREHOUSE_RENDER_ENABLED.setBooleanValue(true);

        assertTrue(Configs.isWarehouseHidden(SERVER, 7),
            "总闸开关循环后，单独隐藏的仓库应保持隐藏");
        assertFalse(Configs.isWarehouseHidden(SERVER, 8),
            "未被隐藏的仓库不受影响");
    }

    @Test
    void warehouse_hiddenStateIsolatedPerServer() {
        Configs.setWarehouseHidden(SERVER, 3, true);

        assertTrue(Configs.isWarehouseHidden(SERVER, 3));
        assertFalse(Configs.isWarehouseHidden("another-server", 3),
            "仓库 ID 是服务端自增值，跨服不得互相影响");
    }
}
