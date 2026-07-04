package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import lin.moduls.loadMcpModules
import org.koin.core.context.GlobalContext

/**
 * configUi 模块下的 MCP 独立入口。
 * 唯一职责：启动 MCP server。
 * 不感知任何 domain 类，不加载 UI 模块。
 */

fun main() {
    loadMcpModules()
    val koin = GlobalContext.get()
    koin.get<MyMcpServer>().start()
}


/**
 * MCP server 装配器。
 * 通过 Koin 获取所有 McpToolProvider，汇聚 tool 列表后注册到 MCP SDK。
 */
class MyMcpServer(
    private val providers: List<McpToolProvider>
) {
    fun start() {
        val tools = providers.flatMap { it.provide() }
        val mcpJsonMapper = JacksonMcpJsonMapper(ObjectMapper())
        val transportProvider = StdioServerTransportProvider(mcpJsonMapper)

        var server = McpServer.sync(transportProvider)
            .serverInfo("deck-plugin-market-config", "0.1.0")
            .instructions("提供卡牌策略配置生成所需的元数据查询、评估树校验和保存工具。")

        tools.forEach { handler ->
            val tool = McpSchema.Tool.builder()
                .name(handler.name)
                .description(handler.description)
                .inputSchema(mcpJsonMapper, handler.inputSchemaJson)
                .build()
            server = server.toolCall(tool) { _, request ->
                val result = handler.call(request.arguments().orEmpty())
                McpSchema.CallToolResult.builder()
                    .addTextContent(result.contentJson)
                    .isError(result.isError)
                    .build()
            }
        }

        server.build()
    }
}
