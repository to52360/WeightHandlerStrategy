package lin.domain.use.plan

import lin.bean.ComboCard
import lin.bean.usePlan.*


data class ComboUseConstraints(
    val selectConstraints: List<SelectConstraint>,
    val useConstraints: List<UseConstraint>
)

object ComboUseConstraintBuilder {
    /**
     * 根据已选中的牌和 combo 定义，生成编排相关约束。
     *
     * 这里不计算分数，也不决定“该不该选这些牌”：
     * - 选牌和 combo 加权继续留在 FindBestCombination / FindComboStrategy。
     * - 本对象只把 combo 关系翻译成互斥约束和使用顺序约束。
     */
    fun build(cards: List<ComboCard>, definitions: List<ComboPlanDefinition>): ComboUseConstraints {
        val selectConstraints = mutableListOf<SelectConstraint>()
        val useConstraints = mutableListOf<UseConstraint>()

        for (definition in definitions) {
            val coreCards = cards.filter { it.groupIds().intersects(definition.coreGroupIds) }
            val depCards = cards.filter { it.groupIds().intersects(definition.depGroupIds) }
            if (coreCards.isEmpty() || depCards.isEmpty()) continue

            useConstraints += definition.toUseConstraints(coreCards, depCards)
        }

        return ComboUseConstraints(selectConstraints, useConstraints)
    }

    /**
     * 把 combo 的关系语义转成使用顺序约束。
     * SCORE_ONLY 不影响顺序，其他时序根据 mustAdjacent 决定是 MustUseBefore 还是 MustUseTogether。
     */
    private fun ComboPlanDefinition.toUseConstraints(
        coreCards: List<ComboCard>,
        depCards: List<ComboCard>
    ): List<UseConstraint> {
        return when (relation) {
            ComboRelation.SCORE_ONLY -> emptyList()
            ComboRelation.CORE_BEFORE_DEP -> {
                if (mustAdjacent) {
                    // 强相邻下一对一配对，防冲突
                    coreCards.zip(depCards).map { (core, dep) ->
                        MustUseTogether(core, dep, "combo:$id core together dep") as UseConstraint
                    }
                } else {
                    // 非相邻下一对多/多对多拓扑先后关系是安全的
                    coreCards.flatMap { core ->
                        depCards.map { dep ->
                            MustUseBefore(core, dep, "combo:$id core before dep") as UseConstraint
                        }
                    }
                }
            }

            ComboRelation.DEP_BEFORE_CORE -> {
                if (mustAdjacent) {
                    // 强相邻下一对一配对，防冲突
                    depCards.zip(coreCards).map { (dep, core) ->
                        MustUseTogether(dep, core, "combo:$id dep together core") as UseConstraint
                    }
                } else {
                    // 非相邻下一对多/多对多拓扑先后关系是安全的
                    depCards.flatMap { dep ->
                        coreCards.map { core ->
                            MustUseBefore(dep, core, "combo:$id dep before core") as UseConstraint
                        }
                    }
                }
            }
        }
    }

    /**
     * 判断卡牌分组是否命中定义里的任意分组。
     */
    private fun Set<String>.intersects(other: Set<String>): Boolean = any { it in other }
}
