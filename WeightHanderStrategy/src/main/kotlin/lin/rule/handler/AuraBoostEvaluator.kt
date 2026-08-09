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
 * 条件树编译缓存与 dynamic-ordering `UsePlanBuilder.treeLogicCache` 同款。
 * 性能（Q-002 方向 c，2026-08-08）：conditionId 树惯例只用全局源（结果与具体卡无关），
 * 编译时强制 crossCard=true → 同一决策 pass 内多卡共享分段管道缓存（RuleEnv.cache）；
 * targetConditionId 可引 evaluating_card（per-card 结果），保持树内 crossCard 配置不覆盖。
 * 注意：conditionId 树若违反"只用全局源"约定引入 evaluating_card，跨卡缓存结果会错误——
 * 建树约定（skill 文档）必须保证 conditionId 树只用全局源。
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
        // 后缀区分编译版本：conditionId 强制 crossCard=true，targetConditionId 保留树内配置
        val trigger = treeLogicCache.getOrPut("${boost.conditionId}#cc") {
            guardCompiler.compileTree(boost.conditionId, crossCard = true)
        }
        if (!trigger(context, ruleEnv)) return false
        val target = treeLogicCache.getOrPut("${boost.targetConditionId}#tree") {
            guardCompiler.compileTree(boost.targetConditionId)
        }
        return target(context, ruleEnv)
    }
}
