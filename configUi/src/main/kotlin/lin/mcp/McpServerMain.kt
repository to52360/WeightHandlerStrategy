package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import lin.moduls.loadMcpModules
import org.koin.core.context.GlobalContext
import java.io.PrintStream

/**
 * MCP 专属 stdout 智能过滤流。
 * 自动识别并拦截所有非 JSON-RPC 杂质（如 JVM 警告、Logback/HikariCP 调试日志），将杂质送往 System.err，
 * 仅透传真正的 JSON-RPC 报文至真正的 stdout，确保 stdio 传输通道绝对干净。
 */
private class McpStdoutFilterStream(
    private val realStdout: PrintStream,
    private val errStream: PrintStream
) : PrintStream(realStdout, true, "UTF-8") {

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val str = String(b, off, len, Charsets.UTF_8).trimStart()
        if (str.startsWith("{") || str.startsWith("[")) {
            realStdout.write(b, off, len)
            realStdout.flush()
        } else {
            errStream.write(b, off, len)
            errStream.flush()
        }
    }
}

/**
 * configUi 模块下的 MCP 独立入口。
 * 唯一职责：启动 MCP server。
 */
fun main() {
    val realStdout = System.out
    val realStderr = System.err

    // 安装防污染拦截器
    System.setOut(McpStdoutFilterStream(realStdout, realStderr))

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
