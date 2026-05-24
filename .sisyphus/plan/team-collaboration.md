# SyncMaterial 团队协作功能 - 实施计划

> **创建时间**: 2025-05-16
> **最后更新**: 2025-05-22 (Phase 1 补充修复：删除 2 个孤立旧包 + 添加 player_inventories 持久化)
> **状态**: Phase 1 🔧 补充修复中 | Phase 2 🔄 详细计划已出炉
> **目标版本**: 1.21.7-0.3.0-alpha.1 (Phase 2 完成后的版本)
> **关联文档**: `.sisyphus/plan/ui-design.md`, `fabric.mod.json`（版本范围 `>=1.21.7 <=1.21.8`）
> **版本兼容**: 单 JAR 支持 MC 1.21.7 ~ 1.21.8

---

## 一、设计变更背景

### 1.1 旧方案（分配式）的核心矛盾

此前 Phase 1 的实现基于**分配式认领**：玩家认领固定数量（如"我要 30 个石头"）。

**致命问题**：当多人部分认领同一材料时，备货区物品放入后"减谁的"？
- A 认领 30，B 认领 20，C 放 10 个进备货区 → 减 A 的？减 B 的？按比例？
- 没有合理的归属逻辑，导致进度计算无法闭环

### 1.2 新方案（协作式）

**核心转变**：从"分配份额"改为"加入协作组"。

| 维度 | 旧方案（分配式） | 新方案（协作式） |
|------|-------------|-------------|
| 认领含义 | "我要 X 个" | "我加入一起收集" |
| 认领字段 | 有 `claimed_count` | 无数量，仅参与标识 |
| 进度计算 | 每人独立缺额 | **所有人共享进度** |
| 归属问题 | ⚠️ 备货区不知道减谁的 | ✅ 算公共贡献，不存在归属 |
| 数据来源 | 仅服务端数据库 | 服务端 DB + 客户端背包上报 |
| 认领操作 | 弹窗输入数量 | 一键加入，无需输入 |

---

## 二、核心设计理念

### 2.1 协作模型

```
材料总需求 = 64 个石头

玩家A 加入协作组 → 背包有 30 个
玩家B 加入协作组 → 背包有 12 个
玩家C 加入协作组 → 背包有 10 个
备货区              → 有 5 个

所有参与者的界面显示：
  总需求: 64
  已收集: 57 (备货区 5 + A:30 + B:12 + C:10)
  剩余:   7
```

**关键原则**：
1. 每个参与者的界面显示**相同的剩余值**，不存在个人缺额
2. 备货区物品无归属标记，放进去就是公共进度
3. 离线玩家基于最后一次上报的背包数据计入进度（离线后背包不会变化，数据依然有效）

### 2.2 进度计算模型

```
总需求 = material_entries.count
已收集 = 备货区库存 + Σ(所有参与者的背包库存)
剩余   = 总需求 - 已收集
```

> **离线玩家处理**：玩家离线时，使用其离线前最后一次上报的背包数据。
> 离线后背包不会变化，数据依然有效。上线后客户端重新上报，自动更新。

### 2.3 状态模型

```
未认领 ←→ 协作中 ←→ 已完成
```

| 状态 | 条件 | 按钮显示 |
|------|------|----------|
| 未认领 | 无参与者 | `[加入协作]` |
| 协作中 | 有参与者，且 已收集 < 总需求 | `[退出协作]` |
| 已完成 | 已收集 ≥ 总需求 | `[已完成 ✓]` |

---

## 三、技术架构

### 3.1 数据库 Schema 变更

```sql
-- claims: 仅标记"玩家参与了此项材料收集"，无数量字段
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

-- 同一玩家不能重复加入同一材料
CREATE UNIQUE INDEX IF NOT EXISTS idx_claim_unique ON claims(schematic_id, material_id, player_name);

-- staging_areas: 备货区区域定义（代码已存在，需要新增 name 字段）
-- 已有字段：id, schematic_id, world, x1, y1, z1, x2, y2, z2, created_at
-- Phase 2 变更：增加 name TEXT NOT NULL DEFAULT '未命名'

-- staging_area_inventory: 备货区内容物实时统计（代码已存在）
-- 已有字段：id, staging_area_id, item_id, count
-- Phase 2 使用：扫描容器后写入此表，按 staging_area_id 分组查询

-- 注：计划文档中统一使用代码中的实际表名：
--   staging_areas（区域定义，plural）、staging_area_inventory（内容物统计）
--   旧的 staging_area 表（单数形式，按 material_id 汇总）将在 Phase 2 移除
```

### 3.2 网络协议

| 包名 | 方向 | 用途 | 阶段 |
|------|------|------|------|
| `JoinCollaborationC2SPacket` | C→S | 玩家加入材料协作组 | Phase 1 ✅ |
| `LeaveCollaborationC2SPacket` | C→S | 玩家退出协作组 | Phase 1 ✅ |
| `InventoryUpdateC2SPacket` | C→S | 客户端上报**自己背包**物品变化 | Phase 1 ✅ |
| `CollaborationStatusS2CPacket` | S→C | 服务端广播协作状态（材料进度 + 参与者列表 + 各参与者背包数量 + 备货区数量） | Phase 1 ✅ |
| `QueryMaterialStatusC2SPacket` | C→S | 打开 GUI 时请求全量状态 | Phase 1 ✅ |
| `StagingAreaConfigC2SPacket` | C→S | 客户端发送备货区区域配置（pos1, pos2, name）到服务端 | Phase 2 新增 |
| `StagingAreaConfigResponseS2CPacket` | S→C | 服务端确认备货区配置已保存 | Phase 2 新增 |

> **注意**：备货区物品变化不需要客户端上报。服务端通过 Mixin 监听 `BlockEntity.markDirty()` 自动检测容器变化。

### 3.3 数据流

```
┌─────────────────────────────────────────────────────────────────┐
│                        客户端（每个玩家）                          │
│                                                                 │
│  1. 加入协作组 → JoinCollaborationC2SPacket → 服务端              │
│  2. 背包变化检测 → 过滤白名单 → InventoryUpdateC2SPacket → 服务端 │
│  3. 接收广播 → CollaborationStatusS2CPacket → 更新 UI            │
└─────────────────────────────────────────────────────────────────┘
                              ↑↓
┌─────────────────────────────────────────────────────────────────┐
│                          服务端                                   │
│                                                                 │
│  1. 维护 claims 表（参与者列表）                                    │
│  2. 维护 staging_area 表（备货区库存）                              │
│  3. 维护在线玩家背包缓存（内存 + player_inventories 表持久化）        │
│  4. 收到任意更新 → 重新计算进度 → 广播给所有参与者                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.4 背包上报策略

**白名单过滤**：客户端只上报当前已加入协作组的材料物品。
- 加入协作组时，服务端返回该原理图所有材料的 `item_id` 列表
- 客户端监听背包变化（`InventoryChanged` 事件），只上报白名单内的物品
- 上报内容：`item_id`, `count`（当前背包中该物品的总数）

**增量 vs 全量**：
- 加入协作组时：发送全量背包数据（所有协作材料）
- 后续变化：发送增量更新（只上报变化的物品）

### 3.5 备货区机制

**选区方式**：复用 Litematica 的小木棍选区（标准模式下的子区域）。
- 玩家用 Litematica 小木棍框选区域（设置 pos1、pos2）
- 客户端通过反射读取 Litematica 的 `SelectionManager.getCurrentSelection()` 获取当前选区
- 从 `AreaSelection.getAllSubRegions()` 提取所有子区域（每个子区域 = 一个备货区）
- 运行时需要 Litematica 已安装（已是 `modCompileOnly` 依赖）

**备货区配置 UI**：
- 在 `GuiMaterialList`（负责人 UI）增加"备货区配置"按钮
- 点击后打开类似 Litematica `GuiAreaSelectionEditorNormal` 的界面
- 显示当前原理图的所有备货区列表（名称、坐标）
- 支持添加（从 Litematica 当前选区导入）、删除、重命名备货区

**服务端监听**：
- Mixin 监听 `BlockEntity.markDirty()`（所有方块实体），通过 `instanceof Inventory` 过滤仅处理容器
- 容器内容变化时，检查位置是否在某个备货区区域内（包围盒检查）
- 如果在备货区内，扫描容器内容（遍历所有槽位）
- 更新 `staging_area_items` 表，广播给所有参与者

**性能优化**：
- 事件驱动，只在容器内容变化时触发
- 包围盒检查 O(N)，N = 备货区数量（通常 1-5 个）
- 只扫描备货区内的容器，其他容器完全忽略

---

## 四、实施阶段

### Phase 1: 协作认领基础 ✅（已完成）

**目标**：实现协作认领核心功能，UI 显示共享进度。

**范围**：
- [x] 数据库 Schema 变更（claims 去数量 + staging_area 表）
- [x] 网络协议实现（Join/Leave/InventoryUpdate/QueryStatus/Status 包）
- [x] 服务端协作管理器（CollaborationManager）
- [x] 客户端背包变化监听 + 白名单过滤 + 上报
- [x] 客户端状态同步（打开列表时请求全量状态）
- [x] UI 协作状态显示（"未认领"/"剩余: X"）
- [x] 删除 GuiClaimDialog（不再需要数量输入）
- [x] 加入协作时携带背包数量，消除服务端缓存延迟
- [x] 协作状态变更广播给所有参与者（多人实时同步）
- [x] 修复：扔掉最后一个物品时正确上报 0 持有量
- [x] 修复：新原理图默认显示"未认领"而非"剩余: X"

**验收标准**：
1. [x] 玩家点击"加入协作"后，无需输入数量直接加入
2. [x] 多人加入同一材料后，所有参与者看到相同的剩余数量
3. [x] 玩家背包物品变化时，进度自动更新
4. [x] 其他参与者打开界面时实时收到进度更新
5. [x] 打开/关闭界面状态正确同步
6. [x] 删除数据库后状态正确重置

### Phase 1 补充修复：孤立的旧网络包文件 + 背包持久化

> 由审查报告 Issue #1.1 和 Issue #1.2 发现的问题，需要在 Phase 2 开始前修复。

---

- [x] P1-Fix-1. 删除孤立旧网络包文件

  **What to do**:
  - 删除以下 2 个零引用的网络包文件：
    - `src/main/java/net/syncmaterial/syncmaterial/network/ClaimMaterialC2SPacket.java`
    - `src/main/java/net/syncmaterial/syncmaterial/network/ClaimResultS2CPacket.java`
  - **不要删除 `MaterialStatusS2CPacket.java`**：它被 `ModNetworkHandler` 和 `ModNetworkHandlerClient` 注册使用，是 `QueryMaterialStatusC2SPacket` 的响应包
  - 删除后执行 `./gradlew compileJava` 验证编译通过

  **Must NOT do**:
  - 不要删除 `MaterialStatusS2CPacket.java`（它正在使用中）
  - 不要删除其他还在使用的包文件
  - 不要修改任何其他文件

  **References**:
  - `src/main/java/net/syncmaterial/syncmaterial/network/` — 目录中应剩余 11 个文件
  - `ModNetworkHandler.java:42` — `MaterialStatusS2CPacket` 的 PayloadType 注册
  - `ModNetworkHandlerClient.java:20` — `MaterialStatusS2CPacket` 的 PayloadType 注册

  **Acceptance Criteria**:
  - [ ] 上述 2 个文件已删除
  - [ ] `MaterialStatusS2CPacket.java` 仍然存在（未被删除）
  - [ ] `src/main/java/net/syncmaterial/syncmaterial/network/` 目录剩 11 个文件：8 个包（`JoinCollaborationC2SPacket`, `LeaveCollaborationC2SPacket`, `InventoryUpdateC2SPacket`, `QueryMaterialStatusC2SPacket`, `CollaborationStatusS2CPacket`, `MaterialStatusS2CPacket`, `MaterialStatsRequestC2SPacket`, `MaterialStatsResponseS2CPacket`）+ `ModPackets` + `ModNetworkHandler` + `ModNetworkHandlerClient`
  - [ ] `./gradlew build` 通过

  **QA Scenarios**:
  ```text
  Scenario: 验证孤立文件已删除
    Tool: Bash
    Steps:
      1. ls src/main/java/net/syncmaterial/syncmaterial/network/
      2. 确认不存在 ClaimMaterialC2SPacket.java、ClaimResultS2CPacket.java
      3. 确认 MaterialStatusS2CPacket.java 仍然存在
    Expected Result: 目录中有 11 个文件
    Evidence: .sisyphus/evidence/p1-fix-1-network-files.txt

  Scenario: 验证编译通过
    Tool: Bash
    Steps:
      1. ./gradlew build
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/p1-fix-1-build.txt
  ```

  **Commit**: `fix(phase1): 删除 2 个孤立旧网络包文件`

---

- [x] P1-Fix-2. 实现玩家背包数据持久化（player_inventories 表）

  **What to do**:
  在 `SchematicDatabase.java` 中添加 `player_inventories` 表并实现持久化：

  1. 在 `createTables()` 中添加建表语句：
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

  2. 在 `CollaborationManager` 中：
     - 添加 `playerInventories` 初始化时从 DB 加载逻辑（在构造函数中调用 `loadPlayerInventoriesFromDb()`）
     - 修改 `updatePlayerInventory()` 同时写入 DB 和内存
     - 给 `onPlayerDisconnect()` 添加注释说明离线玩家数据已持久化

  3. 在 `SchematicDatabase.java` 中添加辅助方法：
     - `loadPlayerInventories(String schematicId)` — 从 DB 加载指定原理图的所有玩家背包数据
     - `upsertPlayerInventory(String schematicId, String playerName, int materialId, int count)` —  upsert 一条记录

  **Must NOT do**:
  - 不要修改已有的 `staging_area` 表或 `staging_areas` 表结构
  - 不要改变 `CollaborationManager.updatePlayerInventory()` 的方法签名（保持兼容）

  **References**:
  - `src/main/java/net/syncmaterial/syncmaterial/server/SchematicDatabase.java:61` — `createTables()` 方法位置
  - `src/main/java/net/syncmaterial/syncmaterial/server/CollaborationManager.java:13` — `playerInventories` 声明
  - `src/main/java/net/syncmaterial/syncmaterial/server/CollaborationManager.java:64-66` — `updatePlayerInventory()` 当前实现
  - `src/main/java/net/syncmaterial/syncmaterial/server/CollaborationManager.java:171` — `onPlayerDisconnect()` 空方法

  **Acceptance Criteria**:
  - [ ] `player_inventories` 表在服务端首次启动后创建
  - [ ] 玩家加入协作时，背包数据同时写入内存和 DB
  - [ ] 服务端重启后，`playerInventories` 从 DB 恢复
  - [ ] `./gradlew build` 通过

  **QA Scenarios**:
  ```text
  Scenario: 服务端重启后玩家背包数据恢复
    Tool: Bash
    Preconditions: 玩家 A 已加入某材料的协作组，背包有 30 个该材料物品
    Steps:
      1. 重启服务端
      2. 玩家 A 再次加入同一材料协作
      3. 查询 DB: SELECT * FROM player_inventories WHERE player_name = 'A'
    Expected Result: 重启前后的数据一致（count = 30）
    Evidence: .sisyphus/evidence/p1-fix-2-persistence.txt

  Scenario: 验证编译通过
    Tool: Bash
    Steps:
      1. ./gradlew build
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/p1-fix-2-build.txt
  ```

  **Commit**: `fix(phase1): 添加 player_inventories 表实现背包数据持久化`

---

### Phase 2: 备货区核心（详细执行计划）

**目标**：备货区物品纳入进度计算。实现 Litematica 选区读取、服务端 Mixin 监听、备货区 GUI 配置。

**执行策略**：3 个 Wave，双轨道并行开发。
- 轨道 A：Task 0 → Task 2 → Task 3 → Task 4（网络包线性依赖）
- 轨道 B：Task 1 → Task 5 → Task 6（数据库→业务逻辑线性依赖）
- 两条轨道互相独立，可并行开发
- Task 7（Mixin）依赖轨道 B 的 Task 5
- Task 8/9 依赖轨道 A 的 Task 3/4 和轨道 B 的 Task 5

---

- [x] 0. 更新 `fabric.mod.json` 版本范围

  **What to do**:
  - 修改 `fabric.mod.json` 中 `"minecraft": "~1.21.7"` 为 `"minecraft": ">=1.21.7 <=1.21.8"`
  - 确保 `depends` 中的其他依赖版本范围也合理

  **References**:
  - `src/main/resources/fabric.mod.json` — 当前版本声明

  **Acceptance Criteria**:
  - [ ] `fabric.mod.json` 版本范围为 `>=1.21.7 <=1.21.8`

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过
  ```

---

- [x] 1. 数据库 Schema 迁移

  **What to do**:
  - 在 `SchematicDatabase.createTables()` 中，给 `staging_areas` 表增加 `name` 字段
  - **安全迁移**：先查询 `staging_areas` 表是否已有 `name` 列（`PRAGMA table_info(staging_areas)`），如果没有再执行 `ALTER TABLE staging_areas ADD COLUMN name TEXT NOT NULL DEFAULT '未命名'`。避免重复执行导致列重复异常
  - 验证旧的 `staging_area` 表（单数形式，按 `material_id` 汇总）是否还在。如果在，暂时保留不删除（向下兼容），Phase 2 全部完成后确认无误再删
  - 确保 `staging_areas` 和 `staging_area_inventory` 表存在（代码中已创建，只需确认）
  - **修正数据表注释**：将代码中各表的 SQL 注释阶段编号统一修正为实际使用阶段

  **Must NOT do**:
  - 不要删除现有数据

  **References**:
  - `src/main/java/net/syncmaterial/syncmaterial/server/SchematicDatabase.java:140-162` — 现有 `staging_areas` 和 `staging_area_inventory` 表定义

  **Acceptance Criteria**:
  - [ ] 服务端启动后 `staging_areas` 表有 `name` 列
  - [ ] 旧 `staging_area` 表未被删除

  **QA Scenarios**:
  ```text
  Scenario: 数据库启动后验证表结构
    Tool: Bash
    Preconditions: 服务端已启动至少一次
    Steps:
      1. 使用 sqlite3 工具连接 syncmaterial.db
      2. 执行: PRAGMA table_info(staging_areas)
      3. 检查输出包含 name 列
    Expected Result: staging_areas 表包含 name 列
    Evidence: .sisyphus/evidence/task-1-schema-verify.txt
  ```

---

- [x] 2. 添加 Phase 2 网络包 ID

  **What to do**:
  - 在 `ModPackets.java` 中添加两个新的 `Identifier` 常量：
    - `STAGING_AREA_CONFIG = Identifier.of("syncmaterial", "staging_area_config")`
    - `STAGING_AREA_CONFIG_RESPONSE = Identifier.of("syncmaterial", "staging_area_config_response")`

  **Must NOT do**:
  - 不要修改已有的 Identifier

  **References**:
  - `src/main/java/net/syncmaterial/syncmaterial/network/ModPackets.java:1-19` — 现有包 ID 声明模式

  **Acceptance Criteria**:
  - [ ] 两个新包 ID 常量已声明
  - [ ] 包 ID 格式符合 `syncmaterial:staging_area_*` 模式

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Preconditions: 代码已修改
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过
    Evidence: .sisyphus/evidence/task-2-compile.txt
  ```

---

- [x] 3. 创建 `StagingAreaConfigC2SPacket`

  **What to do**:
  - 创建 `src/main/java/net/syncmaterial/syncmaterial/network/StagingAreaConfigC2SPacket.java`
  - 使用 `record` 模式，与 `JoinCollaborationC2SPacket` 相同的风格
  - 字段：`schematicId` (String), `action` (String: "ADD"/"DELETE"/"UPDATE"/"LIST"), `name` (String), `x1` (int), `y1` (int), `z1` (int), `x2` (int), `y2` (int), `z2` (int), `areaId` (int, 用于 DELETE/UPDATE)
  - **注意**：虽然字段较多，但保持单包设计以简化注册。action 字段用于区分操作类型，对于 LIST/DELETE 操作，坐标字段可填 0

  **Must NOT do**:
  - 不要使用复杂的嵌套类型，保持简单

  **References**:
  - `JoinCollaborationC2SPacket.java` — record + PacketCodec 模式
  - `PacketCodecs.INTEGER, PacketCodecs.STRING` — 基础类型编码

  **Acceptance Criteria**:
  - [ ] 编译通过
  - [ ] record 字段完整，包含所有必要字段

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Preconditions: 代码已修改
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过
    Evidence: .sisyphus/evidence/task-3-compile.txt
  ```

---

- [x] 4. 创建 `StagingAreaConfigResponseS2CPacket`

  **What to do**:
  - 创建 `src/main/java/net/syncmaterial/syncmaterial/network/StagingAreaConfigResponseS2CPacket.java`
  - 字段：`success` (boolean), `message` (String), `areas` (List of area data)
  - area 数据结构：`areaId` (int), `name` (String), `x1`, `y1`, `z1`, `x2`, `y2`, `z2`

  **Must NOT do**:
  - 不要包含敏感数据

  **References**:
  - `CollaborationStatusS2CPacket.java` — S2C record 模式，含嵌套 record ParticipantInfo
  - `PacketCodec.tuple` + `collect(PacketCodecs.toList())` — 列表编码

  **Acceptance Criteria**:
  - [ ] 编译通过

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Preconditions: 代码已修改
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过
    Evidence: .sisyphus/evidence/task-4-compile.txt
  ```

---

- [x] 5. 创建 `StagingAreaManager`

  **What to do**:
  - 创建 `src/main/java/net/syncmaterial/syncmaterial/server/StagingAreaManager.java`
  - **依赖**：任务 1（数据库 Schema）必须先完成
  - 方法：
    - `addStagingArea(schematicId, world, name, x1, y1, z1, x2, y2, z2)` — 插入一条备货区区域记录，返回 areaId
    - `removeStagingArea(areaId)` — 删除区域及其 inventory 数据（外键级联）
    - `getStagingAreas(schematicId)` — 返回该原理图的所有备货区列表
    - `isInAnyStagingArea(BlockPos pos, World world)` — 遍历所有备货区，用包围盒检查 pos 是否在其中。**必须同时检查坐标和维度**（`world.getRegistryKey().getValue().toString()` 作为维度标识），避免下界和主世界坐标重叠导致误判
    - `scheduleContainerScan(BlockPos pos, World world)` — 标记容器为脏，加入待扫描队列
    - `processDirtyContainers()` — 服务端每 4 tick 调用一次，批量扫描所有脏容器，**处理完后清空 `dirtyContainers` 集合**
    - `scanContainer(BlockPos pos, World world)` — 扫描容器所有槽位，更新 `staging_area_inventory` 表
    - `getStagingCountForMaterial(schematicId, String itemId)` — 关联查询统计总数：
      ```sql
      SELECT COALESCE(SUM(sai.count), 0)
      FROM staging_area_inventory sai
      JOIN staging_areas sa ON sai.staging_area_id = sa.id
      WHERE sa.schematic_id = ? AND sai.item_id = ?
      ```
    - `onContainerRemoved(BlockPos pos, World world)` — 容器销毁时清零对应库存
  - **初始化入口**：在 `SyncMaterial` 或 `ModNetworkHandler.initializeServices()` 中创建并注入 `StagingAreaManager` 实例

  **内部状态**：
  - 缓存备货区列表在内存中（`List<StagingArea>`），`isInAnyStagingArea` 使用 AABB 包围盒检测
  - `dirtyContainers: Map<BlockPos, ServerWorld>` — 脏标记容器集合
  - `world` 列存储格式：`world.getRegistryKey().getValue().toString()`（如 `"minecraft:overworld"`）

  **Must NOT do**:
  - 不要扫描非容器方块（如熔炉、酿造台等，虽然它们也继承容器基类，但不宜计入建筑进度）
  - 不要用反射或复杂空间索引，备货区数量很少
  - 不要在 Mixin 中直接扫描（用脏标记延迟）

  **References**:
  - `CollaborationManager.java` — 类似的服务端管理器模式
  - `SchematicDatabase.java:153-162` — `staging_area_inventory` 表定义

  **Acceptance Criteria**:
  - [ ] 编译通过
  - [ ] 所有方法签名正确
  - [ ] `getStagingCountForMaterial` 返回正确的汇总值

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过

  Scenario: 手动测试 - 坐标和维度判定
    Tool: 手动测试
    Steps:
      1. 在主世界配置备货区 (x:100~200, y:64~70, z:100~200)
      2. 在下界相同坐标放置箱子
      3. 往下界箱子放物品
      4. 观察进度是否变化
    Expected Result: 下界的箱子不影响主世界备货区进度
  ```

---

- [x] 6. 更新 `CollaborationManager.getCollaborationStatus()` 使用新备货区表

  **What to do**:
  - **依赖**：任务 5（StagingAreaManager）必须先完成
  - 修改 `CollaborationManager` 中的 `getCollaborationStatus()` 方法（`CollaborationManager.java:69`）
  - 将旧的 `staging_area` 表查询替换为调用 `StagingAreaManager.getStagingCountForMaterial(schematicId, itemId)`
  - `getStagingCountForMaterial` 内部使用 JOIN 查询：
    ```sql
    SELECT COALESCE(SUM(sai.count), 0)
    FROM staging_area_inventory sai
    JOIN staging_areas sa ON sai.staging_area_id = sa.id
    WHERE sa.schematic_id = ? AND sai.item_id = ?
    ```

  **Must NOT do**:
  - 不要删除旧的 `staging_area` 表（等 Phase 2 全部验证通过再删）
  - 不要修改已有的 collaboration 业务逻辑

  **References**:
  - `CollaborationManager.java:69-123` — `getCollaborationStatus()` 完整实现
  - `SchematicDatabase.java:153-162` — `staging_area_inventory` 表定义

  **Acceptance Criteria**:
  - [ ] `getCollaborationStatus()` 返回的 `stagingCount` 正确反映备货区内的物品总数
  - [ ] 旧 `staging_area` 表不受影响

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Preconditions: 代码已修改
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过
    Evidence: .sisyphus/evidence/task-6-compile.txt
  ```

---

- [x] 7. 创建容器变化监听 Mixin

  **What to do**:
  - **依赖**：任务 5（StagingAreaManager）必须先完成
  - 创建 `src/main/java/net/syncmaterial/syncmaterial/mixin/BlockEntityMixin.java`
  - **注入逻辑**：注入所有方块实体的 `markDirty()` 方法（Yarn 映射名），在方法开头检查"这个方块是不是容器"，不是就跳过
  - `@Mixin(BlockEntity.class)` + `@Inject(method = "markDirty", at = @At("HEAD"))`
  - 方法逻辑：
    1. 检查 `this instanceof Inventory`，不是容器直接 return
    2. 检查 `this.getWorld() != null`（排除未加载的方块实体）
    3. 检查 `!this.getWorld().isClient()`（仅服务端处理）
    4. 获取 position 和 world
    5. 调用 `StagingAreaManager.isInAnyStagingArea(pos, world)` 检查是否在备货区内
    6. 如果在，调用 `StagingAreaManager.scheduleContainerScan(pos, world)`（使用脏标记+延迟扫描，避免高频触发）
  - **脏标记+延迟扫描**：`StagingAreaManager` 维护 `dirtyContainers` 集合，Mixin 只标记脏，不立即扫描。服务端每 4 tick（200ms）批量扫描一次所有脏容器
  - **扫描后只广播受影响的材料**：找出该容器位置涉及的物品，只计算并广播这些物品对应材料的进度
  - **线程安全**：所有数据库操作和广播必须通过 `world.getServer().execute()` 在服务端主线程执行
  - **Mixin 配置**：在 `syncmaterial.mixins.json` 的 `mixins` 列表中注册 `"BlockEntityMixin"`（双端加载，方法内通过 `isClient()` 守卫）

  **Must NOT do**:
  - 不要在客户端执行任何数据库操作
  - 不要在 Mixin 中直接扫描容器（用脏标记延迟）
  - 不要遍历所有材料广播（只广播受影响的材料）

  **References**:
  - `WidgetSyncmaticaServerPlacementEntryMixin.java` — 现有 Mixin 模式
  - `syncmaterial.mixins.json:1-11` — Mixin 配置文件结构

  **Acceptance Criteria**:
  - [ ] 服务端启动时 Mixin 正确注入，无报错
  - [ ] 往备货区箱子放物品时触发容器扫描
  - [ ] 非备货区箱子不会触发扫描
  - [ ] 漏斗高频输入时有节流（200ms 内只扫描一次）

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过

  Scenario: 手动测试 - 备货区容器变化触发进度更新
    Tool: 手动测试（多人联机）
    Preconditions: 服务端已配置备货区，玩家已加入协作组
    Steps:
      1. 在服务端配置一个备货区区域
      2. 玩家 A 加入某材料的协作组
      3. 往备货区箱子放入该材料物品
      4. 观察玩家 A 的 GUI 是否实时更新剩余数量
      5. 从备货区箱子取出物品
      6. 观察玩家 A 的 GUI 是否实时更新剩余数量
    Expected Result: 放入物品后剩余减少，取出物品后剩余增加

  Scenario: 手动测试 - 非备货区容器变化不影响进度
    Tool: 手动测试
    Steps:
      1. 在非备货区位置放置一个箱子
      2. 往箱子里放入物品
      3. 观察是否有进度变化
    Expected Result: 非备货区容器变化不影响进度

  Scenario: 手动测试 - 容器销毁后库存清零
    Tool: 手动测试
    Preconditions: 备货区箱子内有物品，协作组有参与者
    Steps:
      1. 挖掉备货区箱子
      2. 观察进度是否更新
    Expected Result: 容器销毁后，对应库存清零，剩余增加
  ```

---

- [x] 8. 更新 `ModNetworkHandler` 处理备货区配置包

  **What to do**:
  - **依赖**：任务 3（C2SPacket）、任务 5（StagingAreaManager）必须先完成
  - 在 `ModNetworkHandler.register()` 中注册 `StagingAreaConfigC2SPacket`
  - 注册 `ServerPlayNetworking.registerGlobalReceiver(STAGING_AREA_CONFIG, ...)`
  - Handler 逻辑：
    - `action == "ADD"`：调用 `StagingAreaManager.addStagingArea()`，返回 `StagingAreaConfigResponseS2CPacket` 含新 area 信息
    - `action == "DELETE"`：调用 `StagingAreaManager.removeStagingArea()`
    - `action == "LIST"`：查询该原理图的所有备货区，返回列表
    - 向发起请求的玩家发送响应包
  - 需要获取 `StagingAreaManager` 的引用（通过 `initializeServices` 注入或静态访问）

  **Must NOT do**:
  - 不要给非 op 玩家提供编辑权限（Phase 4 再加权限控制，目前先允许所有玩家）

  **References**:
  - `ModNetworkHandler.java:62-77` — JoinCollaborationC2SPacket handler 模式
  - `ModNetworkHandler.java:16-22` — `initializeServices()` 模式

  **Acceptance Criteria**:
  - [ ] 编译通过
  - [ ] Handler 正确注册

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Preconditions: 代码已修改
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过
    Evidence: .sisyphus/evidence/task-8-compile.txt
  ```

---

- [x] 9. 更新 `ModNetworkHandlerClient` 注册新包

  **What to do**:
  - 在 `ModNetworkHandlerClient.register()` 中注册 `StagingAreaConfigC2SPacket` 和 `StagingAreaConfigResponseS2CPacket`
  - 注册 `ClientPlayNetworking.registerGlobalReceiver(STAGING_AREA_CONFIG_RESPONSE, ...)` 用于接收配置确认
  - 响应 handler：更新备货区列表缓存，刷新 UI
  - **说明**：Fabric 规范要求客户端和服务端都注册 `PayloadType`（`PayloadTypeRegistry.playC2S()` 和 `PayloadTypeRegistry.playS2C()`），这是正常设计，不是重复注册

  **Must NOT do**:
  - 不要重复注册已存在的包

  **References**:
  - `ModNetworkHandlerClient.java:9-30` — 完整的包注册和 handler 模式

  **Acceptance Criteria**:
  - [ ] 编译通过

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Preconditions: 代码已修改
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过
    Evidence: .sisyphus/evidence/task-9-compile.txt
  ```

---

- [x] 10. 创建 Litematica 选区读取工具类

  **What to do**:
  - 创建 `src/main/java/net/syncmaterial/syncmaterial/client/LitematicaSelectionReader.java`
  - 通过反射读取 Litematica 的当前选区：
    ```java
    // 反射调用链（Yarn 映射名，对应 Litematica 0.23.x）
    Class<?> dataManagerClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
    Method getSelectionManager = dataManagerClass.getMethod("getSelectionManager");
    Object selectionManager = getSelectionManager.invoke(null);
    
    Method getCurrentSelection = selectionManager.getClass().getMethod("getCurrentSelection");
    Object areaSelection = getCurrentSelection.invoke(selectionManager);
    if (areaSelection == null) return emptyList();
    
    // 注意：Yarn 映射中方法名为 getAllSubRegions()，不是 getSubRegionBoxes()
    Method getAllSubRegions = areaSelection.getClass().getMethod("getAllSubRegions");
    Map<String, Object> subRegions = (Map<String, Object>) getAllSubRegions.invoke(areaSelection);
    ```
  - **API 版本校验**：反射调用前用 `Class.getMethod()` 检查方法是否存在。如果抛出 `NoSuchMethodException`，说明 Litematica 版本变更，记录警告日志并返回空列表（降级策略）。对齐参考源码：`.关于本项目的依赖模组的源代码/litematica-LTS-1.21.8/`
  - 将 Box 映射为 `StagingAreaRegion` 数据结构（name, pos1, pos2）
  - Box 类没有 `contains()` 方法，需要自定义 AABB 检测：
    ```java
    private boolean isInsideBox(BlockPos pos, BlockPos pos1, BlockPos pos2) {
        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());
        return pos.getX() >= minX && pos.getX() <= maxX
            && pos.getY() >= minY && pos.getY() <= maxY
            && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }
    ```
  - 处理 Litematica 未安装的情况（捕获 `NoClassDefFoundError`，返回空列表）

  **Must NOT do**:
  - 不要依赖 Litematica 的编译时引用（用 `Class.forName` 反射）
  - 不要假设 Litematica 一定安装

  **References**:
  - `SelectionManager.java` — `getCurrentSelection()` 方法
  - `AreaSelection.java` — `getAllSubRegions()` 返回 `Map<String, Box>`
  - `Box.java` — `getPos1()`, `getPos2()`, `getName()` 方法

  **Acceptance Criteria**:
  - [ ] Litematica 已安装时能正确读取选区数据
  - [ ] Litematica 未安装时返回空列表，不抛异常

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过

  Scenario: 手动测试 - Litematica 未安装时不崩溃
    Tool: 手动测试
    Steps:
      1. 移除 Litematica mod
      2. 启动游戏
      3. 打开 SyncMaterial GUI
      4. 点击"备货区配置"
    Expected Result: GUI 正常打开，选区列表为空，不崩溃
  ```

---

- [x] 11. 创建 `GuiStagingAreaEditor`

  **What to do**:
  - 创建 `src/main/java/net/syncmaterial/syncmaterial/client/gui/GuiStagingAreaEditor.java`
  - 继承 `GuiListBase`（类似 `GuiMaterialList`，但渲染备货区列表）
  - 界面布局：
    - 顶部：当前原理图名称 + "[×] 关闭"按钮
    - 中间：备货区列表（名称、坐标范围、启用复选框）
    - 底部："从选区添加"、"保存到服务器"、"取消"按钮
    - 提示文字：提示用小木棍框选后点击"从选区添加"
  - 内部维护 `List<StagingAreaRegion>` 缓存
  - "从选区添加"：调用 `LitematicaSelectionReader.read()` 获取当前选区，添加区域
  - "保存到服务器"：发送 `StagingAreaConfigC2SPacket`（action="ADD"/"DELETE"）
  - 打开时发送 `StagingAreaConfigC2SPacket`（action="LIST"）获取已有配置
  - **Litematica 缺失处理**：如果 Litematica 未安装，"从选区添加"按钮应禁用（灰色），并显示提示文字："请安装 Litematica 以使用选区功能"
  - **保存结果反馈**：监听 `StagingAreaConfigResponseS2CPacket`，`success=false` 时在 GUI 底部显示错误消息（从 response 的 `message` 字段读取）

  **Must NOT do**:
  - 不要依赖 Litematica 编译时引用（用反射读取）
  - 不要在无选区时弹错误
  - 不要在 Litematica 缺失时崩溃

  **References**:
  - `GuiMaterialList.java` — 按钮创建模式（`createButtonRefresh`, `createButtonClose`）
  - `GuiAreaSelectionEditorNormal.java:38-80` — 区域编辑器模式（列表 + 编辑区域 + 按钮）
  - `syncmaterial.mixins.json` — 确保不影响 Mixin 配置

  **Acceptance Criteria**:
  - [ ] 打开后显示当前原理图的备货区列表
  - [ ] 点击"从选区添加"后，Litematica 选区的子区域出现在列表中
  - [ ] 点击"保存到服务器"后，配置发送到服务端

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Preconditions: 代码已修改
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过
    Evidence: .sisyphus/evidence/task-11-compile.txt
  ```

---

- [x] 12. 创建备货区列表 Widget

  **What to do**:
  - 创建 `src/main/java/net/syncmaterial/syncmaterial/client/gui/widgets/WidgetListStagingAreas.java`
  - 继承 `WidgetListBase`（类似 `WidgetListMaterialList`）
  - 创建 `src/main/java/net/syncmaterial/syncmaterial/client/gui/widgets/WidgetStagingAreaEntry.java`
  - 渲染每个备货区的：名称、坐标范围（x1~x2, y1~y2, z1~z2）、删除按钮

  **Must NOT do**:
  - 不要过度装饰 UI，保持功能性

  **References**:
  - `WidgetListMaterialList.java` — 列表 Widget 模式
  - `WidgetMaterialListEntry.java` — 条目 Widget 渲染模式

  **Acceptance Criteria**:
  - [ ] 编译通过

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Preconditions: 代码已修改
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过
    Evidence: .sisyphus/evidence/task-12-compile.txt
  ```

---

- [x] 13. 更新 `GuiMaterialList` 增加"备货区配置"按钮

  **What to do**:
  - 在 `initGui()` 中，刷新建按钮布局，在"刷新列表"按钮左边增加"备货区配置"按钮
  - 按钮标签：`"备货区配置"`
  - 点击行为：打开 `new GuiStagingAreaEditor(schematicId)`
  - 调整 `createButtonRefresh` / `createButtonToggleHud` / `createButtonClose` 的 x 坐标以腾出空间
  - 可选：仅负责人可见（Phase 2 暂不限制，所有人可见）

  **Must NOT do**:
  - 不要移除已有的三个按钮
  - 不要改变已登录的交互行为

  **References**:
  - `GuiMaterialList.java:53-90` — `initGui()`, `createButtonRefresh()`, `createButtonClose()`, `createButtonToggleHud()`
  - ui-design.md 五、备货区配置 UI（Phase 2） — 按钮位置和交互设计

  **Acceptance Criteria**:
  - [ ] 按钮出现在 GUI 顶部
  - [ ] 点击后打开备货区编辑器

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Preconditions: 代码已修改
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过
    Evidence: .sisyphus/evidence/task-13-compile.txt
  ```

---

- [x] 14. 新增 `player_inventories` 表实现背包缓存持久化

  **What to do**:
  - 在 `SchematicDatabase.createTables()` 中新增 `player_inventories` 表：
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
  - 修改 `CollaborationManager`：`updatePlayerInventory()` 同时写入内存缓存和数据库
  - 服务端启动时从 `player_inventories` 表加载缓存到内存
  - `getCollaborationStatus()` 优先从内存缓存读取，缓存未命中时查数据库

  **References**:
  - `CollaborationManager.java:64-67` — 当前 `updatePlayerInventory()` 只写内存

  **Acceptance Criteria**:
  - [ ] 服务端重启后，背包缓存从数据库恢复
  - [ ] 玩家离线再上线，背包数据不丢失

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过

  Scenario: 手动测试 - 重启后缓存恢复
    Tool: 手动测试
    Steps:
      1. 玩家 A 加入协作组，背包有 30 个石头
      2. 停止服务端
      3. 重新启动服务端
      4. 玩家 A 重新登录
      5. 查看进度是否仍包含玩家 A 的 30 个石头
    Expected Result: 重启后进度不丢失
  ```

---

- [x] 15. 容器销毁时自动清零备货区库存

  **What to do**:
  - 在任务 7 的 Mixin 中增加对 `World.removeBlockEntity()` 的监听
  - 当方块实体被移除时，检查该位置是否在备货区内
  - 如果在，调用 `StagingAreaManager.onContainerRemoved(pos, world)` 清零对应库存
  - 广播进度更新给所有参与者

  **References**:
  - 任务 5 中 `StagingAreaManager.onContainerRemoved()` 方法定义

  **Acceptance Criteria**:
  - [ ] 挖掉备货区箱子后，进度自动更新

  **QA Scenarios**:
  ```text
  Scenario: 编译验证
    Tool: Bash
    Steps:
      1. 运行 ./gradlew compileJava
      2. 检查无编译错误
    Expected Result: 编译通过

  Scenario: 手动测试 - 挖掉箱子后进度更新
    Tool: 手动测试
    Steps:
      1. 备货区箱子内有 10 个石头
      2. 挖掉箱子
      3. 观察进度是否更新（剩余增加 10）
    Expected Result: 挖掉箱子后进度正确更新
  ```

---

**Phase 2 验收标准**：
1. [ ] 玩家用 Litematica 小木棍框选区域后，点击"从选区添加"成功显示在列表中
2. [ ] 保存到服务端后，备货区区域数据存入 `staging_areas` 表
3. [ ] 往备货区箱子放物品，进度自动更新（Mixin 触发扫描）
4. [ ] 从备货区箱子取走物品，进度自动减少
5. [ ] 非备货区容器变化不影响进度
6. [ ] 多人同时看到备货区进度变化
7. [ ] Litematica 未安装时，选区读取返回空列表（不崩溃）
8. [ ] 漏斗高频输入时，容器扫描有节流机制（500ms 内只扫描一次）
9. [ ] 备货区在不同维度（主世界/下界）不会误判

### Phase 3: 进度条 + 悬停提示 + 高级 UI

**目标**：分段进度条、悬停详情、完成动画。

**范围**：
- [x] `WidgetMaterialListEntry` 进度条渲染（分段：备货区橙色、玩家绿/蓝/紫、剩余灰色）
- [x] 进度条下方文字：`备货区: X | 玩家A: Y | 玩家B: Z | 剩余: W`
- [x] 悬停提示（`postRenderHovered`）：显示物品名、总计、缺失 + 协作参与者详情 + 备货区数量
- [x] 进度条悬停分段提示：鼠标悬停在某段上显示贡献来源详情
- [x] 完成状态：深绿色满进度条 + "已完成 ✓"
- [x] 未认领状态：灰色虚线进度条 + "未认领"
- [x] 超过 5 个参与者时，第 6 个及以后合并为"其他"段

**验收标准**：
1. [ ] 进度条颜色段正确，宽度与数量成正比
2. [ ] 悬停提示显示所有参与者贡献 + 备货区数量
3. [ ] 完成/未认领状态显示正确

### Phase 4: 权限与分配

**目标**：负责人可管理协作组。

**设计决策**（用户确认）：
1. 负责人 = 上传原理图的人（`uploaded_by` 字段）
2. 负责人可分配物品收集任务给其他玩家，也可授权其他人有分配权限
3. 自行认领默认关闭，关闭后玩家不能加入协作，只能被动等待负责人分配
4. 负责人可踢出玩家（手动加入的和被分配的均可踢出）

**范围**：
- [x] 负责人指定分派人白名单
- [x] 负责人开关"允许自行认领"
- [x] 被分配的玩家自动加入协作组
- [x] 负责人可移除参与者

---

## 五、关键决策记录

### 5.1 为什么不用服务端轮询背包？

**决策**：客户端主动上报，服务端不轮询。

**理由**：
- 轮询开销大（每个在线玩家每秒扫描一次背包）
- 事件驱动更精确（只在物品变化时上报）
- 白名单过滤后上报量很小（只上报协作材料）

### 5.2 为什么备货区不标记归属？

**决策**：备货区物品无归属，算公共进度。

**理由**：
- Minecraft 无法可靠追踪物品是谁放入的（漏斗/自动化/离线登录）
- 标记归属会导致边界情况复杂化
- 协作模型下归属概念本身就不适用

### 5.3 离线玩家怎么处理？

**决策**：离线玩家保留在协作组中，基于最后一次上报的背包数据计入进度。

**理由**：
- 离线后背包不会变化，最后一次上报的数据依然有效
- 上线后客户端重新上报，自动更新
- 保持进度连续性，避免玩家上下线导致进度跳动

### 5.4 进度计算精度问题

**决策**：进度 = 备货区 + Σ(所有参与者上报的背包数量)，包含离线玩家。

**理由**：
- 离线玩家数据有效（背包不变），计入更准确
- 上报是事件驱动的，实时性好
- 简单可靠，不需要复杂的同步机制

### 5.5 备货区选区方式

**决策**：复用 Litematica 的小木棍选区（标准模式下的子区域）。

**理由**：
- Litematica 已是运行时依赖，选区工具成熟
- 子区域（SubRegionBox）天然支持多备货区
- 不需要重新实现选区工具
- 通过反射读取 `SelectionManager`，降低 API 变更风险

### 5.6 备货区监听方式

**决策**：服务端 Mixin 监听 `BlockEntity.markDirty()`（Yarn 映射名），注入所有方块实体，通过 `instanceof Inventory` 过滤仅处理容器。

**理由**：
- Yarn 映射中容器标记方法为 `markDirty()`，非 Mojang 映射的 `setChanged()`
- Yarn 映射中容器基类为 `LockableContainerBlockEntity`，但该类未重写 `markDirty()`，注入会穿透到父类
- 方案 A（注入所有方块实体 + `instanceof` 过滤）逻辑更清晰，覆盖所有容器类型
- `instanceof Inventory` 检查开销极低，几乎无性能影响

**备选方案（已排除）**：
- *Fabric BlockEntity 事件*：Fabric 没有提供容器变化事件 API，无法使用
- *World 事件（ServerWorld.CHUNK_EVENT）*：粒度太粗（区块级），无法定位到具体容器
- *定期扫描缓存 diff*：需要定时器，轮询开销大；且 diff 比对需要保存上次快照，内存开销大

### 5.7 备货区容器扫描方式

**决策**：脏标记+延迟扫描（200ms 节流），批量处理所有脏容器。

**理由**：
- 漏斗/红石机器可能每秒触发数十次 `markDirty()`，直接扫描会压垮数据库
- 脏标记将高频触发折叠为低频批量扫描，大幅降低数据库压力
- 每 4 tick（200ms）批量扫描一次，兼顾实时性和性能

**备选方案（已排除）**：
- *增量扫描（只记录变化的物品）*：需要记录上次扫描状态做 diff；漏斗等自动化可能移除物品但不触发扫描，导致计数不准；实现复杂度高
- *只记录变化量*：需要事务保证原子性；红石高频变化可能导致并发问题；需要定期全量校验保证一致性

### 5.8 版本兼容策略

**决策**：单 JAR 支持 MC 1.21.7 ~ 1.21.8，`fabric.mod.json` 声明版本范围。

**理由**：
- 1.21.7 和 1.21.8 核心 API 稳定，Mixin 目标类不变
- Litematica API 用反射读取，即使版本变了也能适配
- 参考 Syncmatica 的做法（`"minecraft": ">=1.21.6 <=1.21.8"`）
- 如果未来版本有破坏性变更，再考虑分支或反射适配

---

## 六、风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 客户端不上报 | 进度不准确 | 加入协作组时发送全量数据；定期心跳校验 |
| 背包扫描性能 | 客户端卡顿 | 白名单过滤，只扫描协作材料；增量上报 |
| 并发更新冲突 | 进度计算错误 | 服务端集中计算，客户端只上报原始数据 |
| UI 进度条渲染复杂 | 性能问题 | 限制显示的玩家数量（最多 5-6 个），超出合并为"其他" |

---

## 七、文件变更清单

### 新增文件
- `src/main/java/net/syncmaterial/syncmaterial/network/JoinCollaborationC2SPacket.java` ✅ Phase 1
- `src/main/java/net/syncmaterial/syncmaterial/network/LeaveCollaborationC2SPacket.java` ✅ Phase 1
- `src/main/java/net/syncmaterial/syncmaterial/network/InventoryUpdateC2SPacket.java` ✅ Phase 1
- `src/main/java/net/syncmaterial/syncmaterial/network/CollaborationStatusS2CPacket.java` ✅ Phase 1
- `src/main/java/net/syncmaterial/syncmaterial/network/QueryMaterialStatusC2SPacket.java` ✅ Phase 1
- `src/main/java/net/syncmaterial/syncmaterial/server/CollaborationManager.java` ✅ Phase 1
- `src/main/java/net/syncmaterial/syncmaterial/client/InventoryWatcher.java` ✅ Phase 1
- `src/main/java/net/syncmaterial/syncmaterial/network/StagingAreaConfigC2SPacket.java` — Phase 2
- `src/main/java/net/syncmaterial/syncmaterial/network/StagingAreaConfigResponseS2CPacket.java` — Phase 2
- `src/main/java/net/syncmaterial/syncmaterial/server/StagingAreaManager.java` — Phase 2
- `src/main/java/net/syncmaterial/syncmaterial/mixin/BlockEntityMixin.java` — Phase 2（任务 7）
- `src/main/java/net/syncmaterial/syncmaterial/client/LitematicaSelectionReader.java` — Phase 2
- `src/main/java/net/syncmaterial/syncmaterial/client/gui/GuiStagingAreaEditor.java` — Phase 2
- `src/main/java/net/syncmaterial/syncmaterial/client/gui/widgets/WidgetListStagingAreas.java` — Phase 2
- `src/main/java/net/syncmaterial/syncmaterial/client/gui/widgets/WidgetStagingAreaEntry.java` — Phase 2

### 修改文件
- `src/main/java/net/syncmaterial/syncmaterial/server/SchematicDatabase.java`（Schema 变更 + Phase 1）
- `src/main/java/net/syncmaterial/syncmaterial/network/ModNetworkHandler.java`（新包处理 + Phase 1）
- `src/main/java/net/syncmaterial/syncmaterial/network/ModNetworkHandlerClient.java`（新包处理 + Phase 1）
- `src/main/java/net/syncmaterial/syncmaterial/client/gui/SyncMaterialList.java`（进度计算 + 协作状态）
- `src/main/java/net/syncmaterial/syncmaterial/client/gui/widgets/WidgetMaterialListEntry.java`（进度条渲染）
- `src/main/java/net/syncmaterial/syncmaterial/api/MaterialEntry.java`（新增协作状态字段）
- `src/main/java/net/syncmaterial/syncmaterial/network/ModPackets.java`（Phase 2 包 ID 常量）
- `src/main/java/net/syncmaterial/syncmaterial/client/gui/GuiMaterialList.java`（Phase 2 备货区按钮）
- `src/main/resources/syncmaterial.mixins.json`（Phase 2 新增 Mixin）

### 删除文件
- `src/main/java/net/syncmaterial/syncmaterial/client/gui/GuiClaimDialog.java`（不再需要数量输入）
- `src/main/java/net/syncmaterial/syncmaterial/network/ClaimMaterialC2SPacket.java`（替换为 JoinCollaboration）
- `src/main/java/net/syncmaterial/syncmaterial/network/ClaimResultS2CPacket.java`（替换为 CollaborationStatus）

---

## 八、提交策略

每个阶段完成后单独提交。Phase 2 内部按功能点分多次提交：

**Phase 1（已完成）：**
- `feat: 协作式认领基础实现`

**Phase 2（备货区集成）：**
- `feat: 更新 fabric.mod.json 版本范围`
- `feat: 数据库 Schema 迁移（staging_areas 增加 name 字段 + player_inventories 表）`
- `feat: 添加备货区网络包（StagingAreaConfig）`
- `feat: 添加 StagingAreaManager 服务端核心（含脏标记延迟扫描）`
- `feat: 添加 BlockEntityMixin 容器监听（含线程安全、客户端守卫、广播优化）`
- `feat: 添加 Litematica 选区读取工具（反射 getAllSubRegions + 自定义 AABB）`
- `feat: 添加备货区 UI（GuiStagingAreaEditor）`
- `feat: 整合备货区到进度计算（含 JOIN 查询）`

**Phase 3（进度条 + 悬停提示）：**（预留）
**Phase 4（权限与分配）：**（预留）
