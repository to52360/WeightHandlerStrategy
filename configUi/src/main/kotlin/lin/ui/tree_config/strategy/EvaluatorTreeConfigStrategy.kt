package lin.ui.tree_config.strategy

import lin.rule.tree.*
import lin.ui.components.TreeConfigStrategy
import lin.ui.service.TreeConfigService

class EvaluatorTreeConfigStrategy(
    private val service: TreeConfigService
) : TreeConfigStrategy<EvaluatorPayload> {

    override fun save(
        name: String,
        root: LogicNode<EvaluatorPayload>,
        existingId: String?,
        extras: Map<String, Any>
    ): String {
        val bindingType = extras["bindingType"] as? EvaluatorTreeBindingType
            ?: EvaluatorTreeBindingType.GROUP
        val bindingIds = extras["bindingIds"] as? List<String> ?: emptyList()
        val leafConfigs = extras["leafConfigs"] as? Map<String, EvaluatorLeafConfig> ?: emptyMap()
        val config = EvaluatorTreeConfig(
            bindingType = bindingType,
            bindingIds = bindingIds,
            root = root,
            leafConfigs = leafConfigs
        )
        return service.saveConfig(name, config, existingId)
    }

    override fun loadAll(): List<TreeConfigStrategy.LoadedConfig<EvaluatorPayload>> {
        return service.loadAll().map { (entity, config) ->
            TreeConfigStrategy.LoadedConfig(
                id = entity.id,
                name = entity.name,
                root = config?.root,
                extras = buildMap {
                    put("bindingIds", entity.bindingIds)
                    config?.let {
                        put("bindingType", it.bindingType)
                        put("bindingIds", it.bindingIds)
                    }
                    config?.leafConfigs?.let { put("leafConfigs", it) }
                }
            )
        }
    }

    override fun delete(id: String) {
        service.delete(id)
    }
}
