package lin.repository.tree_config

import lin.repository.condition_tree.ConditionTreeConfigService
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.rule.condition.collectConditionRefs
import lin.rule.parse.withConditionPrefix
import lin.rule.registry.RuleRegistry
import lin.rule.tree.CONDITION_BUILT_IN_FIELDS
import lin.rule.tree.EvaluatorLeafKind
import lin.rule.tree.EvaluatorLeafMeta
import lin.utils.runCatchingLog

class EvaluatorLeafSourceCatalog(
    private val ruleRegistry: RuleRegistry,
    private val conditionRegistry: ConditionRegistry,
    private val conditionTreeConfigService: ConditionTreeConfigService
) {
    fun loadAll(): List<EvaluatorLeafMeta> {
        val virtualMetas = listOf(
            EvaluatorLeafMeta(
                kind = EvaluatorLeafKind.Condition.Orthogonal,
                sourceId = "orthogonal_condition",
                name = "正交条件 (配置型)",
                desc = "使用数据源与算子灵活组合的配置型条件",
                builtInFields = CONDITION_BUILT_IN_FIELDS,
                fields = emptyList()
            ),
            EvaluatorLeafMeta(
                kind = EvaluatorLeafKind.Rule.Orthogonal,
                sourceId = "orthogonal_rule",
                name = "正交规则 (配置型)",
                desc = "使用守卫条件与评分效应组合的配置型规则",
                builtInFields = emptyList(),
                fields = emptyList()
            )
        )
        return virtualMetas + loadRuleMetas() + loadConditionMetas() + loadConditionTreeMetas()
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

                        is ConditionPayload.PipelineRef -> {
                            val assembler = conditionRegistry.pipelineAssembler
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
                    kind = EvaluatorLeafKind.Condition.Tree,
                    sourceId = id,
                    name = name,
                    desc = "",
                    builtInFields = CONDITION_BUILT_IN_FIELDS,
                    fields = fields
                )
            }
        }.getOrDefault(emptyList())
    }
}
