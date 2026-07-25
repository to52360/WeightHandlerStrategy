package lin.moduls

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import lin.bean.usePlan.DefaultPurposeTagIntentRuleProvider
import lin.bean.usePlan.PurposeTagIntentRuleProvider
import lin.config.PathConfig
import lin.di.infraModule
import lin.repository.HsCardRepository
import lin.repository.OrthogonalTemplateRepository
import lin.repository.TemplateGroupRepository
import lin.repository.card_group.CardGroupBehaviorRepository
import lin.repository.card_group.CardGroupRepository
import lin.repository.card_group.CardGroupService
import lin.repository.card_purpose.CardPurposeRepository
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.repository.condition_tree.ConditionTreeConfigRepository
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.createConditionTreeConfigMapper
import lin.repository.tree_config.EvaluatorLeafConfigRepository
import lin.repository.tree_config.EvaluatorLeafSourceCatalog
import lin.repository.tree_config.EvaluatorTreeTemplateRepository
import lin.repository.tree_config.TreeConfigRepository
import lin.ui.SelectOptionRegistry
import lin.ui.UiExtension
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.card_group.CardGroupExtension
import lin.ui.card_purpose.CardPurposeExtension
import lin.ui.card_purpose.DefaultPurposeTagProvider
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.card_purpose.PurposeTagTreeBindingPolicy
import lin.ui.combo_plan.ComboPlanExtension
import lin.ui.condition_tree.ConditionTreeExtension
import lin.ui.condition_tree.action.ConditionTreeWorkbenchAction
import lin.ui.condition_tree.action.CreateConditionTreeAction
import lin.ui.condition_tree.action.DeleteConditionTreeAction
import lin.ui.condition_tree.action.SaveConditionTreeAction
import lin.ui.service.EvaluatorTreeResolver
import lin.ui.service.EvaluatorTreeTemplateService
import lin.ui.service.TreeConfigService
import lin.ui.service.createTreeConfigMapper
import lin.ui.tree_config.EvaluatorTreeExtension
import lin.ui.tree_config.action.*
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files


/**
 * 业务服务模块——不依赖 JavaFX，MCP server 也需要加载。
 * SPI 基础设施（RuleRegistry/PipelineAssembler/ConditionRegistry/ScoreOperatorRegistry）
 * 已提取至 WeightHandlerStrategy 的 [infraModule]，避免与 [ruleModule] 重复。
 */
val serviceModule = module {

    single { SelectOptionRegistry() }

    // 用途标签目录与显示
    single<PurposeTagProvider> { DefaultPurposeTagProvider() }
    single<PurposeTagIntentRuleProvider> { DefaultPurposeTagIntentRuleProvider() }
    single { PurposeTagTreeBindingPolicy(get()) }

    // 全局卡组选择状态
    single { ActiveManagerHolder() }

}

val uiModule = module {

    // UI 扩展注册
    single { CardGroupExtension() } bind UiExtension::class
    single { EvaluatorTreeExtension() } bind UiExtension::class
    single { ConditionTreeExtension() } bind UiExtension::class
    single { CardPurposeExtension() } bind UiExtension::class
    single { ComboPlanExtension() } bind UiExtension::class

    // 评估树工作台动作注册
    single { CreateNewTreeAction() } bind TreeWorkbenchAction::class
    single { CreateFromTemplateAction() } bind TreeWorkbenchAction::class
    single { EditTreePropertiesAction() } bind TreeWorkbenchAction::class
    single { SaveTreeAction() } bind TreeWorkbenchAction::class
    single { SaveAsTemplateAction() } bind TreeWorkbenchAction::class
    single { DeleteTreeAction() } bind TreeWorkbenchAction::class

    // 条件树工作台动作注册
    single { CreateConditionTreeAction() } bind ConditionTreeWorkbenchAction::class
    single { SaveConditionTreeAction() } bind ConditionTreeWorkbenchAction::class
    single { DeleteConditionTreeAction() } bind ConditionTreeWorkbenchAction::class

}

/**
 * 数据库基础模块，直接创建 HikariCP 连接池与 [JdbcTemplate]。
 * configUi 交互频繁，与引擎侧的一次性加载模式不同，需要连接复用以降低开销。
 */
val dbModule = module {
    single<JdbcTemplate> {
        val dbPath = PathConfig.databasePath
        if (!Files.exists(dbPath)) {
            Files.createFile(dbPath)
        }
        val config = HikariConfig().apply {
            driverClassName = "org.sqlite.JDBC"
            jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
            maximumPoolSize = 2
            minimumIdle = 0
            idleTimeout = 30_000
            connectionTimeout = 10_000
            connectionTestQuery = "SELECT 1"
            connectionInitSql = "ATTACH DATABASE '${PathConfig.hsCardsDbPath.toAbsolutePath()}' AS hs"
            poolName = "ConfigUiPool"
        }
        JdbcTemplate(HikariDataSource(config))
    }

}
val uiDBModule = module {
    single { TreeConfigRepository(get()) }
    single { EvaluatorTreeTemplateRepository(get()) }
    single { EvaluatorLeafConfigRepository(get()) }
    single { TreeConfigService(get(), get(), createTreeConfigMapper()) }
    single { EvaluatorTreeTemplateService(get(), get(), createTreeConfigMapper()) }
    single { EvaluatorTreeResolver(get(), get()) }
    single { ConditionTreeConfigRepository(get()) }
    single {
        ConditionTreeConfigService(
            get(),
            createConditionTreeConfigMapper()
        )
    }
    single { EvaluatorLeafSourceCatalog(get(), get(), get()) }
    single { CardGroupBehaviorRepository(get()) }
    single { CardGroupRepository(get(), get()) }
    single { CardGroupService(get()) }
    single { CardPurposeRepository(get()) }
    single { HsCardRepository(get()) }

    single { ComboPlanDefinitionRepository(get()) }
    single { TemplateGroupRepository(get()) }
    single { OrthogonalTemplateRepository(get()) }
}


/** 加载完整模块（含 JavaFX UI 扩展，不含 MCP） */
fun loadUiModules() {
    startKoin {
        modules(infraModule, serviceModule, dbModule, uiDBModule, uiModule)
    }
}

    

