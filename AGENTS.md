# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 项目概述

**SyncMaterial** — Litematica/Syncmatica 的增强模组，为服务器共享原理图提供材料统计和团队协作收集功能。

- **平台**: Fabric（Minecraft 1.21.7、1.21.8、26.2，同一开发线）
- **Java**: 构建 JVM 25；共享模块及 1.21.x 编译目标 21，26.2 目标 25
- **构建**: Gradle 9.8.2 + Fabric Loom 1.19.4 + Stonecutter 0.10
- **版本依赖**: 见 `versions/<Minecraft>/gradle.properties`，不能用 26.2 构件替代 1.21.x 构件
- **共享依赖**: SQLite JDBC 3.45.1.0；业务模块位于 `shared/`，禁止 Minecraft/Fabric/可选模组依赖

多版本源码、detached 构建与测试矩阵见 [`docs/rewrite/multiversion.md`](docs/rewrite/multiversion.md)。旧 LTS 分支仅作历史参考，新功能和修复不再人工跨版本分支移植。

完整重构的进度、有效决策、下一批边界与验收记录统一维护在 [`docs/rewrite/progress.md`](docs/rewrite/progress.md)。开始新批次、确认重要决策、完成验收或提交／推送／CI 后同步更新；建议、待决策与已实现事实分开，不能把批次完成当成完整重构完成。

## 构建命令

```bash
JAVA_HOME=<Java25> ./gradlew buildAllVersions        # 三节点发行 jar
JAVA_HOME=<Java25> ./gradlew checkAllVersions        # 共享纯测 + 平台/服务端回归
JAVA_HOME=<Java25> ./gradlew testAllClients          # 三运行时客户端回归
JAVA_HOME=<Java25> ./gradlew testOptionalDependencies # 缺可选依赖的双后端启动验证
JAVA_HOME=<Java25> ./gradlew :26.2:runClient          # 单节点客户端调试
JAVA_HOME=<Java25> ./gradlew :1.21.8:runServer        # 单节点专服调试
```

产物位于 `versions/<Minecraft>/build/libs/SyncMaterial-<Minecraft>-<mod_version>.jar`。

## 测试

```bash
./gradlew :shared:test                              # 无 Minecraft 的纯业务测试
./gradlew :26.2:test :26.2:runGameTest                # 平台单测 + 旧后端 GameTest
./gradlew :26.2:runFoundationGameTest                 # 独立新后端 GameTest
./gradlew :26.2:runClientGameTest :26.2:runFoundationClientGameTest
```

- 纯业务测试在 `shared/src/test/java`，不得加载 Minecraft/Fabric；平台测试在根 `src/test/java`，各节点生成后执行。需要注册表的测试用该节点 `Bootstrap.bootStrap()` 与版本化测试垫片。
- GameTest 在 `src/gametest/java`。旧后端注册到 `src/gametest/resources/fabric.mod.json`，Foundation 注册到 `src/foundationtest/resources/fabric.mod.json`，缺依赖测试注册到 `src/optionaltest/resources/fabric.mod.json`；新类未注册不得当作已运行。
- 数据库测试通过生产仓储创建 schema；迁移夹具使用明确的历史版本构建方式，不能手写影子 DDL 随意替代当前 schema。
- 本机外置／网络文件系统不直接运行 Gradle；复制受控源码到本地持久目录的独立工程，测试目录在副本 `.runs/<Minecraft>/`，不能跨工作区共用。未提交源码不得只放 `/tmp`；临时产物可重建。
- CI 每个节点显式跑平台单测、旧后端／Foundation 服务端与客户端回归。共享测试仅统计一次；构建成功不能当客户端已测。

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
  ├─ ModNetworkHandler.registerPayloadTypes()   ← 必须最先注册，否则客户端崩溃
  └─ ModNetworkHandler.register()               ← 服务端 receiver 全进程只注册一次

SyncMaterialClient.onInitializeClient()
  └─ ModNetworkHandlerClient.register()          ← 注册客户端 receiver

ServerLifecycleEvents.SERVER_STARTING
  └─ 安装该 MinecraftServer 的 legacy context / Foundation runtime
```

PayloadType 注册必须在 `onInitialize()` 中完成，不能延迟到 `SERVER_STARTING`。服务端 receiver 不捕获可替换的全局 service；按请求的服务器捕获原上下文，排队后复核上下文及连接身份。

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
| TeamCommandC2SPacket | 项目／请求关联的团队管理动作（协议 4，含 UUID 目标） |

| S2C 包 | 用途 |
|---|---|
| HelloS2CPacket | 版本握手回应，含服务端协议版本与是否接受该客户端（**格式永久冻结**） |
| MaterialStatsResponseS2CPacket | 材料清单响应 |
| MaterialStatusS2CPacket | 材料状态（认领数 + 认领者） |
| MaterialCatalogUpdateS2CPacket | Foundation 材料目录原地更新（协议 2） |
| AreaInventorySnapshotS2CPacket | Foundation 权威去重库存与新鲜度（协议 3，绑定目录修订与库存序号） |
| CollaborationStatusS2CPacket | 协作状态（含参与者列表） |
| StagingAreaConfigResponseS2CPacket | 备货区配置响应（含完整区域列表） |
| RescanStagingAreaResponseS2CPacket | 刷新结果（success + message） |
| OwnerActionResponseS2CPacket | 负责人操作结果 |
| BatchAssignResponseS2CPacket | 批量分配结果 |
| KickFromMaterialResponseS2CPacket | 踢出结果 |
| PlayerListResponseS2CPacket | 玩家列表（名称 + 在线状态） |
| TeamResponseS2CPacket | 关联管理结果／玩家目录及项目团队状态推送（协议 4） |
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
- Syncmatica 发现监听必须绑定真实服务端上下文并保留注销句柄；不能通过客户端 `LitematicManager.setActiveContext` 选择服务端数据库。

### 关键实现细节

- 收集缺口 = `max(0, countTotal - stagingCount - warehouseCount - allPlayersCount)`；搬运口径见 `ProgressFormulas.pickupMissing`，HUD/GUI 使用同一公式
- Foundation 区域库存同项目按物理来源去重，备货区优先；重叠提示与未知／过期警告分开
- Foundation 扫描只读已加载区块的现有方块实体；不初始化待加载实体，不展开 loot；卸载不等于拆箱
- 区域扫描批次架构、协议与验收见 `docs/rewrite/area-scanning.md`
- `StagingAreaSelector` 使用 `isPressed()` 而非 `wasPressed()` 检测鼠标点击（避免被游戏输入系统消耗）
- HUD 颜色使用 ARGB 格式（`0xFFFFFFFF` 完全不透明白色）
- `ModNetworkHandler` 所有 C2S handler 都有输入验证
- `SchematicFolderWatcher.scanOnce()` 用扫描准入防止并发重入；解析完成经 owner 的串行摄取事务提交，关闭／新意图后拒绝旧结果。
- legacy 资源、订阅与时钟任务由 `LegacyServerContext` 拥有；STOPPING 先封闭，STOPPED 有界回收并最后关库，详见 `docs/rewrite/legacy-lifecycle.md`。
- Foundation 团队权限与材料写入同 writer 事务；分配不生成背包量，踢人清持久／在线观测；身份与目标解析不依赖截断的可见目录。详见 `docs/rewrite/team-management.md`。
- 管理回包按项目／请求／连接代次／原界面匹配；`requestId=0` 的团队状态推送不重开 GUI、不重置材料目录／区域快照。旧匿名响应不得跨订阅扇出。

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

下列 Phase 记录为既有功能开发历史，不表示 Foundation 已完成相同能力的迁移；完整重构当前状态以 [`docs/rewrite/progress.md`](docs/rewrite/progress.md) 为准。

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
- 修改代码时更新根 `gradle.properties` 的唯一 `mod_version`，节点版本由构建派生
- `README.md` 中不维护手写 mod 版本号
- 发行版本保持 `<Minecraft>-<mod_version>`，不能在各节点分别硬编码 mod 版本

## 工作流约定

**每次改完代码，验证通过后自行提交并推送，无需等待确认。**

顺序固定为：

1. 跑 `./gradlew buildAllVersions checkAllVersions testAllClients testOptionalDependencies` —— 三节点单测、GameTest、客户端和缺依赖启动全部通过；静态告警按共享／平台基线核对，不增加未经批准的新告警，不能将三节点数量直接相加与单版本 28 条比较
2. 扫调试残留（`System.out` / `printStackTrace` / `TODO` / `FIXME`）
3. 核对根 `gradle.properties` 的 `mod_version` 与各节点产物版本
4. 中文提交信息，说明改了什么、为什么这么改
5. `git push` 到当前分支，并确认 CI 结果

验证不通过就不要提交，先修好。发布（打 tag）仍需明确指示，不在此约定内。

## 发布流程

1. 更新根 `gradle.properties` 版本号 → 完成三节点验证 → 提交
2. 编写 `.github/release-notes/latest.md`（中文，面向玩家） → 提交
3. 打 tag：`git tag v<版本号>`（如 `v1.21.7-0.2.0-alpha.1`）
4. 推送 tag：`git push origin v<版本号>` → GitHub Actions 自动构建发布
5. 发布完成后删除 `.github/release-notes/latest.md` → 提交

## 参考资源

- Litematica 源码: `.关于本项目的依赖模组的源代码/litematica-LTS-1.21.8/`
- MaLiLib 源码: `.关于本项目的依赖模组的源代码/malilib-LTS-1.21.8/`
- Syncmatica 源码: `.关于本项目的依赖模组的源代码/syncmatica-LTS-1.21.8/`
- Wurst 源码: `.关于本项目的依赖模组的源代码/Wurst7-1.21.8/`（ExcavatorHack 选区逻辑参考）
