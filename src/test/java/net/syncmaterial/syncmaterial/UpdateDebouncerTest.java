package net.syncmaterial.syncmaterial;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.client.gui.UpdateDebouncer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发包去抖测试。
 *
 * 锁定的问题：坐标文本框每敲一个字符发一次包，而服务端每次 UPDATE 都要全量
 * 重扫区域并全服广播。这些用例确保连续输入只产生一次发包，同时确保
 * "关界面"与"按钮操作"这两条不该等待的路径仍然立即发出。
 */
class UpdateDebouncerTest {

    /** 记录发包次数与内容，替代真实网络 */
    private final List<String> sent = new ArrayList<>();

    private Runnable send(String value) {
        return () -> sent.add(value);
    }

    private static void tick(UpdateDebouncer debouncer, int times) {
        for (int i = 0; i < times; i++) {
            debouncer.tick();
        }
    }

    @Test
    void consecutiveEdits_collapseToSingleSend() {
        UpdateDebouncer debouncer = new UpdateDebouncer(10);
        // 模拟把坐标从 100 改成 -2048 的七个中间态，每次按键间隔 2 tick
        for (String intermediate : List.of("10", "1", "-", "-2", "-20", "-204", "-2048")) {
            debouncer.schedule(send(intermediate));
            tick(debouncer, 2);
        }
        assertTrue(sent.isEmpty(), "静默期未满不应发包");

        tick(debouncer, 10);
        assertEquals(List.of("-2048"), sent, "连续输入只应发出最终值，中间态全部丢弃");
    }

    @Test
    void quietPeriodElapsed_sendsOnce() {
        UpdateDebouncer debouncer = new UpdateDebouncer(5);
        debouncer.schedule(send("a"));
        tick(debouncer, 5);
        assertEquals(List.of("a"), sent);

        // 已发出后继续 tick 不应重复发送
        tick(debouncer, 20);
        assertEquals(List.of("a"), sent, "发出后不得重复发送");
    }

    @Test
    void scheduleResetsIdleCounter() {
        UpdateDebouncer debouncer = new UpdateDebouncer(5);
        debouncer.schedule(send("a"));
        tick(debouncer, 4);
        // 第 5 tick 前又来一次输入，计时必须归零
        debouncer.schedule(send("b"));
        tick(debouncer, 4);
        assertTrue(sent.isEmpty(), "新输入必须重置静默计时");

        tick(debouncer, 1);
        assertEquals(List.of("b"), sent);
    }

    @Test
    void flushNow_sendsImmediately() {
        UpdateDebouncer debouncer = new UpdateDebouncer(20);
        debouncer.schedule(send("pending"));
        debouncer.flushNow();
        assertEquals(List.of("pending"), sent, "关界面必须立即发出，否则丢掉刚敲的值");
        assertFalse(debouncer.hasPending());
    }

    @Test
    void flushNow_withoutPending_isNoop() {
        UpdateDebouncer debouncer = new UpdateDebouncer(10);
        debouncer.flushNow();
        assertTrue(sent.isEmpty(), "无待发送动作时 flush 不应发包");
    }

    @Test
    void flushNow_clearsPendingSoTickWontResend() {
        UpdateDebouncer debouncer = new UpdateDebouncer(10);
        debouncer.schedule(send("x"));
        debouncer.flushNow();
        tick(debouncer, 30);
        assertEquals(List.of("x"), sent, "flush 后 tick 不得再发一次");
    }

    @Test
    void discard_dropsWithoutSending() {
        UpdateDebouncer debouncer = new UpdateDebouncer(10);
        debouncer.schedule(send("x"));
        debouncer.discard();
        tick(debouncer, 30);
        assertTrue(sent.isEmpty(), "discard 后不应发包");
        assertFalse(debouncer.hasPending());
    }

    @Test
    void hasPending_reflectsState() {
        UpdateDebouncer debouncer = new UpdateDebouncer(3);
        assertFalse(debouncer.hasPending());
        debouncer.schedule(send("x"));
        assertTrue(debouncer.hasPending());
        tick(debouncer, 3);
        assertFalse(debouncer.hasPending(), "发出后应回到无待发送状态");
    }

    @Test
    void tickWithoutSchedule_isNoop() {
        UpdateDebouncer debouncer = new UpdateDebouncer(1);
        tick(debouncer, 100);
        assertTrue(sent.isEmpty());
    }

    @Test
    void quietTicksMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new UpdateDebouncer(0));
        assertThrows(IllegalArgumentException.class, () -> new UpdateDebouncer(-1));
    }

    @Test
    void separateEdits_sendSeparately() {
        UpdateDebouncer debouncer = new UpdateDebouncer(5);
        debouncer.schedule(send("first"));
        tick(debouncer, 5);
        debouncer.schedule(send("second"));
        tick(debouncer, 5);
        assertEquals(List.of("first", "second"), sent,
            "两次间隔足够的编辑应各发一次，去抖不得吞掉真实的第二次修改");
    }
}
