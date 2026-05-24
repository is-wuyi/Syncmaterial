# Learnings - SyncMaterial 团队协作

## 2026-05-22 Phase 1-3 完成

### 技术决策
- Fabric Yarn 映射中 BlockEntity 方法名：`markDirty()`, `markRemoved()`, `getWorld()`, `getPos()`
- PacketCodec.tuple() 最多支持约 6-8 个参数，超过需用嵌套 record
- MaLiLib WidgetListBase 抽象方法：`createListEntryWidget(int x, int y, int width, boolean selected, TYPE entry)`
- MaLiLib WidgetListEntryBase 构造器：`(int x, int y, int width, int height, TYPE entry, int listIndex)`
- MaLiLib WidgetContainer.render 签名：`render(DrawContext, int, int, boolean)` 不含 partialTicks
- MaLiLib WidgetBase.drawStringWithShadow 签名：`(DrawContext, int x, int y, int color, String text)`
- MaLiLib WidgetBase.onMouseClicked 签名：`(int, int, int)` 不含 double

### 数据库
- staging_area (单数) = 旧版按 material_id 汇总，Phase 2 后保留
- staging_areas (复数) = Phase 2 备货区区域定义
- staging_area_inventory = Phase 2 备货区内容物统计
- player_inventories = Phase 2 背包数据持久化
- assignments + assignment_permissions = Phase 4 权限与分配（已建表）

### 架构模式
- SyncMaterial 持有全局实例（StagingAreaManager, CollaborationManager）
- StagingAreaManager 用脏标记+4 tick 批量扫描
- BlockEntityMixin 双端加载，服务端守卫
- 反射读取 Litematica API，优雅降级返回空列表
