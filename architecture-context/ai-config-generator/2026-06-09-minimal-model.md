# 最小可验证模型 - ai-config-generator

## 创建日期: 2026-06-09

## 骨架文件清单

| 文件                                                                           | 用途                          | 标记数          |
|------------------------------------------------------------------------------|-----------------------------|--------------|
| `configUi/src/main/kotlin/lin/mcp/McpServerMain.kt`                          | MCP 独立入口与 Java SDK stdio 适配 | 无            |
| `configUi/src/main/kotlin/lin/mcp/McpToolRouter.kt`                          | MCP tool 到业务服务的最小路由         | P-002        |
| `configUi/src/main/kotlin/lin/mcp/McpModels.kt`                              | 项目内 tool 抽象，隔离 MCP SDK 类型   | 无            |
| `configUi/src/main/kotlin/lin/ai/config/AiConfigGenerationModels.kt`         | AI 配置生成业务输入输出模型             | 无            |
| `configUi/src/main/kotlin/lin/ai/config/DefaultAiConfigGenerationService.kt` | 元数据查询、校验、保存的最小业务链路          | U-001, P-001 |

## 方向总览

MCP 作为 `configUi` 模块的独立入口启动，复用 `ModelsDefine().loadModules()` 获得配置端的 Koin、SQLite、Jackson 和元数据能力。
`lin.mcp` 只做协议适配和 tool 路由，不直接写数据库；`lin.ai.config` 承接 AI 配置生成业务，先提供
`list_evaluator_leaf_sources`、`validate_evaluator_tree`、`save_evaluator_tree` 三个最小 tool。验证分层暂定为：作者侧结构校验先在
`configUi` 门面落地，运行契约校验是否下沉到 `WeightHanderStrategy` 独立 validator 仍保留为待确认项。

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

- [ ] U-001: 运行契约校验是否抽到 `WeightHanderStrategy`，以及 validator 需要依赖哪些 registry/provider。
- [ ] P-001: `EvaluatorLeafConfig.args` 与 `RuleFieldSpec` 的类型/必填校验尚未展开。
- [ ] P-002: MCP `inputSchema` 暂用宽松 object，后续需要给 AI 更精确的 `EvaluatorTreeConfig` JSON Schema。
- [x] P-003: Java MCP SDK 具体 Maven artifact/version 和 `StdioServerTransportProvider + McpServer.sync`
  代码已落地，后续只需被全量编译验证。
