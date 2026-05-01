package lin.moduls

import lin.rule.registry.RuleRegistry
import lin.utils.database.SqliteJdbcProvider
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path

val TestDBUrl = "weightHandlerStrategy.db"

val configUiModule = module {
    single {
        val dbPath = Path.of(System.getProperty("user.dir"), TestDBUrl)
        if (!Files.exists(dbPath)) {
            Files.createFile(dbPath)
        }
        SqliteJdbcProvider(dbPath)
    }

    single { RuleRegistry() }

}

class ModelsDefine {
    fun loadModules() {
        startKoin {
            modules(configUiModule)
        }
    }
}