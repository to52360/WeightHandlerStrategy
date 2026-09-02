package lin.domain.use.plan

import lin.bean.ComboCard
import lin.bean.usePlan.UseIntent
import lin.rule.condition.ConditionLogic
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.handler.GuardCompiler


/**
 * 将候选牌转换为 UsePlan。
 *
 * 本阶段只负责收口数据：
 * - 每张牌的 UseIntent 启动期预推导（UseIntentAssembler → CardCombinedConfig.useIntent），运行期读取。
 * - conditionalStage 覆盖：条件树求值命中 → 覆盖 stage；未命中 → elseStage（null 沿用基础 UseIntent）。
 * - combo 使用约束来自 CardCombinedConfig.comboUseBindings。
 * - 不做真实执行，也不接旧 useGroupId。
 */
class UsePlanBuilder(
    private val guardCompiler: GuardCompiler
) {
    /** 条件树编译缓存：按 conditionId 一次编译，应用运行期复用（参数在树内 config_data，D-007 裸编译）。 */
    private val treeLogicCache = mutableMapOf<String, ConditionLogic>()

    fun build(cards: List<ComboCard>, ruleEnv: RuleEnv): UsePlan {
        val intents = cards.mapNotNull { card ->
            card.useIntent()?.let { base -> card to applyConditionalStage(card, base, ruleEnv) }
        }.toMap()
        val constraints = if (cards.none { it.comboUseBindings().isNotEmpty() }) {
            ComboUseConstraints(emptyList())
        } else {
            ComboUseConstraintBuilder.build(cards)
        }
        return UsePlan(
            cards = cards,
            intents = intents,
            useConstraints = constraints.useConstraints
        )
    }

    /**
     * conditionalStage 覆盖：条件命中 → cs.stage；未命中 → cs.elseStage（null 沿用基础推导 UseIntent）。
     * 条件树惯例只用全局源（不引 evaluating_card，dynamic-ordering driver-case 约定），
     * 编译强制 crossCard=true → 同一决策 pass 内多卡共享分段管道缓存（与 AuraBoost conditionId 对齐）。
     */
    private fun applyConditionalStage(card: ComboCard, base: UseIntent, ruleEnv: RuleEnv): UseIntent {
        // T-013：走 ComboCard 读取入口（含谓词组运行时解析），勿读 combinedConfig 静态预算
        val cs = card.conditionalStage() ?: return base
        val logic =
            treeLogicCache.getOrPut(cs.conditionId) { guardCompiler.compileTree(cs.conditionId, crossCard = true) }
        val matched = logic(RuleContext(card), ruleEnv)
        val stage = if (matched) cs.stage else cs.elseStage
        return if (stage == null) base else base.copy(stage = stage)
    }
}
