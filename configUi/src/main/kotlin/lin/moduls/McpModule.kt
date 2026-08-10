package lin.moduls

import lin.ai.config.AiConfigGenerationService
import lin.ai.config.CardGroupQueryService
import lin.ai.config.DefaultAiConfigGenerationService
import lin.ai.config.DefaultCardGroupQueryService
import lin.di.infraModule
import lin.mcp.*
import lin.repository.card_group.CardGroupService
import lin.repository.condition_tree.ConditionTreeConfigService
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module

/** MCP server 及 ToolProvider，仅 MCP 入口加载，不污染 UI 启动 */
val mcpModule = module {

    single<CardGroupQueryService> { DefaultCardGroupQueryService(get()) }
    single<AiConfigGenerationService> {
        DefaultAiConfigGenerationService(
            get(),
            get(),
            get(),
            get<CardGroupService>(),
            get(),
            get<ConditionTreeConfigService>()
        )
    }
    single<lin.ai.config.draft.DraftTreeService> {
        lin.ai.config.draft.DefaultDraftTreeService(
            get(),
            get(),
            get(),
            get(),
            get<ConditionTreeConfigService>()
        )
    }

    // 多个 McpToolProvider 必须 bind，否则 single<T> 同名覆盖，getAll 只能拿到最后一个
    single { AiTreeTemplateToolProvider(get(), get()) } bind McpToolProvider::class
    single {
        AiTreeConfigToolProvider(
            get<AiConfigGenerationService>(),
            get(),
            get()
        )
    } bind McpToolProvider::class
    single { AiOrthogonalToolProvider(get(), get()) } bind McpToolProvider::class
    single {
        lin.mcp.card_group.CardGroupToolProvider(
            get<CardGroupService>(),
            get<lin.ui.service.TreeConfigService>()
        )
    } bind McpToolProvider::class
    single {
        lin.mcp.card_group.SaveCardGroupToolProvider(
            get<CardGroupService>(),
            get<ConditionTreeConfigService>()
        )
    } bind McpToolProvider::class
    single {
        lin.mcp.card_group.CardPoolToolProvider(
            get<CardGroupQueryService>(),
            get<CardGroupService>(),
            get<lin.repository.HsCardRepository>()
        )
    } bind McpToolProvider::class
    single { TemplateToolProvider(get(), get()) } bind McpToolProvider::class
    single {
        ConditionTreeToolProvider(
            get<ConditionTreeConfigService>(),
            get<lin.repository.aura_boost.AuraBoostConfigService>(),
            get<lin.repository.tree_config.EvaluatorLeafConfigRepository>()
        )
    } bind McpToolProvider::class
    single {
        AuraBoostToolProvider(
            get<lin.repository.aura_boost.AuraBoostConfigService>(),
            get<ConditionTreeConfigService>()
        )
    } bind McpToolProvider::class
    single { lin.mcp.AiDraftTreeToolProvider(get(), get<CardGroupService>()) } bind McpToolProvider::class
    single {
        ComboPlanToolProvider(
            get(),
            get<CardGroupService>(),
            get<lin.repository.HsCardRepository>(),
            get<lin.ui.service.TreeConfigService>()
        )
    } bind McpToolProvider::class
    single {
        lin.mcp.ComboPlanManagementToolProvider(
            get(),
            get<CardGroupService>()
        )
    } bind McpToolProvider::class
    single {
        PurposeTagToolProvider(
            get(),
            get(),
            get<lin.repository.card_purpose.CardPurposeRepository>(),
            get<lin.ui.service.TreeConfigService>()
        )
    } bind McpToolProvider::class
    single {
        StrategyCoverageToolProvider(
            get<CardGroupService>(),
            get<lin.ui.service.TreeConfigService>(),
            get<lin.repository.combo_plan.ComboPlanDefinitionRepository>(),
            get<lin.repository.aura_boost.AuraBoostConfigService>(),
            get<ConditionTreeConfigService>(),
            get<lin.repository.card_purpose.CardPurposeRepository>()
        )
    } bind McpToolProvider::class

    single { MyMcpServer(getAll<McpToolProvider>()) }
}

/** 仅加载 MCP server 所需的模块（无 JavaFX UI 依赖，含 MCP 组件） */

fun loadMcpModules() {
    startKoin {
        modules(infraModule, serviceModule, dbModule, uiDBModule, mcpModule)
    }
}


