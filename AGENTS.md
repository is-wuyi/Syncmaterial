# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 项目概述

**SyncMaterial** — Litematica/Syncmatica 的增强模组，为服务器共享原理图提供材料统计和团队协作收集功能。

- **平台**: Fabric (Minecraft 1.21.7 ~ 1.21.8)
- **Java**: 21
- **构建**: Gradle 8.x + Fabric Loom 1.14.10
- **核心依赖**: Fabric API 0.129.0+1.21.7, MaLiLib 0.25.7, SQLite JDBC 3.45.1.0
- **可选依赖**: Litematica 0.23.6 (`modCompileOnly`), Syncmatica 0.3.15 (`modCompileOnly`)

## 构建命令

```bash
./gradlew build           # 构建 jar → build/libs/Syncmaterial-1-<version>.jar
./gradlew runClient       # 启动客户端测试
./gradlew runServer       # 启动专用服务器
./gradlew runData         # 数据生成
./gradlew clean           # 清理构建缓存
```

## 测试

```bash
./gradlew test            # 跑全部测试：单元测试 + GameTest（test 任务依赖 runGameTest）+ JaCoCo 报告
```

- **单元测试**在 `src/test/java`：纯逻辑测试不依赖 MC 运行时；需要注册表的测试用 `Bootstrap.initialize()`（参考 StatisticsProcessorTest）；网络 handler 测试用 Mockito `mockStatic`（参考 Phase4HandlerTest）
- **GameTest** 在 `src/gametest/java`：真实服务器环境集成测试。**新类必须注册到 `src/gametest/resources/fabric.mod.json` 的 `fabric-gametest` entrypoint，否则不会运行**
- 数据库测试直接实例化 `SchematicDatabase.initialize(临时路径)`，**不要**在测试里手工复制 DDL（影子 schema 漂移时测试会假绿）
- GameTest 运行目录配置在 `/tmp/syncmaterial-gametest/run`（本仓库在外置 exFAT 磁盘上，Gradle 文件锁不可用，本机跑测试需先 `rsync` 到 /tmp 再执行）
- CI（`.github/workflows/ci.yml`）通过 `./gradlew build` 跑全部测试，并在 Job Summary 输出测试数与覆盖率

## 架构要点

### 客户端/服务端分离

服务端代码**不能依赖** Litematica/MaLiLib（服务端需在没有这些 mod 的情况下启动）。UI 代码从 Litematica 复制并改包名为己用，声明为 `modCompileOnly`。

### 数据流

```
SchematicFolderWatcher 监控 placements.json
  → LitematicaParser 解析 .litematic 文件
    → 存入 SQLite (schematics + material_entries 表)
      → 客户端发 MaterialStatsRequestC2SPacket
        → 服务端查库返回 MaterialStatsResponseS2CPacket
          → GuiMaterialList 独立 UI 显示
```

### 网络包注册时序（关键）

```
ModInitializer.onInitialize()
  └─ ModNetworkHandler.registerPayloadTypes()   ← 必须最先注册，否则客户端崩溃

SyncMaterialClient.onInitializeClient()
  └─ ModNetworkHandlerClient.register()          ← 注册客户端 receiver

ServerLifecycleEvents.SERVER_STARTING
  └─ ModNetworkHandler.register()                ← 注册服务端 handler
```

PayloadType 注册必须在 `onInitialize()` 中完成，不能延迟到 `SERVER_STARTING`。

### 网络包清单

| C2S 包 | 用途 |
|---|---|
| HelloC2SPacket | 版本握手，上报客户端协议版本（**格式永久冻结**） |
| MaterialStatsRequestC2SPacket | 请求材料清单 |
| MaterialListCloseC2SPacket | 材料列表关闭通知 |
| JoinCollaborationC2SPacket | 加入协作（schematicId, materialId, inventoryCounts） |
| LeaveCollaborationC2SPacket | 退出协作 |
| InventoryUpdateC2SPacket | 背包更新上报 |
| QueryMaterialStatusC2SPacket | 查询材料状态 |
| StagingAreaConfigC2SPacket | 备货区配置（CRUD + 子区域管理） |
| RescanStagingAreaC2SPacket | 手动刷新请求 |
| OwnerActionC2SPacket | 负责人操作（转让/添加副负责人/移除副负责人/自行认领开关） |
| BatchAssignC2SPacket | 批量分配材料给玩家 |
| KickFromMaterialC2SPacket | 按材料踢出玩家 |
| PlayerListRequestC2SPacket | 请求玩家列表 |

| S2C 包 | 用途 |
|---|---|
| HelloS2CPacket | 版本握手回应，含服务端协议版本与是否接受该客户端（**格式永久冻结**） |
| MaterialStatsResponseS2CPacket | 材料清单响应 |
| MaterialStatusS2CPacket | 材料状态（认领数 + 认领者） |
| CollaborationStatusS2CPacket | 协作状态（含参与者列表） |
| StagingAreaConfigResponseS2CPacket | 备货区配置响应（含完整区域列表） |
| RescanStagingAreaResponseS2CPacket | 刷新结果（success + message） |
| OwnerActionResponseS2CPacket | 负责人操作结果 |
| BatchAssignResponseS2CPacket | 批量分配结果 |
| KickFromMaterialResponseS2CPacket | 踢出结果 |
| PlayerListResponseS2CPacket | 玩家列表（名称 + 在线状态） |
| WarehouseAreaResponseS2CPacket | 仓库区域线框（全局广播，含被引用仓库 ID） |

### 协议版本兼容（必读）

客户端与服务端版本可能不一致（服务器不可能要求所有玩家同时更新），因此有以下约束。

**进服流程**：客户端 JOIN 时发 `HelloC2SPacket` 上报协议版本 → 服务端记录并回 `HelloS2CPacket` → 握手通过后才推送备货区与仓库的初始数据。未握手的玩家视为没装本 mod，服务端不给它发任何包。

**四条硬规矩**：

1. **`HelloC2SPacket` / `HelloS2CPacket` 的字段结构永久冻结。** 它们是全部版本协商的地基，自己变了就无法得知对端是谁。要传新信息一律新开包。
2. **已发布的包，字段顺序和类型不要改。** 字节流里没有标签，接收方完全靠顺序去数，插一个字段就会让旧版本从那里开始全部错位 —— 表现为客户端被踢下线且只显示 `Internal Exception`，玩家无法自诊断。要改语义就新开包，老包留着。
3. **协议版本号独立于 mod 版本号。** 只有线格式或语义真变了才 bump `ProtocolVersion.CURRENT`。纯 UI 调整、渲染修复不要动它，无谓 bump 会让旧客户端凭空丢功能。
4. **新增能力必须带版本判断。** 服务端侧 `ProtocolHandshake.supports(player, N)`，客户端侧 `ClientProtocolState.serverSupports(N)`。客户端发现服务端不支持时应置灰按钮并提示，而非静默失败。

**优先靠字段扩展而非新开包。** `StagingAreaConfigC2SPacket` 用一个 `action` 字符串支撑了 13 种操作，加第 14 种不需要改包结构 —— 这是控制包数量膨胀的主要手段。但注意：加 `action` 新取值属于**语义变更**，旧服务端的白名单会静默丢弃它，因此仍需 bump 协议版本并加判断。

**服务端比客户端宽容。** 服务端只在旧客户端会真正写坏数据时才抬高 `ProtocolVersion.MIN_COMPATIBLE` 硬性拒绝（应极力避免）；客户端发现服务端旧了则自行减少功能，不阻断游戏。

### Mixin 规范

- 每个 Mixin 类只针对**一个目标类**，不能将多个目标放在同一个 `@Mixin(targets = {...})` 中
- `SyncmaticaIntegrationMixin` 在 `mixins` 数组中（非 client），注入 LitematicManager.setContext

### 关键实现细节

- `countMissing` = `max(0, countTotal - stagingCount - allPlayersCount)`，HUD/GUI 直接使用
- `StagingAreaSelector` 使用 `isPressed()` 而非 `wasPressed()` 检测鼠标点击（避免被游戏输入系统消耗）
- HUD 颜色使用 ARGB 格式（`0xFFFFFFFF` 完全不透明白色）
- `ModNetworkHandler` 所有 C2S handler 都有输入验证
- `SchematicFolderWatcher.processPlacementsJson()` 有 synchronized 保护

### 数据库表

SQLite（`SchematicDatabase.java`，AutoCloseable）：
`schematics`, `material_entries`, `staging_areas`, `staging_area_inventory`, `claims`, `player_inventories`, `deputy_owners`

## 核心模块

- **StagingAreaSelector** — 准星选区模式：进入后关闭 GUI，左键设 pos1（红），右键设 pos2（蓝），Enter 确认，Esc 取消，HUD 显示操作提示和准星坐标
- **StagingAreaRenderer** — 备货区游戏内渲染（绿色/黄色线框 + 选区预览 + 名称标注），实现 MaLiLib IRenderer 接口
- **GuiMaterialList** — 材料清单 GUI（7 列布局 + 进度条 + 悬停详情 + 搜索 + 管理弹窗），含刷新、显示/隐藏线框、HUD 开关
- **GuiStagingAreaEditorNormal** — 备货区编辑器，含准星选区按钮（带悬浮提示说明两种模式）
- **GuiStagingAreaEditorSubRegion** — 子区域编辑器（坐标编辑 + 准星选角点）
- **InventoryWatcher** — 背包监控，含潜影盒扫描，通过白名单过滤只上报相关材料
- **CollaborationManager** — 服务端协作管理，每材料独立协作组，参与者背包计入进度
- **StagingAreaManager** — 服务端备货区管理，脏容器延迟扫描 + 容器库存统计
- **Configs** — 配置系统（通用/HUD/渲染三标签页），MaLiLib ConfigDouble/ConfigColor 等

## 当前开发阶段

### 已完成

- Phase 1：协作认领基础（Join/Leave/InventoryUpdate/QueryStatus 协议 + 背包监听 + HUD 渲染）
- Phase 2：备货区集成（编辑器 + 服务端管理 + 脏标记延迟扫描 + 游戏内渲染）
- Phase 3：进度条 + 悬停提示 + 准星选区 + 刷新列表
- Phase 4：负责人管理与批量分配（主/副负责人体系 + 批量分配 + 踢出 + 玩家列表 + 管理界面 + 材料过滤）

### 待开发（Phase 5）

仓库管理与搬运模式：
- 备货区子区域增加 `type` 字段（`staging_area` / `warehouse`）
- 收集模式（仓库 + 备货区 + 背包）和搬运模式（仅备货区 + 背包）
- ~~仓库标注渲染（按需扫描，线框 + 文字）~~ 已完成（beta.17）

## 代码规范

- **提交信息使用中文**
- 修改代码时同步更新 `build.gradle` 中的版本号
- `README.md` 中的版本号不需要同步更新
- 版本格式: `{mod_version}+{mc_version}`（如 `2.0.0+1.21.7`）

## 工作流约定

**每次改完代码，验证通过后自行提交并推送，无需等待确认。**

顺序固定为：

1. 跑 `./gradlew build spotbugsMain` —— 单测、GameTest 必须全绿，SpotBugs 条数不得超过基线（当前 28 条）
2. 扫调试残留（`System.out` / `printStackTrace` / `TODO` / `FIXME`）
3. 同步 `build.gradle` 版本号
4. 中文提交信息，说明改了什么、为什么这么改
5. `git push` 到当前分支，并确认 CI 结果

验证不通过就不要提交，先修好。发布（打 tag）仍需明确指示，不在此约定内。

## 发布流程

1. 更新 `build.gradle` 版本号 → 提交
2. 编写 `.github/release-notes/latest.md`（中文，面向玩家） → 提交
3. 打 tag：`git tag v<版本号>`（如 `v1.21.7-0.2.0-alpha.1`）
4. 推送 tag：`git push origin v<版本号>` → GitHub Actions 自动构建发布
5. 发布完成后删除 `.github/release-notes/latest.md` → 提交

## 参考资源

- Litematica 源码: `.关于本项目的依赖模组的源代码/litematica-LTS-1.21.8/`
- MaLiLib 源码: `.关于本项目的依赖模组的源代码/malilib-LTS-1.21.8/`
- Syncmatica 源码: `.关于本项目的依赖模组的源代码/syncmatica-LTS-1.21.8/`
- Wurst 源码: `.关于本项目的依赖模组的源代码/Wurst7-1.21.8/`（ExcavatorHack 选区逻辑参考）
