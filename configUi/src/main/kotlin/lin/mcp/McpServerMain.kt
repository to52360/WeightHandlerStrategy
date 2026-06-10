package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import lin.ai.config.DefaultAiConfigGenerationService
import lin.moduls.ModelsDefine
import lin.tree_config.db.EvaluatorLeafSourceCatalog
import lin.ui.service.TreeConfigService
import lin.ui.service.createTreeConfigMapper
import org.koin.core.context.GlobalContext

/**
 * configUi 模块下的 MCP 独立入口。
 * IDE 启动 MCP 进程后，本入口加载与配置端一致的 Koin 容器，再把 tools 暴露给 MCP SDK。
 */
object McpServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        ModelsDefine().loadModules()

        val koin = GlobalContext.get()
        val service = DefaultAiConfigGenerationService(
            leafSourceCatalog = koin.get<EvaluatorLeafSourceCatalog>(),
            treeConfigService = koin.get<TreeConfigService>()
        )

        val router = McpToolRouter(
            aiConfigGenerationService = service,
            mapper = createTreeConfigMapper()
        )
        JavaSdkStdioMcpAdapter(router).start()
    }
}

class JavaSdkStdioMcpAdapter(
    private val router: McpToolRouter
) {
    fun start() {
        val mcpJsonMapper = JacksonMcpJsonMapper(ObjectMapper())
        val transportProvider = StdioServerTransportProvider(mcpJsonMapper)

        var server = McpServer.sync(transportProvider)
            .serverInfo("deck-plugin-market-config", "0.1.0")
            .instructions("提供卡牌策略配置生成所需的元数据查询、评估树校验和保存工具。")

        router.tools().forEach { toolHandler ->
            val tool = McpSchema.Tool.builder()
                .name(toolHandler.name)
                .description(toolHandler.description)
                .inputSchema(mcpJsonMapper, toolHandler.inputSchemaJson)
                .build()
            server = server.toolCall(tool) { _, request ->
                val result = toolHandler.call(request.arguments().orEmpty())
                McpSchema.CallToolResult.builder()
                    .addTextContent(result.contentJson)
                    .isError(result.isError)
                    .build()
            }
        }

        server.build()
    }
}
