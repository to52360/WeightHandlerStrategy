package lin.tree_config.service

import lin.rule.tree.EvaluatorTreeConfig
import lin.rule.tree.TreeConfigProvider
import lin.tree_config.repository.TreeConfigRepository
import lin.utils.database.SqliteJdbcProvider
import org.koin.core.context.GlobalContext

/**
 * 供 ServiceLoader 使用的无参适配器，避免策略层直接依赖 configUi 的 Koin 装配。
 */
class SpiTreeConfigProvider : TreeConfigProvider {
    private val delegate by lazy {
        SqliteTreeConfigProvider(
            repository = TreeConfigRepository(resolveJdbcProvider()),
            mapper = createTreeConfigMapper()
        )
    }

    override fun findById(id: String): EvaluatorTreeConfig? = delegate.findById(id)

    override fun findAll(): List<EvaluatorTreeConfig> = delegate.findAll()

    private fun resolveJdbcProvider(): SqliteJdbcProvider {
        return runCatching {
            GlobalContext.get().get<SqliteJdbcProvider>()
        }.getOrElse {
            SqliteJdbcProvider()
        }
    }

}
