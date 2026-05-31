package lin.domain.use.plan

import lin.bean.ComboCard


class UsePlanBuilder {
    /**
     * 将候选牌转换为 UsePlan。
     *
     * 本阶段只负责收口数据：
     * - 每张牌的 UseIntent 由 UseIntentDeriver 从 CardUseConfig 推导。
     * - combo 使用约束来自 CardCombinedConfig.comboUseBindings。
     * - 选牌和分数仍然由 FindBestCombination / FindComboStrategy 负责。
     * - 不做真实执行，也不接旧 useGroupId。
     */
    fun build(cards: List<ComboCard>): UsePlan {
        val intents = cards.associateWith { card ->
            card.useIntent()
        }
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
}
