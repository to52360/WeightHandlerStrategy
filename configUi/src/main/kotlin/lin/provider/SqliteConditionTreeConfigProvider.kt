package lin.provider

import lin.condition_tree.db.ConditionTreeConfigService
import lin.rule.condition.ConditionTreeConfig
import lin.serviceLoader.provider.config.ConditionTreeConfigProvider

class SqliteConditionTreeConfigProvider(
    private val service: ConditionTreeConfigService
) : ConditionTreeConfigProvider {
    override fun findById(id: String): ConditionTreeConfig? {
        return service.findById(id)
    }

    override fun findAll(): List<ConditionTreeConfig> {
        return service.loadAll().mapNotNull { (_, config) -> config }
    }
}
