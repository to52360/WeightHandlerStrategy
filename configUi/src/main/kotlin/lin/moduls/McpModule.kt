package lin.moduls

import lin.ai.config.AiConfigGenerationService
import lin.ai.config.CardGroupQueryService
import lin.ai.config.DefaultAiConfigGenerationService
import lin.ai.config.DefaultCardGroupQueryService
import lin.di.infraModule
import lin.mcp.*
import lin.ui.card_group.db.CardGroupService
import lin.ui.service.createTreeConfigMapper
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.module

/** MCP server 及 ToolProvider，仅 MCP 入口加载，不污染 UI 启动 */
val mcpModule = module {

    single<CardGroupQueryService> { DefaultCardGroupQueryService(get()) }
    single<AiConfigGenerationService> { DefaultAiConfigGenerationService(get(), get(), get(), get<CardGroupService>()) }
    single<lin.ai.config.draft.DraftTreeService> { lin.ai.config.draft.DefaultDraftTreeService(get(), get(), get()) }
    single<McpToolProvider> {
        AiTreeTemplateToolProvider(get(), get(), createTreeConfigMapper())
    }
    single<McpToolProvider> {
        AiTreeConfigToolProvider(get<AiConfigGenerationService>(), get(), createTreeConfigMapper())
    }
    single<McpToolProvider> {
        AiOrthogonalToolProvider(get(), get(), createTreeConfigMapper())
    }
    single<McpToolProvider> {
        CardGroupToolProvider(
            get<CardGroupQueryService>(),
            get<CardGroupService>(),
            createTreeConfigMapper()
        )
    }
    single<McpToolProvider> { TemplateToolProvider(get(), get(), createTreeConfigMapper()) }
    single<McpToolProvider> { lin.mcp.AiDraftTreeToolProvider(get(), createTreeConfigMapper()) }
    single { MyMcpServer(getAll<McpToolProvider>()) }
}

/** 仅加载 MCP server 所需的模块（无 JavaFX UI 依赖，含 MCP 组件） */

fun loadMcpModules() {
    startKoin {
        modules(infraModule, serviceModule, dbModule, uiDBModule, mcpModule)
    }
}


