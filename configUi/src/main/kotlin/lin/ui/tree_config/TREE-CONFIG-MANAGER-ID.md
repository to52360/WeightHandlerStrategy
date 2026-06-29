# 评估树 manager_id 说明

## 为什么用 nullable？

评估树有两种绑定类型：

- **GROUP 绑定** → `manager_id = 创建/编辑属性时的 activeManagerId 快照`（属于该方案）
- **PURPOSE_TAG 绑定** → `manager_id = NULL`（用途标签全局概念，跨方案共享）

## is_template

- `is_template = 1` → 模板，可由"从模板新建"复制结构
- `is_template = 0` → 普通评估树
- 不影响 manager_id 逻辑，仅标记"可被复用"

## 迁移 SQL

```sql
ALTER TABLE tree_config ADD COLUMN manager_id TEXT;
ALTER TABLE tree_config ADD COLUMN is_template INTEGER NOT NULL DEFAULT 0;
```
