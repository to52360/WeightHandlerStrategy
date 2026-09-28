package lin.moduls

import lin.ai.config.AiConfigGenerationService
import lin.ai.config.CardGroupQueryService
import lin.ai.config.DefaultAiConfigGenerationService
import lin.ai.config.DefaultCardGroupQueryService
import lin.ai.config.draft.DefaultDraftTreeService
import lin.ai.config.draft.DraftTreeService
import lin.di.infraModule
import lin.mcp.*
import lin.mcp.action.*
import lin.mcp.card_group.CardGroupToolProvider
import lin.mcp.card_group.CardPoolToolProvider
import lin.mcp.card_group.SaveCardGroupToolProvider
import lin.mcp.card_group.StrategyPresetToolProvider
import lin.mcp.combo_plan.ComboPlanToolProvider
import lin.repository.HsCardRepository
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.card_group.CardGroupCascadeDeleteService
import lin.repository.card_group.CardGroupService
import lin.repository.card_group.DimensionItemResolver
import lin.repository.card_group.StrategyPresetService
import lin.repository.card_group.SurplusGateValidator
import lin.repository.card_purpose.CardPurposeRepository
import lin.repository.card_purpose.PurposeTagDefRepository
import lin.repository.card_purpose.PurposeTagRuleRepository
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider
import lin.repository.card_purpose.PurposeTagService
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.repository.combo_plan.ComboPlanService
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.delete_snapshot.SnapshotStore
import lin.repository.tree_config.EvaluatorLeafConfigRepository
import lin.repository.tree_config.EvaluatorTreeTemplateRepository
import lin.repository.tree_config.TreeConfigRepository
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.service.EvaluatorTreeTemplateService
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
    single {
        AiTreeTemplateToolProvider(
            get<EvaluatorTreeTemplateRepository>(),
            get<EvaluatorTreeTemplateService>()
        )
    } bind McpToolProvider::class
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
            get<ConditionTreeConfigService>(),
            get<SurplusGateValidator>()
        )
    } bind McpToolProvider::class
    single {
        CardPoolToolProvider(
            get<HsCardRepository>(),
            get<CardGroupQueryService>(),
            get<CardGroupService>(),
            get<SurplusGateValidator>()
        )
    } bind McpToolProvider::class
    single { TemplateToolProvider(get(), get()) } bind McpToolProvider::class
    single {
        ConditionTreeToolProvider(
            get<ConditionTreeConfigService>(),
            get<AuraBoostConfigService>(),
            get<EvaluatorLeafConfigRepository>()
        )
    } bind McpToolProvider::class
    single {
        AuraBoostToolProvider(
            get<AuraBoostConfigService>(),
            // D-DP-002：删除悬空守卫（扫描 AURA_BOOST 维度项）
            get<lin.repository.card_group.StrategyPresetRepository>()
        )
    } bind McpToolProvider::class
    single {
        AiDraftTreeToolProvider(
            get<DraftTreeService>(),
            get<CardGroupService>()
        )
    } bind McpToolProvider::class
    single {
        ComboPlanToolProvider(
            get<ComboPlanDefinitionRepository>(),
            get<CardGroupService>(),
            get<HsCardRepository>(),
            get<TreeConfigService>(),
            get<ComboPlanService>()
        )
    } bind McpToolProvider::class
    single {
        StrategyPresetToolProvider(
            get<StrategyPresetService>(),
            get<TreeConfigRepository>(),
            get<DimensionItemResolver>(),
            get<TreeConfigService>(),
            // D-DP-004：可声明作用候选单点门面（内置常量 ∪ 库中晋级行）
            get<PurposeTagDefRepository>(),
            // D-DP-001：光环行引用校验（只能引用存在且 manager_id IS NULL 的行）
            get<lin.repository.aura_boost.AuraBoostConfigService>()
        )
    } bind McpToolProvider::class
    single {
        AiTreeConfigToolProvider(
            get<TreeConfigService>(),
            get<ComboPlanDefinitionRepository>(),
            get<AiConfigGenerationService>(),
            get<CardGroupService>()
        )
    } bind McpToolProvider::class
    single {
        PurposeTagToolProvider(
            get<PurposeTagProvider>(),
            get<CardPurposeRepository>(),
            get<PurposeTagDefRepository>(),
            get<PurposeTagRuleRepository>(),
            get<TreeConfigService>(),
            get<PurposeTagService>()
        )
    } bind McpToolProvider::class
    // 只读配置快照装配器（单点）：strategy_coverage / strategy_diagnostics 共享同一份读取路径
    single {
        ConfigSnapshotAssembler(
            get<CardGroupService>(),
            get<TreeConfigService>(),
            get<ComboPlanDefinitionRepository>(),
            get<AuraBoostConfigService>(),
            get<ConditionTreeConfigService>(),
            get<CardPurposeRepository>(),
            get<PurposeTagIntentRuleProvider>()
        )
    }
    single {
        StrategyCoverageToolProvider(get<ConfigSnapshotAssembler>())
    } bind McpToolProvider::class
    // T-FO-019（Q-FO-004 层 1）：配置体检（只读、只提示）——「机制对不对」，与 coverage（「有没有机制」）互补
    single {
        StrategyDiagnosticsToolProvider(
            get<ConfigSnapshotAssembler>(),
            get<PurposeTagIntentRuleProvider>(),
            get<HsCardRepository>()
        )
    } bind McpToolProvider::class
    // 动作索引单例：get / list / delete / restore 四条链路共用（索引只建一次；lazy 取 providers 避循环依赖）
    single { ActionRegistry(lazy { getAll<McpToolProvider>() }) }

    single {
        RestoreSnapshotToolProvider(get<SnapshotStore>(), get<ActionRegistry>())
    } bind McpToolProvider::class

    // ── 动作大类 dispatcher（共用同一个 ActionRegistry 单例，索引只建一次）──
    single {
        GetDispatcher(get<ActionRegistry>())
    } bind McpToolProvider::class
    single {
        ListDispatcher(get<ActionRegistry>())
    } bind McpToolProvider::class
    single {
        // T-TG-022（J′）：delete 的落快照/删除编排单点在此 dispatcher，各 Provider 只声明 deleteOps() 值
        DeleteDispatcher(get<ActionRegistry>(), get<SnapshotStore>())
    } bind McpToolProvider::class
    single {
        ToolCapabilitiesProvider(get<ActionRegistry>())
    } bind McpToolProvider::class

    single { MyMcpServer(getAll<McpToolProvider>()) }
}

/** 仅加载 MCP server 所需的模块（无 JavaFX UI 依赖，含 MCP 组件） */

fun loadMcpModules() {
    startKoin {
        modules(infraModule, serviceModule, dbModule, uiDBModule, mcpModule)
    }
}
