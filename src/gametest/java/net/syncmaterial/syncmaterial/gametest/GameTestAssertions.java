package net.syncmaterial.syncmaterial.gametest;

import java.math.BigInteger;
import java.util.Objects;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

final class GameTestAssertions {
    private GameTestAssertions() {}

    static void assertEquals(GameTestHelper helper, Object expected, Object actual, Component message) {
        helper.assertTrue(equal(expected, actual), message);
    }

    /**
     * 数值按值比较，不比较装箱类型：统计接口返回 long，测试期望常用 int 字面量，
     * 直接 Objects.equals 会把 Integer(48) 与 Long(48) 判为不等。
     *
     * <p>仅对整数类型（Byte/Short/Integer/Long/BigInteger）做精确比较。不走 double
     * 转换，避免 Long.MAX_VALUE 附近两个不同值被舍入成同一个 double；浮点类型仍按
     * 类型严格的 Objects.equals 处理，不会让 48.0 与 48 误判为相等。
     */
    static boolean equal(Object expected, Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            BigInteger leftInteger = integralValue(left);
            BigInteger rightInteger = integralValue(right);
            if (leftInteger != null && rightInteger != null) {
                return leftInteger.equals(rightInteger);
            }
        }
        return Objects.equals(expected, actual);
    }

    private static BigInteger integralValue(Number value) {
        if (value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(value.longValue());
        }
        return value instanceof BigInteger integer ? integer : null;
    }
}
