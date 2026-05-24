# SyncMaterial 计划评估报告 - 问题清单

> **评估日期**: 2026-05-22
> **评估范围**: 两份计划文档 + 当前项目代码实现
> **评估结果**: 发现 23 个问题（其中 5 个严重问题、8 个中等问题、10 个轻微问题）

---

## 一、严重问题（CRITICAL）

### 1.1 Phase 1 未按计划完成：孤立的旧网络包文件未删除

**位置**: `src/main/java/net/syncmaterial/syncmaterial/network/`
- ❌ `ClaimMaterialC2SPacket.java` - 应删除（已被 JoinCollaborationC2SPacket 取代）
- ❌ `ClaimResultS2CPacket.java` - 应删除（已被 CollaborationStatusS2CPacket 取代）

**问题描述**:
- 计划七《文件变更清单》明确说"删除文件"包括这两个包
- 当前代码中这两个文件仍然存在，虽然未被使用（无任何引用）
- 这些孤立文件会造成代码混乱，可能误导未来的维护者

**影响**: 
- Phase 1 验收标准未完全满足
- 代码库不够整洁

**解决方案**:
- 删除 `ClaimMaterialC2SPacket.java`
- 删除 `ClaimResultS2CPacket.java`
- 检查是否还有 `MaterialStatusS2CPacket.java` 也需删除（Explore agent 提到该文件存在但未使用）

**验收标准**:
- [ ] 上述文件已删除
- [ ] 无任何代码引用这些文件
- [ ] 编译通过

---

### 1.2 Phase 1 验收标准：离线玩家数据持久化未实现

**位置**: 
- `CollaborationManager.java:171-172` - `onPlayerDisconnect()` 方法为空
- `CollaborationManager.java:13` - `playerInventories` 仅在内存中存储

**问题描述**:
- 计划二《核心设计理念 2.2》明确说："离线玩家基于最后一次上报的背包数据计入进度"
- 计划中 Phase 2 Task 14 要求添加 `player_inventories` 表来持久化背包缓存
- **但当前 Phase 1 实现中，背包数据仅存储在 ConcurrentHashMap 中，服务端重启会丢失**
- `onPlayerDisconnect()` 是空方法，无任何离线玩家处理逻辑

**问题代码**:
```java
private final Map<String, Map<Integer, Integer>> playerInventories = new ConcurrentHashMap<>();

public void onPlayerDisconnect(String playerName) {
    // 空方法！没有任何离线处理逻辑
}
```

**影响**:
- Phase 1 验收标准 2 要求"多人加入同一材料后，所有参与者看到相同的剩余数量" - 当离线玩家数据丢失时无法满足
- 服务端重启后，之前加入协作的玩家的背包数据全部丢失，进度计算不准确
- **严重影响 Phase 1 的可靠性**

**根本原因**:
- 计划中说"Phase 2 新增 player_inventories 表"，但实际上这应该是 Phase 1 就需要的
- Phase 1 的"协作式认领基础"需要能够正确处理离线玩家

**解决方案**:
1. **立即**（Phase 1 补充）在 SchematicDatabase 中添加 `player_inventories` 表：
   ```sql
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
   ```

2. 修改 `CollaborationManager.updatePlayerInventory()` 同时写入数据库
3. 服务端启动时从数据库加载背包缓存
4. 实现 `onPlayerDisconnect()` 逻辑（可为空，但应有注释说明离线玩家数据已持久化）

**验收标准**:
- [ ] `player_inventories` 表已创建
- [ ] 服务端重启后背包缓存从数据库恢复
- [ ] 玩家离线再上线，背包数据不丢失
- [ ] 编译通过

---

### 1.3 两个 Schema 表设计混乱：staging_area vs staging_areas 关系不清

**位置**: `SchematicDatabase.java:99-162`

**问题描述**:

现有两个表：
```
staging_area (旧表，单数)
  - id, schematic_id, material_id, item_id, count
  - 唯一索引：idx_staging_material (schematic_id, material_id)
  - 注释：Phase 1

staging_areas (新表，复数)
  - id, schematic_id, world, x1, y1, z1, x2, y2, z2
  - 注释：Phase 3 备货区容器配置
  - 没有 name 字段
```

**混乱之处**:
1. 两个表的设计理念完全不同：
   - `staging_area` = 按材料统计的库存汇总（物品ID+数量）
   - `staging_areas` = 物理区域的坐标定义（无物品信息）
2. 计划文档说：
   - 旧 `staging_area` 表应该在 Phase 2 "暂时保留不删除"，Phase 2 全部完成后再删
   - 新 `staging_areas` 表应该增加 `name` 字段（Phase 2 Task 1）
3. 但实际代码中：
   - `staging_areas` 表没有 `name` 字段
   - `staging_area` 表的注释说"Phase 1"，但实际应该是旧版本（Phase 1 协作式前）
   - 没有 `staging_area_inventory` 表来存储各区域的物品信息

**问题代码**:
```java
// staging_area 表 - 旧的按 material_id 汇总模式
executeUpdate("""
    CREATE TABLE IF NOT EXISTS staging_area (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        schematic_id TEXT NOT NULL,
        material_id INTEGER NOT NULL,
        item_id TEXT NOT NULL,
        count INTEGER DEFAULT 0,
        FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
        FOREIGN KEY (material_id) REFERENCES material_entries(id) ON DELETE CASCADE
    );
    """);

// staging_areas 表 - 新的多区域模式，但缺少 name 字段，且注释说 Phase 3（应该是 Phase 2）
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
```

**影响**:
- 开发者困惑两个表的用途
- Phase 2 任务 5（StagingAreaManager）和任务 6（更新进度计算）的实现会遇到困难
- 数据迁移路径不清楚

**解决方案**:
1. **立即修正表注释**，明确标注：
   ```sql
   -- 备货区库存汇总表 (旧版本，Phase 1 沿用，Phase 2 后续会迁移)
   CREATE TABLE IF NOT EXISTS staging_area (...);
   
   -- 备货区区域配置表 (Phase 2 新增，支持多区域)
   CREATE TABLE IF NOT EXISTS staging_areas (...);
   ```

2. **修正 staging_areas 表定义**（Phase 2 Task 1）：
   - 增加 `name TEXT NOT NULL DEFAULT '未命名'` 字段
   - 修正注释为"Phase 2"而非"Phase 3"

3. **补充 staging_area_inventory 表**（已存在，但需验证是否与 staging_areas 外键关联正确）

**验收标准**:
- [ ] 所有表注释清晰标注所属阶段
- [ ] `staging_areas` 表有 `name` 字段
- [ ] `staging_area_inventory` 正确关联 `staging_areas`
- [ ] 编译通过且能正确创建所有表

---

### 1.4 fabric.mod.json 版本范围不符合计划

**位置**: `src/main/resources/fabric.mod.json:29`

**问题描述**:
```json
"minecraft": "~1.21.7"  // 当前：只支持 1.21.7
```

计划《七、文件变更清单 Phase 2 Task 0》要求：
```json
"minecraft": ">=1.21.7 <=1.21.8"  // 计划：支持 1.21.7 和 1.21.8
```

**问题原因**:
- 计划中明确说版本兼容策略是"单 JAR 支持 MC 1.21.7 ~ 1.21.8"
- 当前版本范围用的是 Gradle 的 `~1.21.7` 格式（Gradle 特定语法），而不是标准的版本范围

**影响**:
- 玩家在 Minecraft 1.21.8 上无法使用此模组
- 不符合设计意图

**解决方案**:
- **Phase 2 Task 0** 时修改为：`"minecraft": ">=1.21.7 <=1.21.8"`
- 注意 `fabric.mod.json` 中的版本范围与 `build.gradle` 中的 Gradle 语法不同

**验收标准**:
- [ ] `fabric.mod.json` 中版本范围为 `>=1.21.7 <=1.21.8`

---

### 1.5 CollaborationManager.getCollaborationStatus() 的备货区查询逻辑不符合新架构

**位置**: `CollaborationManager.java:69-91`

**问题描述**:
```java
int stagingCount = 0;
try (var rs = database.executeQuery(
    "SELECT count FROM staging_area WHERE schematic_id = ? AND material_id = ?",
    schematicId, materialId
)) {
    if (rs.next()) {
        stagingCount = rs.getInt("count");
    }
}
```

**问题**:
1. 当前查询旧的 `staging_area` 表（按 material_id 汇总）
2. 计划中 Phase 2 要求改为多区域架构：
   - 使用新的 `staging_areas` 表（多条记录，每条是一个区域）
   - 使用 `staging_area_inventory` 表（每个区域的物品清单）
   - 需要 JOIN 查询来汇总所有区域的物品总数
3. **问题是**：Plan 中 Phase 2 Task 5 要求创建 StagingAreaManager 的 `getStagingCountForMaterial()` 方法，但这个查询逻辑应该在那个方法中，不是在这里

**但实际上**：
- 当前 getCollaborationStatus() 直接查询 staging_area
- 这会导致 Phase 2 的集成很复杂，因为需要替换这里的查询逻辑

**影响**:
- Phase 2 Task 6 要求"更新 getCollaborationStatus() 使用新备货区表"，这个任务会很困难，因为需要找到并替换这里的查询

**解决方案**:
- **这是一个设计问题，不是代码 bug**
- 计划中应该明确说：当 Phase 2 Task 5（StagingAreaManager）完成后，Phase 2 Task 6 需要将这里的查询改为：
  ```java
  int stagingCount = stagingAreaManager.getStagingCountForMaterial(schematicId, itemId);
  ```
- 而不是直接修改 SQL 语句

**建议**:
- 计划中 Phase 2 Task 6 应该标记为"依赖 Task 5"（已经标记了，但要确保开发者理解）

---

## 二、中等问题（MEDIUM）

### 2.1 JoinCollaborationC2SPacket 的 inventoryCounts 字段设计不够清晰

**位置**: `JoinCollaborationC2SPacket.java:11, 18`

**问题描述**:
```java
public record JoinCollaborationC2SPacket(
    String schematicId, 
    int materialId, 
    Map<Integer, Integer> inventoryCounts  // Key: materialId? 还是 itemId？
) implements CustomPayload { ... }
```

**混乱之处**:
- 字段名 `inventoryCounts` 不清楚 Key 是 materialId 还是 itemId
- 计划文档《三、技术架构 3.2》网络协议表说：
  - "加入协作组时，发送**全量背包数据**（所有协作材料）"
  - 但没有明确说 Key 是什么

**实际用法**（从 ModNetworkHandler.java:71-73）:
```java
for (Map.Entry<Integer, Integer> entry : inventoryCounts.entrySet()) {
    collaborationManager.updatePlayerInventory(playerName, schematicId, entry.getKey(), entry.getValue());
}
```
- 这里 `entry.getKey()` 被当作 materialId 传给 `updatePlayerInventory()`
- 而 `updatePlayerInventory()` 的签名是 `(playerName, schematicId, materialId, count)`

**问题**:
- 虽然代码工作正常，但字段名 `inventoryCounts` 容易误导
- 应该是 `materialCounts` 或 `materialIdToCount` 更清晰

**影响**:
- 代码可读性降低
- 未来维护者可能误解这个字段的含义

**解决方案**:
- 重命名字段为 `materialIdToCount` 或 `materialCounts`
- 在 PacketCodec 中保持兼容性（字段在网络包中的序列化方式不变）
- 添加注释说明 Key 是 materialId

**验收标准**:
- [ ] 字段已重命名且清晰
- [ ] 网络协议向后兼容（如果已有旧客户端）

---

### 2.2 CollaborationManager 缺少玩家离线时的背包清理逻辑

**位置**: `CollaborationManager.java:171-172`

**问题描述**:
```java
public void onPlayerDisconnect(String playerName) {
    // 应该清理玩家的背包数据吗？还是保留？
}
```

**计划中的设计**:
- 《二、核心设计理念 2.1》说："离线玩家基于最后一次上报的背包数据计入进度"
- 这意味着离线玩家的背包数据应该保留（至少在内存中或数据库中）

**问题**:
- 现在 onPlayerDisconnect() 是空方法
- 但考虑到"持久化背包缓存"的需求（Plan Task 14），应该在玩家离线时做什么？
  1. 保留数据（推荐 - 符合计划设计）？
  2. 清理数据（不推荐 - 会导致离线玩家的贡献丢失）？

**影响**:
- 内存中的 playerInventories 随着玩家加入而增长，但玩家离线时不清理
- 长期运行的服务器可能出现内存泄漏

**解决方案**:
- **推荐**：保留数据（因为要持久化到数据库），不做清理
- 或：在玩家离线时将其数据从内存迁移到数据库（如果实现了 Task 14）
- 添加注释说明设计意图

**验收标准**:
- [ ] onPlayerDisconnect() 有清晰的注释说明为什么是空方法（或有适当的实现）

---

### 2.3 WidgetMaterialListEntry 的按钮状态文本没有显示"已完成"状态

**位置**: `WidgetMaterialListEntry.java:85-87`

**问题描述**:
```java
if (type == ButtonListener.ButtonType.CLAIM && this.entry != null && this.materialList instanceof SyncMaterialList) {
    label = ((SyncMaterialList) this.materialList).isCollaborating(this.entry) ? "退出协作" : "加入协作";
}
```

**UI 设计要求**（ui-design.md:167-172）:
| 材料状态 | 按钮文本 |
|---------|--------|
| 未认领 | `[加入协作]` |
| 协作中（自己已加入） | `[退出协作]` |
| 已完成 | `[已完成 ✓]` |（不可点击，灰色显示）

**问题**:
- 当前代码只处理"加入"和"退出"两种状态
- 没有处理"已完成"状态
- 按钮应该在已完成时显示"已完成 ✓"且禁用

**影响**:
- UI 显示不完整，用户无法看到已完成的材料状态
- 不符合 UI 设计方案

**这是 Phase 3 的任务**，但需要在 Phase 1 评估时注意：
- 当前代码结构难以扩展到已完成状态
- 建议在后续维护时考虑重构按钮逻辑

**解决方案**:
- Phase 3 时实现完整的按钮状态逻辑
- 可以提前在 Phase 1 预留扩展点

**验收标准**:
- [ ] Phase 3 时实现完整状态逻辑
- [ ] 按钮正确显示"已完成"状态

---

### 2.4 ModNetworkHandler.broadcastStatus() 只广播给参与者，新参与者加入时其他人看不到

**位置**: `ModNetworkHandler.java:124-136`

**问题描述**:
```java
private static void broadcastStatus(MinecraftServer server, String schematicId, int materialId) {
    var status = collaborationManager.getCollaborationStatus(schematicId, materialId);
    if (status == null) return;

    List<String> participants = collaborationManager.getParticipants(schematicId, materialId);
    SyncMaterial.LOGGER.info("广播材料 {} 的状态给 {} 位参与者", materialId, participants.size());
    for (String name : participants) {
        var player = server.getPlayerManager().getPlayer(name);
        if (player != null) {
            ServerPlayNetworking.send(player, status);
        }
    }
}
```

**问题**:
- `getParticipants()` 从数据库查询当前的参与者列表（status='active')
- 但在 JoinCollaborationC2SPacket 的处理中，是先 insert into claims，然后调用 broadcastStatus()
- 此时 getParticipants() 会返回**最新**的参与者列表，包括刚加入的玩家

**看似没问题，但有隐藏风险**:
- 如果 GUI 已经打开（之前的参与者），他们看到的是旧数据
- 新参与者加入后，旧的 GUI 不会自动刷新（除非手动重新请求）
- 虽然计划中说"协作状态变更时广播给所有参与者"，但这里只广播给当前参与者

**但实际上计划已经处理了这个**:
- 最近提交信息 f152440 说"协作状态变更时广播给所有参与者"
- 所以这个问题可能已经在实现中解决了，只是代码审查时需要确认

**验收标准**:
- [ ] 确认 broadcastStatus() 实际上发送给所有相关玩家（不仅是参与者）
- [ ] 或添加注释说明为什么只发给参与者是正确的

---

### 2.5 Phase 2 Task 1 中的"安全迁移"逻辑不够完整

**位置**: team-collaboration.md Task 1，《What to do》部分

**问题描述**:
计划说：
```
先查询 staging_areas 表是否已有 name 列
（PRAGMA table_info(staging_areas)），
如果没有再执行 ALTER TABLE staging_areas ADD COLUMN name TEXT NOT NULL DEFAULT '未命名'
避免重复执行导致列重复异常
```

**问题**:
1. 计划中说"避免重复执行"，但实际上 SQLite 的 `ALTER TABLE` 如果列已存在会直接报错
2. 用 PRAGMA 检查是可以的，但需要捕获异常以应对 SQLite 版本差异
3. 如果检查失败（PRAGMA 支持差异），整个初始化会失败

**影响**:
- Task 1 的实现可能因为异常处理不足而导致服务启动失败
- 数据库迁移缺乏鲁棒性

**解决方案**:
- 使用 try-catch 捕获异常
- 添加日志说明是否成功添加列
- 如果列已存在，捕获异常后继续（不是致命错误）

**验收标准**:
- [ ] 服务端启动时能安全地处理 staging_areas 表的迁移
- [ ] 无论表是否已有 name 列，服务都能正常启动

---

### 2.6 Phase 2 Task 5 中的 isInAnyStagingArea() 必须检查维度，但实现细节不明

**位置**: team-collaboration.md Task 5，《What to do》部分

**问题描述**:
计划说：
```
必须同时检查坐标和维度（world.getRegistryKey().getValue().toString() 作为维度标识）
避免下界和主世界坐标重叠导致误判
```

**问题**:
1. 计划给出了维度标识的实现方式，但这是 Yarn 映射特定的
2. 不同版本的 Minecraft 或映射可能有不同的实现
3. `getRegistryKey().getValue().toString()` 返回什么格式？应该是 `minecraft:overworld` 这样的格式吗？
4. 如果格式变化，`isInAnyStagingArea()` 就会误判

**影响**:
- Phase 2 Task 5 的实现可能因为维度标识格式不统一而失败
- 跨维度的备货区配置可能工作不正常

**解决方案**:
- 在 StagingAreaManager 初始化时，添加维度格式验证
- 在数据库表中使用一致的维度标识格式
- 添加单元测试验证维度标识的准确性

**验收标准**:
- [ ] isInAnyStagingArea() 正确处理多维度场景
- [ ] 主世界和下界的相同坐标不会冲突

---

### 2.7 Phase 2 Task 7 中的 Mixin 注入位置和时机可能有问题

**位置**: team-collaboration.md Task 7，《What to do》部分

**问题描述**:
计划说：
```
@Inject(method = "markDirty", at = @At("HEAD"))
在方法开头检查"这个方块是不是容器"，不是就跳过
```

**问题**:
1. `BlockEntity.markDirty()` 是一个很频繁调用的方法（每次方块实体状态变化都会调用）
2. 即使添加 `instanceof Inventory` 检查，仍然会有大量的无用调用
3. `@At("HEAD")` 是在方法最开始注入，但如果检查失败就直接 return，这是最优的
4. **但问题是**：如果有多个 Mixin 都注入到 `markDirty()`，它们的执行顺序是什么？

**影响**:
- 可能影响性能（虽然计划说 instanceof 开销极低）
- Mixin 执行顺序不确定可能导致诡异 bug

**解决方案**:
- 在 Mixin 配置中明确指定优先级（fabric Mixin 支持 priority 设置）
- 添加性能测试验证 instanceof 检查的开销
- 考虑使用 `@Inject` 的 `cancellable=true` 选项

**验收标准**:
- [ ] Mixin 正确注入且不影响其他 Mixin
- [ ] 性能测试通过（instanceof 检查开销可接受）

---

### 2.8 Phase 2 Task 10 中的 Litematica 反射调用可能因版本变化失败

**位置**: team-collaboration.md Task 10，《What to do》部分

**问题描述**:
计划给出了反射调用的完整代码，包括 `getAllSubRegions()` 方法。但：
1. 计划中说"API 版本校验：反射调用前用 `Class.getMethod()` 检查方法是否存在"
2. **但给出的代码中没有包含这个检查**
3. Litematica 版本更新时，方法名可能变化

**影响**:
- Task 10 的实现可能遇到 NoSuchMethodException
- 需要添加版本兼容性处理

**解决方案**:
- 计划中已经说了要做版本校验，实现时确保添加 try-catch
- 添加降级策略（如果版本不兼容，返回空列表）

**验收标准**:
- [ ] Litematica 版本变化时能优雅降级
- [ ] 无错误日志输出到用户端

---

## 三、轻微问题（MINOR）

### 3.1 计划文档中表注释的阶段标记不一致

**位置**: team-collaboration.md 三、技术架构 3.1

**问题描述**:
计划文档中对表的注释说：
- staging_areas 表：`Phase 2 变更`（但 SQL 注释说"Phase 3"）
- staging_area_inventory 表：`Phase 2 使用`（但 SQL 注释说"Phase 3"）

**实际代码**:
- 注释都写的是 `Phase 3`

**影响**:
- 文档和代码不一致，容易误导
- 虽然不影响功能，但影响理解

**解决方案**:
- 统一修正所有表注释为正确的阶段号（Phase 2 还是 Phase 3）
- 确保 team-collaboration.md 和代码注释一致

**验收标准**:
- [ ] 所有表注释清晰标注所属阶段，文档和代码一致

---

### 3.2 Phase 2 Task 3 和 Task 4 对网络包数据结构设计不明确

**位置**: team-collaboration.md Task 3 和 Task 4

**问题描述**:
Task 3 要求创建 `StagingAreaConfigC2SPacket`，字段包括：
```
schematicId, action, name, x1, y1, z1, x2, y2, z2, areaId
```

Task 4 要求创建 `StagingAreaConfigResponseS2CPacket`，字段包括：
```
success, message, areas (List of area data)
```

**问题**:
1. Task 3 中说"action 字段用于区分操作类型，对于 LIST/DELETE 操作，坐标字段可填 0"
2. 但这样设计容易导致客户端发送不完整的数据
3. **更好的设计**是用多个字段（都是可选的）或用 sealed class 来代表不同的操作类型

**影响**:
- 代码审查时可能会因为数据结构设计不清而费时
- 运行时可能出现因缺少必要字段导致的错误

**解决方案**:
- 保持当前设计（action + 可选字段），但添加详细的文档
- 或重构为多个特定的包类（如 `StagingAreaAddC2SPacket`, `StagingAreaDeleteC2SPacket` 等）

**验收标准**:
- [ ] 网络包字段设计清晰，文档明确说明各 action 的必需字段
- [ ] 编译和序列化无问题

---

### 3.3 Phase 2 Task 5 中 StagingAreaManager 的 dirtyContainers 集合没有说明容量上限

**位置**: team-collaboration.md Task 5《内部状态》

**问题描述**:
计划说：
```
dirtyContainers: Map<BlockPos, ServerWorld> — 脏标记容器集合
```

**问题**:
1. 没有说明这个集合的容量上限
2. 长期运行中，如果有大量容器变化，集合可能无限增长
3. 没有说明多久清空一次脏标记

**影响**:
- 长期运行的服务器可能出现内存泄漏
- 性能逐渐下降

**解决方案**:
- 在 processDirtyContainers() 方法中，处理完后清空 dirtyContainers 集合
- 如果集合过大，添加容量警告日志
- 计划中已经说"每 4 tick 调用一次，批量扫描所有脏容器"，应该每次处理完都清空

**验收标准**:
- [ ] processDirtyContainers() 方法能正确清空脏标记集合
- [ ] 长期运行无内存泄漏

---

### 3.4 Phase 2 Task 8 中没有说明如何处理权限校验

**位置**: team-collaboration.md Task 8《Must NOT do》

**问题描述**:
计划说：
```
不要给非 op 玩家提供编辑权限（Phase 4 再加权限控制，目前先允许所有玩家）
```

**问题**:
1. 这意味着 Phase 2 中所有玩家都可以创建/删除/修改备货区
2. 没有权限校验代码
3. 虽然计划说"Phase 4 再加"，但最好在 Phase 2 时预留扩展点

**影响**:
- 多人服务器中，普通玩家可能误删其他人的备货区配置
- 需要在 Phase 4 时重新审视权限校验逻辑

**解决方案**:
- Phase 2 时创建一个 `checkPermission()` 方法（先简单实现为总是返回 true）
- Phase 4 时完善权限校验逻辑
- 添加注释说明权限校验的扩展点

**验收标准**:
- [ ] 权限校验方法已创建（预留扩展点）
- [ ] Phase 4 时易于添加完整的权限校验

---

### 3.5 Phase 2 Task 11 中的 GuiStagingAreaEditor 没有说明错误处理

**位置**: team-collaboration.md Task 11

**问题描述**:
计划说：
```
点击"保存到服务器"：发送 StagingAreaConfigC2SPacket 到服务端
```

**问题**:
1. 如果网络发送失败怎么办？
2. 如果服务器拒绝（权限不足）怎么办？
3. 没有错误提示给用户

**影响**:
- 用户不知道操作是否成功
- 如果失败，用户没有重试的提示

**解决方案**:
- 监听 `StagingAreaConfigResponseS2CPacket`，检查 `success` 字段
- 失败时显示错误消息（从 response 中的 message 字段）
- 成功时刷新列表

**验收标准**:
- [ ] 用户能看到保存成功/失败的提示

---

### 3.6 计划中没有提及 Litematica 未安装时的 UI 表现

**位置**: team-collaboration.md 和 ui-design.md

**问题描述**:
计划中说"Litematica 未安装时返回空列表"，但：
1. 没有说 GUI 中如何提示用户
2. "从选区添加"按钮是否应该禁用？
3. 用户如何知道需要安装 Litematica？

**影响**:
- 用户体验不佳
- 可能导致困惑

**解决方案**:
- Task 11 中添加对 Litematica 缺失的检查
- 如果缺失，显示提示文字："请安装 Litematica mod 以使用选区功能"
- "从选区添加"按钮应该禁用

**验收标准**:
- [ ] Litematica 未安装时有清晰的提示
- [ ] 相关功能被禁用而不是崩溃

---

### 3.7 Phase 3 悬停提示的性能影响未评估

**位置**: ui-design.md 六、悬停提示（Phase 3）

**问题描述**:
计划中 Phase 3 要求显示详细的悬停提示，包括：
- 物品名、总计、缺失
- 备货区数量
- 每个参与者的贡献

**问题**:
1. 悬停提示需要实时计算各个段的占比百分比
2. 如果参与者众多（5 个以上），性能可能受影响
3. 没有评估性能影响

**影响**:
- Phase 3 实现时可能发现性能问题
- 需要在那时优化

**解决方案**:
- Phase 3 时进行性能测试
- 考虑缓存计算结果（虽然数据可能频繁变化）

**验收标准**:
- [ ] Phase 3 时添加性能测试

---

### 3.8 计划中 Phase 4 的"分配"概念与 Phase 1 的"协作"概念冲突未澄清

**位置**: team-collaboration.md 四、实施阶段 Phase 4

**问题描述**:
计划说 Phase 1 是"协作式"（多人自愿加入），Phase 4 是"分配"（负责人指派）。

但这两个模式是互斥的还是并存的？
1. 能同时支持"某些人自愿协作"和"某些人被分配"吗？
2. 如果同时支持，UI 和数据库设计需要改吗？

**影响**:
- 设计的一致性不明确
- Phase 4 实现时可能发现设计冲突

**解决方案**:
- 在 Phase 2 结束时，对 Phase 3 和 Phase 4 的设计进行审视
- 确保数据模型能支持两种模式
- 更新计划文档以澄清两种模式的关系

**验收标准**:
- [ ] Phase 3 时回顾设计，确认 Phase 4 不会产生冲突

---

### 3.9 计划中没有提及客户端背包扫描的性能优化

**位置**: team-collaboration.md 三、技术架构 3.4

**问题描述**:
计划说"白名单过滤：客户端只上报当前已加入协作组的材料物品"。

但客户端背包扫描的性能如何？
1. 每 20 ticks 扫描一次（见 InventoryWatcher）
2. 白名单中有 N 个物品，是否逐一检查 N 个位置？
3. 如果原理图有 100+ 个材料，扫描会很慢吗？

**影响**:
- 客户端可能卡顿
- 虽然计划中提到"白名单过滤"，但没有评估性能

**解决方案**:
- 限制白名单大小或使用更高效的数据结构（Set 而不是 List）
- 添加性能测试
- 考虑采样扫描（不是每个 tick 都扫描，而是每 N 个 tick 扫描一次）

**验收标准**:
- [ ] 背包扫描性能可接受

---

### 3.10 计划中 Phase 2 的 13 个 Task 依赖关系复杂，但没有图示

**位置**: team-collaboration.md 四、实施阶段 Phase 2

**问题描述**:
计划说"3 个 Wave，双轨道并行开发"，但没有提供清晰的任务依赖图。

当前的文字描述：
```
轨道 A：Task 0 → Task 2 → Task 3 → Task 4
轨道 B：Task 1 → Task 5 → Task 6
Task 7 依赖轨道 B 的 Task 5
Task 8/9 依赖轨道 A 的 Task 3/4 和轨道 B 的 Task 5
...
```

**问题**:
1. 这样的文字描述容易误读
2. 没有明确的开始和结束任务
3. Task 10-15 的依赖关系不清楚

**影响**:
- 开发者可能按错误的顺序实现任务
- 导致返工

**解决方案**:
- 在计划中添加一个依赖关系图（用 ASCII 图或 Mermaid 图）
- 或用表格清晰列出每个 Task 的前置任务

**验收标准**:
- [ ] 计划文档中有清晰的任务依赖关系图

---

## 四、建议和改进方向

### 4.1 立即修复的 5 个关键问题

按优先级：
1. **删除孤立的旧网络包文件** (Issue 1.1) - 5 分钟
2. **添加 player_inventories 表持久化背包缓存** (Issue 1.2) - 30 分钟
3. **统一表注释和文档阶段标记** (Issue 1.3) - 15 分钟
4. **修正 fabric.mod.json 版本范围** (Issue 1.4) - 5 分钟 (但这是 Phase 2 Task 0)

### 4.2 建议改进的文档

1. 在 team-collaboration.md 中添加任务依赖关系图
2. 明确 staging_area 和 staging_areas 的设计理念和迁移路径
3. 在各 Task 中添加"前置任务"字段

### 4.3 建议的测试计划

1. Phase 1：
   - [ ] 服务端重启后背包缓存恢复（Issue 1.2）
   - [ ] 玩家离线再上线，贡献不丢失
   - [ ] 多人实时协作，进度同步正确

2. Phase 2：
   - [ ] 备货区配置正确保存和读取
   - [ ] 不同维度的备货区互不干扰
   - [ ] 容器变化能正确触发进度更新

3. Phase 3：
   - [ ] 悬停提示显示正确
   - [ ] 进度条分段颜色正确

4. Phase 4：
   - [ ] 权限校验生效
   - [ ] 分配和协作模式不冲突

---

## 五、总体评估

### ✓ 完成得好的地方

1. **Phase 1 核心功能完全实现** - 所有 5 个网络包、CollaborationManager、InventoryWatcher、UI 都正确实现
2. **代码结构清晰** - 网络层、服务层、UI 层分离，易于维护
3. **计划详尽** - 两份计划文档非常详细，几乎涵盖所有设计决策
4. **数据库设计合理** - 虽然有混乱之处，但整体架构是可行的

### ⚠️ 需要改进的地方

1. **Phase 1 不完整** - 背包缓存未持久化，违反了"离线玩家数据保留"的设计
2. **文档和代码不一致** - 表注释阶段标记、孤立文件等
3. **错误处理不足** - 缺少异常处理和降级策略
4. **设计细节不够明确** - 如网络包字段含义、维度标识格式等

### 📋 后续行动

**立即**（本评估报告完成后）：
- [ ] 修复 5 个严重问题（Issue 1.1 ~ 1.5）
- [ ] 补充 Phase 1 的持久化层实现

**Phase 2 开始前**：
- [ ] 更新计划文档，澄清混乱之处
- [ ] 添加任务依赖关系图

**每个 Phase 结束时**：
- [ ] 对照计划验收标准进行检查
- [ ] 更新计划文档中的完成状态

---

## 六、问题总结表

| 问题编号 | 标题 | 优先级 | 状态 | 需要的工作量 |
|---------|------|--------|------|-----------|
| 1.1 | 孤立的旧网络包文件未删除 | CRITICAL | 未完成 | 5 分钟 |
| 1.2 | 离线玩家数据持久化未实现 | CRITICAL | 未完成 | 30 分钟 |
| 1.3 | 两个 Schema 表设计混乱 | CRITICAL | 未完成 | 15 分钟 |
| 1.4 | fabric.mod.json 版本范围不符 | CRITICAL | 未完成 | 5 分钟 |
| 1.5 | 备货区查询逻辑不符新架构 | CRITICAL | 未完成 | 需 Phase 2 Task 5 |
| 2.1 | inventoryCounts 字段名不清晰 | MEDIUM | 未完成 | 10 分钟 |
| 2.2 | 玩家离线时清理逻辑缺失 | MEDIUM | 未完成 | 5 分钟 |
| 2.3 | 按钮"已完成"状态未实现 | MEDIUM | 未完成 | 需 Phase 3 |
| 2.4 | 广播逻辑可能遗漏某些玩家 | MEDIUM | 需确认 | 5 分钟 |
| 2.5 | Phase 2 Task 1 迁移逻辑不够鲁棒 | MEDIUM | 未完成 | 需 Phase 2 Task 1 |
| 2.6 | 维度标识格式未统一 | MEDIUM | 需设计 | 需 Phase 2 Task 5 |
| 2.7 | Mixin 注入优先级未设置 | MEDIUM | 需实现 | 需 Phase 2 Task 7 |
| 2.8 | Litematica 反射版本校验不完整 | MEDIUM | 需实现 | 需 Phase 2 Task 10 |
| 3.1 | 表注释阶段标记不一致 | MINOR | 未完成 | 5 分钟 |
| 3.2 | 网络包数据结构设计不明确 | MINOR | 需文档 | 10 分钟 |
| 3.3 | dirtyContainers 容量上限未说明 | MINOR | 需设计 | 需 Phase 2 Task 5 |
| 3.4 | Phase 2 Task 8 权限校验预留不足 | MINOR | 需实现 | 需 Phase 2 Task 8 |
| 3.5 | GuiStagingAreaEditor 错误处理缺失 | MINOR | 需实现 | 需 Phase 2 Task 11 |
| 3.6 | Litematica 缺失时 UI 提示不足 | MINOR | 需实现 | 需 Phase 2 Task 11 |
| 3.7 | Phase 3 悬停提示性能未评估 | MINOR | 需测试 | 需 Phase 3 |
| 3.8 | Phase 4 设计冲突未澄清 | MINOR | 需设计 | 需 Phase 2 总结 |
| 3.9 | 客户端扫描性能未优化 | MINOR | 需优化 | 需性能测试 |
| 3.10 | Phase 2 任务依赖关系图缺失 | MINOR | 需添加 | 15 分钟 |

---

## 七、验收建议

**这份计划可以继续执行，但建议**：

1. **立即修复** Issue 1.1 ~ 1.4（5 个关键问题中的 4 个）
2. **在 Phase 2 Task 1 中** 修复 Issue 1.2（背包持久化）
3. **在 Phase 2 开始前** 更新计划文档以修复 Issue 1.3（表设计混乱）
4. **在各个 Phase 进行中** 处理相应的 MEDIUM 和 MINOR 问题

**不建议因为这些问题而延迟 Phase 2 开始**，因为：
- 大部分问题都可以在相应 Phase 中修复
- 5 个关键问题可以快速修复
- 整体架构是合理的，没有根本的设计缺陷

---

**报告完成时间**: 2026-05-22 
**评估人**: Claude Code AI
**下一步**: 等待项目团队针对问题的处理计划
