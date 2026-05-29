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
    fun toDomain(): ComboPlanDefinition {
        return ComboPlanDefinition(
            id = id,
            coreGroupIds = if (coreGroupIds.isEmpty()) emptySet() else coreGroupIds.split(",").map { it.trim() }
                .toSet(),
            depGroupIds = if (depGroupIds.isEmpty()) emptySet() else depGroupIds.split(",").map { it.trim() }.toSet(),
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
}
