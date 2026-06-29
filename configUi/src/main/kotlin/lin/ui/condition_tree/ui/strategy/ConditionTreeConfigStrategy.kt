package lin.ui.condition_tree.ui.strategy

import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionTreeConfig
import lin.rule.tree.LogicNode
import lin.ui.components.TreeConfigStrategy
import lin.ui.condition_tree.db.ConditionTreeConfigService

class ConditionTreeConfigStrategy(
    private val service: ConditionTreeConfigService
) : TreeConfigStrategy<ConditionPayload> {

    override fun save(
        name: String,
        root: LogicNode<ConditionPayload>,
        existingId: String?,
        extras: Map<String, Any>
    ): String {
        val config = ConditionTreeConfig(
            id = existingId ?: "",
            name = name,
            root = root
        )
        return service.saveConfig(name, config, existingId)
    }

    override fun loadAll(): List<TreeConfigStrategy.LoadedConfig<ConditionPayload>> {
        return service.loadAll().map { (entity, config) ->
            TreeConfigStrategy.LoadedConfig(
                id = entity.id,
                name = entity.name,
                root = config?.root
            )
        }
    }

    override fun delete(id: String) {
        service.delete(id)
    }
}
