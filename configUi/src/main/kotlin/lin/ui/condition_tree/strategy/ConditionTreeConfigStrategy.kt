package lin.ui.condition_tree.strategy

import lin.repository.condition_tree.ConditionTreeConfigService
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionTreeConfig
import lin.rule.tree.LogicNode
import lin.ui.components.TreeConfigStrategy

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
        val managerId = extras["managerId"] as? String
        return service.saveConfig(name, config, existingId, managerId = managerId)
    }

    override fun loadAll(): List<TreeConfigStrategy.LoadedConfig<ConditionPayload>> {
        return service.loadAll().map { (entity, config) ->
            TreeConfigStrategy.LoadedConfig(
                id = entity.id,
                name = entity.name,
                root = config?.root,
                extras = buildMap {
                    entity.managerId?.let { put("managerId", it) }
                    put("inlineCreated", entity.inlineCreated)
                }
            )
        }
    }

    override fun delete(id: String) {
        service.delete(id)
    }
}
