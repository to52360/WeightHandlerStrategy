package lin.moduls

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import lin.config.AppConfig
import lin.rule.condition.ConditionRegistry
import lin.rule.registry.RuleRegistry
import lin.rule.score.ScoreOperatorRegistry
import lin.ui.UiExtension
import lin.ui.service.TreeConfigService
import lin.ui.service.createTreeConfigMapper
import lin.ui.tree_config.db.EvaluatorLeafConfigRepository
import lin.ui.tree_config.db.EvaluatorLeafSourceCatalog
import lin.ui.tree_config.db.TreeConfigRepository
import lin.ui.tree_config.ui.EvaluatorTreeExtension
import lin.ui.tree_config.ui.action.*
import lin.utils.serviceLoader.loadSpiList
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files


val uiModule = module {

    single { lin.ui.SelectOptionRegistry() }
    single { RuleRegistry(loadSpiList()) }
    single {
        val dataSources = loadSpiList<lin.serviceLoader.provider.DataSourceProvider>()
            .flatMap { it.get() }
            .associateBy { it.id }

        val transforms = loadSpiList<lin.serviceLoader.provider.TransformProvider>()
            .flatMap { it.get() }
            .associateBy { it.id }

        val operators = loadSpiList<lin.serviceLoader.provider.OperatorProvider>()
            .flatMap { it.get() }
            .associateBy { it.id }

        lin.rule.condition.PipelineAssembler(
            dataSources = dataSources,
            transforms = transforms,
            operators = operators,
            objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().apply {
                configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        )
    }
    single { ConditionRegistry(loadSpiList(), get()) }
    single { ScoreOperatorRegistry(loadSpiList()) }

    // UI 扩展注册
    single { _root_ide_package_.lin.ui.card_group.ui.CardGroupExtension() } bind UiExtension::class
    single { EvaluatorTreeExtension() } bind UiExtension::class
    single { _root_ide_package_.lin.ui.condition_tree.ui.ConditionTreeExtension() } bind UiExtension::class
    single { _root_ide_package_.lin.ui.card_purpose.ui.CardPurposeExtension() } bind UiExtension::class
    single { _root_ide_package_.lin.ui.combo_plan.ui.ComboPlanExtension() } bind UiExtension::class

    // 评估树工作台动作注册
    single { CreateNewTreeAction() } bind TreeWorkbenchAction::class
    single { CreateFromTemplateAction() } bind TreeWorkbenchAction::class
    single { EditTreePropertiesAction() } bind TreeWorkbenchAction::class
    single { SaveTreeAction() } bind TreeWorkbenchAction::class
    single { SaveAsTemplateAction() } bind TreeWorkbenchAction::class
    single { DeleteTreeAction() } bind TreeWorkbenchAction::class

    // 条件树工作台动作注册
    single { _root_ide_package_.lin.ui.condition_tree.ui.action.CreateConditionTreeAction() } bind lin.ui.condition_tree.ui.action.ConditionTreeWorkbenchAction::class
    single { _root_ide_package_.lin.ui.condition_tree.ui.action.SaveConditionTreeAction() } bind lin.ui.condition_tree.ui.action.ConditionTreeWorkbenchAction::class
    single { _root_ide_package_.lin.ui.condition_tree.ui.action.DeleteConditionTreeAction() } bind lin.ui.condition_tree.ui.action.ConditionTreeWorkbenchAction::class

    // 用途标签目录与显示
    single<lin.ui.card_purpose.PurposeTagProvider> { _root_ide_package_.lin.ui.card_purpose.DefaultPurposeTagProvider() }
    single { _root_ide_package_.lin.ui.card_purpose.PurposeTagTreeBindingPolicy(get()) }

    // 全局卡组选择状态
    single { _root_ide_package_.lin.ui.card_group.ui.ActiveManagerHolder() }

}

/**
 * 数据库基础模块，直接创建 HikariCP 连接池与 [JdbcTemplate]。
 * configUi 交互频繁，与引擎侧的一次性加载模式不同，需要连接复用以降低开销。
 */
val dbModule = module {
    single<JdbcTemplate> {
        val dbPath = AppConfig.databasePath
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
            poolName = "ConfigUiPool"
        }
        JdbcTemplate(HikariDataSource(config))
    }

}
val uiDBModule = module {
    single { TreeConfigRepository(get()) }
    single { EvaluatorLeafConfigRepository(get()) }
    single { TreeConfigService(get(), get(), createTreeConfigMapper()) }
    single { _root_ide_package_.lin.ui.condition_tree.db.ConditionTreeConfigRepository(get()) }
    single {
        _root_ide_package_.lin.ui.condition_tree.db.ConditionTreeConfigService(
            get(),
            _root_ide_package_.lin.ui.condition_tree.db.createConditionTreeConfigMapper()
        )
    }
    single { EvaluatorLeafSourceCatalog(get(), get(), get()) }
    single { _root_ide_package_.lin.ui.card_group.db.CardGroupRepository(get()) }
    single { _root_ide_package_.lin.ui.card_group.db.CardGroupService(get()) }
    single { _root_ide_package_.lin.ui.card_purpose.db.CardPurposeRepository(get()) }
    single { _root_ide_package_.lin.ui.combo_plan.db.ComboPlanDefinitionRepository(get()) }
    single { _root_ide_package_.lin.ui.db.TemplateGroupRepository(get()) }
    single { _root_ide_package_.lin.ui.db.OrthogonalTemplateRepository(get()) }
}


class ModelsDefine {
    fun loadModules() {
        startKoin {
            modules(uiModule, dbModule, uiDBModule)
        }
    }
}
