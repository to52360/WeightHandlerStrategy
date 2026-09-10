package lin.moduls

import lin.ai.config.AiConfigGenerationService
import lin.ai.config.CardGroupQueryService
import lin.ai.config.DefaultAiConfigGenerationService
import lin.ai.config.DefaultCardGroupQueryService
import lin.ai.config.draft.DefaultDraftTreeService
import lin.ai.config.draft.DraftTreeService
import lin.di.infraModule
import lin.mcp.*
import lin.mcp.action.DeleteDispatcher
import lin.mcp.action.GetDispatcher
import lin.mcp.action.ListDispatcher
import lin.mcp.action.ToolCapabilitiesProvider
import lin.mcp.card_group.CardGroupToolProvider
import lin.mcp.card_group.CardPoolToolProvider
import lin.mcp.card_group.SaveCardGroupToolProvider
import lin.mcp.combo_plan.ComboPlanToolProvider
import lin.repository.HsCardRepository
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.card_group.CardGroupService
import lin.repository.card_purpose.CardPurposeRepository
import lin.repository.card_purpose.PurposeTagDefRepository
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.delete_snapshot.DeleteSnapshotService
import lin.repository.tree_config.EvaluatorLeafConfigRepository
import lin.ui.service.CardGroupCascadeDeleteService
import lin.ui.service.TreeConfigService
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
    single<DraftTreeService> {
        DefaultDraftTreeService(
            get(),
            get(),
            get(),
            get(),
            get<ConditionTreeConfigService>()
        )
    }

    // ── 资源域 Provider（写工具 + 动作同文件，通过 McpToolProvider.actions 声明资源动作）──
    // 注意：Koin 主类型必须用具体实现类，且只 bind McpToolProvider::class（接口主类型 bind 会同名覆盖，
    // getAll 只拿到最后一个，2026-08-12 实测坑）。动作收集由 dispatcher 从 getAll<McpToolProvider>()
    // 的 actions 汇总（Q-007 已实施：无独立 ResourceActionProvider 接口）。
    single { AiTreeTemplateToolProvider(get(), get()) } bind McpToolProvider::class
    single { AiOrthogonalToolProvider(get(), get()) } bind McpToolProvider::class
    single {
        CardGroupToolProvider(
            get<CardGroupService>(),
            get<CardGroupCascadeDeleteService>()
        )
    } bind McpToolProvider::class
    single {
        SaveCardGroupToolProvider(
            get<CardGroupService>(),
            get<ConditionTreeConfigService>()
        )
    } bind McpToolProvider::class
    single {
        CardPoolToolProvider(
            get<CardGroupQueryService>(),
            get<CardGroupService>(),
            get<HsCardRepository>(),
            get<DeleteSnapshotService>()
        )
    } bind McpToolProvider::class
    single { TemplateToolProvider(get(), get()) } bind McpToolProvider::class
    single {
        ConditionTreeToolProvider(
            get<ConditionTreeConfigService>(),
            get<AuraBoostConfigService>(),
            get<EvaluatorLeafConfigRepository>(),
            get<DeleteSnapshotService>()
        )
    } bind McpToolProvider::class
    single {
        AuraBoostToolProvider(
            get<AuraBoostConfigService>(),
            get<DeleteSnapshotService>()
        )
    } bind McpToolProvider::class
    single {
        AiDraftTreeToolProvider(get(), get<CardGroupService>())
    } bind McpToolProvider::class
    single {
        ComboPlanToolProvider(
            get(),
            get<CardGroupService>(),
            get<DeleteSnapshotService>()
        )
    } bind McpToolProvider::class
    single {
        AiTreeConfigToolProvider(
            get<TreeConfigService>(),
            get<ComboPlanDefinitionRepository>(),
            get<AiConfigGenerationService>(),
            get<DeleteSnapshotService>()
        )
    } bind McpToolProvider::class
    single {
        PurposeTagToolProvider(
            get(),
            get(),
            get<CardPurposeRepository>(),
            get<TreeConfigService>(),
            get<PurposeTagDefRepository>(),
            get<DeleteSnapshotService>()
        )
    } bind McpToolProvider::class
    single {
        StrategyCoverageToolProvider(
            get<CardGroupService>(),
            get<TreeConfigService>(),
            get<ComboPlanDefinitionRepository>(),
            get<AuraBoostConfigService>(),
            get<ConditionTreeConfigService>(),
            get<CardPurposeRepository>()
        )
    } bind McpToolProvider::class
    single {
        RestoreSnapshotToolProvider(get<DeleteSnapshotService>())
    } bind McpToolProvider::class

    // ── 动作大类 dispatcher（遍历全部 McpToolProvider 集合的 actions，按 resource 分发）──
    // Lazy 注入避开循环依赖：getAll<McpToolProvider>() 含 dispatcher 自身，构造期解析会 StackOverflow。
    single {
        GetDispatcher(lazy { getAll<McpToolProvider>() })
    } bind McpToolProvider::class
    single {
        ListDispatcher(lazy { getAll<McpToolProvider>() })
    } bind McpToolProvider::class
    single {
        DeleteDispatcher(lazy { getAll<McpToolProvider>() })
    } bind McpToolProvider::class
    single {
        ToolCapabilitiesProvider(lazy { getAll<McpToolProvider>() })
    } bind McpToolProvider::class

    single { MyMcpServer(getAll<McpToolProvider>()) }
}

/** 仅加载 MCP server 所需的模块（无 JavaFX UI 依赖，含 MCP 组件） */

fun loadMcpModules() {
    startKoin {
        modules(infraModule, serviceModule, dbModule, uiDBModule, mcpModule)
    }
}
