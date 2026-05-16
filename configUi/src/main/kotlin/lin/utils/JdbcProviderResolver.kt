package lin.utils

import lin.config.AppConfig
import lin.utils.database.SqliteJdbcProvider
import org.koin.core.context.GlobalContext

/**
 * 从 Koin 容器获取 SqliteJdbcProvider；若容器尚未启动则回退到 AppConfig 配置的路径。
 */
fun resolveJdbcProvider(): SqliteJdbcProvider {
    return runCatching {
        GlobalContext.get().get<SqliteJdbcProvider>()
    }.getOrElse {
        SqliteJdbcProvider(AppConfig.databasePath)
    }
}
