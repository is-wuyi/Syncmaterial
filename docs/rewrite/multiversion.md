# 多版本开发与验收

SyncMaterial 在同一开发线维护 Minecraft `1.21.7`、`1.21.8` 和 `26.2`。业务逻辑不在版本分支间复制；两个 1.21.x 节点共用适配实现，各节点分别编译和验证。

## 工程边界

- `shared/`：独立 Java 21 业务模块。包含模型、SQLite 仓储、导入、运行时、库存与目录跟踪及纯算法。不依赖 Minecraft、Fabric、MaLiLib 或 Litematica。
- 根 `src/`：Minecraft/Fabric/可选模组适配、网络 codec、客户端外壳和 Mixin。Stonecutter 从一份源码生成各节点源码。
- `versions/<Minecraft>/gradle.properties`：每节点的 Minecraft、API、可选模组和 Java 编译版本。
- 根 `gradle.properties`：统一 mod 版本与公共依赖版本。
- `versions/versions.json`：唯一节点清单，settings、聚合任务和 CI 均直接读取。

数据库 schema、协议版本、解码器修订和材料规则修订各有一个事实源，不能随节点分叉。材料解释标识是缓存语义，不是项目身份：同 hash 换解释版本必须重新解析，成功后原子替换需求快照，保留仍存在材料的 ID 和认领。

## 工具与命名

构建 JVM 使用 Java 25；共享模块和 1.21.x 编译目标 Java 21，26.2 编译目标 Java 25。Gradle wrapper 固定版本并验证官方 SHA-256。

1.21.x 使用官方 Mojang mappings，由 remap Loom 生成 intermediary 发行产物；26.2 原生去混淆，不声明 mappings、不 remap。Parchment 不属于必要依赖。

Stonecutter 只管理源码生成，不自动修复 API 或行为差异。条件块只允许放在平台适配、codec 类型、Mixin 接线或客户端绘制／输入外壳，不能进入共享业务。

## 构建与验证

本机外置／网络文件系统不直接运行 Gradle，受控源码复制到本地持久目录中的独立工程；不要把未提交源码仅放 `/tmp`。每个副本的 `.runs/<Minecraft>/` 隔离各测试运行目录，多个工作区不得共用同一目录。

```bash
JAVA_HOME=<Java25> ./gradlew buildAllVersions
JAVA_HOME=<Java25> ./gradlew checkAllVersions
JAVA_HOME=<Java25> ./gradlew testAllClients
JAVA_HOME=<Java25> ./gradlew testOptionalDependencies

# 单节点调试／验证
JAVA_HOME=<Java25> ./gradlew :1.21.7:checkPlatform
JAVA_HOME=<Java25> ./gradlew :1.21.8:runFoundationClientGameTest
JAVA_HOME=<Java25> ./gradlew :26.2:runClientGameTest
```

聚合任务必须显式覆盖共享模块和所有节点；缺任务、未注册测试或空选集不视为成功。纯测试、平台单测、旧后端／Foundation GameTest、客户端与实际 Litematica oracle 分别报告，不能重复统计共享测试。

产物位于 `versions/<Minecraft>/build/libs/`，每个 jar 只声明对应 Minecraft 和 Java 范围。共享业务装配一次，检查整个嵌套依赖闭包无重复业务类；SQLite 驱动必须存在。含注册表／ItemStack 的 codec 按节点验证，不要求跨 Minecraft 的字节完全一致。

根验收任务在真实执行后统一调用 `scripts/verify_multiversion.py`，共享静态告警上限为 0、平台每节点上限为 28，并按已验证基线的 type／class／count 拒绝新告警，不能靠总数较低掩盖回归。必需的回归测试类及类内最少用例数也纳入验收。缺失报告、零测试、跳过测试或缺少任一注册客户端入口都不能通过；测试专属语言适配器只记录实际 `runTest` 的开始／完成／失败，不替代测试。需要只读检查已有产物时：

```bash
python3 -I scripts/verify_multiversion.py --root . --scope all --strict
python3 -I scripts/verify_multiversion.py --root . --node 1.21.7 --scope package,spotbugs --strict
```

每个服务端、客户端和缺依赖运行目录分别清理，不能让某个任务擦掉另一后端的日志。缺可选依赖任务实际剥离依赖 jar 并使用独立测试 mod／类加载组，不禁用 Fabric 的正常类路径隔离。

## detached 与并行工作

CI 使用 detached controller 从各节点生成源构建，检查共享源码前后哈希不变。至少一轮验证禁用构建缓存并重新生成源码和测试，避免活动版本缓存掩盖错误。

同一工作树不能被多个进程同时切换 active 版本。代理并行开发使用独立工作树／副本；版本切换不能覆盖另一进程的未提交工作。提交前归一到受控 VCS 状态，生成目录和运行产物不入库。

## 本轮验收记录

2026-11-10，冻结后的同一源码快照在独立 `/tmp` 工程执行 `clean`、`--no-build-cache --rerun-tasks` 和四个根聚合任务，全部通过：

| 验收 | shared | 1.21.7 | 1.21.8 | 26.2 |
|---|---:|---:|---:|---:|
| JUnit（失败／跳过均为 0） | 183 | 366 | 366 | 363 |
| 旧后端／Foundation 服务端 GameTest | — | 40 / 2 | 40 / 2 | 40 / 2 |
| 旧后端／Foundation 客户端入口 | — | 23 / 2 | 23 / 2 | 23 / 2 |
| 缺依赖双后端服务端 GameTest | — | 2 / 2 | 2 / 2 | 2 / 2 |
| 缺依赖双后端客户端入口 | — | 1 / 1 | 1 / 1 | 1 / 1 |
| SpotBugs | 0 | 22 | 22 | 19 |

Foundation 客户端包含实际 Litematica 材料规则对照，三个节点各执行同一组 41 个状态描述。产物检查确认各节点精确 Minecraft／Java 依赖、shared 与 SQLite 各嵌套一次、无重复业务类和测试类泄漏；既有 payload 字段和 codec 结构未变。272 份受控源码与配置在 detached 完整构建前后哈希一致，统一守卫 45 项通过。

这份记录是本轮本机验收，不代替提交后的 GitHub CI，也不代表已将 Foundation 切到正式服或完成后续扫描重写。客户端运行验证使用真实 Minecraft 的开发／测试环境；不将其冒充独立安装客户端验收。

## 放行纪律

只有三个实际运行时全部通过编译、平台与共享测试、服务端／客户端回归、协议守卫及产物检查，才能宣称统一多版本基座完成。配置或最小工程通过不替代全项目验收。

发现结构性阻塞时先说明失败证据、已完成部分和调整建议；不删除测试、不静默关闭功能、不退回两份业务代码。

默认仍是旧后端，Foundation 用独立 `runtime-v2.sqlite`；不自动导入旧业务库，不切换正式服，不打 tag。真实区域扫描在多版本基座之后接入，见 [区域扫描重写](area-scanning.md)。
