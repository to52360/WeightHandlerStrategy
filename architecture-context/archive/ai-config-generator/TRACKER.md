# 任务追踪 - ai-config-generator

## 当前目标

**V1 (当前迭代)**：实现分组编排元数据暴露 + 评估树绑定分组的一体化 AI 生成通路，让 AI 能够感知可用分组方案并正确创建绑定分组的评估树。

## V1 任务 (分组编排 + 绑定评估树)

### 基础链路（已完成）

| 编号    | 状态   | 任务                        | 说明                                                                                                                                                                                                |
|-------|------|---------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| T-001 | done | 接入 Java MCP SDK           | `configUi` 已接入 `mcp-core + mcp-json-jackson2`，`McpServerMain` 使用 stdio server 注册最小 tools。                                                                                                         |
| T-002 | done | 元数据描述与分类字段评估              | 已完成 `OrthogonalCategoryCatalog` 标准元数据分类定义与暴露规范，正交组件推荐集中收拢至权威分类。                                                                                                                                   |
| T-003 | done | 评估树模板与正式数据共表评估            | 已完成重构解耦。新增物理独立表 `evaluator_tree_template` 并跟随正交模板引入 `group_id` 分组体系，从正式表 `tree_config` 彻底剥离 `is_template` 列并完成 SQL 平滑迁移。                                                                          |
| T-004 | done | 条件逻辑与设计                   | 已通过 `orthogonal-condition` 主题完成。详见 `2026-06-10-condition-design-analysis-draft.md`，演进为 orthogonal-depth condition 设计。                                                                             |
| T-005 | done | 记录当前 MCP 流程图与改造清单         | 见 `2026-06-09-flow.md`。                                                                                                                                                                           |
| T-006 | done | 补齐 evaluator leaf args 校验 | 对应 `P-001`，`SpecValidator.validate(leafConfig.args, allFields)` 已在 `validateLeafConfig()` 中调用，完成类型/必填/约束校验。                                                                                       |
| T-007 | done | 补齐 MCP inputSchema        | 对应 `P-002`，已在 `DefaultAiConfigGenerationService` 中扩展强类型 JSON Schema 构造程序，包含 5 大叶子节点多态隔离与正交组件元数据关联，`McpToolRouter` 已移除占位并接入该动态 Schema。                                                             |
| T-008 | done | 同步 EvaluatorLeafKind 重命名  | `list_evaluator_leaf_sources`→`list_evaluator_leaf_kinds`，`AiEvaluatorLeafSource`→`AiEvaluatorLeafKind`，已同步 `McpToolRouter` / `AiConfigGenerationModels` / `DefaultAiConfigGenerationService` 三处。 |
| T-009 | done | Guard+ScoreEffect 校验      | 对应 `P-005`，`EvaluatorTreeValidator` 已追加 Kind-ScoreEffect 相容性、PipelineRef 非空及引用完整性、SourceScore 非空及注册表校验、PRUNE-warn。`PipelineAssembler` + `ScoreOperatorRegistry` 已注入 validator。                    |

### 分组编排 AI 集成（V1 核心）

| 编号    | 状态   | 任务             | 说明                                                                                                                                                                                                                                                                                                             |
|-------|------|----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| T-010 | done | 暴露卡池源文件列表及卡池内容 | 新增 `list_card_group_sources` + `get_card_group_detail` 两个 MCP tool。list 列出所有 .cardgroup 文件的 fileName/enabled/cardCount；get 按 fileName 返回完整卡池详情（cardId + name + text，name/text 从 hs.cards 批量查询）。AI 先列后查，基于指定卡组编排分组。                                                                                             |
| T-011 | done | 暴露分组方案查询       | 新增 `list_card_groups` MCP tool，直接序列化 `CardGroupService.loadAllManagers()` 结果（id/name/sourceFile/enabled），渐进式先摘要后详情。`CardGroupToolProvider` 新增 `CardGroupService` 注入参数，无新 model。                                                                                                                                |
| T-012 | done | AI 创建/保存分组方案   | 新增 `save_card_group` MCP tool。AI 提供 sourceFile + bindings[{name,description,cardIds}] + 可选 managerName/existingId；服务端补齐 enabled/binding.id。existingId 非空即更新，为空即新建。cardIds 校验依赖 sourceFile 卡池。`CardGroupBinding` 新增 `description` 字段（领域模型+DB 表+实体+Service 同步）。                                                  |
| T-016 | done | 卡组代码解析导入卡池     | 新增 `parse_hearthstone_deck_code` MCP tool（T-010 延伸增强）。AI 提供炉石 deck string，服务端解码 VarInt 流→查 hs.cards 补全 name/text→可选写 `.cardgroup` 文件。`CardGroupToolProvider` 新增 `HsCardRepository` 注入参数。同时迁移 `lin.ui.db`→`lin.db` 包（HsCardRepository/OrthogonalTemplateRepository/TemplateGroupRepository），消除 DB 层对 UI 命名空间误导。 |

### 绑定评估树校验增强（V1 核心）

| 编号    | 状态   | 任务                               | 说明                                                                                                                                                                                                          |
|-------|------|----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| T-013 | done | 绑定类型感知：扩展 save_evaluator_tree 校验 | `DefaultAiConfigGenerationService.validateEvaluatorTree()` 内追加 MCP-only 校验：`PURPOSE_TAG` → 拒绝；`GROUP` → 逐 bindingId 核查已启用分组方案中存在性。`EvaluatorTreeValidator` 保持不变（UI 不受影响）。`McpModule` 注入 `CardGroupService`。 |

### 模板参考（V1 只读）

| 编号    | 状态   | 任务                 | 说明                                                                                                                                                                                                                                                                       |
|-------|------|--------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| T-014 | done | 暴露模板查询与沉淀供 AI 自主决策 | 拆为两个维度：`TemplateToolProvider`（正交叶子级：`list_template_groups` + `list_templates`（CONDITION/RULE）+ `save_template`）；`AiConfigToolProvider`（评估树树级：`list_evaluator_tree_templates` + `save_evaluator_tree_template`）。互不混淆。                                                     |
| T-015 | done | 修复模板保存参数剥离         | 三条保存路径统一剥离：`OrthogonalConditionDialog`（PipelineRef.operatorArgs/transforms[].args/refId→空）、`OrthogonalRuleDialog`（OrthogonalRuleLeafConfig 的所有 args+guard+score）、`EvaluatorTreeTemplateService.saveTemplate()`（所有5种 leaf config 递归剥离 args/nodeId/guard/score 参数）。零 lint。 |

### 设计决策记录（不编码）

> 编码类型（Plain/Coded）不增设分类体系。调查结论：AI 可见的编码类型仅 4 个（新体系 2 规则 + 2 条件），全量返回无压力；旧体系 27
> 个规则走独立 SPI 未接入 AI。正交类型有分类是因为组件是组合型的，编码类型是原子型的，`sourceId` 本身即唯一标识。详见
`references/2026-06-30-status-summary.md`。

## 后续待定任务

| 编号    | 状态      | 任务                        | 说明                                                                                                                                        |
|-------|---------|---------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| T-020 | done    | 建立草稿池服务 DraftTreeService  | 建立内存/缓存级评估树生成草稿池，支持基于 `draftId` 的树骨架暂存、叶子节点配置逐步增量更新与生命周期管理。                                                                               |
| T-021 | done    | 暴露渐进式流式 MCP 工具组           | 提供 `create_draft_tree` (创建骨架), `put_draft_leaf` (填入叶子), `commit_draft_tree` (终极总装落盘) 工具支持。并移除了旧的全量长 JSON 生成工具，强制大模型走流式填充流程。               |
| T-022 | pending | Combo 编排 MCP 暴露           | Combo 编排的查询/保存 MCP 工具。V1 不做，留待后续版本。                                                                                                       |
| T-023 | pending | 用途标签 (PURPOSE_TAG) MCP 暴露 | 用途标签配置的查询/保存 MCP 工具。V1 不做，留待后续版本。                                                                                                         |
| T-024 | done    | 暴露树读取工具与骨架语义增强            | 新增 `get_evaluator_tree_template` 和 `get_evaluator_tree`。并在底层 `LogicNode` 引入全栈一等公民 `nodeName`，满足 UI 与 AI 的双向拓扑语义预览。                        |
| T-025 | done    | 暴露正交组件与配置列表树摘要            | 彻底打通 AI 配置生成闭环。新增 `list_evaluator_trees` 供检索编辑目标；新增极简轻量的 `list_orthogonal_components` 彻底暴露正交算子，并严格实现 `pipeline` 与 `score` 的领域边界隔离防止大模型误用。 |

## 外部依赖同步（orthogonal-condition 已完成）

- [x] D-008: 保留装配与绑定期的 Fail-Fast 防御拦截 —— 影响 U-001 中 validator 的放置决策
- [x] D-012: 强类型参数约束规范 —— 影响 P-001（args 校验参考）
- [x] D-013/D-015: EvaluatorLeafKind 类型隔离 —— 影响 T-008
- [x] D-016: EvalOutcome 三态与守卫剪枝 —— 影响 T-009
- [x] D-017: 评估树替代条件树方向规划 —— 影响 T-004 的后续评估路径
- [x] D-018: 数据源分层供给（Card/ComboCard）+ to_cards 桥接 —— 影响 MCP 暴露的元数据范围
- [x] **T-005**（orthogonal-condition 挂起任务）: AI 生成 MCP Schema 支持（暴露正交组件元数据） —— 直接影响 P-002/T-007
  `(已在 T-007 中完成集成)`

## 备注

- T-002/T-003 需要先讨论边界，再决定是否进入真实编码。
- T-004 已通过 `orthogonal-condition` 主题完成，条件逻辑与正交条件底座已稳定落地。
- `orthogonal-condition` 整体进度已完成，评估树底座稳定，AI 配置生成可按此基础推进。

