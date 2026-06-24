# 最小可验证模型 - ai-config-generator

## 创建日期: 2026-06-09 | 最近同步: 2026-06-23

## 骨架文件清单

| 文件                                                                              | 用途                                                                                  | 标记数   |
|---------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|-------|
| `configUi/src/main/kotlin/lin/mcp/McpServerMain.kt`                             | MCP 独立入口与 Java SDK stdio 适配                                                         | 无     |
| `configUi/src/main/kotlin/lin/mcp/McpToolRouter.kt`                             | MCP tool 到业务服务的最小路由                                                                 | P-002 |
| `configUi/src/main/kotlin/lin/mcp/McpModels.kt`                                 | 项目内 tool 抽象，隔离 MCP SDK 类型                                                           | 无     |
| `configUi/src/main/kotlin/lin/ai/config/AiConfigGenerationModels.kt`            | AI 配置生成业务输入输出模型                                                                     | 无     |
| `configUi/src/main/kotlin/lin/ai/config/DefaultAiConfigGenerationService.kt`    | 元数据查询、校验、保存的最小业务链路                                                                  | 无     |
| `configUi/src/main/kotlin/lin/tree_config/validation/EvaluatorTreeValidator.kt` | UI 和 MCP 共用评估树验证器，收敛两条保存路径。结构级校验（Kind-ScoreEffect 相容性、引用非空），注册表级存在性由引擎 Fail-Fast 保障 | 无     |

## 方向总览

MCP 作为 `configUi` 模块的独立入口启动，复用 `ModelsDefine().loadModules()` 获得配置端的 Koin、SQLite、Jackson 和元数据能力。
`lin.mcp` 只做协议适配和 tool 路由，不直接写数据库；`lin.ai.config` 承接 AI 配置生成业务，先提供
`list_evaluator_leaf_kinds`、`validate_evaluator_tree`、`save_evaluator_tree` 三个最小 tool。

**底层依赖已稳定化**：`orthogonal-condition` 主题已完成（整体状态 ✅ 已完成），评估树底座已收敛为 **Guard (守卫条件) +
ScoreEffect (评分效应) + EvalOutcome 三态** 模型：

- 数据源分层：Card 级（`hand_cards`/`me_board_cards`/`rival_board_cards`/`board_cards`）与 ComboCard 级（`hand_combo_cards`/
  `me_combo_cards`），新增 `to_cards` Transform 桥接
- 叶子类型系统：已通过 D-013/D-015 完成 `EvaluatorLeafSourceType`→`EvaluatorLeafKind` 类型化隔离，语义收敛为 4 种 Kind
- 长期规划：D-017 确立了用评估树叶子（Guard + ScoreEffect）逐步替代条件树模块的方向

验证分层：`SpecValidator` 已在引擎侧落地 `FieldSpec` 类型/必填/约束校验。评估树作者侧验证已收敛为
`EvaluatorTreeValidator`（`lin.tree_config.validation`），UI `SaveTreeAction` 和 MCP `DefaultAiConfigGenerationService`
共用同一套验证入口。验证内容：root 引用完整性、leafConfig 来源合法性、args 契约校验。Guard+ScoreEffect 相容性校验留待 T-009
追加。

## MCP Java SDK 真实接入形状

当前模型已接入 Java MCP SDK `mcp-core + mcp-json-jackson2`，stdio server 的真实接入点是：

```kotlin
val mcpJsonMapper = JacksonMcpJsonMapper(ObjectMapper())
val transportProvider = StdioServerTransportProvider(mcpJsonMapper)
val server = McpServer.sync(transportProvider)
    .serverInfo("deck-plugin-market-config", "0.1.0")
    .toolCall(...)
    .build()
```

tool handler 的真实职责应当只是：

```kotlin
val result = routerTool.call(request.arguments())
CallToolResult(listOf(TextContent(result.contentJson)), result.isError)
```

参考来源：

- [Model Context Protocol Java SDK - Server](https://java.sdk.modelcontextprotocol.io/latest/server/)
- [Model Context Protocol Java SDK - Dependencies](https://java.sdk.modelcontextprotocol.io/latest/getting-started/mcp-dependencies/)

## 待确认项

- [ ] U-001: ~~运行契约校验是否抽到 `WeightHanderStrategy`~~ 已确认——`SpecValidator` 已在引擎侧落地 `FieldSpec`
  类型/必填/约束校验，且 `DefaultAiConfigGenerationService.validateLeafConfig()` 已调用。剩余子问题：validator 需要依赖哪些
  registry/provider（如 `ConditionAssembler`/`EvaluatorTreeInstance` 的现有校验逻辑复用方式），留待 T-009 处理。
- [x] P-001: `EvaluatorLeafConfig.args` 的类型/必填校验已落地——`DefaultAiConfigGenerationService.validateLeafConfig()`
  中已调用 `SpecValidator.validate(leafConfig.args, allFields)` 完成校验。
- [ ] P-002: MCP `inputSchema` 暂用宽松 object，后续需要给 AI 更精确的 `EvaluatorTreeConfig` JSON Schema。
  `orthogonal-condition` 的 T-005（`批注:理清前面内容先`）明确了需要暴露正交组件元数据供 MCP 精确校验，建议在实现 P-002
  时一并考虑从正交组件（DataSource/Operator/ScoreEffect）的 SPI 元数据中动态生成 Schema。
- [x] P-003: Java MCP SDK 具体 Maven artifact/version 和 `StdioServerTransportProvider + McpServer.sync`
  代码已落地，后续只需被全量编译验证。
- [x] P-004: `list_evaluator_leaf_sources`→`list_evaluator_leaf_kinds` 重命名已完成（`McpToolRouter` /
  `AiConfigGenerationModels` / `DefaultAiConfigGenerationService` 三处同步更新）。
- [x] P-005: Guard + ScoreEffect 校验已落地 `EvaluatorTreeValidator`：CONDITION 叶子限定 ConstantScore、PipelineRef
  非空+引用完整性、SourceScore 非空+注册表校验、PRUNE 软提示。
