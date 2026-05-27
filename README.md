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

A Minecraft Fabric mod that activates the "Material List" button in Syncmatica menu to display material requirements for server-shared schematics.

> **Note:** This project is a Vibe Coding product, developed primarily through AI-assisted programming. Unexpected bugs may occur during use. I am well aware of this and will continue to monitor code quality. Issues and PRs are welcome.

## Requirements

- Minecraft 1.21.7
- Fabric Loader 0.16.13+
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Litematica](https://modrinth.com/mod/litematica) 0.23.5+
- [Syncmatica](https://modrinth.com/mod/syncmatica) 0.3.15+

## Installation

This mod requires **BOTH server and client** to have the mod installed.

### Server
1. Download from [Releases](https://github.com/is-wuyi/Syncmaterial/releases)
2. Place `.jar` in server's `mods` folder
3. Restart server

### Client
1. Download the same `.jar` file
2. Place in Minecraft's `mods` folder (`.minecraft/mods/`)
3. Restart Minecraft

## Building

```bash
./gradlew build
```

Output: `build/libs/Syncmaterial-1-1.21.7-0.1.0-alpha.2.jar`

## License

GNU General Public License v3.0 - see [LICENSE](LICENSE)

This project includes code from [Litematica](https://github.com/sakura-kyoko/litematica) by masa, licensed under [LGPL-3.0](https://www.gnu.org/licenses/lgpl-3.0.html). The following files are derived from Litematica:

- `selection/Box.java` - Coordinate data class
- `selection/AreaSelection.java` - Area selection data
- `selection/SelectionMode.java` - Selection mode enum
- `selection/CornerSelectionMode.java` - Corner mode enum
- `client/gui/GuiStagingAreaEditorNormal.java` - Staging area editor (normal mode)
- `client/gui/GuiStagingAreaEditorSimple.java` - Staging area editor (simple mode)
- `client/gui/GuiStagingAreaEditorSubRegion.java` - Staging area editor (sub-region)
- `client/gui/widgets/WidgetListStagingAreas.java` - Staging area list widget
- `client/gui/widgets/WidgetStagingAreaEntry.java` - Staging area entry widget