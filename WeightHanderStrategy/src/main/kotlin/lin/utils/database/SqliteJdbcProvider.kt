package lin.utils.database

import club.xiaojiawei.hsscriptcardsdk.config.DBConfig.CARD_DB_NAME
import lin.myLog
import lin.weightHandler.condition.context.ConditionException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.*
import javax.sql.DataSource

/**
 * 引擎侧轻量数据源——每次请求创建新连接，适合「启动一次性加载」的场景。
 * configUi 侧因需要频繁读写，应自行配置 HikariCP 连接池。
 *
 * ⚠️ **必须把卡表库 ATTACH 成 `hs`**：configUi 的落库 Provider（经 `strategyProviderModule` 在**引擎进程**里跑）
 * 会直接查 `hs.cards` 等（取卡牌名）。configUi 自己的 Hikari 用 `connectionInitSql` 做了这件事，
 * 而引擎侧原先没有 ⇒ 引擎进程里任何 `hs.` 查询都报 `no such table: hs.cards`
 * （2026-09-22 实测：`PurposeStep` 真正跑起来后才暴露）。此处补齐，保持两侧连接环境一致。
 *
 * 参考: club.xiaojiawei.config.DBConfig
 */
class SqliteJdbcProvider(
    private val dbPath: Path = DefDBUrl,
    /**
     * 卡表库路径（ATTACH 成 `hs`）。与 configUi 的 `PathConfig.hsCardsDbPath` 默认同基准（`user.dir`）。
     * 文件不存在时**跳过并 warn**——不静默 ATTACH 出一个空库，否则报的是难懂的 `no such table`。
     */
    private val hsCardsDbPath: Path = DefHsCardsDbUrl,
) {

    private val dataSource: DataSource by lazy {
        validateDbPath(dbPath)
        AttachAwareDataSource(resolveAttachSql()).apply {
            setDriverClassName("org.sqlite.JDBC")
            url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        }
    }

    val jdbcTemplate: JdbcTemplate by lazy {
        JdbcTemplate(dataSource)
    }

    private fun resolveAttachSql(): String? {
        if (!Files.isRegularFile(hsCardsDbPath)) {
            myLog.warn { "卡表库不存在，依赖 hs.* 的查询（卡牌名等）会失败: $hsCardsDbPath" }
            return null
        }
        val escaped = hsCardsDbPath.toAbsolutePath().toString().replace("'", "''")
        return "ATTACH DATABASE '$escaped' AS hs"
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

/**
 * 取连接后执行一次 ATTACH 的 [DriverManagerDataSource]。
 *
 * Spring 的 `AbstractDriverBasedDataSource` 只暴露 `setConnectionProperties` / `setSchema`，**没有 init SQL 通道**
 * （HikariCP 的 `connectionInitSql` 在引擎侧不可用——引擎不依赖 Hikari），故覆写取连接钩子：
 * `getConnection()` 与 `getConnection(user, pass)` 都会汇入 [getConnectionFromDriver]。
 */
private class AttachAwareDataSource(private val attachSql: String?) : DriverManagerDataSource() {

    override fun getConnectionFromDriver(props: Properties): Connection =
        super.getConnectionFromDriver(props).also { connection ->
            if (attachSql != null) {
                connection.createStatement().use { it.execute(attachSql) }
            }
        }
}

val rootPath: String = System.getProperty("user.dir")
const val DBUrl = "/plugin/WeightHandlerStrategy/weightHandlerStrategy.db"
const val TestDBUrl = "./weightHandlerStrategy.db"
val DefDBUrl: Path = Path.of(rootPath, DBUrl)

/** 卡表库默认位置：<user.dir>/hs_cards.db（与 configUi `PathConfig` 默认同基准）。 */
val DefHsCardsDbUrl: Path = Path.of(rootPath, CARD_DB_NAME)
