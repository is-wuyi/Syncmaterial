package net.syncmaterial.syncmaterial.api;

/**
 * 材料进度公式（客户端展示与服务端统计共用，保证口径一致）。
 */
public final class ProgressFormulas {

    private ProgressFormulas() {}

    /**
     * 收集模式缺失数：总数 - 备货区 - 仓库 - 所有参与者背包，下限 0。
     */
    public static long collectedMissing(long total, long stagingCount, long warehouseCount, long playersCount) {
        return Math.max(0, total - stagingCount - warehouseCount - playersCount);
    }

    /**
     * 搬运/取货模式缺失数：还需从仓库搬多少 = min(总数 - 备货区 - 我的背包, 仓库现有量)，下限 0。
     * 不减仓库是因为仓库正是取货来源，上限为仓库现有量。
     */
    public static long pickupMissing(long total, long stagingCount, long myCount, long warehouseCount) {
        return Math.max(0, Math.min(total - stagingCount - myCount, warehouseCount));
    }

    /**
     * 纯背包版缺失数（无协作/备货区/仓库参与时）：总数 - 我的背包，下限 0。
     */
    public static long inventoryOnlyMissing(long total, long playerCount) {
        return Math.max(0, total - playerCount);
    }
}
