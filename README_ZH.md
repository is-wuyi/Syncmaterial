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

输出：`build/libs/Syncmaterial-1-1.0.0.jar`

## 许可证

GNU 通用公共许可证 v3.0 - 详见 [LICENSE](LICENSE)