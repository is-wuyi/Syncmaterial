# SyncMaterial

<p align="center">
  <a href="https://github.com/is-wuyi/Syncmaterial/stargazers">
    <img src="https://img.shields.io/github/stars/is-wuyi/Syncmaterial?style=for-the-badge&logo=github" alt="Stars">
  </a>
  <a href="https://github.com/is-wuyi/Syncmaterial/releases">
    <img src="https://img.shields.io/github/v/release/is-wuyi/Syncmaterial?include_prereleases&style=for-the-badge" alt="Version">
  </a>
  <a href="https://github.com/is-wuyi/Syncmaterial/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/is-wuyi/Syncmaterial?style=for-the-badge" alt="License">
  </a>
</p>

一款 Minecraft Fabric 模组，在 Syncmatica 原理图共享的基础上，提供材料统计和团队协作收集功能。

> **注意：** 本项目为 Vibe Coding 产物，主要通过 AI 辅助编程开发。使用时可能出现意想不到的 bug，本人深知这点并将持续监督代码质量，也欢迎大家提出问题与 PR。

## 功能特性

- **材料清单** — 显示服务器共享原理图的材料需求
- **协作认领** — 玩家认领材料，背包自动同步更新收集进度
- **备货区管理** — 定义备货区/仓库，自动扫描容器库存
- **进度追踪** — 每种材料的进度条，显示备货区 + 玩家贡献
- **负责人系统** — 主负责人 + 副负责人，批量分配、踢出管理
- **准星选区** — 指向即选的区域选择模式（灵感来自 Wurst 的 ExcavatorHack）
- **游戏内渲染** — 备货区线框、名称标注、选区高亮在游戏中实时显示
- **可配置 HUD** — 位置、缩放、颜色、快捷键均可自定义
- **渲染参数可调** — 线框颜色、侧面颜色、文字标签开关等全部可配置
- **多语言支持** — 8 种语言（中/英/日/德/西/法/韩/俄）

## 依赖

- Minecraft 1.21.7 ~ 1.21.8
- Fabric Loader 0.16.13+
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Litematica](https://modrinth.com/mod/litematica) 0.23.6+
- [Syncmatica](https://modrinth.com/mod/syncmatica) 0.3.15+

## 安装

本模组**服务端和客户端都需要安装**。

### 服务端
1. 从 [Releases](https://github.com/is-wuyi/Syncmaterial/releases) 下载
2. 放入服务器 `mods` 文件夹
3. 重启服务器

### 客户端
1. 下载同样的 `.jar` 文件
2. 放入 Minecraft 的 `mods` 文件夹（`.minecraft/mods/`）
3. 重启 Minecraft

## 构建

```bash
./gradlew build
```

输出：`build/libs/Syncmaterial-1-<version>.jar`

## 许可证

GNU 通用公共许可证 v3.0 - 详见 [LICENSE](LICENSE)

本项目包含来自 [Litematica](https://github.com/sakura-kyoko/litematica)（作者 masa）的代码，使用 [LGPL-3.0](https://www.gnu.org/licenses/lgpl-3.0.html) 许可证。以下文件源自 Litematica：

- `selection/Box.java` - 坐标数据类
- `selection/AreaSelection.java` - 区域选择数据
- `selection/SelectionMode.java` - 选择模式枚举
- `selection/CornerSelectionMode.java` - 角点模式枚举
- `client/gui/GuiStagingAreaEditorNormal.java` - 备货区编辑器（标准模式）
- `client/gui/GuiStagingAreaEditorSubRegion.java` - 备货区编辑器（子区域）
- `client/gui/widgets/WidgetListStagingAreas.java` - 备货区列表 widget
- `client/gui/widgets/WidgetStagingAreaEntry.java` - 备货区条目 widget
