package lin.mcp

/**
 * MCP tool 的纯数据结构。
 * 只有数据（name/description/schema）+ 一个 call 函数，没有抽象方法。
 * 具体 tool 用 data class 构造，不需要每个 tool 一个 class。
 */
data class McpToolHandler(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val call: (Map<String, Any?>) -> McpToolResult
)

data class McpToolResult(
    val contentJson: String,
    val isError: Boolean = false
)

/**
 * MCP tool 提供者接口。
 * 每个 domain 实现此接口，提供本域的 tool 列表。
 */
interface McpToolProvider {
    fun provide(): List<McpToolHandler>
}
