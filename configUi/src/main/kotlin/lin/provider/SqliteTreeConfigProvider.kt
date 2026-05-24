package lin.provider

import com.fasterxml.jackson.databind.ObjectMapper
import lin.rule.tree.EvaluatorTreeConfig
import lin.rule.tree.TreeConfigProvider
import lin.tree_config.db.TreeConfigRepository

/**
 * [TreeConfigProvider] 的 SQLite 实现，供策略层通过 SPI 加载评估树配置。
 */
class SqliteTreeConfigProvider(
    private val repository: TreeConfigRepository,
    private val mapper: ObjectMapper
) : TreeConfigProvider {
    override fun findById(id: String): EvaluatorTreeConfig? {
        val entity = repository.findById(id) ?: return null
        return runCatching {
            mapper.readValue(entity.configData, EvaluatorTreeConfig::class.java)
        }.getOrNull()
    }

    override fun findAll(): List<EvaluatorTreeConfig> {
        return repository.findAll().mapNotNull { entity ->
            runCatching {
                mapper.readValue(entity.configData, EvaluatorTreeConfig::class.java)
            }.getOrNull()
        }
    }
}
