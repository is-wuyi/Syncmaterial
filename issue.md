# SyncMaterial 代码审查报告

## 审查日期
2026-05-24

## 审查范围
全项目代码审查，包括架构设计、数据库、网络、GUI、引擎、Mixin 等核心模块。

---

## 一、严重问题 (Critical)

### 1.1 数据库重复初始化导致资源泄漏 ✅ 已修复
**文件**: `SyncmaticaIntegrationMixin.java:26-28`, `SyncMaterial.java:48-49`

**问题描述**:
- `SyncmaticaIntegrationMixin` 在 Syncmatica 初始化时创建并初始化一个新的 `SchematicDatabase` 实例
- `SyncMaterial.onInitialize()` 中的 `SERVER_STARTING` 事件也会创建和初始化数据库
- 这会导致两个独立的数据库实例同时存在，造成：
  - 数据库连接泄漏
  - 数据不一致
  - 潜在的死锁问题

**修复方案**:
- 移除 `SyncmaticaIntegrationMixin` 中的数据库初始化代码
- 统一使用 `SyncMaterial` 中的单例数据库实例
- 添加 `getSharedDatabase()`、`getSharedQueryService()`、`getSharedParser()` 方法

---

### 1.2 数据库连接未正确关闭 ✅ 已修复
**文件**: `SchematicDatabase.java`, `SyncMaterial.java`

**问题描述**:
- `SchematicDatabase` 没有实现 `AutoCloseable` 接口
- `SyncMaterial` 没有注册 `SERVER_STOPPING` 事件来关闭数据库
- 服务器关闭时数据库连接不会被正确释放

**修复方案**:
- 让 `SchematicDatabase` 实现 `AutoCloseable` 接口
- 在 `SyncMaterial` 中注册 `SERVER_STOPPING` 事件关闭数据库和相关资源

---

### 1.3 Mixin 配置错误 ✅ 已修复
**文件**: `syncmaterial.mixins.json`

**问题描述**:
```json
{
  "client": [
    "SyncmaticaIntegrationMixin",  // 错误：这是服务端 Mixin
    "WidgetSyncmaticaServerPlacementEntryMixin",
    "ButtonListenerMixin"
  ]
}
```
- `SyncmaticaIntegrationMixin` 被配置为 client mixin
- 但它是在服务端初始化时使用的（注入到 `LitematicManager.setContext`）
- 在纯服务端环境中这些 mixin 不会被加载

**修复方案**:
- 将 `SyncmaticaIntegrationMixin` 移到 `mixins` 数组（通用 mixin）

---

## 二、高优先级问题 (High)

### 2.1 事务管理不当 ✅ 已修复
**文件**: `SchematicUploadListener.java:158-177`

**问题描述**:
```java
database.executeUpdate("BEGIN TRANSACTION");
try {
    // ... 执行多个 INSERT
    database.executeUpdate("COMMIT");
} catch (Exception e) {
    database.executeUpdate("ROLLBACK");
    throw e;
}
```
- `executeUpdate()` 方法每次都会创建新的 PreparedStatement
- 手动调用 `BEGIN TRANSACTION` 可能与 SQLite 的自动提交模式冲突
- 如果在 `COMMIT` 或 `ROLLBACK` 时发生异常，数据库可能处于不一致状态

**修复方案**:
- 在 `SchematicDatabase` 中添加 `beginTransaction()`、`commitTransaction()`、`rollbackTransaction()` 方法
- 使用 `connection.setAutoCommit(false)` 来管理事务
- 修改 `SchematicUploadListener` 使用新的事务管理方法

---

### 2.2 线程安全问题
**文件**: `SchematicFolderWatcher.java:107-197`

**问题描述**:
- `processPlacementsJson()` 方法中对 `processedHashes` 和 `placementNames` 的操作不是原子的
- 多个线程可能同时调用此方法（文件监控和定时扫描）
- 可能导致重复处理或遗漏处理

**建议修复**:
- 使用 `synchronized` 关键字或 `ReentrantLock` 保护关键区域
- 或者将处理逻辑移到单线程执行器中

---

### 2.3 网络包重复注册
**文件**: `ModNetworkHandler.java:33-43`, `ModNetworkHandlerClient.java:14-24`

**问题描述**:
- 服务端和客户端都注册了相同的 `PayloadType`
- Fabric API 可能会抛出重复注册异常
- 虽然目前可能因为环境隔离而没有问题，但这是潜在的隐患

**建议修复**:
- 服务端只注册 S2C（服务端到客户端）的包
- 客户端只注册 C2S（客户端到服务端）的包
- 或者在注册前检查是否已注册

---

### 2.4 缺少网络包数据验证
**文件**: `ModNetworkHandler.java` (所有 handler)

**问题描述**:
- 没有验证接收到的网络包数据是否合法
- 恶意客户端可能发送：
  - 无效的 `schematicId`
  - 超大或负数的 `count`
  - 不存在的 `materialId`
- 可能导致服务器崩溃或数据损坏

**建议修复**:
- 在处理网络包前验证所有输入数据
- 添加长度限制和范围检查
- 对异常输入记录警告日志

---

## 三、中优先级问题 (Medium)

### 3.1 重复代码 ✅ 已修复
**文件**: `ModNetworkHandler.java:128-227`

**问题描述**:
`handleStagingAreaConfig()` 方法中，每个 case 分支都有重复的代码：
```java
var areas = manager.getStagingAreas(schematicId);
var areaInfos = areas.stream().map(a -> new StagingAreaConfigResponseS2CPacket.AreaInfo(
    a.id(), a.name(), a.x1(), a.y1(), a.z1(), a.x2(), a.y2(), a.z2(), a.world()
)).toList();
ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(true, "", areaInfos));
```

**修复方案**:
- 提取公共方法 `sendStagingAreaResponse()`

---

### 3.2 魔法数字 ✅ 已修复
**文件**: 多个文件

**问题描述**:
- `SyncMaterial.java:65`: `tickCounter[0] >= 4` (4 ticks = 200ms)
- `SchematicFolderWatcher.java:112`: `Thread.sleep(200)` (等待文件写入)
- `MaterialListHudRenderer.java:64`: `currentTime - this.lastUpdateTime > 2000` (2秒更新间隔)
- `InventoryWatcher.java:27`: `tickCounter % 20 != 0` (每20 tick检查一次)

**修复方案**:
- 定义为命名常量：
  - `DIRTY_CONTAINER_CHECK_INTERVAL = 4`
  - `FILE_WATCH_DELAY_MS = 200`
  - `HUD_UPDATE_INTERVAL_MS = 2000`

---

### 3.3 日志级别不当 ✅ 已修复
**文件**: `ModNetworkHandler.java:51-52`, `ModNetworkHandler.java:105`

**问题描述**:
```java
SyncMaterial.LOGGER.info("收到玩家 {} 的材料统计请求: {}", player.getGameProfile().getName(), schematicId);
SyncMaterial.LOGGER.info("收到玩家 {} 的库存更新: 材料 {}, 数量 {}", playerName, materialId, count);
```
- 这些是调试信息，不应该使用 `info` 级别
- 在生产环境中会产生大量日志

**修复方案**:
- 将调试信息改为 `debug` 级别
- 只在关键操作时使用 `info` 级别

---

### 3.4 线程池未正确关闭 ✅ 已修复
**文件**: `SchematicFolderWatcher.java:283-291`

**问题描述**:
```java
public void stop() {
    try {
        watchService.close();
        watchExecutor.shutdown();
        parseExecutor.shutdown();
    } catch (Exception e) {
        SyncMaterial.LOGGER.error("停止监控失败", e);
    }
}
```
- `shutdown()` 只是停止接受新任务，不会中断正在执行的任务
- 应该调用 `shutdownNow()` 并等待任务完成

**修复方案**:
```java
public void stop() {
    try {
        watchService.close();
        watchExecutor.shutdownNow();
        parseExecutor.shutdownNow();
        watchExecutor.awaitTermination(5, TimeUnit.SECONDS);
        parseExecutor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (Exception e) {
        SyncMaterial.LOGGER.error("停止监控失败", e);
    }
}
```

---

### 3.5 SQL LIKE 注入风险 ✅ 已修复
**文件**: `SchematicFolderWatcher.java:180-183`

**问题描述**:
```java
database.executeQuery(
    "SELECT id FROM schematics WHERE file_path LIKE ?",
    "%" + removedHash + "%"
)
```
- 虽然使用了参数化查询，但 `removedHash` 可能包含 SQL 通配符（`%`, `_`）
- 恶意输入可能导致意外匹配

**修复方案**:
- 使用精确匹配和更具体的 LIKE 模式：
  ```java
  "SELECT id FROM schematics WHERE file_path = ? OR file_path LIKE ? OR file_path LIKE ?",
  removedHash, removedHash + "/%", "%/" + removedHash
  ```

---

## 四、低优先级问题 (Low)

### 4.1 代码风格不一致
**文件**: 多个文件

**问题描述**:
- 有些文件使用 4 空格缩进，有些使用 2 空格
- 大括号风格不一致（有些在同一行，有些在新行）
- import 语句顺序不一致

**建议修复**:
- 统一代码风格，建议使用 IDE 的格式化功能

---

### 4.2 未使用的导入
**文件**: `GuiMaterialList.java:9`

**问题描述**:
```java
import fi.dy.masa.malilib.config.HudAlignment;  // 第 6 行
import fi.dy.masa.malilib.config.HudAlignment;  // 第 9 行（重复）
```

**建议修复**:
- 移除重复的导入语句

---

### 4.3 缺少 Javadoc 注释
**文件**: 多个文件

**问题描述**:
- 大部分类和方法缺少 Javadoc 注释
- 公共 API 没有文档说明

**建议修复**:
- 为公共类和方法添加 Javadoc 注释

---

### 4.4 异常处理不一致
**文件**: 多个文件

**问题描述**:
- 有些地方捕获异常后记录日志并继续执行
- 有些地方直接抛出 RuntimeException
- 有些地方吞掉异常

**建议修复**:
- 统一异常处理策略
- 定义项目特定的异常类

---

## 五、性能问题

### 5.1 数据库查询效率低
**文件**: `CollaborationManager.java:108-154`

**问题描述**:
`getCollaborationStatus()` 方法中：
1. 先查询 `material_entries` 获取总数
2. 再查询 `staging_area_inventory` 获取备货区数量
3. 最后查询 `claims` 获取所有参与者
4. 对每个参与者，再从内存 Map 中获取背包数据

这导致多次数据库查询，应该使用 JOIN 优化。

**建议修复**:
- 使用单个 JOIN 查询获取所有需要的数据

---

### 5.2 HUD 渲染性能
**文件**: `MaterialListHudRenderer.java:58-150`

**问题描述**:
- 每次渲染都会调用 `getMaterialsMissingOnly()`
- 即使数据没有变化也会重新计算
- 可能导致帧率下降

**建议修复**:
- 添加脏标记，只在数据变化时重新计算

---

## 六、安全问题

### 6.1 反射使用不安全
**文件**: `ButtonListenerMixin.java:28-57`

**问题描述**:
- 大量使用反射访问私有字段
- 没有充分的错误处理
- 如果 Syncmatica 更新导致字段名变化，会静默失败

**建议修复**:
- 添加更详细的错误日志
- 考虑使用 Mixin 的 `@Shadow` 或 `@Accessor` 注解

---

### 6.2 数据库路径不安全
**文件**: `SchematicDatabase.java:54-57`

**问题描述**:
```java
private String getDatabasePath() {
    File serverDir = new File(".");
    return new File(serverDir, DB_FILE).getAbsolutePath();
}
```
- 使用 `new File(".")` 获取当前目录
- 在不同的运行环境中可能得到不同的结果
- 可能导致数据库文件位置不一致

**建议修复**:
- 使用 Fabric API 的 `FabricLoader.getInstance().getGameDir()` 获取游戏目录

---

## 七、建议改进

### 7.1 架构改进
1. 实现依赖注入，避免全局单例
2. 使用事件总线解耦模块
3. 实现配置文件系统

### 7.2 测试覆盖
1. 添加单元测试
2. 添加集成测试
3. 实现自动化测试流程

### 7.3 文档完善
1. 添加 API 文档
2. 添加部署文档
3. 添加故障排除指南

---

## 八、总结

### 问题统计
- 严重问题: 3 个 (全部已修复 ✅)
- 高优先级问题: 4 个 (1 个已修复 ✅)
- 中优先级问题: 5 个 (全部已修复 ✅)
- 低优先级问题: 4 个
- 性能问题: 2 个
- 安全问题: 2 个

### 已修复问题列表
1. ✅ 1.1 数据库重复初始化
2. ✅ 1.2 数据库连接关闭
3. ✅ 1.3 Mixin 配置错误
4. ✅ 2.1 事务管理
5. ✅ 3.1 重复代码
6. ✅ 3.2 魔法数字
7. ✅ 3.3 日志级别
8. ✅ 3.4 线程池关闭
9. ✅ 3.5 SQL LIKE 注入

### 建议优先修复顺序
1. ✅ 数据库重复初始化问题 (1.1)
2. ✅ Mixin 配置错误 (1.3)
3. ✅ 数据库连接关闭问题 (1.2)
4. ✅ 事务管理问题 (2.1)
5. 2.4 网络包验证问题（待修复）

---

*审查人: OpenCode AI*
*审查完成时间: 2026-05-24*
*最后更新时间: 2026-05-24*
