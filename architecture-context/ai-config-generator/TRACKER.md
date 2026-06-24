# 任务追踪 - ai-config-generator

## 当前目标

围绕 AI 辅助配置生成建立 MCP 最小可运行链路，并把后续元数据、模板、AI 条件逻辑能力拆成可迭代任务。

## 任务列表

| 编号    | 状态      | 任务                        | 说明                                                                                                                                                                                                |
|-------|---------|---------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| T-001 | done    | 接入 Java MCP SDK           | `configUi` 已接入 `mcp-core + mcp-json-jackson2`，`McpServerMain` 使用 stdio server 注册最小 tools。                                                                                                         |
| T-002 | pending | 元数据描述与分类字段评估              | 判断哪些 AI 引用数据需要 description/category/categoryDesc，条件是否必须分类，规则/条件树/评估树是否也需要分类。                                                                                                                      |
| T-003 | pending | 评估树模板与正式数据共表评估            | 评估 `tree_config.is_template` 与正式配置共表是否影响查询、绑定、AI 生成和 UI 操作。                                                                                                                                       |
| T-004 | done    | 条件逻辑与设计                   | 已通过 `orthogonal-condition` 主题完成。详见 `2026-06-10-condition-design-analysis-draft.md`，演进为 orthogonal-depth condition 设计。                                                                             |
| T-005 | done    | 记录当前 MCP 流程图与改造清单         | 见 `2026-06-09-flow.md`。                                                                                                                                                                           |
| T-006 | done    | 补齐 evaluator leaf args 校验 | 对应 `P-001`，`SpecValidator.validate(leafConfig.args, allFields)` 已在 `validateLeafConfig()` 中调用，完成类型/必填/约束校验。                                                                                       |
| T-007 | pending | 补齐 MCP inputSchema        | 对应 `P-002`，为 AI 提供更精确的 `EvaluatorTreeConfig` JSON Schema。需同步暴露正交组件元数据（`orthogonal-condition` T-005）。                                                                                              |
| T-008 | done    | 同步 EvaluatorLeafKind 重命名  | `list_evaluator_leaf_sources`→`list_evaluator_leaf_kinds`，`AiEvaluatorLeafSource`→`AiEvaluatorLeafKind`，已同步 `McpToolRouter` / `AiConfigGenerationModels` / `DefaultAiConfigGenerationService` 三处。 |
| T-009 | done    | Guard+ScoreEffect 校验      | 对应 `P-005`，`EvaluatorTreeValidator` 已追加 Kind-ScoreEffect 相容性、PipelineRef 非空及引用完整性、SourceScore 非空及注册表校验、PRUNE-warn。`PipelineAssembler` + `ScoreOperatorRegistry` 已注入 validator。                    |

## 外部依赖同步（orthogonal-condition 已完成）

- [x] D-008: 保留装配与绑定期的 Fail-Fast 防御拦截 —— 影响 U-001 中 validator 的放置决策
- [x] D-012: 强类型参数约束规范 —— 影响 P-001（args 校验参考）
- [x] D-013/D-015: EvaluatorLeafKind 类型隔离 —— 影响 T-008
- [x] D-016: EvalOutcome 三态与守卫剪枝 —— 影响 T-009
- [x] D-017: 评估树替代条件树方向规划 —— 影响 T-004 的后续评估路径
- [x] D-018: 数据源分层供给（Card/ComboCard）+ to_cards 桥接 —— 影响 MCP 暴露的元数据范围
- [ ] **T-005**（orthogonal-condition 挂起任务）: AI 生成 MCP Schema 支持（暴露正交组件元数据） —— 直接影响 P-002/T-007
  `(批注:理清前面内容先)`

## 备注

- T-002/T-003 需要先讨论边界，再决定是否进入真实编码。
- T-004 已通过 `orthogonal-condition` 主题完成，条件逻辑与正交条件底座已稳定落地。
- `orthogonal-condition` 整体进度已完成，评估树底座稳定，AI 配置生成可按此基础推进。

