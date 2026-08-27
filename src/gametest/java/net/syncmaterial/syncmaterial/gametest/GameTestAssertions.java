package net.syncmaterial.syncmaterial.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

final class GameTestAssertions {
    private GameTestAssertions() {}

    static void assertEquals(GameTestHelper helper, Object expected, Object actual, Component message) {
        helper.assertTrue(java.util.Objects.equals(expected, actual), message);
    }
}
