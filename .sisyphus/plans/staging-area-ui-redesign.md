# 备货区 UI 重设计计划（V2：复制 Litematica 方案）

> **创建时间**: 2026-05-23
> **目标版本**: 1.21.7-0.2.1-alpha.1
> **方案**: 直接复制 Litematica 区域编辑器代码，然后适配

---

## 一、设计目标

### 1.1 用户需求

从截图对比：
- **当前 UI**：只有列表 + 坐标显示，没有编辑能力。新建的 area_1 坐标全是 `[0,0,0]~[0,0,0]`，不可用。
- **Litematica UI**：完整的坐标编辑器，有简易模式和标准模式。

### 1.2 成功案例

之前材料列表（`GuiMaterialList`）就是直接复制 Litematica 的代码，效果非常好。这次备货区 UI 也采用同样的策略。

### 1.3 映射关系

| Litematica | SyncMaterial | 说明 |
|-----------|-------------|------|
| 简易模式 | 备货区简易模式 | 直接设置一个备货区的两个角点坐标 |
| 标准模式 | 备货区标准模式 | 管理多个备货区（每个子区域 = 一个备货区） |
| 子区域编辑 | 备货区编辑 | 设置某个备货区的两个角点坐标 |
| 保存原理图 | 保存到服务器 | 把坐标发送到服务端保存到数据库 |
| 分析区域 | 去掉 | 不需要 |
| 选区浏览器 | 去掉 | 不需要 |

---

## 二、需要复制的文件

### 2.1 GUI 文件（1210 行）

| Litematica 源文件 | 行数 | → 我们的文件 | 说明 |
|------------------|------|------------|------|
| `GuiAreaSelectionEditorNormal.java` | 720 | `GuiStagingAreaEditorNormal.java` | 主编辑器（标准模式） |
| `GuiAreaSelectionEditorSimple.java` | 121 | `GuiStagingAreaEditorSimple.java` | 简易模式编辑器 |
| `GuiAreaSelectionEditorSubRegion.java` | 87 | `GuiStagingAreaEditorSubRegion.java` | 子区域编辑器 |
| `GuiAreaSelectionManager.java` | 282 | 不复制 | 选区浏览器，不需要 |

### 2.2 数据类（989 行）

| Litematica 源文件 | 行数 | → 我们的文件 | 说明 |
|------------------|------|------------|------|
| `AreaSelection.java` | 563 | `AreaSelection.java` | 区域数据类 |
| `Box.java` | 244 | `Box.java` | 坐标数据类 |
| `SelectionMode.java` | 96 | `SelectionMode.java` | 选择模式枚举 |
| `CornerSelectionMode.java` | 86 | `CornerSelectionMode.java` | 角点模式枚举 |

### 2.3 Widget 文件（266 行）

| Litematica 源文件 | 行数 | → 我们的文件 | 说明 |
|------------------|------|------------|------|
| `WidgetListSelectionSubRegions.java` | 62 | `WidgetListStagingAreas.java` | 列表 widget（已复制） |
| `WidgetSelectionSubRegion.java` | 204 | `WidgetStagingAreaEntry.java` | 条目 widget（已复制） |

**总计**：约 2200 行代码需要复制

---

## 三、适配工作

### 3.1 包名重命名

所有文件的包名从 `fi.dy.masa.litematica` 改为 `net.syncmaterial.syncmaterial`。

### 3.2 替换 DataManager

Litematica 使用 `DataManager.getSelectionManager()` 获取当前选区。我们需要：
- 创建一个简单的 `StagingAreaManager`（已有）来替代
- 在 GUI 中直接传入选区数据，而不是从 DataManager 获取

### 3.3 删除不需要的功能

| 功能 | 位置 | 处理 |
|------|------|------|
| 保存原理图 | `GuiAreaSelectionEditorNormal` | 删除按钮和处理逻辑 |
| 分析区域 | `GuiAreaSelectionEditorNormal` | 删除按钮和处理逻辑 |
| 选区浏览器 | `GuiAreaSelectionManager` | 不复制 |
| 快捷键 | `Hotkeys` | 不复制 |
| 配置系统 | `Configs` | 不复制 |

### 3.4 添加网络通信

**当前**：Litematica 把选区保存到本地文件。
**我们需要**：把选区发送到服务端，保存到数据库。

**新增 action**：

| action | 说明 |
|--------|------|
| `UPDATE` | 更新备货区坐标（新增） |

**修改现有包**：

| 包 | 修改 |
|----|------|
| `StagingAreaConfigC2SPacket` | 添加 pos1/pos2 坐标字段 |
| `StagingAreaConfigResponseS2CPacket` | 添加坐标字段 |

### 3.5 数据库层

**无需修改**：`staging_areas` 表已有所有需要的字段（name, x1, y1, z1, x2, y2, z2, world）。

---

## 四、实施任务

### Wave 1：复制数据类 + Widget ✅

- [x] 1. 复制 `Box.java` — 坐标数据类
- [x] 2. 复制 `AreaSelection.java` — 区域数据类
- [x] 3. 复制 `SelectionMode.java` — 选择模式枚举
- [x] 4. 复制 `CornerSelectionMode.java` — 角点模式枚举
- [x] 5. 更新 `WidgetListStagingAreas.java` — 适配新数据类
- [x] 6. 更新 `WidgetStagingAreaEntry.java` — 适配新数据类

### Wave 2：复制 GUI 文件 ✅

- [x] 7. 复制 `GuiAreaSelectionEditorNormal.java` → `GuiStagingAreaEditorNormal.java`
- [x] 8. 复制 `GuiAreaSelectionEditorSimple.java` → `GuiStagingAreaEditorSimple.java`
- [x] 9. 复制 `GuiAreaSelectionEditorSubRegion.java` → `GuiStagingAreaEditorSubRegion.java`

### Wave 3：适配 + 清理 ✅

- [x] 10. 删除不需要的功能（保存原理图、分析区域、选区浏览器）
- [x] 11. 替换 DataManager 为我们的数据源
- [x] 12. 添加网络通信（UPDATE action）
- [x] 13. 更新服务端处理（保存坐标到数据库）

### Wave 4：集成 + 测试 ✅

- [x] 14. 删除旧的 `GuiStagingAreaEditor.java`
- [x] 15. 更新入口点（`GuiMaterialList` → 打开新编辑器）
- [x] 16. 编译验证
- [ ] 17. 测试验证（等用户测试）

---

## 五、关键设计决策

### 5.1 数据流

```
客户端编辑坐标
    ↓
点击"保存"
    ↓
构造 StagingAreaConfigC2SPacket (action="UPDATE")
    ↓
发送到服务端
    ↓
服务端保存到 staging_areas 表
    ↓
返回确认
```

### 5.2 选区管理

**Litematica**：使用 `SelectionManager` 管理所有选区，支持文件 I/O。
**我们**：使用简单的 `StagingAreaManager`（已有），通过网络包同步。

### 5.3 简易模式 vs 标准模式

- **简易模式**：直接设置一个备货区，最常用
- **标准模式**：管理多个备货区，高级功能
- 用户可以在顶部按钮切换模式

---

## 六、验收标准

### 6.1 简易模式

- [ ] 能设置备货区名称
- [ ] 能设置角点1/角点2 的 X/Y/Z 坐标
- [ ] +/- 按钮能微调坐标
- [ ] "移动到玩家位置"能设置坐标
- [ ] "保存"能发送到服务器
- [ ] 服务端能正确保存到数据库

### 6.2 标准模式

- [ ] 能新建子区域
- [ ] 子区域列表显示正确
- [ ] 能重命名子区域
- [ ] 能删除子区域
- [ ] 点"配置"能进入子区域编辑
- [ ] 子区域编辑能设置坐标
- [ ] "保存"能发送所有子区域到服务器

### 6.3 通用

- [ ] 编译通过
- [ ] 界面布局与 Litematica 一致
- [ ] 坐标输入框能正确显示/编辑
- [ ] 错误情况有合理提示
