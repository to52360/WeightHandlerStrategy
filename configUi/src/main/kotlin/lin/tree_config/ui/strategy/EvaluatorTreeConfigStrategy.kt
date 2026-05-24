package lin.tree_config.ui.strategy

import lin.rule.tree.EvaluatorPayload
import lin.rule.tree.EvaluatorTreeConfig
import lin.rule.tree.LogicNode
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
        val bindGroupIds = extras["bindGroupIds"] as? List<String> ?: emptyList()
        val leafConfigs = extras["leafConfigs"] as? Map<String, lin.rule.tree.EvaluatorLeafConfig> ?: emptyMap()
        val config = EvaluatorTreeConfig(
            bindGroupIds = bindGroupIds,
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
                    put("groupIds", entity.groupIds.split(",").filter { it.isNotBlank() })
                    config?.leafConfigs?.let { put("leafConfigs", it) }
                }
            )
        }
    }

    override fun delete(id: String) {
        service.delete(id)
    }
}
