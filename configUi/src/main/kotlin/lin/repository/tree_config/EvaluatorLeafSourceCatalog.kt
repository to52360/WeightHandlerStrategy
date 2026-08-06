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
    /**
     * 加载全部可用叶子能力。
     * @param managerId 非空时条件树按"当前卡组私有 + 全局共享"过滤；为空则条件树全量返回。
     *                  规则/条件等静态能力不受卡组过滤影响。
     */
    fun loadAll(managerId: String? = null): List<EvaluatorLeafMeta> {
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
        return virtualMetas + loadRuleMetas() + loadConditionMetas() + loadConditionTreeMetas(managerId)
    }

    private fun loadRuleMetas(): List<EvaluatorLeafMeta> {
        return runCatchingLog("加载规则项失败") { ruleRegistry.leafMetas() }
            .getOrDefault(emptyList())
    }

    private fun loadConditionMetas(): List<EvaluatorLeafMeta> {
        return runCatchingLog("加载条件项失败") { conditionRegistry.leafMetas() }
            .getOrDefault(emptyList())
    }

    private fun loadConditionTreeMetas(managerId: String? = null): List<EvaluatorLeafMeta> {
        return runCatchingLog("加载条件树配置项失败") {
            // 条件树双维度过滤：
            // 1. 按卡组：managerId 非空时只返回当前卡组私有 + 全局共享树（避免其他卡组树噪音）
            // 2. 过滤一次性树（inlineCreated，由消费方内联自动创建）：背景知识只展示可复用的模板条件树，
            //    避免评估树编排时被 aura-boost/conditionalStage 场景的一次性树噪音干扰。
            conditionTreeConfigService.loadAllMeta(managerId)
                .filterNot { it.inlineCreated }
                .map { meta ->
                    val id = meta.id
                    val name = meta.name
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
