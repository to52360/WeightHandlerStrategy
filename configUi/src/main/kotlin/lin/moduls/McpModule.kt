package lin.moduls

import lin.ai.config.AiConfigGenerationService
import lin.ai.config.CardGroupQueryService
import lin.ai.config.DefaultAiConfigGenerationService
import lin.ai.config.DefaultCardGroupQueryService
import lin.di.infraModule
import lin.mcp.AiConfigToolProvider
import lin.mcp.CardGroupToolProvider
import lin.mcp.McpToolProvider
import lin.mcp.MyMcpServer
import lin.ui.service.createTreeConfigMapper
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.module

/** MCP server 及 ToolProvider，仅 MCP 入口加载，不污染 UI 启动 */
val mcpModule = module {

    single<CardGroupQueryService> { DefaultCardGroupQueryService(get()) }
    single<AiConfigGenerationService> { DefaultAiConfigGenerationService(get(), get(), get()) }
    single<McpToolProvider> { AiConfigToolProvider(get<AiConfigGenerationService>(), createTreeConfigMapper()) }
    single<McpToolProvider> { CardGroupToolProvider(get<CardGroupQueryService>(), createTreeConfigMapper()) }
    single { MyMcpServer(getAll<McpToolProvider>()) }
}

/** 仅加载 MCP server 所需的模块（无 JavaFX UI 依赖，含 MCP 组件） */

fun loadMcpModules() {
    startKoin {
        modules(infraModule, serviceModule, dbModule, uiDBModule, mcpModule)
    }
}


