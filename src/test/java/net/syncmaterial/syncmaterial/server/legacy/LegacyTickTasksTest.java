package net.syncmaterial.syncmaterial.server.legacy;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LegacyTickTasksTest {
    @Test
    void startupUsesElapsedTimeNotTickCount() {
        AtomicLong time = new AtomicLong();
        AtomicInteger scans = new AtomicInteger();
        LegacyTickTasks tasks = new LegacyTickTasks(time::get, scans::incrementAndGet, () -> {});
        tasks.start();
        for (int tick = 0; tick < 1000; tick++) tasks.tick();
        assertEquals(0, scans.get());
        time.set(Duration.ofSeconds(5).toNanos() - 1);
        tasks.tick();
        assertEquals(0, scans.get());
        time.incrementAndGet();
        tasks.tick();
        assertEquals(1, scans.get());
        time.addAndGet(Duration.ofHours(1).toNanos());
        tasks.tick();
        assertEquals(1, scans.get());
    }

    @Test
    void repeatedStartDoesNotResetDelayOrScanAgain() {
        AtomicLong time = new AtomicLong();
        AtomicInteger scans = new AtomicInteger();
        LegacyTickTasks tasks = new LegacyTickTasks(time::get, scans::incrementAndGet, () -> {});
        tasks.start();
        time.set(Duration.ofSeconds(4).toNanos());
        tasks.start();
        time.set(Duration.ofSeconds(5).toNanos());
        tasks.tick();
        tasks.start();
        tasks.tick();
        assertEquals(1, scans.get());
    }

    @Test
    void closeCancelsPendingAndPreventsRestart() {
        AtomicLong time = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        LegacyTickTasks tasks = new LegacyTickTasks(time::get, calls::incrementAndGet, calls::incrementAndGet);
        tasks.start();
        tasks.close();
        tasks.close();
        time.set(Duration.ofSeconds(10).toNanos());
        tasks.start();
        for (int tick = 0; tick < 20; tick++) tasks.tick();
        assertEquals(0, calls.get());
    }

    @Test
    void noStartupBeforeStarted() {
        AtomicInteger scans = new AtomicInteger();
        LegacyTickTasks tasks = new LegacyTickTasks(() -> Long.MAX_VALUE, scans::incrementAndGet, () -> {});
        for (int tick = 0; tick < 8; tick++) tasks.tick();
        assertEquals(0, scans.get());
    }

    @Test
    void dirtyTicksAreIndependentForEachOwner() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        LegacyTickTasks tasksA = new LegacyTickTasks(() -> 0, () -> {}, a::incrementAndGet);
        LegacyTickTasks tasksB = new LegacyTickTasks(() -> 0, () -> {}, b::incrementAndGet);
        for (int tick = 0; tick < 3; tick++) tasksA.tick();
        tasksB.tick();
        assertEquals(0, a.get());
        assertEquals(0, b.get());
        tasksA.tick();
        assertEquals(1, a.get());
        for (int tick = 0; tick < 7; tick++) tasksB.tick();
        assertEquals(2, b.get());
        assertEquals(1, a.get());
    }

    @Test
    void monotonicClockWrapUsesElapsedDifference() {
        AtomicLong time = new AtomicLong(Long.MAX_VALUE - Duration.ofSeconds(2).toNanos());
        AtomicInteger scans = new AtomicInteger();
        LegacyTickTasks tasks = new LegacyTickTasks(time::get, scans::incrementAndGet, () -> {});
        tasks.start();
        time.addAndGet(Duration.ofSeconds(5).toNanos());
        tasks.tick();
        assertEquals(1, scans.get());
    }
}
