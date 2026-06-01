# SyncMaterial — Minecraft Fabric Mod

Litematica/Syncmatica 的增强模组，提供材料列表统计功能。

## 项目信息

- **平台**: Fabric (Minecraft 1.21.7)
- **Java**: 21
- **构建系统**: Gradle + Fabric Loom
- **Mod ID**: `syncmaterial`
- **License**: GPL-3.0-only

## 常用命令

```bash
./gradlew build          # 构建 mod jar
./gradlew runClient      # 启动 Minecraft 客户端测试
./gradlew runServer      # 启动专用服务器测试
./gradlew runData        # 运行数据生成
./gradlew clean          # 清理构建缓存
```

## MCP 服务器

本项目配置了以下 MCP 服务器（需重启会话后生效）：

| 名称 | 用途 |
|------|------|
| `minecraft-dev` | Minecraft 源码反编译、Mixin 验证、版本对比 |
| `mcmodding` | Fabric/NeoForge 开发文档查询 |
| `github` | GitHub API 访问 |

## Skills

项目内置 4 个 Skill（`.claude/skills/`），在相关任务时自动加载：

| Skill | 触发场景 |
|-------|---------|
| `minecraft-fabric-dev` | Fabric mod 开发、Mixin 编写、源码分析、版本迁移 |
| `minecraft-modding` | NeoForge/Fabric mod 开发、方块/物品/实体注册、数据生成 |
| `github` | 使用 `gh` CLI 操作 GitHub |
| `github-workflow` | PR 审查、Issue 处理、分支管理的完整工作流 |

## 关键约定

- **始终使用 yarn mappings**（不是 mojmap）进行 Fabric 开发
- Mixin 编写后必须用 `analyze_mixin` 工具验证
- 每个注册的方块/物品需要配套的 JSON 资源文件（blockstate、model、loot_table、lang）
- 版本格式: `{mod_version}+{mc_version}`（如 `2.0.0+1.21.7`）
