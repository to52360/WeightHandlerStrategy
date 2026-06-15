# 任务追踪 - ai-config-generator

## 当前目标

围绕 AI 辅助配置生成建立 MCP 最小可运行链路，并把后续元数据、模板、AI 条件逻辑能力拆成可迭代任务。

## 任务列表

| 编号    | 状态      | 任务                        | 说明                                                                                        |
|-------|---------|---------------------------|-------------------------------------------------------------------------------------------|
| T-001 | done    | 接入 Java MCP SDK           | `configUi` 已接入 `mcp-core + mcp-json-jackson2`，`McpServerMain` 使用 stdio server 注册最小 tools。 |
| T-002 | pending | 元数据描述与分类字段评估              | 判断哪些 AI 引用数据需要 description/category/categoryDesc，条件是否必须分类，规则/条件树/评估树是否也需要分类。              |
| T-003 | pending | 评估树模板与正式数据共表评估            | 评估 `tree_config.is_template` 与正式配置共表是否影响查询、绑定、AI 生成和 UI 操作。                               |
| T-004 | 🎴      | 条件逻辑与设计                   | 需要评估一下是否可行。详见 `2026-06-10-condition-design-analysis-draft.md`。                            |
| T-005 | done    | 记录当前 MCP 流程图与改造清单         | 见 `2026-06-09-flow.md`。                                                                   |
| T-006 | pending | 补齐 evaluator leaf args 校验 | 对应 `P-001`，基于 `EvaluatorLeafSourceCatalog.fields` 校验必填和类型。                                |
| T-007 | pending | 补齐 MCP inputSchema        | 对应 `P-002`，为 AI 提供更精确的 `EvaluatorTreeConfig` JSON Schema。                                 |

## 备注

- T-002/T-003 需要先讨论边界，再决定是否进入真实编码。
- T-004 暂时无法收敛，设计草案已归档。后续可能需要开辟独立专题重新评估。

