# 现状汇总 — AI 配置生成 V1 前基线

> 日期: 2026-06-30
> 目的: 梳理当前 AI 配置生成全流程现状，明确 V1（分组编排 + 绑定评估树）的起点和各模块关联关系，不做决策结论。

---

## 1. 当前 AI 配置生成链路全貌（已完成）

```
┌─────────────────────────────────────────────────┐
│                   AI Client                      │
│  (大模型应用层，如 Claude Desktop / Cursor)       │
└──────────────────────┬──────────────────────────┘
                       │ MCP (Stdio Transport)
                       ▼
┌─────────────────────────────────────────────────┐
│           McpServerMain (configUi 独立入口)        │
│  ├─ list_evaluator_leaf_kinds     ← 叶子种类查询  │
│  ├─ validate_evaluator_tree       ← 校验评估树    │
│  └─ save_evaluator_tree           ← 保存评估树    │
└───────────────┬─────────────────────────────────┘
                │ 委托
                ▼
┌─────────────────────────────────────────────────┐
│      DefaultAiConfigGenerationService            │
│  ├─ loadAll() → EvaluatorLeafSourceCatalog       │
│  ├─ validate() → EvaluatorTreeValidator          │
│  └─ save() → TreeConfigService                   │
└─────────────────────────────────────────────────┘
```

**当前 MCP 暴露了 3 个 Tool，已完成基础链路**：

| MCP Tool                    | 输入                       | 输出                        |
|-----------------------------|--------------------------|---------------------------|
| `list_evaluator_leaf_kinds` | 无                        | List<AiEvaluatorLeafKind> |
| `validate_evaluator_tree`   | SaveEvaluatorTreeRequest | ValidationReport          |
| `save_evaluator_tree`       | SaveEvaluatorTreeRequest | SaveEvaluatorTreeResult   |

**当前链路缺失的关键环节**：

- AI 不知道有哪些分组方案（`card_group_manager` / `card_group_binding`），因此无法生成绑定到分组的评估树
- AI 不知道有哪些模板（正交模板、评估树模板），无法参考已有配置
- 叶子种类查询返回全量列表，无分类/分组筛选能力
- 无模板/分组/类别的分类查询流

---

## 2. 分组编排 (card_group) 现状

### 2.1 物理层

```
card_group/
├── db/
│   ├── CardGroupEntities.kt       — CardManagerEntity / CardBindingEntity
│   ├── CardGroupRepository.kt     — JDBC CRUD
│   └── CardGroupService.kt        — 领域服务层
└── ui/
    ├── CardGroupWorkbench.kt      — 工作台入口
    ├── ManagerListPane.kt         — 左侧 Manager 列表面板
    ├── BindingEditorPane.kt       — 右侧 Binding 编辑面板
    ├── WorkbenchState.kt          — 状态模型 (Redux 风格)
    ├── WorkbenchStore.kt          — Store + 副作用
    ├── ActiveManagerHolder.kt     — 全局选中状态
    └── CardGroupExtension.kt      — SPI 导航注册
```

### 2.2 数据模型

**`CardManagerEntity`** (card_group_manager 表):
| 字段 | 类型 | 说明 |
|---|---|---|
| id | TEXT PK | 主键 |
| name | TEXT | 分组方案名称 |
| sourceFile | TEXT | 来源 .cardgroup 文件 |
| enabled | INTEGER | 是否启用 |

**`CardBindingEntity`** (card_group_binding 表):
| 字段 | 类型 | 说明 |
|---|---|---|
| id | TEXT PK | 主键 |
| managerId | TEXT FK | 所属 Manager |
| name | TEXT | 分组名称 |
| cardIds | TEXT | JSON 数组 |
| overrides | TEXT | GroupUseOverride JSON |

### 2.3 运行期绑定关系

树配置 (tree_config 表) 通过 `bindingType=GROUP` + `bindingIds` 引用分组：

- `bindingType`: `GROUP` / `PURPOSE_TAG`（枚举值）
- `bindingIds`: 逗号分隔的 `CardBindingEntity.id` 列表
- `managerId`: 非空（GROUP 绑定时必须指定所属 Manager）

运行期 `SqliteTreeConfigProvider` 只返回 `enabled=true` 的 Manager，然后 filter 掉已禁用的 Binding。

### 2.4 与 AI 生成的关系

**现状**：AI 侧**完全不知晓分组编排的存在**。`SaveEvaluatorTreeRequest.config` 中有 `bindingType` + `bindingIds` 字段，但
AI 无法知道可用的分组 ID，提交的分组 ID 也无法被校验。

---

## 3. 模板系统现状

### 3.1 三种模板实体

| 实体                            | 表名                      | 分组引用                         | 内容                        |
|-------------------------------|-------------------------|------------------------------|---------------------------|
| `EvaluatorTreeTemplateEntity` | evaluator_tree_template | group_id → template_group.id | 整棵评估树的 root JSON          |
| `OrthogonalTemplateEntity`    | orthogonal_templates    | group_id → template_group.id | 正交条件/规则配置快照               |
| `TemplateGroupEntity`         | template_group          | —                            | 模板分类分组（name, description） |

### 3.2 模板与 AI 的关系

**现状**：模板在 UI 侧可独立管理（`TemplateNameDialog`），但**未通过任何 MCP tool 暴露给 AI**。

- AI 无法读取已有模板
- AI 无法基于模板创建新配置
- 模板分组 `template_group` 对 AI 不可见

### 3.3 正交模板的双层结构

正交模板有 `type` 字段：`"CONDITION"` 或 `"RULE"`，且通过 `group_id` 引用 `template_group` 的分类分组。

评估树模板只有一个 `group_id` 引用 `template_group`，不区分子类型。

---

## 4. 叶子类型分类体系现状（回应问题 2.1）

### 4.1 EvaluatorLeafKind 分类层次

```
EvaluatorLeafCategory (一级：CONDITION / RULE)
│
├── CONDITION 组
│   ├── Plain ("CONDITION")           — 编码条件原子，ID 为 "condition_xxx"
│   │   └── 有无分类: ❌ 无，靠 sourceId 字符串区分（如 "minion_count", "hand_size"）
│   │
│   ├── Orthogonal ("ORTHOGONAL_CONDITION") — 正交条件（配置型）
│   │   └── 有无分类: ✅ 有，OrthogonalCategoryCatalog（HAND/BOARD/HERO/GAME_STATE/MANA）
│   │
│   └── Tree ("CONDITION_TREE")       — 引用已有条件树组
│       └── 有无分类: ❌ 无，靠引用 ID 区分
│
└── RULE 组
    ├── Coded ("RULE")                 — 编码规则原子，ID 为 "rule_xxx"
    │   └── 有无分类: ❌ 无，靠 sourceId 字符串区分（如 "attack_lowest", "heal_priority"）
    │
    └── Orthogonal ("ORTHOGONAL_RULE") — 正交规则（配置型）
        └── 有无分类: ✅ 有，由内部 Guard 和 ScoreEffect 的正交组件隐含分类
```

### 4.2 分类不统一现状

| 叶子类型                  | 有无独立分类 | 分类来源                           | 备注              |
|-----------------------|--------|--------------------------------|-----------------|
| CONDITION(Plain)      | ❌      | 无                              | sourceId 是唯一标识  |
| CONDITION(Orthogonal) | ✅      | OrthogonalCategoryCatalog      | 数据源层面有 5 个标准分类  |
| CONDITION(Tree)       | ❌      | 无                              | 引用已有配置          |
| RULE(Coded)           | ❌      | 无                              | sourceId 是唯一标识  |
| RULE(Orthogonal)      | ⚠️ 间接有 | 通过 Guard 和 ScoreEffect 的正交组件暗示 | 没有独立的 "规则分类" 概念 |

### 4.3 问题提炼

> "是不是全部类型叶子都有分类，没有的话是不是不够统一"

- 目前只有正交类型有明确的分类体系（`OrthogonalCategoryCatalog`），非正交类型没有
- "统一性"问题：如果 AI 期望按分类缩小查询范围，非正交类型无法提供分类
- 编码类型（Plain/Coded）是否真的需要分类？它们的 `sourceId` 本身就是唯一标识，AI 可直接查全量

> "编码类我对它的定位，补充型（我都不太清楚是否全部都能够正交型满足）"

- 编码类（Coded Rule / Plain Condition）当前是硬编码的原子行为
- 正交类型理论上可替代大部分编码类型，但存在「正交无法表达的边界场景」：
    - 涉及复杂游戏状态组合的判断逻辑（如多条件聚合）
    - 需要特定排序/过滤算法的场景（如自定义优先级计算）
    - 性能敏感的原子操作（正交管道链路可能较慢）
- 编码类作为「补充型」定位：正交覆盖 80% 通用场景，编码覆盖 20% 特殊场景

---

## 5. 查询流程现状（回应问题 2.2）

### 5.1 当前查询流

```
AI → list_evaluator_leaf_kinds(无参) → 返回全量 List<AiEvaluatorLeafKind>
```

- 返回值：无筛选/无分页/无分类的**全部叶子类型**
- 对于普通类型（Plain/Coded），数量通常有限（几十个），不需要分类筛选
- 对于正交类型，正交组件（DataSource/Operator）本身有 5 个分类（HAND/BOARD/HERO/GAME_STATE/MANA），但**AI 无法按分类查询**

### 5.2 模板查询流

**当前**：无 MCP 接口。

**可能的方向**（不做决策，仅记录现状）：

- **方案 A**: 分类查（按 `template_group` 查）→ 返回分组列表 → AI 选分组 → 查分组下模板
- **方案 B**: 全量查 → 返回所有模板（含 groupId）→ AI 自行聚合展示
- **方案 C**: 分层查（先查模板分组总数 → AI 决策 → 查具体分组模板）

### 5.3 正交组件查询流

正交组件（DataSource/Operator）目前通过 `getEvaluatorTreeInputSchema()` 的 JSON Schema 中的 `oneOf` 枚举暴露给
AI，而非专门的查询接口。
AI 无法单独查询"某个分类下有那些数据源"或"某个数据源支持那些算子"。

---

## 6. 已知缺口清单

### 6.1 功能缺口（V1 范围内）

| #    | 缺口                                 | 影响              | 关联模块                |
|------|------------------------------------|-----------------|---------------------|
| G-01 | AI 无法查询分组方案                        | AI 生成的评估树无法绑定分组 | card_group          |
| G-02 | save_evaluator_tree 不校验 bindingIds | 可能保存无效的绑定       | save_evaluator_tree |
| G-03 | AI 无法参考模板                          | 没有模板驱动的基础配置可参考  | template            |
| G-04 | 叶子分类不统一                            | AI 无法按分类筛选叶子类型  | leaf kind catalog   |

### 4.4 编码类型实际数量调查结论

> 日期: 2026-06-30 补充调查

**关键发现：AI 可见的编码类型极少。**

| 体系                                     | 编码规则          | 编码条件   | AI 可见 |
|----------------------------------------|---------------|--------|-------|
| 新体系（RuleRegistry/ConditionRegistry）    | 2（均为未实现 Demo） | 2（已实现） | ✅     |
| 旧体系（RuleInfo SPI / Java ServiceLoader） | 27（已实现）       | 0      | ❌ 未接入 |

- `EvaluatorLeafSourceCatalog` 只从新体系的 `RuleRegistry` / `ConditionRegistry` 加载，旧体系 27 个规则走独立 SPI 通道，AI
  完全看不到
- **AI 当前实际可见的编码类型：仅 4 个**（2 规则 + 2 条件），其中 2 个规则还是 `TODO("返回你的 RuleResult 结果")` 的脚手架
- 旧体系 27 个规则按功能分布在 `hand`/`onWar`/`status`/`afterRule` 目录，有天然分类结构，但未接入新体系

**结论：编码类型不增设分类。** 数量太少（4 个），加分类只增加查询步骤无实际收益。`list_evaluator_leaf_kinds` 继续全量返回。

---

## 5. 模板存储策略决策（回应问题：AI 生成内容是否都存模板）

> 日期: 2026-06-30

**决定：AI 生成内容不自动存模板。**

|    | 正式配置 (tree_config) | 模板 (evaluator_tree_template / orthogonal_templates) |
|----|--------------------|-----------------------------------------------------|
| 定位 | 生产运行用              | 可复用的参考骨架                                            |
| 来源 | AI 生成 / UI 编辑      | 人工筛选沉淀                                              |
| 数量 | 按需增长               | 精简可控                                                |

如果 AI 生成的全部自动存模板，会导致模板池膨胀、一次性配置污染模板库、AI 查模板时噪声过大。

**推荐做法**：AI 生成内容默认写入 `tree_config`（正式表）。如果用户/AI 判断某个配置有复用价值，再单独"提升为模板"。V1 阶段模板对
AI 只读。

---

## 7. V1 阶段范围约定

### 7.1 V1 包含

- **分组编排暴露**：AI 能查询分组方案，绑定评估树时能正确引用分组 ID
- **绑定类型感知**：`save_evaluator_tree` 能校验 GROUP 绑定的合法性
- **模板参考**：AI 能按分类查询已有模板（只读，不落 AI 生成的模板）
- **叶子分类说明**：AI 能理解每种叶子类型的分类归属

### 7.2 V1 不做

- Combo 编排的 MCP 暴露（排期未定）
- 用途标签 (PURPOSE_TAG) 的 MCP 暴露（排期未定）
- AI 创建/修改模板（后续版本）
- 渐进式流式草稿池（DraftTreeService，已拆为 T-020/T-021）
- 编码类/正交类的替代讨论（Q-01/Q-02 待讨论）
- 正交组件独立 MCP 查询（可能合并到模板查询）

---

## 8. 数据流关系图（V1 目标态）

```
AI Client
  │
  ├── list_evaluator_leaf_kinds ───→ 返回全量叶子（+分类归属说明）
  ├── list_card_groups ──────────→ 返回当前已启用分组方案（Manager+Bindings）
  ├── list_template_groups ─────→ 返回模板分组列表（只读）
  ├── list_orthogonal_templates ─→ 按 group_id 查正交条件/规则模板（只读）
  │
  └── save_evaluator_tree ───────→ 校验 bindingType=GROUP + bindingIds 合法性
                                              ↓
                                   写入 tree_config + evaluator_leaf_config
```

---

## 附录：涉及的关键源文件索引

| 文件                                                                 | 用途            |
|--------------------------------------------------------------------|---------------|
| `configUi/.../card_group/db/CardGroupRepository.kt`                | 分组 DB 层       |
| `configUi/.../card_group/db/CardGroupService.kt`                   | 分组业务服务        |
| `configUi/.../ai/config/DefaultAiConfigGenerationService.kt`       | AI 生成主服务      |
| `configUi/.../ai/config/AiConfigGenerationModels.kt`               | AI 生成模型定义     |
| `configUi/.../mcp/McpToolRouter.kt`                                | MCP tool 路由   |
| `configUi/.../tree_config/db/EvaluatorLeafSourceCatalog.kt`        | 叶子源目录         |
| `configUi/.../tree_config/db/EvaluatorTreeTemplateEntity.kt`       | 评估树模板实体       |
| `configUi/.../tree_config/service/EvaluatorTreeTemplateService.kt` | 评估树模板服务       |
| `configUi/.../db/OrthogonalTemplateRepository.kt`                  | 正交模板 DB       |
| `configUi/.../db/TemplateGroupRepository.kt`                       | 模板分组 DB       |
| `WeightHanderStrategy/.../orthogonal/OrthogonalCategories.kt`      | 正交分类目录        |
| `WeightHanderStrategy/.../tree/EvaluatorLeafMeta.kt`               | 叶子元信息/Kind 定义 |
