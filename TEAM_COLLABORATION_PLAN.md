# SyncMaterial 团队协作功能 - 完整规划文档

> **创建时间**: 2025年
> **状态**: 规划阶段
> **目标版本**: 0.2.0-alpha.x

---

## 一、项目背景

SyncMaterial 是一个 Minecraft Fabric 模组（1.21.7），当前版本 `0.1.0-alpha.4`，功能是增强 Litematica 和 Syncmatica，提供服务器共享原理图的材料统计。

本次规划目标是为模组增加**团队协作功能**，让玩家可以协同收集原理图所需材料。

---

## 二、功能需求完整描述

### 1. 认领机制
- 玩家可自行认领原理图材料清单中的材料
- 支持部分认领（如需要64个，只认领32个）
- 认领后其他人不能再认领同一材料
- 玩家可主动放弃已认领的任务，放弃后材料释放给其他人
- 负责人可撤回已分配的任务

### 2. 分配机制
- 原理图上传者（负责人）可分配任务给其他玩家
- 负责人可指定其他人也有分配权限（白名单）
- 支持部分分配（如需要64个铁锭，分配给玩家A 32个，玩家B 32个）
- 离线玩家可以被分配任务，上线后弹窗通知

### 3. 接受/拒绝机制
- 被分配任务的玩家弹窗通知
- 接受：确认并开始任务
- 拒绝：需填写理由，理由发送给负责人

### 4. 权限机制
- 负责人控制：是否允许玩家自行认领（开关）
- 负责人设置：谁能分配任务（白名单）

### 5. 备货区机制
- 负责人框选一个区域作为备货区
- 服务端监听备货区内容器的变化（非轮询，通过Mixin监听Container.setChanged()）
- 备货区物品数量动态更新
- 备货区物品减少时，缺口变成新的"未认领"需求

### 6. 材料状态模型

```
未认领 → 正在收集 → 已完成收集但未放入备货区 → 已完成备货
```

| 状态 | 触发条件 |
|------|----------|
| 未认领 | 无人负责 / 玩家放弃任务 / 负责人撤回分配 |
| 正在收集 | 玩家认领或被分配 |
| 已完成收集但未放入备货区 | 玩家背包中材料数量 ≥ 需要数量，但备货区中还没有 |
| 已完成备货 | 备货区中材料数量 ≥ 需要数量 |

**关键逻辑**:
- 玩家剩余需要 = 总需要 - 备货区当前数量
- 备货区增加 → 所有玩家的剩余需要减少
- 任务一旦完成不会倒退
- 备货区物品被拿走 → 缺口变成新的"未认领"需求

### 7. HUD
- 参考 Litematica 的 HUD 设计
- 只显示当前玩家的任务进度
- 支持多 HUD（多个任务同时显示）

### 8. 离线处理
- 离线玩家可以被分配任务
- 上线后弹窗通知

### 9. 任务超时/放弃机制
- 任务不设置超时
- 玩家可以主动放弃已认领的任务，放弃后材料释放给其他人
- 被分配的任务可以被负责人撤回

### 10. 备货区验证
- 容器被拆了或物品被拿走，算备货区物品丢失
- 只丢失一部分，则显示"物品丢失"状态并显示缺少数量
- 负责人可以重新分配任务，或其他玩家可以认领这个

### 11. 完成流程
- 玩家收到任务或自行认领物品
- 打开 HUD 辅助收集
- 收集完毕，放入备货区容器
- 持续检测备货区容器变化
- 放入足够数量后，任务完成

---

## 三、技术规划

### 1. 数据库 Schema 设计

```sql
-- 原理图设置（扩展现有 schematics 表）
ALTER TABLE schematics ADD COLUMN allow_self_claim BOOLEAN DEFAULT 1;

-- 认领记录
CREATE TABLE IF NOT EXISTS claims (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    schematic_id TEXT NOT NULL,
    material_id TEXT NOT NULL,
    player_uuid TEXT NOT NULL,
    player_name TEXT NOT NULL,
    claimed_count INTEGER NOT NULL,
    status TEXT DEFAULT 'active',
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE
);

-- 分配记录
CREATE TABLE IF NOT EXISTS assignments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    schematic_id TEXT NOT NULL,
    material_id TEXT NOT NULL,
    assignee_uuid TEXT NOT NULL,
    assignee_name TEXT NOT NULL,
    assigned_by_uuid TEXT NOT NULL,
    assigned_by_name TEXT NOT NULL,
    assigned_count INTEGER NOT NULL,
    status TEXT DEFAULT 'pending',
    reject_reason TEXT,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE
);

-- 分配权限白名单
CREATE TABLE IF NOT EXISTS assignment_permissions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    schematic_id TEXT NOT NULL,
    player_uuid TEXT NOT NULL,
    player_name TEXT NOT NULL,
    granted_by_uuid TEXT NOT NULL,
    FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE
);

-- 备货区配置
CREATE TABLE IF NOT EXISTS staging_areas (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    schematic_id TEXT NOT NULL,
    world TEXT NOT NULL,
    x1 INTEGER, y1 INTEGER, z1 INTEGER,
    x2 INTEGER, y2 INTEGER, z2 INTEGER,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE
);

-- 备货区内容物缓存
CREATE TABLE IF NOT EXISTS staging_area_inventory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    staging_area_id INTEGER NOT NULL,
    item_id TEXT NOT NULL,
    count INTEGER NOT NULL,
    FOREIGN KEY (staging_area_id) REFERENCES staging_areas(id) ON DELETE CASCADE
);
```

### 2. 网络协议设计

| 方向 | Packet | 用途 |
|------|--------|------|
| C→S | `ClaimMaterialC2S` | 认领材料 |
| S→C | `ClaimResultS2C` | 认领结果 |
| C→S | `AssignTaskC2S` | 分配任务 |
| S→C | `AssignmentNotificationS2C` | 任务分配通知 |
| C→S | `RespondAssignmentC2S` | 接受/拒绝任务 |
| S→C | `AssignmentResultS2C` | 分配结果 |
| C→S | `UpdatePermissionsC2S` | 更新权限 |
| S→C | `StagingAreaUpdateS2C` | 备货区更新 |
| C→S | `SetStagingAreaC2S` | 设置备货区 |
| S→C | `TaskListS2C` | 任务列表 |
| S→C | `NotificationS2C` | 通用通知 |

### 3. 需要新增/修改的文件清单

#### 服务端（不含 GUI）

| 文件 | 操作 | 说明 |
|------|------|------|
| `server/SchematicDatabase.java` | **重构** | 新增表结构、查询方法 |
| `server/DatabaseQueryService.java` | **重构** | 新增认领/分配/权限查询 |
| `server/TeamManager.java` | **新增** | 团队协作核心逻辑 |
| `server/StagingAreaManager.java` | **新增** | 备货区管理 |
| `server/ContainerChangeListener.java` | **新增** | 容器变化监听（Mixin） |
| `server/PermissionManager.java` | **新增** | 权限管理 |
| `network/` | **扩展** | 新增 Packet 类 |
| `api/MaterialEntry.java` | **重构** | 新增状态字段 |

#### 客户端

| 文件 | 操作 | 说明 |
|------|------|------|
| `client/gui/TeamMaterialListGui.java` | **新增** | 自定义材料清单 GUI |
| `client/gui/TaskListGui.java` | **新增** | 任务列表 GUI |
| `client/gui/AssignmentDialogGui.java` | **新增** | 分配任务对话框 |
| `client/gui/NotificationPopupGui.java` | **新增** | 通知弹窗 |
| `client/gui/PermissionSettingsGui.java` | **新增** | 权限设置 GUI |
| `client/gui/StagingAreaSelectorGui.java` | **新增** | 备货区框选 GUI |
| `client/hud/TaskProgressHud.java` | **新增** | 任务进度 HUD |
| `client/TeamClientState.java` | **新增** | 客户端状态管理 |

---

## 四、分阶段实施计划

### Phase 1: 数据库重构 + 认领机制

**目标**: 基础架构 + 最简单的协作功能

| 编号 | 任务 | 状态 |
|------|------|------|
| 1.1 | 重构 SchematicDatabase，新增表结构 | ☐ 待开始 |
| 1.2 | 新增 TeamManager 核心逻辑（认领、放弃、查询状态） | ☐ 待开始 |
| 1.3 | 实现认领网络协议（ClaimMaterialC2S / ClaimResultS2C） | ☐ 待开始 |
| 1.4 | 创建基础的 TeamMaterialListGui（显示材料列表 + 认领按钮） | ☐ 待开始 |
| 1.5 | 实现材料状态计算逻辑（未认领/正在收集） | ☐ 待开始 |
| 1.6 | 版本号更新为 1.21.7-0.2.0-alpha.1 | ☐ 待开始 |
| 1.7 | 本地构建测试 | ☐ 待开始 |
| 1.8 | 确认 Phase 1 完成 | ☐ 待开始 |

**预计工作量**: 3-4 天

---

### Phase 2: 分配机制 + 权限

**目标**: 完整的任务分配流程

| 编号 | 任务 | 状态 |
|------|------|------|
| 2.1 | 实现分配网络协议（AssignTaskC2S / AssignmentNotificationS2C） | ☐ 待开始 |
| 2.2 | 创建 NotificationPopupGui（接受/拒绝弹窗） | ☐ 待开始 |
| 2.3 | 实现 AssignmentDialogGui（分配任务界面） | ☐ 待开始 |
| 2.4 | 新增 PermissionManager 权限管理 | ☐ 待开始 |
| 2.5 | 实现 PermissionSettingsGui（权限设置界面） | ☐ 待开始 |
| 2.6 | 扩展 schematics 表，新增 allow_self_claim 字段 | ☐ 待开始 |
| 2.7 | 版本号更新为 1.21.7-0.2.0-alpha.2 | ☐ 待开始 |
| 2.8 | 本地构建测试 | ☐ 待开始 |
| 2.9 | 确认 Phase 2 完成 | ☐ 待开始 |

**预计工作量**: 4-5 天

---

### Phase 3: 备货区 + 实时同步

**目标**: 备货区监控和状态同步

| 编号 | 任务 | 状态 |
|------|------|------|
| 3.1 | 新增 staging_areas / staging_area_inventory 表 | ☐ 待开始 |
| 3.2 | 实现 StagingAreaManager 备货区管理 | ☐ 待开始 |
| 3.3 | 通过 Mixin 监听 Container.setChanged() 实现容器变化检测 | ☐ 待开始 |
| 3.4 | 实现 StagingAreaUpdateS2C 推送更新 | ☐ 待开始 |
| 3.5 | 实现 SetStagingAreaC2S 框选备货区 | ☐ 待开始 |
| 3.6 | 完善材料状态：已完成收集但未放入备货区 / 已完成备货 | ☐ 待开始 |
| 3.7 | 版本号更新为 1.21.7-0.2.0-alpha.3 | ☐ 待开始 |
| 3.8 | 本地构建测试 | ☐ 待开始 |
| 3.9 | 确认 Phase 3 完成 | ☐ 待开始 |

**预计工作量**: 4-5 天

---

### Phase 4: HUD + 任务管理

**目标**: 完整的用户体验

| 编号 | 任务 | 状态 |
|------|------|------|
| 4.1 | 实现 TaskProgressHud（参考 Litematica HUD） | ☐ 待开始 |
| 4.2 | 创建 TaskListGui（任务列表界面） | ☐ 待开始 |
| 4.3 | 实现离线通知队列（上线弹窗） | ☐ 待开始 |
| 4.4 | 实现任务放弃 / 撤回功能 | ☐ 待开始 |
| 4.5 | 完善所有 GUI 的交互逻辑 | ☐ 待开始 |
| 4.6 | 版本号更新为 1.21.7-0.2.0-alpha.4 | ☐ 待开始 |
| 4.7 | 本地构建测试 | ☐ 待开始 |
| 4.8 | 确认 Phase 4 完成 | ☐ 待开始 |

**预计工作量**: 3-4 天

---

## 五、确认完成机制

### 要求

1. **每个任务完成后必须确认**：完成一项任务后，必须在本文档中将状态从"☐ 待开始"改为"☑ 已完成"
2. **每个阶段完成后必须确认**：完成一个 Phase 后，必须在本文档中确认该 Phase 完成
3. **构建测试必须通过**：每个 Phase 完成后必须本地构建测试通过
4. **用户测试确认**：构建通过后，必须由用户测试确认功能正常
5. **提交不推送**：测试通过后提交代码，但不推送，等待用户最终确认

### 确认格式

每完成一项任务，在状态列更新：
```
| 1.1 | 重构 SchematicDatabase，新增表结构 | ☑ 已完成 (2025-01-01) |
```

每个 Phase 完成后，在 Phase 结束处添加：
```
**Phase 1 确认完成**: 2025-01-01，用户测试通过，已提交
```

---

## 六、风险点和注意事项

| 风险 | 说明 | 应对方案 |
|------|------|----------|
| **UI 重写工作量大** | 不再复用 Litematica UI，需要从零构建 | 参考 MaLiLib 的 Widget 体系，复用基础组件 |
| **容器监听性能** | Mixin 监听 `setChanged()` 可能影响性能 | 只监听备货区内的容器，其他忽略 |
| **并发问题** | 多玩家同时认领同一材料 | 数据库事务 + 唯一约束 |
| **状态同步延迟** | 客户端显示可能与服务端不同步 | 使用可靠的包传递，定期同步 |
| **反射依赖** | 当前代码通过反射修改 Litematica 字段 | UI 重写后移除反射依赖 |
| **数据库迁移** | 旧版本数据库没有新字段 | 测试阶段不考虑迁移，正式版再处理 |

---

## 七、版本规划

| 版本 | 内容 |
|------|------|
| 0.1.0-alpha.4 | 初始功能（材料统计 + 服务端查询） |
| 0.2.0-alpha.1 | 数据库重构 + 认领机制 |
| 0.2.0-alpha.2 | 分配机制 + 权限 |
| 0.2.0-alpha.3 | 备货区 + 实时同步 |
| 0.2.0-alpha.4 | HUD + 任务管理 |
| 0.2.0-beta.1 | 完整功能测试版 |
| 0.2.0 | 稳定正式版 |

---

## 八、关键设计决策记录

1. **认领与分配互斥**：一旦有人认领，负责人就不能再分配这个材料，反之亦然
2. **任务不设置超时**：玩家可以随时放弃，负责人可以随时撤回
3. **备货区实时监听**：通过 Mixin 监听容器变化，非轮询，避免性能问题
4. **任务完成不倒退**：任务一旦完成就不会倒退，备货区物品被拿走是新的需求
5. **UI 需要重写**：不再复用 Litematica 的 UI，需要自己写 GUI

---

## 九、参考资源

- Litematica 源码: `.关于本项目的依赖模组的源代码/litematica-LTS-1.21.8/`
- MaLiLib 源码: `.关于本项目的依赖模组的源代码/malilib-LTS-1.21.8/`
- Syncmatica 源码: `.关于本项目的依赖模组的源代码/syncmatica-LTS-1.21.8/`
- Fabric API 文档: https://fabricmc.net/wiki/
- MaLiLib Widget 体系: 参考 `WidgetListBase`, `WidgetBase` 等类
