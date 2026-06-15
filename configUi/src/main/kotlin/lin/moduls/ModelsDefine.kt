package lin.moduls

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import lin.card_group.db.CardGroupRepository
import lin.card_group.db.CardGroupService
import lin.card_group.ui.ActiveManagerHolder
import lin.card_group.ui.CardGroupExtension
import lin.card_purpose.DefaultPurposeTagProvider
import lin.card_purpose.PurposeTagProvider
import lin.card_purpose.PurposeTagTreeBindingPolicy
import lin.card_purpose.db.CardPurposeRepository
import lin.card_purpose.ui.CardPurposeExtension
import lin.condition_tree.db.ConditionTreeConfigRepository
import lin.condition_tree.db.ConditionTreeConfigService
import lin.condition_tree.db.createConditionTreeConfigMapper
import lin.condition_tree.ui.ConditionTreeExtension
import lin.condition_tree.ui.action.ConditionTreeWorkbenchAction
import lin.condition_tree.ui.action.CreateConditionTreeAction
import lin.condition_tree.ui.action.DeleteConditionTreeAction
import lin.condition_tree.ui.action.SaveConditionTreeAction
import lin.config.AppConfig
import lin.rule.condition.ConditionRegistry
import lin.rule.registry.RuleRegistry
import lin.rule.score.ScoreOperatorRegistry
import lin.serviceLoader.provider.ConditionRegistrationProvider
import lin.serviceLoader.provider.RuleRegistrationProvider
import lin.serviceLoader.provider.ScoreOperatorProvider
import lin.tree_config.db.EvaluatorLeafSourceCatalog
import lin.tree_config.db.TreeConfigRepository
import lin.tree_config.ui.EvaluatorTreeExtension
import lin.tree_config.ui.action.CreateNewTreeAction
import lin.tree_config.ui.action.DeleteTreeAction
import lin.tree_config.ui.action.SaveTreeAction
import lin.tree_config.ui.action.TreeWorkbenchAction
import lin.ui.UiExtension
import lin.ui.service.TreeConfigService
import lin.ui.service.createTreeConfigMapper
import lin.utils.serviceLoader.ServiceLoaderUtils
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files


val uiModule = module {


    single {
        val providers =
            ServiceLoaderUtils.loadServices(RuleRegistrationProvider::class.java)
        RuleRegistry(providers)
    }
    single {
        val providers =
            ServiceLoaderUtils.loadServices(ConditionRegistrationProvider::class.java)
        ConditionRegistry(providers)
    }
    single {
        val providers =
            ServiceLoaderUtils.loadServices(ScoreOperatorProvider::class.java)
        ScoreOperatorRegistry(providers)
    }

    // UI 扩展注册
    single { CardGroupExtension() } bind UiExtension::class
    single { EvaluatorTreeExtension() } bind UiExtension::class
    single { ConditionTreeExtension() } bind UiExtension::class
    single { CardPurposeExtension() } bind UiExtension::class
    single { lin.combo_plan.ui.ComboPlanExtension() } bind UiExtension::class

    // 评估树工作台动作注册
    single { CreateNewTreeAction() } bind TreeWorkbenchAction::class
    single { lin.tree_config.ui.action.CreateFromTemplateAction() } bind TreeWorkbenchAction::class
    single { lin.tree_config.ui.action.EditTreePropertiesAction() } bind TreeWorkbenchAction::class
    single { SaveTreeAction() } bind TreeWorkbenchAction::class
    single { DeleteTreeAction() } bind TreeWorkbenchAction::class

    // 条件树工作台动作注册
    single { CreateConditionTreeAction() } bind ConditionTreeWorkbenchAction::class
    single { SaveConditionTreeAction() } bind ConditionTreeWorkbenchAction::class
    single { DeleteConditionTreeAction() } bind ConditionTreeWorkbenchAction::class

    // 用途标签目录与显示
    single<PurposeTagProvider> { DefaultPurposeTagProvider() }
    single { PurposeTagTreeBindingPolicy(get()) }

    // 全局卡组选择状态
    single { ActiveManagerHolder() }

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
    single { TreeConfigService(get(), createTreeConfigMapper()) }
    single { ConditionTreeConfigRepository(get()) }
    single { ConditionTreeConfigService(get(), createConditionTreeConfigMapper()) }
    single { EvaluatorLeafSourceCatalog(get(), get(), get()) }
    single { CardGroupRepository(get()) }
    single { CardGroupService(get()) }
    single { CardPurposeRepository(get()) }
    single { lin.combo_plan.db.ComboPlanDefinitionRepository(get()) }
}


class ModelsDefine {
    fun loadModules() {
        startKoin {
            modules(uiModule, dbModule, uiDBModule)
        }
    }
}
