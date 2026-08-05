package lin.rule.handler

import lin.bean.AuraBoostConfig
import lin.bean.ComboCard
import lin.rule.condition.ConditionLogic
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.serviceLoader.provider.AuraBoostConfigProvider

/**
 * Push 广播评分求值器（aura-boost D-001/D-002）。
 *
 * 对每张候选卡求值其命中的 AuraBoost：
 * - [AuraBoostConfig.conditionId]（触发条件树）未命中 → 短路；
 * - [AuraBoostConfig.targetConditionId]（受益卡过滤）命中 → 加分。
 *
 * 条件树编译缓存与 dynamic-ordering `UsePlanBuilder.treeLogicCache` 同款；
 * conditionId 条件树惯例只用全局源，命中结果靠 pipelineCache 整局兜底。
 */
class AuraBoostEvaluator(
    private val guardCompiler: GuardCompiler,
    providers: List<AuraBoostConfigProvider>
) {
    private val boosts: List<AuraBoostConfig> = providers.flatMap { it.findAll() }
    private val treeLogicCache = mutableMapOf<String, ConditionLogic>()

    /** 当前卡命中的所有 boost 分之和（additive 独立通道，D-004）。 */
    fun activeScore(card: ComboCard, ruleEnv: RuleEnv): Double {
        if (boosts.isEmpty()) return 0.0
        val context = RuleContext(card)
        var total = 0.0
        for (boost in boosts) {
            if (match(context, boost, ruleEnv)) total += boost.score
        }
        return total
    }

    private fun match(context: RuleContext, boost: AuraBoostConfig, ruleEnv: RuleEnv): Boolean {
        val trigger = treeLogicCache.getOrPut(boost.conditionId) { guardCompiler.compileTree(boost.conditionId) }
        if (!trigger(context, ruleEnv)) return false
        val target =
            treeLogicCache.getOrPut(boost.targetConditionId) { guardCompiler.compileTree(boost.targetConditionId) }
        return target(context, ruleEnv)
    }
}
