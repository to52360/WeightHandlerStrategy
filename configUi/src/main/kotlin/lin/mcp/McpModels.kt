package lin.mcp

/**
 * MCP tool 的项目内最小抽象。
 * 真实 SDK 对接只在 adapter 层处理，业务代码不依赖 MCP request/response 类型。
 */
interface McpToolHandler {
    val name: String
    val description: String
    val inputSchemaJson: String

    fun call(arguments: Map<String, Any?>): McpToolResult
}

data class McpToolResult(
    val contentJson: String,
    val isError: Boolean = false
)
