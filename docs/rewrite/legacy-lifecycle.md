# Legacy 服务器生命周期

## 范围

本批为默认 legacy 后端建立明确的服务器资源所有权，解决旧服务器延迟任务选择新全局 manager、解析资源泄漏、关库续写及连接／订阅串服的问题。它不是 WP5 负责人业务迁移，也不是永久保留双轨后端的决定。

默认后端、区域权限、材料／背包统计口径、GUI 操作、已发布 payload、协议 3 和 Foundation schema 3 不变。Foundation 的共享业务与区域扫描不在本批重写；共同握手状态只补服务器／连接归属。正式服切换、旧库导入与发布仍不在授权范围。

## 所有权与入口

`LegacyServerContext` 一次绑定 `MinecraftServer` 和独立 epoch，拥有数据库、query service、parser、借用 parser 的统计引擎、协作／区域 manager、watcher、摄取协调器、Syncmatica 注册句柄、订阅、名称缓存与 tick 任务。

- 资源创建成功后经 `LegacyServerRegistry` 安装；失败清理已创建资源。同一对象重复安装幂等，竞争对象不得关闭已安装对象。
- 服务端 receiver 与生命周期／tick listener 在 mod init 注册一次。数据库不需要在 receiver 注册时已经创建；payload 类型仍最先注册。
- 有 server／player／world 的入口使用明确归属。排队工作捕获原上下文与原连接，执行时验证 registry identity、open 状态、当前玩家与连接。
- 无 server 的 `SyncMaterial.getShared*()` 仅为历史兼容读取：唯一打开上下文时返回资源，多服务器或无上下文时返回 null。生产异步业务不调用它们。
- legacy 材料列表／仓库明细订阅及 placement names 不再是全局表。关闭封存订阅、清除缓存，不修改另一服务器。
- `ProtocolHandshake` 共同状态以服务器、UUID、连接身份记录；旧断线不能删掉新连接，关闭删除本服务器准入，迟到 Hello 不能复活记录。

## 启动、发现与提交

启动扫描由 `LegacyTickTasks` 在服务器 tick 上推进：启动后单调时间经过约 5 秒执行一次，dirty 处理每 4 ticks 一次。没有 common-pool 睡眠任务，也不把 100 ticks 当作固定 5 秒。

watcher 的目录注册、初始读取与事件循环在后台；`initialScanCompleted()` 只表示目录读取／排队结束，不表示解析已完成。解析完成向原上下文提交项目补扫及材料／仓库发布，慢解析不依赖启动延迟碰巧足够。

文件与上传发现共用 `LegacySchematicIngest`：

1. 事件捕获不可变元数据；原 Syncmatica placement 不进入异步计算。
2. 项目 ID 是意图键，同 hash 的不同项目独立处理。每项目 generation 拒绝被删除／替换的解析结果。
3. parser 返回真正与 executor task 绑定的可取消 future。完成回调只把纯结果放回摄取串行器，不阻塞 `join()`。
4. 数据库 `inTransaction` 内检查 owner／epoch，并原子领取提交租约。取消先于租约则不写；已准入提交先完整结束，后续串行删除／替换获胜。完成信号不早于完整事务结束。
5. watcher／upload 共享任务用参与者句柄释放兴趣，旧监听器退出不取消仍由其他来源需要的任务。
6. 删除仅在事务真正提交后发布；失败保留重试，不能用成功广播掩盖失败。
7. 最终更新／删除发布由上下文生命周期锁和摄取意图锁共同限定；扫描过程中意图变更，旧发布不清新缓存或订阅。

原先 `SyncmaticaIntegrationMixin` 绑定 client context、选择全局服务端数据库的路径已删除。legacy 现在像 Foundation 的现有适配一样，等待真实 `SERVER_CONTEXT`，核对配置目录属于本世界，保存准确 manager／consumer，换上下文或停服注销。缺 Syncmatica 时文件发现继续工作。

## 两阶段关闭

`STARTING → RUNNING → STOPPING → CLOSED` 单向推进。

- `SERVER_STOPPING`：先封闭上下文，取消 tick 任务；数据库 seal 拒绝后续 SQL、关闭已签发查询、回滚未完成事务并唤醒等待者；注销上传监听，关闭 watcher／摄取／manager／订阅，关闭引擎与 parser 的准入和任务。
- `SERVER_STOPPED`：用一个总计 5 秒的等待预算回收 watcher／摄取／parser，最后关闭数据库。`CLOSED` 与 `whenClosed()` 在收尾后才完成；重复 close 不二次释放，其他调用者等待已开始的关闭。
- 单项回收失败记录错误但继续释放其他资源。异常事务必须先成功回滚才恢复 auto-commit；回滚失败封存并丢弃连接，不触发 JDBC 的残留提交。

解析池采用 JDK `FutureTask` 与 `CompletableFuture` 配对，处理开始／取消交接；队列取消释放槽位。关闭先阻止执行器取出新任务，再终结 future／取消真实任务，不能让中断当前任务后工作线程抢先执行排队任务。

### 限制

- 5 秒是线程终止的总等待预算，不是强制中断任何本地 IO／JDBC／第三方阻塞调用的硬时限。seal 等待已进入的完整事务，解析 NBT 解压在原生读取返回后才响应协作式中断；后台线程使用 daemon 作为退出保障。
- 历史 `getExecutor()` 普通 executor 入口与 `QueryResult.getResultSet()` 原始 ResultSet 逃生口仍存在；生产 parser 使用 tracked submit，正常查询代理方法经数据库 monitor。raw ResultSet 在 seal 被物理关闭，但调用方若绕过代理并发读取，不能获得同样的锁语义。
- legacy 扫描算法仍是待 WP10 退役的旧实现；本批不宣称已把 Foundation 的全部被动扫描语义移植到旧 scanner。

## 验证与证据

当前状态：**同一源码 clean／禁用缓存三节点完整本地验收已通过；提交、推送和 CI 待确认。** 完整进度见 [统一进度入口](progress.md)。

测试分别覆盖：可控单调时钟／4-tick 节奏，关闭／旧队列／原连接，真实 SQLite seal／事务等待／回滚失败，实际任务中断与排队取消，发现 generation／共享句柄／晚完成删除，以及真实客户端同 JVM A→B 重开。

最终本地验收（2026-11-13）：

- `buildAllVersions checkAllVersions testAllClients testOptionalDependencies` 全部通过，先 clean、禁用缓存；源码哈希前后相同。
- shared 260；三节点平台 579／579／576，全无失败／错误／跳过；每节点 legacy／Foundation 服务端 40／13、客户端 24／2；缺依赖两后端服务端各 2、客户端各 1。
- 49 项统一守卫全绿；shared SpotBugs 0、三节点各 11，无新增 accepted findings；没有增加 exclude。
- 同 JVM A→B 重开包含关服前保留的 owner 回调、A 名称／两类订阅／握手／数据库／registry 清理，以及 B 名称、握手、64 仓库计数与真实 GUI／明细回流。

实现期间失败与修复记录：

- 三节点平台测试 575／575／572 全部通过；26.2 实际 legacy 客户端 24 个入口全部通过，包含新 A→B 重开和仓库网络闭环。
- 首轮整合测试编译失败两个新夹具，修复异常声明／重载后重跑；后续 571 单测中发现解析池关闭时排队任务抢跑，补实现与确定性回归后通过。
- 首轮真实客户端失败于多人假人广播：假人未握手，新统一发包门按设计拒绝。测试假连接显式使用服务器／连接握手，保留生产能力门和原广播断言，整套重跑通过。
- 静态告警清理不修改 accepted findings、不增加 exclude；最终计数与精确检查如上。

本地放行已满足四项聚合任务、源码哈希一致、协议 golden／打包／依赖隔离和新测试非空注册守卫。提交后的 CI 仍单独确认；资源限制与完整重构后续范围不会因本批通过而消失。
