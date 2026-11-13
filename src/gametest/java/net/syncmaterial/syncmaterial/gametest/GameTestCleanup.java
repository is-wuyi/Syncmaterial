package net.syncmaterial.syncmaterial.gametest;

import net.minecraft.gametest.framework.GameTestHelper;

/** Native test cleanup ownership differs; this shim never changes production gameplay. */
public final class GameTestCleanup {
    private GameTestCleanup() {}

    public static void register(GameTestHelper helper, Runnable cleanup) {
        //? if >=26.2 {
        helper.runBeforeTestEnd(cleanup);
        //?} else {
        /*try {
            var field = GameTestHelper.class.getDeclaredField("testInfo");
            field.setAccessible(true);
            var test = (net.minecraft.gametest.framework.GameTestInfo) field.get(helper);
            test.addListener(new net.minecraft.gametest.framework.GameTestListener() {
                private boolean closed;

                private void close() {
                    if (closed) return;
                    closed = true;
                    cleanup.run();
                }

                @Override
                public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}

                @Override
                public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,
                                       net.minecraft.gametest.framework.GameTestRunner runner) {
                    close();
                }

                @Override
                public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,
                                       net.minecraft.gametest.framework.GameTestRunner runner) {
                    close();
                }

                @Override
                public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo old,
                                             net.minecraft.gametest.framework.GameTestInfo next,
                                             net.minecraft.gametest.framework.GameTestRunner runner) {
                    close();
                }
            });
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot register native test cleanup", failure);
        }
        *///?}
    }
}
