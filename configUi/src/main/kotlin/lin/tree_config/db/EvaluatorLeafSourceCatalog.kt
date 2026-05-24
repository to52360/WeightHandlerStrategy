package lin.tree_config.db

import lin.condition_tree.db.ConditionTreeConfigService
import lin.rule.condition.ConditionRegistry
import lin.rule.condition.collectConditionRefs
import lin.rule.parse.withConditionPrefix
import lin.rule.registry.RuleRegistry
import lin.rule.tree.EvaluatorLeafSourceType
import lin.rule.tree.EvaluatorLeafUiItem

class EvaluatorLeafSourceCatalog(
    private val ruleRegistry: RuleRegistry,
    private val conditionRegistry: ConditionRegistry,
    private val conditionTreeConfigService: ConditionTreeConfigService
) {
    fun loadAll(): List<EvaluatorLeafUiItem> {
        return loadRuleItems() + loadConditionItems() + loadConditionTreeItems()
    }

    private fun loadRuleItems(): List<EvaluatorLeafUiItem> {
        return runCatching { ruleRegistry.leafUiItems() }.getOrDefault(emptyList())
    }

    private fun loadConditionItems(): List<EvaluatorLeafUiItem> {
        return runCatching { conditionRegistry.leafUiItems() }.getOrDefault(emptyList())
    }

    private fun loadConditionTreeItems(): List<EvaluatorLeafUiItem> {
        return runCatching {
            conditionTreeConfigService.loadAllMeta().map { (id, name) ->
                val config = conditionTreeConfigService.findById(id)
                val fields = if (config != null) {
                    config.root.collectConditionRefs()
                        .distinctBy { it.refId }
                        .mapNotNull { ref ->
                            val registration = conditionRegistry.find(ref.conditionId)
                            val displayName = registration?.metadata?.name ?: ref.conditionId
                            registration?.field?.toRuleFieldSpec()
                                ?.withConditionPrefix(ref.refId, displayName)
                        }
                } else {
                    emptyList()
                }
                EvaluatorLeafUiItem(
                    sourceType = EvaluatorLeafSourceType.CONDITION_TREE,
                    sourceId = id,
                    name = name,
                    desc = null,
                    fields = fields
                )
            }
        }.getOrDefault(emptyList())
    }
}
