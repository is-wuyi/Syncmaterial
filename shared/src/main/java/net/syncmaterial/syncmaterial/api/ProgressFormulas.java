package net.syncmaterial.syncmaterial.api;

/**
 * 材料进度公式（客户端展示与服务端统计共用，保证口径一致）。
 *
 * <p>所有输入必须是已验证的非负数量；负数属于调用方缺陷，直接抛出
 * {@link IllegalArgumentException}，不做静默截断。差值在 {@code 0} 处饱和，
 * 不会因为减法下溢变成更大的正数；累加使用 {@link #saturatingAdd(long, long)}
 * 在 {@link Long#MAX_VALUE} 处饱和，不会溢出回绕。</p>
 */
public final class ProgressFormulas {

    private ProgressFormulas() {}

    /**
     * 收集模式缺失数：总数 - 备货区 - 仓库 - 所有参与者背包，下限 0。
     */
    public static long collectedMissing(long total, long stagingCount, long warehouseCount, long playersCount) {
        long remaining = nonNegative("total", total);
        remaining = saturatingSubtract(remaining, nonNegative("stagingCount", stagingCount));
        remaining = saturatingSubtract(remaining, nonNegative("warehouseCount", warehouseCount));
        return saturatingSubtract(remaining, nonNegative("playersCount", playersCount));
    }

    /**
     * 搬运/取货模式缺失数：还需从仓库搬多少 = min(总数 - 备货区 - 我的背包, 仓库现有量)，下限 0。
     * 不减仓库是因为仓库正是取货来源，上限为仓库现有量。
     */
    public static long pickupMissing(long total, long stagingCount, long myCount, long warehouseCount) {
        long remaining = nonNegative("total", total);
        remaining = saturatingSubtract(remaining, nonNegative("stagingCount", stagingCount));
        remaining = saturatingSubtract(remaining, nonNegative("myCount", myCount));
        return Math.min(remaining, nonNegative("warehouseCount", warehouseCount));
    }

    /**
     * 纯背包版缺失数（无协作/备货区/仓库参与时）：总数 - 我的背包，下限 0。
     */
    public static long inventoryOnlyMissing(long total, long playerCount) {
        return saturatingSubtract(nonNegative("total", total), nonNegative("playerCount", playerCount));
    }

    /**
     * 非负数量的安全累加：超过 {@link Long#MAX_VALUE} 时饱和，不溢出回绕。
     *
     * @throws IllegalArgumentException 任一输入为负
     */
    public static long saturatingAdd(long left, long right) {
        long a = nonNegative("left", left);
        long b = nonNegative("right", right);
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    /**
     * 非负数量的安全差值：{@code minuend <= subtrahend} 时返回 0，否则返回精确差值。
     *
     * @throws IllegalArgumentException 任一输入为负
     */
    public static long saturatingSubtract(long minuend, long subtrahend) {
        long a = nonNegative("minuend", minuend);
        long b = nonNegative("subtrahend", subtrahend);
        return a <= b ? 0L : a - b;
    }

    private static long nonNegative(String field, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be >= 0");
        }
        return value;
    }
}
