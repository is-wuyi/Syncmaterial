package net.syncmaterial.syncmaterial.client.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector.SelectionScope;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector.TargetType;

/**
 * 选区实例的生命周期测试：一次消费、视图围栏、维度围栏、断开空实例。
 * 不启动 Minecraft，玩家维度通过 {@link StagingAreaSelector.DimensionSource} 注入。
 */
class StagingAreaSelectorLifecycleTest {

    private static final BlockPos POS1 = new BlockPos(1, 64, 1);
    private static final BlockPos POS2 = new BlockPos(4, 66, 4);

    private StagingAreaSelector selector;

    @BeforeEach
    void setUp() {
        this.selector = new StagingAreaSelector();
    }

    @AfterEach
    void tearDown() {
        this.selector.reset();
        ClientSessionManager.resetForTesting();
    }

    private static StagingAreaSelector.SelectionCallback counting(AtomicInteger calls) {
        return (name, pos1, pos2) -> calls.incrementAndGet();
    }

    @Test
    void confirm_consumesIntentExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<BlockPos> seen1 = new AtomicReference<>();
        AtomicReference<BlockPos> seen2 = new AtomicReference<>();

        this.selector.start((name, pos1, pos2) -> {
            calls.incrementAndGet();
            seen1.set(pos1);
            seen2.set(pos2);
        }, null, "区域A", POS1, POS2);

        this.selector.confirm();
        this.selector.confirm();
        this.selector.cancel();

        assertEquals(1, calls.get(), "确认只能消费一次，重复 confirm/cancel 不得再次回调");
        assertEquals(POS1, seen1.get());
        assertEquals(POS2, seen2.get());
        assertFalse(this.selector.isActive());
        assertFalse(this.selector.isPendingScreenClose());
        assertNull(this.selector.callbackForTesting(), "callback 必须释放");
        assertNull(this.selector.returnScreenForTesting(), "returnScreen 必须释放");
    }

    @Test
    void cancel_consumesIntentWithoutCallback() {
        AtomicInteger calls = new AtomicInteger();
        this.selector.start(counting(calls), null, "区域A", POS1, POS2);

        this.selector.cancel();
        this.selector.cancel();
        this.selector.confirm();

        assertEquals(0, calls.get());
        assertFalse(this.selector.isActive());
        assertFalse(this.selector.isPendingScreenClose());
        assertNull(this.selector.callbackForTesting());
        assertNull(this.selector.returnScreenForTesting());
    }

    @Test
    void confirm_withStaleViewFenceDropsResultAndReleasesIntent() {
        AtomicInteger calls = new AtomicInteger();
        this.selector.start(counting(calls), null, "区域A", POS1, POS2);
        // 模拟目标项目/仓库已被删除或视图已换代
        this.selector.setViewFenceForTesting(() -> false);

        this.selector.confirm();

        assertEquals(0, calls.get(), "目标视图失效时不得回调旧界面");
        assertFalse(this.selector.isActive());
        assertFalse(this.selector.isPendingScreenClose());
        assertNull(this.selector.callbackForTesting());
        assertNull(this.selector.returnScreenForTesting());
    }

    @Test
    void cancel_withStaleViewFenceDoesNotRestoreOldScreen() {
        Screen returnScreen = mock(Screen.class);
        this.selector.start(counting(new AtomicInteger()), returnScreen, "区域A", POS1, POS2);
        assertSame(returnScreen, this.selector.returnScreenForTesting());
        this.selector.setViewFenceForTesting(() -> false);

        this.selector.cancel();

        assertFalse(this.selector.isActive());
        assertNull(this.selector.returnScreenForTesting(),
                "目标已失效时取消不得保留/恢复旧界面");
    }

    @Test
    void dimensionChange_discardsWithoutCallbackAndClearsCorners() {
        AtomicReference<String> dimension = new AtomicReference<>("minecraft:overworld");
        this.selector.setDimensionSourceForTesting(dimension::get);
        AtomicInteger calls = new AtomicInteger();
        this.selector.start(counting(calls), null, "区域A", POS1, POS2);
        assertEquals("minecraft:overworld", this.selector.startDimensionForTesting());

        dimension.set("minecraft:the_nether");
        assertTrue(this.selector.discardIfDimensionChanged(), "换维度后必须丢弃本次选区");

        assertEquals(0, calls.get(), "换维度不得提交旧角点");
        assertFalse(this.selector.isActive());
        assertNull(this.selector.getPos1());
        assertNull(this.selector.getPos2());
        assertNull(this.selector.callbackForTesting());
        assertNull(this.selector.returnScreenForTesting());
    }

    @Test
    void dimensionChange_beforeConfirmDropsResult() {
        AtomicReference<String> dimension = new AtomicReference<>("minecraft:overworld");
        this.selector.setDimensionSourceForTesting(dimension::get);
        AtomicInteger calls = new AtomicInteger();
        this.selector.start(counting(calls), null, "区域A", POS1, POS2);

        dimension.set("minecraft:the_end");
        this.selector.confirm();

        assertEquals(0, calls.get(), "confirm 自身也要做维度围栏，不能只靠 tick");
        assertFalse(this.selector.isActive());
        assertNull(this.selector.callbackForTesting());
    }

    @Test
    void start_capturesProjectScopeAndEditIntent() {
        this.selector.start(counting(new AtomicInteger()), null, "区域A", POS1, POS2,
                TargetType.STAGING_AREA, "project-1", -1);

        assertEquals(SelectionScope.project("project-1"), this.selector.getScope());
        assertTrue(this.selector.isEditingStagingArea("project-1", "区域A"));
        assertFalse(this.selector.isEditingStagingArea("project-2", "区域A"));

        this.selector.discard();

        assertNull(this.selector.getScope(), "消费后 scope 必须释放");
        assertFalse(this.selector.isEditingStagingArea("project-1", "区域A"));
    }

    @Test
    void start_warehouseTargetCapturesGlobalScope() {
        this.selector.start(counting(new AtomicInteger()), null, "仓库A", POS1, POS2,
                TargetType.WAREHOUSE, "project-ignored", 7);

        assertNotNull(this.selector.getScope());
        assertTrue(this.selector.getScope().global(), "仓库编辑属于 global scope，不隶属任何项目");
        assertTrue(this.selector.isEditingWarehouse(7));
        assertFalse(this.selector.isEditingStagingArea("project-ignored", "仓库A"));
    }

    @Test
    void withoutSession_getInstanceIsDetachedAndNeverActivates() {
        ClientSessionManager.resetForTesting();
        StagingAreaSelector detached = StagingAreaSelector.getInstance();
        AtomicInteger calls = new AtomicInteger();

        detached.start(counting(calls), null, "区域A", POS1, POS2);
        detached.confirm();
        detached.cancel();
        detached.onTick();

        assertFalse(detached.isActive(), "无会话的断开空实例不得激活");
        assertEquals(0, calls.get());
        assertNull(detached.callbackForTesting());
    }
}
