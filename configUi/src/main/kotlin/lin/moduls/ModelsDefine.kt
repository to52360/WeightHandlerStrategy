package lin.moduls

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import lin.bean.usePlan.DefaultPurposeTagIntentRuleProvider
import lin.bean.usePlan.PurposeTagIntentRuleProvider
import lin.config.PathConfig
import lin.db.HsCardRepository
import lin.db.OrthogonalTemplateRepository
import lin.db.TemplateGroupRepository
import lin.di.infraModule
import lin.ui.SelectOptionRegistry
import lin.ui.UiExtension
import lin.ui.card_group.db.CardGroupBehaviorRepository
import lin.ui.card_group.db.CardGroupRepository
import lin.ui.card_group.db.CardGroupService
import lin.ui.card_group.ui.ActiveManagerHolder
import lin.ui.card_group.ui.CardGroupExtension
import lin.ui.card_purpose.DefaultPurposeTagProvider
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.card_purpose.PurposeTagTreeBindingPolicy
import lin.ui.card_purpose.db.CardPurposeRepository
import lin.ui.card_purpose.ui.CardPurposeExtension
import lin.ui.combo_plan.db.ComboPlanDefinitionRepository
import lin.ui.combo_plan.ui.ComboPlanExtension
import lin.ui.condition_tree.db.ConditionTreeConfigRepository
import lin.ui.condition_tree.db.ConditionTreeConfigService
import lin.ui.condition_tree.db.createConditionTreeConfigMapper
import lin.ui.condition_tree.ui.ConditionTreeExtension
import lin.ui.condition_tree.ui.action.ConditionTreeWorkbenchAction
import lin.ui.condition_tree.ui.action.CreateConditionTreeAction
import lin.ui.condition_tree.ui.action.DeleteConditionTreeAction
import lin.ui.condition_tree.ui.action.SaveConditionTreeAction
import lin.ui.service.EvaluatorTreeResolver
import lin.ui.service.EvaluatorTreeTemplateService
import lin.ui.service.TreeConfigService
import lin.ui.service.createTreeConfigMapper
import lin.ui.tree_config.db.EvaluatorLeafConfigRepository
import lin.ui.tree_config.db.EvaluatorLeafSourceCatalog
import lin.ui.tree_config.db.EvaluatorTreeTemplateRepository
import lin.ui.tree_config.db.TreeConfigRepository
import lin.ui.tree_config.ui.EvaluatorTreeExtension
import lin.ui.tree_config.ui.action.*
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

    

