package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * SchematicDatabase 生命周期与并发事务边界的真实 JDBC 测试。
 *
 * <p>并发用例只使用 latch 与线程状态推进，不使用 sleep：
 * 事务持有者、等待者、seal/close 的先后顺序完全由测试显式控制。
 */
public class SchematicDatabaseLifecycleTest
{
    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private Path dbPath()
    {
        return tempDir.resolve("lifecycle.db");
    }

    private SchematicDatabase open()
    {
        SchematicDatabase db = new SchematicDatabase();
        db.initialize(dbPath().toString());
        assertTrue(db.isOpen());
        return db;
    }

    private void insertSchematic(SchematicDatabase db, String id) throws SQLException
    {
        db.executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
            id, id, "/" + id + ".litematic");
    }

    private int countById(Path dbFile, String id) throws SQLException
    {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM schematics WHERE id = ?"))
        {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery())
            {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private int scalar(SchematicDatabase db, String sql) throws SQLException
    {
        try (var qr = db.executeQuery(sql))
        {
            return qr.next() ? qr.getInt(1) : -1;
        }
    }

    /**
     * 等待线程进入指定状态。用于证明等待者已停在 wait()/monitor 上，
     * 而不是靠猜测或固定 sleep。
     */
    private static void awaitThreadState(Thread thread, Thread.State expected) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline)
        {
            if (thread.getState() == expected)
            {
                return;
            }
            Thread.onSpinWait();
        }
        fail("线程未进入 " + expected + "，当前状态: " + thread.getState());
    }

    private static Throwable joinAndGetFailure(Thread thread, AtomicReference<Throwable> failure) throws InterruptedException
    {
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(thread.isAlive(), "工作线程应在超时前结束");
        return failure.get();
    }

    // ========== 状态机 ==========

    @Test
    void newDatabase_rejectsSqlAndSealPreventsReopen() throws Exception
    {
        SchematicDatabase db = new SchematicDatabase();
        assertFalse(db.isOpen(), "NEW 不应被视为 open");
        assertThrows(SQLException.class,
            () -> db.executeUpdate("CREATE TABLE forbidden (id INTEGER)"),
            "NEW 状态的 SQL 入口必须抛 SQLException");
        assertThrows(SQLException.class, db::beginTransaction);

        db.seal();
        assertFalse(db.isOpen());
        assertThrows(SQLException.class, () -> db.executeQuery("SELECT 1"));
        assertThrows(IllegalStateException.class,
            () -> db.initialize(dbPath().toString()),
            "seal 后不能通过 initialize 复活");

        db.close();
        db.close(); // 幂等
        assertFalse(db.isOpen());
    }

    @Test
    void seal_rejectsAllEntrypointsAndIsIdempotent() throws Exception
    {
        SchematicDatabase db = open();
        String path = dbPath().toString();
        db.seal();
        db.seal();

        assertFalse(db.isOpen());
        assertThrows(SQLException.class, () -> db.executeUpdate("DELETE FROM schematics"));
        assertThrows(SQLException.class, () -> db.executeQuery("SELECT 1"));
        assertThrows(SQLException.class, () -> db.executePragma("PRAGMA foreign_keys=ON"));
        assertThrows(SQLException.class, () -> db.executeQueryAndProcess("SELECT 1", rs -> { }));
        assertThrows(SQLException.class, db::beginTransaction);
        assertThrows(SQLException.class, () -> db.inTransaction(() -> "x"));
        assertThrows(IllegalStateException.class, () -> db.initialize(path));

        // 未持有事务时 commit/rollback 保持旧的幂等语义
        db.commitTransaction();
        db.rollbackTransaction();

        db.close();
        db.close();
        assertFalse(db.isOpen());
        assertThrows(SQLException.class, () -> db.executeUpdate("DELETE FROM schematics"));
    }

    @Test
    void initializeFailure_closesPartialConnection_andCannotReopen() throws Exception
    {
        SchematicDatabase db = new SchematicDatabase(); // 先加载驱动
        // material_entries 缺少迁移/建索引所需列，让 initialize 在建立连接后失败
        createIncompatibleDatabase(dbPath());

        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> db.initialize(dbPath().toString()));
        assertNotNull(failure.getCause(), "初始化失败应保留 SQLException 原因");
        assertTrue(failure.getCause() instanceof SQLException,
            "初始化失败原因应为 SQLException，实际: " + failure.getCause());

        assertFalse(db.isOpen());
        assertNull(readConnectionField(db), "初始化失败的半成品连接必须被关闭并清空");
        assertThrows(SQLException.class, () -> db.executeUpdate("SELECT 1"));
        assertThrows(IllegalStateException.class,
            () -> db.initialize(dbPath().toString()),
            "失败的初始化不能通过再次 initialize 复活");

        db.close();
        db.close();
    }

    private static void createIncompatibleDatabase(Path dbFile) throws SQLException
    {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement())
        {
            stmt.execute("CREATE TABLE material_entries (id INTEGER PRIMARY KEY AUTOINCREMENT)");
        }
    }

    private static Object readConnectionField(SchematicDatabase db) throws Exception
    {
        Field field = SchematicDatabase.class.getDeclaredField("connection");
        field.setAccessible(true);
        return field.get(db);
    }

    /**
     * 初始化真实 schema 后把连接替换为 Mockito 连接，用于精确断言失败路径。
     * 原连接先关闭，避免测试自身泄漏。
     */
    private static void swapConnection(SchematicDatabase db, Connection replacement) throws Exception
    {
        Field field = SchematicDatabase.class.getDeclaredField("connection");
        field.setAccessible(true);
        Connection original = (Connection) field.get(db);
        if (original != null)
        {
            original.close();
        }
        field.set(db, replacement);
    }

    private static int openQueryResultCount(SchematicDatabase db) throws Exception
    {
        Field field = SchematicDatabase.class.getDeclaredField("openQueryResults");
        field.setAccessible(true);
        return ((java.util.Set<?>) field.get(db)).size();
    }

    // ========== 连接失败与查询结果封存 ==========

    @Test
    void queryResult_sealClosesExactlyOnceAndRejectsReads() throws Exception
    {
        SchematicDatabase db = open();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        swapConnection(db, connection);

        var queryResult = db.executeQuery("SELECT 1");
        assertEquals(1, openQueryResultCount(db), "已签发结果应被跟踪");

        db.seal();
        assertEquals(0, openQueryResultCount(db), "seal 后不应残留已签发结果");
        assertThrows(SQLException.class, queryResult::next,
            "seal 后代理读取必须拒绝");

        queryResult.close();
        queryResult.close();
        verify(resultSet, times(1)).close();
        verify(statement, times(1)).close();

        db.seal(); // 幂等：不能再次物理关闭
        verify(resultSet, times(1)).close();
        verify(statement, times(1)).close();
        verify(connection, never()).close(); // seal 只封存，不关闭连接

        db.close();
        verify(connection, times(1)).close();
    }

    @Test
    void abortRollbackFailure_neverRestoresAutoCommit_andClosesConnection() throws Exception
    {
        SchematicDatabase db = open();
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        swapConnection(db, connection);

        db.beginTransaction();
        verify(connection).setAutoCommit(false);

        db.seal();

        // rollback 失败后再 setAutoCommit(true) 可能提交半成品，必须禁止
        verify(connection, never()).setAutoCommit(true);
        verify(connection, times(1)).close();
        assertFalse(db.isOpen());
        assertNull(readConnectionField(db), "回滚失败后连接必须被断开");

        db.seal();
        db.close();
        verify(connection, times(1)).close();
        assertThrows(SQLException.class, () -> db.executeUpdate("DELETE FROM schematics"));
    }

    @Test
    void abortRollbackSuccess_restoresAutoCommitWithoutClosingConnection() throws Exception
    {
        SchematicDatabase db = open();
        Connection connection = mock(Connection.class);
        swapConnection(db, connection);

        db.beginTransaction();
        db.seal();

        verify(connection, times(1)).setAutoCommit(true);
        verify(connection, never()).close();
        assertFalse(db.isOpen());

        db.close();
        verify(connection, times(1)).close();
    }

    @Test
    void rollbackTransaction_failureSealsWithoutRestoringAutoCommit() throws Exception
    {
        SchematicDatabase db = open();
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        swapConnection(db, connection);

        db.beginTransaction();
        SQLException thrown = assertThrows(SQLException.class, db::rollbackTransaction);

        assertEquals("rollback failed", thrown.getMessage());
        verify(connection, never()).setAutoCommit(true);
        verify(connection, times(1)).close();
        assertFalse(db.isOpen());
        assertNull(readConnectionField(db));

        db.close();
        verify(connection, times(1)).close();
    }

    @Test
    void rollbackFailure_wakesWaitersAndSeals() throws Exception
    {
        SchematicDatabase db = open();
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        swapConnection(db, connection);
        db.beginTransaction();

        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread waiter = new Thread(() ->
        {
            started.countDown();
            try
            {
                insertSchematic(db, "waiter-after-rollback-failure");
            }
            catch (Throwable t)
            {
                waiterFailure.set(t);
            }
            finally
            {
                finished.countDown();
            }
        }, "db-rollback-waiter");

        waiter.start();
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        awaitThreadState(waiter, Thread.State.WAITING);

        assertThrows(SQLException.class, db::rollbackTransaction);

        assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "回滚失败封存必须唤醒等待者");
        assertTrue(waiterFailure.get() instanceof SQLException,
            "等待者应因数据库封存收到 SQLException，实际: " + waiterFailure.get());
        verify(connection, times(1)).close();
        assertFalse(db.isOpen());
        db.close();
    }

    @Test
    void commitTransaction_failureRollsBackBeforeRestoringAutoCommit() throws Exception
    {
        SchematicDatabase db = open();
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("commit failed")).when(connection).commit();
        swapConnection(db, connection);

        db.beginTransaction();
        SQLException thrown = assertThrows(SQLException.class, db::commitTransaction);

        assertEquals("commit failed", thrown.getMessage());
        InOrder order = inOrder(connection);
        order.verify(connection).rollback();
        order.verify(connection).setAutoCommit(true);
        verify(connection, never()).close();
        assertTrue(db.isOpen());
        db.close();
    }

    @Test
    void commitTransaction_commitAndRollbackFailureSealsWithoutAutoCommit() throws Exception
    {
        SchematicDatabase db = open();
        Connection connection = mock(Connection.class);
        SQLException rollbackFailure = new SQLException("rollback failed");
        doThrow(new SQLException("commit failed")).when(connection).commit();
        doThrow(rollbackFailure).when(connection).rollback();
        swapConnection(db, connection);

        db.beginTransaction();
        SQLException thrown = assertThrows(SQLException.class, db::commitTransaction);

        assertEquals("commit failed", thrown.getMessage());
        assertTrue(thrown.getSuppressed().length == 1 && thrown.getSuppressed()[0] == rollbackFailure,
            "提交失败应保留回滚失败作为 suppressed");
        verify(connection, never()).setAutoCommit(true);
        verify(connection, times(1)).close();
        assertFalse(db.isOpen());

        db.close();
        verify(connection, times(1)).close();
    }

    @Test
    void commitRollbackOnly_rollbackFailureSealsWithoutAutoCommit() throws Exception
    {
        SchematicDatabase db = open();
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        swapConnection(db, connection);

        db.beginTransaction();
        db.beginTransaction();
        db.rollbackTransaction(); // 内层仅标记 rollback-only，不触碰连接

        SQLException thrown = assertThrows(SQLException.class, db::commitTransaction);

        assertTrue(thrown.getMessage().contains("内层"), "应保留原 rollback-only 拒绝语义");
        verify(connection, never()).setAutoCommit(true);
        verify(connection, times(1)).close();
        assertFalse(db.isOpen());

        db.close();
        verify(connection, times(1)).close();
    }

    @Test
    void executeQuery_bindFailure_closesStatement() throws Exception
    {
        SchematicDatabase db = open();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        doThrow(new SQLException("bind failed")).when(statement).setObject(anyInt(), any());
        swapConnection(db, connection);

        assertThrows(SQLException.class, () -> db.executeQuery("SELECT ?", "value"));

        verify(statement, times(1)).close();
        verify(statement, never()).executeQuery();
        assertEquals(0, openQueryResultCount(db), "绑定失败不能登记半成品查询结果");
        db.close();
    }

    // ========== 生命周期打断事务 ==========

    @Test
    void close_rollsBackIncompleteLegacyTransaction() throws Exception
    {
        SchematicDatabase db = open();
        db.beginTransaction();
        insertSchematic(db, "pending-close");

        db.close();
        db.close();

        assertFalse(db.isOpen());
        assertEquals(0, countById(dbPath(), "pending-close"),
            "close 必须回滚未完成的 legacy 事务");
        assertThrows(SQLException.class, () -> db.executeUpdate("DELETE FROM schematics"));
        // 事务归属已清除，旧调用点重复 commit/rollback 仍幂等
        db.commitTransaction();
        db.rollbackTransaction();
    }

    @Test
    void sealFromAnotherThread_abortsPausedLegacyTransaction() throws Exception
    {
        SchematicDatabase db = open();
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        CountDownLatch transactionOpen = new CountDownLatch(1);
        CountDownLatch resumeOwner = new CountDownLatch(1);

        Thread owner = new Thread(() ->
        {
            try
            {
                db.beginTransaction();
                insertSchematic(db, "paused-owner");
                transactionOpen.countDown();
                awaitLatch(resumeOwner);
                try
                {
                    insertSchematic(db, "resurrected");
                    ownerFailure.set(new AssertionError("seal 后旧事务线程不应还能写库"));
                }
                catch (SQLException expected)
                {
                    ownerFailure.set(expected);
                }
                db.commitTransaction(); // seal 已清除归属，应为幂等 no-op
            }
            catch (Throwable t)
            {
                unexpected.set(t);
            }
        }, "db-paused-owner");

        owner.start();
        assertTrue(transactionOpen.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        db.seal();
        resumeOwner.countDown();

        assertNull(joinAndGetFailure(owner, unexpected));
        assertTrue(ownerFailure.get() instanceof SQLException,
            "旧事务线程恢复后应收到 SQLException，实际: " + ownerFailure.get());
        assertEquals(0, countById(dbPath(), "paused-owner"), "seal 必须回滚暂停中的事务");
        assertEquals(0, countById(dbPath(), "resurrected"));
        db.close();
    }

    @Test
    void closeFromAnotherThread_rollsBackPausedLegacyTransaction() throws Exception
    {
        SchematicDatabase db = open();
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        CountDownLatch transactionOpen = new CountDownLatch(1);
        CountDownLatch resumeOwner = new CountDownLatch(1);

        Thread owner = new Thread(() ->
        {
            try
            {
                db.beginTransaction();
                insertSchematic(db, "pending-external-close");
                transactionOpen.countDown();
                awaitLatch(resumeOwner);
                try
                {
                    insertSchematic(db, "after-external-close");
                    ownerFailure.set(new AssertionError("close 后旧事务线程不应还能写库"));
                }
                catch (SQLException expected)
                {
                    ownerFailure.set(expected);
                }
                db.rollbackTransaction(); // close 已清除归属，应为幂等 no-op
            }
            catch (Throwable t)
            {
                unexpected.set(t);
            }
        }, "db-paused-owner-close");

        owner.start();
        assertTrue(transactionOpen.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        db.close();
        resumeOwner.countDown();

        assertNull(joinAndGetFailure(owner, unexpected));
        assertTrue(ownerFailure.get() instanceof SQLException,
            "close 后旧事务线程应收到 SQLException，实际: " + ownerFailure.get());
        assertEquals(0, countById(dbPath(), "pending-external-close"),
            "close 必须回滚另一线程暂停中的事务");
        assertEquals(0, countById(dbPath(), "after-external-close"));
        db.close();
    }

    @Test
    void seal_rollsBackIncompleteLegacyTransaction_andOwnerCannotResurrect() throws Exception
    {
        SchematicDatabase db = open();
        db.beginTransaction();
        insertSchematic(db, "pending-seal");

        db.seal();

        assertFalse(db.isOpen());
        assertEquals(0, countById(dbPath(), "pending-seal"), "seal 必须回滚未完成事务");
        assertThrows(SQLException.class, () -> insertSchematic(db, "after-seal"),
            "seal 后原事务线程不能再写库");
        assertThrows(SQLException.class, db::beginTransaction);
        db.commitTransaction();
        db.rollbackTransaction();
        db.close();
    }

    @Test
    void inTransaction_sealInsideCallback_rollsBackAndRejectsCommit() throws Exception
    {
        SchematicDatabase db = open();
        SQLException failure = assertThrows(SQLException.class, () -> db.inTransaction(() ->
        {
            insertSchematic(db, "sealed-inside");
            db.seal();
            return null;
        }));

        assertTrue(failure.getMessage().contains("封存") || failure.getMessage().contains("关闭"),
            "回调内 seal 后提交应被拒绝，实际: " + failure.getMessage());
        assertFalse(db.isOpen());
        assertEquals(0, countById(dbPath(), "sealed-inside"), "seal 回调结束时未提交的数据必须回滚");
        db.close();
    }

    // ========== 事务与等待者 ==========

    @Test
    void inTransaction_commitsAndRollsBack() throws Exception
    {
        SchematicDatabase db = open();

        String result = db.inTransaction(() ->
        {
            insertSchematic(db, "tx-ok");
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(1, countById(dbPath(), "tx-ok"));

        SQLException failure = assertThrows(SQLException.class, () -> db.inTransaction(() ->
        {
            insertSchematic(db, "tx-sql-fail");
            throw new SQLException("boom");
        }));
        assertEquals("boom", failure.getMessage());
        assertEquals(0, countById(dbPath(), "tx-sql-fail"));

        assertThrows(IllegalStateException.class, () -> db.inTransaction(() ->
        {
            insertSchematic(db, "tx-runtime-fail");
            throw new IllegalStateException("runtime");
        }));
        assertEquals(0, countById(dbPath(), "tx-runtime-fail"));

        db.close();
    }

    @Test
    void inTransaction_nestedFailurePoisonsOuterCommit() throws Exception
    {
        SchematicDatabase db = open();

        assertThrows(SQLException.class, () -> db.inTransaction(() ->
        {
            insertSchematic(db, "outer");
            try
            {
                db.inTransaction(() ->
                {
                    insertSchematic(db, "inner");
                    throw new SQLException("inner-fail");
                });
            }
            catch (SQLException expected)
            {
                // 外层吞掉内层异常也不能让半成品数据被提交
            }
            return null;
        }));

        assertEquals(0, countById(dbPath(), "outer"), "外层提交必须因内层回滚被拒绝");
        assertEquals(0, countById(dbPath(), "inner"));
        assertEquals(0, scalar(db, "SELECT COUNT(*) FROM schematics"),
            "回滚后库应保持为空");

        // 中毒事务结束后，数据库仍可继续使用
        db.inTransaction(() ->
        {
            insertSchematic(db, "after-poison");
            return null;
        });
        assertEquals(1, countById(dbPath(), "after-poison"));
        db.close();
    }

    @Test
    void nonOwnerStatement_waitsInsteadOfJoiningForeignTransaction() throws Exception
    {
        SchematicDatabase db = open();
        db.beginTransaction();
        insertSchematic(db, "owner-rolled-back");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread waiter = new Thread(() ->
        {
            started.countDown();
            try
            {
                insertSchematic(db, "worker-after-wait");
            }
            catch (Throwable t)
            {
                failure.set(t);
            }
            finally
            {
                finished.countDown();
            }
        }, "db-non-owner");

        waiter.start();
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        awaitThreadState(waiter, Thread.State.WAITING);
        assertTrue(finished.getCount() > 0, "事务未结束前非持有线程不应完成 SQL");

        db.rollbackTransaction();
        assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "事务结束后等待者应被唤醒");
        assertNull(joinAndGetFailure(waiter, failure), "等待者不应失败");

        assertEquals(0, countById(dbPath(), "owner-rolled-back"));
        assertEquals(1, countById(dbPath(), "worker-after-wait"),
            "若等待者误入他人事务，其写入会随回滚一起消失");
        db.close();
    }

    @Test
    void seal_wakesWaiterAndRollsBackOwnerTransaction() throws Exception
    {
        SchematicDatabase db = open();
        db.beginTransaction();
        insertSchematic(db, "owner-sealed");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread waiter = new Thread(() ->
        {
            started.countDown();
            try
            {
                insertSchematic(db, "worker-sealed");
            }
            catch (Throwable t)
            {
                failure.set(t);
            }
            finally
            {
                finished.countDown();
            }
        }, "db-seal-waiter");

        waiter.start();
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        awaitThreadState(waiter, Thread.State.WAITING);

        db.seal();
        assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "seal 必须唤醒等待者");
        Throwable waiterFailure = joinAndGetFailure(waiter, failure);
        assertTrue(waiterFailure instanceof SQLException,
            "被 seal 唤醒的等待者应收到 SQLException，实际: " + waiterFailure);

        assertEquals(0, countById(dbPath(), "owner-sealed"));
        assertEquals(0, countById(dbPath(), "worker-sealed"));
        assertThrows(SQLException.class, () -> insertSchematic(db, "post-seal"));
        db.close();
    }

    @Test
    void close_wakesWaiterAndRollsBackOwnerTransaction() throws Exception
    {
        SchematicDatabase db = open();
        db.beginTransaction();
        insertSchematic(db, "owner-closed");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread waiter = new Thread(() ->
        {
            started.countDown();
            try
            {
                insertSchematic(db, "worker-closed");
            }
            catch (Throwable t)
            {
                failure.set(t);
            }
            finally
            {
                finished.countDown();
            }
        }, "db-close-waiter");

        waiter.start();
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        awaitThreadState(waiter, Thread.State.WAITING);

        db.close();
        assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "close 必须唤醒等待者");
        assertTrue(joinAndGetFailure(waiter, failure) instanceof SQLException,
            "被 close 唤醒的等待者应收到 SQLException");

        assertEquals(0, countById(dbPath(), "owner-closed"));
        assertEquals(0, countById(dbPath(), "worker-closed"));
        db.close();
    }

    @Test
    void inTransaction_holdsMonitorAndSerializesOtherOwners() throws Exception
    {
        SchematicDatabase db = open();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread first = new Thread(() ->
        {
            try
            {
                db.inTransaction(() ->
                {
                    insertSchematic(db, "serial-first");
                    firstInside.countDown();
                    awaitLatch(releaseFirst);
                    return null;
                });
            }
            catch (Throwable t)
            {
                firstFailure.set(t);
            }
        }, "db-tx-first");
        first.start();
        assertTrue(firstInside.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        Thread second = new Thread(() ->
        {
            try
            {
                db.inTransaction(() ->
                {
                    insertSchematic(db, "serial-second");
                    return null;
                });
            }
            catch (Throwable t)
            {
                secondFailure.set(t);
            }
        }, "db-tx-second");
        second.start();

        // 第一个回调持有 connection monitor，第二个事务必须停在 monitor 上
        awaitThreadState(second, Thread.State.BLOCKED);
        assertEquals(0, countById(dbPath(), "serial-second"), "第二个事务未获准入前不应提交");

        releaseFirst.countDown();
        assertNull(joinAndGetFailure(first, firstFailure));
        assertNull(joinAndGetFailure(second, secondFailure));

        assertEquals(1, countById(dbPath(), "serial-first"));
        assertEquals(1, countById(dbPath(), "serial-second"));
        db.close();
    }

    private static void awaitLatch(CountDownLatch latch)
    {
        try
        {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("等待 latch 超时");
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 latch 被中断", e);
        }
    }
}
