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

一款 Minecraft Fabric 模组，用于激活 Syncmatica 菜单中的「材料清单」按钮，显示服务器共享原理图的材料需求。

> **注意：** 本项目为 Vibe Coding 产物，主要通过 AI 辅助编程开发。使用时可能出现意想不到的 bug，本人深知这点并将持续监督代码质量，也欢迎大家提出问题与 PR。

## 依赖

- Minecraft 1.21.7
- Fabric Loader 0.16.13+
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Litematica](https://modrinth.com/mod/litematica) 0.23.5+
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

输出：`build/libs/Syncmaterial-1-1.21.7-0.1.0-alpha.2.jar`

## 许可证

GNU 通用公共许可证 v3.0 - 详见 [LICENSE](LICENSE)

本项目包含来自 [Litematica](https://github.com/sakura-kyoko/litematica)（作者 masa）的代码，使用 [LGPL-3.0](https://www.gnu.org/licenses/lgpl-3.0.html) 许可证。以下文件源自 Litematica：

- `selection/Box.java` - 坐标数据类
- `selection/AreaSelection.java` - 区域选择数据
- `selection/SelectionMode.java` - 选择模式枚举
- `selection/CornerSelectionMode.java` - 角点模式枚举
- `client/gui/GuiStagingAreaEditorNormal.java` - 备货区编辑器（标准模式）
- `client/gui/GuiStagingAreaEditorSimple.java` - 备货区编辑器（简易模式）
- `client/gui/GuiStagingAreaEditorSubRegion.java` - 备货区编辑器（子区域）
- `client/gui/widgets/WidgetListStagingAreas.java` - 备货区列表 widget
- `client/gui/widgets/WidgetStagingAreaEntry.java` - 备货区条目 widget