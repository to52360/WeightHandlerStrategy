package lin.tree_config.db

import lin.condition_tree.db.ConditionTreeConfigService
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.rule.condition.collectConditionRefs
import lin.rule.parse.withConditionPrefix
import lin.rule.registry.RuleRegistry
import lin.rule.tree.EvaluatorLeafMeta
import lin.rule.tree.EvaluatorLeafSourceType
import lin.utils.runCatchingLog

class EvaluatorLeafSourceCatalog(
    private val ruleRegistry: RuleRegistry,
    private val conditionRegistry: ConditionRegistry,
    private val conditionTreeConfigService: ConditionTreeConfigService
) {
    fun loadAll(): List<EvaluatorLeafMeta> {
        return loadRuleMetas() + loadConditionMetas() + loadConditionTreeMetas()
    }

    private fun loadRuleMetas(): List<EvaluatorLeafMeta> {
        return runCatchingLog("加载规则项失败") { ruleRegistry.leafMetas() }
            .getOrDefault(emptyList())
    }

    private fun loadConditionMetas(): List<EvaluatorLeafMeta> {
        return runCatchingLog("加载条件项失败") { conditionRegistry.leafMetas() }
            .getOrDefault(emptyList())
    }

    private fun loadConditionTreeMetas(): List<EvaluatorLeafMeta> {
        return runCatchingLog("加载条件树配置项失败") {
            conditionTreeConfigService.loadAllMeta().map { (id, name) ->
                val config = conditionTreeConfigService.findById(id)
                val fields = config?.root?.collectConditionRefs()?.distinctBy { it.refId }?.flatMap { ref ->
                    when (ref) {
                        is ConditionPayload.ConditionRef -> {
                            val registration = conditionRegistry.find(ref.conditionId)
                            val displayName = registration?.metadata?.name ?: ref.conditionId
                            val spec = registration?.field?.toFieldSpec()
                                ?.withConditionPrefix(ref.refId, displayName)
                            if (spec != null) listOf(spec) else emptyList()
                        }

                        is ConditionPayload.OrthogonalRef -> {
                            val assembler = conditionRegistry.conditionAssembler
                            val operator = assembler?.findOperator(ref.operatorId)
                            val displayName = operator?.id ?: ref.operatorId
                            operator?.paramSpecs?.map { spec ->
                                spec.withConditionPrefix(ref.refId, displayName)
                            } ?: emptyList()
                        }
                    }
                }
                    ?: emptyList()
                EvaluatorLeafMeta(
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
