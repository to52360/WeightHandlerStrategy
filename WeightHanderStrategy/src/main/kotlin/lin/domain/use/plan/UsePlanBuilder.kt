package lin.domain.use.plan

import lin.bean.ComboCard
import lin.bean.facet.groupIds

class UsePlanBuilder(
    private val intentProvider: UseIntentProvider = TodoUseIntentProvider,
    private val definitionProvider: ComboPlanDefinitionProvider = TodoComboPlanDefinitionProvider
) {
    /**
     * 将候选牌转换为 UsePlan。
     *
     * 本阶段只负责收口数据：
     * - 每张牌的 UseIntent 来自 UseIntentProvider。
     * - combo 使用约束来自 ComboUseConstraintBuilder。
     * - 选牌和分数仍然由 FindBestCombination / FindComboStrategy 负责。
     * - 不做真实执行，也不接旧 useGroupId。
     */
    fun build(cards: List<ComboCard>): UsePlan {
        val intents = cards.associateWith { card ->
            intentProvider.intentOf(card.groupIds())
        }
        val constraints = ComboUseConstraintBuilder.build(cards, definitionProvider.findAll())
        return UsePlan(
            cards = cards,
            intents = intents,
            selectConstraints = constraints.selectConstraints,
            useConstraints = constraints.useConstraints
        )
    }
}
