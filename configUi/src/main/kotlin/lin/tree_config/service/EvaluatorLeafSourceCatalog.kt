package lin.tree_config.service

import lin.condition_tree.service.ConditionTreeConfigService
import lin.rule.condition.ConditionRegistry
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
                EvaluatorLeafUiItem(
                    sourceType = EvaluatorLeafSourceType.CONDITION_TREE,
                    sourceId = id,
                    name = name,
                    desc = null,
                    fields = emptyList()
                )
            }
        }.getOrDefault(emptyList())
    }
}
