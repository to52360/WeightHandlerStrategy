package lin.repository.card_purpose

import lin.bean.usePlan.CandidatePolicy
import lin.bean.usePlan.CardPurpose
import lin.bean.usePlan.PurposeTagId

data class CardPurposeEntity(
    val cardId: String,
    val name: String?,
    val purposeTags: String, // 逗号分隔的 tagId
    val replanAfterUse: Boolean = false,
    val candidatePolicy: CandidatePolicy? = null, // 三态：null=跟随用途标签默认，显式值=覆盖
    val createdDate: String? = null
) {
    fun toDomain(): CardPurpose {
        return CardPurpose(
            purposeTags = parsePurposeTags(purposeTags),
            replanAfterUse = replanAfterUse,
            candidatePolicy = candidatePolicy
        )
    }

    private fun parsePurposeTags(value: String): Set<PurposeTagId> {
        if (value.isEmpty()) return emptySet()
        return value.split(",")
            .map { PurposeTagId(it.trim()) }
            .toSet()
    }
}
