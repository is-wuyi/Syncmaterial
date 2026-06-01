# SyncMaterial 项目指南

## 项目概述

**项目名称**: SyncMaterial  
**目标环境**: Minecraft 1.21.7, Fabric Loader  
**核心功能**: 增强 Litematica 和 Syncmatica，提供服务器共享原理图的材料统计功能，以及团队协作收集材料。

## 技术栈

- **语言**: Java 21
- **模组 API**: Fabric API 0.129.0+1.21.7
- **数据库**: SQLite (嵌入式) via JDBC 3.45.1.0
- **构建工具**: Gradle 8.x + Fabric Loom 1.14.10

## 核心依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Minecraft | 1.21.7 | 游戏本体 |
| Fabric Loader | 0.16.13 | 模组加载器 |
| MaLiLib | 0.25.7 | GUI 组件库 |
| SQLite JDBC | 3.45.1.0 | 数据库驱动 |
| Litematica | 0.23.6 | 运行时必需（modCompileOnly） |

## 项目结构

```
src/main/java/net/syncmaterial/syncmaterial/
├── SyncMaterial.java                    # 模组入口类（onInitialize）
├── api/
│   └── MaterialEntry.java               # 材料条目数据结构
├── client/
│   ├── SyncMaterialClient.java           # 客户端入口（onInitializeClient）
│   ├── InventoryWatcher.java             # 背包监控（潜影盒扫描）
│   ├── gui/                              # 独立 UI（不依赖 Litematica 运行时）
│   │   ├── GuiMaterialList.java          # 材料清单 GUI 主类
│   │   ├── SyncMaterialList.java         # 数据适配（MaterialEntry → MaterialListEntry）
│   │   ├── MaterialListBase.java         # 材料列表基类
│   │   ├── MaterialListEntry.java        # 列表条目数据类
│   │   ├── MaterialListSorter.java       # 排序器
│   │   ├── MaterialListUtils.java        # 工具类（背包检测、数据转换）
│   │   ├── MaterialListHudRenderer.java  # HUD 渲染器
│   │   ├── StagingAreaSelector.java      # 准星选区模式（左键/右键/Enter/Esc）
│   │   ├── GuiStagingAreaEditorNormal.java   # 备货区编辑器（标准模式）
│   │   ├── GuiStagingAreaEditorSimple.java   # 备货区编辑器（简易模式）
│   │   ├── GuiStagingAreaEditorSubRegion.java # 备货区编辑器（子区域）
│   │   └── widgets/
│   │       ├── WidgetListMaterialList.java    # 材料列表 widget
│   │       ├── WidgetMaterialListEntry.java   # 材料条目 widget
│   │       ├── WidgetListStagingAreas.java    # 备货区列表 widget
│   │       └── WidgetStagingAreaEntry.java    # 备货区条目 widget
│   ├── render/
│   │   └── StagingAreaRenderer.java      # 备货区渲染器（含选区预览）
│   └── infohud/
│       ├── IInfoHudRenderer.java         # HUD 渲染接口
│       └── RenderPhase.java              # 渲染阶段枚举
├── server/
│   ├── SchematicDatabase.java            # SQLite 数据库（AutoCloseable）
│   ├── DatabaseQueryService.java         # 数据库查询服务
│   ├── SchematicFolderWatcher.java       # 原理图文件夹监控
│   ├── SchematicUploadListener.java      # 原理图上传监听
│   ├── StagingAreaManager.java           # 备货区管理器
│   ├── CollaborationManager.java         # 协作管理器
│   └── PlacementsUtil.java               # placements.json 工具
├── network/
│   ├── ModNetworkHandler.java            # 服务端网络处理（PayloadType 注册 + handler）
│   ├── ModNetworkHandlerClient.java      # 客户端网络处理（receiver 注册）
│   ├── ModPackets.java                   # 包 ID 常量
│   ├── MaterialStatsRequestC2SPacket.java
│   ├── MaterialStatsResponseS2CPacket.java
│   ├── JoinCollaborationC2SPacket.java
│   ├── CollaborationStatusS2CPacket.java
│   ├── LeaveCollaborationC2SPacket.java
│   ├── InventoryUpdateC2SPacket.java
│   ├── QueryMaterialStatusC2SPacket.java
│   ├── StagingAreaConfigC2SPacket.java
│   ├── StagingAreaConfigResponseS2CPacket.java
│   ├── RescanStagingAreaC2SPacket.java   # 刷新备货区请求
│   └── RescanStagingAreaResponseS2CPacket.java # 刷新备货区响应
│   ├── LeaveCollaborationC2SPacket.java
│   ├── InventoryUpdateC2SPacket.java
│   ├── QueryMaterialStatusC2SPacket.java
│   ├── StagingAreaConfigC2SPacket.java
│   └── StagingAreaConfigResponseS2CPacket.java
├── selection/                            # 备货区坐标数据类（复制自 Litematica）
│   ├── AreaSelection.java
│   ├── Box.java
│   ├── SelectionMode.java
│   └── CornerSelectionMode.java
├── engine/
│   ├── LitematicaParser.java             # Litematica 文件解析器
│   ├── AbstractLitematicaParser.java     # 解析器基类
│   └── impl/
│       ├── DefaultMaterialStatisticsEngine.java
│       ├── StatisticsProcessor.java
│       └── DefaultLitematicaParser.java
├── mixin/
│   ├── SyncmaticaIntegrationMixin.java       # 注册原理图上传监听器（通用 mixin）
│   ├── WidgetSyncmaticaServerPlacementEntryMixin.java
│   └── ButtonListenerMixin.java
└── config/
    └── ModConfig.java
```

## 数据流

### 1. 服务端原理图解析

1. `SchematicFolderWatcher` 监控 `syncmatica/placements.json` 文件变化
2. 检测到新的原理图时，调用 `LitematicaParser` 解析 `.litematic` 文件
3. 统计所有方块需求，存入 SQLite 数据库（`schematics` + `material_entries` 表）

### 2. 客户端请求材料清单

1. 玩家点击 Syncmatica 材料清单按钮
2. Mixin 拦截点击事件，发送 `MaterialStatsRequestC2SPacket`（含 schematicId）
3. 服务端 `ModNetworkHandler` 接收请求，从数据库查询统计结果

### 3. 服务端响应

1. 返回 `MaterialStatsResponseS2CPacket`（含 schematicName + List<MaterialEntry>）
2. schematicName 从 placements.json 的 display_name 字段获取

### 4. 客户端显示

1. 客户端接收响应，创建 `GuiMaterialList`（独立 UI，不依赖 Litematica 运行时）
2. `SyncMaterialList.setMaterialEntries()` 转换数据格式
3. 自动检测玩家背包，更新 countAvailable / countMissing
4. HUD 可通过按钮切换，作为全局叠加层持续显示

## 关键文件说明

### 核心数据类

**MaterialEntry.java** (api/) — 网络传输用
```
字段：stack, countTotal, countMissing, countMismatched, countAvailable
```

**MaterialListEntry.java** (gui/) — UI 显示用（复制自 Litematica）
```
字段：stack, countTotal, countMissing(可变), countMismatched, countAvailable(可变)
```

### 服务端核心

**SchematicDatabase.java** — SQLite 数据库操作
- 表: schematics, material_entries, staging_areas, staging_area_inventory, claims, assignments, assignment_permissions
- 实现 AutoCloseable，通过 SERVER_STOPPING 事件关闭

**SchematicFolderWatcher.java** — 原理图监控
- 监控 placements.json 变化
- 维护 placementNames Map（id → display_name）
- processPlacementsJson() 有 synchronized 保护

**ModNetworkHandler.java** — 网络处理
- **PayloadType 注册必须在 onInitialize() 中**（比 SERVER_STARTING 更早），否则客户端崩溃
- 接收客户端请求，查询数据库，返回响应
- 所有 C2S handler 都有输入验证（schematicId/materialId/count/player/action）

### 客户端核心

**GuiMaterialList.java** — 材料清单 GUI 主类
- 继承 MaLiLib 的 GuiListBase
- 按钮：刷新列表（触发服务端重新扫描）、HUD 开关、关闭
- 表头列：物品、总计、缺失、背包（点击排序）

**StagingAreaSelector.java** — 准星选区模式
- 点击"准星选区"按钮进入，关闭 GUI 进入选区模式
- 左键设置 pos1（红色），右键设置 pos2（蓝色），准星位置显示黄色
- Enter 确认（创建/更新备货区），Esc 取消
- HUD 显示操作提示和准星坐标
- 参考 Wurst 的 ExcavatorHack 实现

**GuiStagingAreaEditorNormal.java** — 备货区标准编辑器
- 复制自 Litematica 的 GuiAreaSelectionEditorNormal
- 支持多子区域管理，通过网络包与服务端同步
- 包含"准星选区"按钮（带悬浮提示）

**StagingAreaRenderer.java** — 备货区渲染器
- 实现 MaLiLib 的 IRenderer 接口
- 渲染备货区边框（绿色/黄色）
- 渲染 StagingAreaSelector 的选区预览

**MaterialListHudRenderer.java** — HUD 渲染器
- 每 2 秒自动刷新背包检测
- 通过 HudRenderCallback 全局叠加渲染

### Mixin

**SyncmaticaIntegrationMixin.java** — 通用 mixin（在 mixins 数组中，非 client）
- 注入 LitematicManager.setContext，注册 SchematicUploadListener
- 使用 SyncMaterial.getSharedDatabase() 获取共享实例

## 网络包架构

### PayloadType 注册时序（关键）

```
ModInitializer.onInitialize()
  └─ ModNetworkHandler.registerPayloadTypes()  ← 必须在这里
       注册所有 C2S 和 S2C 的 PayloadType
       ↓
SyncMaterialClient.onInitializeClient()
  └─ ModNetworkHandlerClient.register()  ← 注册客户端 receiver
       需要 PayloadType 已经存在
       ↓
ServerLifecycleEvents.SERVER_STARTING
  └─ ModNetworkHandler.register()  ← 注册服务端 handler
       需要 PayloadType 已经存在
```

### C2S 包（客户端发送，服务端接收）
| 包 | 字段 |
|---|---|
| MaterialStatsRequestC2SPacket | schematicId |
| JoinCollaborationC2SPacket | schematicId, materialId, inventoryCounts |
| LeaveCollaborationC2SPacket | schematicId, materialId |
| InventoryUpdateC2SPacket | schematicId, materialId, count |
| QueryMaterialStatusC2SPacket | schematicId |
| StagingAreaConfigC2SPacket | schematicId, action, areaId, areaData |

### S2C 包（服务端发送，客户端接收）
| 包 | 字段 |
|---|---|
| MaterialStatsResponseS2CPacket | schematicId, schematicName, materials |
| CollaborationStatusS2CPacket | schematicId, materialId, totalCount, stagingCount, participants |
| StagingAreaConfigResponseS2CPacket | success, message, areas |
| RescanStagingAreaResponseS2CPacket | success, message |

## 构建与运行

```bash
# 构建模组
./gradlew build

# 输出文件
# build/libs/Syncmaterial-1-<version>.jar
```

## 开发注意事项

### 1. 服务端不含 GUI 代码

服务端代码不应依赖 Litematica/MaLiLib，因为服务端需要能在没有这些 mod 的情况下启动。客户端 GUI 逻辑全部在 client 包下。

### 2. UI 独立化

从 0.2.0 开始，UI 代码直接从 Litematica 复制并改包名为己用，不再运行时依赖 Litematica 的 GUI 类。`build.gradle` 中 Litematica 声明为 `modCompileOnly`（编译时需要，运行时用户自行安装）。

### 3. countMissing 逻辑

`MaterialListUtils.updateAvailableCounts()` 会将 countMissing 更新为 `max(0, countTotal - countAvailable)`。
因此 HUD 和 GUI 直接使用 `countMissing` 即可，不需要再减 `countAvailable`。

### 4. 数据分离

- 服务端负责存储和查询材料统计数据
- 客户端负责 GUI 显示和数据转换
- 服务端不依赖任何客户端 mod

### 5. Mixin 规范

- 每个 Mixin 类只针对**一个目标类**
- 不能将多个目标类放在同一个 `@Mixin(targets = {...})` 中
- 否则所有注入会应用到每个目标类，导致运行时崩溃

### 6. 提交规范

- 提交记录使用**中文**
- 修改代码时自动更新 `build.gradle` 中的版本号
- `README.md` / `README_ZH.md` 中的版本号**不需要同步更新**（只是说明输出目录）
- 只提交代码和配置修改，AGENTS.md 不提交（skip-worktree）

### 7. 准星选区模式

StagingAreaSelector 实现了类似 Wurst Excavator 的选区逻辑：
- 进入选区模式后，关闭 GUI，玩家在游戏世界中操作
- 左键设置 pos1（红色方块），右键设置 pos2（蓝色方块）
- Enter 确认（创建/更新备货区），Esc 取消
- HUD 显示操作提示和准星坐标（带半透明黑色背景）
- 选区完成后自动同步到服务端

关键实现细节：
- 使用 `isPressed()` 而非 `wasPressed()` 检测鼠标点击（避免被游戏输入系统消耗）
- HUD 颜色使用 ARGB 格式（`0xFFFFFFFF` 为完全不透明白色）
- 通过 `ClientTickEvents.END_CLIENT_TICK` 注册 tick 事件
- 通过 `HudRenderCallback.EVENT` 注册 HUD 渲染事件

### 7. 发布规范

**必须按以下步骤操作，缺一不可：**

#### 7.1 编写发布说明
在 `build.gradle` 更新版本号后、打 tag 前，编辑 `.github/release-notes/latest.md` 文件，写入本次发布的更新说明。

格式示例：
```markdown
## SyncMaterial v1.21.7-0.2.0-alpha.1

### 新功能
- 功能 A：简要描述

### 修复
- 修复 B：简要描述

### 技术变更
- 版本号更新、依赖调整等
```

> 描述要通俗易懂，用中文，让玩家能看明白。

#### 7.2 发布流程
1. 更新 `build.gradle` 版本号 → 提交
2. 编写 `.github/release-notes/latest.md` → 提交
3. 打 tag：`git tag v<版本号>`（如 `v1.21.7-0.2.0-alpha.1`）
4. 推送 tag：`git push origin v<版本号>`
5. GitHub Actions 自动构建并发布
6. 发布完成后，删除 `.github/release-notes/latest.md` → 提交

## 参考资源

- Litematica 源码: `.关于本项目的依赖模组的源代码/litematica-LTS-1.21.8/`
- MaLiLib 源码: `.关于本项目的依赖模组的源代码/malilib-LTS-1.21.8/`
- Syncmatica 源码: `.关于本项目的依赖模组的源代码/syncmatica-LTS-1.21.8/`
- Wurst 源码: `.关于本项目的依赖模组的源代码/Wurst7-1.21.8/`（参考 ExcavatorHack 的选区逻辑）
