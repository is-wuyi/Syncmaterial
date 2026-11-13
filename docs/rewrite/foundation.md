# Foundation 后端

首批建立新运行时、新 SQLite 仓储、统一导入及材料／认领／库存闭环。多版本基座之后继续区域扫描重写，细节见 [区域扫描](area-scanning.md)。**不改现有 GUI 布局**，不切换正式服，不发布 tag。

## 模式和数据隔离

默认仍是 `legacy`。只有游戏 JVM 显式设置 `-Dsyncmaterial.backend=foundation` 才使用新后端。

- 新库：`<world>/syncmaterial/runtime-v2.sqlite`。
- 若首次创建新库时发现旧 `syncmaterial.db`（优先旧后端的进程工作目录，兼容回退到游戏目录），将旧文件与必要的 `-wal/-shm` 复制到 `<world>/syncmaterial/legacy-backups/<timestamp>/`，不打开旧库连接、不自动导入、不删除原文件。
- 两模式不同时运行；Foundation 不创建旧数据库、旧扫描器或旧解析线程池。
- 已发布的 Hello、材料、认领和库存包字段顺序和类型不变。协议 2 新增材料目录原地更新；协议 3 新增有序权威区域库存快照，绑定当前目录投递修订；协议 4 增加项目／请求关联的团队管理结果和状态推送。新包各自按能力门发送，最低兼容版本仍为 1。
- 新客户端收到 Foundation 目录后自动启用每 100 tick 的库存心跳，不依赖客户端启动参数；服务端 10 秒未续期即不计入可用量。旧客户端仍可使用已发布包，但没有权威区域快照时不应把界面“收集完成”作为有效备货确认，首次查询明确提示限制。
- 区域配置、仓库引用、被动扫描与明细由新共享仓储和扫描服务接通；客户端只在当前目录的权威区域快照完整、新鲜时确认准备状态。WP5 负责人／批量协作已接通共享事务及管理界面，三节点本地完整验收通过，提交／CI 状态见 [团队管理](team-management.md)；完整团队搬运、订阅整合与旧实现退场仍有后续批次。

普通启动时将 `-D` 交给 **Minecraft JVM**，不是只传给 Gradle daemon。测试任务已正确传递模式，无需手动设全局属性。

## 本批结构

- `shared/`：Java 21 的 `core`、`server/storage`、`server/ingest` 与 `server/runtime`，依赖闭包不包含 Minecraft/Fabric；模型、真实 JDBC 和运行时测试独立运行。
- `server/storage`：单写入执行器、业务级事务、外键／唯一约束、schema 版本和稳定材料 ID；生产 API 不暴露 SQL、Connection 或 ResultSet。
- `server/ingest`：两个发现入口统一调用 IngestService；按项目串行更新，内容 hash 不作为项目身份。
- `adapter/litematica`：同步、有界的 NBT 解码与材料规则；区域分别统计后累计，不构造全局方块位置 Map，不读取原理图容器内物品。
- `server/runtime`：运行时资源关闭、在线库存新鲜度、UUID 订阅，以及旧库备份。项目变更和库存心跳按项目合并到一次 tick 广播。
- 数据库关闭超时会明确失败，保持该路径租约，直到旧写入线程真正结束；新 runtime 不能同时重新打开同一路径。
- `network/FoundationNetworkAdapter`：新仓储到旧包的薄适配层，尚未接入功能不回报完成。

## 材料口径

对照每个节点实际加载的 Litematica：1.21.7/1.21.8 使用 0.23.7 的 pick-stack 规则，26.2 使用 0.28.5 的 as-item 规则。区域遍历、位数组、限额和材料聚合在 `shared` 中只有一份；平台仅提供原生 NBT 与方块事实。各 region 分别统计后累计，墙式／悬挂式同物品按 item id 聚合。

材料解释标识为 `mc:<DataVersion>/decoder:1/materials:1`，与文件 hash 一并判断缓存有效性。同 hash 换解释必须重新解析；schema 2 在同一事务写入需求与解释标识，迁移前用 SQLite 一致性备份。新 hash 解码失败时保留已提交的材料 ID、需求和认领，但拒绝把旧统计作为当前结果下发或用于新认领，向玩家提示统计已过期。

结构损坏、短数组、越界调色板或非法状态必须失败，不能成功存为缺材料清单。默认限额：NBT 512 MiB、总方块 6400 万、region 65536、palette 1048576。药水等仍按 item id，不在本批增加组件／配额模型。

同一项目重新导入保留仍存在材料的 ID 与认领；仅删除消失材料。区域、仓库引用和负责人配置不会因 hash 更新被整项目删除。

## 验证命令

本机外置／网络文件系统不直接构建；在本地文件系统的持久独立副本执行，源码与交接记录不只保存在 `/tmp`。测试运行目录按副本和节点隔离。

```bash
JAVA_HOME=<Java25> ./gradlew checkAllVersions
JAVA_HOME=<Java25> ./gradlew testAllClients
JAVA_HOME=<Java25> ./gradlew testOptionalDependencies
# 单节点
JAVA_HOME=<Java25> ./gradlew :1.21.7:runFoundationGameTest
JAVA_HOME=<Java25> ./gradlew :26.2:runFoundationClientGameTest
```

- 常规 `build` 保持旧后端回归。
- Foundation 服务器任务只选 `syncmaterial-foundation-test:foundation_server_game_test_*`。
- 缺依赖验收用独立测试 mod，服务器分别验证旧／新后端启动和实际 NBT → 解码／导入；客户端验证无 MaLiLib 时主菜单与真实专服连接可用，未握手不会收到业务包。MaLiLib 缺失时不提供 GUI/HUD/选区，不把启动冒烟称为完整界面可用。
- Foundation 客户端使用独立测试 mod，只运行 Foundation 网络闭环和实际 Litematica oracle，不静默跳过旧测试。
- `LegacyWireGoldenTest` 固定 Hello 和材料／库存请求的编码字节。
- JUnit 覆盖回滚、ID 稳定、重复导入、失败重试、项目更新、未知 schema 拒绝、关闭与旧库隔离。
- 目录同步回归覆盖同 hash 重命名、保留勾选与认领、不同玩家／连接独立投递及删后重建；旧协议握手与包字节保持不变。

2026-11-08 最终本机同快照验证：478 个 JUnit 测试零失败、零跳过；旧后端服务端 GameTest 40/40，Foundation 2/2；旧客户端与 Foundation 客户端任务均通过，包括实际 Litematica 材料规则对照、清单原地更新和静止背包心跳续期。SpotBugs 27 条，不超过 28 条基线。构建在独立 `/tmp` 副本执行。

另外以外置测试脚本移除 MaLiLib/Litematica/Syncmatica 的运行 classpath，独立运行目录下旧后端 40/40、Foundation 2/2 均通过。客户端无这三模组时由 bootstrap 禁用界面／HUD／选区，已实际进入 `TitleScreen` 并正常结束；这只是缺依赖启动冒烟，不代表缺库时仍提供完整界面功能。

## 后续批次

1. 负责人／批量协作、统一订阅及团队剩余缺口。
2. 完整团队搬运与客户端会话／状态绑定迁移。
3. 删除旧结构与全仓最终验收。

区域扫描详见 [本批规则与验收](area-scanning.md)。分批实施不等于完整后端已完成，也不代表正式服可直接切换。
