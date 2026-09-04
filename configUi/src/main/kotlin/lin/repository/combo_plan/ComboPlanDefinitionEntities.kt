package lin.repository.combo_plan

import lin.bean.usePlan.ComboPlanDefinition
import lin.bean.usePlan.ComboRelation

data class ComboPlanDefinitionEntity(
    val managerId: String,
    val id: String,
    val coreGroupIds: String, // 逗号分隔
    val depGroupIds: String,  // 逗号分隔
    val score: Double,
    /** 起手换牌专用组合协同加分（引擎 `ComboPlanDefinition.changeScore`）；0.0 = 不加成 */
    val changeScore: Double = 0.0,
    val coreMutex: Boolean,
    val relation: String,
    val mustAdjacent: Boolean
) {
    fun coreGroupIdSet(): Set<String> = coreGroupIds.toGroupIdSet()

    fun depGroupIdSet(): Set<String> = depGroupIds.toGroupIdSet()

    fun toDomain(): ComboPlanDefinition {
        return ComboPlanDefinition(
            id = id,
            coreGroupIds = coreGroupIdSet(),
            depGroupIds = depGroupIdSet(),
            score = score,
            changeScore = changeScore,
            coreMutex = coreMutex,
            relation = try {
                ComboRelation.valueOf(relation)
            } catch (e: Exception) {
                ComboRelation.SCORE_ONLY
            },
            mustAdjacent = mustAdjacent
        )
    }

    private fun String.toGroupIdSet(): Set<String> {
        if (isEmpty()) return emptySet()
        return split(",").mapNotNull { raw ->
            raw.trim().takeIf { it.isNotEmpty() }
        }.toSet()
    }
}
