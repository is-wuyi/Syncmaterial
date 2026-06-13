package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.SyncMaterial;

import java.io.File;
import java.sql.*;
import java.util.Map;

/**
 * SQLite数据库管理器
 * 负责数据库连接、表创建和基本维护操作
 */
public class SchematicDatabase implements AutoCloseable {
    private static final String DB_FILE = "syncmaterial.db";
    private Connection connection;

    // 手动注册 SQLite JDBC 驱动
    static {
        try {
            Class.forName("org.sqlite.JDBC");
            SyncMaterial.LOGGER.debug("SQLite JDBC 驱动已注册");
        } catch (ClassNotFoundException e) {
            SyncMaterial.LOGGER.error("无法加载 SQLite JDBC 驱动", e);
        }
    }

    /**
     * 初始化数据库连接和表结构
     */
    public void initialize() {
        try {
            // 创建数据库连接
            String dbPath = getDatabasePath();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

            // 启用WAL模式提升并发性能
            executePragma("PRAGMA journal_mode=WAL;");
            executePragma("PRAGMA synchronous=NORMAL;");

            // 创建表结构
            createTables();

            SyncMaterial.LOGGER.info("数据库初始化完成: {}", dbPath);

        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("数据库初始化失败", e);
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    /**
     * 获取数据库文件路径
     */
    private String getDatabasePath() {
        File serverDir = new File(".");
        return new File(serverDir, DB_FILE).getAbsolutePath();
    }

    /**
     * 创建数据库表
     */
    private void createTables() throws SQLException {
        // 原理图基础信息表
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS schematics (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                uploaded_by TEXT,
                allow_self_claim INTEGER DEFAULT 1,
                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
            );
            """);

        // 材料条目表
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS material_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                count INTEGER NOT NULL,
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE
            );
            """);

        // 协作认领记录表 (Phase 1: 协作式)
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                material_id INTEGER NOT NULL,
                player_name TEXT NOT NULL,
                status TEXT DEFAULT 'active',  -- active / abandoned / completed
                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                FOREIGN KEY (material_id) REFERENCES material_entries(id) ON DELETE CASCADE
            );
            """);

        // 副负责人表 (Phase 4: 负责人管理)
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS deputy_owners (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                player_name TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                UNIQUE(schematic_id, player_name)
            );
            """);

        // 备货区区域定义表 (Phase 2: 备货区集成)
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS staging_areas (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                world TEXT NOT NULL,
                x1 INTEGER, y1 INTEGER, z1 INTEGER,
                x2 INTEGER, y2 INTEGER, z2 INTEGER,
                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE
            );
            """);

        // 安全迁移：给 staging_areas 表添加 name 列（如果不存在）
        try {
            boolean hasName = false;
            try (var rs = executeQuery("PRAGMA table_info(staging_areas)")) {
                while (rs.next()) {
                    if ("name".equals(rs.getString("name"))) {
                        hasName = true;
                        break;
                    }
                }
            }
            if (!hasName) {
                executeUpdate("ALTER TABLE staging_areas ADD COLUMN name TEXT NOT NULL DEFAULT '未命名'");
                SyncMaterial.LOGGER.info("数据库迁移：staging_areas 表已添加 name 列");
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.warn("数据库迁移：检查 staging_areas.name 列时出错", e);
        }

        // 备货区内容物实时统计表 (Phase 2: 备货区集成)
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS staging_area_inventory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                staging_area_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                count INTEGER NOT NULL,
                FOREIGN KEY (staging_area_id) REFERENCES staging_areas(id) ON DELETE CASCADE
            );
            """);

        // 玩家背包缓存表 (Phase 2: 背包数据持久化)
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS player_inventories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                player_name TEXT NOT NULL,
                material_id INTEGER NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                UNIQUE(schematic_id, player_name, material_id)
            );
            """);

        // 创建索引以提升查询性能
        createIndexes();
    }

    /**
     * 创建数据库索引
     */
    private void createIndexes() throws SQLException {
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_material_entries_schematic ON material_entries(schematic_id);");
        
        // Claims indexes
        executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_claim_unique ON claims(schematic_id, material_id, player_name);");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_claims_schematic ON claims(schematic_id);");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_claims_player ON claims(player_name);");

        // Deputy owners indexes (Phase 4)
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_deputy_owners_schematic ON deputy_owners(schematic_id);");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_deputy_owners_player ON deputy_owners(player_name);");
    }

    /**
     * 执行UPDATE/INSERT/DELETE语句
     */
    public void executeUpdate(String sql, Object... params) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            setParameters(stmt, params);
            stmt.executeUpdate();
        }
    }

    /**
     * 开始事务
     */
    public void beginTransaction() throws SQLException {
        connection.setAutoCommit(false);
    }

    /**
     * 提交事务
     */
    public void commitTransaction() throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

    /**
     * 回滚事务
     */
    public void rollbackTransaction() throws SQLException {
        try {
            connection.rollback();
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * 执行PRAGMA语句（可能返回结果）
     */
    public void executePragma(String sql) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            boolean hasResultSet = stmt.execute();
            if (hasResultSet) {
                // 消费结果集（如果有的话）
                try (ResultSet rs = stmt.getResultSet()) {
                    // 简单消费结果，不处理具体内容
                    while (rs.next()) {
                        // 什么都不做，只是消费结果
                    }
                }
            }
        }
    }

    /**
     * 执行查询并返回结果。
     * 返回的 QueryResult 实现 AutoCloseable，关闭时同时释放 ResultSet 和 PreparedStatement。
     */
    public QueryResult executeQuery(String sql, Object... params) throws SQLException {
        PreparedStatement stmt = connection.prepareStatement(sql);
        setParameters(stmt, params);
        try {
            ResultSet rs = stmt.executeQuery();
            return new QueryResult(rs, stmt);
        } catch (SQLException e) {
            stmt.close();
            throw e;
        }
    }

    /**
     * 查询结果封装，持有 ResultSet 和其背后的 PreparedStatement，
     * 关闭时同时释放两者，防止 Statement 资源泄漏。
     */
    public static class QueryResult implements AutoCloseable {
        private final ResultSet resultSet;
        private final PreparedStatement statement;

        QueryResult(ResultSet resultSet, PreparedStatement statement) {
            this.resultSet = resultSet;
            this.statement = statement;
        }

        public ResultSet getResultSet() {
            return resultSet;
        }

        // 代理 ResultSet 常用方法
        public boolean next() throws SQLException { return resultSet.next(); }
        public String getString(String columnLabel) throws SQLException { return resultSet.getString(columnLabel); }
        public String getString(int columnIndex) throws SQLException { return resultSet.getString(columnIndex); }
        public int getInt(String columnLabel) throws SQLException { return resultSet.getInt(columnLabel); }
        public int getInt(int columnIndex) throws SQLException { return resultSet.getInt(columnIndex); }
        public long getLong(String columnLabel) throws SQLException { return resultSet.getLong(columnLabel); }
        public long getLong(int columnIndex) throws SQLException { return resultSet.getLong(columnIndex); }

        @Override
        public void close() throws SQLException {
            try {
                resultSet.close();
            } finally {
                statement.close();
            }
        }
    }

    /**
     * 执行查询并使用Consumer处理结果（自动管理资源）
     */
    public void executeQueryAndProcess(String sql, ResultSetConsumer consumer, Object... params) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            setParameters(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                consumer.accept(rs);
            }
        }
    }

    @FunctionalInterface
    public interface ResultSetConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    /**
     * 设置PreparedStatement参数
     */
    private void setParameters(PreparedStatement stmt, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
        }
    }

    /**
     * 加载指定原理图的所有玩家背包数据
     */
    public Map<String, Map<Integer, Integer>> loadPlayerInventories(String schematicId) throws SQLException {
        Map<String, Map<Integer, Integer>> result = new java.util.HashMap<>();
        try (var rs = executeQuery(
            "SELECT player_name, material_id, count FROM player_inventories WHERE schematic_id = ?",
            schematicId
        )) {
            while (rs.next()) {
                String playerName = rs.getString("player_name");
                int materialId = rs.getInt("material_id");
                int count = rs.getInt("count");
                result.computeIfAbsent(playerName, k -> new java.util.HashMap<>()).put(materialId, count);
            }
        }
        return result;
    }

    /**
     * 插入或更新玩家背包数据
     */
    public void upsertPlayerInventory(String schematicId, String playerName, int materialId, int count) throws SQLException {
        executeUpdate(
            "INSERT INTO player_inventories (schematic_id, player_name, material_id, count) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT(schematic_id, player_name, material_id) DO UPDATE SET count = ?, updated_at = (strftime('%s', 'now') * 1000)",
            schematicId, playerName, materialId, count, count
        );
    }

    // ========== 副负责人管理 (Phase 4) ==========

    /**
     * 获取主负责人名称
     */
    public String getUploadedBy(String schematicId) throws SQLException {
        try (var rs = executeQuery(
            "SELECT uploaded_by FROM schematics WHERE id = ?",
            schematicId
        )) {
            if (rs.next()) {
                String name = rs.getString("uploaded_by");
                return name != null ? name : "";
            }
            return "";
        }
    }

    /**
     * 检查是否是主负责人
     */
    public boolean isMainOwner(String schematicId, String playerName) throws SQLException {
        try (var rs = executeQuery(
            "SELECT 1 FROM schematics WHERE id = ? AND uploaded_by = ?",
            schematicId, playerName
        )) {
            return rs.next();
        }
    }

    /**
     * 检查是否是负责人（主负责人或副负责人）
     */
    public boolean isOwner(String schematicId, String playerName) throws SQLException {
        if (isMainOwner(schematicId, playerName)) return true;
        return isDeputyOwner(schematicId, playerName);
    }

    /**
     * 添加副负责人
     */
    public void addDeputyOwner(String schematicId, String playerName) throws SQLException {
        executeUpdate(
            "INSERT OR IGNORE INTO deputy_owners (schematic_id, player_name) VALUES (?, ?)",
            schematicId, playerName
        );
    }

    /**
     * 移除副负责人
     */
    public void removeDeputyOwner(String schematicId, String playerName) throws SQLException {
        executeUpdate(
            "DELETE FROM deputy_owners WHERE schematic_id = ? AND player_name = ?",
            schematicId, playerName
        );
    }

    /**
     * 检查是否是副负责人
     */
    public boolean isDeputyOwner(String schematicId, String playerName) throws SQLException {
        try (var rs = executeQuery(
            "SELECT 1 FROM deputy_owners WHERE schematic_id = ? AND player_name = ?",
            schematicId, playerName
        )) {
            return rs.next();
        }
    }

    /**
     * 获取所有副负责人
     */
    public java.util.List<String> getDeputyOwners(String schematicId) throws SQLException {
        java.util.List<String> result = new java.util.ArrayList<>();
        executeQueryAndProcess(
            "SELECT player_name FROM deputy_owners WHERE schematic_id = ?",
            rs -> {
                while (rs.next()) {
                    result.add(rs.getString("player_name"));
                }
            },
            schematicId
        );
        return result;
    }

    /**
     * 转让主负责人
     */
    public void transferOwnership(String schematicId, String newOwnerName) throws SQLException {
        executeUpdate(
            "UPDATE schematics SET uploaded_by = ? WHERE id = ?",
            newOwnerName, schematicId
        );
    }

    /**
     * 设置自行认领开关
     */
    public void setAllowSelfClaim(String schematicId, boolean allow) throws SQLException {
        executeUpdate(
            "UPDATE schematics SET allow_self_claim = ? WHERE id = ?",
            allow ? 1 : 0, schematicId
        );
    }

    /**
     * 获取自行认领开关状态
     */
    public boolean getAllowSelfClaim(String schematicId) throws SQLException {
        try (var rs = executeQuery(
            "SELECT allow_self_claim FROM schematics WHERE id = ?",
            schematicId
        )) {
            return rs.next() && rs.getInt("allow_self_claim") == 1;
        }
    }

    /**
     * 关闭数据库连接
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                SyncMaterial.LOGGER.info("数据库连接已关闭");
            } catch (SQLException e) {
                SyncMaterial.LOGGER.error("关闭数据库连接失败", e);
            }
        }
    }
}