package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.SyncMaterial;

import java.io.File;
import java.sql.*;

/**
 * SQLite数据库管理器
 * 负责数据库连接、表创建和基本维护操作
 */
public class SchematicDatabase {
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

        // 认领记录表
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                material_id INTEGER NOT NULL,
                player_name TEXT NOT NULL,
                claimed_count INTEGER NOT NULL,
                status TEXT DEFAULT 'active',
                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                FOREIGN KEY (material_id) REFERENCES material_entries(id) ON DELETE CASCADE
            );
            """);

        // 分配记录表
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS assignments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                material_id INTEGER NOT NULL,
                assignee_name TEXT NOT NULL,
                assigned_by_name TEXT NOT NULL,
                assigned_count INTEGER NOT NULL,
                status TEXT DEFAULT 'pending',
                reject_reason TEXT,
                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                FOREIGN KEY (material_id) REFERENCES material_entries(id) ON DELETE CASCADE
            );
            """);

        // 分配权限白名单表
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS assignment_permissions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                player_name TEXT NOT NULL,
                granted_by_name TEXT NOT NULL,
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE
            );
            """);

        // 备货区配置表（Phase 3 使用）
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

        // 备货区内容物缓存表（Phase 3 使用）
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS staging_area_inventory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                staging_area_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                count INTEGER NOT NULL,
                FOREIGN KEY (staging_area_id) REFERENCES staging_areas(id) ON DELETE CASCADE
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
        executeUpdate("DROP INDEX IF EXISTS idx_active_claim;");
        executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_active_claim ON claims(schematic_id, material_id, player_name) WHERE status = 'active';");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_claims_schematic ON claims(schematic_id, status);");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_claims_player ON claims(player_name, status);");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_assignments_schematic ON assignments(schematic_id, status);");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_assignments_assignee ON assignments(assignee_name, status);");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_assignment_permissions_schematic ON assignment_permissions(schematic_id);");
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
     * 执行查询并返回结果
     */
    public ResultSet executeQuery(String sql, Object... params) throws SQLException {
        PreparedStatement stmt = connection.prepareStatement(sql);
        setParameters(stmt, params);
        return stmt.executeQuery(); // 注意：调用者需要负责关闭ResultSet和Statement
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

    /**
     * 获取数据库统计信息
     */
    public String getStats() {
        final int[] counts = new int[2];
        try {
            executeQueryAndProcess("SELECT COUNT(*) as schematics FROM schematics", rs -> {
                if (rs.next()) counts[0] = rs.getInt("schematics");
            });
            executeQueryAndProcess("SELECT COUNT(*) as entries FROM material_entries", rs -> {
                if (rs.next()) counts[1] = rs.getInt("entries");
            });
            return String.format("原理图: %d, 材料条目: %d", counts[0], counts[1]);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("获取数据库统计信息失败", e);
            return "统计信息获取失败";
        }
    }
}