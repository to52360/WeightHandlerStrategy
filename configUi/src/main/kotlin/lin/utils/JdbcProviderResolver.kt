package lin.utils

import lin.utils.database.SqliteJdbcProvider
import org.koin.core.context.GlobalContext

/**
 * 从 Koin 容器获取 SqliteJdbcProvider；若容器尚未启动则回退到无参默认实例。
 */
fun resolveJdbcProvider(): SqliteJdbcProvider {
    return runCatching {
        GlobalContext.get().get<SqliteJdbcProvider>()
    }.getOrElse {
        SqliteJdbcProvider()
    }
}
