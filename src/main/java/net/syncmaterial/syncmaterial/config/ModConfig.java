package net.syncmaterial.syncmaterial.config;

/**
 * 模组配置类。
 * 包含客户端和服务端的所有配置项。
 * 目前使用静态变量，后续可扩展为文件持久化和UI编辑。
 */
public class ModConfig {

    // ========== 客户端配置项 ==========

    /**
     * 材料统计是否包含无掉落物的方块。
     * 默认关闭，避免统计如基岩、水、熔岩等方块。
     */
    public static boolean includeNonDroppableBlocks = false;

    /**
     * 材料统计是否包含容器内的物品。
     * 默认关闭，以保持与Litematica原生行为一致。
     */
    public static boolean includeContainerItems = false;

    /**
     * 是否开启背包库存缺口对比功能。
     * 默认开启，在材料清单中显示玩家背包持有量与需求量的对比。
     */
    public static boolean enableInventoryGapComparison = true;

    /**
     * 材料清单默认排序规则。
     * 0: 按数量降序, 1: 按数量升序, 2: 按名称首字母
     */
    public static int defaultSortOrder = 0;

    // ========== 服务端配置项 ==========

    /**
     * 是否启用服务端材料统计功能。
     * 默认开启，允许服务端解析并存储原理图材料数据。
     */
    public static boolean enableServerStatistics = true;

    /**
     * 数据库文件路径。
     * 相对于服务端根目录的路径，默认使用 syncmaterial.db。
     */
    public static String databasePath = "syncmaterial.db";

    /**
     * 是否自动解析新上传的原理图。
     * 默认开启，当新原理图上传到服务端时自动进行材料统计。
     */
    public static boolean autoParseUploadedSchematics = true;

    /**
     * 是否在服务端启动时验证数据库完整性。
     * 默认开启，启动时检查并修复数据库问题。
     */
    public static boolean validateDatabaseOnStartup = true;

    /**
     * 数据库连接池大小。
     * 默认5个连接，适用于小型到中型服务器。
     */
    public static int databaseConnectionPoolSize = 5;

    // ========== 工具方法 ==========

    /**
     * 获取排序规则的描述文本。
     */
    public static String getSortOrderDescription(int sortOrder) {
        return switch (sortOrder) {
            case 0 -> "按数量降序";
            case 1 -> "按数量升序";
            case 2 -> "按名称首字母";
            default -> "未知排序规则";
        };
    }

    /**
     * 重置所有配置为默认值。
     */
    public static void resetToDefaults() {
        includeNonDroppableBlocks = false;
        includeContainerItems = false;
        enableInventoryGapComparison = true;
        defaultSortOrder = 0;

        enableServerStatistics = true;
        databasePath = "syncmaterial.db";
        autoParseUploadedSchematics = true;
        validateDatabaseOnStartup = true;
        databaseConnectionPoolSize = 5;
    }
}
