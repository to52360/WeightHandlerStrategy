package lin.utils.database

import lin.weightHandler.condition.context.ConditionException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * 引擎侧轻量数据源——每次请求创建新连接，适合「启动一次性加载」的场景。
 * configUi 侧因需要频繁读写，应自行配置 HikariCP 连接池。
 *
 * 参考:
 * club.xiaojiawei.config.DBConfig
 */
class SqliteJdbcProvider(private val dbPath: Path = DefDBUrl) {

    private val dataSource: DataSource by lazy {
        validateDbPath(dbPath)
        DriverManagerDataSource().apply {
            setDriverClassName("org.sqlite.JDBC")
            url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        }
    }

    val jdbcTemplate: JdbcTemplate by lazy {
        JdbcTemplate(dataSource)
    }

    private fun validateDbPath(path: Path) {
        val parentDir = path.parent
        if (parentDir != null && !Files.exists(parentDir)) {
            throw ConditionException("数据库路径不存在: $parentDir")
        }
        if (Files.exists(path) && !Files.isRegularFile(path)) {
            throw ConditionException("数据库路径不是一个文件: $path")
        }
        if (!Files.exists(path)) {
            throw ConditionException("数据库文件不存在")
        }
    }
}

val rootPath: String = System.getProperty("user.dir")
const val DBUrl = "/plugin/WeightHandlerStrategy/weightHandlerStrategy.db"
const val TestDBUrl = "./weightHandlerStrategy.db"
val DefDBUrl: Path = Path.of(rootPath, DBUrl)
