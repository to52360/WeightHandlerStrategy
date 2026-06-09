# manager_id 接入指南

> 新增配置表时，参照本文判断是否加 `manager_id` 及如何接入。

## 一、要不要加？

**切换顶部卡组选择器后，该列表的内容应该变化 → 必须加。**

| 模式       | 约束                         | 适用场景               |
|----------|----------------------------|--------------------|
| **强绑定**  | `TEXT NOT NULL DEFAULT ''` | 数据必须属于某个方案         |
| **可选全局** | `TEXT` (nullable)          | 可能属于方案，也可能全局共享,待收敛 |
| **不涉及**  | 不加字段                       | 与卡组方案无关（卡片级/全局级属性） |

现有表一览：

| 表名                      | 约束                  | 模式   |
|-------------------------|---------------------|------|
| `card_group_binding`    | NOT NULL            | 强绑定  |
| `combo_plan_definition` | NOT NULL DEFAULT '' | 强绑定  |
| `tree_config`           | NULLABLE            | 可选全局 |
| `card_purpose`          | —                   | 不涉及  |

## 二、接入步骤

1. **DDL**：加 `manager_id` 列 + 索引（拼写 `manager_id`，不是 `manger_id`）
2. **Entity**：加 `val managerId: String?`（nullable 模式）或 `String`（强绑定模式）
3. **Repository**：加 `findByManagerId(managerId)` 方法，nullable 模式加 `OR manager_id IS NULL`
4. **Workbench**：注入 `ActiveManagerHolder`，监听 `activeManagerProperty` 只用于列表过滤/刷新
5. **创建**：进入新建或弹窗时把当前 `activeManagerId` 作为默认值快照写入草稿/表单状态
6. **保存**：从草稿/编辑对象自己的 `managerId` 写入，禁止在保存瞬间重新读取 `ActiveManagerHolder`
7. **引擎层不动**：`manager_id` 纯 UI 层概念，SPI Provider 不做过滤；如需过滤，内部通过 `CardGroupRepository` 查启用 Manager
   列表，不改变 Provider 接口

## 三、检查清单

- [ ] 切换方案后内容该变？→ 加 `manager_id`
- [ ] 有全局共享场景？→ nullable
- [ ] DDL 加列+索引，Entity 加字段
- [ ] Repository 加 `findByManagerId()`
- [ ] Workbench 注入 + 监听 `ActiveManagerHolder` 只驱动过滤/刷新
- [ ] 创建时快照 `activeManagerId` 到草稿/表单
- [ ] 保存时从草稿/编辑对象自己的 `managerId` 取值
- [ ] 引擎 SPI Provider 不动
