package lin.moduls

import lin.card_group.repository.CardGroupRepository
import lin.card_group.service.CardGroupService
import lin.card_group.ui.CardGroupExtension
import lin.config.AppConfig
import lin.rule.registry.RuleRegistry
import lin.serviceLoader.provider.RuleRegistrationProvider
import lin.tree_config.service.TreeConfigService
import lin.tree_config.ui.EvaluatorTreeExtension
import lin.tree_config.ui.action.CreateNewTreeAction
import lin.tree_config.ui.action.DeleteTreeAction
import lin.tree_config.ui.action.SaveTreeAction
import lin.tree_config.ui.action.TreeWorkbenchAction
import lin.ui.UiExtension
import lin.utils.database.SqliteJdbcProvider
import lin.utils.serviceLoader.ServiceLoaderUtils
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import java.nio.file.Files


val uiModule = module {


    single {
        val providers =
            ServiceLoaderUtils.loadServices(RuleRegistrationProvider::class.java)
        RuleRegistry(providers)
    }

    // UI 扩展注册
    single { CardGroupExtension() } bind UiExtension::class
    single { EvaluatorTreeExtension() } bind UiExtension::class

    // 评估树工作台动作注册
    single { CreateNewTreeAction() } bind TreeWorkbenchAction::class
    single { SaveTreeAction() } bind TreeWorkbenchAction::class
    single { DeleteTreeAction() } bind TreeWorkbenchAction::class

}

/**
 * 数据库基础模块，提供 [SqliteJdbcProvider] 及其他数据库共享依赖
 */
val dbModule = module {
    single {
        val dbPath = AppConfig.databasePath
        if (!Files.exists(dbPath)) {
            Files.createFile(dbPath)
        }
        SqliteJdbcProvider(dbPath)
    }

    single { get<SqliteJdbcProvider>().jdbcTemplate }

}
val uiDBModule = module {
    single { TreeConfigService(get(), get()) }
    single { CardGroupRepository(get()) }
    single { CardGroupService(get()) }
}


class ModelsDefine {
    fun loadModules() {
        startKoin {
            modules(uiModule, dbModule, uiDBModule)
        }
    }
}
