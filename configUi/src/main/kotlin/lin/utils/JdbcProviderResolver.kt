package lin.utils

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import lin.config.AppConfig
import org.koin.core.context.GlobalContext
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 从 Koin 容器获取 [JdbcTemplate]（带连接池）；
 * 若容器尚未启动则回退到 AppConfig 配置的路径创建临时数据源（仅供诊断用）。
 */
fun resolveJdbcProvider(): JdbcTemplate {
    return runCatchingLog("Koin容器未启动，使用回退数据源") {
        GlobalContext.get().get<JdbcTemplate>()
    }.getOrElse {
        val dbPath = AppConfig.databasePath
        val config = HikariConfig().apply {
            driverClassName = "org.sqlite.JDBC"
            jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
            maximumPoolSize = 1
            connectionTestQuery = "SELECT 1"
            poolName = "FallbackPool"
        }
        JdbcTemplate(HikariDataSource(config))
    }
}
