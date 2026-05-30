package lin.combo_plan.db

import lin.bean.usePlan.ComboPlanDefinition
import lin.bean.usePlan.ComboRelation

data class ComboPlanDefinitionEntity(
    val id: String,
    val coreGroupIds: String, // 逗号分隔
    val depGroupIds: String,  // 逗号分隔
    val score: Double,
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
