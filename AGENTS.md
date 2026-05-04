# SyncMaterial 项目指南

## 项目概述

**项目名称**: SyncMaterial  
**目标环境**: Minecraft 1.21.7, Fabric Loader  
**核心功能**: 增强 Litematica 和 Syncmatica，提供服务器共享原理图的材料统计功能。玩家可以从服务端获取任意共享原理图的材料清单，并在客户端查看需要的材料数量。

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
| Litematica | 0.23.6 | 材料清单 UI |
| MaLiLib | 0.25.7 | GUI 组件库 |
| SQLite JDBC | 3.45.1.0 | 数据库驱动 |

## 项目结构

```
src/main/java/net/syncmaterial/syncmaterial/
├── SyncMaterial.java                    # 模组入口类
├── api/
│   ├── MaterialEntry.java               # 材料条目数据结构
│   └── MaterialStatisticsEngine.java    # 材料统计引擎接口
├── client/
│   ├── SyncMaterialClient.java           # 客户端入口
│   └── gui/
│       └── LitematicaMaterialListAdapter.java  # Litematica UI 适配器
├── server/
│   ├── SchematicDatabase.java            # SQLite 数据库
│   ├── DatabaseQueryService.java         # 数据库查询服务
│   ├── SchematicFolderWatcher.java       # 原理图文件夹监控
│   ├── SchematicUploadListener.java      # 原理图上传监听
│   ├── PlacementsUtil.java               # placements.json 工具
│   └── ...
├── network/
│   ├── ModNetworkHandler.java            # 服务端网络处理
│   ├── ModNetworkHandlerClient.java      # 客户端网络处理
│   ├── MaterialStatsRequestC2SPacket.java   # 请求包
│   ├── MaterialStatsResponseS2CPacket.java  # 响应包
│   └── ...
├── engine/
│   ├── DefaultMaterialStatisticsEngine.java  # 统计引擎实现
│   ├── LitematicaParser.java             # Litematica 文件解析器
│   ├── AbstractLitematicaParser.java     # 解析器基类
│   └── impl/
│       ├── StatisticsProcessor.java      # 统计处理器
│       ├── DefaultLitematicaParser.java  # 默认解析器
│       └── ...
├── mixin/
│   ├── WidgetSyncmaticaServerPlacementEntryMixin.java  # 按钮注入
│   └── SyncmaticaIntegrationMixin.java
└── config/
    └── ModConfig.java
```

## 数据流

### 1. 服务端原理图解析

1. `SchematicFolderWatcher` 监控 `syncmatica/placements.json` 文件变化
2. 检测到新的原理图时，调用 `LitematicaParser` 解析 `.litematic` 文件
3. 统计所有方块需求，存入 SQLite 数据库

### 2. 客户端请求材料清单

1. 玩家点击 Litematica/Syncmatica 的材料清单按钮
2. Mixin 拦截点击事件，发送 `MaterialStatsRequestC2SPacket`（含 schematicId）
3. 服务端 `ModNetworkHandler` 接收请求，从数据库查询统计结果

### 3. 服务端响应

1. 服务端返回 `MaterialStatsResponseS2CPacket`（含 schematicName + List<MaterialEntry>）
2. schematicName 从 placements.json 的 display_name 字段获取

### 4. 客户端显示

1. 客户端接收响应，调用 `LitematicaMaterialListAdapter` 转换为 Litematica 格式
2. 创建 `MaterialListBase` 子类，复用 Litematica 的 `GuiMaterialList` UI
3. 自动检测玩家背包，更新 countAvailable
4. GUI 显示：总计、缺失、已有数量

## 关键文件说明

### 核心数据类

**MaterialEntry.java** - 材料条目
```java
// 字段：stack, countTotal, countMissing, countMismatched, countAvailable
// 用于网络传输和服务端存储
```

### 服务端核心

**SchematicDatabase.java** - SQLite 数据库操作
- 创建表: schematics, material_entries
- 提供增删改查接口

**SchematicFolderWatcher.java** - 原理图监控
- 监控 placements.json 变化
- 维护 placementNames Map（id → display_name）
- 触发原理图解析和数据库存储

**DatabaseQueryService.java** - 数据库查询
- getMaterials(schematicId): 查询指定原理图的材料列表

**ModNetworkHandler.java** - 网络处理
- 接收客户端请求，查询数据库，返回响应

### 客户端核心

**LitematicaMaterialListAdapter.java** - UI 适配器
- 将 MaterialEntry 转换为 MaterialListEntry
- 调用 updateAvailableCounts() 检测背包
- 创建 SyncMaterialMaterialList 继承 MaterialListBase

**ModNetworkHandlerClient.java** - 客户端网络
- 接收服务端响应，调用 GUI 显示

### Mixin

**WidgetSyncmaticaServerPlacementEntryMixin.java**
- 拦截 Syncmatica 的材料清单按钮点击
- 发送网络请求

## 构建与运行

```bash
# 构建模组
./gradlew build

# 输出文件
# build/libs/Syncmaterial-1-1.0.0.jar
```

## 开发注意事项

### 1. 服务端不含 GUI 代码

服务端代码不应依赖 Litematica/MaLiLib，因为服务端需要能在没有这些 mod 的情况下启动。客户端 GUI 逻辑全部在 client 包下。

### 2. countMissing 逻辑

Litematica 的 countMissing 设计：
- 初始 = 总需求 - 已放置方块（从世界检测）
- HUD 显示 = countMissing - countAvailable

由于服务端没有世界数据，countMissing = countTotal，HUD 会多减一次 countAvailable。这是 Litematica 的设计，不是 bug。

### 3. Litematica 复用

直接复用 Litematica 的 GUI：
- 使用 modImplementation 依赖 litematica
- 创建 MaterialListBase 子类
- 让 Litematica 处理背包检测、排序、过滤等全部 UI 逻辑

### 4. 数据分离

- 服务端负责存储和查询材料统计数据
- 客户端负责 GUI 显示和数据转换
- 服务端不依赖任何客户端 mod

## 参考资源

- Litematica 源码: `.关于本项目的依赖模组的源代码/litematica-LTS-1.21.8/`
- MaLiLib 源码: `.关于本项目的依赖模组的源代码/malilib-LTS-1.21.8/`
- Syncmatica 源码: `.关于本项目的依赖模组的源代码/syncmatica-LTS-1.21.8/`